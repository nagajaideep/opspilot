package dev.opspilot.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AuditRepository extends JpaRepository<AuditEvent, Long> {
    List<AuditEvent> findByRequestIdOrderByCreatedAtAscIdAsc(Long requestId);
    Page<AuditEvent> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);
}
