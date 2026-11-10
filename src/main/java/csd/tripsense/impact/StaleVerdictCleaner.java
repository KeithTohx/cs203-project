package csd.tripsense.impact;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import csd.tripsense.activity.ActivityEditedEvent;
import csd.tripsense.alert.AlertsRemovedEvent;
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
// edit fails nothing is lost. NewItemEvaluationListener judges the missing pairs after the commit.
// it only reads recent news by itself, so the articles cleared here are handed to it as well,
// otherwise a verdict on an older article would be deleted and never judged again
@Component
public class StaleVerdictCleaner {

    private static final Logger log = LoggerFactory.getLogger(StaleVerdictCleaner.class);

    private final ImpactRepository impacts;
    private final ApplicationEventPublisher events;

    public StaleVerdictCleaner(ImpactRepository impacts, ApplicationEventPublisher events) {
        this.impacts = impacts;
        this.events = events;
    }

    // a renamed or rescheduled trip only makes its whole trip verdicts stale, since activity
    // verdicts were judged on the activity's own details. a new country makes the activity
    // verdicts stale too, and the revisions already say so.
    // transactional so the find and the cascading delete share one session, it joins the edit's
    @EventListener
    @Transactional
    public void onItineraryEdited(ItineraryEditedEvent event) {
        List<Impact> onTrip = impacts.findByItineraryId(event.itineraryId());
        if (event.keepVerdicts()) {
            int kept = carryOver(onTrip);
            log.info("Itinerary {} edited: kept {} verdicts judged on the old details",
                    event.itineraryId(), kept);
            return;
        }

        int cleared = clearOutOfDate(onTrip);
        log.info("Itinerary {} edited: cleared {} verdicts judged on the old details",
                event.itineraryId(), cleared);
    }

    @EventListener
    @Transactional
    public void onActivityEdited(ActivityEditedEvent event) {
        List<Impact> forActivity = impacts.findByActivityId(event.activityId());
        if (event.keepVerdicts()) {
            int kept = carryOver(forActivity);
            log.info("Activity {} edited: kept {} verdicts judged on the old details",
                    event.activityId(), kept);
            return;
        }

        int cleared = clearOutOfDate(forActivity);
        log.info("Activity {} edited: cleared {} verdicts judged on the old details",
                event.activityId(), cleared);
    }

    // the edit was too small to change any verdict, so the out of date ones are marked as judged on
    // the new details. the verdict key stays, since the verdict itself has not changed and advice
    // drafted from it still holds. the managed rows are saved when the edit commits
    private static int carryOver(List<Impact> verdicts) {
        List<Impact> outOfDate = verdicts.stream()
                .filter(verdict -> !verdict.isJudgedOnCurrentDetails())
                .toList();

        for (Impact verdict : outOfDate) {
            verdict.setJudgedRevision(currentRevisionOf(verdict));
        }
        return outOfDate.size();
    }

    // the revision a verdict has to carry to count as current, see Impact.isJudgedOnCurrentDetails
    private static long currentRevisionOf(Impact verdict) {
        if (verdict.getActivity() != null) {
            return verdict.getActivity().currentRevision();
        }
        return verdict.getItinerary().currentRevision();
    }

    private int clearOutOfDate(List<Impact> verdicts) {
        List<Impact> outOfDate = verdicts.stream()
                .filter(verdict -> !verdict.isJudgedOnCurrentDetails())
                .toList();

        if (outOfDate.isEmpty()) {
            return 0;
        }

        // worked out before the delete, while the alerts are still there to look at
        Set<Long> ownersLosingAlerts = outOfDate.stream()
                .filter(Impact::hasAlerts)
                .map(Impact::ownerId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        VerdictsClearedEvent cleared = articlesBehind(outOfDate);

        impacts.deleteAll(outOfDate);

        // only acted on once the edit has committed, so nothing is judged again if the edit fails
        events.publishEvent(cleared);

        // the stream service only sends this once the edit has committed. the new verdicts may say
        // unaffected, and then no new alert would ever arrive to make the browser reload its list
        for (Long ownerId : ownersLosingAlerts) {
            events.publishEvent(new AlertsRemovedEvent(ownerId));
        }
        return outOfDate.size();
    }

    // which articles each trip or activity had a verdict on. an activity verdict goes under the
    // activity, not its trip, since the activity may have moved to another trip in the edit
    private static VerdictsClearedEvent articlesBehind(List<Impact> verdicts) {
        Map<Long, Set<Long>> newsIdsByTrip = new HashMap<>();
        Map<Long, Set<Long>> newsIdsByActivity = new HashMap<>();

        for (Impact verdict : verdicts) {
            Long newsId = verdict.getNews().getId();
            if (verdict.getActivity() != null) {
                Long activityId = verdict.getActivity().getId();
                newsIdsByActivity.computeIfAbsent(activityId, id -> new HashSet<>()).add(newsId);
            } else if (verdict.getItinerary() != null) {
                Long itineraryId = verdict.getItinerary().getId();
                newsIdsByTrip.computeIfAbsent(itineraryId, id -> new HashSet<>()).add(newsId);
            }
        }
        return new VerdictsClearedEvent(newsIdsByTrip, newsIdsByActivity);
    }
}
