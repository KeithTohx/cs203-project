package csd.tripsense.admin;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// an itinerary as an admin creates or replaces it. the owner comes from the url, never the body
public record AdminItineraryRequest(
        @NotBlank(message = "name must not be blank")
        @Size(max = 100, message = "name must be at most 100 characters")
        String name,

        @NotNull(message = "countryId is required")
        Long countryId,

        LocalDateTime dateStart,
        LocalDateTime dateEnd,

        // on an edit, true keeps the impacts even when the name, country or dates change, eg when the
        // edit only fixes a typo. new news is still checked. ignored on create
        Boolean keepImpacts) {

    // without keepImpacts, so an edit clears what it makes out of date
    public AdminItineraryRequest(String name, Long countryId, LocalDateTime dateStart, LocalDateTime dateEnd) {
        this(name, countryId, dateStart, dateEnd, null);
    }

    public boolean keepsImpacts() {
        return Boolean.TRUE.equals(keepImpacts);
    }
}
