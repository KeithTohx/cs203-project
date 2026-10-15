package csd.tripsense.impact;

import java.util.List;

// impacted at the top level is true when any activity is impacted, so a caller
// can check one field instead of walking the list
public record EvaluateResponse(
        Long itineraryId,
        Long newsId,
        String newsTitle,
        boolean impacted,
        ItineraryResult itinerary,
        List<ActivityResult> activities) {

    // the verdict for the trip as a whole, separate from any single activity
    public record ItineraryResult(
            boolean impacted,
            String reason) {
    }

    public record ActivityResult(
            Long activityId,
            String name,
            boolean impacted,
            String reason) {
    }
}
