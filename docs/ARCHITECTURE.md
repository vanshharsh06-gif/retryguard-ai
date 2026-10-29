# Architecture

RetryGuard AI is a modular monolith built with Java 21, Spring Boot, Spring MVC, Spring Data JPA, and PostgreSQL.

## Current order-creation flow

1. `MerchantOrderController` accepts an HTTP request and applies Jakarta Bean Validation to `CreateOrderRequest`.
2. `MerchantOrderService` checks for an existing merchant reference, maps the request into a `MerchantOrder`, sets its initial status to `CREATED`, and saves it inside a transaction.
3. The controller maps the saved entity to `CreateOrderResponse`; the JPA entity is not serialized as the API response.
4. `GlobalExceptionHandler` maps known duplicate-reference exceptions to an RFC-style Problem Details response with HTTP 409.
5. `GET /api/orders` returns at most the 50 newest orders as response DTOs for the browser dashboard.

## Payment-attempt tracking flow

1. `PaymentAttemptController` accepts `POST /api/orders/{orderId}/payment-attempts` and validates `CreatePaymentAttemptRequest`.
2. `PaymentAttemptService` loads the merchant order, copies its amount and currency, records the caller-supplied provider label, sets status to `CREATED`, and persists the attempt transactionally.
3. `CreatePaymentAttemptResponse` exposes attempt data without returning the JPA entity. Missing orders are mapped to HTTP 404.
4. This flow only records a local row. It does not invoke a provider, create a provider payment reference, or initiate a charge.

## Simulated payment-event flow

1. `PaymentEventController` accepts `POST /api/payment-attempts/{paymentAttemptId}/events`; Bean Validation requires a provider event ID, event type, and JSON object payload.
2. `PaymentEventService` loads the attempt and uses its provider label, serializes a payload of at most 64 KiB, and acquires a PostgreSQL transaction advisory lock for `(provider, providerEventId)`.
3. The service checks for an existing event with that key. If one exists, it returns the original record unchanged. Otherwise it stores a `SIMULATED` event with `NOT_APPLICABLE` verification and `RECEIVED` processing status.
4. The controller returns 201 for a new event and 200 for a duplicate delivery. An additive partial unique index protects the same key in PostgreSQL.
5. This endpoint never verifies a provider signature, marks a payment as successful, processes the event, or updates order/attempt state. Its rows are not trusted provider evidence.

## Payment timeline flow

1. `PaymentTimelineController` accepts `GET /api/orders/{orderId}/timeline`.
2. `PaymentTimelineService` verifies the order exists, loads its attempts ordered by creation time, and loads linked events ordered by receipt time.
3. It maps the order, attempts, and events into timeline DTO entries and sorts them by occurrence timestamp.
4. Event source, verification, and processing states are included, but raw payloads are excluded.
5. An attempt's status is its current persisted status at read time, not a historical state at the attempt's creation timestamp. Status-change history is not currently recorded.

## PayPal Sandbox order and capture flow

1. `PaypalPaymentController` exposes separate explicit create-order and capture actions for an existing `PAYPAL_SANDBOX` attempt.
2. `PaypalPaymentService` loads the attempt with its merchant order and validates the provider and current workflow state. The caller cannot supply amount, currency, or provider order ID.
3. `PaypalClient` obtains a Sandbox OAuth token and makes Orders v2 requests against the fixed Sandbox host. It derives amount/currency from the persisted attempt and sends deterministic operation-specific `PayPal-Request-Id` values based on the attempt UUID.
4. Provider calls are made without a surrounding transaction. `PaypalPaymentPersistenceService` starts a separate transaction after a successful response to validate the returned order ID, capture amount, and currency against the attempt before saving the PayPal capture reference and mapped local payment status. Capture persistence locks the attempt row; a completed capture marks both the attempt `SUCCEEDED` and its merchant order `PAID`. The response is also recorded as a separate `PAYMENT.CAPTURE.RESPONSE` timeline event with `verification_status=NOT_APPLICABLE`.
5. Provider errors are converted to generic safe Problem Details; response bodies and credentials are neither logged nor returned. A transport failure does not prove that PayPal failed the operation.
6. Create-order stores the PayPal order ID and returns its approval URL. Capture uses only that stored ID; mismatched IDs, amounts, or currencies are rejected without changing payment state. A weaker capture response cannot downgrade a successful or terminal attempt.

