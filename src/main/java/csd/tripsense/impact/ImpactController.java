package csd.tripsense.impact;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import csd.tripsense.auth.CurrentUser;
import csd.tripsense.chat.ChatService;
import csd.tripsense.itinerary.ItineraryAccess;
import csd.tripsense.itinerary.ItineraryService;
import csd.tripsense.activity.ActivityService;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api")
public class ImpactController {
    private ImpactService impactService;
    private ChatService chatService;
    private ItineraryAccess access;
    private final ItineraryService itineraries;
    private final ActivityService activities;

    public ImpactController(ImpactService impactService, ChatService chatService, ItineraryAccess access,
                            ItineraryService itineraries, ActivityService activities) {
        this.impactService = impactService;
        this.chatService = chatService;
        this.access = access;
        this.itineraries = itineraries;
        this.activities = activities;
    }

    /**
     * List all impacts in the system
     *
     * @return list of all impacts
     */
    @GetMapping("/impact")
    public List<ImpactResponse> getImpacts(@AuthenticationPrincipal CurrentUser me) {
        access.requireSelf(me == null ? null : me.getId(), me);
        return impactService.listImpacts().stream()
                .filter(i -> access.canAccess(i.getItinerary(), me)).map(ImpactResponse::from).toList();
    }

    /**
     * Search for impact with the given id
     * If there is no impact with the given "id", throw a ImpactNotFoundException
     * @param id
     * @return impact with the given id
     */
    @GetMapping("/impact/{id}")
    public ImpactResponse getImpact(@PathVariable Long id, @AuthenticationPrincipal CurrentUser me){
        return ImpactResponse.from(access.requireAccess(impactService.getImpact(id), me));

    }

    /**
     * List all impacts for user.
     *
     * @param id
     * @return list of impacts belonging to user
     */
    @GetMapping("/user/{userId}/impact")
    public List<ImpactResponse> getImpactsByUser(@PathVariable Long userId, @AuthenticationPrincipal CurrentUser me) {
        access.requireSelf(userId, me);
        return impactService.getImpactsByUser(userId).stream()
                .filter(i -> access.canAccess(i.getItinerary(), me)).map(ImpactResponse::from).toList();

    }

    /**
     * List all impacts for itinerary.
     *
     * @param id
     * @return list of impacts belonging to itinerary
     */
    @GetMapping("/itinerary/{itineraryId}/impact")
    public List<ImpactResponse> getImpactsByItinerary(@PathVariable Long itineraryId, @AuthenticationPrincipal CurrentUser me) {
        access.requireAccess(itineraries.getItineraryById(itineraryId), me);
        return impactService.getImpactsByItinerary(itineraryId).stream().map(ImpactResponse::from).toList();

    }

    /**
     * List all impacts for activity.
     *
     * @param id
     * @return list of impacts belonging to activity
     */
    @GetMapping("/activity/{activityId}/impact")
    public List<ImpactResponse> getImpactsByActivity(@PathVariable Long activityId, @AuthenticationPrincipal CurrentUser me) {
        access.requireAccess(activities.getActivityById(activityId).getItinerary(), me);
        return impactService.getImpactsByActivity(activityId).stream().map(ImpactResponse::from).toList();

    }

    /**
     * Remove a impact with the DELETE request to "/impacts/{id}"
     * If there is no impact with the given "id", throw a ImpactNotFoundException
     * @param id
     */
    @DeleteMapping("/impact/{id}")
    public void deleteImpact(@PathVariable Long id, @AuthenticationPrincipal CurrentUser me){
        access.requireAccess(impactService.getImpact(id), me);
        impactService.deleteImpact(id);
    }

    /**
     * Reject an impact the user disagrees with, or restore one they rejected earlier
     * Only the owner of the impact's itinerary may do this, anyone else gets a 404
     *
     * @param id the impact to change
     * @param request whether the impact should be rejected
     * @return the updated impact
     */
    @PatchMapping("/impact/{id}")
    public ImpactResponse updateImpact(@PathVariable Long id, @Valid @RequestBody ImpactUpdateRequest request,
                                       @AuthenticationPrincipal CurrentUser me) {
        Impact impact = impactService.getImpact(id);
        access.requireAccess(impact, me);

        Impact updated = impactService.setRejected(id, request.rejected());
        return ImpactResponse.from(updated);
    }


    /**
     * Check every activity in an itinerary against one news article
     * Saves an impact record per activity and returns the verdicts
     *
     * @param request the itinerary and news to check
     * @return the verdict per activity, plus whether any of them is impacted
     */
    @PostMapping("/impact/evaluate")
    public EvaluateResponse evaluate(@Valid @RequestBody EvaluateRequest request, @AuthenticationPrincipal CurrentUser me) {
        access.requireAccess(itineraries.getItineraryById(request.itineraryId()), me);
        return impactService.evaluate(request.itineraryId(), request.newsId());
    }

    /**
     * Quick check that the AI model is reachable
     * Useful before running an evaluation, which takes a few seconds per activity
     *
     * @param prompt anything to send the model
     * @return the model reply, or a readable message if it could not be reached
     */
    @GetMapping("/impact/ping")
    public String ping(@RequestParam(defaultValue = "Say hello in one short sentence") String prompt) {
        return chatService.ask(prompt);
    }

}
