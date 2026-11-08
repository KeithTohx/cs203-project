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
        LocalDateTime dateEnd) {
}
