package csd.tripsense.alert;

import csd.tripsense.auth.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** CS203-5 backend API. The authenticated session determines the recipient, never a request user id. */
@RestController
@RequestMapping("/api/alerts")
public class AlertController {
    private final AlertService alerts;
    private final AlertStreamService streams;

    public AlertController(AlertService alerts, AlertStreamService streams) {
        this.alerts = alerts;
        this.streams = streams;
    }

    @Operation(summary = "List your saved disruption alerts",
            description = "Newest first, optionally unread only. Returns unreadCount across all alerts. "
                    + "To load older alerts, send the response's nextBeforeId back as beforeId. "
                    + "hasMore is false and nextBeforeId is null once there are no older alerts.")
    @GetMapping
    public AlertService.AlertPage list(@AuthenticationPrincipal CurrentUser me,
                                       @RequestParam(defaultValue = "false") boolean unreadOnly,
                                       @RequestParam(required = false) Long beforeId,
                                       @RequestParam(defaultValue = "20") int size) {
        return alerts.list(userId(me), unreadOnly, beforeId, size);
    }

    @Operation(summary = "Receive live disruption alerts",
            description = "Session-authenticated SSE. Events: ready and alert. On each connection or reconnection, "
                    + "fetch GET /api/alerts to recover any alerts missed while disconnected. Heartbeat every 15 seconds.")
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@AuthenticationPrincipal CurrentUser me, HttpServletRequest request,
                             HttpServletResponse response) {
        Long userId = userId(me);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Accel-Buffering", "no");
        return streams.subscribe(userId, request.getSession(false));
    }

    @Operation(summary = "Update one of your alerts",
            description = "Send {\"read\": true} to mark it as read. Idempotent. Requires CSRF token.")
    @PatchMapping("/{id}")
    public AlertResponse update(@PathVariable Long id, @Valid @RequestBody AlertUpdateRequest request,
                                @AuthenticationPrincipal CurrentUser me) {
        // validation only lets read: true through, so there is nothing else to apply
        // if AlertUpdateRequest gains more fields, apply them here
        return alerts.markRead(id, userId(me));
    }

    @PatchMapping("/{id}/group")
    public AlertService.UnreadCount updateGroup(@PathVariable Long id,
            @Valid @RequestBody AlertUpdateRequest request, @AuthenticationPrincipal CurrentUser me) {
        return alerts.markGroupRead(id, userId(me));
    }

    @Operation(summary = "Update all of your alerts",
            description = "Send {\"read\": true} to mark every alert as read, not just the loaded ones. "
                    + "Pass upToId (the newest alert id the user has seen) so alerts arriving meanwhile stay unread. "
                    + "Returns the unreadCount left afterwards. Idempotent. Requires CSRF token.")
    @PatchMapping
    public AlertService.UnreadCount updateAll(@Valid @RequestBody AlertUpdateRequest request,
                                              @RequestParam(required = false) Long upToId,
                                              @AuthenticationPrincipal CurrentUser me) {
        // same as update: validation already guarantees read is true
        return alerts.markAllRead(userId(me), upToId);
    }

    private Long userId(CurrentUser me) {
        if (me == null || me.getId() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not logged in");
        return me.getId();
    }
}
