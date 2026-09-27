package csd.tripsense.activity;

import java.util.List;

import org.springframework.stereotype.Service;

@Service 
public class ActivityServiceImpl implements ActivityService {
    private ActivityRepository activities;

    public ActivityServiceImpl(ActivityRepository activities) {
        this.activities = activities;
    }

    @Override 
    public List<Activity> getAllActivities() {
        return activities.findAll();
    }

    @Override 
    public Activity getActivityById(Long id) {
        return activities.findById(id).map(activity -> {
            return activity;
        }).orElseThrow(() -> new ActivityNotFoundException(id));
    }

    @Override 
    public List<Activity> getActivitiesByItinerary(long itineraryId) {
        return (List<Activity>) activities.findById(itineraryId).map(itineraryActivities -> {
            return itineraryActivities;
        }).orElseThrow(() -> new ActivityNotFoundException("Itinerary (ID: " + itineraryId + " has no activities."));
    }

    @Override 
    public Activity addActivity(Activity activity) {
        return activities.save(activity);
    }

    @Override 
    public Activity updateActivity(Long id, Activity newActivityInfo) {
        return activities.findById(id).map(activity -> {
            activity.setItinerary(newActivityInfo.getItinerary());
            activity.setName(newActivityInfo.getName());
            activity.setAddress(newActivityInfo.getAddress());
            activity.setDateStart(newActivityInfo.getDateStart());
            activity.setDateEnd(newActivityInfo.getDateEnd());
            return activities.save(activity);
        }).orElseThrow(() -> new ActivityNotFoundException(id));
    }

    @Override 
    public void deleteActivity(Long id){
        if (!activities.existsById(id)) {
            throw new ActivityNotFoundException(id);
        }
        activities.deleteById(id);
    }
}
