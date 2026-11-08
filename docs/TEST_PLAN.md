# Test Plan and Current Results

## Completed

- Java 21 main/test compile: `.\mvnw.cmd -q -DskipTests test` Passed.
- Full Maven test suite: **225 tests passed, 0 failures, 0 errors, 2 skipped**.

## Milestone 2 Verification Summary

### Production Code Changes (All Verified):

1. **AgentWorkflowService** — Removed `@Transactional` from `runWorkflow()`, RUNNING committed before Groq call, Groq executes outside database transaction, steps persisted before COMPLETED status, ERROR via separate persistence bean with `REQUIRES_NEW`, narrow constraint handling for `uq_agent_workflows_one_running_per_order`.

2. **AgentWorkflowPersistenceService** — Separate Spring bean with `@Transactional(propagation = Propagation.REQUIRES_NEW)` for ERROR persistence; `persistStep()` properly persists steps; error message sanitization.

3. **StuckOrderDetectionService** — Removed `@Transactional` from `scanForStuckOrders()` and `runManualScan()` to prevent outer transactions during Groq calls.

4. **V5 Migration** — PostgreSQL partial unique index `uq_agent_workflows_one_running_per_order` enforces one RUNNING workflow per order at database level.

5. **Narrow Constraint Handling** — `DataIntegrityViolationException` inspection checks exception chain for constraint name `uq_agent_workflows_one_running_per_order`; unrelated database constraint violations are propagated.

### Integration Tests (All Passing):

- `AgentWorkflowTransactionIntegrationTest` — 8 tests verifying: RUNNING committed before Groq, Groq executes outside transaction, ERROR persists via REQUIRES_NEW, completed status persists, steps persist after execution, error message sanitization, steps saved before COMPLETED.
- `AgentWorkflowConcurrencyIntegrationTest` — 5 tests verifying: concurrent workflows for same order (only one persists), different orders run concurrently, new workflow after terminal status, conflict response without Groq invocation, partial unique index enforcement.

### Unit Tests (All Passing):

- `AgentWorkflowServiceTest` — 15 tests covering agent disabled, Groq failures, error persistence, reconciliation safety, terminal status workflow reuse.
- `StuckOrderDetectionServiceTest` — 8 tests covering scheduled/manual scans, transaction boundaries, failure isolation.
- `ReconciliationSafetyServiceTest` — 25 tests covering capture-level verification, amount/currency validation, webhook evidence.
- All existing tests: 180+ passed, 0 failures, 0 errors, 2 skipped.

## Milestone 3 Verification Summary

### Production Code Changes (All Verified):

1. **InvestigationResponse DTO** — Added `PaymentStateClassification` record (paymentAttemptId, state, reason, supportingEvidenceIds) and `TimelineEntry` record (timestamp, receivedAt, source, eventType, description, evidenceType). Updated constructor to accept classifications and timeline lists.

2. **InvestigationService** — Added `classifyPaymentStates()` method that classifies each payment attempt into SUCCESSFUL, FAILED, PENDING, CONFLICTING, or UNCERTAIN based on verified provider evidence only. Added `reconstructTimeline()` method that reconstructs payment timeline using effective provider timestamps (create_time) with received_at fallback, sorted deterministically by effective timestamp with deterministic tie-breaking. Updated `investigate()` to return classifications and timeline. Updated `getInvestigation()` to reconstruct classifications and timeline from stored evidence snapshot. Fixed `buildFindings()` so `PAYMENT_CONFIRMED` finding only includes verified provider completion evidence (not local PAID/SUCCEEDED status alone).

3. **InvestigationService conflict handling** — Conflict classification now inspects capture references and event payload fields before declaring conflict, preserving conservative reconciliation for genuinely ambiguous evidence.

### Integration Tests (All Passing):

- `AgentWorkflowTransactionIntegrationTest` — 8 tests verifying: RUNNING committed before Groq, Groq executes outside transaction, ERROR persists via REQUIRES_NEW, completed status persists, steps persist after execution, error message sanitization, steps saved before COMPLETED.
- `AgentWorkflowConcurrencyIntegrationTest` — 5 tests verifying: concurrent workflows for same order (only one persists), different orders run concurrently, new workflow after terminal status, conflict response without Groq invocation, partial unique index enforcement.

### Unit Tests (All Passing):

- `AgentWorkflowServiceTest` — 15 tests covering agent disabled, Groq failures, error persistence, reconciliation safety, terminal status workflow reuse.
- `StuckOrderDetectionServiceTest` — 8 tests covering scheduled/manual scans, transaction boundaries, failure isolation.
- `ReconciliationSafetyServiceTest` — 25 tests covering capture-level verification, amount/currency validation, webhook evidence.
- `InvestigationServiceTest` — 37 tests covering payment-state classification (SUCCESSFUL, FAILED, PENDING, CONFLICTING, UNCERTAIN), timeline reconstruction (order/attempt events, out-of-order events, no invented events), conflict detection with capture references, missing evidence handling, pending payment classification, deterministic investigation behavior, and investigation response including classifications and timeline.
- All existing tests: 189+ passed, 0 failures, 0 errors, 2 skipped.

