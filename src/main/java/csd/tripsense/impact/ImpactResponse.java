package csd.tripsense.impact;

/**
 * What the API returns for an impact.
 *
 * Related records are flattened to an id and a display name so a client can render
 * a list without fetching each one separately, and without the whole object graph
 * coming along for the ride.
 */
public record ImpactResponse(
        Long id,
        Boolean impacted,
        String reason,
        Long userId,
        String username,
        Long newsId,
        String newsTitle,
        Long itineraryId,
        String itineraryName,
        Long activityId,
        String activityName) {

    public static ImpactResponse from(Impact impact) {
        // itinerary and activity are optional on an impact, so both are guarded
        return new ImpactResponse(
                impact.getId(),
                impact.getImpacted(),
                impact.getReason(),
                impact.getUser() == null ? null : impact.getUser().getId(),
                impact.getUser() == null ? null : impact.getUser().getUsername(),
                impact.getNews() == null ? null : impact.getNews().getId(),
                impact.getNews() == null ? null : impact.getNews().getTitle(),
                impact.getItinerary() == null ? null : impact.getItinerary().getId(),
                impact.getItinerary() == null ? null : impact.getItinerary().getName(),
                impact.getActivity() == null ? null : impact.getActivity().getId(),
                impact.getActivity() == null ? null : impact.getActivity().getName());
    }
}
