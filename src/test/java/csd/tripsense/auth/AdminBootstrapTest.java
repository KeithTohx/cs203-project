package csd.tripsense.auth;

import csd.tripsense.user.*;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminBootstrapTest {
    private final UserRepository users = mock(UserRepository.class);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    private void run(String email, String username, String password) {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            new AdminBootstrap(users, encoder, validator, email, username, password)
                    .run(new DefaultApplicationArguments());
        }
    }

    @Test
    void unsetSecretsNeverCreateAccounts() {
        run("", "", "");
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void validSecretsCreateAnAdminWithAHash() {
        run("manager@example.com", "manager", "strong-secret-123");
        var capture = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(capture.capture());
        assertThat(capture.getValue().getRole()).isEqualTo("ADMIN");
        assertThat(encoder.matches("strong-secret-123", capture.getValue().getPassword())).isTrue();
    }

    @Test
    void incompleteOrWeakSecretsFailWithoutCreatingAnAccount() {
        assertThatThrownBy(() -> run("manager@example.com", "manager", "admin123"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> run("", "manager", "strong-secret-123"))
                .isInstanceOf(IllegalStateException.class);
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void bootstrapNeverPromotesAnExistingUser() {
        when(users.findByEmailIgnoreCase("person@example.com"))
                .thenReturn(Optional.of(new User("person@example.com", "person", "hash", "USER")));
        assertThatThrownBy(() -> run("person@example.com", "person", "strong-secret-123"))
                .isInstanceOf(IllegalStateException.class);
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void publicDemoAdminIsDisabledOnUpgrade() {
        User demo = new User("admin@example.com", "admin", encoder.encode("admin123"), "ADMIN");
        when(users.findByEmailIgnoreCase("admin@example.com")).thenReturn(Optional.of(demo));
        run("", "", "");
        assertThat(demo.isEnabled()).isFalse();
        verify(users).save(demo);
    }

    @Test
    void existingSecureAdminIsNotReset() {
        User admin = new User("manager@example.com", "manager", encoder.encode("previous-secret-123"), "ADMIN");
        when(users.findByEmailIgnoreCase("manager@example.com")).thenReturn(Optional.of(admin));
        run("manager@example.com", "manager", "strong-secret-123");
        assertThat(encoder.matches("previous-secret-123", admin.getPassword())).isTrue();
        verify(users, never()).saveAndFlush(any());
    }
}
