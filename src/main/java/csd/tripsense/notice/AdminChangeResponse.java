package csd.tripsense.notice;

import java.time.Instant;

// no admin details, the traveller is only told that an admin did it.
// itineraryExists says whether the trip can still be opened, so the alert only links to one that is there
public record AdminChangeResponse(Long id, String message, Instant createdAt, Instant readAt,
                                  Long itineraryId, String itineraryName, boolean itineraryExists,
                                  Long newsId, String newsTitle) {

    public static AdminChangeResponse from(AdminChange change, boolean itineraryExists) {
        return new AdminChangeResponse(change.getId(), change.getMessage(), change.getCreatedAt(), change.getReadAt(),
                change.getItineraryId(), change.getItineraryName(), itineraryExists,
                change.getNewsId(), change.getNewsTitle());
    }
}
