# Project Status

## Implemented MVP

- Existing order/attempt creation, PayPal Sandbox order and capture, signature-verified webhook ingestion, event idempotency, conservative deterministic investigations, timeline reconstruction, and Gemini explanation flow are preserved.
- Investigations now persist an append-only JSONB findings array with stable code, category, severity, evidence references, non-executing operator guidance, and root-cause uncertainty. Supported findings are generated only from actual persisted order/attempt/event evidence. They do not alter the decision rules.
- New read APIs retrieve a run, list up to 50 order runs newest-first, and return history oldest-first. They use DTO responses and do not expose provider payloads.
- `/` now serves a responsive static dashboard backed by the real application APIs. It supports order/attempt creation, timeline and autopsy review, previous runs/history, and Gemini explanation. PayPal order creation and capture are separate user-initiated Sandbox actions; capture requires confirmation.
- A bounded `GET /api/orders` endpoint feeds the dashboard's recent-order selector.
- Agent workflow reliability (Milestone 2) production code is fully implemented:
  - RUNNING workflow committed before Groq call via `AgentWorkflowPersistenceService.persistRunning()`.
  - Groq agent execution runs without an active database transaction.
  - Workflow results, steps, and ERROR status are persisted using separate short transactions via `AgentWorkflowPersistenceService`.
  - ERROR persistence uses `@Transactional(propagation = Propagation.REQUIRES_NEW)` through a separate Spring-managed bean (`AgentWorkflowPersistenceService`) to avoid self-invocation proxy bypass.
  - PostgreSQL partial unique index `uq_agent_workflows_one_running_per_order` prevents multiple RUNNING workflows for the same order.
  - `persistResults()` persists all steps before marking the workflow COMPLETED, and persists ERROR independently if step persistence fails.
  - Step persistence failures result in ERROR status, not misleading COMPLETED.
  - Concurrency conflict handling catches `DataIntegrityViolationException` safely and narrows it to only the expected PostgreSQL unique index violation `uq_agent_workflows_one_running_per_order`.
  - Unrelated database constraint violations are propagated, not misclassified.
- Agent workflow reliability (Milestone 2) investigation reliability:
  - Payment-state classification per attempt: SUCCESSFUL, FAILED, PENDING, CONFLICTING, UNCERTAIN based on verified provider evidence only.
  - Timeline reconstruction using effective provider timestamps (create_time) with received_at fallback, sorted deterministically.
  - Conflicting evidence detection now inspects capture references and event payload fields before declaring conflict.
  - Payment-confirmed findings require verified provider completion evidence; local PAID/SUCCEEDED status alone is not sufficient.
  - Investigation retrieval (`getInvestigation()`) returns the same classifications and timeline as the original response.
- Deterministic investigation rules preserved (15-minute freshness, REVIEW_ELIGIBLE only with verified terminal-denial evidence, etc.).
- PayPal capture-level verification (Milestone 1) remains intact.

## Milestone 5 — Security & Production Configuration

### Implemented (M5)

1. **Spring Security** (`spring-boot-starter-security` added to `pom.xml`):
   - `SecurityConfig` protects all sensitive API endpoints (order creation, payments, investigations, workflows, scans) with HTTP Basic authentication.
   - PayPal webhook endpoint (`/api/webhooks/paypal`) remains accessible without authentication but is protected by mandatory signature verification.
   - Read-only order listing (`GET /api/orders`) and actuator health/info are permitted without auth.
   - CORS restricted to `RETRYGUARD_CORS_ALLOWED_ORIGINS` env var (localhost fallback for development only).
   - CSRF disabled (stateless API).

2. **Credentials from environment only**:
   - `SecurityProperties` / `SecurityUserConfig` read `RETRYGUARD_API_USER` and `RETRYGUARD_API_PASSWORD` from environment variables.
   - No defaults; no hardcoded credentials. No user registered when credentials missing.

3. **Production profile** (`application-prod.yaml`):
   - All secrets via environment variables (no defaults).
   - `DB_USERNAME` defaults to `retryguard_app` (restricted account, not `postgres`).
   - Hibernate `ddl-auto: validate`; `show-sql: false`; `format_sql: false`.
   - `management.endpoint.health.show-details: never`.
   - Management endpoints limited to health and info.
   - Agent/Groq/Gemini disabled by default.

