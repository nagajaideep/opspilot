-- Demo inventory. Some rows intentionally break policy so the nightly job has work to do.

INSERT INTO cluster (name, environment, region, owner_team, max_servers) VALUES
    ('payments-prod',  'PROD',    'us-west-2', 'payments-sre', 12),
    ('search-prod',    'PROD',    'us-east-1', 'search-infra', 20),
    ('analytics-stg',  'STAGING', 'us-west-2', 'data-platform', 8),
    ('ml-train-dev',   'DEV',     'us-west-2', NULL,            6),   -- missing owner -> violation
    ('edge-cache-prod','PROD',    'eu-west-1', 'edge-team',     4);

-- payments-prod: 10 of 12 used
INSERT INTO server (hostname, cluster_id, cpu_cores, memory_gb, os_name, os_eol_date, owner, tags)
SELECT 'pay-' || lpad(g::text, 2, '0') || '.prod', c.id, 16, 64, 'Ubuntu 22.04', DATE '2027-04-30', 'payments-sre', 'cost-center:101,tier:1'
FROM cluster c, generate_series(1, 10) g WHERE c.name = 'payments-prod';

-- search-prod: 12 of 20 used, two on an end-of-life OS
INSERT INTO server (hostname, cluster_id, cpu_cores, memory_gb, os_name, os_eol_date, owner, tags)
SELECT 'search-' || lpad(g::text, 2, '0') || '.prod', c.id, 32, 128,
       CASE WHEN g <= 2 THEN 'CentOS 7' ELSE 'Ubuntu 24.04' END,
       CASE WHEN g <= 2 THEN DATE '2024-06-30' ELSE DATE '2029-04-30' END,
       'search-infra', 'cost-center:202,tier:1'
FROM cluster c, generate_series(1, 12) g WHERE c.name = 'search-prod';

-- analytics-stg: 3 of 8 used, one without tags or owner
INSERT INTO server (hostname, cluster_id, cpu_cores, memory_gb, os_name, os_eol_date, owner, tags)
SELECT 'ana-' || lpad(g::text, 2, '0') || '.stg', c.id, 8, 32, 'Ubuntu 24.04', DATE '2029-04-30',
       CASE WHEN g = 3 THEN NULL ELSE 'data-platform' END,
       CASE WHEN g = 3 THEN '' ELSE 'cost-center:303,tier:3' END
FROM cluster c, generate_series(1, 3) g WHERE c.name = 'analytics-stg';

-- ml-train-dev: 2 of 6 used
INSERT INTO server (hostname, cluster_id, cpu_cores, memory_gb, os_name, os_eol_date, owner, tags)
SELECT 'ml-' || lpad(g::text, 2, '0') || '.dev', c.id, 64, 512, 'Ubuntu 22.04', DATE '2027-04-30', NULL, 'tier:3'
FROM cluster c, generate_series(1, 2) g WHERE c.name = 'ml-train-dev';

-- edge-cache-prod: 4 of 4 used (full!)
INSERT INTO server (hostname, cluster_id, cpu_cores, memory_gb, os_name, os_eol_date, owner, tags)
SELECT 'edge-' || lpad(g::text, 2, '0') || '.prod', c.id, 8, 32, 'Debian 12', DATE '2028-06-30', 'edge-team', 'cost-center:404,tier:1'
FROM cluster c, generate_series(1, 4) g WHERE c.name = 'edge-cache-prod';

-- ---------------------------------------------------------------------------
-- Runbooks: short operational procedures the agent can retrieve and cite.
-- ---------------------------------------------------------------------------
INSERT INTO runbook (slug, title, content) VALUES
('scale-out-cluster', 'Scaling out a cluster (adding servers)',
 'Use this runbook when adding servers to an existing cluster. 1. Confirm the cluster has spare capacity: current servers plus new servers must not exceed max_servers. 2. Provision servers from the approved golden image. 3. Apply standard tags: cost-center and tier. 4. Add servers to the load balancer in drain mode. 5. Run health checks for 15 minutes. 6. Shift traffic gradually: 10%, 50%, 100%. Rollback: remove new servers from the load balancer and decommission them.'),
('scale-in-cluster', 'Scaling in a cluster (removing servers)',
 'Use this runbook when removing or decommissioning servers. 1. Verify remaining capacity can absorb peak traffic plus 30% headroom. 2. Drain connections from the servers being removed. 3. Remove from the load balancer. 4. Wait for one full traffic cycle before decommissioning. Never remove more than 25% of a production cluster in one change. Rollback: re-enable the drained servers.'),
('os-upgrade', 'Rolling OS upgrade',
 'Use this runbook for operating system upgrades and patching, including replacing end-of-life OS versions such as CentOS 7. 1. Upgrade one canary server first and soak for 24 hours. 2. Upgrade remaining servers in batches of at most 20% of the cluster. 3. Each batch: drain, upgrade, reboot, health check, re-enable. Rollback: restore the server from the pre-upgrade snapshot.'),
('prod-change-freeze', 'Production change windows and freezes',
 'Production changes must happen inside an approved maintenance window, Tuesday to Thursday, 02:00-06:00 local time of the region. Overlapping changes on the same cluster are not allowed; serialize them. Changes during a declared freeze require director approval. Any production change touching tier:1 services is at least MEDIUM risk.'),
('capacity-planning', 'Capacity planning and cluster limits',
 'Each cluster has a hard max_servers limit set by the network and licensing design. Requests that would exceed max_servers must be rejected or split, and a new cluster requested instead. Keep production clusters below 85% utilization to leave failover headroom.'),
('config-change', 'Configuration changes',
 'Use this runbook for configuration-only changes such as kernel parameters, feature flags, or service configuration. 1. Peer-review the diff. 2. Apply to one server and verify. 3. Roll out with the configuration management tool in batches. Rollback: revert the commit and re-apply.'),
('tagging-standard', 'Resource tagging and ownership standard',
 'Every server must have an owner and the tags cost-center and tier. Every cluster must have an owner team. Untagged or unowned resources are flagged by the nightly policy scan and must be fixed within 7 days.');
