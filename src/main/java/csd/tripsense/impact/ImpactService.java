package csd.tripsense.impact;

import java.util.List;
import csd.tripsense.activity.Activity;
import csd.tripsense.itinerary.Itinerary;
import csd.tripsense.news.News;

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

    /**
     * Retrieves a list of Impact for the User's Itinerary
     * 
     * @param userId the user to check
     * @param itineraryId the itinerary to check
     * @return
     */
    List<Impact> findImpacted(Long userId, Long itineraryId);

    /**
     * Counts all impact rows for this itinerary and user, whether impacted or not
     * 
     * @param itineraryId the itinerary to check
     * @param userId the user to check
     * @return
     */
    long countByItineraryIdAndUserId(Long itineraryId, Long userId);

    /**
     * Judges one itinerary as a whole against one news article. Returns the stored verdict if
     * there already is one (with a reason), otherwise asks the model and stores the answer.
     *
     * Unlike evaluate(), a failed model call is NOT swallowed and nothing is saved, so the
     * caller can stop and the pair is retried next time.
     *
     * @param itinerary the itinerary, with its user loaded
     * @param news the news article
     * @return the stored or newly saved itinerary-level impact
     * @throws RuntimeException if the model call fails
     */
    Impact evaluateItineraryForNews(Itinerary itinerary, News news);

    /**
     * Same as evaluateItineraryForNews, but for one activity.
     *
     * @param activity the activity, with its itinerary and the itinerary's user loaded
     * @param news the news article
     * @return the stored or newly saved activity-level impact
     * @throws RuntimeException if the model call fails
     */
    Impact evaluateActivityForNews(Activity activity, News news);
}
