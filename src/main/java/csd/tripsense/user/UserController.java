package csd.tripsense.user;

import csd.tripsense.audit.AuditService;
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
import org.springframework.http.HttpStatus;
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
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.bind.annotation.DeleteMapping;

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
        private final AuditService auditService;

        public UserController(UserService userService, PreferenceService preferenceService,
                                SessionService sessionService, AuditService auditService) {
                this.userService = userService;
                this.preferenceService = preferenceService;
                this.sessionService = sessionService;
                this.auditService = auditService;
        }

        @Operation(summary = "Register a new user account",
                description = "Creates an account with role USER. Email and username must both be unique.")
        @ApiResponse(responseCode = "201", description = "Account created")
        @ApiResponse(responseCode = "400", description = "Validation failed")
        @ApiResponse(responseCode = "409", description = "Email or username already taken")
        @PostMapping("/user/register")
        public ResponseEntity<User> register(@Valid @RequestBody User user) {
                User created = userService.register(user);
                auditService.record(AuditService.REGISTER, created.getId(), created.getEmail(), created.getId(), null);
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

                // an admin reading someone else's account is going past the owner-only rule, so it is traced
                boolean adminOverride = isAdmin && !isOwner;
                if (adminOverride) {
                        Long adminId = authentication.getPrincipal() instanceof CurrentUser admin ? admin.getId() : null;
                        auditService.record(AuditService.ADMIN_ACCOUNT_VIEWED, adminId, authentication.getName(),
                                user.getId(), "viewed account '" + user.getUsername() + "'");
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
                requireLoggedIn(currentUser);
                User updated = userService.updateUsername(currentUser.getId(), request.username());
                // The session holds a copy of the account, so refresh it or /api/auth/me shows the old name.
                sessionService.refreshCurrentSession(updated, httpRequest, httpResponse);
                auditService.record(AuditService.PROFILE_UPDATED, currentUser.getId(), currentUser.getEmail(),
                        currentUser.getId(), "changed username");
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
                requireLoggedIn(currentUser);
                userService.changePassword(currentUser.getId(), request.currentPassword(), request.newPassword());
                // Runs after the password is saved: ends this and every other login so old sessions can't linger.
                sessionService.expireSessionsFor(currentUser.getEmail());
                // Only the fact that it happened is recorded, never either password.
                auditService.record(AuditService.PASSWORD_CHANGED, currentUser.getId(), currentUser.getEmail(),
                        currentUser.getId(), null);
                return ResponseEntity.noContent().build();
        }

        // Normally unreachable: SecurityConfig already returns 401 before a handler runs.
        private static void requireLoggedIn(CurrentUser currentUser) {
                if (currentUser == null) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not logged in");
                }
        }
        
        @Operation(summary = "Get your preferences",
        description = "Requires login. Returns your saved preferences, or creates and returns the defaults "
                + "(MEDIUM risk, SOMEWHAT_FLEXIBLE, BRIEF, SOLO, no preferred categories) the first time. "
                + "Admin accounts cannot use preferences and get 403.")
        @ApiResponse(responseCode = "200", description = "Preferences returned")
        @ApiResponse(responseCode = "401", description = "Not logged in")
        @ApiResponse(responseCode = "403", description = "Admin account: preferences are for USER accounts only")
        @ApiResponse(responseCode = "409", description = "Another request is creating your preferences, retry")
        @GetMapping("/user/me/preferences")
        public ResponseEntity<PreferenceResponse> getPreferences(@AuthenticationPrincipal CurrentUser currentUser) {
                requireLoggedIn(currentUser);
                return ResponseEntity.ok(PreferenceResponse.from(preferenceService.getPreferences(currentUser.getId())));
        }

        @Operation(summary = "Update your preferences",
        description = "Requires login and the X-XSRF-TOKEN header. Only the fields you send are changed; "
                + "a field left out or null keeps its saved value. "
                + "preferredCategories replaces the whole list, so send [] to clear it. It only accepts the "
                + "indoor categories MUSEUM_GALLERY, FOOD_DINING, SHOPPING and WELLNESS_SPA, because "
                + "recommendations only suggest indoor alternatives. "
                + "travellingWithChildren and travellingWithElderly cannot be true while travelGroup is SOLO. "
                + "After a change, call POST /api/recommendation/generate to rebuild your recommendations. "
                + "Admin accounts get 403.")
        @ApiResponse(responseCode = "200", description = "Preferences updated")
        @ApiResponse(responseCode = "400", description = "Validation failed: an outdoor or null preferred category, "
                + "or children/elderly with a SOLO travel group")
        @ApiResponse(responseCode = "401", description = "Not logged in")
        @ApiResponse(responseCode = "403", description = "Admin account, or the X-XSRF-TOKEN header is missing")
        @ApiResponse(responseCode = "409", description = "Another request is creating your preferences, retry")
        @PatchMapping("/user/me/preferences")
        public ResponseEntity<PreferenceResponse> updatePreferences(
                @Valid @RequestBody UpdatePreferenceRequest request,
                @AuthenticationPrincipal CurrentUser currentUser) {
                requireLoggedIn(currentUser);
                return ResponseEntity.ok(PreferenceResponse.from(
                        preferenceService.updatePreferences(currentUser.getId(), request)));
        }

        @Operation(summary = "Delete your account",
                description = "Requires login, the X-XSRF-TOKEN header and the current password. Permanently deletes the "
                        + "account and all its itineraries, activities and impact results, then logs you out everywhere.")
        @ApiResponse(responseCode = "204", description = "Account deleted")
        @ApiResponse(responseCode = "400", description = "Validation failed or current password wrong")
        @ApiResponse(responseCode = "401", description = "Not logged in")
        @ApiResponse(responseCode = "409", description = "Last remaining admin cannot be deleted")
        @DeleteMapping("/user/me")
        public ResponseEntity<Void> deleteAccount(@Valid @RequestBody DeleteAccountRequest request,
                                                @AuthenticationPrincipal CurrentUser currentUser,
                                                HttpServletRequest httpRequest,
                                                HttpServletResponse httpResponse) {
                requireLoggedIn(currentUser);
                userService.deleteAccount(currentUser.getId(), request.currentPassword());
                auditService.record(AuditService.ACCOUNT_DELETED, currentUser.getId(), currentUser.getEmail(),
                currentUser.getId(), "deleted own account");
                // Only after the delete committed, so a failed delete doesn't log the user out.
                sessionService.endAllSessions(currentUser.getEmail(), httpRequest, httpResponse);
                return ResponseEntity.noContent().build();
        }
}
