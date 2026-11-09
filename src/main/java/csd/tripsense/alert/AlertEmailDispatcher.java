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
                log.warn("Email delivery attempt {} failed for alert {}", delivery.attempt(), id);
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
        return alert == null ? delivery.key() : AlertEmailGroup.key(alert.itineraryId(), alert.newsId());
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
            // Retain only this group's confirmation; never repeat its accepted SMTP request.
            log.warn("Email accepted for alert {}; database confirmation deferred", accepted.delivery().alertId());
        }
    }

    @PreDestroy void stop() { worker.shutdownNow(); }
}
