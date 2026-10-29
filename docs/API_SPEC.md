# API Specification

The interactive Swagger UI is available at `http://localhost:8080/swagger-ui/index.html` while the application is running. The generated OpenAPI JSON is at `http://localhost:8080/v3/api-docs`.

## Create merchant order

`POST /api/orders`

Creates a merchant order record. It does not initiate, authorize, or capture a payment.

### Request

```json
{
  "merchantOrderReference": "merchant-ref-1",
  "amount": 19.95,
  "currency": "USD"
}
```

Validation:

- `merchantOrderReference`: required, nonblank, maximum 100 characters.
- `amount`: required, greater than zero, up to 10 integer digits and 2 fractional digits.
- `currency`: required, exactly three uppercase letters. The pattern does not confirm that it is an assigned ISO currency.

### Success response

Returns `201 Created` with a `CreateOrderResponse` body containing `id`, `merchantOrderReference`, `amount`, `currency`, `orderStatus`, and `createdAt`. New orders have status `CREATED`.

### Errors

- `400 Bad Request` for invalid request JSON or validation failures (Spring MVC default error response).
- `409 Conflict` when the service finds an existing merchant order reference; the response uses Problem Details.

MockMvc controller tests cover the 201 response, validation rejection, and 409 mapping with a mocked service. These do not exercise the complete Spring application or PostgreSQL. The duplicate pre-check does not eliminate a concurrent insert race; the database unique constraint remains authoritative.

## List recent merchant orders

`GET /api/orders`

Returns up to 50 most recently created orders as `CreateOrderResponse` DTOs, newest first. This bounded read endpoint supports the dashboard and returns an empty array when there are no orders. It does not include payment events or provider payloads.

## Create a local payment-attempt record

`POST /api/orders/{orderId}/payment-attempts`

Creates an audit/tracking row linked to an existing merchant order. This endpoint does not call PayPal or any other provider and does not charge a customer. Multiple rows are allowed for the same order.

### Request

```json
{
  "provider": "PAYPAL_SANDBOX"
}
```

`provider` is required, nonblank metadata with a maximum length of 30 characters. The service copies `amount` and `currency` from the referenced order; clients cannot supply a conflicting amount or initial status. New attempt records start in `CREATED`, and `providerPaymentReference` remains unset.

### Success response

Returns `201 Created` with `CreatePaymentAttemptResponse` containing the attempt ID, order ID, provider, amount, currency, `paymentStatus`, provider payment reference, and creation timestamp.

### Errors

- `400 Bad Request` for invalid JSON or a blank/oversized provider label.
- `404 Not Found` when the merchant order does not exist; the response uses Problem Details.

MockMvc tests cover response mapping, request validation, and missing-order handling with a mocked service. A separate PostgreSQL integration test verifies persistence and the order relationship in a rollback transaction. Creating this local tracking row does not initiate a provider action; PayPal actions are separate explicit endpoints described below.

## Record a simulated payment event

`POST /api/payment-attempts/{paymentAttemptId}/events`

Stores a local simulated event linked to an existing attempt. This is not a webhook endpoint: it does not authenticate or verify a payment provider, change payment status, or trigger any payment action.

### Request

```json
{
  "providerEventId": "event-123",
  "eventType": "PAYMENT.PENDING",
  "payload": {
    "state": "pending",
    "simulated": true
  }
}
```

Validation:

- `providerEventId`: required, nonblank, maximum 200 characters.
- `eventType`: required, nonblank, maximum 100 characters.
- `payload`: required JSON object, maximum 65,536 UTF-8 bytes after serialization.
- The provider is inherited from the payment attempt; it is not accepted from the request.
- Do not include secrets, card data, or unnecessary personal/payment data; the JSON payload is stored in PostgreSQL.

### Success responses

- `201 Created` for a new event.
- `200 OK` when the same `(provider, providerEventId)` is delivered again. The original record and payload are returned unchanged; the duplicate request does not overwrite the first event.

The record is initialized with `sourceType=SIMULATED`, `verificationStatus=NOT_APPLICABLE`, and `processingStatus=RECEIVED`. Neither the request nor its stored event is verified provider evidence.

### Errors

- `400 Bad Request` for invalid request fields or a payload larger than 64 KiB.
- `404 Not Found` when `paymentAttemptId` does not exist; response uses Problem Details.

The endpoint uses a transaction-scoped PostgreSQL advisory lock and the partial unique index on `(provider, provider_event_id)` for idempotency. MockMvc tests cover HTTP behavior; PostgreSQL integration tests cover persistence and repeat delivery. Parallel-load behavior has not yet been separately stress-tested.

