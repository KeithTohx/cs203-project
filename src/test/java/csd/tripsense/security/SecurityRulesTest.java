package csd.tripsense.security;

import static io.restassured.module.mockmvc.RestAssuredMockMvc.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import csd.tripsense.auth.CurrentUser;
import csd.tripsense.country.Country;
import csd.tripsense.country.CountryRepository;
import csd.tripsense.itinerary.Itinerary;
import csd.tripsense.itinerary.ItineraryRepository;
import csd.tripsense.user.User;
import csd.tripsense.user.UserRepository;
import io.restassured.http.ContentType;
import io.restassured.module.mockmvc.RestAssuredMockMvc;

// integration tests for the security rules in SecurityConfig, using REST Assured without a real server.
// the whole app runs with its real security filters, and MockMvc is the bridge REST Assured sends
// requests through. checks who may reach what: logged out, a user, an admin, and the csrf token
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:sqlite::memory:",
    "spring.datasource.hikari.maximum-pool-size=1",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.sql.init.mode=never",
    "gdelt.ingest.enabled=false",
    "impact.eval.enabled=false"
})
@DisplayName("Security Rules Integration Tests")
class SecurityRulesTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ItineraryRepository itineraryRepository;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        // Configure REST Assured to use MockMvc
        RestAssuredMockMvc.mockMvc(mockMvc);

        // Clean database before each test, itineraries first since they point at users
        itineraryRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ---------------------------------------------------------------- helpers

    private User savedAccount(String name, String role, boolean enabled) {
        User account = new User(name + "@example.com", name, passwordEncoder.encode(PASSWORD), role);
        account.setEnabled(enabled);
        return userRepository.saveAndFlush(account);
    }

    // the same principal the real login puts in the session, so controllers get a CurrentUser
    private CurrentUser loggedInAs(User account) {
        return new CurrentUser(account.getId(), account.getEmail(), account.getUsername(), null,
                account.getRole(), account.isEnabled());
    }

    private Itinerary savedTrip(User owner, String name) {
        Country japan = new Country();
        japan.setName("Japan");
        japan = countryRepository.saveAndFlush(japan);

        Itinerary trip = new Itinerary();
        trip.setName(name);
        trip.setCountry(japan);
        trip.setUser(owner);
        return itineraryRepository.saveAndFlush(trip);
    }

    // ===== NOT LOGGED IN =====

    @Test
    @DisplayName("Should return 401, not a redirect to a login page, when not logged in")
    void protectedEndpoint_ShouldReturn401_WhenNotLoggedIn() {
        given()
        .when()
            .get("/api/itinerary")
        .then()
            .status(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Should let anyone open the API docs without logging in")
    void apiDocs_ShouldBePublic() {
        given()
        .when()
            .get("/v3/api-docs")
        .then()
            .status(HttpStatus.OK);
    }

    @Test
    @DisplayName("Should let anyone register without logging in")
    void register_ShouldBePublic() {
        given()
            .postProcessors(csrf())
            .contentType(ContentType.JSON)
            .body("""
                {
                    "username": "newtraveller",
                    "email": "newtraveller@example.com",
                    "password": "%s"
                }
                """.formatted(PASSWORD))
        .when()
            .post("/api/user/register")
        .then()
            .status(HttpStatus.CREATED)
            .body("username", equalTo("newtraveller"))
            .body("role", equalTo("USER"));
    }

    // ===== ROLES =====

    @Test
    @DisplayName("Should return 403 when a normal user opens the admin endpoints")
    @WithMockUser(roles = "USER")
    void adminEndpoint_ShouldReturn403_WhenUser() {
        given()
        .when()
            .get("/api/admin/users")
        .then()
            .status(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Should return 403 when a normal user opens the activity log")
    @WithMockUser(roles = "USER")
    void auditLog_ShouldReturn403_WhenUser() {
        given()
        .when()
            .get("/api/admin/audit")
        .then()
            .status(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Should let an admin open the admin endpoints")
    @WithMockUser(roles = "ADMIN")
    void adminEndpoint_ShouldReturn200_WhenAdmin() {
        given()
        .when()
            .get("/api/admin/users")
        .then()
            .status(HttpStatus.OK)
            .body("totalElements", notNullValue());
    }

    @Test
    @DisplayName("Should never let registration create an admin, even if the request asks for one")
    void register_ShouldIgnoreRequestedRole() {
        given()
            .postProcessors(csrf())
            .contentType(ContentType.JSON)
            .body("""
                {
                    "username": "sneaky",
                    "email": "sneaky@example.com",
                    "password": "%s",
                    "role": "ADMIN"
                }
                """.formatted(PASSWORD))
        .when()
            .post("/api/user/register")
        .then()
            .status(HttpStatus.CREATED)
            .body("role", equalTo("USER"));
    }

    // ===== CSRF =====

    @Test
    @DisplayName("Should return 403 when a logged in user sends a change without the csrf token")
    void post_ShouldReturn403_WhenCsrfTokenMissing() {
        User account = savedAccount("traveller", "USER", true);

        given()
            .postProcessors(user(loggedInAs(account)))
            .contentType(ContentType.JSON)
            .body("{\"name\": \"Japan Ski Trip\"}")
        .when()
            .post("/api/itinerary")
        .then()
            .status(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Should accept the same change once the csrf token is sent")
    void post_ShouldSucceed_WhenCsrfTokenSent() {
        User account = savedAccount("traveller", "USER", true);
        Country japan = new Country();
        japan.setName("Japan");
        japan = countryRepository.saveAndFlush(japan);

        given()
            .postProcessors(user(loggedInAs(account)), csrf())
            .contentType(ContentType.JSON)
            .body("""
                {
                    "name": "Japan Ski Trip",
                    "country": { "id": %d }
                }
                """.formatted(japan.getId()))
        .when()
            .post("/api/itinerary")
        .then()
            .status(HttpStatus.OK)
            .body("name", equalTo("Japan Ski Trip"));
    }

    @Test
    @DisplayName("Should return 403 when registering without the csrf token")
    void register_ShouldReturn403_WhenCsrfTokenMissing() {
        given()
            .contentType(ContentType.JSON)
            .body("{\"username\": \"newtraveller\", \"email\": \"newtraveller@example.com\", \"password\": \"%s\"}"
                    .formatted(PASSWORD))
        .when()
            .post("/api/user/register")
        .then()
            .status(HttpStatus.FORBIDDEN);
    }

    // ===== OWNERSHIP =====
    // another user's trip gives 404, not 403, so nobody can find out which trip ids exist

    @Test
    @DisplayName("Should return 404 when a user opens another user's itinerary")
    void getItinerary_ShouldReturn404_WhenItineraryBelongsToAnotherUser() {
        User owner = savedAccount("owner", "USER", true);
        User stranger = savedAccount("stranger", "USER", true);
        Itinerary ownersTrip = savedTrip(owner, "Japan Ski Trip");

        given()
            .postProcessors(user(loggedInAs(stranger)))
        .when()
            .get("/api/itinerary/" + ownersTrip.getId())
        .then()
            .status(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Should return 404 and leave the itinerary unchanged when a user edits another user's itinerary")
    void updateItinerary_ShouldReturn404AndNotChange_WhenItineraryBelongsToAnotherUser() {
        User owner = savedAccount("owner", "USER", true);
        User stranger = savedAccount("stranger", "USER", true);
        Itinerary ownersTrip = savedTrip(owner, "Japan Ski Trip");

        given()
            .postProcessors(user(loggedInAs(stranger)), csrf())
            .contentType(ContentType.JSON)
            .body("{\"name\": \"Taken over\"}")
        .when()
            .put("/api/itinerary/" + ownersTrip.getId())
        .then()
            .status(HttpStatus.NOT_FOUND);

        String nameAfter = itineraryRepository.findById(ownersTrip.getId()).orElseThrow().getName();
        assertEquals("Japan Ski Trip", nameAfter);
    }

    @Test
    @DisplayName("Should return 404 and keep the itinerary when a user deletes another user's itinerary")
    void deleteItinerary_ShouldReturn404AndKeepIt_WhenItineraryBelongsToAnotherUser() {
        User owner = savedAccount("owner", "USER", true);
        User stranger = savedAccount("stranger", "USER", true);
        Itinerary ownersTrip = savedTrip(owner, "Japan Ski Trip");

        given()
            .postProcessors(user(loggedInAs(stranger)), csrf())
        .when()
            .delete("/api/itinerary/" + ownersTrip.getId())
        .then()
            .status(HttpStatus.NOT_FOUND);

        assertTrue(itineraryRepository.existsById(ownersTrip.getId()));
    }

    @Test
    @DisplayName("Should return 404 when a user lists the activities of another user's itinerary")
    void getActivities_ShouldReturn404_WhenItineraryBelongsToAnotherUser() {
        User owner = savedAccount("owner", "USER", true);
        User stranger = savedAccount("stranger", "USER", true);
        Itinerary ownersTrip = savedTrip(owner, "Japan Ski Trip");

        given()
            .postProcessors(user(loggedInAs(stranger)))
        .when()
            .get("/api/itinerary/" + ownersTrip.getId() + "/activity")
        .then()
            .status(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Should only list the user's own itineraries")
    void getAllItineraries_ShouldListOnlyOwnTrips_WhenOtherUsersHaveTrips() {
        User owner = savedAccount("owner", "USER", true);
        User stranger = savedAccount("stranger", "USER", true);
        savedTrip(owner, "Japan Ski Trip");
        savedTrip(stranger, "Bali Beach Trip");

        given()
            .postProcessors(user(loggedInAs(stranger)))
        .when()
            .get("/api/itinerary")
        .then()
            .status(HttpStatus.OK)
            .body("size()", equalTo(1))
            .body("[0].name", equalTo("Bali Beach Trip"));
    }

    @Test
    @DisplayName("Should return 404 even for an admin, since trips are private to their owner")
    void getItinerary_ShouldReturn404_WhenAdminOpensAnotherUsersItinerary() {
        User owner = savedAccount("owner", "USER", true);
        User admin = savedAccount("manager", "ADMIN", true);
        Itinerary ownersTrip = savedTrip(owner, "Japan Ski Trip");

        given()
            .postProcessors(user(loggedInAs(admin)))
        .when()
            .get("/api/itinerary/" + ownersTrip.getId())
        .then()
            .status(HttpStatus.NOT_FOUND);
    }

    // ===== LOGIN AND LOGOUT =====

    @Test
    @DisplayName("Should log in with the right password and say which role the account has")
    void login_ShouldReturn200AndRole_WhenPasswordCorrect() {
        savedAccount("traveller", "USER", true);

        given()
            .postProcessors(csrf())
            .contentType(ContentType.URLENC)
            .formParam("email", "traveller@example.com")
            .formParam("password", PASSWORD)
        .when()
            .post("/api/auth/login")
        .then()
            .status(HttpStatus.OK)
            .body("role", equalTo("USER"));
    }

    @Test
    @DisplayName("Should return 401 with a general message when the password is wrong")
    void login_ShouldReturn401_WhenPasswordWrong() {
        savedAccount("traveller", "USER", true);

        given()
            .postProcessors(csrf())
            .contentType(ContentType.URLENC)
            .formParam("email", "traveller@example.com")
            .formParam("password", "not-the-password")
        .when()
            .post("/api/auth/login")
        .then()
            .status(HttpStatus.UNAUTHORIZED)
            .body("message", equalTo("Invalid email or password"));
    }

    @Test
    @DisplayName("Should give the same 401 for an email nobody has, so accounts cannot be guessed")
    void login_ShouldReturnSame401_WhenEmailUnknown() {
        given()
            .postProcessors(csrf())
            .contentType(ContentType.URLENC)
            .formParam("email", "nobody@example.com")
            .formParam("password", PASSWORD)
        .when()
            .post("/api/auth/login")
        .then()
            .status(HttpStatus.UNAUTHORIZED)
            .body("message", equalTo("Invalid email or password"));
    }

    @Test
    @DisplayName("Should refuse a disabled account even with the right password, with the same message")
    void login_ShouldReturn401_WhenAccountDisabled() {
        savedAccount("suspended", "USER", false);

        given()
            .postProcessors(csrf())
            .contentType(ContentType.URLENC)
            .formParam("email", "suspended@example.com")
            .formParam("password", PASSWORD)
        .when()
            .post("/api/auth/login")
        .then()
            .status(HttpStatus.UNAUTHORIZED)
            .body("message", equalTo("Invalid email or password"));
    }

    @Test
    @DisplayName("Should return 204 on logout")
    void logout_ShouldReturn204() {
        User account = savedAccount("traveller", "USER", true);

        given()
            .postProcessors(user(loggedInAs(account)), csrf())
        .when()
            .post("/api/auth/logout")
        .then()
            .status(HttpStatus.NO_CONTENT);
    }
}
