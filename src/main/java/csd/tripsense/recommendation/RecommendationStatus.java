package csd.tripsense.recommendation;

// what the traveller decided about one suggestion. a dismissed row is kept, not deleted,
// so the history still shows what we offered and they turned down
public enum RecommendationStatus {
    PENDING,
    ACCEPTED,
    DISMISSED
}
