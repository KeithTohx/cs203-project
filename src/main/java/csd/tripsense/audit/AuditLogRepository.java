package csd.tripsense.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    Page<AuditLog> findAllByOrderByIdDesc(Pageable pageable);

    Page<AuditLog> findByActorEmailContainingIgnoreCaseOrderByIdDesc(String actorEmail, Pageable pageable);

    Page<AuditLog> findByActionOrderByIdDesc(String action, Pageable pageable);

    Page<AuditLog> findByActorEmailContainingIgnoreCaseAndActionOrderByIdDesc(
            String actorEmail, String action, Pageable pageable);

    /** Everything this account DID (as actor) or that was done TO it (as target), newest first. */
    @Query(value = "select a from AuditLog a where a.actorId = :userId or a.targetId = :userId order by a.id desc",
           countQuery = "select count(a) from AuditLog a where a.actorId = :userId or a.targetId = :userId")
    Page<AuditLog> findForUser(@Param("userId") Long userId, Pageable pageable);

    @Query(value = "select a from AuditLog a where (a.actorId = :userId or a.targetId = :userId)"
                   + " and a.action = :action order by a.id desc",
           countQuery = "select count(a) from AuditLog a where (a.actorId = :userId or a.targetId = :userId)"
                   + " and a.action = :action")
    Page<AuditLog> findForUserAndAction(@Param("userId") Long userId, @Param("action") String action,
                                        Pageable pageable);
}
