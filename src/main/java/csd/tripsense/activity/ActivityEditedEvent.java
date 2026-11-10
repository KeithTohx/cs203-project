package csd.tripsense.activity;

// published when a saved activity's name, address or start change, which is what its verdicts
// were judged on, or when it moves to another itinerary. a new end time or category does not
// publish it. keepVerdicts works the same as on ItineraryEditedEvent
public record ActivityEditedEvent(Long activityId, boolean keepVerdicts) {

    public ActivityEditedEvent(Long activityId) {
        this(activityId, false);
    }
}
