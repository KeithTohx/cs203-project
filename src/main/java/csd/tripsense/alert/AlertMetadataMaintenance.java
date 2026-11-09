package csd.tripsense.alert;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Cleans alert-owned metadata only; does not modify trips, assessments, or evaluation queue tasks. */
@Component
@org.springframework.context.annotation.DependsOn("alertSchemaMigration")
public class AlertMetadataMaintenance {
    private final JdbcTemplate sql;
    public AlertMetadataMaintenance(JdbcTemplate sql) { this.sql=sql; }
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void prepare() { clean(); }
    @Scheduled(scheduler="alertTaskScheduler",fixedDelay=86400000,initialDelay=86400000)
    @Transactional
    public void clean() {
        sql.update("update alert_email_group set itinerary_id=cast(substr(id,1,instr(id,':')-1) as integer), "
                + "news_id=cast(substr(id,instr(id,':')+1) as integer) where itinerary_id is null");
        // Backfill terminal state for groups created before delivered was recorded on the group itself.
        sql.update("update alert_email_group set delivered=1, sent_at=coalesce(sent_at,"
                + "(select max(a.email_sent_at) from alert a join impact i on i.id=a.impact_id "
                + "where i.itinerary_id=alert_email_group.itinerary_id and i.news_id=alert_email_group.news_id)) "
                + "where (delivered is null or delivered=0) and exists "
                + "(select 1 from alert a join impact i on i.id=a.impact_id "
                + "where i.itinerary_id=alert_email_group.itinerary_id and i.news_id=alert_email_group.news_id "
                + "and (a.email_status='SENT' or a.email_status='SUPPRESSED'))");
        for (String table:new String[]{"alert_email_group","evaluation_batch","evaluation_target"}) {
            sql.update("delete from "+table+" where not exists (select 1 from itinerary i where i.id="+table+".itinerary_id)"
                    + " or not exists (select 1 from news n where n.id="+table+".news_id)");
        }
        sql.update("delete from evaluation_target where activity_id is not null and not exists "
                + "(select 1 from activity a where a.id=evaluation_target.activity_id and a.itinerary_id=evaluation_target.itinerary_id)");
        sql.update("update evaluation_batch set finished_at=lease_until, partial=1 where finished_at is null "
                + "and lease_until < ?",Timestamp.from(Instant.now()));
        Timestamp cutoff=Timestamp.from(Instant.now().minusSeconds(30L*86400));
        sql.update("delete from evaluation_batch where finished_at is not null and target_keys is not null and finished_at < ?",cutoff);
        sql.update("delete from evaluation_target where resolved=1 and updated_at < ?",cutoff);
    }
}
