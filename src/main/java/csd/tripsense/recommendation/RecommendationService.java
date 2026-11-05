package csd.tripsense.recommendation;

import java.util.List;

import csd.tripsense.auth.CurrentUser;

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

    /**
     * Same, but only the live attempt per impact and dismissed ones can be left out.
     *
     * @param itineraryId      the itinerary to look up
     * @param includeDismissed true to keep rows the traveller already dismissed
     * @param me               the caller, must own the itinerary
     * @return the stored recommendations, worst severity first
     */
    List<Recommendation> getByItinerary(Long itineraryId, boolean includeDismissed, CurrentUser me);

    /**
     * Records that the traveller accepted or dismissed a suggestion.
     *
     * Any of the three states can follow any other, a dismissed suggestion can be accepted
     * later once they change their mind.
     *
     * @param recommendationId the suggestion being decided on
     * @param status           what they decided
     * @param me               the caller, must own the trip the suggestion belongs to
     * @return the updated recommendation
     * @throws csd.tripsense.impact.ImpactNotFoundException if it is not theirs or does not exist
     */
    Recommendation decide(Long recommendationId, RecommendationStatus status, CurrentUser me);

    /**
     * Asks for a different suggestion for the same impact.
     *
     * Every option already offered for that impact is excluded, so the new row cannot repeat
     * a venue they have just turned down. The old row is left alone as history.
     *
     * @param recommendationId the suggestion they do not want
     * @param me               the caller, must own the trip the suggestion belongs to
     * @return a new pending recommendation on the same impact
     * @throws csd.tripsense.impact.ImpactNotFoundException if it is not theirs or does not exist
     */
    Recommendation regenerate(Long recommendationId, CurrentUser me);

    /**
     * Every attempt made for one impact, oldest first, dismissed ones included.
     *
     * @param impactId the impact to look up
     * @param me       the caller, must own the trip
     * @return the attempts in the order they were made
     */
    List<Recommendation> getHistory(Long impactId, CurrentUser me);
}
