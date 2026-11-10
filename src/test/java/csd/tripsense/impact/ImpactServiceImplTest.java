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
import org.mockito.Mock;
import org.mockito.InjectMocks;
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

    @Mock private csd.tripsense.alert.AlertProgressReporter progress;
    @Mock private AssessmentSourceGuard sourceGuard;

    @InjectMocks
    private ImpactServiceImpl impactService;

    private User user;
    private Itinerary itinerary;
    private Activity activity;
    private News news;

    @BeforeEach
    void setUp() {
        lenient().when(itineraryService.findRevision(any(Itinerary.class))).thenReturn(Optional.of(0L));
        lenient().when(activityService.findRevision(any(Activity.class))).thenReturn(Optional.of(0L));
        lenient().when(sourceGuard.saveIfCurrent(any(), any())).thenAnswer(inv -> {
            java.util.function.Supplier<Impact> save = inv.getArgument(1);
            return save.get();
        });
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
        when(chatService.ask(anyString(), any(Class.class))).thenAnswer(inv -> {
            Class<?> type = inv.getArgument(1);
            var constructor = type.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            // an impacted answer has to name a kind, NONE is what the service overrules
            Class<?> kind = constructor.getParameterTypes()[1];
            return constructor.newInstance(reason,
                    Enum.valueOf((Class<Enum>) kind, impacted ? "DISASTER" : "NONE"), impacted);
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
            return constructor.newInstance(reason, Enum.valueOf((Class<Enum>) kind, "NONE"), true);
        });
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

    @Test void sqliteUniqueCollisionReturnsTheOtherWorkersAssessment() {
        Impact winner = storedImpact("Already assessed"); winner.setId(99L);
        when(impactRepository.findFirstByNewsIdAndItineraryIdAndActivityIsNull(5L, 1L))
                .thenReturn(Optional.empty(), Optional.empty(), Optional.of(winner));
        givenModelAnswers(true, "Affected");
        var sql = new java.sql.SQLException("UNIQUE constraint failed: impact.assessment_key", null, 2067);
        when(impactRepository.save(any(Impact.class))).thenThrow(
                new org.springframework.orm.jpa.JpaSystemException(new RuntimeException(sql)));
        assertSame(winner, impactService.evaluateItineraryForNews(itinerary, news));
        verifyNoInteractions(events);
    }

    @Test void alertProgressDoesNotCrashWhenAnAssessmentReasonIsNull() {
        when(itineraryService.getItineraryById(1L)).thenReturn(itinerary);
        when(newsService.getNewsById(5L)).thenReturn(news);
        when(activityService.getActivitiesByItinerary(1L)).thenReturn(List.of());
        givenModelAnswers(true,null); givenSaveEchoes();
        var result=impactService.evaluate(1L,5L);
        assertNotNull(result);
        assertNull(result.itinerary().reason());
        verify(progress).finish(null,true);
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

    @Test void alertProgressFailureDoesNotChangeASavedPositiveAssessmentResponse() {
        when(itineraryService.getItineraryById(1L)).thenReturn(itinerary);
        when(newsService.getNewsById(5L)).thenReturn(news);
        when(activityService.getActivitiesByItinerary(1L)).thenReturn(List.of());
        givenModelAnswers(true,"Trip affected"); givenSaveEchoes();
        var tracking=mock(EvaluationProgress.class);
        doThrow(new IllegalStateException("progress store unavailable")).when(tracking).resolved(1L,5L,null);
        org.springframework.test.util.ReflectionTestUtils.setField(impactService,"progress",new csd.tripsense.alert.AlertProgressReporter(tracking));
        var response=impactService.evaluate(1L,5L);
        assertTrue(response.impacted());
        assertTrue(response.itinerary().impacted());
        var saved=org.mockito.ArgumentCaptor.forClass(Impact.class);
        verify(impactRepository).save(saved.capture());
        assertTrue(saved.getValue().getImpacted());
        assertEquals("Trip affected",saved.getValue().getReason());
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
        when(impactRepository.findById(3L)).thenReturn(Optional.of(rejectableImpact(3L, null, false)));

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
}
