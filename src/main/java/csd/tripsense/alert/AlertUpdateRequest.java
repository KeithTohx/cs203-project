package csd.tripsense.alert;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

// what the client sends to PATCH /api/alerts/{id} and PATCH /api/alerts. read is the only field a user
// may change, and only to true since alerts cannot be marked unread
public record AlertUpdateRequest(
        @NotNull(message = "read is required")
        @AssertTrue(message = "alerts can only be marked as read") Boolean read) {
}
