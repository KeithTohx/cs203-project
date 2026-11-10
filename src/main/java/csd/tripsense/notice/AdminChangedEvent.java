package csd.tripsense.notice;

// published after an admin change is saved, so the traveller's open tabs can reload their notice
public record AdminChangedEvent(Long userId) {
}
