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
 *   <li>the headline names no place, and exactly one configured country is mentioned at least
 *       min-location-mentions times in the article: use it;</li>
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

    private final Set<String> countryCodes;
    private final int minMentions;
    private final Pattern keywords;
    private final List<String> themePrefixes;
    private final Set<String> countryNames;                  // lower case, from gdelt-country-names.txt

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

        // 1. the headline names a place
        Set<String> inHeadline = countriesNamedInHeadline(title, blocks);
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

    // Country codes of every location whose name appears in the headline.
    private Set<String> countriesNamedInHeadline(String title, List<String[]> blocks) {
        String headline = normalise(title);
        Set<String> codes = new HashSet<>();
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
                    codes.add(p[2]);
                }
            }
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

    // Exactly one configured country with at least min-location-mentions mentions, else empty.
    private Optional<String> onlyCountryMentionedOften(List<String[]> blocks) {
        Map<String, Integer> counts = new HashMap<>();
        for (String[] p : blocks) {
            if (countryCodes.contains(p[2])) counts.merge(p[2], 1, Integer::sum);
        }
        List<String> often = counts.entrySet().stream()
                .filter(e -> e.getValue() >= minMentions)
                .map(Map.Entry::getKey)
                .toList();
        return often.size() == 1 ? Optional.of(often.get(0)) : Optional.empty();
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