package csd.tripsense.impact;

import csd.tripsense.activity.*;
import csd.tripsense.alert.*;
import csd.tripsense.chat.ChatService;
import csd.tripsense.country.*;
import csd.tripsense.itinerary.*;
import csd.tripsense.news.*;
import csd.tripsense.user.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "gdelt.ingest.enabled=false", "impact.eval.enabled=false", "spring.sql.init.mode=never",
        "impact.eval.new-itinerary.enabled=true", "alerts.email.enabled=false",
        "alerts.email.initial-group-wait-seconds=0", "alerts.reconcile.initial-delay-ms=86400000",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.datasource.hikari.maximum-pool-size=2"
})
class AutomaticAlertIntegrationTest {
    private static final Path DATABASE = database();
    private static Path database() {
        try { return Files.createTempDirectory("tripsense-auto-alert-").resolve("test.db"); }
        catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }
    @DynamicPropertySource static void databaseProperties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE + "?foreign_keys=on");
    }
    @Autowired private EvaluationProgress progress;
    @Autowired private EvaluationBatchRepository batches;
    @Autowired private csd.tripsense.alert.AlertEmailGroupRepository emailGroups;
    @Autowired UserRepository users;
    @Autowired CountryRepository countries;
    @Autowired NewsRepository news;
    @Autowired ItineraryRepository trips;
    @Autowired ActivityRepository activities;
    @Autowired ItineraryService itineraryService;
    @Autowired ActivityService activityService;
    @Autowired ImpactRepository impacts;
    @Autowired ImpactService evaluator;
    @Autowired ImpactEvaluationQueue queue;
    @Autowired PendingImpactEvaluationRepository tasks;
    @Autowired AlertService alerts;
    @Autowired AlertEmailStore email;
    @Autowired AlertRepository alertRows;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ApplicationEventPublisher events;
    @MockitoBean ChatService ai;
    @MockitoSpyBean NewItemEvaluationListener listener;
    @MockitoSpyBean AlertStreamService streams;
    private User owner;
    private Country country;
    private News article;

    @BeforeEach void setup() {
        // Keep real transactional event handling, but drive the worker deterministically in tests.
        doNothing().when(listener).retryPending();
        tasks.deleteAll();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        owner = users.saveAndFlush(new User(suffix + "@example.com", "user" + suffix, "long-enough-hash", "USER"));
        country = new Country(); country.setName("Country " + suffix); country = countries.saveAndFlush(country);
        article = article("Disruption " + suffix);
        verdict();
    }
    private News article(String title) {
        News n = new News(); n.setTitle(title); n.setDescription("Access restricted");
        n.setUrl("https://example.com/" + UUID.randomUUID()); n.setCountries(Set.of(country));
        n.setPublishedAt(LocalDateTime.now(ZoneOffset.UTC)); return news.saveAndFlush(n);
    }
    private void verdict() {
        doAnswer(inv -> {
            var c = ((Class<?>) inv.getArgument(1)).getDeclaredConstructor(boolean.class, String.class);
            c.setAccessible(true); return c.newInstance(true, "Access restricted");
        }).when(ai).ask(anyString(), any(Class.class));
    }
    private Itinerary newTrip() {
        Itinerary trip = new Itinerary(); trip.setUser(owner); trip.setCountry(country); trip.setName("Demo trip");
        trip.setDateStart(LocalDateTime.now().plusDays(1)); trip.setDateEnd(LocalDateTime.now().plusDays(2));
        return itineraryService.addItinerary(trip);
    }
    private Activity activity(Itinerary trip) {
        Activity a = new Activity(); a.setItinerary(trip); a.setName("Trek");
        return activityService.addActivity(a);
    }
    private NewItemEvaluationListener worker(int budget) {
        return new NewItemEvaluationListener(queue, tasks, trips, activities, news, impacts, evaluator, progress, true, budget);
    }
    private void readyAll() {
        for (var task : tasks.findAll()) queue.progress(task.getTaskKey(), task.getCursor(), Instant.EPOCH);
    }

    @Test void creationAutomaticallyQueuesBothAssessmentsAndOneGroupedDelivery() {
        Itinerary trip = newTrip(); Activity activity = activity(trip);
        assertThat(tasks.count()).isEqualTo(2);
        assertThat(impacts.findByItineraryId(trip.getId())).isEmpty();
        listener.processPending();
        assertThat(tasks.count()).isZero();
        assertThat(impacts.findByItineraryId(trip.getId())).hasSize(2);
        var saved = alerts.list(owner.getId(), false, null, 20).content();
        assertThat(saved).hasSize(2);
        verify(ai, times(2)).ask(anyString(), any(Class.class));
        verify(streams, times(2)).onCreated(argThat(e -> e.userId().equals(owner.getId())));
        var delivery = email.claim(saved.getFirst().id(), Instant.now().plusSeconds(1)).orElseThrow();
        assertThat(delivery.relatedAlerts()).hasSize(2);
        email.sent(delivery, Instant.now());
        assertThat(email.claim(saved.getLast().id(), Instant.now().plusSeconds(1000))).isEmpty();
        User other = users.saveAndFlush(new User(UUID.randomUUID() + "@example.com", "other" + owner.getId(), "long-enough-hash", "USER"));
        assertThat(alerts.list(other.getId(), false, null, 20).content()).isEmpty();
    }

    @Test void rollbackAlsoRemovesQueuedWorkAndNeverCallsAi() {
        var tx = new TransactionTemplate(transactions);
        Long id = tx.execute(status -> { var trip = newTrip(); status.setRollbackOnly(); return trip.getId(); });
        assertThat(trips.findById(id)).isEmpty();
        assertThat(tasks.count()).isZero();
        listener.processPending(); verifyNoInteractions(ai);
    }

    @Test void workerIsWokenOnlyAfterSourceCreationCommits() {
        clearInvocations(listener);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            newTrip();
            assertThat(tasks.count()).isEqualTo(1);
            verify(listener, never()).retryPending();
            verifyNoInteractions(ai);
        });
        verify(listener).retryPending();
    }

    @Test void negativeAutomaticAssessmentDoesNotCreateAnAlert() {
        doAnswer(inv -> {
            var c = ((Class<?>) inv.getArgument(1)).getDeclaredConstructor(boolean.class, String.class);
            c.setAccessible(true); return c.newInstance(false, "Different dates");
        }).when(ai).ask(anyString(), any(Class.class));
        Itinerary trip = newTrip(); listener.processPending();
        assertThat(tasks.count()).isZero();
        assertThat(impacts.findByItineraryId(trip.getId())).hasSize(1)
                .allSatisfy(i -> assertThat(i.getAlertEligible()).isFalse());
        assertThat(alerts.list(owner.getId(), false, null, 20).content()).isEmpty();
    }

    @Test void cappedWorkResumesAfterWorkerReplacementWithoutRepeatingCompletedCalls() {
        article("Second disruption"); Itinerary trip = newTrip();
        Activity a = new Activity(); a.setItinerary(trip); a.setCountry(country); a.setName("Trek"); activities.saveAndFlush(a);
        try (var replacement = new Worker(worker(1))) {
            replacement.listener.processPending();
            assertThat(tasks.count()).isEqualTo(1);
            assertThat(impacts.findByItineraryId(trip.getId())).hasSize(1);
        }
        // A new worker uses persisted progress, including a partially completed article.
        try (var replacement = new Worker(worker(1))) {
            for (int i = 0; i < 4; i++) { readyAll(); replacement.listener.processPending(); }
        }
        assertThat(tasks.count()).isZero();
        assertThat(impacts.findByItineraryId(trip.getId())).hasSize(4);
        verify(ai, times(4)).ask(anyString(), any(Class.class));
    }

    @Test void failedAssessmentsRetryEvenWhenTheArticleAgesOutsideTheLookback() {
        Itinerary trip = newTrip();
        when(ai.ask(anyString(), any(Class.class))).thenThrow(new RuntimeException("Model offline"));
        listener.processPending(); assertThat(tasks.count()).isEqualTo(1);
        article.setPublishedAt(LocalDateTime.now(ZoneOffset.UTC).minusDays(30)); news.saveAndFlush(article);
        verdict(); readyAll();
        try (var replacement = new Worker(worker(200))) { replacement.listener.processPending(); }
        assertThat(tasks.count()).isZero();
        assertThat(alerts.list(owner.getId(), false, null, 20).content()).hasSize(1);
        assertThat(impacts.findByItineraryId(trip.getId())).hasSize(1);
    }

    @Test void duplicateCreationEventsDoNotDuplicateTheTaskOrAlerts() {
        Itinerary trip = newTrip();
        events.publishEvent(new ItineraryCreatedEvent(trip.getId()));
        events.publishEvent(new ItineraryCreatedEvent(trip.getId()));
        assertThat(tasks.count()).isEqualTo(1);
        listener.processPending(); listener.processPending();
        verify(ai, times(1)).ask(anyString(), any(Class.class));
        assertThat(alerts.list(owner.getId(), false, null, 20).content()).hasSize(1);
    }

    @Test void lateActivityGetsAnInAppAlertWithoutSendingTheSameNewsTwice() {
        Itinerary trip = newTrip(); listener.processPending();
        var first = alerts.list(owner.getId(), false, null, 20).content().getFirst();
        email.sent(email.claim(first.id(), Instant.now().plusSeconds(1)).orElseThrow(), Instant.now());
        activity(trip); listener.processPending();
        var saved = alerts.list(owner.getId(), false, null, 20).content();
        assertThat(saved).hasSize(2);
        assertThat(email.claim(saved.getFirst().id(), Instant.now().plusSeconds(1))).isEmpty();
        verify(ai, times(2)).ask(anyString(), any(Class.class));
    }

    @Test void deletedTargetsAreDiscardedInsteadOfRetriedForever() {
        Itinerary trip = newTrip(); trips.deleteById(trip.getId());
        listener.processPending(); assertThat(tasks.count()).isZero(); verifyNoInteractions(ai);
    }

    @Test void concurrentEvaluationPathsShareOneAssessmentAndOneAiCall() throws Exception {
        Itinerary trip = newTrip();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var secondStarted = new java.util.concurrent.CountDownLatch(1);
        doAnswer(inv -> {
            entered.countDown();
            if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Test timed out");
            var c = ((Class<?>) inv.getArgument(1)).getDeclaredConstructor(boolean.class, String.class);
            c.setAccessible(true); return c.newInstance(true, "Access restricted");
        }).when(ai).ask(anyString(), any(Class.class));
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> evaluator.evaluateItineraryForNews(trip, article));
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> { secondStarted.countDown(); return evaluator.evaluateItineraryForNews(trip, article); });
            assertThat(secondStarted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            try { verify(ai, after(150).times(1)).ask(anyString(), any(Class.class)); }
            finally { release.countDown(); }
            assertThat(first.get(5, java.util.concurrent.TimeUnit.SECONDS).getId())
                    .isEqualTo(second.get(5, java.util.concurrent.TimeUnit.SECONDS).getId());
        } finally { release.countDown(); }
        assertThat(impacts.findByItineraryId(trip.getId())).hasSize(1);
        assertThat(alerts.list(owner.getId(), false, null, 20).content()).hasSize(1);
    }

    @Test void databaseRejectsDuplicateAssessmentKeys() {
        Itinerary trip = newTrip(); listener.processPending();
        Impact first = impacts.findByItineraryId(trip.getId()).getFirst();
        Impact duplicate = new Impact(); duplicate.setUser(owner); duplicate.setItinerary(trip);
        duplicate.setNews(article); duplicate.setImpacted(true); duplicate.setReason("Duplicate");
        duplicate.setAssessmentKey(first.getAssessmentKey());
        assertThatThrownBy(() -> impacts.saveAndFlush(duplicate))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .hasMessageContaining("impact.assessment_key");
        assertThat(impacts.findByItineraryId(trip.getId())).hasSize(1);
    }

    @Test void initialGroupingWindowIncludesActivitiesAddedImmediatelyAfterTheTrip() {
        Itinerary trip = newTrip(); listener.processPending();
        var first = alerts.list(owner.getId(), false, null, 20).content().getFirst();
        var grouping = new AlertEmailStore(alertRows, emailGroups, batches, 120, 30, 30);
        var tx = new TransactionTemplate(transactions);
        var waiting = tx.execute(s -> grouping.claim(first.id(), first.createdAt().plusSeconds(1)));
        assertThat(waiting).isEmpty();
        activity(trip); listener.processPending();
        var second = alerts.list(owner.getId(), false, null, 20).content().getFirst();
        var delivery = tx.execute(s -> grouping.claim(second.id(), first.createdAt().plusSeconds(31))).orElseThrow();
        assertThat(delivery.relatedAlerts()).hasSize(2);
        assertThat(delivery.partialAssessment()).isFalse();
    }

    private record Worker(NewItemEvaluationListener listener) implements AutoCloseable {
        @Override public void close() { listener.shutdown(); }
    }
}
