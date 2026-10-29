-- PayOps Phase 1 Additive Migration Script
-- Target: PostgreSQL 14+
-- Purpose: Additive, non-destructive migration to support Phase 1 MVP requirements:
--   1. MERCHANT role and merchant-scoped user association
--   2. PENDING, APPROVED, DECLINED payment and payment-event statuses
--   3. Card masking, idempotency key, request fingerprinting, and bank reference columns on payment
--   4. Idempotent ledger entry constraint on journal_entry
--   5. Fake-bank card/balance storage (bank_card)
--   6. Fake-bank durable transaction & duplicate debit protection (bank_transaction)
--   7. Seed data for Phase 1 (MERCHANT role, FAKE-BANK switch, demo fictional cards)
--
-- Note: This script is ADDITIVE and NON-DESTRUCTIVE. It does not drop tables or existing data.
-- Do NOT execute against production or Supabase without prior review.

BEGIN;

-- ============================================================================
-- 1. ROLES & USERS: Add MERCHANT role & user merchant scoping
-- ============================================================================

-- Update check constraint on role.name to include 'MERCHANT'
ALTER TABLE role DROP CONSTRAINT IF EXISTS ck_role_name;
ALTER TABLE role ADD CONSTRAINT ck_role_name 
    CHECK (name IN ('ADMIN', 'OPS_ENGINEER', 'FINANCE', 'SUPPORT', 'MERCHANT'));

-- Seed MERCHANT role if not already present
INSERT INTO role (name, description)
SELECT 'MERCHANT', 'Merchant portal access: create payments and view merchant-scoped payments'
WHERE NOT EXISTS (SELECT 1 FROM role WHERE name = 'MERCHANT');

-- Add merchant_id to app_user to support merchant scoping (nullable for platform users like ADMIN)
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS merchant_id BIGINT;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_app_user_merchant_id'
    ) THEN
        ALTER TABLE app_user ADD CONSTRAINT fk_app_user_merchant_id 
            FOREIGN KEY (merchant_id) REFERENCES merchant(id) ON DELETE SET NULL;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_app_user_merchant_id ON app_user (merchant_id);


-- ============================================================================
-- 2. PAYMENT STATUSES: Add PENDING, APPROVED, DECLINED
-- ============================================================================

-- Update payment.status check constraint to support Phase 1 lifecycle
ALTER TABLE payment DROP CONSTRAINT IF EXISTS ck_payment_status;
ALTER TABLE payment ADD CONSTRAINT ck_payment_status 
    CHECK (status IN ('INITIATED', 'PENDING', 'APPROVED', 'DECLINED', 'AUTHORIZED', 'CAPTURED', 'FAILED', 'REFUNDED', 'SETTLED'));

-- Update payment_event.event_type check constraint to match payment status transitions
ALTER TABLE payment_event DROP CONSTRAINT IF EXISTS ck_payment_event_event_type;
ALTER TABLE payment_event ADD CONSTRAINT ck_payment_event_event_type 
    CHECK (event_type IN ('INITIATED', 'PENDING', 'APPROVED', 'DECLINED', 'AUTHORIZED', 'CAPTURED', 'FAILED', 'REFUNDED', 'SETTLED'));


-- ============================================================================
-- 3. PAYMENT TABLE EXTENSIONS: Card masking, idempotency & bank references
-- ============================================================================

-- Masked card number (e.g. 411111******1111) - full PAN is never stored in payment table
ALTER TABLE payment ADD COLUMN IF NOT EXISTS masked_card_number VARCHAR(30);

-- Merchant idempotency key supplied via Idempotency-Key HTTP header
ALTER TABLE payment ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(100);

-- SHA-256 fingerprint hash of payment request payload to detect payload mismatches on repeated keys
ALTER TABLE payment ADD COLUMN IF NOT EXISTS request_hash VARCHAR(64);

-- Client request ID sent to the fake bank
ALTER TABLE payment ADD COLUMN IF NOT EXISTS bank_request_id VARCHAR(100);

