package csd.tripsense.recommendation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import csd.tripsense.activity.Activity;
import csd.tripsense.activity.ActivityCategory;
import csd.tripsense.chat.ChatService;
import csd.tripsense.country.Country;
import csd.tripsense.impact.Impact;
import csd.tripsense.impact.ImpactService;
import csd.tripsense.itinerary.Itinerary;
import csd.tripsense.itinerary.ItineraryNotFoundException;
import csd.tripsense.itinerary.ItineraryService;
import csd.tripsense.news.News;
import csd.tripsense.user.User;

// unit tests for RecommendationServiceImpl
// the model is always mocked, so these check what the service does with its answer:
// reuse what is stored, keep only places we offered, and still answer when the model fails
@ExtendWith(MockitoExtension.class)
@DisplayName("RecommendationService Unit Tests")
class RecommendationServiceImplTest {

    @Mock private RecommendationRepository recommendations;
    @Mock private ActivityOptionRepository options;
    @Mock private ImpactService impactService;
    @Mock private ItineraryService itineraryService;
    @Mock private ChatService chatService;

    @InjectMocks
    private RecommendationServiceImpl recommendationService;

    private Itinerary itinerary;
    private Activity skiDay;
    private News earthquake;
    private Impact skiImpact;

    // both in sapporo, so the rules rank them by id and the museum comes first
    private final ActivityOption museum = new ActivityOption(501L, "Japan", "Sapporo",
            ActivityCategory.MUSEUM_GALLERY, "Sapporo Art Museum", "Chuo-ku, Sapporo", 43.06, 141.35);
    private final ActivityOption ramenAlley = new ActivityOption(502L, "Japan", "Sapporo",
            ActivityCategory.FOOD_DINING, "Ramen Alley", "Susukino, Sapporo", 43.06, 141.35);
    // another ski field is never offered for a ski day, but the rules need it to place niseko
    private final ActivityOption rusutsu = new ActivityOption(503L, "Japan", "Niseko",
            ActivityCategory.SKI_SNOW, "Rusutsu Resort", "Niseko", 42.80, 140.68);

    @BeforeEach
    void setUp() {
        User traveller = new User("traveller@example.com", "traveller", "hash", "USER");
        ReflectionTestUtils.setField(traveller, "id", 7L);

        Country japan = new Country();
        japan.setName("Japan");

        itinerary = new Itinerary();
        itinerary.setId(1L);
        itinerary.setName("Japan Ski Trip");
        itinerary.setUser(traveller);
        itinerary.setCountry(japan);

        skiDay = new Activity();
        skiDay.setId(10L);
        skiDay.setName("Ski at Niseko");
        skiDay.setAddress("Niseko, Hokkaido, Japan");
        skiDay.setCategory(ActivityCategory.SKI_SNOW);
        skiDay.setItinerary(itinerary);

        earthquake = new News();
        earthquake.setId(5L);
        earthquake.setTitle("Earthquake near Hokkaido");
        earthquake.setDescription("Ski resorts are suspending lifts.");

        skiImpact = impactOn(100L, skiDay, "Lifts may stay shut");
    }

    // ---------------------------------------------------------------- helpers

    private Impact impactOn(Long id, Activity activity, String reason) {
        Impact impact = new Impact();
        impact.setId(id);
        impact.setItinerary(itinerary);
        impact.setActivity(activity);
        impact.setNews(earthquake);
        impact.setImpacted(true);
        impact.setReason(reason);
        return impact;
    }

    private void givenImpacts(Impact... impacts) {
        when(itineraryService.getItineraryById(1L)).thenReturn(itinerary);
        when(impactService.findImpacted(7L, 1L)).thenReturn(List.of(impacts));
    }

    private void givenNothingStored() {
        when(recommendations.findByImpactId(anyLong())).thenReturn(Optional.empty());
    }

    private void givenJapanOptions() {
        when(options.findByCountry("Japan")).thenReturn(List.of(museum, ramenAlley, rusutsu));
    }

