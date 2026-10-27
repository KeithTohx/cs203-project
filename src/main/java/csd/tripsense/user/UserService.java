package csd.tripsense.user;

public interface UserService {

    /**
     * Registers a new account with role USER; the password is stored as a BCrypt hash.
     * Throws AccountRuleException (CONFLICT) if the email or username is already taken.
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
     * @throws AccountRuleException (CONFLICT) if another account has that username
     */
    User updateUsername(Long userId, String newUsername);

    /**
     * Changes the password of the given account after checking the current one.
     * The new password is stored as a BCrypt hash.
     * @throws UserNotFoundException if the account no longer exists
     * @throws AccountRuleException (INVALID_INPUT) if the current password is wrong,
     *         the new password is longer than 72 bytes, or it is the same as the current one
     */
    void changePassword(Long userId, String currentPassword, String newPassword);



    /**
     * Permanently deletes the account and everything it owns (itineraries, activities, impacts).
     * @throws UserNotFoundException if the account no longer exists
     * @throws AccountRuleException (INVALID_INPUT) if the password is wrong,
     *         (CONFLICT) if this is the last enabled admin
     */
    void deleteAccount(Long userId, String currentPassword);

        /**
     * The ONE place that removes an account and everything it owns. Both ways of deleting an account call
     * it (the user deleting themselves, and an admin deleting someone), so neither can forget a step.
     * It does NO password check and NO "who is asking" check: the caller must already have authorised it.
     * @throws UserNotFoundException if the account no longer exists
     * @throws AccountRuleException (CONFLICT) if this is the last remaining admin
     */
    void deleteUserAndData(Long userId);
}
