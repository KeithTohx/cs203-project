package csd.tripsense.impact;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PendingImpactEvaluationRepository extends JpaRepository<PendingImpactEvaluation, String> {
    void deleteByTargetIdAndActivity(Long targetId, boolean activity);
    boolean existsByTargetIdAndActivity(Long targetId, boolean activity);
    List<PendingImpactEvaluation> findByRetryAtLessThanEqualOrderByRetryAtAscTaskKeyAsc(Instant now, Pageable page);
}
