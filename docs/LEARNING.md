# OpsPilot study guide

How the project was built, step by step: what each part does, why it's designed that way, the files to read, and the
interview questions it prepares you for. Read it with the code open.

> **Before you put this on your resume:** be able to explain every file listed below without notes. Interviewers pick
> one line and dig. A good exercise: delete `RiskRules.java` and `LlmAgent.parseFinal`, rewrite them from the tests,
> and get the suite green again.

---

## 1. Data model and migrations

**Files:** `backend/pom.xml`, `db/migration/V1__init_schema.sql`, `V2__seed_inventory_and_runbooks.sql`, `*/Cluster.java`, `*/Server.java`

- **Spring Boot starters** bundle dependencies plus auto-configuration. Adding `starter-data-jpa` gives you a
  `DataSource`, an `EntityManager` and repositories from `application.yml` alone.
- **Flyway** applies numbered SQL files once, in order, and records them in `flyway_schema_history`. Every environment
  gets the same schema. Never edit a migration that has run anywhere; add `V3__...`.
- **`ddl-auto: validate`**: Hibernate checks the entities match the tables but never changes the schema. Flyway owns it.
- **Constraints in the database** (`CHECK`, `UNIQUE`, foreign keys) are the last line of defense even if app code has a bug.
- **Generated `tsvector` column + GIN index** for runbook search, kept up to date by Postgres automatically.
- **Partial unique index** (`... WHERE resolved_at IS NULL`): at most one *open* violation per entity and rule.

**Q: Why not let Hibernate create the tables?** `ddl-auto=update` can't do safe renames, data backfills or
reviewable changes, and it differs between environments. Migrations are code-reviewed, versioned and repeatable.

**Q: What is the N+1 problem?** Loading 50 servers, then lazily loading each one's cluster = 51 queries. Fixed with
`join fetch` (`ServerRepository.findAllActiveWithCluster`) or `@EntityGraph` (`ChangeRequestRepository`).

## 2. Authentication and authorization

**Files:** `auth/SecurityConfig.java`, `TokenService.java`, `AuthController.java`, `LoginAttemptService.java`, `CurrentUser.java`

- **Authentication** = who you are (login). **Authorization** = what you may do (roles).
- **JWT**: `header.payload.signature`, base64url. The server signs `{sub, uid, roles, exp, iss}` with an HMAC-SHA256
  secret. Any request carrying the token can be verified without a DB lookup (stateless). The payload is *readable*
  by anyone, so never put secrets in it; it's the signature that prevents tampering (see `forgedTokenIsRejected`).
- **BCrypt (cost 12)**: salted and deliberately slow (~250 ms), so stolen hashes are expensive to crack.
- **Timing-safe login**: unknown usernames still run a BCrypt check against a dummy hash, so response time doesn't
  reveal which usernames exist. Same error message for both cases.
- **Brute-force lockout**: 5 failures → 15-minute lock per username.
- **Least privilege**: Requester (create/submit own), Approver (decide/execute others' requests), Admin (policy scan,
  audit). Coarse rules in `SecurityConfig` URL matchers; fine rules with `@PreAuthorize` and ownership checks in services.
- **CSRF is disabled** because auth is an `Authorization` header the frontend sets itself; CSRF exploits cookies the
  browser sends automatically.

**Q: Where would you store the token in the browser?** This app uses `sessionStorage` (tab-scoped, cleared on close).
Any JS-readable storage is exposed to XSS, mitigated here by React's escaping and a strict CSP. The stronger option is
an `httpOnly`, `Secure`, `SameSite=Strict` cookie, which JS can't read, but then you must handle CSRF.

**Q: How do you revoke a JWT before it expires?** Short TTLs plus refresh tokens, or a deny-list of token IDs (`jti`)
checked on each request. That's the trade-off of stateless tokens.

**Q: HS256 vs RS256?** HS256 uses one shared secret (fine when one service issues and verifies). RS256 signs with a
private key and verifies with a public key, which is better when many services verify tokens issued by one identity provider.

## 3. Workflow, state machine and audit

**Files:** `request/RequestStatus.java`, `ChangeRequest.java`, `ChangeRequestService.java`, `audit/AuditService.java`

- **Explicit state machine**: `RequestStatus.next()` lists legal moves; `ChangeRequest.transitionTo` is the *only* way
  status changes. Illegal moves → `ConflictException` → HTTP 409.
- **Separation of duties**: nobody decides their own request (403).
- **Optimistic locking (`@Version`)**: Hibernate adds `WHERE version = ?` to updates. Two approvers clicking at once:
  the second update affects 0 rows → 409 "reload and try again".
- **Transactions**: `submit()` is split into tx1 (→ AGENT_REVIEW), the LLM call with no transaction, tx2 (save review).
  Holding a pooled DB connection for 10 seconds of network I/O would exhaust the pool (10 connections) under load.
- **Audit with `Propagation.MANDATORY`**: the audit row must be written inside the caller's transaction, so the change
  and its audit record commit or roll back together.
