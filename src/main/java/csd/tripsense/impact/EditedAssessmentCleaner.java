package csd.tripsense.impact;

import csd.tripsense.activity.*;
import csd.tripsense.itinerary.*;
import csd.tripsense.alert.*;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Deletes obsolete verdicts and dependent advice/alerts in the edit transaction. */
@Component
public class EditedAssessmentCleaner {
    private final ImpactRepository impacts;
    private final PendingImpactEvaluationRepository tasks;
    private final ActivityRepository activities;
    private final ItineraryRepository trips;
    private final org.springframework.jdbc.core.JdbcTemplate sql;
    private final ApplicationEventPublisher events;
    public EditedAssessmentCleaner(ImpactRepository impacts, PendingImpactEvaluationRepository tasks,
            ActivityRepository activities, ItineraryRepository trips,
            org.springframework.jdbc.core.JdbcTemplate sql, ApplicationEventPublisher events) {
        this.impacts=impacts; this.tasks=tasks; this.activities=activities; this.trips=trips;
        this.sql=sql; this.events=events;
    }
    @EventListener @Transactional
    public void itineraryEdited(ItineraryEditedEvent event) {
        var trip=trips.findById(event.itineraryId()).orElseThrow();
        clear(impacts.findByItineraryId(trip.getId()));
        tasks.deleteByTargetIdAndActivity(trip.getId(),false);
        // Country edits supersede snapshots queued under the old country, including activity tasks.
        for (var activity : activities.findByItineraryIdOrderByDateStart(trip.getId()))
            tasks.deleteByTargetIdAndActivity(activity.getId(),true);
        events.publishEvent(new AlertsChangedEvent(trip.getUser().getId()));
    }
    @EventListener @Transactional
    public void activityEdited(ActivityEditedEvent event) {
        var activity=activities.findById(event.activityId()).orElseThrow();
        clear(impacts.findByActivityId(activity.getId()));
        tasks.deleteByTargetIdAndActivity(activity.getId(),true);
        events.publishEvent(new AlertsChangedEvent(activity.getItinerary().getUser().getId()));
    }
    private void clear(List<Impact> candidates) {
        for (Impact impact : candidates) {
            if (impact.judgedOnCurrentDetails()) continue;
            // Re-evaluated details need a new delivery identity and assessment progress.
            Long trip=impact.getItinerary().getId(), news=impact.getNews().getId();
            sql.update("insert into alert_email_group(id,itinerary_id,news_id,delivery_key,delivered,partial) "
                    + "values(?,?,?,?,0,1) on conflict(id) do update set delivery_key=excluded.delivery_key, "
                    + "delivered=0, partial=1, sent_at=null", AlertEmailGroup.key(trip,news), trip,news,
                    java.util.UUID.randomUUID().toString());
            sql.update("delete from evaluation_target where itinerary_id=? and news_id=? and activity_id is ?",
                    trip,news,impact.getActivity()==null ? null : impact.getActivity().getId());
            impacts.delete(impact);
        }
        impacts.flush();
    }
}