-- External reference / transaction ID returned by fake bank
ALTER TABLE payment ADD COLUMN IF NOT EXISTS bank_reference VARCHAR(100);

-- Uniqueness constraint: (merchant_id, idempotency_key) prevents duplicate payment creation per merchant
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uq_payment_merchant_idempotency'
    ) THEN
        ALTER TABLE payment ADD CONSTRAINT uq_payment_merchant_idempotency 
            UNIQUE (merchant_id, idempotency_key);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_payment_idempotency_key ON payment (idempotency_key);
CREATE INDEX IF NOT EXISTS idx_payment_bank_reference ON payment (bank_reference);
CREATE UNIQUE INDEX IF NOT EXISTS idx_payment_bank_request_id ON payment (bank_request_id) 
    WHERE bank_request_id IS NOT NULL;


-- ============================================================================
-- 4. ACCOUNTING / LEDGER: Safe idempotent journal entries on payment approval
-- ============================================================================

-- Update journal_entry.source check constraint to allow PAYMENT_APPROVED
ALTER TABLE journal_entry DROP CONSTRAINT IF EXISTS ck_journal_entry_source;
ALTER TABLE journal_entry ADD CONSTRAINT ck_journal_entry_source 
    CHECK (source IN ('PAYMENT_CAPTURE', 'PAYMENT_APPROVED', 'REFUND', 'SETTLEMENT', 'MANUAL'));

-- Unique index to guarantee that retrying an approved payment transition never duplicates journal entries
CREATE UNIQUE INDEX IF NOT EXISTS uq_journal_entry_payment_source 
    ON journal_entry (payment_id, source) 
    WHERE payment_id IS NOT NULL;

-- Update reconciliation_result.result_type check constraint to include MISSING_IN_BANK
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'reconciliation_result') THEN
        ALTER TABLE reconciliation_result DROP CONSTRAINT IF EXISTS ck_reconciliation_result_result_type;
        ALTER TABLE reconciliation_result ADD CONSTRAINT ck_reconciliation_result_result_type 
            CHECK (result_type IN ('MATCHED', 'AMOUNT_MISMATCH', 'MISSING_IN_BANK', 'MISSING_IN_SETTLEMENT', 'MISSING_IN_PAYMENTS', 'DUPLICATE'));
    END IF;
END $$;

-- Support bank_reference on settlement_record alongside payment_reference
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'settlement_record') THEN
        ALTER TABLE settlement_record ADD COLUMN IF NOT EXISTS bank_reference VARCHAR(100);
        ALTER TABLE settlement_record ALTER COLUMN payment_reference DROP NOT NULL;
    END IF;
END $$;

-- Associate app_user with merchant for MERCHANT role accounts
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'app_user') THEN
        ALTER TABLE app_user ADD COLUMN IF NOT EXISTS merchant_id BIGINT;
        IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE constraint_name = 'fk_app_user_merchant_id') THEN
            ALTER TABLE app_user ADD CONSTRAINT fk_app_user_merchant_id FOREIGN KEY (merchant_id) REFERENCES merchant(id) ON DELETE SET NULL;
        END IF;
    END IF;
END $$;


-- ============================================================================
-- 5. FAKE BANK SERVICE: Card and balance storage
-- ============================================================================

-- Logical ownership: fake-bank service only
CREATE TABLE IF NOT EXISTS bank_card (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY,
    card_number VARCHAR(19) NOT NULL,
    cardholder_name VARCHAR(100) NOT NULL,
    expiry_month INTEGER NOT NULL,
    expiry_year INTEGER NOT NULL,
    cvv VARCHAR(4) NOT NULL,
    balance NUMERIC(19,4) NOT NULL DEFAULT 0,
    currency VARCHAR(3) NOT NULL DEFAULT 'INR',
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_bank_card PRIMARY KEY (id),
    CONSTRAINT uq_bank_card_card_number UNIQUE (card_number),
    CONSTRAINT ck_bank_card_balance CHECK (balance >= 0),
    CONSTRAINT ck_bank_card_status CHECK (status IN ('ACTIVE', 'BLOCKED', 'EXPIRED')),
    CONSTRAINT ck_bank_card_expiry_month CHECK (expiry_month BETWEEN 1 AND 12)
);


