package csd.tripsense.activity;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;


@RestController 
public class ActivityController {
    private ActivityService activityService;

    public ActivityController(ActivityService activityService) {
        this.activityService = activityService;
    }

    @GetMapping("/activities")
    public List<Activity> getAllActivities() {
        return activityService.getAllActivities();
    }

    @GetMapping("/activities/(id)")
    public Activity getActivityById(@PathVariable Long id) {
        try {
            return activityService.getActivityById(id);
        } catch (ActivityNotFoundException e) {
            return null;
        }
    }
    
     @GetMapping("/itineraries/{id}/activities")
    public List<Activity> getActivitiesByItinerary(@PathVariable Long itineraryId) {
        try {
            return activityService.getActivitiesByItinerary(itineraryId);
        } catch (ActivityNotFoundException e) {
            return null;
        }
    }
}
