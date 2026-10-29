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
- Added mocked HTTP/provider/service/controller tests for valid and sanitized Gemini requests, missing key, malformed/empty/provider failures, fallback and output filtering, persistence ordering/update-only behavior, unchanged decision, and 404. Focused selection passed 11 tests; expanded database-independent selection passed 78 tests with the Java 17 override.
- No Gemini credential was present; no live call, database-backed explanation persistence, or full-context validation was run.

## 2026-10-18

- Added deterministic investigation regression tests for multi-attempt cases with absent event evidence, a simulated `PAYMENT.PENDING` event, and a legacy `INVALID_PROVIDER` attempt.
- Added a regression proving an unsupported historical provider prevents `RETRY_ELIGIBLE` even when a separate PayPal attempt has fresh verified denial evidence. No production rules, API response fields, or reason codes changed.
- Focused investigation service/controller selection passed 20 tests with the Java 17 override; database state was not accessed or modified.
