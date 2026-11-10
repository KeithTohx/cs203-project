package csd.tripsense.activity;

// published when a saved activity's name, address or start change, which is what its verdicts
// were judged on, or when it moves to another itinerary. a new end time or category does not
// publish it
public record ActivityEditedEvent(Long activityId) {
}
