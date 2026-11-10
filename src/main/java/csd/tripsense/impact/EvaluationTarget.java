package csd.tripsense.impact;

import jakarta.persistence.*;
import java.time.Instant;

/** Alert-delivery metadata for an explicitly selected assessment target, not an evaluation queue. */
@Entity
@Table(indexes = @Index(name = "idx_evaluation_target_group", columnList = "itinerary_id,news_id"))
public class EvaluationTarget {
    @Id private String id;
    @Column(name = "itinerary_id")
    private Long itineraryId;
    private Long newsId;
    private Long activityId;
    private boolean resolved;
    private Instant updatedAt = Instant.now();
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "itinerary_id", insertable = false, updatable = false)
    private csd.tripsense.itinerary.Itinerary itinerary;
    protected EvaluationTarget() {}
    public EvaluationTarget(Long tripId, Long newsId, Long activityId) {
        this.id=key(tripId,newsId,activityId); this.itineraryId=tripId; this.newsId=newsId; this.activityId=activityId;
    }
    public static String key(Long tripId,Long newsId,Long activityId) {
        return tripId+":"+newsId+":"+(activityId==null?"trip":activityId);
    }
    public void resolve() { resolved=true; updatedAt=Instant.now(); }
}
