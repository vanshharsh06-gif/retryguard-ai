# RetryGuard AI — Security Documentation

## 1. Security Overview

**Current State:** Local PayPal Sandbox demonstration with HTTP Basic + form authentication, CSRF protection, CORS configuration, and mandatory PayPal webhook signature verification.

**Scope:** This documentation covers the implemented security controls. **This application is not production-hardened** — see §9 for known limitations.

---

## 2. Authentication

### 2.1 HTTP Basic Authentication (API Clients)

- **Realm:** `RetryGuard AI`
- **Credentials:** `RETRYGUARD_API_USER` / `RETRYGUARD_API_PASSWORD` environment variables
- **Encoding:** BCrypt (configured in `SecurityUserConfig`)
- **Usage:** `Authorization: Basic <base64(username:password)>`
- **Applies to:** All `/api/**` endpoints except explicitly public ones

### 2.2 Form Login (Browser Dashboard)

- **Login Page:** `/login` (Spring Boot default)
- **Success Redirect:** `/` (dashboard)
- **Session:** Standard `JSESSIONID` cookie
- **Credentials:** Same `RETRYGUARD_API_USER` / `RETRYGUARD_API_PASSWORD`

### 2.3 User Provisioning (`SecurityUserConfig`)

```java
@Bean
public UserDetailsService userDetailsService(PasswordEncoder encoder) {
    String username = System.getenv("RETRYGUARD_API_USER");
    String password = System.getenv("RETRYGUARD_API_PASSWORD");
    if (username != null && password != null) {
        return new InMemoryUserDetailsManager(
            User.withUsername(username)
                .password(encoder.encode(password))
                .roles("USER")
                .build()
        );
    }
    return new InMemoryUserDetailsManager(); // No user → all protected endpoints 401
}
```

- **No default credentials** — must be set via environment variables
- **BCrypt encoding** — `BCryptPasswordEncoder` bean
- **Single role:** `USER` (no fine-grained authorization yet)

### 2.4 API Exception Handling

- `/api/**` requests receive **401 Unauthorized** (not redirect to login)
- Configured via `HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)` in `SecurityConfig`

---

## 3. Authorization

### 3.1 Public Endpoints (No Authentication Required)

| Endpoint | Method | Reason |
|----------|--------|--------|
| `/api/orders` | GET | Dashboard order listing |
| `/api/payment-attempts/*/paypal/return` | GET | PayPal browser redirect |
| `/api/payment-attempts/*/paypal/cancel` | GET | PayPal browser redirect |
| `/api/webhooks/paypal` | POST | PayPal webhook (signature-verified) |
| `/actuator/health` | GET | Health check |
| `/actuator/info` | GET | Application info |
| `/login` | GET/POST | Login page |
| `/error` | GET | Error page |

### 3.2 Protected Endpoints (Authentication Required)

**All other `/api/**` endpoints** — require valid authentication (Basic or session).

### 3.3 CSRF Protection (Mutating Requests)

Required for: `POST`, `PUT`, `PATCH`, `DELETE` on protected endpoints.

---

## 4. CSRF Protection

### 4.1 Mechanism

- **Token Repository:** `CookieCsrfTokenRepository.withHttpOnlyFalse()`
- **Cookie Name:** `XSRF-TOKEN`
- **Header Name:** `X-XSRF-TOKEN`
- **Cookie Attributes:** `Path=/`, `SameSite=Lax`, `Secure` (if HTTPS), **not HttpOnly** (JavaScript readable)

### 4.2 Critical Fix: Eager Cookie Emission (`CsrfCookieFilter`)

**Problem:** Spring's `CsrfTokenRequestAttributeHandler` defers token resolution — the `XSRF-TOKEN` cookie is only written when the token is accessed during request handling. The dashboard's first mutating request would fail with 403 because the cookie was never set on the initial `GET /` response.

**Solution:** `CsrfCookieFilter` (extends `OncePerRequestFilter`, placed after `BasicAuthenticationFilter`):

```java
@Override
protected void doFilterInternal(HttpServletRequest request,
                                 HttpServletResponse response,
                                 FilterChain filterChain) {
    CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
    if (csrfToken != null) {
        csrfToken.getToken(); // Force token resolution → cookie written
    }
    filterChain.doFilter(request, response);
}
```

**Result:** `XSRF-TOKEN` cookie emitted on **every response**, including the initial dashboard load.

### 4.3 Webhook Exemption

`POST /api/webhooks/paypal` is **exempt from CSRF** (configured in `SecurityConfig`):

```java
.csrf(csrf -> csrf
    .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
    .csrfTokenRequestHandler(csrfRequestHandler)
    .ignoringRequestMatchers("/api/webhooks/paypal")
)
```

**Reason:** PayPal webhooks cannot provide CSRF tokens; they are authenticated by PayPal signature verification instead.

### 4.4 Dashboard Integration (`index.html`)

