# DATA DICTIONARY (PayOps Phase 0.5)

Generated from the same spec as the Java entities and schema.sql, so all three agree. `created_at/created_by/updated_at` come from the `CreatedAudit` / `Auditable` base classes (not tables).


## Role  (table `role`)

**Purpose:** A job title that decides what a person may do (ADMIN, OPS_ENGINEER, FINANCE, SUPPORT).  
**Owner:** Teammate A (Security)

| Column | Java type | SQL type | Nullable | Constraints | Description |
|---|---|---|---|---|---|
| id | Long | BIGINT | No | PK, identity | Primary key |
| name | RoleName (enum) | VARCHAR(30) | No | CHECK in enum | Role name (fixed list), unique |
| description | String | VARCHAR(200) | Yes |  | What this role is allowed to do |
| created_at | LocalDateTime | TIMESTAMP | No | DEFAULT now | Row creation time |
| created_by | String | VARCHAR(50) | Yes | | Username who created it |

**Enum RoleName:** ADMIN, OPS_ENGINEER, FINANCE, SUPPORT


## User  (table `app_user`)

**Purpose:** A person who logs in to PayOps. Table is app_user because "user" is reserved in PostgreSQL.  
**Owner:** Teammate A (Security)

| Column | Java type | SQL type | Nullable | Constraints | Description |
|---|---|---|---|---|---|
| id | Long | BIGINT | No | PK, identity | Primary key |
| username | String | VARCHAR(50) | No | UNIQUE | Login name, unique |
| email | String | VARCHAR(100) | No | UNIQUE | Email, unique |
| password_hash | String | VARCHAR(100) | No |  | BCrypt hash of the password. Write-only in JSON: never returned to clients |
| full_name | String | VARCHAR(100) | Yes |  | Display name |
| status | UserStatus (enum) | VARCHAR(30) | No | CHECK in enum; DEFAULT ACTIVE | Account state |
| last_login_at | LocalDateTime | TIMESTAMP | Yes |  | Last successful login |
| created_at | LocalDateTime | TIMESTAMP | No | DEFAULT now | Row creation time |
| created_by | String | VARCHAR(50) | Yes | | Username who created it |
| updated_at | LocalDateTime | TIMESTAMP | No | DEFAULT now | Last change time |

Join table `user_role(user_id, role_id)`: composite PK, both FK ON DELETE CASCADE.

**Enum UserStatus:** ACTIVE, LOCKED, DISABLED


## SwitchDatabase  (table `switch_database`)

**Purpose:** ASSUMPTION: routing/connection profile of one bank, acquirer or network the transaction switch can send payments to.  
**Owner:** Teammate B (Payments core)

| Column | Java type | SQL type | Nullable | Constraints | Description |
|---|---|---|---|---|---|
| id | Long | BIGINT | No | PK, identity | Primary key |
| code | String | VARCHAR(30) | No | UNIQUE | Short unique switch code, e.g. HDFC_ACQ |
| name | String | VARCHAR(100) | No |  | Human name |
| switch_type | SwitchType (enum) | VARCHAR(30) | No | CHECK in enum | BANK, ACQUIRER or NETWORK |
| protocol | SwitchProtocol (enum) | VARCHAR(30) | No | CHECK in enum | How we talk to it |
| host | String | VARCHAR(255) | Yes |  | Host name or URL (no passwords/secrets stored here) |
| port | Integer | INTEGER | Yes | CHECK (port BETWEEN 1 AND 65535) | TCP port |
| status | SwitchStatus (enum) | VARCHAR(30) | No | CHECK in enum; DEFAULT ACTIVE | Is the route usable |
| priority | Integer | INTEGER | No | CHECK (priority > 0); DEFAULT 1 | Routing priority, 1 = first choice |
| timeout_ms | Integer | INTEGER | No | CHECK (timeout_ms > 0); DEFAULT 30000 | Timeout in milliseconds |
| created_at | LocalDateTime | TIMESTAMP | No | DEFAULT now | Row creation time |
| created_by | String | VARCHAR(50) | Yes | | Username who created it |
| updated_at | LocalDateTime | TIMESTAMP | No | DEFAULT now | Last change time |

**Enum SwitchType:** BANK, ACQUIRER, NETWORK

**Enum SwitchProtocol:** ISO8583, REST, SFTP

**Enum SwitchStatus:** ACTIVE, INACTIVE, MAINTENANCE


## Merchant  (table `merchant`)

**Purpose:** A business that accepts payments through PayOps.  
**Owner:** Teammate B (Payments core)

