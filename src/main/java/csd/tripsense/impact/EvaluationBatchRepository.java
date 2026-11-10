package csd.tripsense.impact;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EvaluationBatchRepository extends JpaRepository<EvaluationBatch, String> {
    @Query("select count(b) from EvaluationBatch b where b.itineraryId = :tripId "
            + "and b.finishedAt is null and b.leaseUntil > :now")
    long countRunningForItinerary(@Param("tripId") Long tripId, @Param("now") Instant now);

    @Query("select count(p) from PendingImpactEvaluation p where "
            + "(p.activity=false and p.targetId=:tripId) or (p.activity=true and p.targetId in "
            + "(select a.id from Activity a where a.itinerary.id=:tripId))")
    long countQueuedForItinerary(@Param("tripId") Long tripId);

    @Query("select count(b) from EvaluationBatch b where b.itineraryId = :tripId and b.newsId = :newsId "
            + "and b.finishedAt is null and b.leaseUntil > :now")
    long countRunning(@Param("tripId") Long tripId, @Param("newsId") Long newsId, @Param("now") Instant now);
    // Retain pre-target-tracking batches from earlier deployments until their verdicts resolve.
    @Query("select count(b) from EvaluationBatch b where b.itineraryId=:tripId and b.newsId=:newsId "
            + "and b.targetKeys is null and (b.partial=true or b.finishedAt is null)")
    long countLegacyIncomplete(@Param("tripId") Long tripId, @Param("newsId") Long newsId);

    @org.springframework.data.jpa.repository.Modifying
    @Query("update EvaluationBatch b set b.partial=false, b.finishedAt=:now where b.itineraryId=:tripId "
            + "and b.newsId=:newsId and b.targetKeys is null and (b.finishedAt is not null or b.leaseUntil<=:now)")
    void resolveLegacy(@Param("tripId") Long tripId, @Param("newsId") Long newsId, @Param("now") Instant now);

}
