package csd.tripsense.recommendation;

import jakarta.validation.constraints.NotNull;

// what the client sends to /api/recommendation/generate
public record GenerateRequest(
        @NotNull(message = "itineraryId is required") Long itineraryId) {
}
