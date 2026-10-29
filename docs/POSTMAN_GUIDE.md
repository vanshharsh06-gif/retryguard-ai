# Postman Quick Start

## Before opening Postman

1. Install and select Java 21. This project targets Java 21; the current machine's active Java 17 runtime cannot run the application.
2. Start PostgreSQL with the intended RetryGuard database and set `DB_PASSWORD` in the same PowerShell process that will run Spring Boot. Do not paste credentials into Postman or commit them.
3. Apply `src/main/resources/db/migration/V2__paypal_payment_references.sql` to the intended database before starting the application. It adds the two nullable PayPal reference/status columns required by Hibernate schema validation. This SQL has not yet been applied in the current documented database.
4. Start the API from PowerShell:

```powershell
$env:DB_PASSWORD = "<your-local-database-password>"
.\mvnw.cmd spring-boot:run
```

Set optional Gemini configuration in that same shell before starting the server:

```powershell
$env:GEMINI_API_KEY = "<your-Gemini-API-key>"
```

Never share the key in chat, a Postman request, screenshots, terminal output, or a committed environment export. The backend sends the key to Gemini; Postman calls only the local RetryGuard API.

Check the local API before importing the collection:

```text
GET http://localhost:8080/actuator/health
```

Expected response includes `"status":"UP"`.

## Import the safe flow

1. In Postman, choose **Import** and select `postman/RetryGuard AI.postman_collection.json`.
2. Import `postman/RetryGuard AI Local.postman_environment.json` and select the `RetryGuard AI Local` environment.
3. Run the requests in **Safe local investigation flow** in order. The scripts generate unique order/event references and save returned IDs into the environment.
4. Expected progression:
   - Order and attempt: `201 Created`.
   - First simulated event: `201 Created`, source `SIMULATED`, verification `NOT_APPLICABLE`.
   - Repeat event ID: `200 OK`, same saved event ID and original event type.
   - Timeline: `200 OK`; payload is omitted.
   - Investigation using only local simulated evidence: `200 OK` and `WAIT`.
   - Explanation: `200 OK`; response reports either `GEMINI` or `DETERMINISTIC_FALLBACK`.

These requests create local database records. They do not charge a customer or contact PayPal. Keep test payloads free of card data, secrets, and unnecessary personal information.

## Check whether Gemini is actually being used

The environment template defaults `expectGemini` to `false`, so the explanation request accepts either the live provider result or a deterministic fallback and logs the returned source in Postman's Console.

To require a live response:

1. Configure a valid `GEMINI_API_KEY` in the PowerShell process that starts the Spring Boot server, then restart the server.
2. Set `expectGemini` to the string `true` in the selected Postman environment.
3. Run **7. Ask Gemini to explain persisted decision** after creating a new investigation.
4. The Postman test passes only when the response source is `GEMINI`. `DETERMINISTIC_FALLBACK` means the application did not use a valid Gemini response; consult the server's sanitized error logs and verify key/model/API availability without printing the key.

No key was configured and no live Gemini request has been made in the current environment. Mocked tests establish request/response handling, not live account access, quotas, billing, or model availability.

## Optional provider workflows

- The **Optional PayPal Sandbox actions** folder is separate from the safe flow. Use only after the additive V2 migration and Sandbox credentials are configured. Open the returned buyer approval URL and complete Sandbox approval before requesting capture.
- A webhook requires a real PayPal transmission and a reachable registered Sandbox webhook URL. Do not fabricate valid-looking signature headers or use simulated events to test signature verification.
- These APIs currently have no application authentication; keep the service on a trusted local network.
