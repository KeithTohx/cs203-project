package csd.tripsense.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of PATCH /api/user/me. Only the username can be changed: the email is the login identity,
 * and the role is never client-controlled. The rules match the ones on User.username.
 */
public record UpdateProfileRequest(
        @NotBlank(message = "username must not be blank")
        @Pattern(regexp = "^[A-Za-z0-9_.-]+$",
                message = "username may only contain letters, digits, '.', '_' and '-'")
        @Size(min = 3, max = 30, message = "username must be between 3 and 30 characters")
        String username) {
}
