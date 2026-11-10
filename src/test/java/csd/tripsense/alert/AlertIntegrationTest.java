package csd.tripsense.alert;

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
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
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
}
