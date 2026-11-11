# RetryGuard AI — Testing Documentation

## 1. Automated Testing

### 1.1 Test Suite Summary

| Metric | Value |
|--------|-------|
| **Total Tests** | 222 |
| **Passed** | 220 |
| **Failed** | 0 |
| **Errors** | 0 |
| **Skipped** | 2 |

**Skipped Tests (Gated):**
- `PaypalOrderDiagnosticTest` — Requires live PayPal Sandbox (`@EnabledIfSystemProperty`)
- `PaymentEventIdempotencyMigrationTest` — Requires manual DB setup (`@EnabledIfSystemProperty`)

**Command:**
```powershell
.\mvnw.cmd test
```

**Prerequisites:**
- PostgreSQL running with `retryguard_db` schema + V1–V5 migrations applied
- Environment variables for DB connection (test profile uses `postgres`/`root1` by default):
  ```powershell
  $env:DB_URL="jdbc:postgresql://localhost:5432/retryguard_db"
  $env:DB_USERNAME="postgres"
  $env:DB_PASSWORD="root1"
  ```

---

### 1.2 Test Categories

| Category | Count | Key Classes |
|----------|-------|-------------|
| **Unit Tests (Mockito)** | ~140 | `*Test.java` in `service/`, `ai/`, `paypal/`, `controller/` |
| **Integration Tests (Spring + PostgreSQL)** | ~80 | `*IntegrationTest.java`, `*PersistenceIntegrationTest.java` |
| **Security Tests** | 6 | `SecurityAuthAndWebhookTest`, `SecurityConfigTest`, `SecurityWebhookAndExceptionTest`, `SecurityIntegrationTest` |
| **Controller Tests (MockMvc)** | ~30 | `*ControllerTest.java` |

---

### 1.3 Key Integration Test Classes

| Test Class | Purpose | Database Required |
|------------|---------|-------------------|
| `AgentWorkflowTransactionIntegrationTest` | Verifies transaction boundaries: RUNNING committed before Groq, no TX during Groq, ERROR via REQUIRES_NEW, steps survive failures | Yes (PostgreSQL) |
| `AgentWorkflowConcurrencyIntegrationTest` | Verifies V5 partial unique index: concurrent RUNNING workflows blocked, different orders allowed, terminal → new allowed | Yes (PostgreSQL) |
| `InvestigationPersistenceIntegrationTest` | Investigation persistence, history, evidence snapshots | Yes |
| `PaymentEventPersistenceIntegrationTest` | Webhook idempotency, event processing | Yes |
| `PaymentTimelinePersistenceIntegrationTest` | Timeline reconstruction from DB | Yes |
| `MerchantOrderPersistenceIntegrationTest` | Order CRUD, constraints | Yes |
| `PaymentAttemptPersistenceIntegrationTest` | Attempt CRUD, provider refs | Yes |
| `PaypalWebhookEventProcessorTest` | Webhook signature verification, duplicate handling | Yes |
| `ReconciliationSafetyServiceTest` | 16-condition verification, success/failure paths | Yes |

---

### 1.4 Test Configuration

**Test Profile (`src/test/resources/application.yaml`):**
```yaml
spring:
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/retryguard_db}
    username: ${DB_USERNAME:postgres}
    password: ${DB_PASSWORD:root1}
  jpa:
    hibernate:
      ddl-auto: validate
  flyway:
    enabled: false  # Migrations applied manually
```

**Dependencies (pom.xml):**
- JUnit 5 (Jupiter)
- Mockito 5.x
- Spring Boot Test
- Spring Security Test
- Testcontainers (on classpath, not used by default)
- PostgreSQL JDBC driver

---

### 1.5 Running Specific Test Groups

```powershell
# All tests
.\mvnw.cmd test

# Only unit tests (no DB)
.\mvnw.cmd test -Dtest=*Test,!*IntegrationTest

# Only integration tests
.\mvnw.cmd test -Dtest=*IntegrationTest

# Specific class
.\mvnw.cmd test -Dtest=AgentWorkflowTransactionIntegrationTest

# With live PayPal (requires Sandbox credentials + system property)
.\mvnw.cmd test -Dtest=PaypalOrderDiagnosticTest -Dpaypal.live=true
```

---

## 2. UI Testing Walkthrough (Manual Dashboard Verification)

