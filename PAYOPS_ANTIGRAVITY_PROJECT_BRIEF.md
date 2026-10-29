# PayOps — Antigravity Project Brief

## 1. What we are building

**Project name:** PayOps — Enterprise Payment Operations Platform (Phase 1 MVP)

PayOps is a small, educational/demo payment-processing and operations system. It should demonstrate one complete payment lifecycle and the operational controls around it. It is not a production payment processor and must use fictional test cards and simulated bank responses only. It must not connect to real card networks or move real money.

**Primary goal:** deliver the smallest working application that satisfies every Phase 1 requirement end to end. A feature is not “done” just because a dashboard screen, database table, seed record, or API endpoint exists; the actual workflow must run and be tested.

## 2. Required Phase 1 MVP — acceptance checklist

### A. Payment screen
- Provide a minimal Angular screen with a card number field, amount field, and Pay button.
- Show the resulting payment status clearly: APPROVED, DECLINED, or PENDING.
- Include useful validation/error messages.
- Use fictional demo cards. Never use real card data.

### B. Main payment switch (existing Spring Boot app)
- Expose a payment endpoint (expected route: `POST /api/payments` or adapt the existing `/api/payments` route rather than adding a duplicate route).
- Validate card-number format and Luhn checksum before contacting the fake bank.
- Send the payment request to the fake-bank service over HTTP.
- Persist the payment and status/event history in PostgreSQL.
- Return a clear payment reference and state.
- Keep the full card number out of normal payment records, API responses, and logs; retain only what the fake-bank service needs and mask card details in UI/logs.

### C. Separate fake-bank service
- This must be a **separate, small Spring Boot service/process**, not just another class or controller inside the main switch app.
- It owns card/balance behavior and the `cards` data. It may use the same local PostgreSQL instance for simplicity, but only the fake-bank service should read/write its card balance table. Do not create a second database server unless absolutely required.
- Seed a few clearly documented fictional cards and starting balances.
- If the card has sufficient funds: approve it and debit the balance once.
- If funds are insufficient or the card does not exist: decline without a debit.
- A card number ending in `9` simulates a 30-second bank response delay/hang.
- The fake bank must expose a way to check a request's eventual state by a stable payment/bank reference, so the main switch can resolve timeouts later.
- Prevent duplicate bank debits when the same payment is retried or status is queried repeatedly.

### D. PostgreSQL and accounting
- Persist payment transactions in PostgreSQL.
- Money columns must use `NUMERIC(19,4)` (Java `BigDecimal`; do not use floating-point types for money).
- Every APPROVED payment must automatically create exactly two balanced ledger/posting entries. Use the existing journal-entry/posting model if it supports this cleanly; do not create a second, parallel accounting system just to match the word “ledger”.
- Debits must equal credits for the payment. The two entries must be created atomically with the approved payment transition; avoid a state where a payment is approved but its ledger is missing, or where retrying creates duplicate entries.
- DECLINED payments create no payment ledger entries.
- The fake-bank service owns the cards/balances table. The main switch must not bypass the fake-bank API to change balances directly.

### E. CSV reconciliation
- Provide an action to download/export a bank settlement CSV containing realistic demo rows and stable payment references.
- The operator can edit the downloaded CSV manually (e.g. change an amount or remove/add a row) and upload the edited file.
- Parse the uploaded CSV, validate its basic structure, and automatically compare rows with persisted payments. Do not require the user to manually create reconciliation results via API.
- At minimum report:
  - **MATCHED**: reference and amount agree.
  - **AMOUNT MISMATCH**: same reference exists but amount differs.
  - **MISSING IN BANK/SETTLEMENT**: payment exists but no matching CSV row exists.
  - **MISSING IN PAYMENTS**: CSV row exists but no matching payment exists.
- Show a concise summary and the row-level results in the Angular UI. Reuse existing settlement/reconciliation entities and APIs when practical.

### F. Login and roles
- Add login backed by JWT and password hashing.
- Implement exactly the two MVP roles: `MERCHANT` and `ADMIN`.
- `MERCHANT` can create/view payments belonging to their own merchant only. The backend must enforce this scope; hiding UI items alone is not security.
- `ADMIN` can view all payments and run/view reconciliation.
- Unauthenticated API requests and role-inappropriate requests must be rejected.
- Provide local-only demo credentials in the README using non-sensitive seeded credentials; do not hard-code production secrets.

