package csd.tripsense.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import csd.tripsense.audit.AuditService;
import csd.tripsense.auth.CurrentUser;
import csd.tripsense.itinerary.Itinerary;
import csd.tripsense.itinerary.ItineraryAccess;
import csd.tripsense.itinerary.ItineraryNotFoundException;
import csd.tripsense.itinerary.ItineraryService;
import csd.tripsense.user.User;
import csd.tripsense.user.UserRepository;
import csd.tripsense.activity.Activity;
import csd.tripsense.activity.ActivityCategory;
import csd.tripsense.impact.Impact;
import csd.tripsense.news.News;

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
    private AuditService auditService;
    private RecommendationController controller;

    /**
     * John owns the itineraries in these tests, 
     * Bob is a different user, 
     * Admin has the ADMIN role but still must not see other people's trips
     */
    private final CurrentUser john = new CurrentUser(1L, "john@example.com", "john", null, "USER", true);
    private final CurrentUser bob = new CurrentUser(4L, "bob@example.com", "bob", null, "USER", true);
    private final CurrentUser admin = new CurrentUser(3L, "admin@example.com", "admin", null, "ADMIN", true);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        recommendationService = mock(RecommendationService.class);
        itineraryService = mock(ItineraryService.class);
        // ItineraryAccess only needs the repository for prepareNew, which the controller never calls
        ItineraryAccess access = new ItineraryAccess(mock(UserRepository.class));
        auditService = mock(AuditService.class);
        controller = new RecommendationController(recommendationService, itineraryService, access, auditService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    /**
     * Builds an itinerary owned by the user with this id and makes the service return it,
     * the same way the real service would when the controller looks it up by id.
     */
    private Itinerary ownedBy(long userId, long itineraryId) {
        User owner = mock(User.class);
        when(owner.getId()).thenReturn(userId);
        Itinerary itinerary = new Itinerary();
        itinerary.setId(itineraryId);
        itinerary.setName("Trip " + itineraryId);
        itinerary.setUser(owner);
        when(itineraryService.getItineraryById(itineraryId)).thenReturn(itinerary);
        return itinerary;
    }

    private static void loginAs(CurrentUser user) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));
    }

    private Recommendation skiRecommendation() {
        Itinerary itinerary = ownedBy(1L, 1L);
        //itinerary.setId(1L);

        Activity skiDay = new Activity();
        skiDay.setId(10L);
        skiDay.setName("Ski at Niseko");

        News earthquake = new News();
        earthquake.setId(5L);
        earthquake.setTitle("Earthquake near Hokkaido");

        Impact impact = new Impact();
        impact.setId(100L);
        impact.setItinerary(itinerary);
        impact.setActivity(skiDay);
        impact.setNews(earthquake);
        impact.setReason("Lifts may stay shut");

        ActivityOption museum = new ActivityOption(501L, "Japan", "Sapporo", ActivityCategory.MUSEUM_GALLERY,
                "Sapporo Art Museum", "Chuo-ku, Sapporo", 43.06, 141.35);

        Recommendation recommendation = new Recommendation();
        recommendation.setId(1L);
        recommendation.setImpact(impact);
        recommendation.setSeverity(Severity.HIGH);
        recommendation.setAction(Action.REPLACE);
        recommendation.setAdvice("Spend the day at the art museum instead.");
        recommendation.setSuggestedOption(museum);
        return recommendation;
    }

    // ---------- POST /api/recommendation/generate ----------

    @Test
    @DisplayName("Should return the recommendations flattened for the frontend")
    void generate_ShouldReturnFlatJson_WhenItineraryIdGiven() throws Exception {
        Recommendation replace = skiRecommendation();
        when(recommendationService.generate(1L)).thenReturn(List.of(replace));
        
        List<RecommendationResponse> result = controller.generate(new GenerateRequest(1L), john);

        assertNotNull(result);
        assertEquals(Severity.HIGH, result.get(0).severity());
        assertEquals(Action.REPLACE, result.get(0).action());
        assertEquals("Spend the day at the art museum instead.", result.get(0).advice());
        assertEquals(100L, result.get(0).impactId());
        assertEquals("Lifts may stay shut", result.get(0).reason());
        assertEquals(1L, result.get(0).itineraryId());
        assertEquals("Ski at Niseko", result.get(0).activityName());
        assertEquals("Earthquake near Hokkaido", result.get(0).newsTitle());
        assertEquals(501L, result.get(0).optionId());
        assertEquals(ActivityCategory.MUSEUM_GALLERY, result.get(0).optionCategory());
        assertEquals("Sapporo Art Museum", result.get(0).optionName());
        assertEquals("Sapporo", result.get(0).optionArea());
        verify(recommendationService).generate(1L);

    }

    @Test
    @DisplayName("Should send nulls for the place when nothing was suggested")
    void generate_ShouldSendNullOption_WhenNothingSuggested() throws Exception {
        Recommendation monitor = skiRecommendation();
        monitor.setSuggestedOption(null);
        monitor.setAction(Action.MONITOR);
        when(recommendationService.generate(1L)).thenReturn(List.of(monitor));

        List<RecommendationResponse> result = controller.generate(new GenerateRequest(1L), john);

        assertNotNull(result);
        assertEquals(Action.MONITOR, result.get(0).action());
        assertEquals(null, result.get(0).optionId());
        assertEquals(null, result.get(0).optionName());
        verify(recommendationService).generate(1L);

    }

    /**
     * happy path: the owner reaches the service. 
     * The list is empty because this test is about access, not about what a recommendation contains
     */
    @Test
    @DisplayName("Should return owner's recommendations")
    void generate_owner_getsTheirRecommendations() {
        ownedBy(1L, ITINERARY_ID);
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
    @DisplayName("Should return exception when user is not owner")
    void generate_otherUser_getsNotFound_andNothingIsGenerated() {
        ownedBy(1L, ITINERARY_ID);

        assertThatThrownBy(() -> controller.generate(new GenerateRequest(ITINERARY_ID), bob))
                .isInstanceOf(ItineraryNotFoundException.class);

        // no model call and no rows written for someone else's trip
        verifyNoInteractions(recommendationService);
    }

    /** Being an admin gives no access to other people's trips, only the owner counts */
    @Test
    @DisplayName("Should return exception when user is ADMIN role")
    void generate_admin_cannotGenerateForAnotherUsersItinerary() {
        ownedBy(1L, ITINERARY_ID);

        assertThatThrownBy(() -> controller.generate(new GenerateRequest(ITINERARY_ID), admin))
                .isInstanceOf(ItineraryNotFoundException.class);

        verifyNoInteractions(recommendationService);
    }

    /**
     * A missing itinerary and someone else's itinerary must look the same to the caller,
     * otherwise a user could probe which ids exist
     */
    @Test
    @DisplayName("Should return exception when itinerary is missing")
    void generate_missingItinerary_getsTheSameNotFound() {
        when(itineraryService.getItineraryById(ITINERARY_ID))
                .thenThrow(new ItineraryNotFoundException(ITINERARY_ID));

        assertThatThrownBy(() -> controller.generate(new GenerateRequest(ITINERARY_ID), john))
                .isInstanceOf(ItineraryNotFoundException.class);

        verifyNoInteractions(recommendationService);
    }

    /** An itinerary with no user attached belongs to nobody, so nobody can use it */
    @Test
    @DisplayName("Should return exception when itinerary has no user attached")
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
    @DisplayName("Should return 401 when the user is not logged in")
    void generate_notLoggedIn_isRejectedWith401() {
        ownedBy(1L, ITINERARY_ID);

        assertThatThrownBy(() -> controller.generate(new GenerateRequest(ITINERARY_ID), null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));

        verifyNoInteractions(recommendationService);
    }

    // ---------- GET /api/itinerary/{id}/recommendation ----------

    /** happy path: the owner can read the stored recommendations */
    @Test
    @DisplayName("Should return recommendation when user is owner")
    void getByItinerary_owner_getsStoredRecommendations() {
        ownedBy(1L, ITINERARY_ID);
        when(recommendationService.getByItinerary(ITINERARY_ID, false, john)).thenReturn(List.of());

        List<RecommendationResponse> result = controller.getByItinerary(ITINERARY_ID, false, john);

        assertThat(result).isEmpty();
        verify(recommendationService).getByItinerary(ITINERARY_ID, false, john);
    }

    /** 
     * The read endpoint used to answer for anyone, 
     * so this is the test that would have caught the original hole: 
     * another user must get 404 and no data may be read
     */
    @Test
    @DisplayName("Should return exception when user is not owner")
    void getByItinerary_otherUser_getsNotFound_andNothingIsRead() {
        ownedBy(1L, ITINERARY_ID);

        assertThatThrownBy(() -> controller.getByItinerary(ITINERARY_ID, false, bob))
                .isInstanceOf(ItineraryNotFoundException.class);

        verifyNoInteractions(recommendationService);
    }

    /** Same rule as for generate, the admin role does not unlock other people's data */
    @Test
    @DisplayName("Should return exception when user is ADMIN role")
    void getByItinerary_admin_cannotReadAnotherUsersRecommendations() {
        ownedBy(1L, ITINERARY_ID);

        assertThatThrownBy(() -> controller.getByItinerary(ITINERARY_ID, false, admin))
                .isInstanceOf(ItineraryNotFoundException.class);

        verifyNoInteractions(recommendationService);
    }

    /** 
     * Before the fix this returned an empty list for an itinerary that does not exist. 
     * Now it is a 404, and it matches the answer for someone else's itinerary
     */
    @Test
    @DisplayName("Should return exception when itinerary is missing")
    void getByItinerary_missingItinerary_isNotFoundAndNotAnEmptyList() {
        when(itineraryService.getItineraryById(ITINERARY_ID))
                .thenThrow(new ItineraryNotFoundException(ITINERARY_ID));

        assertThatThrownBy(() -> controller.getByItinerary(ITINERARY_ID, false, john))
                .isInstanceOf(ItineraryNotFoundException.class);

        verifyNoInteractions(recommendationService);
    }

    /** A null caller is a 401 here too, and nothing is read */
    @Test
    @DisplayName("Should return 401 when the user is not logged in")
    void getByItinerary_notLoggedIn_isRejectedWith401() {
        ownedBy(1L, ITINERARY_ID);

        assertThatThrownBy(() -> controller.getByItinerary(ITINERARY_ID, false, null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));

        verifyNoInteractions(recommendationService);
    }

    // ===== AUDIT TRAIL FOR OVERRIDES =====

    // advice on john's trip, as the service would hand it back once john decided on it
    private Recommendation adviceOnJohnsTrip(long id, RecommendationStatus status) {
        Impact impact = new Impact();
        impact.setId(30L);
        impact.setItinerary(ownedBy(1L, ITINERARY_ID));

        Recommendation recommendation = new Recommendation();
        recommendation.setId(id);
        recommendation.setImpact(impact);
        recommendation.setSeverity(Severity.HIGH);
        recommendation.setAction(Action.REPLACE);
        recommendation.setAdvice("Try the museum instead");
        recommendation.setStatus(status);
        return recommendation;
    }

    @Test
    @DisplayName("Should record who dismissed the AI advice, and what it said")
    void decide_dismissal_isAudited() {
        Recommendation dismissed = adviceOnJohnsTrip(5L, RecommendationStatus.DISMISSED);
        when(recommendationService.decide(5L, RecommendationStatus.DISMISSED, john)).thenReturn(dismissed);

        controller.decide(5L, new StatusRequest(RecommendationStatus.DISMISSED), john);

        verify(auditService).record(eq(AuditService.RECOMMENDATION_DISMISSED), eq(1L), eq("john@example.com"),
                eq(1L), contains("Try the museum instead"));
    }

    @Test
    @DisplayName("Should record accepting and reopening advice as their own actions")
    void decide_acceptAndReopen_areAudited() {
        Recommendation accepted = adviceOnJohnsTrip(5L, RecommendationStatus.ACCEPTED);
        Recommendation reopened = adviceOnJohnsTrip(5L, RecommendationStatus.PENDING);
        when(recommendationService.decide(5L, RecommendationStatus.ACCEPTED, john)).thenReturn(accepted);
        when(recommendationService.decide(5L, RecommendationStatus.PENDING, john)).thenReturn(reopened);

        controller.decide(5L, new StatusRequest(RecommendationStatus.ACCEPTED), john);
        controller.decide(5L, new StatusRequest(RecommendationStatus.PENDING), john);

        verify(auditService).record(eq(AuditService.RECOMMENDATION_ACCEPTED), eq(1L), eq("john@example.com"),
                eq(1L), contains("recommendation 5"));
        verify(auditService).record(eq(AuditService.RECOMMENDATION_REOPENED), eq(1L), eq("john@example.com"),
                eq(1L), contains("recommendation 5"));
    }

    @Test
    @DisplayName("Should record which advice was turned down and what replaced it")
    void regenerate_isAudited() {
        Recommendation fresh = adviceOnJohnsTrip(6L, RecommendationStatus.PENDING);
        when(recommendationService.regenerate(5L, john)).thenReturn(fresh);

        controller.regenerate(5L, john);

        verify(auditService).record(eq(AuditService.RECOMMENDATION_REGENERATED), eq(1L), eq("john@example.com"),
                eq(1L), contains("turned down recommendation 5, offered instead recommendation 6"));
    }

    @Test
    @DisplayName("Should record nothing when the decision is refused")
    void decide_refused_isNotAudited() {
        when(recommendationService.decide(5L, RecommendationStatus.DISMISSED, bob))
                .thenThrow(new ItineraryNotFoundException(ITINERARY_ID));

        assertThatThrownBy(() -> controller.decide(5L, new StatusRequest(RecommendationStatus.DISMISSED), bob))
                .isInstanceOf(ItineraryNotFoundException.class);

        verifyNoInteractions(auditService);
    }
}
