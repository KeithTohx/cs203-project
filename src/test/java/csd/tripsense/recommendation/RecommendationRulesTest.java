package csd.tripsense.recommendation;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import csd.tripsense.activity.ActivityCategory;

import static csd.tripsense.activity.ActivityCategory.FOOD_DINING;
import static csd.tripsense.activity.ActivityCategory.MUSEUM_GALLERY;
import static csd.tripsense.activity.ActivityCategory.SHOPPING;
import static csd.tripsense.activity.ActivityCategory.SKI_SNOW;
import static csd.tripsense.activity.ActivityCategory.WELLNESS_SPA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// no spring context on purpose, the rules are plain logic so this runs in milliseconds
class RecommendationRulesTest {

    // real coordinates, so the distances the rules work out are the real ones.
    // niseko to sapporo is about 60km, niseko to tokyo about 800km
    private static final Map<String, double[]> WHERE = Map.of(
            "Niseko",     new double[] { 42.8048, 140.6874 },
            "Sapporo",    new double[] { 43.0618, 141.3545 },
            "Tokyo",      new double[] { 35.6762, 139.6503 },
            "Bangkok",    new double[] { 13.7563, 100.5018 },
            "Samae Dam",  new double[] { 13.6500, 100.4500 },   // a district of bangkok, ~13km out
            "Ayutthaya",  new double[] { 14.3532, 100.5689 },   // ~67km from bangkok
            "Krabi",      new double[] {  8.0863,  98.9063 },
            "Thailand",   new double[] { 15.0000, 101.0000 });  // a few rows only know the country

    private ActivityOption option(long id, String area, ActivityCategory category, String name) {
        double[] at = WHERE.get(area);
        return new ActivityOption(id, "Japan", area, category, name, area + ", Japan",
                at[0], at[1]);
    }

    // an option from before we started keeping coordinates
    private ActivityOption uncharted(long id, String area, ActivityCategory category, String name) {
        return new ActivityOption(id, "Japan", area, category, name, area + ", Japan", null, null);
    }

    @Test
    void suggestionsAreAlwaysIndoor() {
        assertTrue(RecommendationRules.substitutesFor(SKI_SNOW).stream()
                .allMatch(ActivityCategory::isIndoor));
    }

    @Test
    void nullCategoryGivesNoSubstitutes() {
        // activities created before the category column existed
        assertTrue(RecommendationRules.substitutesFor(null).isEmpty());
    }

    @Test
    void onlySubstituteCategoriesAreOffered() {
        // another ski field is no use when the snow is the problem, so it never reaches the model
        List<ActivityOption> options = List.of(
                option(1, "Sapporo", SKI_SNOW, "another ski field"),
                option(2, "Sapporo", MUSEUM_GALLERY, "a museum"));

        List<ActivityOption> result = RecommendationRules.shortlist(
                options, SKI_SNOW, "Sapporo, Japan", "");

        assertEquals(1, result.size());
        assertEquals("a museum", result.get(0).getName());
    }

    @Test
    void indoorActivityPrefersItsOwnKind() {
        // same area, so distance cannot separate them and the kind decides
        List<ActivityOption> options = List.of(
                option(1, "Sapporo", FOOD_DINING, "a restaurant"),
                option(2, "Sapporo", MUSEUM_GALLERY, "another museum"));

        List<ActivityOption> result = RecommendationRules.shortlist(
                options, MUSEUM_GALLERY, "Higashi-ku, Sapporo, Japan", "");

        assertEquals("another museum", result.get(0).getName());
    }

    @Test
    void indoorActivityStillGetsSuggestionsWhenItsOwnKindIsMissing() {
        // the onsen case: wikidata has no japanese spas, so an impacted onsen must still
        // be offered something rather than nothing
        List<ActivityOption> options = List.of(
                option(1, "Sapporo", MUSEUM_GALLERY, "a museum"));

        List<ActivityOption> result = RecommendationRules.shortlist(
                options, WELLNESS_SPA, "Jozankei Onsen, Sapporo, Japan", "");

        assertEquals(1, result.size());
    }

    @Test
    void rightPlaceBeatsRightKind() {
        List<ActivityOption> options = List.of(
                option(1, "Tokyo", MUSEUM_GALLERY, "tokyo museum"),
                option(2, "Sapporo", FOOD_DINING, "sapporo restaurant"));

        List<ActivityOption> result = RecommendationRules.shortlist(
                options, MUSEUM_GALLERY, "Higashi-ku, Sapporo, Japan", "");

        assertEquals("sapporo restaurant", result.get(0).getName());
    }

