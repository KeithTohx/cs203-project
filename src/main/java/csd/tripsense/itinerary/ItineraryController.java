package csd.tripsense.itinerary;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ItineraryController {
    private ItineraryService itineraryService;

    public ItineraryController(ItineraryService itineraryService) {
        this.itineraryService = itineraryService;
    }

    @GetMapping("/itinerary")
    public List<ItineraryResponse> getAllItineraries() {
        return itineraryService.getAllItineraries().stream().map(ItineraryResponse::from).toList();
    }

    @GetMapping("/itinerary/{id}")
    public ItineraryResponse getItineraryById(@PathVariable Long id) {
        try {
            return ItineraryResponse.from(itineraryService.getItineraryById(id));
        } catch (ItineraryNotFoundException e) {
            return null;
        }
    }

    @GetMapping("/user/{userId}/itinerary")
    public List<ItineraryResponse> getItinerariesByUser(@PathVariable Long userId) {
        try {
            return itineraryService.getItinerariesByUser(userId).stream().map(ItineraryResponse::from).toList();
        } catch (ItineraryNotFoundException e) {
            return null;
        }
    }
    
    @GetMapping("/country/{countryId}/itinerary")
    public List<ItineraryResponse> getItinerariesByCountry(@PathVariable Long countryId) {
        try {
            return itineraryService.getItinerariesByCountry(countryId).stream().map(ItineraryResponse::from).toList();
        } catch (ItineraryNotFoundException e) {
            return null;
        }
    }

    @PostMapping("/itinerary")
    public ItineraryResponse addItinerary(@RequestBody Itinerary itinerary) {
        return ItineraryResponse.from(itineraryService.addItinerary(itinerary));
    }
    
    @PutMapping("/itinerary/{id}")
    public ItineraryResponse updateItinerary(@PathVariable Long id, @RequestBody Itinerary newItineraryInfo) {
        return ItineraryResponse.from(itineraryService.updateItinerary(id, newItineraryInfo));
    }
    
    @DeleteMapping("/itinerary/{id}")
    public void deleteItinerary(@PathVariable Long id){
        itineraryService.deleteItinerary(id);
    }
}
