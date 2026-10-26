package csd.tripsense.itinerary;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

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
     * Retrieve the Itinerary by its unique identifier which belongs to the User 
     * 
     * @param id the unique identifier of the Itinerary
     * @param userId the unique identifier of the User
     * @return the Itinerary entity with the given Itinerary ID and User ID
     */
    Itinerary getItineraryByIdAndUserId(Long id, Long userId);

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


    /**
     * IDs of countries that have at least one itinerary that has not ended yet.
     * @param now the cut-off; itineraries ending before it are ignored
     * @return distinct country IDs (empty if there are none)
     */
    List<Long> getCountryIdsWithActiveItineraries(LocalDateTime now);

    /**
     * Itineraries in a country that have not ended yet.
     * @param countryId the unique identifier of the Country
     * @param now the cut-off; itineraries ending before it are ignored
     * @return matching itineraries, with user and country loaded (empty if none)
     */
    List<Itinerary> getActiveItinerariesByCountry(Long countryId, LocalDateTime now);

    /**
     * Itineraries in a country that have not ended yet and have not yet been evaluated
     * against the given news.
     * @param newsId the unique identifier of the News
     * @param countryId the unique identifier of the Country
     * @param now the cut-off; itineraries ending before it are ignored
     * @return itineraries still waiting for an impact decision on this news (empty if none)
     */
    List<Itinerary> getItinerariesPendingForNews(Long newsId, Long countryId, LocalDateTime now);

}
