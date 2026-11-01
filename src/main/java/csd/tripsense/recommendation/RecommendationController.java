package csd.tripsense.recommendation;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import csd.tripsense.itinerary.ItineraryAccess;   
import csd.tripsense.itinerary.ItineraryService; 
import csd.tripsense.auth.CurrentUser;        
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api")
public class RecommendationController {

    private final RecommendationService recommendationService;
    private final ItineraryService itineraryService;
    private final ItineraryAccess access;

    public RecommendationController(RecommendationService recommendationService,
                                    ItineraryService itineraryService,
                                    ItineraryAccess access) {
        this.recommendationService = recommendationService;
        this.itineraryService = itineraryService;
        this.access = access;
    }

    /**
     * Work out what to do about every impacted activity on an itinerary
     * Takes a few seconds per activity the first time, then comes back from the database
     * Only the itinerary's owner may call this; anyone else gets 404
     *
     * @param request the itinerary to recommend for
     * @return one recommendation per impacted activity, worst severity first
     */
    @PostMapping("/recommendation/generate")
    public List<RecommendationResponse> generate(@Valid @RequestBody GenerateRequest request,
                                                 @AuthenticationPrincipal CurrentUser me) {
        requireOwnItinerary(request.itineraryId(), me);   // before the model is ever called
        return recommendationService.generate(request.itineraryId()).stream()
                .map(RecommendationResponse::from).toList();
    }

    /**
     * List the recommendations already stored for an itinerary
     * Empty until generate has been called, and never calls the model
     * Only the itinerary's owner may call this; anyone else gets 404
     *
     * @param itineraryId
     * @return stored recommendations, worst severity first
     */
    @GetMapping("/itinerary/{itineraryId}/recommendation")
    public List<RecommendationResponse> getByItinerary(@PathVariable Long itineraryId,
                                                       @AuthenticationPrincipal CurrentUser me) {
        requireOwnItinerary(itineraryId, me);
        return recommendationService.getByItinerary(itineraryId).stream()
                .map(RecommendationResponse::from).toList();
    }

    /**
     * Loads the itinerary and throws 404 unless the caller owns it. 
     * A missing itinerary and someone else's itinerary give the same answer, so ids can't be probed.
     */
    private void requireOwnItinerary(Long itineraryId, CurrentUser me) {
        access.requireAccess(itineraryService.getItineraryById(itineraryId), me);
    }

}