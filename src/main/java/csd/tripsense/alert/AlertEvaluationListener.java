package csd.tripsense.alert;

import csd.tripsense.impact.ImpactRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class AlertEvaluationListener {
    private static final Logger log = LoggerFactory.getLogger(AlertEvaluationListener.class);
    private final AlertService alerts;
    private final ImpactRepository impacts;

    public AlertEvaluationListener(AlertService alerts, ImpactRepository impacts) {
        this.alerts = alerts;
        this.impacts = impacts;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onEvaluated(ImpactEvaluatedEvent event) {
        create(event.impactId());
    }

    // Recovers a missed event after a restart or a temporary database failure. No AI calls are repeated.
    @Scheduled(fixedDelayString = "${alerts.reconcile.interval-ms:15000}",
               initialDelayString = "${alerts.reconcile.initial-delay-ms:15000}")
    public void reconcile() {
        for (Long id : impacts.findAwaitingAlerts(PageRequest.of(0, 100))) create(id);
    }

    private void create(Long id) {
        try { alerts.createForImpact(id); }
        catch (RuntimeException exception) {
            // A competing delivery may have won the unique impact constraint; otherwise the next scan retries.
            log.warn("Alert creation deferred for impact {}: {}", id, exception.getMessage());
        }
    }
}
