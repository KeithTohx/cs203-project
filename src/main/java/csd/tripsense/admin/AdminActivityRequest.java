package csd.tripsense.admin;

import java.time.LocalDateTime;

import csd.tripsense.activity.ActivityCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// an activity as an admin creates or replaces it. the itinerary comes from the url, and the
// country is copied from that itinerary like it is for travellers
public record AdminActivityRequest(
        @NotBlank(message = "name must not be blank")
        @Size(max = 100, message = "name must be at most 100 characters")
        String name,

        @Size(max = 200, message = "address must be at most 200 characters")
        String address,

        LocalDateTime dateStart,
        LocalDateTime dateEnd,

        // optional, the recommendation rules skip activities without one
        ActivityCategory category,

        // same as on an itinerary: true keeps the impacts on a name, address or start change
        Boolean keepImpacts) {

    public AdminActivityRequest(String name, String address, LocalDateTime dateStart, LocalDateTime dateEnd,
                                ActivityCategory category) {
        this(name, address, dateStart, dateEnd, category, null);
    }

    public boolean keepsImpacts() {
        return Boolean.TRUE.equals(keepImpacts);
    }
}
