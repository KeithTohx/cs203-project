package csd.tripsense.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import csd.tripsense.activity.ActivityCategory;
import csd.tripsense.user.User;
import csd.tripsense.user.preference.ExplanationDetail;
import csd.tripsense.user.preference.FlexibilityLevel;
import csd.tripsense.user.preference.RiskTolerance;
import csd.tripsense.user.preference.UserPreference;

// the small static helpers that turn a traveller's preferences into rules and prompt wording.
// no spring and no model, so this runs in milliseconds like RecommendationGuardsTest
class RecommendationPreferenceHelpersTest {

    // ----- helpers -----

    // a row nobody has saved yet: the entity defaults, and an id of null
    private UserPreference unsavedPrefs() {
        return new UserPreference(mock(User.class));
    }

    // a row that has been saved, last changed at the given moment
    private UserPreference savedPrefs(Instant updatedAt) {
        UserPreference prefs = unsavedPrefs();
        ReflectionTestUtils.setField(prefs, "id", 1L);                 // no setter, the database sets it
        ReflectionTestUtils.setField(prefs, "updatedAt", updatedAt);   // @PreUpdate sets it in real life
        return prefs;
    }

    private Recommendation madeAt(LocalDateTime createdAt) {
        Recommendation recommendation = new Recommendation();
        recommendation.setCreatedAt(createdAt);
        return recommendation;
    }

    private ActivityOption option(long id, ActivityCategory category) {
        return new ActivityOption(id, "Japan", "Sapporo", category, "place " + id,
                "Sapporo, Japan", 43.0618, 141.3545);
    }

    // ----- allowedActions -----

    @Test
    void aTravellerWithNoSettingsMayBeGivenEveryAction() {
        assertThat(RecommendationServiceImpl.allowedActions(unsavedPrefs()))
                .containsExactlyInAnyOrder(Action.values());
    }

    @Test
    void fixedDatesRulesOutRescheduleAndNothingElse() {
        UserPreference prefs = unsavedPrefs();
        prefs.setFlexibilityLevel(FlexibilityLevel.FIXED_DATES);

        assertThat(RecommendationServiceImpl.allowedActions(prefs))
                .doesNotContain(Action.RESCHEDULE)
                .hasSize(Action.values().length - 1);
    }

    @Test
    void aFlexibleTravellerMayBeToldToReschedule() {
        UserPreference prefs = unsavedPrefs();
        prefs.setFlexibilityLevel(FlexibilityLevel.VERY_FLEXIBLE);

        assertThat(RecommendationServiceImpl.allowedActions(prefs)).contains(Action.RESCHEDULE);
    }

    // ----- firstPreferred -----

    @Test
    void firstPreferredIsTheFirstShortlistedPlaceOfAPreferredKind() {
        UserPreference prefs = unsavedPrefs();
        prefs.setPreferredCategories(Set.of(ActivityCategory.SHOPPING, ActivityCategory.FOOD_DINING));
        // the shortlist is best first, so the first match is also the nearest one
        List<ActivityOption> shortlist = List.of(
                option(1, ActivityCategory.MUSEUM_GALLERY),
                option(2, ActivityCategory.SHOPPING),
                option(3, ActivityCategory.SHOPPING),
                option(4, ActivityCategory.FOOD_DINING));

        assertThat(RecommendationServiceImpl.firstPreferred(shortlist, prefs).getId()).isEqualTo(2L);
    }

    @Test
    void firstPreferredIsNullWhenTheTravellerHasNoPreference() {
        List<ActivityOption> shortlist = List.of(option(1, ActivityCategory.MUSEUM_GALLERY));

        assertThat(RecommendationServiceImpl.firstPreferred(shortlist, unsavedPrefs())).isNull();
    }

    @Test
    void firstPreferredIsNullWhenNoPreferredKindIsOnTheShortlist() {
        UserPreference prefs = unsavedPrefs();
        prefs.setPreferredCategories(Set.of(ActivityCategory.SHOPPING));
        List<ActivityOption> shortlist = List.of(option(1, ActivityCategory.MUSEUM_GALLERY));

        assertThat(RecommendationServiceImpl.firstPreferred(shortlist, prefs)).isNull();
    }

    // ----- isStale -----

    @Test
    void aRecommendationMadeBeforeThePreferencesChangedIsStale() {
        UserPreference prefs = savedPrefs(Instant.now());

        assertThat(RecommendationServiceImpl.isStale(madeAt(LocalDateTime.now().minusHours(2)), prefs))
                .isTrue();
    }

