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
    @Lob private String extraArticleIds;
    public java.util.Set<Long> extraArticles() {
        if (extraArticleIds==null || extraArticleIds.isBlank()) return java.util.Set.of();
        return java.util.Arrays.stream(extraArticleIds.split(",")).map(Long::valueOf)
                .collect(java.util.stream.Collectors.toSet());
    }
    public void include(java.util.Set<Long> ids) {
        var all=new java.util.TreeSet<Long>(extraArticles()); all.addAll(ids);
        extraArticleIds=all.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
    }
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
