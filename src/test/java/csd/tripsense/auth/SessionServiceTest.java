package csd.tripsense.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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
 * LOCATION: src/test/java/csd/tripsense/auth/SessionServiceTest.java
 *
 * Unit tests for SessionService.endAllSessions (added for the delete-account story) and
 * SessionService.expireSessionsFor (existing, called by endAllSessions)
 * Tests that every session of the account is expired, the current session is invalidated and the
 * security context cleared, and the JSESSIONID cookie is sent back expired
 * SessionRegistry is a Mockito mock. The request and response are Spring's MockHttpServletRequest and
 * MockHttpServletResponse, so the real Spring Security logout handlers run against them
 * refreshCurrentSession is not covered here
 * Does not cover the 401 that other devices get on their next request, which comes from the
 * session filter in the Spring Security filter chain
 *
 * Source statement                                           -> Test that covers it
 * endAllSessions: expireSessionsFor(email)                   -> endAllSessions_ShouldExpireEveryMatchingSession_WhenUserHasManySessions
 * endAllSessions: SecurityContextLogoutHandler.logout(...)   -> endAllSessions_ShouldInvalidateSessionAndClearContext_WhenLoggedIn
 * endAllSessions: CookieClearingLogoutHandler("JSESSIONID")  -> endAllSessions_ShouldClearJsessionidCookie_WhenCalled
 * expireSessionsFor: equalsIgnoreCase(email) and the count   -> expireSessionsFor_ShouldMatchEmailIgnoringCase_WhenCasingDiffers
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SessionService Unit Tests")
class SessionServiceTest {

    @Mock
    private SessionRegistry sessionRegistry;

    @Mock
    private SessionInformation bobSession1;

    @Mock
    private SessionInformation bobSession2;

    @InjectMocks
    private SessionService sessionService;

    private CurrentUser bob;
    private CurrentUser alice;
    private MockHttpServletRequest httpRequest;
    private MockHttpServletResponse httpResponse;

    @BeforeEach
    void setUp() {
        bob = new CurrentUser(4L, "bob@example.com", "bob", null, "USER");
        alice = new CurrentUser(2L, "alice@example.com", "alice", null, "USER");
        httpRequest = new MockHttpServletRequest();
        httpResponse = new MockHttpServletResponse();

        // The registry knows two logged-in accounts, and bob is logged in on two devices
        when(sessionRegistry.getAllPrincipals()).thenReturn(List.of(bob, alice));
        when(sessionRegistry.getAllSessions(bob, false)).thenReturn(List.of(bobSession1, bobSession2));
    }

    @AfterEach
    void clearSecurityContext() {
        // The logout handlers use the static SecurityContextHolder, so reset it between tests
        SecurityContextHolder.clearContext();
    }

    // ===== END ALL SESSIONS TESTS =====

    @Test
    @DisplayName("Should expire every session of the account and no one else's")
    void endAllSessions_ShouldExpireEveryMatchingSession_WhenUserHasManySessions() {
        // Act
        sessionService.endAllSessions("bob@example.com", httpRequest, httpResponse);

        // Assert
        verify(bobSession1, times(1)).expireNow();
        verify(bobSession2, times(1)).expireNow();
        verify(sessionRegistry, never()).getAllSessions(alice, false);
    }

    @Test
    @DisplayName("Should invalidate the current session and clear the security context")
    void endAllSessions_ShouldInvalidateSessionAndClearContext_WhenLoggedIn() {
        // Arrange - a live session and an authenticated user
        httpRequest.getSession(true);
        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(bob, null, bob.getAuthorities()));

        // Act
        sessionService.endAllSessions("bob@example.com", httpRequest, httpResponse);

        // Assert
        assertNull(httpRequest.getSession(false));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    @DisplayName("Should send back an expired JSESSIONID cookie so the browser deletes it")
    void endAllSessions_ShouldClearJsessionidCookie_WhenCalled() {
        // Act
        sessionService.endAllSessions("bob@example.com", httpRequest, httpResponse);

        // Assert - max age 0 tells the browser to delete the cookie
        Cookie cookie = httpResponse.getCookie("JSESSIONID");
        assertNotNull(cookie);
        assertEquals(0, cookie.getMaxAge());
    }

    // ===== EXPIRE SESSIONS TESTS =====

    @Test
    @DisplayName("Should match the email ignoring case and return how many sessions were ended")
    void expireSessionsFor_ShouldMatchEmailIgnoringCase_WhenCasingDiffers() {
        // Act
        int expired = sessionService.expireSessionsFor("BOB@Example.com");

        // Assert
        assertEquals(2, expired);
        verify(bobSession1, times(1)).expireNow();
        verify(bobSession2, times(1)).expireNow();
        verify(sessionRegistry, never()).getAllSessions(alice, false);
    }
}
