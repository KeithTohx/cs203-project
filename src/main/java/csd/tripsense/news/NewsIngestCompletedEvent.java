package csd.tripsense.news;

/** Published when a GDELT file has been fully judged and its relevant articles saved. */
public record NewsIngestCompletedEvent(String sourceFile) {
}