    @Test
    void nearerCityBeatsFartherCity() {
        // the whole point of keeping coordinates. ids are deliberately backwards, so ordering
        // by id the way we used to would have put tokyo first
        List<ActivityOption> options = List.of(
                option(1, "Tokyo", MUSEUM_GALLERY, "tokyo museum"),
                option(2, "Sapporo", MUSEUM_GALLERY, "sapporo museum"),
                option(3, "Niseko", MUSEUM_GALLERY, "niseko museum"));

        List<ActivityOption> result = RecommendationRules.shortlist(
                options, MUSEUM_GALLERY, "Niseko, Hokkaido, Japan", "");

        assertEquals(List.of("niseko museum", "sapporo museum", "tokyo museum"),
                result.stream().map(ActivityOption::getName).toList());
    }

    @Test
    void fallsBackToAreaMatchingWhenNothingHasCoordinates() {
        // the 180 rows generated before coordinates were kept still have to rank sensibly
        List<ActivityOption> options = List.of(
                uncharted(1, "Tokyo", MUSEUM_GALLERY, "tokyo museum"),
                uncharted(2, "Sapporo", MUSEUM_GALLERY, "sapporo museum"));

        List<ActivityOption> result = RecommendationRules.shortlist(
                options, SKI_SNOW, "Higashi-ku, Sapporo, Japan", "");

        assertEquals("sapporo museum", result.get(0).getName());
    }

    @Test
    void anOptionWithNoCoordinatesSinksBelowOneWeCanPlace() {
        List<ActivityOption> options = List.of(
                uncharted(1, "Tokyo", MUSEUM_GALLERY, "unplaceable museum"),
                option(2, "Sapporo", MUSEUM_GALLERY, "sapporo museum"));

        List<ActivityOption> result = RecommendationRules.shortlist(
                options, SKI_SNOW, "Higashi-ku, Sapporo, Japan", "");

        assertEquals("sapporo museum", result.get(0).getName());
    }

    @Test
    void areasNamedInTheNewsAreDropped() {
        List<ActivityOption> options = List.of(
                option(1, "Bangkok", MUSEUM_GALLERY, "bangkok museum"),
                option(2, "Krabi", FOOD_DINING, "krabi restaurant"));

        List<ActivityOption> result = RecommendationRules.shortlist(
                options, MUSEUM_GALLERY, "Bangkok, Thailand",
                "Flooding closes Bangkok tourist areas");

        assertFalse(result.stream().anyMatch(o -> o.getArea().equals("Bangkok")));
        assertEquals(1, result.size());
    }

    @Test
    void nearbyOptionsSinkWhenTheNewsNamesTheActivitysOwnArea() {
        // samae dam is a district of bangkok, so a bangkok flood catches it too even though the
        // news never says its name. with thousands of areas most of them are districts like this
        List<ActivityOption> options = List.of(
                option(1, "Bangkok", MUSEUM_GALLERY, "bangkok museum"),
                option(2, "Samae Dam", SHOPPING, "samae dam mall"),
                option(3, "Ayutthaya", MUSEUM_GALLERY, "ayutthaya museum"));

        List<ActivityOption> result = RecommendationRules.shortlist(
                options, MUSEUM_GALLERY, "Na Phra Lan Rd, Bangkok, Thailand",
                "Flooding closes Bangkok tourist areas");

        assertEquals("ayutthaya museum", result.get(0).getName());
        assertFalse(result.stream().anyMatch(o -> o.getArea().equals("Bangkok")));
    }

    @Test
    void aBroadAreaNameDoesNotDragTheOriginAwayFromTheCity() {
        // "Thailand" is the area on a handful of rows where wikidata only knew the country, and
        // it appears in every thai address. averaging it in with Bangkok moved the origin 40km
        // into the gulf, which was enough for the nearby guard below to miss
        List<ActivityOption> options = List.of(
                option(1, "Bangkok", MUSEUM_GALLERY, "bangkok museum"),
                option(2, "Samae Dam", SHOPPING, "samae dam mall"),
                option(3, "Ayutthaya", MUSEUM_GALLERY, "ayutthaya museum"),
                option(4, "Thailand", MUSEUM_GALLERY, "vague museum"));

        List<ActivityOption> result = RecommendationRules.shortlist(
                options, MUSEUM_GALLERY, "Na Phra Lan Rd, Bangkok, Thailand",
                "Flooding closes Bangkok tourist areas");

        assertEquals("ayutthaya museum", result.get(0).getName());
    }

