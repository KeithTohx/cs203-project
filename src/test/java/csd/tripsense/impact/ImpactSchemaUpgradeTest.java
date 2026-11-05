package csd.tripsense.impact;

import java.nio.file.*;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {
        "gdelt.ingest.enabled=false", "impact.eval.enabled=false", "impact.eval.new-itinerary.enabled=false",
        "alerts.email.enabled=false", "alerts.reconcile.initial-delay-ms=86400000",
        "spring.sql.init.mode=never", "spring.jpa.hibernate.ddl-auto=update"
})
class ImpactSchemaUpgradeTest {
    private static final Path DATABASE = legacyDatabase();
    private static Path legacyDatabase() {
        try {
            Path path = Files.createTempDirectory("tripsense-impact-upgrade-").resolve("legacy.db");
            try (var db = DriverManager.getConnection("jdbc:sqlite:" + path); var sql = db.createStatement()) {
                sql.execute("""
                        create table impact (id integer primary key, activity_id bigint, itinerary_id bigint,
                        news_id bigint not null, user_id bigint not null, impacted boolean, rejected boolean,
                        alert_eligible boolean, reason varchar(500))
                        """);
                sql.execute("insert into impact(id,news_id,user_id,impacted,reason) values(41,1,1,1,'Historical assessment')");
                sql.execute("insert into impact(id,news_id,user_id,impacted,reason) values(42,1,1,1,'Another historical row')");
            }
            return path;
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE);
    }
    @Autowired JdbcTemplate sql;

    @Test void updatePreservesLegacyRowsAndEnforcesNewAssessmentKeys() {
        assertThat(sql.queryForObject("select count(*) from impact", Integer.class)).isEqualTo(2);
        assertThat(sql.queryForObject("select count(*) from impact where assessment_key is null", Integer.class)).isEqualTo(2);
        sql.update("update impact set assessment_key='1:1:trip' where id=41");
        assertThatThrownBy(() -> sql.update("update impact set assessment_key='1:1:trip' where id=42"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(sql.queryForObject("select reason from impact where id=42", String.class)).isEqualTo("Another historical row");
        assertThat(sql.queryForObject("select count(*) from pending_impact_evaluation", Integer.class)).isZero();
    }
}