This section guides a reviewer through the complete dashboard flow in the correct order. Each step includes what to click/enter, expected result, what to verify, and suggested screenshot.

### Prerequisites

1. **PostgreSQL** running with schema + migrations
2. **Environment variables set** (minimum):
   ```powershell
   $env:DB_URL="jdbc:postgresql://localhost:5432/retryguard_db"
   $env:DB_USERNAME="postgres"
   $env:DB_PASSWORD="root1"
   $env:RETRYGUARD_API_USER="demo"
   $env:RETRYGUARD_API_PASSWORD="demo123"
   ```
3. **Optional for full PayPal flow:**
   ```powershell
   $env:PAYPAL_CLIENT_ID="<sandbox-client-id>"
   $env:PAYPAL_CLIENT_SECRET="<sandbox-secret>"
   $env:PAYPAL_WEBHOOK_ID="<sandbox-webhook-id>"
   $env:PAYPAL_PUBLIC_BASE_URL="https://<your-ngrok>.ngrok-free.dev"
   ```
4. **Optional for agent flow:**
   ```powershell
   $env:GROQ_API_KEY="<groq-api-key>"
   ```
5. **Start application:**
   ```powershell
   .\mvnw.cmd spring-boot:run
   ```
6. **Open browser:** `http://localhost:8080/`

---

### Step 1: Login

| Action | Details |
|--------|---------|
| **Navigate to** | `http://localhost:8080/` |
| **Expected** | Redirect to `/login` (Spring Security) |
| **Enter** | Username: `demo` (from `RETRYGUARD_API_USER`)<br>Password: `demo123` (from `RETRYGUARD_API_PASSWORD`) |
| **Click** | "Sign in" |
| **Expected Result** | Redirect to `/` — dashboard loads with "RetryGuard AI | Payment Autopsy Console" header |
| **Verify** | - Header shows "PAYPAL SANDBOX · DEMO ONLY" badge<br>- No console errors (F12 → Console)<br>- `XSRF-TOKEN` cookie present (Application → Cookies) |
| **Screenshot** | Login page → Dashboard after login |

---

### Step 2: Create Merchant Order

| Action | Details |
|--------|---------|
| **Locate** | "Create merchant order" card (left sidebar, top) |
| **Enter** | Merchant reference: `demo-order-001`<br>Amount: `25.00`<br>Currency: `USD` |
| **Click** | "Create order" |
| **Expected Result** | Green status: "Order created: demo-order-001 (25.00 USD)"<br>Order appears in "Orders" dropdown below |
| **Verify** | - `POST /api/orders` → 201 Created (Network tab)<br>- `X-XSRF-TOKEN` header sent<br>- Order dropdown shows "demo-order-001 · 25.00 USD · CREATED" |
| **Screenshot** | Filled form → Success status → Orders dropdown populated |

---

### Step 3: Create Payment Attempt

| Action | Details |
|--------|---------|
| **Select** | Order "demo-order-001" from Orders dropdown |
| **Verify** | Order summary card shows: Reference, Amount, Currency, Status=CREATED, Timestamps |
| **Locate** | "Payment attempt" card (below Orders) |
| **Select** | Provider: `PAYPAL_SANDBOX` (only option) |
| **Click** | "Create attempt" |
| **Expected Result** | Green status: "Attempt created: <uuid> (PAYPAL_SANDBOX)"<br>Attempt appears in "Attempt for Sandbox actions" dropdown |
| **Verify** | - `POST /api/orders/{id}/payment-attempts` → 201 Created<br>- Attempt dropdown shows attempt ID + provider |
| **Screenshot** | Attempt creation success → Attempt dropdown populated |

---

### Step 4: Inspect Order & Payment Timeline

| Action | Details |
|--------|---------|
| **Locate** | "Payment timeline" card (main area, top) |
| **Verify** | Timeline shows:<br>1. ORDER_CREATED — "Merchant order created"<br>2. PAYMENT_ATTEMPT_CREATED — "Payment attempt created" |
| **Click** | "Refresh" button in Orders card (if needed) |
| **Expected** | Timeline loads with two entries, chronological order |
| **Verify** | - `GET /api/orders/{id}/timeline` → 200 OK<br>- Entries have timestamps, types, descriptions, status badges |
| **Screenshot** | Timeline with two entries |

