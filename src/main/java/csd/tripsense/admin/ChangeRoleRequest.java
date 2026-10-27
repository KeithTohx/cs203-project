package csd.tripsense.admin;

import csd.tripsense.user.Role;
import jakarta.validation.constraints.NotNull;

public record ChangeRoleRequest(@NotNull Role role) {}
