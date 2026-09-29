package csd.tripsense.impact;

import jakarta.validation.constraints.NotNull;

// what the client sends to /api/chat/evaluate
public record EvaluateRequest(
        @NotNull(message = "itineraryId is required") Long itineraryId,
        @NotNull(message = "newsId is required") Long newsId) {
}
