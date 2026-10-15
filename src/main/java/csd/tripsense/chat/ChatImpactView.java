package csd.tripsense.chat;

import csd.tripsense.activity.Activity;
import csd.tripsense.impact.Impact;
import csd.tripsense.news.News;
import java.time.LocalDateTime;

public record ChatImpactView(String affectedActivity, String newsHeadline, String newsSource,
                             LocalDateTime newsPublishedAt, String newsSummary, String reason) {

    private static final int MAX_SUMMARY = 400;

    public static ChatImpactView from(Impact i) {
        News n = i.getNews();
        Activity a = i.getActivity();
        return new ChatImpactView(
            a != null ? a.getName() : "Whole itinerary",
            n.getTitle(),
            n.getSource(),
            n.getPublishedAt(),
            truncate(n.getDescription()),
            i.getReason());
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= MAX_SUMMARY ? s : s.substring(0, MAX_SUMMARY) + "…";
    }
}