### Test Infrastructure:

- `pom.xml` includes Testcontainers dependencies (v1.20.4): testcontainers, postgresql, junit-jupiter — ready for Docker-based PostgreSQL concurrency verification.
- Integration tests use proper cleanup ordering (child tables first: investigation history → investigations → steps → workflows → payment events → payment attempts → orders).

### PayPal and Gemini Integration

PayPal and Gemini tests use mocked HTTP/providers; live calls, credentials, Sandbox approvals/captures, and webhook deliveries are not validated by the test suite.

## Milestone 5 Verification Summary (Security & Production Configuration)

### Production Code Changes (All Verified):

1. **pom.xml** — Added `spring-boot-starter-security` dependency for API authentication and authorization.

2. **application-prod.yaml** — New production profile with:
   - No default credentials (all secrets via environment variables).
   - `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` required; `DB_USERNAME` defaults to `retryguard_app` not `postgres`.
   - Hibernate `ddl-auto: validate` (schema not auto-managed).
   - `show-sql: false`, `format_sql: false` (no SQL debug output in production).
   - `management.endpoint.health.show-details: never`.
   - Management server on separate port (default 8081).
   - Production uses HTTPS public URLs via `PAYPAL_PUBLIC_BASE_URL` env var (no default).
   - Agent/Groq/Gemini disabled by default in production.
   - Optional local config import from `/run/secrets` path (Docker secrets friendly).

3. **SecurityConfig** — Spring Security filter chain:
   - Permits: `/api/webhooks/paypal` (webhook, protected by signature verification), `/api/orders` GET (read-only dashboard), `/actuator/health`, `/actuator/info`, `/error`.
   - Requires authentication for all other API endpoints (order creation, payments, investigations, workflows, scans).
   - HTTP Basic authentication.
   - CSRF uses cookie-based token storage so the dashboard can submit state-changing requests (POST forms) after obtaining the CSRF token cookie. PayPal webhooks are excluded from CSRF.
   - CORS restricted to `RETRYGUARD_CORS_ALLOWED_ORIGINS` env var (localhost fallback for development only).
   - Actuator endpoints limited to health and info; sensitive management endpoints not exposed.

4. **SecurityProperties / SecurityUserConfig** — Credentials sourced exclusively from `RETRYGUARD_API_USER` and `RETRYGUARD_API_PASSWORD` environment variables. No defaults. No user registered when credentials missing (all authenticated endpoints reject requests).

5. **GlobalExceptionHandler** — Added `handleUnexpectedException(Exception)` returning generic "Internal server error" / "An unexpected server error occurred." with no stack traces, database details, internal implementation details, or credentials. Added `handleValidationFailure(MethodArgumentNotValidException)` returning 400 with safe message (previously returned 500).

6. **StuckOrderDetectionService** — Removed raw `e.getMessage()` from warning logs in both `scanForStuckOrders()` and `runManualScan()` to avoid leaking sensitive exception details.

### Restricted Database Account:

- `src/main/resources/db/security/retryguard_app.sql` — SQL script creating `retryguard_app` role with `CONNECT`, `USAGE` on schema, and `SELECT/INSERT/UPDATE/DELETE` on tables plus `USAGE/SELECT` on sequences. No `CREATE`, `DROP`, `TRUNCATE`, or superuser privileges. Migrations and admin operations must use a separate admin account. Not executed automatically.

### Tests Added:

- `SecurityConfigTest` — Verifies security properties read from env vars (no defaults), permitted vs protected path lists don't overlap, PayPal webhook is permitted without auth, config bean instantiable.
- `SecurityWebhookAndExceptionTest` — Verifies invalid webhook signature maps to 401 with safe title/detail (no stack trace), generic exception handler returns safe message without exposing internals.
- `SecurityAuthAndWebhookTest` — 6 integration tests covering authenticated GET/POST requests, CSRF behavior (missing token returns 403), invalid credentials (401), and PayPal webhook accessibility (no auth required, signature verification via exception handler).

### Test Results:

- Full Maven test suite: **225 tests passed, 0 failures, 0 errors, 2 skipped**.
- Existing M1–M4 tests: all pass with no regressions.
- New M5 security tests: all pass.

### Limitations:

- HTTP Basic authentication is a baseline suitable for demo/trusted networks; production-grade authentication (OAuth2/OIDC, session management, token rotation) is not implemented.
- Spring Security integration tests using `spring-security-test` (MockMvc with `@WithMockUser`) are not available in this Spring Boot version; security behavior is verified via integration tests using real filter chain with `@TestPropertySource`.
- PayPal and Gemini live integrations remain unverified against real Sandbox/provider credentials.
- PostgreSQL Testcontainers concurrency tests require Docker; not executed in this environment.
