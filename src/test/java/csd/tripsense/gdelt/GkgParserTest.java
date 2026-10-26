package csd.tripsense.gdelt;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for GkgParser
 * Tests row parsing, filtering rules and title extraction
 */
@DisplayName("GkgParser Unit Tests")
class GkgParserTest {

    // ASSUMPTION: constructor is (countryCodes, minLocationMentions, titleKeywords, themePrefixes)
    // with comma-separated Strings. If yours differs, change ONLY this method.
    private GkgParser parser(String titleKeywords) {
        return new GkgParser("JA,TH", 3, titleKeywords, "NATURAL_DISASTER");
    }

    // three mentions of Japan (FIPS code JA) -> passes min-location-mentions = 3
    private static final String JAPAN_3X =
            "4#Tokyo, Japan#JA#JA40#35.6#139.7#-246227#10;"
          + "4#Tokyo, Japan#JA#JA40#35.6#139.7#-246227#50;"
          + "4#Tokyo, Japan#JA#JA40#35.6#139.7#-246227#90";

    /** Builds one 27-column GKG row (columns not listed here are left empty). */
    private static String line(String id, String collection, String title,
                               String locations, String themes) {
        String[] c = new String[27];
        Arrays.fill(c, "");
        c[0] = id;                                   // record id ("-T" = machine translated)
        c[1] = "20261020070000";                     // date
        c[2] = collection;                           // "1" = web
        c[3] = "example.com";                        // source name
        c[4] = "https://example.com/story";          // url
        c[8] = themes;                               // V2EnhancedThemes
        c[10] = locations;                           // V2EnhancedLocations
        c[26] = title == null ? "" : "<PAGE_TITLE>" + title + "</PAGE_TITLE>";   // extras XML
        return String.join("\t", c);
    }

    // ===== VALID ROWS =====

    // TESTS: the whole happy path of parse(): columns, title, date, country match, buildDescription()
    @Test
    @DisplayName("Should turn a valid row into an article")
    void parse_ShouldReturnArticle_WhenRowIsValid() {
        Optional<GkgArticle> result = parser("").parse(
                line("20261020070000-1", "1", "Typhoon hits Tokyo", JAPAN_3X, ""));

        assertTrue(result.isPresent());
        GkgArticle a = result.get();
        assertEquals("https://example.com/story", a.url());                          // f[4]
        assertEquals("Typhoon hits Tokyo", a.title());                               // extractTitle(f[26])
        assertEquals("example.com", a.domain());                                     // f[3]
        assertEquals(LocalDateTime.of(2026, 10, 20, 7, 0), a.publishedAt());         // f[1]
        assertTrue(a.countryCodes().contains("JA"));                                 // matchedCountries(f[10])
        assertTrue(a.description().contains("example.com"));                         // "Reported by <source>..."
    }

    // TESTS: extractTitle() -> HtmlUtils.htmlUnescape(...)
    @Test
    @DisplayName("Should unescape HTML entities in the title")
    void parse_ShouldUnescapeHtmlEntitiesInTheTitle() {
        Optional<GkgArticle> result = parser("").parse(
                line("1", "1", "Fish &amp; Chips closed in Tokyo", JAPAN_3X, ""));

        assertTrue(result.isPresent());
        assertEquals("Fish & Chips closed in Tokyo", result.get().title());
    }

    // ===== ROWS THAT ARE DROPPED =====

    // TESTS: if (f.length < 27) return Optional.empty();
    @Test
    @DisplayName("Should drop a row with too few columns")
    void parse_ShouldDropRow_WhenTooFewColumns() {
        assertTrue(parser("").parse("only\ttwo columns").isEmpty());
    }

    // TESTS: "1".equals(f[2])  -> only web sources
    @Test
    @DisplayName("Should drop a row that is not from a web source")
    void parse_ShouldDropRow_WhenNonWebSource() {
        assertTrue(parser("").parse(line("1", "2", "Typhoon hits Tokyo", JAPAN_3X, "")).isEmpty());
    }

