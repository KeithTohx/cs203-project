package csd.tripsense.alert;

import csd.tripsense.auth.CurrentUser;
import csd.tripsense.user.UserRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Live delivery for this application instance. Durable history is the recovery mechanism. */
@Service
public class AlertStreamService {
    private static final Logger log = LoggerFactory.getLogger(AlertStreamService.class);
    private final UserRepository users;
    private final SessionRegistry sessions;
    private final ConcurrentMap<Long, Set<Connection>> connections = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "alert-heartbeat"); thread.setDaemon(true); return thread;
    });
    private final ExecutorService delivery = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(100), task -> {
                Thread thread = new Thread(task, "alert-delivery"); thread.setDaemon(true); return thread;
            });

    public AlertStreamService(UserRepository users, SessionRegistry sessions) {
        this.users = users;
        this.sessions = sessions;
    }

    @PostConstruct
    void start() {
        // Independent of the news ingestion scheduler, which can spend minutes waiting for the AI.
        heartbeats.scheduleWithFixedDelay(this::heartbeat, 15, 15, TimeUnit.SECONDS);
    }

    protected SseEmitter newEmitter() { return new SseEmitter(30 * 60 * 1000L); }

    public SseEmitter subscribe(Long userId, HttpSession session) {
        Connection connection = new Connection(userId, session, newEmitter());
        if (!active(connection)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not logged in");
        connections.computeIfAbsent(userId, ignored -> ConcurrentHashMap.newKeySet()).add(connection);
        connection.emitter.onCompletion(() -> remove(connection));
        connection.emitter.onTimeout(() -> close(connection));
        connection.emitter.onError(error -> remove(connection));
        send(connection, SseEmitter.event().name("ready").reconnectTime(3000)
                .data("Connected. GET /api/alerts for saved or missed alerts."));
        return connection.emitter;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCreated(AlertCreatedEvent event) {
        try { delivery.execute(() -> deliver(event)); }
        catch (RejectedExecutionException exception) {
            log.warn("Live alert delivery busy; alert {} remains available in history", event.alert().id());
        }
    }

    // Package-private for deterministic tests without timing assertions.
    void deliver(AlertCreatedEvent event) {
        Set<Connection> recipients = connections.get(event.userId());
        if (recipients == null) return;
        for (Connection connection : recipients) {
            send(connection, SseEmitter.event().name("alert").id(event.alert().id().toString()).data(event.alert()));
        }
    }

    void heartbeat() {
        try {
            connections.values().forEach(set -> set.forEach(connection ->
                    send(connection, SseEmitter.event().comment("keep-alive"))));
        } catch (RuntimeException exception) {
            log.warn("Alert heartbeat failed: {}", exception.getMessage());
        }
    }

    private boolean active(Connection connection) {
        try {
            if (connection.session == null) return false;
            var info = sessions.getSessionInformation(connection.session.getId());
            if (info == null || info.isExpired()) return false;
            Object stored = connection.session.getAttribute(
                    HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
            if (!(stored instanceof SecurityContext context) || context.getAuthentication() == null
                    || !context.getAuthentication().isAuthenticated()
                    || !(context.getAuthentication().getPrincipal() instanceof CurrentUser principal)
                    || !connection.userId.equals(principal.getId())) return false;
            return users.findById(connection.userId).map(user -> user.isEnabled()).orElse(false);
        } catch (RuntimeException exception) {
            // A logged-out, expired, deleted or unverifiable account must stop receiving private data.
            return false;
        }
    }

    private void send(Connection connection, SseEmitter.SseEventBuilder event) {
        synchronized (connection) {
            if (!active(connection)) { close(connection); return; }
            try { connection.emitter.send(event); }
            catch (IOException | IllegalStateException exception) { close(connection); }
        }
    }

    private void remove(Connection connection) {
        connections.computeIfPresent(connection.userId, (id, set) -> {
            set.remove(connection);
            return set.isEmpty() ? null : set;
        });
    }

    private void close(Connection connection) {
        remove(connection);
        connection.emitter.complete();
    }

    @PreDestroy
    void stop() {
        heartbeats.shutdownNow();
        delivery.shutdownNow();
        connections.values().forEach(set -> set.forEach(this::close));
    }

    private record Connection(Long userId, HttpSession session, SseEmitter emitter) {}
}
