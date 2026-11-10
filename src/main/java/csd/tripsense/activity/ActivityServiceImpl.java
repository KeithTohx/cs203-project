package csd.tripsense.activity;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;

import csd.tripsense.itinerary.Itinerary;
import csd.tripsense.itinerary.ItineraryService;

@Service
public class ActivityServiceImpl implements ActivityService {
    private ActivityRepository activities;
    private ItineraryService itineraryService;
    private ApplicationEventPublisher events;

    public ActivityServiceImpl(ActivityRepository activities, ItineraryService itineraryService,
                              ApplicationEventPublisher events) {
       this.activities = activities;
       this.itineraryService = itineraryService;
       this.events = events;
   }

    @Override
    public List<Activity> getAllActivities() {
        return activities.findAll();
    }

    @Override
    public Activity getActivityById(Long id) {
        return activities.findById(id).orElseThrow(() -> new ActivityNotFoundException(id));
    }

    @Override
    public List<Activity> getActivitiesByItinerary(long itineraryId) {
        return activities.findByItineraryIdOrderByDateStart(itineraryId);
    }

    @Override
    public List<Activity> getActivitiesPendingForNews(Long newsId, Long countryId, LocalDateTime now) {
        return activities.findPendingForNews(newsId, countryId, now);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public Activity addActivity(Activity activity) {
        Itinerary itinerary = itineraryService.getItineraryById(activity.getItinerary().getId());
        activity.setItinerary(itinerary);
        activity.setCountry(itinerary.getCountry());
        Activity saved = activities.save(activity);
        events.publishEvent(new ActivityCreatedEvent(saved.getId()));
        return saved;
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public Activity updateActivity(Long id, Activity newActivityInfo) {
        return activities.findById(id).map(activity -> {
            boolean changed = !java.util.Objects.equals(activity.getName(), newActivityInfo.getName())
                    || !java.util.Objects.equals(activity.getAddress(), newActivityInfo.getAddress())
                    || !java.util.Objects.equals(activity.getDateStart(), newActivityInfo.getDateStart());
            Long oldTrip = activity.getItinerary().getId();
            Itinerary itinerary = newActivityInfo.getItinerary() == null
                    ? activity.getItinerary()
                    : itineraryService.getItineraryById(newActivityInfo.getItinerary().getId());
            activity.setItinerary(itinerary);
            activity.setCountry(itinerary.getCountry());
            activity.setName(newActivityInfo.getName());
            activity.setAddress(newActivityInfo.getAddress());
            activity.setDateStart(newActivityInfo.getDateStart());
            activity.setDateEnd(newActivityInfo.getDateEnd());
            changed |= !java.util.Objects.equals(oldTrip, itinerary.getId());
            if (changed) activity.reviseAssessment();
            Activity saved = activities.save(activity);
            if (changed) events.publishEvent(new ActivityEditedEvent(saved.getId()));
            return saved;
        }).orElseThrow(() -> new ActivityNotFoundException(id));
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public void deleteActivity(Long id) {
        if (!activities.existsById(id)) {
            throw new ActivityNotFoundException(id);
        }
        var removed = activities.findById(id).orElseThrow();
        Long userId = removed.getItinerary().getUser().getId();
        activities.deleteById(id);
        events.publishEvent(new csd.tripsense.alert.AlertsChangedEvent(userId));
    }
}