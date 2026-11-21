-- ============================================================
-- OpsPilot schema v1
-- ============================================================

-- Users and roles. One role per user keeps least-privilege easy to reason about.
CREATE TABLE app_user (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(50)  NOT NULL UNIQUE,
    display_name  VARCHAR(100) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,            -- BCrypt hash, never the password
    role          VARCHAR(20)  NOT NULL CHECK (role IN ('REQUESTER', 'APPROVER', 'ADMIN')),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Inventory: a cluster groups servers and has a hard capacity limit.
CREATE TABLE cluster (
    id           BIGSERIAL PRIMARY KEY,
    name         VARCHAR(80) NOT NULL UNIQUE,
    environment  VARCHAR(20) NOT NULL CHECK (environment IN ('DEV', 'STAGING', 'PROD')),
    region       VARCHAR(40) NOT NULL,
    owner_team   VARCHAR(80),                       -- nullable on purpose: policy job flags missing owners
    max_servers  INT         NOT NULL CHECK (max_servers > 0),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE server (
    id          BIGSERIAL PRIMARY KEY,
    hostname    VARCHAR(120) NOT NULL UNIQUE,
    cluster_id  BIGINT       NOT NULL REFERENCES cluster (id),
    cpu_cores   INT          NOT NULL CHECK (cpu_cores > 0),
    memory_gb   INT          NOT NULL CHECK (memory_gb > 0),
    os_name     VARCHAR(60)  NOT NULL,
    os_eol_date DATE,                               -- end-of-life date of the OS version
    owner       VARCHAR(80),
    tags        VARCHAR(255) NOT NULL DEFAULT '',   -- comma-separated, e.g. "cost-center:42,tier:1"
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DECOMMISSIONED')),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_server_cluster ON server (cluster_id);

-- Change requests: the core workflow entity.
CREATE TABLE change_request (
    id               BIGSERIAL PRIMARY KEY,
    title            VARCHAR(140) NOT NULL,
    description      TEXT         NOT NULL,
    type             VARCHAR(30)  NOT NULL CHECK (type IN ('ADD_SERVERS', 'REMOVE_SERVERS', 'OS_UPGRADE', 'CONFIG_CHANGE')),
    cluster_id       BIGINT       NOT NULL REFERENCES cluster (id),
    server_count     INT          NOT NULL DEFAULT 0 CHECK (server_count >= 0),
    window_start     TIMESTAMPTZ  NOT NULL,         -- maintenance window
    window_end       TIMESTAMPTZ  NOT NULL,
    status           VARCHAR(30)  NOT NULL,
    requester_id     BIGINT       NOT NULL REFERENCES app_user (id),
    approver_id      BIGINT       REFERENCES app_user (id),
    decision_comment TEXT,
    -- Agent output
    risk_level       VARCHAR(10)  CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH')),
    agent_summary    TEXT,
    agent_plan       TEXT,                          -- JSON array of steps
    agent_citations  TEXT,                          -- JSON array of runbook slugs
    agent_recommendation VARCHAR(20) CHECK (agent_recommendation IN ('APPROVE', 'REJECT')),
    agent_source     VARCHAR(20),                   -- LLM, FALLBACK (LLM failed) or RULES (no LLM configured)
    agent_trace      TEXT,                          -- JSON: which tools the agent called, for transparency
    agent_guardrail  BOOLEAN NOT NULL DEFAULT FALSE,-- true if a deterministic safety check overrode the LLM
    -- Timestamps
    submitted_at     TIMESTAMPTZ,
    decided_at       TIMESTAMPTZ,
    executed_at      TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version          BIGINT       NOT NULL DEFAULT 0,   -- optimistic locking
    CHECK (window_end > window_start)
);
CREATE INDEX idx_cr_status ON change_request (status);
CREATE INDEX idx_cr_cluster_window ON change_request (cluster_id, window_start, window_end);

-- Append-only audit trail. Every state change writes one row.
CREATE TABLE audit_event (
    id          BIGSERIAL PRIMARY KEY,
    request_id  BIGINT REFERENCES change_request (id),
    actor       VARCHAR(50) NOT NULL,
    action      VARCHAR(40) NOT NULL,
    details     TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_request ON audit_event (request_id);

-- Runbooks with a generated full-text-search column (retrieval for the agent).
CREATE TABLE runbook (
    id       BIGSERIAL PRIMARY KEY,
    slug     VARCHAR(80)  NOT NULL UNIQUE,
    title    VARCHAR(140) NOT NULL,
    content  TEXT         NOT NULL,
    search   TSVECTOR GENERATED ALWAYS AS (
                 setweight(to_tsvector('english', title), 'A') ||
                 setweight(to_tsvector('english', content), 'B')
             ) STORED
);
CREATE INDEX idx_runbook_search ON runbook USING GIN (search);

-- Output of the nightly policy job.
CREATE TABLE policy_violation (
    id           BIGSERIAL PRIMARY KEY,
    entity_type  VARCHAR(20)  NOT NULL CHECK (entity_type IN ('CLUSTER', 'SERVER')),
    entity_id    BIGINT       NOT NULL,
    entity_name  VARCHAR(120) NOT NULL,
    rule         VARCHAR(40)  NOT NULL,
    message      VARCHAR(255) NOT NULL,
    detected_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_at  TIMESTAMPTZ
);
-- Partial unique index: at most ONE open violation per (entity, rule). Makes the scan idempotent.
CREATE UNIQUE INDEX uq_violation_open ON policy_violation (entity_type, entity_id, rule) WHERE resolved_at IS NULL;
