package csd.tripsense.impact;

// the trip or activity was edited, deleted or replaced by a new one with the same id while the
// model was judging it, so the verdict is about details the traveller no longer has and was thrown
// away instead of saved.
// not a model failure: the edit queues its own evaluation of the new details
public class EditedDuringEvaluationException extends org.springframework.dao.OptimisticLockingFailureException {

    public EditedDuringEvaluationException(String what, Long id) {
        super(what + " " + id + " was edited or deleted while it was being judged, so the verdict was not saved");
    }
}
