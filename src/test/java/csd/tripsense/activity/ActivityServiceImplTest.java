package csd.tripsense.activity;

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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import csd.tripsense.country.Country;
import csd.tripsense.itinerary.Itinerary;
import csd.tripsense.itinerary.ItineraryService;

@ExtendWith(MockitoExtension.class)
@DisplayName("ActivityService Unit Tests")
class ActivityServiceImplTest {

    @Mock private ActivityRepository activityRepository;
    @Mock private ItineraryService itineraryService;

    @InjectMocks
    private ActivityServiceImpl activityService;

    private Country japan;
    private Country thailand;
    private Itinerary japanTrip;
    private Itinerary thailandTrip;

    @BeforeEach
    void setUp() {
        japan = new Country();
        japan.setId(1L);
        japan.setName("Japan");

        thailand = new Country();
        thailand.setId(3L);
        thailand.setName("Thailand");

        japanTrip = new Itinerary();
        japanTrip.setId(1L);
        japanTrip.setCountry(japan);

        thailandTrip = new Itinerary();
        thailandTrip.setId(2L);
        thailandTrip.setCountry(thailand);
    }

    /** What a client sends: only the itinerary id, nothing else about the itinerary. */
    private Activity requestBody(Long itineraryId) {
        Itinerary stub = new Itinerary();
        stub.setId(itineraryId);
        Activity body = new Activity();
        body.setName("Ski at Niseko");
        body.setItinerary(stub);
        return body;
    }

    // ===== ADD =====

    @Test
    @DisplayName("Should copy the country from the itinerary when adding an activity")
    void addActivity_ShouldCopyCountryFromItinerary() {
        when(itineraryService.getItineraryById(1L)).thenReturn(japanTrip);
        when(activityRepository.save(any(Activity.class))).thenAnswer(inv -> inv.getArgument(0));

        Activity saved = activityService.addActivity(requestBody(1L));

        assertSame(japan, saved.getCountry());
        assertSame(japanTrip, saved.getItinerary());   // the real itinerary replaces the id-only stub
        verify(activityRepository, times(1)).save(any(Activity.class));
    }

    @Test
    @DisplayName("Should ignore a country sent by the client")
    void addActivity_ShouldOverrideClientSuppliedCountry() {
        when(itineraryService.getItineraryById(1L)).thenReturn(japanTrip);
        when(activityRepository.save(any(Activity.class))).thenAnswer(inv -> inv.getArgument(0));
        Activity body = requestBody(1L);
        body.setCountry(thailand);                      // wrong on purpose

        Activity saved = activityService.addActivity(body);

        assertSame(japan, saved.getCountry());
    }

    // ===== UPDATE =====

    @Test
    @DisplayName("Should re-derive the country when the activity moves to another itinerary")
    void updateActivity_ShouldRederiveCountry_WhenItineraryChanges() {
        Activity existing = new Activity();
        existing.setId(10L);
        existing.setItinerary(japanTrip);
        existing.setCountry(japan);
        when(activityRepository.findById(10L)).thenReturn(Optional.of(existing));
        when(itineraryService.getItineraryById(2L)).thenReturn(thailandTrip);
        when(activityRepository.save(any(Activity.class))).thenAnswer(inv -> inv.getArgument(0));

        Activity updated = activityService.updateActivity(10L, requestBody(2L));

        assertSame(thailand, updated.getCountry());
        assertSame(thailandTrip, updated.getItinerary());
    }

    @Test
    @DisplayName("Should keep the current itinerary and country when the update has no itinerary")
    void updateActivity_ShouldKeepItinerary_WhenBodyHasNone() {
        Activity existing = new Activity();
        existing.setId(10L);
        existing.setItinerary(japanTrip);
        existing.setCountry(japan);
        when(activityRepository.findById(10L)).thenReturn(Optional.of(existing));
        when(activityRepository.save(any(Activity.class))).thenAnswer(inv -> inv.getArgument(0));
        Activity body = new Activity();
        body.setName("Renamed");

        Activity updated = activityService.updateActivity(10L, body);

        assertSame(japan, updated.getCountry());
        assertEquals("Renamed", updated.getName());
        verifyNoInteractions(itineraryService);
    }

    @Test
    @DisplayName("Should throw ActivityNotFoundException when updating a non-existent activity")
    void updateActivity_ShouldThrow_WhenActivityDoesNotExist() {
        when(activityRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(ActivityNotFoundException.class,
                () -> activityService.updateActivity(999L, requestBody(1L)));

        verify(activityRepository, never()).save(any(Activity.class));
    }

    // ===== DELETE =====

    @Test
    @DisplayName("Should delete the activity when it exists")
    void deleteActivity_ShouldDelete_WhenActivityExists() {
        when(activityRepository.existsById(10L)).thenReturn(true);

        activityService.deleteActivity(10L);

        verify(activityRepository, times(1)).deleteById(10L);
    }

    @Test
    @DisplayName("Should throw ActivityNotFoundException and delete nothing when the activity does not exist")
    void deleteActivity_ShouldThrowAndDeleteNothing_WhenActivityDoesNotExist() {
        when(activityRepository.existsById(999L)).thenReturn(false);

        assertThrows(ActivityNotFoundException.class, () -> activityService.deleteActivity(999L));

        verify(activityRepository, never()).deleteById(anyLong());
    }

    // ===== PENDING FOR NEWS =====

    @Test
    @DisplayName("Should return the activities the repository says are still pending for the news")
    void getActivitiesPendingForNews_ShouldDelegateToRepository() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 25, 12, 0);
        Activity pending = new Activity();
        when(activityRepository.findPendingForNews(5L, 1L, now)).thenReturn(List.of(pending));

        List<Activity> result = activityService.getActivitiesPendingForNews(5L, 1L, now);

        assertEquals(List.of(pending), result);
        verify(activityRepository, times(1)).findPendingForNews(5L, 1L, now);
    }
}