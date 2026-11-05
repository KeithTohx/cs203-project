package csd.tripsense.alert;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Short database transactions; no transaction or SQLite write lock spans an SMTP request. */
@Service
public class AlertEmailStore {
    static final int MAX_ATTEMPTS = 3;
    static final List<EmailStatus> RETRYABLE = List.of(EmailStatus.PENDING, EmailStatus.SENDING);
    private final AlertRepository alerts;
    private final long maxGroupWaitSeconds;
    private final long groupRecheckSeconds;
    private final long initialGroupWaitSeconds;

    public AlertEmailStore(AlertRepository alerts,
            @Value("${alerts.email.max-group-wait-seconds:120}") long maxGroupWaitSeconds,
            @Value("${alerts.email.group-recheck-seconds:30}") long groupRecheckSeconds,
            @Value("${alerts.email.initial-group-wait-seconds:30}") long initialGroupWaitSeconds) {
        this.alerts = alerts;
        this.maxGroupWaitSeconds = Math.max(0, maxGroupWaitSeconds);
        this.groupRecheckSeconds = Math.max(1, groupRecheckSeconds);
        this.initialGroupWaitSeconds = Math.min(this.maxGroupWaitSeconds, Math.max(0, initialGroupWaitSeconds));
    }

    public record ClaimedAlert(Long id, int attempt) {}
    public record Delivery(Long alertId, int attempt, String key, String recipient, AlertResponse alert,
                           List<AlertResponse> relatedAlerts, List<ClaimedAlert> claims, boolean partialAssessment) {
        public Delivery(Long alertId, int attempt, String key, String recipient, AlertResponse alert,
                        List<AlertResponse> relatedAlerts, List<ClaimedAlert> claims) {
            this(alertId, attempt, key, recipient, alert, relatedAlerts, claims, false);
        }
        public Delivery(Long alertId, int attempt, String key, String recipient, AlertResponse alert) {
            this(alertId, attempt, key, recipient, alert,
                    alert == null ? List.of() : List.of(alert), List.of(new ClaimedAlert(alertId, attempt)), false);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Delivery> claim(Long id, Instant now) {
        Alert requested = alerts.findById(id).orElse(null);
        if (requested == null || !RETRYABLE.contains(requested.getEmailStatus())
                || requested.getEmailNextAttemptAt().isAfter(now)) return Optional.empty();
        var impact = requested.getImpact();
        var recipient = impact.getItinerary().getUser();
        if (!recipient.isEnabled()) return Optional.empty();
        var group = alerts.findEmailGroup(impact.getItinerary().getId(), impact.getNews().getId());
        // A news/trip pair is notified once, including after a later activity assessment or restart.
        var sent = group.stream().filter(a -> a.getEmailStatus() == EmailStatus.SENT).findFirst();
        if (sent.isPresent()) {
            for (Alert member : group) {
                if (RETRYABLE.contains(member.getEmailStatus())) {
                    alerts.finishEmail(member.getId(), member.getEmailAttempts(), RETRYABLE,
                            EmailStatus.SENT, sent.get().getEmailSentAt(), now);
                }
            }
            return Optional.empty();
        }
        // A new member must not bypass an active SMTP lease or another member's retry backoff.
        Instant nextReady = group.stream().filter(a -> RETRYABLE.contains(a.getEmailStatus()))
                .map(Alert::getEmailNextAttemptAt).max(Comparator.naturalOrder()).orElse(now);
        if (nextReady.isAfter(now)) {
            deferPending(group, nextReady);
            return Optional.empty();
        }
        // Failure belongs to the news/trip group. Late members inherit it rather than waiting forever.
        if (group.stream().anyMatch(a -> a.getEmailStatus() == EmailStatus.FAILED
                || a.getEmailAttempts() >= MAX_ATTEMPTS)) {
            for (Alert member : group) alerts.finishEmail(member.getId(), member.getEmailAttempts(), RETRYABLE,
                    EmailStatus.FAILED, null, now);
            return Optional.empty();
        }
        boolean incomplete = alerts.countIncompleteEmailGroups(impact.getItinerary().getId(), impact.getNews().getId(),
                LocalDateTime.ofInstant(now, ZoneId.systemDefault())) != 0;
        Instant deadline = group.stream().map(Alert::getCreatedAt).min(Comparator.naturalOrder())
                .orElse(now).plusSeconds(maxGroupWaitSeconds);
        Instant settleUntil = deadline.minusSeconds(maxGroupWaitSeconds).plusSeconds(initialGroupWaitSeconds);
        if (group.stream().allMatch(a -> a.getEmailAttempts() == 0) && now.isBefore(settleUntil)) {
            deferPending(group, settleUntil);
            return Optional.empty();
        }
        if (incomplete && now.isBefore(deadline)) {
            Instant recheck = now.plusSeconds(groupRecheckSeconds);
            deferPending(group, recheck.isBefore(deadline) ? recheck : deadline);
            return Optional.empty();
        }
        var members = group.stream().filter(a -> Boolean.TRUE.equals(a.getImpact().getImpacted())
                && !Boolean.TRUE.equals(a.getImpact().getRejected())).toList();
        if (members.isEmpty() || members.stream().anyMatch(a -> !RETRYABLE.contains(a.getEmailStatus())
                || a.getEmailNextAttemptAt().isAfter(now))) return Optional.empty();
        var claims = new ArrayList<ClaimedAlert>();
        for (Alert member : members) {
            int attempt = member.getEmailAttempts() + 1;
            if (alerts.claimEmail(member.getId(), attempt - 1, RETRYABLE, EmailStatus.SENDING,
                    now, now.plusSeconds(120)) != 1) {
                // Roll back every claim if another worker acquired any part of this group.
                throw new OptimisticLockingFailureException("Email group already claimed");
            }
            claims.add(new ClaimedAlert(member.getId(), attempt));
        }
        Alert leader = members.getFirst();
        return Optional.of(new Delivery(leader.getId(), claims.getFirst().attempt(), leader.getEmailDeliveryKey(),
                recipient.getEmail(), AlertResponse.from(leader),
                members.stream().map(AlertResponse::from).toList(), List.copyOf(claims), incomplete));
    }

    private void deferPending(List<Alert> group, Instant next) {
        for (Alert member : group) {
            alerts.deferEmail(member.getId(), member.getEmailAttempts(), EmailStatus.PENDING, next);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sent(Delivery delivery, Instant now) {
        for (ClaimedAlert claim : delivery.claims()) {
            alerts.finishEmail(claim.id(), claim.attempt(), List.of(EmailStatus.SENDING), EmailStatus.SENT, now, now);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failed(Delivery delivery, Instant now) {
        for (ClaimedAlert claim : delivery.claims()) {
            EmailStatus state = claim.attempt() >= MAX_ATTEMPTS ? EmailStatus.FAILED : EmailStatus.PENDING;
            alerts.finishEmail(claim.id(), claim.attempt(), List.of(EmailStatus.SENDING), state,
                    null, now.plusSeconds(60L * claim.attempt()));
        }
    }
}
