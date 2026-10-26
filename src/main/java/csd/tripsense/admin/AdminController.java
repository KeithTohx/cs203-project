package csd.tripsense.admin;

import csd.tripsense.audit.AuditEntryResponse;
import csd.tripsense.audit.AuditService;
import csd.tripsense.auth.CurrentUser;
import csd.tripsense.auth.SessionService;
import csd.tripsense.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Everything under /api/admin is for ADMIN accounts only (USER gets 403, not logged in gets 401). */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService adminService;
    private final AuditService auditService;
    private final SessionService sessionService;

    public AdminController(AdminService adminService, AuditService auditService, SessionService sessionService) {
        this.adminService = adminService;
        this.auditService = auditService;
        this.sessionService = sessionService;
    }

    @Operation(summary = "List and search user accounts",
            description = "Paged. 'search' matches part of the email or username. Never returns passwords.")
    @ApiResponse(responseCode = "200", description = "One page of accounts")
    @ApiResponse(responseCode = "403", description = "Not an admin")
    @GetMapping("/users")
    public PageResponse<AdminUserResponse> listUsers(
            @RequestParam(name = "search", required = false) String search,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return PageResponse.from(adminService.listUsers(search, page, size).map(AdminUserResponse::from));
    }

    @Operation(summary = "View one user account")
    @ApiResponse(responseCode = "200", description = "Account")
    @ApiResponse(responseCode = "403", description = "Not an admin")
    @ApiResponse(responseCode = "404", description = "No account with that id")
    @GetMapping("/users/{id}")
    public AdminUserResponse getUser(@PathVariable("id") Long id) {
        return AdminUserResponse.from(adminService.getUser(id));
    }

    @Operation(summary = "Disable an account",
            description = "The user can no longer log in and their sessions end, but all their data stays. "
                    + "An admin cannot disable their own account.")
    @ApiResponse(responseCode = "200", description = "Account is now disabled")
    @ApiResponse(responseCode = "400", description = "Admin tried to disable themselves")
    @ApiResponse(responseCode = "403", description = "Not an admin")
    @ApiResponse(responseCode = "404", description = "No account with that id")
    @PostMapping("/users/{id}/disable")
    public AdminUserResponse disableUser(@PathVariable("id") Long id, @AuthenticationPrincipal CurrentUser admin) {
        if (admin == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not logged in");
        }
        User user = adminService.disableUser(id, admin.getId());

        sessionService.expireSessionsFor(user.getId());   // kick them out now, not when the session times out
        auditService.record(AuditService.ACCOUNT_DISABLED, admin.getId(), admin.getEmail(), user.getId(),
                "disabled user '" + user.getUsername() + "'");
        return AdminUserResponse.from(user);
    }

    @Operation(summary = "Re-enable an account", description = "The user can log in again.")
    @ApiResponse(responseCode = "200", description = "Account is now enabled")
    @ApiResponse(responseCode = "403", description = "Not an admin")
    @ApiResponse(responseCode = "404", description = "No account with that id")
    @PostMapping("/users/{id}/enable")
    public AdminUserResponse enableUser(@PathVariable("id") Long id, @AuthenticationPrincipal CurrentUser admin) {
        if (admin == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not logged in");
        }
        User user = adminService.enableUser(id);

        auditService.record(AuditService.ACCOUNT_ENABLED, admin.getId(), admin.getEmail(), user.getId(),
                "enabled user '" + user.getUsername() + "'");
        return AdminUserResponse.from(user);
    }

    @Operation(summary = "Review what a user did",
            description = "That account's activity, newest first: what it did (logins, profile changes, itinerary "
                    + "changes) and what was done to it (disabled, enabled, failed login attempts against it). "
                    + "Optional 'action' filter. Works for deleted accounts too.")
    @ApiResponse(responseCode = "200", description = "One page of audit entries")
    @ApiResponse(responseCode = "403", description = "Not an admin")
    @GetMapping("/users/{id}/actions")
    public PageResponse<AuditEntryResponse> reviewUserActions(
            @PathVariable("id") Long id,
            @RequestParam(name = "action", required = false) String action,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return PageResponse.from(adminService.reviewUserActions(id, action, page, size));
    }

    @Operation(summary = "Delete a user account and its data",
            description = "Permanent. Removes the account's itineraries, activities and impacts, and ends its sessions. "
                    + "An admin cannot delete their own account.")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "400", description = "Admin tried to delete themselves")
    @ApiResponse(responseCode = "403", description = "Not an admin")
    @ApiResponse(responseCode = "404", description = "No account with that id")
    @DeleteMapping("/users/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable("id") Long id, @AuthenticationPrincipal CurrentUser admin) {
        if (admin == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not logged in");
        }
        User deleted = adminService.deleteUser(id, admin.getId());

        sessionService.expireSessionsFor(deleted.getId());   // kick the deleted user out immediately
        auditService.record(AuditService.ACCOUNT_DELETED, admin.getId(), admin.getEmail(), deleted.getId(),
                "deleted user '" + deleted.getUsername() + "'");
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Review user actions",
            description = "Audit trail, newest first. Filter with 'email' (part of the actor's email) and/or 'action' "
                    + "(LOGIN, LOGIN_FAILED, LOGOUT, REGISTER, PROFILE_UPDATED, PASSWORD_CHANGED, ACCOUNT_DISABLED, ACCOUNT_ENABLED, ACCOUNT_DELETED, ITINERARY_CREATED, ITINERARY_UPDATED, ITINERARY_DELETED).")
    @ApiResponse(responseCode = "200", description = "One page of audit entries")
    @ApiResponse(responseCode = "403", description = "Not an admin")
    @GetMapping("/audit")
    public PageResponse<AuditEntryResponse> reviewActions(
            @RequestParam(name = "email", required = false) String email,
            @RequestParam(name = "action", required = false) String action,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return PageResponse.from(adminService.reviewActions(email, action, page, size));
    }
}
