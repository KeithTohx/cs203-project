package csd.tripsense.user;

public interface UserService {

    /**
     * Registers a new account with role USER; the password is stored as a BCrypt hash.
     * Throws ResponseStatusException (409) if the email or username is already taken.
     */
    User register(User user);

    /**
     * Looks up a user by username.
     * @throws UserNotFoundException if no user has that username
     */
    User getByUsername(String username);

    /**
     * Changes the username of the given account. Renaming to a different casing of your own
     * username is allowed.
     * @throws UserNotFoundException if the account no longer exists
     * @throws org.springframework.web.server.ResponseStatusException (409) if another account has that username
     */
    User updateUsername(Long userId, String newUsername);

    /**
     * Changes the password of the given account after checking the current one.
     * The new password is stored as a BCrypt hash.
     * @throws UserNotFoundException if the account no longer exists
     * @throws org.springframework.web.server.ResponseStatusException (400) if the current password is wrong,
     *         the new password is longer than 72 bytes, or it is the same as the current one
     */
    void changePassword(Long userId, String currentPassword, String newPassword);

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
     * @param userId
     * @param request
     * @return
     */
    UserPreference updatePreferences(Long userId, UpdatePreferenceRequest request);

}
