package csd.tripsense.user;

import csd.tripsense.auth.CurrentUser;
import csd.tripsense.auth.SessionService;
import csd.tripsense.user.preference.PreferenceResponse;
import csd.tripsense.user.preference.PreferenceService;
import csd.tripsense.user.preference.UpdatePreferenceRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

/**
 * Login (POST /api/auth/login) and logout (POST /api/auth/logout) are handled by
 * Spring Security itself - see SecurityConfig - so they have no method here.
 */
@RestController
@RequestMapping("/api")
public class UserController {

    private final UserService userService;
    private final PreferenceService preferenceService;
    private final SessionService sessionService;

    public UserController(UserService userService, PreferenceService preferenceService, SessionService sessionService) {
        this.userService = userService;
        this.preferenceService = preferenceService;
        this.sessionService = sessionService;
    }

    @Operation(summary = "Register a new user account",
            description = "Creates an account with role USER. Email and username must both be unique.")
    @ApiResponse(responseCode = "201", description = "Account created")
    @ApiResponse(responseCode = "400", description = "Validation failed")
    @ApiResponse(responseCode = "409", description = "Email or username already taken")
    @PostMapping("/user/register")
    public ResponseEntity<User> register(@Valid @RequestBody User user) {
        User created = userService.register(user);
        URI location = UriComponentsBuilder.fromPath("/api/user/{username}")
                .buildAndExpand(created.getUsername()).encode().toUri();
        return ResponseEntity.created(location).body(created);
    }

    @Operation(summary = "Get a user by username",
            description = "Requires login. Allowed for the account owner or an ADMIN.")
    @ApiResponse(responseCode = "200", description = "User found")
    @ApiResponse(responseCode = "401", description = "Not logged in")
    @ApiResponse(responseCode = "404", description = "No user with that username, or not your account")
    @GetMapping("/user/{username}")
    public ResponseEntity<User> getByUsername(@PathVariable String username, Authentication authentication) {
        User user = userService.getByUsername(username);

        boolean isAdmin = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        boolean isOwner = user.getEmail().equalsIgnoreCase(authentication.getName());
        if (!isAdmin && !isOwner) {
            // Same answer as "no such user", so the response doesn't reveal which usernames exist.
            throw UserNotFoundException.forUsername(username);
        }
        return ResponseEntity.ok(user);
    }

    @Operation(summary = "Change your username",
            description = "Requires login and the X-XSRF-TOKEN header. Updates the logged-in account only; "
                    + "the email and role cannot be changed here.")
    @ApiResponse(responseCode = "200", description = "Username updated; returns the account")
    @ApiResponse(responseCode = "400", description = "Validation failed")
    @ApiResponse(responseCode = "401", description = "Not logged in")
    @ApiResponse(responseCode = "409", description = "Username already taken")
    @PatchMapping("/user/me")
    public ResponseEntity<User> updateProfile(@Valid @RequestBody UpdateProfileRequest request,
                                              @AuthenticationPrincipal CurrentUser currentUser,
                                              HttpServletRequest httpRequest,
                                              HttpServletResponse httpResponse) {
        User updated = userService.updateUsername(currentUser.getId(), request.username());
        // The session holds a copy of the account, so refresh it or /api/auth/me shows the old name.
        sessionService.refreshCurrentSession(updated, httpRequest, httpResponse);
        return ResponseEntity.ok(updated);
    }

    @Operation(summary = "Change your password",
            description = "Requires login, the X-XSRF-TOKEN header and the current password. "
                    + "On success every session of this account is ended, so log in again with the new password.")
    @ApiResponse(responseCode = "204", description = "Password changed")
    @ApiResponse(responseCode = "400", description = "Validation failed, current password wrong, or new password unchanged")
    @ApiResponse(responseCode = "401", description = "Not logged in")
    @PutMapping("/user/me/password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                               @AuthenticationPrincipal CurrentUser currentUser) {
        userService.changePassword(currentUser.getId(), request.currentPassword(), request.newPassword());
        // Runs after the password is saved: ends this and every other login so old sessions can't linger.
        sessionService.expireSessionsFor(currentUser.getEmail());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Get your preferences",
            description = "Requires login. Returns defaults for accounts that haven't set any yet.")
    @ApiResponse(responseCode = "200", description = "Preferences returned")
    @ApiResponse(responseCode = "401", description = "Not logged in")
    @GetMapping("/user/me/preferences")
    public ResponseEntity<PreferenceResponse> getPreferences(@AuthenticationPrincipal CurrentUser currentUser) {
        return ResponseEntity.ok(PreferenceResponse.from(preferenceService.getPreferences(currentUser.getId())));
    }

    @Operation(summary = "Update your preferences",
            description = "Requires login and the X-XSRF-TOKEN header. Only the fields you send are changed; ")
    @ApiResponse(responseCode = "200", description = "Preferences updated")
    @ApiResponse(responseCode = "400", description = "Validation failed")
    @ApiResponse(responseCode = "401", description = "Not logged in")
    @PatchMapping("/user/me/preferences")
    public ResponseEntity<PreferenceResponse> updatePreferences(
            @Valid @RequestBody UpdatePreferenceRequest request,
            @AuthenticationPrincipal CurrentUser currentUser) {
        return ResponseEntity.ok(PreferenceResponse.from(
                preferenceService.updatePreferences(currentUser.getId(), request)));
    }

}
