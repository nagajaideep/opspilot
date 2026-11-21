package dev.opspilot.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Inject Clock instead of calling Instant.now() directly, so tests can control time. */
@Configuration
public class TimeConfig {
    @Bean
    Clock clock() { return Clock.systemUTC(); }
}
