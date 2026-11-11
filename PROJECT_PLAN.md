# RetryGuard AI — Project Plan

## 1. Project Vision

When an online payment "fails," the truth is often ambiguous: the buyer may have been charged while the merchant order still looks unpaid, a webhook may arrive late, provider and local records may disagree, or the evidence may simply be incomplete. Blindly retrying in these situations risks **double charges**; blindly marking orders paid risks **revenue loss and fraud**.

RetryGuard AI treats each incident like a post-mortem ("autopsy"). It gathers the available evidence, applies **deterministic, conservative rules** to classify the situation, and records an auditable verdict with linked evidence. AI assistance is strictly advisory and read-only: a Groq agent can gather and summarize diagnostics and suggest a next step, but a separate Java safety service independently re-verifies the provider before any local payment state is ever changed.

**Core principles (from VINAX.md):**

- Deterministic rules are the authority for every payment decision.
- The AI agent cannot authorize, execute, retry, capture, or refund a payment.
- No order is marked `PAID` without independent, Java-side PayPal verification.
- Uncertain or conflicting evidence never triggers a retry.

## 2. Problem Statement

Merchants integrating with PayPal Sandbox (or production) face these recurring challenges:

1. **Ambiguous payment state** — Buyer approved on PayPal, but capture failed; or capture succeeded but webhook hasn't arrived yet.
2. **Double-charge risk** — Automated retries without verified terminal-denial evidence can charge the buyer twice.
3. **Revenue loss / fraud risk** — Marking orders `PAID` without independent provider verification opens the door to webhook spoofing or local data corruption.
4. **No audit trail** — Ad-hoc debugging leaves no record of what evidence was considered and why a decision was made.
5. **AI safety** — Generative AI suggestions cannot be trusted to move money; they need hard safety gates.

## 3. Objectives

### Primary (Implemented & Verified)

| Objective | Status | Evidence |
|-----------|--------|----------|
| Merchant order & payment-attempt tracking (local records only) | ✅ Implemented | `MerchantOrderController`, `PaymentAttemptController`, entities, repositories |
| PayPal Sandbox checkout (create order, approval redirect, explicit capture) | ✅ Implemented | `PaypalPaymentController`, `PaypalPaymentService` |
| Signature-verified webhooks with idempotent processing | ✅ Implemented | `PaypalWebhookController`, `PaypalWebhookService`, partial unique index V1 |
| Payment timeline reconstruction | ✅ Implemented | `PaymentTimelineController`, `PaymentTimelineService` |
| Deterministic Payment Autopsy investigation | ✅ Implemented | `InvestigationController`, `InvestigationService`, rules v1.0, 15-min freshness |
| Deterministic decision explanation (no external AI) | ✅ Implemented | `InvestigationExplanationController`, `InvestigationExplanationService` |
| Groq agentic workflow (read-only tools, bounded turns/calls) | ✅ Implemented | `AgentWorkflowController`, `AgentWorkflowService`, `AgentToolRegistry`, `GroqAgentProvider` |
| Java reconciliation safety gate (independent PayPal verification) | ✅ Implemented | `ReconciliationSafetyService` — capture-level verification |
| Static dashboard (single-page console) | ✅ Implemented | `src/main/resources/static/index.html` |
| Security (HTTP Basic + form login, CSRF, CORS) | ✅ Implemented | `SecurityConfig`, `SecurityUserConfig`, `CsrfCookieFilter` |

### Secondary (Partially Implemented / In Scope)

| Objective | Status | Notes |
|-----------|--------|-------|
| Scheduled stuck-order scanning | ⚠️ Inert | `StuckOrderDetectionService` annotated `@Scheduled` but no `@EnableScheduling`; manual scan only |
| PostgreSQL-specific concurrency constraints | ✅ Implemented | V5 partial unique index for one RUNNING workflow per order |
| Automatic DB migration runner | ❌ Not Implemented | No Flyway/Liquibase; migrations applied manually |
| Full end-to-end live PayPal Sandbox validation | ⚠️ Not Verified | Unit tests use mocks; real Sandbox flow not exercised in CI |
| Production hardening (authZ, secrets, deployment) | ❌ Not Done | README explicitly states "local Sandbox demo only" |

## 4. Scope

### In Scope (Current Delivery)

- **Backend**: Spring Boot 4.1.1, Java 21, PostgreSQL, Spring Data JPA
- **PayPal**: Sandbox API only (`https://api-m.sandbox.paypal.com`), webhook signature verification
- **AI**: Groq Chat Completions via function calling; deterministic explanation only (Gemini removed)
- **Frontend**: Single static HTML file served by Spring Boot (`index.html`)
- **Security**: HTTP Basic + form login, CSRF (cookie-to-header), CORS with localhost/ngrok defaults
- **Testing**: 222 tests (2 skipped), JUnit 5, Mockito, Spring Security Test; Testcontainers on classpath

