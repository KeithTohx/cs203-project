package csd.tripsense.activity;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ActivityRepository extends JpaRepository<Activity, Long> {

    List<Activity> findByItineraryIdOrderByDateStart(Long itineraryId);

    // Activities in one country that have not finished yet and have no impact row for this news,
    // so one article is never judged twice against the same activity. The itinerary and its user
    // are fetched up front because an Impact needs the user and open-in-view is off.
    @Query("""
            select a from Activity a
            join fetch a.itinerary i
            join fetch i.user
            where a.country.id = :countryId
              and (a.dateEnd is null or a.dateEnd >= :now)
              and not exists (
                    select 1 from Impact im
                    where im.activity = a
                      and im.news.id = :newsId)
            """)
    List<Activity> findPendingForNews(@Param("newsId") Long newsId,
                                      @Param("countryId") Long countryId,
                                      @Param("now") LocalDateTime now);
}