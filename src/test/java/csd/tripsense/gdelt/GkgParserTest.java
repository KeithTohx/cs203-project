package csd.tripsense.gdelt;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for GkgParser
 * Tests row parsing, country tagging (headline first, then mention count with a margin), outlet-name
 * stripping, overlapping place names and title extraction
 */
@DisplayName("GkgParser Unit Tests")
class GkgParserTest {

    // ASSUMPTION: constructor is (countryCodes, minLocationMentions, titleKeywords, themePrefixes)
    // with comma-separated Strings. If yours differs, change ONLY this method.
    private GkgParser parser(String titleKeywords) {
        return new GkgParser("JA,TH", 3, titleKeywords, "NATURAL_DISASTER");
    }

    /** One real GDELT location block: Type#FullName#CountryCode#ADM1#ADM2#Lat#Long#FeatureID#Offset */
    private static String block(String type, String name, String country, int offset) {
        return String.join("#", type, name, country, "", "", "35.6", "139.7", "1", String.valueOf(offset));
    }

    private static String blocks(String... blocks) {
        return String.join(";", blocks);
    }

    // three mentions of Japan (FIPS code JA), spread over the article body
    private static final String JAPAN_3X = blocks(
            block("4", "Tokyo, Japan", "JA", 10),
            block("4", "Tokyo, Japan", "JA", 50),
            block("4", "Tokyo, Japan", "JA", 90));

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

    private Optional<GkgArticle> parse(String title, String locations) {
        return parser("").parse(line("1", "1", title, locations, ""));
    }

    // same, but with other countries configured, for places outside Japan and Thailand
    private Optional<GkgArticle> parseWith(String countryCodes, String title, String locations) {
        return new GkgParser(countryCodes, 3, "", "NATURAL_DISASTER")
                .parse(line("1", "1", title, locations, ""));
    }

