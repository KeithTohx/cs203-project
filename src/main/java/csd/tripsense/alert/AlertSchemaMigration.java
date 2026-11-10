package csd.tripsense.alert;

import jakarta.persistence.EntityManagerFactory;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Transactional SQLite upgrades for email statuses and non-reusable notification ids. */
@Component
public class AlertSchemaMigration {
    public AlertSchemaMigration(EntityManagerFactory entities, JdbcTemplate sql,
                                PlatformTransactionManager transactions) {
        entities.getMetamodel().entity(Alert.class); // Hibernate first creates any missing columns/tables.
        new TransactionTemplate(transactions).executeWithoutResult(tx -> migrate(sql));
    }

    static void migrate(JdbcTemplate sql) {
        var tables = sql.queryForList("select sql from sqlite_master where type='table' and name='alert'", String.class);
        if (tables.isEmpty()) return;
        String ddl = tables.getFirst();
        sql.execute("create table if not exists alert_schema_history (version integer primary key)");
        var status = Pattern.compile("(?i)(email_status\\s+in\\s*\\()([^)]*)(\\))").matcher(ddl);
        String upgraded = status.find() && !status.group(2).contains("SUPPRESSED")
                ? status.replaceFirst("$1$2,'SUPPRESSED'$3") : ddl;
        boolean hadSequence = Pattern.compile("(?i)\\bautoincrement\\b").matcher(ddl).find();
        if (!hadSequence) {
            // Hibernate's SQLite identity DDL uses a table-level primary key. Older
            // databases may use an inline key. Both must become INTEGER PRIMARY KEY AUTOINCREMENT.
            upgraded = upgraded.replaceFirst("(?i),\\s*primary\\s+key\\s*\\(\\s*\"?alert_id\"?\\s*\\)", "");
            upgraded = upgraded.replaceFirst("(?i)(\"?alert_id\"?\\s+integer)(?:\\s+(?:not\\s+null\\s+)?primary\\s+key)?",
                    "$1 primary key autoincrement");
        }
        if (!upgraded.equals(ddl)) {
            upgraded = upgraded.replaceFirst("(?i)CREATE TABLE\\s+(?:\"alert\"|alert)", "CREATE TABLE alert_upgrade");
            var objects = sql.queryForList("select sql from sqlite_master where tbl_name='alert' "
                    + "and type in ('index','trigger') and sql is not null", String.class);
            Long sequence = hadSequence
                    ? sql.queryForObject("select coalesce(max(seq),0) from sqlite_sequence where name='alert'", Long.class) : null;
            sql.execute(upgraded);
            sql.execute("insert into alert_upgrade select * from alert");
            sql.execute("drop table alert");
            sql.execute("alter table alert_upgrade rename to alert");
            for (String object : objects) sql.execute(object);
            if (sequence != null) sql.update("update sqlite_sequence set seq=max(seq,?) where name='alert'", sequence);
        }
        boolean legacyMarker = sql.queryForList("pragma table_info(alert)").stream()
                .anyMatch(column -> "email_suppressed".equals(column.get("name")));
        if (legacyMarker) {
            sql.update("update alert set email_status='SUPPRESSED', email_sent_at=null where email_suppressed=1");
            sql.execute("alter table alert drop column email_suppressed");
        }
        sql.update("insert or ignore into alert_schema_history(version) values(1),(2)");
    }
}
