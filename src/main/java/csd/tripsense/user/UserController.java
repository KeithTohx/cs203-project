package csd.tripsense.user;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;

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
        return ResponseEntity.created(URI.create("/api/users/" + created.getId())).body(created);
    }

    @Operation(summary = "Get a user by username",
            description = "Used to verify a registered account exists.")
    @ApiResponse(responseCode = "200", description = "User found")
    @ApiResponse(responseCode = "404", description = "No user with that username")
    @GetMapping("/user/{username}")
    public ResponseEntity<User> getByUsername(@PathVariable String username) {
        User user = userService.getByUsername(username);
        return ResponseEntity.ok(user);
    }
}
