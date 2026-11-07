package csd.tripsense.activity;
 
/** Published after a new activity has been saved. Carries only the id so listeners reload fresh state. */
public record ActivityCreatedEvent(Long activityId) {

}
