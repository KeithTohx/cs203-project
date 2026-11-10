package csd.tripsense.alert;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AlertRepository extends JpaRepository<Alert, Long> {
    boolean existsByImpactId(Long impactId);

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {
            "impact", "impact.user", "impact.itinerary", "impact.itinerary.user", "impact.itinerary.country",
            "impact.activity", "impact.activity.country", "impact.activity.itinerary",
            "impact.activity.itinerary.country", "impact.activity.itinerary.user", "impact.news"})
    @Query("select a from Alert a where a.impact.itinerary.id = :tripId and a.impact.news.id = :newsId order by a.id")
    java.util.List<Alert> findEmailGroup(@Param("tripId") Long tripId, @Param("newsId") Long newsId);

    @Modifying
    @Query("update Alert a set a.emailStatus = csd.tripsense.alert.EmailStatus.SUPPRESSED, a.emailSentAt = null "
            + "where a.id = :id and a.emailAttempts = :attempts and a.emailStatus in :states")
    int suppressEmail(@Param("id") Long id, @Param("attempts") int attempts,
                      @Param("states") java.util.List<EmailStatus> states);

    // Suppress notifications the traveller rejected, including notifications created before rejection.
    String VISIBLE = "a.impact.itinerary.user.id = :userId and a.impact.impacted = true "
            + "and (a.impact.rejected = false or a.impact.rejected is null)";

    // beforeId is the last alert already shown, so alerts marked read or newly arrived never shift the next page
    // a slice skips the count query, since a cursor has no fixed total to report
    @Query("select a from Alert a where " + VISIBLE + " and (:unreadOnly = false or a.readAt is null)"
            + " and (:beforeId is null or a.id < :beforeId) order by a.id desc")
    Slice<Alert> findForUser(@Param("userId") Long userId, @Param("unreadOnly") boolean unreadOnly,
                             @Param("beforeId") Long beforeId, Pageable page);

    @Query("select count(a) from Alert a where " + VISIBLE + " and a.readAt is null")
    long countUnread(@Param("userId") Long userId);

    // upToId is the newest alert the user has seen, so an alert arriving mid-click is left unread
    // a bulk update only writes readAt, so it cannot overwrite email state the email worker changed meanwhile
    // VISIBLE navigates through impact, which an update cannot join on, so the same rule goes in a subquery
    @Modifying
    @Query("update Alert a set a.readAt = :now where a.readAt is null"
            + " and (:upToId is null or a.id <= :upToId)"
            + " and a.id in (select v.id from Alert v where v.impact.itinerary.user.id = :userId"
            + " and v.impact.impacted = true and (v.impact.rejected = false or v.impact.rejected is null))")
    int markAllReadForUser(@Param("userId") Long userId, @Param("upToId") Long upToId, @Param("now") Instant now);

    @Modifying
    @Query("update Alert a set a.readAt = :now where a.readAt is null and a.id <= :upToId"
            + " and a.id in (select v.id from Alert v where v.impact.itinerary.user.id = :userId"
            + " and v.impact.impacted = true "
            + "and (v.impact.rejected = false or v.impact.rejected is null)"
            + " and v.impact.itinerary.id = :tripId and v.impact.news.id = :newsId)")
    int markGroupRead(@Param("userId") Long userId, @Param("tripId") Long tripId, @Param("newsId") Long newsId,
                      @Param("upToId") Long upToId, @Param("now") Instant now);

    @Query("select a from Alert a where a.id = :id and " + VISIBLE)
    Optional<Alert> findOwned(@Param("id") Long id, @Param("userId") Long userId);

    @Query("""
            select a.id from Alert a where a.emailStatus in :states and a.emailNextAttemptAt <= :now
              and a.impact.impacted = true and (a.impact.rejected = false or a.impact.rejected is null)
              and (a.impact.itinerary.user.enabled = true or a.impact.itinerary.user.enabled is null)
            order by a.emailNextAttemptAt, a.id
            """)
    java.util.List<Long> findEmailReady(@Param("states") java.util.List<EmailStatus> states,
                                       @Param("now") java.time.Instant now, Pageable page);

    @org.springframework.data.jpa.repository.Modifying
    @Query("""
            update Alert a set a.emailNextAttemptAt = :next
            where a.id = :id and a.emailStatus = :state and a.emailAttempts = :attempts
              and a.emailNextAttemptAt < :next
            """)
    int deferEmail(@Param("id") Long id, @Param("attempts") int attempts,
                   @Param("state") EmailStatus state, @Param("next") java.time.Instant next);

    @org.springframework.data.jpa.repository.Modifying
    @Query("""
            update Alert a set a.emailStatus = :sending, a.emailAttempts = a.emailAttempts + 1,
                               a.emailDeliveryKey = :deliveryKey,
                               a.emailNextAttemptAt = :leaseUntil
            where a.id = :id and a.emailAttempts = :attempts and a.emailStatus in :states
              and a.emailNextAttemptAt <= :now
            """)
    int claimEmail(@Param("id") Long id, @Param("attempts") int attempts,
                   @Param("states") java.util.List<EmailStatus> states,
                   @Param("sending") EmailStatus sending,
                   @Param("now") java.time.Instant now, @Param("leaseUntil") java.time.Instant leaseUntil,
                   @Param("deliveryKey") String deliveryKey);

    @org.springframework.data.jpa.repository.Modifying
    @Query("""
            update Alert a set a.emailStatus = :state, a.emailSentAt = :sentAt, a.emailNextAttemptAt = :next
            where a.id = :id and a.emailAttempts = :attempts and a.emailStatus in :states
            """)
    int finishEmail(@Param("id") Long id, @Param("attempts") int attempts,
                    @Param("states") java.util.List<EmailStatus> states, @Param("state") EmailStatus state,
                    @Param("sentAt") java.time.Instant sentAt, @Param("next") java.time.Instant next);

}
