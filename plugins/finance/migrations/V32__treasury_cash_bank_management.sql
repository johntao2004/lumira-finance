-- V4 Parallel Candidate Lane W07: Treasury (MD04, TR01, TR02, TR03, TR04)
-- PostgreSQL 18 schema for cash & bank master, treasury orders, payment instructions,
-- bank statement import & matching, balance reconciliation sheet, and tiered cash forecasting.

CREATE TABLE IF NOT EXISTS plugin_finance_bank_account (
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    id uuid PRIMARY KEY,
    account_no varchar(64) NOT NULL,
    account_name varchar(128) NOT NULL,
    bank_name varchar(128) NOT NULL,
    branch_name varchar(128) NOT NULL,
    account_type varchar(32) NOT NULL CHECK (account_type IN ('BASIC', 'GENERAL', 'TAX', 'CASH')),
    currency varchar(8) NOT NULL DEFAULT 'CNY' CHECK (currency = 'CNY'),
    gl_account_id uuid NOT NULL,
    status varchar(32) NOT NULL DEFAULT 'PENDING_REVIEW' CHECK (status IN ('PENDING_REVIEW', 'ACTIVE', 'FROZEN', 'CLOSED')),
    maker_person_id uuid NOT NULL,
    reviewer_person_id uuid,
    frozen_reason text,
    closing_reason text,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_bank_account_no UNIQUE (tenant_id, book_id, account_no)
);

CREATE INDEX IF NOT EXISTS idx_bank_account_lookup ON plugin_finance_bank_account(tenant_id, book_id, status);

CREATE TABLE IF NOT EXISTS plugin_finance_treasury_numbering_policy (
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    id uuid PRIMARY KEY,
    period_id uuid NOT NULL,
    period_no integer NOT NULL,
    order_type varchar(32) NOT NULL CHECK (order_type IN ('RECEIPT', 'PAYMENT', 'TRANSFER')),
    prefix varchar(16) NOT NULL,
    pattern varchar(32) NOT NULL,
    current_sequence integer NOT NULL DEFAULT 0,
    is_approved boolean NOT NULL DEFAULT false,
    maker_person_id uuid NOT NULL,
    reviewer_person_id uuid,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_treasury_numbering_policy UNIQUE (tenant_id, book_id, period_id, order_type)
);

CREATE TABLE IF NOT EXISTS plugin_finance_treasury_order (
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    id uuid PRIMARY KEY,
    order_no varchar(64) NOT NULL,
    order_type varchar(32) NOT NULL CHECK (order_type IN ('RECEIPT', 'PAYMENT', 'TRANSFER_OUT', 'TRANSFER_IN')),
    account_id uuid NOT NULL REFERENCES plugin_finance_bank_account(id),
    counterparty_id uuid,
    target_account_id uuid REFERENCES plugin_finance_bank_account(id),
    transfer_pair_id uuid,
    order_date date NOT NULL,
    amount numeric(28,8) NOT NULL CHECK (amount > 0),
    fee_amount numeric(28,8) NOT NULL DEFAULT 0 CHECK (fee_amount >= 0),
    fee_gl_account_id uuid,
    status varchar(32) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'APPROVED', 'IN_TRANSIT', 'EXECUTED', 'CANCELLED', 'FAILED')),
    purpose text,
    maker_person_id uuid NOT NULL,
    reviewer_person_id uuid,
    journal_id uuid,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_treasury_order_no UNIQUE (tenant_id, book_id, order_no)
);

CREATE INDEX IF NOT EXISTS idx_treasury_order_lookup ON plugin_finance_treasury_order(tenant_id, book_id, account_id, status);

