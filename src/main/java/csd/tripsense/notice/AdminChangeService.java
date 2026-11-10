package csd.tripsense.notice;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import csd.tripsense.itinerary.ItineraryRepository;

// tells a traveller what an admin changed in their trips. they sit in the bell and on the alerts
// page next to the news alerts, each with its own trip link and read state
@Service
public class AdminChangeService {

    private static final Logger log = LoggerFactory.getLogger(AdminChangeService.class);
    private static final int MESSAGE_MAX = 500;

    private final AdminChangeRepository changes;
    private final ItineraryRepository itineraries;
    private final ApplicationEventPublisher events;

    public AdminChangeService(AdminChangeRepository changes, ItineraryRepository itineraries,
                              ApplicationEventPublisher events) {
        this.changes = changes;
        this.itineraries = itineraries;
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
        record(ownerId, adminId, message, AdminChangeTarget.NONE);
    }

    public void record(Long ownerId, Long adminId, String message, AdminChangeTarget target) {
        boolean noOwner = ownerId == null;
        boolean ownData = Objects.equals(ownerId, adminId);
        if (noOwner || ownData) {
            return;
        }

        try {
            changes.save(new AdminChange(ownerId, truncate(message), target));
            events.publishEvent(new AdminChangedEvent(ownerId));
        } catch (RuntimeException e) {
            log.error("Could not save the admin change notice for user {}", ownerId, e);
        }
    }

    @Transactional(readOnly = true)
    public AdminChangePage list(Long userId) {
        List<AdminChangeResponse> newest = changes.findTop20ByUserIdOrderByIdDesc(userId).stream()
                .map(change -> AdminChangeResponse.from(change, tripStillTheirs(change, userId)))
                .toList();
        long unread = changes.countByUserIdAndReadAtIsNull(userId);
        return new AdminChangePage(newest, unread);
    }

    @Transactional
    public UnreadCount markAllRead(Long userId, Long upToId) {
        changes.markRead(userId, upToId, Instant.now());
        return new UnreadCount(changes.countByUserIdAndReadAtIsNull(userId));
    }

    @Transactional
    public UnreadCount markRead(Long userId, Long changeId) {
        changes.markOneRead(userId, changeId, Instant.now());
        return new UnreadCount(changes.countByUserIdAndReadAtIsNull(userId));
    }

    // sqlite hands a deleted trip's id to the next one, so the owner is checked too. otherwise the
    // link could open someone else's trip
    private boolean tripStillTheirs(AdminChange change, Long userId) {
        if (change.getItineraryId() == null) {
            return false;
        }
        return itineraries.existsByIdAndUserId(change.getItineraryId(), userId);
    }

    private static String truncate(String message) {
        if (message.length() <= MESSAGE_MAX) {
            return message;
        }
        return message.substring(0, MESSAGE_MAX - 1) + "…";
    }
}
