package csd.tripsense.itinerary;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;



@RestController
public class ItineraryController {
    private ItineraryService itineraryService;

    public ItineraryController(ItineraryService itineraryService) {
        this.itineraryService = itineraryService;
    }

    @GetMapping("/itineraries")
    public List<Itinerary> getAllItineraries() {
        return itineraryService.getAllItineraries();
    }

    @GetMapping("/itineraries/(id)")
    public Itinerary getItineraryById(@PathVariable Long id) {
        try {
            return itineraryService.getItineraryById(id);
        } catch (ItineraryNotFoundException e) {
            return null;
        }
    }

    @GetMapping("/users/{id}/itineraries")
    public List<Itinerary> getItinerariesByUser(@PathVariable Long userId) {
        try {
            return itineraryService.getItinerariesByUser(userId);
        } catch (ItineraryNotFoundException e) {
            return null;
        }
    }
    
    @GetMapping("/countries/{id}/itineraries")
    public List<Itinerary> getItinerariesByCountry(@PathVariable Long countryId) {
        try {
            return itineraryService.getItinerariesByCountry(countryId);
        } catch (ItineraryNotFoundException e) {
            return null;
        }
    }
    
}
