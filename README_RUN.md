# PayOps build and run

## Requirements

- **Java**: Java 21 LTS (OpenJDK 21.0.9+). `pom.xml` targets Java 21.
- **Maven**: Maven 3.9+ or the included Maven Wrapper (`mvnw` / `mvnw.cmd`).
- **Node.js**: Node.js `>=22.22.3` (required by Angular 22 per `frontend/package.json` engines: `^22.22.3 || ^24.15.0 || ^26.0.0`).
  *Note:* The system currently has Node.js `v20.19.6` installed. You must upgrade Node.js manually (e.g. via `winget install OpenJS.NodeJS.LTS` or `nvm`) to build or run the Angular frontend.
- **PostgreSQL**: PostgreSQL 14 or later (or Supabase).

Configure these environment variables before running:

- `PAYOPS_DB_URL` (default `jdbc:postgresql://localhost:5432/payops`)
- `PAYOPS_DB_USERNAME` (default `postgres`)
- `PAYOPS_DB_PASSWORD` (default empty)
- `PAYOPS_JWT_SECRET` (**required**, no default fallback)

No database password or JWT signing secret is stored in source control.

### JWT Signing Secret Configuration (`PAYOPS_JWT_SECRET`)

The main switch requires `PAYOPS_JWT_SECRET` for HMAC-SHA256 signing of JSON Web Tokens.
- **Requirement:** At least 32 bytes (256 bits) of cryptographically random secret material.
- If missing, blank, or fewer than 32 bytes, application startup fails immediately with a clear configuration error. Shorter secrets are never silently hashed.
- **Do not commit secrets to source control or check them into `application.properties`.**

#### Generating a Secure Secret

**Option 1: OpenSSL**
```bash
openssl rand -base64 32
# or hex:
openssl rand -hex 32
```

**Option 2: PowerShell**
```powershell
$bytes = [byte[]]::new(32); [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes); [Convert]::ToBase64String($bytes)
```

**Option 3: Python**
```bash
python -c "import secrets; print(secrets.token_urlsafe(32))"
```

#### Configuring the Secret in Your Environment

**Windows PowerShell:**
```powershell
$env:PAYOPS_JWT_SECRET = "your-securely-generated-secret-at-least-32-bytes"
```

**Linux / macOS Bash:**
```bash
export PAYOPS_JWT_SECRET="your-securely-generated-secret-at-least-32-bytes"
```

**Production / Docker / Kubernetes:**
Inject `PAYOPS_JWT_SECRET` via environment secrets (e.g. Docker secrets, Kubernetes Secret, or AWS Secrets Manager / HashiCorp Vault), never stored in repository files.

## Prepare the database

### Option A: Fresh local database setup (Dev only)
From the project root, create the database if needed, then apply `sql/schema.sql`:

```powershell
createdb -U postgres payops
psql -U postgres -d payops -f sql\schema.sql
```

**Warning:** `sql/schema.sql` drops and recreates the project tables and view before creating them. Use it only with a disposable development database.

### Option B: Additive Phase 1 migration
To apply the non-destructive Phase 1 schema changes (roles, payment statuses, idempotency keys, masked cards, fake-bank tables, and demo cards) to an existing database:

```powershell
psql -U postgres -d payops -f sql\migrate-phase1.sql
```

### Fake-Bank Service (Spring Boot on Port 8081)

The fake bank is a separate lightweight Spring Boot process that owns fictional card storage, balance deductions, duplicate debit prevention, and simulated response delays.

Using the Maven Wrapper:
```powershell
# Run unit & integration tests
.\mvnw.cmd test -f fake-bank\pom.xml

# Start fake bank service (runs on http://localhost:8081)
.\mvnw.cmd spring-boot:run -f fake-bank\pom.xml
```

On Linux/macOS:
```bash
./mvnw test -f fake-bank/pom.xml
./mvnw spring-boot:run -f fake-bank/pom.xml
```

### Main Payment Switch (Spring Boot on Port 8080)

```powershell
# Run unit, integration, and concurrency tests
.\mvnw.cmd test

# Start main payment switch (runs on http://localhost:8080)
.\mvnw.cmd spring-boot:run
```

On Linux/macOS:
```bash
./mvnw test
./mvnw spring-boot:run
```

Hibernate uses `ddl-auto=validate`; startup checks entity mappings against the prepared schema and does not alter existing tables.

### Frontend (Angular)

*Requires Node.js >= 22.22.3.*

