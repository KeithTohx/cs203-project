package csd.tripsense.admin;

import csd.tripsense.recommendation.Action;
import csd.tripsense.recommendation.RecommendationStatus;
import csd.tripsense.recommendation.Severity;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// a hand made recommendation (suggestion) on one impact. the impact comes from the url on create
public record AdminRecommendationRequest(
        @NotNull(message = "severity is required")
        Severity severity,

        @NotNull(message = "action is required")
        Action action,

        @Size(max = 500, message = "advice must be at most 500 characters")
        String advice,

        // an activity_option id, or null when there is nothing to swap to
        Long optionId,

        // PENDING when left out
        RecommendationStatus status) {
}
