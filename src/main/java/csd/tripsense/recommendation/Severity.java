package csd.tripsense.recommendation;

/**
 * How badly one activity is hit.
 *
 * Declared worst first on purpose, so the natural enum order already sorts a list the way
 * the traveller wants to read it and no extra comparator is needed.
 */
public enum Severity {
    HIGH,
    MEDIUM,
    LOW
}
