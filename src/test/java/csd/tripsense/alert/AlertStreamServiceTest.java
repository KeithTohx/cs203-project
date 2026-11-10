package csd.tripsense.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import csd.tripsense.auth.CurrentUser;
import csd.tripsense.user.User;
import csd.tripsense.user.UserRepository;

// unit tests for AlertStreamService, which pushes new alerts to the open streams of the right user
// every emitter is swapped for a recording one, so each test can check what each stream received
@ExtendWith(MockitoExtension.class)
@DisplayName("AlertStreamService Unit Tests")
class AlertStreamServiceTest {

    @Mock
    private UserRepository userRepository;

    private SessionRegistryImpl sessionRegistry;
    private List<RecordingEmitter> emitters;
    private AlertStreamService streamService;

    @BeforeEach
    void setUp() {
        sessionRegistry = new SessionRegistryImpl();
        emitters = new ArrayList<>();
        streamService = new AlertStreamService(userRepository, sessionRegistry) {
            @Override
            protected SseEmitter newEmitter() {
                RecordingEmitter emitter = new RecordingEmitter();
                emitters.add(emitter);
                return emitter;
            }
        };
    }

    @AfterEach
    void tearDown() {
        streamService.stop();
        SecurityContextHolder.clearContext();
    }

    // ===== HELPERS =====

