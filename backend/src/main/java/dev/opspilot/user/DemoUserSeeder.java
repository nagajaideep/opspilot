package dev.opspilot.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates one demo user per role on first start. Disabled unless opspilot.demo-users.enabled=true,
 * so a real deployment never ships with known passwords.
 */
@Component
@ConditionalOnProperty(name = "opspilot.demo-users.enabled", havingValue = "true")
public class DemoUserSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoUserSeeder.class);

    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final String password;

    public DemoUserSeeder(AppUserRepository users, PasswordEncoder encoder,
                          @Value("${opspilot.demo-users.password}") String password) {
        this.users = users;
        this.encoder = encoder;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.count() > 0) return;
        String hash = encoder.encode(password);
        users.save(new AppUser("riya", "Riya Requester", hash, Role.REQUESTER));
        users.save(new AppUser("sam", "Sam Requester", hash, Role.REQUESTER));
        users.save(new AppUser("alex", "Alex Approver", hash, Role.APPROVER));
        users.save(new AppUser("ada", "Ada Admin", hash, Role.ADMIN));
        log.info("Seeded demo users: riya, sam (REQUESTER), alex (APPROVER), ada (ADMIN)");
    }
}
