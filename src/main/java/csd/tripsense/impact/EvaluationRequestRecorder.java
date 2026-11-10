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
    public EvaluationRequestRecorder(PendingEvaluationRequestRepository requests,
            @Value("${impact.eval.new-itinerary.enabled:true}") boolean enabled) {
        this.requests = requests;
        this.enabled = enabled;
    }
    @EventListener @Transactional
    public void itineraryCreated(ItineraryCreatedEvent event) { record(event.itineraryId(), false); }
    @EventListener @Transactional
    public void activityCreated(ActivityCreatedEvent event) { record(event.activityId(), true); }
    @EventListener @Transactional
    public void itineraryEdited(ItineraryEditedEvent event) { record(event.itineraryId(), false); }
    @EventListener @Transactional
    public void activityEdited(ActivityEditedEvent event) { record(event.activityId(), true); }
    private void record(Long id, boolean activity) {
        if (enabled) requests.save(new PendingEvaluationRequest(id, activity));
    }
}
