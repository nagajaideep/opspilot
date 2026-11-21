package dev.opspilot.user;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

// Spring Data generates the implementation; the query comes from the method name.
public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByUsername(String username);
}
