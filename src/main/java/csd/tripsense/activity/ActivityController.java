package csd.tripsense.activity;

import java.util.List;
import csd.tripsense.auth.CurrentUser;
import csd.tripsense.itinerary.Itinerary;
import csd.tripsense.itinerary.ItineraryAccess;
import csd.tripsense.itinerary.ItineraryService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class ActivityController {
    private final ActivityService activityService;
    private final ItineraryService itineraries;
    private final ItineraryAccess access;

    public ActivityController(ActivityService activityService, ItineraryService itineraries, ItineraryAccess access) {
        this.activityService = activityService;
        this.itineraries = itineraries;
        this.access = access;
    }

    @GetMapping("/activity")
    public List<ActivityResponse> getAllActivities(@AuthenticationPrincipal CurrentUser me) {
        access.requireSelf(me == null ? null : me.getId(), me);
        return activityService.getAllActivities().stream()
                .filter(a -> access.canAccess(a.getItinerary(), me)).map(ActivityResponse::from).toList();
    }

    private Activity owned(Long id, CurrentUser me) {
        Activity activity = activityService.getActivityById(id);
        if (!access.canAccess(activity.getItinerary(), me)) throw new ActivityNotFoundException(id);
        return activity;
    }

    private Itinerary destination(Activity activity, CurrentUser me) {
        if (activity.getItinerary() == null || activity.getItinerary().getId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "itinerary id is required");
        }
        return access.requireAccess(itineraries.getItineraryById(activity.getItinerary().getId()), me);
    }

    @GetMapping("/activity/{id}")
    public ActivityResponse getActivityById(@PathVariable Long id, @AuthenticationPrincipal CurrentUser me) {
        return ActivityResponse.from(owned(id, me));
    }

    @GetMapping("/itinerary/{itineraryId}/activity")
    public List<ActivityResponse> getActivitiesByItinerary(@PathVariable Long itineraryId,
                                                          @AuthenticationPrincipal CurrentUser me) {
        access.requireAccess(itineraries.getItineraryById(itineraryId), me);
        return activityService.getActivitiesByItinerary(itineraryId).stream().map(ActivityResponse::from).toList();
    }

    @PostMapping("/activity")
    public ActivityResponse addActivity(@RequestBody Activity activity, @AuthenticationPrincipal CurrentUser me) {
        activity.setItinerary(destination(activity, me));
        activity.setId(null);
        return ActivityResponse.from(activityService.addActivity(activity));
    }

    @PutMapping("/activity/{id}")
    public ActivityResponse updateActivity(@PathVariable Long id, @RequestBody Activity changes,
                                           @AuthenticationPrincipal CurrentUser me) {
        Activity existing = owned(id, me);
        Itinerary target = changes.getItinerary() == null ? existing.getItinerary() : destination(changes, me);
        if (!existing.getItinerary().getId().equals(target.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "activities cannot be moved between itineraries; create a new activity instead");
        }
        changes.setItinerary(target);
        return ActivityResponse.from(activityService.updateActivity(id, changes));
    }

    @DeleteMapping("/activity/{id}")
    public void deleteActivity(@PathVariable Long id, @AuthenticationPrincipal CurrentUser me) {
        owned(id, me);
        activityService.deleteActivity(id);
    }
}
