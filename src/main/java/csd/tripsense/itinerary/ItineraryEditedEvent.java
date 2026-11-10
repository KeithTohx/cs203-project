package csd.tripsense.itinerary;

// published when a saved itinerary's name, country or dates change, which is what its whole trip
// verdicts were judged on. the trip's revision has already gone up by then, and so has each
// activity's when the country changed, so the verdicts judged on the old details can be found
// by their revision. the impact package listens for it, since this package cannot reach impacts
// without a cycle
public record ItineraryEditedEvent(Long itineraryId) {
}
