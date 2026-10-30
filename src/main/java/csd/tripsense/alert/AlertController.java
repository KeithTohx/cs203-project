package csd.tripsense.alert;

import csd.tripsense.auth.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
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
            description = "Newest first, optionally unread only. Returns unreadCount across all pages.")
    @GetMapping
    public AlertService.AlertPage list(@AuthenticationPrincipal CurrentUser me,
                                       @RequestParam(defaultValue = "false") boolean unreadOnly,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        return alerts.list(userId(me), unreadOnly, page, size);
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

    @Operation(summary = "Mark one of your alerts as read", description = "Idempotent. Requires CSRF token.")
    @PatchMapping("/{id}/read")
    public AlertResponse markRead(@PathVariable Long id, @AuthenticationPrincipal CurrentUser me) {
        return alerts.markRead(id, userId(me));
    }

    private Long userId(CurrentUser me) {
        if (me == null || me.getId() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not logged in");
        return me.getId();
    }
}
