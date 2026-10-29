# RetryGuard AI

RetryGuard AI is a Spring Boot payment-investigation demo. It records orders, attempts, and payment events; reconstructs a timeline; and creates conservative, deterministic investigations with evidence-linked Payment Autopsy findings. Gemini can explain a persisted result, but cannot change its decision or trigger payment activity.

## Prerequisites

- Java 21
- PostgreSQL with the existing application tables (`merchant_orders`, `payment_attempts`, `payment_events`, `investigations`, and `investigation_history`)
- The Maven Wrapper included in this repository

There is no separate frontend build or development server. The dashboard is served by Spring Boot at `http://localhost:8080/`.

## Configure local settings

`src/main/resources/application.yaml` contains the safe defaults and imports the optional, Git-ignored `src/main/resources/application-local.yaml`. Put local passwords and provider credentials in that local file, not in the tracked `application.yaml`. Create it with the following structure and replace each placeholder locally:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/retryguard_db
    username: postgres
    password: "REPLACE_WITH_LOCAL_POSTGRES_PASSWORD"

retryguard:
  paypal:
    client-id: "REPLACE_WITH_PAYPAL_SANDBOX_CLIENT_ID"
    client-secret: "REPLACE_WITH_PAYPAL_SANDBOX_CLIENT_SECRET"
    webhook-id: "REPLACE_WITH_PAYPAL_SANDBOX_WEBHOOK_ID"
  ai:
    gemini:
      api-key: "REPLACE_WITH_GEMINI_API_KEY"
      model: gemini-2.5-flash
      endpoint: https://generativelanguage.googleapis.com/v1beta
```

The PayPal API remains Sandbox-only at `https://api-m.sandbox.paypal.com`. The configured public callback base URL remains the existing ngrok URL in `application.yaml`; keep the tunnel active and update it there only if the URL changes. PayPal callbacks use that base URL plus the payment-attempt-specific return/cancel path.

The local override is ignored by Git. Keep real credentials out of `application.yaml`; before committing, inspect `git diff --cached` and ensure it contains no credentials. Do not force-add the ignored local file. Environment variables remain supported as a fallback for local/CI use.

## Configure and migrate PostgreSQL

The schema is checked with Hibernate `ddl-auto: validate`; the application does not create or reset the base tables. Before starting, inspect and apply only migrations not already reflected in the intended database. Migration files are additive and kept under `src/main/resources/db/migration/`:

1. `V1__payment_event_idempotency.sql` adds the partial unique provider-event index. Check for duplicate non-null `(provider, provider_event_id)` values first; the index creation will fail if they exist. Do not delete or rewrite existing events to force it through.
2. `V2__paypal_payment_references.sql` adds PayPal capture-reference and status columns.
3. `V3__investigation_findings.sql` adds the JSONB findings column with an empty-array default for existing investigations.

There is no automatic migration runner. For a target database, inspect the actual schema and apply each required file once, in order, from the repository root, for example:

```powershell
psql -v ON_ERROR_STOP=1 -h localhost -U postgres -d retryguard_db -f src\main\resources\db\migration\V1__payment_event_idempotency.sql
psql -v ON_ERROR_STOP=1 -h localhost -U postgres -d retryguard_db -f src\main\resources\db\migration\V2__paypal_payment_references.sql
psql -v ON_ERROR_STOP=1 -h localhost -U postgres -d retryguard_db -f src\main\resources\db\migration\V3__investigation_findings.sql
```

V1 and V2 use `IF NOT EXISTS`; V3 also uses `IF NOT EXISTS` and preserves existing rows. V1 still requires the duplicate check. These commands are examples, not evidence that the migrations have been applied to your database. The database configured in this environment could not be inspected because its password was not available.

## Optional provider configuration

PayPal operations remain explicit Sandbox actions and require the Sandbox client ID, client secret, and webhook ID in the local override. Gemini explanations are optional; without a key, the application returns and persists a deterministic fallback. No provider connectivity is implied by configuring these values.

Do not put credentials in browser code. The dashboard calls only the local application API.

## Run

From the repository root, after configuring the database and applying the necessary migrations:

```powershell
.\mvnw.cmd test
.\mvnw.cmd spring-boot:run
```

Open **http://localhost:8080/** for the dashboard. Swagger UI is at `http://localhost:8080/swagger-ui/index.html`; OpenAPI JSON is at `http://localhost:8080/v3/api-docs`.

## Demonstration flow

1. Create an order in the dashboard, then create a local `PAYPAL_SANDBOX` attempt. Creating either record does not charge a buyer.
2. Review the order timeline and run an investigation. With no verified terminal provider evidence, expect `WAIT` and findings such as missing or unverified evidence; simulated events never qualify for retry eligibility.
3. To demonstrate actual Sandbox processing, explicitly choose **Create Sandbox order**, open the PayPal approval link, complete buyer approval in the Sandbox account, and explicitly choose **Capture Sandbox payment**. A configured verified webhook can also deliver provider evidence. These are real external Sandbox operations, not mocked dashboard results.
4. Select an investigation to inspect its findings and chronological audit history. Optionally request a Gemini explanation; the page identifies whether it used Gemini or the deterministic fallback.

The investigation engine remains authoritative. Findings contain evidence IDs, stable root-cause categories, severity, operator guidance, and uncertainty. Recommendations are informational and do not retry, capture, refund, or charge. See [`docs/API_SPEC.md`](docs/API_SPEC.md) for endpoint details and [`docs/DEMO_GUIDE.md`](docs/DEMO_GUIDE.md) for a reproducible walkthrough.

## Troubleshooting and limitations

- **Application fails schema validation:** inspect the target database, then apply the missing V1/V2/V3 migration. Do not assume a migration ran because its file exists.
- **Database connection fails:** verify PostgreSQL is running and `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` target the intended database. Do not paste credentials into issue reports.
- **PayPal returns unavailable:** check server-side Sandbox credentials and webhook ID. Provider credentials and live Sandbox behavior were not validated by mocked tests.
- **Gemini fallback is shown:** configure a valid `GEMINI_API_KEY`; fallback is expected when Gemini is unconfigured or the response is rejected.
- The application has **no authentication or authorization**. Keep it on a trusted local network; do not expose the demo publicly.
- Live PayPal/Gemini calls, database-backed findings persistence, and production deployment must be validated separately. The app does not perform automatic payment recovery.

See [`docs/PROJECT_STATUS.md`](docs/PROJECT_STATUS.md) and [`docs/TEST_PLAN.md`](docs/TEST_PLAN.md) for current test and verification status.
