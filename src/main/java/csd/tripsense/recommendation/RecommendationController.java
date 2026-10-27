package csd.tripsense.recommendation;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api")
public class RecommendationController {
    private RecommendationService recommendationService;

    public RecommendationController(RecommendationService recommendationService) {
        this.recommendationService = recommendationService;
    }

    /**
     * Work out what to do about every impacted activity on an itinerary
     * Takes a few seconds per activity the first time, then comes back from the database
     *
     * @param request the itinerary to recommend for
     * @return one recommendation per impacted activity, worst severity first
     */
    @PostMapping("/recommendation/generate")
    public List<RecommendationResponse> generate(@Valid @RequestBody GenerateRequest request) {
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
    public List<RecommendationResponse> getByItinerary(@PathVariable Long itineraryId) {
        return recommendationService.getByItinerary(itineraryId).stream()
                .map(RecommendationResponse::from).toList();
    }
}
