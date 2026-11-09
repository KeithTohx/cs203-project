package csd.tripsense.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The service against a REAL sqlite file in a temporary folder, not a mock. What it has to prove is
 * about how sqlite behaves: that the connection is read-only, that a cap and a timeout apply, and
 * that the password hash stays out of the result. A mock could not show any of that.
 *
 * The @PreAuthorize checks are not tested here, they need spring security's proxy. The URL rule and
 * the method rule are checked by hand, see the list in the pull request.
 */
class DbConsoleServiceTest {

    @TempDir
    Path folder;

    private String url;
    private DbConsoleService service;

    @BeforeEach
    void setUp() throws SQLException {
        url = "jdbc:sqlite:" + folder.resolve("test.db");
        try (Connection connection = DriverManager.getConnection(url); Statement s = connection.createStatement()) {
            s.execute("CREATE TABLE country (id INTEGER PRIMARY KEY, name VARCHAR, fips VARCHAR)");
            s.execute("INSERT INTO country VALUES (1, 'Japan', 'JA'), (2, 'Thailand', NULL)");
            s.execute("CREATE TABLE user (id INTEGER PRIMARY KEY, username VARCHAR, password VARCHAR, role VARCHAR)");
            s.execute("INSERT INTO user VALUES (1, 'john', '$2a$10$abcdefghijklmnopqrstuv', 'USER')");
            s.execute("CREATE TABLE autoinc (id INTEGER PRIMARY KEY AUTOINCREMENT, note VARCHAR)");
            s.execute("INSERT INTO autoinc (note) VALUES ('a')");   // makes sqlite create sqlite_sequence
        }
        service = new DbConsoleService(url, 5);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    // ----- helpers -----

    private long count(String table) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url);
             Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private List<String> asText(List<Object> row) {
        return row.stream().map(String::valueOf).toList();
    }

    // ----- reading -----

