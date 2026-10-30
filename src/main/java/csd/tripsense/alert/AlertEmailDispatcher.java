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
    private final ExecutorService worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(100), task -> {
                Thread thread = new Thread(task, "alert-email"); thread.setDaemon(true); return thread;
            });

    public AlertEmailDispatcher(AlertRepository alerts, AlertEmailStore store, AlertEmailSender sender) {
        this.alerts = alerts; this.store = store; this.sender = sender;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCreated(AlertCreatedEvent event) { enqueue(event.alert().id()); }

    @Scheduled(fixedDelayString = "${alerts.email.retry-interval-ms:15000}", initialDelay = 15000)
    public void retryPending() {
        if (!sender.enabled()) return;
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
            var claimed = store.claim(id, Instant.now());
            if (claimed.isEmpty()) return;
            var delivery = claimed.get();
            try {
                sender.send(delivery);
                store.sent(delivery, Instant.now());
            } catch (Exception exception) {
                if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
                store.failed(delivery, Instant.now());
                // Do not log SMTP credentials, recipient details, provider responses or message content.
                log.warn("Email delivery attempt {} failed for alert {}", delivery.attempt(), id);
            }
        } catch (RuntimeException exception) {
            log.warn("Email delivery deferred for alert {}", id);
        }
    }

    @PreDestroy void stop() { worker.shutdownNow(); }
}