    @Test
    void aRecommendationMadeAfterThePreferencesChangedIsFresh() {
        UserPreference prefs = savedPrefs(Instant.now().minusSeconds(7200));

        assertThat(RecommendationServiceImpl.isStale(madeAt(LocalDateTime.now()), prefs)).isFalse();
    }

    @Test
    void unsavedDefaultsNeverMakeAnythingStale() {
        // an unsaved default has updatedAt = now, which would make every row look old and call the
        // model on every request. the null id is how we tell it apart from a saved row
        assertThat(RecommendationServiceImpl.isStale(
                madeAt(LocalDateTime.now().minusDays(30)), unsavedPrefs())).isFalse();
    }

    @Test
    void aSeededRowWithNoCreatedAtIsLeftAlone() {
        UserPreference prefs = savedPrefs(Instant.now());

        assertThat(RecommendationServiceImpl.isStale(madeAt(null), prefs)).isFalse();
    }

    // a recommendation that records which version of the preferences it was written for
    private Recommendation writtenFor(Instant version, LocalDateTime createdAt) {
        Recommendation recommendation = madeAt(createdAt);
        recommendation.setPreferencesVersion(version);
        return recommendation;
    }

    @Test
    void adviceWrittenForAnOlderVersionIsStaleEvenIfItWasSavedAfterTheChange() {
        // the race: the model started on the old preferences, the traveller changed them, and the
        // row was saved after that. its createdAt is later than the change, but the advice is not current
        Instant oldVersion = Instant.parse("2026-11-01T10:00:00Z");
        UserPreference prefs = savedPrefs(Instant.parse("2026-11-01T10:00:05Z"));

        assertThat(RecommendationServiceImpl.isStale(writtenFor(oldVersion, LocalDateTime.now()), prefs)).isTrue();
    }

    @Test
    void adviceWrittenForTheCurrentVersionIsFresh() {
        Instant version = Instant.parse("2026-11-01T10:00:00Z");
        UserPreference prefs = savedPrefs(version);

        // equal is not older, so a row is not rebuilt over and over for preferences it already matches
        assertThat(RecommendationServiceImpl.isStale(writtenFor(version, LocalDateTime.now().minusDays(3)), prefs))
                .isFalse();
    }

    @Test
    void theStoredVersionOutranksWhenTheRowWasMade() {
        // made long ago, but written for the current preferences, so nothing has changed since
        Instant version = Instant.parse("2026-11-01T10:00:00Z");
        UserPreference prefs = savedPrefs(version);

        assertThat(RecommendationServiceImpl.isStale(
                writtenFor(version, LocalDateTime.of(2020, 1, 1, 0, 0)), prefs)).isFalse();
    }

    @Test
    void aRowWithNoVersionFallsBackToWhenItWasMade() {
        UserPreference prefs = savedPrefs(Instant.now());

        // rows made before the version was kept, they behave as they did before
        assertThat(RecommendationServiceImpl.isStale(
                writtenFor(null, LocalDateTime.now().minusHours(2)), prefs)).isTrue();
        assertThat(RecommendationServiceImpl.isStale(
                writtenFor(null, LocalDateTime.now().plusHours(1)), prefs)).isFalse();
    }

    @Test
    void aVersionNeverMakesAnythingStaleForUnsavedDefaults() {
        assertThat(RecommendationServiceImpl.isStale(
                writtenFor(Instant.parse("2020-01-01T00:00:00Z"), LocalDateTime.now()), unsavedPrefs())).isFalse();
    }

    @Test
    void theVersionOfSavedPreferencesIsWhenTheyLastChanged() {
        Instant changedAt = Instant.parse("2026-11-01T10:00:00Z");

        assertThat(RecommendationServiceImpl.versionOf(savedPrefs(changedAt))).isEqualTo(changedAt);
    }

    @Test
    void unsavedDefaultsHaveNoVersion() {
        assertThat(RecommendationServiceImpl.versionOf(unsavedPrefs())).isNull();
    }

    // ----- travellerNotes -----

    @Test
    void aTravellerWithNoSpecialSettingsGetsNoNotes() {
        assertThat(RecommendationServiceImpl.travellerNotes(unsavedPrefs())).isEmpty();
    }

    @Test
    void aRiskAverseTravellerIsDescribedAsSuch() {
        UserPreference prefs = unsavedPrefs();
        prefs.setRiskTolerance(RiskTolerance.LOW);

        assertThat(RecommendationServiceImpl.travellerNotes(prefs)).contains("risk-averse");
    }

