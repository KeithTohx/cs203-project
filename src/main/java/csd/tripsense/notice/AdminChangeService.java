package csd.tripsense.notice;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// tells a traveller what an admin changed in their trips. the bell shows them all as one alert,
// and the alerts page lists each change
@Service
public class AdminChangeService {

    private static final Logger log = LoggerFactory.getLogger(AdminChangeService.class);
    private static final int MESSAGE_MAX = 500;

    private final AdminChangeRepository changes;
    private final ApplicationEventPublisher events;

    public AdminChangeService(AdminChangeRepository changes, ApplicationEventPublisher events) {
        this.changes = changes;
        this.events = events;
    }

    public record AdminChangePage(List<AdminChangeResponse> content, long unreadCount) {
    }

    public record UnreadCount(long unreadCount) {
    }

    // called once the change itself has been saved. like the audit log, a failure here is only
    // logged, since the change has already happened and should not look like it failed.
    // an admin changing their own trips is not told about it
    public void record(Long ownerId, Long adminId, String message) {
        boolean noOwner = ownerId == null;
        boolean ownData = Objects.equals(ownerId, adminId);
        if (noOwner || ownData) {
            return;
        }

        try {
            changes.save(new AdminChange(ownerId, truncate(message)));
            events.publishEvent(new AdminChangedEvent(ownerId));
        } catch (RuntimeException e) {
            log.error("Could not save the admin change notice for user {}", ownerId, e);
        }
    }

    @Transactional(readOnly = true)
    public AdminChangePage list(Long userId) {
        List<AdminChangeResponse> newest = changes.findTop20ByUserIdOrderByIdDesc(userId).stream()
                .map(AdminChangeResponse::from)
                .toList();
        long unread = changes.countByUserIdAndReadAtIsNull(userId);
        return new AdminChangePage(newest, unread);
    }

    @Transactional
    public UnreadCount markAllRead(Long userId, Long upToId) {
        changes.markRead(userId, upToId, Instant.now());
        return new UnreadCount(changes.countByUserIdAndReadAtIsNull(userId));
    }

    private static String truncate(String message) {
        if (message.length() <= MESSAGE_MAX) {
            return message;
        }
        return message.substring(0, MESSAGE_MAX - 1) + "…";
    }
}
