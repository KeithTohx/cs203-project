package csd.tripsense.impact;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

// verdicts reached under older prompts (see Impact.CURRENT_PROMPT_VERSION) are cleared on start, the
// same way an edit clears verdicts on details the traveller no longer has. without this they would
// stay forever: the evaluator only judges a pair that has no verdict yet, and a trip that is never
// renamed or moved never has its whole trip verdicts cleared.
//
// the clearing goes through StaleVerdictCleaner, so alerts and advice go with each verdict, and the
// cleared articles are handed to NewItemEvaluationListener to be judged again with the new prompts.
// one transaction per trip, so each trip is judged again as its own run with its own max-calls
@Component
public class OutdatedVerdictSweeper {

    private static final Logger log = LoggerFactory.getLogger(OutdatedVerdictSweeper.class);

    private final ImpactRepository impacts;
    private final StaleVerdictCleaner cleaner;
    private final TransactionOperations transactions;
    private final boolean enabled;

    public OutdatedVerdictSweeper(ImpactRepository impacts, StaleVerdictCleaner cleaner,
                                  TransactionOperations transactions,
                                  @Value("${impact.eval.outdated-sweep.enabled:true}") boolean enabled) {
        this.impacts = impacts;
        this.cleaner = cleaner;
        this.transactions = transactions;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!enabled) {
            return;
        }

        // a failed sweep must not stop the app, the old verdicts just stay until the next start
        try {
            sweep();
        } catch (Exception e) {
            log.warn("Clearing verdicts from older prompts failed: {}", e.getMessage());
        }
    }

    // returns how many verdicts were cleared
    int sweep() {
        List<Long> tripIds = impacts.findTripsJudgedBeforePrompt(Impact.CURRENT_PROMPT_VERSION);
        if (tripIds.isEmpty()) {
            return 0;
        }

        int total = 0;
        for (Long tripId : tripIds) {
            Integer cleared = transactions.execute(status -> {
                List<Impact> fromOlderPrompts = impacts.findByItineraryId(tripId).stream()
                        .filter(OutdatedVerdictSweeper::isJudgedByOlderPrompts)
                        .toList();
                return cleaner.clearOutOfDate(fromOlderPrompts);
            });
            total += cleared == null ? 0 : cleared;
        }

        log.info("Cleared {} verdicts from older prompts on {} trips, they will be judged again",
                total, tripIds.size());
        return total;
    }

    // the same rule as the query that found the trips. the cleaner would also take anything else
    // out of date on the trip, like a seed row nobody has judged yet, so only these are handed over
    private static boolean isJudgedByOlderPrompts(Impact verdict) {
        boolean wasJudged = verdict.getReason() != null;
        return wasJudged && !verdict.isFromCurrentPrompt();
    }
}
