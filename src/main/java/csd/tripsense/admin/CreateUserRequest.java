package csd.tripsense.admin;

import csd.tripsense.user.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// what an admin sends to make an account by hand, eg a throwaway user for testing.
// same rules as registering, except the admin also picks the role
public record CreateUserRequest(
        @NotBlank(message = "username must not be blank")
        @Pattern(regexp = "^[A-Za-z0-9_.-]+$",
                message = "username may only contain letters, digits, '.', '_' and '-'")
        @Size(min = 3, max = 30, message = "username must be between 3 and 30 characters")
        String username,

        @NotBlank(message = "email must not be blank")
        @Email(message = "email must be a valid email address")
        String email,

        @NotBlank(message = "password must not be blank")
        @Size(min = 8, max = 72, message = "password must be between 8 and 72 characters")
        String password,

        @NotNull(message = "role is required")
        Role role) {
}
