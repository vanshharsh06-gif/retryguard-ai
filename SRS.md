# RetryGuard AI — Software Requirements Specification

## 1. Introduction

### 1.1 Purpose
This document specifies the functional and non-functional requirements for RetryGuard AI, a Spring Boot application that reconstructs payment incidents, applies deterministic conservative rules, and offers an advisory AI agent — all within a local PayPal Sandbox demonstration context.

### 1.2 Scope
RetryGuard AI is a **modular monolith** that:
- Tracks merchant orders and local payment attempts (no automatic charging)
- Integrates with PayPal Sandbox for explicit order creation, approval redirect, and capture
- Verifies PayPal webhook signatures and processes events idempotently
- Runs a deterministic "Payment Autopsy" investigation with evidence-linked findings
- Provides a deterministic, plain-language explanation of investigation decisions
- Runs a Groq-based agentic workflow with read-only diagnostic tools
- Enforces independent Java-side PayPal verification before any payment state change

### 1.3 Definitions & Acronyms
| Term | Definition |
|------|------------|
| **Merchant Order** | Local record of a sale; status: CREATED, PENDING, PAID, FAILED, CANCELLED |
| **Payment Attempt** | Local record of a payment try for an order; provider, references, status |
| **Payment Event** | Provider or simulated event; JSONB payload; verification & processing status |
| **Investigation** | Deterministic verdict (RETRY_ELIGIBLE, RECONCILE, WAIT) with findings |
| **Autopsy Finding** | Evidence-linked observation (code, severity, root cause, guidance) |
| **Agent Workflow** | Groq multi-turn function-calling session with allowlisted tools |
| **Reconciliation** | Java-side independent PayPal capture verification before state change |
| **Trusted Evidence** | Provider=PAYPAL_SANDBOX, source=PROVIDER, verification=VERIFIED |

---

## 2. Functional Requirements

### 2.1 Merchant Order Management

| ID | Requirement | API | Details |
|----|-------------|-----|---------|
| FR-ORD-01 | Create merchant order | `POST /api/orders` | Body: `merchantOrderReference` (unique, ≤100), `amount` (>0, 2 decimals), `currency` (ISO-4217, 3 chars). Returns order ID, reference, amount, currency, status=CREATED, timestamps. |
| FR-ORD-02 | List recent orders | `GET /api/orders` | Returns up to 50 orders with ID, reference, amount, currency, status, createdAt. Public (no auth). |

### 2.2 Payment Attempt Management

| ID | Requirement | API | Details |
|----|-------------|-----|---------|
| FR-PA-01 | Create payment attempt | `POST /api/orders/{orderId}/payment-attempts` | Body: `provider` (currently only PAYPAL_SANDBOX), `amount`, `currency`. Links to order. Status=CREATED. Returns attempt ID, order ID, provider, amount, currency, status, provider refs (null), createdAt. |

### 2.3 PayPal Sandbox Integration

| ID | Requirement | API | Details |
|----|-------------|-----|---------|
| FR-PP-01 | Create PayPal Sandbox order | `POST /api/payment-attempts/{attemptId}/paypal/order` | Calls PayPal Orders API (intent=CAPTURE). Stores `providerPaymentReference` (PayPal order ID). Returns approval URL, provider status, provider order ID. |
| FR-PP-02 | Validate PayPal return redirect | `GET /api/payment-attempts/{attemptId}/paypal/return?token=` | Validates token matches attempt's provider order ID. Returns HTML page with capture button. Public (browser redirect). |
| FR-PP-03 | Validate PayPal cancel redirect | `GET /api/payment-attempts/{attemptId}/paypal/cancel?token=` | Validates token. Returns HTML cancel page. Public (browser redirect). |
| FR-PP-04 | Explicitly capture PayPal order | `POST /api/payment-attempts/{attemptId}/paypal/capture` | Calls PayPal Capture API. Stores `providerCaptureReference`, `providerStatus`. Returns capture result. |
| FR-PP-05 | Receive & verify PayPal webhook | `POST /api/webhooks/paypal` | Verifies signature using `PAYPAL_WEBHOOK_ID`. Idempotent via partial unique index on `(provider, provider_event_id)`. Maps event types to payment status. Sets order PAID only on verified COMPLETED with amount/currency match. Returns `{ "state": "ACCEPTED" }` or `{ "state": "DUPLICATE" }`. |

### 2.4 Payment Timeline Reconstruction

