package csd.tripsense.impact;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface EvaluationTargetRepository extends JpaRepository<EvaluationTarget,String> {
    long countByItineraryId(Long itineraryId);
    String IN_SCOPE = "(t.activityId is null or exists (select a.id from Activity a "
            + "where a.id=t.activityId and a.itinerary.id=t.itineraryId))";
    String ASSESSED = "exists (select i.id from Impact i where i.itinerary.id=t.itineraryId and i.news.id=t.newsId "
            + "and ((t.activityId is null and i.activity is null) or i.activity.id=t.activityId) "
            + "and i.reason is not null and trim(i.reason)<>'')";

    @Query("select count(t) from EvaluationTarget t where t.itineraryId=:tripId and t.newsId=:newsId "
            + "and t.resolved=false and " + IN_SCOPE)
    long countUnresolved(Long tripId,Long newsId);

    // SQLite serializes the upsert. Re-registration reopens only targets without a saved valid verdict.
    @Modifying
    @Query(value="""
        insert into evaluation_target(id,itinerary_id,news_id,activity_id,resolved,updated_at)
        values(:key,:tripId,:newsId,:activityId,
          case when exists (select 1 from impact i where i.itinerary_id=:tripId and i.news_id=:newsId
             and i.activity_id is :activityId and i.reason is not null and trim(i.reason)<>'')
          then 1 else 0 end,:now)
        on conflict(id) do update set resolved=excluded.resolved, updated_at=excluded.updated_at
        """,nativeQuery=true)
    void register(String key,Long tripId,Long newsId,Long activityId,Instant now);

    @Modifying
    @Query("update EvaluationTarget t set t.resolved=true,t.updatedAt=:now where t.id=:key and " + IN_SCOPE + " and " + ASSESSED)
    int resolveIfAssessed(String key,Instant now);

    @Query("select t.id from EvaluationTarget t where t.resolved=false and " + IN_SCOPE + " and " + ASSESSED + " order by t.id")
    List<String> findResolvable(Pageable page);
}
