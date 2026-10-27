package csd.tripsense.audit;

import java.time.Instant;

/** What the admin audit endpoint returns for one entry. */
public record AuditEntryResponse(Long id, Instant occurredAt, Long actorId, String actorEmail,
                                 String action, Long targetId, String details) {

    public static AuditEntryResponse from(AuditLog entry) {
        return new AuditEntryResponse(entry.getId(), entry.getOccurredAt(), entry.getActorId(),
                entry.getActorEmail(), entry.getAction(), entry.getTargetId(), entry.getDetails());
    }
}
