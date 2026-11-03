package csd.tripsense.alert;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Short database transactions; no transaction or SQLite write lock spans an SMTP request. */
@Service
public class AlertEmailStore {
    static final int MAX_ATTEMPTS = 3;
    static final List<EmailStatus> RETRYABLE = List.of(EmailStatus.PENDING, EmailStatus.SENDING);
    private final AlertRepository alerts;

    public AlertEmailStore(AlertRepository alerts) { this.alerts = alerts; }

    public record ClaimedAlert(Long id, int attempt) {}
    public record Delivery(Long alertId, int attempt, String key, String recipient, AlertResponse alert,
                           List<AlertResponse> relatedAlerts, List<ClaimedAlert> claims) {
        public Delivery(Long alertId, int attempt, String key, String recipient, AlertResponse alert) {
            this(alertId, attempt, key, recipient, alert,
                    alert == null ? List.of() : List.of(alert), List.of(new ClaimedAlert(alertId, attempt)));
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
        if (alerts.countIncompleteEmailGroups(impact.getItinerary().getId(), impact.getNews().getId(),
                java.time.LocalDateTime.ofInstant(now, java.time.ZoneId.systemDefault())) != 0) return Optional.empty();
        var members = group.stream().filter(a -> Boolean.TRUE.equals(a.getImpact().getImpacted())
                && !Boolean.TRUE.equals(a.getImpact().getRejected())).toList();
        if (members.isEmpty() || members.stream().anyMatch(a -> !RETRYABLE.contains(a.getEmailStatus())
                || a.getEmailNextAttemptAt().isAfter(now))) return Optional.empty();
        if (members.stream().anyMatch(a -> a.getEmailAttempts() >= MAX_ATTEMPTS)) {
            for (Alert member : members) alerts.finishEmail(member.getId(), member.getEmailAttempts(), RETRYABLE,
                    EmailStatus.FAILED, null, now);
            return Optional.empty();
        }
        var claims = new java.util.ArrayList<ClaimedAlert>();
        for (Alert member : members) {
            int attempt = member.getEmailAttempts() + 1;
            if (alerts.claimEmail(member.getId(), attempt - 1, RETRYABLE, EmailStatus.SENDING,
                    now, now.plusSeconds(120)) != 1) {
                // Roll back every claim if another worker acquired any part of this group.
                throw new org.springframework.dao.OptimisticLockingFailureException("Email group already claimed");
            }
            claims.add(new ClaimedAlert(member.getId(), attempt));
        }
        Alert leader = members.getFirst();
        return Optional.of(new Delivery(leader.getId(), claims.getFirst().attempt(), leader.getEmailDeliveryKey(),
                recipient.getEmail(), AlertResponse.from(leader),
                members.stream().map(AlertResponse::from).toList(), List.copyOf(claims)));
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
