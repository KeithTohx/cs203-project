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
    @MockitoSpyBean private EvaluationProgress progress;
    @Autowired private AlertEvaluationListener recovery;
    @Autowired private AlertProgressReporter reporter;
    @Autowired private EvaluationTargetRepository targetProgress;
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
    @MockitoSpyBean ImpactEvaluationQueue queue;
    @Autowired PendingEvaluationRequestRepository requests;
    @Autowired EvaluationRequestRecovery requestRecovery;
    @Autowired csd.tripsense.recommendation.RecommendationService advice;
    @Autowired csd.tripsense.recommendation.RecommendationRepository recommendations;
    @Autowired AssessmentSourceGuard sourceGuard;
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
        requests.deleteAll();
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
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object modelVerdict(Class<?> type, boolean affected, String reason) throws Exception {
        var constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Class<?> kind = constructor.getParameterTypes()[1];
        return constructor.newInstance(reason,
                Enum.valueOf((Class<Enum>) kind, affected ? "DISASTER" : "NONE"), affected);
    }
    private void verdict() {
        doAnswer(inv -> {
            return modelVerdict(inv.getArgument(1), true, "Access restricted");
        }).when(ai).ask(anyString(), any(Class.class));
    }
    private Itinerary newTrip() {
        Itinerary trip = new Itinerary(); trip.setUser(owner); trip.setCountry(country); trip.setName("Demo trip");
        trip.setDateStart(LocalDateTime.now().plusDays(1)); trip.setDateEnd(LocalDateTime.now().plusDays(2));
        Itinerary saved=itineraryService.addItinerary(trip);
        materializeCreatedRequests(); return saved;
    }
    private Activity activity(Itinerary trip) {
        Activity a = new Activity(); a.setItinerary(trip); a.setName("Trek");
        Activity saved=activityService.addActivity(a);
        materializeCreatedRequests(); return saved;
    }
    // The production worker materializes intents asynchronously. These tests suppress its wakeup
    // and explicitly materialize outside the source transaction to inspect the durable snapshot.
    private void materializeCreatedRequests() {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            try { queue.recoverRequests(); } catch (RuntimeException temporaryFailure) { /* recovery test retries it */ }
        }
    }
    private NewItemEvaluationListener worker(int budget) {
        return new NewItemEvaluationListener(queue, tasks, trips, activities, news, impacts, evaluator, reporter, true, budget);
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
        assertThat(requests.findById(PendingEvaluationRequest.key(id,false))).isEmpty();
        assertThat(tasks.count()).isZero();
        listener.processPending(); verifyNoInteractions(ai);
    }

    @Test void workerIsWokenOnlyAfterSourceCreationCommits() {
        clearInvocations(listener);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            newTrip();
            assertThat(tasks.count()).isZero();
            verify(listener, never()).retryPending();
            verifyNoInteractions(ai);
        });
        verify(listener).retryPending();
    }

    @Test void negativeAutomaticAssessmentDoesNotCreateAnAlert() {
        doAnswer(inv -> {
            return modelVerdict(inv.getArgument(1), false, "Different dates");
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

    @Test void tripDeletionImmediatelyRemovesItsTripAndActivityTasks() {
        Itinerary deleted = newTrip();
        Activity child = activity(deleted);
        Itinerary retained = newTrip();
        assertThat(tasks.count()).isEqualTo(3);

        itineraryService.deleteItinerary(deleted.getId());

        assertThat(tasks.findAll()).singleElement().satisfies(task -> {
            assertThat(task.isActivity()).isFalse();
            assertThat(task.getTargetId()).isEqualTo(retained.getId());
        });
        assertThat(activities.findById(child.getId())).isEmpty();
        verifyNoInteractions(ai);
    }

    @Test void activityDeletionDoesNotRemoveTheParentTripsTask() {
        Itinerary trip = newTrip();
        Activity deleted = activity(trip);
        Activity retained = activity(trip);

        activityService.deleteActivity(deleted.getId());

        assertThat(tasks.findAll()).hasSize(2).noneSatisfy(task -> {
            assertThat(task.isActivity()).isTrue();
            assertThat(task.getTargetId()).isEqualTo(deleted.getId());
        });
        // Direct repository deletes use the same cleanup, including account-deletion cascades.
        activities.deleteById(retained.getId());
        assertThat(tasks.findAll()).singleElement().satisfies(task -> assertThat(task.isActivity()).isFalse());
        trips.deleteById(trip.getId());
        assertThat(tasks.count()).isZero();
    }

    @Test void rollingBackTripDeletionAlsoRestoresItsPendingTasks() {
        Itinerary trip = newTrip();
        activity(trip);
        var keys = tasks.findAll().stream().map(PendingImpactEvaluation::getTaskKey).toList();

        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            trips.deleteById(trip.getId());
            trips.flush();
            assertThat(tasks.count()).isZero();
            tx.setRollbackOnly();
        });

        assertThat(trips.findById(trip.getId())).isPresent();
        assertThat(tasks.findAll()).extracting(PendingImpactEvaluation::getTaskKey)
                .containsExactlyInAnyOrderElementsOf(keys);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void inFlightVerdictCannotAttachToAReplacementTrip(boolean activityAssessment, boolean differentOwnerAndCountry) {
        Itinerary deleted = newTrip();
        Activity deletedActivity = activityAssessment ? activity(deleted) : null;
        User replacementOwner = differentOwnerAndCountry
                ? users.saveAndFlush(new User(UUID.randomUUID() + "@example.com", "replacement" + owner.getId(), "long-enough-hash", "USER"))
                : owner;
        Country replacementCountry = country;
        if (differentOwnerAndCountry) {
            replacementCountry = new Country(); replacementCountry.setName("Replacement " + UUID.randomUUID());
            replacementCountry = countries.saveAndFlush(replacementCountry);
        }
        Country destination = replacementCountry;
        var replacement = new java.util.concurrent.atomic.AtomicReference<Itinerary>();
        doAnswer(inv -> {
            // The model is still running while the source is deleted and its numeric id reused.
            trips.deleteById(deleted.getId());
            Itinerary fresh = new Itinerary(); fresh.setUser(replacementOwner); fresh.setCountry(destination);
            fresh.setName(deleted.getName()); fresh.setDateStart(deleted.getDateStart()); fresh.setDateEnd(deleted.getDateEnd());
            fresh = itineraryService.addItinerary(fresh);
            assertThat(fresh.getId()).isEqualTo(deleted.getId());
            assertThat(fresh.getAssessmentIdentity()).isNotEqualTo(deleted.getAssessmentIdentity());
            replacement.set(fresh);
            if (activityAssessment) {
                Activity freshActivity = activity(fresh);
                assertThat(freshActivity.getId()).isEqualTo(deletedActivity.getId());
            }
            return modelVerdict(inv.getArgument(1), true, "Verdict for deleted trip");
        }).when(ai).ask(anyString(), any(Class.class));

        assertThatThrownBy(() -> {
            if (activityAssessment) evaluator.evaluateActivityForNews(deletedActivity, article);
            else evaluator.evaluateItineraryForNews(deleted, article);
        }).isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);

        assertThat(impacts.findByItineraryId(replacement.get().getId())).isEmpty();
        assertThat(alertRows.findEmailGroup(replacement.get().getId(), article.getId())).isEmpty();
        // Rejecting the obsolete result must leave the replacement free to be assessed normally.
        verdict();
        Impact freshVerdict = evaluator.evaluateItineraryForNews(replacement.get(), article);
        assertThat(freshVerdict.getUser().getId()).isEqualTo(replacementOwner.getId());
        assertThat(freshVerdict.getReason()).isEqualTo("Access restricted");
        assertThat(alertRows.findEmailGroup(replacement.get().getId(), article.getId())).hasSize(1);
    }

    @Test void inFlightActivityVerdictCannotAttachToAReplacementActivityOnTheSameTrip() {
        Itinerary trip = newTrip();
        Activity deleted = activity(trip);
        doAnswer(inv -> {
            activities.deleteById(deleted.getId());
            Activity replacement = activity(trip);
            assertThat(replacement.getId()).isEqualTo(deleted.getId());
            assertThat(replacement.getAssessmentIdentity()).isNotEqualTo(deleted.getAssessmentIdentity());
            return modelVerdict(inv.getArgument(1), true, "Verdict for deleted activity");
        }).when(ai).ask(anyString(), any(Class.class));

        assertThatThrownBy(() -> evaluator.evaluateActivityForNews(deleted, article))
                .isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
        assertThat(impacts.findByItineraryId(trip.getId())).isEmpty();
        assertThat(alertRows.findEmailGroup(trip.getId(), article.getId())).isEmpty();
    }

    @Test void sourceCannotBeDeletedBetweenValidationAndSavingTheVerdict() {
        Itinerary trip = newTrip();
        sourceGuard.saveIfCurrent(AssessmentSourceGuard.Source.capture(trip, null), () -> {
            try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + DATABASE);
                 var sql = connection.createStatement()) {
                sql.execute("pragma busy_timeout=0");
                assertThatThrownBy(() -> sql.executeUpdate("delete from itinerary where id=" + trip.getId()))
                        .isInstanceOf(java.sql.SQLException.class).hasMessageContaining("SQLITE_BUSY");
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
            return null;
        });
        assertThat(trips.findById(trip.getId())).isPresent();
    }

    @Test void reusedTripIdGetsFreshNewsAndOldWorkerCannotModifyItsTask() {
        Itinerary deleted = newTrip();
        var obsolete = tasks.findAll().getFirst();
        trips.deleteById(deleted.getId());
        assertThat(tasks.count()).isZero();

        Country replacementCountry = new Country(); replacementCountry.setName("Replacement " + UUID.randomUUID());
        replacementCountry = countries.saveAndFlush(replacementCountry);
        News replacementNews = article("Replacement country's disruption");
        replacementNews.setCountries(Set.of(replacementCountry));
        replacementNews = news.saveAndFlush(replacementNews);
        Itinerary replacement = new Itinerary(); replacement.setUser(owner); replacement.setCountry(replacementCountry);
        replacement.setName("Replacement trip"); replacement = itineraryService.addItinerary(replacement);
        queue.recoverRequests();
        assertThat(replacement.getId()).isEqualTo(deleted.getId());
        var fresh = tasks.findAll().getFirst();
        assertThat(fresh.getTaskKey()).isNotEqualTo(obsolete.getTaskKey());
        assertThat(fresh.articles()).containsExactly(replacementNews.getId());

        queue.progress(obsolete.getTaskKey(), 99, Instant.EPOCH);
        queue.deferArticle(obsolete.getTaskKey(), article.getId(), 99);
        queue.finishPass(obsolete.getTaskKey());
        queue.complete(obsolete.getTaskKey());
        assertThat(tasks.findById(fresh.getTaskKey())).hasValueSatisfying(task -> {
            assertThat(task.getCursor()).isZero();
            assertThat(task.articles()).containsExactly(fresh.articles().getFirst());
        });
        listener.processPending();
        assertThat(impacts.findByItineraryId(replacement.getId())).singleElement()
                .satisfies(impact -> assertThat(impact.getNews().getId()).isEqualTo(fresh.articles().getFirst()));
    }

    @Test void concurrentEvaluationPathsShareOneAssessmentAndOneAiCall() throws Exception {
        Itinerary trip = newTrip();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var secondStarted = new java.util.concurrent.CountDownLatch(1);
        doAnswer(inv -> {
            entered.countDown();
            if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Test timed out");
            return modelVerdict(inv.getArgument(1), true, "Access restricted");
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
        var grouping = new AlertEmailStore(alertRows, emailGroups, batches, targetProgress, 120, 30, 30);
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

    @Test void alertReconciliationRepairsMissedCompletionWithoutRepeatingAi() {
        doThrow(new IllegalStateException("temporary progress failure")).doCallRealMethod()
                .when(progress).resolved(anyLong(),anyLong(),isNull());
        Itinerary trip=newTrip();
        listener.processPending();
        assertThat(impacts.findByItineraryId(trip.getId())).hasSize(1);
        assertThat(tasks.count()).isZero();
        assertThat(targetProgress.countUnresolved(trip.getId(),article.getId())).isEqualTo(1);
        recovery.reconcile();
        assertThat(tasks.count()).isZero();
        assertThat(targetProgress.countUnresolved(trip.getId(),article.getId())).isZero();
        verify(ai,times(1)).ask(anyString(),any(Class.class));
        var alert=alerts.list(owner.getId(),false,null,20).content().getFirst();
        assertThat(email.claim(alert.id(),Instant.now().plusSeconds(1)).orElseThrow().partialAssessment()).isFalse();
    }

    @Test void editedDuringModelCallDiscardsOldVerdictThenEvaluatesNewDetails() {
        Itinerary trip=newTrip(); Activity original=activity(trip);
        tasks.deleteAll();
        doAnswer(inv -> {
            Activity changes=new Activity(); changes.setName("New activity"); changes.setAddress("Different city");
            activityService.updateActivity(original.getId(),changes);
            return modelVerdict(inv.getArgument(1), true, "Old details");
        }).when(ai).ask(anyString(),any(Class.class));
        assertThatThrownBy(() -> evaluator.evaluateActivityForNews(original,article))
                .isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
        assertThat(alerts.list(owner.getId(),false,null,20).content()).isEmpty();
        verdict(); listener.processPending();
        assertThat(impacts.findFirstByNewsIdAndActivityId(article.getId(),original.getId()))
                .hasValueSatisfying(i -> assertThat(i.getJudgedRevision()).isEqualTo(1));
    }

    @Test void committedSchedulingRequestRecoversAfterQueueInsertFails() {
        // This article also lies outside the ingest job's shorter three-day window.
        article.setPublishedAt(LocalDateTime.now(ZoneOffset.UTC).minusDays(4)); news.saveAndFlush(article);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("temporary failure"))
                .when(queue).enqueue(anyLong(),eq(false));
        Itinerary trip=newTrip();
        assertThat(tasks.count()).isZero();
        assertThat(requests.findById(PendingEvaluationRequest.key(trip.getId(),false))).isPresent();
        doCallRealMethod().when(queue).enqueue(anyLong(),eq(false));
        requestRecovery.recover(); listener.processPending();
        assertThat(requests.findById(PendingEvaluationRequest.key(trip.getId(),false))).isEmpty();
        assertThat(impacts.findByItineraryId(trip.getId())).hasSize(1);
    }

    @Test void countryEditClearsOldVerdictsAdviceAndRequeuesBothScopes() {
        Itinerary trip=newTrip(); Activity original=activity(trip);
        listener.processPending();
        var oldImpact=impacts.findFirstByNewsIdAndActivityId(article.getId(),original.getId()).orElseThrow();
        var oldAdvice=new csd.tripsense.recommendation.Recommendation(); oldAdvice.setImpact(oldImpact);
        oldAdvice.setAction(csd.tripsense.recommendation.Action.MONITOR);
        oldAdvice.setSeverity(csd.tripsense.recommendation.Severity.HIGH);
        oldAdvice.setStatus(csd.tripsense.recommendation.RecommendationStatus.PENDING);
        recommendations.saveAndFlush(oldAdvice);
        Country destination=new Country(); destination.setName("Destination "+UUID.randomUUID());
        destination=countries.saveAndFlush(destination);
        News destinationArticle=article("Destination disruption"); destinationArticle.setCountries(Set.of(destination));
        destinationArticle=news.saveAndFlush(destinationArticle);
        Itinerary changes=new Itinerary(); changes.setName(trip.getName()); changes.setCountry(destination);
        changes.setDateStart(trip.getDateStart()); changes.setDateEnd(trip.getDateEnd());
        itineraryService.updateItinerary(trip.getId(),changes);
        assertThat(impacts.findByItineraryId(trip.getId())).isEmpty();
        assertThat(recommendations.findByImpactItineraryId(trip.getId())).isEmpty();
        listener.processPending();
        Long expectedArticle=destinationArticle.getId();
        assertThat(activities.findById(original.getId()).orElseThrow().getCountry().getId()).isEqualTo(destination.getId());
        assertThat(impacts.findByItineraryId(trip.getId())).hasSize(2)
                .allSatisfy(i -> { assertThat(i.getNews().getId()).isEqualTo(expectedArticle);
                    assertThat(i.getJudgedRevision()).isEqualTo(1); });
    }


    @Test void adviceInFlightCannotBeAttachedToReplacementVerdict() {
        Itinerary trip=newTrip(); Activity original=activity(trip); listener.processPending();
        doAnswer(inv -> {
            Class<?> type=inv.getArgument(1);
            if (type.getSimpleName().equals("Suggestion")) {
                Activity changes=new Activity(); changes.setName("Changed activity"); changes.setAddress("New location");
                activityService.updateActivity(original.getId(),changes);
                evaluator.evaluateActivityForNews(activities.findById(original.getId()).orElseThrow(),article);
                var c=type.getDeclaredConstructor(String.class,String.class,Long.class,String.class);
                c.setAccessible(true); return c.newInstance("HIGH","MONITOR",null,"Advice for the old activity");
            }
            return modelVerdict(type, true, "Current details");
        }).when(ai).ask(anyString(),any(Class.class));
        advice.generate(trip.getId());
        assertThat(recommendations.findByImpactItineraryId(trip.getId()))
                .noneMatch(r -> "Advice for the old activity".equals(r.getAdvice()));
    }

    @Test void renameCanSendFreshTripAlertWithoutResendingUnchangedActivity() {
        Itinerary trip=newTrip(); Activity original=activity(trip); listener.processPending();
        var initial=alerts.list(owner.getId(),false,null,20).content();
        var first=email.claim(initial.getFirst().id(),Instant.now().plusSeconds(1)).orElseThrow();
        email.sent(first,Instant.now());
        Long activityVerdict=impacts.findFirstByNewsIdAndActivityId(article.getId(),original.getId()).orElseThrow().getId();
        Itinerary changes=new Itinerary(); changes.setName("Renamed trip"); changes.setCountry(trip.getCountry());
        changes.setDateStart(trip.getDateStart()); changes.setDateEnd(trip.getDateEnd());
        itineraryService.updateItinerary(trip.getId(),changes); listener.processPending();
        assertThat(impacts.findFirstByNewsIdAndActivityId(article.getId(),original.getId()).orElseThrow().getId())
                .isEqualTo(activityVerdict);
        var fresh=alerts.list(owner.getId(),false,null,20).content().stream()
                .filter(a -> a.activityId()==null).findFirst().orElseThrow();
        var followup=email.claim(fresh.id(),Instant.now().plusSeconds(2)).orElseThrow();
        assertThat(followup.key()).isNotEqualTo(first.key());
        assertThat(followup.relatedAlerts()).hasSize(1);
        assertThat(followup.alert().itineraryName()).isEqualTo("Renamed trip");
    }

    @Test void readInvalidationsArePublishedOnlyAfterCommit() {
        Itinerary trip=newTrip(); listener.processPending(); clearInvocations(streams);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            alerts.markAllRead(owner.getId(),null);
            verify(streams,never()).onChanged(any());
            status.setRollbackOnly();
        });
        verify(streams,never()).onChanged(any());
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            alerts.markAllRead(owner.getId(),null);
            verify(streams,never()).onChanged(any());
        });
        verify(streams).onChanged(argThat(e -> e.userId().equals(owner.getId())));
    }
}
