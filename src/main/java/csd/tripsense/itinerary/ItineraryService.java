package csd.tripsense.itinerary;

import java.util.List;

public interface ItineraryService {

    /**
     * Retrieve all itineraries from the database
     * @return List of all Itinerary entities
     */
    List<Itinerary> getAllItineraries();

    /**
     * Retrieve Itinerary by its unique identifier
     * @param id the unique identifier of the Itinerary to retrieve
     * @return the Itinerary entity with the given ID
     * @throws ItineraryNotFoundException if no Itinerary exists with the given ID
     */
    Itinerary getItineraryById(Long id);

    /**
     * Retrieve all itineraries by users' unique identifier
     * @param userId the unique identifier of the User
     * @return List of all Itinerary entities with the given User ID
     * @throws ItineraryNotFoundException if no Itinerary exists with the given ID
     */
    List<Itinerary> getItinerariesByUser(Long userId);

    /**
     * Retrieve all itineraries by countries' unique identifier
     * @param countryId the unique identifier of the Country
     * @return List of all Itinerary entities with the given Country ID
     * @throws ItineraryNotFoundException if no Itinerary exists with the given ID
     */
    List<Itinerary> getItinerariesByCountry(Long countryId);

    /**
     * Creates a new Itinerary in the database
     * @param itinerary the Itinerary to be added
     * @return the newly created Itinerary entity with assigned ID
     */
    Itinerary addItinerary(Itinerary itinerary);

    /**
     * Updates an existing Itinerary with new information
     * @param id the unique identifier of the Itinerary to update
     * @param newItineraryInfo the Itinerary with updated information
     * @return the updated Itinerary entity with all changes applied
     * @throws ItineraryNotFoundException if no Itinerary exists with the given ID
     */
    Itinerary updateItinerary(Long id, Itinerary newItineraryInfo);

    /**
     * Removes Itinerary by its unique identifier
     * @param id the unique identifier of the Itinerary to remove
     * @throws ItineraryNotFoundException if no Itinerary exists with the given ID
     */
    void deleteItinerary(Long id);

}
