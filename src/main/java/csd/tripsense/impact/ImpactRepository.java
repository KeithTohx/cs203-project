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
}