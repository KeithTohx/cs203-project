package csd.tripsense.impact;

import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Small durable lifecycle records; no transaction spans an AI call. Abandoned leases expire. */
@Service
public class EvaluationProgress {
    private final EvaluationBatchRepository batches;
    public EvaluationProgress(EvaluationBatchRepository batches) { this.batches = batches; }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String begin(Long tripId, Long newsId) {
        return batches.save(new EvaluationBatch(tripId, newsId, Instant.now())).getId();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(String id, boolean partial) {
        batches.findById(id).ifPresent(b -> b.finish(partial, Instant.now()));
    }
}
