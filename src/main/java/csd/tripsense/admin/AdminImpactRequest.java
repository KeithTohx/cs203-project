package csd.tripsense.admin;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// a hand made impact, so alerts and recommendations can be tested without waiting for the model.
// leave activityId out for a whole trip impact.
// a blank reason counts as "not evaluated yet", so the evaluator will judge the pair again later
public record AdminImpactRequest(
        @NotNull(message = "newsId is required")
        Long newsId,

        @NotNull(message = "itineraryId is required")
        Long itineraryId,

        Long activityId,

        @NotNull(message = "impacted is required")
        Boolean impacted,

        @Size(max = 500, message = "reason must be at most 500 characters")
        String reason,

        Boolean rejected,

        // true raises an alert for the owner (and sends the email), like a real evaluation would.
        // only works for an impacted, not rejected impact that has a reason
        Boolean sendAlert) {
}
