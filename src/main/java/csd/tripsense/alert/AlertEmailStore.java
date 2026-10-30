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

    public record Delivery(Long alertId, int attempt, String key, String recipient, AlertResponse alert) {}

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Delivery> claim(Long id, Instant now) {
        Alert alert = alerts.findById(id).orElse(null);
        if (alert == null || !RETRYABLE.contains(alert.getEmailStatus())
                || alert.getEmailNextAttemptAt().isAfter(now)) return Optional.empty();
        if (alert.getEmailAttempts() >= MAX_ATTEMPTS) {
            alerts.finishEmail(id, alert.getEmailAttempts(), RETRYABLE, EmailStatus.FAILED, null, now);
            return Optional.empty();
        }
        var impact = alert.getImpact();
        var recipient = impact.getItinerary().getUser();
        if (!Boolean.TRUE.equals(impact.getImpacted()) || Boolean.TRUE.equals(impact.getRejected())
                || !recipient.isEnabled()) return Optional.empty();
        int attempt = alert.getEmailAttempts() + 1;
        var message = new Delivery(id, attempt, alert.getEmailDeliveryKey(), recipient.getEmail(), AlertResponse.from(alert));
        // The conditional update prevents two workers claiming the same pending email.
        int claimed = alerts.claimEmail(id, attempt - 1, RETRYABLE, EmailStatus.SENDING, now, now.plusSeconds(120));
        return claimed == 1 ? Optional.of(message) : Optional.empty();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sent(Delivery delivery, Instant now) {
        alerts.finishEmail(delivery.alertId(), delivery.attempt(), List.of(EmailStatus.SENDING), EmailStatus.SENT, now, now);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failed(Delivery delivery, Instant now) {
        EmailStatus state = delivery.attempt() >= MAX_ATTEMPTS ? EmailStatus.FAILED : EmailStatus.PENDING;
        alerts.finishEmail(delivery.alertId(), delivery.attempt(), List.of(EmailStatus.SENDING), state,
                null, now.plusSeconds(60L * delivery.attempt()));
    }
}