CREATE TABLE IF NOT EXISTS plugin_finance_treasury_payment_instruction (
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    id uuid PRIMARY KEY,
    instruction_no varchar(64) NOT NULL,
    treasury_order_id uuid NOT NULL REFERENCES plugin_finance_treasury_order(id),
    payer_account_id uuid NOT NULL REFERENCES plugin_finance_bank_account(id),
    payee_account_no varchar(64) NOT NULL,
    payee_account_name varchar(128) NOT NULL,
    payee_bank_name varchar(128) NOT NULL,
    amount numeric(28,8) NOT NULL CHECK (amount > 0),
    status varchar(32) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'APPROVED', 'DISPATCHED_UNKNOWN', 'SETTLED', 'FAILED_RETURNED', 'CANCELLED')),
    bank_direct_channel varchar(32) NOT NULL DEFAULT 'NOT_VERIFIED' CHECK (bank_direct_channel = 'NOT_VERIFIED'),
    maker_person_id uuid NOT NULL,
    approver_person_id uuid,
    dispatched_at timestamptz,
    receipt_original_sha256 varchar(64),
    receipt_provider_ref varchar(128),
    receipt_verifier_person_id uuid,
    receipt_verified_at timestamptz,
    failure_reason text,
    adjustment_ref_id uuid,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_payment_instruction_no UNIQUE (tenant_id, book_id, instruction_no)
);

CREATE INDEX IF NOT EXISTS idx_payment_inst_lookup ON plugin_finance_treasury_payment_instruction(tenant_id, book_id, status);

CREATE TABLE IF NOT EXISTS plugin_finance_bank_statement_batch (
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES plugin_finance_bank_account(id),
    file_sha256 varchar(64) NOT NULL,
    file_format varchar(16) NOT NULL CHECK (file_format IN ('CSV', 'JSON')),
    statement_date date NOT NULL,
    opening_balance numeric(28,8) NOT NULL,
    closing_balance numeric(28,8) NOT NULL,
    total_credit_amount numeric(28,8) NOT NULL CHECK (total_credit_amount >= 0),
    total_debit_amount numeric(28,8) NOT NULL CHECK (total_debit_amount >= 0),
    record_count integer NOT NULL CHECK (record_count >= 0),
    imported_by uuid NOT NULL,
    status varchar(32) NOT NULL DEFAULT 'IMPORTED' CHECK (status IN ('IMPORTED', 'RECONCILED', 'CANCELLED')),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_bank_statement_batch UNIQUE (tenant_id, book_id, account_id, file_sha256)
);

CREATE TABLE IF NOT EXISTS plugin_finance_bank_statement_line (
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    id uuid PRIMARY KEY,
    batch_id uuid NOT NULL REFERENCES plugin_finance_bank_statement_batch(id) ON DELETE CASCADE,
    account_id uuid NOT NULL REFERENCES plugin_finance_bank_account(id),
    line_no integer NOT NULL,
    line_hash varchar(64) NOT NULL,
    transaction_date date NOT NULL,
    value_date date NOT NULL,
    direction varchar(16) NOT NULL CHECK (direction IN ('CREDIT', 'DEBIT')),
    amount numeric(28,8) NOT NULL CHECK (amount > 0),
    balance_after numeric(28,8) NOT NULL,
    counterparty_account_no varchar(64),
    counterparty_name varchar(128),
    transaction_no varchar(128),
    summary varchar(256),
    match_status varchar(32) NOT NULL DEFAULT 'UNMATCHED' CHECK (match_status IN ('UNMATCHED', 'PROPOSED', 'MATCHED', 'AMBIGUOUS_MULTI_CANDIDATE')),
    matched_order_id uuid,
    matched_journal_line_id uuid,
    match_reviewer_person_id uuid,
    matched_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_bank_statement_line_no UNIQUE (tenant_id, batch_id, line_no),
    CONSTRAINT uq_bank_statement_line_hash UNIQUE (tenant_id, account_id, line_hash)
);

CREATE INDEX IF NOT EXISTS idx_statement_line_lookup ON plugin_finance_bank_statement_line(tenant_id, account_id, transaction_date, match_status);

