# Demo Guide

## Start the demo

Use Java 21 and PostgreSQL with the existing RetryGuard tables. Set `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` in the server process, inspect the target schema, and apply only missing migrations V1, V2, and V3 as described in the README. Then run:

```powershell
.\mvnw.cmd test
.\mvnw.cmd spring-boot:run
```

Open `http://localhost:8080/`. The app has no authentication or authorization; keep it on a trusted local network.

## Reproducible safe demonstration

1. In **Create merchant order**, enter a unique reference, a positive amount, and a three-letter uppercase currency. Submit and confirm the order appears in the order list.
2. Select the order and use **Create attempt**. This creates only a local tracking row; no provider request or charge occurs.
3. Inspect the payment timeline. It is assembled from persisted order, attempt, and event records. The attempt status is the current state, not a historical transition.
4. Run **Run investigation**. With no terminal verified evidence, the conservative deterministic outcome should be `WAIT` and findings should identify missing evidence. The persisted investigation can be selected again and its `CREATED` history entry inspected.
5. Choose **Explain with Gemini**. With `GEMINI_API_KEY`, the response may show `GEMINI`; otherwise it should explicitly show `DETERMINISTIC_FALLBACK`. Neither can change the stored decision.

This safe flow does not simulate a successful payment. The simulated-event API is a separate API-only tool; simulated rows are unverified and cannot establish provider outcomes or qualify an order for retry.

## Optional real PayPal Sandbox demonstration

Only use an approved PayPal Sandbox account and server-side Sandbox credentials (`PAYPAL_CLIENT_ID`, `PAYPAL_CLIENT_SECRET`, and webhook ID if testing webhooks). After creating and selecting a `PAYPAL_SANDBOX` attempt:

1. Click **Create Sandbox order**. The dashboard makes the explicit API request and displays the provider approval link.
2. Open the link and complete the buyer approval in the PayPal Sandbox.
3. Click **Capture Sandbox payment** and confirm the browser prompt. Capture is never automatic.
4. For webhook demonstration, configure PayPal's Sandbox webhook to reach the trusted application URL and configure `PAYPAL_WEBHOOK_ID`. The application verifies the signature with PayPal before accepting event evidence.
5. Refresh the timeline and run a new investigation. Review the persisted timeline, decision, findings, and investigation history.

These provider steps make real external Sandbox calls. They have not been live-validated here; mocked tests are not evidence of functioning credentials or webhook registration. A pending provider status is not proof of a provider/network root cause.

## API alternative

The same flow can be exercised directly:

```text
POST /api/orders
POST /api/orders/{orderId}/payment-attempts
GET  /api/orders/{orderId}/timeline
POST /api/orders/{orderId}/investigations
GET  /api/orders/{orderId}/investigations
GET  /api/investigations/{investigationId}
GET  /api/investigations/{investigationId}/history
POST /api/investigations/{investigationId}/explanation
```

See [`API_SPEC.md`](API_SPEC.md) for payloads and the PayPal/webhook endpoint contract.
