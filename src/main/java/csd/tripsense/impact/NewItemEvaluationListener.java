package csd.tripsense.impact;

import csd.tripsense.activity.*;
import csd.tripsense.itinerary.*;
import csd.tripsense.news.NewsRepository;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Durable new-item evaluation. Events wake the worker; the database retains unfinished work. */
@Component
public class NewItemEvaluationListener {
    private static final Logger log = LoggerFactory.getLogger(NewItemEvaluationListener.class);
    private final ImpactEvaluationQueue queue;
    private final PendingImpactEvaluationRepository tasks;
    private final ItineraryRepository trips;
    private final ActivityRepository activities;
    private final NewsRepository news;
    private final ImpactRepository impacts;
    private final ImpactService evaluator;
    private final EvaluationProgress progress;
    private final boolean enabled;
    private final int maxCalls;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), runnable -> {
                Thread thread = new Thread(runnable, "new-item-evaluation"); thread.setDaemon(true); return thread;
            });

    public NewItemEvaluationListener(ImpactEvaluationQueue queue, PendingImpactEvaluationRepository tasks,
            ItineraryRepository trips, ActivityRepository activities, NewsRepository news,
            ImpactRepository impacts, ImpactService evaluator, EvaluationProgress progress,
            @Value("${impact.eval.new-itinerary.enabled:true}") boolean enabled,
            @Value("${impact.eval.new-itinerary.max-calls:200}") int maxCalls) {
        this.queue = queue; this.tasks = tasks; this.trips = trips; this.activities = activities;
        this.news = news; this.impacts = impacts; this.evaluator = evaluator; this.progress = progress;
        this.enabled = enabled; this.maxCalls = Math.max(1, maxCalls);
    }

    @EventListener
    public void onItineraryCreated(ItineraryCreatedEvent event) {
        if (enabled) queue.enqueue(event.itineraryId(), false);
    }

    @EventListener
    public void onActivityCreated(ActivityCreatedEvent event) {
        if (enabled) queue.enqueue(event.activityId(), true);
    }

    @TransactionalEventListener(classes = {ItineraryCreatedEvent.class, ActivityCreatedEvent.class},
            phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void afterCreationCommitted() { retryPending(); }

    @Scheduled(fixedDelayString = "${impact.eval.new-itinerary.retry-interval-ms:30000}", initialDelay = 15000)
    public void retryPending() {
        if (!enabled || !running.compareAndSet(false, true)) return;
        try {
            worker.execute(() -> {
                try { processPending(); }
                catch (RuntimeException exception) { log.warn("New-item evaluation deferred; saved tasks will retry"); }
                finally { running.set(false); }
            });
        } catch (RejectedExecutionException exception) {
            running.set(false); // shutdown or busy: the durable task is still present
        }
    }

    // Package-private for deterministic integration tests; production calls it through the single worker.
    void processPending() {
        Budget budget = new Budget(maxCalls);
        for (PendingImpactEvaluation task : tasks.findByRetryAtLessThanEqualOrderByRetryAtAscTaskKeyAsc(
                Instant.now(), PageRequest.of(0, 10))) {
            int cursor = task.getCursor();
            try {
                Activity activity = task.isActivity() ? activities.findById(task.getTargetId()).orElse(null) : null;
                Itinerary trip = task.isActivity() ? activity == null ? null : activity.getItinerary()
                        : trips.findById(task.getTargetId()).orElse(null);
                if (trip == null || trip.getCountry() == null || !trip.getUser().isEnabled()
                        || trip.getDateEnd() != null && trip.getDateEnd().isBefore(LocalDateTime.now())) {
                    queue.complete(task.getTaskKey()); continue;
                }
                List<Activity> targets = activity == null
                        ? activities.findByItineraryIdOrderByDateStart(trip.getId()) : List.of(activity);
                List<Long> articles = task.articles();
                for (; cursor < articles.size(); cursor++) {
                    var article = news.findById(articles.get(cursor)).orElse(null);
                    if (article != null) {
                        String batch = progress.begin(trip.getId(), article.getId());
                        boolean partial = true;
                        try {
                            // A new activity may arrive after fresh news; ensure its parent's verdict exists too.
                            if (impacts.findFirstByNewsIdAndItineraryIdAndActivityIsNull(article.getId(), trip.getId())
                                    .filter(i -> i.getReason() != null).isEmpty()) {
                                budget.take(); evaluator.evaluateItineraryForNews(trip, article);
                            }
                            for (Activity target : targets) {
                                if (impacts.findFirstByNewsIdAndActivityId(article.getId(), target.getId())
                                        .filter(i -> i.getReason() != null).isEmpty()) {
                                    budget.take(); evaluator.evaluateActivityForNews(target, article);
                                }
                            }
                            partial = false;
                        } finally { progress.finish(batch, partial); }
                    }
                    queue.progress(task.getTaskKey(), cursor + 1, Instant.now());
                }
                queue.complete(task.getTaskKey());
            } catch (BudgetReached exception) {
                queue.progress(task.getTaskKey(), cursor, Instant.now().plusSeconds(30));
                return;
            } catch (RuntimeException exception) {
                queue.progress(task.getTaskKey(), cursor, Instant.now().plusSeconds(60));
                log.warn("Evaluation task {} deferred; progress saved for retry", task.getTaskKey());
            }
        }
    }

    private static class BudgetReached extends RuntimeException {}
    private static class Budget {
        private int remaining;
        Budget(int remaining) { this.remaining = remaining; }
        void take() { if (remaining-- <= 0) throw new BudgetReached(); }
    }

    @PreDestroy void shutdown() { worker.shutdownNow(); }
}