Authentication and authorization are not implemented in the current application. Keep these demo endpoints on a trusted local network; do not expose them publicly.

## Get an order payment timeline

`GET /api/orders/{orderId}/timeline`

Returns a read-only chronological reconstruction from the persisted order, payment-attempt, and payment-event records. It does not run an investigation or infer whether payment succeeded.

### Response

Returns `200 OK` with:

- `orderId`, `merchantOrderReference`, and `currentOrderStatus`.
- `entries`, ordered by each record's creation/receipt timestamp.
- Each entry has an ID, `entryType`, `occurredAt`, and relevant provider/status/event fields. Non-applicable fields are `null`.

Entry types are `ORDER_CREATED`, `PAYMENT_ATTEMPT_CREATED`, and `PAYMENT_EVENT_RECEIVED`. Event entries include `sourceType`, `verificationStatus`, and `processingStatus`. Raw event payloads are intentionally omitted. Attempt entries expose `currentStatus`, which is the status at read time, not a historical snapshot at creation. The schema does not record payment-status transitions.

An order with no attempts returns its order-creation entry and an empty event set. An unknown order returns `404 Not Found` with Problem Details. MockMvc and PostgreSQL-backed tests verify response mapping, ordering, status labels, and persistence reconstruction.

## Run a deterministic payment investigation

`POST /api/orders/{orderId}/investigations`

Runs the versioned deterministic rules against the order, every linked attempt, and only events linked to those attempts. Every request creates a new immutable `investigations` record and an `investigation_history` row with action `CREATED`; prior snapshots are not overwritten. The history row records the prior investigation's decision when one exists.

### Response

Returns `200 OK` with the investigation ID, order ID, decision, stable reason code, rules version (`1.0`), explanation, timestamp, a structured evidence snapshot, and a `findings` array. Evidence lists each attempt and linked event's identifiers, provider/status/provenance/verification labels, received time, provider `create_time` when present, effective evidence time, timestamp source, freshness, and whether it is trusted PayPal evidence. Raw event payloads, provider payment/capture references, amounts, and other event fields are not returned or copied into the snapshot.

Each finding has a stable `code`, title, description, `rootCauseCategory`, severity (`INFO`, `LOW`, `MEDIUM`, `HIGH`), evidence references (`ORDER`, `PAYMENT_ATTEMPT`, or `PAYMENT_EVENT` plus the persisted record ID), a non-executing `recommendedAction`, and `rootCauseUncertain`. Findings are emitted only when supported by the persisted state/evidence. Supported codes include `PROVIDER_DENIAL`, `PENDING_PAYMENT`, `CONFLICTING_OUTCOMES`, `STALE_EVIDENCE`, `UNVERIFIED_EVIDENCE`, `MISSING_EVIDENCE`, and `PAYMENT_CONFIRMED`. The rules avoid asserting the cause of a denial or delay when the evidence does not establish it. Findings do not alter the decision or authorize a payment operation.

Example finding:

```json
{
  "code": "PROVIDER_DENIAL",
  "title": "Verified provider denial",
  "description": "PayPal reported a capture denial for this attempt. The event confirms the outcome but does not establish the underlying cause.",
  "rootCauseCategory": "PROVIDER_DENIAL",
  "severity": "HIGH",
  "evidence": [{"type": "PAYMENT_EVENT", "id": "b8654333-10bc-40ec-86e9-0b816fe990a1"}],
  "recommendedAction": "Review the verified provider event and merchant records before deciding on any next step.",
  "rootCauseUncertain": true
}
```

### Decision policy

- `RETRY_ELIGIBLE` is returned only when there is at least one attempt and **every** attempt is `PAYPAL_SANDBOX`/`FAILED`, has linked `PAYMENT.CAPTURE.DENIED` evidence with source `PROVIDER` and verification `VERIFIED`, and that evidence is no more than 15 minutes old. No unverified/simulated event, pending or unknown outcome, or later event may remain for an attempt.
- A merchant order with verified completion evidence but a status other than `PAID` returns `RECONCILE`. A `PAID` order with denial evidence but no verified completion also returns `RECONCILE`. A `PENDING` order whose attempts are all terminally denied requires reconciliation; pending or cancelled orders without contradictory terminal evidence return `WAIT`. A failed earlier attempt followed by a separately completed attempt is consistent success and returns `WAIT`.
- Provider `create_time` is preferred for freshness. If that field is absent, the event's `received_at` is used. An invalid/malformed provider timestamp is treated as unknown/stale; it is not replaced by receipt time. Future timestamps are not fresh.
- `RECONCILE` is used when verified denial/completion outcomes conflict on an attempt or when merchant/payment state contradicts verified outcomes.
- Otherwise `WAIT` means evidence is missing, stale, pending, unknown, unverified, or otherwise inconclusive. Verified completion without a conflict returns `WAIT` with reason `PAYMENT_CONFIRMED`, explicitly meaning no retry is indicated because this decision enum has no separate no-action state.

