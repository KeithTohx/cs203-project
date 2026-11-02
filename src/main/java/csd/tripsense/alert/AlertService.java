package csd.tripsense.alert;

import csd.tripsense.impact.Impact;
import csd.tripsense.impact.ImpactRepository;
import csd.tripsense.user.UserRepository;
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

    public record AlertPage(List<AlertResponse> content, int page, int size, long totalElements,
                            int totalPages, long unreadCount) {}

    @Transactional(readOnly = true)
    public AlertPage list(Long userId, boolean unreadOnly, int page, int size) {
        requireActiveAccount(userId);
        var result = alerts.findForUser(userId, unreadOnly,
                PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size))));
        return new AlertPage(result.getContent().stream().map(AlertResponse::from).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages(),
                alerts.countUnread(userId));
    }

    @Transactional
    public AlertResponse markRead(Long id, Long userId) {
        requireActiveAccount(userId);
        Alert alert = alerts.findOwned(id, userId).orElseThrow(() -> new AlertNotFoundException(id));
        alert.markRead();
        return AlertResponse.from(alerts.save(alert));
    }

    /** Called after assessment commit; a notification failure cannot roll back an impact. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createForImpact(Long impactId) {
        if (impactId == null || alerts.existsByImpactId(impactId)) return;
        Impact impact = impacts.findById(impactId).orElse(null);
        if (impact == null || !Boolean.TRUE.equals(impact.getAlertEligible()) || !Boolean.TRUE.equals(impact.getImpacted())
                || Boolean.TRUE.equals(impact.getRejected()) || impact.getItinerary() == null
                || impact.getItinerary().getUser() == null || !impact.getItinerary().getUser().isEnabled()
                || impact.getNews() == null || impact.getReason() == null || impact.getReason().isBlank()) return;
        Alert alert = alerts.saveAndFlush(new Alert(impact));
        events.publishEvent(new AlertCreatedEvent(impact.getItinerary().getUser().getId(), AlertResponse.from(alert)));
    }

    private void requireActiveAccount(Long userId) {
        if (userId == null || !users.findById(userId).map(u -> u.isEnabled()).orElse(false)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not logged in");
        }
    }
}
