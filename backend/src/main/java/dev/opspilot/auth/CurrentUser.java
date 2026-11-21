package dev.opspilot.auth;

import dev.opspilot.user.Role;
import org.springframework.security.oauth2.jwt.Jwt;

/** The authenticated caller, extracted from the verified JWT. No DB lookup needed. */
public record CurrentUser(Long id, String username, Role role) {

    public static CurrentUser from(Jwt jwt) {
        Number uid = jwt.getClaim("uid");
        String role = jwt.getClaimAsStringList("roles").get(0);
        return new CurrentUser(uid.longValue(), jwt.getSubject(), Role.valueOf(role));
    }

    public boolean isApprover() { return role == Role.APPROVER || role == Role.ADMIN; }
}
