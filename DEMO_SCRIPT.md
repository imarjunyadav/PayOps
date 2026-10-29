# PayOps demo: run steps and talking points

## A. Start (3 terminals, PowerShell, project root)
```powershell
# 1. Fresh database (drops and recreates tables, seeds roles + 4 accounts)
psql -U postgres -c "DROP DATABASE IF EXISTS payops;"
psql -U postgres -c "CREATE DATABASE payops;"
psql -U postgres -d payops -f sql\schema.sql

# 2. Backend (new window)
$env:PAYOPS_DB_PASSWORD = "your_postgres_password"
mvn spring-boot:run          # wait for "Started PayopsApplication"

# 3. Demo data (another window, backend must be up)
.\scripts\seed-demo.ps1

# 4. Frontend (another window)
cd frontend
npm install                  # first time only
npm start                    # http://localhost:4200
```
If the seed script says "Merchants already exist", redo step 1, restart the backend, rerun step 3.
If the backend refuses to start with a schema validation message, send me the message (it is the first real PostgreSQL run of this schema).

## B. What you will see (exact numbers from the seed)
10 payments, volume Rs 92,850, 3 merchants, 2 switches, 2 settlement files (10 records), 2 reconciliation runs:
run 1 = 8 checked, 5 matched, 3 exceptions (PAY-1006 amount mismatch, PAY-9999 missing in payments, PAY-1010 missing in settlement);
run 2 = 3 checked, 3 matched. Overall match rate 9/11 = 81.8%. 12 journals, all balanced.

## C. Click path (about 5 minutes)
1. **Overview**: KPIs, payment status bars, reconciliation outcome, recent items. "One screen for payments, settlement and reconciliation."
2. **Payments**: click PAY-1006 (Rs 6,800). The **Transaction trace** shows merchant, routed switch, event, journal postings, the settlement record and the reconciliation result. This is the whole story of one transaction.
3. **Accounting**: click a journal. Dr Acquirer Receivable / Cr Merchant Payable, totals equal, badge "Balanced". Click JE-0011 for the settlement posting (cash + fee expense vs receivable).
4. **Settlement files**: click HDFC_SETTLE_20261023.csv to see its records; PAY-9999 shows "Unknown".
5. **Reconciliation**: click RUN-20261024-001 to see expected vs actual and the difference for PAY-1006.
6. Back to **Overview**.

## D. Honest answers if asked
- **Why is every payment "Initiated"?** This build implements payment initiation only. Authorize/capture/refund transitions are future work; the status model (enum) already supports them. We did not fake transitions.
- **Why do runs show "Running" and files "Received"?** Status-change endpoints for runs/files are not built yet (future work); counts and results are real.
- **Who does the matching?** Results are classified by the backend (matched / mismatch / missing) from the payment and record linked; the seed script pairs them by payment reference. An automatic matching endpoint is future work.
- **Why journals for initiated payments?** They represent the expected receivable and merchant payable; the settlement journals show cash and fee when the bank pays.
- Architecture: controller / entity / repository / model / exception only; business rules live in controllers (project guide convention).

## E. Known limits
- Legacy `/api/postings` endpoint is only used for reading; avoid demonstrating its create/update/delete (it does not enforce balance).
- Nothing here is authenticated (security is a later phase).
