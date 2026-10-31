package csd.tripsense.activity;

import csd.tripsense.auth.CurrentUser;
import csd.tripsense.itinerary.*;
import csd.tripsense.user.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActivityOwnershipTest {
    private final ActivityService activities = mock(ActivityService.class);
    private final ItineraryService itineraries = mock(ItineraryService.class);
    private final ItineraryAccess access = new ItineraryAccess(mock(UserRepository.class));
    private final ActivityController controller = new ActivityController(activities, itineraries, access);
    private final CurrentUser owner = new CurrentUser(1L, "owner@example.com", "owner", null, "USER");
    private Itinerary trip;
    private Activity activity;

    @BeforeEach
    void setUp() {
        User account = new User("owner@example.com", "owner", "hash", "USER");
        ReflectionTestUtils.setField(account, "id", 1L);
        trip = new Itinerary(); trip.setId(10L); trip.setUser(account);
        activity = new Activity(); activity.setId(20L); activity.setItinerary(trip);
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "ADMIN"})
    void otherAccountsCannotReadChangeDeleteOrCreateActivities(String role) {
        CurrentUser other = new CurrentUser(2L, "other@example.com", "other", null, role);
        when(activities.getActivityById(20L)).thenReturn(activity);
        when(itineraries.getItineraryById(10L)).thenReturn(trip);
        assertThatThrownBy(() -> controller.getActivityById(20L, other)).isInstanceOf(ActivityNotFoundException.class);
        assertThatThrownBy(() -> controller.updateActivity(20L, activity, other)).isInstanceOf(ActivityNotFoundException.class);
        assertThatThrownBy(() -> controller.deleteActivity(20L, other)).isInstanceOf(ActivityNotFoundException.class);
        assertThatThrownBy(() -> controller.getActivitiesByItinerary(10L, other)).isInstanceOf(ItineraryNotFoundException.class);
        assertThatThrownBy(() -> controller.addActivity(activity, other)).isInstanceOf(ItineraryNotFoundException.class);
        verify(activities, never()).addActivity(any());
        verify(activities, never()).updateActivity(any(), any());
        verify(activities, never()).deleteActivity(any());
    }

    @Test
    void collectionsOnlyIncludeOwnActivities() {
        Activity orphan = new Activity(); orphan.setId(21L);
        when(activities.getAllActivities()).thenReturn(List.of(activity, orphan));
        assertThat(controller.getAllActivities(owner)).extracting(ActivityResponse::id).containsExactly(20L);
        assertThatThrownBy(() -> controller.getAllActivities(null)).isInstanceOf(ResponseStatusException.class);
    }

    // the owner's own paths (client id dropped on create, missing activity is 404) are in ActivityControllerTest

    @Test
    void ownerCannotMoveAnActivityToAnotherUsersTrip() {
        User other = new User("other@example.com", "other", "hash", "USER");
        ReflectionTestUtils.setField(other, "id", 2L);
        Itinerary destination = new Itinerary(); destination.setId(11L); destination.setUser(other);
        Activity changes = new Activity(); changes.setItinerary(destination);
        when(activities.getActivityById(20L)).thenReturn(activity);
        when(itineraries.getItineraryById(11L)).thenReturn(destination);
        assertThatThrownBy(() -> controller.updateActivity(20L, changes, owner))
                .isInstanceOf(ItineraryNotFoundException.class);
        verify(activities, never()).updateActivity(any(), any());
    }
}
