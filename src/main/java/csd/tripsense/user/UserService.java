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



}
