package csd.tripsense.impact;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import csd.tripsense.auth.CurrentUser;
import csd.tripsense.chat.ChatService;
import csd.tripsense.itinerary.Itinerary;
import csd.tripsense.itinerary.ItineraryAccess;
import csd.tripsense.user.User;
import csd.tripsense.user.UserRepository;

// unit tests for ImpactController. ItineraryAccess is the real one, so these check the
// same ownership rule the itinerary endpoints use, without starting a web server
@ExtendWith(MockitoExtension.class)
@DisplayName("ImpactController Unit Tests")
class ImpactControllerTest {

    @Mock
    private ImpactService impactService;

    @Mock
    private ChatService chatService;

    @Mock
    private UserRepository userRepository;

    private ImpactController impactController;

    private CurrentUser owner;
    private CurrentUser otherUser;
    private CurrentUser admin;
    private Impact testImpact;

    @BeforeEach
    void setUp() {
        ItineraryAccess access = new ItineraryAccess(userRepository);
        impactController = new ImpactController(impactService, chatService, access,
                mock(csd.tripsense.itinerary.ItineraryService.class),
                mock(csd.tripsense.activity.ActivityService.class));

        owner = new CurrentUser(1L, "john@example.com", "john", null, "USER");
        otherUser = new CurrentUser(2L, "alice@example.com", "alice", null, "USER");
        admin = new CurrentUser(3L, "admin@example.com", "admin", null, "ADMIN");

        User john = new User("john@example.com", "john", "password123", "USER");
        ReflectionTestUtils.setField(john, "id", 1L);   // User has no setter for its id

        Itinerary itinerary = new Itinerary();
        itinerary.setId(1L);
        itinerary.setUser(john);

        testImpact = new Impact();
        testImpact.setId(1L);
        testImpact.setUser(john);
        testImpact.setItinerary(itinerary);
        testImpact.setImpacted(true);
    }

    // what the service hands back after saving, so the response can be checked
    private Impact rejectedCopy() {
        Impact rejected = new Impact();
        rejected.setId(testImpact.getId());
        rejected.setItinerary(testImpact.getItinerary());
        rejected.setImpacted(true);
        rejected.setRejected(true);
        return rejected;
    }

    // ===== UPDATE IMPACT TESTS =====

    @Test
    @DisplayName("Should reject impact when caller owns the itinerary")
    void updateImpact_ShouldReject_WhenCallerIsOwner() {
        // arrange
        when(impactService.getImpact(1L)).thenReturn(testImpact);
        when(impactService.setRejected(1L, true)).thenReturn(rejectedCopy());

        // act
        ImpactResponse response = impactController.updateImpact(1L, new ImpactUpdateRequest(true), owner);

        // assert
        assertTrue(response.rejected());
        verify(impactService).setRejected(1L, true);
    }

    @Test
    @DisplayName("Admins cannot change another user's impact")
    void updateImpact_ShouldReject_WhenCallerIsAdmin() {
        when(impactService.getImpact(1L)).thenReturn(testImpact);
        assertThrows(ImpactNotFoundException.class,
                () -> impactController.updateImpact(1L, new ImpactUpdateRequest(true), admin));
        verify(impactService, never()).setRejected(anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("Should answer not found when caller does not own the itinerary")
    void updateImpact_ShouldThrowNotFound_WhenCallerIsNotOwner() {
        when(impactService.getImpact(1L)).thenReturn(testImpact);

        // a 404 rather than a 403, so someone else cannot find out which impact ids exist
        assertThrows(ImpactNotFoundException.class,
                () -> impactController.updateImpact(1L, new ImpactUpdateRequest(true), otherUser));
        verify(impactService, never()).setRejected(anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("Should only let an admin change an impact with no itinerary")
    void updateImpact_ShouldThrowNotFound_WhenImpactHasNoItineraryAndCallerIsNotAdmin() {
        testImpact.setItinerary(null);
        when(impactService.getImpact(1L)).thenReturn(testImpact);

        assertThrows(ImpactNotFoundException.class,
                () -> impactController.updateImpact(1L, new ImpactUpdateRequest(true), owner));
        verify(impactService, never()).setRejected(anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("Should answer unauthorised when nobody is logged in")
    void updateImpact_ShouldThrowUnauthorised_WhenNotLoggedIn() {
        when(impactService.getImpact(1L)).thenReturn(testImpact);

        assertThrows(ResponseStatusException.class,
                () -> impactController.updateImpact(1L, new ImpactUpdateRequest(true), null));
        verify(impactService, never()).setRejected(anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("Should pass not found through when impact does not exist")
    void updateImpact_ShouldThrowNotFound_WhenImpactDoesNotExist() {
        when(impactService.getImpact(99L)).thenThrow(new ImpactNotFoundException(99L));

        assertThrows(ImpactNotFoundException.class,
                () -> impactController.updateImpact(99L, new ImpactUpdateRequest(true), owner));
    }
}
