# TEAM CONTRACT (one page)
Everyone codes against `sql/schema.sql` + `docs/DATA_DICTIONARY.md`. Do not rename a table/column/enum value without team agreement. **Never change the shared rules below.**

## Shared rules
- Packages: controller, entity, repository, model, exception only. Logic in controllers. Entities are the JSON bodies.
- PK `Long` IDENTITY; money `BigDecimal` NUMERIC(19,4); time `LocalDateTime`; enums `@Enumerated(STRING)` + DB CHECK; tables snake_case singular; FK column `<entity>_id`; user table `app_user`.
- All relations are unidirectional `@ManyToOne` LAZY + `@JsonIgnoreProperties({"hibernateLazyInitializer","handler"})`. No `mappedBy` back-references.
- Audit: extend `Auditable` (created_at, created_by, updated_at) or `CreatedAudit` (append-only rows). `createdBy` = username String.
- Money fields > 0 where stated; amounts never `double`.

## Ownership (proposed split — confirm names; entity *ownership* of the 3 yours is fixed)
**Role** (`role`, Teammate A (Security)): id BIGINT; name enum RoleName; description VARCHAR(200)?; created_at, created_by

**User** (`app_user`, Teammate A (Security)): id BIGINT; username VARCHAR(50); email VARCHAR(100); password_hash VARCHAR(100); full_name VARCHAR(100)?; status enum UserStatus; last_login_at TIMESTAMP?; created_at, created_by, updated_at

**SwitchDatabase** (`switch_database`, Teammate B (Payments core)): id BIGINT; code VARCHAR(30); name VARCHAR(100); switch_type enum SwitchType; protocol enum SwitchProtocol; host VARCHAR(255)?; port INTEGER?; status enum SwitchStatus; priority INTEGER; timeout_ms INTEGER; created_at, created_by, updated_at

**Merchant** (`merchant`, Teammate B (Payments core)): id BIGINT; code VARCHAR(30); name VARCHAR(100); email VARCHAR(100)?; currency VARCHAR(3); status enum MerchantStatus; default_switch_id →switch_database?; created_at, created_by, updated_at

**Account** (`account`, Teammate C (Accounting)): id BIGINT; code VARCHAR(30); name VARCHAR(100); account_type enum AccountType; currency VARCHAR(3); active BOOLEAN; merchant_id →merchant?; created_at, created_by, updated_at

**Payment** (`payment`, Teammate B (Payments core)): id BIGINT; reference VARCHAR(50); merchant_id →merchant; switch_database_id →switch_database; amount NUMERIC(19,4); refunded_amount NUMERIC(19,4); currency VARCHAR(3); method enum PaymentMethod; status enum PaymentStatus; switch_reference VARCHAR(50)?; customer_ref VARCHAR(100)?; failure_reason VARCHAR(255)?; created_at, created_by, updated_at

**PaymentEvent** (`payment_event`, Teammate B (Payments core)): id BIGINT; payment_id →payment; event_type enum PaymentStatus; amount NUMERIC(19,4)?; message VARCHAR(255)?; occurred_at TIMESTAMP; created_at, created_by

**SettlementFile** (`settlement_file`, YOU (Settlement)): id BIGINT; file_name VARCHAR(255); switch_database_id →switch_database; settlement_date DATE; status enum SettlementFileStatus; record_count INTEGER; total_amount NUMERIC(19,4); received_at TIMESTAMP; created_at, created_by, updated_at

**SettlementRecord** (`settlement_record`, YOU (Settlement)): id BIGINT; settlement_file_id →settlement_file; payment_reference VARCHAR(50); payment_id →payment?; amount NUMERIC(19,4); fee NUMERIC(19,4); currency VARCHAR(3); transaction_date TIMESTAMP?; created_at, created_by

**JournalEntry** (`journal_entry`, Teammate C (Accounting)): id BIGINT; entry_number VARCHAR(40); source enum JournalSource; description VARCHAR(255)?; entry_date TIMESTAMP; payment_id →payment?; settlement_record_id →settlement_record?; created_at, created_by

**Posting** (`posting`, YOU (Settlement)): id BIGINT; journal_entry_id →journal_entry; account_id →account; direction enum PostingDirection; amount NUMERIC(19,4); description VARCHAR(255)?; created_at, created_by

**ReconciliationRun** (`reconciliation_run`, Teammate D (Reconciliation)): id BIGINT; run_number VARCHAR(40); settlement_file_id →settlement_file; status enum RunStatus; started_at TIMESTAMP; completed_at TIMESTAMP?; total_checked INTEGER; matched_count INTEGER; exception_count INTEGER; created_at, created_by

**ReconciliationResult** (`reconciliation_result`, Teammate D (Reconciliation)): id BIGINT; reconciliation_run_id →reconciliation_run; settlement_record_id →settlement_record?; payment_id →payment?; result_type enum ResultType; expected_amount NUMERIC(19,4)?; actual_amount NUMERIC(19,4)?; note VARCHAR(255)?; created_at, created_by

`user_role(user_id→app_user, role_id→role)` composite PK, ON DELETE CASCADE. Enums: see `model/` (14 files) — values are final.

## Key relationships
Merchant 1—* Payment; SwitchDatabase 1—* Payment/SettlementFile/Merchant(default); Payment 1—* PaymentEvent; JournalEntry 1—* Posting (≥2, balanced); Account 1—* Posting; SettlementFile 1—* SettlementRecord; SettlementRecord *—0..1 Payment; ReconciliationRun 1—* ReconciliationResult; User *—* Role.

## Open decisions (default answer applies unless team says otherwise)
| # | Decision | Default |
|---|---|---|
| D1 | `createdBy` String vs FK to app_user | **String username** — survives user deletion, no cross-owner FK, simple JSON. Trade-off: no referential integrity |
| D2 | What is SwitchDatabase? | **Routing profile of a bank/acquirer/network** (code, type, protocol, host, port, status, priority, timeout). No secrets stored. If it should be a real DB connection config, only this entity changes |
| D3 | PaymentEvent type enum | Reuses `PaymentStatus` values |
| D4 | Refunds | Tracked as `payment.refunded_amount` + PaymentEvent REFUNDED (partial refunds allowed); status REFUNDED when fully refunded |
| D5 | Who bears bank fee | Platform (expense 5100) |
| D6 | Roles stored as enum-valued names | `role.name` ∈ 4 fixed values |
| D7 | Delete policy | Financial rows RESTRICT; settlement records/recon results CASCADE from parent; optional links SET NULL |
| D8 | Currency | `VARCHAR(3)` default INR; no multi-currency FX in this phase |
| D9 | JWT/security placement | See ARCHITECTURE §6 |
| D10 | Ownership split A/B/C/D | Proposed above; swap names freely |
