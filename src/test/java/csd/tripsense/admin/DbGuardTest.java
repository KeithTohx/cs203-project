package csd.tripsense.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * the statement filter for the SQL console. plain logic, no spring and no database.
 * the guard is the polite first line of defence: the read-only connection is the real one,
 * and SqlConsoleServiceTest proves that separately
 */
class DbGuardTest {

    // ----- statements that must get through -----

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT * FROM user",
            "select * from user;",
            "  SELECT 1  ",
            "SeLeCt 1",
            "-- all the users\nSELECT * FROM user",
            "/* a note */ SELECT 1",
            "WITH x AS (SELECT 1 AS a) SELECT a FROM x",
            "WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM c WHERE x < 5) SELECT x FROM c",
            "SELECT COUNT(*) FROM impact WHERE impacted = 1",
            "SELECT * FROM recommendation r JOIN impact i ON i.id = r.impact_id",
            "SELECT * FROM activity_option WHERE country = 'Japan' LIMIT 20",
            "SELECT * FROM sqlite_master",
            "SELECT * FROM pragma_table_info('user')"
    })
    void aPlainSelectIsAllowed(String sql) {
        assertThat(DbGuard.check(sql)).isEqualTo(sql.strip());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT ';' AS semicolon",
            "SELECT 'delete me' AS note",
            "SELECT 'DROP TABLE user' AS note",
            "SELECT 'it''s fine' AS quote",
            "SELECT created_at, updated_at FROM user_preference",
            "SELECT replace(name, 'a', 'b') FROM country",
            "SELECT \"name\" FROM country",
            "SELECT 'a;b;c' AS parts",
            "SELECT 1; -- finished",
            "SELECT 1 -- ; DROP TABLE user",
            "SELECT 1 /* ; DROP TABLE user */"
    })
    void wordsInsideStringsAndCommentsDoNotCount(String sql) {
        // a forbidden word in a string, or hidden in a comment, is only text
        assertThat(DbGuard.check(sql)).isEqualTo(sql.strip());
    }

    @Test
    void aTrailingSemicolonIsFine() {
        assertThat(DbGuard.check("SELECT 1;")).isEqualTo("SELECT 1;");
    }

    // ----- statements that must be refused -----

    @ParameterizedTest
    @ValueSource(strings = {
            "INSERT INTO country(name) VALUES ('x')",
            "UPDATE user SET role = 'ADMIN'",
            "DELETE FROM user",
            "DROP TABLE user",
            "CREATE TABLE x(a)",
            "ALTER TABLE user ADD COLUMN x",
            "ATTACH DATABASE 'other.db' AS other",
            "DETACH DATABASE other",
            "VACUUM",
            "VACUUM INTO 'copy.db'",
            "REINDEX",
            "PRAGMA table_info(user)",
            "PRAGMA writable_schema = ON",
            "REPLACE INTO country(name) VALUES ('x')",
            "EXPLAIN SELECT 1",
            "dRoP TABLE user",
            "/* hi */ DROP TABLE user",
            "-- note\nDELETE FROM user"
    })
    void anythingButASelectIsRefused(String sql) {
        assertRefused(sql);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "WITH x AS (SELECT 1) DELETE FROM user",
            "WITH x AS (SELECT 1) INSERT INTO country(name) SELECT 'a' FROM x",
            "WITH x AS (SELECT 1) UPDATE user SET role = 'ADMIN'"
    })
    void aWithThatEndsInAWriteIsRefused(String sql) {
        // WITH is allowed to start a SELECT, so this is caught by the word check, and would be
        // caught again by the read-only connection
        assertRefused(sql);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT 1; DROP TABLE user",
            "SELECT 1; SELECT 2",
            "SELECT 1;;",
            "SELECT 1; /* ok */ SELECT 2"
    })
    void moreThanOneStatementIsRefused(String sql) {
        assertThatThrownBy(() -> DbGuard.check(sql))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).contains("one statement");
                });
    }

    @Test
    void loadingAnExtensionIsRefused() {
        assertRefused("SELECT load_extension('evil')");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT password FROM user",
            "SELECT PASSWORD FROM user",
            "SELECT u.password FROM user u",
            "SELECT \"password\" FROM user",
            "SELECT `password` FROM user",
            "SELECT [password] FROM user",
            "SELECT substr(password, 1, 10) FROM user",
            "SELECT /* sneaky */ password FROM user",
            "SELECT * FROM user WHERE password LIKE '$2a%'"
    })
    void namingThePasswordColumnIsRefused(String sql) {
        assertThatThrownBy(() -> DbGuard.check(sql))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).contains("password");
                });
    }

    @Test
    void selectStarFromUserIsLeftToTheServiceToMask() {
        // the guard cannot see a column that * expands to. SqlConsoleService hides it in the result
        assertThat(DbGuard.check("SELECT * FROM user")).isEqualTo("SELECT * FROM user");
    }

    @Test
    void aColumnThatOnlyContainsTheWordIsNotTheWord() {
        assertThat(DbGuard.check("SELECT password_changed_at FROM audit_log"))
                .isEqualTo("SELECT password_changed_at FROM audit_log");
    }

    // ----- bad input -----

    @Test
    void nullAndBlankAreRefused() {
        assertRefused(null);
        assertRefused("");
        assertRefused("   \n  ");
    }

    @Test
    void aStatementThatIsOnlyAComment_isRefused() {
        assertRefused("-- just a comment");
        assertRefused("/* nothing here */");
    }

    @Test
    void anUnclosedQuoteIsRefused() {
        assertRefused("SELECT 'unclosed");
        assertRefused("SELECT \"unclosed FROM user");
        assertRefused("SELECT [unclosed FROM user");
    }

    @Test
    void aStatementOverTheLengthLimitIsRefused() {
        String tooLong = "SELECT '" + "x".repeat(DbGuard.MAX_LENGTH) + "'";

        assertThatThrownBy(() -> DbGuard.check(tooLong))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getReason()).contains("longer than"));
    }

    @Test
    void aStatementExactlyAtTheLimitIsAllowed() {
        String prefix = "SELECT '";
        String exactly = prefix + "x".repeat(DbGuard.MAX_LENGTH - prefix.length() - 1) + "'";

        assertThat(exactly).hasSize(DbGuard.MAX_LENGTH);
        assertThat(DbGuard.check(exactly)).isEqualTo(exactly);
    }

    @Test
    void theMessageForAWriteSaysTheConsoleIsReadOnly() {
        assertThatThrownBy(() -> DbGuard.check("DELETE FROM user"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getReason()).contains("read-only"));
    }

    // every refusal is a 400 the console can show as it is
    private void assertRefused(String sql) {
        assertThatThrownBy(() -> DbGuard.check(sql))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
