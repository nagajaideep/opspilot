package dev.opspilot.auth;

import dev.opspilot.user.AppUser;
import dev.opspilot.user.AppUserRepository;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Auth")
public class AuthController {
    // Real BCrypt hash of a random string: unknown usernames take as long as wrong passwords,
    // so response time can't reveal which usernames exist (user enumeration).
    private final String dummyHash;

    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final TokenService tokens;
    private final LoginAttemptService attempts;

    public AuthController(AppUserRepository users, PasswordEncoder encoder, TokenService tokens,
                          LoginAttemptService attempts) {
        this.users = users;
        this.encoder = encoder;
        this.tokens = tokens;
        this.attempts = attempts;
        this.dummyHash = encoder.encode(java.util.UUID.randomUUID().toString());
    }

    public record LoginRequest(@NotBlank @Size(max = 50) String username, @NotBlank @Size(max = 100) String password) { }

    public record UserView(Long id, String username, String displayName, String role) {
        static UserView of(AppUser u) { return new UserView(u.getId(), u.getUsername(), u.getDisplayName(), u.getRole().name()); }
    }

    public record LoginResponse(String token, Instant expiresAt, UserView user) { }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req) {
        if (attempts.isLocked(req.username())) {
            return error(HttpStatus.TOO_MANY_REQUESTS, "Too many failed attempts. Try again in 15 minutes.");
        }
        var user = users.findByUsername(req.username());
        boolean ok = encoder.matches(req.password(), user.map(AppUser::getPasswordHash).orElse(dummyHash));
        if (user.isEmpty() || !ok) {
            attempts.recordFailure(req.username());
            // Same message for "no such user" and "wrong password".
            return error(HttpStatus.UNAUTHORIZED, "Invalid username or password.");
        }
        attempts.recordSuccess(req.username());
        var issued = tokens.issue(user.get());
        return ResponseEntity.ok(new LoginResponse(issued.token(), issued.expiresAt(), UserView.of(user.get())));
    }

    @GetMapping("/me")
    public UserView me(@AuthenticationPrincipal Jwt jwt) {
        CurrentUser cu = CurrentUser.from(jwt);
        return new UserView(cu.id(), cu.username(), jwt.getClaimAsString("name"), cu.role().name());
    }

    private static ResponseEntity<ProblemDetail> error(HttpStatus status, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(status.getReasonPhrase());
        return ResponseEntity.status(status).body(pd);
    }
}
