package csd.tripsense.impact;

import java.time.LocalDateTime;

import csd.tripsense.news.News;

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
        boolean rejected,
        Long userId,
        String username,
        Long newsId,
        String newsTitle,
        String newsSource,
        String newsUrl,
        String newsDescription,
        LocalDateTime newsPublishedAt,
        Long itineraryId,
        String itineraryName,
        Long activityId,
        String activityName) {

    public static ImpactResponse from(Impact impact) {
        News news = impact.getNews();

        // itinerary and activity are optional on an impact, so both are guarded
        return new ImpactResponse(
                impact.getId(),
                impact.getImpacted(),
                impact.getReason(),
                Boolean.TRUE.equals(impact.getRejected()),   // so clients never have to handle a null
                impact.getUser() == null ? null : impact.getUser().getId(),
                impact.getUser() == null ? null : impact.getUser().getUsername(),
                news == null ? null : news.getId(),
                news == null ? null : news.getTitle(),
                news == null ? null : news.getSource(),
                news == null ? null : news.getUrl(),
                news == null ? null : news.getDescription(),
                news == null ? null : news.getPublishedAt(),
                impact.getItinerary() == null ? null : impact.getItinerary().getId(),
                impact.getItinerary() == null ? null : impact.getItinerary().getName(),
                impact.getActivity() == null ? null : impact.getActivity().getId(),
                impact.getActivity() == null ? null : impact.getActivity().getName());
    }
}