### G. Timeout and recovery
- The switch's bank HTTP request timeout is **5 seconds**.
- If the fake bank has not responded by then, return/persist **PENDING**, not a false DECLINED or APPROVED result.
- A small scheduled background job checks pending payments with the fake bank and updates them to their final APPROVED or DECLINED/FAILED state when known.
- Record state changes consistently. If the final state is APPROVED, create the ledger exactly once.
- The `...9` delayed-card scenario must return PENDING after the switch timeout and resolve after the fake bank's 30-second delay.
- Make the simulated delay configurable in tests so tests do not all have to wait 30 real seconds, but keep the required demo default at 30 seconds.

### H. Idempotency
- Accept an idempotency key for every payment submission, preferably through the `Idempotency-Key` HTTP header.
- Enforce uniqueness in the database, scoped appropriately to the merchant.
- Repeating the same key and same request returns the original payment/result and must not call the bank for a second charge or create more ledger entries.
- Reusing the same key with a different request payload should return a clear conflict (e.g. HTTP 409).
- Handle concurrent repeated requests safely; an in-memory map alone is not sufficient.

## 3. Minimal target architecture

Keep the architecture to these parts only:

1. **Angular frontend** — payment form, own payment history for a merchant, admin payment/reconciliation view, login.
2. **Main Spring Boot payment switch** — validation, merchant authorization, idempotency, calls to bank, payment state, event history, ledger creation, timeout recovery.
3. **Small separate Spring Boot fake bank** — fictional cards/balances, authorization/debit, deterministic delayed-card behavior, payment-status lookup, CSV export if that is the cleanest place for it.
4. **One local PostgreSQL instance** — reuse the current schema and add only necessary fields/tables/indexes. The fake bank owns the cards data logically; a separate database server is not required.

Use the simplest implementation that safely satisfies the requirements. Keep business logic in small service classes for payment orchestration, fake-bank HTTP client, ledger posting, reconciliation, authentication, and timeout recovery where needed. Do not put all core workflow logic inside large controllers. Use DTOs where necessary to avoid exposing entities or full card details.

## 4. What is already in this repository (verify against source)

The current workspace is named `payops-phase05`. It includes:

- `pom.xml`: main Spring Boot/Maven project, documented as Spring Boot 4.1.1 and Java 25.
- `src/main/java/com/yourorg/payops/`: existing controllers, entities, repositories, enums/models, exception handling, and application entry point.
- Existing main-app foundations for payments and payment events, merchant/switch selection, accounts, journal entries, and balanced posting batches.
- Existing settlement/reconciliation-related entities and controller files; their actual behavior must be inspected because repository documentation has inconsistencies about whether these are implemented.
- `sql/schema.sql`: a broad PostgreSQL schema with 13 entity tables plus the `user_role` join table, constraints/indexes, and `v_unbalanced_journal` view.
- `frontend/`: Angular operations dashboard with a number of screens, including overview, payments, accounting, settlement/reconciliation, and master-data screens.
- `scripts/seed-demo.ps1`: demo-data script using existing REST APIs.
- Project notes: `README_RUN.md`, `PROJECT_STATUS.md`, `HANDOFF.md`, `DEMO_SCRIPT.md`, `docs/ARCHITECTURE.md`, `docs/DATA_DICTIONARY.md`, and diagrams.
- Existing controller/unit tests under `src/test/java`.

The current documentation says that payment initiation/event history, merchant/switch/account APIs, and manual balanced journal/posting endpoints exist. It also says that authentication/JWT, payment authorization against a fake bank, automated ledger-on-approval, timeout recovery, idempotency, and complete CSV reconciliation are not finished. **Treat these statements as leads, not unquestionable truth: inspect the actual code and tests before concluding feature status.**

Known documentation drift: `PROJECT_STATUS.md` says settlement/reconciliation work is not started, while files for settlement/reconciliation controllers and tests exist. Determine what they really do. The latest documented frontend increment improves demo KPIs/details and seed data, but it does not establish that the required payment flow is working. Seeded sample data is not proof of a working feature.

## 5. Simplification rules — prevent scope creep

