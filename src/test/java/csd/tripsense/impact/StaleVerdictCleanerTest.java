package csd.tripsense.impact;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import csd.tripsense.activity.Activity;
import csd.tripsense.activity.ActivityEditedEvent;
import csd.tripsense.alert.Alert;
import csd.tripsense.alert.AlertsRemovedEvent;
import csd.tripsense.itinerary.Itinerary;
import csd.tripsense.itinerary.ItineraryEditedEvent;
import csd.tripsense.news.News;
import csd.tripsense.user.User;

// the edit raises the revisions before this runs, so each test sets the trip and activity up as
// they are after the edit, and the verdicts with the revision they were judged on
@ExtendWith(MockitoExtension.class)
@DisplayName("StaleVerdictCleaner Unit Tests")
class StaleVerdictCleanerTest {

    @Mock private ImpactRepository impacts;
    @Mock private ApplicationEventPublisher events;
    @Mock private EditedAssessmentCleaner metadata;

    @InjectMocks
    private StaleVerdictCleaner cleaner;

    private Itinerary trip;
    private Activity skiing;

    @BeforeEach
    void setUp() {
        trip = new Itinerary();
        trip.setId(7L);
        trip.setRevision(1L);

        skiing = new Activity();
        skiing.setId(100L);
        skiing.setItinerary(trip);
        skiing.setRevision(1L);
    }

    private Impact wholeTripVerdict(Long judgedRevision) {
        return wholeTripVerdict(judgedRevision, 50L);
    }

    private Impact wholeTripVerdict(Long judgedRevision, Long newsId) {
        News article = new News();
        article.setId(newsId);

        Impact impact = new Impact();
        impact.setItinerary(trip);
        impact.setNews(article);
        impact.setJudgedRevision(judgedRevision);
        return impact;
    }

    private Impact activityVerdict(Long judgedRevision) {
        Impact impact = wholeTripVerdict(judgedRevision);
        impact.setActivity(skiing);
        return impact;
    }

    @Test
    @DisplayName("Should clear only the verdicts judged on an older revision when a trip is edited")
    void onItineraryEdited_ClearsOnlyOutOfDateVerdicts() {
        trip.setRevision(2L);
        Impact oldWholeTrip = wholeTripVerdict(1L);
        // the activity was not touched by a rename or new dates, so its revision did not move
        Impact activityStillStands = activityVerdict(1L);
        when(impacts.findByItineraryId(7L)).thenReturn(List.of(oldWholeTrip, activityStillStands));

        cleaner.onItineraryEdited(new ItineraryEditedEvent(7L));

        verify(impacts).deleteAll(List.of(oldWholeTrip));
    }

    @Test
    @DisplayName("Should clear the activity verdicts too when the trip's country change raised their revision")
    void onItineraryEdited_CountryChanged_ClearsActivityVerdicts() {
        trip.setRevision(2L);
        skiing.setRevision(2L);
        List<Impact> onTrip = List.of(wholeTripVerdict(1L), activityVerdict(1L));
        when(impacts.findByItineraryId(7L)).thenReturn(onTrip);

        cleaner.onItineraryEdited(new ItineraryEditedEvent(7L));

        verify(impacts).deleteAll(onTrip);
    }

    @Test
    @DisplayName("Should keep verdicts already judged on the latest details, so a second queued edit does not throw them away")
    void onItineraryEdited_KeepsVerdictsJudgedOnLatestRevision() {
        trip.setRevision(3L);
        Impact judgedAfterFirstEdit = wholeTripVerdict(2L);
        Impact judgedOnLatest = wholeTripVerdict(3L);
        when(impacts.findByItineraryId(7L)).thenReturn(List.of(judgedAfterFirstEdit, judgedOnLatest));

        cleaner.onItineraryEdited(new ItineraryEditedEvent(7L));

        verify(impacts).deleteAll(List.of(judgedAfterFirstEdit));
    }

    @Test
    @DisplayName("Should treat verdicts from before revisions existed as revision 0")
    void onItineraryEdited_OlderRowsWithoutRevision_CountAsRevisionZero() {
        trip.setRevision(null);
        Impact olderRow = wholeTripVerdict(null);
        when(impacts.findByItineraryId(7L)).thenReturn(List.of(olderRow));

        cleaner.onItineraryEdited(new ItineraryEditedEvent(7L));

        verify(impacts, never()).deleteAll(any());
    }

