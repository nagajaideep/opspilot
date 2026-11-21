package dev.opspilot.auth;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Brute-force protection: after 5 failed logins a username is locked for 15 minutes.
 * In-memory is fine for one instance; with several instances you'd move this to Redis.
 */
@Service
public class LoginAttemptService {
    static final int MAX_FAILURES = 5;
    static final Duration LOCK = Duration.ofMinutes(15);

    private record Attempts(int failures, Instant lockedUntil) { }

    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();
    private final Clock clock;

    public LoginAttemptService(Clock clock) { this.clock = clock; }

    public boolean isLocked(String username) {
        Attempts a = attempts.get(key(username));
        return a != null && a.lockedUntil() != null && clock.instant().isBefore(a.lockedUntil());
    }

    public void recordFailure(String username) {
        attempts.compute(key(username), (k, a) -> {
            int failures = (a == null ? 0 : a.failures()) + 1;
            Instant lock = failures >= MAX_FAILURES ? clock.instant().plus(LOCK) : null;
            return new Attempts(lock != null ? 0 : failures, lock);
        });
    }

    public void recordSuccess(String username) { attempts.remove(key(username)); }

    private static String key(String username) { return username.toLowerCase(); }
}
