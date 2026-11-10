package csd.tripsense.itinerary;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ItineraryRepository extends JpaRepository<Itinerary, Long> {

    //Returns all of one user’s itineraries  
    List<Itinerary> findByUserId(Long userId);

    boolean existsByIdAndUserId(Long id, Long userId);
    
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

    // read straight from the database, never from an entity already loaded, so the evaluator
    // sees an edit that committed while the model was answering. empty once the trip is deleted,
    // and also once its id has gone to a new trip, which has a different identity key.
    // the type is Number, not Long: sqlite hands coalesce back as an Integer, and the service
    // normalises it to a Long so a later Optional comparison does not silently miss
    @Query("""
            select coalesce(i.revision, 0L) from Itinerary i
            where i.id = :id and i.identityKey = :identityKey
            """)
    Optional<Number> findRevision(@Param("id") Long id, @Param("identityKey") String identityKey);
}
