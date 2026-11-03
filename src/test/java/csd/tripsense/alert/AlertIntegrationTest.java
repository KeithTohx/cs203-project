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
    @Autowired csd.tripsense.recommendation.RecommendationRepository recommendations;
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

    @Test void itineraryAndActivitiesAreClaimedTogetherOnlyAfterAssessmentCompletes() {
        Activity activity = new Activity(); activity.setItinerary(trip); activity.setCountry(trip.getCountry());
        activity.setName("Mount Batur trek"); activity = activities.saveAndFlush(activity);
        evaluate();
        Long tripAlert = alertService.list(user.getId(), false, 0, 20).content().getFirst().id();
        assertThat(emailStore.claim(tripAlert, java.time.Instant.now().plusSeconds(1))).isEmpty();
        impactService.evaluateActivityForNews(activity, article);
        Long activityAlert = alertService.list(user.getId(), false, 0, 20).content().getFirst().id();
        var now = java.time.Instant.now().plusSeconds(31);
        var delivery = emailStore.claim(activityAlert, now).orElseThrow();
        assertThat(delivery.relatedAlerts()).hasSize(2);
        assertThat(delivery.relatedAlerts()).extracting(AlertResponse::activityName).containsExactly(null, "Mount Batur trek");
        assertThat(emailStore.claim(tripAlert, now)).isEmpty();
        assertThat(emailStore.claim(activityAlert, now)).isEmpty();
        emailStore.sent(delivery, now);
        assertThat(alertService.list(user.getId(), false, 0, 20).content()).hasSize(2)
                .allSatisfy(a -> assertThat(a.emailStatus()).isEqualTo(EmailStatus.SENT));
        assertThat(emailStore.claim(tripAlert, now.plusSeconds(1000))).isEmpty();
    }

    @Test void groupedRetryPreservesMessageIdentityAndCompletesAllMembers() {
        Activity activity = new Activity(); activity.setItinerary(trip); activity.setCountry(trip.getCountry());
        activity.setName("Trek"); activity = activities.saveAndFlush(activity);
        evaluate(); impactService.evaluateActivityForNews(activity, article);
        Long id = alertService.list(user.getId(), false, 0, 20).content().getFirst().id();
        var now = java.time.Instant.now().plusSeconds(1);
        var first = emailStore.claim(id, now).orElseThrow();
        emailStore.failed(first, now);
        assertThat(emailStore.claim(id, now.plusSeconds(59))).isEmpty();
        var retry = emailStore.claim(id, now.plusSeconds(61)).orElseThrow();
        assertThat(retry.key()).isEqualTo(first.key());
        assertThat(retry.claims()).hasSize(2).allSatisfy(c -> assertThat(c.attempt()).isEqualTo(2));
        emailStore.sent(first, now.plusSeconds(62));
        assertThat(alertService.list(user.getId(), false, 0, 20).content())
                .allSatisfy(a -> assertThat(a.emailStatus()).isEqualTo(EmailStatus.SENDING));
        emailStore.sent(retry, now.plusSeconds(63));
        assertThat(alertService.list(user.getId(), false, 0, 20).content())
                .allSatisfy(a -> assertThat(a.emailStatus()).isEqualTo(EmailStatus.SENT));
    }

    @Test void incompleteGroupIsDeferredAndSendsAvailableImpactsAtTheDeadline() {
        Activity activity = new Activity(); activity.setItinerary(trip); activity.setCountry(trip.getCountry());
        activity.setName("Not yet assessed"); activity = activities.saveAndFlush(activity);
        evaluate();
        AlertResponse alert = alertService.list(user.getId(), false, 0, 20).content().getFirst();
        var now = alert.createdAt().plusSeconds(1);
        assertThat(emailStore.claim(alert.id(), now)).isEmpty();
        Alert waiting = alerts.findById(alert.id()).orElseThrow();
        assertThat(waiting.getEmailNextAttemptAt()).isEqualTo(now.plusSeconds(30));
        assertThat(waiting.getEmailAttempts()).isZero();
        assertThat(emailStore.claim(alert.id(), now.plusSeconds(29))).isEmpty();
        // Checking near the deadline must not push it back another whole recheck interval.
        assertThat(emailStore.claim(alert.id(), alert.createdAt().plusSeconds(119))).isEmpty();
        assertThat(alerts.findById(alert.id()).orElseThrow().getEmailNextAttemptAt())
                .isEqualTo(alert.createdAt().plusSeconds(120));
        var partial = emailStore.claim(alert.id(), alert.createdAt().plusSeconds(120)).orElseThrow();
        assertThat(partial.partialAssessment()).isTrue();
        assertThat(partial.relatedAlerts()).hasSize(1);
        emailStore.sent(partial, alert.createdAt().plusSeconds(121));
        verdict(true); impactService.evaluateActivityForNews(activity, article);
        var late = alertService.list(user.getId(), false, 0, 20).content().getFirst();
        assertThat(emailStore.claim(late.id(), alert.createdAt().plusSeconds(122))).isEmpty();
        assertThat(alertService.list(user.getId(), false, 0, 20).content()).hasSize(2);
    }

    @Test void lateAlertInAFailedGroupBecomesFailedEvenWhenOtherAssessmentsAreMissing() {
        evaluate();
        var firstAlert = alertService.list(user.getId(), false, 0, 20).content().getFirst();
        var now = firstAlert.createdAt().plusSeconds(1);
        var first = emailStore.claim(firstAlert.id(), now).orElseThrow(); emailStore.failed(first, now);
        var second = emailStore.claim(firstAlert.id(), now.plusSeconds(61)).orElseThrow();
        emailStore.failed(second, now.plusSeconds(61));
        var third = emailStore.claim(firstAlert.id(), now.plusSeconds(182)).orElseThrow();
        emailStore.failed(third, now.plusSeconds(182));
        Activity lateActivity = new Activity(); lateActivity.setItinerary(trip); lateActivity.setCountry(trip.getCountry());
        lateActivity.setName("Late activity"); lateActivity = activities.saveAndFlush(lateActivity);
        Activity missing = new Activity(); missing.setItinerary(trip); missing.setCountry(trip.getCountry());
        missing.setName("Still unassessed"); activities.saveAndFlush(missing);
        impactService.evaluateActivityForNews(lateActivity, article);
        var late = alertService.list(user.getId(), false, 0, 20).content().getFirst();
        assertThat(emailStore.claim(late.id(), now.plusSeconds(183))).isEmpty();
        assertThat(alerts.findById(late.id()).orElseThrow().getEmailStatus()).isEqualTo(EmailStatus.FAILED);
        assertThat(alerts.findEmailReady(AlertEmailStore.RETRYABLE, now.plusSeconds(1000),
                org.springframework.data.domain.PageRequest.of(0, 1000)))
                .doesNotContain(late.id(), firstAlert.id());
        assertThat(alertService.list(user.getId(), false, 0, 20).content()).hasSize(2);
    }

    @Test void fiftyIncompleteGroupsDoNotStarveANewerEmailRetry() {
        var now = java.time.Instant.now();
        // Other tests keep their own fixtures in this isolated SQLite database. Park their jobs
        // so the first fifty candidates below are exactly this scenario's blocked groups.
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            for (Alert existing : alerts.findAll()) {
                alerts.finishEmail(existing.getId(), existing.getEmailAttempts(), AlertEmailStore.RETRYABLE,
                        existing.getEmailStatus(), existing.getEmailSentAt(), now.plusSeconds(3600));
            }
        });
        java.util.List<Long> blocked = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) {
            Itinerary other = new Itinerary(); other.setUser(user); other.setCountry(trip.getCountry());
            other.setName("Waiting trip " + i); other = itineraries.saveAndFlush(other);
            Activity missing = new Activity(); missing.setItinerary(other); missing.setCountry(trip.getCountry());
            missing.setName("Missing assessment"); activities.saveAndFlush(missing);
            blocked.add(pendingAt(other, java.time.Instant.EPOCH, now).getId());
        }
        Alert healthy = pendingAt(trip, java.time.Instant.EPOCH.plusMillis(1), now);
        var page = org.springframework.data.domain.PageRequest.of(0, 50);
        var first = alerts.findEmailReady(AlertEmailStore.RETRYABLE, now.plusSeconds(1), page);
        assertThat(first).containsExactlyElementsOf(blocked);
        for (Long id : first) assertThat(emailStore.claim(id, now.plusSeconds(1))).isEmpty();
        var next = alerts.findEmailReady(AlertEmailStore.RETRYABLE, now.plusSeconds(1), page);
        assertThat(next).contains(healthy.getId()).doesNotContainAnyElementsOf(blocked);
        var delivery = emailStore.claim(healthy.getId(), now.plusSeconds(1)).orElseThrow();
        // A failed first send must still receive a retry while all fifty old groups remain incomplete.
        emailStore.failed(delivery, now.plusSeconds(1));
        for (Long id : blocked) assertThat(emailStore.claim(id, now.plusSeconds(61))).isEmpty();
        assertThat(alerts.findEmailReady(AlertEmailStore.RETRYABLE, now.plusSeconds(61), page)).contains(healthy.getId());
        assertThat(emailStore.claim(healthy.getId(), now.plusSeconds(61))).isPresent();
    }

    private Alert pendingAt(Itinerary itinerary, java.time.Instant readyAt, java.time.Instant createdAt) {
        Impact impact = new Impact(); impact.setUser(user); impact.setItinerary(itinerary); impact.setNews(article);
        impact.setImpacted(true); impact.setAlertEligible(true); impact.setReason("Saved impact");
        impact = impacts.saveAndFlush(impact);
        Alert alert = new Alert(impact);
        org.springframework.test.util.ReflectionTestUtils.setField(alert, "createdAt", createdAt);
        org.springframework.test.util.ReflectionTestUtils.setField(alert, "emailNextAttemptAt", readyAt);
        return alerts.saveAndFlush(alert);
    }

    @Test void lateActivityForAlreadyNotifiedNewsDoesNotSendAnotherEmail() {
        evaluate();
        Long id = alertService.list(user.getId(), false, 0, 20).content().getFirst().id();
        var now = java.time.Instant.now().plusSeconds(1);
        emailStore.sent(emailStore.claim(id, now).orElseThrow(), now);
        Activity activity = new Activity(); activity.setItinerary(trip); activity.setCountry(trip.getCountry());
        activity.setName("Added later"); activity = activities.saveAndFlush(activity);
        impactService.evaluateActivityForNews(activity, article);
        Long added = alertService.list(user.getId(), false, 0, 20).content().getFirst().id();
        assertThat(emailStore.claim(added, now.plusSeconds(1000))).isEmpty();
        assertThat(alertService.list(user.getId(), false, 0, 20).content()).hasSize(2);
    }

    @Test void activityOnlyDisruptionStillGetsAnEmailAndOtherNewsIsSeparate() {
        Activity activity = new Activity(); activity.setItinerary(trip); activity.setCountry(trip.getCountry());
        activity.setName("Trek"); activity = activities.saveAndFlush(activity);
        verdict(false); impactService.evaluateItineraryForNews(trip, article);
        verdict(true); impactService.evaluateActivityForNews(activity, article);
        Long id = alertService.list(user.getId(), false, 0, 20).content().getFirst().id();
        var now = java.time.Instant.now().plusSeconds(1);
        var delivery = emailStore.claim(id, now).orElseThrow();
        assertThat(delivery.relatedAlerts()).hasSize(1);
        assertThat(delivery.alert().activityId()).isEqualTo(activity.getId());
        emailStore.sent(delivery, now);
        News other = new News(); other.setTitle("Another event"); other.setUrl("https://example.com/" + UUID.randomUUID());
        other = news.saveAndFlush(other);
        impactService.evaluateItineraryForNews(trip, other);
        impactService.evaluateActivityForNews(activity, other);
        Long newAlert = alertService.list(user.getId(), false, 0, 20).content().getFirst().id();
        assertThat(emailStore.claim(newAlert, java.time.Instant.now().plusSeconds(1))).isPresent();
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

    private DemoDisruptionService demo(boolean enabled) {
        return new DemoDisruptionService(impacts, alerts, recommendations, itineraries, users, events,
                enabled, user.getId(), trip.getId(), article.getId());
    }

    @Test void demoResetRemovesTheDisruptionAndTriggerRestoresItWithoutCallingAi() {
        Activity activity = new Activity(); activity.setItinerary(trip); activity.setCountry(trip.getCountry());
        activity.setName("Demo trek"); activity = activities.saveAndFlush(activity);
        Impact overall = evaluate(); impactService.evaluateActivityForNews(activity, article);
        var recommendation = new csd.tripsense.recommendation.Recommendation();
        recommendation.setImpact(overall);
        recommendation.setSeverity(csd.tripsense.recommendation.Severity.MEDIUM);
        recommendation.setAction(csd.tripsense.recommendation.Action.MONITOR);
        recommendation.setAdvice("Demo advice"); recommendations.saveAndFlush(recommendation);
        var originalKeys = alerts.findEmailGroup(trip.getId(), article.getId()).stream()
                .map(Alert::getEmailDeliveryKey).toList();
        var service = demo(true);
        var tx = new TransactionTemplate(transactions);
        var reset = tx.execute(s -> service.reset(user.getId()));
        assertThat(reset.state()).isEqualTo("READY");
        assertThat(alertService.list(user.getId(), false, 0, 20).content()).isEmpty();
        assertThat(recommendations.findByImpactItineraryId(trip.getId())).isEmpty();
        assertThat(impacts.findByItineraryIdAndNewsId(trip.getId(), article.getId()))
                .hasSize(2).allSatisfy(i -> {
                    assertThat(i.getImpacted()).isFalse();
                    assertThat(i.getReason()).isEqualTo("Resort closed");
                });
        recovery.reconcile();
        assertThat(alertService.list(user.getId(), false, 0, 20).content()).isEmpty();
        clearInvocations(ai);
        var triggered = tx.execute(s -> service.trigger(user.getId()));
        assertThat(triggered.alerts()).isEqualTo(2);
        var fresh = alertService.list(user.getId(), false, 0, 20).content();
        assertThat(fresh).hasSize(2).allSatisfy(a -> {
            assertThat(a.readAt()).isNull();
            assertThat(a.emailStatus()).isEqualTo(EmailStatus.PENDING);
        });
        assertThat(alerts.findEmailGroup(trip.getId(), article.getId()))
                .allSatisfy(a -> assertThat(a.getEmailDeliveryKey()).isNotIn(originalKeys));
        verifyNoInteractions(ai);
        var delivery = emailStore.claim(fresh.getFirst().id(), java.time.Instant.now().plusSeconds(1)).orElseThrow();
        assertThat(delivery.relatedAlerts()).hasSize(2);
        emailStore.sent(delivery, java.time.Instant.now());
        assertThat(alertService.list(user.getId(), false, 0, 20).content())
                .allSatisfy(a -> assertThat(a.emailStatus()).isEqualTo(EmailStatus.SENT));
        assertThatThrownBy(() -> tx.execute(s -> service.trigger(user.getId())))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test void demoRequiresExplicitEnablementAndTheConfiguredOwner() {
        evaluate();
        assertThatThrownBy(() -> demo(false).reset(user.getId()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> demo(true).reset(user.getId() + 1))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> demo(true).trigger(null))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        for (String role : java.util.List.of("USER", "ADMIN")) {
            User other = users.saveAndFlush(new User(UUID.randomUUID() + "@example.com",
                    "other" + UUID.randomUUID().toString().substring(0, 8), "long-enough-hash", role));
            assertThatThrownBy(() -> demo(true).reset(other.getId()))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                    .hasMessageContaining("404");
            assertThatThrownBy(() -> demo(true).trigger(other.getId()))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                    .hasMessageContaining("404");
        }
        assertThat(alertService.list(user.getId(), false, 0, 20).content()).hasSize(1);
    }

    @Test void demoCannotResetWhileItsEmailIsBeingSent() {
        evaluate();
        Long id = alertService.list(user.getId(), false, 0, 20).content().getFirst().id();
        emailStore.claim(id, java.time.Instant.now().plusSeconds(1)).orElseThrow();
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(s -> demo(true).reset(user.getId())))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("delivery");
        assertThat(alertService.list(user.getId(), false, 0, 20).content()).hasSize(1);
    }

    @Test void demoEndpointsAreAbsentByDefault() throws Exception {
        var session = login(user);
        var csrf = mvc.perform(get("/").session(session)).andReturn().getResponse().getCookie("XSRF-TOKEN");
        mvc.perform(post("/api/alerts/demo/reset").session(session).cookie(csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())).andExpect(status().isNotFound());
    }

    @Test void enabledDemoRoutesRequireSessionAndCsrfAndPassTheAuthenticatedOwner() throws Exception {
        DemoDisruptionService service = mock(DemoDisruptionService.class);
        var routes = MockMvcBuilders.standaloneSetup(new DemoDisruptionController(service))
                .setCustomArgumentResolvers(new org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver())
                .addFilters(security).build();
        var anonymousCsrf = mvc.perform(get("/")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        routes.perform(post("/api/alerts/demo/trigger").cookie(anonymousCsrf)
                .header("X-XSRF-TOKEN", anonymousCsrf.getValue())).andExpect(status().isUnauthorized());
        var session = login(user);
        routes.perform(post("/api/alerts/demo/reset").session(session)).andExpect(status().isForbidden());
        verifyNoInteractions(service);
        var csrf = mvc.perform(get("/").session(session)).andReturn().getResponse().getCookie("XSRF-TOKEN");
        when(service.reset(user.getId())).thenReturn(new DemoDisruptionService.Result(trip.getId(), article.getId(), 1, 0, "READY"));
        when(service.trigger(user.getId())).thenReturn(new DemoDisruptionService.Result(trip.getId(), article.getId(), 1, 1, "TRIGGERED"));
        routes.perform(post("/api/alerts/demo/reset").session(session).cookie(csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("READY"));
        routes.perform(post("/api/alerts/demo/trigger").session(session).cookie(csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("TRIGGERED"));
        verify(service).reset(user.getId()); verify(service).trigger(user.getId());
    }

}