| Column | Java type | SQL type | Nullable | Constraints | Description |
|---|---|---|---|---|---|
| id | Long | BIGINT | No | PK, identity | Primary key |
| code | String | VARCHAR(30) | No | UNIQUE | Unique merchant code |
| name | String | VARCHAR(100) | No |  | Business name |
| email | String | VARCHAR(100) | Yes |  | Contact email |
| currency | String | VARCHAR(3) | No | DEFAULT INR | Default ISO currency |
| status | MerchantStatus (enum) | VARCHAR(30) | No | CHECK in enum; DEFAULT ACTIVE | Merchant state |
| default_switch_id | SwitchDatabase | BIGINT | Yes | FK → switch_database(id) ON DELETE SET NULL | Preferred switch (optional) |
| created_at | LocalDateTime | TIMESTAMP | No | DEFAULT now | Row creation time |
| created_by | String | VARCHAR(50) | Yes | | Username who created it |
| updated_at | LocalDateTime | TIMESTAMP | No | DEFAULT now | Last change time |

**Enum MerchantStatus:** ACTIVE, SUSPENDED, CLOSED


## Account  (table `account`)

**Purpose:** A ledger account (chart of accounts) that postings debit or credit.  
**Owner:** Teammate C (Accounting)

| Column | Java type | SQL type | Nullable | Constraints | Description |
|---|---|---|---|---|---|
| id | Long | BIGINT | No | PK, identity | Primary key |
| code | String | VARCHAR(30) | No | UNIQUE | Unique account code, e.g. 1100 |
| name | String | VARCHAR(100) | No |  | Account name |
| account_type | AccountType (enum) | VARCHAR(30) | No | CHECK in enum | ASSET/LIABILITY/REVENUE/EXPENSE/EQUITY |
| currency | String | VARCHAR(3) | No | DEFAULT INR | ISO currency |
| active | Boolean | BOOLEAN | No | DEFAULT True | Can still be posted to |
| merchant_id | Merchant | BIGINT | Yes | FK → merchant(id) ON DELETE RESTRICT | Owning merchant for merchant-specific accounts |
| created_at | LocalDateTime | TIMESTAMP | No | DEFAULT now | Row creation time |
| created_by | String | VARCHAR(50) | Yes | | Username who created it |
| updated_at | LocalDateTime | TIMESTAMP | No | DEFAULT now | Last change time |

**Enum AccountType:** ASSET, LIABILITY, REVENUE, EXPENSE, EQUITY


## Payment  (table `payment`)

**Purpose:** One customer payment made to a merchant and routed through a switch. Central table.  
**Owner:** Teammate B (Payments core)

| Column | Java type | SQL type | Nullable | Constraints | Description |
|---|---|---|---|---|---|
| id | Long | BIGINT | No | PK, identity | Primary key |
| reference | String | VARCHAR(50) | No | UNIQUE | Unique payment reference |
| merchant_id | Merchant | BIGINT | No | FK → merchant(id) ON DELETE RESTRICT | Merchant being paid |
| switch_database_id | SwitchDatabase | BIGINT | No | FK → switch_database(id) ON DELETE RESTRICT | Switch used for routing |
| amount | BigDecimal | NUMERIC(19,4) | No | CHECK (amount > 0) | Payment amount |
| refunded_amount | BigDecimal | NUMERIC(19,4) | No | CHECK (refunded_amount >= 0 AND refunded_amount <= amount); DEFAULT 0 | Total refunded so far |
| currency | String | VARCHAR(3) | No | DEFAULT INR | ISO currency |
| method | PaymentMethod (enum) | VARCHAR(30) | No | CHECK in enum | CARD, UPI, NETBANKING, WALLET |
| status | PaymentStatus (enum) | VARCHAR(30) | No | CHECK in enum; DEFAULT INITIATED | Current lifecycle state |
| switch_reference | String | VARCHAR(50) | Yes |  | RRN/auth code returned by the switch |
| customer_ref | String | VARCHAR(100) | Yes |  | Customer identifier (masked) |
| failure_reason | String | VARCHAR(255) | Yes |  | Why it failed |
| created_at | LocalDateTime | TIMESTAMP | No | DEFAULT now | Row creation time |
| created_by | String | VARCHAR(50) | Yes | | Username who created it |
| updated_at | LocalDateTime | TIMESTAMP | No | DEFAULT now | Last change time |

**Enum PaymentMethod:** CARD, UPI, NETBANKING, WALLET

**Enum PaymentStatus:** INITIATED, AUTHORIZED, CAPTURED, FAILED, REFUNDED, SETTLED


## PaymentEvent  (table `payment_event`)

**Purpose:** Append-only audit row written on every Payment state change.  
**Owner:** Teammate B (Payments core)

