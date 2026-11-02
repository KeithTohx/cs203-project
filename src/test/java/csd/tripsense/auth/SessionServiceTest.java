package csd.tripsense.auth;

import csd.tripsense.user.User;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistryImpl;

import static org.assertj.core.api.Assertions.assertThat;

class SessionServiceTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ---------- expireSessionsFor ----------

    @Test
    void expireSessionsFor_userId_endsOnlyThatUsersSessions() {
        SessionRegistryImpl registry = new SessionRegistryImpl();
        registry.registerNewSession("bob-1", new CurrentUser(4L, "bob@example.com", "bob", null, "USER", true));
        registry.registerNewSession("bob-2", new CurrentUser(4L, "bob@example.com", "bob", null, "USER", true));
        registry.registerNewSession("john-1", new CurrentUser(1L, "john@example.com", "john", null, "USER", true));

        int ended = new SessionService(registry).expireSessionsFor(4L);

        assertThat(ended).isEqualTo(2);
        assertThat(registry.getSessionInformation("bob-1").isExpired()).isTrue();
        assertThat(registry.getSessionInformation("bob-2").isExpired()).isTrue();
        assertThat(registry.getSessionInformation("john-1").isExpired()).isFalse();
    }

    @Test
    void expireSessionsFor_email_ignoresCase_andReturnsHowManyEnded() {
        SessionRegistryImpl registry = new SessionRegistryImpl();
        registry.registerNewSession("bob-1", new CurrentUser(4L, "bob@example.com", "bob", null, "USER", true));
        registry.registerNewSession("bob-2", new CurrentUser(4L, "bob@example.com", "bob", null, "USER", true));
        registry.registerNewSession("alice-1", new CurrentUser(2L, "alice@example.com", "alice", null, "USER", true));

        int ended = new SessionService(registry).expireSessionsFor("BOB@Example.com");

        assertThat(ended).isEqualTo(2);
        assertThat(registry.getSessionInformation("bob-1").isExpired()).isTrue();
        assertThat(registry.getSessionInformation("bob-2").isExpired()).isTrue();
        assertThat(registry.getSessionInformation("alice-1").isExpired()).isFalse();
    }

    // ---------- endAllSessions (used when an account is deleted) ----------

    @Test
    void endAllSessions_endsEveryOneOfThatUsersSessions_andNoOneElses() {
        SessionRegistryImpl registry = new SessionRegistryImpl();
        registry.registerNewSession("bob-laptop", new CurrentUser(4L, "bob@example.com", "bob", null, "USER", true));
        registry.registerNewSession("bob-phone", new CurrentUser(4L, "bob@example.com", "bob", null, "USER", true));
        registry.registerNewSession("alice-1", new CurrentUser(2L, "alice@example.com", "alice", null, "USER", true));

        new SessionService(registry)
                .endAllSessions("bob@example.com", new MockHttpServletRequest(), new MockHttpServletResponse());

        assertThat(registry.getSessionInformation("bob-laptop").isExpired()).isTrue();
        assertThat(registry.getSessionInformation("bob-phone").isExpired()).isTrue();
        assertThat(registry.getSessionInformation("alice-1").isExpired()).isFalse();
    }

    @Test
    void endAllSessions_invalidatesTheCurrentSession_andClearsTheSecurityContext() {
        CurrentUser bob = new CurrentUser(4L, "bob@example.com", "bob", null, "USER", true);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(bob, null, bob.getAuthorities()));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true);

        new SessionService(new SessionRegistryImpl())
                .endAllSessions("bob@example.com", request, new MockHttpServletResponse());

        assertThat(request.getSession(false)).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void endAllSessions_sendsBackAnExpiredSessionCookie_soTheBrowserDeletesIt() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new SessionService(new SessionRegistryImpl())
                .endAllSessions("bob@example.com", new MockHttpServletRequest(), response);

        Cookie cookie = response.getCookie("JSESSIONID");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getMaxAge()).isZero();
    }

    // ---------- refreshCurrentSession ----------

    @Test
    void refreshCurrentSession_putsTheNewUsernameInTheSession_andNoPasswordHash() {
        CurrentUser before = new CurrentUser(7L, "alice@example.com", "alice", null, "USER", true);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(before, null, before.getAuthorities()));
        User renamed = new User("alice@example.com", "alice2", "the-hash", "USER");

        new SessionService(new SessionRegistryImpl())
                .refreshCurrentSession(renamed, new MockHttpServletRequest(), new MockHttpServletResponse());

        CurrentUser after = (CurrentUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertThat(after.getAccountUsername()).isEqualTo("alice2");
        assertThat(after.getEmail()).isEqualTo("alice@example.com");
        assertThat(after.getPassword()).isNull();
    }
}
