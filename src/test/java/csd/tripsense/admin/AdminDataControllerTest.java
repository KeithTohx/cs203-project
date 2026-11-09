package csd.tripsense.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import csd.tripsense.activity.Activity;
import csd.tripsense.audit.AuditService;
import csd.tripsense.auth.CurrentUser;
import csd.tripsense.country.Country;
import csd.tripsense.impact.Impact;
import csd.tripsense.itinerary.Itinerary;
import csd.tripsense.itinerary.ItineraryNotFoundException;
import csd.tripsense.news.News;
import csd.tripsense.recommendation.Action;
import csd.tripsense.recommendation.Recommendation;
import csd.tripsense.recommendation.RecommendationStatus;
import csd.tripsense.recommendation.Severity;
import csd.tripsense.user.User;

// standalone MockMvc has no security filters, so who may call these is checked in
// SecurityRulesIntegrationTest instead
class AdminDataControllerTest {

    private AdminDataService adminData;
    private AuditService auditService;
    private MockMvc mockMvc;

    private final CurrentUser admin = new CurrentUser(3L, "admin@example.com", "admin", null, "ADMIN", true);

    @BeforeEach
    void setUp() {
        adminData = mock(AdminDataService.class);
        auditService = mock(AuditService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminDataController(adminData, auditService))
                .setControllerAdvice(new AdminDataExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(admin, null, admin.getAuthorities()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static User account(Long id, String name) {
        User user = new User(name + "@example.com", name, "secret-hash", "USER");
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private static Itinerary trip(Long id, User owner) {
        Country japan = new Country();
        japan.setId(1L);
        japan.setName("Japan");

        Itinerary itinerary = new Itinerary();
        itinerary.setId(id);
        itinerary.setUser(owner);
        itinerary.setCountry(japan);
        itinerary.setName("Japan Ski Trip");
        return itinerary;
    }

    private static Activity activityOn(Long id, Itinerary itinerary) {
        Activity activity = new Activity();
        activity.setId(id);
        activity.setItinerary(itinerary);
        activity.setName("Ski at Niseko");
        return activity;
    }

    private static Impact impactFor(Long id, User owner) {
        Impact impact = new Impact();
        impact.setId(id);
        impact.setUser(owner);
        impact.setItinerary(trip(10L, owner));
        impact.setImpacted(true);
        return impact;
    }

    private static Recommendation recommendationOn(Long id, Impact impact) {
        Recommendation recommendation = new Recommendation();
        recommendation.setId(id);
        recommendation.setImpact(impact);
        recommendation.setSeverity(Severity.HIGH);
        recommendation.setAction(Action.CANCEL);
        recommendation.setStatus(RecommendationStatus.PENDING);
        return recommendation;
    }

    @Test
    @DisplayName("Should create an account, return 201 without the password, and record it")
    void createUser_Returns201_AndRecordsIt() throws Exception {
        when(adminData.createUser(any())).thenReturn(account(9L, "tester"));

        mockMvc.perform(post("/api/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "tester", "email": "tester@example.com",
                                 "password": "long-password", "role": "USER"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(9))
                .andExpect(jsonPath("$.username").value("tester"))
                .andExpect(jsonPath("$.password").doesNotExist());

        verify(auditService).record(eq(AuditService.ACCOUNT_CREATED), eq(3L), eq("admin@example.com"),
                eq(9L), contains("tester"));
    }

    @Test
    @DisplayName("Should return 400 with the field's own message for a short password, and create nothing")
    void createUser_ShortPassword_Returns400WithReadableMessage() throws Exception {
        mockMvc.perform(post("/api/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "tester", "email": "tester@example.com",
                                 "password": "short", "role": "USER"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("password must be between 8 and 72 characters"));

        verifyNoInteractions(adminData);
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("Should list another account's itineraries in the usual flat shape")
    void listItineraries_ReturnsFlatResponses() throws Exception {
        User bob = account(4L, "bob");
        when(adminData.listItineraries(4L)).thenReturn(List.of(trip(10L, bob)));

        mockMvc.perform(get("/api/admin/users/4/itineraries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(10))
                .andExpect(jsonPath("$[0].userId").value(4))
                .andExpect(jsonPath("$[0].countryName").value("Japan"))
                .andExpect(jsonPath("$[0].user").doesNotExist());
    }

    @Test
    @DisplayName("Should return 204 for a deleted itinerary and record it against its owner")
    void deleteItinerary_Returns204_AndRecordsAgainstTheOwner() throws Exception {
        when(adminData.deleteItinerary(10L)).thenReturn(trip(10L, account(4L, "bob")));

        mockMvc.perform(delete("/api/admin/itineraries/10"))
                .andExpect(status().isNoContent());

        // the owner is the target, so the change shows up on bob's admin page
        verify(auditService).record(eq(AuditService.ITINERARY_DELETED), eq(3L), eq("admin@example.com"),
                eq(4L), contains("by admin"));
    }

    @Test
    @DisplayName("Should create an impact by hand, return 201 and record it against the user")
    void createImpact_Returns201_AndRecordsIt() throws Exception {
        User bob = account(4L, "bob");
        News article = new News();
        article.setId(30L);
        article.setTitle("Earthquake near Hokkaido");

        Impact impact = new Impact();
        impact.setId(70L);
        impact.setUser(bob);
        impact.setItinerary(trip(10L, bob));
        impact.setNews(article);
        impact.setImpacted(true);
        impact.setReason("Rail is suspended");
        when(adminData.createImpact(eq(4L), any())).thenReturn(impact);

        mockMvc.perform(post("/api/admin/users/4/impacts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newsId": 30, "itineraryId": 10, "impacted": true,
                                 "reason": "Rail is suspended", "sendAlert": true}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(70))
                .andExpect(jsonPath("$.newsTitle").value("Earthquake near Hokkaido"))
                .andExpect(jsonPath("$.rejected").value(false));

        verify(auditService).record(eq(AuditService.IMPACT_CREATED), eq(3L), eq("admin@example.com"),
                eq(4L), contains("impact 70"));
    }

    @Test
    @DisplayName("Should return 400 when an impact is missing its news, and create nothing")
    void createImpact_MissingNews_Returns400() throws Exception {
        mockMvc.perform(post("/api/admin/users/4/impacts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"itineraryId": 10, "impacted": true}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("newsId is required"));

        verifyNoInteractions(adminData);
    }

    @Test
    @DisplayName("Should record each verdict an itinerary edit cleared, against the trip's owner")
    void updateItinerary_ClearedVerdicts_AreRecorded() throws Exception {
        Itinerary updated = trip(10L, account(4L, "bob"));
        ClearedVerdict cleared = new ClearedVerdict(70L, 4L, 30L, "Rail strike", true,
                "Trains are not running", true, true, 1);
        when(adminData.updateItinerary(eq(10L), any())).thenReturn(new AdminEdit<>(updated, List.of(cleared)));

        mockMvc.perform(put("/api/admin/itineraries/10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Japan Ski Trip", "countryId": 2}
                                """))
                .andExpect(status().isOk());

        verify(auditService).record(eq(AuditService.ITINERARY_UPDATED), eq(3L), eq("admin@example.com"),
                eq(4L), contains("itinerary 10"));
        verify(auditService).record(eq(AuditService.IMPACT_CLEARED), eq(3L), eq("admin@example.com"),
                eq(4L), contains("impact 70 on news 30 'Rail strike': impacted, rejected by the traveller, had an alert"));
    }

    @Test
    @DisplayName("Should 404 when the itinerary to change does not exist, and record nothing")
    void updateItinerary_Missing_Returns404() throws Exception {
        when(adminData.updateItinerary(eq(99L), any())).thenThrow(new ItineraryNotFoundException(99L));

        mockMvc.perform(put("/api/admin/itineraries/99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Trip", "countryId": 1}
                                """))
                .andExpect(status().isNotFound());

        verifyNoInteractions(auditService);
    }

    // ---------- activities ----------

    @Test
    @DisplayName("Should list every activity on an account's trips in one response")
    void listUserActivities_ReturnsAllOfThem() throws Exception {
        User bob = account(4L, "bob");
        Activity first = activityOn(20L, trip(10L, bob));
        Activity second = activityOn(21L, trip(11L, bob));
        when(adminData.listUserActivities(4L)).thenReturn(List.of(first, second));

        mockMvc.perform(get("/api/admin/users/4/activities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].itineraryId").value(10))
                .andExpect(jsonPath("$[1].itineraryId").value(11));
    }

    @Test
    @DisplayName("Should add an activity, return 201 and record it against the trip's owner")
    void createActivity_Returns201_AndRecordsIt() throws Exception {
        Activity created = activityOn(20L, trip(10L, account(4L, "bob")));
        when(adminData.createActivity(eq(10L), any())).thenReturn(created);

        mockMvc.perform(post("/api/admin/itineraries/10/activities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Ski at Niseko", "category": "SKI_SNOW"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(20))
                .andExpect(jsonPath("$.itineraryId").value(10));

        verify(auditService).record(eq(AuditService.ACTIVITY_CREATED), eq(3L), eq("admin@example.com"),
                eq(4L), contains("activity 20"));
    }

    @Test
    @DisplayName("Should replace an activity and record it as updated by an admin")
    void updateActivity_Returns200_AndRecordsIt() throws Exception {
        Activity updated = activityOn(20L, trip(10L, account(4L, "bob")));
        when(adminData.updateActivity(eq(20L), any())).thenReturn(new AdminEdit<>(updated, List.of()));

        mockMvc.perform(put("/api/admin/activities/20")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Ski at Niseko"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Ski at Niseko"));

        verify(auditService).record(eq(AuditService.ACTIVITY_UPDATED), eq(3L), eq("admin@example.com"),
                eq(4L), contains("by admin"));
    }

    @Test
    @DisplayName("Should return 400 for an activity with a blank name, and change nothing")
    void updateActivity_BlankName_Returns400() throws Exception {
        mockMvc.perform(put("/api/admin/activities/20")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": " "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("name must not be blank"));

        verifyNoInteractions(adminData);
    }

    @Test
    @DisplayName("Should return 204 for a deleted activity and record it against the trip's owner")
    void deleteActivity_Returns204_AndRecordsIt() throws Exception {
        when(adminData.deleteActivity(20L)).thenReturn(activityOn(20L, trip(10L, account(4L, "bob"))));

        mockMvc.perform(delete("/api/admin/activities/20"))
                .andExpect(status().isNoContent());

        verify(auditService).record(eq(AuditService.ACTIVITY_DELETED), eq(3L), eq("admin@example.com"),
                eq(4L), contains("activity 20"));
    }

    // ---------- recommendations ----------

    @Test
    @DisplayName("Should create a recommendation on an impact, return 201 and record it")
    void createRecommendation_Returns201_AndRecordsIt() throws Exception {
        Recommendation created = recommendationOn(80L, impactFor(70L, account(4L, "bob")));
        when(adminData.createRecommendation(eq(70L), any())).thenReturn(created);

        mockMvc.perform(post("/api/admin/impacts/70/recommendations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"severity": "HIGH", "action": "CANCEL", "advice": "Skip it"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(80))
                .andExpect(jsonPath("$.impactId").value(70))
                .andExpect(jsonPath("$.status").value("PENDING"));

        verify(auditService).record(eq(AuditService.RECOMMENDATION_CREATED), eq(3L), eq("admin@example.com"),
                eq(4L), contains("recommendation 80"));
    }

    @Test
    @DisplayName("Should return 400 for a recommendation without a severity, and create nothing")
    void createRecommendation_MissingSeverity_Returns400() throws Exception {
        mockMvc.perform(post("/api/admin/impacts/70/recommendations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action": "CANCEL"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("severity is required"));

        verifyNoInteractions(adminData);
    }

    @Test
    @DisplayName("Should replace a recommendation and record it as updated")
    void updateRecommendation_Returns200_AndRecordsIt() throws Exception {
        Recommendation updated = recommendationOn(80L, impactFor(70L, account(4L, "bob")));
        updated.setStatus(RecommendationStatus.ACCEPTED);
        when(adminData.updateRecommendation(eq(80L), any())).thenReturn(updated);

        mockMvc.perform(put("/api/admin/recommendations/80")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"severity": "HIGH", "action": "CANCEL", "status": "ACCEPTED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        verify(auditService).record(eq(AuditService.RECOMMENDATION_UPDATED), eq(3L), eq("admin@example.com"),
                eq(4L), contains("recommendation 80"));
    }

    @Test
    @DisplayName("Should 404 when the recommendation to delete does not exist, and record nothing")
    void deleteRecommendation_Missing_Returns404() throws Exception {
        when(adminData.deleteRecommendation(99L))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "No recommendation with id 99"));

        mockMvc.perform(delete("/api/admin/recommendations/99"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("Should return 204 for a deleted recommendation and record it against the owner")
    void deleteRecommendation_Returns204_AndRecordsIt() throws Exception {
        when(adminData.deleteRecommendation(80L))
                .thenReturn(recommendationOn(80L, impactFor(70L, account(4L, "bob"))));

        mockMvc.perform(delete("/api/admin/recommendations/80"))
                .andExpect(status().isNoContent());

        verify(auditService).record(eq(AuditService.RECOMMENDATION_DELETED), eq(3L), eq("admin@example.com"),
                eq(4L), contains("recommendation 80"));
    }
}
