package csd.tripsense.recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static org.mockito.Mockito.lenient;
import static org.mockito.ArgumentMatchers.anyLong;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import csd.tripsense.activity.Activity;
import csd.tripsense.activity.ActivityCategory;
import csd.tripsense.auth.CurrentUser;
import csd.tripsense.chat.ChatService;
import csd.tripsense.country.Country;
import csd.tripsense.impact.Impact;
import csd.tripsense.impact.ImpactNotFoundException;
import csd.tripsense.impact.ImpactService;
import csd.tripsense.itinerary.Itinerary;
import csd.tripsense.itinerary.ItineraryAccess;
import csd.tripsense.itinerary.ItineraryService;
import csd.tripsense.news.News;
import csd.tripsense.user.User;
import csd.tripsense.user.UserRepository;
import csd.tripsense.user.preference.PreferenceService;
import csd.tripsense.user.preference.UserPreference;
import java.time.Instant;
import java.util.Set;
import org.springframework.test.util.ReflectionTestUtils;

// accept, dismiss and regenerate. ItineraryAccess is the real one so the ownership checks are
// actually exercised, everything else is mocked
@DisplayName("Recommendation decisions")
class RecommendationDecisionTest {

    private static final long IMPACT_ID = 100L;

    private RecommendationRepository recommendations;
    private ActivityOptionRepository options;
    private ImpactService impactService;
    private ItineraryService itineraryService;
    private ChatService chatService;
    private RecommendationServiceImpl service;

    private Impact impact;

    private final CurrentUser karwei = new CurrentUser(7L, "kw@email.com", "karwei", null, "USER");
    private final CurrentUser alice = new CurrentUser(8L, "alice@example.com", "alice", null, "USER");

    // both museums in sapporo, so the only thing separating them is the exclude set
    private final ActivityOption firstMuseum = new ActivityOption(501L, "Japan", "Sapporo",
            ActivityCategory.MUSEUM_GALLERY, "Sapporo Art Museum", "Chuo-ku, Sapporo", 43.06, 141.35);
    private final ActivityOption secondMuseum = new ActivityOption(502L, "Japan", "Sapporo",
            ActivityCategory.MUSEUM_GALLERY, "Hokkaido Museum", "Atsubetsu-ku, Sapporo", 43.05, 141.44);

    private UserPreference preferences;

    @BeforeEach
    void setUp() {
        recommendations = mock(RecommendationRepository.class);
        options = mock(ActivityOptionRepository.class);
        impactService = mock(ImpactService.class);
        itineraryService = mock(ItineraryService.class);
        chatService = mock(ChatService.class);
        // only prepareNew needs the repository and nothing here calls it
        ItineraryAccess access = new ItineraryAccess(mock(UserRepository.class));

        PreferenceService preferenceService = mock(PreferenceService.class);
        preferences = new UserPreference(null);
        lenient().when(preferenceService.getPreferences(anyLong())).thenReturn(preferences);
        var writeGuard = mock(RecommendationWriteGuard.class);
        lenient().when(writeGuard.saveIfCurrent(any(), any())).thenAnswer(inv ->
                ((java.util.function.Supplier<Recommendation>) inv.getArgument(1)).get());
        service = new RecommendationServiceImpl(recommendations, options, impactService,
                itineraryService, chatService, preferenceService, access, writeGuard);

        User owner = mock(User.class);
        when(owner.getId()).thenReturn(karwei.getId());

        Country japan = new Country();
        japan.setName("Japan");

        Itinerary itinerary = new Itinerary();
        itinerary.setId(1L);
        itinerary.setUser(owner);
        itinerary.setCountry(japan);

        Activity skiDay = new Activity();
        skiDay.setId(10L);
        skiDay.setName("Niseko ski day");
        skiDay.setAddress("Niseko, Hokkaido, Japan");
        skiDay.setCategory(ActivityCategory.SKI_SNOW);

        News quake = new News();
        quake.setId(20L);
        quake.setTitle("Earthquake closes Hokkaido lifts");
        quake.setDescription("Lifts shut for checks");

        impact = new Impact();
        impact.setId(IMPACT_ID);
        impact.setItinerary(itinerary);
        impact.setActivity(skiDay);
        impact.setNews(quake);
        impact.setReason("Lifts may be shut");
    }

    // ---------------------------------------------------------------- helpers

    private Recommendation stored(long id, RecommendationStatus status, ActivityOption option) {
        Recommendation recommendation = new Recommendation();
        recommendation.setId(id);
        recommendation.setImpact(impact);
        recommendation.setSeverity(Severity.HIGH);
        recommendation.setAction(Action.REPLACE);
        recommendation.setAdvice("go here instead");
        recommendation.setStatus(status);
        recommendation.setSuggestedOption(option);
        return recommendation;
    }

    // ===== accept and dismiss =====

