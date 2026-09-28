package csd.tripsense.itinerary;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository 
public interface ItineraryRepository extends JpaRepository<Itinerary, Long> {
    
}
