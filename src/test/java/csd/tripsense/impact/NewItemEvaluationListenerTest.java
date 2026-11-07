package csd.tripsense.impact;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import csd.tripsense.activity.Activity;
import csd.tripsense.activity.ActivityCreatedEvent;
import csd.tripsense.activity.ActivityService;
import csd.tripsense.country.Country;
import csd.tripsense.itinerary.Itinerary;
import csd.tripsense.itinerary.ItineraryCreatedEvent;
import csd.tripsense.itinerary.ItineraryService;
import csd.tripsense.news.News;
import csd.tripsense.news.NewsService;

/**
 * Unit tests for NewItemEvaluationListener.
 * Every collaborator is mocked and the executor runs on the calling thread, so there is no
 * database, no AI and no waiting.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NewItemEvaluationListener Unit Tests")
class NewItemEvaluationListenerTest {

    private static final Long COUNTRY_ID = 1L;
    private static final Long ITINERARY_ID = 7L;
    private static final Long ACTIVITY_ID = 100L;

    @Mock private ItineraryService itineraryService;
    @Mock private ActivityService activityService;
    @Mock private NewsService newsService;
    @Mock private ImpactService impactService;

    // ---------------------------------------------------------------- helpers

    // Runnable::run so the "background" work happens on the test thread
    private NewItemEvaluationListener listener(boolean enabled, int lookbackDays, int maxCalls) {
        return new NewItemEvaluationListener(itineraryService, activityService, newsService,
                impactService, enabled, lookbackDays, maxCalls, Runnable::run);
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
        return trip;
    }

    /** A trip that has not ended yet, which the service hands back for ITINERARY_ID. */
    private Itinerary givenActiveTrip() {
        Itinerary trip = trip(LocalDateTime.now().plusDays(10));
        when(itineraryService.getItineraryById(ITINERARY_ID)).thenReturn(trip);
        return trip;
    }

    /** An activity on a trip that has not ended yet, which the service hands back for ACTIVITY_ID. */
    private Activity givenActivityOnActiveTrip() {
        Activity act = activity(ACTIVITY_ID);
        act.setItinerary(trip(LocalDateTime.now().plusDays(10)));
        when(activityService.getActivityById(ACTIVITY_ID)).thenReturn(act);
        return act;
    }

    private void givenNews(News... items) {
        when(newsService.getNewsByCountrySince(eq(COUNTRY_ID), any(LocalDateTime.class)))
                .thenReturn(List.of(items));
    }

    private void itineraryCreated(NewItemEvaluationListener listener) {
        listener.onItineraryCreated(new ItineraryCreatedEvent(ITINERARY_ID));
    }

    private void activityCreated(NewItemEvaluationListener listener) {
        listener.onActivityCreated(new ActivityCreatedEvent(ACTIVITY_ID));
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

        verifyNoInteractions(itineraryService, activityService, newsService, impactService);
    }

    // ===== NEW ITINERARY: WHAT GETS LOOKED AT =====

    @Test
    @DisplayName("Should only ask for news from the configured number of days, measured in UTC")
    void onItineraryCreated_ShouldUseConfiguredLookbackInUtc() {
        givenActiveTrip();
        givenNews();
        ArgumentCaptor<LocalDateTime> sinceCaptor = ArgumentCaptor.forClass(LocalDateTime.class);

        itineraryCreated(listener(true, 5, 200));

        verify(newsService).getNewsByCountrySince(eq(COUNTRY_ID), sinceCaptor.capture());
        assertRoughlyDaysAgoInUtc(5, sinceCaptor.getValue());
    }

    @Test
    @DisplayName("Should move the window when the lookback setting changes")
    void onItineraryCreated_ShouldFollowTheLookbackSetting() {
        givenActiveTrip();
        givenNews();
        ArgumentCaptor<LocalDateTime> sinceCaptor = ArgumentCaptor.forClass(LocalDateTime.class);

        itineraryCreated(listener(true, 1, 200));

        verify(newsService).getNewsByCountrySince(eq(COUNTRY_ID), sinceCaptor.capture());
        assertRoughlyDaysAgoInUtc(1, sinceCaptor.getValue());
    }

    @Test
    @DisplayName("Should skip a trip that has already ended")
    void onItineraryCreated_ShouldSkip_WhenTripAlreadyEnded() {
        when(itineraryService.getItineraryById(ITINERARY_ID))
                .thenReturn(trip(LocalDateTime.now().minusDays(1)));

        itineraryCreated(listener(true, 5, 200));

        verifyNoInteractions(newsService, activityService, impactService);
    }

    @Test
    @DisplayName("Should still evaluate a trip that has no end date")
    void onItineraryCreated_ShouldEvaluate_WhenTripHasNoEndDate() {
        Itinerary open = trip(null);
        when(itineraryService.getItineraryById(ITINERARY_ID)).thenReturn(open);
        News n = news(10L);
        givenNews(n);
        when(activityService.getActivitiesByItinerary(ITINERARY_ID)).thenReturn(List.of());

        itineraryCreated(listener(true, 5, 200));

        verify(impactService, times(1)).evaluateItineraryForNews(open, n);
    }

    @Test
    @DisplayName("Should skip a trip that has no country")
    void onItineraryCreated_ShouldSkip_WhenTripHasNoCountry() {
        Itinerary noCountry = new Itinerary();
        noCountry.setId(ITINERARY_ID);
        when(itineraryService.getItineraryById(ITINERARY_ID)).thenReturn(noCountry);

        itineraryCreated(listener(true, 5, 200));

        verifyNoInteractions(newsService, activityService, impactService);
    }

    @Test
    @DisplayName("Should not call the model when the country has no recent news")
    void onItineraryCreated_ShouldNotEvaluate_WhenNoRecentNews() {
        givenActiveTrip();
        givenNews();   // no news

        itineraryCreated(listener(true, 5, 200));

        verifyNoInteractions(activityService, impactService);
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
        when(activityService.getActivitiesByItinerary(ITINERARY_ID)).thenReturn(List.of(act));

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
        when(activityService.getActivitiesByItinerary(ITINERARY_ID)).thenReturn(List.of());

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
        when(activityService.getActivitiesByItinerary(ITINERARY_ID)).thenReturn(List.of());
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
        when(activityService.getActivitiesByItinerary(ITINERARY_ID)).thenReturn(List.of());
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
        when(activityService.getActivitiesByItinerary(ITINERARY_ID)).thenReturn(List.of());
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
        when(itineraryService.getItineraryById(ITINERARY_ID)).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> itineraryCreated(listener(true, 5, 200)));
    }

    // ===== NEW ITINERARY: CAP =====

    @Test
    @DisplayName("Should stop after max-calls model calls, counting trip and activities together")
    void onItineraryCreated_ShouldStop_WhenCapReached() {
        givenActiveTrip();
        givenNews(news(10L), news(11L));
        when(activityService.getActivitiesByItinerary(ITINERARY_ID))
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

        verifyNoInteractions(itineraryService, activityService, newsService, impactService);
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

        verify(newsService).getNewsByCountrySince(eq(COUNTRY_ID), sinceCaptor.capture());
        assertRoughlyDaysAgoInUtc(5, sinceCaptor.getValue());
    }

    @Test
    @DisplayName("Should skip a new activity whose trip has already ended")
    void onActivityCreated_ShouldSkip_WhenTripAlreadyEnded() {
        Activity act = activity(ACTIVITY_ID);
        act.setItinerary(trip(LocalDateTime.now().minusDays(1)));
        when(activityService.getActivityById(ACTIVITY_ID)).thenReturn(act);

        activityCreated(listener(true, 5, 200));

        verifyNoInteractions(newsService, impactService);
    }

    @Test
    @DisplayName("Should skip a new activity whose trip has no country")
    void onActivityCreated_ShouldSkip_WhenTripHasNoCountry() {
        Activity act = activity(ACTIVITY_ID);
        Itinerary noCountry = new Itinerary();
        noCountry.setId(ITINERARY_ID);
        act.setItinerary(noCountry);
        when(activityService.getActivityById(ACTIVITY_ID)).thenReturn(act);

        activityCreated(listener(true, 5, 200));

        verifyNoInteractions(newsService, impactService);
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
        when(activityService.getActivityById(ACTIVITY_ID)).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> activityCreated(listener(true, 5, 200)));
    }
}