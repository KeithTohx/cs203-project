package csd.tripsense.admin;

import csd.tripsense.impact.Impact;

// an out of date verdict that an edit deleted, summed up while the row still existed. the
// controller writes it to the audit log once the edit has committed, so the live data only holds
// verdicts about the trip as it is now, and the log still says what the traveller had been told
public record ClearedVerdict(
        Long impactId,
        Long ownerId,
        Long newsId,
        String newsTitle,
        boolean impacted,
        String reason,
        boolean rejected,
        boolean alerted,
        int recommendations) {

    // has to run inside the transaction, the alerts and recommendations are loaded lazily
    static ClearedVerdict of(Impact impact) {
        Long ownerId = impact.getUser() == null ? null : impact.getUser().getId();
        Long newsId = impact.getNews() == null ? null : impact.getNews().getId();
        String newsTitle = impact.getNews() == null ? null : impact.getNews().getTitle();
        boolean alerted = impact.getAlerts() != null && !impact.getAlerts().isEmpty();
        int recommendationCount = impact.getRecommendations() == null ? 0 : impact.getRecommendations().size();

        return new ClearedVerdict(
                impact.getId(),
                ownerId,
                newsId,
                newsTitle,
                Boolean.TRUE.equals(impact.getImpacted()),
                impact.getReason(),
                Boolean.TRUE.equals(impact.getRejected()),
                alerted,
                recommendationCount);
    }

    // the reason goes last, since the log cuts long details off at the end
    public String auditDetails(String cause) {
        StringBuilder details = new StringBuilder();
        details.append("impact ").append(impactId)
                .append(" on news ").append(newsId);
        if (newsTitle != null) {
            details.append(" '").append(newsTitle).append("'");
        }

        details.append(": ").append(impacted ? "impacted" : "not impacted");
        if (rejected) {
            details.append(", rejected by the traveller");
        }
        if (alerted) {
            details.append(", had an alert");
        }
        if (recommendations > 0) {
            String noun = recommendations == 1 ? "recommendation" : "recommendations";
            details.append(", ").append(recommendations).append(" ").append(noun);
        }

        details.append(". cleared because ").append(cause).append(" (by admin)");
        if (reason != null) {
            details.append(". reason: ").append(reason);
        }
        return details.toString();
    }
}
