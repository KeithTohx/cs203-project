package csd.tripsense.security;

import csd.tripsense.activity.*;
import csd.tripsense.admin.*;
import csd.tripsense.auth.CurrentUser;
import csd.tripsense.country.*;
import csd.tripsense.impact.*;
import csd.tripsense.itinerary.*;
import csd.tripsense.news.*;
import csd.tripsense.user.*;
import csd.tripsense.user.preference.*;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {
        "gdelt.ingest.enabled=false", "impact.eval.enabled=false", "spring.sql.init.mode=never",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.datasource.hikari.maximum-pool-size=1",
        "app.bootstrap-admin.email=", "app.bootstrap-admin.username=", "app.bootstrap-admin.password="
})
class AccountSecurityIntegrationTest {
    private static final Path DATABASE = database();
    private static Path database() {
        try { return Files.createTempDirectory("tripsense-security-").resolve("test.db"); }
        catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE + "?foreign_keys=on");
    }

    @Autowired UserRepository users;
    @Autowired UserService userService;
    @Autowired UserPreferenceRepository preferences;
    @Autowired CountryRepository countries;
    @Autowired ItineraryRepository trips;
    @Autowired ActivityRepository activities;
    @Autowired NewsRepository news;
    @Autowired ImpactRepository impacts;
    @Autowired ChatMemoryRepository conversations;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired AdminService admins;
    @Autowired ItineraryService itineraryService;
    @Autowired org.springframework.web.context.WebApplicationContext webContext;
    @Autowired org.springframework.security.web.FilterChainProxy securityFilter;
    @Autowired org.springframework.security.core.session.SessionRegistry sessionRegistry;

    private org.springframework.mock.web.MockHttpSession login(User user) {
        CurrentUser principal = CurrentUser.from(user);
        principal.eraseCredentials();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities()));
        var session = new org.springframework.mock.web.MockHttpSession();
        session.setAttribute(org.springframework.security.web.context.HttpSessionSecurityContextRepository
                .SPRING_SECURITY_CONTEXT_KEY, context);
        sessionRegistry.registerNewSession(session.getId(), principal);
        return session;
    }

    @AfterEach
    void clearContext() { SecurityContextHolder.clearContext(); }

    @Test
    void selfDeletionRemovesPreferencesTripsActivitiesImpactsAndChatWithForeignKeysEnabled() {
        assertThat(jdbc.queryForObject("pragma foreign_keys", Integer.class)).isEqualTo(1);
        Long id = new TransactionTemplate(transactionManager).execute(status -> {
            User user = users.saveAndFlush(new User("delete@example.com", "deleteperson",
                    new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("password123"), "USER"));
            preferences.saveAndFlush(new UserPreference(user));
            Country country = new Country(); country.setName("Test country"); country = countries.saveAndFlush(country);
            Itinerary trip = new Itinerary(); trip.setUser(user); trip.setCountry(country); trip.setName("Test trip");
            trip = trips.saveAndFlush(trip);
            Activity activity = new Activity(); activity.setItinerary(trip); activity.setName("Test activity");
            activity = activities.saveAndFlush(activity);
            News article = new News(); article.setTitle("Test news"); article = news.saveAndFlush(article);
            Impact impact = new Impact(); impact.setUser(user); impact.setItinerary(trip);
            impact.setActivity(activity); impact.setNews(article); impacts.saveAndFlush(impact);
            return user.getId();
        });
        conversations.saveAll(id + ":1", List.of(new UserMessage("private message")));
        userService.deleteAccount(id, "password123");
        assertThat(users.existsById(id)).isFalse();
        assertThat(preferences.findByUserId(id)).isEmpty();
        assertThat(trips.findByUserId(id)).isEmpty();
        assertThat(impacts.findByUserId(id)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from activity", Long.class)).isZero();
        assertThat(conversations.findByConversationId(id + ":1")).isEmpty();
    }

    @Test
    void serviceRoleAuthorizationRejectsNonAdmins() {
        CurrentUser ordinary = new CurrentUser(99L, "user@example.com", "person", null, "USER");
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(ordinary, null, ordinary.getAuthorities()));
        assertThatThrownBy(() -> admins.changeRole(1L, Role.ADMIN, 99L))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void enabledAdminCountExcludesDisabledAccounts() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            User active = new User("active@example.com", "active", "long-enough-hash", "ADMIN");
            User disabled = new User("disabled@example.com", "disabled", "long-enough-hash", "ADMIN");
            disabled.setEnabled(false);
            users.saveAndFlush(active); users.saveAndFlush(disabled);
            assertThat(users.countEnabledByRole("ADMIN")).isEqualTo(1);
            status.setRollbackOnly();
        });
    }

    @Test
    void chatOwnershipLookupNeverReturnsNullForAnInaccessibleTrip() {
        User owner = users.saveAndFlush(new User("chat@example.com", "chatperson", "long-enough-hash", "USER"));
        assertThatThrownBy(() -> itineraryService.getItineraryByIdAndUserId(999L, owner.getId()))
                .isInstanceOf(ItineraryNotFoundException.class);
    }

    @Test
    void httpRoleChangesRequireAdminAndCsrfAndExpireTheTargetsSessions() throws Exception {
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(securityFilter).build();
        User admin = users.saveAndFlush(new User("httpadmin@example.com", "httpadmin", "long-enough-hash", "ADMIN"));
        User ordinary = users.saveAndFlush(new User("httpuser@example.com", "httpuser", "long-enough-hash", "USER"));
        var userSession = login(ordinary);
        var adminSession = login(admin);
        var userCsrf = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/")
                .session(userSession)).andReturn().getResponse().getCookie("XSRF-TOKEN");
        var adminCsrf = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/")
                .session(adminSession)).andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(userCsrf).isNotNull();
        assertThat(adminCsrf).isNotNull();
        String endpoint = "/api/admin/users/" + ordinary.getId() + "/role";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(endpoint)
                        .session(userSession).cookie(userCsrf).header("X-XSRF-TOKEN", userCsrf.getValue())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"role\":\"ADMIN\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(endpoint)
                        .session(adminSession).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(endpoint)
                        .session(adminSession).cookie(adminCsrf).header("X-XSRF-TOKEN", adminCsrf.getValue())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"role\":\"ADMIN\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        assertThat(users.findById(ordinary.getId()).orElseThrow().getRole()).isEqualTo("ADMIN");
        assertThat(sessionRegistry.getSessionInformation(userSession.getId()).isExpired()).isTrue();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/auth/me").session(userSession))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action = 'ROLE_CHANGED' and target_id = ?",
                Long.class, ordinary.getId())).isEqualTo(1);
        users.deleteById(ordinary.getId());
        users.deleteById(admin.getId());
    }

}
