package csd.tripsense.admin;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// the verdict side of an impact. the news, itinerary and activity it is about stay as they are,
// so to point an impact at something else, delete it and make a new one
public record AdminImpactUpdateRequest(
        @NotNull(message = "impacted is required")
        Boolean impacted,

        @Size(max = 500, message = "reason must be at most 500 characters")
        String reason,

        Boolean rejected) {
}
