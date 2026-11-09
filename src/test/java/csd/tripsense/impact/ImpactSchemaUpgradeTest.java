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
                sql.execute("""
                        create table alert (alert_id integer primary key, impact_id bigint not null unique,
                        created_at timestamp not null, read_at timestamp, email_status varchar(255) not null
                        check (email_status in ('PENDING','SENDING','SENT','FAILED')), email_attempts bigint not null,
                        email_next_attempt_at timestamp not null, email_sent_at timestamp,
                        email_delivery_key varchar(255) not null, email_suppressed boolean)
                        """);
                sql.execute("insert into alert(alert_id,impact_id,created_at,email_status,email_attempts,"
                        + "email_next_attempt_at,email_delivery_key) values(7,41,0,'PENDING',0,0,'legacy-key')");
                sql.execute("insert into alert(alert_id,impact_id,created_at,email_status,email_attempts,"
                        + "email_next_attempt_at,email_delivery_key,email_suppressed) values(8,42,0,'PENDING',0,0,'suppressed-key',1)");
            }
            return path;
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE);
    }
    @Autowired JdbcTemplate sql;
    @Autowired csd.tripsense.alert.AlertRepository alerts;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

    @Test void suppressionWorksWithTheOriginalSqliteStatusConstraint() {
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status ->
                alerts.suppressEmail(7L, 0, java.util.List.of(csd.tripsense.alert.EmailStatus.PENDING)));
        var alert = alerts.findById(7L).orElseThrow();
        assertThat(alert.getEmailStatus()).isEqualTo(csd.tripsense.alert.EmailStatus.SUPPRESSED);
        assertThat(alert.getEmailSentAt()).isNull();
        assertThat(sql.queryForObject("select email_status from alert where alert_id=7", String.class))
                .isEqualTo("SUPPRESSED");
        assertThat(sql.queryForObject("select sql from sqlite_master where name='alert'", String.class))
                .contains("SUPPRESSED");
    }


    @Test void upgradeConvertsTheLegacyMarkerAndCanRunAgain() {
        assertThat(sql.queryForObject("select email_status from alert where alert_id=8", String.class))
                .isEqualTo("SUPPRESSED");
        assertThat(sql.queryForList("pragma table_info(alert)").stream()
                .anyMatch(c -> "email_suppressed".equals(c.get("name")))).isFalse();
        assertThat(sql.queryForObject("select count(*) from alert_schema_history where version=1", Integer.class)).isEqualTo(1);
    }

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
