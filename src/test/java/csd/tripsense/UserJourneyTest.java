package csd.tripsense;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import csd.tripsense.activity.Activity;
import csd.tripsense.activity.ActivityCategory;
import csd.tripsense.activity.ActivityRepository;
import csd.tripsense.chat.ChatService;
import csd.tripsense.country.Country;
import csd.tripsense.country.CountryRepository;
import csd.tripsense.impact.Impact;
import csd.tripsense.impact.ImpactRepository;
import csd.tripsense.itinerary.Itinerary;
import csd.tripsense.itinerary.ItineraryRepository;
import csd.tripsense.news.News;
import csd.tripsense.news.NewsRepository;
import csd.tripsense.recommendation.ActivityOption;
import csd.tripsense.recommendation.ActivityOptionRepository;
import csd.tripsense.user.User;
import csd.tripsense.user.UserRepository;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

// end to end test of one traveller's journey, against a real server with real http.
// the test acts like the browser: it keeps the session and csrf cookies between requests and sends
// the csrf token back as a header, like the frontend does. only the ai model is mocked, since a real
// model answers differently each time and needs ollama running
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:sqlite::memory:",
    "spring.datasource.hikari.maximum-pool-size=1",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.sql.init.mode=never",
    "gdelt.ingest.enabled=false",
    "impact.eval.enabled=false",
    "logging.level.org.springframework.security=WARN"
})
@DisplayName("User Journey Real Server Integration Test")
class UserJourneyTest {

    private static final String PASSWORD = "correct-horse-battery";

    @LocalServerPort
    private int port;

    @MockitoBean
    private ChatService chatService;

    @Autowired private UserRepository userRepository;
    @Autowired private CountryRepository countryRepository;
    @Autowired private ItineraryRepository itineraryRepository;
    @Autowired private ActivityRepository activityRepository;
    @Autowired private NewsRepository newsRepository;
    @Autowired private ImpactRepository impactRepository;
    @Autowired private ActivityOptionRepository optionRepository;

    // what a browser would be holding: the session cookie and the csrf cookie
    private final Map<String, String> cookies = new HashMap<>();

    private Country japan;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();

