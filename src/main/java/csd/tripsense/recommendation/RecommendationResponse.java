package csd.tripsense.recommendation;

import csd.tripsense.impact.Impact;

/**
 * What the API returns for a recommendation.
 *
 * Flattened the same way ImpactResponse is, ids plus display names, so a client can render
 * the list without the whole object graph coming along.
 */
public record RecommendationResponse(
        Long id,
        Severity severity,
        Action action,
        String advice,
        Long impactId,
        String reason,
        Long itineraryId,
        Long activityId,
        String activityName,
        Long newsId,
        String newsTitle,
        Long optionId,
        String optionName,
        String optionArea,
        String optionAddress) {

    public static RecommendationResponse from(Recommendation recommendation) {
        Impact impact = recommendation.getImpact();
        // activity is null on a whole trip impact, and the option is null when nothing was offered
        ActivityOption option = recommendation.getSuggestedOption();

        return new RecommendationResponse(
                recommendation.getId(),
                recommendation.getSeverity(),
                recommendation.getAction(),
                recommendation.getAdvice(),
                impact == null ? null : impact.getId(),
                impact == null ? null : impact.getReason(),
                impact == null || impact.getItinerary() == null ? null : impact.getItinerary().getId(),
                impact == null || impact.getActivity() == null ? null : impact.getActivity().getId(),
                impact == null || impact.getActivity() == null ? null : impact.getActivity().getName(),
                impact == null || impact.getNews() == null ? null : impact.getNews().getId(),
                impact == null || impact.getNews() == null ? null : impact.getNews().getTitle(),
                option == null ? null : option.getId(),
                option == null ? null : option.getName(),
                option == null ? null : option.getArea(),
                option == null ? null : option.getAddress());
    }
}