    @Test
    @DisplayName("Accepting records the decision and when it was made")
    void decide_ShouldSetStatusAndTime() {
        Recommendation pending = stored(1L, RecommendationStatus.PENDING, firstMuseum);
        when(recommendations.findById(1L)).thenReturn(Optional.of(pending));
        when(recommendations.save(any(Recommendation.class))).thenAnswer(i -> i.getArgument(0));

        Recommendation result = service.decide(1L, RecommendationStatus.ACCEPTED, karwei);

        assertEquals(RecommendationStatus.ACCEPTED, result.getStatus());
        assertNotNull(result.getDecidedAt());
    }

    @Test
    @DisplayName("A dismissed suggestion can be accepted later")
    void decide_ShouldAllowChangingMind() {
        Recommendation dismissed = stored(1L, RecommendationStatus.DISMISSED, firstMuseum);
        when(recommendations.findById(1L)).thenReturn(Optional.of(dismissed));
        when(recommendations.save(any(Recommendation.class))).thenAnswer(i -> i.getArgument(0));

        assertEquals(RecommendationStatus.ACCEPTED,
                service.decide(1L, RecommendationStatus.ACCEPTED, karwei).getStatus());
    }

    @Test
    @DisplayName("Going back to pending clears the decision time")
    void decide_ShouldClearTimeWhenUndecided() {
        Recommendation accepted = stored(1L, RecommendationStatus.ACCEPTED, firstMuseum);
        when(recommendations.findById(1L)).thenReturn(Optional.of(accepted));
        when(recommendations.save(any(Recommendation.class))).thenAnswer(i -> i.getArgument(0));

        assertNull(service.decide(1L, RecommendationStatus.PENDING, karwei).getDecidedAt());
    }

    @Test
    @DisplayName("Another traveller gets not found, never forbidden")
    void decide_ShouldNotFind_WhenNotTheOwner() {
        when(recommendations.findById(1L))
                .thenReturn(Optional.of(stored(1L, RecommendationStatus.PENDING, firstMuseum)));

        // 404 and not 403, so another user cannot tell a real id from a made up one
        assertThrows(ImpactNotFoundException.class,
                () -> service.decide(1L, RecommendationStatus.ACCEPTED, alice));
        verify(recommendations, never()).save(any());
    }

    @Test
    @DisplayName("A recommendation id that does not exist is not found")
    void decide_ShouldNotFind_WhenMissing() {
        when(recommendations.findById(99L)).thenReturn(Optional.empty());

        assertThrows(ImpactNotFoundException.class,
                () -> service.decide(99L, RecommendationStatus.ACCEPTED, karwei));
    }

    @Test
    void regenerationHonoursPreferencesWithoutRepeatingAnEarlierOption() {
        Instant version = Instant.parse("2026-11-06T00:00:00Z");
        ReflectionTestUtils.setField(preferences, "updatedAt", version);
        preferences.setPreferredCategories(Set.of(ActivityCategory.SHOPPING));
        ActivityOption mall = new ActivityOption(99L, "Japan", "Sapporo",
                ActivityCategory.SHOPPING, "Sapporo Mall", "Sapporo", 43.06, 141.35);
        Recommendation previous = stored(1L, RecommendationStatus.DISMISSED, firstMuseum);
        when(recommendations.findById(1L)).thenReturn(Optional.of(previous));
        when(recommendations.findByImpactIdOrderByIdAsc(IMPACT_ID)).thenReturn(List.of(previous));
        when(options.findByCountry("Japan")).thenReturn(List.of(firstMuseum, secondMuseum, mall));
        when(chatService.ask(any(), any())).thenThrow(new RuntimeException("model down"));
        when(recommendations.save(any(Recommendation.class))).thenAnswer(i -> i.getArgument(0));

        Recommendation fresh = service.regenerate(1L, karwei);

        assertEquals(mall, fresh.getSuggestedOption());
        assertEquals(version, fresh.getPreferencesVersion());
        assertEquals(RecommendationStatus.PENDING, fresh.getStatus());
        assertEquals(RecommendationStatus.DISMISSED, previous.getStatus());
    }

    // ===== regenerate =====

    @Test
    @DisplayName("Regenerating never offers an option already suggested for that impact")
    void regenerate_ShouldExcludePriorOptions() {
        Recommendation rejected = stored(1L, RecommendationStatus.DISMISSED, firstMuseum);
        when(recommendations.findById(1L)).thenReturn(Optional.of(rejected));
        when(recommendations.findByImpactIdOrderByIdAsc(IMPACT_ID)).thenReturn(List.of(rejected));
        when(options.findByCountry("Japan")).thenReturn(List.of(firstMuseum, secondMuseum));
        // model down, so the fallback takes the best of the shortlist and we can see which
        when(chatService.ask(any(), any())).thenThrow(new RuntimeException("model down"));
        when(recommendations.save(any(Recommendation.class))).thenAnswer(i -> i.getArgument(0));

        Recommendation fresh = service.regenerate(1L, karwei);

        assertEquals(secondMuseum, fresh.getSuggestedOption());
        assertEquals(RecommendationStatus.PENDING, fresh.getStatus());
    }

