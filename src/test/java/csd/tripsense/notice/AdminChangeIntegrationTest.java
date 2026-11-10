package csd.tripsense.notice;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import csd.tripsense.auth.CurrentUser;
import csd.tripsense.country.Country;
import csd.tripsense.country.CountryRepository;
import csd.tripsense.itinerary.ItineraryRepository;
import csd.tripsense.user.User;
import csd.tripsense.user.UserRepository;
import io.restassured.http.ContentType;
import io.restassured.module.mockmvc.RestAssuredMockMvc;

// the traveller's side of admin changes, with the real security filters and database, so the
// mark read query runs against sqlite and each user only ever sees their own
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:sqlite::memory:",
    "spring.datasource.hikari.maximum-pool-size=1",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.sql.init.mode=never",
    "gdelt.ingest.enabled=false",
    "impact.eval.enabled=false",
    "impact.eval.new-itinerary.enabled=false"
})
@DisplayName("Admin change notices against a real database")
class AdminChangeIntegrationTest {

    private static final String PATH = "/api/user/me/admin-changes";

    @Autowired private MockMvc mockMvc;
    @Autowired private AdminChangeService adminChanges;
    @Autowired private AdminChangeRepository changeRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ItineraryRepository itineraryRepository;
    @Autowired private CountryRepository countryRepository;

    private User traveller;
    private User stranger;

    @BeforeEach
    void setUp() {
        RestAssuredMockMvc.mockMvc(mockMvc);
        changeRepository.deleteAll();
        itineraryRepository.deleteAll();
        userRepository.deleteAll();

        traveller = userRepository.saveAndFlush(new User("traveller@example.com", "traveller", "placeholder-password-hash", "USER"));
        stranger = userRepository.saveAndFlush(new User("stranger@example.com", "stranger", "placeholder-password-hash", "USER"));
    }

    private static CurrentUser loggedInAs(User account) {
        return new CurrentUser(account.getId(), account.getEmail(), account.getUsername(), null,
                account.getRole(), account.isEnabled());
    }

