package csd.tripsense.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Constructor;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import csd.tripsense.activity.Activity;
import csd.tripsense.activity.ActivityCategory;
import csd.tripsense.chat.ChatService;
import csd.tripsense.impact.Impact;
import csd.tripsense.impact.ImpactService;
import csd.tripsense.itinerary.Itinerary;
import csd.tripsense.itinerary.ItineraryService;
import csd.tripsense.itinerary.ItineraryNotFoundException;
import csd.tripsense.news.News;
import csd.tripsense.user.User;
import csd.tripsense.user.preference.FlexibilityLevel;
import csd.tripsense.user.preference.PreferenceService;
import csd.tripsense.user.preference.UserPreference;
import csd.tripsense.country.Country;



/**
 * The whole generate flow, with the model, the database and the other services mocked.
 * RecommendationRules is the real one, so the shortlist the model is offered is the real shortlist.
 *
 * The setup is a ski day at Niseko hit by snow, and three indoor options in Sapporo. Sapporo is
 * not named in the activity's address, so the rules cannot work out a distance and every option
 * ties. That makes the order simple to reason about: by id (museum, mall, restaurant), and with a
 * preferred kind, that kind jumps to the front.
 */
class RecommendationServiceImplTest {

    private static final Long USER_ID = 1L;
    private static final Long ITINERARY_ID = 10L;

    private RecommendationRepository recommendations;
    private ActivityOptionRepository options;
    private ImpactService impactService;
    private ItineraryService itineraryService;
    private ChatService chatService;
    private PreferenceService preferenceService;
    private RecommendationServiceImpl service;

    private UserPreference prefs;

    private final ActivityOption museum = new ActivityOption(1L, "Japan", "Sapporo",
            ActivityCategory.MUSEUM_GALLERY, "Sapporo Art Museum", "Sapporo, Japan", 43.0618, 141.3545);
    private final ActivityOption mall = new ActivityOption(2L, "Japan", "Sapporo",
            ActivityCategory.SHOPPING, "Sapporo Mall", "Sapporo, Japan", 43.0618, 141.3545);
    private final ActivityOption restaurant = new ActivityOption(3L, "Japan", "Sapporo",
            ActivityCategory.FOOD_DINING, "Sapporo Ramen House", "Sapporo, Japan", 43.0618, 141.3545);

