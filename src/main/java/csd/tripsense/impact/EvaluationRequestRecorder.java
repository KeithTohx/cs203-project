package csd.tripsense.impact;

import csd.tripsense.activity.*;
import csd.tripsense.itinerary.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Joins the source transaction: either both the source and its intent commit, or neither does. */
@Component
public class EvaluationRequestRecorder {
    private final PendingEvaluationRequestRepository requests;
    private final boolean enabled;
    private final PendingImpactEvaluationRepository tasks;
    private final csd.tripsense.activity.ActivityRepository activities;
    public EvaluationRequestRecorder(PendingEvaluationRequestRepository requests, PendingImpactEvaluationRepository tasks,
            csd.tripsense.activity.ActivityRepository activities,
            @Value("${impact.eval.new-itinerary.enabled:true}") boolean enabled) {
        this.requests = requests; this.tasks=tasks; this.activities=activities;
        this.enabled = enabled;
    }
    @EventListener @Transactional
    public void itineraryCreated(ItineraryCreatedEvent event) { record(event.itineraryId(), false); }
    @EventListener @Transactional
    public void activityCreated(ActivityCreatedEvent event) { record(event.activityId(), true); }
    @EventListener @Transactional
    public void itineraryEdited(ItineraryEditedEvent event) {
        tasks.deleteByTargetIdAndActivity(event.itineraryId(),false);
        for (var activity : activities.findByItineraryIdOrderByDateStart(event.itineraryId()))
            tasks.deleteByTargetIdAndActivity(activity.getId(),true);
        record(event.itineraryId(), false);
    }
    @EventListener @Transactional
    public void activityEdited(ActivityEditedEvent event) {
        tasks.deleteByTargetIdAndActivity(event.activityId(),true);
        record(event.activityId(), true);
    }
    @EventListener @Transactional
    public void verdictsCleared(VerdictsClearedEvent event) {
        if (!enabled) return;
        event.newsIdsByTrip().forEach((id,articles) -> include(id,false,articles));
        event.newsIdsByActivity().forEach((id,articles) -> include(id,true,articles));
    }
    private void include(Long id,boolean activity,java.util.Set<Long> articles) {
        var request=requests.findById(PendingEvaluationRequest.key(id,activity))
                .orElseGet(() -> new PendingEvaluationRequest(id,activity));
        request.include(articles); requests.save(request);
    }
    private void record(Long id, boolean activity) {
        if (enabled) {
            var existing=requests.findById(PendingEvaluationRequest.key(id,activity));
            var request=new PendingEvaluationRequest(id,activity);
            existing.ifPresent(old -> request.include(old.extraArticles()));
            requests.save(request);
        }
    }
}