| ID | Requirement | API | Details |
|----|-------------|-----|---------|
| FR-TL-01 | Get order timeline | `GET /api/orders/{orderId}/timeline` | Returns chronological entries: order creation, payment attempts, verified events, investigations. Each entry: timestamp, type, description, details, status badge. |

### 2.5 Deterministic Payment Autopsy Investigation

| ID | Requirement | API | Details |
|----|-------------|-----|---------|
| FR-INV-01 | Run investigation on order | `POST /api/orders/{orderId}/investigations` | Evaluates all attempts & verified events. Applies rules v1.0 (15-min freshness). Returns `InvestigationResponse`: decision (RETRY_ELIGIBLE/RECONCILE/WAIT), reason, rules version, explanation, evidence snapshot, findings, payment-state classifications, timeline. Persists `Investigation` + `InvestigationHistory`. |
| FR-INV-02 | Get investigation by ID | `GET /api/investigations/{investigationId}` | Returns full `InvestigationResponse` with reconstructed classifications & timeline from stored snapshot. |
| FR-INV-03 | List investigations for order | `GET /api/orders/{orderId}/investigations` | Returns up to 50 most recent investigations for the order. |
| FR-INV-04 | Get investigation history | `GET /api/investigations/{investigationId}/history` | Returns audit trail: action, previous decision, new decision, reason, timestamp. |

#### Investigation Decision Logic (Rules v1.0)

| Decision | Condition |
|----------|-----------|
| **RETRY_ELIGIBLE** | Every failed PayPal attempt has fresh (≤15 min), verified terminal-denial evidence; no later untrusted/pending/completed/unknown outcome |
| **RECONCILE** | Verified outcomes conflict with each other or with current order/payment status |
| **WAIT** | Any uncertain, pending, stale, incomplete, or confirmed-successful situation |

#### Finding Codes (`AutopsyFinding.Code`)
`PROVIDER_DENIAL`, `PENDING_PAYMENT`, `CONFLICTING_OUTCOMES`, `STALE_EVIDENCE`, `UNVERIFIED_EVIDENCE`, `MISSING_EVIDENCE`, `PAYMENT_CONFIRMED`

### 2.6 Deterministic Decision Explanation

| ID | Requirement | API | Details |
|----|-------------|-----|---------|
| FR-EXP-01 | Get explanation for investigation | `POST /api/investigations/{investigationId}/explanation` | Builds deterministic plain-language summary from decision, reason, rules version, findings. Response: `InvestigationExplanationResponse` with `source = DETERMINISTIC`. Ends with: "This explanation is based only on the recorded evidence and does not authorize a payment action." Never calls external AI. Persists explanation text on investigation. |

### 2.7 Groq Agentic Workflow

| ID | Requirement | API | Details |
|----|-------------|-----|---------|
| FR-AG-01 | Start agent workflow for order | `POST /api/agent/workflows/orders/{orderId}` | Validates order exists, not PAID, agent enabled, no RUNNING workflow. Persists RUNNING (committed). Executes Groq multi-turn loop (max 5 turns, 8 tool calls). Persists steps, result, status. If `RECONCILE_LOCAL_ORDER`, invokes `ReconciliationSafetyService`. Returns `AgentWorkflowResponse`. |
| FR-AG-02 | Get workflow by ID | `GET /api/agent/workflows/{workflowId}` | Returns workflow with steps (input/output summaries, errors). |
| FR-AG-03 | List recent workflows | `GET /api/agent/workflows` | Returns up to 50 workflows with ID, order ID, status, summary, timestamps, tool calls used. |
| FR-AG-04 | Run manual stuck-order scan | `POST /api/agent/scans/run` | Triggers `StuckOrderDetectionService.runManualScan()`. Returns orders processed. |

#### Agent Safety Boundaries (Enforced in Code)

| Boundary | Enforcement |
|----------|-------------|
| Max model turns | 5 (hardcoded in `GroqAgentProvider`) |
| Max tool calls | Configurable, default 8 (`AgentProperties.maxToolCalls`) |
| Allowlisted tools only | `AgentToolRegistry.getTools()` returns fixed list of 6 tools |
| Read-only tools | All tools query repositories/PayPalClient; no mutations |
| Order-scoped | Every tool validates `orderId` matches investigated order |
| No payment actions | No tool can capture, refund, retry, or change status |
| Advisory recommendation | `final_recommendation` tool returns enum; Java safety gate re-verifies |

#### Available Tools (`AgentToolRegistry`)

