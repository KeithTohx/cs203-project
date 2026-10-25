package csd.tripsense.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import csd.tripsense.chat.ChatbotService;
import csd.tripsense.impact.ImpactRepository;
import csd.tripsense.itinerary.Itinerary;
import csd.tripsense.itinerary.ItineraryRepository;

/**
 * Unit tests for UserServiceImpl.deleteAccount (no Spring, no database: every dependency is a Mockito mock).
 *
 * Map of source statements -> tests that cover them
 * ---------------------------------------------------------------------------------------------
 * deleteAccount statement                                         | Covered by
 * ---------------------------------------------------------------------------------------------
 * User user = findById(userId);                                   | deleteAccount_userMissing_throwsNotFound
 * if (!passwordEncoder.matches(...)) throw 400                    | deleteAccount_wrongPassword_throws400_andDeletesNothing
 * if ("ADMIN".equals(role) && countByRole("ADMIN") <= 1) throw 409| deleteAccount_lastAdmin_throws409_andDeletesNothing
 *                                                                 | deleteAccount_adminWhenAnotherAdminExists_succeeds
 *                                                                 | deleteAccount_normalUser_neverCountsAdmins
 * chatbotService.deleteAllConversationsFor(userId);               | deleteAccount_success_deletesEverythingInOrder
 * impactRepository.deleteByUserId(userId);                        | deleteAccount_success_deletesEverythingInOrder
 * itineraryRepository.deleteAll(findByUserId(userId));            | deleteAccount_success_deletesEverythingInOrder
 * itineraryRepository.flush();                                    | deleteAccount_success_deletesEverythingInOrder
 * userRepository.delete(user);                                    | deleteAccount_success_deletesEverythingInOrder
 * ---------------------------------------------------------------------------------------------
 */
