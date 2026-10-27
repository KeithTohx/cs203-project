package csd.tripsense.user;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.Column;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.OneToOne;
import jakarta.persistence.CascadeType;
import jakarta.persistence.FetchType;
import csd.tripsense.user.preference.UserPreference;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// No @Table: Hibernate names the table "user", matching schema.sql and the other entities.
@Entity
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private Long id;

    @NotBlank(message = "username must not be blank")
    @Pattern(regexp = "^[A-Za-z0-9_.-]+$",
            message = "username may only contain letters, digits, '.', '_' and '-'")
    @Size(min = 3, max = 30, message = "username must be between 3 and 30 characters")
    @Column(nullable = false, unique = true)
    private String username;

    @NotBlank(message = "email must not be blank")
    @Email(message = "email must be a valid email address")
    @Column(nullable = false, unique = true)
    private String email;

    // Stored as a BCrypt hash (see UserServiceImpl). WRITE_ONLY: accepted in requests, never returned in responses.
    @NotBlank(message = "password must not be blank")
    @Size(min = 8, max = 72, message = "password must be between 8 and 72 characters")
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Column(nullable = false)
    private String password;

    // "USER" or "ADMIN". Always forced to "USER" on registration (see UserServiceImpl).
    @Column(nullable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @Enumerated(EnumType.STRING)
    private Role role = Role.USER;

    @com.fasterxml.jackson.annotation.JsonIgnore
    @OneToOne(mappedBy = "user", cascade = CascadeType.REMOVE, fetch = FetchType.LAZY)
    private UserPreference preferences;

    // null (rows seeded by data-dev.sql, or created before this column existed) counts as enabled.
    @Column
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private Boolean enabled = true;

    protected User() {
    }

    public User(String email, String username, String password, String role) {
        this.email = email;
        this.username = username;
        this.password = password;
        this.role = role == null ? Role.USER : Role.valueOf(role);
    }

    public Long getId() { return id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getRole() { return role.name(); }
    public void setRole(Role role) { this.role = java.util.Objects.requireNonNull(role); }

    public boolean isEnabled() { return enabled == null || enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

}
