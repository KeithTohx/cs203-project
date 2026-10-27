package csd.tripsense.auth;

import csd.tripsense.user.User;
import csd.tripsense.user.UserRepository;
import jakarta.validation.Validator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;

/** Creates an administrator from deployment secrets, never from public demo credentials. */
@Component
@Profile("!dev")
public class AdminBootstrap implements ApplicationRunner {
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final Validator validator;
    private final String email;
    private final String username;
    private final String password;

    public AdminBootstrap(UserRepository users, PasswordEncoder encoder, Validator validator,
                          @Value("${app.bootstrap-admin.email:}") String email,
                          @Value("${app.bootstrap-admin.username:}") String username,
                          @Value("${app.bootstrap-admin.password:}") String password) {
        this.users = users;
        this.encoder = encoder;
        this.validator = validator;
        this.email = email;
        this.username = username;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        // Quarantine the old public demo admin when upgrading an existing deployment.
        users.findByEmailIgnoreCase("admin@example.com").ifPresent(user -> {
            if ("ADMIN".equals(user.getRole()) && encoder.matches("admin123", user.getPassword())) {
                user.setEnabled(false);
                users.save(user);
            }
        });
        if (email.isBlank() && username.isBlank() && password.isBlank()) return;
        User account = new User(email, username, password, "ADMIN");
        if (email.isBlank() || username.isBlank() || password.isBlank()
                || !validator.validate(account).isEmpty()
                || password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72
                || password.equals("password123") || password.equals("admin123")) {
            throw new IllegalStateException("Set valid bootstrap admin email, username and a password of 12 to 72 bytes");
        }
        var existing = users.findByEmailIgnoreCase(email);
        if (existing.isPresent()) {
            User user = existing.get();
            if (!"ADMIN".equals(user.getRole()) || !user.isEnabled()) {
                throw new IllegalStateException("Bootstrap email belongs to an existing non-admin or disabled account");
            }
            return;
        }
        if (users.existsByUsernameIgnoreCase(username)) {
            throw new IllegalStateException("Bootstrap username is already in use");
        }
        account.setPassword(encoder.encode(password));
        users.saveAndFlush(account);
    }
}
