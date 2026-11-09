package csd.tripsense.gdelt;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/** GkgParser: turns one tab-separated row into an article, or drops it.
 * It keeps web, non-translated rows with a PAGE_TITLE and gives each one exactly one country,
 * the place the story is about, not where the outlet is. The country is chosen in this order:
 * <ol>
 *   <li>the headline names a place (from GDELT's location column) in exactly one country, and that
 *       country is in gdelt.country-codes: use it;</li>
 *   <li>the headline names no place, and one configured country is mentioned at least
 *       min-location-mentions times in the article and at least twice as often as any other
 *       country (configured or not): use it;</li>
 *   <li>anything else (several countries in the headline, a country we do not support, no clear
 *       country) is dropped.</li>
 * </ol>
 * Nationalities such as "Polish" are ignored as headline places: GDELT lists them as country-level
 * locations too, but they say who is involved, not where. If gdelt.title-keywords is set, the
 * title must also match a keyword (or the article a theme); if it is empty, topic
 * relevance is left to the AI filter. It decodes HTML entities in titles. */
@Component
public class GkgParser {

    private static final DateTimeFormatter BATCH = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final Pattern TITLE = Pattern.compile("<PAGE_TITLE>(.*?)</PAGE_TITLE>", Pattern.DOTALL);
    private static final Pattern NOT_LETTER_OR_DIGIT = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final String COUNTRY_NAMES_FILE = "/gdelt-country-names.txt";
    private static final int MIN_PLACE_NAME_LENGTH = 3;     // ignores two-letter names that match by accident
    // " | ", " – ", " — ", " · ", " :: " or " - " with spaces around it: where an outlet name usually starts
    private static final Pattern OUTLET_SEPARATOR =
            Pattern.compile("\\s+(?:\\||\\u2013|\\u2014|\\u00b7|::|-)\\s+");
    private static final int MAX_OUTLET_WORDS = 6;
    // small words that stay lower case inside a Title Case outlet name ("The Times of India")
    private static final Set<String> TITLE_CASE_SMALL_WORDS = Set.of(
            "a", "an", "and", "at", "de", "del", "der", "des", "du", "for", "in", "la", "le",
            "of", "on", "the", "to", "van", "von", "y", "e", "&");
    // step 2 only tags a country that is mentioned at least this many times more than any other country
    private static final int MIN_MARGIN = 2;

    private final Set<String> countryCodes;
    private final int minMentions;
    private final Pattern keywords;
    private final List<String> themePrefixes;
    private final Set<String> countryNames;                  // lower case, from gdelt-country-names.txt
    private final List<String> countryNeedles;               // the same names, normalised for matching

    public GkgParser(@Value("${gdelt.country-codes}") String countryCodes,
                     @Value("${gdelt.min-location-mentions}") int minMentions,
                     @Value("${gdelt.title-keywords}") String titleKeywords,
                     @Value("${gdelt.theme-prefixes:}") String themePrefixes) {
        this.countryCodes = Set.copyOf(Arrays.asList(countryCodes.split("\\s*,\\s*")));
        this.minMentions = minMentions;
        this.keywords = titleKeywords.isBlank()
            ? null
            : Pattern.compile("(?i)\\b(" + String.join("|", titleKeywords.split("\\s*,\\s*")) + ")");
        this.themePrefixes = themePrefixes.isBlank()
                ? List.of()
                : Arrays.asList(themePrefixes.split("\\s*,\\s*"));
        this.countryNames = loadCountryNames();
        this.countryNeedles = countryNames.stream()
                .filter(n -> n.length() >= MIN_PLACE_NAME_LENGTH)
                .map(GkgParser::normalise)
                .toList();
    }

