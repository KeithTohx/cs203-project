package csd.tripsense.itinerary;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ItineraryController {
    private ItineraryService itineraryService;

    public ItineraryController(ItineraryService itineraryService) {
        this.itineraryService = itineraryService;
    }

    @GetMapping("/api/itinerary")
    public List<Itinerary> getAllItineraries() {
        return itineraryService.getAllItineraries();
    }

    @GetMapping("/api/itinerary/{id}")
    public Itinerary getItineraryById(@PathVariable Long id) {
        try {
            return itineraryService.getItineraryById(id);
        } catch (ItineraryNotFoundException e) {
            return null;
        }
    }

    @GetMapping("/api/user/{userId}/itinerary")
    public List<Itinerary> getItinerariesByUser(@PathVariable Long userId) {
        try {
            return itineraryService.getItinerariesByUser(userId);
        } catch (ItineraryNotFoundException e) {
            return null;
        }
    }
    
    @GetMapping("/api/country/{countryId}/itinerary")
    public List<Itinerary> getItinerariesByCountry(@PathVariable Long countryId) {
        try {
            return itineraryService.getItinerariesByCountry(countryId);
        } catch (ItineraryNotFoundException e) {
            return null;
        }
    }

    @PostMapping("/api/itinerary")
    public Itinerary addItinerary(@RequestBody Itinerary itinerary) {
        return itineraryService.addItinerary(itinerary);
    }
    
    @PutMapping("/api/itinerary/{id}")
    public Itinerary updateItinerary(@PathVariable Long id, @RequestBody Itinerary newItineraryInfo) {
        return itineraryService.updateItinerary(id, newItineraryInfo);
    }
    
    @DeleteMapping("/api/itinerary/{id}")
    public void deleteItinerary(@PathVariable Long id){
        itineraryService.deleteItinerary(id);
    }
}