- Do **not** rewrite the whole project from scratch. First identify what can be reused safely.
- Keep the existing Angular + Spring Boot + PostgreSQL stack unless source inspection reveals a hard blocker.
- Reuse existing `Payment`, `PaymentEvent`, `Merchant`, switch, journal/posting, settlement, and reconciliation structures wherever practical.
- Prefer a small additive schema change over a broad schema redesign. Do not drop/recreate a database or run `sql/schema.sql` against a database containing data without explicit warning/approval; it is destructive.
- The current dashboard has more sections than the MVP needs. Reduce the visible navigation to the minimum required routes, or leave unused screens out of the main flow. Do not spend time polishing or extending non-MVP sections.
- Do not delete existing tables/entities/controllers just because the first screen does not use them. Identify dead weight first and only remove code if it is clearly unused, its removal is safe, and tests pass.
- Do not create parallel versions of existing concepts (e.g. a second payment table, second ledger model, duplicate reconciliation API, or second authentication mechanism).
- No Kafka, Redis, microservice fleet, Docker/Kubernetes, real payment gateways/networks, external bank APIs, AI/ML/fraud engine, charts/analytics work, disputes, refunds, notifications, or unrelated admin tooling in Phase 1.
- The fake bank is the one intentional second backend service because it is explicitly required.
- Do not add features because they are common in enterprise payment systems. Only implement what is listed in this brief.
- No real PANs, CVVs, credentials, tokens, or secrets in source control. Mask card numbers in UI/logs and never persist the full number in the switch's payment table.

## 6. Acceptance tests that must pass before saying “Phase 1 MVP complete”

1. Invalid Luhn card is rejected before the fake bank is called.
2. Valid funded test card becomes APPROVED, the fake-bank balance is reduced once, and exactly two balanced ledger entries are created.
3. Insufficient-funds card becomes DECLINED; no balance is changed and no payment ledger entries are created.
4. A card ending in `9` returns PENDING within roughly the 5-second switch timeout; the recovery job later fetches the bank result and transitions it to a final state.
5. Repeating a payment with the same idempotency key and payload returns the original result, creates no second bank debit, and creates no duplicate ledger entries.
6. Reusing an idempotency key with a different payload returns a conflict and makes no new charge.
7. Export bank CSV, upload an unchanged copy, and see MATCHED rows.
8. Modify a CSV amount, remove a row, and add an unknown payment reference; the reconciliation shows amount mismatch, missing-in-bank, and missing-in-payments cases respectively.
9. A MERCHANT can access only their own payments and cannot run reconciliation; an ADMIN can see all payments and run/view reconciliation.
10. Unauthenticated requests to protected endpoints are rejected.
11. Backend tests pass; frontend type-check/build passes; run instructions work on a documented local development setup.

## 7. Required working style for the coding agent

### First response: read-only repository audit
Before editing anything:
1. Read the relevant root documentation, `pom.xml`, frontend `package.json`, SQL schema, controllers/services (if any), entities/repositories, and tests. Follow the request flow through code rather than relying on names alone.
2. Map which Phase 1 requirements are genuinely implemented, partially implemented, or missing, with file/line references where possible.
3. Explicitly identify documentation/code inconsistencies.
4. Categorize current code into **KEEP/REUSE**, **SIMPLIFY/HIDE**, **MISSING/BUILD**, and **SAFE TO REMOVE LATER**.
5. Propose the smallest implementation sequence and the exact files/schema changes likely needed.
6. Run safe existing checks where possible and report actual output. Do not claim a test/build passed unless you ran it successfully. If blocked by missing Java, Node, PostgreSQL, or credentials, state the blocker and use static review where appropriate.
7. **Do not edit, delete, or reformat files during this initial audit.** Return the audit and concise plan, then wait for the user to approve implementation.
8. The IDE currently displays a `github-mcp-server` Docker API connection error. Treat this as a separate tooling issue: do not spend the audit troubleshooting Docker/MCP, and do not introduce Docker-based setup because Docker is out of Phase 1 scope. Use normal filesystem and terminal inspection available in the IDE.

### After the audit is approved
- Implement in small, end-to-end increments, keeping changes focused on the acceptance checklist.
- Add tests alongside each workflow and run relevant checks after each increment.
- Preserve working code and avoid unrelated formatting/refactoring.
- Update the README with exact local setup commands, service ports, demo card numbers/balances, demo login credentials, how to exercise the delayed-card timeout, and CSV reconciliation steps.
- At each milestone, summarize what works, files changed, tests run/results, and anything still blocked.
- Never label the entire MVP complete until all acceptance tests above are passed or any remaining gap is clearly documented.

## 8. Immediate task

Perform **only the read-only audit in Section 7** on the current opened workspace. Do not start implementing features yet. Return a concise but complete status matrix against every Phase 1 requirement, what existing code should be reused, what should be simplified, the minimal implementation order, and any environment blockers.