The configured integration is Sandbox-only (`https://api-m.sandbox.paypal.com`). It does not automatically retry or refund. Database columns for capture reference and provider status are introduced by additive migration `V2__paypal_payment_references.sql`; as there is no general migration runner, operators must apply the SQL to a target schema before starting with Hibernate schema validation.

## PayPal verified webhook flow

1. `PaypalWebhookController` accepts `/api/webhooks/paypal`, the original JSON request text, and the five PayPal transmission headers. Missing headers, malformed/non-object JSON, or bodies over 64 KiB are rejected before provider or repository calls.
2. `PaypalWebhookService` parses the event without acting on it, obtains a Sandbox OAuth token, and submits the parsed event, actual transmission headers, and `PAYPAL_WEBHOOK_ID` to PayPal's official `/v1/notifications/verify-webhook-signature` endpoint. The outbound API call is outside database transactions.
3. Only an explicit `verification_status=SUCCESS` allows event processing. Other signature results return 401 without persistence; provider communication errors are sanitized. No secrets or payloads are logged.
4. `PaypalWebhookEventProcessor` opens a transaction, locks `(PAYPAL_SANDBOX, eventId)` using the existing PostgreSQL advisory lock, and checks the existing unique-index key. Only an existing verified provider event is a duplicate; a simulated collision is not considered trusted evidence and returns a safe conflict response. The attempt row is also locked while applying an event so different deliveries cannot race its state.
5. Verified event payloads are stored as provider/verified. Completed-capture events must resolve to one attempt whose order and capture IDs agree and whose amount and currency match the persisted attempt. The capture ID from a valid event is retained on the attempt. Unknown references remain received; ambiguous or mismatched references/money are retained as failed processing and cannot change payment state.
6. Exact known capture events map completed to succeeded, pending to pending, and denied to failed. A completed event marks the related merchant order `PAID`; weaker events cannot downgrade a successful attempt. Successfully mapped events are marked processed. Simulated events remain untrusted and are not processed by this flow.

The webhook uses existing event columns and the existing idempotency index; it needs the existing V2 reference columns for useful attempt correlation, but introduces no additional migration. PayPal client ID, client secret, webhook ID, and database password are supplied through environment variables rather than configuration defaults. PostgreSQL JSONB preserves the event structure, not its original textual formatting.

## Deterministic investigation flow

1. `InvestigationController` accepts `POST /api/orders/{orderId}/investigations`; no request body can influence the decision rules.
2. `InvestigationService` loads the order, all attempts, and only events associated with those attempts in a repeatable-read transaction so the audit snapshot uses a consistent database view. It evaluates events as trusted only when provider is `PAYPAL_SANDBOX`, source is `PROVIDER`, and verification is `VERIFIED`.
3. Rules version `1.0` requires every attempt to be failed and supported by fresh terminal PayPal capture-denial evidence (15-minute threshold) before returning `RETRY_ELIGIBLE`. Provider `create_time` is preferred; missing time falls back to event receipt time, while malformed provider time remains unknown/stale.
4. Conflicting verified outcomes/current order state return `RECONCILE`; this includes provider completion while the order is not `PAID`, a `PAID` order with only denial evidence, and a pending order when every attempt is terminally denied. Missing, unverified, pending, unknown, stale, and successful-but-nonconflicting evidence return `WAIT`. The service has no provider, charge, retry, refund, or AI client dependency.
5. Each invocation serializes a sanitized structured evidence snapshot and evidence-linked findings into a new `investigations` row and writes an `investigation_history` row with action `CREATED` and the prior decision when present. Findings are deterministic, only emitted when supported by recorded state/evidence, include uncertainty for unresolved causes, and carry non-executing operator guidance. Runs are append-only; raw event payloads, provider references, and payment amounts are omitted.
6. The response uses DTOs rather than exposing JPA entities. Snapshot event IDs are local record UUIDs, and provider payload fields are not exposed.
7. `GET /api/orders/{orderId}/investigations` lists at most 50 runs newest-first; `GET /api/investigations/{investigationId}` retrieves a persisted run; and `GET /api/investigations/{investigationId}/history` returns audit rows oldest-first. Reads do not rerun rules or mutate earlier runs.

