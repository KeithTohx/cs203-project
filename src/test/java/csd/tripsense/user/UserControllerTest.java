package csd.tripsense.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;

import csd.tripsense.auth.CurrentUser;
import csd.tripsense.auth.SessionService;

/**
 * LOCATION: src/test/java/csd/tripsense/user/UserControllerTest.java
 *
 * Unit tests for UserController.deleteAccount, DELETE /api/user/me (added for the delete-account story)
 * Tests that the logged-in user's id is used, that sessions end only after a successful delete,
 * and that the response is 204
 * Calls the controller method directly with Spring's MockHttpServletRequest and MockHttpServletResponse
 * Other endpoints in UserController (register, getByUsername, updateProfile, changePassword) are not covered here
 * Does not cover @Valid on the request body (blank password gives 400), the CSRF check, or the 401
 * for a user who is not logged in, because those run in Spring's filter chain, which this test bypasses
 *
 * Source statement in deleteAccount                                -> Test that covers it
 * userService.deleteAccount(currentUser.getId(), request.currentPassword())
 *                                                                  -> deleteAccount_ShouldPassLoggedInUsersIdAndPassword_WhenCalled
 * sessionService.endAllSessions(...) called after the delete       -> deleteAccount_ShouldEndSessionsAfterDelete_WhenDeleteSucceeds
 * return ResponseEntity.noContent().build()                        -> deleteAccount_ShouldReturn204_WhenDeleteSucceeds
 * exception from userService.deleteAccount skips endAllSessions    -> deleteAccount_ShouldNotEndSessions_WhenDeleteFails
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserController deleteAccount Unit Tests")
class UserControllerTest {

    @Mock
    private UserService userService;

    @Mock
    private SessionService sessionService;

    @InjectMocks
    private UserController userController;

    private CurrentUser testCurrentUser;
    private MockHttpServletRequest httpRequest;
    private MockHttpServletResponse httpResponse;

    @BeforeEach
    void setUp() {
        testCurrentUser = new CurrentUser(4L, "bob@example.com", "bob", null, "USER");
        httpRequest = new MockHttpServletRequest();
        httpResponse = new MockHttpServletResponse();
    }

    // ===== SUCCESSFUL DELETE TESTS =====

    @Test
    @DisplayName("Should pass the logged-in user's id and the typed password to the service")
    void deleteAccount_ShouldPassLoggedInUsersIdAndPassword_WhenCalled() {
        // Arrange
        DeleteAccountRequest request = new DeleteAccountRequest("password123");

        // Act
        userController.deleteAccount(request, testCurrentUser, httpRequest, httpResponse);

        // Assert - the id comes from the session principal, never from the request
        verify(userService, times(1)).deleteAccount(4L, "password123");
    }

    @Test
    @DisplayName("Should end all sessions after the account is deleted")
    void deleteAccount_ShouldEndSessionsAfterDelete_WhenDeleteSucceeds() {
        // Arrange
        DeleteAccountRequest request = new DeleteAccountRequest("password123");

        // Act
        userController.deleteAccount(request, testCurrentUser, httpRequest, httpResponse);

        // Assert - delete first, then log out
        InOrder order = inOrder(userService, sessionService);
        order.verify(userService).deleteAccount(4L, "password123");
        order.verify(sessionService).endAllSessions("bob@example.com", httpRequest, httpResponse);
    }

    @Test
    @DisplayName("Should return 204 with no body when delete succeeds")
    void deleteAccount_ShouldReturn204_WhenDeleteSucceeds() {
        // Arrange
        DeleteAccountRequest request = new DeleteAccountRequest("password123");

        // Act
        ResponseEntity<Void> response =
            userController.deleteAccount(request, testCurrentUser, httpRequest, httpResponse);

        // Assert
        assertEquals(204, response.getStatusCode().value());
        assertNull(response.getBody());
    }

    // ===== FAILED DELETE TESTS =====

    @Test
    @DisplayName("Should not end sessions and should pass the exception on when delete fails")
    void deleteAccount_ShouldNotEndSessions_WhenDeleteFails() {
        // Arrange
        DeleteAccountRequest request = new DeleteAccountRequest("wrong");
        doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "current password is incorrect"))
            .when(userService).deleteAccount(4L, "wrong");

        // Act
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
            () -> userController.deleteAccount(request, testCurrentUser, httpRequest, httpResponse));

        // Assert - the user stays logged in so they can see the error and retry
        assertEquals(400, exception.getStatusCode().value());
        verifyNoInteractions(sessionService);
    }
}
