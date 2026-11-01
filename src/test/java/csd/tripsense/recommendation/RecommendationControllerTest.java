package csd.tripsense.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import csd.tripsense.auth.CurrentUser;
import csd.tripsense.itinerary.Itinerary;
import csd.tripsense.itinerary.ItineraryAccess;
import csd.tripsense.itinerary.ItineraryNotFoundException;
import csd.tripsense.itinerary.ItineraryService;
import csd.tripsense.user.User;
import csd.tripsense.user.UserRepository;

/**
 * Checks that only the owner of an itinerary can generate or read its recommendations.
 *
 * The real ItineraryAccess is used on purpose, so these tests prove the controller and the
 * ownership rule work together. Only the two services are mocked, which lets us check that
 * nothing is generated or read when the caller is not the owner.
 *
 * Each rejected case ends with verifyNoInteractions(recommendationService). That line is the
 * point of the class: for generate it proves a stranger cannot trigger a model call or create
 * rows, and for the GET it proves no data is read.
 */
class RecommendationControllerTest {

    // the one itinerary every test works on
    private static final Long ITINERARY_ID = 10L;

    private RecommendationService recommendationService;
    private ItineraryService itineraryService;
    private RecommendationController controller;

    /**
     * John owns the itineraries in these tests, 
     * Bob is a different user, 
     * Admin has the ADMIN role but still must not see other people's trips
     */
    private final CurrentUser john = new CurrentUser(1L, "john@example.com", "john", null, "USER", true);
    private final CurrentUser bob = new CurrentUser(4L, "bob@example.com", "bob", null, "USER", true);
    private final CurrentUser admin = new CurrentUser(3L, "admin@example.com", "admin", null, "ADMIN", true);

    @BeforeEach
    void setUp() {
        recommendationService = mock(RecommendationService.class);
        itineraryService = mock(ItineraryService.class);
        // ItineraryAccess only needs the repository for prepareNew, which the controller never calls
        ItineraryAccess access = new ItineraryAccess(mock(UserRepository.class));
        controller = new RecommendationController(recommendationService, itineraryService, access);
    }

    /**
     * Builds an itinerary owned by the user with this id and makes the service return it,
     * the same way the real service would when the controller looks it up by id.
     */
    private Itinerary existingItineraryOwnedBy(long userId) {
        User owner = mock(User.class);
        when(owner.getId()).thenReturn(userId);
        Itinerary itinerary = new Itinerary();
        itinerary.setId(ITINERARY_ID);
        itinerary.setUser(owner);
        when(itineraryService.getItineraryById(ITINERARY_ID)).thenReturn(itinerary);
        return itinerary;
    }

    // ---------- POST /recommendation/generate ----------

    /**
     * happy path: the owner reaches the service. 
     * The list is empty because this test is about access, not about what a recommendation contains
     */
    @Test
    void generate_owner_getsTheirRecommendations() {
        existingItineraryOwnedBy(1L);
        when(recommendationService.generate(ITINERARY_ID)).thenReturn(List.of());

        List<RecommendationResponse> result = controller.generate(new GenerateRequest(ITINERARY_ID), john);

        assertThat(result).isEmpty();
        verify(recommendationService).generate(ITINERARY_ID);
    }

    /**
     * The main security case: generate is the expensive one (model calls plus database writes),
     * so a stranger must be stopped before the service is touched at all
     */
    @Test
    void generate_otherUser_getsNotFound_andNothingIsGenerated() {
        existingItineraryOwnedBy(1L);

        assertThatThrownBy(() -> controller.generate(new GenerateRequest(ITINERARY_ID), bob))
                .isInstanceOf(ItineraryNotFoundException.class);

        // no model call and no rows written for someone else's trip
        verifyNoInteractions(recommendationService);
    }

    /** Being an admin gives no access to other people's trips, only the owner counts */
    @Test
    void generate_admin_cannotGenerateForAnotherUsersItinerary() {
        existingItineraryOwnedBy(1L);

        assertThatThrownBy(() -> controller.generate(new GenerateRequest(ITINERARY_ID), admin))
                .isInstanceOf(ItineraryNotFoundException.class);

        verifyNoInteractions(recommendationService);
    }

