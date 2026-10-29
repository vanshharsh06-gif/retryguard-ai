# Review Handoff

## Project goal

RetryGuard AI investigates uncertain payment outcomes to help prevent duplicate customer charges. Deterministic evidence-based logic must own recovery decisions; AI may explain but never override them.

## Architecture and stack

Java 21, Spring Boot, Maven, Spring MVC, Spring Data JPA, and PostgreSQL. Modular monolith rooted at `com.retryguard_ai`.

## Current MVP scope

Merchant-order creation, local payment-attempt tracking, simulated-event recording/idempotency, a read-only timeline, explicit PayPal Sandbox order creation/capture, verified PayPal Sandbox webhook ingestion, deterministic investigations, and optional Gemini explanations for persisted investigations are implemented. Authentication, dashboard, and demo deployment remain unimplemented.

## Verified work

- `CreateOrderRequest` contains Bean Validation constraints.
- `MerchantOrderService.createOrder` constructs and persists a `CREATED` order, after checking for a pre-existing merchant reference.
- `POST /api/orders` maps the persisted entity into `CreateOrderResponse` and returns HTTP 201.
- `GlobalExceptionHandler` maps the service duplicate exception to HTTP 409 Problem Details.
- `POST /api/orders/{orderId}/payment-attempts` creates a local `CREATED` record with provider metadata and order-derived amount/currency; it does not call a provider or charge.
- Missing orders map to HTTP 404 Problem Details. Multiple attempt rows are allowed for history.
- `POST /api/payment-attempts/{paymentAttemptId}/events` records simulated events as `SIMULATED`, `NOT_APPLICABLE`, and `RECEIVED`; it does not alter payment state.
- First event submissions return HTTP 201; repeats of `(provider, providerEventId)` return the original record unchanged with HTTP 200.
- Event payloads are limited to 64 KiB. PostgreSQL advisory locking and a partial unique index enforce event idempotency.
- The index migration was applied after preflight confirmed there were no duplicate non-null event IDs.
- `GET /api/orders/{orderId}/timeline` combines order, attempt, and event records chronologically. Payloads are omitted and event provenance/verification labels are preserved.
- Timeline status values are clearly current-state fields; historical state transitions are not stored.
- The full test suite passed: 30 tests, 0 failures/errors/skips, including service, MockMvc, persistence, and migration tests.
- `POST /api/payment-attempts/{paymentAttemptId}/paypal/order` and `/paypal/capture` use PayPal Orders v2 Sandbox only, require a `PAYPAL_SANDBOX` attempt, and take no payment amount, currency, or provider order ID from the caller.
- OAuth secrets come from `PAYPAL_CLIENT_ID` and `PAYPAL_CLIENT_SECRET`; no credentials were present or used. Mocked HTTP verifies request construction, status mapping, idempotency headers, and sanitized error handling.
- PayPal order/capture references and provider status persist in `payment_attempts`; provider calls are outside transactions and no retry/refund is automatic.
- Additive migration `V2__paypal_payment_references.sql` is required but was not applied to any database.
- `POST /api/webhooks/paypal` verifies actual PayPal transmission headers/event against the configured Sandbox webhook ID before entering the transactional processor.
- Verified webhook delivery uses existing advisory-lock/index idempotency. Exact capture completed/pending/denied events may update one uniquely matched attempt through stored order/capture references; unknown, ambiguous, or unmatched events never update payment state. Simulated events cannot be treated as verified duplicates.
- Webhook route and event processing use mocked provider/repository tests; no provider call, V2 migration, or database operation was performed. Configure `PAYPAL_WEBHOOK_ID` as well as OAuth credentials before enabling webhook verification.
- Webhook implementation files: `PaypalWebhookController`, `PaypalWebhookResponse`, `PaypalWebhookTransmission`, `PaypalWebhookService`, `PaypalWebhookEventProcessor`, `PaypalWebhookProcessingResult`, webhook exceptions, corresponding service/controller tests, and expanded `PaypalClientTest`.
- `POST /api/orders/{orderId}/investigations` runs deterministic rules version `1.0` and returns a DTO with the decision and sanitized evidence snapshot. `RETRY_ELIGIBLE` is gated on fresh verified PayPal terminal denial evidence for every failed PayPal attempt; freshness is 15 minutes, uses provider `create_time` when present, and falls back to event receipt time only when absent. Malformed timestamps cannot authorize retry. Uncertainty returns `WAIT`; conflicts return `RECONCILE`; consistent success is `WAIT`/`PAYMENT_CONFIRMED`.
- Each investigation run creates a distinct `investigations` row and an `investigation_history` `CREATED` row; the previous decision is recorded if available. No schema migration, provider/AI call, automatic charge, retry, capture, or refund was added. The snapshot excludes raw payloads and payment/provider references.
- Investigation implementation files: `InvestigationController`, `InvestigationService`, `InvestigationResponse`, `InvestigationServiceTest`, and `InvestigationControllerTest`.
- Added service regression tests for multiple attempts with no events, a simulated pending event, a legacy unsupported provider, and an unsupported provider blocking otherwise fresh verified denial evidence. Investigation service/controller selection now passes 20 tests; no production decision logic or API reason codes changed. Exact command is in `docs/TEST_PLAN.md`.
- `POST /api/investigations/{investigationId}/explanation` reads an existing investigation and uses Gemini only for explanation text. Response includes unchanged decision/reason/rules version plus explanation source (`GEMINI` or `DETERMINISTIC_FALLBACK`). Input is a minimized allowlisted state/provenance/freshness summary; no IDs, timestamps, raw payloads, payment/provider references, amounts, or secrets are sent.
- Gemini config is environment-based: `GEMINI_API_KEY`, optional `GEMINI_MODEL` (default `gemini-2.5-flash`) and `GEMINI_ENDPOINT` (default Gemini v1beta). No credential was present. External calls run outside transactions, and a separate short transaction writes only `investigations.explanation`; no schema migration is needed. Invalid/unavailable output uses an explicit deterministic fallback. Existing retry rules, PayPal behavior, and datasource credential hardening are unchanged.
- Gemini implementation files: `ai/ExplanationProvider`, `ai/GeminiExplanationProvider`, `ai/GeminiProperties`, `service/InvestigationExplanationService`, `service/InvestigationExplanationPersistenceService`, explanation DTO/controller, and their mocked tests.

