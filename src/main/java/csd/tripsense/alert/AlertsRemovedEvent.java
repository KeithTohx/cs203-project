package csd.tripsense.alert;

// some of this user's alerts were deleted along with the verdicts they came from, eg after an
// edit made those verdicts out of date. carries no alert data, the browser reloads the list
public record AlertsRemovedEvent(Long userId) {}
