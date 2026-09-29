package csd.tripsense.user;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// No @Table: Hibernate names the table "user", matching schema.sql and the other entities.
@Entity
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "username must not be blank")
    @Size(min = 3, max = 30, message = "username must be between 3 and 30 characters")
    @Column(nullable = false, unique = true)
    private String username;

    @NotBlank(message = "email must not be blank")
    @Email(message = "email must be a valid email address")
    @Column(nullable = false, unique = true)
    private String email;

    // Plaintext to match data.sql. WRITE_ONLY: accepted in requests, never returned in responses.
    @NotBlank(message = "password must not be blank")
    @Size(min = 8, max = 100, message = "password must be at least 8 characters")
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Column(nullable = false)
    private String password;

    // "USER" or "ADMIN". Always forced to "USER" on registration (see UserServiceImpl).
    @Column(nullable = false)
    private String role = "USER";

    protected User() {
    }

    public User(String email, String username, String password, String role) {
        this.email = email;
        this.username = username;
        this.password = password;
        this.role = role;
    }

    public Long getId() { return id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getRole() { return role; }

}
