package csd.tripsense.itinerary;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import csd.tripsense.auth.CurrentUser;

@RestController
@RequestMapping("/api")
public class ItineraryController {
    private ItineraryService itineraryService;
    private ItineraryAccess access;

    public ItineraryController(ItineraryService itineraryService, ItineraryAccess access) {
        this.itineraryService = itineraryService;
        this.access = access;
    }

    // Your own itineraries (an admin sees everyone's).
    @GetMapping("/itinerary")
    public List<ItineraryResponse> getAllItineraries(@AuthenticationPrincipal CurrentUser me) {
        return access.visibleTo(itineraryService.getAllItineraries(), me).stream().map(ItineraryResponse::from).toList();
    }

    // Not yours (or not found) -> 404.
    @GetMapping("/itinerary/{id}")
    public ItineraryResponse getItineraryById(@PathVariable Long id, @AuthenticationPrincipal CurrentUser me) {
        return ItineraryResponse.from(access.requireAccess(itineraryService.getItineraryById(id), me));
    }

    // Only that user, or an admin; anyone else -> 404.
    @GetMapping("/user/{userId}/itinerary")
    public List<ItineraryResponse> getItinerariesByUser(@PathVariable Long userId, @AuthenticationPrincipal CurrentUser me) {
        access.requireSelfOrAdmin(userId, me);
        try {
            return itineraryService.getItinerariesByUser(userId).stream().map(ItineraryResponse::from).toList();
        } catch (ItineraryNotFoundException e) {
            return List.of();
        }
    }

    // Only the itineraries of that country that you may see.
    @GetMapping("/country/{countryId}/itinerary")
    public List<ItineraryResponse> getItinerariesByCountry(@PathVariable Long countryId, @AuthenticationPrincipal CurrentUser me) {
        try {
            return access.visibleTo(itineraryService.getItinerariesByCountry(countryId), me).stream().map(ItineraryResponse::from).toList();
        } catch (ItineraryNotFoundException e) {
            return List.of();
        }
    }

    // The owner is the logged-in user; any "user" or "id" in the request body is ignored.
    @PostMapping("/itinerary")
    public ItineraryResponse addItinerary(@RequestBody Itinerary itinerary, @AuthenticationPrincipal CurrentUser me) {
        return ItineraryResponse.from(itineraryService.addItinerary(access.prepareNew(itinerary, me)));
    }

    @PutMapping("/itinerary/{id}")
    public ItineraryResponse updateItinerary(@PathVariable Long id, @RequestBody Itinerary newItineraryInfo,
                                             @AuthenticationPrincipal CurrentUser me) {
        access.requireAccess(itineraryService.getItineraryById(id), me);
        return ItineraryResponse.from(itineraryService.updateItinerary(id, newItineraryInfo));
    }

    @DeleteMapping("/itinerary/{id}")
    public void deleteItinerary(@PathVariable Long id, @AuthenticationPrincipal CurrentUser me) {
        access.requireAccess(itineraryService.getItineraryById(id), me);
        itineraryService.deleteItinerary(id);
    }
}
