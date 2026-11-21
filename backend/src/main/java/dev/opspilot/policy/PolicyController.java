package dev.opspilot.policy;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/policy")
@Tag(name = "Policy")
public class PolicyController {
    public record ViolationView(Long id, String entityType, Long entityId, String entityName, String rule,
                                String message, Instant detectedAt, Instant resolvedAt) {
        static ViolationView of(PolicyViolation v) {
            return new ViolationView(v.getId(), v.getEntityType().name(), v.getEntityId(), v.getEntityName(),
                    v.getRule(), v.getMessage(), v.getDetectedAt(), v.getResolvedAt());
        }
    }

    private final PolicyScanService scanner;
    private final PolicyViolationRepository violations;

    public PolicyController(PolicyScanService scanner, PolicyViolationRepository violations) {
        this.scanner = scanner;
        this.violations = violations;
    }

    @GetMapping("/violations")
    @Transactional(readOnly = true)
    @Operation(summary = "Open violations (default) or recent history")
    public List<ViolationView> list(@RequestParam(defaultValue = "true") boolean open) {
        var list = open ? violations.findByResolvedAtIsNullOrderByRuleAscEntityNameAsc()
                        : violations.findTop200ByOrderByDetectedAtDesc();
        return list.stream().map(ViolationView::of).toList();
    }

    /** ADMIN only (enforced in SecurityConfig). */
    @PostMapping("/scan")
    @Operation(summary = "Run the policy scan now (ADMIN)")
    public PolicyScanService.ScanResult scanNow() {
        return scanner.scan();
    }
}
