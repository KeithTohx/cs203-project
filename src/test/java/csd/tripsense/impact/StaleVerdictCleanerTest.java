package csd.tripsense.impact;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import csd.tripsense.activity.Activity;
import csd.tripsense.activity.ActivityEditedEvent;
import csd.tripsense.itinerary.ItineraryEditedEvent;

@ExtendWith(MockitoExtension.class)
@DisplayName("StaleVerdictCleaner Unit Tests")
class StaleVerdictCleanerTest {

    @Mock private ImpactRepository impacts;

    @InjectMocks
    private StaleVerdictCleaner cleaner;

    private static Impact wholeTripImpact() {
        return new Impact();
    }

    private static Impact activityImpact() {
        Impact impact = new Impact();
        impact.setActivity(new Activity());
        return impact;
    }

    @Test
    @DisplayName("Should clear only the whole trip verdicts when a trip's country or dates change")
    void onItineraryEdited_ClearsOnlyWholeTripVerdicts() {
        Impact wholeTrip = wholeTripImpact();
        Impact onActivity = activityImpact();
        when(impacts.findByItineraryId(7L)).thenReturn(List.of(wholeTrip, onActivity));

        cleaner.onItineraryEdited(new ItineraryEditedEvent(7L, false));

        // the activity verdict was judged on the activity alone, so it stands
        verify(impacts).deleteAll(List.of(wholeTrip));
    }

    @Test
    @DisplayName("Should clear the activity verdicts too when a trip moves to another country")
    void onItineraryEdited_CountryChanged_ClearsEveryVerdictOnTheTrip() {
        List<Impact> onTrip = List.of(wholeTripImpact(), activityImpact());
        when(impacts.findByItineraryId(7L)).thenReturn(onTrip);

        cleaner.onItineraryEdited(new ItineraryEditedEvent(7L, true));

        // they were about news from the old country, which the news job no longer checks
        verify(impacts).deleteAll(onTrip);
    }

    @Test
    @DisplayName("Should clear every verdict on an activity whose judged details changed")
    void onActivityEdited_ClearsThatActivitysVerdicts() {
        List<Impact> forActivity = List.of(activityImpact(), activityImpact());
        when(impacts.findByActivityId(100L)).thenReturn(forActivity);

        cleaner.onActivityEdited(new ActivityEditedEvent(100L));

        verify(impacts).deleteAll(forActivity);
    }

    @Test
    @DisplayName("Should not touch the database when there is nothing to clear")
    void onItineraryEdited_NothingJudgedYet_DeletesNothing() {
        when(impacts.findByItineraryId(7L)).thenReturn(List.of(activityImpact()));

        cleaner.onItineraryEdited(new ItineraryEditedEvent(7L, false));

        verify(impacts, never()).deleteAll(any());
    }
}
