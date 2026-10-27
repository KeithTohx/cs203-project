package csd.tripsense.admin;

import csd.tripsense.user.User;

/** An account as an admin sees it. Never contains the password or its hash. */
public record AdminUserResponse(Long id, String username, String email, String role, boolean enabled) {

    public static AdminUserResponse from(User user) {
        return new AdminUserResponse(user.getId(), user.getUsername(), user.getEmail(),
                user.getRole(), user.isEnabled());
    }
}
