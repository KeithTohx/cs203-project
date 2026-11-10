package csd.tripsense.impact;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import csd.tripsense.activity.Activity;
import csd.tripsense.activity.ActivityService;
import csd.tripsense.audit.AuditService;
import csd.tripsense.auth.CurrentUser;
import csd.tripsense.chat.ChatService;
import csd.tripsense.itinerary.Itinerary;
import csd.tripsense.itinerary.ItineraryAccess;
import csd.tripsense.itinerary.ItineraryNotFoundException;
import csd.tripsense.itinerary.ItineraryService;
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

    @Mock
    private ItineraryService itineraryService;

    @Mock
    private ActivityService activityService;

    @Mock
    private AuditService auditService;

    private ImpactController impactController;

    private CurrentUser owner;
    private CurrentUser otherUser;
    private CurrentUser admin;
    private Impact testImpact;

    @BeforeEach
    void setUp() {
        ItineraryAccess access = new ItineraryAccess(userRepository);
        impactController = new ImpactController(impactService, chatService, access,
                itineraryService, activityService, auditService);

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

    // ===== OWNER TESTS =====

    @Test
    @DisplayName("Should return the impact when caller owns the itinerary")
    void getImpact_ShouldReturnImpact_WhenCallerIsOwner() {
        // arrange
        when(impactService.getImpact(1L)).thenReturn(testImpact);

        // act
        ImpactResponse response = impactController.getImpact(1L, owner);

        // assert
        assertEquals(1L, response.id());
    }

    @Test
    @DisplayName("Should delete the impact when caller owns the itinerary")
    void deleteImpact_ShouldDelete_WhenCallerIsOwner() {
        // arrange
        when(impactService.getImpact(1L)).thenReturn(testImpact);

        // act
        impactController.deleteImpact(1L, owner);

        // assert
        verify(impactService).deleteImpact(1L);
    }

    @Test
    @DisplayName("Should evaluate the itinerary when caller owns it")
    void evaluate_ShouldRun_WhenCallerOwnsItinerary() {
        // arrange
        when(itineraryService.getItineraryById(1L)).thenReturn(testImpact.getItinerary());

        // act
        impactController.evaluate(new EvaluateRequest(1L, 5L), owner);

        // assert
        verify(impactService).evaluate(1L, 5L);
    }

    @Test
    @DisplayName("Should list only the impacts on the caller's own trips")
    void getImpacts_ShouldOnlyListOwnImpacts() {
        // arrange: an impact with no itinerary belongs to nobody, so it is left out
        Impact orphan = new Impact();
        orphan.setId(2L);
        when(impactService.listImpacts()).thenReturn(List.of(testImpact, orphan));

        // act
        List<ImpactResponse> result = impactController.getImpacts(owner);

        // assert
        assertEquals(List.of(1L), result.stream().map(ImpactResponse::id).toList());
    }

    // ===== OTHER USER TESTS =====

    @ParameterizedTest
    @ValueSource(strings = {"USER", "ADMIN"})
    @DisplayName("Should answer not found to any other account, admins included, and change nothing")
    void allEndpoints_ShouldThrowNotFound_WhenCallerDoesNotOwnTheItinerary(String role) {
        // arrange
        CurrentUser other = new CurrentUser(2L, "alice@example.com", "alice", null, role);
        Activity activity = new Activity();
        activity.setItinerary(testImpact.getItinerary());
        when(impactService.getImpact(1L)).thenReturn(testImpact);
        when(itineraryService.getItineraryById(1L)).thenReturn(testImpact.getItinerary());
        when(activityService.getActivityById(20L)).thenReturn(activity);

        // act + assert: a 404 rather than a 403, so someone else cannot find out which ids exist
        assertThrows(ImpactNotFoundException.class, () -> impactController.getImpact(1L, other));
        assertThrows(ImpactNotFoundException.class, () -> impactController.deleteImpact(1L, other));
        assertThrows(ItineraryNotFoundException.class, () -> impactController.getImpactsByUser(1L, other));
        assertThrows(ItineraryNotFoundException.class, () -> impactController.getImpactsByItinerary(1L, other));
        assertThrows(ItineraryNotFoundException.class, () -> impactController.getImpactsByActivity(20L, other));
        assertThrows(ItineraryNotFoundException.class,
                () -> impactController.evaluate(new EvaluateRequest(1L, 5L), other));

        verify(impactService, never()).deleteImpact(any());
        verify(impactService, never()).evaluate(any(), any());
        verifyNoInteractions(auditService);
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

    // ===== AUDIT TRAIL FOR OVERRIDES =====

    @Test
    @DisplayName("Should record who rejected the AI verdict, and what the AI had said")
    void updateImpact_ShouldAuditRejection() {
        testImpact.setReason("Quake closes the ski lifts");
        Impact rejected = rejectedCopy();
        rejected.setReason("Quake closes the ski lifts");
        when(impactService.getImpact(1L)).thenReturn(testImpact);
        when(impactService.setRejected(1L, true)).thenReturn(rejected);

        impactController.updateImpact(1L, new ImpactUpdateRequest(true), owner);

        verify(auditService).record(eq(AuditService.IMPACT_REJECTED), eq(1L), eq("john@example.com"),
                eq(1L), contains("Quake closes the ski lifts"));
    }

    @Test
    @DisplayName("Should record a restored verdict as its own action")
    void updateImpact_ShouldAuditRestore() {
        testImpact.setRejected(true);
        when(impactService.getImpact(1L)).thenReturn(testImpact);
        when(impactService.setRejected(1L, false)).thenReturn(testImpact);

        impactController.updateImpact(1L, new ImpactUpdateRequest(false), owner);

        verify(auditService).record(eq(AuditService.IMPACT_RESTORED), eq(1L), eq("john@example.com"),
                eq(1L), anyString());
    }

    @Test
    @DisplayName("Should not record anything when the verdict was already in the state asked for")
    void updateImpact_ShouldNotAudit_WhenNothingChanged() {
        testImpact.setRejected(true);
        when(impactService.getImpact(1L)).thenReturn(testImpact);
        when(impactService.setRejected(1L, true)).thenReturn(rejectedCopy());

        impactController.updateImpact(1L, new ImpactUpdateRequest(true), owner);

        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("Should record a deleted verdict with what it said, read before it was deleted")
    void deleteImpact_ShouldAuditDeletion() {
        testImpact.setReason("Strike at the airport");
        when(impactService.getImpact(1L)).thenReturn(testImpact);

        impactController.deleteImpact(1L, owner);

        verify(auditService).record(eq(AuditService.IMPACT_DELETED), eq(1L), eq("john@example.com"),
                eq(1L), contains("Strike at the airport"));
    }
}
