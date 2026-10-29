# PayOps

An educational payment processing and reconciliation simulator. A merchant initiates a
payment, it is routed to a simulated acquirer bank, the result is recorded in a double-entry
ledger, and a bank settlement file is later reconciled against the internal records.

This is a learning project. It does not process real card payments or connect to any real
banking network.

## Features

- Payment lifecycle: initiate, route to a switch, approve / decline / pending, with an event log per payment
- Simulated acquirer bank (separate service) with fictional cards, balances and response delays
- Double-entry ledger: every approved payment creates a balanced journal entry (debit = credit)
- Settlement file upload and CSV reconciliation with exception detection
- JWT authentication with role-based access (ADMIN, MERCHANT, and others)
- Angular operations console for payments, ledger, settlements and reconciliation

## Tech stack

| Layer     | Technology                         |
|-----------|------------------------------------|
| Backend   | Spring Boot 4.1, Java 21           |
| Frontend  | Angular 22                         |
| Database  | PostgreSQL (Supabase or local)     |
| Fake bank | Spring Boot (separate module)      |
| Auth      | Spring Security + JWT (jjwt)        |

## Project structure

```
src/            Main payment service (Spring Boot)
fake-bank/      Simulated acquirer bank service
frontend/       Angular operations console
sql/            Database schema and seed scripts
postman/        Postman collection for the API
docs/           Architecture notes, data dictionary, diagrams
```

## Prerequisites

- Java 21
- Maven 3.9+ (or the included `mvnw` / `mvnw.cmd` wrapper)
- Node.js 22.22.3+ (for the Angular frontend)
- PostgreSQL 14+ or a Supabase project

## Configuration

Copy `.env.example` to `.env` and fill in your values:

```
PAYOPS_DB_URL=jdbc:postgresql://localhost:5432/payops
PAYOPS_DB_USERNAME=postgres
PAYOPS_DB_PASSWORD=your-password
PAYOPS_JWT_SECRET=at-least-32-bytes-of-random-secret
```

`PAYOPS_JWT_SECRET` is required and has no default. Generate one with:

```bash
openssl rand -base64 48
```

Secrets are never committed to source control.

## Database setup

Apply the schema and demo data to your database:

```bash
psql -U postgres -d payops -f sql/fresh-install.sql
```

Hibernate runs with `ddl-auto=validate`, so it checks the entity mappings against this schema
and never alters existing tables.

## Running

Start the three services in separate terminals.

**1. Fake bank** (port 8081)

```bash
./mvnw spring-boot:run -pl fake-bank
```

**2. Payment service** (port 8080)

```bash
./mvnw spring-boot:run
```

**3. Frontend** (port 4200)

```bash
cd frontend
npm install
npm start
```

On Windows you can start the two backend services together with `./start-dev.ps1`.

Then open http://localhost:4200.

## Demo

**Login**

| User      | Password        | Role     |
|-----------|-----------------|----------|
| admin     | `Admin@123!`    | ADMIN    |
| merchant1 | `Merchant@123!` | MERCHANT |

**Test cards** (fictional)

| Card number           | Result   | Why                                      |
|-----------------------|----------|------------------------------------------|
| `4111111111111111`    | APPROVED | Funded (balance 50,000)                  |
| `4000000000000002`    | DECLINED | Insufficient funds (balance 5)           |
| `4000000000000259`    | PENDING  | Delayed bank response, resolved by a recovery job |

## API documentation

With the payment service running, the Swagger UI is at
http://localhost:8080/swagger-ui/index.html.

## Tests

```bash
./mvnw test
./mvnw test -pl fake-bank
```
