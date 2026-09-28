package csd.tripsense.user;

public interface UserService {

    /**
     * Registers a new account with role USER.
     * Throws ResponseStatusException (409) if the email or username is already taken.
     */
    User register(User user);
}
