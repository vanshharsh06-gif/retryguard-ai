# RetryGuard AI — Business Requirements Document

## 1. Executive Summary

**Product Name:** RetryGuard AI  
**Tagline:** Payment Autopsy Console — Conservative, evidence-based payment incident investigation  
**Current State:** Local PayPal Sandbox demonstration  
**Technology:** Spring Boot 4.1.1, Java 21, PostgreSQL, Groq AI (advisory only)

---

## 2. Business Problem

### 2.1 Core Pain Points

Online merchants integrating with PayPal (or any payment provider) face recurring ambiguity when payments "fail":

| Scenario | Risk | Current Industry Gap |
|----------|------|---------------------|
| Buyer approves on PayPal, capture fails | **Double-charge** if automated retry fires without verified terminal denial | Most systems retry on generic "failure" without checking provider capture status |
| Capture succeeds, webhook delayed/lost | **Revenue loss** if order stuck in PENDING; **Fraud risk** if manually marked PAID without verification | No systematic evidence reconstruction; manual investigation is slow and error-prone |
| Provider and local records disagree | **Reconciliation errors** — either lost revenue or customer disputes | No deterministic audit trail linking evidence to decision |
| Evidence is incomplete/stale/conflicting | **Wrong action** (retry when shouldn't, or don't retry when should) | No conservative rules engine; decisions are ad-hoc |

### 2.2 Why Existing Solutions Fall Short

- **Payment gateways** provide raw events but no interpretation.
- **Fraud tools** score risk but don't reconstruct payment timelines.
- **Custom internal tools** are often brittle, untested, and lack AI assistance.
- **Generative AI** cannot be trusted to move money — needs hard safety gates.

---

## 3. Stakeholders

| Stakeholder | Interest | Success Metric |
|-------------|----------|----------------|
| **Merchant / Payment Operations** | Understand true payment state; avoid double charges & revenue loss | Zero double-charge incidents; reduced manual investigation time |
| **Engineering / Platform Team** | Safe, auditable integration with PayPal; clear API contracts | All payment state changes traceable to verified evidence |
| **Compliance / Audit** | Immutable decision log with evidence references | Investigation history survives; findings linked to persisted records |
| **Product / Demo Audience (Hackathon)** | Convincing demonstration of conservative payment safety | Live dashboard shows deterministic decision + AI advisory workflow |

---

## 4. Business Objectives

| Objective | Description | Priority |
|-----------|-------------|----------|
| **OBJ-1: Evidence-based verdict** | Every payment incident gets a deterministic classification (RETRY_ELIGIBLE, RECONCILE, WAIT) backed by persisted, evidence-linked findings | Critical |
| **OBJ-2: No automatic money movement** | System never retries, captures, refunds, or charges automatically | Critical |
| **OBJ-3: Independent provider verification** | Before any local order becomes PAID, Java service re-verifies PayPal capture status, amount, currency, and capture ID | Critical |
| **OBJ-4: Advisory AI only** | Groq agent can read diagnostics and recommend; cannot change state | Critical |
| **OBJ-5: Audit trail** | All investigation decisions, transitions, and workflow steps persisted | High |
| **OBJ-6: Developer-friendly dashboard** | Single-page console for creating orders, running autopsies, viewing timelines, triggering agent | High |
| **OBJ-7: Local Sandbox demo readiness** | Runs on developer machine with PayPal Sandbox + ngrok; no cloud deployment needed | High |

---

## 5. Business Requirements

### 5.1 Functional Requirements

| ID | Requirement | Traceability |
|----|-------------|--------------|
| BR-F-01 | Create merchant order with reference, amount, currency | `POST /api/orders` |
| BR-F-02 | Create local payment attempt linked to order (provider = PAYPAL_SANDBOX) | `POST /api/orders/{id}/payment-attempts` |
| BR-F-03 | Create PayPal Sandbox order (intent CAPTURE) and return approval URL | `POST /api/payment-attempts/{id}/paypal/order` |
| BR-F-04 | Handle PayPal return redirect; validate token | `GET /api/payment-attempts/{id}/paypal/return` |
| BR-F-05 | Explicitly capture approved PayPal order | `POST /api/payment-attempts/{id}/paypal/capture` |
| BR-F-06 | Receive PayPal webhooks; verify signature; process idempotently | `POST /api/webhooks/paypal` |
| BR-F-07 | Reconstruct chronological payment timeline (order, attempts, verified events) | `GET /api/orders/{id}/timeline` |
| BR-F-08 | Run deterministic Payment Autopsy investigation on an order | `POST /api/orders/{id}/investigations` |
| BR-F-09 | Investigation returns: decision (RETRY_ELIGIBLE/RECONCILE/WAIT), reason, rules version, findings with evidence refs | `InvestigationResponse` |
| BR-F-10 | Get deterministic plain-language explanation of an investigation | `POST /api/investigations/{id}/explanation` |
| BR-F-11 | Run Groq agent workflow for an order (read-only diagnostic tools) | `POST /api/agent/workflows/orders/{id}` |
| BR-F-12 | Agent workflow returns: steps, recommendation (RECONCILE_LOCAL_ORDER/WAIT_FOR_WEBHOOK/ESCALATE), token usage | `AgentWorkflowResponse` |
| BR-F-13 | RECONCILE_LOCAL_ORDER triggers Java-side `ReconciliationSafetyService` which independently verifies PayPal before marking PAID | `ReconciliationSafetyService` |
| BR-F-14 | View investigation history (audit trail of decision changes) | `GET /api/investigations/{id}/history` |
| BR-F-15 | List recent agent workflows | `GET /api/agent/workflows` |

