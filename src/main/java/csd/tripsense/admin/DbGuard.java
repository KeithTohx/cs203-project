package csd.tripsense.admin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * First line of defence for the SQL console: only one SELECT statement gets through.
 *
 * This is NOT what keeps the database safe. The console runs on a connection that SQLite itself
 * opened read-only (see SqlConsoleService.open), so even a statement that slipped past this class
 * could not change anything. This class exists to give the admin a clear message up front, and to
 * stop things a read-only connection does not stop, such as reading another database file with
 * ATTACH or loading an extension.
 *
 * It reads the statement the way SQLite does: comments and the inside of 'strings' and "quoted
 * names" are skipped, so the word delete in a string, or the column updated_at, is fine.
 */
final class DbGuard {

    static final int MAX_LENGTH = 5_000;

    /**
     * words that are never needed to read data. replace is left out on purpose, 
     * it is also a string function, and the read-only connection stops REPLACE INTO anyway
     */
    private static final Set<String> FORBIDDEN = Set.of(
            "insert", "update", "delete", "drop", "create", "alter", "attach", "detach",
            "vacuum", "reindex", "pragma", "load_extension");

    // the hashed password column. see SqlConsoleService for the other half of keeping it hidden
    private static final String HIDDEN_COLUMN = "password";

    private DbGuard() {
    }

    /**
     * Returns the statement to run, or throws a 400 saying why it was refused.
     * Package private and static so a test can hit it without a spring context.
     */
    static String check(String sql) {
        if (sql == null || sql.isBlank()) {
            throw reject("Type a SQL statement first.");
        }
        if (sql.length() > MAX_LENGTH) {
            throw reject("The statement is longer than " + MAX_LENGTH + " characters.");
        }

        // WITH is allowed because a SELECT can start with a CTE. a WITH that ends in a write is
        // caught by the word check below, and by the read-only connection if that ever missed it
        String first = firstKeyword(sql);
        if (!first.equals("select") && !first.equals("with")) {
            throw reject("Only SELECT statements can be run here: the console is read-only.");
        }

        List<String> words = new ArrayList<>();   // every name or keyword outside quotes and comments
        StringBuilder word = new StringBuilder();
        boolean ended = false;                    // a ; has been seen, so more SQL means a second statement
        int i = 0;
        int n = sql.length();

        while (i < n) {
            char c = sql.charAt(i);
            char next = i + 1 < n ? sql.charAt(i + 1) : '\0';

            if (c == '-' && next == '-') {                      // -- comment to the end of the line
                flush(word, words);
                int newline = sql.indexOf('\n', i);
                i = newline < 0 ? n : newline + 1;
            } else if (c == '/' && next == '*') {               // /* comment */
                flush(word, words);
                int close = sql.indexOf("*/", i + 2);
                i = close < 0 ? n : close + 2;
            } else if (Character.isWhitespace(c)) {
                flush(word, words);
                i++;
            } else if (ended) {                                 // real SQL after the ;
                throw reject("Only one statement can be run at a time.");
            } else if (c == ';') {
                flush(word, words);
                ended = true;
                i++;
            } else if (c == '\'') {                             // a string: its contents are not names
                flush(word, words);
                i = skipQuoted(sql, i, '\'', null);
            } else if (c == '"' || c == '`') {                  // a quoted name: its contents ARE a name
                flush(word, words);
                i = skipQuoted(sql, i, c, words);
            } else if (c == '[') {                              // [a quoted name] in SQLite's other style
                flush(word, words);
                int close = sql.indexOf(']', i + 1);
                if (close < 0) {
                    throw reject("A [ was never closed.");
                }
                words.add(sql.substring(i + 1, close).toLowerCase(Locale.ROOT));
                i = close + 1;
            } else if (Character.isLetterOrDigit(c) || c == '_') {
                word.append(c);
                i++;
            } else {                                            // an operator, bracket, comma, dot...
                flush(word, words);
                i++;
            }
        }
        flush(word, words);

        for (String w : words) {
            if (w.equals(HIDDEN_COLUMN)) {
                throw reject("The password column can't be queried.");
            }
            if (FORBIDDEN.contains(w)) {
                throw reject("'" + w.toUpperCase(Locale.ROOT) + "' is not allowed: the console is read-only.");
            }
        }
        return sql.strip();
    }

    private static void flush(StringBuilder word, List<String> words) {
        if (word.length() > 0) {
            words.add(word.toString().toLowerCase(Locale.ROOT));
            word.setLength(0);
        }
    }

    // the first word of the statement, once leading whitespace and comments are skipped
    private static String firstKeyword(String sql) {
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            char next = i + 1 < n ? sql.charAt(i + 1) : '\0';
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '-' && next == '-') {
                int newline = sql.indexOf('\n', i);
                i = newline < 0 ? n : newline + 1;
            } else if (c == '/' && next == '*') {
                int close = sql.indexOf("*/", i + 2);
                i = close < 0 ? n : close + 2;
            } else {
                break;
            }
        }
        int start = i;
        while (i < n && Character.isLetter(sql.charAt(i))) {
            i++;
        }
        return sql.substring(start, i).toLowerCase(Locale.ROOT);
    }

    /**
     * Skips from an opening quote to its closing quote and returns the index after it. A doubled
     * quote inside is a literal quote, as in 'it''s'. When words is given the contents are added to
     * it, because a quoted name such as "password" is still a name.
     */
    private static int skipQuoted(String sql, int start, char quote, List<String> words) {
        StringBuilder content = new StringBuilder();
        int i = start + 1;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == quote) {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                    content.append(quote);
                    i += 2;
                    continue;
                }
                if (words != null) {
                    words.add(content.toString().toLowerCase(Locale.ROOT));
                }
                return i + 1;
            }
            content.append(c);
            i++;
        }
        throw reject("A quote was never closed.");
    }

    private static ResponseStatusException reject(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
