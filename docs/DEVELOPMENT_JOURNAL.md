# Development Journal

## 2026-10-16

- Read `Prompt.md` and confirmed the specified next implementation was the create-order flow, starting with its request DTO.
- Added `CreateOrderRequest` with reference, amount, and currency validation.
- Added `MerchantOrderService`, duplicate-reference exception, and two Mockito unit tests.
- Added `POST /api/orders`, an immutable response record, and a duplicate-reference Problem Details handler.
- Added three standalone MockMvc tests for successful response mapping, request validation, and duplicate-conflict mapping.
- Added a PostgreSQL persistence integration test that flushes and reloads an order inside a rollback test transaction.
- All six focused tests passed with a Java 17 compiler override. A normal Java 21 compile is blocked by the active Java 17 installation.
- PostgreSQL connection, existing schema compatibility, and order insert/reload were verified; test data was rolled back.
- Added local payment-attempt tracking: validated provider metadata, `CREATED` status, order-derived amount/currency, missing-order 404, and response DTO.
- Added service, MockMvc, and rollback-based PostgreSQL integration tests for payment attempts.
- Added explicit unit coverage that multiple attempt records are allowed for one order.
- The combined order and payment-attempt test set passed (13/13) with the Java 17 compiler override.
- No PayPal call or charge is performed by the payment-attempt endpoint.
- Next: agree on the next independent MVP feature before implementation.

## 2026-10-17

- Resumed from the documented handoff and reviewed the existing `PaymentEvent` mapping and repository.
- Agreed to implement simulated event recording only; required provider event IDs; repeat submissions return the original stored event with HTTP 200.
- Added `RecordPaymentEventRequest`/`Response`, `PaymentEventService`, `PaymentEventController`, and missing-attempt/payload exceptions.
- New events derive provider from the attempt and are saved as `SIMULATED`, `NOT_APPLICABLE`, `RECEIVED`; no payment/order state changes or provider calls occur.
- Payloads are serialized as JSON and capped at 64 KiB. Event data is not logged.
- Added PostgreSQL transaction advisory locking by provider/event ID and a partial unique index migration. Before applying the index, the opt-in migration test confirmed there were no duplicate non-null event keys; the migration then applied and verified the index.
- Added the Boot 4 Jackson starter after compilation showed the existing webmvc setup did not provide Jackson classes to production compilation.
- Added event service, MockMvc, PostgreSQL persistence/idempotency, and opt-in migration tests.
- Full suite passed: 24 tests, 0 failures, 0 errors, 0 skipped using `.\mvnw.cmd -q '-Djava.version=17' '-Dretryguard.apply-payment-event-idempotency-migration=true' test`.
- Java 21 remains unavailable as the active toolchain; the project still targets Java 21 and tests used a temporary version override.
- Next: discuss and approve the next independent task; verified provider webhooks and event processing remain unimplemented.
- Added read-only `GET /api/orders/{orderId}/timeline`; service gathers order, attempts, and linked events then sorts entries by their timestamps.
- Timeline tests passed for ordering, no-attempt and unknown-order cases, HTTP mapping, payload omission, and PostgreSQL reconstruction in rollback.
- Timeline keeps event provenance/verification/processing labels, does not expose payload JSON, and labels attempt status as current rather than historical.
- Equal timestamps are ordered deterministically by entry type and record ID.
- Final complete test suite after timeline implementation passed: 30 tests, 0 failures, 0 errors, 0 skipped using the Java 17 override and enabled migration test.
- Next: discuss and agree on the next independent feature before changing code.

### Later session: PayPal Sandbox order and capture

- Added separate explicit PayPal Sandbox order-creation and capture endpoints for existing `PAYPAL_SANDBOX` attempts. The PayPal host is fixed to the Sandbox Orders v2 API.
- Amount and currency are derived from persisted attempt/order data. Create and capture use separate deterministic `PayPal-Request-Id` values derived from the attempt UUID.
- OAuth credentials are optional environment properties (`PAYPAL_CLIENT_ID`, `PAYPAL_CLIENT_SECRET`); none were available, and no external provider calls were made.
- Successful provider responses persist PayPal order ID, capture ID, provider status, and safe local status mapping. Provider HTTP requests are outside transactions; persistence occurs in subsequent transactions. Errors are sanitized and do not claim a payment failed.
- Added additive V2 SQL for provider capture-reference/status columns. It was not applied to a database.
- Targeted mocked HTTP/service command passed 10 tests. All 34 explicitly selected non-database tests passed under Java 17 override.
- Full suite attempt comprised 40 tests: 0 assertion failures, 5 DB/application-context errors, and 1 opt-in migration test skipped. JDBC metadata could not be obtained with `DB_PASSWORD` unset; database-backed checks also require V2 to be applied.
- No verified webhook, automatic retry/refund, or investigation logic was added. PayPal Sandbox end-to-end behavior remains unverified.

### Later session: verified PayPal Sandbox webhooks

