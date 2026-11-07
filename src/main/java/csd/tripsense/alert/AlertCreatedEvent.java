package csd.tripsense.alert;

public record AlertCreatedEvent(Long userId, AlertResponse alert) {}