Findings are stored as JSONB in `investigations.findings`, added by additive migration V3 with a default empty array. Existing investigations remain intact. The dashboard is plain HTML/CSS/JavaScript in `src/main/resources/static/index.html` and calls these APIs directly; there is no separate frontend service or build.

The implementation reuses existing investigation tables and requires no schema migration. Database-independent service and controller tests cover conservative decisions, timestamp handling, audit row creation, not-found behavior, and payload redaction; database persistence and full application startup remain separately unverified for this feature.

## Gemini investigation explanation flow

1. `InvestigationExplanationController` accepts `POST /api/investigations/{investigationId}/explanation` with no request body. The service reads the persisted investigation and does not call `InvestigationService` or re-evaluate rules.
2. `InvestigationExplanationService` converts the persisted snapshot and findings into an allowlisted `ExplanationInput`: decision/reason/rules version, order/payment statuses, event type/provenance/verification labels, timestamp source, freshness/trust flags, finding codes/categories/severities/uncertainty and evidence types, and the 15-minute threshold only. It omits IDs, exact timestamps, raw payloads, free-text finding/recommendation text, references, and amounts. Unexpected values are reduced to `OTHER`; excessive or invalid evidence falls back without an external call.
3. `GeminiExplanationProvider` sends a constrained prompt to the configurable Gemini generate-content endpoint using `GEMINI_API_KEY` in `x-goog-api-key`. The system instruction makes the persisted deterministic decision authoritative, prohibits unsupported inferences and payment-action recommendations, and requests explanation text only.
4. The HTTP call runs outside any DB transaction. Narrowly typed unavailable/provider/invalid-response failures and invalid or unsafe output select an explicit deterministic fallback. Output must be nonblank, at most 1,200 characters, and not contain recognized action directives.
5. `InvestigationExplanationPersistenceService` starts a short transaction after the provider call and updates only `investigations.explanation`; it does not alter the decision, reason, rules version, snapshot, or history. The response includes the unchanged persisted decision fields and source `GEMINI` or `DETERMINISTIC_FALLBACK`.

The feature reuses the existing nullable explanation column; no migration or payment-provider action was introduced. Tests use mocked HTTP/providers and repositories. Gemini credentials were absent, so no live call or end-to-end validation was performed.

## Package responsibilities

- `controller`: HTTP routing, validation boundary, and response mapping.
- `dto`: request and response contracts.
- `service`: application/business operations.
- `repository`: Spring Data persistence interfaces.
- `entity`: JPA persistence mappings.
- `exception`: application exceptions and HTTP exception mapping.

The deterministic investigation engine determines recovery decisions from evidence before the separate Gemini explanation action runs. Gemini can only explain the persisted outcome; it cannot change a decision or invoke payment operations. The payment provider adapter remains PayPal Sandbox Orders v2 plus verified Sandbox webhooks.

## Dashboard flow and limits

The static dashboard is served at `/` by Spring Boot. It reads recent orders, timeline records, investigations, and history from the REST API; all result records come from PostgreSQL-backed application endpoints. Creating an order/attempt is separate from PayPal. PayPal order creation and capture require explicit user clicks, and capture has a confirmation prompt. The dashboard does not generate events or claim success itself. Server credentials are never delivered to the page. Authentication/authorization is not implemented; only use this demo behind trusted infrastructure.
