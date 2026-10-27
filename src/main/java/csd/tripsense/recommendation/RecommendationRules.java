package csd.tripsense.recommendation;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import csd.tripsense.activity.ActivityCategory;

import static csd.tripsense.activity.ActivityCategory.FOOD_DINING;
import static csd.tripsense.activity.ActivityCategory.MUSEUM_GALLERY;
import static csd.tripsense.activity.ActivityCategory.SHOPPING;
import static csd.tripsense.activity.ActivityCategory.WELLNESS_SPA;

/**
 * Picks which alternatives to offer. No Spring and no AI, so it is cheap to test and the
 * same inputs always give the same answer.
 *
 * The model is handed the shortlist this produces and may only choose from it. That is
 * the whole guard against a 1.5B model inventing a venue that does not exist.
 */
public final class RecommendationRules {

    public static final int MAX_CANDIDATES = 3;

    // whatever went wrong, something indoors is the safer suggestion. an outdoor plan was
    // probably ruined by weather, and an indoor one that closed is still best replaced by
    // something else under a roof
    private static final Set<ActivityCategory> INDOOR =
            Set.of(MUSEUM_GALLERY, FOOD_DINING, SHOPPING, WELLNESS_SPA);

    // how much detour we will accept to keep an indoor activity in its own category. inside one
    // city everything is closer than this, so the kind decides, and between cities distance wins
    private static final double SAME_KIND_DETOUR_KM = 15;

    // when the news names the area the activity is in, anything this close is probably caught
    // up in the same trouble
    private static final double NEWS_NEARBY_KM = 30;

    // big enough to sink a nearby option below anything else in the country, but still a
    // number, so we offer it anyway rather than nothing when the country has nothing further out
    private static final double NEWS_NEARBY_PENALTY_KM = 5_000;

    // further than anywhere on earth, used when we cannot place an option at all
    private static final double NOWHERE_KM = 50_000;

    private static final double EARTH_RADIUS_KM = 6371;

    private RecommendationRules() {
    }

    public static Set<ActivityCategory> substitutesFor(ActivityCategory category) {
        return category == null ? Set.of() : INDOOR;
    }

    /**
     * Narrows a country's options down to the few worth offering for one disrupted activity.
     *
     * @param options  every option in the trip's country, categories included
     * @param category what the disrupted activity was, may be null on older rows
     * @param address  the activity's address, matched against an option's area
     * @param newsText headline plus description, used to drop areas the news itself names
     */
    public static List<ActivityOption> shortlist(List<ActivityOption> options,
                                                 ActivityCategory category,
                                                 String address, String newsText) {
        Set<ActivityCategory> substitutes = substitutesFor(category);
        if (substitutes.isEmpty()) {
            return List.of();
        }

        String where = lower(address);
        String news = lower(newsText);

        // an activity address has no coordinates, so the nearest thing to the activity's
        // position is the middle of whichever area the address happens to name
        double[] origin = originFor(options, where);

        // a closed museum is best replaced by another museum. a cancelled ski day is not
        // replaced by more skiing, so only indoor activities prefer their own kind
        boolean preferSameKind = category != null && category.isIndoor();

        Context context = new Context(origin, where, category, preferSameKind,
                                      localAreaIsInTheNews(options, where, news));

        return options.stream()
                .filter(option -> substitutes.contains(option.getCategory()))
                // a keyword guard, not geography. "flooding closes bangkok" drops every
                // bangkok option, but a quake headline naming only hokkaido keeps sapporo
                .filter(option -> news.isEmpty() || !news.contains(lower(option.getArea())))
                .sorted(Comparator
                        .comparingDouble((ActivityOption option) -> score(option, context))
                        .thenComparing(ActivityOption::getId))
                .limit(MAX_CANDIDATES)
                .toList();
    }

    /** What the ranking needs to know, kept together so score stays readable. */
    private record Context(double[] origin, String where, ActivityCategory category,
                           boolean preferSameKind, boolean localAreaHit) {
    }

    /**
     * Whether the news names the place the activity is in.
     *
     * The filter above only drops an option whose own area name the news happens to say, which
     * was enough when there were ten areas. Worldwide there are thousands and most of them are
     * districts, so "flooding closes Bangkok" would still cheerfully offer a mall in Samae Dam,
     * which is in Bangkok. If the trouble is local then everything nearby is suspect.
     */
    private static boolean localAreaIsInTheNews(List<ActivityOption> options, String where,
                                                String news) {
        if (where.isEmpty() || news.isEmpty()) {
            return false;
        }
        return options.stream()
                .map(option -> lower(option.getArea()))
                .anyMatch(area -> !area.isEmpty() && news.contains(area) && where.contains(area));
    }

    /**
     * The middle of the options belonging to whichever area the address names, or null when
     * the address names nowhere we have coordinates for.
     *
     * The centre of an area's notable places is close enough to the centre of the area itself
     * for ranking, and it saves keeping a separate table of places and their coordinates.
     */
    private static double[] originFor(List<ActivityOption> options, String where) {
        String area = narrowestAreaIn(options, where);
        if (area == null) {
            return null;
        }

        double latitude = 0, longitude = 0;
        int found = 0;
        for (ActivityOption option : options) {
            if (option.getLatitude() == null || option.getLongitude() == null
                    || !lower(option.getArea()).equals(area)) {
                continue;
            }
            latitude += option.getLatitude();
            longitude += option.getLongitude();
            found++;
        }
        return found == 0 ? null : new double[] { latitude / found, longitude / found };
    }

    /**
     * The most specific area we know of that the address names.
     *
     * An address is written from the specific to the general, "Chatuchak, Bangkok, Thailand",
     * so whichever area appears earliest in it is the narrowest. Picking one matters: a few
     * rows have the country itself as their area, which appears in every address in that
     * country, and averaging those in with a city put the middle of Bangkok 40km out to sea.
     */
    private static String narrowestAreaIn(List<ActivityOption> options, String where) {
        if (where.isEmpty()) {
            return null;
        }

        String narrowest = null;
        int earliest = Integer.MAX_VALUE;
        for (ActivityOption option : options) {
            if (option.getLatitude() == null || option.getLongitude() == null) {
                continue;
            }
            String area = lower(option.getArea());
            int at = area.isEmpty() ? -1 : where.indexOf(area);
            // on a tie the longer name is the more specific one, eg Bangkok over Bang
            if (at >= 0 && (at < earliest
                    || (at == earliest && area.length() > narrowest.length()))) {
                earliest = at;
                narrowest = area;
            }
        }
        return narrowest;
    }

    /** Lower is a better suggestion, measured in km so the signals are all comparable. */
    private static double score(ActivityOption option, Context context) {
        double[] origin = context.origin();
        double score;
        if (origin == null) {
            // no coordinates to measure from, so fall back to whether the address names this
            // area at all, which is all the geography we had before
            score = context.where().contains(lower(option.getArea())) ? 0 : NOWHERE_KM;
        } else if (option.getLatitude() == null || option.getLongitude() == null) {
            score = NOWHERE_KM;
        } else {
            score = distanceKm(origin[0], origin[1], option.getLatitude(), option.getLongitude());
            if (context.localAreaHit() && score < NEWS_NEARBY_KM) {
                score += NEWS_NEARBY_PENALTY_KM;
            }
        }

        if (context.preferSameKind() && option.getCategory() != context.category()) {
            score += SAME_KIND_DETOUR_KM;
        }
        return score;
    }

    // haversine, close enough at city scale and nobody needs a library for it
    private static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.sqrt(a));
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
