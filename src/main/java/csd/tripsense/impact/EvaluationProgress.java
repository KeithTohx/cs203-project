package csd.tripsense.impact;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Records assessment completion for alert delivery; does not select, schedule, or retry AI work. */
@Service
public class EvaluationProgress {
    private final EvaluationBatchRepository batches;
    private final EvaluationTargetRepository targets;
    public EvaluationProgress(EvaluationBatchRepository batches,EvaluationTargetRepository targets) {
        this.batches=batches; this.targets=targets;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public String begin(Long tripId,Long newsId) {
        return batches.save(new EvaluationBatch(tripId,newsId,Instant.now())).getId();
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public String begin(Long tripId,Long newsId,boolean overall,List<Long> activities) {
        List<String> keys=new ArrayList<>();
        if (overall) register(tripId,newsId,null,keys);
        for (Long activityId:activities) register(tripId,newsId,activityId,keys);
        var batch=new EvaluationBatch(tripId,newsId,Instant.now()); batch.scope(keys);
        return batches.save(batch).getId();
    }
    private void register(Long tripId,Long newsId,Long activityId,List<String> keys) {
        String key=EvaluationTarget.key(tripId,newsId,activityId); keys.add(key);
        if (!targets.existsById(key)) targets.save(new EvaluationTarget(tripId,newsId,activityId));
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void resolved(Long tripId,Long newsId,Long activityId) {
        targets.findById(EvaluationTarget.key(tripId,newsId,activityId)).ifPresent(EvaluationTarget::resolve);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void finish(String id,boolean partial) {
        batches.findById(id).ifPresent(b -> {
            b.finish(partial,Instant.now());
            if (!partial && b.getTargetKeys()!=null && targets.countUnresolved(b.getItineraryId(),b.getNewsId())==0)
                batches.resolveLegacy(b.getItineraryId(),b.getNewsId(),Instant.now());
        });
    }
}
