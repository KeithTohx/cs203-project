package csd.tripsense.alert;

import csd.tripsense.auth.CurrentUser;
import csd.tripsense.notice.AdminChangedEvent;
import csd.tripsense.user.UserRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
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
    // One in-flight write per connection bounds virtual-thread and payload retention.
    private final ExecutorService writes = Executors.newVirtualThreadPerTaskExecutor();
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
        // Insert under the same per-user map lock used by removal. Otherwise the last
        // old connection can remove this set between lookup and insertion on reconnect.
        connections.compute(userId, (id, current) -> {
            Set<Connection> subscribers = current == null ? ConcurrentHashMap.newKeySet() : current;
            subscribers.add(connection);
            return subscribers;
        });
        connection.emitter.onCompletion(() -> {
            connection.closed.set(true);
            remove(connection);
        });
        connection.emitter.onTimeout(() -> close(connection));
        connection.emitter.onError(error -> close(connection));
        send(connection, SseEmitter.event().name("ready").reconnectTime(3000)
                .data("Connected. GET /api/alerts for saved or missed alerts."));
        return connection.emitter;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCreated(AlertCreatedEvent event) {
        try { delivery.execute(() -> deliver(event)); }
        catch (RejectedExecutionException exception) {
            Set<Connection> recipients = connections.get(event.userId());
            if (recipients != null) recipients.forEach(connection -> connection.historyRefreshRequired.set(true));
            log.warn("Live alert delivery busy; history refresh requested for alert {}", event.alert().id());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onChanged(AlertsChangedEvent event) {
        Set<Connection> recipients = connections.get(event.userId());
        if (recipients != null) recipients.forEach(connection -> send(connection,
                SseEmitter.event().name("ready").data("Alert history changed. Reload saved history.")));
    }

    // Package-private for deterministic tests without timing assertions.
    void deliver(AlertCreatedEvent event) {
        Set<Connection> recipients = connections.get(event.userId());
        if (recipients == null) return;
        for (Connection connection : recipients) {
            send(connection, SseEmitter.event().name("alert").id(event.alert().id().toString()).data(event.alert()));
        }
    }

    // a removal is never followed by an alert event of its own, so without this a connected browser
    // keeps showing the deleted alert and its unread count. it stops polling while the stream is up
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRemoved(AlertsRemovedEvent event) {
        try {
            delivery.execute(() -> deliverRemoval(event));
        } catch (RejectedExecutionException exception) {
            Set<Connection> recipients = connections.get(event.userId());
            if (recipients != null) recipients.forEach(connection -> connection.historyRefreshRequired.set(true));
            log.warn("Live alert delivery busy; history refresh requested for user {}", event.userId());
        }
    }

    // package private for the same reason as deliver
    void deliverRemoval(AlertsRemovedEvent event) {
        Set<Connection> recipients = connections.get(event.userId());
        if (recipients == null) {
            return;
        }
        for (Connection connection : recipients) {
            send(connection, SseEmitter.event().name("alerts-changed").data("GET /api/alerts for the current list"));
        }
    }

    // only says that something changed, the page then loads the notice from
    // GET /api/user/me/admin-changes like it loads alerts
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAdminChanged(AdminChangedEvent event) {
        try {
            delivery.execute(() -> deliverAdminChange(event.userId()));
        } catch (RejectedExecutionException exception) {
            log.warn("Live delivery busy; admin change for user {} shows on the next load", event.userId());
        }
    }

    // package private for the same reason as deliver
    void deliverAdminChange(Long userId) {
        Set<Connection> recipients = connections.get(userId);
        if (recipients == null) {
            return;
        }
        for (Connection connection : recipients) {
            send(connection, SseEmitter.event().name("admin-change").data("changed"));
        }
    }

    void heartbeat() {
        try {
            connections.values().forEach(set -> set.forEach(connection -> write(connection, () -> {
                // Consume recovery only when a writer can actually send it. A busy connection
                // keeps its flag, and another overflow during this send survives to the next tick.
                if (connection.historyRefreshRequired.getAndSet(false)) {
                    return SseEmitter.event().name("ready")
                            .data("Live delivery fell behind. GET /api/alerts for saved or missed alerts.");
                }
                return SseEmitter.event().comment("keep-alive");
            }, false)));
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

    // Each connection writes independently; neither the heartbeat nor the shared event
    // workers perform network I/O. A slow connection coalesces missed events into a refresh.
    protected void dispatchWrite(Runnable task) { writes.execute(task); }

    private void send(Connection connection, SseEmitter.SseEventBuilder event) {
        write(connection, () -> event, true);
    }

    private void write(Connection connection, Supplier<SseEmitter.SseEventBuilder> event, boolean refreshIfBusy) {
        if (connection.closed.get()) return;
        if (!connection.writing.compareAndSet(false, true)) {
            if (refreshIfBusy) connection.historyRefreshRequired.set(true);
            return;
        }
        try {
            dispatchWrite(() -> {
                try {
                    if (connection.closed.get()) return;
                    if (!active(connection)) { close(connection); return; }
                    try { connection.emitter.send(event.get()); }
                    catch (IOException | IllegalStateException exception) { close(connection); }
                } finally { connection.writing.set(false); }
            });
        } catch (RejectedExecutionException exception) {
            connection.writing.set(false);
            connection.historyRefreshRequired.set(true);
        }
    }

    private void remove(Connection connection) {
        connections.computeIfPresent(connection.userId, (id, set) -> {
            set.remove(connection);
            return set.isEmpty() ? null : set;
        });
    }

    private void close(Connection connection) {
        if (!connection.closed.compareAndSet(false, true)) return;
        remove(connection);
        // Completion can wait for the emitter's write lock too, so isolate it from other clients.
        try { dispatchWrite(connection.emitter::complete); }
        catch (RejectedExecutionException ignored) { /* Application shutdown is already closing responses. */ }
    }

    @PreDestroy
    void stop() {
        heartbeats.shutdownNow();
        delivery.shutdownNow();
        connections.values().forEach(set -> set.forEach(this::close));
        writes.shutdown();
    }

    private record Connection(Long userId, HttpSession session, SseEmitter emitter,
                              AtomicBoolean historyRefreshRequired, AtomicBoolean writing, AtomicBoolean closed) {
        Connection(Long userId, HttpSession session, SseEmitter emitter) {
            this(userId, session, emitter, new AtomicBoolean(), new AtomicBoolean(), new AtomicBoolean());
        }
    }
}