-- ============================================================================
-- 6. FAKE BANK SERVICE: Durable request tracking & duplicate debit prevention
-- ============================================================================

-- Logical ownership: fake-bank service only
CREATE TABLE IF NOT EXISTS bank_transaction (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY,
    request_id VARCHAR(100) NOT NULL,
    payment_reference VARCHAR(50) NOT NULL,
    card_id BIGINT,
    card_hash VARCHAR(64),
    masked_card VARCHAR(30),
    amount NUMERIC(19,4) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'INR',
    status VARCHAR(30) NOT NULL,
    bank_reference VARCHAR(100) NOT NULL,
    response_code VARCHAR(30),
    response_message VARCHAR(255),
    debited BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_bank_transaction PRIMARY KEY (id),
    CONSTRAINT uq_bank_transaction_request_id UNIQUE (request_id),
    CONSTRAINT uq_bank_transaction_payment_ref UNIQUE (payment_reference),
    CONSTRAINT uq_bank_transaction_bank_ref UNIQUE (bank_reference),
    CONSTRAINT fk_bank_transaction_card_id FOREIGN KEY (card_id) REFERENCES bank_card(id) ON DELETE SET NULL,
    CONSTRAINT ck_bank_transaction_status CHECK (status IN ('PENDING', 'APPROVED', 'DECLINED', 'TIMEOUT')),
    CONSTRAINT ck_bank_transaction_amount CHECK (amount > 0)
);

-- Additive compatibility: ensure columns exist if bank_transaction was created by an earlier revision
ALTER TABLE bank_transaction ADD COLUMN IF NOT EXISTS card_hash VARCHAR(64);
ALTER TABLE bank_transaction ADD COLUMN IF NOT EXISTS masked_card VARCHAR(30);

CREATE INDEX IF NOT EXISTS idx_bank_transaction_status ON bank_transaction (status);


-- ============================================================================
-- 7. PHASE 1 SEED DATA
-- ============================================================================

-- Seed FAKE-BANK switch in switch_database if not present
INSERT INTO switch_database (code, name, switch_type, protocol, host, port, status, priority, timeout_ms, created_by)
SELECT 'FAKE-BANK', 'Simulated Acquirer Bank', 'BANK', 'REST', 'localhost', 8081, 'ACTIVE', 1, 5000, 'MIGRATION'
WHERE NOT EXISTS (SELECT 1 FROM switch_database WHERE code = 'FAKE-BANK');

-- Seed fictional demo test cards with valid Luhn checksums into bank_card
-- 1. Funded test card (balance: 50,000 INR) -> Will result in APPROVED
INSERT INTO bank_card (card_number, cardholder_name, expiry_month, expiry_year, cvv, balance, currency, status)
SELECT '4111111111111111', 'John Demo Merchant', 12, 2028, '123', 50000.0000, 'INR', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM bank_card WHERE card_number = '4111111111111111');

-- 2. Insufficient funds test card (balance: 5.00 INR) -> Will result in DECLINED
INSERT INTO bank_card (card_number, cardholder_name, expiry_month, expiry_year, cvv, balance, currency, status)
SELECT '4000000000000002', 'Low Balance Demo', 12, 2028, '123', 5.0000, 'INR', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM bank_card WHERE card_number = '4000000000000002');

-- 3. Delayed test card ending in 9 (valid Luhn, balance: 10,000 INR) -> Simulates 30-second delay / PENDING
INSERT INTO bank_card (card_number, cardholder_name, expiry_month, expiry_year, cvv, balance, currency, status)
SELECT '4000000000000259', 'Delayed Timeout Demo', 12, 2028, '123', 10000.0000, 'INR', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM bank_card WHERE card_number = '4000000000000259');

COMMIT;
