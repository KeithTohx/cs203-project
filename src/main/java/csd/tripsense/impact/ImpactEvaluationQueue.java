package csd.tripsense.impact;

import csd.tripsense.activity.ActivityRepository;
import csd.tripsense.itinerary.Itinerary;
import csd.tripsense.itinerary.ItineraryRepository;
import csd.tripsense.news.NewsRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@org.springframework.context.annotation.DependsOn("evaluationTaskSchemaMigration")
public class ImpactEvaluationQueue {
    private final PendingImpactEvaluationRepository tasks;
    private final ItineraryRepository trips;
    private final ActivityRepository activities;
    private final NewsRepository news;
    private final int lookbackDays;

    public ImpactEvaluationQueue(PendingImpactEvaluationRepository tasks, ItineraryRepository trips,
            ActivityRepository activities, NewsRepository news,
            @Value("${impact.eval.new-itinerary.news-lookback-days:5}") int lookbackDays) {
        this.tasks = tasks; this.trips = trips; this.activities = activities; this.news = news;
        this.lookbackDays = Math.max(1, lookbackDays);
    }

    /** Runs after source commit; a scheduling failure cannot roll back the saved trip/activity. */
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void enqueue(Long id, boolean activity) {
        if (tasks.existsByTargetIdAndActivity(id, activity)) return;
        Itinerary trip = activity
                ? activities.findById(id).map(a -> a.getItinerary()).orElse(null)
                : trips.findById(id).orElse(null);
        if (trip == null || trip.getCountry() == null || !trip.getUser().isEnabled()
                || trip.getDateEnd() != null && trip.getDateEnd().isBefore(LocalDateTime.now())) return;
        var articles = news.findByCountryIdSince(trip.getCountry().getId(),
                LocalDateTime.now(ZoneOffset.UTC).minusDays(lookbackDays)).stream()
                .map(n -> n.getId()).distinct().sorted().toList();
        if (!articles.isEmpty()) tasks.save(new PendingImpactEvaluation(id, activity, articles));
    }

    /** A short transaction; AI calls never hold a database transaction open. */
    @Transactional
    public void progress(String key, int cursor, Instant retryAt) {
        tasks.findById(key).ifPresent(task -> task.advance(cursor, retryAt));
    }

    @Transactional
    public void deferArticle(String key,Long newsId,int nextCursor) {
        tasks.findById(key).ifPresent(task -> { task.defer(newsId); task.advance(nextCursor,Instant.now().plusSeconds(60)); });
    }

    @Transactional
    public void finishPass(String key) {
        tasks.findById(key).ifPresent(task -> {
            if (task.deferredArticles().isEmpty()) tasks.deleteById(key);
            else task.retryDeferred(Instant.now().plusSeconds(60));
        });
    }

    @Transactional
    public void complete(String key) { tasks.deleteById(key); }
}
