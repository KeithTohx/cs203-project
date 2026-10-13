package csd.tripsense.user;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;

/**
 * Login (POST /api/users/login) and logout (POST /api/users/logout) are handled by
 * Spring Security itself - see SecurityConfig - so they have no method here.
 */
@RestController
@RequestMapping("/api")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @Operation(summary = "Register a new user account",
            description = "Creates an account with role USER. Email and username must both be unique.")
    @ApiResponse(responseCode = "201", description = "Account created")
    @ApiResponse(responseCode = "400", description = "Validation failed")
    @ApiResponse(responseCode = "409", description = "Email or username already taken")
    @PostMapping("/user/register")
    public ResponseEntity<User> register(@Valid @RequestBody User user) {
        User created = userService.register(user);
        return ResponseEntity.created(URI.create("/api/user/" + created.getId())).body(created);
    }

    @Operation(summary = "Get a user by username",
            description = "Requires login. Allowed for the account owner or an ADMIN.")
    @ApiResponse(responseCode = "200", description = "User found")
    @ApiResponse(responseCode = "401", description = "Not logged in")
    @ApiResponse(responseCode = "403", description = "Not your account")
    @ApiResponse(responseCode = "404", description = "No user with that username")
    @GetMapping("/user/{username}")
    public ResponseEntity<User> getByUsername(@PathVariable String username, Authentication authentication) {
        User user = userService.getByUsername(username);

        boolean isAdmin = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        boolean isOwner = user.getEmail().equalsIgnoreCase(authentication.getName());
        if (!isAdmin && !isOwner) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only view your own account");
        }
        return ResponseEntity.ok(user);
    }
}
