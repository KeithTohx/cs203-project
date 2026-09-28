package csd.tripsense.activity;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;


@RestController 
public class ActivityController {
    private ActivityService activityService;

    public ActivityController(ActivityService activityService) {
        this.activityService = activityService;
    }

    @GetMapping("/api/activity")
    public List<Activity> getAllActivities() {
        return activityService.getAllActivities();
    }

    @GetMapping("/api/activity/{id}")
    public Activity getActivityById(@PathVariable Long id) {
        try {
            return activityService.getActivityById(id);
        } catch (ActivityNotFoundException e) {
            return null;
        }
    }
    
    @GetMapping("/api/itinerary/{itineraryId}/activity")
    public List<Activity> getActivitiesByItinerary(@PathVariable Long itineraryId) {
        try {
            return activityService.getActivitiesByItinerary(itineraryId);
        } catch (ActivityNotFoundException e) {
            return null;
        }
    }

    @PostMapping("/api/activity")
    public Activity addActivity(@RequestBody Activity activity) {
        return activityService.addActivity(activity);
    }
    
    @PutMapping("/api/activity/{id}")
    public Activity updateActivity(@PathVariable Long id, @RequestBody Activity newActivityInfo) {
        return activityService.updateActivity(id, newActivityInfo);
    }
    
    @DeleteMapping("/api/activity/{id}")
    public void deleteActivity(@PathVariable Long id){
        activityService.deleteActivity(id);
    }
}
