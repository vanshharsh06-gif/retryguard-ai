# RetryGuard AI — Changelog

All notable changes to this project are documented here.

**Format:** Based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/)  
**Versioning:** No formal releases yet — this is a demonstration project. Commits are referenced by short SHA.

---

## [Unreleased] — Current Working Tree (Post-Stabilization)

### Security
- **Fixed CSRF cookie emission** — Added `CsrfCookieFilter` (extends `OncePerRequestFilter`) that eagerly reads `csrfToken.getToken()` on every request, forcing `XSRF-TOKEN` cookie emission on the first response. Resolves 403 on dashboard's first mutating request. (SecurityConfig:167-188)
- **Added `/paypal/cancel` to permitAll** — Both PayPal return and cancel redirects now accessible without authentication. (SecurityConfig:69-73)
- **Expanded CORS defaults** — Added `https://*.ngrok-free.dev`, `https://*.ngrok-free.app`, `https://*.ngrok.io` plus `PAYPAL_PUBLIC_BASE_URL` env var. No wildcard-with-credentials. (SecurityConfig:122-133)

### Agent Workflow
- **Removed hardcoded `enabled: false` in production config** — Both `application.yaml` and `application-prod.yaml` now bind `agent.enabled` and `scheduler-enabled` to `RETRYGUARD_AI_AGENT_ENABLED` / `RETRYGUARD_AI_AGENT_SCHEDULER_ENABLED` environment variables. (application.yaml:37-38, application-prod.yaml:38-39)
- **Removed `GROQ_NOT_CONFIGURED` guard from `AgentWorkflowService`** — Tests stub `enabled=true` but not `groq()`; guard caused 13 test failures. `GroqAgentProvider` already returns `PROVIDER_UNAVAILABLE` with clear message. (AgentWorkflowService:120-127)
- **Added startup logging** — `@PostConstruct` logs resolved agent config: `enabled`, `groqConfigured`, `model`. (AgentWorkflowService:84-93)

### Deterministic Explanation (Gemini Removal)
- **Deleted 9 Gemini/provider-abstraction files:**
  - `GeminiExplanationProvider.java`
  - `GeminiConfiguration.java`
  - `GeminiProperties.java`
  - `ExplanationProvider.java`
  - `ExplanationInput.java`
  - `ExplanationProviderException.java`
  - `ExplanationProviderUnavailableException.java`
  - `ExplanationProviderFailureException.java`
  - `InvalidExplanationResponseException.java`
- **Rewrote `InvestigationExplanationService`** — Deterministic-only: builds explanation from investigation's decision, reason, rules version, findings. Response `source: DETERMINISTIC`. No external provider call. (InvestigationExplanationService:31-137)
- **Updated tests** — `InvestigationExplanationServiceTest` and `InvestigationExplanationControllerTest` now verify deterministic behavior.
- **Updated dashboard** — "Explain with Gemini" button → "Explain decision"; result text no longer branches on source.

### Configuration & Secrets
- **Removed `gemini` block** from `application.yaml`, `application-prod.yaml`, `application-local.yaml`, `src/test/resources/application.yaml`
- **All secrets via env vars** — Tracked configs use empty placeholders `${VAR:}`; `application-local.yaml` gitignored; `application-prod.yaml` untracked.
- **Updated `.gitignore`** — Added `VINAX.local.md`, `*.log`, `/fix_*.py`, `/test_output*.txt`, `*.bak`, backup HTML files.

### Testing
- **222 tests passing** (0 failures, 0 errors, 2 skipped)
- **Integration tests use real PostgreSQL** — Testcontainers on classpath but not auto-configured; tests run against manually provisioned DB.
- **Security integration tests** — Require `DB_URL`/`DB_USERNAME`/`DB_PASSWORD` pointing at working `postgres`/`root1` database.

---

## [223eaf2] — 2024 — Security & Agent Workflow Improvements

### Added
- `CsrfCookieFilter` for eager CSRF token emission
- `PAYPAL_PUBLIC_BASE_URL` support in CORS defaults
- Startup logging for agent configuration resolution