4. **Safe error handling** (`GlobalExceptionHandler`):
   - `handleUnexpectedException` returns generic "Internal server error" without stack traces or internal details.
   - `handleValidationFailure` returns 400 with safe message.

5. **Logging security** (`StuckOrderDetectionService`):
   - Removed raw `e.getMessage()` from warning logs in both scheduled and manual scans.

6. **Restricted database account**:
   - `src/main/resources/db/security/retryguard_app.sql` creates `retryguard_app` with `CONNECT`, `USAGE`, `SELECT/INSERT/UPDATE/DELETE` on tables, `USAGE/SELECT` on sequences. No superuser or DDL privileges. Not executed automatically.

## Test Results

- Full Maven test suite: **225 tests passed, 0 failures, 0 errors, 2 skipped**.
- New M5 security tests: `SecurityConfigTest` (5 tests), `SecurityWebhookAndExceptionTest` (3 tests), `SecurityAuthAndWebhookTest` (6 tests).
- Existing M1–M4 tests all pass with no regressions.

### Limitations

- HTTP Basic authentication is a baseline for demo/trusted networks; production-grade OAuth2/OIDC is not implemented.
- Live PayPal Sandbox and Gemini integrations remain unverified against real credentials.
- PostgreSQL Testcontainers concurrency tests require Docker; not executed in this environment.

## Verification performed in this task

- Java 21 compilation of main and test sources passed with `.\mvnw.cmd -q -DskipTests test`.
- Full Maven test suite: **189 tests executed, 187 passed, 0 failures, 0 errors, 2 skipped**.
- Focused findings, retrieval controller, Gemini, order-list, and PayPal webhook regression: 58 tests, 0 failures, 0 errors, 0 skipped.
- Dashboard inline JavaScript syntax check passed with Node.js.
- Agent workflow service transaction separation verified: RUNNING committed before Groq, Groq executes without active DB transaction, ERROR persists independently through separate Spring bean.
- PayPal capture-level verification (Milestone 1) confirmed: capture status COMPLETED/PENDING/DENIED, amount/currency verification, capture ID validation, multiple captures handling.
- Deterministic investigation rules confirmed unchanged.
- Groq agent workflow safety rules preserved (bounded turns, allowlisted tools, read-only advisory role).
- Narrow database conflict handling: `DataIntegrityViolationException` now inspects the exception chain for constraint name `uq_agent_workflows_one_running_per_order` before classifying a conflict; unrelated database constraint violations are propagated.
- Investigation reliability (Milestone 3) verified: payment-state classification, timeline reconstruction with effective timestamps, conflicting evidence detection with capture reference comparison, payment-confirmed findings requiring verified provider completion, and investigation retrieval consistency.

## Important remaining limitations

- Integration tests (`AgentWorkflowTransactionIntegrationTest`, `AgentWorkflowConcurrencyIntegrationTest`) are designed for Testcontainers PostgreSQL; full concurrency verification requires Docker availability for Testcontainers.
- Live PayPal Sandbox credentials, approval/capture, and webhook configuration/delivery are unverified.
- Live Gemini credentials/API behavior is unverified.
- The app has no authentication/authorization and is for trusted-network demo use only.
- The order timeline reports current attempt status, not a history of individual payment-status transitions.

## Narrow database conflict handling

- The `DataIntegrityViolationException` in `AgentWorkflowService.catch` now specifically checks for the PostgreSQL partial unique index `uq_agent_workflows_one_running_per_order` before classifying a conflict as `WORKFLOW_ALREADY_RUNNING`.
- Unrelated database constraint violations are propagated (thrown), not misclassified.
- Different orders can still execute concurrently.
- Rejected concurrent requests do not invoke Groq.

## Testcontainers Integration Test Infrastructure (Ready)

- `pom.xml` includes Testcontainers dependencies (v1.20.4): testcontainers, postgresql, junit-jupiter
- Integration test classes prepared for PostgreSQL concurrency verification when Docker is available:
  - `AgentWorkflowTransactionIntegrationTest` — 8 Spring transaction boundary tests
  - `AgentWorkflowConcurrencyIntegrationTest` — 5 PostgreSQL concurrency tests
- Tests use deterministic coordination (CountDownLatch) and unique merchant order references per test
- When Docker is available, Testcontainers will spin up isolated PostgreSQL per test class, applying V5 migration and verifying partial unique index behavior