package csd.tripsense.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;

import csd.tripsense.auth.CurrentUser;
import csd.tripsense.auth.SessionService;

/**
 *
 * Unit tests for UserController.deleteAccount (DELETE /api/user/me). The controller method is called
 * directly; this checks the wiring and ordering, not HTTP, CSRF or JSON parsing.
 *
 * Map of source statements -> tests that cover them
 * ---------------------------------------------------------------------------------------------
 * userService.deleteAccount(currentUser.getId(), request.currentPassword());   | deleteAccount_success_usesLoggedInUsersIdAndPassword
 * sessionService.endAllSessions(currentUser.getEmail(), req, resp);            | deleteAccount_success_endsSessionsAfterDelete
 * return ResponseEntity.noContent().build();                                   | deleteAccount_success_returns204
 * (ordering: delete first, sessions second, only if delete did not throw)      | deleteAccount_serviceFails_sessionsAreNotEnded
 * ---------------------------------------------------------------------------------------------
 */
@ExtendWith(MockitoExtension.class)
class UserControllerDeleteAccountTest {

    @Mock private UserService userService;
    @Mock private SessionService sessionService;

    private UserController controller;
    private final CurrentUser bob = new CurrentUser(4L, "bob@example.com", "bob", null, "USER");
    private final MockHttpServletRequest httpRequest = new MockHttpServletRequest();
    private final MockHttpServletResponse httpResponse = new MockHttpServletResponse();

    @BeforeEach
    void setUp() {
        controller = new UserController(userService, sessionService);
    }

    /**
     * TESTS: userService.deleteAccount(currentUser.getId(), request.currentPassword())
     * The id comes from the logged-in principal and the password from the body (never from a URL id).
     */
    @Test
    void deleteAccount_success_usesLoggedInUsersIdAndPassword() {
        controller.deleteAccount(new DeleteAccountRequest("password123"), bob, httpRequest, httpResponse);

        org.mockito.Mockito.verify(userService).deleteAccount(4L, "password123");  // TESTS: id + password passed through
    }

    /**
     * TESTS: sessionService.endAllSessions(currentUser.getEmail(), ...) and that it runs AFTER the delete.
     */
    @Test
    void deleteAccount_success_endsSessionsAfterDelete() {
        controller.deleteAccount(new DeleteAccountRequest("password123"), bob, httpRequest, httpResponse);

        InOrder order = inOrder(userService, sessionService);
        order.verify(userService).deleteAccount(4L, "password123");                // TESTS: delete first...
        order.verify(sessionService).endAllSessions("bob@example.com", httpRequest, httpResponse); // ...sessions second
    }

    /**
     * TESTS: return ResponseEntity.noContent().build() -> 204 with no body.
     */
    @Test
    void deleteAccount_success_returns204() {
        ResponseEntity<Void> result =
                controller.deleteAccount(new DeleteAccountRequest("password123"), bob, httpRequest, httpResponse);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);       // TESTS: 204
        assertThat(result.getBody()).isNull();                                     // TESTS: no body
    }

    /**
     * TESTS: ordering guarantee. If userService.deleteAccount throws (wrong password, last admin...),
     * the exception propagates and the user is NOT logged out.
     */
    @Test
    void deleteAccount_serviceFails_sessionsAreNotEnded() {
        doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "current password is incorrect"))
                .when(userService).deleteAccount(4L, "wrong");

        assertThatThrownBy(() ->
                controller.deleteAccount(new DeleteAccountRequest("wrong"), bob, httpRequest, httpResponse))
                .isInstanceOf(ResponseStatusException.class);                      // TESTS: error reaches the caller

        verifyNoInteractions(sessionService);                                      // TESTS: user stays logged in
    }
}
