package csd.tripsense.security;

import csd.tripsense.admin.*;
import csd.tripsense.auth.CurrentUser;
import csd.tripsense.itinerary.*;
import csd.tripsense.user.*;
import org.junit.jupiter.api.*;
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
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {
        "gdelt.ingest.enabled=false", "impact.eval.enabled=false", "spring.sql.init.mode=never",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.datasource.hikari.maximum-pool-size=1"
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
