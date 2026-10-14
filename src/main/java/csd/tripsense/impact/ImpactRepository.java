package csd.tripsense.impact;

import java.util.*;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ImpactRepository extends JpaRepository <Impact, Long> {

    @Query("""
    select i from Impact i
    join fetch i.news
    left join fetch i.activity
    where i.itinerary.id = :itineraryId
      and i.user.id = :userId
      and i.impacted = true
    order by i.news.publishedAt desc
    """)
    List<Impact> findImpacted(@Param("userId") Long userId, @Param("itineraryId") Long itineraryId);

    long countByItineraryIdAndUserId(Long itineraryId, Long userId);

}
