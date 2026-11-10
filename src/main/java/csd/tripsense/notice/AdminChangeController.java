package csd.tripsense.notice;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import csd.tripsense.auth.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

// the logged in user's own notices, never anyone else's
@RestController
@RequestMapping("/api/user/me/admin-changes")
@Tag(name = "Admin changes", description = "What an admin changed in your trips")
public class AdminChangeController {

    private final AdminChangeService adminChanges;

    public AdminChangeController(AdminChangeService adminChanges) {
        this.adminChanges = adminChanges;
    }

    @Operation(summary = "List what an admin changed in your trips",
            description = "The newest 20, newest first, with unreadCount across all of them.")
    @GetMapping
    public AdminChangeService.AdminChangePage list(@AuthenticationPrincipal CurrentUser me) {
        return adminChanges.list(userId(me));
    }

    @Operation(summary = "Mark them as read",
            description = "Pass upToId (the newest one you have seen) so one arriving meanwhile stays unread. "
                    + "Returns the unreadCount left. Requires CSRF token.")
    @PatchMapping
    public AdminChangeService.UnreadCount markAllRead(@RequestParam(required = false) Long upToId,
                                                      @AuthenticationPrincipal CurrentUser me) {
        return adminChanges.markAllRead(userId(me), upToId);
    }

    private static Long userId(CurrentUser me) {
        if (me == null || me.getId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not logged in");
        }
        return me.getId();
    }
}
