package csd.tripsense.impact;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import csd.tripsense.activity.ActivityEditedEvent;
import csd.tripsense.itinerary.ItineraryEditedEvent;

// the evaluator reuses any stored verdict for a news and trip pair without asking again, so once
// the details a verdict was judged on change, it has to go before the trip is judged again.
// its alerts and recommendations go with it, since they were about the old details.
//
// a plain listener and not an after commit one, so the delete is part of the edit's transaction:
// the traveller never sees a verdict about their old plans next to their new ones, and if the
// edit fails nothing is lost. NewItemEvaluationListener re-judges the trip after the commit, and
// calls clearFor once more first, see the comment there
@Component
public class StaleVerdictCleaner {

    private static final Logger log = LoggerFactory.getLogger(StaleVerdictCleaner.class);

    private final ImpactRepository impacts;

    public StaleVerdictCleaner(ImpactRepository impacts) {
        this.impacts = impacts;
    }

    @EventListener
    public void onItineraryEdited(ItineraryEditedEvent event) {
        int cleared = clearFor(event);
        log.info("Itinerary {} edited: cleared {} verdicts (country changed: {})",
                event.itineraryId(), cleared, event.countryChanged());
    }

    @EventListener
    public void onActivityEdited(ActivityEditedEvent event) {
        int cleared = clearFor(event);
        log.info("Activity {} edited: cleared {} verdicts", event.activityId(), cleared);
    }

    // a new name or new dates only touch the whole trip verdicts, since activity verdicts were
    // judged on the activity's own name, address and start. a new country clears the activity
    // verdicts too, the same way moving an activity to another trip does, because they are about
    // news from a country the trip is no longer in.
    // transactional so the find and the delete share one session when this runs on the
    // evaluation thread. inside an edit it just joins the edit's transaction
    @Transactional
    public int clearFor(ItineraryEditedEvent event) {
        List<Impact> onTrip = impacts.findByItineraryId(event.itineraryId());

        List<Impact> outOfDate;
        if (event.countryChanged()) {
            outOfDate = onTrip;
        } else {
            outOfDate = onTrip.stream()
                    .filter(impact -> impact.getActivity() == null)
                    .toList();
        }

        return clear(outOfDate);
    }

    @Transactional
    public int clearFor(ActivityEditedEvent event) {
        List<Impact> forActivity = impacts.findByActivityId(event.activityId());
        return clear(forActivity);
    }

    private int clear(List<Impact> outOfDate) {
        if (outOfDate.isEmpty()) {
            return 0;
        }
        impacts.deleteAll(outOfDate);
        return outOfDate.size();
    }
}
