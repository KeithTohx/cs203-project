package csd.tripsense.impact;

import csd.tripsense.alert.AlertEmailGroup;
import java.util.List;
import org.springframework.stereotype.Component;

/** Resets delivery metadata and superseded work before stale verdicts are deleted. */
@Component
public class EditedAssessmentCleaner {
    private final PendingImpactEvaluationRepository tasks;
    private final org.springframework.jdbc.core.JdbcTemplate sql;
    public EditedAssessmentCleaner(PendingImpactEvaluationRepository tasks, org.springframework.jdbc.core.JdbcTemplate sql) {
        this.tasks=tasks; this.sql=sql;
    }
    public void prepare(List<Impact> candidates) {
        for (Impact impact : candidates) {
            if (impact.judgedOnCurrentDetails()) continue;
            // Re-evaluated details need a new delivery identity and assessment progress.
            Long trip=impact.getItinerary().getId(), news=impact.getNews().getId();
            tasks.deleteByTargetIdAndActivity(trip,false);
            if (impact.getActivity()!=null) tasks.deleteByTargetIdAndActivity(impact.getActivity().getId(),true);
            sql.update("insert into alert_email_group(id,itinerary_id,news_id,delivery_key,delivered,partial) "
                    + "values(?,?,?,?,0,1) on conflict(id) do update set delivery_key=excluded.delivery_key, "
                    + "delivered=0, partial=1, sent_at=null", AlertEmailGroup.key(trip,news), trip,news,
                    java.util.UUID.randomUUID().toString());
            sql.update("delete from evaluation_target where itinerary_id=? and news_id=? and activity_id is ?",
                    trip,news,impact.getActivity()==null ? null : impact.getActivity().getId());
        }
    }
}
