package csd.tripsense.itinerary;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * What the API returns for an itinerary.
 *
 * The user and country are flattened to an id and a name, so the response does not
 * carry a whole user record every time an itinerary is listed.
 */
public record ItineraryResponse(
        Long id,
        String name,
        LocalDateTime dateStart,
        LocalDateTime dateEnd,
        Long userId,
        String username,
        Long countryId,
        String countryName,
        // null unless an admin has changed the trip, so the traveller can be shown a note
        Instant adminEditedAt) {

    public static ItineraryResponse from(Itinerary itinerary) {
        return new ItineraryResponse(
                itinerary.getId(),
                itinerary.getName(),
                itinerary.getDateStart(),
                itinerary.getDateEnd(),
                itinerary.getUser() == null ? null : itinerary.getUser().getId(),
                itinerary.getUser() == null ? null : itinerary.getUser().getUsername(),
                itinerary.getCountry() == null ? null : itinerary.getCountry().getId(),
                itinerary.getCountry() == null ? null : itinerary.getCountry().getName(),
                itinerary.getAdminEditedAt());
    }
}
