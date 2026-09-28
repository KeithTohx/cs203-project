package csd.tripsense.itinerary;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND) // 404 Error
public class ItineraryNotFoundException extends RuntimeException {

    public ItineraryNotFoundException(Long id) {
        super("Itinerary with ID: " + id + " not found");
    }

    public ItineraryNotFoundException(String message) {
        super(message);
    }
    
}