    // the same location block repeated, at different offsets
    private static String repeat(String type, String name, String country, int times) {
        String[] out = new String[times];
        for (int i = 0; i < times; i++) out[i] = block(type, name, country, 10 + i * 40);
        return String.join(";", out);
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
        assertEquals(Set.of("JA"), a.countryCodes());                                // exactly one country
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

    // ===== ROWS THAT ARE DROPPED (basic checks) =====

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

    // ===== STEP 1: THE HEADLINE NAMES A PLACE =====

    // TESTS: the headline place decides the country, even when another country is mentioned more
    // (a Thai story in an outlet that keeps mentioning Japan)
    @Test
    @DisplayName("Should tag the country named in the headline, not the one mentioned most")
    void parse_ShouldTagHeadlineCountry_EvenWhenAnotherCountryIsMentionedMore() {
        String locations = blocks(
                block("4", "Bangkok, Thailand", "TH", 10),
                block("4", "Tokyo, Japan", "JA", 50),
                block("4", "Tokyo, Japan", "JA", 90),
                block("4", "Tokyo, Japan", "JA", 130));

        Optional<GkgArticle> result = parse("Floods close Bangkok airport", locations);

        assertTrue(result.isPresent());
        assertEquals(Set.of("TH"), result.get().countryCodes());
    }

    // TESTS: a country-level block counts when its name is a real country name
    @Test
    @DisplayName("Should tag a country named in the headline")
    void parse_ShouldTagCountry_WhenHeadlineNamesTheCountry() {
        Optional<GkgArticle> result = parse("Heavy rain expected across Thailand",
                block("1", "Thailand", "TH", 10));

        assertTrue(result.isPresent());
        assertEquals(Set.of("TH"), result.get().countryCodes());
    }

    // TESTS: the original bug. A Japan article whose headline is about Qatar must not be tagged Japan
    @Test
    @DisplayName("Should drop a story about an unsupported country even when a supported one is mentioned often")
    void parse_ShouldDropRow_WhenHeadlineNamesUnsupportedCountryButBodyMentionsJapan() {
        String locations = blocks(
                block("1", "Qatar", "QA", 5),
                block("4", "Tokyo, Japan", "JA", 50),
                block("4", "Tokyo, Japan", "JA", 90),
                block("4", "Tokyo, Japan", "JA", 130));

        assertTrue(parse("Qatar airport closed after storm", locations).isEmpty());
    }

    // TESTS: two countries in the headline is ambiguous, so the row is dropped
    @Test
    @DisplayName("Should drop a row when the headline names places in two countries")
    void parse_ShouldDropRow_WhenHeadlineNamesTwoCountries() {
        // Japan is also mentioned 3 times, so the mention-count fallback would pick it if the
        // headline check did not drop the row first
        String locations = blocks(
                block("1", "Japan", "JA", 10),
                block("1", "Thailand", "TH", 50),
                block("4", "Tokyo, Japan", "JA", 90),
                block("4", "Tokyo, Japan", "JA", 130));

        assertTrue(parse("Japan and Thailand agree new flight route", locations).isEmpty());
    }

    // TESTS: a nationality is not a place. "Thai" would otherwise count as a second country (TH)
    @Test
    @DisplayName("Should ignore a nationality in the headline")
    void parse_ShouldIgnoreNationalityInHeadline() {
        String locations = blocks(
                block("1", "Thai", "TH", 5),
                block("4", "Tokyo, Japan", "JA", 50));

        Optional<GkgArticle> result = parse("Thai man held in Tokyo", locations);

        assertTrue(result.isPresent());
        assertEquals(Set.of("JA"), result.get().countryCodes());
    }

    // TESTS: names match whole words only, and punctuation such as 's does not get in the way
    @Test
    @DisplayName("Should match a place name as a whole word, even with an apostrophe")
    void parse_ShouldMatchWholeWordsOnly() {
        String locations = block("4", "Tokyo, Japan", "JA", 10);

        assertTrue(parse("Tokyo's airport reopens", locations).isPresent());
        assertTrue(parse("Tokyonese delicacies on offer", locations).isEmpty());
    }

    // ===== STEP 2: NO PLACE IN THE HEADLINE, ONE COUNTRY MENTIONED OFTEN =====

    // TESTS: onlyCountryMentionedOften() -> exactly one configured country with >= 3 mentions
    @Test
    @DisplayName("Should fall back to the one country mentioned often when the headline names no place")
    void parse_ShouldFallBackToMentions_WhenHeadlineNamesNoPlace() {
        Optional<GkgArticle> result = parse("Flights cancelled after storm", JAPAN_3X);

        assertTrue(result.isPresent());
        assertEquals(Set.of("JA"), result.get().countryCodes());
    }

    // TESTS: the fallback needs >= min-location-mentions (3) mentions
    @Test
    @DisplayName("Should drop a row when the headline names no place and the country is mentioned too few times")
    void parse_ShouldDropRow_WhenCountryMentionedTooFewTimes() {
        String twoMentions = blocks(
                block("4", "Tokyo, Japan", "JA", 10),
                block("4", "Tokyo, Japan", "JA", 50));

        assertTrue(parse("Flights cancelled after storm", twoMentions).isEmpty());
    }

    // TESTS: two countries both mentioned often is ambiguous, so the row is dropped
    @Test
    @DisplayName("Should drop a row when the headline names no place and two countries are mentioned often")
    void parse_ShouldDropRow_WhenTwoCountriesMentionedOften() {
        String locations = blocks(
                block("4", "Tokyo, Japan", "JA", 10),
                block("4", "Tokyo, Japan", "JA", 50),
                block("4", "Tokyo, Japan", "JA", 90),
                block("4", "Bangkok, Thailand", "TH", 110),
                block("4", "Bangkok, Thailand", "TH", 150),
                block("4", "Bangkok, Thailand", "TH", 190));

        assertTrue(parse("Flights cancelled after storm", locations).isEmpty());
    }

    // TESTS: the country must be in gdelt.country-codes
    @Test
    @DisplayName("Should drop a row when the country is not configured")
    void parse_ShouldDropRow_WhenCountryNotConfigured() {
        String usa3x = blocks(
                block("3", "New York, United States", "US", 10),
                block("3", "New York, United States", "US", 50),
                block("3", "New York, United States", "US", 90));

        assertTrue(parse("Typhoon hits New York", usa3x).isEmpty());
        assertTrue(parse("Typhoon hits the east coast", usa3x).isEmpty());
    }

    // TESTS: a headline place with the outlet name after " - " is looked up without the outlet name
    @Test
    @DisplayName("Should ignore a place in the outlet name at the end of the title")
    void parse_ShouldIgnorePlaceInOutletName() {
        // "Tokyo Daily News" is the outlet, so only Bangkok counts. Without the strip this is two countries.
        String locations = blocks(
                block("4", "Bangkok, Thailand", "TH", 10),
                block("4", "Tokyo, Japan", "JA", 50));

        Optional<GkgArticle> result = parse("Floods hit Bangkok - Tokyo Daily News", locations);

        assertTrue(result.isPresent());
        assertEquals(Set.of("TH"), result.get().countryCodes());
    }

    // TESTS: a tail that is not Title Case is part of the headline, not an outlet name
    @Test
    @DisplayName("Should not strip a tail that is not written like a name")
    void parse_ShouldNotStripTail_WhenNotTitleCase() {
        String locations = blocks(
                block("4", "Bangkok, Thailand", "TH", 10),
                block("4", "Tokyo, Japan", "JA", 50));

        // "what Tokyo knows" is part of the headline, so the headline names two countries
        assertTrue(parse("Floods hit Bangkok and surrounding areas - what Tokyo knows", locations).isEmpty());
    }

    // TESTS: a tail that is a place or country in the article stays part of the headline
    @Test
    @DisplayName("Should not strip a tail that is a place")
    void parse_ShouldNotStripTail_WhenTailIsAPlace() {
        Optional<GkgArticle> city = parse("Storm damage - Bangkok", block("4", "Bangkok, Thailand", "TH", 10));
        Optional<GkgArticle> country = parse("Heavy rain expected across the region - Thailand",
                block("1", "Thailand", "TH", 10));

        assertEquals(Set.of("TH"), city.orElseThrow().countryCodes());
        assertEquals(Set.of("TH"), country.orElseThrow().countryCodes());
    }

    // ===== NAMES THAT CONTAIN OTHER PLACE NAMES =====

    // TESTS: "South Sudan" is not also "Sudan" (the article lists both, as GDELT often does)
    @Test
    @DisplayName("Should not count Sudan as named when the headline says South Sudan")
    void parse_ShouldPreferTheLongerName_SouthSudan() {
        String locations = blocks(
                block("1", "South Sudan", "OD", 5),
                block("1", "Sudan", "SU", 60));

        Optional<GkgArticle> result = parseWith("OD,SU", "Floods displace thousands in South Sudan", locations);

        assertEquals(Set.of("OD"), result.orElseThrow().countryCodes());
    }

    // TESTS: the longer name is removed only once, so a real second country is still seen
    @Test
    @DisplayName("Should still see Sudan when the headline names both countries")
    void parse_ShouldStillSeeBothCountries_WhenHeadlineNamesSudanAndSouthSudan() {
        String locations = blocks(
                block("1", "South Sudan", "OD", 5),
                block("1", "Sudan", "SU", 60));

        assertTrue(parseWith("OD,SU", "South Sudan and Sudan agree ceasefire", locations).isEmpty());
    }

    // TESTS: names from gdelt-country-names.txt are removed even when the article has no row for them
    @Test
    @DisplayName("Should not tag Sudan for a South Sudan headline when only Sudan is listed")
    void parse_ShouldNotTagSudan_WhenHeadlineSaysSouthSudanAndOnlySudanIsListed() {
        assertTrue(parseWith("SU", "Floods displace thousands in South Sudan",
                block("1", "Sudan", "SU", 5)).isEmpty());
    }

    @Test
    @DisplayName("Should prefer the Democratic Republic of the Congo over Congo")
    void parse_ShouldPreferTheLongerName_Congo() {
        String locations = blocks(
                block("1", "Democratic Republic of the Congo", "CG", 5),
                block("1", "Congo", "CF", 60));

        Optional<GkgArticle> result = parseWith("CG", "Cholera outbreak in Democratic Republic of the Congo", locations);

        assertEquals(Set.of("CG"), result.orElseThrow().countryCodes());
    }

    @Test
    @DisplayName("Should prefer New York over York")
    void parse_ShouldPreferTheLongerName_NewYork() {
        String locations = blocks(
                block("4", "New York, New York, United States", "US", 5),
                block("4", "York, York, United Kingdom", "UK", 60));

        Optional<GkgArticle> result = parseWith("US,UK", "Storm hits New York", locations);

        assertEquals(Set.of("US"), result.orElseThrow().countryCodes());
    }

    @Test
    @DisplayName("Should prefer Northern Ireland over Ireland")
    void parse_ShouldPreferTheLongerName_NorthernIreland() {
        String locations = blocks(
                block("4", "Northern Ireland, United Kingdom", "UK", 5),
                block("1", "Ireland", "EI", 60));

        Optional<GkgArticle> result = parseWith("UK", "Snow closes roads across Northern Ireland", locations);

        assertEquals(Set.of("UK"), result.orElseThrow().countryCodes());
    }

    @Test
    @DisplayName("Should prefer New Mexico over Mexico")
    void parse_ShouldPreferTheLongerName_NewMexico() {
        String locations = blocks(
                block("4", "New Mexico, United States", "US", 5),
                block("1", "Mexico", "MX", 60));

        Optional<GkgArticle> result = parseWith("US", "Wildfire spreads in New Mexico", locations);

        assertEquals(Set.of("US"), result.orElseThrow().countryCodes());
    }

    @Test
    @DisplayName("Should prefer North Korea over Korea")
    void parse_ShouldPreferTheLongerName_NorthKorea() {
        String locations = blocks(
                block("1", "North Korea", "KN", 5),
                block("1", "Korea", "KS", 60));

        Optional<GkgArticle> result = parseWith("KN", "North Korea fires missile", locations);

        assertEquals(Set.of("KN"), result.orElseThrow().countryCodes());
    }

    // ===== STEP 2 MARGIN =====

    // TESTS: a configured country mentioned 3 times is not tagged when another country is mentioned far more
    @Test
    @DisplayName("Should drop a row when an unsupported country is mentioned much more than Japan")
    void parse_ShouldDropRow_WhenAnotherCountryDominates() {
        String locations = blocks(
                repeat("4", "Tokyo, Japan", "JA", 3),
                repeat("3", "New York, United States", "US", 10));

        assertTrue(parse("Flights cancelled after storm", locations).isEmpty());
    }

    // TESTS: at least twice as many mentions as the runner-up is enough, even if the runner-up is configured
    @Test
    @DisplayName("Should tag the country with at least twice the mentions of the next one")
    void parse_ShouldTag_WhenCountryHasTwiceTheMentions() {
        String locations = blocks(
                repeat("4", "Tokyo, Japan", "JA", 6),
                repeat("4", "Bangkok, Thailand", "TH", 3));

        assertEquals(Set.of("JA"), parse("Flights cancelled after storm", locations).orElseThrow().countryCodes());
    }

    @Test
    @DisplayName("Should drop a row when the top country has less than twice the next one")
    void parse_ShouldDropRow_WhenMarginIsTooThin() {
        String locations = blocks(
                repeat("4", "Tokyo, Japan", "JA", 5),
                repeat("4", "Bangkok, Thailand", "TH", 3));

        assertTrue(parse("Flights cancelled after storm", locations).isEmpty());
    }

    // ===== TITLE KEYWORD / THEME FILTER =====

    // TESTS: if (keywords != null && !keywords.matcher(title).find() && !hasTheme(f[8]))
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