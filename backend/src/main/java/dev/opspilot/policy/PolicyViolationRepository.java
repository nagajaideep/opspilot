package dev.opspilot.policy;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PolicyViolationRepository extends JpaRepository<PolicyViolation, Long> {
    List<PolicyViolation> findByResolvedAtIsNullOrderByRuleAscEntityNameAsc();
    List<PolicyViolation> findTop200ByOrderByDetectedAtDesc();
    long countByResolvedAtIsNull();
}
