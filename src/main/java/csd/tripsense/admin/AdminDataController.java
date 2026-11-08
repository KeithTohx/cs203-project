package csd.tripsense.admin;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import csd.tripsense.activity.Activity;
import csd.tripsense.activity.ActivityResponse;
import csd.tripsense.audit.AuditService;
import csd.tripsense.auth.CurrentUser;
import csd.tripsense.impact.Impact;
import csd.tripsense.impact.ImpactResponse;
import csd.tripsense.itinerary.Itinerary;
import csd.tripsense.itinerary.ItineraryResponse;
import csd.tripsense.recommendation.Recommendation;
import csd.tripsense.recommendation.RecommendationResponse;
import csd.tripsense.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

// the admin console's data endpoints. like the rest of /api/admin, USER gets 403 and
// logged out gets 401. every change is written to the audit log against the account that owns
// the data, so it shows up on that user's admin page
@RestController
@RequestMapping("/api/admin")
@Tag(name = "Admin console", description = "Read and change any account's trip data, mainly for testing")
public class AdminDataController {

    private final AdminDataService adminData;
    private final AuditService auditService;

    public AdminDataController(AdminDataService adminData, AuditService auditService) {
        this.adminData = adminData;
        this.auditService = auditService;
    }

    // ---------- accounts ----------

    @Operation(summary = "Create an account", description = "Any role. The password is hashed like on registration.")
    @PostMapping("/users")
    public ResponseEntity<AdminUserResponse> createUser(@Valid @RequestBody CreateUserRequest request,
                                                        @AuthenticationPrincipal CurrentUser admin) {
        requireLoggedIn(admin);
        User created = adminData.createUser(request);

        String details = "created " + created.getRole() + " account '" + created.getUsername() + "'";
        auditService.record(AuditService.ACCOUNT_CREATED, admin.getId(), admin.getEmail(), created.getId(), details);
        return ResponseEntity.status(HttpStatus.CREATED).body(AdminUserResponse.from(created));
    }

    // ---------- itineraries ----------

    @Operation(summary = "List one account's itineraries")
    @GetMapping("/users/{userId}/itineraries")
    public List<ItineraryResponse> listItineraries(@PathVariable("userId") Long userId) {
        return adminData.listItineraries(userId).stream().map(ItineraryResponse::from).toList();
    }

    @Operation(summary = "Create an itinerary for an account")
    @PostMapping("/users/{userId}/itineraries")
    public ResponseEntity<ItineraryResponse> createItinerary(@PathVariable("userId") Long userId,
                                                             @Valid @RequestBody AdminItineraryRequest request,
                                                             @AuthenticationPrincipal CurrentUser admin) {
        requireLoggedIn(admin);
        Itinerary created = adminData.createItinerary(userId, request);

        recordByAdmin(AuditService.ITINERARY_CREATED, admin, userId, describe(created));
        return ResponseEntity.status(HttpStatus.CREATED).body(ItineraryResponse.from(created));
    }

    @Operation(summary = "Replace an itinerary's name, country and dates")
    @PutMapping("/itineraries/{id}")
    public ItineraryResponse updateItinerary(@PathVariable("id") Long id,
                                             @Valid @RequestBody AdminItineraryRequest request,
                                             @AuthenticationPrincipal CurrentUser admin) {
        requireLoggedIn(admin);
        Itinerary updated = adminData.updateItinerary(id, request);

        recordByAdmin(AuditService.ITINERARY_UPDATED, admin, ownerOf(updated), describe(updated));
        return ItineraryResponse.from(updated);
    }

    @Operation(summary = "Delete an itinerary",
            description = "Also removes its activities, impacts, alerts and recommendations.")
    @DeleteMapping("/itineraries/{id}")
    public ResponseEntity<Void> deleteItinerary(@PathVariable("id") Long id, @AuthenticationPrincipal CurrentUser admin) {
        requireLoggedIn(admin);
        Itinerary deleted = adminData.deleteItinerary(id);

        recordByAdmin(AuditService.ITINERARY_DELETED, admin, ownerOf(deleted), describe(deleted));
        return ResponseEntity.noContent().build();
    }

