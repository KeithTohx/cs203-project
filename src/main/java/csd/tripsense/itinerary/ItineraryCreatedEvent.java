package csd.tripsense.itinerary;
 
/** Published after a new itinerary has been saved. Carries only the id so listeners reload fresh state. */
public record ItineraryCreatedEvent(Long itineraryId) {
    
}
 