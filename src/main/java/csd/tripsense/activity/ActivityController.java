package csd.tripsense.activity;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController 
@RequestMapping("/api")
public class ActivityController {
    private ActivityService activityService;

    public ActivityController(ActivityService activityService) {
        this.activityService = activityService;
    }

    @GetMapping("/activity")
    public List<ActivityResponse> getAllActivities() {
        return activityService.getAllActivities().stream().map(ActivityResponse::from).toList();
    }

    @GetMapping("/activity/{id}")
    public ActivityResponse getActivityById(@PathVariable Long id) {
        try {
            return ActivityResponse.from(activityService.getActivityById(id));
        } catch (ActivityNotFoundException e) {
            return null;
        }
    }
    
    @GetMapping("/itinerary/{itineraryId}/activity")
    public List<ActivityResponse> getActivitiesByItinerary(@PathVariable Long itineraryId) {
        try {
            return activityService.getActivitiesByItinerary(itineraryId).stream().map(ActivityResponse::from).toList();
        } catch (ActivityNotFoundException e) {
            return null;
        }
    }

    @PostMapping("/activity")
    public ActivityResponse addActivity(@RequestBody Activity activity) {
        return ActivityResponse.from(activityService.addActivity(activity));
    }
    
    @PutMapping("/activity/{id}")
    public ActivityResponse updateActivity(@PathVariable Long id, @RequestBody Activity newActivityInfo) {
        return ActivityResponse.from(activityService.updateActivity(id, newActivityInfo));
    }
    
    @DeleteMapping("/activity/{id}")
    public void deleteActivity(@PathVariable Long id){
        activityService.deleteActivity(id);
    }
}
