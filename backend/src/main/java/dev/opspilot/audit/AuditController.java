package dev.opspilot.audit;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Global audit log. URL rule in SecurityConfig restricts this to APPROVER and ADMIN. */
@RestController
@RequestMapping("/api/audit")
@Validated
@Tag(name = "Audit")
public class AuditController {
    private final AuditService audit;

    public AuditController(AuditService audit) { this.audit = audit; }

    @GetMapping
    public List<AuditService.AuditView> recent(@RequestParam(defaultValue = "0") @Min(0) int page,
                                               @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return audit.recent(page, size);
    }
}
