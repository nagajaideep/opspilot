package dev.opspilot.auth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class LoginAttemptServiceTest {

    /** A clock we can move forward: this is why the app injects Clock instead of calling Instant.now(). */
    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void locksAfterFiveFailuresAndUnlocksAfterFifteenMinutes() {
        var clock = new MutableClock();
        var svc = new LoginAttemptService(clock);
        for (int i = 0; i < 4; i++) svc.recordFailure("Riya");
        assertThat(svc.isLocked("riya")).isFalse();
        svc.recordFailure("riya");
        assertThat(svc.isLocked("RIYA")).isTrue();   // case-insensitive
        clock.now = clock.now.plus(Duration.ofMinutes(16));
        assertThat(svc.isLocked("riya")).isFalse();
    }

    @Test
    void successResetsTheCounter() {
        var svc = new LoginAttemptService(new MutableClock());
        for (int i = 0; i < 4; i++) svc.recordFailure("sam");
        svc.recordSuccess("sam");
        svc.recordFailure("sam");
        assertThat(svc.isLocked("sam")).isFalse();
    }
}
