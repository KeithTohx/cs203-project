package csd.tripsense.impact;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EvaluationBatchRepository extends JpaRepository<EvaluationBatch, String> {
    @Query("select count(b) from EvaluationBatch b where b.itineraryId = :tripId and b.newsId = :newsId "
            + "and b.finishedAt is null and b.leaseUntil > :now")
    long countRunning(Long tripId, Long newsId, Instant now);
    @Query("select count(b) from EvaluationBatch b where b.itineraryId=:tripId and b.newsId=:newsId "
            + "and b.targetKeys is null and (b.partial=true or b.finishedAt is null)")
    long countLegacyIncomplete(Long tripId,Long newsId);

    @org.springframework.data.jpa.repository.Modifying
    @Query("update EvaluationBatch b set b.partial=false, b.finishedAt=:now where b.itineraryId=:tripId "
            + "and b.newsId=:newsId and b.targetKeys is null and (b.finishedAt is not null or b.leaseUntil<=:now)")
    void resolveLegacy(Long tripId,Long newsId,Instant now);

    Optional<EvaluationBatch> findFirstByItineraryIdAndNewsIdOrderByStartedAtDesc(Long tripId, Long newsId);
}
