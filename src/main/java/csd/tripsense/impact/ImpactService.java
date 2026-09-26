package csd.tripsense.impact;

import java.util.List;

public interface ImpactService {
    /**
     * Retrieves all impacts from the system.
     * 
     * @return a list of all impacts in the system. Returns an empty list if no impacts exist.
     */
    List<Impact> listImpacts();
    

    /**
     * Retrieves a specific impact by its unique identifier.
     * 
     * @param id the unique identifier of the impact to retrieve
     * @return the impact with the specified ID
     * @throws ImpactNotFoundException if no impact exists with the given ID
     */
    Impact getImpact(Long id);

    /**
     * Retrieves a impacts by user.
     * 
     * @param userId the unique identifier of the user 
     * @return a list of all impacts belonging to a user. Returns an empty list if no impacts exist.
     */
    List<Impact> getImpactsByUser(Long userId);

    /**
     * Retrieves impacts by itinerary.
     * 
     * @param itineraryId the unique identifier of the itinerary 
     * @return a list of all impacts affecting the itinerary. Returns an empty list if no impacts exist.
     */
    List<Impact> getImpactsByItinerary(Long itineraryId);

    /**
     * Retrieves impacts by activity.
     * 
     * @param activityId the unique identifier of the activity 
     * @return a list of all impacts affecting the activity. Returns an empty list if no impacts exist.
     */
    List<Impact> getImpactsByActivity(Long activityId);

    /**
     * Creates a new impact in the system.
     * 
     * @param impact the impact to be added
     * @return the newly created impact with assigned ID
     */
    Impact addImpact(Impact impact);
    
    /**
     * Updates an existing impact with new information.
     * 
     * @param id the unique identifier of the impact to update
     * @param newImpactInfo the updated impact information
     * @return the updated impact with all changes applied
     * @throws ImpactNotFoundException if no impact exists with the given ID
     */
    Impact updateImpact(Long id, Impact newImpactInfo);

    /**
     * Removes a impact from the system permanently.
     * 
     * @param id the unique identifier of the impact to delete
     * @throws ImpactNotFoundException if no impact exists with the given ID
     */
    void deleteImpact(Long id);
}
