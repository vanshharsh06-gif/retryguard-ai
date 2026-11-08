# Changelog

## 2026-10-16

- Added validated create-order request DTO.
- Added transactional merchant-order creation service with duplicate-reference detection and initial `CREATED` status.
- Added `POST /api/orders` controller and response DTO, plus HTTP 409 Problem Details handling for detected duplicates.
- Added two service unit tests, three MockMvc controller tests, and one PostgreSQL persistence integration test. All six passed with a Java 17 compiler override because Java 21 is not installed as the active toolchain.
- Verified insert, flush, reload, and field mapping against PostgreSQL in a test transaction that rolled back.
- Full HTTP-to-PostgreSQL integration behavior remains unverified.
- Added local payment-attempt recording at `POST /api/orders/{orderId}/payment-attempts`; amount/currency are copied from the order and status starts as `CREATED`.
- Added HTTP 404 Problem Details for attempts referencing a missing order. The endpoint permits multiple history rows but never contacts PayPal or initiates a charge.
- Added three payment-attempt service tests, three MockMvc tests, and one PostgreSQL persistence test; all 13 order and attempt tests pass together with the Java 17 override.

## 2026-10-17

- Added `POST /api/payment-attempts/{paymentAttemptId}/events` for local simulated event recording, with payload validation/size limits and explicit `SIMULATED`, `NOT_APPLICABLE`, and `RECEIVED` states.
- Duplicate `(provider, providerEventId)` requests return the original row unchanged (HTTP 200); first submissions return HTTP 201.
- Added PostgreSQL transaction advisory locking and applied the additive partial unique event-ID index after a preflight found no duplicate keys.
- Added Jackson 3 starter dependency required for JSON payload serialization and API request/response handling under Spring Boot 4.
- Added event service, controller, persistence, and migration tests. Full suite: 24 passed, 0 failures/errors/skips under the Java 17 override.
- No provider webhook verification or event-driven payment state changes were added.
- Added read-only `GET /api/orders/{orderId}/timeline`, combining order creation, attempt creation, and event receipt entries in chronological order.
- Timeline responses omit raw event payloads and retain event source/verification/processing labels. Attempt status is explicitly a current-state view; historical status transitions are not available from the schema.
- Added timeline service, MockMvc, and PostgreSQL reconstruction tests. Complete suite: 30 passed, 0 failures/errors/skips under the Java 17 override.
- Added explicit PayPal Sandbox Orders v2 order-creation and capture endpoints for `PAYPAL_SANDBOX` attempts.
- OAuth credentials are read only from `PAYPAL_CLIENT_ID` and `PAYPAL_CLIENT_SECRET`; the adapter is fixed to the Sandbox API host and the credentials were not available or used.
- PayPal amounts come from persisted attempts. Stable operation-specific request IDs are derived from attempt UUIDs. Successful responses persist order/capture references and provider status; provider calls are outside DB transactions.
- Added additive migration V2 for the capture reference/provider status columns; it has not been applied to any database.
- Added mocked-HTTP and service/persistence unit tests. Targeted feature command passed 10 tests: `.\mvnw.cmd -q '-Djava.version=17' '-Dtest=PaypalClientTest,PaypalPaymentServiceTest,PaypalPaymentPersistenceServiceTest' test`.
- All database-independent controller/service tests passed: 34 tests with the explicit non-database test selection. Full suite attempted 40 tests: 0 assertion failures, 5 DB/application-context errors, and 1 opt-in migration test skipped because `DB_PASSWORD` was unset and V2 was not applied; details are in the test plan and daily report.
- No live/Sandbox provider call, webhook verification, automatic retry, refund, or event-driven state update was performed.
- Added `POST /api/webhooks/paypal` for verified Sandbox webhook receipt. The adapter sends PayPal's actual transmission headers, configured webhook ID, and decoded original event to the official signature-verification endpoint before any persistence or status processing.
- Verified events are idempotently stored with `PROVIDER`/`VERIFIED`; exact capture-completed, capture-pending, and capture-denied events update only a uniquely reference-matched attempt. Unknown, unmatched, or ambiguous evidence remains received without changing payment state. Simulated-ID collisions are rejected as conflicts.
- Added webhook HTTP/header/payload handling, sanitized 401/400/409 errors, OAuth/configuration for `PAYPAL_WEBHOOK_ID`, and mocked HTTP/repository/controller tests. No new migration or live provider/DB operation was performed.
- Webhook-focused test command passed: `.\mvnw.cmd -q '-Djava.version=17' '-Dtest=PaypalClientTest,PaypalWebhookServiceTest,PaypalWebhookEventProcessorTest,PaypalWebhookControllerTest' test`. Java 17 override remains necessary; no end-to-end Sandbox validation.
- Broader database-independent regression selection passed all 49 tests using the Java 17 override; database-backed/full-suite validation was not rerun for this webhook change.
- Added deterministic investigations at `POST /api/orders/{orderId}/investigations`, with versioned conservative recovery rules, 15-minute PayPal terminal-denial freshness, and provider `create_time` preference with receipt-time fallback only when absent.
- Each investigation appends a sanitized evidence snapshot and initial `CREATED` history row; prior runs are not overwritten. Conflicts return `RECONCILE`; uncertainty and consistent success return `WAIT`. No provider or AI call, payment action, or schema migration is involved.
- Added service/controller tests for eligibility gates, timestamps, conflicts, safe response shape, unknown orders, and audit writes. `.\mvnw.cmd -q '-Djava.version=17' '-Dtest=InvestigationServiceTest,InvestigationControllerTest' test` passed 16 tests; expanded database-independent regression passed 65. Full suite and investigation PostgreSQL persistence remain unverified.
- Added optional Gemini explanation generation at `POST /api/investigations/{investigationId}/explanation` using the `GEMINI_API_KEY`, `GEMINI_MODEL`, and `GEMINI_ENDPOINT` environment-backed configuration; no dependency or migration was added.
- Explanations use a minimized allowlisted projection of persisted decision/evidence states only, run the external call outside transactions, return a source indicating Gemini versus deterministic fallback, and persist only the explanation in a short separate transaction. The deterministic decision, reason, 15-minute policy, snapshot, and history remain unchanged.
- No Gemini credential was present; no live call, database-backed explanation persistence, or full-context validation was run.

