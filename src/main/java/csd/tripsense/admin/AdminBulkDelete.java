package csd.tripsense.admin;

import csd.tripsense.itinerary.Itinerary;

// what a "delete all" in the admin console removed: how many rows, and the trip they were on so the
// controller can word the audit entry and the note to the traveller
public record AdminBulkDelete(int count, Itinerary itinerary) {
}