- **`allowedActions`**: the server tells the UI which buttons to show; permission logic lives in one place.
- **Execution re-checks capacity** (`executionRechecksCapacity` test): two approved +2 requests can't both fit.

**Q: Optimistic vs pessimistic locking?** Optimistic (version check at write) suits low contention and doesn't hold
locks. Pessimistic (`SELECT ... FOR UPDATE`) suits high contention where retries would be expensive.

## 4. The AI agent

**Files:** `agent/LlmAgent.java`, `AgentTools.java`, `RunbookSearch.java`, `RiskRules.java`, `RuleBasedPlanner.java`, `AgentService.java`, `OpenAiCompatibleLlmClient.java`, `resources/prompts/agent-system.txt`

- **Tool calling**: tools are described with JSON Schema. The model replies with `tool_calls`; we run them, append
  `role: tool` messages with the results, and call the model again, until it returns a final answer or hits
  `maxSteps` (8). That loop is the agent.
- **Read-only tools**: even a manipulated model can't change anything; changes only happen through human-approved execution.
- **Tool errors go back to the model as data** (`{"error": ...}`) so it can recover, instead of crashing the loop.
- **Output validation**: parse JSON (tolerating markdown fences), check the enums, require a non-empty summary and plan,
  cap the length. Anything invalid → `InvalidAgentOutput` → fallback.
- **Hallucination check**: citations must be slugs that `searchRunbooks` actually returned during the run; others are dropped and logged.
- **Retrieval (RAG)**: Postgres full-text search. Query words are sanitized and OR-ed; `ts_rank` with title weighted
  above body. Why not vectors: tiny corpus, exact keywords, no embedding API, explainable. Upgrade to pgvector +
  embeddings (hybrid search) when runbooks number in the hundreds or queries become paraphrases.
- **Guardrail**: `RiskRules` (a pure function) computes a floor; `AgentService.applyGuardrail` raises the LLM's risk to
  at least the floor and forces REJECT on hard blockers. More cautious LLM answers are kept.
- **Prompt injection**: user text is wrapped in delimiters and labelled untrusted, delimiter tokens are stripped,
  tools are read-only, the guardrail sets a floor, and a human decides. No single defense is enough; layers are.