---

### Step 5: Run Payment Autopsy (Investigation)

| Action | Details |
|--------|---------|
| **Locate** | "Payment autopsy" card (main area, middle) |
| **Click** | "Run investigation" |
| **Expected Result** | Investigation result card appears with:<br>- **Decision badge**: `WAIT` (amber) — no PayPal evidence yet<br>- **Reason**: `INSUFFICIENT_OR_STALE_EVIDENCE` or `PAYMENT_OUTCOME_UNCERTAIN`<br>- **Findings**: List (may be empty or show `MISSING_EVIDENCE`)<br>- **Timeline**: Updated with INVESTIGATION_RUN entry |
| **Verify** | - `POST /api/orders/{id}/investigations` → 200 OK<br>- Response includes `decision`, `reason`, `rulesVersion: "1.0"`, `findings[]`, `evidenceSnapshot`, `classifications[]`, `timeline[]`<br>- "Previous investigations" dropdown now has entry |
| **Screenshot** | Investigation result with WAIT decision |

---

### Step 6: Review Deterministic Explanation

| Action | Details |
|--------|---------|
| **Select** | The investigation from "Previous investigations" dropdown |
| **Verify** | Investigation result updates to show selected run |
| **Click** | "Explain decision" button (enabled after investigation selected) |
| **Expected Result** | Explanation box appears below with text:<br>```
The deterministic investigation returned WAIT (INSUFFICIENT_OR_STALE_EVIDENCE) under rules version 1.0. Evidence-based findings: MISSING_EVIDENCE (MEDIUM). This explanation is based only on the recorded evidence and does not authorize a payment action.
``` |
| **Verify** | - `POST /api/investigations/{id}/explanation` → 200 OK<br>- Response `source: "DETERMINISTIC"`<br>- Text references decision, reason, findings<br>- Ends with safety statement |
| **Screenshot** | Explanation box with deterministic text |

---

### Step 7: Run Groq Agent Workflow

**Prerequisite:** `GROQ_API_KEY` environment variable set

| Action | Details |
|--------|---------|
| **Locate** | "Agentic AI Workflow" card (bottom of main area) |
| **Verify** | "Select an order to start a workflow" message |
| **Select** | Same order from Orders dropdown (if not already) |
| **Verify** | Workflow card shows "Select an order to start a workflow" → updates to show order reference |
| **Click** | "Start Agent Workflow" (mint green button) |
| **Expected Result** | - Button disabled, shows "Starting agent workflow…"<br>- Status: "Running… (workflow-id-short)"<br>- After ~10-30s: Status updates to terminal state |
| **Typical Outcome** | **Status:** `WAITING_FOR_WEBHOOK` (amber badge)<br>**Steps executed:** 5<br>**Summary:** `WAIT_FOR_WEBHOOK`<br>**Recommendation:** `WAIT_FOR_WEBHOOK`<br>**Steps shown:** 5 tool calls (get_order_summary, get_payment_attempts, get_verified_webhook_summary, get_latest_investigation, verify_paypal_capture, final_recommendation) |
| **Verify** | - `POST /api/agent/workflows/orders/{id}` → 200 OK<br>- Polling: `GET /api/agent/workflows/{workflowId}` every 3s<br>- Steps persisted: `agent_workflow_steps` table populated<br>- "Advisory only. The agent uses read-only diagnostic tools and does not execute payment actions." disclaimer shown |
| **Screenshot** | Workflow running (spinner) → Completed with steps expanded |

---

### Step 8: Inspect Workflow History & Recommendation

| Action | Details |
|--------|---------|
| **Locate** | Workflow details card (expanded after completion) |
| **Verify** | - Workflow ID displayed<br>- Steps executed count (5)<br>- Summary: `WAIT_FOR_WEBHOOK`<br>- Recommendation: `WAIT_FOR_WEBHOOK`<br>- Step list with input/output summaries (truncated to 500 chars) |
| **Click** | "Refresh Workflow" button |
| **Expected** | Re-fetches workflow, shows same data |
| **Change Order** | Select different order in Orders dropdown |
| **Verify** | Workflow card resets: "Select an order to start a workflow", status cleared |
| **Screenshot** | Workflow details with all steps expanded |

---

### Step 9: PayPal Sandbox Checkout (Full Flow — Requires Credentials)