- Implemented `POST /api/webhooks/paypal` using the five actual PayPal transmission headers and the configured `PAYPAL_WEBHOOK_ID`. The original request JSON string is preserved through verification; its decoded JSON object, headers, and webhook ID are sent to the fixed official Sandbox `/v1/notifications/verify-webhook-signature` endpoint using OAuth.
- No event persistence or attempt mutation occurs until PayPal returns `SUCCESS`. Missing headers, malformed/non-object JSON, and bodies over 64 KiB fail before provider calls; invalid signatures return 401 and sanitized PayPal failures return safe 502/503 responses.
- A separate transactional processor uses the existing advisory lock and unique provider-event index after external verification. Verified duplicates return duplicate; simulated key collisions return 409 and are never treated as trusted deliveries.
- Provider events are stored as verified evidence. Only the exact capture completed/pending/denied event types can set success/pending/failure, and only when one existing PayPal attempt matches by capture or order reference. Unknown, unmatched, and ambiguous evidence does not change payment status. Terminal states are protected from contradictory pending/denial regressions.
- No schema migration, DB mutation, secret, or live PayPal call was introduced. JSONB can normalize serialized whitespace/key ordering. Sandbox credentials/webhook ID and DB credentials are absent.
- Targeted webhook command passed: `.\mvnw.cmd -q '-Djava.version=17' '-Dtest=PaypalClientTest,PaypalWebhookServiceTest,PaypalWebhookEventProcessorTest,PaypalWebhookControllerTest' test`. A broader database-independent regression selection passed all 49 tests. Tests mock PayPal and repositories; PostgreSQL concurrency and actual Sandbox delivery remain unverified.
- Updated API, architecture, schema, status, test plan, changelog, README, daily report, and review handoff.

### Later session: deterministic investigations

- Implemented `POST /api/orders/{orderId}/investigations` using deterministic rules version `1.0`; no request-controlled rule inputs, provider calls, AI calls, or payment actions are involved.
- The `RETRY_ELIGIBLE` guard requires at least one attempt and fresh (15-minute maximum) verified PayPal `PAYMENT.CAPTURE.DENIED` evidence for every `PAYPAL_SANDBOX` attempt currently in `FAILED`. Simulated/unverified events, pending, unknown, successful, missing, stale, or later evidence block eligibility. Conflicting verified outcomes/current order state return `RECONCILE`; all other uncertainty and consistent success return `WAIT`.
- Provider `create_time` is preferred for evidence time; event `received_at` is used only when that field is absent. Malformed or future provider times are not fresh.
- Every run persists a distinct sanitized JSON evidence snapshot and a `CREATED` history record with prior decision where available. The response omits raw payloads, provider references, and amounts. Existing tables suffice; no schema change was made.
- Added service and MockMvc coverage for decision rules, timestamp edge cases, conflict handling, not-found mapping, append-only audit writes, and safe response fields.
- `.\mvnw.cmd -q '-Djava.version=17' '-Dtest=InvestigationServiceTest,InvestigationControllerTest' test` passed 16 tests with no failures/errors/skips. No full-suite or PostgreSQL-backed investigation test was run.
- Expanded database-independent regression selection including investigation tests passed 65 tests; the exact command is recorded in `docs/TEST_PLAN.md`.
- Remaining roadmap work includes AI explanations (which may not override deterministic decisions), dashboard/demo deployment, and database-backed investigation persistence/full application-context verification.

### Later session: Gemini explanations for persisted investigations

- Implemented `POST /api/investigations/{investigationId}/explanation` using a small `ExplanationProvider` abstraction and Gemini REST adapter backed by Spring `RestClient`, Jackson 3, and no new dependency.
- `GEMINI_API_KEY` is read from the environment and sent in `x-goog-api-key`; model and v1beta endpoint are configurable with `GEMINI_MODEL` and `GEMINI_ENDPOINT`. No credentials were present and no live API request was made.
- The request projection includes only the already-persisted decision/reason/rules version and allowlisted state, provenance, verification, timestamp-source, freshness, trust, and threshold labels. Local IDs, exact timestamps, raw payloads, amounts, provider/payment references, and credentials are excluded.
- Provider call runs without a DB transaction; a separate short transactional service updates only `investigations.explanation`. Decision/reason/rules version/snapshot/history are unchanged. Response source explicitly distinguishes `GEMINI` from `DETERMINISTIC_FALLBACK`.
- Added narrow typed provider-unavailable/provider-failure/invalid-response handling, bounded nonblank output validation, action-directive rejection, and a safe deterministic fallback. Invalid or excessive evidence does not get sent to Gemini.
- No schema migration, dependency, database operation, automatic retry/payment action, or change to the existing 15-minute retry policy, PayPal status handling, or datasource credential hardening was made.
- Mocked tests cover valid sanitized request/response, missing key without HTTP, malformed/empty responses, HTTP provider failure, fallback/persistence ordering, output safety, decision immutability, and API mapping/not found.
- Focused tests passed: 11 tests. Expanded database-independent regression passed 78 tests using `.\mvnw.cmd -q '-Djava.version=17' ...`; exact selector is in `docs/TEST_PLAN.md`.
- PostgreSQL-backed explanation persistence, full application-context/Java 21 validation, and live Gemini API behavior remain unverified. Authentication, dashboard, and demo deployment remain incomplete.

## 2026-10-18

- Reviewed the existing deterministic investigation service, tests, response DTOs, and API documentation against the reported case with multiple attempts, absent events, a simulated pending event, and a legacy unsupported provider.
- Confirmed existing behavior was conservative: missing/untrusted evidence and any non-PayPal attempt prevent `RETRY_ELIGIBLE`; no decision-rule or response reason changes were needed.
- Added two `InvestigationServiceTest` regressions: one covers multiple eventless attempts plus a simulated `PAYMENT.PENDING` and an `INVALID_PROVIDER` attempt; the other verifies the unsupported attempt blocks fresh verified PayPal denial evidence from becoming retry eligible.
- Focused run passed 20 service/controller tests with Java 17 override using the isolated Maven build-directory command recorded in `docs/TEST_PLAN.md`. An initial run hit stale Java 21 class files in `target`; a Maven clean then could not remove a locked generated class directory. No database was reset, deleted, or modified.
- Updated the test plan, project status, and review handoff. PostgreSQL data/persistence was not inspected or modified; unsupported historical records are left intact and are not rewritten to bypass rules.
