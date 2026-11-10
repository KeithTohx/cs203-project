package csd.tripsense.impact;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository

public interface ImpactRepository extends JpaRepository<Impact, Long> {

    List<Impact> findByUserId(Long userId);

    List<Impact> findByItineraryId(Long itineraryId);

    List<Impact> findByItineraryIdAndNewsId(Long itineraryId, Long newsId);

    List<Impact> findByActivityId(Long activityId);

    // the itinerary-level verdict (no activity) for one news article, if there is one
    Optional<Impact> findFirstByNewsIdAndItineraryIdAndActivityIsNull(Long newsId, Long itineraryId);

    // a verdict already reached on this trip for the same headline, since one story reaches us
    // from many outlets as separate news rows
    Optional<Impact> findFirstByItineraryIdAndActivityIsNullAndNewsTitleAndReasonIsNotNull(
            Long itineraryId, String newsTitle);

    // the verdict for one activity and one news article, if there is one
    Optional<Impact> findFirstByNewsIdAndActivityId(Long newsId, Long activityId);

    List<Impact> findByUserIdAndItineraryIdAndImpactedTrue(Long userId, Long itineraryId);

    long countByItineraryIdAndUserId(Long itineraryId, Long userId);

    // the trips holding a verdict the model reached under older prompts, see Impact.CURRENT_PROMPT_VERSION.
    // seed rows without a reason have never been judged, so they are left for the evaluator
    @org.springframework.data.jpa.repository.Query("""
            select distinct i.itinerary.id from Impact i
            where i.reason is not null
              and (i.promptVersion is null or i.promptVersion < :current)
            """)
    List<Long> findTripsJudgedBeforePrompt(@org.springframework.data.repository.query.Param("current") long current);
    
    void deleteByUserId(Long userId);

    @org.springframework.data.jpa.repository.Query("""
            select i.id from Impact i
            where i.alertEligible = true and i.impacted = true and (i.rejected = false or i.rejected is null)
              and i.reason is not null and trim(i.reason) <> ''
              and i.itinerary is not null
              and (i.itinerary.user.enabled = true or i.itinerary.user.enabled is null)
              and not exists (select a.id from Alert a where a.impact = i)
            order by i.id
            """)
    List<Long> findAwaitingAlerts(org.springframework.data.domain.Pageable page);
}
