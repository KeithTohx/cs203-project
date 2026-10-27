package csd.tripsense.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One thing a user (or an admin) did. It stores plain ids and an email, NOT a link to the user table,
 * so the record survives when the account is deleted. It never holds passwords or tokens.
 */
@Entity
@Table(name = "audit_log")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt = Instant.now();

    @Column(name = "actor_id")
    private Long actorId;           // who did it (null for a failed login by an unknown person)

    @Column(name = "actor_email")
    private String actorEmail;

    @Column(nullable = false)
    private String action;          // LOGIN, LOGIN_FAILED, LOGOUT, REGISTER, PROFILE_UPDATED, ACCOUNT_DELETED

    @Column(name = "target_id")
    private Long targetId;          // the account it was done to, if any

    @Column(length = 500)
    private String details;

    protected AuditLog() {
    }

    public AuditLog(String action, Long actorId, String actorEmail, Long targetId, String details) {
        this.action = action;
        this.actorId = actorId;
        this.actorEmail = actorEmail;
        this.targetId = targetId;
        this.details = details;
    }

    public Long getId() { return id; }
    public Instant getOccurredAt() { return occurredAt; }
    public Long getActorId() { return actorId; }
    public String getActorEmail() { return actorEmail; }
    public String getAction() { return action; }
    public Long getTargetId() { return targetId; }
    public String getDetails() { return details; }
}
