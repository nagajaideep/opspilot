package dev.opspilot.request;

import dev.opspilot.auth.CurrentUser;
import dev.opspilot.request.RequestDtos.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/requests")
@Validated
@Tag(name = "Change requests", description = "Create, review, approve and execute infrastructure changes")
public class ChangeRequestController {
    private final ChangeRequestService service;

    public ChangeRequestController(ChangeRequestService service) { this.service = service; }

    @GetMapping
    @Operation(summary = "List change requests (newest first)")
    public PageView<RequestView> list(@AuthenticationPrincipal Jwt jwt,
                                      @RequestParam(required = false) RequestStatus status,
                                      @RequestParam(defaultValue = "false") boolean mine,
                                      @RequestParam(defaultValue = "0") @Min(0) int page,
                                      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(status, mine, CurrentUser.from(jwt), page, size);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Request detail with audit history and the actions available to the caller")
    public RequestDetail get(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return service.detail(CurrentUser.from(jwt), id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a DRAFT change request")
    public RequestView create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateRequest body) {
        return service.create(CurrentUser.from(jwt), body);
    }

    @PostMapping("/{id}/submit")
    @Operation(summary = "Submit a draft: the AI agent reviews it, then it waits for human approval")
    public RequestView submit(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return service.submit(CurrentUser.from(jwt), id);
    }

    @PostMapping("/{id}/approve")
    @Operation(summary = "Approve (APPROVER/ADMIN, not your own request)")
    public RequestView approve(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                               @Valid @RequestBody(required = false) DecisionRequest body) {
        return service.approve(CurrentUser.from(jwt), id, body == null ? null : body.comment());
    }

    @PostMapping("/{id}/reject")
    @Operation(summary = "Reject with a reason (APPROVER/ADMIN, not your own request)")
    public RequestView reject(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                              @Valid @RequestBody RejectRequest body) {
        return service.reject(CurrentUser.from(jwt), id, body.comment());
    }

    @PostMapping("/{id}/execute")
    @Operation(summary = "Execute an approved change against inventory (APPROVER/ADMIN)")
    public RequestView execute(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return service.execute(CurrentUser.from(jwt), id);
    }
}