```javascript
const getCsrfToken = () => {
    const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/);
    return match ? decodeURIComponent(match[1]) : null;
};

async function api(path, options = {}) {
    const mutating = options.method && ["POST", "PUT", "PATCH", "DELETE"].includes(options.method.toUpperCase());
    const headers = { ... };
    if (mutating) {
        const token = getCsrfToken();
        if (token) headers["X-XSRF-TOKEN"] = token;
    }
    // ... fetch with headers
}
```

---

## 5. CORS Configuration

### 5.1 Default Allowed Origins (when `RETRYGUARD_CORS_ALLOWED_ORIGINS` not set)

```java
patterns.add("http://localhost:*");
patterns.add("https://localhost:*");
patterns.add("https://*.ngrok-free.dev");
patterns.add("https://*.ngrok-free.app");
patterns.add("https://*.ngrok.io");

String publicBaseUrl = System.getenv("PAYPAL_PUBLIC_BASE_URL");
if (publicBaseUrl != null && !publicBaseUrl.isBlank()) {
    patterns.add(publicBaseUrl.trim());
}
```

### 5.2 Configurable Origins

Set `RETRYGUARD_CORS_ALLOWED_ORIGINS` (comma-separated) to override defaults.

### 5.3 CORS Settings

| Setting | Value |
|---------|-------|
| Allowed Methods | GET, POST, PUT, DELETE, OPTIONS |
| Allowed Headers | Authorization, Content-Type, PayPal headers, X-XSRF-TOKEN, X-CSRF-TOKEN |
| Allow Credentials | true |
| Max Age | 3600 seconds |
| Wildcard with Credentials | **Not allowed** (specific patterns only) |

---

## 6. PayPal Webhook Security

### 6.1 Signature Verification (Mandatory)

- **Webhook ID:** `PAYPAL_WEBHOOK_ID` environment variable
- **Headers Verified:**
  - `PAYPAL-AUTH-ALGO`
  - `PAYPAL-CERT-URL`
  - `PAYPAL-TRANSMISSION-ID`
  - `PAYPAL-TRANSMISSION-SIG`
  - `PAYPAL-TRANSMISSION-TIME`
- **Implementation:** `PaypalWebhookService.receive(PaypalWebhookTransmission, payload)`
- **Failure:** Invalid signature → event stored with `verification_status=REJECTED`, `processing_status=FAILED` → no state changes

### 6.2 Idempotency (Duplicate Protection)

**Database Constraint (V1 Migration):**
```sql
CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_events_provider_event_id
    ON payment_events (provider, provider_event_id)
    WHERE provider_event_id IS NOT NULL;
```

**Processing Flow:**
1. Verify signature
2. Attempt `INSERT` into `payment_events`
3. If unique constraint violation → catch → return `{ "state": "DUPLICATE" }`
4. If new → process event, update payment attempt/order status

### 6.3 Payload Handling

- **Raw payload stored** in `payment_events.payload` (JSONB) for audit
- **Never exposed to AI agent** — `get_verified_webhook_summary` tool returns only metadata
- **Size limited** by Spring Boot defaults (configurable via `spring.servlet.multipart.max-request-size`)

### 6.4 Event Mapping (Only Verified Events Affect State)

| Event Type | Verification Required | State Change |
|------------|----------------------|--------------|
| `PAYMENT.CAPTURE.COMPLETED` | Yes | Attempt → SUCCEEDED; Order → PAID (if amount/currency match) |
| `PAYMENT.CAPTURE.PENDING` | Yes | Attempt → PENDING |
| `PAYMENT.CAPTURE.DENIED` | Yes | Attempt → FAILED |
| Other | N/A | Stored only (RECEIVED) |

---

## 7. Secrets Management

### 7.1 Environment Variables Only

| Variable | Purpose | Required |
|----------|---------|----------|
| `DB_URL` | PostgreSQL JDBC URL | Yes |
| `DB_USERNAME` | Database username | Yes |
| `DB_PASSWORD` | Database password | Yes |
| `RETRYGUARD_API_USER` | Dashboard/API username | Yes |
| `RETRYGUARD_API_PASSWORD` | Dashboard/API password | Yes |
| `PAYPAL_CLIENT_ID` | PayPal Sandbox client ID | For PayPal features |
| `PAYPAL_CLIENT_SECRET` | PayPal Sandbox client secret | For PayPal features |
| `PAYPAL_WEBHOOK_ID` | PayPal webhook ID for verification | For webhooks |
| `PAYPAL_PUBLIC_BASE_URL` | Public HTTPS URL (ngrok) | For webhooks/redirects |
| `GROQ_API_KEY` | Groq API key | For agent |
| `GROQ_MODEL` | Groq model name | No (default) |
| `GROQ_BASE_URL` | Groq API base URL | No (default) |

### 7.2 Configuration Files

| File | Tracked | Secrets |
|------|---------|---------|
| `application.yaml` | ✅ Yes | **Empty placeholders only** (`${VAR:}`) |
| `application-prod.yaml` | ❌ No (untracked) | Empty placeholders |
| `application-local.yaml` | ❌ No (gitignored) | **Developer's actual values** |

### 7.3 No Hardcoded Secrets

