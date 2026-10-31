package csd.tripsense.recommendation;

import java.util.List;

public interface RecommendationService {

    /**
     * Works out what to do about every impacted activity on an itinerary.
     *
     * An impact that already has a recommendation is served from the database, so the model is
     * asked once per impact and the demo still works when Ollama is down.
     *
     * @param itineraryId the itinerary to recommend for
     * @return one recommendation per impacted activity, worst severity first
     * @throws csd.tripsense.itinerary.ItineraryNotFoundException if the itinerary does not exist
     */
    List<Recommendation> generate(Long itineraryId);

    /**
     * Retrieves the recommendations already stored for an itinerary, worst severity first.
     *
     * @param itineraryId the itinerary to look up
     * @return the stored recommendations, or an empty list if generate has not been called yet
     */
    List<Recommendation> getByItinerary(Long itineraryId);
}
