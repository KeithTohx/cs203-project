package csd.tripsense.impact;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import csd.tripsense.user.User;
import csd.tripsense.alert.AlertProgressReporter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import csd.tripsense.activity.Activity;
import csd.tripsense.activity.ActivityCreatedEvent;
import csd.tripsense.activity.ActivityRepository;
import csd.tripsense.country.Country;
import csd.tripsense.itinerary.Itinerary;
import csd.tripsense.itinerary.ItineraryCreatedEvent;
import csd.tripsense.itinerary.ItineraryRepository;
import csd.tripsense.news.News;
import csd.tripsense.news.NewsRepository;

/**
 * Unit tests for NewItemEvaluationListener.
 * Repositories and AI are mocked; the production durable queue is drained synchronously.
 * The incoming lookback, cap, failure-guard, and eligibility cases are preserved.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NewItemEvaluationListener Unit Tests")
class NewItemEvaluationListenerTest {

    private static final Long COUNTRY_ID = 1L;
    private static final Long ITINERARY_ID = 7L;
    private static final Long ACTIVITY_ID = 100L;

    @Mock private ItineraryRepository itineraries;
    @Mock private ActivityRepository activities;
    @Mock private NewsRepository newsRepository;
    @Mock private ImpactService impactService;

    // ---------------------------------------------------------------- helpers

    @Mock private PendingImpactEvaluationRepository tasks;
    @Mock private ImpactRepository impacts;
    @Mock private AlertProgressReporter progress;
    private final Map<String,PendingImpactEvaluation> savedTasks=new LinkedHashMap<>();
    private final List<NewItemEvaluationListener> listeners=new ArrayList<>();

    @BeforeEach void durableRepository() {
        lenient().when(tasks.existsById(anyString())).thenAnswer(i -> savedTasks.containsKey(i.getArgument(0)));
        lenient().when(tasks.findById(anyString())).thenAnswer(i -> Optional.ofNullable(savedTasks.get(i.getArgument(0))));
        lenient().when(tasks.save(any())).thenAnswer(i -> { PendingImpactEvaluation task=i.getArgument(0); savedTasks.put(task.getTaskKey(),task); return task; });
        lenient().when(tasks.findByRetryAtLessThanEqualOrderByRetryAtAscTaskKeyAsc(any(),any()))
                .thenAnswer(i -> new ArrayList<>(savedTasks.values()));
        lenient().doAnswer(i -> { savedTasks.remove(i.getArgument(0)); return null; }).when(tasks).deleteById(anyString());
    }
    @AfterEach void closeWorkers() { listeners.forEach(NewItemEvaluationListener::shutdown); }

    // Drive the production durable worker directly, so no background thread or database is needed.
    private NewItemEvaluationListener listener(boolean enabled, int lookbackDays, int maxCalls) {
        var queue=new ImpactEvaluationQueue(tasks,itineraries,activities,newsRepository,lookbackDays);
        var listener=new NewItemEvaluationListener(queue,tasks,itineraries,activities,newsRepository,impacts,
                impactService,progress,enabled,maxCalls) {
            @Override public void retryPending() { /* The test explicitly drains persisted work below. */ }
        };
        listeners.add(listener);
        return listener;
    }

    private static News news(long id) {
        News n = new News();
        n.setId(id);
        n.setTitle("News " + id);
        return n;
    }

    private static Activity activity(long id) {
        Activity a = new Activity();
        a.setId(id);
        return a;
    }

    private static Itinerary trip(LocalDateTime dateEnd) {
        Country country = new Country();
        country.setId(COUNTRY_ID);
        Itinerary trip = new Itinerary();
        trip.setId(ITINERARY_ID);
        trip.setCountry(country);
        trip.setDateEnd(dateEnd);
        trip.setUser(new User("owner@example.com","owner","long-hash","USER"));
        return trip;
    }

    /** A trip that has not ended yet, which the service hands back for ITINERARY_ID. */
    private Itinerary givenActiveTrip() {
        Itinerary trip = trip(LocalDateTime.now().plusDays(10));
        when(itineraries.findById(ITINERARY_ID)).thenReturn(Optional.of(trip));
        return trip;
    }

    /** An activity on a trip that has not ended yet, which the service hands back for ACTIVITY_ID. */
    private Activity givenActivityOnActiveTrip() {
        Activity act = activity(ACTIVITY_ID);
        act.setItinerary(trip(LocalDateTime.now().plusDays(10)));
        when(activities.findById(ACTIVITY_ID)).thenReturn(Optional.of(act));
        // The incoming activity-only tests assume its parent's verdict was already saved.
        Impact parent=new Impact(); parent.setReason("Already assessed");
        lenient().when(impacts.findFirstByNewsIdAndItineraryIdAndActivityIsNull(anyLong(),eq(ITINERARY_ID)))
                .thenReturn(Optional.of(parent));
        return act;
    }

    private void givenNews(News... items) {
        when(newsRepository.findByCountryIdSince(eq(COUNTRY_ID), any(LocalDateTime.class)))
                .thenReturn(List.of(items));
        for (News item:items) lenient().when(newsRepository.findById(item.getId())).thenReturn(Optional.of(item));
    }

    private void itineraryCreated(NewItemEvaluationListener listener) {
        listener.onItineraryCreated(new ItineraryCreatedEvent(ITINERARY_ID));
        listener.processPending();
    }

    private void activityCreated(NewItemEvaluationListener listener) {
        listener.onActivityCreated(new ActivityCreatedEvent(ACTIVITY_ID));
        listener.processPending();
    }

    private static void assertRoughlyDaysAgoInUtc(int days, LocalDateTime actual) {
        LocalDateTime expected = LocalDateTime.now(ZoneOffset.UTC).minusDays(days);
        assertTrue(Duration.between(expected, actual).abs().toMinutes() < 1);
    }

    // ===== NEW ITINERARY: SWITCH =====

    @Test
    @DisplayName("Should do nothing for a new itinerary when evaluation is disabled")
    void onItineraryCreated_ShouldDoNothing_WhenDisabled() {
        itineraryCreated(listener(false, 5, 200));

        verifyNoInteractions(itineraries, activities, newsRepository, impactService);
    }

    // ===== NEW ITINERARY: WHAT GETS LOOKED AT =====

    @Test
    @DisplayName("Should only ask for news from the configured number of days, measured in UTC")
    void onItineraryCreated_ShouldUseConfiguredLookbackInUtc() {
        givenActiveTrip();
        givenNews();
        ArgumentCaptor<LocalDateTime> sinceCaptor = ArgumentCaptor.forClass(LocalDateTime.class);

        itineraryCreated(listener(true, 5, 200));

        verify(newsRepository).findByCountryIdSince(eq(COUNTRY_ID), sinceCaptor.capture());
        assertRoughlyDaysAgoInUtc(5, sinceCaptor.getValue());
    }

    @Test
    @DisplayName("Should move the window when the lookback setting changes")
    void onItineraryCreated_ShouldFollowTheLookbackSetting() {
        givenActiveTrip();
        givenNews();
        ArgumentCaptor<LocalDateTime> sinceCaptor = ArgumentCaptor.forClass(LocalDateTime.class);

        itineraryCreated(listener(true, 1, 200));

        verify(newsRepository).findByCountryIdSince(eq(COUNTRY_ID), sinceCaptor.capture());
        assertRoughlyDaysAgoInUtc(1, sinceCaptor.getValue());
    }

    @Test
    @DisplayName("Should skip a trip that has already ended")
    void onItineraryCreated_ShouldSkip_WhenTripAlreadyEnded() {
        when(itineraries.findById(ITINERARY_ID))
                .thenReturn(Optional.of(trip(LocalDateTime.now().minusDays(1))));

        itineraryCreated(listener(true, 5, 200));

        verifyNoInteractions(newsRepository, activities, impactService);
    }

    @Test
    @DisplayName("Should still evaluate a trip that has no end date")
    void onItineraryCreated_ShouldEvaluate_WhenTripHasNoEndDate() {
        Itinerary open = trip(null);
        when(itineraries.findById(ITINERARY_ID)).thenReturn(Optional.of(open));
        News n = news(10L);
        givenNews(n);
        when(activities.findByItineraryIdOrderByDateStart(ITINERARY_ID)).thenReturn(List.of());

        itineraryCreated(listener(true, 5, 200));

        verify(impactService, times(1)).evaluateItineraryForNews(open, n);
    }

    @Test
    @DisplayName("Should skip a trip that has no country")
    void onItineraryCreated_ShouldSkip_WhenTripHasNoCountry() {
        Itinerary noCountry = new Itinerary();
        noCountry.setId(ITINERARY_ID);
        when(itineraries.findById(ITINERARY_ID)).thenReturn(Optional.of(noCountry));

        itineraryCreated(listener(true, 5, 200));

        verifyNoInteractions(newsRepository, activities, impactService);
    }

    @Test
    @DisplayName("Should not call the model when the country has no recent news")
    void onItineraryCreated_ShouldNotEvaluate_WhenNoRecentNews() {
        givenActiveTrip();
        givenNews();   // no news

        itineraryCreated(listener(true, 5, 200));

        verifyNoInteractions(activities, impactService);
    }

    // ===== NEW ITINERARY: EVALUATION =====

    @Test
    @DisplayName("Should judge the trip and every activity against every news item")
    void onItineraryCreated_ShouldEvaluateTripAndActivitiesForEachNews() {
        Itinerary trip = givenActiveTrip();
        News n1 = news(10L);
        News n2 = news(11L);
        Activity act = activity(100L);
        givenNews(n1, n2);
        when(activities.findByItineraryIdOrderByDateStart(ITINERARY_ID)).thenReturn(List.of(act));

        itineraryCreated(listener(true, 5, 200));

        verify(impactService, times(1)).evaluateItineraryForNews(trip, n1);
        verify(impactService, times(1)).evaluateItineraryForNews(trip, n2);
        verify(impactService, times(1)).evaluateActivityForNews(act, n1);
        verify(impactService, times(1)).evaluateActivityForNews(act, n2);
    }

    @Test
    @DisplayName("Should still judge the trip as a whole when it has no activities")
    void onItineraryCreated_ShouldEvaluateTrip_WhenNoActivities() {
        Itinerary trip = givenActiveTrip();
        News n = news(10L);
        givenNews(n);
        when(activities.findByItineraryIdOrderByDateStart(ITINERARY_ID)).thenReturn(List.of());

        itineraryCreated(listener(true, 5, 200));

        verify(impactService, times(1)).evaluateItineraryForNews(trip, n);
        verify(impactService, never()).evaluateActivityForNews(any(Activity.class), any(News.class));
    }

    // ===== NEW ITINERARY: FAILURES =====

    @Test
    @DisplayName("Should skip one failed pair and carry on with the next")
    void onItineraryCreated_ShouldContinue_AfterASingleFailure() {
        givenActiveTrip();
        givenNews(news(10L), news(11L));
        when(activities.findByItineraryIdOrderByDateStart(ITINERARY_ID)).thenReturn(List.of());
        when(impactService.evaluateItineraryForNews(any(Itinerary.class), any(News.class)))
                .thenThrow(new RuntimeException("unreadable answer"))
                .thenReturn(new Impact());

        itineraryCreated(listener(true, 5, 200));

        verify(impactService, times(2)).evaluateItineraryForNews(any(Itinerary.class), any(News.class));
    }

    @Test
    @DisplayName("Should stop after 3 failures in a row (model is probably down)")
    void onItineraryCreated_ShouldStop_AfterThreeConsecutiveFailures() {
        givenActiveTrip();
        givenNews(news(10L), news(11L), news(12L), news(13L));
        when(activities.findByItineraryIdOrderByDateStart(ITINERARY_ID)).thenReturn(List.of());
        when(impactService.evaluateItineraryForNews(any(Itinerary.class), any(News.class)))
                .thenThrow(new RuntimeException("model down"));

        itineraryCreated(listener(true, 5, 200));

        verify(impactService, times(3)).evaluateItineraryForNews(any(Itinerary.class), any(News.class));
    }

    @Test
    @DisplayName("Should reset the failure count after a success")
    void onItineraryCreated_ShouldResetFailureCount_AfterASuccess() {
        givenActiveTrip();
        givenNews(news(10L), news(11L), news(12L), news(13L), news(14L));
        when(activities.findByItineraryIdOrderByDateStart(ITINERARY_ID)).thenReturn(List.of());
        RuntimeException failure = new RuntimeException("flaky");
        // fail, fail, ok, fail, fail: never 3 in a row, so all 5 are tried
        when(impactService.evaluateItineraryForNews(any(Itinerary.class), any(News.class)))
                .thenThrow(failure).thenThrow(failure).thenReturn(new Impact())
                .thenThrow(failure).thenThrow(failure);

        itineraryCreated(listener(true, 5, 200));

        verify(impactService, times(5)).evaluateItineraryForNews(any(Itinerary.class), any(News.class));
    }

    @Test
    @DisplayName("Should not let an unexpected error escape, since the itinerary is already saved")
    void onItineraryCreated_ShouldSwallowUnexpectedErrors() {
        when(itineraries.findById(ITINERARY_ID)).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> itineraryCreated(listener(true, 5, 200)));
    }

    // ===== NEW ITINERARY: CAP =====

    @Test
    @DisplayName("Should stop after max-calls model calls, counting trip and activities together")
    void onItineraryCreated_ShouldStop_WhenCapReached() {
        givenActiveTrip();
        givenNews(news(10L), news(11L));
        when(activities.findByItineraryIdOrderByDateStart(ITINERARY_ID))
                .thenReturn(List.of(activity(100L), activity(101L)));
        when(impactService.evaluateItineraryForNews(any(Itinerary.class), any(News.class)))
                .thenReturn(new Impact());
        when(impactService.evaluateActivityForNews(any(Activity.class), any(News.class)))
                .thenReturn(new Impact());

        itineraryCreated(listener(true, 5, 2));   // trip + first activity for the first news, then stop

        verify(impactService, times(1)).evaluateItineraryForNews(any(Itinerary.class), any(News.class));
        verify(impactService, times(1)).evaluateActivityForNews(any(Activity.class), any(News.class));
    }

    // ===== NEW ACTIVITY =====

    @Test
    @DisplayName("Should do nothing for a new activity when evaluation is disabled")
    void onActivityCreated_ShouldDoNothing_WhenDisabled() {
        activityCreated(listener(false, 5, 200));

        verifyNoInteractions(itineraries, activities, newsRepository, impactService);
    }

    @Test
    @DisplayName("Should judge a new activity against each news item, and not the trip itself")
    void onActivityCreated_ShouldEvaluateActivityForEachNews() {
        Activity act = givenActivityOnActiveTrip();
        News n1 = news(10L);
        News n2 = news(11L);
        givenNews(n1, n2);

        activityCreated(listener(true, 5, 200));

        verify(impactService, times(1)).evaluateActivityForNews(act, n1);
        verify(impactService, times(1)).evaluateActivityForNews(act, n2);
        // the trip's own verdict came from when the itinerary was created
        verify(impactService, never()).evaluateItineraryForNews(any(Itinerary.class), any(News.class));
    }

    @Test
    @DisplayName("Should use the same lookback window as a new itinerary")
    void onActivityCreated_ShouldUseConfiguredLookbackInUtc() {
        givenActivityOnActiveTrip();
        givenNews();
        ArgumentCaptor<LocalDateTime> sinceCaptor = ArgumentCaptor.forClass(LocalDateTime.class);

        activityCreated(listener(true, 5, 200));

        verify(newsRepository).findByCountryIdSince(eq(COUNTRY_ID), sinceCaptor.capture());
        assertRoughlyDaysAgoInUtc(5, sinceCaptor.getValue());
    }

    @Test
    @DisplayName("Should skip a new activity whose trip has already ended")
    void onActivityCreated_ShouldSkip_WhenTripAlreadyEnded() {
        Activity act = activity(ACTIVITY_ID);
        act.setItinerary(trip(LocalDateTime.now().minusDays(1)));
        when(activities.findById(ACTIVITY_ID)).thenReturn(Optional.of(act));

        activityCreated(listener(true, 5, 200));

        verifyNoInteractions(newsRepository, impactService);
    }

    @Test
    @DisplayName("Should skip a new activity whose trip has no country")
    void onActivityCreated_ShouldSkip_WhenTripHasNoCountry() {
        Activity act = activity(ACTIVITY_ID);
        Itinerary noCountry = new Itinerary();
        noCountry.setId(ITINERARY_ID);
        act.setItinerary(noCountry);
        when(activities.findById(ACTIVITY_ID)).thenReturn(Optional.of(act));

        activityCreated(listener(true, 5, 200));

        verifyNoInteractions(newsRepository, impactService);
    }

    @Test
    @DisplayName("Should stop evaluating a new activity after 3 failures in a row")
    void onActivityCreated_ShouldStop_AfterThreeConsecutiveFailures() {
        givenActivityOnActiveTrip();
        givenNews(news(10L), news(11L), news(12L), news(13L));
        when(impactService.evaluateActivityForNews(any(Activity.class), any(News.class)))
                .thenThrow(new RuntimeException("model down"));

        activityCreated(listener(true, 5, 200));

        verify(impactService, times(3)).evaluateActivityForNews(any(Activity.class), any(News.class));
    }

    @Test
    @DisplayName("Should stop evaluating a new activity after max-calls model calls")
    void onActivityCreated_ShouldStop_WhenCapReached() {
        givenActivityOnActiveTrip();
        givenNews(news(10L), news(11L), news(12L));
        when(impactService.evaluateActivityForNews(any(Activity.class), any(News.class)))
                .thenReturn(new Impact());

        activityCreated(listener(true, 5, 2));

        verify(impactService, times(2)).evaluateActivityForNews(any(Activity.class), any(News.class));
    }

    @Test
    @DisplayName("Should not let an unexpected error escape when a new activity is evaluated")
    void onActivityCreated_ShouldSwallowUnexpectedErrors() {
        when(activities.findById(ACTIVITY_ID)).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> activityCreated(listener(true, 5, 200)));
    }
    @Test void failedTripVerdictDoesNotPreventItsActivityChecksBelowTheFailureLimit() {
        givenActiveTrip(); givenNews(news(10L));
        Activity first=activity(100L), second=activity(101L);
        when(activities.findByItineraryIdOrderByDateStart(ITINERARY_ID)).thenReturn(List.of(first,second));
        when(impactService.evaluateItineraryForNews(any(),any())).thenThrow(new IllegalStateException("trip answer failed"));
        itineraryCreated(listener(true,5,200));
        verify(impactService).evaluateActivityForNews(eq(first),any());
        verify(impactService).evaluateActivityForNews(eq(second),any());
        assertEquals(List.of(10L),savedTasks.values().iterator().next().articles());
    }

    @Test void consecutiveFailureGuardIncludesTripAndActivityFailures() {
        givenActiveTrip(); givenNews(news(10L));
        when(activities.findByItineraryIdOrderByDateStart(ITINERARY_ID))
                .thenReturn(List.of(activity(100L),activity(101L),activity(102L)));
        when(impactService.evaluateItineraryForNews(any(),any())).thenThrow(new IllegalStateException("trip answer failed"));
        when(impactService.evaluateActivityForNews(any(),any())).thenThrow(new IllegalStateException("activity answer failed"));
        itineraryCreated(listener(true,5,200));
        verify(impactService,times(1)).evaluateItineraryForNews(any(),any());
        verify(impactService,times(2)).evaluateActivityForNews(any(),any());
        assertFalse(savedTasks.isEmpty());
    }

}