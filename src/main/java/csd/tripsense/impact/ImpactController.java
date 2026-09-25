package csd.tripsense.impact;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController 
public class ImpactController {
    private ImpactService impactService;

    public ImpactController(ImpactService impactService) {
        this.impactService = impactService;
    }

    /**
     * List all impacts in the system
     *
     * @return list of all impacts
     */
    @GetMapping("/impacts")
    public List<Impact> getImpacts() {
        return impactService.listImpacts();
    }

    /**
     * Search for impact with the given id
     * If there is no impact with the given "id", throw a ImpactNotFoundException
     * @param id
     * @return impact with the given id
     */
    @GetMapping("/impacts/{id}")
    public Impact getImpact(@PathVariable Long id){
        return impactService.getImpact(id);

    }

    /**
     * List all impacts for user.
     *
     * @param id
     * @return list of impacts belonging to user
     */
    @GetMapping("/impacts/user/{userId}")
    public List<Impact> getImpactsByUser(@PathVariable Long userId) {
        return impactService.getImpactsByUser(userId);

    }

    /**
     * List all impacts for itinerary.
     *
     * @param id
     * @return list of impacts belonging to itinerary
     */
    @GetMapping("/impacts/itinerary/{itineraryId}")
    public List<Impact> getImpactsByItinerary(@PathVariable Long itineraryId) {
        return impactService.getImpactsByItinerary(itineraryId);

    }

    /**
     * List all impacts for activity.
     *
     * @param id
     * @return list of impacts belonging to activity
     */
    @GetMapping("/impacts/activity/{activityId}")
    public List<Impact> getImpactsByActivity(@PathVariable Long activityId) {
        return impactService.getImpactsByActivity(activityId);

    }

    /**
     * Add a new impact with POST request to "/impacts"
     * Note the use of @RequestBody
     * @param impact
     * @return list of all impacts
     */
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/impacts")
    public Impact addImpact(@RequestBody Impact impact){
        return impactService.addImpact(impact);
    }

    /**
     * If there is no impact with the given "id", throw a ImpactNotFoundException
     * @param id
     * @param newImpactInfo
     * @return the updated, or newly added impact
     */
    @PutMapping("/impacts/{id}")
    public Impact updateImpact(@PathVariable Long id, @RequestBody Impact newImpactInfo){
        return impactService.updateImpact(id, newImpactInfo);
    }

    /**
     * Remove a impact with the DELETE request to "/impacts/{id}"
     * If there is no impact with the given "id", throw a ImpactNotFoundException
     * @param id
     */
    @DeleteMapping("/impacts/{id}")
    public void deleteImpact(@PathVariable Long id){
        impactService.deleteImpact(id);
    }

}