| Tool | Description | Returns |
|------|-------------|---------|
| `get_order_summary` | Local order details (status, amount, currency, timestamps) | Order summary map |
| `get_payment_attempts` | All attempts for order (provider refs masked) | List of attempt summaries |
| `get_verified_webhook_summary` | Verified PayPal events only (type, provider, verification labels, time) | List of webhook summaries |
| `get_latest_investigation` | Latest deterministic investigation (decision, reason, findings) | Investigation summary |
| `verify_paypal_capture` | Read-only PayPal order/capture diagnostic (local vs provider amounts/currency) | Diagnostic summary with distinct local/PayPal values |
| `final_recommendation` | Submit one of: RECONCILE_LOCAL_ORDER, WAIT_FOR_WEBHOOK, ESCALATE | `{ recommendation, reason }` |

### 2.8 Reconciliation Safety Gate

| ID | Requirement | Class | Details |
|----|-------------|-------|---------|
| FR-REC-01 | Independent PayPal verification before PAID | `ReconciliationSafetyService.reconcileIfAuthorized()` | Verifies: order exists, attempt belongs to order, provider=PAYPAL_SANDBOX, provider order ID exists, PayPal HTTP 200, order status=COMPLETED, intent=CAPTURE, purchase unit matches merchant reference, capture exists, capture status=COMPLETED, capture amount=currency match attempt, capture ID matches stored reference (if any). Only then sets order=PAID, attempt=SUCCEEDED. |

---

## 3. Non-Functional Requirements

### 3.1 Security

| ID | Requirement | Implementation |
|----|-------------|----------------|
| NFR-SEC-01 | Authentication | HTTP Basic (API) + Form login (browser); credentials from `RETRYGUARD_API_USER` / `RETRYGUARD_API_PASSWORD`; BCrypt encoded |
| NFR-SEC-02 | Authorization | Only `/api/orders` (GET), `/api/payment-attempts/*/paypal/return`, `/api/payment-attempts/*/paypal/cancel`, `/api/webhooks/paypal`, `/actuator/health`, `/actuator/info` are public |
| NFR-SEC-03 | CSRF | `CookieCsrfTokenRepository` with `XSRF-TOKEN` cookie / `X-XSRF-TOKEN` header; `CsrfCookieFilter` eagerly emits cookie on every response; webhook endpoint exempt |
| NFR-SEC-04 | CORS | Configurable via `RETRYGUARD_CORS_ALLOWED_ORIGINS`; defaults: `http://localhost:*`, `https://localhost:*`, `https://*.ngrok-free.dev/.app/.io`, plus `PAYPAL_PUBLIC_BASE_URL`; credentials allowed; no wildcard-with-credentials |
| NFR-SEC-05 | Webhook Integrity | Mandatory PayPal signature verification; payload size limited; idempotent processing; raw payloads never exposed to AI |
| NFR-SEC-06 | Secrets Management | All secrets from env vars; tracked `application.yaml` uses empty placeholders; `application-local.yaml` gitignored; `application-prod.yaml` untracked |
| NFR-SEC-07 | AI Containment | Agent read-only; cannot change payment state; only Java reconciliation service can after independent verification |

### 3.2 Reliability & Data Integrity

| ID | Requirement | Implementation |
|----|-------------|----------------|
| NFR-REL-01 | Idempotent Webhook Processing | Partial unique index `uq_payment_events_provider_event_id` on `(provider, provider_event_id) WHERE provider_event_id IS NOT NULL` (V1) |
| NFR-REL-02 | Concurrency Control | Partial unique index `uq_agent_workflows_one_running_per_order` on `agent_workflows(order_id) WHERE status = 'RUNNING'` (V5); app catches `DataIntegrityViolationException` and returns `WORKFLOW_ALREADY_RUNNING` |
| NFR-REL-03 | Transaction Boundaries | RUNNING persisted before Groq; Groq executes without DB transaction; steps/results persisted via short transactions; ERROR via `REQUIRES_NEW` on separate `AgentWorkflowPersistenceService` bean |
| NFR-REL-04 | No Auto-Migrations | `spring.jpa.hibernate.ddl-auto=validate`; additive migrations V1–V5 applied manually via `psql` |
| NFR-REL-05 | Evidence Freshness | 15-minute threshold (`InvestigationService.FRESHNESS_THRESHOLD_MINUTES`) for trusted evidence |

### 3.3 Performance & Scalability (Demo Scope)

