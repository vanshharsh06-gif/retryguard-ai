# Architecture and Implementation Decisions

## Initial order status

New merchant orders are created with status `CREATED`. Creating an order record alone is not evidence that a payment succeeded or failed.

## Duplicate merchant references

The service checks for an existing reference to provide a clear application-level duplicate outcome. The unique database constraint remains necessary because concurrent requests can both pass the pre-check.

## API entity boundary

The controller maps the persisted JPA entity into `CreateOrderResponse` rather than returning the entity directly. This keeps persistence details out of the external contract.

## Money representation

Amounts use `BigDecimal` and request validation limits scale and precision to align with the mapped database column.

## Payment-attempt tracking is metadata-only

The payment-attempt endpoint creates a local record in `CREATED` state and never contacts PayPal or initiates a charge. Provider text is supplied as descriptive metadata; order amount and currency are copied from the persisted merchant order.

## Multiple attempt rows

The tracking endpoint allows multiple attempt rows per order to preserve history. Since it performs no payment action, creating a row does not authorize a retry or imply that a provider attempt occurred. Future retry behavior must be gated by deterministic evidence and separate explicit rules.

## Simulated events are untrusted evidence

The first event API accepts only local simulated events. It derives the provider label from the associated attempt, sets source to `SIMULATED`, verification to `NOT_APPLICABLE`, and processing status to `RECEIVED`. Recording an event does not alter attempt/order status or prove a real payment outcome.

## Event idempotency key

The idempotency key is `(provider, providerEventId)`. The first accepted event wins; subsequent requests return the original record and do not replace its payload. PostgreSQL advisory locking serializes competing writes for the key, and a partial unique index enforces uniqueness for non-null event IDs. The database index migration is additive and was applied after a duplicate-key preflight found no existing duplicates.

## Event payload size

Event payloads are JSON objects capped at 64 KiB after serialization to limit unbounded request/database payload growth. Event bodies are not logged or sent to an LLM.

## PayPal Sandbox is an explicit two-step action

The payment-attempt API remains metadata-only. PayPal integration is exposed as separate explicit create-order and capture endpoints and only accepts attempts whose provider is `PAYPAL_SANDBOX`. The Sandbox API hostname is fixed in the adapter. No automatic retry, refund, or webhook processing is introduced.

## Payment amount authority and provider IDs

PayPal amount/currency are constructed from the persisted attempt (originally copied from the persisted order), never from a provider-action request. The PayPal order ID remains in `provider_payment_reference`; the capture ID and latest provider status use separate nullable columns added by V2. Attempt UUID plus operation name produces a deterministic UUID request ID, keeping create and capture idempotency keys distinct and within PayPal's key length.

## External-call transaction boundary and uncertain failures

Attempt/order data is loaded before the HTTP call; provider calls run without an application transaction. Successful provider responses are persisted in a separate short transaction. Provider response payloads are not logged or included in errors. A timeout or rejected API request is not treated as proof of payment failure and does not rewrite local status; the returned generic error makes the uncertain outcome explicit to integrators.

## Provider status mapping

Order creation sets local status to `PENDING` while persisting the PayPal order status. Capture `COMPLETED` maps to `SUCCEEDED`, `PENDING` to `PENDING`, `DECLINED`/`DENIED`/`FAILED` to `FAILED`, and other values to `UNKNOWN`. Unknown states never become success. Verified PayPal webhook processing is implemented as a separate evidence-ingestion path.

## Deterministic investigation is authoritative

Investigation decisions are produced by versioned deterministic rules, not an LLM. The separate Gemini explanation action may run only after an investigation has been persisted and cannot override its decision. Every run appends a distinct evidence snapshot and `CREATED` history entry; previous investigation rows are never updated.

`RETRY_ELIGIBLE` is deliberately narrow: at least one attempt must exist, every attempt must be `PAYPAL_SANDBOX` and currently `FAILED`, and each must have linked, verified provider `PAYMENT.CAPTURE.DENIED` evidence fresh within 15 minutes. Simulated/unverified events, pending, successful, unknown, missing, stale, or malformed-timestamp evidence prevent retry eligibility. A provider `create_time` is authoritative when present; only an absent timestamp allows receipt-time fallback. Conflicting verified outcomes on the same attempt or disagreement with merchant status returns `RECONCILE`; a completed provider payment while the order is not `PAID` is a conflict. A pending order whose attempts are all terminally denied also requires reconciliation, while pending/cancelled orders without contradictory terminal evidence return `WAIT`. A denied earlier attempt followed by a separate completed attempt is consistent success and returns `WAIT`. All other uncertainty, including a consistent success, returns `WAIT`; in the success case this means no retry is indicated, not that a payment action should occur.

The investigation endpoint is read-only with respect to payment processing: it makes no provider or AI calls and never charges, retries, captures, or refunds. Its evidence DTO/snapshot excludes raw payloads, provider references, and amounts.

## Gemini is explanation-only

Gemini is used only by the explicit `POST /api/investigations/{investigationId}/explanation` action after a deterministic investigation has been persisted. It receives only the already-decided outcome and an allowlisted, minimized state/provenance/freshness summary. IDs, exact timestamps, raw payloads, references, amounts, and secrets are excluded. The provider is not called within a database transaction; a separate short transaction updates only the existing explanation column after a valid response or safe fallback has been selected. The response reports `GEMINI` versus `DETERMINISTIC_FALLBACK`, and returns the original decision/reason/rules version. Invalid, unavailable, or unsafe provider output is replaced by a deterministic fallback. No migration, automatic payment action, or AI-controlled decision is permitted.