- **Resilience4j**: `@Retry` (3 attempts, 1 s then 2 s backoff, only on timeouts/5xx/429; a 401 isn't retried) wraps
  `@CircuitBreaker` (opens at ≥50% failures over the last 10 calls, waits 30 s, then lets 2 trial calls through). When open, calls fail
  instantly → immediate fallback instead of every user waiting for timeouts.
- **`temperature: 0`** for repeatable reviews.

**Q: How do you test something non-deterministic?** Split it. The *loop* is deterministic code, tested with a scripted
fake LLM (`ScriptedLlmClient`). The *model's judgment* is measured with an offline eval on a labelled set (step 8) and
online by how often humans agree with it (a metric).

**Q: What happens if the LLM is down?** Retries for transient errors; the circuit breaker stops calling after repeated
failures; the request is reviewed by the rule-based planner and marked FALLBACK in the UI and in metrics. If even that
fails (DB down), the request returns to DRAFT with a clear message.

## 5. Policy automation

**Files:** `policy/PolicyScanService.java`

- `@Scheduled(cron = "0 0 2 * * *", zone = "UTC")`, also runnable on demand by admins.
- **Idempotent**: compares detected findings with open ones by key; inserts new, resolves vanished, leaves the rest.
- **Multiple instances** would each run the job; production fix: a distributed lock such as ShedLock (a lock row in the DB).

## 6. Testing

**Files:** `src/test/java/...`, `support/IntegrationTestBase.java`, `support/TestcontainersConfig.java`

- **Test pyramid**: many fast unit tests (`RiskRulesTest`, `RequestStatusTest`, `LlmAgentTest`, `AgentServiceTest`),
  fewer integration tests through HTTP against real Postgres.
- **Testcontainers + `@ServiceConnection`**: a real `postgres:16-alpine` per test run; Spring wires the datasource automatically.
- **Inject `Clock`**, never call `Instant.now()` in business code, so tests control time (`LoginAttemptServiceTest`).
- **Bugs the tests caught**: a `ts_headline` syntax error (only visible against real Postgres), unknown URLs returning
  500 instead of 404, and a test that wrongly assumed Los Angeles time for a New York cluster.

**Q: Why not H2 for tests?** H2 isn't Postgres: no `tsvector`, different SQL dialect, different locking. Tests would
pass on H2 and fail in production.

## 7. Frontend and accessibility

**Files:** `frontend/src/api.ts`, `auth.tsx`, `components/Layout.tsx`, `pages/NewRequest.tsx`, `pages/RequestDetail.tsx`, `pages/pages.test.tsx`

- **Typed API client**: one `fetch` wrapper adds the token, converts Problem Details into `ApiError` (with field
  errors), and logs out on 401.
- **Nested routes**: one `RequireAuth` guard + one shared `Layout` with `<Outlet/>`. Route guards are UX only; the API
  enforces everything.
- **Accessibility (WCAG 2.1 AA)**: labels for every input; `aria-invalid` + `aria-describedby` linking errors and hints;
  an **error summary** that receives focus and links to each field; `role="alert"` vs `role="status"`; a skip link;
  focus moves to the page `<h1>` on in-app navigation (`useNavigationType() !== "POP"`); risk shown as text + shape +
  color; tables with `<th scope>`; a visible focus ring; contrast ≥ 4.5:1.
- **Testing a11y**: axe-core in jsdom (fast, in CI) plus axe in real Chromium (catches contrast and real focus
  behavior). The browser run caught three bugs jsdom missed.

**Q: Why does focus management matter in an SPA?** A full page load announces the new page to screen readers; a
client-side route change doesn't. Moving focus to the new `<h1>` restores that.

## 8. Evaluation

**Files:** `eval/cases.json`, `backend/src/test/java/dev/opspilot/agent/AgentEvaluationTest.java`, `eval/report.md`

- 30 labelled cases covering boundaries (exactly at capacity, exactly 25% removal), windows, conflicts, non-prod, and
  two prompt-injection cases.
- Reports **raw LLM** accuracy (honest number), **post-guardrail** accuracy (what users see), and the rules baseline
  (100%, a label-consistency check, not an achievement).
- Excluded from `mvn verify` (`@Tag("eval")`), run with `mvn test -Peval`, so CI doesn't spend API money.

**Q: Isn't 30 cases too few?** For a demo it shows the method. In production you'd grow the set from real requests
where humans disagreed with the agent (the `human_agreement` metric finds them), and track accuracy per model/prompt version.

## 9. Containers and observability

**Files:** `backend/Dockerfile`, `frontend/Dockerfile`, `frontend/nginx.conf`, `docker-compose.yml`, `infra/`

- **Multi-stage builds**: build tools stay in stage 1; the runtime image has only the JRE and the jar, running as a non-root user.
- **Layer caching**: copy `pom.xml` / `package-lock.json` first, so dependency layers rebuild only when dependencies change.
- **`depends_on: condition: service_healthy`** + health checks: the backend waits for Postgres; the frontend waits for the backend.
- **nginx**: serves the SPA (`try_files $uri /index.html`), proxies `/api`, sets security headers. Gotcha found here:
  any `add_header` inside a `location` drops all inherited `add_header`s, so caching uses `expires` instead.
- **Metrics**: Micrometer counters, timers and histograms → `/actuator/prometheus`. Business metrics: requests opened,
  decisions, approval latency, agent runs by source, fallbacks by reason, guardrail overrides, human-agent agreement,
  open violations. Prometheus naming gotcha: a `_created` suffix is reserved, so the counter is `opspilot.requests.opened`.
- **Logs**: a request id in the MDC on every line (`X-Request-Id` echoed back); JSON (ECS) logs under the `prod` profile.
- **Dashboard as code**: Grafana datasource and dashboard are provisioned from files.

**Q: Counter vs gauge vs histogram?** Counter only goes up (requests); query with `rate`/`increase`. Gauge goes up
and down (open violations). Histogram buckets observations so you can compute percentiles (`histogram_quantile`).

## 10. CI/CD

**File:** `.github/workflows/ci.yml`. Three jobs: backend `mvn verify` (+ coverage artifact), frontend typecheck/test/build,
then Docker builds + a compose smoke test. Deployment steps are in `DEPLOY.md`.

---

## How AI coding tools were used

This project was built with help from an AI coding assistant. Be ready to answer the job description's
"use GenAI coding tools responsibly" question honestly. A good answer covers:

- **What it was used for**: scaffolding, boilerplate, test cases, debugging.
- **How you verified it**: 58 + 10 tests, coverage, running the real app, axe audits, reading every diff.
- **Where its output was wrong and testing caught it**: invalid `ts_headline` options, a made-up dummy BCrypt hash that
  would have broken timing-safe login, a CI image-tag mismatch, the nginx header bug, and focus-management timing.
  These are *real* examples from this build and make strong interview stories.

## Your resume bullets (fill in the bracket after running the eval)

- Built OpsPilot, a Java 21 / Spring Boot and React + TypeScript platform for infrastructure change requests, with
  JWT authentication, role-based least-privilege access, and Bean Validation on every API.
- Designed an LLM agent that calls read-only tools (capacity, conflicts, change windows, runbook search) to draft risk
  assessments and rollout plans, behind mandatory human approval, a deterministic guardrail and an audit trail;
  correct on [X]% of a 30-case labelled test set, including prompt-injection cases.
- Added Resilience4j retries, a circuit breaker and a rule-based fallback, Micrometer/Prometheus metrics with a Grafana
  dashboard, and shipped with Docker Compose and GitHub Actions CI at 90% backend test coverage (Testcontainers).