### 5.2 Non-Functional Requirements

| ID | Requirement | Target |
|----|-------------|--------|
| BR-NF-01 | **Security** — HTTP Basic + form login; CSRF protection; CORS restricted | All mutating endpoints protected |
| BR-NF-02 | **Secrets** — All credentials from environment variables; no defaults in tracked config | Zero secrets in git |
| BR-NF-03 | **Determinism** — Investigation rules produce same output for same input; versioned (v1.0) | Rules version recorded in every investigation |
| BR-NF-04 | **AI Safety** — Agent bounded (5 turns, 8 tool calls); allowlisted tools only; read-only | Enforced in `GroqAgentProvider`, `AgentToolRegistry` |
| BR-NF-05 | **Concurrency** — Max 1 RUNNING workflow per order (DB constraint + app handling) | V5 partial unique index + `DataIntegrityViolationException` handling |
| BR-NF-06 | **Transaction Safety** — Groq HTTP call executes outside DB transaction; ERROR persisted via REQUIRES_NEW | `AgentWorkflowService` + `AgentWorkflowPersistenceService` |
| BR-NF-07 | **Test Coverage** — Unit + integration tests for all critical paths | 222 tests passing |
| BR-NF-08 | **Observability** — Actuator health/info endpoints public | `/actuator/health`, `/actuator/info` |
| BR-NF-09 | **No Auto-Migrations** — Schema validated at startup; additive migrations applied manually | `ddl-auto: validate` |

---

## 6. Scope & Constraints

### 6.1 In Scope (Current Delivery)

- ✅ Local PayPal Sandbox integration only
- ✅ Deterministic investigation engine (rules v1.0)
- ✅ Groq agentic workflow (advisory, read-only)
- ✅ Static HTML dashboard served by Spring Boot
- ✅ HTTP Basic + form authentication
- ✅ CSRF cookie-to-header pattern
- ✅ PostgreSQL partial unique indexes for idempotency & concurrency
- ✅ Manual migration application (no Flyway runner)

### 6.2 Out of Scope (Explicitly Deferred)

| Item | Reason |
|------|--------|
| Production deployment (Docker, K8s, secrets manager) | Requires separate infra/ops work |
| Full RBAC / OAuth2 / JWT authorization | Current auth is Basic + form for demo |
| Automated payment recovery (retry/capture/refund) | Violates core safety principle OBJ-2 |
| Multi-provider support (Stripe, Adyen, etc.) | Scope limited to PayPal Sandbox for demo |
| Real-time WebSocket updates | Polling sufficient for dashboard demo |
| Automated Flyway/Liquibase migrations | Manual control preferred for Sandbox demo |
| Comprehensive load/performance testing | Not a demo requirement |
| End-to-end live PayPal Sandbox CI validation | Requires Sandbox credentials in CI (security risk) |

### 6.3 Technical Constraints

- Java 21, Spring Boot 4.1.1
- PostgreSQL (partial unique indexes require PG; H2 not used for integration tests)
- Groq API key required for agent (optional for deterministic core)
- PayPal Sandbox credentials required for live checkout (optional for core tests)
- ngrok or similar HTTPS tunnel required for webhook delivery to localhost

---

## 7. Expected Outcomes

### 7.1 For the Hackathon Demo

1. **Dashboard loads** → Login → Create order → Create attempt → Run investigation → See deterministic verdict
2. **PayPal Sandbox flow** → Create order → Approve in Sandbox → Capture → Webhook received → Order marked PAID
3. **Agent workflow** → Start workflow → Watch Groq call tools → See recommendation → See safety gate if RECONCILE_LOCAL_ORDER
4. **Explanation** → Click "Explain decision" → Get deterministic plain-language summary
5. **Timeline** → See chronological reconstruction of all evidence

### 7.2 For Future Production Readiness (Not in Current Scope)

- Replace Basic auth with OAuth2/JWT + scopes
- Add Flyway for automated migrations
- Deploy behind API gateway with rate limiting
- Add structured logging, distributed tracing
- Implement comprehensive monitoring/alerting
- Conduct penetration testing & security audit

---

## 8. Success Criteria (Definition of Done for Current Scope)

| Criterion | Verification Method |
|-----------|---------------------|
| All 222 tests pass (0 failures, 0 errors, 2 skipped) | `.\mvnw.cmd test` |
| Application starts without `GEMINI_API_KEY` | Verified — Gemini removed |
| Dashboard loads, login works, CSRF cookie emitted | Manual browser test |
| Create order → attempt → investigation works | Manual browser test |
| Groq agent runs (5 tool calls, WAITING_FOR_WEBHOOK) | Manual browser test with `GROQ_API_KEY` |
| Deterministic explanation returns `source: DETERMINISTIC` | Manual browser test + API check |
| No secrets in tracked files | `git log --all --full-history -- <secret-patterns>` clean |
| PayPal webhook signature verification enforced | Unit tests + manual test with invalid signature |
| V1–V5 migrations apply cleanly to fresh PostgreSQL | Manual `psql` application verified |

---

*This BRD reflects the actual implemented capabilities as of the latest verified state. Features marked with ✅ have passing tests and/or manual runtime confirmation. Out-of-scope items are explicitly documented to prevent scope creep.*