    private void givenSaveEchoes() {
        when(recommendations.save(any(Recommendation.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    // the answer type is a private record, so it is built by reflection from whatever type is asked for
    private void givenModelAnswers(String severity, String action, Long optionId, String advice) {
        when(chatService.ask(anyString(), any(Class.class))).thenAnswer(invocation -> {
            Class<?> type = invocation.getArgument(1);
            var constructor = type.getDeclaredConstructor(String.class, String.class, Long.class, String.class);
            constructor.setAccessible(true);
            return constructor.newInstance(severity, action, optionId, advice);
        });
    }

    private Recommendation stored(Impact impact, Severity severity) {
        Recommendation recommendation = new Recommendation();
        recommendation.setImpact(impact);
        recommendation.setSeverity(severity);
        recommendation.setAction(Action.MONITOR);
        recommendation.setAdvice("Already worked out");
        return recommendation;
    }

    // ===== GENERATE: REUSING WHAT IS STORED =====

    @Test
    @DisplayName("Should reuse the stored recommendation without asking the model")
    void generate_ShouldReuseStored_WhenImpactAlreadyHasOne() {
        // Arrange
        givenImpacts(skiImpact);
        Recommendation existing = stored(skiImpact, Severity.HIGH);
        when(recommendations.findByImpactId(100L)).thenReturn(Optional.of(existing));

        // Act
        List<Recommendation> result = recommendationService.generate(1L);

        // Assert
        assertEquals(List.of(existing), result);
        verifyNoInteractions(chatService);
        verify(recommendations, never()).save(any(Recommendation.class));
    }

    @Test
    @DisplayName("Should return nothing when the itinerary has no impacts")
    void generate_ShouldReturnEmpty_WhenNothingIsImpacted() {
        // Arrange
        givenImpacts();

        // Act
        List<Recommendation> result = recommendationService.generate(1L);

        // Assert
        assertTrue(result.isEmpty());
        verifyNoInteractions(chatService);
    }

    @Test
    @DisplayName("Should pass on the not found error when the itinerary does not exist")
    void generate_ShouldThrow_WhenItineraryDoesNotExist() {
        // Arrange
        when(itineraryService.getItineraryById(99L)).thenThrow(new ItineraryNotFoundException(99L));

        // Act + Assert
        assertThrows(ItineraryNotFoundException.class, () -> recommendationService.generate(99L));
        verifyNoInteractions(chatService);
    }

    // ===== GENERATE: USING THE MODEL'S ANSWER =====

    @Test
    @DisplayName("Should save the model's severity, action, place and advice when the place was offered")
    void generate_ShouldSaveModelAnswer_WhenOptionWasOffered() {
        // Arrange
        givenImpacts(skiImpact);
        givenNothingStored();
        givenJapanOptions();
        givenSaveEchoes();
        givenModelAnswers("HIGH", "REPLACE", 501L, "Spend the day at the art museum instead.");

        // Act
        Recommendation result = recommendationService.generate(1L).get(0);

        // Assert
        assertEquals(Severity.HIGH, result.getSeverity());
        assertEquals(Action.REPLACE, result.getAction());
        assertSame(museum, result.getSuggestedOption());
        assertEquals("Spend the day at the art museum instead.", result.getAdvice());
        assertSame(skiImpact, result.getImpact());
        assertNotNull(result.getCreatedAt());
        verify(recommendations, times(1)).save(any(Recommendation.class));
    }

    @Test
    @DisplayName("Should only offer the model indoor places, never another ski field")
    void generate_ShouldOnlyOfferSubstitutes_InThePrompt() {
        // Arrange
        givenImpacts(skiImpact);
        givenNothingStored();
        givenJapanOptions();
        givenSaveEchoes();
        givenModelAnswers("MEDIUM", "REPLACE", 501L, "Go to the museum.");

        // Act
        recommendationService.generate(1L);

        // Assert
        verify(chatService).ask(argThat(prompt -> prompt.contains("501: Sapporo Art Museum")
                && prompt.contains("502: Ramen Alley")
                && !prompt.contains("Rusutsu")), any(Class.class));
    }

    @Test
    @DisplayName("Should drop a place the model made up, since it was never on the shortlist")
    void generate_ShouldDropOption_WhenModelInventsOne() {
        // Arrange
        givenImpacts(skiImpact);
        givenNothingStored();
        givenJapanOptions();
        givenSaveEchoes();
        givenModelAnswers("HIGH", "REPLACE", 999L, "Go to the Hokkaido Snow Palace.");

        // Act
        Recommendation result = recommendationService.generate(1L).get(0);

        // Assert
        assertNull(result.getSuggestedOption());
    }

    @Test
    @DisplayName("Should say no change is needed when severity is LOW, even if the model named a place")
    void generate_ShouldMonitorWithoutOption_WhenSeverityIsLow() {
        // Arrange
        givenImpacts(skiImpact);
        givenNothingStored();
        givenJapanOptions();
        givenSaveEchoes();
        givenModelAnswers("LOW", "REPLACE", 501L, "No change is needed.");

        // Act
        Recommendation result = recommendationService.generate(1L).get(0);

        // Assert
        assertEquals(Severity.LOW, result.getSeverity());
        assertEquals(Action.MONITOR, result.getAction());
        assertNull(result.getSuggestedOption());
    }

    @Test
    @DisplayName("Should fall back to safe defaults when the model sends severity and action we do not know")
    void generate_ShouldUseDefaults_WhenModelSendsUnknownValues() {
        // Arrange
        givenImpacts(skiImpact);
        givenNothingStored();
        givenJapanOptions();
        givenSaveEchoes();
        givenModelAnswers("CATASTROPHIC", "PANIC", 999L, "Run.");

        // Act
        Recommendation result = recommendationService.generate(1L).get(0);

        // Assert: medium by default, and with no place to go there is nothing to replace with
        assertEquals(Severity.MEDIUM, result.getSeverity());
        assertEquals(Action.MONITOR, result.getAction());
        assertNull(result.getSuggestedOption());
    }

    @Test
    @DisplayName("Should write its own advice when the model leaves it blank")
    void generate_ShouldUseFallbackAdvice_WhenModelAdviceIsBlank() {
        // Arrange
        givenImpacts(skiImpact);
        givenNothingStored();
        givenJapanOptions();
        givenSaveEchoes();
        givenModelAnswers("HIGH", "REPLACE", 501L, "  ");

        // Act
        Recommendation result = recommendationService.generate(1L).get(0);

        // Assert
        assertEquals("Swap this for Sapporo Art Museum in Sapporo.", result.getAdvice());
    }

    // ===== GENERATE: WHEN THE MODEL CANNOT HELP =====

    @Test
    @DisplayName("Should suggest the top shortlisted place when the model is unreachable")
    void generate_ShouldUseTopOption_WhenModelFails() {
        // Arrange
        givenImpacts(skiImpact);
        givenNothingStored();
        givenJapanOptions();
        givenSaveEchoes();
        when(chatService.ask(anyString(), any(Class.class))).thenThrow(new RuntimeException("ollama is down"));

        // Act
        Recommendation result = recommendationService.generate(1L).get(0);

        // Assert
        assertEquals(Severity.MEDIUM, result.getSeverity());
        assertEquals(Action.REPLACE, result.getAction());
        assertSame(museum, result.getSuggestedOption());
        assertEquals("Swap this for Sapporo Art Museum in Sapporo.", result.getAdvice());
    }

    @Test
    @DisplayName("Should advise watching the news when the model fails and there is nothing to offer")
    void generate_ShouldMonitor_WhenModelFailsWithEmptyShortlist() {
        // Arrange: an activity from before categories existed gets no shortlist at all
        skiDay.setCategory(null);
        givenImpacts(skiImpact);
        givenNothingStored();
        givenSaveEchoes();
        when(chatService.ask(anyString(), any(Class.class))).thenThrow(new RuntimeException("ollama is down"));

        // Act
        Recommendation result = recommendationService.generate(1L).get(0);

        // Assert
        assertEquals(Action.MONITOR, result.getAction());
        assertNull(result.getSuggestedOption());
        assertEquals("No change needed for now, keep an eye on the news before you travel.", result.getAdvice());
        verifyNoInteractions(options);
    }

    @Test
    @DisplayName("Should monitor a whole trip impact without asking the model")
    void generate_ShouldMonitorWithoutModel_WhenImpactCoversWholeTrip() {
        // Arrange
        Impact wholeTrip = impactOn(101L, null, "Flights into Sapporo may be delayed");
        givenImpacts(wholeTrip);
        givenNothingStored();
        givenSaveEchoes();

        // Act
        Recommendation result = recommendationService.generate(1L).get(0);

        // Assert
        assertEquals(Severity.MEDIUM, result.getSeverity());
        assertEquals(Action.MONITOR, result.getAction());
        assertNull(result.getSuggestedOption());
        verifyNoInteractions(chatService);
    }

    // ===== ORDERING =====

    @Test
    @DisplayName("Should return the worst severity first when generating")
    void generate_ShouldSortWorstFirst() {
        // Arrange: the stored one is low, the new one comes back high
        Activity onsen = new Activity();
        onsen.setId(11L);
        onsen.setName("Onsen visit");
        onsen.setItinerary(itinerary);
        Impact onsenImpact = impactOn(102L, onsen, "Baths closed for checks");

        givenImpacts(onsenImpact, skiImpact);
        when(recommendations.findByImpactId(102L)).thenReturn(Optional.of(stored(onsenImpact, Severity.LOW)));
        when(recommendations.findByImpactId(100L)).thenReturn(Optional.empty());
        givenJapanOptions();
        givenSaveEchoes();
        givenModelAnswers("HIGH", "REPLACE", 501L, "Go to the museum.");

        // Act
        List<Recommendation> result = recommendationService.generate(1L);

        // Assert
        assertEquals(List.of(Severity.HIGH, Severity.LOW),
                result.stream().map(Recommendation::getSeverity).toList());
    }

    @Test
    @DisplayName("Should list stored recommendations worst first without asking the model")
    void getByItinerary_ShouldSortWorstFirst_AndNeverAskModel() {
        // Arrange
        Recommendation low = stored(skiImpact, Severity.LOW);
        Recommendation high = stored(skiImpact, Severity.HIGH);
        Recommendation medium = stored(skiImpact, Severity.MEDIUM);
        when(recommendations.findByImpactItineraryId(1L)).thenReturn(List.of(low, high, medium));

        // Act
        List<Recommendation> result = recommendationService.getByItinerary(1L);

        // Assert
        assertEquals(List.of(high, medium, low), result);
        verifyNoInteractions(chatService);
    }
}
