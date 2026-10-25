package csd.tripsense.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;

import jakarta.servlet.http.Cookie;

/**
 * Unit tests for SessionService.expireSessionsFor (existing, used by the new code) and
 * SessionService.endAllSessions (new).
 *
 * SessionRegistry is a Mockito mock. The servlet request/response are Spring's MockHttpServletRequest /
 * MockHttpServletResponse, which behave like the real thing, so the real logout handlers can run.
 *
 * Map of source statements -> tests that cover them
 * ---------------------------------------------------------------------------------------------
 * endAllSessions: expireSessionsFor(email);                       | endAllSessions_expiresEveryMatchingSession
 * endAllSessions: new SecurityContextLogoutHandler().logout(...)  | endAllSessions_invalidatesCurrentSession_andClearsContext
 * endAllSessions: new CookieClearingLogoutHandler("JSESSIONID")   | endAllSessions_clearsJsessionidCookie
 * expireSessionsFor: principal instanceof CurrentUser && email ==  | expireSessionsFor_matchesEmailIgnoringCase_andSkipsOthers
 * ---------------------------------------------------------------------------------------------
 */
@ExtendWith(MockitoExtension.class)
class SessionServiceTest {

    @Mock private SessionRegistry sessionRegistry;
    @Mock private SessionInformation session1;
    @Mock private SessionInformation session2;
    @Mock private SessionInformation otherUsersSession;

    private final CurrentUser bob = new CurrentUser(4L, "bob@example.com", "bob", null, "USER");
    private final CurrentUser alice = new CurrentUser(2L, "alice@example.com", "alice", null, "USER");

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();   // the handlers touch the static holder; keep tests independent
    }

    private SessionService service() {
        return new SessionService(sessionRegistry);
    }

    private void givenRegistryHoldsBobAndAlice() {
        when(sessionRegistry.getAllPrincipals()).thenReturn(List.of(bob, alice));
        when(sessionRegistry.getAllSessions(bob, false)).thenReturn(List.of(session1, session2));
    }

    // =========================================================================================
    // endAllSessions -> expireSessionsFor(email)
    // =========================================================================================

    /**
     * TESTS: endAllSessions calls expireSessionsFor(email), which calls expireNow() on EVERY session of
     * the account (two devices here) and not on anyone else's.
     */
    @Test
    void endAllSessions_expiresEveryMatchingSession() {
        givenRegistryHoldsBobAndAlice();
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        service().endAllSessions("bob@example.com", request, response);

        verify(session1).expireNow();                                              // TESTS: device 1 logged out
        verify(session2).expireNow();                                              // TESTS: device 2 logged out
        verify(sessionRegistry, never()).getAllSessions(alice, false);             // TESTS: alice untouched
    }

    // =========================================================================================
    // endAllSessions -> SecurityContextLogoutHandler
    // =========================================================================================

    /**
     * TESTS: new SecurityContextLogoutHandler().logout(request, response, auth)
     * -> the current HTTP session is invalidated and the security context is cleared.
     */
    @Test
    void endAllSessions_invalidatesCurrentSession_andClearsContext() {
        givenRegistryHoldsBobAndAlice();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true);                                                  // a live session exists
        MockHttpServletResponse response = new MockHttpServletResponse();

        UsernamePasswordAuthenticationToken auth = UsernamePasswordAuthenticationToken.authenticated(
                bob, null, bob.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);                // the "logged in" state

        service().endAllSessions("bob@example.com", request, response);

        assertThat(request.getSession(false)).isNull();                            // TESTS: session invalidated
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull(); // TESTS: context cleared
    }

    // =========================================================================================
    // endAllSessions -> CookieClearingLogoutHandler("JSESSIONID")
    // =========================================================================================

    /**
     * TESTS: new CookieClearingLogoutHandler("JSESSIONID").logout(...)
     * -> the response carries a JSESSIONID cookie with max-age 0, which makes the browser delete it.
     */
    @Test
    void endAllSessions_clearsJsessionidCookie() {
        givenRegistryHoldsBobAndAlice();
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        service().endAllSessions("bob@example.com", request, response);

        Cookie cookie = response.getCookie("JSESSIONID");
        assertThat(cookie).isNotNull();                                            // TESTS: cookie is sent back
        assertThat(cookie.getMaxAge()).isZero();                                   // TESTS: ...as an expiry
    }

    // =========================================================================================
    // expireSessionsFor (existing method that endAllSessions relies on)
    // =========================================================================================

    /**
     * TESTS: the principal filter in expireSessionsFor: matches the email case-insensitively
     * (equalsIgnoreCase) and returns the number of sessions ended; other accounts are skipped.
     */
    @Test
    void expireSessionsFor_matchesEmailIgnoringCase_andSkipsOthers() {
        givenRegistryHoldsBobAndAlice();

        int expired = service().expireSessionsFor("BOB@Example.com");              // different casing on purpose

        assertThat(expired).isEqualTo(2);                                          // TESTS: count of sessions ended
        verify(session1).expireNow();
        verify(session2).expireNow();
        verify(otherUsersSession, never()).expireNow();                            // TESTS: not called for others
    }
}