```powershell
cd frontend
npm install
npm run start
```

## Payment Authorization & Demo Scenarios

The main switch connects to the fake bank at `http://localhost:8081` (configurable via `fakebank.url`) with a **5-second request timeout**.

### Fictional Demo Cards

| Card Number | Starting Balance | Purpose / Expected Result |
|---|---|---|
| `4111111111111111` | ₹50,000.00 | **Funded Card**: Returns `APPROVED`. Debits bank balance once. Automatically generates 2 balanced double-entry ledger postings (Dr 1100 Acquirer Receivable, Cr 2100 Merchant Payable). |
| `4000000000000002` | ₹5.00 | **Insufficient Funds Card**: Returns `DECLINED` for charges > ₹5.00. No balance debit, no ledger postings. |
| `4000000000000259` | ₹10,000.00 | **Delayed Card (ends in 9)**: Simulates 30s bank response delay. Switch times out at 5s and marks payment `PENDING` for recovery. No false decline. |
| `4111111111111112` | N/A | **Invalid Luhn Card**: Fails checksum validation immediately on the switch (HTTP 400). Bank is never called. |

### Example 1: Submit an Approved Card Payment

```powershell
curl.exe -X POST http://localhost:8080/api/payments `
  -H "Content-Type: application/json" `
  -H "Idempotency-Key: pay-card-demo-001" `
  -d '{"reference":"ref-001","merchant":{"id":1},"amount":250.00,"currency":"INR","method":"CARD","cardNumber":"4111111111111111"}'
```

Result: HTTP 201 Created with `status: APPROVED`, masked card `411111******1111`, and `bankReference`. Automatically creates journal entry `JE-APP-ref-001` with balanced postings.

### Example 2: Idempotent Replay

Re-sending the exact same request with header `Idempotency-Key: pay-card-demo-001` returns the original result immediately without calling the bank again or duplicating ledger postings.

Re-sending the same key with a modified amount or card returns **HTTP 409 Conflict**.

### Example 3: Fake-Bank Status Lookup (for Timeout Recovery)

```powershell
curl.exe http://localhost:8081/api/bank/payments/{bankRequestId}
```

## Current API routes

Implemented routes:

- Switches: `GET/POST /api/switches`, `GET/PUT /api/switches/{id}`, `PATCH /api/switches/{id}/status`
- Merchants: `GET/POST /api/merchants`, `GET/PUT /api/merchants/{id}`, `PATCH /api/merchants/{id}/status`
- Payments: `GET /api/payments` (optional `status`, `merchantId`, `from`, `to` filters), `GET /api/payments/{id}`, `GET /api/payments/by-reference/{reference}`, `POST /api/payments` (supports card payments, Luhn validation, masking, idempotency, fake-bank integration, timeout handling)
- Payment event history: `GET /api/payments/{paymentId}/events`
- Accounts: `GET/POST /api/accounts`, `GET/PUT /api/accounts/{id}`, `PATCH /api/accounts/{id}/active`
- Journals: `POST /api/journal-entries`, `GET /api/journal-entries/{id}`
- Postings: `POST /api/journal-entries/{journalEntryId}/postings` (balanced batch only), `GET /api/journal-entries/{journalEntryId}/postings`
- Fake-Bank: `POST /api/bank/payments` (authorization), `GET /api/bank/payments/{requestId}` (status lookup)

There are no delete routes. Payment creation starts at `INITIATED`, selects the merchant's active default switch or the first active switch by priority, and writes the initial payment event atomically. A journal header is created separately; posting lines are accepted as one positive-amount debit/credit-balanced batch and saved atomically. The schema has no journal lifecycle/status field, so no finalize/post transition is exposed. Reversal is not implemented because the current schema has no reversal marker/reference. Authorization, capture, failure, refund, settlement, reconciliation, user management, and security routes are not implemented. APIs are currently unauthenticated; use only in a trusted local development environment.

Example: create a payment for an existing active merchant (`id` 1). The route is selected by the server:

```powershell
curl.exe -X POST http://localhost:8080/api/payments `
  -H "Content-Type: application/json" `
  -d '{"reference":"pay-demo-001","merchant":{"id":1},"amount":125.50,"currency":"INR","method":"UPI"}'
```

Query it and its event history:

```powershell
curl.exe http://localhost:8080/api/payments/by-reference/pay-demo-001
curl.exe http://localhost:8080/api/payments/1/events
```