### Out of Scope (Future Work)

- Production deployment (Docker, Kubernetes, secrets management)
- Full authentication/authorization (roles, scopes, token-based auth)
- Multi-provider support (Stripe, Adyen, etc.)
- Automated payment recovery (retry, capture, refund)
- Real-time WebSocket updates
- Multi-tenant / SaaS architecture
- Comprehensive audit logging beyond investigation history

## 5. Planned Modules & Design Decisions

| Module | Key Design Decision | Source |
|--------|---------------------|--------|
| **Order/Attempt/Event** | Local records only; creation never charges buyer | VINAX.md §11 |
| **PayPal Integration** | Explicit capture; approval ≠ payment; webhook mandatory | VINAX.md §11 |
| **Deterministic Investigation** | Conservative rules v1.0; 15-min freshness; `RETRY_ELIGIBLE` only on fresh verified denials | VINAX.md §6, `InvestigationService` |
| **AI Agent** | Bounded (5 turns, 8 tool calls); allowlisted read-only tools; advisory only | VINAX.md §7, `AgentToolRegistry` |
| **Reconciliation Safety** | Independent PayPal capture verification before any state change | VINAX.md §2, `ReconciliationSafetyService` |
| **Concurrency Control** | PostgreSQL partial unique index (V5) + app-level exception handling | VINAX.md §5, V5 migration |
| **Transaction Boundaries** | RUNNING persisted before Groq; Groq outside DB transaction; ERROR via REQUIRES_NEW | VINAX.md §4, `AgentWorkflowService`, `AgentWorkflowPersistenceService` |
| **Security** | CSRF cookie emitted on every response; webhook exempt; ngrok in CORS defaults | `SecurityConfig`, `CsrfCookieFilter` |
| **Explanation** | Deterministic-only; `Source.DETERMINISTIC`; no external provider call | `InvestigationExplanationService` |

## 6. Development Direction (From Original VINAX.md)

The original 16-milestone plan in `VINAX.md` outlined:

1. **M1** — PayPal capture-level verification ✅ Done
2. **M2** — Agent Workflow Reliability ✅ Done (transaction boundaries, concurrency, integration tests created but PostgreSQL tests blocked by Docker)
3. **M3** — Deterministic Investigation Engine ✅ Done (rules preserved)
4. **M4** — Groq Agent Workflow Verification ✅ Done (tools, limits, safety)
5. **M5** — Gemini Explanation Verification → **Removed** (Gemini integration deleted)
6. **M6** — Database & Migration Review ✅ Done (V1–V5 present, manually applied)
7. **M7** — Security Hardening ⚠️ Partial (CSRF, CORS, webhook verification done; no production authZ)
8. **M8** — API & Dashboard Regression ✅ Done (all controllers verified)
9. **M9** — Missing Tests ✅ Done (222 tests passing)
10. **M10** — Docker & Production Prep ❌ Not Done
11. **M11** — Documentation Updates ✅ This document set
12. **M13** — Final Verification ✅ Completed (222 tests, app starts, dashboard works)

## 7. Milestone Status Summary

| Milestone | Description | Status |
|-----------|-------------|--------|
| M1 | PayPal capture-level verification | ✅ Verified (tests + code) |
| M2 | Agent Workflow Reliability | ✅ Verified (code); integration tests exist but PostgreSQL concurrency tests blocked by Docker unavailability |
| M3 | Deterministic Investigation Engine | ✅ Verified (rules preserved, tests passing) |
| M4 | Groq Agent Workflow Verification | ✅ Verified (tools, limits, safety boundaries) |
| M5 | Gemini Explanation | 🗑️ Removed (deterministic-only explanation implemented) |
| M6 | Database & Migration Review | ✅ Verified (V1–V5, manual application) |
| M7 | Security Hardening | ⚠️ Partial (CSRF, CORS, webhook, Basic auth; no production authZ) |
| M8 | API & Dashboard | ✅ Verified (all endpoints functional) |
| M9 | Missing Tests | ✅ 222 tests, 0 failures, 0 errors, 2 skipped |
| M10 | Docker & Production | ❌ Not started |
| M11 | Documentation | ✅ This document set |
| M13 | Final Verification | ✅ Completed |

---

*This plan reflects the actual implemented state of the codebase as of the latest verified commit (223eaf2). Features marked "verified" have passing tests and/or manual runtime confirmation. Features marked "partial" or "not started" are explicitly noted.*