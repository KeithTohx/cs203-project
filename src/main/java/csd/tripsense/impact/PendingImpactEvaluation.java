package csd.tripsense.impact;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/** Durable snapshot of the news to assess for one newly created itinerary or activity. */
@Entity
@Table(name = "pending_impact_evaluation")
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
        this.taskKey = key(targetId, activity); this.targetId = targetId; this.activity = activity;
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