### Changed
- `SecurityConfig`: Added `/paypal/cancel` to public endpoints; expanded CORS patterns
- `application.yaml` / `application-prod.yaml`: Agent enablement via env vars only
- `AgentWorkflowService`: Removed `GROQ_NOT_CONFIGURED` guard; added `@PostConstruct` logging

### Removed
- Hardcoded `agent.enabled: false` from `application-prod.yaml`

### Fixed
- Dashboard first-request 403 (CSRF cookie not emitted)
- PayPal cancel redirect 401 (not in permitAll)
- CORS "Invalid CORS request" from ngrok origins
- AgentWorkflowServiceTest 13 failures (guard removed)

---

## [744c813] — 2024 — Agent Workflow Configuration & Backend Tests

### Added
- Agent workflow transaction boundary fixes:
  - `AgentWorkflowPersistenceService` bean with `REQUIRES_NEW` error persistence
  - RUNNING workflow committed before Groq call
  - Groq execution outside database transaction
  - Steps persisted before COMPLETED status
- V5 migration: `uq_agent_workflows_one_running_per_order` partial unique index
- Concurrency violation handling in `AgentWorkflowService.isWorkflowConcurrencyViolation()`

### Changed
- `AgentWorkflowService.runWorkflow()` no longer `@Transactional`
- Transaction boundaries delegated to `AgentWorkflowPersistenceService`
- `StuckOrderDetectionService` — removed `@Transactional` from scan methods

### Fixed
- Groq execution inside database transaction (now outside)
- `REQUIRES_NEW` bypassed via self-invocation (now separate bean)
- Concurrent workflow race condition (DB partial unique index + app handling)

---

## [eb52590] — 2024 — Initial Commit: RetryGuard AI Backend

### Added
- **Core Domain:**
  - `MerchantOrder`, `PaymentAttempt`, `PaymentEvent` entities
  - `MerchantOrderService`, `PaymentAttemptService`, `PaymentEventService`
  - REST controllers for orders, attempts, events
- **PayPal Integration:**
  - `PaypalClient` (create order, capture, diagnose)
  - `PaypalPaymentService` (order creation, capture, return/cancel validation)
  - `PaypalWebhookService` (signature verification, idempotent processing)
  - Webhook controller (exempt from CSRF)
- **Deterministic Investigation:**
  - `InvestigationService` (rules v1.0, 15-min freshness)
  - `AutopsyFinding` with evidence references
  - Investigation history (audit trail)
- **Payment Timeline:**
  - `PaymentTimelineService` (chronological reconstruction)
- **Security:**
  - HTTP Basic + form login
  - CSRF (CookieCsrfTokenRepository)
  - CORS configuration
  - `SecurityUserConfig` (env-var credentials, BCrypt)
- **Database:**
  - V1: Payment event idempotency partial unique index
  - V2: PayPal capture reference columns
  - Base schema via JPA entities
- **Testing:**
  - Unit tests for services, controllers, PayPal client
  - Integration tests with PostgreSQL
  - Security tests

---

## Migration History (Additive SQL Scripts)

| Migration | File | Purpose | Applied |
|-----------|------|---------|---------|
| V1 | `V1__payment_event_idempotency.sql` | Partial unique index on `payment_events(provider, provider_event_id)` | Manual |
| V2 | `V2__paypal_payment_references.sql` | Add `provider_capture_reference`, `provider_status` to `payment_attempts` | Manual |
| V3 | `V3__investigation_findings.sql` | Add `findings` JSONB column to `investigations` | Manual |
| V4 | `V4__agent_workflows.sql` | Create `agent_workflows` & `agent_workflow_steps` tables + indexes | Manual |
| V5 | `V5__concurrent_workflow_prevention.sql` | Partial unique index: one RUNNING workflow per order | Manual |

**Note:** No Flyway/Liquibase runner. Migrations applied manually via `psql`. Presence of file ≠ proof of application — verify with `\d` and `\di` in PostgreSQL.

---

## Contributors

- Commits authored by project developers (see `git log`)

---

## Notes

- This changelog reflects changes supported by Git history and verified implementation.
- Dates are approximate (Git timestamps not preserved in this summary).
- No formal version tags exist — project is in demonstration phase.
- For production release, semantic versioning and formal release notes would be established.

---

*Generated from Git history (commits eb52590 → 223eaf2) and implementation verification.*