package csd.tripsense.impact;

import java.util.Map;
import java.util.Set;

// the articles whose verdicts an edit just cleared (see StaleVerdictCleaner), keyed by the trip or
// activity they were about. the usual re-evaluation after an edit only reads recent news, so an older
// article that had been judged, and maybe alerted, would never be judged again without this.
// carries ids only, so the listener reloads everything fresh once the edit has committed
public record VerdictsClearedEvent(Map<Long, Set<Long>> newsIdsByTrip,
                                   Map<Long, Set<Long>> newsIdsByActivity) {

    public boolean isEmpty() {
        return newsIdsByTrip.isEmpty() && newsIdsByActivity.isEmpty();
    }
}
