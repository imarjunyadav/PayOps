# PayOps developer handoff

Last updated: 2026-10-23. This describes the actual checked-in code after the Phase 3 accounting increment.

## 1. What PayOps is

PayOps is a payment operations backend foundation for routing merchant payments, recording ledger entries, ingesting bank settlement files, and reconciling those files against payments. The workspace contains the data model and database design, Spring Boot build foundation, Phase 2 master-data/payment-initiation APIs, and journal/posting operations for balanced posting batches. Capture/refund, settlement, and reconciliation workflows are not implemented.

## 2. Purpose

The project is intended to become a handoff-ready backend that another developer can extend without inferring table names, relationships, lifecycle intent, or local run setup from scratch. Existing schema and documentation are the design reference. Preserve that model unless an implementation cannot proceed without a clearly documented decision.

## 3. Technology stack

- Java 25
- Maven
- Spring Boot 4.1.1
- Spring MVC / embedded Tomcat
- Spring Data JPA / Hibernate
- Jakarta Bean Validation
- PostgreSQL JDBC driver
- JUnit Jupiter and Spring Boot test support
- Spring Security/JWT is intentionally not included yet

## 4. Package structure

The application package is `com.yourorg.payops`, with only the approved subpackages: `controller`, `entity`, `exception`, `model`, and `repository`. No service, DTO, mapper, config, or enums package is used. Business logic belongs in controllers under the agreed architecture.

## 5. The 13 business entities

1. `SwitchDatabase` → `switch_database`
2. `Merchant` → `merchant`
3. `Payment` → `payment`
4. `PaymentEvent` → `payment_event`
5. `Account` → `account`
6. `JournalEntry` → `journal_entry`
7. `Posting` → `posting`
8. `SettlementFile` → `settlement_file`
9. `SettlementRecord` → `settlement_record`
10. `ReconciliationRun` → `reconciliation_run`
11. `ReconciliationResult` → `reconciliation_result`
12. `User` → `app_user`
13. `Role` → `role`

`Auditable` and `CreatedAudit` are mapped audit superclasses, not business entities or tables.

## 6. Supporting classes

- `CreatedAudit` maps `created_at` and `created_by`; it initializes creation time at persist. `created_by` is a username string by design.
- `Auditable` extends it with `updated_at`, updated on persist/update.
- Model enums represent persisted enum strings; `ApiError` is a plain HTTP error response model, not a table.

## 7. Database architecture

`sql/schema.sql` creates the 13 entity tables and the `user_role` join table, associated constraints and indexes, a `v_unbalanced_journal` view, four role seed rows, and four chart-of-account rows. It is a development bootstrap script that drops and recreates objects. Do not apply it to data that must be preserved. Hibernate is configured with `ddl-auto=validate`; Spring SQL initialization is disabled so startup will not execute the destructive script.

## 8. Important relationships

- Merchant can have an optional default switch; a merchant has many payments.
- Payment uses a merchant and switch and has many payment events.
- Account can optionally belong to a merchant.
- Journal entries can optionally link to a payment or settlement record; postings belong to a journal and account.
- Settlement files belong to a switch and contain settlement records; a record can link to a payment.
- Reconciliation runs belong to a settlement file; results belong to a run and may link to a record/payment.
- User and Role are many-to-many through `user_role`.
- Database foreign-key deletion rules are documented in `sql/schema.sql` and `docs/DATA_DICTIONARY.md`.

## 9. API/controller structure

Implemented controllers are `SwitchDatabaseController`, `MerchantController`, `PaymentController`, `PaymentEventController`, `AccountController`, and `JournalEntryController`. `docs/ARCHITECTURE.md` includes a wider planned API catalog; routes outside the list here are not implemented.

- Switch: list/get/create/replace and status patch.
- Merchant: list/get/create/replace and status patch.
- Payment: filtered list, get by ID/reference, and create in `INITIATED` state.
- PaymentEvent: read-only ordered history by payment.
- Account: list/get/create/replace and active flag patch.
- JournalEntry: create journal header and get by ID.
- Posting: get a journal's postings and create a complete balanced posting batch for a journal.

There are no delete routes. These controllers use entity bodies and controller-owned logic, as required by the architecture.

## 10. Business rules

Payment creation requires an existing active merchant. The controller uses that merchant's active default switch when available; otherwise it chooses the first active switch ordered by priority. It creates the payment in `INITIATED` status with a zero refunded amount and creates its `INITIATED` event inside one transaction. The caller cannot submit a captured/settled state through this endpoint. Authorization/capture/refund/fail operations are not implemented; do not allow callers to set lifecycle states arbitrarily before those operations exist.

## 11. Accounting rules

`JournalEntryController` creates journal headers and accepts posting lines only as a balanced batch. The controller verifies the journal and each account, rejects missing directions and non-positive amounts, compares debit/credit totals with `BigDecimal.compareTo`, and saves the posting batch transactionally. Duplicate journal numbers conflict. There is no explicit finalize/post state in SQL or the entity, and the API exposes no such transition. Reversal remains unsupported: `Posting` and `posting` have no reversal flag or reference, and no schema/entity change was made.

## 12. Settlement behavior

