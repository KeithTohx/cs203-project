package csd.tripsense.auth;

import csd.tripsense.user.User;
import csd.tripsense.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserDetailsServiceImplTest {

    private UserRepository userRepository;
    private UserDetailsServiceImpl service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        service = new UserDetailsServiceImpl(userRepository);
    }

    @Test
    void loadUserByUsername_returnsCurrentUserWithEmailHashAndRole() {
        when(userRepository.findByEmailIgnoreCase("john@example.com"))
                .thenReturn(Optional.of(new User("john@example.com", "john", "the-hash", "ADMIN")));

        UserDetails details = service.loadUserByUsername("john@example.com");

        assertThat(details).isInstanceOf(CurrentUser.class);
        assertThat(details.getUsername()).isEqualTo("john@example.com");
        assertThat(details.getPassword()).isEqualTo("the-hash");
        assertThat(details.getAuthorities()).extracting("authority").containsExactly("ROLE_ADMIN");
        assertThat(((CurrentUser) details).getAccountUsername()).isEqualTo("john");
    }

    @Test
    void loadUserByUsername_whenMissing_throwsUsernameNotFound() {
        when(userRepository.findByEmailIgnoreCase("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadUserByUsername("nobody@example.com"))
                .isInstanceOf(UsernameNotFoundException.class);
    }
}
