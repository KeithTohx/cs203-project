package csd.tripsense.auth;

import csd.tripsense.user.User;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * The logged-in user as Spring Security sees them. It is stored in the session at login, so
 * controllers can read it with @AuthenticationPrincipal CurrentUser without another database query.
 *
 * Spring's UserDetails contract calls the login identity "username"; in TripSense that is the
 * EMAIL. The account's own username is exposed separately as getAccountUsername().
 */
public class CurrentUser implements UserDetails, CredentialsContainer {

    private static final long serialVersionUID = 1L;

    private final Long id;
    private final String email;
    private final String accountUsername;
    private String password;          // BCrypt hash; needed for the login check, wiped right after
    private final String role;        // "USER" or "ADMIN"
    private final boolean enabled;    // false = Spring Security refuses to log this account in

    public CurrentUser(Long id, String email, String accountUsername, String password, String role) {
        this(id, email, accountUsername, password, role, true);
    }

    public CurrentUser(Long id, String email, String accountUsername, String password, String role,
                       boolean enabled) {
        this.id = id;
        this.email = email;
        this.accountUsername = accountUsername;
        this.password = password;
        this.role = role;
        this.enabled = enabled;
    }

    public static CurrentUser from(User user) {
        return new CurrentUser(user.getId(), user.getEmail(), user.getUsername(),
                user.getPassword(), user.getRole(), user.isEnabled());
    }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getAccountUsername() { return accountUsername; }
    public String getRole() { return role; }

    // ---- UserDetails ----

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Override
    public String getPassword() { return password; }

    /** Login identity = email (see class comment). */
    @Override
    public String getUsername() { return email; }

    @Override
    public boolean isAccountNonExpired() { return true; }

    @Override
    public boolean isAccountNonLocked() { return true; }

    @Override
    public boolean isCredentialsNonExpired() { return true; }

    @Override
    public boolean isEnabled() { return enabled; }

    /** Spring calls this after a successful login so the hash is not kept in the session. */
    @Override
    public void eraseCredentials() {
        this.password = null;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof CurrentUser other && Objects.equals(email, other.email));
    }

    @Override
    public int hashCode() {
        return Objects.hash(email);
    }
}