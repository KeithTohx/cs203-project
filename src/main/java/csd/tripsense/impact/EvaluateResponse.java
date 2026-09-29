package csd.tripsense.impact;

import java.util.List;

// impacted at the top level is true when any activity is impacted, so a caller
// can check one field instead of walking the list
public record EvaluateResponse(
        Long itineraryId,
        Long newsId,
        String newsTitle,
        boolean impacted,
        List<ActivityResult> activities) {

    public record ActivityResult(
            Long activityId,
            String name,
            boolean impacted,
            String reason) {
    }
}
