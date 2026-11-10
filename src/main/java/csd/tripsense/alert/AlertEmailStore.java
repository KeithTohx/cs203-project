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

    private enum ClaimDecision { DEFER, SUPPRESS, FAIL, PROCEED }

    private record EmailGroup(Long tripId, Long newsId, String recipient,
                              List<Alert> members, Optional<AlertEmailGroup> state) {
        String key() { return AlertEmailGroup.key(tripId, newsId); }
        List<Alert> retryable() {
            return members.stream().filter(a -> RETRYABLE.contains(a.getEmailStatus())).toList();
        }
    }
    private record AssessmentGate(ClaimDecision decision, boolean incomplete) {}

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Delivery> claim(Long id, Instant now, java.util.Set<String> confirmingGroups) {
        Alert requested = alerts.findById(id).orElse(null);
        if (requested == null || !RETRYABLE.contains(requested.getEmailStatus())
                || requested.getEmailNextAttemptAt().isAfter(now)) return Optional.empty();
        var impact = requested.getImpact();
        var recipient = impact.getItinerary().getUser();
        if (!recipient.isEnabled()) return Optional.empty();
        Long tripId = impact.getItinerary().getId();
        Long newsId = impact.getNews().getId();
        var group = new EmailGroup(tripId, newsId, recipient.getEmail(),
                alerts.findEmailGroup(tripId, newsId), emailGroups.findById(AlertEmailGroup.key(tripId, newsId)));

        if (prepareGroup(group, confirmingGroups, now) != ClaimDecision.PROCEED) return Optional.empty();
        AssessmentGate assessment = waitForAssessments(group, now);
        if (assessment.decision() != ClaimDecision.PROCEED) return Optional.empty();
        return claimReadyMembers(group, assessment.incomplete(), now);
    }

    // Order matters: confirmation and prior delivery precede lease/backoff, then attempt limits.
    private ClaimDecision prepareGroup(EmailGroup group, java.util.Set<String> confirmingGroups, Instant now) {
        if (checkConfirmation(group, confirmingGroups, now) != ClaimDecision.PROCEED) return ClaimDecision.DEFER;
        if (checkDeliveryHistory(group) != ClaimDecision.PROCEED) return ClaimDecision.SUPPRESS;
        if (checkBackoff(group, now) != ClaimDecision.PROCEED) return ClaimDecision.DEFER;
        return checkAttemptLimit(group, now);
    }

    private ClaimDecision checkConfirmation(EmailGroup group, java.util.Set<String> confirmingGroups, Instant now) {
        if (!confirmingGroups.contains(group.key())) return ClaimDecision.PROCEED;
        deferRetryable(group.members(), now.plusSeconds(groupRecheckSeconds));
        return ClaimDecision.DEFER;
    }

    private ClaimDecision checkDeliveryHistory(EmailGroup group) {
        var sent = group.members().stream().filter(a -> a.getEmailStatus() == EmailStatus.SENT).findFirst();
        boolean delivered = group.state().map(AlertEmailGroup::isDelivered).orElse(false)
                || sent.isPresent() || group.members().stream().anyMatch(a -> a.getEmailStatus() == EmailStatus.SUPPRESSED);
        if (!delivered || group.state().map(AlertEmailGroup::isPartial).orElse(false)) return ClaimDecision.PROCEED;
        var state = group.state().orElseGet(() -> emailGroups.saveAndFlush(new AlertEmailGroup(
                group.tripId(), group.newsId(), group.members().getFirst().getEmailDeliveryKey())));
        state.markDelivered(sent.map(Alert::getEmailSentAt).orElse(null));
        for (Alert member : group.retryable()) alerts.suppressEmail(member.getId(), member.getEmailAttempts(), RETRYABLE);
        return ClaimDecision.SUPPRESS;
    }

    private ClaimDecision checkBackoff(EmailGroup group, Instant now) {
        // A new member cannot bypass an active SMTP lease or another member's retry backoff.
        Instant nextReady = group.retryable().stream().map(Alert::getEmailNextAttemptAt)
                .max(Comparator.naturalOrder()).orElse(now);
        if (!nextReady.isAfter(now)) return ClaimDecision.PROCEED;
        deferRetryable(group.members(), nextReady);
        return ClaimDecision.DEFER;
    }

    private ClaimDecision checkAttemptLimit(EmailGroup group, Instant now) {
        String deliveryKey = group.state().map(AlertEmailGroup::getDeliveryKey)
                .orElseGet(() -> group.members().getFirst().getEmailDeliveryKey());
        boolean exhausted = group.members().stream().anyMatch(a -> deliveryKey.equals(a.getEmailDeliveryKey())
                && (a.getEmailStatus() == EmailStatus.FAILED
                || RETRYABLE.contains(a.getEmailStatus()) && a.getEmailAttempts() >= MAX_ATTEMPTS));
        if (!exhausted) return ClaimDecision.PROCEED;
        // Failure belongs to this delivery generation. Older generations remain history
        // and must not block a new email after an edited plan resets the group key.
        for (Alert member : group.members()) {
            // An unattempted late member still has its own initial key. It belongs to this
            // delivery generation too, and must inherit the exhausted group's failure.
            if (deliveryKey.equals(member.getEmailDeliveryKey()) || member.getEmailAttempts() == 0) {
                alerts.finishEmail(member.getId(), member.getEmailAttempts(), RETRYABLE,
                        EmailStatus.FAILED, null, now);
            }
        }
        return ClaimDecision.FAIL;
    }

    private AssessmentGate waitForAssessments(EmailGroup group, Instant now) {
        List<Alert> retryable = group.retryable();
        Instant firstCreatedAt = retryable.stream().map(Alert::getCreatedAt).min(Comparator.naturalOrder()).orElse(now);
        Instant settleUntil = firstCreatedAt.plusSeconds(initialGroupWaitSeconds);
        if (retryable.stream().allMatch(a -> a.getEmailAttempts() == 0) && now.isBefore(settleUntil)) {
            deferRetryable(group.members(), settleUntil);
            return new AssessmentGate(ClaimDecision.DEFER, false);
        }
        boolean incomplete = evaluations.countRunning(group.tripId(), group.newsId(), now) != 0;
        Instant deadline = firstCreatedAt.plusSeconds(maxGroupWaitSeconds);
        if (incomplete && now.isBefore(deadline)) {
            Instant recheck = now.plusSeconds(groupRecheckSeconds);
            deferRetryable(group.members(), recheck.isBefore(deadline) ? recheck : deadline);
            return new AssessmentGate(ClaimDecision.DEFER, true);
        }
        return new AssessmentGate(ClaimDecision.PROCEED, incomplete);
    }

    private Optional<Delivery> claimReadyMembers(EmailGroup group, boolean incomplete, Instant now) {
        var members = group.retryable().stream().filter(a -> Boolean.TRUE.equals(a.getImpact().getImpacted())
                && !Boolean.TRUE.equals(a.getImpact().getRejected())).toList();
        if (members.isEmpty()) return Optional.empty();
        var claims = new ArrayList<ClaimedAlert>();
        Alert leader = members.getFirst();
        String deliveryKey = deliveryIdentity(group, leader);
        for (Alert member : members) {
            int attempt = member.getEmailAttempts() + 1;
            if (alerts.claimEmail(member.getId(), attempt - 1, RETRYABLE, EmailStatus.SENDING,
                    now, now.plusSeconds(120), deliveryKey) != 1) {
                // Roll back every claim if another worker acquired any part of this group.
                throw new OptimisticLockingFailureException("Email group already claimed");
            }
            claims.add(new ClaimedAlert(member.getId(), attempt));
        }
        boolean partial = incomplete || targets.countUnresolved(group.tripId(), group.newsId()) > 0
                || evaluations.countLegacyIncomplete(group.tripId(), group.newsId()) > 0;
        return Optional.of(new Delivery(leader.getId(), claims.getFirst().attempt(), deliveryKey,
                group.recipient(), AlertResponse.from(leader),
                members.stream().map(AlertResponse::from).toList(), List.copyOf(claims), partial));
    }

    private String deliveryIdentity(EmailGroup group, Alert leader) {
        // Keep retries stable even when the original visible leader is rejected/deleted.
        var state = group.state().orElseGet(() -> emailGroups.saveAndFlush(new AlertEmailGroup(
                group.tripId(), group.newsId(), group.members().getFirst().getEmailDeliveryKey())));
        if (state.isDelivered()) state.beginDelivery(leader.getEmailDeliveryKey());
        return state.getDeliveryKey();
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
