# Test Plan and Current Results

## Completed

- Java 21 main/test compile:
  `.\mvnw.cmd -q -DskipTests test`
  Passed.
- Focused regression selection:
  `.\mvnw.cmd -q '-Dtest=InvestigationServiceTest,InvestigationControllerTest,InvestigationQueryControllerTest,InvestigationExplanationServiceTest,InvestigationExplanationControllerTest,GeminiExplanationProviderTest,MerchantOrderControllerTest,PaypalWebhookControllerTest,PaypalWebhookEventProcessorTest,PaypalWebhookServiceTest' test`
  Passed 58 tests, 0 failures, 0 errors, 0 skipped. Coverage includes finding categories/evidence references and unchanged deterministic decisions, retrieval/list/history response and error mapping, Gemini sanitization/fallback, order listing, and webhook invalid-signature/duplicate protection.
- Dashboard inline JavaScript parse check using Node:
  `node -e "const fs=require('fs');const vm=require('vm');const html=fs.readFileSync('src/main/resources/static/index.html','utf8');const script=html.split('<script>')[1]?.split('</script>')[0];if(!script)throw new Error('Dashboard script missing');new vm.Script(script);console.log('Dashboard JavaScript syntax OK');"`
  Passed. No frontend package manager/build/test pipeline exists.

## Full-suite result and blocker

The normal Java 21 command `.\mvnw.cmd test` executed 112 tests and ended with 0 assertion failures, 7 errors, and 2 skipped. Errors occurred while Spring Boot integration/application tests tried to initialize PostgreSQL: the configured server required SCRAM authentication but no password was available (`DB_PASSWORD` is unset). A separate read-only `psql` query also failed with “no password supplied.” The full-suite integration errors therefore do not verify or falsify the new findings persistence behavior.

`InvestigationPersistenceIntegrationTest` is added to verify JSONB findings, run listing, and persisted chronological history after a PostgreSQL connection is available. It has not passed in this environment. It also requires the V3 findings migration.

## Required rerun after database setup

1. Inspect the intended schema and apply only missing additive migrations V1, V2, and V3 (V1 requires checking for duplicate provider event keys). Commands are documented in `README.md`.
2. Configure `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` for the target local database.
3. Run `.\mvnw.cmd test` on Java 21 and confirm Spring schema validation plus `InvestigationPersistenceIntegrationTest` pass.
4. Manually verify the browser flow at `http://localhost:8080/`.

PayPal and Gemini tests use mocked HTTP/providers; live calls, credentials, Sandbox approvals/captures, and webhook deliveries are not validated by the test suite.
