package csd.tripsense.itinerary;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ItineraryRepository extends JpaRepository<Itinerary, Long> {

    //Returns all of one user’s itineraries  
    List<Itinerary> findByUserId(Long userId);
    
    // Countries that have at least one trip still to come (or under way). Lets the evaluator
    // skip news about countries nobody is travelling to.
    @Query("""
            select distinct i.country.id from Itinerary i
            where i.dateEnd is null or i.dateEnd >= :now
            """)
    List<Long> findCountryIdsWithActiveItineraries(@Param("now") LocalDateTime now);

    // Trips that have not finished yet, in one country. user and country are fetched up front
    // so the evaluator can read them without lazy-loading.
    @Query("""
            select i from Itinerary i
            join fetch i.user
            join fetch i.country
            where i.country.id = :countryId
              and (i.dateEnd is null or i.dateEnd >= :now)
            """)
    List<Itinerary> findActiveByCountry(@Param("countryId") Long countryId,
                                        @Param("now") LocalDateTime now);

    // Same, but only the itineraries that have no itinerary-level impact row for this news yet,
    // so one news article is never judged twice against the same itinerary.
        @Query("""
            select i from Itinerary i
            join fetch i.user
            join fetch i.country
            where i.country.id = :countryId
              and (i.dateEnd is null or i.dateEnd >= :now)
              and not exists (
                    select 1 from Impact im
                    where im.itinerary = i
                      and im.news.id = :newsId
                      and im.activity is null
                      and im.reason is not null)
            """)
    List<Itinerary> findPendingForNews(@Param("newsId") Long newsId,
                                       @Param("countryId") Long countryId,
                                       @Param("now") LocalDateTime now);
}


}

