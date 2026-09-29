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
     * Checks every activity in an itinerary against one news article and saves the result.
     *
     * Each activity is assessed on its own, so a failure on one still leaves the others usable.
     * Re-running for the same itinerary and news updates the existing records rather than
     * adding duplicates.
     *
     * @param itineraryId the itinerary to check
     * @param newsId the news article to check it against
     * @return the verdict for each activity, plus whether any of them is impacted
     * @throws csd.tripsense.itinerary.ItineraryNotFoundException if the itinerary does not exist
     * @throws csd.tripsense.news.NewsNotFoundException if the news article does not exist
     */
    EvaluateResponse evaluate(Long itineraryId, Long newsId);

    /**
     * Removes a impact from the system permanently.
     * 
     * @param id the unique identifier of the impact to delete
     * @throws ImpactNotFoundException if no impact exists with the given ID
     */
    void deleteImpact(Long id);
}
