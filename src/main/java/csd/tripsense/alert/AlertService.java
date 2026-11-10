package csd.tripsense.alert;

import csd.tripsense.impact.Impact;
import csd.tripsense.impact.ImpactRepository;
import csd.tripsense.user.UserRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AlertService {
    private final AlertRepository alerts;
    private final ImpactRepository impacts;
    private final UserRepository users;
    private final ApplicationEventPublisher events;

    public AlertService(AlertRepository alerts, ImpactRepository impacts, UserRepository users,
                        ApplicationEventPublisher events) {
        this.alerts = alerts;
        this.impacts = impacts;
        this.users = users;
        this.events = events;
    }

    // nextBeforeId is what the client sends back as beforeId to load older alerts, null once there are none
    public record AlertPage(List<AlertResponse> content, boolean hasMore, Long nextBeforeId, long unreadCount) {}

    // beforeId null means start from the newest alert
    @Transactional(readOnly = true)
    public AlertPage list(Long userId, boolean unreadOnly, Long beforeId, int size) {
        requireActiveAccount(userId);
        int pageSize = Math.min(100, Math.max(1, size));
        var result = alerts.findForUser(userId, unreadOnly, beforeId, PageRequest.of(0, pageSize));

        List<AlertResponse> content = result.getContent().stream().map(AlertResponse::from).toList();
        Long nextBeforeId = null;
        if (result.hasNext()) {
            nextBeforeId = content.getLast().id();
        }
        return new AlertPage(content, result.hasNext(), nextBeforeId, alerts.countUnread(userId));
    }

    @Transactional
    public AlertResponse markRead(Long id, Long userId) {
        requireActiveAccount(userId);
        Alert alert = alerts.findOwned(id, userId).orElseThrow(() -> new AlertNotFoundException(id));
        alert.markRead();
        events.publishEvent(new AlertsChangedEvent(userId));
        return AlertResponse.from(alerts.save(alert));
    }

    @Transactional
    public UnreadCount markGroupRead(Long id, Long userId) {
        requireActiveAccount(userId);
        Alert anchor = alerts.findOwned(id, userId).orElseThrow(() -> new AlertNotFoundException(id));
        alerts.markGroupRead(userId, anchor.getImpact().getItinerary().getId(),
                anchor.getImpact().getNews().getId(), id, Instant.now());
        events.publishEvent(new AlertsChangedEvent(userId));
        return new UnreadCount(alerts.countUnread(userId));
    }

    // what is left unread after marking all as read. not always 0, since alerts newer than upToId are skipped
    public record UnreadCount(long unreadCount) {}

    // marks every visible unread alert, not just one page. upToId null means no upper limit
    // one bulk update instead of loading every alert, see AlertRepository.markAllReadForUser for why
    @Transactional
    public UnreadCount markAllRead(Long userId, Long upToId) {
        requireActiveAccount(userId);
        Instant now = Instant.now();
        alerts.markAllReadForUser(userId, upToId, now);
        events.publishEvent(new AlertsChangedEvent(userId));
        return new UnreadCount(alerts.countUnread(userId));
    }

    /** Called after assessment commit; a notification failure cannot roll back an impact. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createForImpact(Long impactId) {
        if (impactId == null) return;
        createInTransaction(impacts.findById(impactId).orElse(null));
    }

    @Transactional
    public boolean createInTransaction(Impact impact) {
        if (impact == null || !Boolean.TRUE.equals(impact.getAlertEligible()) || !Boolean.TRUE.equals(impact.getImpacted())
                || Boolean.TRUE.equals(impact.getRejected()) || impact.getItinerary() == null
                || impact.getItinerary().getUser() == null || !impact.getItinerary().getUser().isEnabled()
                || impact.getNews() == null || impact.getReason() == null || impact.getReason().isBlank()
                || alerts.existsByImpactId(impact.getId())) return false;
        Alert alert = alerts.saveAndFlush(new Alert(impact));
        events.publishEvent(new AlertCreatedEvent(impact.getItinerary().getUser().getId(), AlertResponse.from(alert)));
        return true;
    }

    private void requireActiveAccount(Long userId) {
        if (userId == null || !users.findById(userId).map(u -> u.isEnabled()).orElse(false)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not logged in");
        }
    }
}