    @BeforeEach
    void setUp() {
        recommendations = mock(RecommendationRepository.class);
        options = mock(ActivityOptionRepository.class);
        impactService = mock(ImpactService.class);
        itineraryService = mock(ItineraryService.class);
        chatService = mock(ChatService.class);
        preferenceService = mock(PreferenceService.class);
        service = new RecommendationServiceImpl(recommendations, options, impactService,
                itineraryService, chatService, preferenceService);

        // deep stubs so we can say "its user has id 1 and its country is Japan" without building a
        // Country object, which this test has no reason to know about
        Itinerary itinerary = mock(Itinerary.class, RETURNS_DEEP_STUBS);
        when(itinerary.getUser().getId()).thenReturn(USER_ID);
        when(itinerary.getCountry().getName()).thenReturn("Japan");
        when(itineraryService.getItineraryById(ITINERARY_ID)).thenReturn(itinerary);

        // a traveller with the entity defaults, and no saved recommendations to reuse
        prefs = new UserPreference(mock(User.class));
        when(preferenceService.getPreferences(USER_ID)).thenReturn(prefs);
        when(options.findByCountry("Japan")).thenReturn(List.of(museum, mall, restaurant));
        when(recommendations.findByImpactId(any())).thenReturn(Optional.empty());
        // save hands back what it was given, like a real repository
        when(recommendations.save(any(Recommendation.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ----- helpers -----

    // a cancelled ski day. its address names no option's area, see the class comment
    private Impact skiImpact(long impactId) {
        Activity activity = mock(Activity.class);
        when(activity.getName()).thenReturn("Ski at Niseko");
        when(activity.getCategory()).thenReturn(ActivityCategory.SKI_SNOW);
        when(activity.getAddress()).thenReturn("Niseko, Hokkaido, Japan");

        News news = mock(News.class);
        when(news.getTitle()).thenReturn("Heavy snow closes ski resorts");
        when(news.getDescription()).thenReturn("Lifts are suspended across the region.");

        Impact impact = mock(Impact.class);
        when(impact.getId()).thenReturn(impactId);
        when(impact.getActivity()).thenReturn(activity);
        when(impact.getNews()).thenReturn(news);
        when(impact.getReason()).thenReturn("The resort is closed.");
        return impact;
    }

    private void theImpactsAre(Impact... impacts) {
        when(impactService.findImpacted(USER_ID, ITINERARY_ID)).thenReturn(List.of(impacts));
    }

    /**
     * What the model answers. Suggestion is a private record, so it is built through reflection
     * from the class the service passes in. Making it package private would let this be a plain
     * "new Suggestion(...)".
     */
    private Answer<Object> modelSays(String severity, String action, Long optionId, String advice) {
        return invocation -> {
            Class<?> type = invocation.getArgument(1);
            Constructor<?> constructor = type.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            return constructor.newInstance(severity, action, optionId, advice);
        };
    }

    private void theModelSays(String severity, String action, Long optionId, String advice) {
        when(chatService.ask(anyString(), any())).thenAnswer(modelSays(severity, action, optionId, advice));
    }

    // generate for one impact and hand back the one recommendation
    private Recommendation generateOne() {
        List<Recommendation> result = service.generate(ITINERARY_ID);
        assertThat(result).hasSize(1);
        return result.get(0);
    }

    // a saved preferences row, last changed at this moment (the unsaved default has no id)
    private void preferencesSavedAt(Instant updatedAt) {
        ReflectionTestUtils.setField(prefs, "id", 1L);
        ReflectionTestUtils.setField(prefs, "updatedAt", updatedAt);
    }

    private Recommendation savedRecommendationMadeAt(LocalDateTime createdAt) {
        Recommendation recommendation = new Recommendation();
        recommendation.setSeverity(Severity.MEDIUM);
        recommendation.setAction(Action.MONITOR);
        recommendation.setCreatedAt(createdAt);
        return recommendation;
    }

    // ----- caching and staleness -----

    @Test
    void aRecommendationMadeAfterThePreferencesLastChangedIsReusedWithoutTheModel() {
        preferencesSavedAt(Instant.now().minusSeconds(3600));
        Recommendation cached = savedRecommendationMadeAt(LocalDateTime.now());
        when(recommendations.findByImpactId(4L)).thenReturn(Optional.of(cached));
        theImpactsAre(skiImpact(4L));

        List<Recommendation> result = service.generate(ITINERARY_ID);

        assertThat(result).containsExactly(cached);
        verifyNoInteractions(chatService);
        verify(recommendations, never()).delete(any());
        verify(recommendations, never()).save(any());
    }

    @Test
    void aRecommendationMadeBeforeThePreferencesChangedIsThrownAwayAndRebuilt() {
        preferencesSavedAt(Instant.now());
        Recommendation stale = savedRecommendationMadeAt(LocalDateTime.now().minusHours(2));
        when(recommendations.findByImpactId(4L)).thenReturn(Optional.of(stale));
        theImpactsAre(skiImpact(4L));
        theModelSays("MEDIUM", "REPLACE", 1L, "Visit the art museum.");

        Recommendation rebuilt = generateOne();

        verify(recommendations).delete(stale);
        assertThat(rebuilt).isNotSameAs(stale);
        assertThat(rebuilt.getSuggestedOption()).isEqualTo(museum);
    }

    // ----- picking the option -----

    @Test
    void anOptionOfThePreferredKindChosenByTheModelIsKept() {
        prefs.setPreferredCategories(Set.of(ActivityCategory.SHOPPING));
        theImpactsAre(skiImpact(4L));
        theModelSays("MEDIUM", "REPLACE", 2L, "Try Sapporo Mall instead.");

        Recommendation recommendation = generateOne();

        assertThat(recommendation.getSuggestedOption()).isEqualTo(mall);
        assertThat(recommendation.getAdvice()).isEqualTo("Try Sapporo Mall instead.");   // the model's own words
    }

    @Test
    void aModelThatIgnoresThePreferredKindIsOverruled() {
        prefs.setPreferredCategories(Set.of(ActivityCategory.SHOPPING));
        theImpactsAre(skiImpact(4L));
        theModelSays("MEDIUM", "REPLACE", 1L, "Go to the art museum.");   // a museum, not shopping

        Recommendation recommendation = generateOne();

        assertThat(recommendation.getSuggestedOption()).isEqualTo(mall);
        assertThat(recommendation.getAction()).isEqualTo(Action.REPLACE);
        // the model's advice named the museum, so it is replaced with one that names the mall
        assertThat(recommendation.getAdvice()).isEqualTo("Swap this for Sapporo Mall in Sapporo.");
    }

    @Test
    void aPreferenceNeverForcesAnOptionOnAModelThatChoseNone() {
        prefs.setPreferredCategories(Set.of(ActivityCategory.SHOPPING));
        theImpactsAre(skiImpact(4L));
        theModelSays("MEDIUM", "MONITOR", null, "Keep watching the news.");

        Recommendation recommendation = generateOne();

        assertThat(recommendation.getSuggestedOption()).isNull();
        assertThat(recommendation.getAction()).isEqualTo(Action.MONITOR);
        assertThat(recommendation.getAdvice()).isEqualTo("Keep watching the news.");
    }

    @Test
    void anOptionIdWeNeverOfferedIsDropped() {
        theImpactsAre(skiImpact(4L));
        theModelSays("MEDIUM", "REPLACE", 999L, "Go to the Grand Hall.");

        // the guard this whole design exists for: an invented id never reaches the database
        assertThat(generateOne().getSuggestedOption()).isNull();
    }

    // ----- flexibility -----

    @Test
    void fixedDatesRefuseARescheduleTheModelStillSuggested() {
        prefs.setFlexibilityLevel(FlexibilityLevel.FIXED_DATES);
        theImpactsAre(skiImpact(4L));
        theModelSays("MEDIUM", "RESCHEDULE", null, "Move it to next week.");

        Recommendation recommendation = generateOne();

        assertThat(recommendation.getAction()).isEqualTo(Action.MONITOR);
        // the model's advice described the reschedule we just refused, so it is not kept
        assertThat(recommendation.getAdvice()).doesNotContain("next week");
    }

    @Test
    void fixedDatesTurnARescheduleIntoASwapWhenThereIsSomewhereToGo() {
        prefs.setFlexibilityLevel(FlexibilityLevel.FIXED_DATES);
        theImpactsAre(skiImpact(4L));
        theModelSays("MEDIUM", "RESCHEDULE", 2L, "Move it to next week.");

        Recommendation recommendation = generateOne();

        assertThat(recommendation.getAction()).isEqualTo(Action.REPLACE);
        assertThat(recommendation.getSuggestedOption()).isEqualTo(mall);
        assertThat(recommendation.getAdvice()).isEqualTo("Swap this for Sapporo Mall in Sapporo.");
    }

    @Test
    void aFlexibleTravellersRescheduleIsKept() {
        prefs.setFlexibilityLevel(FlexibilityLevel.VERY_FLEXIBLE);
        theImpactsAre(skiImpact(4L));
        theModelSays("MEDIUM", "RESCHEDULE", null, "Move it to next week.");

        Recommendation recommendation = generateOne();

        assertThat(recommendation.getAction()).isEqualTo(Action.RESCHEDULE);
        assertThat(recommendation.getAdvice()).isEqualTo("Move it to next week.");
    }

    // ----- severity, failure and special cases -----

    @Test
    void lowSeverityMeansMonitorAndNoAlternative() {
        theImpactsAre(skiImpact(4L));
        theModelSays("LOW", "REPLACE", 1L, "No change needed.");

        Recommendation recommendation = generateOne();

        assertThat(recommendation.getSeverity()).isEqualTo(Severity.LOW);
        assertThat(recommendation.getAction()).isEqualTo(Action.MONITOR);
        assertThat(recommendation.getSuggestedOption()).isNull();
    }

    @Test
    void whenTheModelIsDownTheBestOfTheShortlistIsOffered() {
        theImpactsAre(skiImpact(4L));
        when(chatService.ask(anyString(), any())).thenThrow(new RuntimeException("ollama is not running"));

        Recommendation recommendation = generateOne();

        assertThat(recommendation.getSeverity()).isEqualTo(Severity.MEDIUM);
        assertThat(recommendation.getAction()).isEqualTo(Action.REPLACE);
        assertThat(recommendation.getSuggestedOption()).isEqualTo(museum);   // first by the rules
        assertThat(recommendation.getAdvice()).isEqualTo("Swap this for Sapporo Art Museum in Sapporo.");
    }

    @Test
    void whenTheModelIsDownThePreferredKindIsStillFirst() {
        prefs.setPreferredCategories(Set.of(ActivityCategory.FOOD_DINING));
        theImpactsAre(skiImpact(4L));
        when(chatService.ask(anyString(), any())).thenThrow(new RuntimeException("ollama is not running"));

        assertThat(generateOne().getSuggestedOption()).isEqualTo(restaurant);
    }

    @Test
    void aWholeTripImpactNeverCallsTheModelAndOffersNoPlace() {
        Impact wholeTrip = mock(Impact.class);
        when(wholeTrip.getId()).thenReturn(5L);
        when(wholeTrip.getActivity()).thenReturn(null);
        theImpactsAre(wholeTrip);

        Recommendation recommendation = generateOne();

        assertThat(recommendation.getSeverity()).isEqualTo(Severity.MEDIUM);
        assertThat(recommendation.getAction()).isEqualTo(Action.MONITOR);
        assertThat(recommendation.getSuggestedOption()).isNull();
        verifyNoInteractions(chatService);
    }

    @Test
    void adviceTooLongForTheColumnIsCutBeforeItIsSaved() {
        theImpactsAre(skiImpact(4L));
        theModelSays("MEDIUM", "MONITOR", null, "x".repeat(600));

        // the column holds 500 characters, a longer string would fail the insert
        assertThat(generateOne().getAdvice()).hasSize(500).endsWith("...");
    }

    @Test
    void recommendationsComeBackWorstFirst() {
        theImpactsAre(skiImpact(4L), skiImpact(5L));
        when(chatService.ask(anyString(), any()))
                .thenAnswer(modelSays("LOW", "MONITOR", null, "No change needed."))
                .thenAnswer(modelSays("HIGH", "REPLACE", 1L, "Go to the art museum."));

        List<Recommendation> result = service.generate(ITINERARY_ID);

        assertThat(result).extracting(Recommendation::getSeverity)
                .containsExactly(Severity.HIGH, Severity.LOW);
    }

    // ----- what the model is told -----

    @Test
    void thePromptCarriesThePreferencesAndOnlyTheAllowedActions() {
        prefs.setFlexibilityLevel(FlexibilityLevel.FIXED_DATES);
        prefs.setPreferredCategories(Set.of(ActivityCategory.SHOPPING));
        prefs.setTravellingWithChildren(true);
        theImpactsAre(skiImpact(4L));
        theModelSays("MEDIUM", "REPLACE", 2L, "Try Sapporo Mall instead.");

        generateOne();

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(chatService).ask(prompt.capture(), any());
        String text = prompt.getValue();

        assertThat(text)
                .contains("The traveller prefers these kinds of activity: SHOPPING")
                .contains("The dates cannot move")
                .contains("Children are travelling")
                .contains("2: Sapporo Mall (Sapporo, SHOPPING)");   // the shortlist, with its categories
        String actionLine = text.lines().filter(line -> line.startsWith("action - one of"))
                .findFirst().orElseThrow();
        assertThat(actionLine).contains("REPLACE").doesNotContain("RESCHEDULE");
    }
}
