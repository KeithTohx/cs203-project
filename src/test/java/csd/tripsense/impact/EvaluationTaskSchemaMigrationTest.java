package csd.tripsense.impact;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import static org.assertj.core.api.Assertions.assertThat;

class EvaluationTaskSchemaMigrationTest {
    @Test void upgradePreservesLegacyWorkAndCleansOrphansAndCascadedDeletes() {
        try (var data = new SingleConnectionDataSource("jdbc:sqlite::memory:", true)) {
            var sql = new JdbcTemplate(data);
            sql.execute("pragma foreign_keys=on");
            sql.execute("create table itinerary(id integer primary key)");
            sql.execute("create table activity(id integer primary key, itinerary_id integer "
                    + "references itinerary(id) on delete cascade)");
            sql.execute("create table pending_impact_evaluation(task_key text primary key, "
                    + "target_id integer not null, activity boolean not null, article_ids text, cursor integer)");
            sql.execute("insert into itinerary values(1), (2)");
            sql.execute("insert into activity values(1,1), (2,2)");
            // Legacy logical keys and partially completed snapshots need no rewrite on upgrade.
            sql.execute("insert into pending_impact_evaluation values "
                    + "('trip:1',1,0,'11,12',1), ('activity:1',1,1,'11',0), "
                    + "('trip:2',2,0,'22',0), ('activity:2',2,1,'22',0), "
                    + "('trip:99',99,0,'99',0), ('activity:99',99,1,'99',0)");

            EvaluationTaskSchemaMigration.migrate(sql);
            EvaluationTaskSchemaMigration.migrate(sql);

            assertThat(sql.queryForList("select task_key from pending_impact_evaluation order by task_key", String.class))
                    .containsExactly("activity:1", "activity:2", "trip:1", "trip:2");
            assertThat(sql.queryForObject("select cursor from pending_impact_evaluation where task_key='trip:1'", Integer.class))
                    .isEqualTo(1);
            sql.update("delete from itinerary where id=1");
            assertThat(sql.queryForList("select task_key from pending_impact_evaluation order by task_key", String.class))
                    .containsExactly("activity:2", "trip:2");
        }
    }
}
