# RetryGuard AI — API Documentation

## 1. Overview

**Base URL:** `http://localhost:8080` (or configured `SERVER_PORT`)  
**Authentication:** HTTP Basic (API) + Form Login (browser)  
**CSRF Protection:** Required for all mutating requests (`POST`, `PUT`, `PATCH`, `DELETE`)  
**CSRF Mechanism:** `XSRF-TOKEN` cookie → `X-XSRF-TOKEN` header  
**Content Type:** `application/json` (request/response)  
**Error Format:** RFC 7807 Problem Details (via `GlobalExceptionHandler`)

---

## 2. Authentication & CSRF

### 2.1 Authentication

| Method | Description |
|--------|-------------|
| **HTTP Basic** | `Authorization: Basic <base64(user:pass)>` for API clients |
| **Form Login** | `POST /login` with `username`/`password` form fields; redirects to `/` on success |

**Credentials Source:** Environment variables `RETRYGUARD_API_USER` and `RETRYGUARD_API_PASSWORD` (BCrypt encoded at startup via `SecurityUserConfig`). If unset, no user exists and all protected endpoints return 401.

### 2.2 CSRF Protection

- **Cookie:** `XSRF-TOKEN` (set by `CsrfCookieFilter` on every response)
- **Header:** `X-XSRF-TOKEN: <cookie-value>` required on mutating requests
- **Exempt:** `POST /api/webhooks/paypal` (authenticated by PayPal signature instead)
- **GET/HEAD/OPTIONS:** No CSRF required

**Flow:**
1. Client makes any request (e.g., `GET /`)
2. Server responds with `Set-Cookie: XSRF-TOKEN=<token>; Path=/; SameSite=Lax`
3. Client reads cookie, includes `X-XSRF-TOKEN: <token>` on subsequent mutating requests

---

## 3. CORS Configuration

**Allowed Origins (defaults):**
- `http://localhost:*`
- `https://localhost:*`
- `https://*.ngrok-free.dev`
- `https://*.ngrok-free.app`
- `https://*.ngrok.io`
- `PAYPAL_PUBLIC_BASE_URL` (from env var)

**Allowed Methods:** `GET`, `POST`, `PUT`, `DELETE`, `OPTIONS`  
**Allowed Headers:** `Authorization`, `Content-Type`, PayPal webhook headers, `X-XSRF-TOKEN`, `X-CSRF-TOKEN`  
**Credentials:** Allowed (`Access-Control-Allow-Credentials: true`)  
**Max Age:** 3600 seconds

---

## 4. Error Responses

