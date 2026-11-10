package csd.tripsense.alert;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import csd.tripsense.activity.Activity;
import csd.tripsense.activity.ActivityRepository;
import csd.tripsense.auth.CurrentUser;
import csd.tripsense.chat.ChatService;
import csd.tripsense.country.Country;
import csd.tripsense.country.CountryRepository;
import csd.tripsense.impact.Impact;
import csd.tripsense.impact.ImpactRepository;
import csd.tripsense.impact.ImpactService;
import csd.tripsense.itinerary.Itinerary;
import csd.tripsense.itinerary.ItineraryRepository;
import csd.tripsense.news.News;
import csd.tripsense.news.NewsRepository;
import csd.tripsense.user.User;
import csd.tripsense.user.UserRepository;

// integration tests for alerts, from an impact assessment through to the http endpoints and email claims
// the whole app runs against a temporary sqlite file with foreign keys on, with its real security filters.
// only the AI model is mocked, so each test decides whether the news affects the trip
@SpringBootTest(properties = {
        "gdelt.ingest.enabled=false",
        "impact.eval.enabled=false",
        "impact.eval.new-itinerary.enabled=false",
        "alerts.email.initial-group-wait-seconds=0",
        "spring.sql.init.mode=never",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.hikari.maximum-pool-size=1",
        "alerts.reconcile.initial-delay-ms=86400000",
        "alerts.email.enabled=false"
})
@AutoConfigureMockMvc
@DisplayName("Alert Integration Tests")
class AlertIntegrationTest {

    private static final Path DATABASE = temporaryDatabase();

    private static final String READ_TRUE = """
            {
                "read": true
            }
            """;

    private static final String READ_FALSE = """
            {
                "read": false
            }
            """;

    @Autowired private csd.tripsense.impact.EvaluationProgress progress;
    @Autowired private csd.tripsense.impact.EvaluationTargetRepository targets;
    @Autowired private AlertMetadataMaintenance metadata;
    @Autowired private csd.tripsense.impact.EvaluationBatchRepository batches;
    @Autowired private AlertEmailGroupRepository emailGroups;
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private ItineraryRepository itineraryRepository;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private NewsRepository newsRepository;

    @Autowired
    private ImpactRepository impactRepository;

    @Autowired
    private ImpactService impactService;

    @Autowired
    private AlertRepository alertRepository;

    @Autowired
    private AlertService alertService;

    @Autowired
    private AlertEmailStore emailStore;

    @Autowired
    private AlertEvaluationListener recovery;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private SessionRegistry sessionRegistry;

    @MockitoBean
    private ChatService ai;

    @Autowired private csd.tripsense.recommendation.RecommendationRepository recommendations;
    @Autowired private ApplicationEventPublisher events;
    @Autowired private org.springframework.security.web.FilterChainProxy security;

    private User traveller;
    private Itinerary skiTrip;
    private News earthquake;

    private static Path temporaryDatabase() {
        try {
            return Files.createTempDirectory("tripsense-alert-").resolve("test.db");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE + "?foreign_keys=on");
    }

    @BeforeEach
    void setUp() {
        traveller = savedUser("traveller");

        Country japan = new Country();
        japan.setName("Japan");
        japan = countryRepository.saveAndFlush(japan);

        skiTrip = savedTrip(traveller, japan, "Ski trip");

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        earthquake = new News();
        earthquake.setTitle("Earthquake");
        earthquake.setDescription("Lifts closed");
        earthquake.setSource("Test source");
        earthquake.setUrl("https://example.com/" + suffix);
        earthquake = newsRepository.saveAndFlush(earthquake);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ===== HELPERS =====

    // the database is shared by every test in this class, so each account gets a unique email
    private User savedUser(String name) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User account = new User(name + suffix + "@example.com", name + suffix, "long-enough-hash", "USER");
        return userRepository.saveAndFlush(account);
    }

    private Itinerary savedTrip(User owner, Country country, String name) {
        Itinerary trip = new Itinerary();
        trip.setUser(owner);
        trip.setCountry(country);
        trip.setName(name);
        return itineraryRepository.saveAndFlush(trip);
    }

    // makes the mocked AI model answer with this verdict for the next assessment
    private void givenAiVerdict(boolean affected) {
        String reason = affected ? "Resort closed" : "Different location";
        when(ai.ask(anyString(), any(Class.class))).thenAnswer(invocation -> {
            Class<?> verdictType = invocation.getArgument(1);
            var constructor = verdictType.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            // an impacted answer has to name a kind or the service overrules it
            Class<?> kind = constructor.getParameterTypes()[1];
            return constructor.newInstance(reason,
                    Enum.valueOf((Class<Enum>) kind, affected ? "DISASTER" : "NONE"), affected);
        });
    }

    private Impact assessTripAsImpacted(Itinerary trip) {
        givenAiVerdict(true);
        return impactService.evaluateItineraryForNews(trip, earthquake);
    }

    // one alert per activity, all for the same news article
    private void createActivityAlerts(int count) {
        for (int i = 0; i < count; i++) {
            Activity activity = new Activity();
            activity.setItinerary(skiTrip);
            activity.setCountry(skiTrip.getCountry());
            activity.setName("Activity " + i);
            activity = activityRepository.saveAndFlush(activity);

            givenAiVerdict(true);
            impactService.evaluateActivityForNews(activity, earthquake);
        }
    }

    private Impact savedEligibleImpactWithoutAlert() {
        Impact impact = new Impact();
        impact.setUser(traveller);
        impact.setItinerary(skiTrip);
        impact.setNews(earthquake);
        impact.setImpacted(true);
        impact.setAlertEligible(true);
        impact.setReason("Closure confirmed");
        return impactRepository.saveAndFlush(impact);
    }

    private Long newestAlertId(User owner) {
        return alertService.list(owner.getId(), false, null, 1).content().getFirst().id();
    }

    private long unreadCount(User owner) {
        return alertService.list(owner.getId(), false, null, 20).unreadCount();
    }

