package csd.tripsense.impact;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import csd.tripsense.activity.ActivityEditedEvent;
import csd.tripsense.itinerary.ItineraryEditedEvent;

// once the details a verdict was judged on change, the verdict is about plans the traveller no
// longer has. its alerts and recommendations go with it, since they were about the old details.
//
// which verdicts are out of date comes from the revisions: the edit has already raised the trip's
// revision (and each activity's when the country changed, see ItineraryServiceImpl), so a verdict
// whose judged revision no longer matches its trip or activity is stale. a verdict that matches
// is left alone, even if it was written after an earlier edit, so a second edit to the same trip
// never throws away the fresh verdicts the first one paid the model for.
//
// a plain listener and not an after commit one, so the delete is part of the edit's transaction:
// the traveller never sees a verdict about their old plans next to their new ones, and if the
// edit fails nothing is lost. NewItemEvaluationListener judges the missing pairs after the commit
@Component
public class StaleVerdictCleaner {

    private static final Logger log = LoggerFactory.getLogger(StaleVerdictCleaner.class);

    private final ImpactRepository impacts;

    public StaleVerdictCleaner(ImpactRepository impacts) {
        this.impacts = impacts;
    }

    // a renamed or rescheduled trip only makes its whole trip verdicts stale, since activity
    // verdicts were judged on the activity's own details. a new country makes the activity
    // verdicts stale too, and the revisions already say so.
    // transactional so the find and the cascading delete share one session, it joins the edit's
    @EventListener
    @Transactional
    public void onItineraryEdited(ItineraryEditedEvent event) {
        List<Impact> onTrip = impacts.findByItineraryId(event.itineraryId());
        int cleared = clearOutOfDate(onTrip);
        log.info("Itinerary {} edited: cleared {} verdicts judged on the old details",
                event.itineraryId(), cleared);
    }

    @EventListener
    @Transactional
    public void onActivityEdited(ActivityEditedEvent event) {
        List<Impact> forActivity = impacts.findByActivityId(event.activityId());
        int cleared = clearOutOfDate(forActivity);
        log.info("Activity {} edited: cleared {} verdicts judged on the old details",
                event.activityId(), cleared);
    }

    private int clearOutOfDate(List<Impact> verdicts) {
        List<Impact> outOfDate = verdicts.stream()
                .filter(verdict -> !verdict.isJudgedOnCurrentDetails())
                .toList();

        if (outOfDate.isEmpty()) {
            return 0;
        }
        impacts.deleteAll(outOfDate);
        return outOfDate.size();
    }
}
