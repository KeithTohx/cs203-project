package csd.tripsense.alert;

import csd.tripsense.impact.EvaluationBatchRepository;
import csd.tripsense.impact.Impact;
import csd.tripsense.impact.ImpactRepository;
import csd.tripsense.itinerary.ItineraryRepository;
import csd.tripsense.recommendation.RecommendationRepository;
import csd.tripsense.user.UserRepository;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Replays only a configured demonstration fixture, never an arbitrary user's trip. */
@Service
public class DemoDisruptionService {
    private final ImpactRepository impacts;
    private final AlertRepository alerts;
    private final RecommendationRepository recommendations;
    private final ItineraryRepository itineraries;
    private final UserRepository users;
    private final AlertService alertService;
    private final AlertEmailGroupRepository emailGroups;
    private final EvaluationBatchRepository evaluations;
    private final boolean enabled;
    private final long userId;
    private final long itineraryId;
    private final long newsId;

    public DemoDisruptionService(ImpactRepository impacts, AlertRepository alerts,
            RecommendationRepository recommendations, ItineraryRepository itineraries, UserRepository users,
            AlertService alertService, AlertEmailGroupRepository emailGroups,
            EvaluationBatchRepository evaluations, @Value("${alerts.demo.enabled:false}") boolean enabled,
            @Value("${alerts.demo.user-id:0}") long userId,
            @Value("${alerts.demo.itinerary-id:0}") long itineraryId,
            @Value("${alerts.demo.news-id:0}") long newsId) {
        this.impacts = impacts; this.alerts = alerts; this.recommendations = recommendations;
        this.itineraries = itineraries; this.users = users; this.alertService = alertService;
        this.emailGroups = emailGroups; this.evaluations = evaluations;
        this.enabled = enabled; this.userId = userId; this.itineraryId = itineraryId; this.newsId = newsId;
    }

    public record Result(long itineraryId, long newsId, int impacts, int alerts, String state) {}

    @Transactional
    public Result reset(Long callerId) {
        List<Impact> fixture = requireFixture(callerId);
        var existing = alerts.findEmailGroup(itineraryId, newsId);
        if (existing.stream().anyMatch(a -> a.getEmailStatus() == EmailStatus.SENDING)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Wait for email delivery to finish before resetting");
        }
        // Delete notifications, not their source assessments. Their text is reused without an AI call.
        alerts.deleteAll(existing);
        alerts.flush();
        emailGroups.deleteById(AlertEmailGroup.key(itineraryId, newsId));
        var associated = recommendations.findByImpactItineraryId(itineraryId).stream()
                .filter(r -> r.getImpact().getNews().getId().equals(newsId)).toList();
        recommendations.deleteAll(associated);
        for (Impact impact : fixture) {
            impact.setImpacted(false);
            // Remembers which saved positive verdicts the replay should restore.
            impact.setAlertEligible(true);
            impact.setRejected(false);
        }
        impacts.saveAll(fixture);
        return new Result(itineraryId, newsId, fixture.size(), 0, "READY");
    }

    @Transactional
    public Result trigger(Long callerId) {
        List<Impact> fixture = requireFixture(callerId);
        if (!alerts.findEmailGroup(itineraryId, newsId).isEmpty()
                || fixture.stream().anyMatch(i -> Boolean.TRUE.equals(i.getImpacted()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Reset the demo disruption before triggering it");
        }
        for (Impact impact : fixture) {
            impact.setImpacted(true);
            impact.setAlertEligible(true);
            impact.setRejected(false);
        }
        impacts.saveAllAndFlush(fixture);
        // Create the complete group atomically so email cannot run before all impacts are restored.
        int created = 0;
        for (Impact impact : fixture) {
            if (alertService.createInTransaction(impact)) created++;
        }
        return new Result(itineraryId, newsId, fixture.size(), created, "TRIGGERED");
    }

    private List<Impact> requireFixture(Long callerId) {
        if (!enabled || userId <= 0 || itineraryId <= 0 || newsId <= 0 || !Long.valueOf(userId).equals(callerId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Demo disruption not available");
        }
        var owner = users.findById(userId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Demo account not available"));
        var trip = itineraries.findById(itineraryId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Demo itinerary not available"));
        if (!owner.isEnabled() || trip.getUser() == null || !trip.getUser().getId().equals(callerId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Demo itinerary not available");
        }
        var saved = impacts.findByItineraryIdAndNewsId(itineraryId, newsId);
        if (saved.isEmpty() || saved.stream().anyMatch(i -> i.getReason() == null || i.getReason().isBlank())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Complete the demo assessment before preparing replay");
        }
        var positive = saved.stream().filter(i -> Boolean.TRUE.equals(i.getImpacted())
                || Boolean.TRUE.equals(i.getAlertEligible())).toList();
        if (positive.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No saved disruption to replay");
        }
        if (evaluations.countRunning(itineraryId, newsId, java.time.Instant.now()) != 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Wait for all demo assessments and alerts to finish");
        }
        return positive;
    }
}
