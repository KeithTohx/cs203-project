package csd.tripsense.alert;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import csd.tripsense.impact.EvaluationBatchRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Short database transactions; no transaction or SQLite write lock spans an SMTP request. */
@Service
@org.springframework.context.annotation.DependsOn("alertSchemaMigration")
public class AlertEmailStore {
    static final int MAX_ATTEMPTS = 3;
    static final List<EmailStatus> RETRYABLE = List.of(EmailStatus.PENDING, EmailStatus.SENDING);
    private final AlertRepository alerts;
    private final AlertEmailGroupRepository emailGroups;
    private final EvaluationBatchRepository evaluations;
    private final csd.tripsense.impact.EvaluationTargetRepository targets;
    private final long maxGroupWaitSeconds;
    private final long groupRecheckSeconds;
    private final long initialGroupWaitSeconds;

    public AlertEmailStore(AlertRepository alerts, AlertEmailGroupRepository emailGroups,
            EvaluationBatchRepository evaluations, csd.tripsense.impact.EvaluationTargetRepository targets,
            @Value("${alerts.email.max-group-wait-seconds:120}") long maxGroupWaitSeconds,
            @Value("${alerts.email.group-recheck-seconds:30}") long groupRecheckSeconds,
            @Value("${alerts.email.initial-group-wait-seconds:30}") long initialGroupWaitSeconds) {
        this.alerts = alerts; this.emailGroups = emailGroups; this.evaluations = evaluations; this.targets=targets;
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
        return claim(id, now, java.util.Set.of());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Delivery> claim(Long id, Instant now, java.util.Set<String> confirmingGroups) {
        Alert requested = alerts.findById(id).orElse(null);
        if (requested == null || !RETRYABLE.contains(requested.getEmailStatus())
                || requested.getEmailNextAttemptAt().isAfter(now)) return Optional.empty();
        var impact = requested.getImpact();
        var recipient = impact.getItinerary().getUser();
        if (!recipient.isEnabled()) return Optional.empty();
        var group = alerts.findEmailGroup(impact.getItinerary().getId(), impact.getNews().getId());
        Long tripId=impact.getItinerary().getId();
        Long newsId=impact.getNews().getId();
        if (confirmingGroups.contains(AlertEmailGroup.key(tripId, newsId))) {
            deferRetryable(group, now.plusSeconds(groupRecheckSeconds));
            return Optional.empty();
        }
        var deliveryGroup=emailGroups.findById(AlertEmailGroup.key(tripId,newsId));
        // The group retains its terminal state even if a sent impact/alert is subsequently deleted.
        var sent=group.stream().filter(a -> a.getEmailStatus()==EmailStatus.SENT).findFirst();
        boolean previouslyDelivered=deliveryGroup.map(AlertEmailGroup::isDelivered).orElse(false)
                || sent.isPresent() || group.stream().anyMatch(a -> a.getEmailStatus()==EmailStatus.SUPPRESSED);
        if (previouslyDelivered && !deliveryGroup.map(AlertEmailGroup::isPartial).orElse(false)) {
            var state=deliveryGroup.orElseGet(() -> emailGroups.saveAndFlush(new AlertEmailGroup(
                    tripId,newsId,group.getFirst().getEmailDeliveryKey())));
            state.markDelivered(sent.map(Alert::getEmailSentAt).orElse(null));
            for (Alert member:group) if (RETRYABLE.contains(member.getEmailStatus()))
                alerts.suppressEmail(member.getId(),member.getEmailAttempts(),RETRYABLE);
            return Optional.empty();
        }
        // A new member must not bypass an active SMTP lease or another member's retry backoff.
        Instant nextReady = group.stream().filter(a -> RETRYABLE.contains(a.getEmailStatus()))
                .map(Alert::getEmailNextAttemptAt).max(Comparator.naturalOrder()).orElse(now);
        if (nextReady.isAfter(now)) {
            deferRetryable(group, nextReady);
            return Optional.empty();
        }
        // Failure belongs to the news/trip group. Late members inherit it rather than waiting forever.
        if (group.stream().anyMatch(a -> a.getEmailStatus() == EmailStatus.FAILED
                || RETRYABLE.contains(a.getEmailStatus()) && a.getEmailAttempts() >= MAX_ATTEMPTS)) {
            for (Alert member : group) alerts.finishEmail(member.getId(), member.getEmailAttempts(), RETRYABLE,
                    EmailStatus.FAILED, null, now);
            return Optional.empty();
        }
        Instant firstCreatedAt = group.stream().filter(a -> RETRYABLE.contains(a.getEmailStatus()))
                .map(Alert::getCreatedAt).min(Comparator.naturalOrder()).orElse(now);
        Instant deadline = firstCreatedAt.plusSeconds(maxGroupWaitSeconds);
        Instant settleUntil = firstCreatedAt.plusSeconds(initialGroupWaitSeconds);
        if (group.stream().filter(a -> RETRYABLE.contains(a.getEmailStatus())).allMatch(a -> a.getEmailAttempts() == 0) && now.isBefore(settleUntil)) {
            deferRetryable(group, settleUntil);
            return Optional.empty();
        }
        boolean incomplete = evaluations.countRunning(tripId, newsId, now) != 0;
        if (incomplete && now.isBefore(deadline)) {
            Instant recheck = now.plusSeconds(groupRecheckSeconds);
            deferRetryable(group, recheck.isBefore(deadline) ? recheck : deadline);
            return Optional.empty();
        }
        var members = group.stream().filter(a -> RETRYABLE.contains(a.getEmailStatus()))
                .filter(a -> Boolean.TRUE.equals(a.getImpact().getImpacted())
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
        // Anchor the first attempt to the full group, including rejected members. Persist the
        // key before SMTP so a retry never derives its identity from the current visible leader.
        String deliveryKey = deliveryGroup.orElseGet(() -> emailGroups.saveAndFlush(new AlertEmailGroup(tripId, newsId,
                        group.getFirst().getEmailDeliveryKey()))).getDeliveryKey();
        if (deliveryGroup.isPresent() && deliveryGroup.get().isDelivered()) {
            deliveryKey = leader.getEmailDeliveryKey();
            deliveryGroup.get().beginDelivery(deliveryKey);
        }
        boolean partial=incomplete || targets.countUnresolved(tripId,newsId)>0
                || evaluations.countLegacyIncomplete(tripId,newsId)>0;
        return Optional.of(new Delivery(leader.getId(), claims.getFirst().attempt(), deliveryKey,
                recipient.getEmail(), AlertResponse.from(leader),
                members.stream().map(AlertResponse::from).toList(), List.copyOf(claims), partial));
    }

    private void deferRetryable(List<Alert> group, Instant next) {
        for (Alert member : group) {
            if (RETRYABLE.contains(member.getEmailStatus())) {
                // Keep an expired SENDING member out of the due queue while its group waits.
                // Match state and attempt so a newer worker's claim cannot be overwritten.
                alerts.deferEmail(member.getId(), member.getEmailAttempts(), member.getEmailStatus(), next);
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sent(Delivery delivery, Instant now) {
        int completed=0;
        for (ClaimedAlert claim : delivery.claims()) {
            completed+=alerts.finishEmail(claim.id(),claim.attempt(),List.of(EmailStatus.SENDING),EmailStatus.SENT,now,now);
        }
        // An obsolete worker must not mark a newer replay/group delivered.
        boolean removed = delivery.claims().stream().allMatch(c -> !alerts.existsById(c.id()));
        if (completed>0 || removed) {
            var summary=delivery.alert();
            var existing = emailGroups.findById(AlertEmailGroup.key(summary.itineraryId(),summary.newsId()));
            // Deleting the trip also removes its delivery group. There is nothing left to confirm.
            if (removed && existing.isEmpty()) return;
            var group=existing.orElseGet(() -> emailGroups.saveAndFlush(new AlertEmailGroup(
                            summary.itineraryId(),summary.newsId(),delivery.key())));
            if (group.getDeliveryKey().equals(delivery.key())) {
                group.markDelivered(now);
                group.setPartial(delivery.partialAssessment());
            }
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
