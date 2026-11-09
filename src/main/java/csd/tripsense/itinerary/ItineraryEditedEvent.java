package csd.tripsense.itinerary;

// published when a saved itinerary's name, country or dates change, which is what its whole trip
// verdicts were judged on.
// countryChanged is there because a new country also makes the activity verdicts stale, they
// were about news from the old country and the news job will never look at that country again.
// the impact package listens for it, since this package cannot reach impacts without a cycle
public record ItineraryEditedEvent(Long itineraryId, boolean countryChanged) {
}
