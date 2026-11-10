package csd.tripsense.itinerary;

// published when a saved itinerary's name, country or dates change, which is what its whole trip
// verdicts were judged on. the trip's revision has already gone up by then, and so has each
// activity's when the country changed, so the verdicts judged on the old details can be found
// by their revision. the impact package listens for it, since this package cannot reach impacts
// without a cycle.
// keepVerdicts is for an edit that changes nothing real, eg an admin fixing a typo: the verdicts
// judged on the old details are carried over to the new revision instead of being cleared
public record ItineraryEditedEvent(Long itineraryId, boolean keepVerdicts) {

    public ItineraryEditedEvent(Long itineraryId) {
        this(itineraryId, false);
    }
}