**Prerequisites:** All PayPal env vars + ngrok running (`ngrok http 8080`)

| Action | Details |
|--------|---------|
| **Select** | Order with attempt (from Steps 2-3) |
| **Locate** | "Explicit PayPal Sandbox actions" card |
| **Click** | "Create Sandbox order" (amber button) |
| **Expected** | Green status: "Sandbox order created (CREATED). Open the approval link to continue in PayPal."<br>Blue "Open PayPal approval" button appears |
| **Click** | "Open PayPal approval" (opens new tab) |
| **In PayPal Sandbox** | Login with **buyer** Sandbox account → Approve payment |
| **Redirect** | Returns to dashboard: "PayPal approval received" page with "Capture payment" button |
| **Click** | "Capture payment" on approval page |
| **Dashboard** | Click "Capture Sandbox payment" (red button) → Confirm |
| **Expected** | Green status: "Capture response: COMPLETED · local status: SUCCEEDED." |
| **Verify Timeline** | Click "Refresh" in Orders → Timeline now shows:<br>1. ORDER_CREATED<br>2. PAYMENT_ATTEMPT_CREATED<br>3. PAYPAL_ORDER_CREATED<br>4. PAYPAL_CAPTURE_COMPLETED<br>5. WEBHOOK_RECEIVED (COMPLETED) |
| **Verify Order Status** | Order summary shows status: `PAID` |
| **Run Investigation** | Click "Run investigation" again |
| **Expected** | Decision: `WAIT` (or `RECONCILE` if webhook pending) with `PAYMENT_CONFIRMED` finding |
| **Screenshot** | PayPal approval page → Capture confirmation → Timeline with all events → Investigation with PAYMENT_CONFIRMED |

---

## 3. Verification Checklist (Post-Test)

After completing the walkthrough, verify:

| Check | Method |
|-------|--------|
| All 222 tests pass | `.\mvnw.cmd test` |
| No secrets in git history | `git log --all --full-history -- "*secret*" "*password*" "*key*"` |
| `application-local.yaml` not tracked | `git status` — should show as ignored |
| CSRF cookie emitted on first load | Browser DevTools → Application → Cookies → `XSRF-TOKEN` present |
| Mutating requests include `X-XSRF-TOKEN` | Network tab → Request Headers |
| Webhook endpoint exempt from CSRF | `POST /api/webhooks/paypal` succeeds without token |
| Agent workflow steps in DB | `SELECT * FROM agent_workflow_steps ORDER BY created_at;` |
| Investigation history audit trail | `SELECT * FROM investigation_history;` |
| Payment events idempotency | Duplicate webhook → `DUPLICATE` state |
| Partial unique index enforced | `SELECT * FROM pg_indexes WHERE indexname = 'uq_agent_workflows_one_running_per_order';` |

---

## 4. Limitations

### 4.1 Automated Testing
- **PostgreSQL required** for integration tests (no Testcontainers auto-run)
- **PayPal live tests gated** behind system property (require Sandbox credentials)
- **No contract testing** against PayPal/Groq APIs
- **No load/performance tests**
- **No mutation testing**

### 4.2 Manual UI Testing
- **PayPal Sandbox flow** requires valid Sandbox accounts and ngrok
- **Groq agent** requires valid API key
- **No automated browser tests** (Selenium/Playwright not configured)
- **Visual regression** not tested
- **Accessibility** not formally verified

### 4.3 Known Gaps
| Gap | Impact |
|-----|--------|
| No `@EnableScheduling` | Scheduled scans inert; only manual scan works |
| No Flyway runner | Migrations manual; drift possible |
| No production authZ | Single role, no scopes |
| No rate limiting | DoS risk if exposed |
| No CSP/HSTS headers | Browser security features missing |

---

## 5. Test Evidence (Latest Verified Run)

```
.\mvnw.cmd test

Tests run: 222, Failures: 0, Errors: 0, Skipped: 2
BUILD SUCCESS
```

**Environment:** Windows 10, Java 21, PostgreSQL 18, Maven Wrapper 3.9+

**Date of Verification:** Current session (see git commit 223eaf2)

---

*This testing documentation reflects the actual test suite and manual verification procedures. All automated tests pass; UI walkthrough steps have been executed against the running application.*