package csd.tripsense.impact;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionOperations;

import csd.tripsense.activity.Activity;
import csd.tripsense.activity.ActivityService;
import csd.tripsense.alert.Alert;
import csd.tripsense.alert.AlertsRemovedEvent;
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

    private ImpactServiceImpl impactService;

    private User user;
    private Itinerary itinerary;
    private Activity activity;
    private News news;

    @BeforeEach
    void setUp() {
        // runs the guarded save straight away, there is no database to open a transaction on
        impactService = new ImpactServiceImpl(impactRepository, chatService, itineraryService,
                activityService, newsService, events, TransactionOperations.withoutTransaction());

        // nothing has been edited since it was loaded, unless a test says otherwise
        lenient().when(itineraryService.findRevision(any(Itinerary.class))).thenReturn(Optional.of(0L));
        lenient().when(activityService.findRevision(any(Activity.class))).thenReturn(Optional.of(0L));

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
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void givenModelAnswers(boolean impacted, String reason) {
        givenModelAnswers(impacted, reason, 90);
    }

    // the days the model claims the trouble lasts, which the service caps and checks against the trip
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void givenModelAnswers(boolean impacted, String reason, int lastsDays) {
        when(chatService.ask(anyString(), any(Class.class))).thenAnswer(inv -> {
            Class<?> type = inv.getArgument(1);
            var constructor = type.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            // an impacted answer has to name a kind, NONE is what the service overrules
            Class<?> kind = constructor.getParameterTypes()[1];
            return constructor.newInstance(reason,
                    Enum.valueOf((Class<Enum>) kind, impacted ? "DISASTER" : "NONE"), lastsDays, impacted);
        });
    }

    /** Builds a verdict the model never backs up, so the service should overrule it. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void givenModelAnswersWithoutAKind(String reason) {
        when(chatService.ask(anyString(), any(Class.class))).thenAnswer(inv -> {
            Class<?> type = inv.getArgument(1);
            var constructor = type.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            Class<?> kind = constructor.getParameterTypes()[1];
            return constructor.newInstance(reason, Enum.valueOf((Class<Enum>) kind, "NONE"), 0, true);
        });
    }

    // same as above but naming the kind, since the cap that applies depends on it
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void givenModelAnswers(String kindName, String reason, int lastsDays) {
        when(chatService.ask(anyString(), any(Class.class))).thenAnswer(inv -> {
            Class<?> type = inv.getArgument(1);
            var constructor = type.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            Class<?> kind = constructor.getParameterTypes()[1];
            return constructor.newInstance(reason, Enum.valueOf((Class<Enum>) kind, kindName),
                    lastsDays, true);
        });
    }

    // the window is measured from when we first saw the story
    private void givenNewsSeenNow() {
        news.setPublishedAt(LocalDateTime.now());
    }

    private void givenNoStoredItineraryVerdict() {
        when(impactRepository.findFirstByNewsIdAndItineraryIdAndActivityIsNull(5L, 1L))
                .thenReturn(Optional.empty());
    }

    private void givenSaveEchoes() {
        when(impactRepository.save(any(Impact.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Impact storedImpact(String reason) {
        Impact stored = new Impact();
        stored.setImpacted(true);
        stored.setReason(reason);
        stored.setPromptVersion(Impact.CURRENT_PROMPT_VERSION);
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
    @DisplayName("Should overrule an impacted answer the model could not name a kind for")
    void evaluateItineraryForNews_ShouldNotImpact_WhenNoKindNamed() {
        when(impactRepository.findFirstByNewsIdAndItineraryIdAndActivityIsNull(5L, 1L))
                .thenReturn(Optional.empty());
        givenModelAnswersWithoutAKind("Box office record for a monster film");
        givenSaveEchoes();

        Impact result = impactService.evaluateItineraryForNews(itinerary, news);

        // the model said yes but backed it with nothing, which is how the noise used to get in
        assertFalse(result.getImpacted());
        assertFalse(result.getAlertEligible());
    }

    @Test
    @DisplayName("Should reuse the verdict already given for the same headline instead of asking again")
    void evaluateItineraryForNews_ShouldReuseVerdict_WhenSameHeadlineAlreadyJudged() {
        when(impactRepository.findFirstByNewsIdAndItineraryIdAndActivityIsNull(5L, 1L))
                .thenReturn(Optional.empty());
        when(impactRepository.findFirstByItineraryIdAndActivityIsNullAndNewsTitleAndReasonIsNotNull(
                1L, "Earthquake near Hokkaido"))
                .thenReturn(Optional.of(storedImpact("Resorts closed")));
        givenSaveEchoes();

        Impact result = impactService.evaluateItineraryForNews(itinerary, news);

        assertTrue(result.getImpacted());
        assertEquals("Resorts closed", result.getReason());
        verifyNoInteractions(chatService);
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

    // ===== verdicts and edits (revisions) =====

    @Test
    @DisplayName("Should record which revision of the trip a new verdict was judged on")
    void evaluateItineraryForNews_ShouldRecordJudgedRevision() {
        itinerary.setRevision(3L);
        when(itineraryService.findRevision(itinerary)).thenReturn(Optional.of(3L));
        when(impactRepository.findFirstByNewsIdAndItineraryIdAndActivityIsNull(5L, 1L))
                .thenReturn(Optional.empty());
        givenModelAnswers(true, "Quake affects Hokkaido trips");
        givenSaveEchoes();

        Impact result = impactService.evaluateItineraryForNews(itinerary, news);

        assertEquals(3L, result.getJudgedRevision());
    }

    @Test
    @DisplayName("Should ask the model again when the stored verdict was judged on an older revision of the trip")
    void evaluateItineraryForNews_ShouldAskAgain_WhenStoredVerdictIsFromOlderRevision() {
        itinerary.setRevision(2L);
        when(itineraryService.findRevision(itinerary)).thenReturn(Optional.of(2L));
        Impact judgedInJapan = storedImpact("Quake near the trip");
        judgedInJapan.setJudgedRevision(1L);
        when(impactRepository.findFirstByNewsIdAndItineraryIdAndActivityIsNull(5L, 1L))
                .thenReturn(Optional.of(judgedInJapan));
        givenModelAnswers(false, "Different country");
        givenSaveEchoes();

        Impact result = impactService.evaluateItineraryForNews(itinerary, news);

        // a fresh row, so the old row's alert, advice and rejection go with the old row
        verify(impactRepository).delete(judgedInJapan);
        assertNotSame(judgedInJapan, result);
        assertEquals("Different country", result.getReason());
        assertEquals(2L, result.getJudgedRevision());
    }

    @Test
    @DisplayName("Should throw the verdict away when the trip is edited while the model is answering")
    void evaluateItineraryForNews_ShouldNotSave_WhenTripEditedDuringModelCall() {
        // loaded at revision 0, but the edit committed while the model was answering
        when(itineraryService.findRevision(itinerary)).thenReturn(Optional.of(1L));
        when(impactRepository.findFirstByNewsIdAndItineraryIdAndActivityIsNull(5L, 1L))
                .thenReturn(Optional.empty());
        givenModelAnswers(true, "Quake affects Hokkaido trips");

        assertThrows(EditedDuringEvaluationException.class,
                () -> impactService.evaluateItineraryForNews(itinerary, news));

        // nothing saved means no alert either
        verify(impactRepository, never()).save(any(Impact.class));
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("Should throw the verdict away when the trip is deleted while the model is answering")
    void evaluateItineraryForNews_ShouldNotSave_WhenTripDeletedDuringModelCall() {
        when(itineraryService.findRevision(itinerary)).thenReturn(Optional.empty());
        when(impactRepository.findFirstByNewsIdAndItineraryIdAndActivityIsNull(5L, 1L))
                .thenReturn(Optional.empty());
        givenModelAnswers(true, "Quake affects Hokkaido trips");

        assertThrows(EditedDuringEvaluationException.class,
                () -> impactService.evaluateItineraryForNews(itinerary, news));

        verify(impactRepository, never()).save(any(Impact.class));
    }

    @Test
    @DisplayName("Should throw the verdict away when the activity is edited while the model is answering")
    void evaluateActivityForNews_ShouldNotSave_WhenActivityEditedDuringModelCall() {
        when(activityService.findRevision(activity)).thenReturn(Optional.of(1L));
        when(impactRepository.findFirstByNewsIdAndActivityId(5L, 10L)).thenReturn(Optional.empty());
        givenModelAnswers(true, "Niseko lifts suspended");

        assertThrows(EditedDuringEvaluationException.class,
                () -> impactService.evaluateActivityForNews(activity, news));

        verify(impactRepository, never()).save(any(Impact.class));
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("Should keep the verdict another run saved while the model was answering, instead of storing the pair twice")
    void evaluateActivityForNews_ShouldKeepOtherRunsVerdict_WhenSavedMeanwhile() {
        Impact savedByOtherRun = storedImpact("Lifts closed");
        // nothing stored when this run started, the other run's verdict is there by the time it saves
        when(impactRepository.findFirstByNewsIdAndActivityId(5L, 10L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(savedByOtherRun));
        givenModelAnswers(true, "Niseko lifts suspended");

        Impact result = impactService.evaluateActivityForNews(activity, news);

        assertSame(savedByOtherRun, result);
        // no second row, and no second alert for the traveller
        verify(impactRepository, never()).save(any(Impact.class));
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("Should give the verdict a new key every time it is written, even when a seed row is filled in place")
    void evaluateActivityForNews_ShouldGiveVerdictNewKey_WhenWritten() {
        Impact seed = storedImpact(null);
        seed.setVerdictKey("written-before");
        when(impactRepository.findFirstByNewsIdAndActivityId(5L, 10L)).thenReturn(Optional.of(seed));
        givenModelAnswers(true, "Niseko lifts suspended");
        givenSaveEchoes();

        Impact result = impactService.evaluateActivityForNews(activity, news);

        assertSame(seed, result);
        assertNotNull(result.getVerdictKey());
        assertNotEquals("written-before", result.getVerdictKey());
    }

    @Test
    @DisplayName("Should tell the traveller's browser an alert went when the verdict it came from is replaced")
    void evaluateItineraryForNews_ShouldAnnounceRemovedAlert_WhenReplacedVerdictHadOne() {
        when(user.getId()).thenReturn(3L);
        itinerary.setRevision(2L);
        when(itineraryService.findRevision(itinerary)).thenReturn(Optional.of(2L));
        Impact alerted = storedImpact("Quake near the trip");
        alerted.setItinerary(itinerary);
        alerted.setJudgedRevision(1L);
        alerted.setAlerts(List.of(new Alert(alerted)));
        when(impactRepository.findFirstByNewsIdAndItineraryIdAndActivityIsNull(5L, 1L))
                .thenReturn(Optional.of(alerted));
        // the new details are not affected, so no new alert will arrive to refresh the bell
        givenModelAnswers(false, "Different country");
        givenSaveEchoes();

        impactService.evaluateItineraryForNews(itinerary, news);

        verify(impactRepository).delete(alerted);
        verify(events).publishEvent(new AlertsRemovedEvent(3L));
    }

    @Test
    @DisplayName("Should say nothing about alerts when the replaced verdict never had one")
    void evaluateItineraryForNews_ShouldNotAnnounceRemoval_WhenReplacedVerdictHadNoAlert() {
        itinerary.setRevision(2L);
        when(itineraryService.findRevision(itinerary)).thenReturn(Optional.of(2L));
        Impact notAlerted = storedImpact("Quake near the trip");
        notAlerted.setJudgedRevision(1L);
        when(impactRepository.findFirstByNewsIdAndItineraryIdAndActivityIsNull(5L, 1L))
                .thenReturn(Optional.of(notAlerted));
        givenModelAnswers(false, "Different country");
        givenSaveEchoes();

        impactService.evaluateItineraryForNews(itinerary, news);

        verify(events, never()).publishEvent(any(AlertsRemovedEvent.class));
    }

    // ===== hasCurrentVerdict =====

    @Test
    @DisplayName("Should report a current verdict only when it was judged on the details the trip has now")
    void hasCurrentVerdict_Itinerary_ShouldMatchRevision() {
        itinerary.setRevision(2L);
        Impact judgedEarlier = storedImpact("Quake near the trip");
        judgedEarlier.setJudgedRevision(1L);
        when(impactRepository.findFirstByNewsIdAndItineraryIdAndActivityIsNull(5L, 1L))
                .thenReturn(Optional.of(judgedEarlier));

        assertFalse(impactService.hasCurrentVerdict(itinerary, news));

        judgedEarlier.setJudgedRevision(2L);
        assertTrue(impactService.hasCurrentVerdict(itinerary, news));
    }

    @Test
    @DisplayName("Should not count a seed row with no reason, or a missing row, as a current activity verdict")
    void hasCurrentVerdict_Activity_ShouldNeedAReason() {
        when(impactRepository.findFirstByNewsIdAndActivityId(5L, 10L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(storedImpact(null)))
                .thenReturn(Optional.of(storedImpact("Lifts closed")));

        assertFalse(impactService.hasCurrentVerdict(activity, news));
        assertFalse(impactService.hasCurrentVerdict(activity, news));
        assertTrue(impactService.hasCurrentVerdict(activity, news));
    }

    // ===== findUnchanged =====

    private Impact activityVerdict(Long id, String key) {
        Impact verdict = storedImpact("Lifts closed");
        verdict.setId(id);
        verdict.setActivity(activity);
        verdict.setItinerary(itinerary);
        verdict.setVerdictKey(key);
        return verdict;
    }

    @Test
    @DisplayName("Should hand back the stored impact when it is still the verdict that was read")
    void findUnchanged_ShouldReturnStored_WhenSameVerdict() {
        Impact seen = activityVerdict(40L, "key-a");
        Impact stored = activityVerdict(40L, "key-a");
        when(impactRepository.findById(40L)).thenReturn(Optional.of(stored));

        assertEquals(Optional.of(stored), impactService.findUnchanged(seen));
    }

    @Test
    @DisplayName("Should not mistake a new verdict that reused the id for the one that was read")
    void findUnchanged_ShouldBeEmpty_WhenIdReusedByNewVerdict() {
        Impact seen = activityVerdict(40L, "key-a");
        // the edit deleted 40 and sqlite gave the id to the verdict judged on the new details
        Impact replacement = activityVerdict(40L, "key-b");
        when(impactRepository.findById(40L)).thenReturn(Optional.of(replacement));

        assertTrue(impactService.findUnchanged(seen).isEmpty());
    }

    @Test
    @DisplayName("Should be empty once the impact has been deleted")
    void findUnchanged_ShouldBeEmpty_WhenDeleted() {
        when(impactRepository.findById(40L)).thenReturn(Optional.empty());

        assertTrue(impactService.findUnchanged(activityVerdict(40L, "key-a")).isEmpty());
    }

    @Test
    @DisplayName("Should be empty when the activity was edited after the verdict was judged")
    void findUnchanged_ShouldBeEmpty_WhenActivityEditedSince() {
        Impact seen = activityVerdict(40L, "key-a");
        Impact stored = activityVerdict(40L, "key-a");
        stored.setJudgedRevision(0L);
        activity.setRevision(1L);
        when(impactRepository.findById(40L)).thenReturn(Optional.of(stored));

        assertTrue(impactService.findUnchanged(seen).isEmpty());
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
    @DisplayName("Should not count a rejected trip-wide impact towards the overall verdict")
    void evaluate_ShouldNotBeImpacted_WhenOnlyTripImpactIsRejected() {
        // the trip verdict is the one rejected this time, and the activity is fine
        Impact rejectedTripImpact = rejectableImpact(2L, null, true);
        Impact activityImpact = rejectableImpact(1L, activity, false);
        activityImpact.setImpacted(false);
        givenStoredVerdicts(rejectedTripImpact, activityImpact);

        EvaluateResponse response = impactService.evaluate(1L, 5L);

        assertFalse(response.impacted());
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

    // ===== plain lookups and delete =====

    @Test
    @DisplayName("Should hand back every impact the repository holds")
    void listImpacts_ShouldReturnAll() {
        List<Impact> all = List.of(storedImpact("one"), storedImpact("two"));
        when(impactRepository.findAll()).thenReturn(all);

        assertEquals(all, impactService.listImpacts());
    }

    @Test
    @DisplayName("Should hand back only the impacts belonging to the user")
    void getImpactsByUser_ShouldReturnTheUsersRows() {
        List<Impact> mine = List.of(storedImpact("mine"));
        when(impactRepository.findByUserId(7L)).thenReturn(mine);

        assertEquals(mine, impactService.getImpactsByUser(7L));
    }

    @Test
    @DisplayName("Should hand back only the impacts for one activity")
    void getImpactsByActivity_ShouldReturnTheActivityRows() {
        List<Impact> forActivity = List.of(storedImpact("lifts shut"));
        when(impactRepository.findByActivityId(10L)).thenReturn(forActivity);

        assertEquals(forActivity, impactService.getImpactsByActivity(10L));
    }

    @Test
    @DisplayName("Should delete the impact when it exists")
    void deleteImpact_ShouldDelete_WhenImpactExists() {
        when(impactRepository.existsById(3L)).thenReturn(true);

        impactService.deleteImpact(3L);

        verify(impactRepository).deleteById(3L);
    }

    @Test
    @DisplayName("Should throw and delete nothing when the impact does not exist")
    void deleteImpact_ShouldThrow_WhenImpactDoesNotExist() {
        when(impactRepository.existsById(99L)).thenReturn(false);

        assertThrows(ImpactNotFoundException.class, () -> impactService.deleteImpact(99L));
        // a delete that quietly does nothing would hide a bad id from the caller
        verify(impactRepository, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("Should count the impacts on one itinerary for one user")
    void countByItineraryIdAndUserId_ShouldReturnTheCount() {
        when(impactRepository.countByItineraryIdAndUserId(1L, 7L)).thenReturn(4L);

        assertEquals(4L, impactService.countByItineraryIdAndUserId(1L, 7L));
    }

    @Test
    @DisplayName("Should re-ask the model when the stored activity row has no reason (seed data)")
    void evaluateActivityForNews_ShouldUpdateSameRow_WhenStoredWithoutReason() {
        // arrange
        Impact seedRow = storedImpact(null);
        seedRow.setId(42L);
        when(impactRepository.findFirstByNewsIdAndActivityId(news.getId(), activity.getId()))
                .thenReturn(Optional.of(seedRow));
        givenModelAnswers(true, "Lifts are shut.");
        givenSaveEchoes();

        // act
        Impact result = impactService.evaluateActivityForNews(activity, news);

        // assert
        // the same row is updated, a second row for the pair would be re-asked forever
        assertEquals(42L, result.getId());
        assertEquals("Lifts are shut.", result.getReason());
    }

    // ===== the event window =====

    @Test
    @DisplayName("Should not impact a trip when the trouble is over long before it starts")
    void evaluateItineraryForNews_ShouldNotImpact_WhenTroubleEndsBeforeTheTrip() {
        givenNoStoredItineraryVerdict();
        givenNewsSeenNow();
        itinerary.setDateStart(LocalDateTime.now().plusDays(60));
        givenModelAnswers("TRANSPORT", "Flights cancelled by the storm", 7);
        givenSaveEchoes();

        Impact result = impactService.evaluateItineraryForNews(itinerary, news);

        // a week of cancellations cannot reach a trip two months out
        assertFalse(result.getImpacted());
    }

    @Test
    @DisplayName("Should impact a trip when the trouble plausibly lasts into it")
    void evaluateItineraryForNews_ShouldImpact_WhenTroubleLastsIntoTheTrip() {
        givenNoStoredItineraryVerdict();
        givenNewsSeenNow();
        itinerary.setDateStart(LocalDateTime.now().plusDays(30));
        givenModelAnswers("DISASTER", "Quake damaged the rail line", 90);
        givenSaveEchoes();

        Impact result = impactService.evaluateItineraryForNews(itinerary, news);

        assertTrue(result.getImpacted());
    }

    @Test
    @DisplayName("Should cap the days the model asks for at what its kind really lasts")
    void evaluateItineraryForNews_ShouldCapTheModelsEstimate() {
        givenNoStoredItineraryVerdict();
        givenNewsSeenNow();
        itinerary.setDateStart(LocalDateTime.now().plusDays(100));
        // a strike does not shut a country for a year, whatever the model says
        givenModelAnswers("TRANSPORT", "Rail strike", 365);
        givenSaveEchoes();

        Impact result = impactService.evaluateItineraryForNews(itinerary, news);

        assertFalse(result.getImpacted());
    }

    @Test
    @DisplayName("Should fall back to the cap when the model gives no number of days")
    void evaluateItineraryForNews_ShouldUseTheCap_WhenNoDaysGiven() {
        givenNoStoredItineraryVerdict();
        givenNewsSeenNow();
        itinerary.setDateStart(LocalDateTime.now().plusDays(30));
        givenModelAnswers("DISASTER", "Quake near the resort", 0);
        givenSaveEchoes();

        Impact result = impactService.evaluateItineraryForNews(itinerary, news);

        // no answer must not mean no impact, the cap stands in
        assertTrue(result.getImpacted());
    }

    @Test
    @DisplayName("Should keep the verdict when the trip has no dates to compare")
    void evaluateItineraryForNews_ShouldKeepVerdict_WhenTripHasNoDates() {
        givenNoStoredItineraryVerdict();
        news.setPublishedAt(LocalDateTime.now().minusDays(400));
        givenModelAnswers("DISASTER", "Quake near the resort", 5);
        givenSaveEchoes();

        Impact result = impactService.evaluateItineraryForNews(itinerary, news);

        assertTrue(result.getImpacted());
    }

    @Test
    @DisplayName("Should not impact an activity when the trouble is over before it")
    void evaluateActivityForNews_ShouldNotImpact_WhenTroubleEndsBeforeIt() {
        when(impactRepository.findFirstByNewsIdAndActivityId(5L, 10L)).thenReturn(Optional.empty());
        givenNewsSeenNow();
        activity.setDateStart(LocalDateTime.now().plusDays(45));
        givenModelAnswers("UNREST", "Protests in the city centre", 30);
        givenSaveEchoes();

        Impact result = impactService.evaluateActivityForNews(activity, news);

        assertFalse(result.getImpacted());
    }

    @Test
    @DisplayName("Should judge an activity with no date of its own against the trip dates")
    void evaluateActivityForNews_ShouldUseTripDates_WhenActivityHasNoDate() {
        when(impactRepository.findFirstByNewsIdAndActivityId(5L, 10L)).thenReturn(Optional.empty());
        givenNewsSeenNow();
        itinerary.setDateStart(LocalDateTime.now().plusDays(60));
        givenModelAnswers("TRANSPORT", "Airport closed by snow", 14);
        givenSaveEchoes();

        Impact result = impactService.evaluateActivityForNews(activity, news);

        assertFalse(result.getImpacted());
    }
}
