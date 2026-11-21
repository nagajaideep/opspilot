package dev.opspilot.audit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

@Service
public class AuditService {
    public record AuditView(Long id, Long requestId, String actor, String action, String details, java.time.Instant at) {
        static AuditView of(AuditEvent e) {
            return new AuditView(e.getId(), e.getRequestId(), e.getActor(), e.getAction(), e.getDetails(), e.getCreatedAt());
        }
    }

    private final AuditRepository repo;
    private final Clock clock;

    public AuditService(AuditRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    /**
     * MANDATORY: must be called inside an existing transaction, so the audit row commits
     * (or rolls back) together with the change it describes. No change without its audit record.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Long requestId, String actor, String action, String details) {
        repo.save(new AuditEvent(requestId, actor, action, details, clock.instant()));
    }

    @Transactional(readOnly = true)
    public List<AuditView> forRequest(Long requestId) {
        return repo.findByRequestIdOrderByCreatedAtAscIdAsc(requestId).stream().map(AuditView::of).toList();
    }

    @Transactional(readOnly = true)
    public List<AuditView> recent(int page, int size) {
        return repo.findAllByOrderByCreatedAtDescIdDesc(org.springframework.data.domain.PageRequest.of(page, size))
                .map(AuditView::of).getContent();
    }
}