| ID | Requirement | Target |
|----|-------------|--------|
| NFR-PERF-01 | Dashboard load time | < 2s on localhost |
| NFR-PERF-02 | Investigation execution | < 500ms (deterministic, no external calls) |
| NFR-PERF-03 | Agent workflow | Bounded by 5 turns × Groq latency; typically 10–30s |
| NFR-PERF-04 | Database | PostgreSQL; indexes on FKs, `created_at`, partial unique indexes |

### 3.4 Observability

| ID | Requirement | Implementation |
|----|-------------|----------------|
| NFR-OBS-01 | Health endpoint | `/actuator/health` (public) |
| NFR-OBS-02 | Info endpoint | `/actuator/info` (public) |
| NFR-OBS-03 | Startup logging | `AgentWorkflowService` logs resolved agent config (enabled, groqConfigured, model) |
| NFR-OBS-04 | Error sanitization | `AgentWorkflowPersistenceService.sanitizeErrorMessage()` strips bearer tokens, API keys, passwords, secrets |

---

## 4. System Interfaces

### 4.1 External APIs

| Interface | Protocol | Purpose |
|-----------|----------|---------|
| PayPal Sandbox REST API | HTTPS (mTLS not required for Sandbox) | Create order, capture order, diagnose order, verify webhook signature |
| Groq Chat Completions API | HTTPS (Bearer token) | Multi-turn function-calling for agent workflow |

### 4.2 Internal Interfaces

| Component | Interface | Consumers |
|-----------|-----------|-----------|
| `MerchantOrderRepository` | Spring Data JPA | `MerchantOrderService`, `InvestigationService`, `AgentToolRegistry` |
| `PaymentAttemptRepository` | Spring Data JPA | `PaymentAttemptService`, `InvestigationService`, `AgentToolRegistry`, `ReconciliationSafetyService` |
| `PaymentEventRepository` | Spring Data JPA | `PaymentEventService`, `InvestigationService`, `AgentToolRegistry` |
| `InvestigationRepository` | Spring Data JPA | `InvestigationService`, `InvestigationExplanationService`, `AgentToolRegistry` |
| `AgentWorkflowRepository` | Spring Data JPA | `AgentWorkflowService`, `AgentWorkflowController` |
| `PaypalClient` | Custom HTTP client | `PaypalPaymentService`, `PaypalWebhookService`, `ReconciliationSafetyService`, `AgentToolRegistry` |

### 4.3 User Interface

| Interface | Technology | Access |
|-----------|------------|--------|
| Static Dashboard | HTML/CSS/JS (single file `index.html`) | Served at `/` by Spring Boot static resources; requires login |

---

## 5. Data Requirements

### 5.1 Persistent Entities (PostgreSQL)

| Table | Key Columns | Constraints |
|-------|-------------|-------------|
| `merchant_orders` | `id` (PK, UUID), `merchant_order_reference` (UK), `amount`, `currency`, `order_status`, `created_at`, `updated_at` | Status enum; reference unique |
| `payment_attempts` | `id` (PK, UUID), `order_id` (FK), `provider`, `provider_payment_reference`, `provider_capture_reference`, `provider_status`, `amount`, `currency`, `payment_status`, `failure_code`, `failure_message`, `created_at`, `updated_at` | Status enum; FK to merchant_orders |
| `payment_events` | `id` (PK, UUID), `payment_attempt_id` (FK, nullable), `provider`, `provider_event_id`, `event_type`, `source_type`, `verification_status`, `processing_status`, `payload` (JSONB), `received_at`, `processed_at` | Partial UK on `(provider, provider_event_id) WHERE provider_event_id IS NOT NULL` (V1) |
| `investigations` | `id` (PK, UUID), `order_id` (FK), `payment_attempt_id` (FK, nullable), `decision`, `decision_reason`, `evidence_snapshot` (JSONB), `findings` (JSONB), `rules_version`, `explanation`, `created_at` | Decision enum; FK to merchant_orders, payment_attempts |
| `investigation_history` | `id` (PK, UUID), `investigation_id` (FK), `action`, `previous_decision`, `new_decision`, `reason`, `created_at` | FK to investigations |
| `agent_workflows` | `id` (PK, UUID), `order_id` (FK), `status`, `summary`, `tool_calls_used`, `model_turns_used`, `provider_name`, `model_name`, `prompt_tokens`, `completion_tokens`, `total_tokens`, `started_at`, `completed_at`, `created_at` | Partial UK on `order_id WHERE status = 'RUNNING'` (V5) |
| `agent_workflow_steps` | `id` (PK, UUID), `workflow_id` (FK, cascade delete), `step_type`, `step_name`, `input_summary` (JSONB), `output_summary` (JSONB), `error`, `created_at` | FK to agent_workflows (ON DELETE CASCADE) |