- Verified: `git log --all --full-history -- "*secret*" "*password*" "*key*"` — no secrets in history
- `application.yaml` uses `${VAR:}` with empty defaults
- `application-local.yaml` in `.gitignore`
- `application-prod.yaml` not committed

---

## 8. AI Safety Boundaries

### 8.1 Agent Cannot Change Payment State

| Prohibited | Enforcement |
|------------|-------------|
| Capture payment | No capture tool in `AgentToolRegistry` |
| Refund payment | No refund tool |
| Retry payment | No retry tool; `RETRY_ELIGIBLE` is deterministic only |
| Change order/attempt status | All tools read-only; only `ReconciliationSafetyService` writes |
| Override deterministic decision | Agent sees investigation via tool; cannot modify it |

### 8.2 Reconciliation Safety Gate

`RECONCILE_LOCAL_ORDER` recommendation → `ReconciliationSafetyService.reconcileIfAuthorized()`:

- Independent `@Transactional` method
- Re-verifies **16 conditions** against PayPal before any state change
- Only sets `order_status = PAID` and `payment_status = SUCCEEDED` on full verification

### 8.3 Agent Execution Limits

- **Max model turns:** 5 (hardcoded)
- **Max tool calls:** 8 (configurable)
- **Allowlisted tools only:** 6 tools, validated by name
- **Unknown tools rejected:** Returns `INVALID_TOOL` error
- **Tool failures handled:** Returns `TOOL_ERROR`, workflow → `ERROR`

### 8.4 Error Sanitization

`AgentWorkflowPersistenceService.sanitizeErrorMessage()` strips:
- Bearer tokens: `bearer <token>` → `bearer ***`
- API keys: `api_key=value` → `api_key=***`
- Passwords: `password=value` → `password=***`
- Secrets: `secret=value` → `secret=***`
- Generic tokens: `token=value` → `token=***`

---

## 9. Known Security Limitations (Not Production-Ready)

| Limitation | Impact | Mitigation for Demo |
|------------|--------|---------------------|
| **Single user, no RBAC** | All authenticated users have same access | Demo runs on trusted local network |
| **HTTP Basic auth** | Credentials sent on every request (base64, not encrypted) | Use HTTPS (ngrok provides TLS) |
| **No token-based auth** | No JWT/OAuth2, no token revocation | Session-based for browser; Basic for API |
| **No rate limiting** | Potential DoS on endpoints | Not exposed publicly |
| **No audit logging beyond investigations** | Limited security event visibility | Investigation history provides decision audit |
| **CSRF SameSite=Lax** | May not protect against all CSRF variants | Demo only; production needs SameSite=Strict + explicit origin checks |
| **No security headers (CSP, HSTS, etc.)** | Browser security features not enabled | Add via Spring Security headers config for production |
| **No penetration testing** | Unknown vulnerabilities | Not performed for demo |
| **Secrets in env vars** | Not a secret manager (Vault, AWS Secrets Manager) | Acceptable for local demo |
| **Database credentials in env** | No rotation, no encryption at rest | PostgreSQL on trusted local machine |

---

## 10. Security Checklist for Demo Deployment

- [ ] Set `RETRYGUARD_API_USER` / `RETRYGUARD_API_PASSWORD` (strong, unique)
- [ ] Set `PAYPAL_CLIENT_ID` / `PAYPAL_CLIENT_SECRET` / `PAYPAL_WEBHOOK_ID` (Sandbox only)
- [ ] Set `GROQ_API_KEY` (if agent demo needed)
- [ ] Set `PAYPAL_PUBLIC_BASE_URL` to ngrok HTTPS URL
- [ ] Run `ngrok http 8080` for HTTPS tunnel
- [ ] Configure PayPal Sandbox webhook URL to `https://<ngrok>/api/webhooks/paypal`
- [ ] Verify `application-local.yaml` is gitignored and not committed
- [ ] Verify no secrets in `git log`
- [ ] Test: Dashboard loads → login works → CSRF cookie set → mutating requests succeed
- [ ] Test: Invalid signature webhook → rejected
- [ ] Test: Duplicate webhook → DUPLICATE state
- [ ] Test: Agent workflow → advisory only, no state changes without reconciliation

---

## 11. Incident Response (Demo Context)

| Incident | Response |
|----------|----------|
| Compromised API credentials | Rotate `RETRYGUARD_API_USER` / `RETRYGUARD_API_PASSWORD`; restart app |
| Compromised PayPal credentials | Rotate in PayPal Sandbox dashboard; update env vars |
| Compromised Groq API key | Rotate in Groq console; update env var |
| Suspicious webhook activity | Check `payment_events` table; verify signatures; block IP at network level |
| Database access concern | Use `retryguard_app` restricted role (see `retryguard_app.sql`) |

---

*This security documentation reflects the actual implemented controls in `SecurityConfig`, `SecurityUserConfig`, `CsrfCookieFilter`, `PaypalWebhookService`, `AgentWorkflowPersistenceService`, and `ReconciliationSafetyService`. The application is configured for local Sandbox demonstration only and should not be exposed publicly without completing the production hardening work noted in §9.*