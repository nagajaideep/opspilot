package dev.opspilot.policy;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "policy_violation")
public class PolicyViolation {
    public enum EntityType { CLUSTER, SERVER }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING) @Column(name = "entity_type", nullable = false) private EntityType entityType;
    @Column(name = "entity_id", nullable = false) private Long entityId;
    @Column(name = "entity_name", nullable = false) private String entityName;
    @Column(nullable = false) private String rule;
    @Column(nullable = false) private String message;
    @Column(name = "detected_at", nullable = false) private Instant detectedAt;
    @Column(name = "resolved_at") private Instant resolvedAt;

    protected PolicyViolation() { }

    public PolicyViolation(EntityType entityType, Long entityId, String entityName, String rule, String message, Instant detectedAt) {
        this.entityType = entityType;
        this.entityId = entityId;
        this.entityName = entityName;
        this.rule = rule;
        this.message = message;
        this.detectedAt = detectedAt;
    }

    public void resolve(Instant at) { this.resolvedAt = at; }

    /** Identity of a finding across scans. */
    public String key() { return entityType + ":" + entityId + ":" + rule; }

    public Long getId() { return id; }
    public EntityType getEntityType() { return entityType; }
    public Long getEntityId() { return entityId; }
    public String getEntityName() { return entityName; }
    public String getRule() { return rule; }
    public String getMessage() { return message; }
    public Instant getDetectedAt() { return detectedAt; }
    public Instant getResolvedAt() { return resolvedAt; }
}