| Column | Java type | SQL type | Nullable | Constraints | Description |
|---|---|---|---|---|---|
| id | Long | BIGINT | No | PK, identity | Primary key |
| payment_id | Payment | BIGINT | No | FK → payment(id) ON DELETE RESTRICT | Payment this event belongs to |
| event_type | PaymentStatus (enum) | VARCHAR(30) | No | CHECK in enum | State reached (same values as PaymentStatus) |
| amount | BigDecimal | NUMERIC(19,4) | Yes |  | Amount involved (e.g. refund amount) |
| message | String | VARCHAR(255) | Yes |  | Free text note |
| occurred_at | LocalDateTime | TIMESTAMP | No | DEFAULT now | When it happened |
| created_at | LocalDateTime | TIMESTAMP | No | DEFAULT now | Row creation time |
| created_by | String | VARCHAR(50) | Yes | | Username who created it |

**Enum PaymentStatus:** INITIATED, AUTHORIZED, CAPTURED, FAILED, REFUNDED, SETTLED


## SettlementFile  (table `settlement_file`)

**Purpose:** One file received from a bank/acquirer listing settled transactions.  
**Owner:** YOU (Settlement)

| Column | Java type | SQL type | Nullable | Constraints | Description |
|---|---|---|---|---|---|
| id | Long | BIGINT | No | PK, identity | Primary key |
| file_name | String | VARCHAR(255) | No | UNIQUE | Unique file name |
| switch_database_id | SwitchDatabase | BIGINT | No | FK → switch_database(id) ON DELETE RESTRICT | Bank/acquirer that sent it |
| settlement_date | LocalDate | DATE | No |  | Business date of settlement |
| status | SettlementFileStatus (enum) | VARCHAR(30) | No | CHECK in enum; DEFAULT RECEIVED | Processing state |
| record_count | Integer | INTEGER | No | CHECK (record_count >= 0); DEFAULT 0 | Number of records |
| total_amount | BigDecimal | NUMERIC(19,4) | No | CHECK (total_amount >= 0); DEFAULT 0 | Sum of record amounts |
| received_at | LocalDateTime | TIMESTAMP | No | DEFAULT now | When the file arrived |
| created_at | LocalDateTime | TIMESTAMP | No | DEFAULT now | Row creation time |
| created_by | String | VARCHAR(50) | Yes | | Username who created it |
| updated_at | LocalDateTime | TIMESTAMP | No | DEFAULT now | Last change time |

**Enum SettlementFileStatus:** RECEIVED, PARSED, FAILED, RECONCILED


## SettlementRecord  (table `settlement_record`)

**Purpose:** One transaction line inside a SettlementFile.  
**Owner:** YOU (Settlement)

| Column | Java type | SQL type | Nullable | Constraints | Description |
|---|---|---|---|---|---|
| id | Long | BIGINT | No | PK, identity | Primary key |
| settlement_file_id | SettlementFile | BIGINT | No | FK → settlement_file(id) ON DELETE CASCADE | Parent file |
| payment_reference | String | VARCHAR(50) | No |  | Payment reference as written by the bank |
| payment_id | Payment | BIGINT | Yes | FK → payment(id) ON DELETE SET NULL | Matched Payment (optional, filled by reconciliation) |
| amount | BigDecimal | NUMERIC(19,4) | No | CHECK (amount > 0) | Settled amount |
| fee | BigDecimal | NUMERIC(19,4) | No | CHECK (fee >= 0 AND fee <= amount); DEFAULT 0 | Bank fee |
| currency | String | VARCHAR(3) | No | DEFAULT INR | ISO currency |
| transaction_date | LocalDateTime | TIMESTAMP | Yes |  | Transaction time at bank |
| created_at | LocalDateTime | TIMESTAMP | No | DEFAULT now | Row creation time |
| created_by | String | VARCHAR(50) | Yes | | Username who created it |


## JournalEntry  (table `journal_entry`)

**Purpose:** One accounting event (capture, refund, settlement). Holds balanced Postings.  
**Owner:** Teammate C (Accounting)

| Column | Java type | SQL type | Nullable | Constraints | Description |
|---|---|---|---|---|---|
| id | Long | BIGINT | No | PK, identity | Primary key |
| entry_number | String | VARCHAR(40) | No | UNIQUE | Unique journal number |
| source | JournalSource (enum) | VARCHAR(30) | No | CHECK in enum | What caused this entry |
| description | String | VARCHAR(255) | Yes |  | Narration |
| entry_date | LocalDateTime | TIMESTAMP | No | DEFAULT now | Accounting date |
| payment_id | Payment | BIGINT | Yes | FK → payment(id) ON DELETE RESTRICT | Related payment |
| settlement_record_id | SettlementRecord | BIGINT | Yes | FK → settlement_record(id) ON DELETE RESTRICT | Related settlement line |
| created_at | LocalDateTime | TIMESTAMP | No | DEFAULT now | Row creation time |
| created_by | String | VARCHAR(50) | Yes | | Username who created it |

