package csd.tripsense.alert;

import csd.tripsense.auth.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Session and CSRF protected; unavailable unless explicitly enabled for one demo fixture. */
@RestController
@RequestMapping("/api/alerts/demo")
@ConditionalOnProperty(name = "alerts.demo.enabled", havingValue = "true")
public class DemoDisruptionController {
    private final DemoDisruptionService demo;
    public DemoDisruptionController(DemoDisruptionService demo) { this.demo = demo; }

    @Operation(summary = "Clear the configured demo disruption before a rehearsal")
    @PostMapping("/reset")
    public DemoDisruptionService.Result reset(@AuthenticationPrincipal CurrentUser me) {
        return demo.reset(me == null ? null : me.getId());
    }

    @Operation(summary = "Replay the saved demo disruption without calling AI")
    @PostMapping("/trigger")
    public DemoDisruptionService.Result trigger(@AuthenticationPrincipal CurrentUser me) {
        return demo.trigger(me == null ? null : me.getId());
    }
}