## Files added

- `src/main/java/com/retryguard_ai/dto/CreateOrderRequest.java`
- `src/main/java/com/retryguard_ai/dto/CreateOrderResponse.java`
- `src/main/java/com/retryguard_ai/service/MerchantOrderService.java`
- `src/main/java/com/retryguard_ai/controller/MerchantOrderController.java`
- `src/main/java/com/retryguard_ai/exception/MerchantOrderAlreadyExistsException.java`
- `src/main/java/com/retryguard_ai/exception/GlobalExceptionHandler.java`
- `src/test/java/com/retryguard_ai/service/MerchantOrderServiceTest.java`
- `src/test/java/com/retryguard_ai/controller/MerchantOrderControllerTest.java`
- `src/test/java/com/retryguard_ai/service/MerchantOrderPersistenceIntegrationTest.java`
- `src/main/java/com/retryguard_ai/dto/CreatePaymentAttemptRequest.java`
- `src/main/java/com/retryguard_ai/dto/CreatePaymentAttemptResponse.java`
- `src/main/java/com/retryguard_ai/service/PaymentAttemptService.java`
- `src/main/java/com/retryguard_ai/controller/PaymentAttemptController.java`
- `src/main/java/com/retryguard_ai/exception/MerchantOrderNotFoundException.java`
- `src/test/java/com/retryguard_ai/service/PaymentAttemptServiceTest.java`
- `src/test/java/com/retryguard_ai/controller/PaymentAttemptControllerTest.java`
- `src/test/java/com/retryguard_ai/service/PaymentAttemptPersistenceIntegrationTest.java`
- `src/main/java/com/retryguard_ai/dto/RecordPaymentEventRequest.java`
- `src/main/java/com/retryguard_ai/dto/RecordPaymentEventResponse.java`
- `src/main/java/com/retryguard_ai/controller/PaymentEventController.java`
- `src/main/java/com/retryguard_ai/service/PaymentEventService.java`
- `src/main/java/com/retryguard_ai/service/PaymentEventCreationResult.java`
- `src/main/java/com/retryguard_ai/exception/PaymentAttemptNotFoundException.java`
- `src/main/java/com/retryguard_ai/exception/InvalidPaymentEventPayloadException.java`
- `src/main/resources/db/migration/V1__payment_event_idempotency.sql`
- `src/test/java/com/retryguard_ai/controller/PaymentEventControllerTest.java`
- `src/test/java/com/retryguard_ai/service/PaymentEventServiceTest.java`
- `src/test/java/com/retryguard_ai/service/PaymentEventPersistenceIntegrationTest.java`
- `src/test/java/com/retryguard_ai/service/PaymentEventIdempotencyMigrationTest.java`
- `src/main/java/com/retryguard_ai/dto/OrderTimelineEntry.java`
- `src/main/java/com/retryguard_ai/dto/OrderTimelineResponse.java`
- `src/main/java/com/retryguard_ai/controller/PaymentTimelineController.java`
- `src/main/java/com/retryguard_ai/service/PaymentTimelineService.java`
- `src/test/java/com/retryguard_ai/controller/PaymentTimelineControllerTest.java`
- `src/test/java/com/retryguard_ai/service/PaymentTimelineServiceTest.java`
- `src/test/java/com/retryguard_ai/service/PaymentTimelinePersistenceIntegrationTest.java`
- `src/main/java/com/retryguard_ai/paypal/PaypalClient.java` and related PayPal config/result classes
- `src/main/java/com/retryguard_ai/controller/PaypalPaymentController.java`
- `src/main/java/com/retryguard_ai/service/PaypalPaymentService.java` and `PaypalPaymentPersistenceService.java`
- `src/main/java/com/retryguard_ai/dto/PaypalPaymentResponse.java`
- `src/main/resources/db/migration/V2__paypal_payment_references.sql`
- `src/test/java/com/retryguard_ai/paypal/PaypalClientTest.java`
- `src/test/java/com/retryguard_ai/service/PaypalPaymentServiceTest.java`
- `src/test/java/com/retryguard_ai/service/PaypalPaymentPersistenceServiceTest.java`
- `docs/daily-reports/2026-10-17.md`
- `pom.xml`: added the Spring Boot Jackson starter.
- `README.md` and the technical/status files under `docs/`.

