package csd.tripsense.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import csd.tripsense.admin.AdminBootstrap.Outcome;
import csd.tripsense.audit.AuditService;
import csd.tripsense.user.User;
import csd.tripsense.user.UserRepository;

/** 
 * the first-admin startup class. a mocked repository, so no database and no spring context.
 * the encoder is the real BCrypt (at its cheapest setting), so the tests can check a password was hashed
 */
class AdminBootstrapTest {

    private static final String PASSWORD = "a-long-enough-password";

    private UserRepository users;
    private AuditService audit;
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        audit = mock(AuditService.class);
        when(users.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(users.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private AdminBootstrap with(String email, String username, String password) {
        return new AdminBootstrap(users, encoder, audit, email, username, password, "");
    }

    private AdminBootstrap withHash(String email, String username, String hash) {
        return new AdminBootstrap(users, encoder, audit, email, username, "", hash);
    }

    // a real BCrypt hash of a password, the way someone would generate one to put in the settings
    private String hashOf(String password) {
        return encoder.encode(password);
    }

    private AdminBootstrap configured() {
        return with("owner@example.com", "owner", PASSWORD);
    }

    // ----- not set up -----

    @Test
    void withNothingSetItDoesNothingAtAll() {
        assertThat(with("", "", "").bootstrap()).isEqualTo(Outcome.NOT_CONFIGURED);

        verifyNoInteractions(users, audit);
    }

    @Test
    void blankSettingsCountAsNotSet() {
        assertThat(with("  ", "  ", "").bootstrap()).isEqualTo(Outcome.NOT_CONFIGURED);
    }

    @Test
    void settingOnlySomeOfThemIsIncompleteAndCreatesNothing() {
        assertThat(with("owner@example.com", "", "").bootstrap()).isEqualTo(Outcome.INCOMPLETE);
        assertThat(with("", "owner", PASSWORD).bootstrap()).isEqualTo(Outcome.INCOMPLETE);
        assertThat(with("owner@example.com", "owner", "").bootstrap()).isEqualTo(Outcome.INCOMPLETE);

        verify(users, never()).saveAndFlush(any());
    }

    // ----- the password -----

    @Test
    void aPasswordUnderEightCharactersIsRefused() {
        assertThat(with("owner@example.com", "owner", "short12").bootstrap()).isEqualTo(Outcome.BAD_PASSWORD);

        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void aPasswordOverSeventyTwoBytesIsRefused() {
        assertThat(with("owner@example.com", "owner", "x".repeat(73)).bootstrap()).isEqualTo(Outcome.BAD_PASSWORD);
    }

    @Test
    void theLimitIsInBytesNotCharacters() {
        // 40 characters but 80 bytes: BCrypt would silently ignore the end of it
        assertThat(with("owner@example.com", "owner", "é".repeat(40)).bootstrap()).isEqualTo(Outcome.BAD_PASSWORD);
    }

    @Test
    void aPasswordOfExactlySeventyTwoBytesIsAccepted() {
        assertThat(with("owner@example.com", "owner", "x".repeat(72)).bootstrap()).isEqualTo(Outcome.CREATED);
    }

    @Test
    void aPasswordOfExactlyEightCharactersIsAccepted() {
        assertThat(with("owner@example.com", "owner", "12345678").bootstrap()).isEqualTo(Outcome.CREATED);
    }

    // ----- creating the account -----

    @Test
    void itCreatesAnAdminWithTheGivenEmailAndUsername() {
        assertThat(configured().bootstrap()).isEqualTo(Outcome.CREATED);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("owner@example.com");
        assertThat(saved.getValue().getUsername()).isEqualTo("owner");
        assertThat(saved.getValue().getRole()).isEqualTo("ADMIN");
    }

    @Test
    void thePasswordIsStoredHashedAndNeverAsTyped() {
        configured().bootstrap();

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getPassword()).isNotEqualTo(PASSWORD).startsWith("$2");
        assertThat(encoder.matches(PASSWORD, saved.getValue().getPassword())).isTrue();
    }

    @Test
    void spacesAroundTheEmailAndUsernameAreIgnored() {
        with("  owner@example.com ", " owner  ", PASSWORD).bootstrap();

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("owner@example.com");
        assertThat(saved.getValue().getUsername()).isEqualTo("owner");
    }

    @Test
    void theEmailIsLookedUpWithoutCaringAboutCase() {
        with("Owner@Example.COM", "owner", PASSWORD).bootstrap();

        // findByEmailIgnoreCase, so Owner@... finds owner@... and a second account is not made
        verify(users).findByEmailIgnoreCase("Owner@Example.COM");
    }

    @Test
    void creatingTheAdminIsAudited() {
        configured().bootstrap();

        verify(audit).record(eq(AuditService.REGISTER), any(), eq("owner@example.com"), any(),
                eq("admin account created at startup from the admin settings"));
    }

    // ----- an account that is already there -----

    @Test
    void anExistingAdminIsLeftAloneSoRestartsDoNothing() {
        when(users.findByEmailIgnoreCase("owner@example.com"))
                .thenReturn(Optional.of(new User("owner@example.com", "owner", "somehash", "ADMIN")));

        assertThat(configured().bootstrap()).isEqualTo(Outcome.ALREADY_ADMIN);

        verify(users, never()).saveAndFlush(any());
        verifyNoInteractions(audit);
    }

    @Test
    void aNormalAccountWithThatEmailIsNeverPromotedOrChanged() {
        User stranger = new User("owner@example.com", "someone", "theirhash", "USER");
        when(users.findByEmailIgnoreCase("owner@example.com")).thenReturn(Optional.of(stranger));

        // registration does not check the email belongs to the person who typed it, so this could be
        // someone else's account. promoting it would hand them admin
        assertThat(configured().bootstrap()).isEqualTo(Outcome.EXISTS_NOT_ADMIN);

        verify(users, never()).saveAndFlush(any());
        assertThat(stranger.getRole()).isEqualTo("USER");
        assertThat(stranger.getPassword()).isEqualTo("theirhash");
    }

    @Test
    void aTakenUsernameStopsItAndCreatesNothing() {
        when(users.existsByUsernameIgnoreCase("owner")).thenReturn(true);

        assertThat(configured().bootstrap()).isEqualTo(Outcome.USERNAME_TAKEN);

        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void runningItTwiceCreatesTheAccountOnlyOnce() {
        AdminBootstrap bootstrap = configured();
        User created = new User("owner@example.com", "owner", "somehash", "ADMIN");
        when(users.findByEmailIgnoreCase("owner@example.com"))
                .thenReturn(Optional.empty(), Optional.of(created));

        assertThat(bootstrap.bootstrap()).isEqualTo(Outcome.CREATED);
        assertThat(bootstrap.bootstrap()).isEqualTo(Outcome.ALREADY_ADMIN);   // the next restart

        verify(users).saveAndFlush(any());   // once, not twice
    }

    // ----- it must never stop the site starting -----

    @Test
    void aFailureWhileSavingDoesNotStopTheApplicationStarting() {
        when(users.saveAndFlush(any(User.class))).thenThrow(new RuntimeException("database is locked"));

        assertThatCode(() -> configured().run(null)).doesNotThrowAnyException();
    }

    @Test
    void runDoesNotThrowWhenNothingIsConfigured() {
        assertThatCode(() -> with("", "", "").run(null)).doesNotThrowAnyException();
    }

    // ----- a password hash, for when the settings are committed to the repository -----

    @Test
    void aBcryptHashIsStoredExactlyAsGivenAndNeverHashedAgain() {
        String hash = hashOf("a-long-random-password-1234");

        assertThat(withHash("owner@example.com", "owner", hash).bootstrap()).isEqualTo(Outcome.CREATED);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(saved.capture());
        // hashing a hash would make an account nobody could log into
        assertThat(saved.getValue().getPassword()).isEqualTo(hash);
        assertThat(saved.getValue().getRole()).isEqualTo("ADMIN");
    }

    @Test
    void thePasswordBehindAStoredHashCanLogIn() {
        String hash = hashOf("a-long-random-password-1234");
        withHash("owner@example.com", "owner", hash).bootstrap();

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(saved.capture());
        assertThat(encoder.matches("a-long-random-password-1234", saved.getValue().getPassword())).isTrue();
        assertThat(encoder.matches("some-other-password", saved.getValue().getPassword())).isFalse();
    }

    @Test
    void aHashMadeByHtpasswdIsAccepted() {
        // htpasswd -bnBC 10 writes $2y$, and spring's encoder reads it the same as $2a$
        String y = "$2y$10$" + "a".repeat(53);

        assertThat(withHash("owner@example.com", "owner", y).bootstrap()).isEqualTo(Outcome.CREATED);
    }

    @Test
    void spacesAroundAHashAreIgnored() {
        String hash = hashOf("a-long-random-password-1234");

        withHash("owner@example.com", "owner", "  " + hash + "\n").bootstrap();

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getPassword()).isEqualTo(hash);
    }

    @Test
    void somethingThatIsNotABcryptHashIsRefusedAndNothingIsSaved() {
        // an account with a bad hash could never be logged into, so it is better not to make it
        assertThat(withHash("owner@example.com", "owner", "not-a-hash").bootstrap()).isEqualTo(Outcome.BAD_HASH);
        assertThat(withHash("owner@example.com", "owner", "$2a$10$tooshort").bootstrap()).isEqualTo(Outcome.BAD_HASH);
        assertThat(withHash("owner@example.com", "owner", "$1$10$" + "a".repeat(53)).bootstrap())
                .isEqualTo(Outcome.BAD_HASH);
        // a plain password pasted into the hash setting by mistake
        assertThat(withHash("owner@example.com", "owner", PASSWORD).bootstrap()).isEqualTo(Outcome.BAD_HASH);

        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void theHashWinsWhenBothAPasswordAndAHashAreGiven() {
        String hash = hashOf("the-password-behind-the-hash");
        AdminBootstrap both = new AdminBootstrap(users, encoder, audit, "owner@example.com", "owner",
                PASSWORD, hash);

        both.bootstrap();

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getPassword()).isEqualTo(hash);
        assertThat(encoder.matches(PASSWORD, saved.getValue().getPassword())).isFalse();
    }

    @Test
    void aHashWithNoEmailOrUsernameIsIncomplete() {
        String hash = hashOf("a-long-random-password-1234");

        assertThat(withHash("", "owner", hash).bootstrap()).isEqualTo(Outcome.INCOMPLETE);
        assertThat(withHash("owner@example.com", "", hash).bootstrap()).isEqualTo(Outcome.INCOMPLETE);
    }

    @Test
    void anEmailAndUsernameWithNeitherAPasswordNorAHashIsIncomplete() {
        assertThat(with("owner@example.com", "owner", "").bootstrap()).isEqualTo(Outcome.INCOMPLETE);
    }

    @Test
    void aHashStillNeverTouchesAnExistingAccount() {
        User stranger = new User("owner@example.com", "someone", "theirhash", "USER");
        when(users.findByEmailIgnoreCase("owner@example.com")).thenReturn(Optional.of(stranger));

        assertThat(withHash("owner@example.com", "owner", hashOf("a-long-random-password-1234")).bootstrap())
                .isEqualTo(Outcome.EXISTS_NOT_ADMIN);

        verify(users, never()).saveAndFlush(any());
        assertThat(stranger.getPassword()).isEqualTo("theirhash");
        assertThat(stranger.getRole()).isEqualTo("USER");
    }

    @Test
    void aBadHashDoesNotStopTheApplicationStarting() {
        assertThatCode(() -> withHash("owner@example.com", "owner", "not-a-hash").run(null))
                .doesNotThrowAnyException();
    }
}
