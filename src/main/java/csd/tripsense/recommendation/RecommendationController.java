package csd.tripsense.recommendation;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import csd.tripsense.audit.AuditService;
import csd.tripsense.auth.CurrentUser;
import csd.tripsense.impact.Impact;
import csd.tripsense.itinerary.ItineraryAccess;
import csd.tripsense.itinerary.ItineraryService;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api")
public class RecommendationController {
    private RecommendationService recommendationService;

    private final ItineraryService itineraryService;
    private final ItineraryAccess access;
    private final AuditService auditService;

    public RecommendationController(RecommendationService recommendationService,
                                    ItineraryService itineraryService, ItineraryAccess access,
                                    AuditService auditService) {
        this.recommendationService = recommendationService;
        this.itineraryService = itineraryService;
        this.access = access;
        this.auditService = auditService;
    }

    /**
     * Work out what to do about every impacted activity on an itinerary
     * Takes a few seconds per activity the first time, then comes back from the database
     *
     * @param request the itinerary to recommend for
     * @return one recommendation per impacted activity, worst severity first
     */
    @PostMapping("/recommendation/generate")
    public List<RecommendationResponse> generate(@Valid @RequestBody GenerateRequest request,
            @AuthenticationPrincipal CurrentUser me) {
        access.requireAccess(itineraryService.getItineraryById(request.itineraryId()), me);
        return recommendationService.generate(request.itineraryId()).stream()
                .map(RecommendationResponse::from).toList();
    }

    /**
     * List the recommendations already stored for an itinerary
     * Empty until generate has been called, and never calls the model
     *
     * @param itineraryId
     * @return stored recommendations, worst severity first
     */
    @GetMapping("/itinerary/{itineraryId}/recommendation")
    public List<RecommendationResponse> getByItinerary(@PathVariable Long itineraryId,
            @RequestParam(defaultValue = "false") boolean includeDismissed,
            @AuthenticationPrincipal CurrentUser me) {
        access.requireAccess(itineraryService.getItineraryById(itineraryId), me);
        return recommendationService.getByItinerary(itineraryId, includeDismissed, me).stream()
                .map(RecommendationResponse::from).toList();
    }

    /**
     * Accept or dismiss a suggestion
     * A dismissed one is kept but drops out of the itinerary list
     *
     * @param id the suggestion being decided on
     * @param request the new status
     * @return the updated recommendation
     */
    @PatchMapping("/recommendation/{id}")
    public RecommendationResponse decide(@PathVariable Long id,
            @Valid @RequestBody StatusRequest request,
            @AuthenticationPrincipal CurrentUser me) {
        Recommendation decided = recommendationService.decide(id, request.status(), me);
        auditService.record(decisionAction(request.status()), me.getId(), me.getEmail(),
                ownerId(decided), describe(decided));
        return RecommendationResponse.from(decided);
    }

    /**
     * Ask for a different suggestion for the same impacted activity
     * Never offers an option already suggested for it, so the answer really is different
     *
     * @param id the suggestion they do not want
     * @return a new pending recommendation
     */
    @PostMapping("/recommendation/{id}/regenerate")
    public RecommendationResponse regenerate(@PathVariable Long id,
            @AuthenticationPrincipal CurrentUser me) {
        Recommendation fresh = recommendationService.regenerate(id, me);
        String details = "turned down recommendation " + id + ", offered instead " + describe(fresh);
        auditService.record(AuditService.RECOMMENDATION_REGENERATED, me.getId(), me.getEmail(),
                ownerId(fresh), details);
        return RecommendationResponse.from(fresh);
    }

    /**
     * Every suggestion made for one impacted activity, oldest first
     * Dismissed ones are included, this is the audit trail of what was offered
     *
     * @param impactId the impact to look up
     * @return the attempts in the order they were made
     */
    @GetMapping("/impact/{impactId}/recommendation")
    public List<RecommendationResponse> getHistory(@PathVariable Long impactId,
            @AuthenticationPrincipal CurrentUser me) {
        return recommendationService.getHistory(impactId, me).stream()
                .map(RecommendationResponse::from).toList();
    }

    private static String decisionAction(RecommendationStatus status) {
        return switch (status) {
            case ACCEPTED -> AuditService.RECOMMENDATION_ACCEPTED;
            case DISMISSED -> AuditService.RECOMMENDATION_DISMISSED;
            case PENDING -> AuditService.RECOMMENDATION_REOPENED;
        };
    }

    private static Long ownerId(Recommendation recommendation) {
        Impact impact = recommendation.getImpact();
        if (impact == null) {
            return null;
        }
        return impact.ownerId();
    }

    // what the AI suggested and for which impact, so the entry reads on its own in the admin trail
    private static String describe(Recommendation recommendation) {
        Impact impact = recommendation.getImpact();
        String impactId = impact == null ? "unknown" : String.valueOf(impact.getId());

        return "recommendation " + recommendation.getId() + " on impact " + impactId
                + " (" + recommendation.getSeverity() + ", " + recommendation.getAction() + "): "
                + recommendation.getAdvice();
    }
}