    /**
     * A missing itinerary and someone else's itinerary must look the same to the caller,
     * otherwise a user could probe which ids exist
     */
    @Test
    void generate_missingItinerary_getsTheSameNotFound() {
        when(itineraryService.getItineraryById(ITINERARY_ID))
                .thenThrow(new ItineraryNotFoundException(ITINERARY_ID));

        assertThatThrownBy(() -> controller.generate(new GenerateRequest(ITINERARY_ID), john))
                .isInstanceOf(ItineraryNotFoundException.class);

        verifyNoInteractions(recommendationService);
    }

    /** An itinerary with no user attached belongs to nobody, so nobody can use it */
    @Test
    void generate_itineraryWithNoOwner_isInaccessible() {
        Itinerary orphan = new Itinerary();
        orphan.setId(ITINERARY_ID);
        when(itineraryService.getItineraryById(ITINERARY_ID)).thenReturn(orphan);

        assertThatThrownBy(() -> controller.generate(new GenerateRequest(ITINERARY_ID), john))
                .isInstanceOf(ItineraryNotFoundException.class);

        verifyNoInteractions(recommendationService);
    }

    /**
     * Security normally stops this earlier, 
     * but if a null caller ever reaches the controller it must be a 401 
     * and not a NullPointerException or an accidental success
     */
    @Test
    void generate_notLoggedIn_isRejectedWith401() {
        existingItineraryOwnedBy(1L);

        assertThatThrownBy(() -> controller.generate(new GenerateRequest(ITINERARY_ID), null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));

        verifyNoInteractions(recommendationService);
    }

    // ---------- GET /itinerary/{id}/recommendation ----------

    /** happy path: the owner can read the stored recommendations */
    @Test
    void getByItinerary_owner_getsStoredRecommendations() {
        existingItineraryOwnedBy(1L);
        when(recommendationService.getByItinerary(ITINERARY_ID)).thenReturn(List.of());

        List<RecommendationResponse> result = controller.getByItinerary(ITINERARY_ID, john);

        assertThat(result).isEmpty();
        verify(recommendationService).getByItinerary(ITINERARY_ID);
    }

    /** The read endpoint used to answer for anyone, 
     * so this is the test that would have caught the original hole: 
     * another user must get 404 and no data may be read
     */
    @Test
    void getByItinerary_otherUser_getsNotFound_andNothingIsRead() {
        existingItineraryOwnedBy(1L);

        assertThatThrownBy(() -> controller.getByItinerary(ITINERARY_ID, bob))
                .isInstanceOf(ItineraryNotFoundException.class);

        verifyNoInteractions(recommendationService);
    }

    /** Same rule as for generate, the admin role does not unlock other people's data */
    @Test
    void getByItinerary_admin_cannotReadAnotherUsersRecommendations() {
        existingItineraryOwnedBy(1L);

        assertThatThrownBy(() -> controller.getByItinerary(ITINERARY_ID, admin))
                .isInstanceOf(ItineraryNotFoundException.class);

        verifyNoInteractions(recommendationService);
    }

    /** 
     * Before the fix this returned an empty list for an itinerary that does not exist. 
     * Now it is a 404, and it matches the answer for someone else's itinerary
     */
    @Test
    void getByItinerary_missingItinerary_isNotFoundAndNotAnEmptyList() {
        when(itineraryService.getItineraryById(ITINERARY_ID))
                .thenThrow(new ItineraryNotFoundException(ITINERARY_ID));

        assertThatThrownBy(() -> controller.getByItinerary(ITINERARY_ID, john))
                .isInstanceOf(ItineraryNotFoundException.class);

        verifyNoInteractions(recommendationService);
    }

    /** A null caller is a 401 here too, and nothing is read */
    @Test
    void getByItinerary_notLoggedIn_isRejectedWith401() {
        existingItineraryOwnedBy(1L);

        assertThatThrownBy(() -> controller.getByItinerary(ITINERARY_ID, null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));

        verifyNoInteractions(recommendationService);
    }

    
}
