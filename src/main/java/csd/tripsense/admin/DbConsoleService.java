package csd.tripsense.admin;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.sqlite.SQLiteConfig;

import jakarta.annotation.PreDestroy;

/**
 * Runs read-only SQL for the admin console.
 *
 * Several things stand between a statement and the data, and each one is enough on its own to stop
 * a write:
 * 1. URL rule: /api/admin/** is for ADMIN accounts only (SecurityConfig).
 * 2. @PreAuthorize here repeats the check, like AdminService, so one wrong route cannot expose it.
 * 3. SqlGuard accepts a single SELECT and gives a clear message for anything else.
 * 4. The connection is opened read-only: SQLite itself refuses every write, including a write
 *    hidden inside a WITH. This is the one that actually keeps the database safe.
 * 5. PRAGMA query_only is switched on as well, in case the connection flag ever changes.
 *
 * It also limits what a SELECT can cost: at most MAX_ROWS rows, long cells are cut, and a query
 * that runs longer than the timeout is stopped.
 *
 * It does not use the application's own datasource. That connection is read-write and pooled, and
 * the sqlite driver cannot make an open connection read-only afterwards. A new read-only connection
 * per query is cheap for SQLite, which is only a file.
 */
@Service
public class DbConsoleService {

    static final int MAX_ROWS = 500;
    static final int MAX_CELL_CHARS = 2_000;
    static final String HIDDEN = "[hidden]";

    private static final Logger log = LoggerFactory.getLogger(DbConsoleService.class);

    private final String datasourceUrl;
    private final int timeoutSeconds;