    // ---------- activities ----------

    @Operation(summary = "List an itinerary's activities", description = "Earliest first.")
    @GetMapping("/itineraries/{itineraryId}/activities")
    public List<ActivityResponse> listActivities(@PathVariable("itineraryId") Long itineraryId) {
        return adminData.listActivities(itineraryId).stream().map(ActivityResponse::from).toList();
    }

    @Operation(summary = "Add an activity to an itinerary")
    @PostMapping("/itineraries/{itineraryId}/activities")
    public ResponseEntity<ActivityResponse> createActivity(@PathVariable("itineraryId") Long itineraryId,
                                                           @Valid @RequestBody AdminActivityRequest request,
                                                           @AuthenticationPrincipal CurrentUser admin) {
        requireLoggedIn(admin);
        Activity created = adminData.createActivity(itineraryId, request);

        recordByAdmin(AuditService.ACTIVITY_CREATED, admin, ownerOf(created.getItinerary()), describe(created));
        return ResponseEntity.status(HttpStatus.CREATED).body(ActivityResponse.from(created));
    }

    @Operation(summary = "Replace an activity's details", description = "It stays on the same itinerary.")
    @PutMapping("/activities/{id}")
    public ActivityResponse updateActivity(@PathVariable("id") Long id,
                                           @Valid @RequestBody AdminActivityRequest request,
                                           @AuthenticationPrincipal CurrentUser admin) {
        requireLoggedIn(admin);
        Activity updated = adminData.updateActivity(id, request);

        recordByAdmin(AuditService.ACTIVITY_UPDATED, admin, ownerOf(updated.getItinerary()), describe(updated));
        return ActivityResponse.from(updated);
    }

    @Operation(summary = "Delete an activity", description = "Also removes the impacts on it and what hangs off them.")
    @DeleteMapping("/activities/{id}")
    public ResponseEntity<Void> deleteActivity(@PathVariable("id") Long id, @AuthenticationPrincipal CurrentUser admin) {
        requireLoggedIn(admin);
        Activity deleted = adminData.deleteActivity(id);

        recordByAdmin(AuditService.ACTIVITY_DELETED, admin, ownerOf(deleted.getItinerary()), describe(deleted));
        return ResponseEntity.noContent().build();
    }

    // ---------- impacts ----------

    @Operation(summary = "List one account's impacts", description = "Newest first, rejected ones included.")
    @GetMapping("/users/{userId}/impacts")
    public List<ImpactResponse> listImpacts(@PathVariable("userId") Long userId) {
        return adminData.listImpacts(userId).stream().map(ImpactResponse::from).toList();
    }

    @Operation(summary = "Create an impact by hand",
            description = "Links a news article to one of the account's itineraries, and optionally one of its "
                    + "activities. sendAlert raises an alert like a real evaluation would.")
    @PostMapping("/users/{userId}/impacts")
    public ResponseEntity<ImpactResponse> createImpact(@PathVariable("userId") Long userId,
                                                       @Valid @RequestBody AdminImpactRequest request,
                                                       @AuthenticationPrincipal CurrentUser admin) {
        requireLoggedIn(admin);
        Impact created = adminData.createImpact(userId, request);

        recordByAdmin(AuditService.IMPACT_CREATED, admin, userId, describe(created));
        return ResponseEntity.status(HttpStatus.CREATED).body(ImpactResponse.from(created));
    }

    @Operation(summary = "Change an impact's verdict, reason or rejected flag")
    @PutMapping("/impacts/{id}")
    public ImpactResponse updateImpact(@PathVariable("id") Long id,
                                       @Valid @RequestBody AdminImpactUpdateRequest request,
                                       @AuthenticationPrincipal CurrentUser admin) {
        requireLoggedIn(admin);
        Impact updated = adminData.updateImpact(id, request);

        recordByAdmin(AuditService.IMPACT_UPDATED, admin, ownerOf(updated), describe(updated));
        return ImpactResponse.from(updated);
    }