---

## 6. Acceptance Criteria

| AC-ID | Scenario | Given | When | Then |
|-------|----------|-------|------|------|
| AC-01 | Create order | Valid reference, amount, currency | POST `/api/orders` | 201 Created; order returned with status CREATED |
| AC-02 | Create attempt | Valid order ID, provider=PAYPAL_SANDBOX, amount, currency | POST `/api/orders/{id}/payment-attempts` | 201 Created; attempt returned with status CREATED |
| AC-03 | PayPal order creation | Valid attempt ID | POST `/api/payment-attempts/{id}/paypal/order` | 200 OK; approval URL returned; providerPaymentReference stored |
| AC-04 | PayPal return validation | Valid token matching attempt | GET `/api/payment-attempts/{id}/paypal/return?token=` | 200 OK; HTML page with capture button |
| AC-05 | PayPal capture | Approved attempt | POST `/api/payment-attempts/{id}/paypal/capture` | 200 OK; capture result; providerCaptureReference & providerStatus stored |
| AC-06 | Webhook verified & processed | Valid PayPal signature, COMPLETED capture event | POST `/api/webhooks/paypal` | 200 OK; `{ "state": "ACCEPTED" }`; order status → PAID if amount/currency match |
| AC-07 | Duplicate webhook rejected | Same provider_event_id delivered twice | POST `/api/webhooks/paypal` (2nd time) | 200 OK; `{ "state": "DUPLICATE" }`; no duplicate processing |
| AC-08 | Investigation RETRY_ELIGIBLE | All failed attempts have fresh verified denials | POST `/api/orders/{id}/investigations` | Decision = RETRY_ELIGIBLE; reason = ALL_ATTEMPTS_FRESHLY_DENIED |
| AC-09 | Investigation RECONCILE | Verified completion but order not PAID (or conflict) | POST `/api/orders/{id}/investigations` | Decision = RECONCILE; reason = CONFLICTING_PAYMENT_EVIDENCE |
| AC-10 | Investigation WAIT | Any uncertainty, pending, stale evidence | POST `/api/orders/{id}/investigations` | Decision = WAIT; reason reflects uncertainty |
| AC-11 | Explanation deterministic | Existing investigation | POST `/api/investigations/{id}/explanation` | 200 OK; source = DETERMINISTIC; explanation references decision/reason/findings; ends with safety statement |
| AC-12 | Agent workflow runs | Order not PAID, agent enabled, Groq configured, no RUNNING workflow | POST `/api/agent/workflows/orders/{id}` | Workflow persists RUNNING → Groq executes → persists COMPLETED/other; steps recorded |
| AC-13 | Agent RECONCILE_LOCAL_ORDER → safety gate | Agent recommends RECONCILE_LOCAL_ORDER | Workflow completes | `ReconciliationSafetyService` invoked; if verified, order → PAID; if not, ESCALATED |
| AC-14 | Concurrent workflow blocked | Two simultaneous requests for same order | Both POST `/api/agent/workflows/orders/{id}` | Exactly one RUNNING created; other returns SKIPPED · WORKFLOW_ALREADY_RUNNING |
| AC-15 | CSRF protection | Unauthenticated browser session | First GET `/` then POST mutating endpoint | XSRF-TOKEN cookie emitted; POST includes X-XSRF-TOKEN; 200 OK |

---

## 7. Constraints & Assumptions

### 7.1 Technical Constraints
- Java 21, Spring Boot 4.1.1, PostgreSQL 14+
- No Flyway/Liquibase runner — migrations manual
- PayPal Sandbox only (no production PayPal)
- Groq API required for agent (optional for deterministic core)
- ngrok/HTTPS tunnel required for webhook delivery to localhost

### 7.2 Assumptions
- Developer has PayPal Sandbox account for live demo
- Developer has Groq API key for agent demo
- PostgreSQL instance available with schema + migrations applied
- Demo runs on trusted local network (not public internet)

---

*This SRS reflects the actual implemented behavior as verified by 222 passing tests and manual runtime checks. Requirements trace to specific controllers, services, and database constraints in the codebase.*