    // TESTS: !f[0].contains("-T")  -> machine-translated rows
    @Test
    @DisplayName("Should drop a machine-translated row")
    void parse_ShouldDropRow_WhenMachineTranslated() {
        assertTrue(parser("").parse(
                line("20261020070000-T1", "1", "Typhoon hits Tokyo", JAPAN_3X, "")).isEmpty());
    }

    // TESTS: title = extractTitle(f[26]) -> a row without <PAGE_TITLE> is dropped
    @Test
    @DisplayName("Should drop a row with no page title")
    void parse_ShouldDropRow_WhenTitleMissing() {
        assertTrue(parser("").parse(line("1", "1", null, JAPAN_3X, "")).isEmpty());
    }

    // TESTS: matchedCountries(f[10]) -> country must be mentioned >= gdelt.min-location-mentions (3)
    @Test
    @DisplayName("Should drop a row when the country is mentioned too few times")
    void parse_ShouldDropRow_WhenCountryMentionedTooFewTimes() {
        String twoMentions =
                "4#Tokyo, Japan#JA#JA40#35.6#139.7#-246227#10;"
              + "4#Tokyo, Japan#JA#JA40#35.6#139.7#-246227#50";

        assertTrue(parser("").parse(line("1", "1", "Typhoon hits Tokyo", twoMentions, "")).isEmpty());
    }

    // TESTS: matchedCountries(f[10]) -> country must be in gdelt.country-codes
    @Test
    @DisplayName("Should drop a row when the country is not configured")
    void parse_ShouldDropRow_WhenCountryNotConfigured() {
        String usa3x = "3#New York, United States#US#USNY#40.7#-74.0#1#10;"
                     + "3#New York, United States#US#USNY#40.7#-74.0#1#50;"
                     + "3#New York, United States#US#USNY#40.7#-74.0#1#90";

        assertTrue(parser("").parse(line("1", "1", "Typhoon hits New York", usa3x, "")).isEmpty());
    }

    // ===== TITLE KEYWORD / THEME FILTER =====

    // TESTS: line 66  if (keywords != null && !keywords.matcher(title).find() && !hasTheme(f[8]))
    //        -> blank title-keywords means "no keyword filter": everything passes (and no NullPointerException)
    @Test
    @DisplayName("Should not filter at all when title keywords are blank")
    void parse_ShouldNotFilter_WhenKeywordsBlank() {
        assertTrue(parser("").parse(
                line("1", "1", "Tokyo cooking class", JAPAN_3X, "")).isPresent());
    }

    // TESTS: same line, keywords set: title that does not match and has no theme -> dropped
    @Test
    @DisplayName("Should drop a row when keywords are set and neither the title nor the theme matches")
    void parse_ShouldDropRow_WhenKeywordsSetAndNoTitleOrThemeMatch() {
        assertTrue(parser("typhoon").parse(
                line("1", "1", "Tokyo cooking class", JAPAN_3X, "ECON_BANKRUPTCY,12")).isEmpty());
    }

    // TESTS: same line, keywords set: title matches -> kept
    @Test
    @DisplayName("Should keep a row when keywords are set and the title matches")
    void parse_ShouldKeepRow_WhenKeywordsSetAndTitleMatches() {
        assertTrue(parser("typhoon").parse(
                line("1", "1", "heavy typhoon hits Tokyo", JAPAN_3X, "")).isPresent());
    }

    // TESTS: same line, keywords set: title does not match BUT hasTheme(f[8]) is true (NATURAL_DISASTER prefix)
    @Test
    @DisplayName("Should keep a row when the title does not match but a theme does")
    void parse_ShouldKeepRow_WhenKeywordsSetAndThemeMatches() {
        assertTrue(parser("typhoon").parse(
                line("1", "1", "Tokyo cooking class", JAPAN_3X, "NATURAL_DISASTER_FLOOD,120")).isPresent());
    }
}
