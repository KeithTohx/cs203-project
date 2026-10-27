package csd.tripsense.impact;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
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
    private final boolean enabled;
    private final int lookbackDays;
    private final int maxPerRun;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public ImpactEvaluationJob(ItineraryService itineraryService, ActivityService activityService,
                               NewsService newsService, ImpactService impactService,
                               @Value("${impact.eval.enabled:true}") boolean enabled,
                               @Value("${impact.eval.news-lookback-days:3}") int lookbackDays,
                               @Value("${impact.eval.max-per-run:200}") int maxPerRun) {
        this.itineraryService = itineraryService;
        this.activityService = activityService;
        this.newsService = newsService;
        this.impactService = impactService;
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

                for (Itinerary itinerary : itineraryService.getItinerariesPendingForNews(news.getId(), countryId, now)) {
                    if (judged >= maxPerRun) { done(judged, skipped, "cap reached"); return; }
                    try {
                        impactService.evaluateItineraryForNews(itinerary, news);
                        judged++;
                        failures = 0;
                    } catch (Exception e) {
                        skipped++;
                        if (++failures >= MAX_CONSECUTIVE_FAILURES) { done(judged, skipped, "model failing: " + e.getMessage()); return; }
                    }
                }

                for (Activity activity : activityService.getActivitiesPendingForNews(news.getId(), countryId, now)) {
                    if (judged >= maxPerRun) { done(judged, skipped, "cap reached"); return; }
                    try {
                        impactService.evaluateActivityForNews(activity, news);
                        judged++;
                        failures = 0;
                    } catch (Exception e) {
                        skipped++;
                        if (++failures >= MAX_CONSECUTIVE_FAILURES) { done(judged, skipped, "model failing: " + e.getMessage()); return; }
                    }
                }
            }
        }
        done(judged, skipped, "complete");
    }

    private void done(int judged, int skipped, String why) {
        log.info("Impact evaluation: judged {}, skipped {} ({})", judged, skipped, why);
    }
}