    /** 
     * two at a time, so one admin pasting a slow query cannot starve the rest. 
     * daemon threads so a stuck query can never stop the application shutting down
     */
    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "sql-console");
        thread.setDaemon(true);
        return thread;
    });

    public DbConsoleService(@Value("${spring.datasource.url}") String datasourceUrl,
                             @Value("${tripsense.sql-console.timeout-seconds:5}") int timeoutSeconds) {
        // the url is only checked when a query is run, so an app with a different database (a test
        // with an in-memory one, say) still starts
        this.datasourceUrl = datasourceUrl;
        this.timeoutSeconds = timeoutSeconds;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /** Runs one SELECT. Throws 400 for a refused or invalid statement and 408 for one that ran too long. */
    @PreAuthorize("hasRole('ADMIN')")
    public DbQueryResponse run(String sql) {
        return runWithTimeout(DbGuard.check(sql));
    }

    /**
     * Every table with its row count, for the sidebar. Names come from sqlite_master and are quoted
     * anyway. The tables SQLite keeps for itself (sqlite_sequence and so on) are left out.
     */
    @PreAuthorize("hasRole('ADMIN')")
    public List<DbTableInfo> listTables() {
        try (Connection connection = open(pathFrom(datasourceUrl)); Statement statement = connection.createStatement()) {
            List<String> names = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery("SELECT name FROM sqlite_master "
                    + "WHERE type = 'table' AND name NOT LIKE 'sqlite\\_%' ESCAPE '\\' ORDER BY name")) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }

            List<DbTableInfo> tables = new ArrayList<>();
            for (String name : names) {
                try (ResultSet rs = statement.executeQuery(
                        "SELECT COUNT(*) FROM \"" + name.replace("\"", "\"\"") + "\"")) {
                    tables.add(new DbTableInfo(name, rs.next() ? rs.getLong(1) : 0));
                }
            }
            return tables;
        } catch (SQLException e) {
            log.error("Could not list the tables for the SQL console", e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not read the table list.");
        }
    }

    /**
     * Runs a statement WITHOUT the guard. Package private, and only for a test to prove that the
     * read-only connection refuses writes by itself, with the guard out of the way.
     */
    DbQueryResponse executeUnguarded(String sql) {
        return runWithTimeout(sql);
    }

    private DbQueryResponse runWithTimeout(String sql) {
        String path = pathFrom(datasourceUrl);   // checked here so a bad setup is a clear error, not a failed task

        RunningQuery running = new RunningQuery();
        long started = System.nanoTime();
        Future<DbQueryResponse> future = executor.submit(() -> execute(path, sql, running, started));
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            // interrupt the statement inside SQLite. cancelling the thread alone would not stop it,
            // sqlite does not look at thread interrupts
            running.cancel();
            future.cancel(true);
            throw new ResponseStatusException(HttpStatus.REQUEST_TIMEOUT,
                    "The query ran for more than " + timeoutSeconds + " seconds and was stopped.");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof SQLException sqlError) {
                // the message names the problem, eg "no such table: foo", which is what the admin needs
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, sqlError.getMessage());
            }
            log.error("SQL console query failed unexpectedly", e.getCause());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "The query could not be run.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running.cancel();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "The query was interrupted.");
        }
    }

    private DbQueryResponse execute(String path, String sql, RunningQuery running, long startedNanos)
            throws SQLException {
        try (Connection connection = open(path); Statement statement = connection.createStatement()) {
            running.attach(statement);
            // one more than the cap, so we can tell "exactly 500 rows" from "more than 500"
            statement.setMaxRows(MAX_ROWS + 1);

            if (!statement.execute(sql)) {
                throw new SQLException("The statement did not return any rows.");
            }

            try (ResultSet rs = statement.getResultSet()) {
                ResultSetMetaData meta = rs.getMetaData();
                int count = meta.getColumnCount();

                List<String> columns = new ArrayList<>(count);
                boolean[] hidden = new boolean[count];
                for (int i = 1; i <= count; i++) {
                    columns.add(meta.getColumnLabel(i));
                    hidden[i - 1] = isHidden(meta.getTableName(i), meta.getColumnName(i));
                }

                List<List<Object>> rows = new ArrayList<>();
                boolean truncated = false;
                while (rs.next()) {
                    if (rows.size() == MAX_ROWS) {
                        truncated = true;
                        break;
                    }
                    List<Object> row = new ArrayList<>(count);
                    for (int i = 1; i <= count; i++) {
                        row.add(hidden[i - 1] ? HIDDEN : cell(rs.getObject(i)));
                    }
                    rows.add(row);
                }
                long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000;
                return new DbQueryResponse(columns, rows, rows.size(), truncated, elapsedMs);
            }
        }
    }

    /**
     * Opens a connection that SQLite itself treats as read-only. A write fails with
     * SQLITE_READONLY whatever the SQL says.
     */
    private Connection open(String path) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path, config.toProperties());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA query_only = ON");   // a second lock on the same door
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        return connection;
    }

    /**
     * The database file from the datasource url, eg jdbc:sqlite:data/tripsense.db gives
     * data/tripsense.db. Package private and static so a test can hit it without a spring context.
     */
    static String pathFrom(String url) {
        String prefix = "jdbc:sqlite:";
        if (url == null || !url.startsWith(prefix)) {
            throw new IllegalStateException("The SQL console only works with a SQLite database, but the "
                    + "datasource url is " + url);
        }
        String path = url.substring(prefix.length());
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        if (path.isBlank() || path.contains(":memory:")) {
            throw new IllegalStateException("The SQL console needs a database file, not an in-memory database.");
        }
        return path;
    }

    /**
     * The hashed password column is never returned. This looks at where each column came from, so it
     * catches SELECT * and u.* as well as a column picked by name. SqlGuard refuses any statement that
     * names the column, so between them the usual routes are shut.
     *
     * It is a safeguard against showing hashes by accident, not a wall. A cleverly built statement,
     * for example a UNION whose first SELECT has no columns from the user table, can get past it.
     * What protects the hashes for real is that this is admin only, every query is audited, and the
     * hashes are BCrypt.
     */
    private static boolean isHidden(String table, String column) {
        return "user".equalsIgnoreCase(table) && "password".equalsIgnoreCase(column);
    }

    /** 
     * numbers and booleans as they are, blobs as a description, long text cut. 
     * keeps the json small and the response something a browser can show
     */
    private static Object cell(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[] bytes) {
            return "<blob, " + bytes.length + " bytes>";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        String text = value.toString();
        return text.length() <= MAX_CELL_CHARS ? text : text.substring(0, MAX_CELL_CHARS) + "…";
    }

    /** The statement a query is running, so the thread that is waiting can stop it. */
    private static final class RunningQuery {
        private volatile Statement statement;
        private volatile boolean cancelled;

        void attach(Statement statement) throws SQLException {
            this.statement = statement;
            if (cancelled) {   // the caller gave up before the query started
                throw new SQLException("The query was cancelled before it started.");
            }
        }

        void cancel() {
            cancelled = true;
            Statement running = statement;
            if (running != null) {
                try {
                    running.cancel();   // sqlite3_interrupt, which stops a statement mid-step
                } catch (SQLException ignored) {
                    // already finished or closed
                }
            }
        }
    }
}