This endpoint only investigates. It never charges, retries, captures, or refunds. It performs no external provider/AI calls. Unknown orders return `404 Not Found` with Problem Details. Service and MockMvc tests verify decision boundaries and response redaction; PostgreSQL persistence of snapshots/history and the full application context require separate configured-database validation.

## List investigations for an order

`GET /api/orders/{orderId}/investigations`

Returns up to 50 investigations for the order, newest first, as the same stable DTO used by the investigation endpoint, including persisted findings and evidence snapshots. An unknown order returns `404 Not Found` with Problem Details. Each run remains immutable; this endpoint does not rerun the rules.

## Retrieve one investigation

`GET /api/investigations/{investigationId}`

Returns the persisted decision, reason, rules version, explanation, structured evidence snapshot, findings, and creation time. It does not recalculate the investigation. Unknown IDs return `404 Not Found` with Problem Details.

## Retrieve investigation history

`GET /api/investigations/{investigationId}/history`

Returns stable history DTOs ordered oldest first. Each entry contains its ID, investigation ID, action, previous and new decisions, reason, and timestamp. Unknown investigation IDs return `404 Not Found` with Problem Details. History rows do not include raw provider payloads.

## Generate an explanation for a persisted investigation

`POST /api/investigations/{investigationId}/explanation`

Requests an optional Gemini explanation for an existing investigation. It does not re-run the deterministic rules and accepts no request body. The persisted investigation decision, reason, rules version, and sanitized evidence snapshot remain authoritative.

### Response

Returns `200 OK` with `investigationId`, unchanged `decision`, `reason`, and `rulesVersion`, plus `explanation` and `source`. `source` is `GEMINI` when a valid provider response is used and `DETERMINISTIC_FALLBACK` when Gemini is unconfigured/unavailable or its response is malformed, blank, too long, or action-directive. Explanations are limited to 1,200 characters. The fallback repeats the persisted decision/reason/rules version and explicitly does not authorize payment action. Unknown investigation IDs return `404 Not Found` with Problem Details.

The Gemini request contains only the persisted decision, reason code, rules version, order/attempt/payment states, event type and provenance/verification labels, timestamp source, freshness labels, trusted-evidence flags, finding codes/categories/severities/uncertainty labels and evidence types, and the 15-minute freshness threshold. It excludes record IDs, exact timestamps, raw event payloads, merchant/payment/provider references, amounts, finding prose/actions, and credentials. State values are allowlisted before sending. Gemini is instructed not to override decisions, infer missing/unverified evidence, or recommend payment actions. Its output is validated before use; the decision is never parsed from or set by the generated text.

The external HTTP request runs outside database transactions. After the provider response or deterministic fallback is selected, only the `investigations.explanation` field is saved in a separate short transaction; the decision, reason, rules version, snapshot, and investigation history are not modified. No schema migration is required. Configure `GEMINI_API_KEY`; optional `GEMINI_MODEL` and `GEMINI_ENDPOINT` default to `gemini-2.5-flash` and the Gemini v1beta API base URL. The API key is sent through `x-goog-api-key`; it is not included in the prompt or URL.

Mock HTTP/provider and service/controller tests cover successful response parsing, missing key without network use, malformed/empty/provider-error fallback, output validation, persistence ordering, decision immutability, redaction, and not-found behavior. These tests do not call Gemini. No live credentials or end-to-end validation were available. Authentication/authorization is not implemented; keep the endpoint behind trusted infrastructure.

## Create and capture a PayPal Sandbox order

These are two explicit actions; neither is automatic. Both require an existing attempt with provider `PAYPAL_SANDBOX`. The PayPal API base URL is fixed to `https://api-m.sandbox.paypal.com`.

### Create provider order

`POST /api/payment-attempts/{paymentAttemptId}/paypal/order`

Creates a PayPal Orders v2 order with `intent=CAPTURE`. The purchase-unit amount and currency come from the persisted payment attempt, which were copied from its merchant order. No amount or currency is accepted from this request. A successful response returns HTTP 200 with the PayPal order ID, provider status, local payment status, and PayPal approval URL. The provider order ID and status are persisted; the local payment status becomes `PENDING`.

A second create request for an attempt that already has a PayPal order returns HTTP 409. The attempt UUID is transformed into a deterministic, operation-specific UUID and sent as `PayPal-Request-Id`, so a repeated provider request caused by an interrupted save is idempotent.

