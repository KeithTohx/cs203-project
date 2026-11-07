package csd.tripsense.impact;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import csd.tripsense.activity.Activity;
import csd.tripsense.activity.ActivityService;
import csd.tripsense.itinerary.Itinerary;
import csd.tripsense.itinerary.ItineraryService;
import csd.tripsense.news.News;
import csd.tripsense.news.NewsIngestCompletedEvent;
import csd.tripsense.news.NewsService;

/**
 * After a GDELT file has been fully ingested, judges recent news against the itineraries and
 * activities it could affect. Only countries with an active itinerary are looked at, and a
 * (news, itinerary) or (news, activity) pair that already has a stored verdict is never asked again.
 */
@Component
public class ImpactEvaluationJob {

    private static final Logger log = LoggerFactory.getLogger(ImpactEvaluationJob.class);
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    private final ItineraryService itineraryService;
    private final ActivityService activityService;
    private final NewsService newsService;
    private final ImpactService impactService;
    private final EvaluationProgress progress;
    private final boolean enabled;
    private final int lookbackDays;
    private final int maxPerRun;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public ImpactEvaluationJob(ItineraryService itineraryService, ActivityService activityService,
                               NewsService newsService, ImpactService impactService, EvaluationProgress progress,
                               @Value("${impact.eval.enabled:true}") boolean enabled,
                               @Value("${impact.eval.news-lookback-days:3}") int lookbackDays,
                               @Value("${impact.eval.max-per-run:200}") int maxPerRun) {
        this.itineraryService = itineraryService;
        this.activityService = activityService;
        this.newsService = newsService;
        this.impactService = impactService; this.progress = progress;
        this.enabled = enabled;
        this.lookbackDays = lookbackDays;
        this.maxPerRun = maxPerRun;
    }

    @EventListener
    public void onIngestCompleted(NewsIngestCompletedEvent event) {
        log.info("Impact evaluation triggered by {}", event.sourceFile());
        run();
    }

    public void run() {
        if (!enabled) return;
        if (!running.compareAndSet(false, true)) {
            log.info("Impact evaluation already running, skipping");
            return;
        }
        try {
            evaluateAll();
        } catch (Exception e) {
            log.warn("Impact evaluation failed: {}", e.getMessage());
        } finally {
            running.set(false);
        }
    }

    private void evaluateAll() {
        LocalDateTime now = LocalDateTime.now();                                    // trip dates
        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusDays(lookbackDays); // news is stored in UTC

        int judged = 0;
        int failures = 0;      // consecutive
        int skipped = 0;

        List<Long> countryIds = itineraryService.getCountryIdsWithActiveItineraries(now);
        log.info("Impact evaluation: {} countries with active itineraries {}", countryIds.size(), countryIds);
        for (Long countryId : countryIds) {
            for (News news : newsService.getNewsByCountrySince(countryId, since)) {

                var tripTargets = itineraryService.getItinerariesPendingForNews(news.getId(), countryId, now);
                var activityTargets = activityService.getActivitiesPendingForNews(news.getId(), countryId, now);
                var batches = new HashMap<Long, String>();
                for (Itinerary trip : tripTargets) batches.computeIfAbsent(trip.getId(), id -> progress.begin(id, news.getId(),
                        tripTargets.stream().anyMatch(t -> t.getId().equals(id)),
                        activityTargets.stream().filter(a -> a.getItinerary().getId().equals(id)).map(Activity::getId).toList()));
                for (Activity activity : activityTargets) batches.computeIfAbsent(activity.getItinerary().getId(),
                        id -> progress.begin(id, news.getId(),
                        tripTargets.stream().anyMatch(t -> t.getId().equals(id)),
                        activityTargets.stream().filter(a -> a.getItinerary().getId().equals(id)).map(Activity::getId).toList()));
                var expected = new HashMap<Long, Integer>();
                var completed = new HashMap<Long, Integer>();
                tripTargets.forEach(t -> expected.merge(t.getId(), 1, Integer::sum));
                activityTargets.forEach(a -> expected.merge(a.getItinerary().getId(), 1, Integer::sum));
                try {
                    for (Itinerary itinerary : tripTargets) {
                        if (judged >= maxPerRun) { done(judged, skipped, "cap reached"); return; }
                        try {
                            impactService.evaluateItineraryForNews(itinerary, news);
                            progress.resolved(itinerary.getId(),news.getId(),null);
                            completed.merge(itinerary.getId(), 1, Integer::sum);
                            judged++;
                            failures = 0;
                        } catch (Exception e) {
                            skipped++;
                            if (++failures >= MAX_CONSECUTIVE_FAILURES) { done(judged, skipped, "model failing: " + e.getMessage()); return; }
                        }
                    }

                    for (Activity activity : activityTargets) {
                        if (judged >= maxPerRun) { done(judged, skipped, "cap reached"); return; }
                        try {
                            impactService.evaluateActivityForNews(activity, news);
                            progress.resolved(activity.getItinerary().getId(),news.getId(),activity.getId());
                            completed.merge(activity.getItinerary().getId(), 1, Integer::sum);
                            judged++;
                            failures = 0;
                        } catch (Exception e) {
                            skipped++;
                            if (++failures >= MAX_CONSECUTIVE_FAILURES) { done(judged, skipped, "model failing: " + e.getMessage()); return; }
                        }
                    }
                } finally {
                    batches.forEach((tripId, batch) -> progress.finish(batch,
                            completed.getOrDefault(tripId, 0) < expected.get(tripId)));
                }
            }
        }
        done(judged, skipped, "complete");
    }

    private void done(int judged, int skipped, String why) {
        log.info("Impact evaluation: judged {}, skipped {} ({})", judged, skipped, why);
    }
}