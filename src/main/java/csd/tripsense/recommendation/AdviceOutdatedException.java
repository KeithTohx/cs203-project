package csd.tripsense.recommendation;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

// the verdict the advice was being written for changed or went away while the model was answering,
// usually because the traveller edited the activity. nothing was saved, and the trip is being
// judged again, so the client should reload rather than retry straight away
@ResponseStatus(HttpStatus.CONFLICT)
public class AdviceOutdatedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public AdviceOutdatedException(Long impactId) {
        super("Impact " + impactId + " changed while its advice was being written, so the advice was not saved");
    }
}
