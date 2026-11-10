package csd.tripsense.impact;
 
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
 
import csd.tripsense.activity.Activity;
import csd.tripsense.activity.ActivityCreatedEvent;
import csd.tripsense.activity.ActivityEditedEvent;
import csd.tripsense.activity.ActivityService;
import csd.tripsense.itinerary.Itinerary;
import csd.tripsense.itinerary.ItineraryCreatedEvent;
import csd.tripsense.itinerary.ItineraryEditedEvent;
import csd.tripsense.itinerary.ItineraryService;
import csd.tripsense.news.News;
import csd.tripsense.news.NewsService;
import jakarta.annotation.PreDestroy;
 
/**
 * When a new itinerary is saved, judges the news already in the database (the last
 * impact.eval.new-itinerary.news-lookback-days days) against it. ImpactEvaluationJob only runs when
 * fresh news arrives, so without this a new trip would never be checked against older stories.
 * The same goes for an edit to what a trip or activity was judged on, see StaleVerdictCleaner.
 *
 * Runs on its own single thread so creating an itinerary never waits on the model, and so two
 * new itineraries are judged one after the other instead of in parallel.
 */
@Component
public class NewItemEvaluationListener {
 
       private static final Logger log = LoggerFactory.getLogger(NewItemEvaluationListener.class);
    private static final int MAX_CONSECUTIVE_FAILURES = 3;
 
    private final ItineraryService itineraryService;
    private final ActivityService activityService;
    private final NewsService newsService;
    private final ImpactService impactService;
    private final boolean enabled;
    private final int lookbackDays;
    private final int maxCalls;
    private final Executor executor;
 