All errors follow RFC 7807 Problem Details:

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Validation failed: amount must be positive",
  "instance": "/api/orders"
}
```

**Common HTTP Status Codes:**
| Code | Meaning |
|------|---------|
| 200 | OK |
| 201 | Created |
| 400 | Bad Request (validation error) |
| 401 | Unauthorized (missing/invalid auth) |
| 403 | Forbidden (CSRF missing/invalid) |
| 404 | Not Found |
| 409 | Conflict (idempotency, concurrency) |
| 500 | Internal Server Error |
| 502 | Bad Gateway (PayPal/Groq unavailable) |
| 503 | Service Unavailable |

---

## 5. Endpoint Reference

### 5.1 Public Endpoints (No Auth Required)

#### `GET /api/orders`
**Description:** List recent merchant orders (up to 50)  
**Response:** `200 OK` — `CreateOrderResponse[]`

```json
[
  {
    "id": "uuid",
    "merchantOrderReference": "demo-order-001",
    "amount": "25.00",
    "currency": "USD",
    "orderStatus": "CREATED",
    "createdAt": "2024-01-15T10:30:00Z"
  }
]
```

#### `GET /api/payment-attempts/{paymentAttemptId}/paypal/return`
**Description:** PayPal return redirect handler (browser)  
**Parameters:** `token` (query, required) — must match attempt's `providerPaymentReference`  
**Response:** `200 OK` — `text/html` page with capture button

#### `GET /api/payment-attempts/{paymentAttemptId}/paypal/cancel`
**Description:** PayPal cancel redirect handler (browser)  
**Parameters:** `token` (query, required)  
**Response:** `200 OK` — `text/html` cancel page

#### `POST /api/webhooks/paypal`
**Description:** Receive PayPal webhook (signature verified)  
**Headers Required:**
- `PAYPAL-AUTH-ALGO`
- `PAYPAL-CERT-URL`
- `PAYPAL-TRANSMISSION-ID`
- `PAYPAL-TRANSMISSION-SIG`
- `PAYPAL-TRANSMISSION-TIME`
**Body:** Raw webhook JSON  
**CSRF:** Exempt  
**Response:** `200 OK` — `PaypalWebhookResponse`

```json
{ "state": "ACCEPTED" }
```
or
```json
{ "state": "DUPLICATE" }
```

#### `GET /actuator/health`
**Description:** Health check  
**Response:** `200 OK` — Spring Boot actuator health

#### `GET /actuator/info`
**Description:** Application info  
**Response:** `200 OK` — Spring Boot actuator info

---

### 5.2 Authenticated Endpoints (Require Auth + CSRF for Mutations)

#### `POST /api/orders`
**Description:** Create merchant order  
**Auth:** Required  
**CSRF:** Required (`X-XSRF-TOKEN`)  
**Request Body:** `CreateOrderRequest`

```json
{
  "merchantOrderReference": "demo-order-001",
  "amount": 25.00,
  "currency": "USD"
}
```

**Validation:**
- `merchantOrderReference`: required, ≤100 chars, unique
- `amount`: required, > 0, max 2 decimal places
- `currency`: required, 3 uppercase letters (ISO-4217)

**Response:** `201 Created` — `CreateOrderResponse`

```json
{
  "id": "uuid",
  "merchantOrderReference": "demo-order-001",
  "amount": "25.00",
  "currency": "USD",
  "orderStatus": "CREATED",
  "createdAt": "2024-01-15T10:30:00Z"
}
```

**Errors:** 400 (validation), 409 (duplicate reference), 401/403 (auth/CSRF)

---

#### `POST /api/orders/{orderId}/payment-attempts`
**Description:** Create payment attempt for an order  
**Auth:** Required  
**CSRF:** Required  
**Path Variable:** `orderId` (UUID)  
**Request Body:** `CreatePaymentAttemptRequest`

```json
{
  "provider": "PAYPAL_SANDBOX",
  "amount": 25.00,
  "currency": "USD"
}
```

**Validation:**
- `provider`: required, currently only `PAYPAL_SANDBOX` supported
- `amount`: required, > 0
- `currency`: required, 3 uppercase letters

**Response:** `201 Created` — `CreatePaymentAttemptResponse`

```json
{
  "id": "uuid",
  "orderId": "uuid",
  "provider": "PAYPAL_SANDBOX",
  "amount": "25.00",
  "currency": "USD",
  "paymentStatus": "CREATED",
  "providerPaymentReference": null,
  "createdAt": "2024-01-15T10:31:00Z"
}
```

---

#### `POST /api/payment-attempts/{paymentAttemptId}/events`
**Description:** Record simulated payment event (for testing)  
**Auth:** Required  
**CSRF:** Required  
**Path Variable:** `paymentAttemptId` (UUID)  
**Request Body:** `RecordPaymentEventRequest`

```json
{
  "provider": "PAYPAL_SANDBOX",
  "providerEventId": "evt-test-123",
  "eventType": "PAYMENT.CAPTURE.COMPLETED",
  "payload": "{\"resource\":{\"id\":\"CAP-456\",\"amount\":{\"value\":\"25.00\",\"currency_code\":\"USD\"}}}"
}
```

**Response:** `201 Created` (new) or `200 OK` (duplicate) — `RecordPaymentEventResponse`

```json
{
  "id": "uuid",
  "paymentAttemptId": "uuid",
  "provider": "PAYPAL_SANDBOX",
  "providerEventId": "evt-test-123",
  "eventType": "PAYMENT.CAPTURE.COMPLETED",
  "sourceType": "SIMULATED",
  "verificationStatus": "NOT_APPLICABLE",
  "processingStatus": "PROCESSED",
  "payload": { "resource": { "id": "CAP-456", "amount": { "value": "25.00", "currency_code": "USD" } } },
  "receivedAt": "2024-01-15T10:32:00Z",
  "processedAt": "2024-01-15T10:32:00Z"
}
```

---

#### `POST /api/payment-attempts/{paymentAttemptId}/paypal/order`
**Description:** Create PayPal Sandbox order (intent=CAPTURE)  
**Auth:** Required  
**CSRF:** Required  
**Path Variable:** `paymentAttemptId` (UUID)  
**Request Body:** None (empty JSON `{}` or no body)  
**Response:** `200 OK` — `PaypalPaymentResponse`

```json
{
  "paymentAttemptId": "uuid",
  "amount": "25.00",
  "currency": "USD",
  "paymentStatus": "CREATED",
  "providerStatus": "CREATED",
  "providerOrderId": "5O190127TN364715T",
  "providerCaptureId": null,
  "approvalUrl": "https://www.sandbox.paypal.com/checkoutnow?token=5O190127TN364715T"
}
```

**Side Effect:** Updates `paymentAttempt.providerPaymentReference` with PayPal order ID.

---

#### `POST /api/payment-attempts/{paymentAttemptId}/paypal/capture`
**Description:** Explicitly capture approved PayPal Sandbox order  
**Auth:** Required  
**CSRF:** Required  
**Path Variable:** `paymentAttemptId` (UUID)  
**Request Body:** None  
**Response:** `200 OK` — `PaypalPaymentResponse`

```json
{
  "paymentAttemptId": "uuid",
  "amount": "25.00",
  "currency": "USD",
  "paymentStatus": "SUCCEEDED",
  "providerStatus": "COMPLETED",
  "providerOrderId": "5O190127TN364715T",
  "providerCaptureId": "8VC83970UJ974633L",
  "approvalUrl": null
}
```

**Side Effect:** Updates `paymentAttempt.providerCaptureReference`, `providerStatus`, `paymentStatus`.

---

#### `GET /api/orders/{orderId}/timeline`
**Description:** Get chronological payment timeline for an order  
**Auth:** Required  
**CSRF:** Not required (GET)  
**Path Variable:** `orderId` (UUID)  
**Response:** `200 OK` — `OrderTimelineResponse`

```json
{
  "orderId": "uuid",
  "merchantOrderReference": "demo-order-001",
  "orderStatus": "PAID",
  "entries": [
    {
      "timestamp": "2024-01-15T10:30:00Z",
      "type": "ORDER_CREATED",
      "description": "Merchant order created",
      "details": { "amount": "25.00", "currency": "USD" },
      "status": "CREATED"
    },
    {
      "timestamp": "2024-01-15T10:31:00Z",
      "type": "PAYMENT_ATTEMPT_CREATED",
      "description": "Payment attempt created",
      "details": { "provider": "PAYPAL_SANDBOX", "amount": "25.00", "currency": "USD" },
      "status": "CREATED"
    },
    {
      "timestamp": "2024-01-15T10:35:00Z",
      "type": "WEBHOOK_RECEIVED",
      "description": "Verified webhook: PAYMENT.CAPTURE.COMPLETED",
      "details": { "captureId": "8VC83970UJ974633L", "amount": "25.00", "currency": "USD" },
      "status": "VERIFIED"
    }
  ]
}
```

---

#### `POST /api/orders/{orderId}/investigations`
**Description:** Run deterministic Payment Autopsy investigation  
**Auth:** Required  
**CSRF:** Required  
**Path Variable:** `orderId` (UUID)  
**Request Body:** None  
**Response:** `200 OK` — `InvestigationResponse`

```json
{
  "id": "uuid",
  "orderId": "uuid",
  "decision": "RETRY_ELIGIBLE",
  "reason": "ALL_ATTEMPTS_FRESHLY_DENIED",
  "rulesVersion": "1.0",
  "explanation": "Every known attempt is failed and has fresh, verified PayPal terminal-denial evidence...",
  "evidenceSnapshot": {
    "orderStatus": "FAILED",
    "investigationTime": "2024-01-15T10:40:00Z",
    "freshnessThresholdMinutes": 15,
    "attempts": [
      {
        "paymentAttemptId": "uuid",
        "provider": "PAYPAL_SANDBOX",
        "paymentStatus": "FAILED",
        "createdAt": "2024-01-15T10:31:00Z",
        "events": [
          {
            "id": "uuid",
            "eventType": "PAYMENT.CAPTURE.DENIED",
            "provider": "PAYPAL_SANDBOX",
            "sourceType": "PROVIDER",
            "verificationStatus": "VERIFIED",
            "receivedAt": "2024-01-15T10:35:00Z",
            "providerTimestamp": "2024-01-15T10:35:00Z",
            "effectiveEvidenceAt": "2024-01-15T10:35:00Z",
            "timestampSource": "PROVIDER_CREATE_TIME",
            "freshness": "FRESH",
            "trustedPayPalEvidence": true,
            "providerEventId": "WH-123",
            "captureReference": "CAP-456"
          }
        ]
      }
    ]
  },
  "findings": [
    {
      "code": "PROVIDER_DENIAL",
      "title": "PayPal denied capture",
      "description": "Verified PayPal denial evidence exists for this attempt",
      "rootCauseCategory": "PROVIDER_DECLINE",
      "severity": "HIGH",
      "evidence": [
        { "type": "PAYMENT_EVENT", "id": "uuid" }
      ],
      "action": "Wait for fresh denial evidence before retry",
      "rootCauseUncertain": false
    }
  ],
  "classifications": [
    {
      "paymentAttemptId": "uuid",
      "provider": "PAYPAL_SANDBOX",
      "localStatus": "FAILED",
      "providerStatus": "DENIED",
      "classification": "TERMINAL_DENIAL",
      "evidenceFreshness": "FRESH"
    }
  ],
  "timeline": [
    { "timestamp": "2024-01-15T10:30:00Z", "type": "ORDER_CREATED", "description": "..." }
  ],
  "createdAt": "2024-01-15T10:40:00Z"
}
```

---

#### `GET /api/orders/{orderId}/investigations`
**Description:** List investigations for an order (up to 50, newest first)  
**Auth:** Required  
**CSRF:** Not required (GET)  
**Path Variable:** `orderId` (UUID)  
**Response:** `200 OK` — `InvestigationResponse[]`

---

#### `GET /api/investigations/{investigationId}`
**Description:** Get investigation by ID  
**Auth:** Required  
**CSRF:** Not required (GET)  
**Path Variable:** `investigationId` (UUID)  
**Response:** `200 OK` — `InvestigationResponse` (reconstructed from stored snapshot)

---

#### `GET /api/investigations/{investigationId}/history`
**Description:** Get investigation audit history  
**Auth:** Required  
**CSRF:** Not required (GET)  
**Path Variable:** `investigationId` (UUID)  
**Response:** `200 OK` — `InvestigationHistoryResponse[]`

```json
[
  {
    "id": "uuid",
    "investigationId": "uuid",
    "action": "CREATED",
    "previousDecision": null,
    "newDecision": "RETRY_ELIGIBLE",
    "reason": "ALL_ATTEMPTS_FRESHLY_DENIED",
    "createdAt": "2024-01-15T10:40:00Z"
  }
]
```

---

#### `POST /api/investigations/{investigationId}/explanation`
**Description:** Get deterministic explanation of investigation decision  
**Auth:** Required  
**CSRF:** Required  
**Path Variable:** `investigationId` (UUID)  
**Request Body:** None  
**Response:** `200 OK` — `InvestigationExplanationResponse`

```json
{
  "investigationId": "uuid",
  "decision": "RETRY_ELIGIBLE",
  "reason": "ALL_ATTEMPTS_FRESHLY_DENIED",
  "rulesVersion": "1.0",
  "explanation": "The deterministic investigation returned RETRY_ELIGIBLE (ALL_ATTEMPTS_FRESHLY_DENIED) under rules version 1.0. Evidence-based findings: PROVIDER_DENIAL (HIGH) [root cause uncertain]. This explanation is based only on the recorded evidence and does not authorize a payment action.",
  "source": "DETERMINISTIC"
}
```

**Note:** `source` is always `DETERMINISTIC`. No external AI provider is called.

---

#### `POST /api/agent/workflows/orders/{orderId}`
**Description:** Start Groq agent workflow for an order  
**Auth:** Required  
**CSRF:** Required  
**Path Variable:** `orderId` (UUID)  
**Request Body:** None  
**Response:** `200 OK` — `AgentWorkflowResponse`

```json
{
  "id": "uuid",
  "orderId": "uuid",
  "status": "COMPLETED",
  "summary": "WAIT_FOR_WEBHOOK",
  "toolCallsUsed": 5,
  "modelTurnsUsed": 3,
  "startedAt": "2024-01-15T10:45:00Z",
  "completedAt": "2024-01-15T10:45:15Z",
  "steps": [
    {
      "id": "uuid",
      "workflowId": "uuid",
      "stepType": "TOOL_CALL",
      "stepName": "get_order_summary",
      "inputSummary": "{\"orderId\":\"uuid\"}",
      "outputSummary": "{\"id\":\"uuid\",\"merchantOrderReference\":\"demo-order-001\",\"orderStatus\":\"PENDING\",\"amount\":\"25.00\",\"currency\":\"USD\"}",
      "error": null,
      "createdAt": "2024-01-15T10:45:01Z"
    },
    {
      "id": "uuid",
      "workflowId": "uuid",
      "stepType": "TOOL_CALL",
      "stepName": "verify_paypal_capture",
      "inputSummary": "{\"attemptId\":\"uuid\"}",
      "outputSummary": "{\"httpStatus\":200,\"paypalOrderStatus\":\"COMPLETED\",\"paypalCaptureAmount\":\"25.00\"}",
      "error": null,
      "createdAt": "2024-01-15T10:45:05Z"
    },
    {
      "id": "uuid",
      "workflowId": "uuid",
      "stepType": "RECOMMENDATION",
      "stepName": "WAIT_FOR_WEBHOOK",
      "inputSummary": "{\"recommendation\":\"WAIT_FOR_WEBHOOK\",\"reason\":\"Capture completed but webhook not yet received\"}",
      "outputSummary": null,
      "error": null,
      "createdAt": "2024-01-15T10:45:10Z"
    }
  ]
}
```

**Possible Status Values:**
- `RUNNING` — Workflow in progress
- `COMPLETED` — Finished normally (with recommendation)
- `SKIPPED` — Not run (ORDER_ALREADY_PAID, AGENT_DISABLED, WORKFLOW_ALREADY_RUNNING)
- `ERROR` — Execution failed
- `RECONCILED` — RECONCILE_LOCAL_ORDER succeeded, order marked PAID
- `WAITING_FOR_WEBHOOK` — Agent recommends waiting
- `ESCALATED` — Agent recommends escalation or reconciliation failed
- `WAITING_FOR_EVIDENCE` — No attempt ID found for reconciliation
- `PROVIDER_UNAVAILABLE` — Groq not configured/unreachable
- `BUDGET_EXCEEDED` — Max turns/calls reached
- `INVALID_TOOL` / `TOOL_ERROR` / `INVALID_RESPONSE` — Tool execution issues

---

#### `GET /api/agent/workflows/{workflowId}`
**Description:** Get workflow by ID with steps  
**Auth:** Required  
**CSRF:** Not required (GET)  
**Path Variable:** `workflowId` (UUID)  
**Response:** `200 OK` — `AgentWorkflowResponse`

---

#### `GET /api/agent/workflows`
**Description:** List recent workflows (up to 50, newest first)  
**Auth:** Required  
**CSRF:** Not required (GET)  
**Query Parameter:** `orderId` (UUID, optional) — filter by order  
**Response:** `200 OK` — `AgentWorkflowListResponse[]`

```json
[
  {
    "id": "uuid",
    "orderId": "uuid",
    "status": "COMPLETED",
    "summary": "WAIT_FOR_WEBHOOK",
    "startedAt": "2024-01-15T10:45:00Z",
    "completedAt": "2024-01-15T10:45:15Z",
    "toolCallsUsed": 5
  }
]
```

---

#### `POST /api/agent/scans/run`
**Description:** Run manual stuck-order scan  
**Auth:** Required  
**CSRF:** Required  
**Request Body:** None  
**Response:** `200 OK` — `ScanResponse`

```json
{
  "ordersProcessed": 3,
  "message": "Manual scan completed"
}
```

---

## 6. Request/Response DTOs

### 6.1 Request DTOs

| DTO | Used By | Fields |
|-----|---------|--------|
| `CreateOrderRequest` | `POST /api/orders` | `merchantOrderReference` (String), `amount` (BigDecimal), `currency` (String) |
| `CreatePaymentAttemptRequest` | `POST /api/orders/{id}/payment-attempts` | `provider` (String), `amount` (BigDecimal), `currency` (String) |
| `RecordPaymentEventRequest` | `POST /api/payment-attempts/{id}/events` | `provider` (String), `providerEventId` (String), `eventType` (String), `payload` (String) |

### 6.2 Response DTOs

| DTO | Returned By | Key Fields |
|-----|-------------|------------|
| `CreateOrderResponse` | `POST /api/orders`, `GET /api/orders` | `id`, `merchantOrderReference`, `amount`, `currency`, `orderStatus`, `createdAt` |
| `CreatePaymentAttemptResponse` | `POST /api/orders/{id}/payment-attempts` | `id`, `orderId`, `provider`, `amount`, `currency`, `paymentStatus`, `providerPaymentReference`, `createdAt` |
| `PaypalPaymentResponse` | PayPal order/capture endpoints | `paymentAttemptId`, `amount`, `currency`, `paymentStatus`, `providerStatus`, `providerOrderId`, `providerCaptureId`, `approvalUrl` |
| `OrderTimelineResponse` | `GET /api/orders/{id}/timeline` | `orderId`, `merchantOrderReference`, `orderStatus`, `entries[]` |
| `InvestigationResponse` | Investigation endpoints | `id`, `orderId`, `decision`, `reason`, `rulesVersion`, `explanation`, `evidenceSnapshot`, `findings`, `classifications`, `timeline`, `createdAt` |
| `InvestigationHistoryResponse` | `GET /api/investigations/{id}/history` | `id`, `investigationId`, `action`, `previousDecision`, `newDecision`, `reason`, `createdAt` |
| `InvestigationExplanationResponse` | `POST /api/investigations/{id}/explanation` | `investigationId`, `decision`, `reason`, `rulesVersion`, `explanation`, `source` |
| `AgentWorkflowResponse` | Agent workflow endpoints | `id`, `orderId`, `status`, `summary`, `toolCallsUsed`, `modelTurnsUsed`, `startedAt`, `completedAt`, `steps[]` |
| `AgentWorkflowListResponse` | `GET /api/agent/workflows` | `id`, `orderId`, `status`, `summary`, `startedAt`, `completedAt`, `toolCallsUsed` |
| `AgentWorkflowStepResponse` | Included in `AgentWorkflowResponse` | `id`, `workflowId`, `stepType`, `stepName`, `inputSummary`, `outputSummary`, `error`, `createdAt` |
| `PaypalWebhookResponse` | `POST /api/webhooks/paypal` | `state` (ACCEPTED/DUPLICATE) |
| `RecordPaymentEventResponse` | `POST /api/payment-attempts/{id}/events` | `id`, `paymentAttemptId`, `provider`, `providerEventId`, `eventType`, `sourceType`, `verificationStatus`, `processingStatus`, `payload`, `receivedAt`, `processedAt` |
| `ScanResponse` | `POST /api/agent/scans/run` | `ordersProcessed`, `message` |

---

## 7. Swagger / OpenAPI

**Swagger UI:** `http://localhost:8080/swagger-ui/index.html`  
**OpenAPI JSON:** `http://localhost:8080/v3/api-docs`

Both endpoints require authentication (HTTP Basic or form login session).

---

## 8. Rate Limits & Quotas

| Endpoint | Limit |
|----------|-------|
| PayPal API calls | Subject to PayPal Sandbox rate limits |
| Groq API calls | Subject to Groq API tier limits |
| Agent workflow | Max 5 model turns, 8 tool calls per workflow |
| Stuck-order scan | Max 10 orders per scan (`AgentProperties.maxRunsPerScan`) |

---

*This API documentation is generated from the actual controller implementations and DTOs. All endpoints have been verified against the source code.*