    @Test
    @DisplayName("Should clear an edited activity's out of date verdicts")
    void onActivityEdited_ClearsThatActivitysOutOfDateVerdicts() {
        skiing.setRevision(2L);
        Impact oldVerdict = activityVerdict(1L);
        Impact freshVerdict = activityVerdict(2L);
        when(impacts.findByActivityId(100L)).thenReturn(List.of(oldVerdict, freshVerdict));

        cleaner.onActivityEdited(new ActivityEditedEvent(100L));

        verify(impacts).deleteAll(List.of(oldVerdict));
    }

    @Test
    @DisplayName("Should not touch the database when there is nothing to clear")
    void onItineraryEdited_NothingOutOfDate_DeletesNothing() {
        when(impacts.findByItineraryId(7L)).thenReturn(List.of(activityVerdict(1L)));

        cleaner.onItineraryEdited(new ItineraryEditedEvent(7L));

        verify(impacts, never()).deleteAll(any());
        verifyNoInteractions(events);
    }

    // ===== telling the browser its alerts went =====

    @Test
    @DisplayName("Should tell the traveller once, after the delete, when cleared verdicts took alerts with them")
    void onActivityEdited_AlertedVerdictsCleared_AnnouncesRemovalOnce() {
        User traveller = mock(User.class);
        when(traveller.getId()).thenReturn(3L);
        trip.setUser(traveller);
        skiing.setRevision(2L);
        Impact firstAlerted = activityVerdict(1L);
        firstAlerted.setAlerts(List.of(new Alert(firstAlerted)));
        Impact secondAlerted = activityVerdict(0L);
        secondAlerted.setAlerts(List.of(new Alert(secondAlerted)));
        when(impacts.findByActivityId(100L)).thenReturn(List.of(firstAlerted, secondAlerted));

        cleaner.onActivityEdited(new ActivityEditedEvent(100L));

        // published inside the edit's transaction, the stream only sends it once that commits
        InOrder order = inOrder(impacts, events);
        order.verify(impacts).deleteAll(List.of(firstAlerted, secondAlerted));
        order.verify(events, times(1)).publishEvent(new AlertsRemovedEvent(3L));
    }

    @Test
    @DisplayName("Should not announce anything when none of the cleared verdicts had an alert")
    void onActivityEdited_NoAlertsCleared_AnnouncesNothing() {
        skiing.setRevision(2L);
        Impact notAlerted = activityVerdict(1L);
        when(impacts.findByActivityId(100L)).thenReturn(List.of(notAlerted));

        cleaner.onActivityEdited(new ActivityEditedEvent(100L));

        verify(impacts).deleteAll(List.of(notAlerted));
        verify(events, never()).publishEvent(any(AlertsRemovedEvent.class));
    }

    // ===== handing the cleared articles on to be judged again =====

    @Test
    @DisplayName("Should hand on every cleared article, under the trip or activity it was judged against, after the delete")
    void onItineraryEdited_PublishesClearedArticles() {
        trip.setRevision(2L);
        skiing.setRevision(2L);
        Impact oldTripVerdict = wholeTripVerdict(1L, 50L);
        Impact olderTripVerdict = wholeTripVerdict(0L, 51L);
        Impact oldActivityVerdict = wholeTripVerdict(1L, 52L);
        oldActivityVerdict.setActivity(skiing);
        Impact freshTripVerdict = wholeTripVerdict(2L, 53L);
        when(impacts.findByItineraryId(7L))
                .thenReturn(List.of(oldTripVerdict, olderTripVerdict, oldActivityVerdict, freshTripVerdict));

        cleaner.onItineraryEdited(new ItineraryEditedEvent(7L));

        // the fresh verdict stays, so its article is not handed on
        VerdictsClearedEvent expected = new VerdictsClearedEvent(
                Map.of(7L, Set.of(50L, 51L)),
                Map.of(100L, Set.of(52L)));
        InOrder order = inOrder(impacts, events);
        order.verify(impacts).deleteAll(any());
        order.verify(events).publishEvent(expected);
    }
}
