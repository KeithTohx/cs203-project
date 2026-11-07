package csd.tripsense.impact;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EvaluationBatchRepository extends JpaRepository<EvaluationBatch, String> {
    @Query("select count(b) from EvaluationBatch b where b.itineraryId = :tripId and b.newsId = :newsId "
            + "and b.finishedAt is null and b.leaseUntil > :now")
    long countRunning(Long tripId, Long newsId, Instant now);
    Optional<EvaluationBatch> findFirstByItineraryIdAndNewsIdOrderByStartedAtDesc(Long tripId, Long newsId);
}
