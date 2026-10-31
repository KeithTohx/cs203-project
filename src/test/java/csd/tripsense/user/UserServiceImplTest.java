package csd.tripsense.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import csd.tripsense.chat.ChatbotService;
import csd.tripsense.impact.ImpactRepository;
import csd.tripsense.itinerary.Itinerary;
import csd.tripsense.itinerary.ItineraryRepository;

// unit tests for UserServiceImpl, one nested group per method.
// the repositories and chatbot service are Mockito mocks. the password encoder is the real bcrypt one,
// wrapped in a spy so tests can still check it was never called, because faking hashes would hide
// mistakes like comparing against the plain password.
// the JPA cascades and transaction rollback need a real database, so they are not covered here
@ExtendWith(MockitoExtension.class)
@DisplayName("UserService Unit Tests")
class UserServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private ItineraryRepository itineraryRepository;

    @Mock
    private ImpactRepository impactRepository;

    @Mock
    private ChatbotService chatbotService;

    @Spy
    private PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @InjectMocks
    private UserServiceImpl userService;

    // ---------- helpers ----------

    // a saved account: real bcrypt hash, and an id set directly because User has no setter for it
    private User account(Long id, String username, String role, String password) {
        User user = new User(username + "@example.com", username, passwordEncoder.encode(password), role);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private void verifyNothingWasDeleted() {
        verifyNoInteractions(chatbotService, impactRepository, itineraryRepository);
        verify(userRepository, never()).delete(any(User.class));
    }

    // ---------- register ----------

    @Nested
    @DisplayName("register")
    class Register {

        @Test
        @DisplayName("Should store a bcrypt hash and force role USER even when the client asks for ADMIN")
        void register_ShouldStoreBcryptHashAndForceRoleUser_WhenClientAsksForAdmin() {
            // Arrange
            User request = new User("john@example.com", "john", "password123", "ADMIN");
            when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

            // Act
            userService.register(request);

            // Assert
            ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
            verify(userRepository, times(1)).saveAndFlush(captor.capture());
            User saved = captor.getValue();
            assertNotEquals("password123", saved.getPassword());
            assertTrue(passwordEncoder.matches("password123", saved.getPassword()));
            assertEquals("USER", saved.getRole());
        }

        @Test
        @DisplayName("Should throw 409 and not save when the email is taken")
        void register_ShouldThrow409AndNotSave_WhenEmailIsTaken() {
            // Arrange
            when(userRepository.existsByEmailIgnoreCase("john@example.com")).thenReturn(true);
            User request = new User("john@example.com", "john", "password123", "USER");

            // Act
            AccountRuleException exception = assertThrows(AccountRuleException.class,
                () -> userService.register(request));

            // Assert
            assertEquals(AccountRuleException.Kind.CONFLICT, exception.getKind());
            assertTrue(exception.getMessage().contains("email"));
            verify(userRepository, never()).saveAndFlush(any(User.class));
        }

        @Test
        @DisplayName("Should throw 409 and not save when the username is taken")
        void register_ShouldThrow409AndNotSave_WhenUsernameIsTaken() {
            // Arrange
            when(userRepository.existsByUsernameIgnoreCase("john")).thenReturn(true);
            User request = new User("john@example.com", "john", "password123", "USER");

            // Act
            AccountRuleException exception = assertThrows(AccountRuleException.class,
                () -> userService.register(request));

            // Assert
            assertEquals(AccountRuleException.Kind.CONFLICT, exception.getKind());
            assertTrue(exception.getMessage().contains("username"));
            verify(userRepository, never()).saveAndFlush(any(User.class));
        }

        @Test
        @DisplayName("Should throw 409 when the database rejects a duplicate that slipped past the checks")
        void register_ShouldThrow409_WhenDatabaseRejectsDuplicate() {
            // Arrange
            when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("unique"));
            User request = new User("john@example.com", "john", "password123", "USER");

            // Act
            AccountRuleException exception = assertThrows(AccountRuleException.class,
                () -> userService.register(request));

            // Assert
            assertEquals(AccountRuleException.Kind.CONFLICT, exception.getKind());
        }

        @Test
        @DisplayName("Should throw 400 and not save when the password is over 72 bytes")
        void register_ShouldThrow400AndNotSave_WhenPasswordIsOver72Bytes() {
            // Arrange - 40 characters but 80 bytes, since each é takes two
            String longPassword = "é".repeat(40);
            User request = new User("john@example.com", "john", longPassword, "USER");

            // Act
            AccountRuleException exception = assertThrows(AccountRuleException.class,
                () -> userService.register(request));

            // Assert
            assertEquals(AccountRuleException.Kind.INVALID_INPUT, exception.getKind());
            verify(userRepository, never()).saveAndFlush(any(User.class));
        }
    }

    // ---------- getByUsername ----------

    @Nested
    @DisplayName("getByUsername")
    class GetByUsername {

        @Test
        @DisplayName("Should throw UserNotFoundException when no user has that username")
        void getByUsername_ShouldThrowUserNotFoundException_WhenUserDoesNotExist() {
            // Arrange
            when(userRepository.findByUsernameIgnoreCase("ghost")).thenReturn(Optional.empty());

            // Act and Assert
            assertThrows(UserNotFoundException.class, () -> userService.getByUsername("ghost"));
        }
    }

    // ---------- changePassword ----------

    @Nested
    @DisplayName("changePassword")
    class ChangePassword {

        @Test
        @DisplayName("Should save a hash of the new password when the current password is right")
        void changePassword_ShouldSaveHashOfNewPassword_WhenCurrentPasswordIsRight() {
            // Arrange
            User john = account(4L, "john", "USER", "old-password-1");
            when(userRepository.findById(4L)).thenReturn(Optional.of(john));

            // Act
            userService.changePassword(4L, "old-password-1", "new-password-2");

            // Assert
            ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
            verify(userRepository, times(1)).save(captor.capture());
            String savedHash = captor.getValue().getPassword();
            assertNotEquals("new-password-2", savedHash);
            assertTrue(passwordEncoder.matches("new-password-2", savedHash));
            assertFalse(passwordEncoder.matches("old-password-1", savedHash));
        }

        @Test
        @DisplayName("Should throw 400 and not save when the current password is wrong")
        void changePassword_ShouldThrow400AndNotSave_WhenCurrentPasswordIsWrong() {
            // Arrange
            User john = account(4L, "john", "USER", "old-password-1");
            when(userRepository.findById(4L)).thenReturn(Optional.of(john));

            // Act
            AccountRuleException exception = assertThrows(AccountRuleException.class,
                () -> userService.changePassword(4L, "guessed-password", "new-password-2"));

            // Assert
            assertEquals(AccountRuleException.Kind.INVALID_INPUT, exception.getKind());
            assertTrue(exception.getMessage().contains("current password"));
            verify(userRepository, never()).save(any(User.class));
        }

        @Test
        @DisplayName("Should throw 400 and not save when the new password is the same as the current one")
        void changePassword_ShouldThrow400AndNotSave_WhenNewPasswordIsTheSame() {
            // Arrange
            User john = account(4L, "john", "USER", "old-password-1");
            when(userRepository.findById(4L)).thenReturn(Optional.of(john));

            // Act
            AccountRuleException exception = assertThrows(AccountRuleException.class,
                () -> userService.changePassword(4L, "old-password-1", "old-password-1"));

            // Assert
            assertEquals(AccountRuleException.Kind.INVALID_INPUT, exception.getKind());
            assertTrue(exception.getMessage().contains("different"));
            verify(userRepository, never()).save(any(User.class));
        }

        @Test
        @DisplayName("Should throw UserNotFoundException when the user does not exist")
        void changePassword_ShouldThrowUserNotFoundException_WhenUserDoesNotExist() {
            // Arrange
            when(userRepository.findById(99L)).thenReturn(Optional.empty());

            // Act and Assert
            assertThrows(UserNotFoundException.class,
                () -> userService.changePassword(99L, "old-password-1", "new-password-2"));
        }

        // bcrypt only reads 72 bytes, so 72 is the last length that is accepted
        @Test
        @DisplayName("Should accept a new password of exactly 72 bytes")
        void changePassword_ShouldAccept_WhenNewPasswordIsExactly72Bytes() {
            // Arrange
            User john = account(4L, "john", "USER", "old-password-1");
            when(userRepository.findById(4L)).thenReturn(Optional.of(john));
            String longestAllowed = "a".repeat(72);

            // Act
            userService.changePassword(4L, "old-password-1", longestAllowed);

            // Assert
            verify(userRepository, times(1)).save(john);
        }

        @Test
        @DisplayName("Should throw 400 before loading the user when the new password is 73 bytes")
        void changePassword_ShouldThrow400BeforeLoadingUser_WhenNewPasswordIs73Bytes() {
            // Arrange
            String oneByteTooLong = "a".repeat(73);

            // Act
            AccountRuleException exception = assertThrows(AccountRuleException.class,
                () -> userService.changePassword(4L, "old-password-1", oneByteTooLong));

            // Assert
            assertEquals(AccountRuleException.Kind.INVALID_INPUT, exception.getKind());
            verify(userRepository, never()).findById(any());
            verify(userRepository, never()).save(any(User.class));
        }
    }

    // ---------- updateUsername ----------

    @Nested
    @DisplayName("updateUsername")
    class UpdateUsername {

        @Test
        @DisplayName("Should save the new username when nobody else has it")
        void updateUsername_ShouldSaveNewName_WhenNameIsFree() {
            // Arrange
            User john = account(4L, "john", "USER", "password123");
            when(userRepository.findById(4L)).thenReturn(Optional.of(john));
            when(userRepository.findByUsernameIgnoreCase("johnny")).thenReturn(Optional.empty());
            when(userRepository.saveAndFlush(john)).thenReturn(john);

            // Act
            User updated = userService.updateUsername(4L, "johnny");

            // Assert
            assertEquals("johnny", updated.getUsername());
            verify(userRepository, times(1)).saveAndFlush(john);
        }

        // the clash check finds the user's own row, which must not count as someone else having the name
        @Test
        @DisplayName("Should allow changing only the capitalisation of the user's own name")
        void updateUsername_ShouldAllowIt_WhenOnlyCapitalisationChanges() {
            // Arrange
            User john = account(4L, "john", "USER", "password123");
            when(userRepository.findById(4L)).thenReturn(Optional.of(john));
            when(userRepository.findByUsernameIgnoreCase("John")).thenReturn(Optional.of(john));
            when(userRepository.saveAndFlush(john)).thenReturn(john);

            // Act
            User updated = userService.updateUsername(4L, "John");

            // Assert
            assertEquals("John", updated.getUsername());
        }

        @Test
        @DisplayName("Should throw 409 and not save when another user has the name")
        void updateUsername_ShouldThrow409AndNotSave_WhenSomeoneElseHasTheName() {
            // Arrange
            User john = account(4L, "john", "USER", "password123");
            User mary = account(5L, "mary", "USER", "password456");
            when(userRepository.findById(4L)).thenReturn(Optional.of(john));
            when(userRepository.findByUsernameIgnoreCase("mary")).thenReturn(Optional.of(mary));

            // Act
            AccountRuleException exception = assertThrows(AccountRuleException.class,
                () -> userService.updateUsername(4L, "mary"));

            // Assert
            assertEquals(AccountRuleException.Kind.CONFLICT, exception.getKind());
            assertEquals("john", john.getUsername());
            verify(userRepository, never()).saveAndFlush(any(User.class));
        }

        @Test
        @DisplayName("Should throw 409 when the database rejects a duplicate that slipped past the check")
        void updateUsername_ShouldThrow409_WhenDatabaseRejectsDuplicate() {
            // Arrange
            User john = account(4L, "john", "USER", "password123");
            when(userRepository.findById(4L)).thenReturn(Optional.of(john));
            when(userRepository.findByUsernameIgnoreCase("johnny")).thenReturn(Optional.empty());
            when(userRepository.saveAndFlush(john)).thenThrow(new DataIntegrityViolationException("unique"));

            // Act
            AccountRuleException exception = assertThrows(AccountRuleException.class,
                () -> userService.updateUsername(4L, "johnny"));

            // Assert
            assertEquals(AccountRuleException.Kind.CONFLICT, exception.getKind());
        }
    }

    // ---------- deleteAccount (user deletes their own account) ----------

    @Nested
    @DisplayName("deleteAccount")
    class DeleteAccount {

        @Test
        @DisplayName("Should delete chat, impacts, itineraries and then the user, in that order, when the password is correct")
        void deleteAccount_ShouldDeleteEverythingInOrder_WhenPasswordIsCorrect() {
            // Arrange
            User bob = account(4L, "bob", "USER", "password123");
            List<Itinerary> trips = List.of(new Itinerary(), new Itinerary());
            when(userRepository.findById(4L)).thenReturn(Optional.of(bob));
            when(itineraryRepository.findByUserId(4L)).thenReturn(trips);

            // Act
            userService.deleteAccount(4L, "password123");

            // Assert - children are deleted first and the user row last
            InOrder order = inOrder(chatbotService, impactRepository, itineraryRepository, userRepository);
            order.verify(chatbotService).deleteAllConversationsFor(4L);
            order.verify(impactRepository).deleteByUserId(4L);
            order.verify(itineraryRepository).deleteAll(trips);   // cascade removes their activities
            order.verify(itineraryRepository).flush();            // run the deletes before the parent row
            order.verify(userRepository).delete(bob);
        }

        @Test
        @DisplayName("Should not count admins when the user is not an admin")
        void deleteAccount_ShouldNotCountAdmins_WhenUserIsNotAdmin() {
            // Arrange
            User bob = account(4L, "bob", "USER", "password123");
            when(userRepository.findById(4L)).thenReturn(Optional.of(bob));

            // Act
            userService.deleteAccount(4L, "password123");

            // Assert
            verify(userRepository, never()).countEnabledByRole(anyString());
            verify(userRepository, times(1)).delete(bob);
        }

        @Test
        @DisplayName("Should throw 400 and delete nothing when the password is wrong")
        void deleteAccount_ShouldThrow400AndDeleteNothing_WhenPasswordIsWrong() {
            // Arrange
            User bob = account(4L, "bob", "USER", "password123");
            when(userRepository.findById(4L)).thenReturn(Optional.of(bob));

            // Act
            AccountRuleException exception = assertThrows(AccountRuleException.class,
                () -> userService.deleteAccount(4L, "wrong"));

            // Assert
            assertEquals(AccountRuleException.Kind.INVALID_INPUT, exception.getKind());
            assertEquals("current password is incorrect", exception.getMessage());
            verifyNothingWasDeleted();
        }

        @Test
        @DisplayName("Should throw 409 and delete nothing when the user is the last admin")
        void deleteAccount_ShouldThrow409AndDeleteNothing_WhenLastAdmin() {
            // Arrange
            User admin = account(3L, "admin", "ADMIN", "admin123");
            when(userRepository.findById(3L)).thenReturn(Optional.of(admin));
            when(userRepository.countEnabledByRole("ADMIN")).thenReturn(1L);

            // Act
            AccountRuleException exception = assertThrows(AccountRuleException.class,
                () -> userService.deleteAccount(3L, "admin123"));

            // Assert
            assertEquals(AccountRuleException.Kind.CONFLICT, exception.getKind());
            verifyNothingWasDeleted();
        }

        @Test
        @DisplayName("Should delete an admin when another admin exists")
        void deleteAccount_ShouldDeleteAdmin_WhenAnotherAdminExists() {
            // Arrange
            User admin = account(3L, "admin", "ADMIN", "admin123");
            when(userRepository.findById(3L)).thenReturn(Optional.of(admin));
            when(userRepository.countEnabledByRole("ADMIN")).thenReturn(2L);

            // Act
            userService.deleteAccount(3L, "admin123");

            // Assert
            verify(userRepository, times(1)).countEnabledByRole("ADMIN");
            verify(userRepository, times(1)).delete(admin);
        }

        @Test
        @DisplayName("Should throw UserNotFoundException and check no password when the user does not exist")
        void deleteAccount_ShouldThrowUserNotFoundException_WhenUserDoesNotExist() {
            // Arrange
            when(userRepository.findById(999L)).thenReturn(Optional.empty());

            // Act
            assertThrows(UserNotFoundException.class,
                () -> userService.deleteAccount(999L, "password123"));

            // Assert
            verify(userRepository, times(1)).findById(999L);
            verifyNoInteractions(passwordEncoder);
            verifyNothingWasDeleted();
        }
    }

    // ---------- deleteUserAndData (admin deletes an account) ----------

    @Nested
    @DisplayName("deleteUserAndData")
    class DeleteUserAndData {

        @Test
        @DisplayName("Should delete chat, impacts, itineraries and then the user, in that order")
        void deleteUserAndData_ShouldDeleteEverythingInOrder_WhenUserExists() {
            // Arrange
            User bob = account(4L, "bob", "USER", "password123");
            when(userRepository.findById(4L)).thenReturn(Optional.of(bob));
            when(itineraryRepository.findByUserId(4L)).thenReturn(List.of());

            // Act
            userService.deleteUserAndData(4L);

            // Assert
            InOrder order = inOrder(chatbotService, impactRepository, itineraryRepository, userRepository);
            order.verify(chatbotService).deleteAllConversationsFor(4L);
            order.verify(impactRepository).deleteByUserId(4L);
            order.verify(itineraryRepository).deleteAll(any());
            order.verify(itineraryRepository).flush();
            order.verify(userRepository).delete(bob);
        }

        @Test
        @DisplayName("Should throw UserNotFoundException and delete nothing when the user does not exist")
        void deleteUserAndData_ShouldThrowUserNotFoundExceptionAndDeleteNothing_WhenUserDoesNotExist() {
            // Arrange
            when(userRepository.findById(99L)).thenReturn(Optional.empty());

            // Act
            assertThrows(UserNotFoundException.class, () -> userService.deleteUserAndData(99L));

            // Assert
            verifyNothingWasDeleted();
        }

        @Test
        @DisplayName("Should throw 409 and delete nothing when the user is the last admin")
        void deleteUserAndData_ShouldThrow409AndDeleteNothing_WhenLastAdmin() {
            // Arrange
            User admin = account(3L, "admin", "ADMIN", "admin123");
            when(userRepository.findById(3L)).thenReturn(Optional.of(admin));
            when(userRepository.countEnabledByRole("ADMIN")).thenReturn(1L);

            // Act
            AccountRuleException exception = assertThrows(AccountRuleException.class,
                () -> userService.deleteUserAndData(3L));

            // Assert
            assertEquals(AccountRuleException.Kind.CONFLICT, exception.getKind());
            verifyNothingWasDeleted();
        }

        @Test
        @DisplayName("Should delete an admin when another admin exists")
        void deleteUserAndData_ShouldDeleteAdmin_WhenAnotherAdminExists() {
            // Arrange
            User admin = account(5L, "admin", "ADMIN", "admin123");
            when(userRepository.findById(5L)).thenReturn(Optional.of(admin));
            when(userRepository.countEnabledByRole("ADMIN")).thenReturn(2L);
            when(itineraryRepository.findByUserId(5L)).thenReturn(List.of());

            // Act
            userService.deleteUserAndData(5L);

            // Assert
            verify(userRepository, times(1)).delete(admin);
        }

        // only enabled admins count as "the last admin", so a disabled one can always be removed
        @Test
        @DisplayName("Should delete a disabled admin without counting admins")
        void deleteUserAndData_ShouldDeleteWithoutCountingAdmins_WhenAdminIsDisabled() {
            // Arrange
            User admin = account(6L, "admin", "ADMIN", "admin123");
            admin.setEnabled(false);
            when(userRepository.findById(6L)).thenReturn(Optional.of(admin));
            when(itineraryRepository.findByUserId(6L)).thenReturn(List.of());

            // Act
            userService.deleteUserAndData(6L);

            // Assert
            verify(userRepository, never()).countEnabledByRole(any());
            verify(userRepository, times(1)).delete(admin);
        }
    }
}
