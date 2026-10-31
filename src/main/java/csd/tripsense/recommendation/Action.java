package csd.tripsense.recommendation;

/**
 * The one thing we are telling the traveller to do. "Consider your options" is exactly what
 * the story says fails, so every recommendation has to land on one of these.
 */
public enum Action {

    REPLACE,      // do something else instead, we name the alternative
    RELOCATE,     // same kind of activity, somewhere else
    RESCHEDULE,   // same activity, another day
    MONITOR,      // nothing to change yet, keep watching
    CANCEL        // drop it
}