**Enum JournalSource:** PAYMENT_CAPTURE, REFUND, SETTLEMENT, MANUAL


## Posting  (table `posting`)

**Purpose:** One debit or credit line of a JournalEntry against an Account.  
**Owner:** YOU (Settlement)

| Column | Java type | SQL type | Nullable | Constraints | Description |
|---|---|---|---|---|---|
| id | Long | BIGINT | No | PK, identity | Primary key |
| journal_entry_id | JournalEntry | BIGINT | No | FK → journal_entry(id) ON DELETE RESTRICT | Parent journal entry |
| account_id | Account | BIGINT | No | FK → account(id) ON DELETE RESTRICT | Ledger account affected |
| direction | PostingDirection (enum) | VARCHAR(30) | No | CHECK in enum | DEBIT or CREDIT |
| amount | BigDecimal | NUMERIC(19,4) | No | CHECK (amount > 0) | Positive amount |
| description | String | VARCHAR(255) | Yes |  | Line note |
| created_at | LocalDateTime | TIMESTAMP | No | DEFAULT now | Row creation time |
| created_by | String | VARCHAR(50) | Yes | | Username who created it |

**Enum PostingDirection:** DEBIT, CREDIT


## ReconciliationRun  (table `reconciliation_run`)

**Purpose:** One execution of the comparison between a SettlementFile and Payments.  
**Owner:** Teammate D (Reconciliation)

| Column | Java type | SQL type | Nullable | Constraints | Description |
|---|---|---|---|---|---|
| id | Long | BIGINT | No | PK, identity | Primary key |
| run_number | String | VARCHAR(40) | No | UNIQUE | Unique run number |
| settlement_file_id | SettlementFile | BIGINT | No | FK → settlement_file(id) ON DELETE RESTRICT | File being reconciled |
| status | RunStatus (enum) | VARCHAR(30) | No | CHECK in enum; DEFAULT RUNNING | Run state |
| started_at | LocalDateTime | TIMESTAMP | No | DEFAULT now | Start time |
| completed_at | LocalDateTime | TIMESTAMP | Yes |  | End time |
| total_checked | Integer | INTEGER | No | CHECK (total_checked >= 0); DEFAULT 0 | Rows compared |
| matched_count | Integer | INTEGER | No | CHECK (matched_count >= 0); DEFAULT 0 | Rows MATCHED |
| exception_count | Integer | INTEGER | No | CHECK (exception_count >= 0); DEFAULT 0 | Rows not matched |
| created_at | LocalDateTime | TIMESTAMP | No | DEFAULT now | Row creation time |
| created_by | String | VARCHAR(50) | Yes | | Username who created it |

**Enum RunStatus:** RUNNING, COMPLETED, FAILED


## ReconciliationResult  (table `reconciliation_result`)

**Purpose:** One outcome row of a run for one settlement record / payment.  
**Owner:** Teammate D (Reconciliation)

| Column | Java type | SQL type | Nullable | Constraints | Description |
|---|---|---|---|---|---|
| id | Long | BIGINT | No | PK, identity | Primary key |
| reconciliation_run_id | ReconciliationRun | BIGINT | No | FK → reconciliation_run(id) ON DELETE CASCADE | Parent run |
| settlement_record_id | SettlementRecord | BIGINT | Yes | FK → settlement_record(id) ON DELETE SET NULL | Bank side |
| payment_id | Payment | BIGINT | Yes | FK → payment(id) ON DELETE SET NULL | Our side |
| result_type | ResultType (enum) | VARCHAR(30) | No | CHECK in enum | Outcome |
| expected_amount | BigDecimal | NUMERIC(19,4) | Yes |  | Our payment amount |
| actual_amount | BigDecimal | NUMERIC(19,4) | Yes |  | Bank amount |
| note | String | VARCHAR(255) | Yes |  | Explanation |
| created_at | LocalDateTime | TIMESTAMP | No | DEFAULT now | Row creation time |
| created_by | String | VARCHAR(50) | Yes | | Username who created it |

**Enum ResultType:** MATCHED, AMOUNT_MISMATCH, MISSING_IN_SETTLEMENT, MISSING_IN_PAYMENTS, DUPLICATE
