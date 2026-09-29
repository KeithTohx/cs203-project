package csd.tripsense.activity;

import java.time.LocalDateTime;

/**
 * What the API returns for an activity.
 *
 * The itinerary is flattened to an id and a name, which stops each activity from
 * dragging along the itinerary, its user and its country.
 */
public record ActivityResponse(
        Long id,
        String name,
        String address,
        LocalDateTime dateStart,
        LocalDateTime dateEnd,
        Long itineraryId,
        String itineraryName) {

    public static ActivityResponse from(Activity activity) {
        return new ActivityResponse(
                activity.getId(),
                activity.getName(),
                activity.getAddress(),
                activity.getDateStart(),
                activity.getDateEnd(),
                activity.getItinerary() == null ? null : activity.getItinerary().getId(),
                activity.getItinerary() == null ? null : activity.getItinerary().getName());
    }
}