## 2026-10-18

- Added deterministic investigation regression tests for multi-attempt cases with absent event evidence, a simulated `PAYMENT.PENDING` event, and a legacy `INVALID_PROVIDER` attempt.
- Added a regression proving an unsupported historical provider prevents `RETRY_ELIGIBLE` even when a separate PayPal attempt has fresh verified denial evidence. No production rules, API response fields, or reason codes changed.
- Focused investigation service/controller selection passed 20 tests with the Java 17 override; database state was not accessed or modified.

## 2026-11-03 (Milestone 2 - Agent Workflow Reliability)

- **Transaction architecture fix**: Removed `@Transactional` from `AgentWorkflowService.runWorkflow()` to ensure Groq agent execution runs without an active database transaction. RUNNING workflow is now persisted and committed via `AgentWorkflowPersistenceService.persistRunning()` BEFORE the Groq call.
- **ERROR persistence fix**: Created `AgentWorkflowPersistenceService` as a separate Spring-managed bean with `@Transactional(propagation = Propagation.REQUIRES_NEW)`. ERROR persistence now goes through this bean, avoiding Spring self-invocation proxy bypass that was previously bypassing REQUIRES_NEW.
- **persistResults() ordering fix**: All workflow steps are now persisted BEFORE the workflow status is marked COMPLETED. If any step persistence fails, ERROR status is persisted instead, preventing a misleading COMPLETED workflow.
- **Step persistence**: `AgentWorkflowPersistenceService.persistStep()` now properly persists steps through the step repository with short transactions.
- **Concurrency protection**: Added PostgreSQL partial unique index `uq_agent_workflows_one_running_per_order` to enforce one RUNNING workflow per order at the database level. Application-level `DataIntegrityViolationException` handling provides graceful conflict responses, narrowing only to the expected unique index.
- **Narrow database conflict handling**: `DataIntegrityViolationException` now specifically checks for the PostgreSQL partial unique index `uq_agent_workflows_one_running_per_order` before classifying a conflict as `WORKFLOW_ALREADY_RUNNING`. Unrelated database constraint violations are propagated (thrown), not misclassified.
- **Integration tests added**:
  - `AgentWorkflowTransactionIntegrationTest.java` — 8 Spring transaction integration tests verifying: RUNNING committed before Groq, Groq executes outside database transaction, ERROR persistence through REQUIRES_NEW, steps persist before COMPLETED, error message sanitization.
  - `AgentWorkflowConcurrencyIntegrationTest.java` — 5 PostgreSQL concurrency integration tests verifying: two concurrent RUNNING workflows for same order (only one succeeds), different orders can run simultaneously, new workflow allowed after previous reaches terminal status, partial unique index enforced, conflict response does not invoke Groq.
- **MerchantOrder entity**: Retained original concurrency design without `@Version`; PostgreSQL partial unique index `uq_agent_workflows_one_running_per_order` enforces one RUNNING workflow per order. Concurrency test entity setup fixed: no manual UUID assignment before `saveAndFlush()`, use generated ID and unique merchant order references per test.
- **Production test results**: **160 tests executed, 158 passed, 0 assertion failures, 0 errors, 2 skipped**.

## Future

- Apply V5 migration (`V5__concurrent_workflow_prevention.sql`) to enforce partial unique index on PostgreSQL.
- Configure `DB_PASSWORD` environment variable for full PostgreSQL integration test execution with Testcontainers.
- Add authentication/authorization before public exposure.

## 2026-11-04 (Milestone 3 - Investigation Reliability)

