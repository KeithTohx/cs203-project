package csd.tripsense.alert;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AlertRepository extends JpaRepository<Alert, Long> {
    boolean existsByImpactId(Long impactId);

    // Suppress notifications the traveller rejected, including notifications created before rejection.
    String VISIBLE = "a.impact.itinerary.user.id = :userId and a.impact.impacted = true "
            + "and (a.impact.rejected = false or a.impact.rejected is null)";

    @Query("select a from Alert a where " + VISIBLE + " and (:unreadOnly = false or a.readAt is null) order by a.id desc")
    Page<Alert> findForUser(@Param("userId") Long userId, @Param("unreadOnly") boolean unreadOnly, Pageable page);

    @Query("select count(a) from Alert a where " + VISIBLE + " and a.readAt is null")
    long countUnread(@Param("userId") Long userId);

    @Query("select a from Alert a where a.id = :id and " + VISIBLE)
    Optional<Alert> findOwned(@Param("id") Long id, @Param("userId") Long userId);

    @Query("""
            select a.id from Alert a where a.emailStatus in :states and a.emailNextAttemptAt <= :now
              and a.impact.impacted = true and (a.impact.rejected = false or a.impact.rejected is null)
              and (a.impact.itinerary.user.enabled = true or a.impact.itinerary.user.enabled is null)
            order by a.id
            """)
    java.util.List<Long> findEmailReady(@Param("states") java.util.List<EmailStatus> states,
                                       @Param("now") java.time.Instant now, Pageable page);

    @org.springframework.data.jpa.repository.Modifying
    @Query("""
            update Alert a set a.emailStatus = :sending, a.emailAttempts = a.emailAttempts + 1,
                               a.emailNextAttemptAt = :leaseUntil
            where a.id = :id and a.emailAttempts = :attempts and a.emailStatus in :states
              and a.emailNextAttemptAt <= :now
            """)
    int claimEmail(@Param("id") Long id, @Param("attempts") int attempts,
                   @Param("states") java.util.List<EmailStatus> states,
                   @Param("sending") EmailStatus sending,
                   @Param("now") java.time.Instant now, @Param("leaseUntil") java.time.Instant leaseUntil);

    @org.springframework.data.jpa.repository.Modifying
    @Query("""
            update Alert a set a.emailStatus = :state, a.emailSentAt = :sentAt, a.emailNextAttemptAt = :next
            where a.id = :id and a.emailAttempts = :attempts and a.emailStatus in :states
            """)
    int finishEmail(@Param("id") Long id, @Param("attempts") int attempts,
                    @Param("states") java.util.List<EmailStatus> states, @Param("state") EmailStatus state,
                    @Param("sentAt") java.time.Instant sentAt, @Param("next") java.time.Instant next);

}