The schema models a received file and its individual records. Records preserve the bank-provided `payment_reference` and may later link to a matched payment. File parsing, upload, status transitions, and settlement posting are not implemented.

## 13. Reconciliation behavior

The schema and docs list outcomes `MATCHED`, `AMOUNT_MISMATCH`, `MISSING_IN_SETTLEMENT`, `MISSING_IN_PAYMENTS`, and `DUPLICATE`. Runs/results have tables and repositories, but matching behavior and lifecycle/status transitions are not implemented.

## 14. Security status

Spring Security, JWT, password hashing, login, and authorization are not implemented. `User.passwordHash` is marked `WRITE_ONLY` for JSON serialization; this does not hash passwords or implement authentication. Do not accept or store plaintext passwords when adding the security phase.

## 15. Completed

- Existing schema/design package and entity/repository classes retained.
- Maven project foundation and Boot entry point added.
- Environment-based PostgreSQL settings added without secrets.
- Hibernate schema validation enabled; automatic SQL initialization disabled.
- Exception mapping tests added for 400, 404, 409, and 422 responses.
- `README_RUN.md`, `PROJECT_STATUS.md`, and this handoff updated.
- Baseline `mvn clean compile` and `mvn test` passed under Java 25; 22 tests passed before Phase 3 work.
- Phase 3 journal/posting controller, duplicate journal-number query, and 9 focused accounting tests added.

## 16. Partially completed

- Entity validation is present for many individual fields; some cross-field and range constraints are enforced only in SQL.
- The exception advice handles known validation, not-found, business-rule, and data-integrity exceptions; it has no generic catch-all handler.
- PostgreSQL schema validation is unverified. `PAYOPS_DB_URL`, `PAYOPS_DB_USERNAME`, and `PAYOPS_DB_PASSWORD` were unset during this work; no schema script was applied.

## 17. Not completed

- No payment capture/refund, settlement, reconciliation, user/role operations.
- No posting reversal; the current schema has no reversal representation.
- No journal lifecycle/finalized status; the current schema has no such field.
- No settlement file ingestion or reconciliation algorithm.
- No user/role management API or security.
- No database migration tool; `schema.sql` is not incremental.

## 18. Known issues

- `docs/DATA_DICTIONARY.md` describes `role.name` as unique; neither SQL nor Java applies uniqueness. Resolve this discrepancy before changing schema/model.
- Bean Validation does not duplicate every database check. SQL remains the last line of defense for several ranges and cross-field rules.
- There is no confirmed local PostgreSQL connection/schema validation run.
- Maven emits deprecation warnings from `GlobalExceptionHandler`; compilation succeeds, but the affected API can be reviewed during the exception layer's next maintenance pass.

## 19. Known architectural decisions

- Exactly 13 business entities; audit superclasses are not entities.
- Keep the approved package structure and controller-owned business logic; do not add services, DTOs, mappers, config, or enums packages.
- Use `BigDecimal` for money, enum strings, PostgreSQL schema as the database reference, and environment variables for credentials.
- Spring Security/JWT is reserved for the final security phase.
- `SwitchDatabase` is currently documented as a routing profile for bank/acquirer/network, not a database connection entity.

## 20. PostgreSQL configuration

Set `PAYOPS_DB_URL`, `PAYOPS_DB_USERNAME`, and `PAYOPS_DB_PASSWORD`. Defaults are localhost database `payops`, username `postgres`, and empty password. Create the database and apply `sql/schema.sql` manually to a disposable development database. The schema script drops/recreates project tables and view. Never commit real credentials.

## 21. Run the application

From the workspace root, after PostgreSQL and schema setup:

```powershell
mvn spring-boot:run
```

Startup uses `ddl-auto=validate`. Without a reachable database containing the expected schema, startup will fail by design.

## 22. Compile

```powershell
mvn clean compile
```

## 23. Run tests

```powershell
mvn test
```

Current tests include MockMvc coverage for switch/merchant/account create flows, payment and payment-event HTTP routes, direct payment-controller behavior tests, and exception-handler tests. They do not require PostgreSQL and do not verify the schema against a live database.

## 24. Example API requests

Example request to initiate a payment for an existing active merchant (switch routing is selected by the controller):

```powershell
curl.exe -X POST http://localhost:8080/api/payments `
  -H "Content-Type: application/json" `
  -d '{"reference":"pay-demo-001","merchant":{"id":1},"amount":125.50,"currency":"INR","method":"UPI"}'
```

The endpoint returns `201 Created`, with a `Location` header for the new payment. Query the payment and its initial event using:

```powershell
curl.exe http://localhost:8080/api/payments/by-reference/pay-demo-001
curl.exe http://localhost:8080/api/payments/1/events
```

These examples require a configured database with an active merchant and switch. Automated tests use mocked repositories; no database-backed request has yet been exercised.

## 25. Exact next recommended tasks

1. Configure a disposable PostgreSQL database, apply `sql/schema.sql`, start the app, and verify Hibernate schema validation and derived JPQL queries.
2. Keep reversal unsupported until its representation is approved; current `Posting` has no reversal marker/link.
3. Complete the accounting quality gate (final compile/test and, when a safely prepared database is available, schema validation).
4. Then proceed to settlement ingestion and record workflows, followed by reconciliation, user/role, and security phases.
