package csd.tripsense.impact;

import jakarta.validation.constraints.NotNull;

// what the client sends to PATCH /api/impact/{id}. rejected is the only thing a user may change,
// the verdict and reason always come from the model
public record ImpactUpdateRequest(
        @NotNull(message = "rejected is required") Boolean rejected) {
}
