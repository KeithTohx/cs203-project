package csd.tripsense.impact;

import csd.tripsense.activity.Activity;
import csd.tripsense.itinerary.Itinerary;
import java.util.function.Supplier;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Validate source lifetime and save under one SQLite write lock, after the AI call. */
@Service
@org.springframework.context.annotation.DependsOn("impactAssessmentIndex")
public class AssessmentSourceGuard {
    private final JdbcTemplate sql;

    public AssessmentSourceGuard(JdbcTemplate sql) { this.sql = sql; }

    public record Source(Long tripId, String tripIdentity, Long activityId, String activityIdentity) {
        static Source capture(Itinerary trip, Activity activity) {
            return new Source(trip.getId(), trip.getAssessmentIdentity(),
                    activity == null ? null : activity.getId(),
                    activity == null ? null : activity.getAssessmentIdentity());
        }
    }

    @Transactional
    public Impact saveIfCurrent(Source source, Supplier<Impact> save) {
        // A conditional write acquires SQLite's writer lock. A delete/reinsert cannot
        // slip between validation and persistence, even on another application instance.
        int trip = sql.update("update itinerary set assessment_identity=assessment_identity "
                + "where id=? and assessment_identity=?", source.tripId(), source.tripIdentity());
        if (trip != 1) throw changed();
        if (source.activityId() != null) {
            int activity = sql.update("update activity set assessment_identity=assessment_identity "
                    + "where id=? and itinerary_id=? and assessment_identity=?",
                    source.activityId(), source.tripId(), source.activityIdentity());
            if (activity != 1) throw changed();
        }
        return save.get();
    }

    private OptimisticLockingFailureException changed() {
        return new OptimisticLockingFailureException("Assessment source was deleted or replaced");
    }
}
