# ARCHITECTURE (simple language)

> **Implementation status (2026-10-23):** The schema and entity design below remain the data-model reference. Implemented routes include switch, merchant, account, payment initiation/event history, journal headers, and balanced posting batches. Consult `README_RUN.md`, `HANDOFF.md`, and `PROJECT_STATUS.md` for current route details. Journal reversal and lifecycle transitions, settlement processing, reconciliation, and user/role APIs remain unimplemented.

## 1. Layered flow (no service layer)
Angular (screens) → **Controller** (REST + business logic) → **Repository** (Spring Data JPA) → **PostgreSQL**.
Why: the guide's rule is "business logic lives in controllers". Entities double as request/response JSON, so there are no DTOs.
Safety rules because entities are exposed: all relations are one-way `@ManyToOne` (no JSON loops), lazy proxies are hidden with `@JsonIgnoreProperties({"hibernateLazyInitializer","handler"})`, and `User.passwordHash` is `WRITE_ONLY` (accepted on create, never returned).

## 2. Package structure (`com.yourorg.payops`)
| Folder | Contains |
|---|---|
| controller | REST controllers (+ proposed security classes, see §6) |
| entity | 13 entities + `CreatedAudit`, `Auditable` (mapped superclasses, not tables) |
| repository | one `JpaRepository` per entity |
| model | 14 enums, `ApiError` (plain class) |
| exception | `ResourceNotFoundException`, `BusinessRuleException`, `GlobalExceptionHandler` |

## 3. Payment lifecycle (touches all 13 entities)
1. **User** (with **Role** OPS_ENGINEER/ADMIN) logs in and calls the API.
2. **Merchant**'s customer pays → controller creates **Payment** (INITIATED), choosing a **SwitchDatabase** (merchant default, else highest-priority ACTIVE).
3. Controller writes a **PaymentEvent** INITIATED; then AUTHORIZED, CAPTURED (or FAILED) as the switch answers — one event per state change.
4. On CAPTURED the controller creates a **JournalEntry** (PAYMENT_CAPTURE) with two **Postings** against **Accounts** (§4).
5. The bank sends a **SettlementFile** (linked to its SwitchDatabase) containing **SettlementRecords**, one per transaction.
6. A **ReconciliationRun** compares records to Payments by `payment_reference = payment.reference` and writes **ReconciliationResults**: MATCHED, AMOUNT_MISMATCH, MISSING_IN_SETTLEMENT (payment with no record), MISSING_IN_PAYMENTS (record with no payment), DUPLICATE (reference appears twice).
7. For MATCHED records the controller sets `settlement_record.payment_id`, posts the settlement JournalEntry, and moves Payment to SETTLED (+ PaymentEvent SETTLED).

## 4. Double-entry rules (default chart of accounts is seeded)
Every submitted posting batch: total debits = total credits (controller checks before saving; view `v_unbalanced_journal` is a database audit reference). Journal headers and postings are separate calls because the existing entities/schema have no journal status or embedded posting collection. The API exposes no finalize/post operation.
| Event | Debit | Credit |
|---|---|---|
| Capture of A | 1100 Acquirer Receivable (A) | 2100 Merchant Payable (A) |
| Refund of R | 2100 Merchant Payable (R) | 1100 Acquirer Receivable (R) |
| Settlement of record (amount A, fee f) | 1200 Bank Cash (A−f) and 5100 Settlement Fee Expense (f) | 1100 Acquirer Receivable (A) |
Why: when captured we are owed money by the acquirer (asset) and owe the merchant (liability). When the bank pays, the receivable is cleared. Journal entries are never edited; corrections are new entries. *Assumption:* fee is borne by the platform, not deducted from the merchant.

## 5. Validation, exceptions, transactions
- **Validation:** field rules via Bean Validation on entities (`@NotBlank`, `@Positive`, `@Size`) + `@Valid @RequestBody` in controllers; business rules (balanced journal, refund ≤ amount, legal state transition) checked in controller, throwing `BusinessRuleException`. Last line of defence: DB CHECK/UNIQUE/FK.
- **Exceptions:** one `GlobalExceptionHandler` (`@RestControllerAdvice`) → 400 / 404 / 409 / 422 with `ApiError`.
- **Transactions:** put `@Transactional` on the *controller method* that writes more than one table: capture (Payment + PaymentEvent + JournalEntry + Postings), refund, settlement posting, reconciliation run. All succeed or all roll back. Read-only endpoints: `@Transactional(readOnly = true)` optional.

## 6. Spring Security + JWT without a config folder (FLAG: needs guide approval)
Closest compliant placement: `AuthController` (login → JWT) and `JwtAuthFilter extends OncePerRequestFilter` in **controller**; `JwtUtil` (plain class) in **model**; the `SecurityFilterChain` `@Bean` inside the main `PayopsApplication` class (root package, not a folder). Role checks via `@PreAuthorize("hasRole('FINANCE')")` on controller methods. Alternative if guide objects: put the bean in controller as `SecurityConfiguration`.

## 7. API catalog (all under `/api`)
| Entity | Endpoints | Roles (default) |
|---|---|---|
| Auth/User | POST /auth/login; GET/POST /users; GET/PUT /users/{id}; PUT /users/{id}/roles | ADMIN |
| Role | GET /roles | ADMIN |
| SwitchDatabase | GET/POST /switches; GET/PUT /switches/{id}; PATCH /switches/{id}/status | read: all; write: ADMIN, OPS |
| Merchant | GET/POST /merchants; GET/PUT /merchants/{id} | read: all; write: ADMIN, OPS |
| Payment | POST /payments; GET /payments?status=&merchantId=&from=&to=; GET /payments/{id}; GET /payments/by-reference/{ref}; POST /payments/{id}/authorize, /capture, /fail, /refund | OPS; read: SUPPORT, FINANCE |
| PaymentEvent | GET /payments/{id}/events (read-only; created by Payment actions) | all |
| Account | GET/POST /accounts; GET/PUT /accounts/{id}; GET /accounts/{id}/postings | FINANCE, ADMIN |
| JournalEntry | GET /journals?paymentId=; GET /journals/{id}; POST /journals (manual, must balance); POST /journals/{id}/reverse | FINANCE |
| Posting | GET /journals/{id}/postings; GET /postings?accountId= (created with the journal) | FINANCE |

Implemented accounting routes use `/api/journal-entries`: `POST`/`GET /{id}` for journal headers and `POST`/`GET /{journalEntryId}/postings` for lines. Posting creation accepts a complete list, checks positive amounts, existing accounts, required directions, and debit/credit equality using `BigDecimal.compareTo`, then saves the batch transactionally. Journal numbers are unique. Reversal remains unsupported because neither the Posting entity nor SQL table contains a reversal marker/reference. The existing schema also has no finalized/posted state.
| SettlementFile | POST /settlement-files (upload/parse); GET /settlement-files?status=; GET /settlement-files/{id}; DELETE /settlement-files/{id} (only if not RECONCILED) | OPS, FINANCE |
| SettlementRecord | GET /settlement-files/{id}/records; GET /settlement-records?paymentReference= | OPS, FINANCE |
| ReconciliationRun | POST /reconciliation-runs {settlementFileId}; GET /reconciliation-runs; GET /reconciliation-runs/{id} | OPS, FINANCE |
| ReconciliationResult | GET /reconciliation-runs/{id}/results?type= | OPS, FINANCE |