    /** Returns an article if this GKG row is a titled, English web article about one supported country. */
    public Optional<GkgArticle> parse(String line) {

        String[] f = line.split("\t", -1);
        if (f.length < 27) return Optional.empty();
        if (!"1".equals(f[2]) || f[0].contains("-T")) return Optional.empty();  // web pages only, no machine-translated rows
        if (f[4].isBlank()) return Optional.empty();

        String title = extractTitle(f[26]);
        if (title == null) return Optional.empty();

        Optional<String> country = tagCountry(title, f[10]);
        if (country.isEmpty()) return Optional.empty();

        if (keywords != null && !keywords.matcher(title).find() && !hasTheme(f[8])) return Optional.empty();

        try {
            LocalDateTime published = LocalDateTime.parse(f[1], BATCH);
            String description = buildDescription(f[3], f[10], f[8]);
            return Optional.of(new GkgArticle(f[4], title, description, f[3], published, Set.of(country.get())));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private String extractTitle(String extras) {
        Matcher m = TITLE.matcher(extras);
        if (!m.find()) return null;
        String t = HtmlUtils.htmlUnescape(m.group(1)).strip();   // GDELT escapes characters as HTML entities
        if (t.isEmpty()) return null;
        return t.length() > 500 ? t.substring(0, 500) : t;
    }

    // ---- country tagging -------------------------------------------------------------------

    /** The one country this story is about, or empty if it cannot be told. */
    private Optional<String> tagCountry(String title, String locations) {
        List<String[]> blocks = locationBlocks(locations);

        // 1. the headline names a place (the outlet name at the end of the title does not count)
        Set<String> inHeadline = countriesNamedInHeadline(withoutOutletName(title, blocks), blocks);
        if (inHeadline.size() == 1) {
            String code = inHeadline.iterator().next();
            return countryCodes.contains(code) ? Optional.of(code) : Optional.empty();
        }
        if (inHeadline.size() > 1) return Optional.empty();   // several countries, not clear which one

        // 2. the headline names no place: fall back to the one country the article keeps mentioning
        return onlyCountryMentionedOften(blocks);
    }

    // Location blocks look like Type#FullName#CountryCode#ADM1#ADM2#Lat#Long#FeatureID#Offset
    private List<String[]> locationBlocks(String locations) {
        List<String[]> blocks = new ArrayList<>();
        for (String block : locations.split(";")) {
            String[] p = block.split("#", -1);
            if (p.length > 2 && !p[2].isBlank()) blocks.add(p);
        }
        return blocks;
    }

    // Many titles end with the outlet's name: "Floods hit Bangkok | Tokyo Daily News", "... – NBC Los Angeles".
    // The outlet is not part of the story and its name can contain a place ("Bridport and Lyme Regis
    // News"), so it is cut off before looking for places. Only the last separator is used, and only when
    //   - what follows is short (at most MAX_OUTLET_WORDS words) and shorter than the headline before it,
    //   - it is written in Title Case like a name ("Tokyo Daily News"), not a descriptor
    //     ("what we know", "live updates"), which is part of the headline,
    //   - and it is not itself the name of a place or country in the article ("Storm damage - Bangkok").
    // The stored title is not changed, this only affects where places are looked for.
    private String withoutOutletName(String title, List<String[]> blocks) {
        Matcher m = OUTLET_SEPARATOR.matcher(title);
        int start = -1;
        int end = -1;
        while (m.find()) {
            start = m.start();
            end = m.end();
        }
        if (start < 0) return title;

        String headline = title.substring(0, start).strip();
        String tail = title.substring(end).strip();
        if (headline.isEmpty() || tail.isEmpty()) return title;
        if (tail.split("\\s+").length > MAX_OUTLET_WORDS || tail.length() >= headline.length()) return title;
        if (!looksLikeName(tail)) return title;

        String tailKey = tail.toLowerCase(Locale.ROOT);
        if (countryNames.contains(tailKey)) return title;                     // "Floods - Thailand"
        for (String[] p : blocks) {
            for (String place : placeNames(p[1].strip())) {
                if (place.toLowerCase(Locale.ROOT).equals(tailKey)) return title;   // "Storm damage - Bangkok"
            }
        }
        return headline;
    }

    // True if every word that starts with a letter is capitalised, apart from small words such as "of".
    private static boolean looksLikeName(String text) {
        for (String word : text.split("\\s+")) {
            int i = 0;
            while (i < word.length() && !Character.isLetter(word.charAt(i))) i++;
            if (i == word.length()) continue;                              // "2024", "&": nothing to check
            if (Character.isUpperCase(word.charAt(i))) continue;
            if (!TITLE_CASE_SMALL_WORDS.contains(word.toLowerCase(Locale.ROOT))) return false;
        }
        return true;
    }

    // Country codes of every location whose name appears in the headline.
    // Longer names are matched first and removed from the headline once matched, so "South Sudan" does not
    // also count as "Sudan", nor "New York" as "York", "Northern Ireland" as "Ireland", "New Mexico" as
    // "Mexico" or "North Korea" as "Korea". Country names from gdelt-country-names.txt take part in the
    // removal even when the article has no location row for them.
    private Set<String> countriesNamedInHeadline(String title, List<String[]> blocks) {
        String headline = normalise(title);

        // needle -> country codes of the locations with that name (empty for a list-only name)
        Map<String, Set<String>> found = new HashMap<>();
        for (String needle : countryNeedles) {
            if (headline.contains(needle)) found.computeIfAbsent(needle, k -> new HashSet<>());
        }
        for (String[] p : blocks) {
            String name = p[1].strip();
            if (name.isEmpty()) continue;
            // type 1 = country level. GDELT also files nationalities here ("Polish", "Venezuelan"),
            // so only names listed in gdelt-country-names.txt count as a place
            if ("1".equals(p[0]) && !countryNames.contains(name.toLowerCase(Locale.ROOT))) continue;
            for (String candidate : placeNames(name)) {
                String needle = normalise(candidate);
                if (candidate.length() >= MIN_PLACE_NAME_LENGTH && needle.length() > 2
                        && headline.contains(needle)) {
                    found.computeIfAbsent(needle, k -> new HashSet<>()).add(p[2]);
                }
            }
        }

        List<String> longestFirst = new ArrayList<>(found.keySet());
        longestFirst.sort((x, y) -> y.length() - x.length());

        Set<String> codes = new HashSet<>();
        for (String needle : longestFirst) {
            if (!headline.contains(needle)) continue;          // already used up by a longer name
            codes.addAll(found.get(needle));
            headline = headline.replace(needle, " ");          // the needle starts and ends with a space
        }
        return codes;
    }

    // "Kelantan, Kelantan, Malaysia" -> the place itself (first part) and the country (last part)
    private Set<String> placeNames(String fullName) {
        List<String> parts = Arrays.stream(fullName.split(","))
                .map(String::strip).filter(s -> !s.isEmpty()).toList();
        Set<String> names = new LinkedHashSet<>();
        if (!parts.isEmpty()) {
            names.add(parts.get(0));
            names.add(parts.get(parts.size() - 1));
        }
        return names;
    }

    // The country the article keeps mentioning, or empty if there is not one clear winner. Every country
    // is counted, supported or not, so an article that mentions the US ten times and Japan three times
    // is not tagged Japan. The winner must be configured, have at least min-location-mentions
    // mentions and at least MIN_MARGIN times as many as the runner-up.
    private Optional<String> onlyCountryMentionedOften(List<String[]> blocks) {
        Map<String, Integer> counts = new HashMap<>();
        for (String[] p : blocks) counts.merge(p[2], 1, Integer::sum);

        String top = null;
        int topCount = 0;
        int second = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > topCount) {
                second = topCount;
                topCount = e.getValue();
                top = e.getKey();
            } else if (e.getValue() > second) {
                second = e.getValue();
            }
        }
        if (top == null || !countryCodes.contains(top)) return Optional.empty();
        if (topCount < minMentions || topCount < MIN_MARGIN * second) return Optional.empty();
        return Optional.of(top);
    }

