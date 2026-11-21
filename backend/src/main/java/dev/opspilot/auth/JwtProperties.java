package dev.opspilot.auth;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Bound from opspilot.jwt.* in application.yml. @Validated makes startup FAIL if the secret is too short. */
@Validated
@ConfigurationProperties(prefix = "opspilot.jwt")
public record JwtProperties(
        @Size(min = 32, message = "opspilot.jwt.secret must be at least 32 characters (256 bits)") String secret,
        @Min(5) long ttlMinutes,
        String issuer) { }
