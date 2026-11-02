package csd.tripsense.alert;

/** Only a persisted id crosses transaction boundaries, never a managed entity. */
public record ImpactEvaluatedEvent(Long impactId) {}
