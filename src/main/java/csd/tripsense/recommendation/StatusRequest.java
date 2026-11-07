package csd.tripsense.recommendation;

import jakarta.validation.constraints.NotNull;

// accept or dismiss, one endpoint for both since the check and the dto are the same
public record StatusRequest(@NotNull RecommendationStatus status) {
}