@ExtendWith(MockitoExtension.class)
class UserServiceImplDeleteAccountTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private ItineraryRepository itineraryRepository;
    @Mock private ImpactRepository impactRepository;
    @Mock private ChatbotService chatbotService;

    private UserServiceImpl service;

    @BeforeEach
    void setUp() {
        // Same argument order as the constructor of UserServiceImpl.
        service = new UserServiceImpl(userRepository, passwordEncoder,
                itineraryRepository, impactRepository, chatbotService);
    }

    // =========================================================================================
    // HAPPY PATH
    // =========================================================================================

    /**
     * TESTS: the whole body of deleteAccount after the checks pass:
     *   chatbotService.deleteAllConversationsFor -> impactRepository.deleteByUserId
     *   -> itineraryRepository.deleteAll(findByUserId) -> itineraryRepository.flush
     *   -> userRepository.delete(user)
     * The InOrder checks guard the "children first, parent last" rule.
     */
    @Test
    void deleteAccount_success_deletesEverythingInOrder() {
        User bob = new User("bob@example.com", "bob", "hash", "USER");
        Itinerary trip1 = new Itinerary();
        Itinerary trip2 = new Itinerary();
        List<Itinerary> trips = List.of(trip1, trip2);

        // Arrange: lets the code get past findById, the password check and the admin check.
        when(userRepository.findById(4L)).thenReturn(Optional.of(bob));            // feeds: findById(userId)
        when(passwordEncoder.matches("password123", "hash")).thenReturn(true);     // feeds: password check
        when(itineraryRepository.findByUserId(4L)).thenReturn(trips);              // feeds: findByUserId(userId)

        service.deleteAccount(4L, "password123");

        InOrder order = inOrder(chatbotService, impactRepository, itineraryRepository, userRepository);
        order.verify(chatbotService).deleteAllConversationsFor(4L);   // TESTS: chat history deleted first
        order.verify(impactRepository).deleteByUserId(4L);            // TESTS: user's impacts deleted
        order.verify(itineraryRepository).deleteAll(trips);           // TESTS: itineraries deleted (cascade does the rest)
        order.verify(itineraryRepository).flush();                    // TESTS: flush before the parent row goes
        order.verify(userRepository).delete(bob);                     // TESTS: the user row is deleted last
    }

    // =========================================================================================
    // PASSWORD CHECK
    // =========================================================================================

    /**
     * TESTS: if (!passwordEncoder.matches(currentPassword, user.getPassword())) -> 400
     * Also proves nothing is deleted when the check fails.
     */
    @Test
    void deleteAccount_wrongPassword_throws400_andDeletesNothing() {
        User bob = new User("bob@example.com", "bob", "hash", "USER");
        when(userRepository.findById(4L)).thenReturn(Optional.of(bob));
        when(passwordEncoder.matches("wrong", "hash")).thenReturn(false);          // password does not match

        assertThatThrownBy(() -> service.deleteAccount(4L, "wrong"))               // TESTS: the 400 is thrown
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(400))
                .hasMessageContaining("current password is incorrect");

        verifyNoInteractions(chatbotService, impactRepository, itineraryRepository); // TESTS: nothing deleted
        verify(userRepository, never()).delete(any(User.class));                     // TESTS: user row kept
    }

    // =========================================================================================
    // LAST-ADMIN RULE
    // =========================================================================================

    /**
     * TESTS: "ADMIN".equals(user.getRole()) && countByRole("ADMIN") <= 1 -> 409
     * (the boundary: exactly one admin left).
     */
    @Test
    void deleteAccount_lastAdmin_throws409_andDeletesNothing() {
        User admin = new User("admin@example.com", "admin", "hash", "ADMIN");
        when(userRepository.findById(3L)).thenReturn(Optional.of(admin));
        when(passwordEncoder.matches("admin123", "hash")).thenReturn(true);
        when(userRepository.countByRole("ADMIN")).thenReturn(1L);                  // only one admin exists

        assertThatThrownBy(() -> service.deleteAccount(3L, "admin123"))            // TESTS: the 409 is thrown
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(409));

        verifyNoInteractions(chatbotService, impactRepository, itineraryRepository); // TESTS: nothing deleted
        verify(userRepository, never()).delete(any(User.class));
    }

    /**
     * TESTS: the other side of the "<= 1" boundary: with two admins, an admin may delete themselves.
     */
    @Test
    void deleteAccount_adminWhenAnotherAdminExists_succeeds() {
        User admin = new User("admin@example.com", "admin", "hash", "ADMIN");
        when(userRepository.findById(3L)).thenReturn(Optional.of(admin));
        when(passwordEncoder.matches("admin123", "hash")).thenReturn(true);
        when(userRepository.countByRole("ADMIN")).thenReturn(2L);                  // a second admin exists

        service.deleteAccount(3L, "admin123");

        verify(userRepository).delete(admin);                                      // TESTS: deletion goes ahead
    }

    /**
     * TESTS: the "ADMIN".equals(role) short-circuit: a normal user never triggers the admin count query.
     */
    @Test
    void deleteAccount_normalUser_neverCountsAdmins() {
        User bob = new User("bob@example.com", "bob", "hash", "USER");
        when(userRepository.findById(4L)).thenReturn(Optional.of(bob));
        when(passwordEncoder.matches("password123", "hash")).thenReturn(true);

        service.deleteAccount(4L, "password123");

        verify(userRepository, never()).countByRole(anyString());                  // TESTS: count skipped for USER
    }

    // =========================================================================================
    // MISSING USER
    // =========================================================================================

    /**
     * TESTS: findById(userId) -> UserNotFoundException when the account no longer exists
     * (e.g. already deleted from another device).
     */
    @Test
    void deleteAccount_userMissing_throwsNotFound() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteAccount(99L, "whatever"))           // TESTS: UserNotFoundException
                .isInstanceOf(UserNotFoundException.class);

        verifyNoInteractions(passwordEncoder, chatbotService, impactRepository, itineraryRepository);
    }
}
