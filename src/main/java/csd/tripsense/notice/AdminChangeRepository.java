package csd.tripsense.notice;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdminChangeRepository extends JpaRepository<AdminChange, Long> {

    // the newest ones, which is all the traveller is shown
    List<AdminChange> findTop20ByUserIdOrderByIdDesc(Long userId);

    long countByUserIdAndReadAtIsNull(Long userId);

    // upToId keeps a change that arrived while the list was open unread. null marks every one
    @Modifying
    @Query("""
            update AdminChange change set change.readAt = :now
            where change.userId = :userId
              and change.readAt is null
              and (:upToId is null or change.id <= :upToId)
            """)
    int markRead(@Param("userId") Long userId, @Param("upToId") Long upToId, @Param("now") Instant now);

    // one change, only if it is theirs
    @Modifying
    @Query("""
            update AdminChange change set change.readAt = :now
            where change.id = :id
              and change.userId = :userId
              and change.readAt is null
            """)
    int markOneRead(@Param("userId") Long userId, @Param("id") Long id, @Param("now") Instant now);

    void deleteByUserId(Long userId);
}
