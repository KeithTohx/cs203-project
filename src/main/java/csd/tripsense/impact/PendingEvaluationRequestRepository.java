package csd.tripsense.impact;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PendingEvaluationRequestRepository extends JpaRepository<PendingEvaluationRequest, String> {
    List<PendingEvaluationRequest> findAllByOrderByRequestedAtAsc(Pageable page);
}
