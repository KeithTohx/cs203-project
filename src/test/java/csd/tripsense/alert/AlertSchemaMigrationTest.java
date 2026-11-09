package csd.tripsense.alert;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;

class AlertSchemaMigrationTest {
    @Test void repeatedMigrationPreservesRowsIndexesAndTheIdentitySequence() {
        try (var data = new SingleConnectionDataSource("jdbc:sqlite::memory:", true)) {
            var sql = new JdbcTemplate(data);
            sql.execute("create table alert (alert_id integer primary key autoincrement, email_status text not null "
                    + "check(email_status in ('PENDING','SENDING','SENT','FAILED')), email_suppressed boolean, email_sent_at timestamp)");
            sql.execute("create index alert_status_test on alert(email_status)");
            sql.update("insert into alert(alert_id,email_status,email_suppressed) values(7,'PENDING',1)");
            sql.update("insert into alert(alert_id,email_status) values(100,'SENT')");
            sql.update("delete from alert where alert_id=100");
            var tx = new TransactionTemplate(new DataSourceTransactionManager(data));
            tx.executeWithoutResult(status -> AlertSchemaMigration.migrate(sql));
            tx.executeWithoutResult(status -> AlertSchemaMigration.migrate(sql));
            assertThat(sql.queryForObject("select email_status from alert where alert_id=7", String.class)).isEqualTo("SUPPRESSED");
            assertThat(sql.queryForObject("select count(*) from sqlite_master where name='alert_status_test'", Integer.class)).isEqualTo(1);
            sql.update("insert into alert(email_status) values('PENDING')");
            assertThat(sql.queryForObject("select max(alert_id) from alert", Integer.class)).isEqualTo(101);
            assertThat(sql.queryForObject("select count(*) from alert_schema_history", Integer.class)).isEqualTo(1);
        }
    }
}
