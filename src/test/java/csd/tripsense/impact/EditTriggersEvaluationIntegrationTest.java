package csd.tripsense.impact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import csd.tripsense.activity.Activity;
import csd.tripsense.activity.ActivityRepository;
import csd.tripsense.activity.ActivityService;
import csd.tripsense.alert.Alert;
import csd.tripsense.alert.AlertRepository;
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
    @Autowired private UserRepository users;
    @Autowired private CountryRepository countries;
    @Autowired private ItineraryRepository itineraries;
    @Autowired private ActivityRepository activities;
    @Autowired private NewsRepository news;
    @Autowired private ImpactRepository impacts;
    @Autowired private AlertRepository alerts;
    @Autowired private RecommendationRepository recommendations;

    @MockitoBean
    private ChatService ai;

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
        when(ai.ask(anyString(), any(Class.class))).thenAnswer(invocation -> {
            Class<?> verdictType = invocation.getArgument(1);
            var constructor = verdictType.getDeclaredConstructor(boolean.class, String.class);
            constructor.setAccessible(true);
            return constructor.newInstance(true, NEW_REASON);
        });
    }

    private Country savedCountry(String name) {
        Country country = new Country();
        country.setName(name + " " + UUID.randomUUID().toString().substring(0, 8));
        return countries.saveAndFlush(country);
    }

    // published just now, so it is inside the lookback window the re-evaluation reads
    private News savedNews(String title, Country about) {
        News article = new News();
        article.setTitle(title);
        article.setUrl("https://example.com/" + UUID.randomUUID());
        article.setPublishedAt(LocalDateTime.now(ZoneOffset.UTC));
        article.setCountries(Set.of(about));
        return news.saveAndFlush(article);
    }

    // a verdict as the evaluator would have stored it before the edit, with an alert and advice
    // hanging off it. the re-evaluation runs straight away (the model is mocked) and sqlite hands
    // freed ids out again, so the tests tell the old verdicts apart by this reason, not by id
    private Impact savedOldVerdict(Activity activity, News article) {
        Impact impact = new Impact();
        impact.setUser(trip.getUser());
        impact.setItinerary(trip);
        impact.setActivity(activity);
        impact.setNews(article);
        impact.setImpacted(true);
        impact.setReason(OLD_REASON);
        impact = impacts.saveAndFlush(impact);

        alerts.saveAndFlush(new Alert(impact));

        Recommendation advice = new Recommendation();
        advice.setImpact(impact);
        advice.setSeverity(Severity.HIGH);
        advice.setAction(Action.MONITOR);
        advice.setStatus(RecommendationStatus.PENDING);
        advice.setCreatedAt(LocalDateTime.now());
        recommendations.saveAndFlush(advice);
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
        Optional<Impact> stored;
        if (activity == null) {
            stored = impacts.findFirstByNewsIdAndItineraryIdAndActivityIsNull(article.getId(), trip.getId());
        } else {
            stored = impacts.findFirstByNewsIdAndActivityId(article.getId(), activity.getId());
        }
        return stored.filter(impact -> NEW_REASON.equals(impact.getReason())).isPresent();
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
}
