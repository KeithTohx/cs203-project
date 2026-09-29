package csd.tripsense.impact;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import csd.tripsense.chat.ChatService;
import jakarta.validation.Valid;

@RestController 
@RequestMapping("/api")
public class ImpactController {
    private ImpactService impactService;
    private ChatService chatService;

    public ImpactController(ImpactService impactService, ChatService chatService) {
        this.impactService = impactService;
        this.chatService = chatService;
    }

    /**
     * List all impacts in the system
     *
     * @return list of all impacts
     */
    @GetMapping("/impact")
    public List<ImpactResponse> getImpacts() {
        return impactService.listImpacts().stream().map(ImpactResponse::from).toList();
    }

    /**
     * Search for impact with the given id
     * If there is no impact with the given "id", throw a ImpactNotFoundException
     * @param id
     * @return impact with the given id
     */
    @GetMapping("/impact/{id}")
    public ImpactResponse getImpact(@PathVariable Long id){
        return ImpactResponse.from(impactService.getImpact(id));

    }

    /**
     * List all impacts for user.
     *
     * @param id
     * @return list of impacts belonging to user
     */
    @GetMapping("/user/{userId}/impact")
    public List<ImpactResponse> getImpactsByUser(@PathVariable Long userId) {
        return impactService.getImpactsByUser(userId).stream().map(ImpactResponse::from).toList();

    }

    /**
     * List all impacts for itinerary.
     *
     * @param id
     * @return list of impacts belonging to itinerary
     */
    @GetMapping("/itinerary/{itineraryId}/impact")
    public List<ImpactResponse> getImpactsByItinerary(@PathVariable Long itineraryId) {
        return impactService.getImpactsByItinerary(itineraryId).stream().map(ImpactResponse::from).toList();

    }

    /**
     * List all impacts for activity.
     *
     * @param id
     * @return list of impacts belonging to activity
     */
    @GetMapping("/activity/{activityId}/impact")
    public List<ImpactResponse> getImpactsByActivity(@PathVariable Long activityId) {
        return impactService.getImpactsByActivity(activityId).stream().map(ImpactResponse::from).toList();

    }

    /**
     * Remove a impact with the DELETE request to "/impacts/{id}"
     * If there is no impact with the given "id", throw a ImpactNotFoundException
     * @param id
     */
    @DeleteMapping("/impact/{id}")
    public void deleteImpact(@PathVariable Long id){
        impactService.deleteImpact(id);
    }


    /**
     * Check every activity in an itinerary against one news article
     * Saves an impact record per activity and returns the verdicts
     *
     * @param request the itinerary and news to check
     * @return the verdict per activity, plus whether any of them is impacted
     */
    @PostMapping("/impact/evaluate")
    public EvaluateResponse evaluate(@Valid @RequestBody EvaluateRequest request) {
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
