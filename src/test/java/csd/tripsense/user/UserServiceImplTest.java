package csd.tripsense.user;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

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
        userService = new UserServiceImpl(userRepository, passwordEncoder);
    }

    private static void assertStatus(Throwable e, HttpStatus expected) {
        assertThat(e).isInstanceOfSatisfying(ResponseStatusException.class,
                r -> assertThat(r.getStatusCode()).isEqualTo(expected));
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
                .satisfies(e -> assertStatus(e, HttpStatus.CONFLICT))
                .hasMessageContaining("email");
        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    @Test
    void register_withDuplicateUsername_throws409AndDoesNotSave() {
        when(userRepository.existsByUsernameIgnoreCase("john")).thenReturn(true);

        assertThatThrownBy(() -> userService.register(new User("john@example.com", "john", "password123", "USER")))
                .satisfies(e -> assertStatus(e, HttpStatus.CONFLICT))
                .hasMessageContaining("username");
        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    @Test
    void register_whenDatabaseRejectsDuplicate_throws409() {
        when(userRepository.saveAndFlush(any(User.class))).thenThrow(new DataIntegrityViolationException("unique"));

        assertThatThrownBy(() -> userService.register(new User("john@example.com", "john", "password123", "USER")))
                .satisfies(e -> assertStatus(e, HttpStatus.CONFLICT));
    }

    @Test
    void register_withPasswordOver72Bytes_throws400() {
        String longPassword = "\u00e9".repeat(40);   // 40 characters, 80 bytes

        assertThatThrownBy(() -> userService.register(new User("john@example.com", "john", longPassword, "USER")))
                .satisfies(e -> assertStatus(e, HttpStatus.BAD_REQUEST));
        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    @Test
    void getByUsername_whenMissing_throwsNotFound() {
        when(userRepository.findByUsernameIgnoreCase("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getByUsername("ghost"))
                .isInstanceOf(UserNotFoundException.class);
    }
}