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
import org.springframework.context.ApplicationEventPublisher;

import csd.tripsense.country.Country;
import csd.tripsense.itinerary.Itinerary;
import csd.tripsense.itinerary.ItineraryService;

@ExtendWith(MockitoExtension.class)
@DisplayName("ActivityService Unit Tests")
class ActivityServiceImplTest {

    @Mock private ActivityRepository activityRepository;
    @Mock private ItineraryService itineraryService;
    @Mock private ApplicationEventPublisher events;   // without this, @InjectMocks passes null


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

    // ===== CREATED EVENT =====

    @Test
    @DisplayName("Should publish a created event carrying the saved activity's id")
    void addActivity_ShouldPublishCreatedEvent_WithTheSavedId() {
        Activity saved = new Activity();
        saved.setId(25L);
        when(itineraryService.getItineraryById(1L)).thenReturn(japanTrip);
        when(activityRepository.save(any(Activity.class))).thenReturn(saved);

        Activity result = activityService.addActivity(requestBody(1L));

        assertSame(saved, result);
        // the id the database assigned, not the null the request came in with
        verify(events, times(1)).publishEvent(new ActivityCreatedEvent(25L));
    }

    @Test
    @DisplayName("Should publish nothing when the activity could not be saved")
    void addActivity_ShouldNotPublish_WhenSaveFails() {
        when(itineraryService.getItineraryById(1L)).thenReturn(japanTrip);
        when(activityRepository.save(any(Activity.class))).thenThrow(new RuntimeException("db down"));

        assertThrows(RuntimeException.class, () -> activityService.addActivity(requestBody(1L)));

        verifyNoInteractions(events);
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

    @Test
    @DisplayName("Should not publish anything when an update changes nothing the activity was judged on")
    void updateActivity_ShouldNotPublish_WhenNothingJudgedChanged() {
        Activity existing = new Activity();
        existing.setId(10L);
        existing.setItinerary(japanTrip);
        when(activityRepository.findById(10L)).thenReturn(Optional.of(existing));
        when(activityRepository.save(any(Activity.class))).thenAnswer(inv -> inv.getArgument(0));

        activityService.updateActivity(10L, new Activity());

        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("Should not publish anything when only the end time changes, the model never saw it")
    void updateActivity_ShouldNotPublish_WhenOnlyEndChanges() {
        Activity existing = storedActivity();
        when(activityRepository.findById(10L)).thenReturn(Optional.of(existing));
        when(activityRepository.save(any(Activity.class))).thenAnswer(inv -> inv.getArgument(0));

        Activity changes = sameAs(existing);
        changes.setDateEnd(LocalDateTime.of(2027, 1, 8, 18, 0));
        activityService.updateActivity(10L, changes);

        verifyNoInteractions(events);
        // its verdicts still stand, so they must still match
        assertEquals(0, existing.currentRevision());
    }

    @Test
    @DisplayName("Should publish an edited event when the name, address or start changes")
    void updateActivity_ShouldPublish_WhenJudgedFieldChanges() {
        Activity existing = storedActivity();
        when(activityRepository.findById(10L)).thenReturn(Optional.of(existing));
        when(activityRepository.save(any(Activity.class))).thenAnswer(inv -> inv.getArgument(0));

        Activity changes = sameAs(existing);
        changes.setAddress("Furano, Hokkaido");
        activityService.updateActivity(10L, changes);

        verify(events).publishEvent(new ActivityEditedEvent(10L));
        assertEquals(1, existing.currentRevision());
    }

    @Test
    @DisplayName("Should publish an edited event when the activity moves to another trip")
    void updateActivity_ShouldPublish_WhenMovedToAnotherTrip() {
        Activity existing = storedActivity();
        when(activityRepository.findById(10L)).thenReturn(Optional.of(existing));
        when(itineraryService.getItineraryById(2L)).thenReturn(thailandTrip);
        when(activityRepository.save(any(Activity.class))).thenAnswer(inv -> inv.getArgument(0));

        Activity changes = sameAs(existing);
        changes.setItinerary(thailandTrip);
        activityService.updateActivity(10L, changes);

        verify(events).publishEvent(new ActivityEditedEvent(10L));
        assertEquals(1, existing.currentRevision());
    }

    private Activity storedActivity() {
        Activity existing = new Activity();
        existing.setId(10L);
        existing.setItinerary(japanTrip);
        existing.setName("Ski at Niseko");
        existing.setAddress("Niseko, Hokkaido");
        existing.setDateStart(LocalDateTime.of(2027, 1, 8, 9, 0));
        return existing;
    }

    // a request body with the same judged details, so a test can change just one of them
    private static Activity sameAs(Activity stored) {
        Activity body = new Activity();
        body.setName(stored.getName());
        body.setAddress(stored.getAddress());
        body.setDateStart(stored.getDateStart());
        return body;
    }
    // ===== DELETE =====

    @Test
    @DisplayName("Should delete the activity when it exists")
    void deleteActivity_ShouldDelete_WhenActivityExists() {
        when(activityRepository.existsById(10L)).thenReturn(true);
        Activity activity = new Activity(); activity.setItinerary(japanTrip);
        japanTrip.setUser(new csd.tripsense.user.User("owner@example.com", "owner", "hash", "USER"));
        when(activityRepository.findById(10L)).thenReturn(Optional.of(activity));

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