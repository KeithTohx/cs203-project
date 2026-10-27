package csd.tripsense.impact;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
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
import csd.tripsense.activity.ActivityService;
import csd.tripsense.itinerary.Itinerary;
import csd.tripsense.itinerary.ItineraryService;
import csd.tripsense.news.News;
import csd.tripsense.news.NewsIngestCompletedEvent;
import csd.tripsense.news.NewsService;

/**
 * Unit tests for ImpactEvaluationJob.
 * Every collaborator is mocked, so there is no database and no AI.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ImpactEvaluationJob Unit Tests")
class ImpactEvaluationJobTest {

    private static final Long COUNTRY_ID = 1L;

    @Mock private ItineraryService itineraryService;
    @Mock private ActivityService activityService;
    @Mock private NewsService newsService;
    @Mock private ImpactService impactService;

    // ---------------------------------------------------------------- helpers

    private ImpactEvaluationJob job(boolean enabled, int maxPerRun) {
        return new ImpactEvaluationJob(itineraryService, activityService, newsService,
                impactService, enabled, 3, maxPerRun);
    }

    private static News news(long id) {
        News n = new News();
        n.setId(id);
        n.setTitle("News " + id);
        return n;
    }

    private static Itinerary itinerary(long id) {
        Itinerary i = new Itinerary();
        i.setId(id);
        return i;
    }

    private static Activity activity(long id) {
        Activity a = new Activity();
        a.setId(id);
        return a;
    }

    /** One country with an active trip, and the given news about it. */
    private void givenCountryWithNews(News... items) {
        when(itineraryService.getCountryIdsWithActiveItineraries(any(LocalDateTime.class)))
                .thenReturn(List.of(COUNTRY_ID));
        when(newsService.getNewsByCountrySince(eq(COUNTRY_ID), any(LocalDateTime.class)))
                .thenReturn(List.of(items));
    }

    private void givenPendingItineraries(News n, Itinerary... items) {
        when(itineraryService.getItinerariesPendingForNews(eq(n.getId()), eq(COUNTRY_ID), any(LocalDateTime.class)))
                .thenReturn(List.of(items));
    }

    private void givenPendingActivities(News n, Activity... items) {
        when(activityService.getActivitiesPendingForNews(eq(n.getId()), eq(COUNTRY_ID), any(LocalDateTime.class)))
                .thenReturn(List.of(items));
    }

    // ===== SWITCH / ENTRY POINTS =====

    @Test
    @DisplayName("Should do nothing when evaluation is disabled")
    void run_ShouldDoNothing_WhenDisabled() {
        job(false, 200).run();

        verifyNoInteractions(itineraryService, activityService, newsService, impactService);
    }

    @Test
    @DisplayName("Should run an evaluation when an ingest-completed event arrives")
    void onIngestCompleted_ShouldRunEvaluation() {
        when(itineraryService.getCountryIdsWithActiveItineraries(any(LocalDateTime.class)))
                .thenReturn(List.of());

        job(true, 200).onIngestCompleted(new NewsIngestCompletedEvent("file-a"));

        verify(itineraryService, times(1)).getCountryIdsWithActiveItineraries(any(LocalDateTime.class));
    }

    @Test
    @DisplayName("Should not let an unexpected error escape, so the ingest schedule keeps running")
    void run_ShouldSwallowUnexpectedErrors() {
        when(itineraryService.getCountryIdsWithActiveItineraries(any(LocalDateTime.class)))
                .thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> job(true, 200).run());
    }

    // ===== WHAT GETS LOOKED AT =====

    @Test
    @DisplayName("Should evaluate nothing when no country has an active itinerary")
    void run_ShouldEvaluateNothing_WhenNoActiveCountries() {
        when(itineraryService.getCountryIdsWithActiveItineraries(any(LocalDateTime.class)))
                .thenReturn(List.of());

        job(true, 200).run();

        verifyNoInteractions(newsService, activityService, impactService);
    }

    @Test
    @DisplayName("Should not look for pending itineraries or activities when a country has no recent news")
    void run_ShouldSkipPendingQueries_WhenCountryHasNoNews() {
        givenCountryWithNews();   // no news

        job(true, 200).run();

        verify(itineraryService, never()).getItinerariesPendingForNews(anyLong(), anyLong(), any(LocalDateTime.class));
        verifyNoInteractions(activityService, impactService);
    }

    @Test
    @DisplayName("Should only ask for news from the last news-lookback-days, measured in UTC")
    void run_ShouldUseLookbackWindowInUtc() {
        givenCountryWithNews();
        ArgumentCaptor<LocalDateTime> sinceCaptor = ArgumentCaptor.forClass(LocalDateTime.class);

        job(true, 200).run();

        verify(newsService).getNewsByCountrySince(eq(COUNTRY_ID), sinceCaptor.capture());
        LocalDateTime expected = LocalDateTime.now(ZoneOffset.UTC).minusDays(3);
        assertTrue(Duration.between(expected, sinceCaptor.getValue()).abs().toMinutes() < 1);
    }

    // ===== EVALUATION =====

    @Test
    @DisplayName("Should evaluate every pending itinerary and activity for each news item")
    void run_ShouldEvaluatePendingItinerariesAndActivities() {
        News n = news(10L);
        Itinerary trip = itinerary(1L);
        Activity act1 = activity(100L);
        Activity act2 = activity(101L);
        givenCountryWithNews(n);
        givenPendingItineraries(n, trip);
        givenPendingActivities(n, act1, act2);

        job(true, 200).run();

        verify(impactService, times(1)).evaluateItineraryForNews(trip, n);
        verify(impactService, times(1)).evaluateActivityForNews(act1, n);
        verify(impactService, times(1)).evaluateActivityForNews(act2, n);
    }

    @Test
    @DisplayName("Should not call the model when nothing is pending (already judged)")
    void run_ShouldNotEvaluate_WhenNothingIsPending() {
        givenCountryWithNews(news(10L));   // pending queries return empty lists by default

        job(true, 200).run();

        verifyNoInteractions(impactService);
    }

    // ===== FAILURES =====

    @Test
    @DisplayName("Should skip one failed pair and carry on with the next")
    void run_ShouldContinue_AfterASingleFailure() {
        News n = news(10L);
        givenCountryWithNews(n);
        givenPendingItineraries(n, itinerary(1L), itinerary(2L));
        when(impactService.evaluateItineraryForNews(any(Itinerary.class), any(News.class)))
                .thenThrow(new RuntimeException("unreadable answer"))
                .thenReturn(new Impact());

        job(true, 200).run();

        verify(impactService, times(2)).evaluateItineraryForNews(any(Itinerary.class), any(News.class));
    }

    @Test
    @DisplayName("Should stop the run after 3 failures in a row (model is probably down)")
    void run_ShouldStop_AfterThreeConsecutiveFailures() {
        News n = news(10L);
        givenCountryWithNews(n);
        givenPendingItineraries(n, itinerary(1L), itinerary(2L), itinerary(3L), itinerary(4L));
        when(impactService.evaluateItineraryForNews(any(Itinerary.class), any(News.class)))
                .thenThrow(new RuntimeException("model down"));

        job(true, 200).run();

        verify(impactService, times(3)).evaluateItineraryForNews(any(Itinerary.class), any(News.class));
        verifyNoInteractions(activityService);   // never got as far as the activities
    }

    @Test
    @DisplayName("Should reset the failure count after a success")
    void run_ShouldResetFailureCount_AfterASuccess() {
        News n = news(10L);
        givenCountryWithNews(n);
        givenPendingItineraries(n, itinerary(1L), itinerary(2L), itinerary(3L), itinerary(4L), itinerary(5L));
        RuntimeException failure = new RuntimeException("flaky");
        // fail, fail, ok, fail, fail: never 3 in a row, so all 5 are tried
        when(impactService.evaluateItineraryForNews(any(Itinerary.class), any(News.class)))
                .thenThrow(failure).thenThrow(failure).thenReturn(new Impact())
                .thenThrow(failure).thenThrow(failure);

        job(true, 200).run();

        verify(impactService, times(5)).evaluateItineraryForNews(any(Itinerary.class), any(News.class));
    }

    // ===== PER-RUN CAP =====

    @Test
    @DisplayName("Should stop after max-per-run model calls")
    void run_ShouldStop_WhenCapReached() {
        News n = news(10L);
        givenCountryWithNews(n);
        givenPendingItineraries(n, itinerary(1L), itinerary(2L), itinerary(3L));
        when(impactService.evaluateItineraryForNews(any(Itinerary.class), any(News.class)))
                .thenReturn(new Impact());

        job(true, 2).run();

        verify(impactService, times(2)).evaluateItineraryForNews(any(Itinerary.class), any(News.class));
    }

    @Test
    @DisplayName("Should count itineraries and activities together against the cap")
    void run_ShouldShareTheCap_BetweenItinerariesAndActivities() {
        News n = news(10L);
        givenCountryWithNews(n);
        givenPendingItineraries(n, itinerary(1L));
        givenPendingActivities(n, activity(100L), activity(101L));
        when(impactService.evaluateItineraryForNews(any(Itinerary.class), any(News.class)))
                .thenReturn(new Impact());
        when(impactService.evaluateActivityForNews(any(Activity.class), any(News.class)))
                .thenReturn(new Impact());

        job(true, 2).run();   // 1 itinerary + 1 activity = 2, the second activity is left for later

        verify(impactService, times(1)).evaluateItineraryForNews(any(Itinerary.class), any(News.class));
        verify(impactService, times(1)).evaluateActivityForNews(any(Activity.class), any(News.class));
    }
}