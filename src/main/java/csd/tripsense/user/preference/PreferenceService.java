package csd.tripsense.user.preference;

public interface PreferenceService {
    
    /**
     * Retrieves the preferences of the user 
     * @param userId the user to check
     * @return the user preference entity if it exists, else the default values if user have yet to set/update them
     */
    UserPreference getPreferences(Long userId);
        
    /**
     * Changes the preferences of the user
     * Partial update is possible. Omitted fields are not changed
     * Validation happens for travelGroup: travellingWithChildren and travellingWithElderly can’t be set to true when travelling SOLO
     * @param userId the user to check
     * @param request the new preferences to change
     * @return the updated user preferences
     */
    UserPreference updatePreferences(Long userId, UpdatePreferenceRequest request);


}