    @Test
    void aRelaxedTravellerIsNeverTaughtToIgnoreSafety() {
        UserPreference prefs = unsavedPrefs();
        prefs.setRiskTolerance(RiskTolerance.HIGH);

        assertThat(RecommendationServiceImpl.travellerNotes(prefs))
                .contains("relaxed about risk")
                .contains("Never play down danger to safety");
    }

    @Test
    void fixedDatesAreSaidOutLoud() {
        UserPreference prefs = unsavedPrefs();
        prefs.setFlexibilityLevel(FlexibilityLevel.FIXED_DATES);

        assertThat(RecommendationServiceImpl.travellerNotes(prefs))
                .contains("The dates cannot move")
                .contains("never suggest rescheduling");
    }

    @Test
    void aFlexibleTravellerIsToldReschedulingIsFine() {
        UserPreference prefs = unsavedPrefs();
        prefs.setFlexibilityLevel(FlexibilityLevel.VERY_FLEXIBLE);

        assertThat(RecommendationServiceImpl.travellerNotes(prefs)).contains("rescheduling is fine");
    }

    @Test
    void childrenAndElderlyAreMentionedButTheModelMayNotInventSuitability() {
        UserPreference prefs = unsavedPrefs();
        prefs.setTravellingWithChildren(true);
        prefs.setTravellingWithElderly(true);

        // the shortlist has no child friendly or accessibility data, so the model must not claim it
        assertThat(RecommendationServiceImpl.travellerNotes(prefs))
                .contains("Children are travelling")
                .contains("Do not say an alternative is child-friendly")
                .contains("Elderly travellers are in the group")
                .contains("Do not say an alternative is accessible");
    }

    @Test
    void preferredCategoriesAreListedInAStableOrder() {
        UserPreference prefs = unsavedPrefs();
        prefs.setPreferredCategories(Set.of(ActivityCategory.SHOPPING, ActivityCategory.FOOD_DINING));

        // sorted, so the same preferences always give the same prompt
        assertThat(RecommendationServiceImpl.travellerNotes(prefs))
                .contains("FOOD_DINING, SHOPPING")
                .contains("choose it");
    }

    @Test
    void theModelIsToldNotToRepeatTheNotesBack() {
        UserPreference prefs = unsavedPrefs();
        prefs.setRiskTolerance(RiskTolerance.LOW);

        assertThat(RecommendationServiceImpl.travellerNotes(prefs)).contains("do not mention these");
    }

    // ----- adviceLength and lowSeverityRule -----

    @Test
    void briefAdviceIsOneShortSentence() {
        assertThat(RecommendationServiceImpl.adviceLength(unsavedPrefs())).contains("one short sentence");
    }

    @Test
    void detailedAdviceIsFewSentencesAndStaysUnderTheColumnLimit() {
        UserPreference prefs = unsavedPrefs();
        prefs.setExplanationDetail(ExplanationDetail.DETAILED);

        assertThat(RecommendationServiceImpl.adviceLength(prefs))
                .contains("three or four sentences")
                .contains("400 characters");
    }

    @Test
    void aRiskAverseTravellerIsToldLowSeverityIsStillWorthChecking() {
        UserPreference prefs = unsavedPrefs();
        prefs.setRiskTolerance(RiskTolerance.LOW);

        assertThat(RecommendationServiceImpl.lowSeverityRule(prefs)).contains("worth checking again");
    }

    @Test
    void everyoneElseIsToldLowSeverityNeedsNoChange() {
        assertThat(RecommendationServiceImpl.lowSeverityRule(unsavedPrefs()))
                .contains("no change is needed");
    }

    // ----- truncate -----

    @Test
    void shortAdviceIsLeftAlone() {
        assertThat(RecommendationServiceImpl.truncate("Visit the museum instead.")).isEqualTo("Visit the museum instead.");
    }

    @Test
    void adviceExactlyAtTheLimitIsLeftAlone() {
        String exactly500 = "x".repeat(500);

        assertThat(RecommendationServiceImpl.truncate(exactly500)).isEqualTo(exactly500);
    }

    @Test
    void adviceOverTheLimitIsCutToFitTheColumn() {
        // the column is 500 characters, so a longer string would fail the insert
        String cut = RecommendationServiceImpl.truncate("x".repeat(600));

        assertThat(cut).hasSize(500).endsWith("...");
    }
}
