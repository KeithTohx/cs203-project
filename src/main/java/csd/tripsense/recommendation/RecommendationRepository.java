package csd.tripsense.recommendation;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RecommendationRepository extends JpaRepository<Recommendation, Long> {

    // regenerate means an impact can hold several attempts, so "did we already do this" is now
    // the newest one rather than the only one
    Optional<Recommendation> findFirstByImpactIdOrderByIdDesc(Long impactId);

    // every attempt on one impact, so the traveller can see what they already turned down
    List<Recommendation> findByImpactIdOrderByIdAsc(Long impactId);

    List<Recommendation> findByImpactItineraryId(Long itineraryId);

    // every recommendation hanging off this user's impacts; must be removed before the impacts
    void deleteByImpactUserId(Long userId);

    // everything ever suggested to one user, for the admin console
    List<Recommendation> findByImpactUserIdOrderByIdDesc(Long userId);
}