    // registers the session the same way a real login does, so the session expiry rules apply to it
    private MockHttpSession login(User account) {
        CurrentUser principal = CurrentUser.from(account);
        principal.eraseCredentials();

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));

        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        sessionRegistry.registerNewSession(session.getId(), principal);
        return session;
    }

    // ===== ALERT CREATION TESTS =====

    @Test
    @DisplayName("Should create one alert when the trip is impacted, even if assessed twice")
    void evaluateItinerary_ShouldCreateOneAlert_WhenImpactedTwice() {
        // Arrange
        givenAiVerdict(true);

        // Act
        Impact impact = impactService.evaluateItineraryForNews(skiTrip, earthquake);
        impactService.evaluateItineraryForNews(skiTrip, earthquake);

        // Assert
        var page = alertService.list(traveller.getId(), false, null, 20);
        assertTrue(alertRepository.existsByImpactId(impact.getId()));
        assertEquals(1, page.content().size());
        assertEquals("Resort closed", page.content().getFirst().reason());
        assertEquals(1, page.unreadCount());
        verify(ai, times(1)).ask(anyString(), any(Class.class));
    }

    @Test
    @DisplayName("Should not create an alert when the trip is not impacted")
    void evaluateItinerary_ShouldNotCreateAlert_WhenNotImpacted() {
        // Arrange
        givenAiVerdict(false);

        // Act
        Impact impact = impactService.evaluateItineraryForNews(skiTrip, earthquake);

        // Assert
        assertFalse(alertRepository.existsByImpactId(impact.getId()));
    }

    @Test
    @DisplayName("Should link the alert to the activity when an activity is impacted")
    void evaluateActivity_ShouldLinkAlertToActivity_WhenActivityImpacted() {
        // Arrange
        Activity skiing = new Activity();
        skiing.setItinerary(skiTrip);
        skiing.setCountry(skiTrip.getCountry());
        skiing.setName("Ski at Niseko");
        skiing = activityRepository.saveAndFlush(skiing);
        givenAiVerdict(true);

        // Act
        impactService.evaluateActivityForNews(skiing, earthquake);

        // Assert
        AlertResponse alert = alertService.list(traveller.getId(), false, null, 20).content().getFirst();
        assertEquals(skiing.getId(), alert.activityId());
        assertEquals("Ski at Niseko", alert.activityName());
    }

    @Test
    @DisplayName("Should not store an alert when the assessment transaction rolls back")
    void evaluateItinerary_ShouldNotStoreAlert_WhenTransactionRollsBack() {
        // Arrange
        givenAiVerdict(true);

        // Act
        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            impactService.evaluateItineraryForNews(skiTrip, earthquake);
            transaction.setRollbackOnly();
        });

        // Assert
        assertTrue(alertService.list(traveller.getId(), false, null, 20).content().isEmpty());
    }

    @Test
    @DisplayName("Should recreate a missing alert without asking the AI again when the event was missed")
    void reconcile_ShouldCreateAlertWithoutAiCall_WhenAlertMissing() {
        // Arrange
        Impact impact = savedEligibleImpactWithoutAlert();

        // Act
        recovery.reconcile();
        recovery.reconcile();

        // Assert
        assertTrue(alertRepository.existsByImpactId(impact.getId()));
        assertEquals(1, alertService.list(traveller.getId(), false, null, 20).content().size());
        verifyNoInteractions(ai);
    }

    @Test
    @DisplayName("Should not alert for impacts assessed before alerts existed")
    void reconcile_ShouldSkipImpact_WhenNotAlertEligible() {
        // Arrange
        Impact historical = new Impact();
        historical.setUser(traveller);
        historical.setItinerary(skiTrip);
        historical.setNews(earthquake);
        historical.setImpacted(true);
        historical.setReason("Old assessment");
        impactRepository.saveAndFlush(historical);

        // Act
        recovery.reconcile();

        // Assert
        assertTrue(alertService.list(traveller.getId(), false, null, 20).content().isEmpty());
    }

    @Test
    @DisplayName("Should remove the alert when its impact is deleted")
    void deleteImpact_ShouldRemoveAlert_WhenImpactDeleted() {
        // Arrange
        Impact impact = assessTripAsImpacted(skiTrip);

        // Act
        impactService.deleteImpact(impact.getId());

        // Assert
        assertTrue(alertService.list(traveller.getId(), false, null, 20).content().isEmpty());
    }

    // ===== LIST ALERTS TESTS =====

    @Test
    @DisplayName("Should hide the alert and its unread count when the impact is rejected")
    void list_ShouldHideAlert_WhenImpactRejected() {
        // Arrange
        Impact impact = assessTripAsImpacted(skiTrip);

        // Act
        impactService.setRejected(impact.getId(), true);

        // Assert
        var page = alertService.list(traveller.getId(), false, null, 20);
        assertTrue(page.content().isEmpty());
        assertEquals(0, page.unreadCount());
    }

    @Test
    @DisplayName("Should load the next unread alerts from the cursor even after earlier ones were read")
    void list_ShouldNotSkipUnreadAlerts_WhenEarlierPageWasMarkedRead() {
        // Arrange
        createActivityAlerts(4);
        var firstPage = alertService.list(traveller.getId(), true, null, 2);
        for (AlertResponse alert : firstPage.content()) {
            alertService.markRead(alert.id(), traveller.getId());
        }

        // Act
        var nextPage = alertService.list(traveller.getId(), true, firstPage.nextBeforeId(), 2);

        // Assert
        Long lastShown = firstPage.content().getLast().id();
        assertEquals(lastShown, firstPage.nextBeforeId());
        assertEquals(2, nextPage.content().size());
        for (AlertResponse alert : nextPage.content()) {
            assertTrue(alert.id() < lastShown);
        }
        assertFalse(nextPage.hasMore());
        assertNull(nextPage.nextBeforeId());
    }

    @Test
    @DisplayName("Should return 401 when listing alerts without logging in")
    void getAlerts_ShouldReturn401_WhenNotLoggedIn() throws Exception {
        mockMvc.perform(get("/api/alerts"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Should return the user's own alerts when logged in")
    void getAlerts_ShouldReturnOwnAlerts_WhenLoggedIn() throws Exception {
        // Arrange
        assessTripAsImpacted(skiTrip);
        Long alertId = newestAlertId(traveller);
        MockHttpSession session = login(traveller);

        // Act and Assert
        mockMvc.perform(get("/api/alerts").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(alertId))
                .andExpect(jsonPath("$.unreadCount").value(1));
    }

    @Test
    @DisplayName("Should not show another user's alerts")
    void getAlerts_ShouldReturnEmpty_WhenAlertsBelongToAnotherUser() throws Exception {
        // Arrange
        assessTripAsImpacted(skiTrip);
        MockHttpSession strangerSession = login(savedUser("stranger"));

        // Act and Assert
        mockMvc.perform(get("/api/alerts").session(strangerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    @DisplayName("Should return older alerts when given beforeId")
    void getAlerts_ShouldReturnOlderAlerts_WhenBeforeIdGiven() throws Exception {
        // Arrange
        createActivityAlerts(3);
        Long newest = newestAlertId(traveller);
        MockHttpSession session = login(traveller);

        // Act and Assert
        mockMvc.perform(get("/api/alerts").session(session)
                        .param("beforeId", newest.toString())
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.nextBeforeId").doesNotExist());
    }

    // ===== MARK ONE AS READ TESTS =====

    @Test
    @DisplayName("Should mark the alert as read when the owner sends read true")
    void patchAlert_ShouldMarkRead_WhenOwnerSendsReadTrue() throws Exception {
        // Arrange
        assessTripAsImpacted(skiTrip);
        Long alertId = newestAlertId(traveller);
        MockHttpSession session = login(traveller);

        // Act and Assert
        mockMvc.perform(patch("/api/alerts/" + alertId).session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(READ_TRUE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(alertId))
                .andExpect(jsonPath("$.readAt").isNotEmpty());

        assertEquals(0, unreadCount(traveller));
    }

    @Test
    @DisplayName("Should return 403 when marking an alert as read without a CSRF token")
    void patchAlert_ShouldReturn403_WhenCsrfTokenMissing() throws Exception {
        // Arrange
        assessTripAsImpacted(skiTrip);
        Long alertId = newestAlertId(traveller);
        MockHttpSession session = login(traveller);

        // Act and Assert
        mockMvc.perform(patch("/api/alerts/" + alertId).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(READ_TRUE))
                .andExpect(status().isForbidden());

        assertEquals(1, unreadCount(traveller));
    }

    @Test
    @DisplayName("Should return 404 when marking another user's alert as read")
    void patchAlert_ShouldReturn404_WhenAlertBelongsToAnotherUser() throws Exception {
        // Arrange
        assessTripAsImpacted(skiTrip);
        Long alertId = newestAlertId(traveller);
        MockHttpSession strangerSession = login(savedUser("stranger"));

        // Act and Assert
        mockMvc.perform(patch("/api/alerts/" + alertId).session(strangerSession).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(READ_TRUE))
                .andExpect(status().isNotFound());

        assertEquals(1, unreadCount(traveller));
    }

    @Test
    @DisplayName("Should return 400 when trying to mark an alert as unread")
    void patchAlert_ShouldReturn400_WhenReadIsFalse() throws Exception {
        // Arrange
        assessTripAsImpacted(skiTrip);
        Long alertId = newestAlertId(traveller);
        MockHttpSession session = login(traveller);

        // Act and Assert
        mockMvc.perform(patch("/api/alerts/" + alertId).session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(READ_FALSE))
                .andExpect(status().isBadRequest());
    }

    // ===== MARK ALL AS READ TESTS =====

    @Test
    @DisplayName("Should mark every unread alert as read, including ones not loaded yet")
    void patchAlerts_ShouldMarkEveryAlertRead_WhenOwnerSendsReadTrue() throws Exception {
        // Arrange
        createActivityAlerts(3);
        MockHttpSession session = login(traveller);

        // Act and Assert
        mockMvc.perform(patch("/api/alerts").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(READ_TRUE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(0));

        assertEquals(0, unreadCount(traveller));
    }

    @Test
    @DisplayName("Should return 403 when marking all alerts as read without a CSRF token")
    void patchAlerts_ShouldReturn403_WhenCsrfTokenMissing() throws Exception {
        // Arrange
        createActivityAlerts(1);
        MockHttpSession session = login(traveller);

        // Act and Assert
        mockMvc.perform(patch("/api/alerts").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(READ_TRUE))
                .andExpect(status().isForbidden());

        assertEquals(1, unreadCount(traveller));
    }

    @Test
    @DisplayName("Should return 400 when trying to mark all alerts as unread")
    void patchAlerts_ShouldReturn400_WhenReadIsFalse() throws Exception {
        // Arrange
        createActivityAlerts(1);
        MockHttpSession session = login(traveller);

        // Act and Assert
        mockMvc.perform(patch("/api/alerts").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(READ_FALSE))
                .andExpect(status().isBadRequest());

        assertEquals(1, unreadCount(traveller));
    }

    @Test
    @DisplayName("Should leave other users' alerts unread when marking all as read")
    void markAllRead_ShouldNotTouchOtherUsers_WhenTheyHaveUnreadAlerts() {
        // Arrange
        User stranger = savedUser("stranger");
        Itinerary strangerTrip = savedTrip(stranger, skiTrip.getCountry(), "Other trip");
        assessTripAsImpacted(strangerTrip);
        createActivityAlerts(1);

        // Act
        alertService.markAllRead(traveller.getId(), null);

        // Assert
        assertEquals(0, unreadCount(traveller));
        assertEquals(1, unreadCount(stranger));
    }

    @Test
    @DisplayName("Should skip alerts on rejected impacts when marking all as read")
    void markAllRead_ShouldSkipAlert_WhenImpactRejected() {
        // Arrange
        Impact impact = assessTripAsImpacted(skiTrip);
        Long alertId = newestAlertId(traveller);
        impactService.setRejected(impact.getId(), true);

        // Act
        alertService.markAllRead(traveller.getId(), null);

        // Assert
        assertNull(alertRepository.findById(alertId).orElseThrow().getReadAt());
    }

    @Test
    @DisplayName("Should leave alerts newer than upToId unread when marking all as read")
    void patchAlerts_ShouldLeaveNewerAlertsUnread_WhenUpToIdGiven() throws Exception {
        // Arrange
        createActivityAlerts(2);
        Long lastSeenId = newestAlertId(traveller);
        createActivityAlerts(1);
        Long arrivedMeanwhileId = newestAlertId(traveller);
        MockHttpSession session = login(traveller);

        // Act
        mockMvc.perform(patch("/api/alerts").session(session).with(csrf())
                        .param("upToId", lastSeenId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(READ_TRUE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(1));

        // Assert
        assertEquals(1, unreadCount(traveller));
        assertNotNull(alertRepository.findById(lastSeenId).orElseThrow().getReadAt());
        assertNull(alertRepository.findById(arrivedMeanwhileId).orElseThrow().getReadAt());
    }

    // ===== EMAIL DELIVERY TESTS =====

    @Test
    @DisplayName("Should let only one worker claim a pending email")
    void claim_ShouldReturnEmpty_WhenAlreadyClaimed() {
        // Arrange
        assessTripAsImpacted(skiTrip);
        Long alertId = newestAlertId(traveller);
        Instant now = Instant.now().plusSeconds(1);
        emailStore.claim(alertId, now).orElseThrow();

        // Act
        var secondClaim = emailStore.claim(alertId, now);

        // Assert
        assertTrue(secondClaim.isEmpty());
    }

    @Test
    @DisplayName("Should wait a minute before retrying a failed email")
    void claim_ShouldWaitBeforeRetry_WhenAttemptFailed() {
        // Arrange
        assessTripAsImpacted(skiTrip);
        Long alertId = newestAlertId(traveller);
        Instant now = Instant.now().plusSeconds(1);
        emailStore.failed(emailStore.claim(alertId, now).orElseThrow(), now);

        // Act
        var tooEarly = emailStore.claim(alertId, now.plusSeconds(59));
        var afterBackoff = emailStore.claim(alertId, now.plusSeconds(61));

        // Assert
        assertTrue(tooEarly.isEmpty());
        assertTrue(afterBackoff.isPresent());
    }

    @Test
    @DisplayName("Should stop retrying but keep the in-app alert after three failed attempts")
    void claim_ShouldMarkFailed_WhenThreeAttemptsFail() {
        // Arrange
        assessTripAsImpacted(skiTrip);
        Long alertId = newestAlertId(traveller);
        Instant now = Instant.now().plusSeconds(1);

        // Act
        emailStore.failed(emailStore.claim(alertId, now).orElseThrow(), now);
        emailStore.failed(emailStore.claim(alertId, now.plusSeconds(61)).orElseThrow(), now.plusSeconds(61));
        emailStore.failed(emailStore.claim(alertId, now.plusSeconds(182)).orElseThrow(), now.plusSeconds(182));

        // Assert
        assertEquals(EmailStatus.FAILED, alertRepository.findById(alertId).orElseThrow().getEmailStatus());
        assertTrue(emailStore.claim(alertId, now.plusSeconds(1000)).isEmpty());
        assertEquals(1, alertService.list(traveller.getId(), false, null, 20).content().size());
    }

    @Test
    @DisplayName("Should not send an email again once it was accepted")
    void claim_ShouldReturnEmpty_WhenEmailAlreadySent() {
        // Arrange
        assessTripAsImpacted(skiTrip);
        Long alertId = newestAlertId(traveller);
        Instant now = Instant.now().plusSeconds(1);
        emailStore.sent(emailStore.claim(alertId, now).orElseThrow(), now);

        // Act
        var laterClaim = emailStore.claim(alertId, now.plusSeconds(1000));

        // Assert
        Alert saved = alertRepository.findById(alertId).orElseThrow();
        assertTrue(laterClaim.isEmpty());
        assertEquals(EmailStatus.SENT, saved.getEmailStatus());
        assertNotNull(saved.getEmailSentAt());
    }

    @Test
    @DisplayName("Should not email an alert whose impact was rejected")
    void claim_ShouldReturnEmpty_WhenImpactRejected() {
        // Arrange
        Impact impact = assessTripAsImpacted(skiTrip);
        Long alertId = newestAlertId(traveller);
        impactService.setRejected(impact.getId(), true);

        // Act
        var claim = emailStore.claim(alertId, Instant.now().plusSeconds(1));

        // Assert
        assertTrue(claim.isEmpty());
    }

    @Test
    @DisplayName("Should let another worker take over once a claim's lease expires")
    void claim_ShouldRecoverLease_WhenWorkerStopsResponding() {
        // Arrange
        assessTripAsImpacted(skiTrip);
        Long alertId = newestAlertId(traveller);
        Instant now = Instant.now().plusSeconds(1);
        var first = emailStore.claim(alertId, now).orElseThrow();

        // Act
        var beforeExpiry = emailStore.claim(alertId, now.plusSeconds(119));
        var recovered = emailStore.claim(alertId, now.plusSeconds(121)).orElseThrow();

        // Assert
        assertTrue(beforeExpiry.isEmpty());
        assertEquals(2, recovered.attempt());
        assertEquals(first.key(), recovered.key());
    }

    @Test
    @DisplayName("Should ignore an obsolete worker once its lease was taken over")
    void sent_ShouldNotOverwriteNewerAttempt_WhenWorkerIsObsolete() {
        // Arrange
        assessTripAsImpacted(skiTrip);
        Long alertId = newestAlertId(traveller);
        Instant now = Instant.now().plusSeconds(1);
        var obsolete = emailStore.claim(alertId, now).orElseThrow();
        emailStore.claim(alertId, now.plusSeconds(121)).orElseThrow();

        // Act
        emailStore.sent(obsolete, now.plusSeconds(122));

        // Assert
        assertEquals(EmailStatus.SENDING, alertRepository.findById(alertId).orElseThrow().getEmailStatus());
    }


    @Test void positiveAssessmentCreatesOneDurableAlertAndRepeatedEvaluationDoesNotDuplicateIt() {
        Impact impact = evaluate();
        impactService.evaluateItineraryForNews(skiTrip, earthquake);
        assertThat(alertRepository.existsByImpactId(impact.getId())).isTrue();
        var page = alertService.list(traveller.getId(), false, null, 20);
        assertThat(page.content()).hasSize(1);
        assertThat(page.content().getFirst().reason()).isEqualTo("Resort closed");
        assertThat(page.unreadCount()).isEqualTo(1);
        verify(ai, times(1)).ask(anyString(), any(Class.class));
    }

    @Test void unaffectedAssessmentDoesNotCreateAnAlert() {
        verdict(false); Impact impact = impactService.evaluateItineraryForNews(skiTrip, earthquake);
        assertThat(alertRepository.existsByImpactId(impact.getId())).isFalse();
    }

    @Test void rejectedImpactIsHiddenFromHistoryAndUnreadCount() {
        Impact impact = evaluate(); impactService.setRejected(impact.getId(), true);
        assertThat(alertService.list(traveller.getId(), false, null, 20).content()).isEmpty();
        assertThat(alertService.list(traveller.getId(), false, null, 20).unreadCount()).isZero();
    }

    @Test void rollbackDoesNotSendOrStoreAnAlert() {
        verdict(true);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            impactService.evaluateItineraryForNews(skiTrip, earthquake);
            assertThat(alertService.list(traveller.getId(), false, null, 20).content()).isEmpty();
            status.setRollbackOnly();
        });
        assertThat(alertService.list(traveller.getId(), false, null, 20).content()).isEmpty();
    }

    @Test void missedEventIsRecoveredWithoutAnotherAiCall() {
        Impact impact = new Impact(); impact.setUser(traveller); impact.setItinerary(skiTrip); impact.setNews(earthquake);
        impact.setImpacted(true); impact.setAlertEligible(true); impact.setReason("Closure confirmed"); impact = impactRepository.saveAndFlush(impact);
        assertThat(alertRepository.existsByImpactId(impact.getId())).isFalse();
        recovery.reconcile(); recovery.reconcile();
        assertThat(alertService.list(traveller.getId(), false, null, 20).content()).hasSize(1);
        verifyNoInteractions(ai);
    }

    @Test void deletingAnImpactRemovesItsAlertWithForeignKeysEnabled() {
        Impact impact = evaluate(); impactService.deleteImpact(impact.getId());
        assertThat(alertService.list(traveller.getId(), false, null, 20).content()).isEmpty();
    }

    @Test void httpHistoryRequiresLoginAndReadRequiresOwnershipAndCsrf() throws Exception {
        evaluate(); Long id = alertService.list(traveller.getId(), false, null, 20).content().getFirst().id();
        mockMvc.perform(get("/api/alerts")).andExpect(status().isUnauthorized());
        var ownSession = login(traveller);
        mockMvc.perform(get("/api/alerts").session(ownSession))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value(id));
        User admin = userRepository.saveAndFlush(new User(UUID.randomUUID() + "@example.com", "admin" + id, "long-enough-hash", "ADMIN"));
        var otherSession = login(admin);
        mockMvc.perform(get("/api/alerts").session(otherSession)).andExpect(jsonPath("$.content").isEmpty());
        mockMvc.perform(patch("/api/alerts/" + id).contentType(MediaType.APPLICATION_JSON).content(READ_TRUE).session(otherSession)
                        .with(csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/alerts/" + id).contentType(MediaType.APPLICATION_JSON).content(READ_TRUE).session(ownSession)).andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/alerts/" + id).contentType(MediaType.APPLICATION_JSON).content(READ_TRUE).session(ownSession)
                        .with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.readAt").isNotEmpty());
        var readAt = alertService.markRead(id, traveller.getId()).readAt();
        assertThat(alertService.markRead(id, traveller.getId()).readAt()).isEqualTo(readAt);
        assertThat(alertService.list(traveller.getId(), true, null, 20).unreadCount()).isZero();
    }

    @Test void pendingEmailIsClaimedOnceAndRetriesAreBounded() {
        evaluate(); Long id = alertService.list(traveller.getId(), false, null, 20).content().getFirst().id();
        var now = java.time.Instant.now().plusSeconds(1);
        var first = emailStore.claim(id, now).orElseThrow();
        assertThat(emailStore.claim(id, now)).isEmpty();
        emailStore.failed(first, now);
        assertThat(emailStore.claim(id, now.plusSeconds(59))).isEmpty();
        var second = emailStore.claim(id, now.plusSeconds(61)).orElseThrow();
        emailStore.failed(second, now.plusSeconds(61));
        var third = emailStore.claim(id, now.plusSeconds(182)).orElseThrow();
        emailStore.failed(third, now.plusSeconds(182));
        assertThat(alertRepository.findById(id).orElseThrow().getEmailStatus()).isEqualTo(EmailStatus.FAILED);
        assertThat(emailStore.claim(id, now.plusSeconds(1000))).isEmpty();
        assertThat(alertService.list(traveller.getId(), false, null, 20).content()).hasSize(1);
    }

    // Explicitly simulate rows persisted before per-target completion tracking was introduced.
    private String legacyBatch(Long tripId, Long newsId) {
        return batches.saveAndFlush(new csd.tripsense.impact.EvaluationBatch(tripId, newsId, Instant.now())).getId();
    }

    @Test void itineraryAndActivitiesAreClaimedTogetherOnlyAfterAssessmentCompletes() {
        Activity activity = new Activity(); activity.setItinerary(skiTrip); activity.setCountry(skiTrip.getCountry());
        activity.setName("Mount Batur trek"); activity = activityRepository.saveAndFlush(activity);
        String batch = legacyBatch(skiTrip.getId(), earthquake.getId());
        evaluate();
        Long tripAlert = alertService.list(traveller.getId(), false, null, 20).content().getFirst().id();
        assertThat(emailStore.claim(tripAlert, java.time.Instant.now().plusSeconds(1))).isEmpty();
        impactService.evaluateActivityForNews(activity, earthquake);
        progress.finish(batch, false);
        Long activityAlert = alertService.list(traveller.getId(), false, null, 20).content().getFirst().id();
        var now = java.time.Instant.now().plusSeconds(31);
        var delivery = emailStore.claim(activityAlert, now).orElseThrow();
        assertThat(delivery.relatedAlerts()).hasSize(2);
        assertThat(delivery.relatedAlerts()).extracting(AlertResponse::activityName).containsExactly(null, "Mount Batur trek");
        assertThat(emailStore.claim(tripAlert, now)).isEmpty();
        assertThat(emailStore.claim(activityAlert, now)).isEmpty();
        emailStore.sent(delivery, now);
        assertThat(alertService.list(traveller.getId(), false, null, 20).content()).hasSize(2)
                .allSatisfy(a -> assertThat(a.emailStatus()).isEqualTo(EmailStatus.SENT));
        assertThat(emailStore.claim(tripAlert, now.plusSeconds(1000))).isEmpty();
    }

    @Test void groupedRetryPreservesMessageIdentityAndCompletesAllMembers() {
        Activity activity = new Activity(); activity.setItinerary(skiTrip); activity.setCountry(skiTrip.getCountry());
        activity.setName("Trek"); activity = activityRepository.saveAndFlush(activity);
        evaluate(); impactService.evaluateActivityForNews(activity, earthquake);
        Long id = alertService.list(traveller.getId(), false, null, 20).content().getFirst().id();
        var now = java.time.Instant.now().plusSeconds(1);
        var first = emailStore.claim(id, now).orElseThrow();
        emailStore.failed(first, now);
        assertThat(emailStore.claim(id, now.plusSeconds(59))).isEmpty();
        var retry = emailStore.claim(id, now.plusSeconds(61)).orElseThrow();
        assertThat(retry.key()).isEqualTo(first.key());
        assertThat(retry.claims()).hasSize(2).allSatisfy(c -> assertThat(c.attempt()).isEqualTo(2));
        emailStore.sent(first, now.plusSeconds(62));
        assertThat(alertService.list(traveller.getId(), false, null, 20).content())
                .allSatisfy(a -> assertThat(a.emailStatus()).isEqualTo(EmailStatus.SENDING));
        emailStore.sent(retry, now.plusSeconds(63));
        assertThat(alertService.list(traveller.getId(), false, null, 20).content())
                .allSatisfy(a -> assertThat(a.emailStatus()).isEqualTo(EmailStatus.SENT));
    }

    @Test void retryIdentitySurvivesRejectionOfTheOriginalLeader() {
        Activity activity = new Activity(); activity.setItinerary(skiTrip); activity.setCountry(skiTrip.getCountry());
        activity.setName("Trek"); activity = activityRepository.saveAndFlush(activity);
        Impact overall = evaluate(); impactService.evaluateActivityForNews(activity, earthquake);
        var firstId = alertService.list(traveller.getId(), false, null, 20).content().getFirst().id();
        var now = java.time.Instant.now().plusSeconds(1);
        var first = emailStore.claim(firstId, now).orElseThrow();
        emailStore.failed(first, now);
        impactService.setRejected(overall.getId(), true);

        var retry = emailStore.claim(firstId, now.plusSeconds(61)).orElseThrow();

        assertThat(retry.key()).isEqualTo(first.key());
        assertThat(retry.relatedAlerts()).hasSize(1);
        assertThat(retry.alert().activityId()).isEqualTo(activity.getId());
        assertThat(emailGroups.findById(AlertEmailGroup.key(skiTrip.getId(), earthquake.getId())))
                .hasValueSatisfying(g -> assertThat(g.getDeliveryKey()).isEqualTo(first.key()));
    }

    @Test void unassessedActivityWithoutScheduledWorkDoesNotDelayEmail() {
        Activity missing = new Activity(); missing.setItinerary(skiTrip); missing.setCountry(skiTrip.getCountry());
        missing.setName("No evaluation scheduled"); activityRepository.saveAndFlush(missing);
        evaluate();
        var alert = alertService.list(traveller.getId(), false, null, 20).content().getFirst();
        var delivery = emailStore.claim(alert.id(), alert.createdAt().plusSeconds(1)).orElseThrow();
        assertThat(delivery.partialAssessment()).isFalse();
    }

    @Test void completedPartialEvaluationSendsAvailableResultsWithoutWaitingForMissingVerdicts() {
        String batch = legacyBatch(skiTrip.getId(), earthquake.getId());
        evaluate(); progress.finish(batch, true);
        var alert = alertService.list(traveller.getId(), false, null, 20).content().getFirst();
        var delivery = emailStore.claim(alert.id(), alert.createdAt().plusSeconds(1)).orElseThrow();
        assertThat(delivery.partialAssessment()).isTrue();
    }

    @Test void incompleteGroupIsDeferredAndSendsAvailableImpactsAtTheDeadline() {
        Activity activity = new Activity(); activity.setItinerary(skiTrip); activity.setCountry(skiTrip.getCountry());
        activity.setName("Not yet assessed"); activity = activityRepository.saveAndFlush(activity);
        evaluate();
        legacyBatch(skiTrip.getId(), earthquake.getId());
        AlertResponse alert = alertService.list(traveller.getId(), false, null, 20).content().getFirst();
        var now = alert.createdAt().plusSeconds(1);
        assertThat(emailStore.claim(alert.id(), now)).isEmpty();
        Alert waiting = alertRepository.findById(alert.id()).orElseThrow();
        assertThat(waiting.getEmailNextAttemptAt()).isEqualTo(now.plusSeconds(30));
        assertThat(waiting.getEmailAttempts()).isZero();
        assertThat(emailStore.claim(alert.id(), now.plusSeconds(29))).isEmpty();
        // Checking near the deadline must not push it back another whole recheck interval.
        assertThat(emailStore.claim(alert.id(), alert.createdAt().plusSeconds(119))).isEmpty();
        assertThat(alertRepository.findById(alert.id()).orElseThrow().getEmailNextAttemptAt())
                .isEqualTo(alert.createdAt().plusSeconds(120));
        var partial = emailStore.claim(alert.id(), alert.createdAt().plusSeconds(120)).orElseThrow();
        assertThat(partial.partialAssessment()).isTrue();
        assertThat(partial.relatedAlerts()).hasSize(1);
        emailStore.sent(partial, alert.createdAt().plusSeconds(121));
        verdict(true); impactService.evaluateActivityForNews(activity, earthquake);
        var late = alertService.list(traveller.getId(), false, null, 20).content().getFirst();
        var followUp = emailStore.claim(late.id(), alert.createdAt().plusSeconds(240)).orElseThrow();
        assertThat(followUp.relatedAlerts()).extracting(AlertResponse::id).containsExactly(late.id());
        assertThat(followUp.key()).isNotEqualTo(partial.key());
        assertThat(alertService.list(traveller.getId(), false, null, 20).content()).hasSize(2);
    }

    @Test void backfillPreservesAFollowUpRetryIdentityAfterItsOriginalLeaderIsRejected() {
        Activity a=new Activity(); a.setName("First late activity"); a.setItinerary(skiTrip); a.setCountry(skiTrip.getCountry());
        a=activityRepository.saveAndFlush(a);
        Activity b=new Activity(); b.setName("Second late activity"); b.setItinerary(skiTrip); b.setCountry(skiTrip.getCountry());
        b=activityRepository.saveAndFlush(b);
        evaluate();
        String batch=progress.begin(skiTrip.getId(),earthquake.getId(),false,java.util.List.of(a.getId(),b.getId()));
        var first=alertService.list(traveller.getId(),false,null,20).content().getFirst();
        var partial=emailStore.claim(first.id(),first.createdAt().plusSeconds(120)).orElseThrow();
        assertThat(partial.partialAssessment()).isTrue();
        emailStore.sent(partial,first.createdAt().plusSeconds(121));
        var impactA=impactService.evaluateActivityForNews(a,earthquake);
        var impactB=impactService.evaluateActivityForNews(b,earthquake);
        progress.resolved(skiTrip.getId(),earthquake.getId(),a.getId());
        progress.resolved(skiTrip.getId(),earthquake.getId(),b.getId());
        progress.finish(batch,false);
        var late=alertRepository.findEmailGroup(skiTrip.getId(),earthquake.getId()).getLast();
        var followUp=emailStore.claim(late.getId(),first.createdAt().plusSeconds(240)).orElseThrow();
        emailStore.failed(followUp,first.createdAt().plusSeconds(240));
        String groupKey=AlertEmailGroup.key(skiTrip.getId(),earthquake.getId());
        assertThat(emailGroups.findById(groupKey).orElseThrow().isDelivered()).isFalse();
        metadata.clean();
        assertThat(emailGroups.findById(groupKey).orElseThrow().isDelivered()).isFalse();
        impactService.setRejected(impactA.getId(),true);
        var retry=emailStore.claim(late.getId(),first.createdAt().plusSeconds(301)).orElseThrow();
        assertThat(retry.relatedAlerts()).hasSize(1);
        assertThat(retry.key()).isEqualTo(followUp.key());
    }

    @Test void lateAlertInAFailedGroupBecomesFailedEvenWhenOtherAssessmentsAreMissing() {
        evaluate();
        var firstAlert = alertService.list(traveller.getId(), false, null, 20).content().getFirst();
        var now = firstAlert.createdAt().plusSeconds(1);
        var first = emailStore.claim(firstAlert.id(), now).orElseThrow(); emailStore.failed(first, now);
        var second = emailStore.claim(firstAlert.id(), now.plusSeconds(61)).orElseThrow();
        emailStore.failed(second, now.plusSeconds(61));
        var third = emailStore.claim(firstAlert.id(), now.plusSeconds(182)).orElseThrow();
        emailStore.failed(third, now.plusSeconds(182));
        Activity lateActivity = new Activity(); lateActivity.setItinerary(skiTrip); lateActivity.setCountry(skiTrip.getCountry());
        lateActivity.setName("Late activity"); lateActivity = activityRepository.saveAndFlush(lateActivity);
        Activity missing = new Activity(); missing.setItinerary(skiTrip); missing.setCountry(skiTrip.getCountry());
        missing.setName("Still unassessed"); activityRepository.saveAndFlush(missing);
        impactService.evaluateActivityForNews(lateActivity, earthquake);
        var late = alertService.list(traveller.getId(), false, null, 20).content().getFirst();
        assertThat(emailStore.claim(late.id(), now.plusSeconds(183))).isEmpty();
        assertThat(alertRepository.findById(late.id()).orElseThrow().getEmailStatus()).isEqualTo(EmailStatus.FAILED);
        assertThat(alertRepository.findEmailReady(AlertEmailStore.RETRYABLE, now.plusSeconds(1000),
                org.springframework.data.domain.PageRequest.of(0, 1000)))
                .doesNotContain(late.id(), firstAlert.id());
        assertThat(alertService.list(traveller.getId(), false, null, 20).content()).hasSize(2);
    }

    @Test void fiftyIncompleteGroupsDoNotStarveANewerEmailRetry() {
        var now = java.time.Instant.now();
        // Other tests keep their own fixtures in this isolated SQLite database. Park their jobs
        // so the first fifty candidates below are exactly this scenario's blocked groups.
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            for (Alert existing : alertRepository.findAll()) {
                alertRepository.finishEmail(existing.getId(), existing.getEmailAttempts(), AlertEmailStore.RETRYABLE,
                        existing.getEmailStatus(), existing.getEmailSentAt(), now.plusSeconds(3600));
            }
        });
        java.util.List<Long> blocked = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) {
            Itinerary other = new Itinerary(); other.setUser(traveller); other.setCountry(skiTrip.getCountry());
            other.setName("Waiting trip " + i); other = itineraryRepository.saveAndFlush(other);
            Activity missing = new Activity(); missing.setItinerary(other); missing.setCountry(skiTrip.getCountry());
            missing.setName("Missing assessment"); activityRepository.saveAndFlush(missing);
            legacyBatch(other.getId(), earthquake.getId());
            blocked.add(pendingAt(other, java.time.Instant.EPOCH, now).getId());
        }
        Alert healthy = pendingAt(skiTrip, java.time.Instant.EPOCH.plusMillis(1), now);
        var page = org.springframework.data.domain.PageRequest.of(0, 50);
        var first = alertRepository.findEmailReady(AlertEmailStore.RETRYABLE, now.plusSeconds(1), page);
        assertThat(first).containsExactlyElementsOf(blocked);
        for (Long id : first) assertThat(emailStore.claim(id, now.plusSeconds(1))).isEmpty();
        var next = alertRepository.findEmailReady(AlertEmailStore.RETRYABLE, now.plusSeconds(1), page);
        assertThat(next).contains(healthy.getId()).doesNotContainAnyElementsOf(blocked);
        var delivery = emailStore.claim(healthy.getId(), now.plusSeconds(1)).orElseThrow();
        // A failed first send must still receive a retry while all fifty old groups remain incomplete.
        emailStore.failed(delivery, now.plusSeconds(1));
        for (Long id : blocked) assertThat(emailStore.claim(id, now.plusSeconds(61))).isEmpty();
        assertThat(alertRepository.findEmailReady(AlertEmailStore.RETRYABLE, now.plusSeconds(61), page)).contains(healthy.getId());
        assertThat(emailStore.claim(healthy.getId(), now.plusSeconds(61))).isPresent();
    }

    @Test void lateActivityForAlreadyNotifiedNewsDoesNotSendAnotherEmail() {
        evaluate();
        Long id = alertService.list(traveller.getId(), false, null, 20).content().getFirst().id();
        var now = java.time.Instant.now().plusSeconds(1);
        emailStore.sent(emailStore.claim(id, now).orElseThrow(), now);
        Activity activity = new Activity(); activity.setItinerary(skiTrip); activity.setCountry(skiTrip.getCountry());
        activity.setName("Added later"); activity = activityRepository.saveAndFlush(activity);
        impactService.evaluateActivityForNews(activity, earthquake);
        Long added = alertService.list(traveller.getId(), false, null, 20).content().getFirst().id();
        assertThat(emailStore.claim(added, now.plusSeconds(1000))).isEmpty();
        var suppressed = alertRepository.findById(added).orElseThrow();
        assertThat(suppressed.getEmailStatus()).isEqualTo(EmailStatus.SUPPRESSED);
        assertThat(suppressed.getEmailSentAt()).isNull();
        assertThat(suppressed.getEmailAttempts()).isZero();
        assertThat(alertService.list(traveller.getId(), false, null, 20).content().getFirst().emailStatus())
                .isEqualTo(EmailStatus.SUPPRESSED);
        assertThat(alertRepository.findEmailReady(AlertEmailStore.RETRYABLE, now.plusSeconds(2000),
                org.springframework.data.domain.PageRequest.of(0, 1000))).doesNotContain(added);
        assertThat(alertService.list(traveller.getId(), false, null, 20).content()).hasSize(2);
    }

    @Test void activityOnlyDisruptionStillGetsAnEmailAndOtherNewsIsSeparate() {
        Activity activity = new Activity(); activity.setItinerary(skiTrip); activity.setCountry(skiTrip.getCountry());
        activity.setName("Trek"); activity = activityRepository.saveAndFlush(activity);
        verdict(false); impactService.evaluateItineraryForNews(skiTrip, earthquake);
        verdict(true); impactService.evaluateActivityForNews(activity, earthquake);
        Long id = alertService.list(traveller.getId(), false, null, 20).content().getFirst().id();
        var now = java.time.Instant.now().plusSeconds(1);
        var delivery = emailStore.claim(id, now).orElseThrow();
        assertThat(delivery.relatedAlerts()).hasSize(1);
        assertThat(delivery.alert().activityId()).isEqualTo(activity.getId());
        emailStore.sent(delivery, now);
        News other = new News(); other.setTitle("Another event"); other.setUrl("https://example.com/" + UUID.randomUUID());
        other = newsRepository.saveAndFlush(other);
        impactService.evaluateItineraryForNews(skiTrip, other);
        impactService.evaluateActivityForNews(activity, other);
        Long newAlert = alertService.list(traveller.getId(), false, null, 20).content().getFirst().id();
        assertThat(emailStore.claim(newAlert, java.time.Instant.now().plusSeconds(1))).isPresent();
    }

    @Test void acceptedEmailIsNotSentAgainAndRejectedImpactsAreNotEmailed() {
        Impact impact = evaluate(); Long id = alertService.list(traveller.getId(), false, null, 20).content().getFirst().id();
        var now = java.time.Instant.now().plusSeconds(1);
        impactService.setRejected(impact.getId(), true);
        assertThat(emailStore.claim(id, now)).isEmpty();
        impactService.setRejected(impact.getId(), false);
        var claim = emailStore.claim(id, now).orElseThrow(); emailStore.sent(claim, now);
        assertThat(emailStore.claim(id, now.plusSeconds(1000))).isEmpty();
        var saved = alertRepository.findById(id).orElseThrow();
        assertThat(saved.getEmailStatus()).isEqualTo(EmailStatus.SENT);
        assertThat(saved.getEmailSentAt()).isNotNull();
    }

    @Test void expiredSendingMemberIsDeferredUntilItsOtherGroupMemberIsReady() {
        Activity activity = new Activity(); activity.setItinerary(skiTrip); activity.setCountry(skiTrip.getCountry());
        activity.setName("Trek"); activity = activityRepository.saveAndFlush(activity);
        evaluate(); impactService.evaluateActivityForNews(activity, earthquake);
        var group = alertRepository.findEmailGroup(skiTrip.getId(), earthquake.getId());
        Long firstId = group.getFirst().getId();
        Long secondId = group.getLast().getId();
        var now = Instant.now().truncatedTo(ChronoUnit.MILLIS).plusSeconds(1);
        var original = emailStore.claim(firstId, now).orElseThrow();
        // Simulate persisted members with different recovery deadlines after a stopped worker.
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            alertRepository.finishEmail(firstId, 1, AlertEmailStore.RETRYABLE,
                    EmailStatus.SENDING, null, now.plusSeconds(1));
            alertRepository.finishEmail(secondId, 1, AlertEmailStore.RETRYABLE,
                    EmailStatus.SENDING, null, now.plusSeconds(60));
        });

        assertThat(emailStore.claim(firstId, now.plusSeconds(2))).isEmpty();

        var deferred = alertRepository.findById(firstId).orElseThrow();
        assertThat(deferred.getEmailNextAttemptAt()).isEqualTo(now.plusSeconds(60));
        assertThat(deferred.getEmailStatus()).isEqualTo(EmailStatus.SENDING);
        assertThat(deferred.getEmailAttempts()).isEqualTo(1);
        assertThat(alertRepository.findEmailReady(AlertEmailStore.RETRYABLE, now.plusSeconds(17),
                org.springframework.data.domain.PageRequest.of(0, 10000))).doesNotContain(firstId, secondId);
        var recovered = emailStore.claim(firstId, now.plusSeconds(60)).orElseThrow();
        assertThat(recovered.claims()).hasSize(2).allSatisfy(c -> assertThat(c.attempt()).isEqualTo(2));
        assertThat(recovered.key()).isEqualTo(original.key());
        emailStore.sent(original, now.plusSeconds(61));
        assertThat(alertRepository.findById(firstId).orElseThrow().getEmailStatus()).isEqualTo(EmailStatus.SENDING);
    }

    @Test void expiredSendingMemberIsDeferredWhileItsAssessmentBatchStillRuns() {
        evaluate();
        Long id = newestAlertId(traveller);
        var now = Instant.now().truncatedTo(ChronoUnit.MILLIS).plusSeconds(1);
        emailStore.claim(id, now).orElseThrow();
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                alertRepository.finishEmail(id, 1, AlertEmailStore.RETRYABLE,
                        EmailStatus.SENDING, null, now.plusSeconds(1)));
        String batch = progress.begin(skiTrip.getId(), earthquake.getId(), true, java.util.List.of());

        assertThat(emailStore.claim(id, now.plusSeconds(2))).isEmpty();

        assertThat(alertRepository.findById(id).orElseThrow().getEmailNextAttemptAt()).isEqualTo(now.plusSeconds(32));
        assertThat(alertRepository.findEmailReady(AlertEmailStore.RETRYABLE, now.plusSeconds(17),
                org.springframework.data.domain.PageRequest.of(0, 10000))).doesNotContain(id);
        progress.finish(batch, false);
        assertThat(emailStore.claim(id, now.plusSeconds(32))).isPresent();
    }

    @Test void expiredEmailLeaseCanBeRecoveredAfterAWorkerRestart() {
        evaluate(); Long id = alertService.list(traveller.getId(), false, null, 20).content().getFirst().id();
        var now = java.time.Instant.now().plusSeconds(1);
        var first = emailStore.claim(id, now).orElseThrow();
        assertThat(emailStore.claim(id, now.plusSeconds(119))).isEmpty();
        var recovered = emailStore.claim(id, now.plusSeconds(121)).orElseThrow();
        assertThat(recovered.attempt()).isEqualTo(2);
        assertThat(recovered.key()).isEqualTo(first.key());
        emailStore.sent(first, now.plusSeconds(122)); // an obsolete worker cannot overwrite a newer attempt
        assertThat(alertRepository.findById(id).orElseThrow().getEmailStatus()).isEqualTo(EmailStatus.SENDING);
        emailStore.sent(recovered, now.plusSeconds(123));
    }

    @Test void activityAssessmentCreatesAnAlertWithTheAffectedActivity() {
        Activity activity = new Activity(); activity.setItinerary(skiTrip); activity.setCountry(skiTrip.getCountry());
        activity.setName("Ski at Niseko"); activity = activityRepository.saveAndFlush(activity);
        verdict(true); impactService.evaluateActivityForNews(activity, earthquake);
        var alert = alertService.list(traveller.getId(), false, null, 20).content().getFirst();
        assertThat(alert.activityId()).isEqualTo(activity.getId());
        assertThat(alert.activityName()).isEqualTo("Ski at Niseko");
    }

    @Test void recoveryDoesNotNotifyForHistoricalAssessmentsFromBeforeTheFeature() {
        Impact impact = new Impact(); impact.setUser(traveller); impact.setItinerary(skiTrip); impact.setNews(earthquake);
        impact.setImpacted(true); impact.setReason("Old assessment"); impactRepository.saveAndFlush(impact);
        recovery.reconcile();
        assertThat(alertService.list(traveller.getId(), false, null, 20).content()).isEmpty();
    }

    @Test void demoResetRemovesTheDisruptionAndTriggerRestoresItWithoutCallingAi() {
        Activity activity = new Activity(); activity.setItinerary(skiTrip); activity.setCountry(skiTrip.getCountry());
        activity.setName("Demo trek"); activity = activityRepository.saveAndFlush(activity);
        Impact overall = evaluate(); impactService.evaluateActivityForNews(activity, earthquake);
        var recommendation = new csd.tripsense.recommendation.Recommendation();
        recommendation.setImpact(overall);
        recommendation.setStatus(csd.tripsense.recommendation.RecommendationStatus.PENDING);
        recommendation.setSeverity(csd.tripsense.recommendation.Severity.MEDIUM);
        recommendation.setAction(csd.tripsense.recommendation.Action.MONITOR);
        recommendation.setAdvice("Demo advice"); recommendations.saveAndFlush(recommendation);
        long originalMaxId = alertRepository.findEmailGroup(skiTrip.getId(), earthquake.getId()).stream()
                .mapToLong(Alert::getId).max().orElseThrow();
        var originalKeys = alertRepository.findEmailGroup(skiTrip.getId(), earthquake.getId()).stream()
                .map(Alert::getEmailDeliveryKey).toList();
        var service = demo(true);
        var tx = new TransactionTemplate(transactionManager);
        var reset = tx.execute(s -> service.reset(traveller.getId()));
        assertThat(reset.state()).isEqualTo("READY");
        assertThat(alertService.list(traveller.getId(), false, null, 20).content()).isEmpty();
        assertThat(recommendations.findByImpactItineraryId(skiTrip.getId())).hasSize(1);
        assertThat(impactRepository.findByItineraryIdAndNewsId(skiTrip.getId(), earthquake.getId()))
                .hasSize(2).allSatisfy(i -> {
                    assertThat(i.getImpacted()).isFalse();
                    assertThat(i.getReason()).isEqualTo("Resort closed");
                });
        recovery.reconcile();
        assertThat(alertService.list(traveller.getId(), false, null, 20).content()).isEmpty();
        clearInvocations(ai);
        var triggered = tx.execute(s -> service.trigger(traveller.getId()));
        assertThat(triggered.alerts()).isEqualTo(2);
        var fresh = alertService.list(traveller.getId(), false, null, 20).content();
        assertThat(fresh).hasSize(2).allSatisfy(a -> {
            assertThat(a.id()).isGreaterThan(originalMaxId);
            assertThat(a.readAt()).isNull();
            assertThat(a.emailStatus()).isEqualTo(EmailStatus.PENDING);
        });
        assertThat(alertRepository.findEmailGroup(skiTrip.getId(), earthquake.getId()))
                .allSatisfy(a -> assertThat(a.getEmailDeliveryKey()).isNotIn(originalKeys));
        verifyNoInteractions(ai);
        var delivery = emailStore.claim(fresh.getFirst().id(), java.time.Instant.now().plusSeconds(1)).orElseThrow();
        assertThat(delivery.relatedAlerts()).hasSize(2);
        emailStore.sent(delivery, java.time.Instant.now());
        assertThat(alertService.list(traveller.getId(), false, null, 20).content())
                .allSatisfy(a -> assertThat(a.emailStatus()).isEqualTo(EmailStatus.SENT));
        assertThatThrownBy(() -> tx.execute(s -> service.trigger(traveller.getId())))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test void completedUnrelatedScopeDoesNotHideAnUnresolvedAlertAssessment() {
        Activity activity=new Activity(); activity.setName("Not assessed"); activity.setItinerary(skiTrip);
        activity.setCountry(skiTrip.getCountry()); activity=activityRepository.saveAndFlush(activity);
        evaluate();
        String failed=progress.begin(skiTrip.getId(),earthquake.getId(),false,java.util.List.of(activity.getId()));
        progress.finish(failed,true);
        String unrelated=progress.begin(skiTrip.getId(),earthquake.getId(),false,java.util.List.of());
        progress.finish(unrelated,false);
        var alert=alertService.list(traveller.getId(),false,null,20).content().getFirst();
        assertThat(emailStore.claim(alert.id(),alert.createdAt().plusSeconds(1)).orElseThrow().partialAssessment()).isTrue();
    }

    @Test void resolvingTheMissingAssessmentClearsThePartialEmailNotice() {
        Activity activity=new Activity(); activity.setName("Later assessed"); activity.setItinerary(skiTrip);
        activity.setCountry(skiTrip.getCountry()); activity=activityRepository.saveAndFlush(activity);
        evaluate();
        String failed=progress.begin(skiTrip.getId(),earthquake.getId(),false,java.util.List.of(activity.getId()));
        progress.finish(failed,true);
        impactService.evaluateActivityForNews(activity,earthquake);
        progress.resolved(skiTrip.getId(),earthquake.getId(),activity.getId());
        var alert=alertService.list(traveller.getId(),false,null,20).content().getFirst();
        assertThat(emailStore.claim(alert.id(),java.time.Instant.now().plusSeconds(1)).orElseThrow().partialAssessment()).isFalse();
    }

    @Autowired private JdbcTemplate sql;

    @Test void deliveredGroupBackfillDoesNotRewriteAlreadyDeliveredRows() {
        evaluate();
        var alert=alertService.list(traveller.getId(),false,null,20).content().getFirst();
        var now=Instant.now().truncatedTo(ChronoUnit.MILLIS).plusSeconds(1);
        emailStore.sent(emailStore.claim(alert.id(),now).orElseThrow(),now);
        String groupKey=AlertEmailGroup.key(skiTrip.getId(),earthquake.getId());
        // Count real SQLite writes to this group, rather than asserting the SQL's wording.
        sql.execute("create temporary table alert_backfill_writes (group_id text)");
        try {
            sql.execute("create temporary trigger count_alert_backfill after update on alert_email_group "
                    + "when new.id='"+groupKey+"' begin insert into alert_backfill_writes values(new.id); end");
            metadata.clean();
            assertThat(sql.queryForObject("select count(*) from alert_backfill_writes",Integer.class)).isZero();

            sql.update("update alert_email_group set delivered=null,sent_at=null where id=?",groupKey);
            sql.update("delete from alert_backfill_writes");
            metadata.clean();
            assertThat(sql.queryForObject("select count(*) from alert_backfill_writes",Integer.class)).isEqualTo(1);
            assertThat(emailGroups.findById(groupKey)).hasValueSatisfying(g -> assertThat(g.isDelivered()).isTrue());
            metadata.clean();
            assertThat(sql.queryForObject("select count(*) from alert_backfill_writes",Integer.class)).isEqualTo(1);
        } finally {
            sql.execute("drop trigger if exists count_alert_backfill");
            sql.execute("drop table if exists alert_backfill_writes");
        }
    }

    @Test void deletingATripRemovesItsAlertOwnedMetadata() {
        evaluate();
        String batch=progress.begin(skiTrip.getId(),earthquake.getId(),true,java.util.List.of());
        progress.resolved(skiTrip.getId(),earthquake.getId(),null); progress.finish(batch,false);
        var alert=alertService.list(traveller.getId(),false,null,20).content().getFirst();
        var delivery=emailStore.claim(alert.id(),java.time.Instant.now().plusSeconds(1)).orElseThrow();
        emailStore.sent(delivery,java.time.Instant.now());
        assertThat(emailGroups.findById(AlertEmailGroup.key(skiTrip.getId(),earthquake.getId()))).isPresent();
        itineraryRepository.deleteById(skiTrip.getId());
        assertThat(emailGroups.findById(AlertEmailGroup.key(skiTrip.getId(),earthquake.getId()))).isEmpty();
        assertThat(batches.findById(batch)).isEmpty();
        assertThat(targets.countByItineraryId(skiTrip.getId())).isZero();
    }

    @Test void oldCompletedAlertTrackingIsPrunedWithoutDeletingDeliveryIdentity() {
        evaluate();
        var alert=alertService.list(traveller.getId(),false,null,20).content().getFirst();
        var delivery=emailStore.claim(alert.id(),java.time.Instant.now().plusSeconds(1)).orElseThrow();
        emailStore.sent(delivery,java.time.Instant.now());
        String old=progress.begin(skiTrip.getId(),earthquake.getId(),false,java.util.List.of());
        var record=batches.findById(old).orElseThrow();
        org.springframework.test.util.ReflectionTestUtils.setField(record,"leaseUntil",java.time.Instant.now().minusSeconds(40L*86400));
        batches.saveAndFlush(record);
        metadata.clean();
        assertThat(batches.findById(old)).isEmpty();
        assertThat(emailGroups.findById(AlertEmailGroup.key(skiTrip.getId(),earthquake.getId()))).isPresent();
    }

    @Test void demoResetRefusesToEraseRejectedImpactDecisions() {
        Impact impact=evaluate(); impactService.setRejected(impact.getId(),true);
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(s -> demo(true).reset(traveller.getId())))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("decisions");
        assertThat(impactRepository.findById(impact.getId()).orElseThrow().getRejected()).isTrue();
        assertThat(alertRepository.findEmailGroup(skiTrip.getId(),earthquake.getId())).hasSize(1);
    }

    @Test void demoResetPreservesAcceptedRecommendationHistory() {
        Impact impact=evaluate();
        var recommendation=new csd.tripsense.recommendation.Recommendation(); recommendation.setImpact(impact);
        recommendation.setStatus(csd.tripsense.recommendation.RecommendationStatus.ACCEPTED);
        recommendation.setSeverity(csd.tripsense.recommendation.Severity.MEDIUM);
        recommendation.setAction(csd.tripsense.recommendation.Action.MONITOR); recommendation.setAdvice("Accepted advice");
        recommendation=recommendations.saveAndFlush(recommendation);
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(s -> demo(true).reset(traveller.getId())))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("disposable fixture");
        assertThat(recommendations.findById(recommendation.getId()).orElseThrow().getStatus())
                .isEqualTo(csd.tripsense.recommendation.RecommendationStatus.ACCEPTED);
        assertThat(impactRepository.findById(impact.getId()).orElseThrow().getImpacted()).isTrue();
    }

    @Test void demoRequiresExplicitDisposableFixtureOptIn() {
        evaluate();
        var service=new DemoDisruptionService(impactRepository,alertRepository,recommendations,itineraryRepository,
                userRepository,alertService,emailGroups,batches,true,false,traveller.getId(),skiTrip.getId(),earthquake.getId());
        assertThatThrownBy(() -> service.reset(traveller.getId()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("404");
    }

    @Test void demoRequiresExplicitEnablementAndTheConfiguredOwner() {
        evaluate();
        assertThatThrownBy(() -> demo(false).reset(traveller.getId()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> demo(true).reset(traveller.getId() + 1))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> demo(true).trigger(null))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        for (String role : java.util.List.of("USER", "ADMIN")) {
            User other = userRepository.saveAndFlush(new User(UUID.randomUUID() + "@example.com",
                    "other" + UUID.randomUUID().toString().substring(0, 8), "long-enough-hash", role));
            assertThatThrownBy(() -> demo(true).reset(other.getId()))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                    .hasMessageContaining("404");
            assertThatThrownBy(() -> demo(true).trigger(other.getId()))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                    .hasMessageContaining("404");
        }
        assertThat(alertService.list(traveller.getId(), false, null, 20).content()).hasSize(1);
    }

    @Test void demoCannotResetWhileItsEmailIsBeingSent() {
        evaluate();
        Long id = alertService.list(traveller.getId(), false, null, 20).content().getFirst().id();
        emailStore.claim(id, java.time.Instant.now().plusSeconds(1)).orElseThrow();
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(s -> demo(true).reset(traveller.getId())))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("delivery");
        assertThat(alertService.list(traveller.getId(), false, null, 20).content()).hasSize(1);
    }

    @Test void demoEndpointsAreAbsentByDefault() throws Exception {
        var session = login(traveller);
        mockMvc.perform(post("/api/alerts/demo/reset").session(session).with(csrf())).andExpect(status().isNotFound());
    }

    @Test void enabledDemoRoutesRequireSessionAndCsrfAndPassTheAuthenticatedOwner() throws Exception {
        DemoDisruptionService service = mock(DemoDisruptionService.class);
        var routes = MockMvcBuilders.standaloneSetup(new DemoDisruptionController(service))
                .setCustomArgumentResolvers(new org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver())
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity(security)).build();
        routes.perform(post("/api/alerts/demo/trigger").with(csrf())).andExpect(status().isUnauthorized());
        var session = login(traveller);
        routes.perform(post("/api/alerts/demo/reset").session(session)).andExpect(status().isForbidden());
        verifyNoInteractions(service);
        when(service.reset(traveller.getId())).thenReturn(new DemoDisruptionService.Result(skiTrip.getId(), earthquake.getId(), 1, 0, "READY"));
        when(service.trigger(traveller.getId())).thenReturn(new DemoDisruptionService.Result(skiTrip.getId(), earthquake.getId(), 1, 1, "TRIGGERED"));
        routes.perform(post("/api/alerts/demo/reset").session(session).with(csrf())).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("READY"));
        routes.perform(post("/api/alerts/demo/trigger").session(session).with(csrf())).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("TRIGGERED"));
        verify(service).reset(traveller.getId()); verify(service).trigger(traveller.getId());
    }

    private Alert pendingAt(Itinerary itinerary, java.time.Instant readyAt, java.time.Instant createdAt) {
        Impact impact = new Impact(); impact.setUser(traveller); impact.setItinerary(itinerary); impact.setNews(earthquake);
        impact.setImpacted(true); impact.setAlertEligible(true); impact.setReason("Saved impact");
        impact = impactRepository.saveAndFlush(impact);
        Alert alert = new Alert(impact);
        org.springframework.test.util.ReflectionTestUtils.setField(alert, "createdAt", createdAt);
        org.springframework.test.util.ReflectionTestUtils.setField(alert, "emailNextAttemptAt", readyAt);
        return alertRepository.saveAndFlush(alert);
    }

    private DemoDisruptionService demo(boolean enabled) {
        return new DemoDisruptionService(impactRepository, alertRepository, recommendations, itineraryRepository, userRepository, alertService, emailGroups, batches,
                enabled, true, traveller.getId(), skiTrip.getId(), earthquake.getId());
    }

    private void verdict(boolean affected) { givenAiVerdict(affected); }

    private Impact evaluate() { return assessTripAsImpacted(skiTrip); }

    @Test void sentGroupRemainsTerminalAfterItsSentImpactIsDeleted() {
        Impact overall=evaluate();
        var original=alertService.list(traveller.getId(),false,null,20).content().getFirst();
        var now=java.time.Instant.now().plusSeconds(1);
        emailStore.sent(emailStore.claim(original.id(),now).orElseThrow(),now);
        Activity first=new Activity(); first.setName("First later activity"); first.setItinerary(skiTrip); first.setCountry(skiTrip.getCountry());
        first=activityRepository.saveAndFlush(first); impactService.evaluateActivityForNews(first,earthquake);
        var suppressed=alertService.list(traveller.getId(),false,null,20).content().getFirst();
        assertThat(emailStore.claim(suppressed.id(),now.plusSeconds(1000))).isEmpty();
        assertThat(alertRepository.findById(suppressed.id()).orElseThrow().getEmailStatus()).isEqualTo(EmailStatus.SUPPRESSED);
        impactService.deleteImpact(overall.getId());
        Activity second=new Activity(); second.setName("Second later activity"); second.setItinerary(skiTrip); second.setCountry(skiTrip.getCountry());
        second=activityRepository.saveAndFlush(second); impactService.evaluateActivityForNews(second,earthquake);
        var added=alertService.list(traveller.getId(),false,null,20).content().getFirst();
        assertThat(emailStore.claim(added.id(),now.plusSeconds(1001))).isEmpty();
        assertThat(emailStore.claim(added.id(),now.plusSeconds(2001))).isEmpty();
        assertThat(alertRepository.findById(added.id()).orElseThrow().getEmailStatus()).isEqualTo(EmailStatus.SUPPRESSED);
        assertThat(alertRepository.findEmailReady(AlertEmailStore.RETRYABLE,now.plusSeconds(2001),
                org.springframework.data.domain.PageRequest.of(0,1000))).doesNotContain(added.id());
    }

    @Test void deletedAssessmentReopensItsTargetAndFailedReevaluationKeepsThePartialNotice() {
        Activity a=new Activity(); a.setName("Assessment to retry"); a.setItinerary(skiTrip); a.setCountry(skiTrip.getCountry());
        a=activityRepository.saveAndFlush(a);
        Impact overall=evaluate(); Impact activityImpact=impactService.evaluateActivityForNews(a,earthquake);
        impactService.evaluate(skiTrip.getId(),earthquake.getId());
        assertThat(targets.countUnresolved(skiTrip.getId(),earthquake.getId())).isZero();
        impactService.deleteImpact(activityImpact.getId());
        org.mockito.Mockito.when(ai.ask(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(Class.class)))
                .thenThrow(new IllegalStateException("AI unavailable"));
        var retry=impactService.evaluate(skiTrip.getId(),earthquake.getId());
        assertThat(retry.activities().getFirst().reason()).startsWith("Could not evaluate:");
        assertThat(targets.countUnresolved(skiTrip.getId(),earthquake.getId())).isEqualTo(1);
        var alert=alertService.list(traveller.getId(),false,null,20).content().getFirst();
        var delivery=emailStore.claim(alert.id(),java.time.Instant.now().plusSeconds(1)).orElseThrow();
        assertThat(delivery.relatedAlerts()).hasSize(1);
        assertThat(delivery.partialAssessment()).isTrue();
    }

    @Test void movedActivityDoesNotKeepItsOldTripsEmailMarkedPartial() {
        Activity a=new Activity(); a.setName("Moved activity"); a.setItinerary(skiTrip); a.setCountry(skiTrip.getCountry());
        a=activityRepository.saveAndFlush(a); evaluate();
        String failed=progress.begin(skiTrip.getId(),earthquake.getId(),false,java.util.List.of(a.getId()));
        progress.finish(failed,true);
        Itinerary other=new Itinerary(); other.setName("Other trip"); other.setUser(traveller); other.setCountry(skiTrip.getCountry());
        other=itineraryRepository.saveAndFlush(other); a.setItinerary(other); activityRepository.saveAndFlush(a);
        assertThat(activityRepository.findByItineraryIdOrderByDateStart(skiTrip.getId())).isEmpty();
        assertThat(targets.countUnresolved(skiTrip.getId(),earthquake.getId())).isZero();
        var alert=alertService.list(traveller.getId(),false,null,20).content().getFirst();
        assertThat(emailStore.claim(alert.id(),java.time.Instant.now().plusSeconds(1)).orElseThrow().partialAssessment()).isFalse();
        metadata.clean();
        assertThat(targets.countByItineraryId(skiTrip.getId())).isZero();
    }

    @Test void sentGroupStaysDeliveredWhenAllItsOriginalAlertsAreDeleted() {
        Impact original=evaluate();
        var alert=alertService.list(traveller.getId(),false,null,20).content().getFirst();
        var now=java.time.Instant.now().plusSeconds(1);
        emailStore.sent(emailStore.claim(alert.id(),now).orElseThrow(),now);
        assertThat(emailGroups.findById(AlertEmailGroup.key(skiTrip.getId(),earthquake.getId())).orElseThrow().isDelivered()).isTrue();
        impactService.deleteImpact(original.getId());
        Activity activity=new Activity(); activity.setName("Added after deletion"); activity.setItinerary(skiTrip); activity.setCountry(skiTrip.getCountry());
        activity=activityRepository.saveAndFlush(activity); impactService.evaluateActivityForNews(activity,earthquake);
        var later=alertService.list(traveller.getId(),false,null,20).content().getFirst();
        assertThat(emailStore.claim(later.id(),now.plusSeconds(1000))).isEmpty();
        assertThat(alertRepository.findById(later.id()).orElseThrow().getEmailStatus()).isEqualTo(EmailStatus.SUPPRESSED);
    }

    @Test void concurrentAlertTargetRegistrationIsIdempotent() throws Exception {
        Activity activity=new Activity(); activity.setName("Concurrent target"); activity.setItinerary(skiTrip); activity.setCountry(skiTrip.getCountry());
        Long activityId=activityRepository.saveAndFlush(activity).getId();
        var start=new java.util.concurrent.CountDownLatch(1);
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<String> register=() -> {
                start.await();
                return progress.begin(skiTrip.getId(),earthquake.getId(),false,java.util.List.of(activityId));
            };
            var first=executor.submit(register); var second=executor.submit(register); start.countDown();
            String firstBatch=first.get(10,java.util.concurrent.TimeUnit.SECONDS);
            String secondBatch=second.get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(firstBatch).isNotEqualTo(secondBatch);
            assertThat(targets.countByItineraryId(skiTrip.getId())).isEqualTo(1);
        } finally { executor.shutdownNow(); }
    }

    @Test void deletingAnImpactBeforeConfirmationStillRecordsItsAcceptedEmail() {
        Impact original=evaluate();
        var alert=alertService.list(traveller.getId(),false,null,20).content().getFirst();
        var now=Instant.now().plusSeconds(1);
        var accepted=emailStore.claim(alert.id(),now).orElseThrow();
        impactService.deleteImpact(original.getId());
        emailStore.sent(accepted,now);
        assertThat(emailGroups.findById(AlertEmailGroup.key(skiTrip.getId(),earthquake.getId())))
                .hasValueSatisfying(g -> assertThat(g.isDelivered()).isTrue());
    }

    @Test void groupReadIncludesOlderPagesAndLeavesLaterArrivalsUnread() {
        Activity activity=new Activity(); activity.setName("Group activity"); activity.setItinerary(skiTrip);
        activity.setCountry(skiTrip.getCountry()); activity=activityRepository.saveAndFlush(activity);
        evaluate(); impactService.evaluateActivityForNews(activity,earthquake);
        var page=alertService.list(traveller.getId(),false,null,1);
        assertThat(page.hasMore()).isTrue();
        Long anchor=page.content().getFirst().id();
        assertThat(alertService.markGroupRead(anchor,traveller.getId()).unreadCount()).isZero();
        Activity later=new Activity(); later.setName("Later group member"); later.setItinerary(skiTrip);
        later.setCountry(skiTrip.getCountry()); later=activityRepository.saveAndFlush(later);
        impactService.evaluateActivityForNews(later,earthquake);
        assertThat(alertService.markGroupRead(anchor,traveller.getId()).unreadCount()).isEqualTo(1);
        assertThatThrownBy(() -> alertService.markGroupRead(anchor,traveller.getId()+100000))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test void groupReadEndpointRequiresOwnershipAndCsrf() throws Exception {
        evaluate();
        Long id=alertService.list(traveller.getId(),false,null,20).content().getFirst().id();
        String endpoint="/api/alerts/"+id+"/group";
        var ownSession=login(traveller);
        var strangerSession=login(savedUser("stranger"));
        mockMvc.perform(patch(endpoint).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(READ_TRUE))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch(endpoint).session(strangerSession).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(READ_TRUE)).andExpect(status().isNotFound());
        mockMvc.perform(patch(endpoint).session(ownSession).contentType(MediaType.APPLICATION_JSON).content(READ_TRUE))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch(endpoint).session(ownSession).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(READ_TRUE))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unreadCount").value(0));
    }

    @Test void acceptedGroupIsNotReclaimedWhileItsConfirmationIsPending() {
        evaluate();
        var alert=alertService.list(traveller.getId(),false,null,20).content().getFirst();
        var now=Instant.now().plusSeconds(1);
        emailStore.claim(alert.id(),now).orElseThrow();
        assertThat(emailStore.claim(alert.id(),now.plusSeconds(121),
                java.util.Set.of(AlertEmailGroup.key(skiTrip.getId(),earthquake.getId())))).isEmpty();
        assertThat(alertRepository.findById(alert.id()).orElseThrow().getEmailAttempts()).isEqualTo(1);
    }
}