    // lower case, punctuation turned into spaces, padded with spaces, so "Poland's" contains " poland "
    private static String normalise(String text) {
        String t = NOT_LETTER_OR_DIGIT.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
        return " " + t + " ";
    }

    private Set<String> loadCountryNames() {
        try (InputStream in = GkgParser.class.getResourceAsStream(COUNTRY_NAMES_FILE)) {
            if (in == null) {
                throw new IllegalStateException(COUNTRY_NAMES_FILE + " is missing from src/main/resources");
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return reader.lines()
                    .map(String::strip)
                    .filter(s -> !s.isEmpty() && !s.startsWith("#"))
                    .map(s -> s.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toUnmodifiableSet());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- themes and description ------------------------------------------------------------

    // Theme blocks look like THEME_NAME,offset
    private boolean hasTheme(String themes) {
        if (themePrefixes.isEmpty()) return false;
        for (String block : themes.split(";")) {
            String name = block.split(",", 2)[0];
            for (String prefix : themePrefixes) {
                if (name.startsWith(prefix)) return true;
            }
        }
        return false;
    }

    // A short plain-text context line built from GDELT's metadata. It does not summarise the article.
private String buildDescription(String source, String locations, String themes) {
    List<String> places = topPlaces(locations, 4);
    List<String> topics = matchedThemes(themes, 4);

    StringBuilder sb = new StringBuilder("Reported by ").append(source).append(".");
    if (!places.isEmpty()) {
        sb.append(" Places mentioned: ").append(String.join("; ", places)).append(".");
    }
    if (!topics.isEmpty()) {
        sb.append(" Topics: ").append(String.join(", ", topics)).append(".");
    }
    sb.append(" (Generated from GDELT metadata; the article text was not read.)");
    return sb.toString();
}

    // The most frequently mentioned place names in the article
    private List<String> topPlaces(String locations, int limit) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String block : locations.split(";")) {
            String[] p = block.split("#", -1);
            if (p.length > 2 && !p[1].isBlank()) {
                counts.merge(p[1].strip(), 1, Integer::sum);
            }
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
    }

    // Only themes matching gdelt.theme-prefixes, in readable form (NATURAL_DISASTER -> "natural disaster")
    private List<String> matchedThemes(String themes, int limit) {
        if (themePrefixes.isEmpty()) return List.of();
        Set<String> found = new LinkedHashSet<>();
        for (String block : themes.split(";")) {
            String name = block.split(",", 2)[0];
            for (String prefix : themePrefixes) {
                if (name.startsWith(prefix)) {
                    found.add(name.toLowerCase().replace('_', ' '));
                    break;
                }
            }
        }
        return found.stream().limit(limit).toList();
    }
}