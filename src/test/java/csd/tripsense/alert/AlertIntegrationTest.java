package csd.tripsense.alert;

import csd.tripsense.activity.*;
import csd.tripsense.auth.CurrentUser;
import csd.tripsense.chat.ChatService;
import csd.tripsense.country.*;
import csd.tripsense.impact.*;
import csd.tripsense.itinerary.*;
import csd.tripsense.news.*;
import csd.tripsense.user.*;
import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "gdelt.ingest.enabled=false", "impact.eval.enabled=false", "spring.sql.init.mode=never",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.datasource.hikari.maximum-pool-size=1",
        "alerts.reconcile.initial-delay-ms=86400000", "alerts.email.enabled=false"
})
class AlertIntegrationTest {
    private static final Path DATABASE = database();
    private static Path database() {
        try { return Files.createTempDirectory("tripsense-alert-").resolve("test.db"); }
        catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }
    @DynamicPropertySource static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE + "?foreign_keys=on");
    }
    @Autowired UserRepository users;
    @Autowired CountryRepository countries;
    @Autowired ItineraryRepository itineraries;
    @Autowired ActivityRepository activities;
    @Autowired NewsRepository news;
    @Autowired ImpactRepository impacts;
    @Autowired ImpactService impactService;
    @Autowired AlertRepository alerts;
    @Autowired AlertService alertService;
    @Autowired AlertEmailStore emailStore;
    @Autowired AlertEvaluationListener recovery;
    @Autowired ApplicationEventPublisher events;
    @Autowired PlatformTransactionManager transactions;
    @Autowired WebApplicationContext web;
    @Autowired org.springframework.security.web.FilterChainProxy security;
    @Autowired SessionRegistry sessions;
    @MockitoBean ChatService ai;
    private User user;
    private Itinerary trip;
    private News article;
    private MockMvc mvc;

    @BeforeEach void fixture() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        user = users.saveAndFlush(new User(suffix + "@example.com", "user" + suffix, "long-enough-hash", "USER"));
        Country country = new Country(); country.setName("Japan"); country = countries.saveAndFlush(country);
        trip = new Itinerary(); trip.setUser(user); trip.setCountry(country); trip.setName("Ski trip");
        trip = itineraries.saveAndFlush(trip);
        article = new News(); article.setTitle("Earthquake"); article.setDescription("Lifts closed");
        article.setSource("Test source"); article.setUrl("https://example.com/" + suffix);
        article = news.saveAndFlush(article);
        mvc = MockMvcBuilders.webAppContextSetup(web).addFilters(security).build();
    }

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    private void verdict(boolean affected) {
        when(ai.ask(anyString(), any(Class.class))).thenAnswer(inv -> {
            var constructor = ((Class<?>) inv.getArgument(1)).getDeclaredConstructor(boolean.class, String.class);
            constructor.setAccessible(true); return constructor.newInstance(affected, affected ? "Resort closed" : "Different location");
        });
    }

    private Impact evaluate() { verdict(true); return impactService.evaluateItineraryForNews(trip, article); }

    private MockHttpSession login(User account) {
        CurrentUser principal = CurrentUser.from(account); principal.eraseCredentials();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        var session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        sessions.registerNewSession(session.getId(), principal); return session;
    }

    @Test void positiveAssessmentCreatesOneDurableAlertAndRepeatedEvaluationDoesNotDuplicateIt() {
        Impact impact = evaluate();
        impactService.evaluateItineraryForNews(trip, article);
        assertThat(alerts.existsByImpactId(impact.getId())).isTrue();
        var page = alertService.list(user.getId(), false, 0, 20);
        assertThat(page.content()).hasSize(1);
        assertThat(page.content().getFirst().reason()).isEqualTo("Resort closed");
        assertThat(page.unreadCount()).isEqualTo(1);
        verify(ai, times(1)).ask(anyString(), any(Class.class));
    }

    @Test void unaffectedAssessmentDoesNotCreateAnAlert() {
        verdict(false); Impact impact = impactService.evaluateItineraryForNews(trip, article);
        assertThat(alerts.existsByImpactId(impact.getId())).isFalse();
    }

    @Test void rejectedImpactIsHiddenFromHistoryAndUnreadCount() {
        Impact impact = evaluate(); impactService.setRejected(impact.getId(), true);
        assertThat(alertService.list(user.getId(), false, 0, 20).content()).isEmpty();
        assertThat(alertService.list(user.getId(), false, 0, 20).unreadCount()).isZero();
    }

    @Test void rollbackDoesNotSendOrStoreAnAlert() {
        verdict(true);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            impactService.evaluateItineraryForNews(trip, article);
            assertThat(alertService.list(user.getId(), false, 0, 20).content()).isEmpty();
            status.setRollbackOnly();
        });
        assertThat(alertService.list(user.getId(), false, 0, 20).content()).isEmpty();
    }

    @Test void missedEventIsRecoveredWithoutAnotherAiCall() {
        Impact impact = new Impact(); impact.setUser(user); impact.setItinerary(trip); impact.setNews(article);
        impact.setImpacted(true); impact.setAlertEligible(true); impact.setReason("Closure confirmed"); impact = impacts.saveAndFlush(impact);
        assertThat(alerts.existsByImpactId(impact.getId())).isFalse();
        recovery.reconcile(); recovery.reconcile();
        assertThat(alertService.list(user.getId(), false, 0, 20).content()).hasSize(1);
        verifyNoInteractions(ai);
    }

    @Test void deletingAnImpactRemovesItsAlertWithForeignKeysEnabled() {
        Impact impact = evaluate(); impactService.deleteImpact(impact.getId());
        assertThat(alertService.list(user.getId(), false, 0, 20).content()).isEmpty();
    }

    @Test void httpHistoryRequiresLoginAndReadRequiresOwnershipAndCsrf() throws Exception {
        evaluate(); Long id = alertService.list(user.getId(), false, 0, 20).content().getFirst().id();
        mvc.perform(get("/api/alerts")).andExpect(status().isUnauthorized());
        var ownSession = login(user);
        mvc.perform(get("/api/alerts").session(ownSession))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value(id));
        User admin = users.saveAndFlush(new User(UUID.randomUUID() + "@example.com", "admin" + id, "long-enough-hash", "ADMIN"));
        var otherSession = login(admin);
        var csrf = mvc.perform(get("/").session(otherSession)).andReturn().getResponse().getCookie("XSRF-TOKEN");
        mvc.perform(get("/api/alerts").session(otherSession)).andExpect(jsonPath("$.content").isEmpty());
        mvc.perform(patch("/api/alerts/" + id + "/read").session(otherSession)
                        .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isNotFound());
        mvc.perform(patch("/api/alerts/" + id + "/read").session(ownSession)).andExpect(status().isForbidden());
        var ownCsrf = mvc.perform(get("/").session(ownSession)).andReturn().getResponse().getCookie("XSRF-TOKEN");
        mvc.perform(patch("/api/alerts/" + id + "/read").session(ownSession)
                        .cookie(ownCsrf).header("X-XSRF-TOKEN", ownCsrf.getValue()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.readAt").isNotEmpty());
        var readAt = alertService.markRead(id, user.getId()).readAt();
        assertThat(alertService.markRead(id, user.getId()).readAt()).isEqualTo(readAt);
        assertThat(alertService.list(user.getId(), true, 0, 20).unreadCount()).isZero();
    }

    @Test void pendingEmailIsClaimedOnceAndRetriesAreBounded() {
        evaluate(); Long id = alertService.list(user.getId(), false, 0, 20).content().getFirst().id();
        var now = java.time.Instant.now().plusSeconds(1);
        var first = emailStore.claim(id, now).orElseThrow();
        assertThat(emailStore.claim(id, now)).isEmpty();
        emailStore.failed(first, now);
        assertThat(emailStore.claim(id, now.plusSeconds(59))).isEmpty();
        var second = emailStore.claim(id, now.plusSeconds(61)).orElseThrow();
        emailStore.failed(second, now.plusSeconds(61));
        var third = emailStore.claim(id, now.plusSeconds(182)).orElseThrow();
        emailStore.failed(third, now.plusSeconds(182));
        assertThat(alerts.findById(id).orElseThrow().getEmailStatus()).isEqualTo(EmailStatus.FAILED);
        assertThat(emailStore.claim(id, now.plusSeconds(1000))).isEmpty();
        assertThat(alertService.list(user.getId(), false, 0, 20).content()).hasSize(1);
    }

    @Test void acceptedEmailIsNotSentAgainAndRejectedImpactsAreNotEmailed() {
        Impact impact = evaluate(); Long id = alertService.list(user.getId(), false, 0, 20).content().getFirst().id();
        var now = java.time.Instant.now().plusSeconds(1);
        impactService.setRejected(impact.getId(), true);
        assertThat(emailStore.claim(id, now)).isEmpty();
        impactService.setRejected(impact.getId(), false);
        var claim = emailStore.claim(id, now).orElseThrow(); emailStore.sent(claim, now);
        assertThat(emailStore.claim(id, now.plusSeconds(1000))).isEmpty();
        var saved = alerts.findById(id).orElseThrow();
        assertThat(saved.getEmailStatus()).isEqualTo(EmailStatus.SENT);
        assertThat(saved.getEmailSentAt()).isNotNull();
    }


    @Test void expiredEmailLeaseCanBeRecoveredAfterAWorkerRestart() {
        evaluate(); Long id = alertService.list(user.getId(), false, 0, 20).content().getFirst().id();
        var now = java.time.Instant.now().plusSeconds(1);
        var first = emailStore.claim(id, now).orElseThrow();
        assertThat(emailStore.claim(id, now.plusSeconds(119))).isEmpty();
        var recovered = emailStore.claim(id, now.plusSeconds(121)).orElseThrow();
        assertThat(recovered.attempt()).isEqualTo(2);
        assertThat(recovered.key()).isEqualTo(first.key());
        emailStore.sent(first, now.plusSeconds(122)); // an obsolete worker cannot overwrite a newer attempt
        assertThat(alerts.findById(id).orElseThrow().getEmailStatus()).isEqualTo(EmailStatus.SENDING);
        emailStore.sent(recovered, now.plusSeconds(123));
    }

    @Test void activityAssessmentCreatesAnAlertWithTheAffectedActivity() {
        Activity activity = new Activity(); activity.setItinerary(trip); activity.setCountry(trip.getCountry());
        activity.setName("Ski at Niseko"); activity = activities.saveAndFlush(activity);
        verdict(true); impactService.evaluateActivityForNews(activity, article);
        var alert = alertService.list(user.getId(), false, 0, 20).content().getFirst();
        assertThat(alert.activityId()).isEqualTo(activity.getId());
        assertThat(alert.activityName()).isEqualTo("Ski at Niseko");
    }

    @Test void recoveryDoesNotNotifyForHistoricalAssessmentsFromBeforeTheFeature() {
        Impact impact = new Impact(); impact.setUser(user); impact.setItinerary(trip); impact.setNews(article);
        impact.setImpacted(true); impact.setReason("Old assessment"); impacts.saveAndFlush(impact);
        recovery.reconcile();
        assertThat(alertService.list(user.getId(), false, 0, 20).content()).isEmpty();
    }

}
