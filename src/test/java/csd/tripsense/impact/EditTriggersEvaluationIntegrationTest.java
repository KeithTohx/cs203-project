package csd.tripsense.impact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.mockito.invocation.InvocationOnMock;

import csd.tripsense.activity.Activity;
import csd.tripsense.activity.ActivityRepository;
import csd.tripsense.activity.ActivityService;
import csd.tripsense.alert.Alert;
import csd.tripsense.alert.AlertRepository;
import csd.tripsense.alert.AlertService;
import csd.tripsense.alert.AlertStreamService;
import csd.tripsense.alert.AlertsRemovedEvent;
import csd.tripsense.chat.ChatService;
import csd.tripsense.country.Country;
import csd.tripsense.country.CountryRepository;
import csd.tripsense.itinerary.Itinerary;
import csd.tripsense.itinerary.ItineraryRepository;
import csd.tripsense.itinerary.ItineraryService;
import csd.tripsense.news.News;
import csd.tripsense.news.NewsRepository;
import csd.tripsense.recommendation.Action;
import csd.tripsense.recommendation.Recommendation;
import csd.tripsense.recommendation.RecommendationRepository;
import csd.tripsense.recommendation.RecommendationService;
import csd.tripsense.recommendation.RecommendationStatus;
import csd.tripsense.recommendation.Severity;
import csd.tripsense.user.User;
import csd.tripsense.user.UserRepository;

// a traveller's own edit, from the service call through to the trip being judged again.
// a temporary sqlite file with foreign keys on, so a verdict that left its advice or alert
// behind would fail the delete. only the model is mocked, and it always says affected
@SpringBootTest(properties = {
        "gdelt.ingest.enabled=false",
        "impact.eval.enabled=false",
        "spring.sql.init.mode=never",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.hikari.maximum-pool-size=1",
        "alerts.reconcile.initial-delay-ms=86400000",
        "alerts.email.enabled=false"
})
@DisplayName("Edit Triggers Evaluation Integration Tests")
class EditTriggersEvaluationIntegrationTest {

    private static final Path DATABASE = temporaryDatabase();

    private static final String OLD_REASON = "Judged on the details before the edit";
    private static final String NEW_REASON = "Affected";

    // the re-evaluation runs on its own thread after the edit commits
    private static final Duration REJUDGED_WITHIN = Duration.ofSeconds(10);

    @Autowired private ItineraryService itineraryService;
    @Autowired private ActivityService activityService;
    @Autowired private ImpactService impactService;
    @Autowired private UserRepository users;
    @Autowired private CountryRepository countries;
    @Autowired private ItineraryRepository itineraries;
    @Autowired private ActivityRepository activities;
    @Autowired private NewsRepository news;
    @Autowired private ImpactRepository impacts;
    @Autowired private AlertRepository alerts;
    @Autowired private RecommendationRepository recommendations;
    @Autowired private RecommendationService recommendationService;
    @Autowired private AlertService alertService;

    @MockitoBean
    private ChatService ai;

    // real, but watched, to check the open stream is told when alerts are deleted
    @MockitoSpyBean
    private AlertStreamService alertStream;

    private Country japan;
    private Country thailand;
    private Itinerary trip;
    private Activity skiing;
    private News japanNews;
    private News thaiNews;