    @Test
    @DisplayName("Regenerating leaves the old suggestion alone as history")
    void regenerate_ShouldNotTouchTheOldRow() {
        Recommendation rejected = stored(1L, RecommendationStatus.DISMISSED, firstMuseum);
        when(recommendations.findById(1L)).thenReturn(Optional.of(rejected));
        when(recommendations.findByImpactIdOrderByIdAsc(IMPACT_ID)).thenReturn(List.of(rejected));
        when(options.findByCountry("Japan")).thenReturn(List.of(firstMuseum, secondMuseum));
        when(chatService.ask(any(), any())).thenThrow(new RuntimeException("model down"));
        when(recommendations.save(any(Recommendation.class))).thenAnswer(i -> i.getArgument(0));

        service.regenerate(1L, karwei);

        assertEquals(RecommendationStatus.DISMISSED, rejected.getStatus());
        assertEquals(firstMuseum, rejected.getSuggestedOption());
    }

    @Test
    @DisplayName("Cannot regenerate another traveller's recommendation")
    void regenerate_ShouldNotFind_WhenNotTheOwner() {
        when(recommendations.findById(1L))
                .thenReturn(Optional.of(stored(1L, RecommendationStatus.PENDING, firstMuseum)));

        assertThrows(ImpactNotFoundException.class, () -> service.regenerate(1L, alice));
    }

    // ===== listing =====

    @Test
    @DisplayName("Dismissed suggestions drop out of the itinerary list")
    void getByItinerary_ShouldHideDismissed() {
        Impact other = new Impact();
        other.setId(200L);
        other.setItinerary(impact.getItinerary());

        Recommendation dismissed = stored(1L, RecommendationStatus.DISMISSED, firstMuseum);
        Recommendation live = stored(2L, RecommendationStatus.PENDING, secondMuseum);
        live.setImpact(other);

        when(itineraryService.getItineraryById(1L)).thenReturn(impact.getItinerary());
        when(recommendations.findByImpactItineraryId(1L)).thenReturn(List.of(dismissed, live));

        assertEquals(List.of(live), service.getByItinerary(1L, false, karwei));
    }

    @Test
    @DisplayName("Dismissed suggestions come back when asked for")
    void getByItinerary_ShouldIncludeDismissedWhenAsked() {
        Recommendation dismissed = stored(1L, RecommendationStatus.DISMISSED, firstMuseum);

        when(itineraryService.getItineraryById(1L)).thenReturn(impact.getItinerary());
        when(recommendations.findByImpactItineraryId(1L)).thenReturn(List.of(dismissed));

        assertEquals(1, service.getByItinerary(1L, true, karwei).size());
    }

    @Test
    @DisplayName("Only the newest attempt per impact is live")
    void getByItinerary_ShouldKeepOnlyTheNewestPerImpact() {
        Recommendation older = stored(1L, RecommendationStatus.PENDING, firstMuseum);
        Recommendation newer = stored(5L, RecommendationStatus.PENDING, secondMuseum);

        when(itineraryService.getItineraryById(1L)).thenReturn(impact.getItinerary());
        when(recommendations.findByImpactItineraryId(1L)).thenReturn(List.of(older, newer));

        assertEquals(List.of(newer), service.getByItinerary(1L, false, karwei));
    }

    @Test
    @DisplayName("Cannot list another traveller's recommendations")
    void getByItinerary_ShouldNotFind_WhenNotTheOwner() {
        when(itineraryService.getItineraryById(1L)).thenReturn(impact.getItinerary());

        assertThrows(RuntimeException.class, () -> service.getByItinerary(1L, false, alice));
    }

    // ===== history =====

    @Test
    @DisplayName("The history shows every attempt including dismissed ones")
    void getHistory_ShouldReturnEveryAttempt() {
        Recommendation first = stored(1L, RecommendationStatus.DISMISSED, firstMuseum);
        Recommendation second = stored(5L, RecommendationStatus.PENDING, secondMuseum);

        when(impactService.getImpact(IMPACT_ID)).thenReturn(impact);
        when(recommendations.findByImpactIdOrderByIdAsc(IMPACT_ID))
                .thenReturn(List.of(first, second));

        List<Recommendation> history = service.getHistory(IMPACT_ID, karwei);

        assertEquals(List.of(first, second), history);
        assertTrue(history.stream()
                .anyMatch(r -> r.getStatus() == RecommendationStatus.DISMISSED));
    }

    @Test
    @DisplayName("Cannot read the history of another traveller's impact")
    void getHistory_ShouldNotFind_WhenNotTheOwner() {
        when(impactService.getImpact(IMPACT_ID)).thenReturn(impact);

        assertThrows(ImpactNotFoundException.class, () -> service.getHistory(IMPACT_ID, alice));
    }
}
