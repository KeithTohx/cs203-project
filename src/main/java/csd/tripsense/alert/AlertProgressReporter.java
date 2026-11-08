package csd.tripsense.alert;

import csd.tripsense.impact.EvaluationProgress;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Alert metadata is best-effort reporting; its failure cannot change a saved AI verdict or abort evaluation. */
@Service
public class AlertProgressReporter {
    private static final Logger log=LoggerFactory.getLogger(AlertProgressReporter.class);
    private final EvaluationProgress progress;
    public AlertProgressReporter(EvaluationProgress progress) { this.progress=progress; }
    public String begin(Long tripId,Long newsId,boolean overall,List<Long> activities) {
        return safely(() -> progress.begin(tripId,newsId,overall,activities));
    }
    public void resolved(Long tripId,Long newsId,Long activityId) {
        safely(() -> { progress.resolved(tripId,newsId,activityId); return null; });
    }
    public void finish(String batch,boolean partial) {
        if (batch!=null) safely(() -> { progress.finish(batch,partial); return null; });
    }
    public void reconcile() { safely(() -> { progress.reconcile(); return null; }); }
    private <T> T safely(Supplier<T> report) {
        try { return report.get(); }
        catch (RuntimeException failure) {
            // No provider payload, credentials, or assessment text is logged.
            log.warn("Alert progress reporting deferred; saved assessments remain available for reconciliation");
            return null;
        }
    }
}
