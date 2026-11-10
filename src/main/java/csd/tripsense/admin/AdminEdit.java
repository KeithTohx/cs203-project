package csd.tripsense.admin;

import java.util.List;

// what an itinerary or activity edit gives back: the saved record, and the verdicts it cleared
// because they were made for the old details. the list is empty when nothing judged had changed
public record AdminEdit<T>(T updated, List<ClearedVerdict> clearedVerdicts) {
}