    @Autowired
    public NewItemEvaluationListener(ItineraryService itineraryService, ActivityService activityService,
                                          NewsService newsService, ImpactService impactService,
                                          @Value("${impact.eval.new-itinerary.enabled:true}") boolean enabled,
                                          @Value("${impact.eval.new-itinerary.news-lookback-days:5}") int lookbackDays,
                                          @Value("${impact.eval.new-itinerary.max-calls:200}") int maxCalls) {
        this(itineraryService, activityService, newsService, impactService,
                enabled, lookbackDays, maxCalls,
                Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "new-itinerary-eval");
                    thread.setDaemon(true);
                    return thread;
                }));
    }
 
    // package private so a test can pass a direct executor and avoid threads
    NewItemEvaluationListener(ItineraryService itineraryService, ActivityService activityService,
                                   NewsService newsService, ImpactService impactService,
                                   boolean enabled, int lookbackDays, int maxCalls, Executor executor) {
        this.itineraryService = itineraryService;
        this.activityService = activityService;
        this.newsService = newsService;
        this.impactService = impactService;
        this.enabled = enabled;
        this.lookbackDays = lookbackDays;
        this.maxCalls = maxCalls;
        this.executor = executor;
    }
 
    // after commit, so the background thread can always read the row. fallbackExecution because
    // addItinerary is not wrapped in a transaction of its own, and the event would be dropped otherwise
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onItineraryCreated(ItineraryCreatedEvent event) {
        if (!enabled) return;
        executor.execute(() -> evaluate(event.itineraryId()));
    }
 
    // an activity added to a trip that already exists, which is how most activities arrive
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onActivityCreated(ActivityCreatedEvent event) {
        if (!enabled) return;
        executor.execute(() -> evaluateActivity(event.activityId()));
    }

    // an edit to what the trip was judged on. StaleVerdictCleaner has already removed the verdicts
    // judged on the old details inside the edit's transaction, so judging it like a new trip only
    // asks about the pairs that are now missing, and reuses the verdicts that still stand.
    // when two edits queue up here, the first run judges the latest details and the second finds
    // nothing missing, so the model is not asked twice. a run that was still judging the old
    // details when the edit committed has its verdicts thrown away at save time, see
    // ImpactServiceImpl
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onItineraryEdited(ItineraryEditedEvent event) {
        if (!enabled) return;
        executor.execute(() -> evaluate(event.itineraryId()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onActivityEdited(ActivityEditedEvent event) {
        if (!enabled) return;
        executor.execute(() -> evaluateActivity(event.activityId()));
    }
 
    void evaluateActivity(Long activityId) {
        try {
            evaluateNewActivity(activityId);
        } catch (Exception e) {
            log.warn("New activity evaluation failed for activity {}: {}", activityId, e.getMessage());
        }
    }
 
    private void evaluateNewActivity(Long activityId) {
        Activity activity = activityService.getActivityById(activityId);
        Itinerary itinerary = activity.getItinerary();
 
        // same rules as for a new itinerary: ended trips and countryless trips are not judged
        if (itinerary == null || itinerary.getCountry() == null) return;
        if (itinerary.getDateEnd() != null && itinerary.getDateEnd().isBefore(LocalDateTime.now())) {
            log.info("Itinerary {} has already ended, skipping evaluation of new activity {}",
                    itinerary.getId(), activityId);
            return;
        }
 
        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusDays(lookbackDays);
        Progress progress = new Progress();
        for (News news : newsService.getNewsByCountrySince(itinerary.getCountry().getId(), since)) {
            if (!judge(progress, () -> impactService.evaluateActivityForNews(activity, news))) break;
        }
        log.info("New activity {}: judged {}, skipped {} ({})", activityId, progress.judged,
                progress.skipped, progress.stopReason == null ? "complete" : progress.stopReason);
    }
 
    @PreDestroy
    void shutdown() {
        if (executor instanceof ExecutorService service) {
            service.shutdown();
        }
    }
 
    void evaluate(Long itineraryId) {
        try {
            evaluateNewItinerary(itineraryId);
        } catch (Exception e) {
            // never let this reach the caller, the itinerary is already saved
            log.warn("New itinerary evaluation failed for itinerary {}: {}", itineraryId, e.getMessage());
        }
    }
 
    private void evaluateNewItinerary(Long itineraryId) {
        Itinerary itinerary = itineraryService.getItineraryById(itineraryId);
 
        // same rule as the ingest job: a trip that has already ended is not worth judging
        LocalDateTime now = LocalDateTime.now();                                          // trip dates
        if (itinerary.getDateEnd() != null && itinerary.getDateEnd().isBefore(now)) {
            log.info("New itinerary {} has already ended, skipping evaluation", itineraryId);
            return;
        }
        if (itinerary.getCountry() == null) return;
 
        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusDays(lookbackDays);  // news is stored in UTC
        List<News> newsList = newsService.getNewsByCountrySince(itinerary.getCountry().getId(), since);
        if (newsList.isEmpty()) {
            log.info("New itinerary {}: no news in the last {} days", itineraryId, lookbackDays);
            return;
        }
        List<Activity> activities = activityService.getActivitiesByItinerary(itineraryId);
 
        Progress progress = new Progress();
        outer:
        for (News news : newsList) {
            if (!judge(progress, () -> impactService.evaluateItineraryForNews(itinerary, news))) break outer;
            for (Activity activity : activities) {
                if (!judge(progress, () -> impactService.evaluateActivityForNews(activity, news))) break outer;
            }
        }
        log.info("New itinerary {}: judged {}, skipped {} ({})", itineraryId, progress.judged,
                progress.skipped, progress.stopReason == null ? "complete" : progress.stopReason);
    }
 
    // returns false when the run should stop (cap reached, or the model looks to be down)
    private boolean judge(Progress progress, Runnable call) {
        if (progress.judged >= maxCalls) {
            progress.stopReason = "cap reached";
            return false;
        }
        try {
            call.run();
            progress.judged++;
            progress.failures = 0;
        } catch (EditedDuringEvaluationException e) {
            // not the model failing, the edit has queued its own run for the new details
            progress.skipped++;
        } catch (Exception e) {
            progress.skipped++;
            if (++progress.failures >= MAX_CONSECUTIVE_FAILURES) {
                progress.stopReason = "model failing: " + e.getMessage();
                return false;
            }
        }
        return true;
    }
 
    private static final class Progress {
        int judged;
        int skipped;
        int failures;   // consecutive
        String stopReason;
    }
}