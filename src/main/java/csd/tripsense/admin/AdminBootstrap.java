package csd.tripsense.admin;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import csd.tripsense.audit.AuditService;
import csd.tripsense.user.User;
import csd.tripsense.user.UserRepository;

/**
 * Creates the first admin account on a server where nobody can log in as one yet.
 *
 * Registration always makes a USER, and only an existing admin can promote someone, so the first
 * admin has to come from outside the app. The settings can come from application.properties or from
 * environment variables, whichever is easier where the app is deployed:
 *
 *   property                       environment variable
 *   tripsense.admin.email          ADMIN_EMAIL
 *   tripsense.admin.username       ADMIN_USERNAME
 *   tripsense.admin.password-hash  ADMIN_PASSWORD_HASH   a BCrypt hash, stored as it is   (preferred)
 *   tripsense.admin.password       ADMIN_PASSWORD        plain text, hashed here
 *
 * The hash is the one to use when the settings are committed to the repository, because the real
 * password is then never written down anywhere. A hash of a LONG RANDOM password cannot be worked
 * back to it. A hash of a short or guessable one can be cracked offline by anyone who can read the
 * repository, so do not use a word, a pattern or one of the seeded accounts' passwords.
 * Plain text belongs in environment variables only, never in a committed file.
 *
 * It only ever CREATES. It never changes an account that already exists:
 * - not its password, so changing it in the app is not undone by the next restart
 * - not its role, because registration does not check that an email belongs to whoever typed it,
 *   so promoting a stranger's account just because the email matches could hand them admin
 *
 * With nothing set it does nothing, so it is inert on a developer's machine.
 * Runs after data.sql, so the seeded accounts are already there when it looks.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    /** What a run did, so a test can check it and the log can say it in words. */
    enum Outcome {
        NOT_CONFIGURED, INCOMPLETE, BAD_PASSWORD, BAD_HASH, CREATED, ALREADY_ADMIN, EXISTS_NOT_ADMIN, USERNAME_TAKEN
    }

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private static final int MIN_PASSWORD_CHARS = 8;
    private static final int MAX_PASSWORD_BYTES = 72;   // BCrypt only reads this much, see UserServiceImpl

    // what BCryptPasswordEncoder accepts: $2a$, $2b$ or $2y$, a two digit cost, then 53 characters.
    // htpasswd writes $2y$, spring reads it the same as $2a$
    private static final Pattern BCRYPT_HASH = Pattern.compile("^\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}$");

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;
    private final String email;
    private final String username;
    private final String password;
    private final String passwordHash;

    public AdminBootstrap(UserRepository users, PasswordEncoder passwordEncoder, AuditService audit,
                          @Value("${tripsense.admin.email:${ADMIN_EMAIL:}}") String email,
                          @Value("${tripsense.admin.username:${ADMIN_USERNAME:}}") String username,
                          @Value("${tripsense.admin.password:${ADMIN_PASSWORD:}}") String password,
                          @Value("${tripsense.admin.password-hash:${ADMIN_PASSWORD_HASH:}}") String passwordHash) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.email = email;
        this.username = username;
        this.password = password;
        this.passwordHash = passwordHash;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            switch (bootstrap()) {
                case NOT_CONFIGURED -> log.debug("Admin bootstrap: no admin account is configured, nothing to do.");
                case INCOMPLETE -> log.warn("Admin bootstrap skipped: the admin email, username and a password "
                        + "(or password hash) must all be set, and at least one is empty.");
                case BAD_PASSWORD -> log.warn("Admin bootstrap skipped: the admin password must be at least "
                        + MIN_PASSWORD_CHARS + " characters and at most " + MAX_PASSWORD_BYTES + " bytes.");
                case BAD_HASH -> log.warn("Admin bootstrap skipped: the admin password hash is not a BCrypt hash. "
                        + "It should start with $2a$, $2b$ or $2y$ and be 60 characters long.");
                case CREATED -> log.warn("Admin bootstrap: created the admin account {}. Log in and change the "
                        + "password. If it came from a plain text password setting, remove that setting now.",
                        email.trim());
                case ALREADY_ADMIN -> log.info("Admin bootstrap: {} is already an admin, nothing to do.", email.trim());
                case EXISTS_NOT_ADMIN -> log.warn("Admin bootstrap: {} already exists as a normal account, and "
                        + "was NOT changed. Use a different admin email.", email.trim());
                case USERNAME_TAKEN -> log.warn("Admin bootstrap skipped: the username '{}' is already taken. "
                        + "Use a different admin username.", username.trim());
            }
        } catch (RuntimeException e) {
            // an admin that could not be created must not stop the whole site starting
            log.error("Admin bootstrap failed: {}", e.getMessage());
        }
    }

    /**
     * Does the work and says what happened. Package private so a test can call it without spring.
     * Never logs or returns the password or the hash.
     */
    Outcome bootstrap() {
        boolean hasHash = !passwordHash.isBlank();
        boolean hasPassword = !password.isEmpty();

        boolean anySet = !email.isBlank() || !username.isBlank() || hasHash || hasPassword;
        if (!anySet) {
            return Outcome.NOT_CONFIGURED;
        }
        if (email.isBlank() || username.isBlank() || !(hasHash || hasPassword)) {
            return Outcome.INCOMPLETE;
        }

        // the hash wins when both are given. a hash that is not a real one would make an account that
        // nobody could ever log into, so it is refused instead of saved
        if (hasHash) {
            if (!BCRYPT_HASH.matcher(passwordHash.trim()).matches()) {
                return Outcome.BAD_HASH;
            }
        } else if (password.length() < MIN_PASSWORD_CHARS
                || password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            // characters for the minimum, bytes for the maximum: one character can take several bytes,
            // and BCrypt reads bytes
            return Outcome.BAD_PASSWORD;
        }

        String cleanEmail = email.trim();
        String cleanUsername = username.trim();

        Optional<User> existing = users.findByEmailIgnoreCase(cleanEmail);
        if (existing.isPresent()) {
            return "ADMIN".equals(existing.get().getRole()) ? Outcome.ALREADY_ADMIN : Outcome.EXISTS_NOT_ADMIN;
        }
        if (users.existsByUsernameIgnoreCase(cleanUsername)) {
            return Outcome.USERNAME_TAKEN;
        }

        // hashed only now that it is certain to be used, BCrypt is deliberately slow
        String stored = hasHash ? passwordHash.trim() : passwordEncoder.encode(password);
        User admin = users.saveAndFlush(new User(cleanEmail, cleanUsername, stored, "ADMIN"));
        audit.record(AuditService.REGISTER, admin.getId(), admin.getEmail(), admin.getId(),
                "admin account created at startup from the admin settings");
        return Outcome.CREATED;
    }
}
