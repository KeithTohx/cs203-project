package csd.tripsense.impact;

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

import csd.tripsense.activity.Activity;
import csd.tripsense.activity.ActivityService;
import csd.tripsense.chat.ChatService;
import csd.tripsense.itinerary.Itinerary;
import csd.tripsense.itinerary.ItineraryService;
import csd.tripsense.news.News;
import csd.tripsense.news.NewsService;
import csd.tripsense.user.User;

@ExtendWith(MockitoExtension.class)
@DisplayName("ImpactService Unit Tests")
class ImpactServiceImplTest {

    @Mock private ImpactRepository impactRepository;
    @Mock private ChatService chatService;
    @Mock private ItineraryService itineraryService;
    @Mock private ActivityService activityService;
    @Mock private NewsService newsService;
    @Mock private org.springframework.context.ApplicationEventPublisher events;

    @InjectMocks
    private ImpactServiceImpl impactService;

    private User user;
    private Itinerary itinerary;
    private Activity activity;
    private News news;

    @BeforeEach
    void setUp() {
        user = mock(User.class);

        itinerary = new Itinerary();
        itinerary.setId(1L);
        itinerary.setName("Japan Ski Trip");
        itinerary.setUser(user);

        activity = new Activity();
        activity.setId(10L);
        activity.setName("Ski at Niseko");
        activity.setItinerary(itinerary);

        news = new News();
        news.setId(5L);
        news.setTitle("Earthquake near Hokkaido");
        news.setDescription("Ski resorts suspending lifts.");
    }

    // ---------------------------------------------------------------- helpers

    /** Verdict is a private record, so build it by reflection from whatever type is requested. */
    private void givenModelAnswers(boolean impacted, String reason) {
        when(chatService.ask(anyString(), any(Class.class))).thenAnswer(inv -> {
            Class<?> type = inv.getArgument(1);
            var constructor = type.getDeclaredConstructor(boolean.class, String.class);
            constructor.setAccessible(true);
            return constructor.newInstance(impacted, reason);
        });
    }