    @Test
    void aSelectReturnsItsColumnsAndRows() {
        DbQueryResponse result = service.run("SELECT id, name FROM country ORDER BY id");

        assertThat(result.columns()).containsExactly("id", "name");
        assertThat(result.rowCount()).isEqualTo(2);
        assertThat(asText(result.rows().get(0))).containsExactly("1", "Japan");
        assertThat(asText(result.rows().get(1))).containsExactly("2", "Thailand");
        assertThat(result.truncated()).isFalse();
        assertThat(result.elapsedMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void aColumnAliasIsTheColumnName() {
        assertThat(service.run("SELECT name AS country_name FROM country").columns())
                .containsExactly("country_name");
    }

    @Test
    void aNullStaysNull() {
        DbQueryResponse result = service.run("SELECT fips FROM country WHERE id = 2");

        assertThat(result.rows().get(0).get(0)).isNull();
    }

    @Test
    void aQueryThatMatchesNothingReturnsNoRowsButStillHasColumns() {
        DbQueryResponse result = service.run("SELECT id, name FROM country WHERE id = 99");

        assertThat(result.columns()).containsExactly("id", "name");
        assertThat(result.rows()).isEmpty();
        assertThat(result.rowCount()).isZero();
    }

    @Test
    void theSchemaCanBeReadToo() {
        DbQueryResponse result = service.run("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name");

        assertThat(result.rows().stream().map(r -> String.valueOf(r.get(0))).toList())
                .contains("country", "user");
    }

    @Test
    void aWithQueryWorks() {
        DbQueryResponse result = service.run("WITH j AS (SELECT * FROM country WHERE fips = 'JA') SELECT name FROM j");

        assertThat(asText(result.rows().get(0))).containsExactly("Japan");
    }

    // ----- the row cap -----

    private String numbersUpTo(int n) {
        return "WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM c WHERE x < " + n + ") SELECT x FROM c";
    }

    @Test
    void moreRowsThanTheCapAreCutAndSaidSo() {
        DbQueryResponse result = service.run(numbersUpTo(600));

        assertThat(result.rowCount()).isEqualTo(DbConsoleService.MAX_ROWS);
        assertThat(result.rows()).hasSize(DbConsoleService.MAX_ROWS);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void exactlyTheCapIsNotTruncated() {
        DbQueryResponse result = service.run(numbersUpTo(DbConsoleService.MAX_ROWS));

        assertThat(result.rowCount()).isEqualTo(DbConsoleService.MAX_ROWS);
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void oneRowOverTheCapIsTruncated() {
        DbQueryResponse result = service.run(numbersUpTo(DbConsoleService.MAX_ROWS + 1));

        assertThat(result.rowCount()).isEqualTo(DbConsoleService.MAX_ROWS);
        assertThat(result.truncated()).isTrue();
    }

    // ----- cells -----

    @Test
    void veryLongTextIsCut() {
        // 3000 characters, built with sqlite's own functions so no table is needed
        DbQueryResponse result = service.run("SELECT substr(replace(hex(zeroblob(3000)), '0', 'x'), 1, 3000)");

        String cell = (String) result.rows().get(0).get(0);
        assertThat(cell).hasSize(DbConsoleService.MAX_CELL_CHARS + 1).endsWith("…");
    }

    @Test
    void textUnderTheLimitIsLeftAlone() {
        assertThat(service.run("SELECT 'short'").rows().get(0).get(0)).isEqualTo("short");
    }

    @Test
    void aBlobIsDescribedNotSent() {
        assertThat(service.run("SELECT zeroblob(10)").rows().get(0).get(0)).isEqualTo("<blob, 10 bytes>");
    }

    // ----- the password hash -----

    @Test
    void selectStarFromUserNeverReturnsTheHash() {
        DbQueryResponse result = service.run("SELECT * FROM user");

        assertThat(result.columns()).contains("password");
        int password = result.columns().indexOf("password");
        assertThat(result.rows().get(0).get(password)).isEqualTo(DbConsoleService.HIDDEN);
        // and the rest of the row is still there
        assertThat(result.rows().get(0).get(result.columns().indexOf("username"))).isEqualTo("john");
        assertThat(result.rows().toString()).doesNotContain("$2a$");
    }

    @Test
    void aStarThroughAnAliasIsHiddenToo() {
        DbQueryResponse result = service.run("SELECT u.* FROM user u");

        assertThat(result.rows().toString()).doesNotContain("$2a$");
    }

    @Test
    void aStarThroughASubqueryIsHiddenToo() {
        DbQueryResponse result = service.run("SELECT * FROM (SELECT * FROM user)");

        assertThat(result.rows().toString()).doesNotContain("$2a$");
    }

    @Test
    void namingThePasswordColumnIsRefusedBeforeAnythingRuns() {
        assertThatThrownBy(() -> service.run("SELECT password FROM user"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ----- read-only: the guard -----

    @ParameterizedTest
    @ValueSource(strings = {
            "DELETE FROM country",
            "UPDATE country SET name = 'x'",
            "INSERT INTO country VALUES (3, 'x', 'XX')",
            "DROP TABLE country",
            "SELECT 1; DROP TABLE country"
    })
    void runRefusesWritesAndNothingChanges(String sql) throws SQLException {
        assertThatThrownBy(() -> service.run(sql))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        assertThat(count("country")).isEqualTo(2);
    }

    // ----- read-only: the connection itself, with the guard out of the way -----

    @ParameterizedTest
    @ValueSource(strings = {
            "DELETE FROM country",
            "UPDATE country SET name = 'changed'",
            "INSERT INTO country VALUES (3, 'x', 'XX')",
            "DROP TABLE country",
            "CREATE TABLE sneaky (a)",
            "WITH x AS (SELECT 1, 'x', 'XX') INSERT INTO country SELECT * FROM x"
    })
    void theConnectionItselfRefusesToWrite(String sql) throws SQLException {
        // this is the check that actually keeps the database safe. executeUnguarded skips SqlGuard,
        // so if these were not refused, nothing else would have stopped them
        assertThatThrownBy(() -> service.executeUnguarded(sql))
                .isInstanceOf(ResponseStatusException.class);

        assertThat(count("country")).isEqualTo(2);
        try (Connection connection = DriverManager.getConnection(url);
             Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("SELECT name FROM country WHERE id = 1")) {
            rs.next();
            assertThat(rs.getString(1)).isEqualTo("Japan");   // not 'changed'
        }
        try (Connection connection = DriverManager.getConnection(url);
             Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE name = 'sneaky'")) {
            rs.next();
            assertThat(rs.getLong(1)).isZero();
        }
    }

    // ----- errors -----

    @Test
    void sqliteErrorsComeBackAsA400WithTheMessage() {
        assertThatThrownBy(() -> service.run("SELECT * FROM nope"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).contains("no such table");
                });
    }

    @Test
    void aSyntaxErrorIsA400() {
        assertThatThrownBy(() -> service.run("SELECT FROM WHERE"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void aMissingDatabaseFileIsA400NotACrash() {
        DbConsoleService missing = new DbConsoleService("jdbc:sqlite:" + folder.resolve("nothing-here.db"), 5);
        try {
            assertThatThrownBy(() -> missing.run("SELECT 1"))
                    .isInstanceOf(ResponseStatusException.class);
        } finally {
            missing.shutdown();
        }
    }

    // ----- the timeout -----

    @Test
    void aQueryThatNeverFinishesIsStoppedAtTheTimeout() {
        DbConsoleService quick = new DbConsoleService(url, 1);
        try {
            // counts forever. if the timeout did not stop it this test would never return,
            // so it is also given 10 seconds of its own
            assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                    assertThatThrownBy(() -> quick.run(
                            "WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM c) SELECT count(*) FROM c"))
                            .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                                assertThat(e.getStatusCode()).isEqualTo(HttpStatus.REQUEST_TIMEOUT);
                                assertThat(e.getReason()).contains("1 seconds");
                            }));
        } finally {
            quick.shutdown();
        }
    }

    @Test
    void aSlowQueryDoesNotStopTheNextOneWorking() {
        DbConsoleService quick = new DbConsoleService(url, 1);
        try {
            assertThatThrownBy(() -> quick.run(
                    "WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM c) SELECT count(*) FROM c"))
                    .isInstanceOf(ResponseStatusException.class);

            // the cancelled statement released its connection, so a normal query still works
            assertThat(quick.run("SELECT COUNT(*) FROM country").rows().get(0).get(0).toString()).isEqualTo("2");
        } finally {
            quick.shutdown();
        }
    }

    // ----- the table list -----

    @Test
    void theTableListHasEveryTableWithItsRowCount() {
        List<DbTableInfo> tables = service.listTables();

        assertThat(tables).extracting(DbTableInfo::name).containsExactly("autoinc", "country", "user");
        assertThat(tables).filteredOn(t -> t.name().equals("country")).extracting(DbTableInfo::rows)
                .containsExactly(2L);
        assertThat(tables).filteredOn(t -> t.name().equals("user")).extracting(DbTableInfo::rows)
                .containsExactly(1L);
    }

    @Test
    void theTablesSqliteKeepsForItselfAreLeftOut() {
        assertThat(service.listTables()).extracting(DbTableInfo::name)
                .noneMatch(name -> name.startsWith("sqlite_"));
    }

    // ----- the datasource url -----

    @Test
    void thePathComesFromTheDatasourceUrl() {
        assertThat(DbConsoleService.pathFrom("jdbc:sqlite:data/tripsense.db")).isEqualTo("data/tripsense.db");
        assertThat(DbConsoleService.pathFrom("jdbc:sqlite:/var/data/tripsense.db")).isEqualTo("/var/data/tripsense.db");
        assertThat(DbConsoleService.pathFrom("jdbc:sqlite:data/tripsense.db?busy_timeout=500"))
                .isEqualTo("data/tripsense.db");
    }

    @Test
    void anInMemoryDatabaseIsNotSupported() {
        assertThatThrownBy(() -> DbConsoleService.pathFrom("jdbc:sqlite::memory:"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("in-memory");
    }

    @Test
    void aDatabaseThatIsNotSqliteIsNotSupported() {
        assertThatThrownBy(() -> DbConsoleService.pathFrom("jdbc:h2:mem:test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SQLite");
        assertThatThrownBy(() -> DbConsoleService.pathFrom(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anAppOnAnotherDatabaseStillStartsAndOnlyTheConsoleRefuses() {
        // the url is checked when a query runs, not when the bean is built, so building it never fails
        DbConsoleService other = new DbConsoleService("jdbc:h2:mem:test", 5);
        try {
            assertThatThrownBy(() -> other.run("SELECT 1")).isInstanceOf(IllegalStateException.class);
        } finally {
            other.shutdown();
        }
    }
}
