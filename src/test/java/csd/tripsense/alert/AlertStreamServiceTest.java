package csd.tripsense.alert;

import csd.tripsense.auth.CurrentUser;
import csd.tripsense.user.*;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AlertStreamServiceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final SessionRegistryImpl sessions = new SessionRegistryImpl();
    private final List<RecordingEmitter> emitters = new ArrayList<>();
    private final AlertStreamService streams = new AlertStreamService(users, sessions) {
        @Override protected SseEmitter newEmitter() {
            RecordingEmitter emitter = new RecordingEmitter(); emitters.add(emitter); return emitter;
        }
    };

    @AfterEach void stop() { streams.stop(); SecurityContextHolder.clearContext(); }

    private MockHttpSession session(long userId) {
        var principal = new CurrentUser(userId, "user" + userId + "@example.com", "person", null, "USER");
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        var session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        sessions.registerNewSession(session.getId(), principal);
        when(users.findById(userId)).thenReturn(Optional.of(new User(principal.getEmail(), "person", "hash", "USER")));
        return session;
    }

    private AlertCreatedEvent event(long userId) {
        return new AlertCreatedEvent(userId, new AlertResponse(1L, 2L, 3L, "Trip", null, null,
                4L, "News", "Source", "https://example.com/news", "Disruption", Instant.now(), null, EmailStatus.PENDING, null));
    }

    @Test void pushesOnlyToTheRecipientAcrossTheirSessions() {
        streams.subscribe(1L, session(1)); streams.subscribe(1L, session(1)); streams.subscribe(2L, session(2));
        streams.deliver(event(1));
        assertThat(emitters.get(0).alerts()).hasSize(1);
        assertThat(emitters.get(1).alerts()).hasSize(1);
        assertThat(emitters.get(2).alerts()).isEmpty();
    }

    @Test void expiredAndLoggedOutSessionsStopReceivingAlerts() {
        var expired = session(1); streams.subscribe(1L, expired);
        var loggedOut = session(1); streams.subscribe(1L, loggedOut);
        sessions.getSessionInformation(expired.getId()).expireNow(); loggedOut.invalidate();
        streams.deliver(event(1));
        assertThat(emitters).allSatisfy(e -> { assertThat(e.alerts()).isEmpty(); assertThat(e.closed).isTrue(); });
    }

    @Test void disabledAccountStopsReceivingAlertsEvenOnAnOpenConnection() {
        streams.subscribe(1L, session(1));
        User disabled = new User("user1@example.com", "person", "hash", "USER"); disabled.setEnabled(false);
        when(users.findById(1L)).thenReturn(Optional.of(disabled));
        streams.heartbeat(); streams.deliver(event(1));
        assertThat(emitters.getFirst().closed).isTrue();
        assertThat(emitters.getFirst().alerts()).isEmpty();
    }

    @Test void oneBrokenConnectionDoesNotStopAnotherSession() {
        streams.subscribe(1L, session(1)); streams.subscribe(1L, session(1));
        emitters.getFirst().broken = true;
        streams.deliver(event(1));
        assertThat(emitters.getFirst().closed).isTrue();
        assertThat(emitters.getLast().alerts()).hasSize(1);
    }

    @Test void completingTheLastConnectionDuringRegistrationDoesNotDetachItsReplacement() throws Exception {
        try (var closer = Executors.newSingleThreadExecutor()) {
            var registry = new RegistrationRaceMap(closer);
            ReflectionTestUtils.setField(streams, "connections", registry);
            streams.subscribe(1L, session(1));
            RecordingEmitter original = emitters.getFirst();
            registry.arm(original::complete);

            streams.subscribe(1L, session(1));
            streams.deliver(event(1));

            assertThat(original.closed).isTrue();
            assertThat(original.alerts()).isEmpty();
            assertThat(emitters.getLast().alerts()).hasSize(1);
        }
    }

    /** Forces completion at the registration boundary rather than relying on a probabilistic stress test. */
    private static class RegistrationRaceMap extends ConcurrentHashMap<Long, Set<Object>> {
        private final ExecutorService closer;
        private final AtomicBoolean armed = new AtomicBoolean();
        private Runnable completeOriginal;
        private Future<?> completion;

        RegistrationRaceMap(ExecutorService closer) { this.closer = closer; }
        void arm(Runnable completeOriginal) { this.completeOriginal = completeOriginal; armed.set(true); }

        private void startCompletion() {
            if (!armed.compareAndSet(true, false)) return;
            var started = new CompletableFuture<Void>();
            completion = closer.submit(() -> { started.complete(null); completeOriginal.run(); });
            try { started.get(5, TimeUnit.SECONDS); }
            catch (Exception exception) { throw new AssertionError("Completion did not start", exception); }
        }

        private void awaitCompletion() {
            if (completion == null) return;
            try { completion.get(5, TimeUnit.SECONDS); }
            catch (Exception exception) { throw new AssertionError("Completion did not finish", exception); }
        }

        @Override public Set<Object> computeIfAbsent(Long key,
                Function<? super Long, ? extends Set<Object>> factory) {
            Set<Object> result = super.computeIfAbsent(key, factory);
            // The broken implementation exposes the set before inserting the replacement connection.
            startCompletion(); awaitCompletion();
            return result;
        }

        @Override public Set<Object> compute(Long key,
                BiFunction<? super Long, ? super Set<Object>, ? extends Set<Object>> remapping) {
            Set<Object> result = super.compute(key, (id, current) -> {
                // With atomic registration, completion waits for this map update before removing the old entry.
                startCompletion();
                return remapping.apply(id, current);
            });
            awaitCompletion();
            return result;
        }
    }

    private static class RecordingEmitter extends SseEmitter {
        final List<Object> sent = new ArrayList<>(); volatile boolean closed; boolean broken;
        private Runnable completed;
        @Override public void send(SseEventBuilder event) throws IOException {
            if (broken) throw new IOException("disconnected");
            event.build().forEach(part -> sent.add(part.getData()));
        }
        @Override public void onCompletion(Runnable callback) { completed = callback; }
        @Override public void complete() {
            if (!closed) { closed = true; if (completed != null) completed.run(); }
        }
        List<Object> alerts() { return sent.stream().filter(AlertResponse.class::isInstance).toList(); }
    }
}
