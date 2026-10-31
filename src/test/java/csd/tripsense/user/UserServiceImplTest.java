package csd.tripsense.user;

import csd.tripsense.chat.ChatbotService;
import csd.tripsense.impact.ImpactRepository;
import csd.tripsense.itinerary.ItineraryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import csd.tripsense.user.AccountRuleException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceImplTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = new BCryptPasswordEncoder();
        userService = new UserServiceImpl(userRepository, passwordEncoder,
                mock(ItineraryRepository.class),
                mock(ImpactRepository.class),
                mock(ChatbotService.class));
    }

    private static void assertStatus(Throwable e, AccountRuleException.Kind expected) {
        assertThat(e).isInstanceOfSatisfying(AccountRuleException.class,
                r -> assertThat(r.getKind()).isEqualTo(expected));
    }

    @Test
    void register_storesBcryptHashNotPlaintext_andForcesRoleUser() {
        User request = new User("john@example.com", "john", "password123", "ADMIN");
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        userService.register(request);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.getPassword()).isNotEqualTo("password123").startsWith("$2");
        assertThat(passwordEncoder.matches("password123", saved.getPassword())).isTrue();
        assertThat(saved.getRole()).isEqualTo("USER");
    }

    @Test
    void register_withDuplicateEmail_throws409AndDoesNotSave() {
        when(userRepository.existsByEmailIgnoreCase("john@example.com")).thenReturn(true);

        assertThatThrownBy(() -> userService.register(new User("john@example.com", "john", "password123", "USER")))
                .satisfies(e -> assertStatus(e, AccountRuleException.Kind.CONFLICT))
                .hasMessageContaining("email");
        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    @Test
    void register_withDuplicateUsername_throws409AndDoesNotSave() {
        when(userRepository.existsByUsernameIgnoreCase("john")).thenReturn(true);

        assertThatThrownBy(() -> userService.register(new User("john@example.com", "john", "password123", "USER")))
                .satisfies(e -> assertStatus(e, AccountRuleException.Kind.CONFLICT))
                .hasMessageContaining("username");
        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    @Test
    void register_whenDatabaseRejectsDuplicate_throws409() {
        when(userRepository.saveAndFlush(any(User.class))).thenThrow(new DataIntegrityViolationException("unique"));

        assertThatThrownBy(() -> userService.register(new User("john@example.com", "john", "password123", "USER")))
                .satisfies(e -> assertStatus(e, AccountRuleException.Kind.CONFLICT));
    }

    @Test
    void register_withPasswordOver72Bytes_throws400() {
        String longPassword = "\u00e9".repeat(40);   // 40 characters, 80 bytes

        assertThatThrownBy(() -> userService.register(new User("john@example.com", "john", longPassword, "USER")))
                .satisfies(e -> assertStatus(e, AccountRuleException.Kind.INVALID_INPUT));
        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    @Test
    void getByUsername_whenMissing_throwsNotFound() {
        when(userRepository.findByUsernameIgnoreCase("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getByUsername("ghost"))
                .isInstanceOf(UserNotFoundException.class);
    }

    // ---------- change password ----------

    private User accountWithPassword(Long id, String password) {
        User account = new User("john@example.com", "john", passwordEncoder.encode(password), "USER");
        ReflectionTestUtils.setField(account, "id", id);   // User has no setter for its id
        return account;
    }

    @Test
    void changePassword_withTheRightCurrentPassword_savesAHashOfTheNewOne() {
        User john = accountWithPassword(4L, "old-password-1");
        when(userRepository.findById(4L)).thenReturn(Optional.of(john));

        userService.changePassword(4L, "old-password-1", "new-password-2");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        String savedHash = captor.getValue().getPassword();
        assertThat(savedHash).isNotEqualTo("new-password-2");
        assertThat(passwordEncoder.matches("new-password-2", savedHash)).isTrue();
        assertThat(passwordEncoder.matches("old-password-1", savedHash)).isFalse();
    }

    @Test
    void changePassword_withTheWrongCurrentPassword_throws400AndDoesNotSave() {
        User john = accountWithPassword(4L, "old-password-1");
        when(userRepository.findById(4L)).thenReturn(Optional.of(john));

        assertThatThrownBy(() -> userService.changePassword(4L, "guessed-password", "new-password-2"))
                .satisfies(e -> assertStatus(e, AccountRuleException.Kind.INVALID_INPUT))
                .hasMessageContaining("current password");
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void changePassword_toTheSamePassword_throws400AndDoesNotSave() {
        User john = accountWithPassword(4L, "old-password-1");
        when(userRepository.findById(4L)).thenReturn(Optional.of(john));

        assertThatThrownBy(() -> userService.changePassword(4L, "old-password-1", "old-password-1"))
                .satisfies(e -> assertStatus(e, AccountRuleException.Kind.INVALID_INPUT))
                .hasMessageContaining("different");
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void changePassword_forAnUnknownAccount_throwsNotFound() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.changePassword(99L, "old-password-1", "new-password-2"))
                .isInstanceOf(UserNotFoundException.class);
    }

    // bcrypt only reads 72 bytes, so 72 is the last length that is accepted
    @Test
    void changePassword_withANewPasswordOfExactly72Bytes_isAccepted() {
        User john = accountWithPassword(4L, "old-password-1");
        when(userRepository.findById(4L)).thenReturn(Optional.of(john));
        String longestAllowed = "a".repeat(72);

        userService.changePassword(4L, "old-password-1", longestAllowed);

        verify(userRepository).save(john);
    }

    @Test
    void changePassword_withANewPasswordOf73Bytes_throws400BeforeLoadingTheAccount() {
        String oneByteTooLong = "a".repeat(73);

        assertThatThrownBy(() -> userService.changePassword(4L, "old-password-1", oneByteTooLong))
                .satisfies(e -> assertStatus(e, AccountRuleException.Kind.INVALID_INPUT));
        verify(userRepository, never()).findById(any());
        verify(userRepository, never()).save(any(User.class));
    }

    // ---------- change username ----------

    @Test
    void updateUsername_toAFreeName_savesIt() {
        User john = accountWithPassword(4L, "password123");
        when(userRepository.findById(4L)).thenReturn(Optional.of(john));
        when(userRepository.findByUsernameIgnoreCase("johnny")).thenReturn(Optional.empty());
        when(userRepository.saveAndFlush(john)).thenReturn(john);

        User updated = userService.updateUsername(4L, "johnny");

        assertThat(updated.getUsername()).isEqualTo("johnny");
        verify(userRepository).saveAndFlush(john);
    }

    // the clash check finds the user's own row, which must not count as someone else having the name
    @Test
    void updateUsername_changingOnlyTheCapitalisation_isAllowed() {
        User john = accountWithPassword(4L, "password123");
        when(userRepository.findById(4L)).thenReturn(Optional.of(john));
        when(userRepository.findByUsernameIgnoreCase("John")).thenReturn(Optional.of(john));
        when(userRepository.saveAndFlush(john)).thenReturn(john);

        User updated = userService.updateUsername(4L, "John");

        assertThat(updated.getUsername()).isEqualTo("John");
    }

    @Test
    void updateUsername_toANameSomeoneElseHas_throws409AndDoesNotSave() {
        User john = accountWithPassword(4L, "password123");
        User mary = accountWithPassword(5L, "password456");
        when(userRepository.findById(4L)).thenReturn(Optional.of(john));
        when(userRepository.findByUsernameIgnoreCase("mary")).thenReturn(Optional.of(mary));

        assertThatThrownBy(() -> userService.updateUsername(4L, "mary"))
                .satisfies(e -> assertStatus(e, AccountRuleException.Kind.CONFLICT));
        assertThat(john.getUsername()).isEqualTo("john");
        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    @Test
    void updateUsername_whenDatabaseRejectsDuplicate_throws409() {
        User john = accountWithPassword(4L, "password123");
        when(userRepository.findById(4L)).thenReturn(Optional.of(john));
        when(userRepository.findByUsernameIgnoreCase("johnny")).thenReturn(Optional.empty());
        when(userRepository.saveAndFlush(john)).thenThrow(new DataIntegrityViolationException("unique"));

        assertThatThrownBy(() -> userService.updateUsername(4L, "johnny"))
                .satisfies(e -> assertStatus(e, AccountRuleException.Kind.CONFLICT));
    }
}