    private void givenSaveEchoes() {
        when(impactRepository.save(any(Impact.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Impact storedImpact(String reason) {
        Impact stored = new Impact();
        stored.setImpacted(true);
        stored.setReason(reason);
        return stored;
    }

    // ===== evaluateItineraryForNews =====

    @Test
    @DisplayName("Should return the stored verdict without asking the model when it already has a reason")
    void evaluateItineraryForNews_ShouldReturnStored_WhenAlreadyJudged() {
        Impact stored = storedImpact("Resorts closed");
        when(impactRepository.findFirstByNewsIdAndItineraryIdAndActivityIsNull(5L, 1L))
                .thenReturn(Optional.of(stored));

        Impact result = impactService.evaluateItineraryForNews(itinerary, news);

        assertSame(stored, result);
        verifyNoInteractions(chatService);
        verify(impactRepository, never()).save(any(Impact.class));
    }

    @Test
    @DisplayName("Should ask the model and save a trip-level impact when nothing is stored")
    void evaluateItineraryForNews_ShouldAskModelAndSave_WhenNothingStored() {
        when(impactRepository.findFirstByNewsIdAndItineraryIdAndActivityIsNull(5L, 1L))
                .thenReturn(Optional.empty());
        givenModelAnswers(true, "Quake affects Hokkaido trips");
        givenSaveEchoes();

        Impact result = impactService.evaluateItineraryForNews(itinerary, news);

        assertTrue(result.getImpacted());
        assertEquals("Quake affects Hokkaido trips", result.getReason());
        assertSame(user, result.getUser());          // owner comes from the itinerary
        assertSame(itinerary, result.getItinerary());
        assertSame(news, result.getNews());
        assertNull(result.getActivity());            // null activity = whole itinerary
        verify(chatService, times(1)).ask(anyString(), any(Class.class));
        verify(impactRepository, times(1)).save(any(Impact.class));
    }

    @Test
    @DisplayName("Should store a 'not impacted' verdict too, so the pair is not asked again")
    void evaluateItineraryForNews_ShouldSaveNotImpactedVerdict() {
        when(impactRepository.findFirstByNewsIdAndItineraryIdAndActivityIsNull(5L, 1L))
                .thenReturn(Optional.empty());
        givenModelAnswers(false, "Different country");
        givenSaveEchoes();

        Impact result = impactService.evaluateItineraryForNews(itinerary, news);

        assertFalse(result.getImpacted());
        verify(impactRepository, times(1)).save(any(Impact.class));
    }

    @Test
    @DisplayName("Should update the existing row in place when the stored row has no reason (seed data)")
    void evaluateItineraryForNews_ShouldUpdateSameRow_WhenStoredWithoutReason() {
        Impact seedRow = storedImpact(null);
        when(impactRepository.findFirstByNewsIdAndItineraryIdAndActivityIsNull(5L, 1L))
                .thenReturn(Optional.of(seedRow));
        givenModelAnswers(false, "Not about this trip");
        givenSaveEchoes();

        Impact result = impactService.evaluateItineraryForNews(itinerary, news);

        assertSame(seedRow, result);                 // no second row is created
        assertEquals("Not about this trip", result.getReason());
        assertFalse(result.getImpacted());
    }

    @Test
    @DisplayName("Should throw and save nothing when the model fails")
    void evaluateItineraryForNews_ShouldThrowAndSaveNothing_WhenModelFails() {
        when(impactRepository.findFirstByNewsIdAndItineraryIdAndActivityIsNull(5L, 1L))
                .thenReturn(Optional.empty());
        when(chatService.ask(anyString(), any(Class.class))).thenThrow(new RuntimeException("model down"));

        assertThrows(RuntimeException.class, () -> impactService.evaluateItineraryForNews(itinerary, news));

        verify(impactRepository, never()).save(any(Impact.class));
    }

    // ===== evaluateActivityForNews =====

    @Test
    @DisplayName("Should return the stored activity verdict without asking the model")
    void evaluateActivityForNews_ShouldReturnStored_WhenAlreadyJudged() {
        Impact stored = storedImpact("Lifts closed");
        when(impactRepository.findFirstByNewsIdAndActivityId(5L, 10L)).thenReturn(Optional.of(stored));

        Impact result = impactService.evaluateActivityForNews(activity, news);

        assertSame(stored, result);
        verifyNoInteractions(chatService);
        verify(impactRepository, never()).save(any(Impact.class));
    }

    @Test
    @DisplayName("Should ask the model and save an activity impact when nothing is stored")
    void evaluateActivityForNews_ShouldAskModelAndSave_WhenNothingStored() {
        when(impactRepository.findFirstByNewsIdAndActivityId(5L, 10L)).thenReturn(Optional.empty());
        givenModelAnswers(true, "Niseko lifts suspended");
        givenSaveEchoes();

        Impact result = impactService.evaluateActivityForNews(activity, news);

        assertTrue(result.getImpacted());
        assertSame(activity, result.getActivity());
        assertSame(itinerary, result.getItinerary());   // taken from the activity
        assertSame(user, result.getUser());             // and its owner from the itinerary
        verify(impactRepository, times(1)).save(any(Impact.class));
    }

    @Test
    @DisplayName("Should throw and save nothing when the model fails on an activity")
    void evaluateActivityForNews_ShouldThrowAndSaveNothing_WhenModelFails() {
        when(impactRepository.findFirstByNewsIdAndActivityId(5L, 10L)).thenReturn(Optional.empty());
        when(chatService.ask(anyString(), any(Class.class))).thenThrow(new RuntimeException("model down"));

        assertThrows(RuntimeException.class, () -> impactService.evaluateActivityForNews(activity, news));

        verify(impactRepository, never()).save(any(Impact.class));
    }

    // ===== evaluate (the existing API endpoint, must keep its behaviour) =====

    @Test
    @DisplayName("evaluate() should report 'Could not evaluate' instead of throwing when the model fails")
    void evaluate_ShouldReportCouldNotEvaluate_WhenModelFails() {
        when(itineraryService.getItineraryById(1L)).thenReturn(itinerary);
        when(newsService.getNewsById(5L)).thenReturn(news);
        when(activityService.getActivitiesByItinerary(1L)).thenReturn(List.of(activity));
        when(chatService.ask(anyString(), any(Class.class))).thenThrow(new RuntimeException("model down"));

        EvaluateResponse response = impactService.evaluate(1L, 5L);

        assertFalse(response.impacted());
        assertTrue(response.itinerary().reason().startsWith("Could not evaluate"));
        assertEquals(1, response.activities().size());
        assertTrue(response.activities().get(0).reason().startsWith("Could not evaluate"));
        verify(impactRepository, never()).save(any(Impact.class));
    }

    @Test
    @DisplayName("evaluate() should be impacted overall when any activity is impacted")
    void evaluate_ShouldBeImpacted_WhenAnyActivityIsImpacted() {
        when(itineraryService.getItineraryById(1L)).thenReturn(itinerary);
        when(newsService.getNewsById(5L)).thenReturn(news);
        when(activityService.getActivitiesByItinerary(1L)).thenReturn(List.of(activity));
        givenModelAnswers(true, "Affected");
        givenSaveEchoes();

        EvaluateResponse response = impactService.evaluate(1L, 5L);

        assertTrue(response.impacted());
        assertTrue(response.activities().get(0).impacted());
    }

    // ===== rejected impacts =====

    // an impacted verdict that already has a reason, so evaluate serves it from the database
    private Impact rejectableImpact(Long id, Activity forActivity, Boolean rejected) {
        Impact impact = storedImpact("Some ski resorts are suspending lifts.");
        impact.setId(id);
        impact.setUser(user);
        impact.setNews(news);
        impact.setItinerary(itinerary);
        impact.setActivity(forActivity);
        impact.setRejected(rejected);
        return impact;
    }

    @Test
    @DisplayName("Should mark impact as rejected and save it")
    void setRejected_ShouldMarkRejected_WhenImpactExists() {
        // arrange
        Impact impact = rejectableImpact(1L, activity, null);
        when(impactRepository.findById(1L)).thenReturn(Optional.of(impact));
        givenSaveEchoes();

        // act
        Impact result = impactService.setRejected(1L, true);

        // assert
        assertTrue(result.getRejected());
        verify(impactRepository).save(impact);
    }

    @Test
    @DisplayName("Should keep the model's verdict and reason when rejecting")
    void setRejected_ShouldKeepVerdictAndReason_WhenRejecting() {
        Impact impact = rejectableImpact(1L, activity, null);
        when(impactRepository.findById(1L)).thenReturn(Optional.of(impact));
        givenSaveEchoes();

        Impact result = impactService.setRejected(1L, true);

        // restoring has to bring back exactly what the model said, so neither may change
        assertTrue(result.getImpacted());
        assertEquals("Some ski resorts are suspending lifts.", result.getReason());
    }

    @Test
    @DisplayName("Should clear the rejection when restoring")
    void setRejected_ShouldClearRejection_WhenRestoring() {
        Impact impact = rejectableImpact(1L, activity, true);
        when(impactRepository.findById(1L)).thenReturn(Optional.of(impact));
        givenSaveEchoes();

        Impact result = impactService.setRejected(1L, false);

        assertFalse(result.getRejected());
    }

    @Test
    @DisplayName("Should throw ImpactNotFoundException when impact does not exist")
    void setRejected_ShouldThrow_WhenImpactDoesNotExist() {
        when(impactRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(ImpactNotFoundException.class, () -> impactService.setRejected(99L, true));
        verify(impactRepository, never()).save(any(Impact.class));
    }

    // the trip verdict and the activity verdict are both stored, so evaluate never needs the model
    private void givenStoredVerdicts(Impact tripImpact, Impact activityImpact) {
        when(itineraryService.getItineraryById(1L)).thenReturn(itinerary);
        when(newsService.getNewsById(5L)).thenReturn(news);
        when(activityService.getActivitiesByItinerary(1L)).thenReturn(List.of(activity));
        when(impactRepository.findFirstByNewsIdAndItineraryIdAndActivityIsNull(5L, 1L))
                .thenReturn(Optional.of(tripImpact));
        when(impactRepository.findFirstByNewsIdAndActivityId(5L, 10L))
                .thenReturn(Optional.of(activityImpact));
    }

    @Test
    @DisplayName("Should not ask the model again for a rejected impact")
    void evaluate_ShouldNotAskModel_WhenImpactWasRejected() {
        // arrange
        Impact tripImpact = rejectableImpact(2L, null, false);
        tripImpact.setImpacted(false);
        Impact rejectedImpact = rejectableImpact(1L, activity, true);
        givenStoredVerdicts(tripImpact, rejectedImpact);

        // act
        EvaluateResponse response = impactService.evaluate(1L, 5L);

        // assert
        verifyNoInteractions(chatService);
        verify(impactRepository, never()).save(any(Impact.class));
        assertTrue(response.activities().get(0).rejected());
    }

    @Test
    @DisplayName("Should not count a rejected impact towards the overall verdict")
    void evaluate_ShouldNotBeImpacted_WhenOnlyImpactIsRejected() {
        Impact tripImpact = rejectableImpact(2L, null, false);
        tripImpact.setImpacted(false);
        Impact rejectedImpact = rejectableImpact(1L, activity, true);
        givenStoredVerdicts(tripImpact, rejectedImpact);

        EvaluateResponse response = impactService.evaluate(1L, 5L);

        // the activity is still reported as the model saw it, it just does not count
        assertTrue(response.activities().get(0).impacted());
        assertFalse(response.impacted());
    }

    @Test
    @DisplayName("Should still be impacted when another impact is not rejected")
    void evaluate_ShouldBeImpacted_WhenTripImpactIsNotRejected() {
        Impact tripImpact = rejectableImpact(2L, null, false);
        Impact rejectedImpact = rejectableImpact(1L, activity, true);
        givenStoredVerdicts(tripImpact, rejectedImpact);

        EvaluateResponse response = impactService.evaluate(1L, 5L);

        assertTrue(response.impacted());
    }

    @Test
    @DisplayName("Should leave rejected impacts out of findImpacted")
    void findImpacted_ShouldExcludeRejected_WhenSomeAreRejected() {
        // arrange
        Impact rejected = rejectableImpact(1L, activity, true);
        Impact notRejected = rejectableImpact(2L, null, false);
        Impact olderRow = rejectableImpact(3L, null, null);   // from before the column existed
        when(impactRepository.findByUserIdAndItineraryIdAndImpactedTrue(1L, 1L))
                .thenReturn(List.of(rejected, notRejected, olderRow));

        // act
        List<Impact> result = impactService.findImpacted(1L, 1L);

        // assert
        // the chatbot reads this list, so a rejected impact here would be brought up again
        assertEquals(List.of(notRejected, olderRow), result);
    }
}
