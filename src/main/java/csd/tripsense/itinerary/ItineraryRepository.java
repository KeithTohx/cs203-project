package csd.tripsense.itinerary;

import java.util.*;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository 
public interface ItineraryRepository extends JpaRepository<Itinerary, Long> {

    Optional<Itinerary> findByIdAndUserId(Long id, Long userId);
    
}
