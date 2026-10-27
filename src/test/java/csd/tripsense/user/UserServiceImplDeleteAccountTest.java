package csd.tripsense.user;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import csd.tripsense.chat.ChatbotService;
import csd.tripsense.impact.ImpactRepository;
import csd.tripsense.itinerary.Itinerary;
import csd.tripsense.itinerary.ItineraryRepository;

/**
 * LOCATION: src/test/java/csd/tripsense/user/UserServiceImplDeleteAccountTest.java
 *
 * Unit tests for UserServiceImpl.deleteAccount (added for the delete-account story)
 * Tests the password check, the last-admin rule, the order of the deletes, and that a rejected
 * request deletes nothing
 * No Spring and no database: every dependency is a Mockito mock
 * Other methods in UserServiceImpl (register, updateUsername, changePassword) are not covered here
 * Does not cover the JPA cascades or the transaction rollback, which need a real database
 *
 * Source statement in deleteAccount                         -> Test that covers it
 * findById(userId)                                          -> deleteAccount_ShouldThrowUserNotFoundException_WhenUserDoesNotExist
 * !passwordEncoder.matches(...) throws 400                  -> deleteAccount_ShouldThrow400_WhenPasswordIsWrong
 * "ADMIN".equals(role) && countByRole("ADMIN") <= 1 (409)   -> deleteAccount_ShouldThrow409_WhenLastAdmin
 * countByRole("ADMIN") returns 2 or more                    -> deleteAccount_ShouldDeleteAdmin_WhenAnotherAdminExists
 * "ADMIN".equals(role) is false, so no count query          -> deleteAccount_ShouldNotCountAdmins_WhenUserIsNotAdmin
 * deleteAllConversationsFor, deleteByUserId, deleteAll,
 *   flush, delete(user) in that order                       -> deleteAccount_ShouldDeleteEverythingInOrder_WhenPasswordIsCorrect
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserService Unit Tests")
class UserServiceImplDeleteAccountTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private ItineraryRepository itineraryRepository;

    @Mock
    private ImpactRepository impactRepository;

    @Mock
    private ChatbotService chatbotService;

    @InjectMocks
    private UserServiceImpl userService;

    private User testUser;
    private User testAdmin;

    @BeforeEach
    void setUp() {
        testUser = new User("bob@example.com", "bob", "hash", "USER");
        testAdmin = new User("admin@example.com", "admin", "hash", "ADMIN");
    }

    // ===== SUCCESSFUL DELETE TESTS =====

    @Test
    @DisplayName("Should delete chat, impacts, itineraries and then the user, in that order, when password is correct")
    void deleteAccount_ShouldDeleteEverythingInOrder_WhenPasswordIsCorrect() {
        // Arrange
        Itinerary trip1 = new Itinerary();
        Itinerary trip2 = new Itinerary();
        List<Itinerary> trips = List.of(trip1, trip2);

        when(userRepository.findById(4L)).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches("password123", "hash")).thenReturn(true);
        when(itineraryRepository.findByUserId(4L)).thenReturn(trips);

        // Act
        userService.deleteAccount(4L, "password123");

        // Assert - children are deleted first and the user row last
        InOrder order = inOrder(chatbotService, impactRepository, itineraryRepository, userRepository);
        order.verify(chatbotService).deleteAllConversationsFor(4L);   // chat history
        order.verify(impactRepository).deleteByUserId(4L);            // user's impacts
        order.verify(itineraryRepository).deleteAll(trips);           // itineraries (cascade removes activities)
        order.verify(itineraryRepository).flush();                    // run the deletes before the parent row
        order.verify(userRepository).delete(testUser);                // the account itself
    }

    @Test
    @DisplayName("Should not count admins when the user is not an admin")
    void deleteAccount_ShouldNotCountAdmins_WhenUserIsNotAdmin() {
        // Arrange
        when(userRepository.findById(4L)).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches("password123", "hash")).thenReturn(true);

        // Act
        userService.deleteAccount(4L, "password123");

        // Assert - the "ADMIN".equals(role) check short-circuits the count query
        verify(userRepository, never()).countByRole(anyString());
        verify(userRepository, times(1)).delete(testUser);
    }

    // ===== PASSWORD CHECK TESTS =====

    @Test
    @DisplayName("Should throw 400 and delete nothing when password is wrong")
    void deleteAccount_ShouldThrow400_WhenPasswordIsWrong() {
        // Arrange
        when(userRepository.findById(4L)).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches("wrong", "hash")).thenReturn(false);

        // Act
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
            () -> userService.deleteAccount(4L, "wrong"));

        // Assert
        assertEquals(400, exception.getStatusCode().value());
        assertEquals("current password is incorrect", exception.getReason());
        verifyNoInteractions(chatbotService, impactRepository, itineraryRepository);
        verify(userRepository, never()).delete(any(User.class));
    }

    // ===== LAST ADMIN TESTS =====

    @Test
    @DisplayName("Should throw 409 and delete nothing when the user is the last admin")
    void deleteAccount_ShouldThrow409_WhenLastAdmin() {
        // Arrange
        when(userRepository.findById(3L)).thenReturn(Optional.of(testAdmin));
        when(passwordEncoder.matches("admin123", "hash")).thenReturn(true);
        when(userRepository.countByRole("ADMIN")).thenReturn(1L);

        // Act
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
            () -> userService.deleteAccount(3L, "admin123"));

        // Assert
        assertEquals(409, exception.getStatusCode().value());
        verifyNoInteractions(chatbotService, impactRepository, itineraryRepository);
        verify(userRepository, never()).delete(any(User.class));
    }

    @Test
    @DisplayName("Should delete an admin when another admin exists")
    void deleteAccount_ShouldDeleteAdmin_WhenAnotherAdminExists() {
        // Arrange
        when(userRepository.findById(3L)).thenReturn(Optional.of(testAdmin));
        when(passwordEncoder.matches("admin123", "hash")).thenReturn(true);
        when(userRepository.countByRole("ADMIN")).thenReturn(2L);

        // Act
        userService.deleteAccount(3L, "admin123");

        // Assert
        verify(userRepository, times(1)).countByRole("ADMIN");
        verify(userRepository, times(1)).delete(testAdmin);
    }

    // ===== USER NOT FOUND TESTS =====

    @Test
    @DisplayName("Should throw UserNotFoundException when user does not exist")
    void deleteAccount_ShouldThrowUserNotFoundException_WhenUserDoesNotExist() {
        // Arrange
        Long nonExistentId = 999L;
        when(userRepository.findById(nonExistentId)).thenReturn(Optional.empty());

        // Act
        UserNotFoundException exception = assertThrows(UserNotFoundException.class,
            () -> userService.deleteAccount(nonExistentId, "password123"));

        // Assert
        assertNotNull(exception);
        verify(userRepository, times(1)).findById(nonExistentId);
        verifyNoInteractions(passwordEncoder, chatbotService, impactRepository, itineraryRepository);
    }
}