The response contains `paymentAttemptId`, `amount`, `currency`, `paymentStatus`, `providerStatus`, `providerOrderId`, `providerCaptureId`, and `approvalUrl`. `providerCaptureId` is `null` until capture; the create response returns the provider's approval link when supplied.

### Capture provider order

`POST /api/payment-attempts/{paymentAttemptId}/paypal/capture`

Captures the PayPal order ID already stored on the attempt. The endpoint does not accept an order ID from the caller. A successful response returns HTTP 200 and persists the capture ID and provider status. `COMPLETED` maps to local `SUCCEEDED`, `PENDING` to `PENDING`, `DECLINED`/`DENIED`/`FAILED` to `FAILED`, and unrecognized statuses to `UNKNOWN`. Capture uses a separate stable operation-specific `PayPal-Request-Id`. An attempt already marked `SUCCEEDED` cannot be captured again.

### Errors

- `404 Not Found` if the payment attempt does not exist.
- `409 Conflict` if the attempt is not `PAYPAL_SANDBOX`, a PayPal order already exists during create, capture has no stored order, or capture is requested for a successful attempt.
- `503 Service Unavailable` when Sandbox credentials are not configured.
- `502 Bad Gateway` for sanitized PayPal/OAuth/transport failures.

Both actions fetch a Sandbox OAuth client-credentials token. Configure `PAYPAL_CLIENT_ID` and `PAYPAL_CLIENT_SECRET` in the process environment; values are not stored in source or logged. Missing credentials return 503 without an HTTP request. Provider/API failures return a sanitized 502 Problem Details response; raw provider response bodies are not exposed or logged, and failures do not automatically change payment status.

Provider HTTP calls for these two actions occur outside database transactions. Only successful responses are persisted. These actions do not retry/refund or process webhooks themselves; verified webhook ingestion is described separately below. A timeout can leave the provider outcome uncertain; callers must not infer failure from the sanitized error. Use only with trusted clients because application authentication/authorization is not implemented.

## Receive a PayPal Sandbox webhook

`POST /api/webhooks/paypal`

Accepts the PayPal webhook JSON body and the five transmission headers: `PAYPAL-AUTH-ALGO`, `PAYPAL-CERT-URL`, `PAYPAL-TRANSMISSION-ID`, `PAYPAL-TRANSMISSION-SIG`, and `PAYPAL-TRANSMISSION-TIME`. The original request text is retained until verification and the decoded event is submitted with the actual headers and configured webhook ID to PayPal's `/v1/notifications/verify-webhook-signature` endpoint. Configure `PAYPAL_CLIENT_ID`, `PAYPAL_CLIENT_SECRET`, and `PAYPAL_WEBHOOK_ID`. The adapter remains fixed to Sandbox.

No event fields are acted on or persisted as provider evidence until PayPal returns `verification_status=SUCCESS`. Missing transmission headers, malformed/non-object JSON, and oversized bodies return HTTP 400. A verification response other than `SUCCESS` returns HTTP 401; provider/OAuth failures return a sanitized 502 or 503. Signature failures do not create event rows or update attempts.

Verified events are stored as `sourceType=PROVIDER` and `verificationStatus=VERIFIED`. The existing transaction-scoped PostgreSQL advisory lock and unique `(provider, provider_event_id)` index provide idempotency. First accepted delivery returns HTTP 200 with `{"status":"ACCEPTED"}`; a repeated verified provider delivery returns HTTP 200 with `{"status":"DUPLICATE"}`. An existing simulated event with the same provider/event key is never treated as verified and cannot update an attempt; this key collision returns HTTP 409.

Only exact known capture events map payment state: `PAYMENT.CAPTURE.COMPLETED` → `SUCCEEDED`, `PAYMENT.CAPTURE.PENDING` → `PENDING`, and `PAYMENT.CAPTURE.DENIED` → `FAILED`. Matching is limited to a unique PayPal Sandbox attempt already containing the webhook capture ID or related PayPal order ID. Missing, ambiguous, or unmatched references are retained as verified evidence with `processingStatus=RECEIVED` and do not change payment state. Unknown event types are also retained without a payment-state update. A successful mapped event is marked `PROCESSED`. Pending evidence does not downgrade failed/cancelled/succeeded states, and denial does not downgrade success.

The stored JSONB payload is the original event content structurally; PostgreSQL JSONB may normalize textual whitespace/key ordering. No raw payload, transmission signature, or credentials are logged. These webhook tests mock both PayPal and repositories; there is no live provider validation or database concurrency/load test. Application authentication/authorization is not implemented, so expose this endpoint only behind appropriate trusted infrastructure.
