package csd.tripsense.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Login (POST /api/auth/login) and logout (POST /api/auth/logout) are handled by Spring Security
 * itself - see SecurityConfig. This controller adds "who am I".
 */
@RestController
@RequestMapping("/api")
public class AuthController {

    @Operation(summary = "Get the logged-in user",
            description = "Returns the account behind the current session. Requires login.")
    @ApiResponse(responseCode = "200", description = "Current user")
    @ApiResponse(responseCode = "401", description = "Not logged in")
    @GetMapping("/auth/me")
    public ResponseEntity<CurrentUserResponse> me(@AuthenticationPrincipal CurrentUser currentUser) {
        if (currentUser == null) {
            // Normally unreachable: SecurityConfig already returns 401 before this runs.
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not logged in");
        }
        return ResponseEntity.ok(CurrentUserResponse.from(currentUser));
    }
}
