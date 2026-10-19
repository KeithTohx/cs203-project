package csd.tripsense.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of PUT /api/user/me/password. */
public record ChangePasswordRequest(
        @NotBlank(message = "currentPassword must not be blank")
        String currentPassword,

        @NotBlank(message = "newPassword must not be blank")
        @Size(min = 8, max = 72, message = "newPassword must be between 8 and 72 characters")
        String newPassword) {

    /** Keeps both passwords out of logs if a request object is ever printed. */
    @Override
    public String toString() {
        return "ChangePasswordRequest[redacted]";
    }
}
