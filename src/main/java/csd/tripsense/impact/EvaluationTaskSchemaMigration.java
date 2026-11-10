package csd.tripsense.impact;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Keep pending work tied to the lifetime of its source, including cascading deletes. */
@Component
public class EvaluationTaskSchemaMigration {
    public EvaluationTaskSchemaMigration(EntityManagerFactory entities, JdbcTemplate sql,
                                         PlatformTransactionManager transactions) {
        entities.getMetamodel().entity(PendingImpactEvaluation.class);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> migrate(sql));
    }

    static void migrate(JdbcTemplate sql) {
        sql.execute("create table if not exists pending_evaluation_request "
                + "(id varchar(255) primary key, target_id bigint, activity boolean, requested_at timestamp)");
        for (String table : new String[]{"itinerary", "activity"}) {
            int activity = table.equals("activity") ? 1 : 0;
            sql.execute("create trigger if not exists delete_" + table + "_evaluation_request after delete on "
                    + table + " begin delete from pending_evaluation_request where activity=" + activity
                    + " and target_id=old.id; end");
        }
        sql.update("delete from pending_evaluation_request where "
                + "(activity=0 and not exists (select 1 from itinerary i where i.id=target_id)) "
                + "or (activity=1 and not exists (select 1 from activity a where a.id=target_id))");
        // SQLite's Hibernate dialect can omit unique-index DDL.
        sql.execute("create unique index if not exists uk_pending_evaluation_target "
                + "on pending_impact_evaluation(target_id, activity)");
        sql.execute("create trigger if not exists delete_itinerary_evaluation_task "
                + "after delete on itinerary begin delete from pending_impact_evaluation "
                + "where activity=0 and target_id=old.id; end");
        sql.execute("create trigger if not exists delete_activity_evaluation_task "
                + "after delete on activity begin delete from pending_impact_evaluation "
                + "where activity=1 and target_id=old.id; end");
        // Repair pre-upgrade orphan tasks before new creations can reuse their ids.
        sql.update("delete from pending_impact_evaluation where "
                + "(activity=0 and not exists (select 1 from itinerary i where i.id=target_id)) "
                + "or (activity=1 and not exists (select 1 from activity a where a.id=target_id))");
    }
}
