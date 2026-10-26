package csd.tripsense.gdelt;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Optional;

import org.junit.jupiter.api.Test;

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

    // TESTS: the whole happy path of parse(): columns, title, date, country match, buildDescription()
    @Test
    void parse_validRow_becomesAnArticle() {
        Optional<GkgArticle> result = parser("").parse(
                line("20261020070000-1", "1", "Typhoon hits Tokyo", JAPAN_3X, ""));

        assertThat(result).isPresent();
        GkgArticle a = result.get();
        assertThat(a.url()).isEqualTo("https://example.com/story");              // f[4]
        assertThat(a.title()).isEqualTo("Typhoon hits Tokyo");                    // extractTitle(f[26])
        assertThat(a.domain()).isEqualTo("example.com");                          // f[3]
        assertThat(a.publishedAt()).isEqualTo(LocalDateTime.of(2026, 10, 20, 7, 0));   // f[1]
        assertThat(a.countryCodes()).contains("JA");                              // matchedCountries(f[10])
        assertThat(a.description()).contains("example.com");                      // "Reported by <source>..."
    }

    // TESTS: extractTitle() -> HtmlUtils.htmlUnescape(...)
    @Test
    void parse_unescapesHtmlEntitiesInTheTitle() {
        Optional<GkgArticle> result = parser("").parse(
                line("1", "1", "Fish &amp; Chips closed in Tokyo", JAPAN_3X, ""));

        assertThat(result).get().extracting(GkgArticle::title)
                .isEqualTo("Fish & Chips closed in Tokyo");
    }

    // TESTS: if (f.length < 27) return Optional.empty();
    @Test
    void parse_tooFewColumns_isDropped() {
        assertThat(parser("").parse("only\ttwo columns")).isEmpty();
    }

    // TESTS: "1".equals(f[2])  -> only web sources
    @Test
    void parse_nonWebSource_isDropped() {
        assertThat(parser("").parse(line("1", "2", "Typhoon hits Tokyo", JAPAN_3X, ""))).isEmpty();
    }

    // TESTS: !f[0].contains("-T")  -> machine-translated rows
    @Test
    void parse_machineTranslatedRow_isDropped() {
        assertThat(parser("").parse(
                line("20261020070000-T1", "1", "Typhoon hits Tokyo", JAPAN_3X, ""))).isEmpty();
    }

    // TESTS: title = extractTitle(f[26]) -> a row without <PAGE_TITLE> is dropped
    @Test
    void parse_missingTitle_isDropped() {
        assertThat(parser("").parse(line("1", "1", null, JAPAN_3X, ""))).isEmpty();
    }

    // TESTS: matchedCountries(f[10]) -> country must be mentioned >= gdelt.min-location-mentions (3)
    @Test
    void parse_countryMentionedTooFewTimes_isDropped() {
        String twoMentions =
                "4#Tokyo, Japan#JA#JA40#35.6#139.7#-246227#10;"
              + "4#Tokyo, Japan#JA#JA40#35.6#139.7#-246227#50";

        assertThat(parser("").parse(line("1", "1", "Typhoon hits Tokyo", twoMentions, ""))).isEmpty();
    }

    // TESTS: matchedCountries(f[10]) -> country must be in gdelt.country-codes
    @Test
    void parse_countryNotConfigured_isDropped() {
        String usa3x = "3#New York, United States#US#USNY#40.7#-74.0#1#10;"
                     + "3#New York, United States#US#USNY#40.7#-74.0#1#50;"
                     + "3#New York, United States#US#USNY#40.7#-74.0#1#90";

        assertThat(parser("").parse(line("1", "1", "Typhoon hits New York", usa3x, ""))).isEmpty();
    }

    // TESTS: line 66  if (keywords != null && !keywords.matcher(title).find() && !hasTheme(f[8]))
    //        -> blank title-keywords means "no keyword filter": everything passes (and no NullPointerException)
    @Test
    void parse_blankKeywords_doNotFilterAtAll() {
        assertThat(parser("").parse(
                line("1", "1", "Tokyo cooking class", JAPAN_3X, ""))).isPresent();
    }

    // TESTS: same line, keywords set: title that does not match and has no theme -> dropped
    @Test
    void parse_keywordsSet_titleWithoutKeywordAndTheme_isDropped() {
        assertThat(parser("typhoon").parse(
                line("1", "1", "Tokyo cooking class", JAPAN_3X, "ECON_BANKRUPTCY,12"))).isEmpty();
    }

    // TESTS: same line, keywords set: title matches -> kept
    @Test
    void parse_keywordsSet_titleMatches_isKept() {
        assertThat(parser("typhoon").parse(
                line("1", "1", "heavy typhoon hits Tokyo", JAPAN_3X, ""))).isPresent();
    }

    // TESTS: same line, keywords set: title does not match BUT hasTheme(f[8]) is true (NATURAL_DISASTER prefix)
    @Test
    void parse_keywordsSet_matchingThemeSavesTheArticle() {
        assertThat(parser("typhoon").parse(
                line("1", "1", "Tokyo cooking class", JAPAN_3X, "NATURAL_DISASTER_FLOOD,120"))).isPresent();
    }
}