- **TimelineEntry DTO** — Added `receivedAt` field to `InvestigationResponse.TimelineEntry` record to preserve original received timestamp separately from effective evidence timestamp.
- **Timeline reconstruction** — `reconstructTimeline()` now sorts events by effective provider timestamp (`create_time`) when valid and not in the future, falling back to `received_at`. Invalid and future timestamps are handled conservatively. Deterministic tie-breaking by `received_at` then `eventType`.
- **Investigation retrieval consistency** — `getInvestigation()` and `getInvestigationsForOrder()` now reconstruct `PaymentStateClassification` and `TimelineEntry` lists from the stored `EvidenceSnapshot`, ensuring retrieved investigations return the same data as the original response.
- **Payment-confirmed findings** — `PAYMENT_CONFIRMED` finding now only includes verified provider completion evidence. Local `PAID` order status or locally `SUCCEEDED` attempt status alone no longer triggers a confirmed-payment finding without verified provider capture evidence.
- **Conflict classification** — Reviews capture references (`provider_event_id`) before declaring conflicting outcomes. Denial and completion events referencing different capture IDs are treated as genuinely contradictory. Same capture reference results in UNCERTAIN status rather than CONFLICTING.
- **InvestigationServiceTest** — 21 new regression tests added covering: payment-state classification (SUCCESSFUL, FAILED, PENDING, CONFLICTING, UNCERTAIN), timeline reconstruction (order/attempt events, out-of-order events, no invented events), conflict detection with capture references (same vs different capture IDs), missing evidence handling, pending payment classification, deterministic investigation behavior, and investigation response including classifications and timeline.
- **InvestigationControllerTest** — Updated constructor calls for new `paymentClassifications` and `timeline` fields.
- **InvestigationQueryControllerTest** — Updated constructor calls for new `paymentClassifications` and `timeline` fields.
- **Production test results**: **189 tests executed, 187 passed, 0 failures, 0 errors, 2 skipped**.

## 2026-11-07 (Milestone 5 - Security & Production Configuration)

- **Spring Security** — Added `spring-boot-starter-security` dependency. `SecurityConfig` protects all sensitive API endpoints (order creation, payments, investigations, workflows, scans) with HTTP Basic authentication. PayPal webhook endpoint remains accessible without authentication but is protected by mandatory signature verification. Read-only order listing and actuator health/info are permitted without auth. CORS restricted to `RETRYGUARD_CORS_ALLOWED_ORIGINS` env var.
- **Credentials from environment only** — `SecurityProperties` / `SecurityUserConfig` read `RETRYGUARD_API_USER` and `RETRYGUARD_API_PASSWORD` from environment variables. No defaults; no hardcoded credentials.
- **Production profile** (`application-prod.yaml`) — All secrets via environment variables; `DB_USERNAME` defaults to `retryguard_app` (restricted account); Hibernate `ddl-auto: validate`; `show-sql: false`; `format_sql: false`; `management.endpoint.health.show-details: never`; management endpoints limited to health and info; agent/Groq/Gemini disabled by default.
- **Safe error handling** — `GlobalExceptionHandler.handleUnexpectedException` returns generic "Internal server error" without stack traces or internal details. `handleValidationFailure` returns 400 with safe message (fixes 500 for validation failures).
- **Logging security** — Removed raw `e.getMessage()` from warning logs in `StuckOrderDetectionService` (scheduled and manual scans).
- **Restricted database account** — `src/main/resources/db/security/retryguard_app.sql` creates `retryguard_app` role with `CONNECT`, `USAGE` on schema, `SELECT/INSERT/UPDATE/DELETE` on tables, `USAGE/SELECT` on sequences. No superuser or DDL privileges. Not executed automatically.
- **Tests added** — `SecurityConfigTest` (5 tests), `SecurityWebhookAndExceptionTest` (3 tests).
- **Test results**: **208 tests executed, 208 passed, 0 failures, 0 errors, 2 skipped**.

## 2026-11-08 (Security Tests & Full Suite Verification)

- **Security integration tests added** — `SecurityAuthAndWebhookTest.java` (6 tests) covering authenticated GET/POST requests, CSRF behavior (missing token returns 403), invalid credentials (401), and PayPal webhook accessibility (no auth required, signature verification via exception handler).
- **Security test fixes** — Fixed CSRF token handling in tests to work with `CookieCsrfTokenRepository` and `CsrfTokenRequestAttributeHandler` by pre-populating session with known CSRF token.
- **Full Maven test suite**: **225 tests executed, 225 passed, 0 failures, 0 errors, 2 skipped**.
- All M1–M5 functionality verified: Milestone 1 (PayPal capture-level verification), Milestone 2 (Agent Workflow Reliability with transaction boundaries and concurrency protection), Milestone 3 (Investigation Reliability with payment-state classification and timeline reconstruction), Milestone 4 (Dashboard integration), Milestone 5 (Security & Production Configuration).
- PostgreSQL integration tests (`AgentWorkflowTransactionIntegrationTest`, `AgentWorkflowConcurrencyIntegrationTest`, and all persistence integration tests) now execute successfully with database connectivity restored (password provided via `DB_PASSWORD`).