    // a session that looks logged in to the stream service, for an enabled account
    private MockHttpSession loggedInSession(long userId) {
        String email = "user" + userId + "@example.com";
        CurrentUser principal = new CurrentUser(userId, email, "person", null, "USER");

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));

        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        sessionRegistry.registerNewSession(session.getId(), principal);

        User account = new User(email, "person", "hash", "USER");
        when(userRepository.findById(userId)).thenReturn(Optional.of(account));
        return session;
    }

    private AlertCreatedEvent alertFor(long userId) {
        AlertResponse alert = new AlertResponse(1L, 2L, 3L, "Trip", null, null, 4L, "News", "Source",
                "https://example.com/news", "Disruption", Instant.now(), null, EmailStatus.PENDING, null);
        return new AlertCreatedEvent(userId, alert);
    }

    // ===== DELIVER TESTS =====

    @Test
    @DisplayName("Should push the alert to every session of the recipient and to no one else")
    void deliver_ShouldReachOnlyRecipient_WhenRecipientHasTwoSessions() {
        // Arrange
        streamService.subscribe(1L, loggedInSession(1L));
        streamService.subscribe(1L, loggedInSession(1L));
        streamService.subscribe(2L, loggedInSession(2L));

        // Act
        streamService.deliver(alertFor(1L));

        // Assert
        assertEquals(1, emitters.get(0).alerts().size());
        assertEquals(1, emitters.get(1).alerts().size());
        assertTrue(emitters.get(2).alerts().isEmpty());
    }

    @Test
    @DisplayName("Should close the stream instead of pushing when the session expired or logged out")
    void deliver_ShouldCloseStream_WhenSessionExpiredOrLoggedOut() {
        // Arrange
        MockHttpSession expired = loggedInSession(1L);
        streamService.subscribe(1L, expired);
        MockHttpSession loggedOut = loggedInSession(1L);
        streamService.subscribe(1L, loggedOut);

        sessionRegistry.getSessionInformation(expired.getId()).expireNow();
        loggedOut.invalidate();

        // Act
        streamService.deliver(alertFor(1L));

        // Assert
        for (RecordingEmitter emitter : emitters) {
            assertTrue(emitter.alerts().isEmpty());
            assertTrue(emitter.isClosed());
        }
    }

    @Test
    @DisplayName("Should close an open stream when the account is disabled")
    void deliver_ShouldCloseStream_WhenAccountDisabled() {
        // Arrange
        streamService.subscribe(1L, loggedInSession(1L));
        User disabled = new User("user1@example.com", "person", "hash", "USER");
        disabled.setEnabled(false);
        when(userRepository.findById(1L)).thenReturn(Optional.of(disabled));

        // Act
        streamService.heartbeat();
        streamService.deliver(alertFor(1L));

        // Assert
        RecordingEmitter emitter = emitters.getFirst();
        assertTrue(emitter.isClosed());
        assertTrue(emitter.alerts().isEmpty());
    }

    @Test
    @DisplayName("Should still reach the other session when one connection is broken")
    void deliver_ShouldReachOtherSession_WhenOneConnectionBroken() {
        // Arrange
        streamService.subscribe(1L, loggedInSession(1L));
        streamService.subscribe(1L, loggedInSession(1L));
        emitters.getFirst().breakConnection();

        // Act
        streamService.deliver(alertFor(1L));

        // Assert
        assertTrue(emitters.getFirst().isClosed());
        assertEquals(1, emitters.getLast().alerts().size());
    }

    @Test
    @DisplayName("Should tell every session of the owner that alerts were removed, and no one else")
    void deliverRemoval_ShouldReachOnlyOwner() {
        // Arrange
        streamService.subscribe(1L, loggedInSession(1L));
        streamService.subscribe(1L, loggedInSession(1L));
        streamService.subscribe(2L, loggedInSession(2L));

        // Act
        streamService.deliverRemoval(new AlertsRemovedEvent(1L));

        // Assert
        assertTrue(emitters.get(0).received("alerts-changed"));
        assertTrue(emitters.get(1).received("alerts-changed"));
        assertFalse(emitters.get(2).received("alerts-changed"));
        // nothing about the deleted alerts is sent, the browser asks for the list again
        assertTrue(emitters.get(0).alerts().isEmpty());
    }

    @Test
    @DisplayName("Should do nothing when the owner has no open stream")
    void deliverRemoval_ShouldDoNothing_WhenOwnerNotConnected() {
        // Arrange
        streamService.subscribe(2L, loggedInSession(2L));

        // Act
        streamService.deliverRemoval(new AlertsRemovedEvent(1L));

        // Assert
        assertFalse(emitters.getFirst().received("alerts-changed"));
        assertFalse(emitters.getFirst().isClosed());
    }

    // ===== SUBSCRIBE TESTS =====

    @Test
    @DisplayName("Should keep the new connection when the old one closes while the new one registers")
    void subscribe_ShouldKeepReplacement_WhenOldConnectionClosesDuringRegistration() throws Exception {
        try (ExecutorService closer = Executors.newSingleThreadExecutor()) {
            // Arrange
            RegistrationRaceMap connections = new RegistrationRaceMap(closer);
            ReflectionTestUtils.setField(streamService, "connections", connections);
            streamService.subscribe(1L, loggedInSession(1L));
            RecordingEmitter original = emitters.getFirst();
            connections.closeDuringNextRegistration(original::complete);

            // Act
            streamService.subscribe(1L, loggedInSession(1L));
            streamService.deliver(alertFor(1L));

            // Assert
            assertTrue(original.isClosed());
            assertTrue(original.alerts().isEmpty());
            assertEquals(1, emitters.getLast().alerts().size());
        }
    }

    // closes the old connection at the exact moment the new one registers,
    // so the race happens every run instead of relying on a stress test to hit it
    private static class RegistrationRaceMap extends ConcurrentHashMap<Long, Set<Object>> {

        private final ExecutorService closer;
        private final AtomicBoolean armed = new AtomicBoolean();
        private Runnable closeOriginal;
        private Future<?> closing;

        RegistrationRaceMap(ExecutorService closer) {
            this.closer = closer;
        }

        void closeDuringNextRegistration(Runnable closeOriginal) {
            this.closeOriginal = closeOriginal;
            armed.set(true);
        }

        private void startClosing() {
            if (!armed.compareAndSet(true, false)) {
                return;
            }

            CompletableFuture<Void> started = new CompletableFuture<>();
            closing = closer.submit(() -> {
                started.complete(null);
                closeOriginal.run();
            });

            try {
                started.get(5, TimeUnit.SECONDS);
            } catch (Exception exception) {
                throw new AssertionError("closing the old connection did not start", exception);
            }
        }

        private void awaitClosing() {
            if (closing == null) {
                return;
            }

            try {
                closing.get(5, TimeUnit.SECONDS);
            } catch (Exception exception) {
                throw new AssertionError("closing the old connection did not finish", exception);
            }
        }

        @Override
        public Set<Object> computeIfAbsent(Long key, Function<? super Long, ? extends Set<Object>> factory) {
            Set<Object> result = super.computeIfAbsent(key, factory);
            // a broken implementation exposes the set here, before the new connection is added
            startClosing();
            awaitClosing();
            return result;
        }

        @Override
        public Set<Object> compute(Long key,
                BiFunction<? super Long, ? super Set<Object>, ? extends Set<Object>> remapping) {
            Set<Object> result = super.compute(key, (id, current) -> {
                // with atomic registration, the close has to wait for this update before removing anything
                startClosing();
                return remapping.apply(id, current);
            });
            awaitClosing();
            return result;
        }
    }

    // keeps what would have been sent instead of writing to a real http response
    private static class RecordingEmitter extends SseEmitter {

        private final List<Object> sent = new ArrayList<>();
        private volatile boolean closed;
        private boolean broken;
        private Runnable onCompletion;

        @Override
        public void send(SseEventBuilder event) throws IOException {
            if (broken) {
                throw new IOException("disconnected");
            }
            event.build().forEach(part -> sent.add(part.getData()));
        }

        @Override
        public void onCompletion(Runnable callback) {
            onCompletion = callback;
        }

        @Override
        public void complete() {
            if (closed) {
                return;
            }
            closed = true;
            if (onCompletion != null) {
                onCompletion.run();
            }
        }

        void breakConnection() {
            broken = true;
        }

        boolean isClosed() {
            return closed;
        }

        // the event name goes out as text ahead of the data, eg "event:alerts-changed"
        boolean received(String eventName) {
            return sent.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .anyMatch(part -> part.contains("event:" + eventName + "\n"));
        }

        // the ready message is sent as plain text, so only the alert payloads are kept
        List<Object> alerts() {
            return sent.stream()
                    .filter(AlertResponse.class::isInstance)
                    .toList();
        }
    }
}
