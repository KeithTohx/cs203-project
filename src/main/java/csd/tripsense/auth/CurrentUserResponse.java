package csd.tripsense.auth;

/** What GET /api/auth/me returns. Never includes the password or hash. */
public record CurrentUserResponse(Long id, String email, String username, String role) {

    public static CurrentUserResponse from(CurrentUser currentUser) {
        return new CurrentUserResponse(currentUser.getId(), currentUser.getEmail(),
                currentUser.getAccountUsername(), currentUser.getRole());
    }
}
