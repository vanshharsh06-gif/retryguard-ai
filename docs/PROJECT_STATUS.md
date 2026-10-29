# Project Status

## Implemented MVP

- Existing order/attempt creation, PayPal Sandbox order and capture, signature-verified webhook ingestion, event idempotency, conservative deterministic investigations, timeline reconstruction, and Gemini explanation flow are preserved.
- Investigations now persist an append-only JSONB findings array with stable code, category, severity, evidence references, non-executing operator guidance, and root-cause uncertainty. Supported findings are generated only from actual persisted order/attempt/event evidence. They do not alter the decision rules.
- New read APIs retrieve a run, list up to 50 order runs newest-first, and return history oldest-first. They use DTO responses and do not expose provider payloads.
- `/` now serves a responsive static dashboard backed by the real application APIs. It supports order/attempt creation, timeline and autopsy review, previous runs/history, and Gemini explanation. PayPal order creation and capture are separate user-initiated Sandbox actions; capture requires confirmation.
- A bounded `GET /api/orders` endpoint feeds the dashboard's recent-order selector.
- `V3__investigation_findings.sql` adds `investigations.findings JSONB NOT NULL DEFAULT '[]'::jsonb` additively and preserves existing rows.
- Authentication/authorization is still not implemented. The UI and API are for trusted-network demo use only.

## Verification performed in this task

- Java 21 compilation of main and test sources passed with `.\mvnw.cmd -q -DskipTests test`.
- Focused findings, retrieval controller, Gemini, order-list, and PayPal webhook regression command passed: 58 tests, 0 failures, 0 errors, 0 skipped. Exact selector is in `docs/TEST_PLAN.md`.
- Dashboard inline JavaScript syntax check passed with Node.js 24.22.1. No separate frontend build/test tool exists.
- Full Maven run executed 112 tests: 0 assertion failures, 7 errors, 2 skipped. The Spring application/PostgreSQL integration tests could not initialize because PostgreSQL required a password and `DB_PASSWORD` was not configured. `psql` read-only schema inspection failed for the same reason.
- The findings persistence integration test is implemented but remains unverified against PostgreSQL until V3 is applied and database access is configured.
- No migration was applied during this task. The live V1/V2/V3 state could not be inspected. Earlier project notes reported V1 applied and V2 unapplied, but that was not independently confirmed in this environment.
- Gemini and PayPal tests use mocks. No live Gemini request, PayPal Sandbox payment, or webhook delivery was performed.

## Important remaining limitations

- Apply/verify the migrations on the intended PostgreSQL schema and rerun the complete Maven suite, including `InvestigationPersistenceIntegrationTest`.
- Validate the dashboard's full HTTP-to-PostgreSQL flow after the application starts with V3 present.
- Live PayPal Sandbox credentials, approval/capture, and webhook configuration/delivery are unverified. Live Gemini credentials/API behavior is also unverified.
- The app has no authentication/authorization and is not production-ready.
- The order timeline reports current attempt status, not a history of individual payment-status transitions.
