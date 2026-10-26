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
import csd.tripsense.audit.AuditService;

@RestController
@RequestMapping("/api")
public class ItineraryController {
    private ItineraryService itineraryService;
    private ItineraryAccess access;
    private AuditService auditService;

    public ItineraryController(ItineraryService itineraryService, ItineraryAccess access, AuditService auditService) {
        this.itineraryService = itineraryService;
        this.access = access;
        this.auditService = auditService;
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
        Itinerary saved = itineraryService.addItinerary(access.prepareNew(itinerary, me));
        audit(AuditService.ITINERARY_CREATED, me, saved);
        return ItineraryResponse.from(saved);
    }

    @PutMapping("/itinerary/{id}")
    public ItineraryResponse updateItinerary(@PathVariable Long id, @RequestBody Itinerary newItineraryInfo,
                                             @AuthenticationPrincipal CurrentUser me) {
        Itinerary existing = access.requireAccess(itineraryService.getItineraryById(id), me);
        Long ownerId = ownerId(existing);
        Itinerary updated = itineraryService.updateItinerary(id, newItineraryInfo);
        auditService.record(AuditService.ITINERARY_UPDATED, me.getId(), me.getEmail(), ownerId, describe(updated));
        return ItineraryResponse.from(updated);
    }

    @DeleteMapping("/itinerary/{id}")
    public void deleteItinerary(@PathVariable Long id, @AuthenticationPrincipal CurrentUser me) {
        Itinerary existing = access.requireAccess(itineraryService.getItineraryById(id), me);
        Long ownerId = ownerId(existing);
        String description = describe(existing);   // read before it is gone
        itineraryService.deleteItinerary(id);
        auditService.record(AuditService.ITINERARY_DELETED, me.getId(), me.getEmail(), ownerId, description);
    }

    // The account the change is about = the itinerary's owner (the caller, or the owner when an admin edits it).
    private void audit(String action, CurrentUser me, Itinerary itinerary) {
        auditService.record(action, me.getId(), me.getEmail(), ownerId(itinerary), describe(itinerary));
    }

    private static Long ownerId(Itinerary itinerary) {
        return itinerary.getUser() == null ? null : itinerary.getUser().getId();
    }

    private static String describe(Itinerary itinerary) {
        return "itinerary " + itinerary.getId() + " '" + itinerary.getName() + "'";
    }
}
