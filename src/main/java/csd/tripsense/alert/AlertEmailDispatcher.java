package csd.tripsense.alert;

import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class AlertEmailDispatcher {
    private static final Logger log = LoggerFactory.getLogger(AlertEmailDispatcher.class);
    private final AlertRepository alerts;
    private final AlertEmailStore store;
    private final AlertEmailSender sender;
    // Accepted messages retry confirmation independently; other groups can continue sending.
    private record AcceptedEmail(AlertEmailStore.Delivery delivery, Instant acceptedAt) {}
    private final ConcurrentMap<String, AcceptedEmail> awaitingConfirmation = new ConcurrentHashMap<>();
    private final ExecutorService worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(100), task -> {
                Thread thread = new Thread(task, "alert-email"); thread.setDaemon(true); return thread;
            });

    public AlertEmailDispatcher(AlertRepository alerts, AlertEmailStore store, AlertEmailSender sender) {
        this.alerts = alerts; this.store = store; this.sender = sender;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCreated(AlertCreatedEvent event) { enqueue(event.alert().id()); }

    @Scheduled(scheduler = "alertTaskScheduler", fixedDelayString = "${alerts.email.retry-interval-ms:15000}", initialDelay = 15000)
    public void retryPending() {
        if (!sender.enabled()) return;
        if (!awaitingConfirmation.isEmpty()) {
            try { worker.execute(this::confirmAcceptedEmails); }
            catch (RejectedExecutionException exception) { log.warn("Email confirmation queue busy; will retry"); }
        }
        for (Long id : alerts.findEmailReady(AlertEmailStore.RETRYABLE, Instant.now(), PageRequest.of(0, 50))) enqueue(id);
    }

    private void enqueue(Long id) {
        if (!sender.enabled()) return;
        try { worker.execute(() -> deliver(id)); }
        catch (RejectedExecutionException exception) { log.warn("Email queue busy; alert {} will be retried", id); }
    }

    void deliver(Long id) {
        if (!sender.enabled()) return;
        try {
            var claimed = awaitingConfirmation.isEmpty() ? store.claim(id, Instant.now())
                    : store.claim(id, Instant.now(), java.util.Set.copyOf(awaitingConfirmation.keySet()));
            if (claimed.isEmpty()) return;
            var delivery = claimed.get();
            try {
                sender.send(delivery);
            } catch (Exception exception) {
                if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
                store.failed(delivery, Instant.now());
                // Do not log SMTP credentials, recipient details, provider responses or message content.
                log.warn("Email delivery attempt {} failed for alert {}: {}",
                        delivery.attempt(), id, failureSummary(exception));
                return;
            }
            // SMTP has accepted the message. A database failure is not a send failure.
            String group = groupKey(delivery);
            awaitingConfirmation.put(group, new AcceptedEmail(delivery, Instant.now()));
            confirmAcceptedEmail(group);
        } catch (RuntimeException exception) {
            log.warn("Email delivery deferred for alert {}", id);
        }
    }

    private static String groupKey(AlertEmailStore.Delivery delivery) {
        var alert = delivery.alert();
        return alert == null ? delivery.key() : AlertEmailStore.itineraryKey(alert.itineraryId());
    }

    // Provider messages and stack traces can include recipient addresses or credentials.
    // Report exception types, status codes and allowlisted categories, never raw provider text.
    static String failureSummary(Throwable failure) {
        var pending = new java.util.ArrayDeque<Throwable>();
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        var details = new java.util.LinkedHashSet<String>();
        pending.add(failure);
        while (!pending.isEmpty() && seen.size() < 32) {
            Throwable current = pending.removeFirst();
            if (!seen.add(current)) continue;
            String type = current.getClass().getSimpleName();
            if (current instanceof jakarta.mail.SendFailedException) {
                // SMTP replies begin with a three-digit status; discard all remaining text.
                var code = java.util.regex.Pattern.compile("^([245][0-9]{2})(?:[ \\-]|$)")
                        .matcher(String.valueOf(current.getMessage()));
                if (code.find()) type += " SMTP " + code.group(1);
                String response = String.valueOf(current.getMessage());
                var enhanced = java.util.regex.Pattern.compile("\\b[245]\\.[0-9]{1,3}\\.[0-9]{1,3}\\b")
                        .matcher(response);
                if (enhanced.find()) type += " status=" + enhanced.group();
                String lower = response.toLowerCase(java.util.Locale.ROOT);
                for (String category : java.util.List.of("rfc 5322", "rfc5322", "message-id", "from header",
                        "date header", "spam", "quota", "sending limit", "rate limit", "unauthenticated",
                        "dkim", "spf", "blocked", "suspicious", "duplicate")) {
                    if (lower.contains(category)) type += " [" + category + "]";
                }
            }
            details.add(type);
            if (current.getCause() != null) pending.add(current.getCause());
            if (current instanceof jakarta.mail.MessagingException mail && mail.getNextException() != null) {
                pending.add(mail.getNextException());
            }
            if (current instanceof org.springframework.mail.MailSendException mail) {
                pending.addAll(mail.getFailedMessages().values());
            }
        }
        return String.join(" -> ", details);
    }

    private void confirmAcceptedEmails() {
        for (String group : awaitingConfirmation.keySet()) confirmAcceptedEmail(group);
    }

    private void confirmAcceptedEmail(String group) {
        var accepted = awaitingConfirmation.get(group);
        if (accepted == null) return;
        try {
            store.sent(accepted.delivery(), accepted.acceptedAt());
            awaitingConfirmation.remove(group, accepted);
        } catch (RuntimeException exception) {
            // Retry confirmation without resending while this process survives. This state is
            // in memory: a crash before confirmation can cause a duplicate after lease recovery.
            log.warn("Email accepted for alert {}; database confirmation deferred", accepted.delivery().alertId());
        }
    }

    @PreDestroy void stop() { worker.shutdownNow(); }
}
