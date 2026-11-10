package csd.tripsense.notice;

import java.time.Instant;

// no admin details, the traveller is only told that an admin did it
public record AdminChangeResponse(Long id, String message, Instant createdAt, Instant readAt) {

    public static AdminChangeResponse from(AdminChange change) {
        return new AdminChangeResponse(change.getId(), change.getMessage(), change.getCreatedAt(), change.getReadAt());
    }
}
