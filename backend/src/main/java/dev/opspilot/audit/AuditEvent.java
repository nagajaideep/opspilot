package dev.opspilot.audit;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "audit_event")
public class AuditEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id") private Long requestId;
    @Column(nullable = false) private String actor;
    @Column(nullable = false) private String action;
    private String details;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    protected AuditEvent() { }

    public AuditEvent(Long requestId, String actor, String action, String details, Instant createdAt) {
        this.requestId = requestId;
        this.actor = actor;
        this.action = action;
        this.details = details;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public Long getRequestId() { return requestId; }
    public String getActor() { return actor; }
    public String getAction() { return action; }
    public String getDetails() { return details; }
    public Instant getCreatedAt() { return createdAt; }
}
