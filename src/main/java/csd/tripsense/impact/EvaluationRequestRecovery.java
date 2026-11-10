package csd.tripsense.impact;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class EvaluationRequestRecovery {
    private static final Logger log = LoggerFactory.getLogger(EvaluationRequestRecovery.class);
    private final PendingEvaluationRequestRepository requests;
    private final ImpactEvaluationQueue queue;
    private final NewItemEvaluationListener worker;
    private final boolean enabled;
    public EvaluationRequestRecovery(PendingEvaluationRequestRepository requests, ImpactEvaluationQueue queue,
            NewItemEvaluationListener worker, @Value("${impact.eval.new-itinerary.enabled:true}") boolean enabled) {
        this.requests = requests; this.queue = queue; this.worker = worker; this.enabled = enabled;
    }
    @Scheduled(fixedDelayString="${impact.eval.new-itinerary.retry-interval-ms:30000}", initialDelay=15000)
    public void recover() {
        if (!enabled) return;
        for (var request : requests.findAllByOrderByRequestedAtAsc(PageRequest.of(0, 50))) {
            try { queue.enqueue(request.getTargetId(), request.isActivity()); }
            catch (RuntimeException failure) { log.warn("Evaluation scheduling deferred; committed request will retry"); }
        }
        worker.retryPending();
    }
}
