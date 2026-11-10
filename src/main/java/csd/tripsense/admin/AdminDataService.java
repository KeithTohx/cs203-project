package csd.tripsense.admin;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;

import csd.tripsense.activity.Activity;
import csd.tripsense.impact.Impact;
import csd.tripsense.itinerary.Itinerary;
import csd.tripsense.recommendation.Recommendation;
import csd.tripsense.user.User;

// the admin console: read and change any account's trip data, mostly so testing does not need
// a second browser logged in as that user, or the model to be running.
//
// unlike the traveller endpoints there is no ownership check here, an admin sees everyone's data.
// every method checks the role itself as well as the /api/admin/** url rule, like AdminService.
//
// a missing user, itinerary, activity, impact or recommendation in the path is a 404.
// a bad reference in the body (unknown country, news or option, an activity from another trip)
// is a 400, since the thing being asked about does exist
public interface AdminDataService {

    // ---------- accounts ----------

    // makes an account with the given role. the password is hashed the same way as registering.
    // throws AccountRuleException (CONFLICT) if the email or username is taken
    @PreAuthorize("hasRole('ADMIN')")
    User createUser(CreateUserRequest request);

    // ---------- itineraries ----------

    @PreAuthorize("hasRole('ADMIN')")
    List<Itinerary> listItineraries(Long userId);

    @PreAuthorize("hasRole('ADMIN')")
    Itinerary createItinerary(Long userId, AdminItineraryRequest request);

    // the same edit a traveller makes (ItineraryService.updateItinerary): a new name or dates clear
    // the whole trip verdicts, and a new country clears the activity ones as well, unless the request
    // keeps them. what was cleared comes back in the result for the audit log
    @PreAuthorize("hasRole('ADMIN')")
    AdminEdit<Itinerary> updateItinerary(Long itineraryId, AdminItineraryRequest request);

    // also removes its activities, impacts, alerts and recommendations.
    // gives back the deleted itinerary so the caller can record what went
    @PreAuthorize("hasRole('ADMIN')")
    Itinerary deleteItinerary(Long itineraryId);

    // ---------- activities ----------

    @PreAuthorize("hasRole('ADMIN')")
    List<Activity> listActivities(Long itineraryId);

    // every activity on all of one account's itineraries, so the console loads them in one request
    @PreAuthorize("hasRole('ADMIN')")
    List<Activity> listUserActivities(Long userId);

    @PreAuthorize("hasRole('ADMIN')")
    Activity createActivity(Long itineraryId, AdminActivityRequest request);

    // same as above through ActivityService.updateActivity: a new name, address or start clears
    // the verdicts on this activity
    @PreAuthorize("hasRole('ADMIN')")
    AdminEdit<Activity> updateActivity(Long activityId, AdminActivityRequest request);

    // also removes the impacts on it, and their alerts and recommendations
    @PreAuthorize("hasRole('ADMIN')")
    Activity deleteActivity(Long activityId);

    // ---------- impacts ----------

    // newest first
    @PreAuthorize("hasRole('ADMIN')")
    List<Impact> listImpacts(Long userId);

    // the itinerary must belong to the user, and the activity (if any) to the itinerary
    @PreAuthorize("hasRole('ADMIN')")
    Impact createImpact(Long userId, AdminImpactRequest request);

    @PreAuthorize("hasRole('ADMIN')")
    Impact updateImpact(Long impactId, AdminImpactUpdateRequest request);

    // also removes its alert and recommendations
    @PreAuthorize("hasRole('ADMIN')")
    Impact deleteImpact(Long impactId);

    // ---------- recommendations ----------

    // every attempt on every impact of the user, dismissed ones too, newest first
    @PreAuthorize("hasRole('ADMIN')")
    List<Recommendation> listRecommendations(Long userId);

    @PreAuthorize("hasRole('ADMIN')")
    Recommendation createRecommendation(Long impactId, AdminRecommendationRequest request);

    @PreAuthorize("hasRole('ADMIN')")
    Recommendation updateRecommendation(Long recommendationId, AdminRecommendationRequest request);

    @PreAuthorize("hasRole('ADMIN')")
    Recommendation deleteRecommendation(Long recommendationId);
}
