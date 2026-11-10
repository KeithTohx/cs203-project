package csd.tripsense.impact;

import jakarta.persistence.*;
import java.time.Instant;

/** A committed source change that still needs its news snapshot queued. */
@Entity
public class PendingEvaluationRequest {
    @Id private String id;
    private Long targetId;
    private boolean activity;
    private Instant requestedAt;
    protected PendingEvaluationRequest() {}
    PendingEvaluationRequest(Long targetId, boolean activity) {
        this.id = key(targetId, activity);
        this.targetId = targetId;
        this.activity = activity;
        this.requestedAt = Instant.now();
    }
    static String key(Long id, boolean activity) { return (activity ? "activity:" : "trip:") + id; }
    public String getId() { return id; }
    public Long getTargetId() { return targetId; }
    public boolean isActivity() { return activity; }
    public Instant getRequestedAt() { return requestedAt; }
}