## Verification and limitations

- `.\mvnw.cmd -q '-Djava.version=17' '-Dretryguard.apply-payment-event-idempotency-migration=true' test` passed (24 tests total).
- After timeline changes, the same full-suite command passed (30 tests total).
- PayPal focused command passed 10 mocked/service tests: `.\mvnw.cmd -q '-Djava.version=17' '-Dtest=PaypalClientTest,PaypalPaymentServiceTest,PaypalPaymentPersistenceServiceTest' test`.
- Explicit non-database selection passed all 34 tests. Current full suite attempt (40 tests) had 0 failures, 5 errors loading DB-dependent application contexts, and 1 opt-in migration test skipped; `DB_PASSWORD` was unset and V2 was unapplied.
- The default Java 21-targeted compile failed with `release version 21 not supported`.
- PostgreSQL duplicate-key preflight found no duplicate non-null event keys; the partial unique index was applied and verified.
- MockMvc tests use mocked services; PostgreSQL tests verified persistence and repeat-event behavior in rollback transactions.
- Full HTTP-to-PostgreSQL and parallel-load event idempotency tests were not executed.
- Webhook-specific mocked verification/status/idempotency tests passed via `.\mvnw.cmd -q '-Djava.version=17' '-Dtest=PaypalClientTest,PaypalWebhookServiceTest,PaypalWebhookEventProcessorTest,PaypalWebhookControllerTest' test`; the broader database-independent selection passed 49 tests. Actual PayPal Sandbox delivery and concurrent DB webhook processing remain unverified. The previously documented full-suite DB-context errors predate webhook additions; full suite was not rerun.
- Investigation unit/controller command passed 18 tests: `.\mvnw.cmd -q '-Djava.version=17' '-Dtest=InvestigationServiceTest,InvestigationControllerTest' test`. This uses mocked repositories; investigation snapshot/history persistence and full application-context validation against PostgreSQL remain unverified. No new full-suite run was performed.
- After adding two multi-attempt/legacy-provider regressions, the focused investigation service/controller selection passed 20 tests using the isolated Maven build-directory command in `docs/TEST_PLAN.md`. No service decision logic changed.
- The expanded database-independent regression selection including all investigation tests passed 67 tests; see `docs/TEST_PLAN.md` for the exact command. This does not validate database-backed investigation persistence.
- Gemini-focused mocked HTTP/provider/service/controller selection passed 11 tests; expanded database-independent regression passed 78 tests using Java 17 override. Exact commands and selector are in `docs/TEST_PLAN.md`.
- No live Gemini API, PostgreSQL-backed explanation persistence, or full application-context validation was run. No credentials or schema migration were used. Project still targets Java 21 although this environment uses Java 17.

## Risks and decisions

- New orders start in `CREATED`; creation is not payment confirmation.
- The merchant-order duplicate pre-check can race; its database uniqueness remains authoritative, but a concurrent constraint exception is not yet translated to HTTP 409.
- Simulated event data is untrusted and cannot drive payment state. Investigation retry eligibility additionally requires all attempts be failed with fresh verified provider denial evidence. Webhook verification only supports PayPal Sandbox and requires the configured webhook ID; there is no live verification or authentication/authorization layer in this application.
- PayPal behavior is mocked-HTTP tested only; do not claim end-to-end Sandbox validation or production payment readiness.
- Datasource configuration and secrets require careful local handling; no credentials belong in documentation.

## Remaining work

Remaining work includes authentication/authorization, dashboard/demo deployment, and PostgreSQL-backed investigation/explanation persistence plus full application-context/Java 21 validation. Before database-backed testing, configure the intended DB credentials and apply V2 only to the intended database/schema; do not perform destructive database operations. Gemini remains mocked-only until explicit credentials are made available; do not claim live or end-to-end validation.
