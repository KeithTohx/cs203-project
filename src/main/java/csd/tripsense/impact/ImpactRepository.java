package csd.tripsense.impact;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository

public interface ImpactRepository extends JpaRepository<Impact, Long> {

    List<Impact> findByUserId(Long userId);

    List<Impact> findByItineraryId(Long itineraryId);

    List<Impact> findByActivityId(Long activityId);

    // the itinerary-level verdict (no activity) for one news article, if there is one
    Optional<Impact> findFirstByNewsIdAndItineraryIdAndActivityIsNull(Long newsId, Long itineraryId);

    // the verdict for one activity and one news article, if there is one
    Optional<Impact> findFirstByNewsIdAndActivityId(Long newsId, Long activityId);

    List<Impact> findByUserIdAndItineraryIdAndImpactedTrue(Long userId, Long itineraryId);

    long countByItineraryIdAndUserId(Long itineraryId, Long userId);
    
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