package csd.tripsense.alert;

/** Only an invalidation; recipients reload through their own authenticated session. */
public record AlertsChangedEvent(Long userId) {}
