package csd.tripsense.alert;

import java.time.Instant;

/** No account credentials or other users' data are included in an alert. */
public record AlertResponse(Long id, Long impactId, Long itineraryId, String itineraryName,
                            Long activityId, String activityName, Long newsId, String newsTitle,
                            String newsSource, String newsUrl, String reason, Instant createdAt,
                            Instant readAt, EmailStatus emailStatus, Instant emailSentAt) {
    public static AlertResponse from(Alert alert) {
        var impact = alert.getImpact();
        var trip = impact.getItinerary();
        var activity = impact.getActivity();
        var news = impact.getNews();
        return new AlertResponse(alert.getId(), impact.getId(), trip.getId(), trip.getName(),
                activity == null ? null : activity.getId(), activity == null ? null : activity.getName(),
                news.getId(), news.getTitle(), news.getSource(), news.getUrl(), impact.getReason(),
                alert.getCreatedAt(), alert.getReadAt(), alert.getEmailStatus(), alert.getEmailSentAt());
    }
}
