package csd.tripsense.recommendation;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RecommendationRepository extends JpaRepository<Recommendation, Long> {

    // one recommendation per impact, so this doubles as the "did we already do this" check
    Optional<Recommendation> findByImpactId(Long impactId);

    List<Recommendation> findByImpactItineraryId(Long itineraryId);

    // every recommendation hanging off this user's impacts; must be removed before the impacts
    void deleteByImpactUserId(Long userId);
}
