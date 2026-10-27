package csd.tripsense.activity;

import java.util.List;
import java.time.LocalDateTime;

public interface ActivityService {
    
    /**
     * Retrieve all activities from the database
     * @return List of all Activity entities
     */
    List<Activity> getAllActivities();

    /**
     * Retrieve Activity by its unique identifier
     * @param id the unique identifier of the Activity to retrieve
     * @return the Activity entity with the given ID
     * @throws ActivityNotFoundException if no Activity exists with the given ID
     */
    Activity getActivityById(Long id);

    /**
     * Retrieve all activities by unique identifier of itinerary
     * @param itineraryId the unique identifier of the Activity
     * @return List of all Activity entities with the given Activity ID
     * @throws ActivityNotFoundException if no Activity exists with the given ID
     */
    List<Activity> getActivitiesByItinerary(long itineraryId);

    /**
     * Creates a new Activity in the database
     * @param activity the Activity to be added
     * @return the newly created Activity entity with assigned ID
     */
    Activity addActivity(Activity activity);

    /**
     * Updates an existing Activity with new information
     * @param id the unique identifier of the Activity to update
     * @param newActivityInfo the Activity with updated information
     * @return the updated Activity entity with all changes applied
     * @throws ActivityNotFoundException if no Activity exists with the given ID
     */
    Activity updateActivity(Long id, Activity newActivityInfo);

    /**
     * Removes Activity by its unique identifier
     * @param id the unique identifier of the Activity to remove
     * @throws ActivityNotFoundException if no Activity exists with the given ID
     */
    void deleteActivity(Long id);

    /**
     * Activities in a country that have not ended yet and have not been evaluated against the given news.
     * @param newsId the unique identifier of the News
     * @param countryId the unique identifier of the Country
     * @param now the cut-off; activities ending before it are ignored
     * @return activities still waiting for an impact decision on this news (empty if none)
     */
    List<Activity> getActivitiesPendingForNews(Long newsId, Long countryId, LocalDateTime now);

}
