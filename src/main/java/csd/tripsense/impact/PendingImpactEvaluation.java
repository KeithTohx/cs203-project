package csd.tripsense.impact;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/** Durable snapshot of the news to assess for one newly created itinerary or activity. */
@Entity
@Table(name = "pending_impact_evaluation", indexes = @Index(
        name = "uk_pending_evaluation_target", columnList = "target_id,activity", unique = true))
public class PendingImpactEvaluation {
    @Id private String taskKey;
    @Column(nullable = false) private Long targetId;
    @Column(nullable = false) private boolean activity;
    @Lob @Column(nullable = false) private String articleIds;
    @Column(nullable = false) private int cursor;
    @Column(nullable = false) private Instant retryAt = Instant.now();

    // Deferred articles survive a capped pass or restart without blocking later articles in the snapshot.
    @Lob private String deferredArticleIds;
    void defer(Long newsId) {
        var deferred=new java.util.LinkedHashSet<Long>(deferredArticles()); deferred.add(newsId);
        deferredArticleIds=deferred.stream().map(String::valueOf).collect(Collectors.joining(","));
    }
    List<Long> deferredArticles() {
        return deferredArticleIds==null || deferredArticleIds.isBlank()?List.of():
                Arrays.stream(deferredArticleIds.split(",")).map(Long::valueOf).toList();
    }
    void retryDeferred(Instant next) {
        articleIds=deferredArticleIds; deferredArticleIds=null; cursor=0; retryAt=next;
    }

    protected PendingImpactEvaluation() {}
    PendingImpactEvaluation(Long targetId, boolean activity, List<Long> articles) {
        // A replacement target may reuse a deleted SQLite id. Old workers must never
        // advance or delete the replacement target's new task.
        this.taskKey = key(targetId, activity) + ":" + java.util.UUID.randomUUID();
        this.targetId = targetId; this.activity = activity;
        this.articleIds = articles.stream().map(String::valueOf).collect(Collectors.joining(","));
    }
    static String key(Long id, boolean activity) { return (activity ? "activity:" : "trip:") + id; }
    public String getTaskKey() { return taskKey; }
    public Long getTargetId() { return targetId; }
    public boolean isActivity() { return activity; }
    public int getCursor() { return cursor; }
    public List<Long> articles() {
        return articleIds.isBlank() ? List.of() : Arrays.stream(articleIds.split(",")).map(Long::valueOf).toList();
    }
    void advance(int cursor, Instant retryAt) { this.cursor = cursor; this.retryAt = retryAt; }
}
