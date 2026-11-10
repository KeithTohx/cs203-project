package csd.tripsense.impact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

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

// the edit page saves the trip and then every activity at once. with the default pool of several
// connections, sqlite used to fail all but one of those saves with SQLITE_BUSY, which rolled back
// their verdict clearing and left the old impacts and advice on the trip.
// the other integration tests run on a single connection, so they never see this
@SpringBootTest(properties = {
        "gdelt.ingest.enabled=false",
        "impact.eval.enabled=false",
        "impact.eval.new-itinerary.enabled=false",
        "spring.sql.init.mode=never",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "alerts.reconcile.initial-delay-ms=86400000",
        "alerts.email.enabled=false"
})
@DisplayName("Parallel Edits Integration Tests")
class ParallelEditsIntegrationTest {

    private static final Path DATABASE = temporaryDatabase();
    private static final int ACTIVITY_COUNT = 4;
    private static final int ARTICLE_COUNT = 3;

    @Autowired private ItineraryService itineraryService;
    @Autowired private ActivityService activityService;
    @Autowired private UserRepository users;
    @Autowired private CountryRepository countries;
    @Autowired private ItineraryRepository itineraries;
    @Autowired private ActivityRepository activities;
    @Autowired private NewsRepository news;
    @Autowired private ImpactRepository impacts;
    @Autowired private RecommendationRepository recommendations;

    @MockitoBean
    private ChatService ai;

    private static Path temporaryDatabase() {
        try {
            return Files.createTempDirectory("tripsense-parallel-").resolve("test.db");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // a real file and the default pool size, like the deployed server
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE + "?foreign_keys=on");
    }

    @Test
    @DisplayName("Should clear every old verdict and its advice when the trip and all its activities are saved at once")
    void parallelActivityEdits_ClearEveryOldVerdict() {
        when(ai.ask(anyString(), any(Class.class))).thenThrow(new IllegalStateException("model not needed"));

        Country japan = savedCountry();
        Itinerary trip = savedTrip(japan);
        List<Activity> plans = savedActivities(trip, japan);

        for (int i = 0; i < ARTICLE_COUNT; i++) {
            News article = savedNews(japan);
            savedOldVerdictWithAdvice(trip, null, article);
            for (Activity activity : plans) {
                savedOldVerdictWithAdvice(trip, activity, article);
            }
        }

        itineraryService.updateItinerary(trip.getId(), renamed(trip));

        // the same burst of saves the edit page sends
        List<CompletableFuture<Void>> saves = new ArrayList<>();
        for (Activity activity : plans) {
            saves.add(CompletableFuture.runAsync(
                    () -> activityService.updateActivity(activity.getId(), movedElsewhere(trip, activity))));
        }
        CompletableFuture.allOf(saves.toArray(CompletableFuture[]::new)).join();

        assertThat(impacts.findByItineraryId(trip.getId())).isEmpty();
        assertThat(recommendations.findByImpactItineraryId(trip.getId())).isEmpty();
    }

    // ===== HELPERS =====

    private Country savedCountry() {
        Country country = new Country();
        country.setName("Japan " + UUID.randomUUID().toString().substring(0, 8));
        return countries.saveAndFlush(country);
    }

    private Itinerary savedTrip(Country country) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User traveller = users.saveAndFlush(
                new User("p" + suffix + "@example.com", "p" + suffix, "long-enough-hash", "USER"));

        Itinerary trip = new Itinerary();
        trip.setUser(traveller);
        trip.setCountry(country);
        trip.setName("Ski trip");
        trip.setDateStart(LocalDateTime.now().plusDays(10));
        trip.setDateEnd(LocalDateTime.now().plusDays(15));
        return itineraries.saveAndFlush(trip);
    }

    private List<Activity> savedActivities(Itinerary trip, Country country) {
        List<Activity> saved = new ArrayList<>();
        for (int i = 0; i < ACTIVITY_COUNT; i++) {
            Activity activity = new Activity();
            activity.setItinerary(trip);
            activity.setCountry(country);
            activity.setName("Activity " + i);
            activity.setAddress("Niseko, Hokkaido");
            activity.setDateStart(LocalDateTime.now().plusDays(11));
            saved.add(activities.saveAndFlush(activity));
        }
        return saved;
    }

    private News savedNews(Country about) {
        News article = new News();
        article.setTitle("Story " + UUID.randomUUID());
        article.setUrl("https://example.com/" + UUID.randomUUID());
        article.setPublishedAt(LocalDateTime.now(ZoneOffset.UTC));
        article.setCountries(Set.of(about));
        return news.saveAndFlush(article);
    }

    private void savedOldVerdictWithAdvice(Itinerary trip, Activity activity, News article) {
        Impact impact = new Impact();
        impact.setUser(trip.getUser());
        impact.setItinerary(trip);
        impact.setActivity(activity);
        impact.setNews(article);
        impact.setImpacted(true);
        impact.setReason("Judged on the details before the edit");
        impact.setVerdictKey(UUID.randomUUID().toString());
        impact = impacts.saveAndFlush(impact);

        Recommendation advice = new Recommendation();
        advice.setImpact(impact);
        advice.setSeverity(Severity.HIGH);
        advice.setAction(Action.MONITOR);
        advice.setStatus(RecommendationStatus.PENDING);
        advice.setCreatedAt(LocalDateTime.now());
        recommendations.saveAndFlush(advice);
    }

    // the request body only carries the country's id, like the edit page sends it
    private static Itinerary renamed(Itinerary trip) {
        Country sameCountry = new Country();
        sameCountry.setId(trip.getCountry().getId());

        Itinerary changes = new Itinerary();
        changes.setCountry(sameCountry);
        changes.setName("Hokkaido powder week");
        changes.setDateStart(trip.getDateStart());
        changes.setDateEnd(trip.getDateEnd());
        return changes;
    }

    private static Activity movedElsewhere(Itinerary trip, Activity activity) {
        Itinerary sameTrip = new Itinerary();
        sameTrip.setId(trip.getId());

        Activity changes = new Activity();
        changes.setItinerary(sameTrip);
        changes.setName(activity.getName());
        changes.setAddress("Furano, Hokkaido");
        changes.setDateStart(activity.getDateStart());
        return changes;
    }
}