        japan = new Country();
        japan.setName("Japan");
        japan = countryRepository.saveAndFlush(japan);
    }

    // ---------------------------------------------------------------- helpers

    // every request carries the cookies, and the csrf cookie's value as a header, like the frontend's api.js
    private RequestSpecification browser() {
        RequestSpecification request = given().cookies(cookies);
        String csrfToken = cookies.get("XSRF-TOKEN");
        if (csrfToken != null) request.header("X-XSRF-TOKEN", csrfToken);
        return request;
    }

    // keeps whatever cookies the server set or cleared, so the next request sends the latest ones
    private Response remember(Response response) {
        response.getDetailedCookies().forEach(cookie -> {
            boolean cleared = cookie.getValue() == null || cookie.getValue().isEmpty() || cookie.getMaxAge() == 0;
            if (cleared) cookies.remove(cookie.getName());
            else cookies.put(cookie.getName(), cookie.getValue());
        });
        return response;
    }

    // the news fetch is switched off in tests, so this stands in for it finding a story that hits the ski day
    private void systemDetectsEarthquakeAffecting(Long itineraryId, Long activityId) {
        User traveller = userRepository.findByEmailIgnoreCase("traveller@example.com").orElseThrow();
        Itinerary itinerary = itineraryRepository.findById(itineraryId).orElseThrow();
        Activity skiDay = activityRepository.findById(activityId).orElseThrow();

        News earthquake = new News();
        earthquake.setTitle("Earthquake near Hokkaido");
        earthquake.setDescription("Ski resorts are suspending lifts while they are inspected.");
        earthquake.setUrl("https://example.com/news/hokkaido-earthquake");
        earthquake.setPublishedAt(LocalDateTime.of(2026, 12, 14, 8, 30));
        earthquake = newsRepository.saveAndFlush(earthquake);

        Impact impact = new Impact();
        impact.setUser(traveller);
        impact.setItinerary(itinerary);
        impact.setActivity(skiDay);
        impact.setNews(earthquake);
        impact.setImpacted(true);
        impact.setReason("Lifts may stay shut after the earthquake");
        impactRepository.saveAndFlush(impact);
    }

    // the model's answer is a private record in the service, so it is built by reflection from the type asked for
    private void givenModelRecommends(Long optionId) {
        when(chatService.ask(anyString(), any(Class.class))).thenAnswer(invocation -> {
            Class<?> type = invocation.getArgument(1);
            var constructor = type.getDeclaredConstructor(String.class, String.class, Long.class, String.class);
            constructor.setAccessible(true);
            return constructor.newInstance("HIGH", "REPLACE", optionId,
                    "Lifts may stay shut, so spend the day at the art museum in Sapporo.");
        });
    }

    // ===== THE JOURNEY =====

    @Test
    @DisplayName("Should take a traveller from sign up to a recommendation for a disrupted activity")
    void traveller_ShouldGetRecommendation_FromSignUpToLogout() {
        // 1. opening the site hands out the csrf cookie
        remember(browser().when().get("/").then().statusCode(200).extract().response());
        assertNotNull(cookies.get("XSRF-TOKEN"), "the first page load should set the csrf cookie");

        // 2. sign up
        remember(browser()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "username": "traveller",
                    "email": "traveller@example.com",
                    "password": "%s"
                }
                """.formatted(PASSWORD))
        .when()
            .post("/api/user/register")
        .then()
            .statusCode(201)
            .body("password", equalTo(null))
            .extract().response());

        // 3. log in with the real password, which starts a session
        remember(browser()
            .contentType(ContentType.URLENC)
            .formParam("email", "traveller@example.com")
            .formParam("password", PASSWORD)
        .when()
            .post("/api/auth/login")
        .then()
            .statusCode(200)
            .body("role", equalTo("USER"))
            .extract().response());
        assertNotNull(cookies.get("JSESSIONID"), "logging in should start a session");

        browser().when().get("/api/auth/me").then()
            .statusCode(200)
            .body("username", equalTo("traveller"));

        // 4. plan a trip with one activity
        Response createdTrip = remember(browser()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "name": "Japan Ski Trip",
                    "country": { "id": %d },
                    "dateStart": "2026-12-15T00:00:00",
                    "dateEnd": "2026-12-22T00:00:00"
                }
                """.formatted(japan.getId()))
        .when()
            .post("/api/itinerary")
        .then()
            .statusCode(200)
            .body("id", notNullValue())
            .extract().response());
        Long itineraryId = createdTrip.jsonPath().getLong("id");

        Response createdActivity = remember(browser()
            .contentType(ContentType.JSON)
            .body("""
                {
                    "name": "Ski at Niseko",
                    "address": "Niseko, Hokkaido, Japan",
                    "category": "SKI_SNOW",
                    "dateStart": "2026-12-16T09:00:00",
                    "dateEnd": "2026-12-16T16:00:00",
                    "itinerary": { "id": %d }
                }
                """.formatted(itineraryId))
        .when()
            .post("/api/activity")
        .then()
            .statusCode(200)
            .body("itineraryId", equalTo(itineraryId.intValue()))
            .extract().response());
        Long activityId = createdActivity.jsonPath().getLong("id");

        // 5. the system finds news that hits the ski day, and the traveller sees it on their trip
        systemDetectsEarthquakeAffecting(itineraryId, activityId);

        browser().when().get("/api/itinerary/" + itineraryId + "/impact").then()
            .statusCode(200)
            .body("size()", equalTo(1))
            .body("[0].activityName", equalTo("Ski at Niseko"))
            .body("[0].newsTitle", equalTo("Earthquake near Hokkaido"));

        // 6. ask what to do. the rules shortlist a real place nearby and the (mocked) model picks it
        ActivityOption museum = optionRepository.saveAndFlush(new ActivityOption(501L, "Japan", "Sapporo",
                ActivityCategory.MUSEUM_GALLERY, "Sapporo Art Museum", "Chuo-ku, Sapporo", 43.06, 141.35));
        optionRepository.saveAndFlush(new ActivityOption(503L, "Japan", "Niseko",
                ActivityCategory.SKI_SNOW, "Rusutsu Resort", "Niseko", 42.80, 140.68));
        givenModelRecommends(museum.getId());

        remember(browser()
            .contentType(ContentType.JSON)
            .body("{\"itineraryId\": %d}".formatted(itineraryId))
        .when()
            .post("/api/recommendation/generate")
        .then()
            .statusCode(200)
            .body("size()", equalTo(1))
            .body("[0].severity", equalTo("HIGH"))
            .body("[0].action", equalTo("REPLACE"))
            .body("[0].activityName", equalTo("Ski at Niseko"))
            .body("[0].optionName", equalTo("Sapporo Art Museum"))
            .extract().response());

        // 7. coming back later shows the saved recommendation without asking the model again
        browser().when().get("/api/itinerary/" + itineraryId + "/recommendation").then()
            .statusCode(200)
            .body("size()", equalTo(1))
            .body("[0].optionName", equalTo("Sapporo Art Museum"));
        verify(chatService, times(1)).ask(anyString(), any(Class.class));

        // 8. log out, and the session no longer works
        remember(browser().when().post("/api/auth/logout").then().statusCode(204).extract().response());

        browser().when().get("/api/itinerary").then().statusCode(401);
    }
}
