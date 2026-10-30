package csd.tripsense.alert;

import csd.tripsense.auth.CurrentUser;
import csd.tripsense.user.*;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
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

    private static class RecordingEmitter extends SseEmitter {
        final List<Object> sent = new ArrayList<>(); boolean closed; boolean broken;
        @Override public void send(SseEventBuilder event) throws IOException {
            if (broken) throw new IOException("disconnected");
            event.build().forEach(part -> sent.add(part.getData()));
        }
        @Override public void complete() { closed = true; }
        List<Object> alerts() { return sent.stream().filter(AlertResponse.class::isInstance).toList(); }
    }
}
