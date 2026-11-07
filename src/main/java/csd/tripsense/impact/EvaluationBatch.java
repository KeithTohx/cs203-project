package csd.tripsense.impact;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Delivery observes actual evaluation work rather than reconstructing evaluator eligibility rules. */
@Entity
@Table(indexes = @Index(name = "idx_evaluation_batch_group", columnList = "itinerary_id,news_id,started_at"))
public class EvaluationBatch {
    @Id private String id;
    private Long itineraryId;
    private Long newsId;
    private Instant startedAt;
    private Instant leaseUntil;
    private Instant finishedAt;
    private boolean partial;

    protected EvaluationBatch() {}
    public EvaluationBatch(Long itineraryId, Long newsId, Instant now) {
        this.id = UUID.randomUUID().toString();
        this.itineraryId = itineraryId; this.newsId = newsId;
        this.startedAt = now; this.leaseUntil = now.plusSeconds(600);
    }
    public String getId() { return id; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getLeaseUntil() { return leaseUntil; }
    public Instant getFinishedAt() { return finishedAt; }
    public boolean isPartial() { return partial; }
    public void finish(boolean partial, Instant now) { this.partial = partial; this.finishedAt = now; }
}
