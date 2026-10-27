package csd.tripsense.impact;

import csd.tripsense.activity.*;
import csd.tripsense.auth.CurrentUser;
import csd.tripsense.chat.ChatService;
import csd.tripsense.itinerary.*;
import csd.tripsense.user.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImpactOwnershipTest {
    private final ImpactService impacts = mock(ImpactService.class);
    private final ItineraryService itineraries = mock(ItineraryService.class);
    private final ActivityService activities = mock(ActivityService.class);
    private final ImpactController controller = new ImpactController(impacts, mock(ChatService.class),
            new ItineraryAccess(mock(UserRepository.class)), itineraries, activities);
    private final CurrentUser owner = new CurrentUser(1L, "owner@example.com", "owner", null, "USER");
    private Itinerary trip;
    private Impact impact;

    @BeforeEach
    void setUp() {
        User account = new User("owner@example.com", "owner", "hash", "USER");
        ReflectionTestUtils.setField(account, "id", 1L);
        trip = new Itinerary(); trip.setId(10L); trip.setUser(account);
        impact = new Impact(); impact.setId(30L); impact.setItinerary(trip); impact.setUser(account);
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "ADMIN"})
    void otherAccountsCannotReadDeleteOrEvaluateImpacts(String role) {
        CurrentUser other = new CurrentUser(2L, "other@example.com", "other", null, role);
        when(impacts.getImpact(30L)).thenReturn(impact);
        when(itineraries.getItineraryById(10L)).thenReturn(trip);
        Activity activity = new Activity(); activity.setItinerary(trip);
        when(activities.getActivityById(20L)).thenReturn(activity);
        assertThatThrownBy(() -> controller.getImpact(30L, other)).isInstanceOf(ImpactNotFoundException.class);
        assertThatThrownBy(() -> controller.deleteImpact(30L, other)).isInstanceOf(ImpactNotFoundException.class);
        assertThatThrownBy(() -> controller.getImpactsByUser(1L, other)).isInstanceOf(ItineraryNotFoundException.class);
        assertThatThrownBy(() -> controller.getImpactsByItinerary(10L, other)).isInstanceOf(ItineraryNotFoundException.class);
        assertThatThrownBy(() -> controller.getImpactsByActivity(20L, other)).isInstanceOf(ItineraryNotFoundException.class);
        assertThatThrownBy(() -> controller.evaluate(new EvaluateRequest(10L, 1L), other)).isInstanceOf(ItineraryNotFoundException.class);
        verify(impacts, never()).deleteImpact(any());
        verify(impacts, never()).evaluate(any(), any());
    }

    @Test
    void collectionsOnlyIncludeOwnImpacts() {
        Impact orphan = new Impact(); orphan.setId(31L);
        when(impacts.listImpacts()).thenReturn(List.of(impact, orphan));
        assertThat(controller.getImpacts(owner)).extracting(ImpactResponse::id).containsExactly(30L);
    }

    @Test
    void ownerCanReadDeleteAndEvaluate() {
        when(impacts.getImpact(30L)).thenReturn(impact);
        when(itineraries.getItineraryById(10L)).thenReturn(trip);
        assertThat(controller.getImpact(30L, owner).id()).isEqualTo(30L);
        controller.deleteImpact(30L, owner);
        controller.evaluate(new EvaluateRequest(10L, 1L), owner);
        verify(impacts).deleteImpact(30L);
        verify(impacts).evaluate(10L, 1L);
    }
}
