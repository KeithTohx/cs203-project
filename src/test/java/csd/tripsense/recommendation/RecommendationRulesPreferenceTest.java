package csd.tripsense.recommendation;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import csd.tripsense.activity.ActivityCategory;

import static csd.tripsense.activity.ActivityCategory.FOOD_DINING;
import static csd.tripsense.activity.ActivityCategory.MUSEUM_GALLERY;
import static csd.tripsense.activity.ActivityCategory.SHOPPING;
import static csd.tripsense.activity.ActivityCategory.SKI_SNOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// how a traveller's preferred kinds of activity change the ranking. the same style as
// RecommendationRulesTest: real coordinates, no spring, runs in milliseconds
class RecommendationRulesPreferenceTest {

    private static final Map<String, double[]> WHERE = Map.of(
            "Niseko",     new double[] { 42.8048, 140.6874 },
            "Sapporo",    new double[] { 43.0618, 141.3545 },   // ~60km from niseko
            "Bangkok",    new double[] { 13.7563, 100.5018 },
            "Samae Dam",  new double[] { 13.6500, 100.4500 },   // a district of bangkok, ~13km out
            "Ayutthaya",  new double[] { 14.3532, 100.5689 });  // ~67km from bangkok

    private ActivityOption option(long id, String area, ActivityCategory category, String name) {
        double[] at = WHERE.get(area);
        return new ActivityOption(id, "Japan", area, category, name, area + ", Japan", at[0], at[1]);
    }

    private List<String> names(List<ActivityOption> result) {
        return result.stream().map(ActivityOption::getName).toList();
    }

    @Test
    void aPreferredKindComesFirstWhenEverythingElseIsEqual() {
        // same place, so distance cannot separate them. by id the museum would be first
        List<ActivityOption> options = List.of(
                option(1, "Sapporo", MUSEUM_GALLERY, "museum"),
                option(2, "Sapporo", SHOPPING, "mall"),
                option(3, "Sapporo", FOOD_DINING, "restaurant"));

        List<ActivityOption> preferred = RecommendationRules.shortlist(
                options, SKI_SNOW, "Sapporo, Japan", "", Set.of(FOOD_DINING));
        List<ActivityOption> plain = RecommendationRules.shortlist(
                options, SKI_SNOW, "Sapporo, Japan", "", Set.of());

        assertEquals(List.of("restaurant", "museum", "mall"), names(preferred));
        assertEquals(List.of("museum", "mall", "restaurant"), names(plain));
    }

    @Test
    void severalPreferredKindsAreAllLiftedAboveTheRest() {
        List<ActivityOption> options = List.of(
                option(1, "Sapporo", MUSEUM_GALLERY, "museum"),
                option(2, "Sapporo", SHOPPING, "mall"),
                option(3, "Sapporo", FOOD_DINING, "restaurant"));

        List<ActivityOption> result = RecommendationRules.shortlist(
                options, SKI_SNOW, "Sapporo, Japan", "", Set.of(SHOPPING, FOOD_DINING));

        assertEquals("museum", names(result).get(2));
    }

    @Test
    void aPreferenceBeatsKeepingTheSameKindInsideACity() {
        // a closed museum would normally be replaced by another museum (a 15km penalty on anything
        // else). the traveller's taste is worth more than that, so the mall wins
        List<ActivityOption> options = List.of(
                option(1, "Sapporo", MUSEUM_GALLERY, "another museum"),
                option(2, "Sapporo", SHOPPING, "mall"));

        List<ActivityOption> result = RecommendationRules.shortlist(
                options, MUSEUM_GALLERY, "Sapporo, Japan", "", Set.of(SHOPPING));

        assertEquals("mall", result.get(0).getName());
    }

    @Test
    void aPreferenceDoesNotSendTheTravellerToAnotherCity() {
        // the preferred mall is ~60km away and the other museum is right here. taste is worth 25km,
        // not 60, so the nearby museum still wins
        List<ActivityOption> options = List.of(
                option(1, "Niseko", MUSEUM_GALLERY, "niseko museum"),
                option(2, "Sapporo", SHOPPING, "sapporo mall"));

        List<ActivityOption> result = RecommendationRules.shortlist(
                options, MUSEUM_GALLERY, "Niseko, Hokkaido, Japan", "", Set.of(SHOPPING));

        assertEquals("niseko museum", result.get(0).getName());
    }

    @Test
    void aPreferredPlaceInsideTheTroubleZoneStillSinks() {
        // samae dam is a district of bangkok, so a bangkok flood catches it. safety beats taste, so
        // the traveller's preferred mall is still behind somewhere well outside the flood
        List<ActivityOption> options = List.of(
                option(1, "Bangkok", MUSEUM_GALLERY, "bangkok museum"),   // dropped, the news names it
                option(2, "Samae Dam", SHOPPING, "samae dam mall"),
                option(3, "Ayutthaya", MUSEUM_GALLERY, "ayutthaya museum"));

        List<ActivityOption> result = RecommendationRules.shortlist(
                options, MUSEUM_GALLERY, "Na Phra Lan Rd, Bangkok, Thailand",
                "Flooding closes Bangkok tourist areas", Set.of(SHOPPING));

        assertEquals(List.of("ayutthaya museum", "samae dam mall"), names(result));
    }

    @Test
    void anOutdoorPreferenceCannotBringBackAnOutdoorPlace() {
        // outdoor plans are never suggested as a way out of a disruption, whatever the traveller likes
        List<ActivityOption> options = List.of(
                option(1, "Sapporo", SKI_SNOW, "another ski field"),
                option(2, "Sapporo", MUSEUM_GALLERY, "museum"));

        List<ActivityOption> result = RecommendationRules.shortlist(
                options, MUSEUM_GALLERY, "Sapporo, Japan", "", Set.of(SKI_SNOW));

        assertEquals(List.of("museum"), names(result));
    }

    @Test
    void aPreferenceNoOptionMatchesChangesNothing() {
        List<ActivityOption> options = List.of(
                option(1, "Sapporo", MUSEUM_GALLERY, "museum"),
                option(2, "Sapporo", FOOD_DINING, "restaurant"));

        assertEquals(
                names(RecommendationRules.shortlist(options, SKI_SNOW, "Sapporo, Japan", "")),
                names(RecommendationRules.shortlist(options, SKI_SNOW, "Sapporo, Japan", "", Set.of(SHOPPING))));
    }

    @Test
    void noPreferenceGivesTheSameAnswerAsTheOldFourArgumentCall() {
        List<ActivityOption> options = List.of(
                option(1, "Niseko", MUSEUM_GALLERY, "niseko museum"),
                option(2, "Sapporo", SHOPPING, "sapporo mall"),
                option(3, "Sapporo", FOOD_DINING, "sapporo restaurant"));

        assertEquals(
                names(RecommendationRules.shortlist(options, MUSEUM_GALLERY, "Niseko, Hokkaido, Japan", "")),
                names(RecommendationRules.shortlist(options, MUSEUM_GALLERY, "Niseko, Hokkaido, Japan", "", Set.of())));
    }

    @Test
    void aPreferenceNeverMakesTheShortlistLongerThanTheCap() {
        List<ActivityOption> options = List.of(
                option(1, "Sapporo", SHOPPING, "a"),
                option(2, "Sapporo", SHOPPING, "b"),
                option(3, "Sapporo", SHOPPING, "c"),
                option(4, "Sapporo", SHOPPING, "d"));

        assertTrue(RecommendationRules.shortlist(
                options, SKI_SNOW, "Sapporo, Japan", "", Set.of(SHOPPING)).size()
                <= RecommendationRules.MAX_CANDIDATES);
    }
}
