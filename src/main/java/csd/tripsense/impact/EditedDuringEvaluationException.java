package csd.tripsense.impact;

// the trip or activity was edited (or deleted) while the model was judging it, so the verdict is
// about details the traveller no longer has and was thrown away instead of saved.
// not a model failure: the edit queues its own evaluation of the new details
public class EditedDuringEvaluationException extends RuntimeException {

    public EditedDuringEvaluationException(String what, Long id) {
        super(what + " " + id + " was edited while it was being judged, so the verdict was not saved");
    }
}
