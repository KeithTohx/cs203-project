package csd.tripsense.impact;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EvaluationTargetRepository extends JpaRepository<EvaluationTarget,String> {
    long countByItineraryId(Long itineraryId);
    @Query("select count(t) from EvaluationTarget t where t.itineraryId=:tripId and t.newsId=:newsId "
            + "and t.resolved=false and (t.activityId is null or exists (select a.id from Activity a where a.id=t.activityId))")
    long countUnresolved(Long tripId,Long newsId);
}