    private static Path temporaryDatabase() {
        try {
            return Files.createTempDirectory("tripsense-edit-").resolve("test.db");
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
        givenAiSaysAffected();

        japan = savedCountry("Japan");
        thailand = savedCountry("Thailand");

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User traveller = users.saveAndFlush(
                new User("t" + suffix + "@example.com", "t" + suffix, "long-enough-hash", "USER"));

        trip = new Itinerary();
        trip.setUser(traveller);
        trip.setCountry(japan);
        trip.setName("Ski trip");
        trip = itineraries.saveAndFlush(trip);

        skiing = new Activity();
        skiing.setItinerary(trip);
        skiing.setCountry(japan);
        skiing.setName("Ski at Niseko");
        skiing.setAddress("Niseko, Hokkaido");
        skiing = activities.saveAndFlush(skiing);

        japanNews = savedNews("Hokkaido earthquake", japan);
        thaiNews = savedNews("Bangkok floods", thailand);
    }

    // ===== HELPERS =====

    private void givenAiSaysAffected() {
        when(ai.ask(anyString(), any(Class.class))).thenAnswer(invocation -> affected(invocation, NEW_REASON));
    }

    // the verdict type is private to the evaluator, so it is built from whatever type was asked for
    private static Object affected(InvocationOnMock invocation, String reason) throws Exception {
        return verdict(invocation, true, reason);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object verdict(InvocationOnMock invocation, boolean impacted, String reason) throws Exception {
        Class<?> verdictType = invocation.getArgument(1);
        var constructor = verdictType.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        // an impacted answer has to name a kind, NONE is what the service overrules
        Class<?> kind = constructor.getParameterTypes()[1];
        return constructor.newInstance(reason,
                Enum.valueOf((Class<Enum>) kind, impacted ? "DISASTER" : "NONE"), impacted);
    }

    // the recommendation service asks for its own private record: severity, action, option, advice
    private static boolean isAdviceCall(InvocationOnMock invocation) {
        Class<?> answerType = invocation.getArgument(1);
        return answerType.getDeclaredConstructors()[0].getParameterCount() == 4;
    }

    private static Object advice(InvocationOnMock invocation, String text) throws Exception {
        Class<?> suggestionType = invocation.getArgument(1);
        var constructor = suggestionType.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return constructor.newInstance("HIGH", "MONITOR", null, text);
    }

    private Country savedCountry(String name) {
        Country country = new Country();
        country.setName(name + " " + UUID.randomUUID().toString().substring(0, 8));
        return countries.saveAndFlush(country);
    }

    // published just now, so it is inside the lookback window the re-evaluation reads
    private News savedNews(String title, Country about) {
        return savedNewsFrom(title, about, 0);
    }

    // the re-evaluation after an edit only reads the last few days of news by itself
    private News savedNewsFrom(String title, Country about, int daysAgo) {
        News article = new News();
        article.setTitle(title);
        article.setUrl("https://example.com/" + UUID.randomUUID());
        article.setPublishedAt(LocalDateTime.now(ZoneOffset.UTC).minusDays(daysAgo));
        article.setCountries(Set.of(about));
        return news.saveAndFlush(article);
    }

    // a verdict as the evaluator would have stored it before the edit, with an alert and advice
    // hanging off it. the re-evaluation runs straight away (the model is mocked) and sqlite hands
    // freed ids out again, so the tests tell the old verdicts apart by this reason, not by id
    private Impact savedOldVerdict(Activity activity, News article) {
        Impact impact = savedAlertedVerdict(activity, article);

        Recommendation advice = new Recommendation();
        advice.setImpact(impact);
        advice.setSeverity(Severity.HIGH);
        advice.setAction(Action.MONITOR);
        advice.setStatus(RecommendationStatus.PENDING);
        advice.setCreatedAt(LocalDateTime.now());
        recommendations.saveAndFlush(advice);
        return impact;
    }

    // an affected verdict with an unread alert, and no advice yet
    private Impact savedAlertedVerdict(Activity activity, News article) {
        Impact impact = new Impact();
        impact.setUser(trip.getUser());
        impact.setItinerary(trip);
        impact.setActivity(activity);
        impact.setNews(article);
        impact.setImpacted(true);
        impact.setReason(OLD_REASON);
        impact.setVerdictKey(UUID.randomUUID().toString());
        impact = impacts.saveAndFlush(impact);

        alerts.saveAndFlush(new Alert(impact));
        return impact;
    }

    // an alert or advice cannot outlive its verdict here, foreign keys are on and would have
    // failed the delete. so the verdict being gone means they went with it
    private boolean oldVerdictKept(Activity activity, News article) {
        Optional<Impact> stored;
        if (activity == null) {
            stored = impacts.findFirstByNewsIdAndItineraryIdAndActivityIsNull(article.getId(), trip.getId());
        } else {
            stored = impacts.findFirstByNewsIdAndActivityId(article.getId(), activity.getId());
        }
        return stored.filter(impact -> OLD_REASON.equals(impact.getReason())).isPresent();
    }

    // the re-evaluation writes verdicts and alerts but never advice, so any advice left is old
    private int adviceLeftOnTrip() {
        return recommendations.findByImpactItineraryId(trip.getId()).size();
    }

    private boolean judgedAgain(Activity activity, News article) {
        return judgedAgainAs(activity, article, NEW_REASON);
    }

    private boolean judgedAgainAs(Activity activity, News article, String reason) {
        Optional<Impact> stored;
        if (activity == null) {
            stored = impacts.findFirstByNewsIdAndItineraryIdAndActivityIsNull(article.getId(), trip.getId());
        } else {
            stored = impacts.findFirstByNewsIdAndActivityId(article.getId(), activity.getId());
        }
        return stored.filter(impact -> reason.equals(impact.getReason())).isPresent();
    }

    private Activity skiingMovedTo(String address) {
        Activity moved = new Activity();
        moved.setName(skiing.getName());
        moved.setAddress(address);
        return moved;
    }

    private Activity newActivity(String name, Itinerary on) {
        Activity activity = new Activity();
        activity.setItinerary(on);
        activity.setName(name);
        activity.setAddress("Sapporo, Hokkaido");
        return activity;
    }

    private User savedUser() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return users.saveAndFlush(new User("s" + suffix + "@example.com", "s" + suffix, "long-enough-hash", "USER"));
    }

    private static Itinerary tripFor(User owner, Country country, String name) {
        Itinerary itinerary = new Itinerary();
        itinerary.setUser(owner);
        itinerary.setCountry(country);
        itinerary.setName(name);
        return itinerary;
    }

    private static Activity newActivityIn(Itinerary on, Country country, String name) {
        Activity activity = new Activity();
        activity.setItinerary(on);
        activity.setCountry(country);
        activity.setName(name);
        activity.setAddress("Somewhere");
        return activity;
    }

    private Itinerary sameTripIn(Country country) {
        Itinerary changes = new Itinerary();
        changes.setName(trip.getName());
        changes.setCountry(country);
        return changes;
    }

    // ===== TESTS =====

    @Test
    @DisplayName("Should clear every verdict on the trip with its alerts and advice, and judge the trip and its activities again in the new country")
    void countryChange_ClearsTripVerdictsAndRejudges() {
        savedOldVerdict(null, japanNews);
        savedOldVerdict(skiing, japanNews);

        itineraryService.updateItinerary(trip.getId(), sameTripIn(thailand));

        // cleared in the same transaction as the edit, so it is gone as soon as the call returns
        assertThat(oldVerdictKept(null, japanNews)).isFalse();
        // about japanese news, which the trip is no longer checked against
        assertThat(oldVerdictKept(skiing, japanNews)).isFalse();
        assertThat(adviceLeftOnTrip()).isZero();

        // the news job matches activities on their own copy of the country
        assertThat(activities.findById(skiing.getId()).orElseThrow().getCountry().getId())
                .isEqualTo(thailand.getId());

        await().atMost(REJUDGED_WITHIN).until(() ->
                judgedAgain(null, thaiNews) && judgedAgain(skiing, thaiNews));
    }

    @Test
    @DisplayName("Should clear only the whole trip verdict when the dates change, the activity verdict still stands")
    void dateChange_ClearsOnlyWholeTripVerdict() {
        savedOldVerdict(null, japanNews);
        savedOldVerdict(skiing, japanNews);

        Itinerary later = sameTripIn(japan);
        later.setDateStart(LocalDateTime.now().plusDays(30));
        later.setDateEnd(LocalDateTime.now().plusDays(37));
        itineraryService.updateItinerary(trip.getId(), later);

        assertThat(oldVerdictKept(null, japanNews)).isFalse();
        // judged on the activity's own start, which did not change
        assertThat(oldVerdictKept(skiing, japanNews)).isTrue();
        assertThat(adviceLeftOnTrip()).isEqualTo(1);

        await().atMost(REJUDGED_WITHIN).until(() -> judgedAgain(null, japanNews));
    }

    @Test
    @DisplayName("Should clear only the whole trip verdict when the trip is renamed, the activity prompt never sees the trip name")
    void rename_ClearsOnlyWholeTripVerdict() {
        savedOldVerdict(null, japanNews);
        savedOldVerdict(skiing, japanNews);

        Itinerary renamed = sameTripIn(japan);
        renamed.setName("Hokkaido powder week");
        itineraryService.updateItinerary(trip.getId(), renamed);

        assertThat(oldVerdictKept(null, japanNews)).isFalse();
        assertThat(oldVerdictKept(skiing, japanNews)).isTrue();
        assertThat(adviceLeftOnTrip()).isEqualTo(1);

        await().atMost(REJUDGED_WITHIN).until(() -> judgedAgain(null, japanNews));
    }

    @Test
    @DisplayName("Should judge an article older than the lookback again after a rename, instead of losing its verdict")
    void rename_RejudgesOlderArticle() {
        News monthOld = savedNewsFrom("Ski resort fire", japan, 30);
        savedOldVerdict(null, monthOld);

        Itinerary renamed = sameTripIn(japan);
        renamed.setName("Hokkaido powder week");
        itineraryService.updateItinerary(trip.getId(), renamed);

        await().atMost(REJUDGED_WITHIN).until(() -> judgedAgain(null, monthOld));
        assertThat(oldVerdictKept(null, monthOld)).isFalse();
    }

    @Test
    @DisplayName("Should judge an older article again for an activity whose address changed")
    void activityAddressChange_RejudgesOlderArticle() {
        News monthOld = savedNewsFrom("Ski resort fire", japan, 30);
        savedOldVerdict(skiing, monthOld);

        activityService.updateActivity(skiing.getId(), skiingMovedTo("Furano, Hokkaido"));

        await().atMost(REJUDGED_WITHIN).until(() -> judgedAgain(skiing, monthOld));
    }

    @Test
    @DisplayName("Should leave every verdict alone when nothing the model saw changes")
    void unjudgedEdit_KeepsVerdicts() {
        savedOldVerdict(null, japanNews);
        savedOldVerdict(skiing, japanNews);

        // the end time is never part of the activity prompt
        Activity laterFinish = new Activity();
        laterFinish.setName(skiing.getName());
        laterFinish.setAddress(skiing.getAddress());
        laterFinish.setDateEnd(LocalDateTime.now().plusDays(3));
        activityService.updateActivity(skiing.getId(), laterFinish);
        itineraryService.updateItinerary(trip.getId(), sameTripIn(japan));

        assertThat(oldVerdictKept(null, japanNews)).isTrue();
        assertThat(oldVerdictKept(skiing, japanNews)).isTrue();
        assertThat(adviceLeftOnTrip()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should clear an activity's verdicts when its address changes and judge it again")
    void activityAddressChange_ClearsItsVerdictsAndRejudges() {
        savedOldVerdict(skiing, japanNews);
        savedOldVerdict(null, japanNews);

        Activity moved = new Activity();
        moved.setName(skiing.getName());
        moved.setAddress("Furano, Hokkaido");
        activityService.updateActivity(skiing.getId(), moved);

        assertThat(oldVerdictKept(skiing, japanNews)).isFalse();
        // the trip verdict was not about the activity's address
        assertThat(oldVerdictKept(null, japanNews)).isTrue();
        assertThat(adviceLeftOnTrip()).isEqualTo(1);

        await().atMost(REJUDGED_WITHIN).until(() -> judgedAgain(skiing, japanNews));
    }

    // ===== EDITS WHILE AN EVALUATION IS RUNNING =====

    @Test
    @DisplayName("Should throw away a verdict on the old country when the trip moves while the model is answering")
    void editDuringModelCall_VerdictOnOldDetailsIsNotSaved() {
        // loaded before the edit, as the ingest job would have it
        Activity skiingInJapan = activities.findById(skiing.getId()).orElseThrow();
        Thread testThread = Thread.currentThread();

        // the traveller moves the trip to thailand while the model is still judging japanese news
        doAnswer(invocation -> {
            String prompt = invocation.getArgument(0);
            boolean thisTestsCall = Thread.currentThread() == testThread && prompt.contains("Hokkaido earthquake");
            if (thisTestsCall) {
                itineraryService.updateItinerary(trip.getId(), sameTripIn(thailand));
            }
            return affected(invocation, OLD_REASON);
        }).when(ai).ask(anyString(), any(Class.class));

        assertThatThrownBy(() -> impactService.evaluateActivityForNews(skiingInJapan, japanNews))
                .isInstanceOf(EditedDuringEvaluationException.class);

        // the edit's own run judges the new country, and the japanese verdict never comes back
        await().atMost(REJUDGED_WITHIN).until(() -> impacts
                .findFirstByNewsIdAndActivityId(thaiNews.getId(), skiing.getId()).isPresent());
        assertThat(impacts.findFirstByNewsIdAndActivityId(japanNews.getId(), skiing.getId())).isEmpty();
    }

    // sqlite gives the next insert the highest id plus one, so deleting the newest trip frees its id
    // for the next trip, which also starts back at revision 0. a stranger's trip in another country
    // makes an alert saved against it plainly wrong
    @Test
    @DisplayName("Should throw away a verdict when the trip is deleted while the model is answering and a new trip takes its id")
    void tripReplacedDuringModelCall_VerdictIsNotSavedAgainstReplacement() {
        Itinerary doomed = itineraries.saveAndFlush(tripFor(trip.getUser(), japan, "Doomed trip"));
        User stranger = savedUser();
        Itinerary[] replacement = new Itinerary[1];
        Thread testThread = Thread.currentThread();

        doAnswer(invocation -> {
            String prompt = invocation.getArgument(0);
            boolean thisTestsCall = Thread.currentThread() == testThread && prompt.contains("Doomed trip");
            if (thisTestsCall) {
                itineraryService.deleteItinerary(doomed.getId());
                replacement[0] = itineraries.saveAndFlush(tripFor(stranger, thailand, "Stranger's trip"));
            }
            return affected(invocation, OLD_REASON);
        }).when(ai).ask(anyString(), any(Class.class));

        assertThatThrownBy(() -> impactService.evaluateItineraryForNews(doomed, japanNews))
                .isInstanceOf(EditedDuringEvaluationException.class);

        // the case this guards against, so the test fails loudly if sqlite ever stops reusing ids
        assertThat(replacement[0].getId()).isEqualTo(doomed.getId());
        assertThat(replacement[0].currentRevision()).isEqualTo(doomed.currentRevision());

        assertThat(impacts.findByItineraryId(replacement[0].getId())).isEmpty();
        assertThat(alertService.list(stranger.getId(), false, null, 20).content()).isEmpty();
    }

    @Test
    @DisplayName("Should throw away a verdict when the activity is deleted while the model is answering and a new activity takes its id")
    void activityReplacedDuringModelCall_VerdictIsNotSavedAgainstReplacement() {
        Activity doomed = activities.saveAndFlush(newActivityIn(trip, japan, "Doomed visit"));
        Itinerary strangersTrip = itineraries.saveAndFlush(tripFor(savedUser(), thailand, "Stranger's trip"));
        Activity[] replacement = new Activity[1];
        Thread testThread = Thread.currentThread();

        doAnswer(invocation -> {
            String prompt = invocation.getArgument(0);
            boolean thisTestsCall = Thread.currentThread() == testThread && prompt.contains("Doomed visit");
            if (thisTestsCall) {
                activityService.deleteActivity(doomed.getId());
                replacement[0] = activities.saveAndFlush(newActivityIn(strangersTrip, thailand, "Temple visit"));
            }
            return affected(invocation, OLD_REASON);
        }).when(ai).ask(anyString(), any(Class.class));

        assertThatThrownBy(() -> impactService.evaluateActivityForNews(doomed, japanNews))
                .isInstanceOf(EditedDuringEvaluationException.class);

        assertThat(replacement[0].getId()).isEqualTo(doomed.getId());
        assertThat(impacts.findByActivityId(replacement[0].getId())).isEmpty();
    }

    @Test
    @DisplayName("Should give every saved trip and activity its own identity key, and keep it through an edit")
    void identityKey_SetOnInsertAndKeptThroughEdits() {
        String tripKey = trip.getIdentityKey();
        assertThat(tripKey).isNotBlank();
        assertThat(skiing.getIdentityKey()).isNotBlank().isNotEqualTo(tripKey);

        itineraryService.updateItinerary(trip.getId(), sameTripIn(thailand));

        Itinerary edited = itineraries.findById(trip.getId()).orElseThrow();
        assertThat(edited.getIdentityKey()).isEqualTo(tripKey);
        assertThat(itineraryService.findRevision(edited)).contains(edited.currentRevision());
    }

    @Test
    @DisplayName("Should ask the model once when two edits queue up, and keep the verdict judged on the latest details")
    void twoQueuedEdits_LatestDetailsJudgedOnce() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch lastJobDone = new CountDownLatch(1);
        AtomicInteger latestTripCalls = new AtomicInteger();

        doAnswer(invocation -> {
            String prompt = invocation.getArgument(0);
            if (prompt.contains("Activity: Keeps the queue busy")) {
                release.await(10, TimeUnit.SECONDS);
            }
            if (prompt.contains("Trip: Second name")) {
                latestTripCalls.incrementAndGet();
            }
            if (prompt.contains("Activity: Queued last")) {
                lastJobDone.countDown();
            }
            return affected(invocation, NEW_REASON);
        }).when(ai).ask(anyString(), any(Class.class));

        // a separate trip, so the edited trip's runs never judge the activity queued last
        Itinerary otherTrip = new Itinerary();
        otherTrip.setUser(trip.getUser());
        otherTrip.setCountry(japan);
        otherTrip.setName("Other trip");
        otherTrip = itineraries.saveAndFlush(otherTrip);

        try {
            // the evaluation thread is busy, so both edits queue behind it
            activityService.addActivity(newActivity("Keeps the queue busy", otherTrip));

            Itinerary firstEdit = sameTripIn(japan);
            firstEdit.setName("First name");
            itineraryService.updateItinerary(trip.getId(), firstEdit);

            Itinerary secondEdit = sameTripIn(japan);
            secondEdit.setName("Second name");
            itineraryService.updateItinerary(trip.getId(), secondEdit);

            // runs only once both edits' runs are done
            activityService.addActivity(newActivity("Queued last", otherTrip));
        } finally {
            release.countDown();
        }

        assertThat(lastJobDone.await(REJUDGED_WITHIN.toSeconds(), TimeUnit.SECONDS)).isTrue();
        // the first run judged the latest details, the second reused that verdict instead of
        // deleting it and alerting the traveller again
        assertThat(latestTripCalls.get()).isEqualTo(1);
        assertThat(judgedAgain(null, japanNews)).isTrue();
    }

    // ===== WORK THAT DEPENDS ON A VERDICT =====

    @Test
    @DisplayName("Should never save advice written for the old details when the activity is edited while the advice is being written")
    void editDuringAdvice_DraftIsNeverSaved() {
        savedAlertedVerdict(skiing, japanNews);
        Thread generating = Thread.currentThread();

        doAnswer(invocation -> {
            boolean thisTestsAdvice = isAdviceCall(invocation) && Thread.currentThread() == generating;
            if (!thisTestsAdvice) {
                return affected(invocation, NEW_REASON);
            }

            // the traveller moves the activity while the model is still writing the advice
            activityService.updateActivity(skiing.getId(), skiingMovedTo("Furano, Hokkaido"));
            // and the new details are judged before the advice comes back. sqlite gives the new
            // verdict the deleted one's id, so saving by id alone would attach the old advice to it
            await().atMost(REJUDGED_WITHIN).until(() -> judgedAgain(skiing, japanNews));
            return advice(invocation, "Advice about the old plans");
        }).when(ai).ask(anyString(), any(Class.class));

        List<Recommendation> made = recommendationService.generate(trip.getId());

        assertThat(made).isEmpty();
        assertThat(adviceLeftOnTrip()).isZero();
    }

    @Test
    @DisplayName("Should remove the alert and its unread count, and tell the open stream, when an edit makes the activity unaffected")
    void editToUnaffected_AlertGoesAndStreamIsTold() {
        savedAlertedVerdict(skiing, japanNews);
        Long travellerId = trip.getUser().getId();
        assertThat(alertService.list(travellerId, false, null, 20).unreadCount()).isEqualTo(1);

        String unaffected = "The new place is not affected";
        doAnswer(invocation -> verdict(invocation, false, unaffected))
                .when(ai).ask(anyString(), any(Class.class));

        activityService.updateActivity(skiing.getId(), skiingMovedTo("Naha, Okinawa"));

        // after commit and on the edit's own thread, so it has been told by the time the call returns.
        // no new alert follows an unaffected verdict, so this is what makes the browser reload
        verify(alertStream).onRemoved(new AlertsRemovedEvent(travellerId));

        await().atMost(REJUDGED_WITHIN).until(() -> judgedAgainAs(skiing, japanNews, unaffected));
        AlertService.AlertPage page = alertService.list(travellerId, false, null, 20);
        assertThat(page.content()).isEmpty();
        assertThat(page.unreadCount()).isZero();
    }
}