CREATE TABLE IF NOT EXISTS plugin_finance_bank_reconciliation (
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES plugin_finance_bank_account(id),
    period_id uuid NOT NULL,
    period_no integer NOT NULL,
    cutoff_date date NOT NULL,
    bank_statement_balance numeric(28,8) NOT NULL,
    enterprise_recorded_bank_unrecorded numeric(28,8) NOT NULL DEFAULT 0,
    enterprise_paid_bank_unpaid numeric(28,8) NOT NULL DEFAULT 0,
    adjusted_bank_balance numeric(28,8) NOT NULL,
    ledger_balance numeric(28,8) NOT NULL,
    bank_credited_enterprise_unrecorded numeric(28,8) NOT NULL DEFAULT 0,
    bank_debited_enterprise_unrecorded numeric(28,8) NOT NULL DEFAULT 0,
    adjusted_ledger_balance numeric(28,8) NOT NULL,
    is_balanced boolean NOT NULL,
    status varchar(32) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'CONFIRMED', 'CANCELLED')),
    preparer_person_id uuid NOT NULL,
    reviewer_person_id uuid,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_bank_reconciliation UNIQUE (tenant_id, book_id, account_id, period_id)
);

CREATE TABLE IF NOT EXISTS plugin_finance_bank_unreached_item (
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    id uuid PRIMARY KEY,
    reconciliation_id uuid NOT NULL REFERENCES plugin_finance_bank_reconciliation(id) ON DELETE CASCADE,
    account_id uuid NOT NULL REFERENCES plugin_finance_bank_account(id),
    item_type varchar(64) NOT NULL CHECK (item_type IN (
        'ENTERPRISE_RECORDED_BANK_UNRECORDED',
        'ENTERPRISE_PAID_BANK_UNPAID',
        'BANK_CREDITED_ENTERPRISE_UNRECORDED',
        'BANK_DEBITED_ENTERPRISE_UNRECORDED'
    )),
    recorded_date date NOT NULL,
    amount numeric(28,8) NOT NULL CHECK (amount > 0),
    summary varchar(256) NOT NULL,
    aging_days integer NOT NULL CHECK (aging_days >= 0),
    is_overdue_warning boolean NOT NULL DEFAULT false,
    status varchar(32) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'INVESTIGATING', 'ADJUSTED', 'CLEARED')),
    investigation_notes text,
    supplementary_evidence_id uuid,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_unreached_item_rec ON plugin_finance_bank_unreached_item(reconciliation_id, status);

CREATE TABLE IF NOT EXISTS plugin_finance_treasury_cash_forecast (
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES plugin_finance_bank_account(id),
    forecast_horizon_days integer NOT NULL CHECK (forecast_horizon_days IN (7, 14, 30, 90)),
    tier varchar(32) NOT NULL CHECK (tier IN ('CONFIRMED', 'COMMITTED', 'PLANNED')),
    forecast_date date NOT NULL,
    opening_cash numeric(28,8) NOT NULL,
    projected_inflow numeric(28,8) NOT NULL DEFAULT 0,
    projected_outflow numeric(28,8) NOT NULL DEFAULT 0,
    closing_cash numeric(28,8) NOT NULL,
    is_read_only_forecast boolean NOT NULL DEFAULT true CHECK (is_read_only_forecast = true),
    source_reference text,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_cash_forecast_lookup ON plugin_finance_treasury_cash_forecast(tenant_id, account_id, forecast_horizon_days, tier);

CREATE TABLE IF NOT EXISTS plugin_finance_treasury_event (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    entity_type varchar(64) NOT NULL,
    entity_id uuid NOT NULL,
    action varchar(64) NOT NULL,
    operator_person_id uuid NOT NULL,
    payload jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE OR REPLACE FUNCTION trg_fn_treasury_event_immutable()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'plugin_finance_treasury_event rows are immutable';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_treasury_event_immutable ON plugin_finance_treasury_event;
CREATE TRIGGER trg_treasury_event_immutable
BEFORE UPDATE OR DELETE ON plugin_finance_treasury_event
FOR EACH ROW EXECUTE FUNCTION trg_fn_treasury_event_immutable();