    @Test
    @DisplayName("Should 401 when not logged in")
    void list_NotLoggedIn_Returns401() {
        given()
        .when()
            .get(PATH)
        .then()
            .status(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Should list only the logged in user's own changes, newest first")
    void list_ShowsOnlyOwnChanges() {
        adminChanges.record(traveller.getId(), 99L, "An admin added the trip 'Japan'.");
        adminChanges.record(traveller.getId(), 99L, "An admin edited your trip 'Japan'.");
        adminChanges.record(stranger.getId(), 99L, "An admin deleted your trip 'Paris'.");

        given()
            .postProcessors(user(loggedInAs(traveller)))
        .when()
            .get(PATH)
        .then()
            .status(HttpStatus.OK)
            .body("unreadCount", equalTo(2))
            .body("content.size()", equalTo(2))
            .body("content[0].message", equalTo("An admin edited your trip 'Japan'."))
            .body("content[0].readAt", nullValue());
    }

    @Test
    @DisplayName("Should mark changes read only up to the one given, and leave other users alone")
    void markRead_UpToId_LeavesNewerOnesUnread() {
        adminChanges.record(traveller.getId(), 99L, "first");
        adminChanges.record(traveller.getId(), 99L, "second");
        adminChanges.record(stranger.getId(), 99L, "not theirs");
        Long firstId = changeRepository.findTop20ByUserIdOrderByIdDesc(traveller.getId()).get(1).getId();

        given()
            .postProcessors(user(loggedInAs(traveller)), csrf())
            .queryParam("upToId", firstId)
        .when()
            .patch(PATH)
        .then()
            .status(HttpStatus.OK)
            .body("unreadCount", equalTo(1));

        // without upToId every one goes
        given()
            .postProcessors(user(loggedInAs(traveller)), csrf())
        .when()
            .patch(PATH)
        .then()
            .status(HttpStatus.OK)
            .body("unreadCount", equalTo(0));

        long strangersUnread = changeRepository.countByUserIdAndReadAtIsNull(stranger.getId());
        org.assertj.core.api.Assertions.assertThat(strangersUnread).isEqualTo(1L);
    }

    @Test
    @DisplayName("Should not let a traveller mark their own trip as changed by an admin")
    void addItinerary_AdminEditedAtInBody_IsIgnored() {
        Country japan = new Country();
        japan.setName("Japan");
        japan = countryRepository.saveAndFlush(japan);

        given()
            .postProcessors(user(loggedInAs(traveller)), csrf())
            .contentType(ContentType.JSON)
            .body("""
                {
                    "name": "Japan Ski Trip",
                    "country": { "id": %d },
                    "adminEditedAt": "2026-01-01T00:00:00Z"
                }
                """.formatted(japan.getId()))
        .when()
            .post("/api/itinerary")
        .then()
            .status(HttpStatus.OK)
            .body("adminEditedAt", nullValue());
    }

    @Test
    @DisplayName("Should mark one change read, and not someone else's")
    void markOneRead_OnlyTheirOwn() {
        adminChanges.record(traveller.getId(), 99L, "first");
        adminChanges.record(traveller.getId(), 99L, "second");
        adminChanges.record(stranger.getId(), 99L, "not theirs");
        Long firstId = changeRepository.findTop20ByUserIdOrderByIdDesc(traveller.getId()).get(1).getId();
        Long strangersId = changeRepository.findTop20ByUserIdOrderByIdDesc(stranger.getId()).get(0).getId();

        given()
            .postProcessors(user(loggedInAs(traveller)), csrf())
        .when()
            .patch(PATH + "/" + firstId)
        .then()
            .status(HttpStatus.OK)
            .body("unreadCount", equalTo(1));

        // the stranger's stays unread
        given()
            .postProcessors(user(loggedInAs(traveller)), csrf())
        .when()
            .patch(PATH + "/" + strangersId)
        .then()
            .status(HttpStatus.OK);

        long strangersUnread = changeRepository.countByUserIdAndReadAtIsNull(stranger.getId());
        org.assertj.core.api.Assertions.assertThat(strangersUnread).isEqualTo(1L);
    }

    @Test
    @DisplayName("Should say which trip and news a change was about, and only link a trip that is still theirs")
    void list_CarriesTripAndNews() {
        Country japan = new Country();
        japan.setName("Japan");
        japan = countryRepository.saveAndFlush(japan);

        csd.tripsense.itinerary.Itinerary trip = new csd.tripsense.itinerary.Itinerary();
        trip.setUser(traveller);
        trip.setCountry(japan);
        trip.setName("Japan Ski Trip");
        trip = itineraryRepository.saveAndFlush(trip);

        csd.tripsense.itinerary.Itinerary strangersTrip = new csd.tripsense.itinerary.Itinerary();
        strangersTrip.setUser(stranger);
        strangersTrip.setCountry(japan);
        strangersTrip.setName("Paris");
        strangersTrip = itineraryRepository.saveAndFlush(strangersTrip);

        // pointing at someone else's trip, like a reused id after the traveller's trip was deleted
        adminChanges.record(traveller.getId(), 99L, "An admin deleted your trip 'Old trip'.",
                AdminChangeTarget.trip(strangersTrip.getId(), "Old trip"));
        adminChanges.record(traveller.getId(), 99L, "An admin removed the news 'Quake' from your trip 'Japan Ski Trip'.",
                AdminChangeTarget.trip(trip.getId(), "Japan Ski Trip").withNews(7L, "Quake"));

        given()
            .postProcessors(user(loggedInAs(traveller)))
        .when()
            .get(PATH)
        .then()
            .status(HttpStatus.OK)
            .body("content[0].itineraryId", equalTo(trip.getId().intValue()))
            .body("content[0].itineraryName", equalTo("Japan Ski Trip"))
            .body("content[0].itineraryExists", equalTo(true))
            .body("content[0].newsTitle", equalTo("Quake"))
            .body("content[1].itineraryName", equalTo("Old trip"))
            .body("content[1].itineraryExists", equalTo(false));
    }
}
