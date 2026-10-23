package csd.tripsense.user;

import jakarta.validation.constraints.NotBlank;

public record DeleteAccountRequest(@NotBlank(message = "current password must not be blank") String currentPassword) {
}