    @Test
    void aNearbyOptionIsStillOfferedWhenThereIsNothingFurtherOut() {
        // sunk, not dropped. somewhere 13km away beats telling the traveller nothing
        List<ActivityOption> options = List.of(
                option(1, "Bangkok", MUSEUM_GALLERY, "bangkok museum"),
                option(2, "Samae Dam", SHOPPING, "samae dam mall"));

        List<ActivityOption> result = RecommendationRules.shortlist(
                options, MUSEUM_GALLERY, "Na Phra Lan Rd, Bangkok, Thailand",
                "Flooding closes Bangkok tourist areas");

        assertEquals(1, result.size());
        assertEquals("samae dam mall", result.get(0).getName());
    }

    @Test
    void farActivityStillGetsCountryWideSuggestions() {
        // nothing is tagged Niseko in this list, so it falls through to the rest of japan
        List<ActivityOption> options = List.of(
                option(1, "Sapporo", MUSEUM_GALLERY, "sapporo museum"));

        assertEquals(1, RecommendationRules.shortlist(
                options, SKI_SNOW, "Niseko, Hokkaido, Japan", "").size());
    }

    @Test
    void neverReturnsMoreThanTheCap() {
        List<ActivityOption> options = List.of(
                option(1, "Sapporo", MUSEUM_GALLERY, "a"),
                option(2, "Sapporo", MUSEUM_GALLERY, "b"),
                option(3, "Sapporo", MUSEUM_GALLERY, "c"),
                option(4, "Sapporo", MUSEUM_GALLERY, "d"));

        assertEquals(RecommendationRules.MAX_CANDIDATES, RecommendationRules.shortlist(
                options, SKI_SNOW, "Sapporo, Japan", "").size());
    }

    @Test
    void activityWithNoCategoryGetsNoShortlist() {
        // older rows have no category, so there is nothing to base a substitute on
        List<ActivityOption> options = List.of(option(1, "Sapporo", MUSEUM_GALLERY, "a museum"));

        assertTrue(RecommendationRules.shortlist(
                options, null, "Sapporo, Japan", "").isEmpty());
    }

    @Test
    void longerAreaNameWinsWhenTwoStartAtTheSamePlace() {
        // "bang" and "bangkok" both start the address, and bangkok is the one meant. the "bang"
        // row is placed in krabi on purpose, so picking it would move the origin 650km south
        double[] krabi = WHERE.get("Krabi");
        List<ActivityOption> options = List.of(
                new ActivityOption(1L, "Thailand", "Bang", MUSEUM_GALLERY, "bang museum", "Bang, Thailand",
                        krabi[0], krabi[1]),
                option(2, "Bangkok", MUSEUM_GALLERY, "bangkok museum"));

        List<ActivityOption> result = RecommendationRules.shortlist(
                options, SKI_SNOW, "Bangkok, Thailand", "");

        assertEquals("bangkok museum", result.get(0).getName());
    }

    @Test
    void anOptionWithNoAreaIsNeverTakenAsTheActivitysArea() {
        // an empty area is found at the start of every address, so without the guard this row
        // would become the origin and pull the ranking towards krabi
        double[] krabi = WHERE.get("Krabi");
        List<ActivityOption> options = List.of(
                new ActivityOption(1L, "Thailand", "", MUSEUM_GALLERY, "unnamed museum", "Thailand",
                        krabi[0], krabi[1]),
                option(2, "Bangkok", MUSEUM_GALLERY, "bangkok museum"),
                option(3, "Ayutthaya", MUSEUM_GALLERY, "ayutthaya museum"));

        List<ActivityOption> result = RecommendationRules.shortlist(
                options, SKI_SNOW, "Na Phra Lan Rd, Bangkok, Thailand", "");

        assertEquals(List.of("bangkok museum", "ayutthaya museum", "unnamed museum"),
                result.stream().map(ActivityOption::getName).toList());
    }

    @Test
    void emptyOptionsDoesNotThrow() {
        assertTrue(RecommendationRules.shortlist(
                List.of(), SKI_SNOW, "Sapporo", "anything").isEmpty());
    }

    @Test
    void handlesNullAddressAndNullNews() {
        List<ActivityOption> options = List.of(option(1, "Sapporo", MUSEUM_GALLERY, "museum"));
        assertEquals(1, RecommendationRules.shortlist(options, SKI_SNOW, null, null).size());
    }
}