    @Operation(summary = "Delete an impact", description = "Also removes its alert and recommendations.")
    @DeleteMapping("/impacts/{id}")
    public ResponseEntity<Void> deleteImpact(@PathVariable("id") Long id, @AuthenticationPrincipal CurrentUser admin) {
        requireLoggedIn(admin);
        Impact deleted = adminData.deleteImpact(id);

        recordByAdmin(AuditService.IMPACT_DELETED, admin, ownerOf(deleted), describe(deleted));
        return ResponseEntity.noContent().build();
    }

    // ---------- recommendations ----------

    @Operation(summary = "List one account's recommendations", description = "Every attempt, newest first.")
    @GetMapping("/users/{userId}/recommendations")
    public List<RecommendationResponse> listRecommendations(@PathVariable("userId") Long userId) {
        return adminData.listRecommendations(userId).stream().map(RecommendationResponse::from).toList();
    }

    @Operation(summary = "Create a recommendation on an impact by hand")
    @PostMapping("/impacts/{impactId}/recommendations")
    public ResponseEntity<RecommendationResponse> createRecommendation(@PathVariable("impactId") Long impactId,
                                                                       @Valid @RequestBody AdminRecommendationRequest request,
                                                                       @AuthenticationPrincipal CurrentUser admin) {
        requireLoggedIn(admin);
        Recommendation created = adminData.createRecommendation(impactId, request);

        recordByAdmin(AuditService.RECOMMENDATION_CREATED, admin, ownerOf(created.getImpact()), describe(created));
        return ResponseEntity.status(HttpStatus.CREATED).body(RecommendationResponse.from(created));
    }

    @Operation(summary = "Replace a recommendation's severity, action, advice, option and status")
    @PutMapping("/recommendations/{id}")
    public RecommendationResponse updateRecommendation(@PathVariable("id") Long id,
                                                       @Valid @RequestBody AdminRecommendationRequest request,
                                                       @AuthenticationPrincipal CurrentUser admin) {
        requireLoggedIn(admin);
        Recommendation updated = adminData.updateRecommendation(id, request);

        recordByAdmin(AuditService.RECOMMENDATION_UPDATED, admin, ownerOf(updated.getImpact()), describe(updated));
        return RecommendationResponse.from(updated);
    }

    @Operation(summary = "Delete a recommendation")
    @DeleteMapping("/recommendations/{id}")
    public ResponseEntity<Void> deleteRecommendation(@PathVariable("id") Long id,
                                                     @AuthenticationPrincipal CurrentUser admin) {
        requireLoggedIn(admin);
        Recommendation deleted = adminData.deleteRecommendation(id);

        recordByAdmin(AuditService.RECOMMENDATION_DELETED, admin, ownerOf(deleted.getImpact()), describe(deleted));
        return ResponseEntity.noContent().build();
    }

    // ---------- helpers ----------

    // normally unreachable, the security filter already answers 401 before this runs
    private static void requireLoggedIn(CurrentUser admin) {
        if (admin == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not logged in");
        }
    }

    // recorded against the owner of the data, so it shows up on their admin page
    private void recordByAdmin(String action, CurrentUser admin, Long ownerId, String details) {
        auditService.record(action, admin.getId(), admin.getEmail(), ownerId, details + " (by admin)");
    }

    private static Long ownerOf(Itinerary itinerary) {
        if (itinerary == null || itinerary.getUser() == null) {
            return null;
        }
        return itinerary.getUser().getId();
    }

    private static Long ownerOf(Impact impact) {
        if (impact == null || impact.getUser() == null) {
            return null;
        }
        return impact.getUser().getId();
    }

    private static String describe(Itinerary itinerary) {
        return "itinerary " + itinerary.getId() + " '" + itinerary.getName() + "'";
    }

    private static String describe(Activity activity) {
        return "activity " + activity.getId() + " '" + activity.getName() + "'";
    }

    private static String describe(Impact impact) {
        String verdict = Boolean.TRUE.equals(impact.getImpacted()) ? "impacted" : "not impacted";
        return "impact " + impact.getId() + " (" + verdict + ")";
    }

    private static String describe(Recommendation recommendation) {
        return "recommendation " + recommendation.getId() + " (" + recommendation.getAction() + ")";
    }
}
