package csd.tripsense.gdelt;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/** GkgParser: turns one tab-separated row into an article, or drops it.
 * It keeps web, non-translated rows with a PAGE_TITLE, mentioning one of your
 * countries at least min-location-mentions times. If gdelt.title-keywords is set, the
 * title must also match a keyword (or the article a theme); if it is empty, topic
 * relevance is left to the AI filter. It decodes HTML entities in titles. */
@Component
public class GkgParser {

    private static final DateTimeFormatter BATCH = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final Pattern TITLE = Pattern.compile("<PAGE_TITLE>(.*?)</PAGE_TITLE>", Pattern.DOTALL);

    private final Set<String> countryCodes;
    private final int minMentions;
    private final Pattern keywords;
    private final List<String> themePrefixes;

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
        
    }

    /** Returns an article if this GKG row is a relevant, titled, English web article. */
    public Optional<GkgArticle> parse(String line) {
        
        String[] f = line.split("\t", -1);
        if (f.length < 27) return Optional.empty();
        if (!"1".equals(f[2]) || f[0].contains("-T")) return Optional.empty();  // web pages only, no machine-translated rows
        if (f[4].isBlank()) return Optional.empty();

        String title = extractTitle(f[26]);
        if (title == null) return Optional.empty();

        Set<String> codes = matchedCountries(f[10]);
        if (codes.isEmpty()) return Optional.empty();

        if (keywords != null && !keywords.matcher(title).find() && !hasTheme(f[8])) return Optional.empty();

        try {
            LocalDateTime published = LocalDateTime.parse(f[1], BATCH);
            String description = buildDescription(f[3], f[10], f[8]);
            return Optional.of(new GkgArticle(f[4], title, description, f[3], published, codes));
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

    // Location blocks look like Type#FullName#CountryCode#ADM1#ADM2#Lat#Long#FeatureID#Offset
    private Set<String> matchedCountries(String locations) {
        Map<String, Integer> counts = new HashMap<>();
        for (String block : locations.split(";")) {
            String[] p = block.split("#", -1);
            if (p.length > 2 && countryCodes.contains(p[2])) {
                counts.merge(p[2], 1, Integer::sum);
            }
        }
        return counts.entrySet().stream()
                .filter(e -> e.getValue() >= minMentions)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

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