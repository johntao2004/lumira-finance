-- Candidate migration for Lane W05: Expense, Cash Advance, Reimbursement & Travel Standards
-- Supports EXP01, EXP02, EXP03, EXP04 in compliance with PostgreSQL 18 & Forced RLS.

-- 1. Travel & Expense Standards (EXP04)
CREATE TABLE IF NOT EXISTS plugin_finance_travel_policy (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    policy_code VARCHAR(64) NOT NULL,
    version_int INT NOT NULL,
    name VARCHAR(128) NOT NULL,
    effective_from DATE NOT NULL,
    effective_to DATE,
    status VARCHAR(32) NOT NULL CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    created_by UUID NOT NULL,
    CONSTRAINT uq_travel_policy_version UNIQUE (tenant_id, policy_code, version_int),
    CONSTRAINT ck_travel_policy_dates CHECK (effective_to IS NULL OR effective_to >= effective_from)
);

CREATE TABLE IF NOT EXISTS plugin_finance_travel_policy_cap (
    id UUID PRIMARY KEY,
    policy_id UUID NOT NULL REFERENCES plugin_finance_travel_policy(id) ON DELETE CASCADE,
    city_tier VARCHAR(32) NOT NULL CHECK (city_tier IN ('TIER_1', 'TIER_2', 'TIER_3', 'SPECIAL')),
    employee_rank VARCHAR(32) NOT NULL CHECK (employee_rank IN ('STAFF', 'MANAGER', 'EXECUTIVE')),
    expense_category VARCHAR(32) NOT NULL CHECK (expense_category IN ('HOTEL', 'MEAL', 'TRANSPORT', 'DAILY_ALLOWANCE', 'INCIDENTAL')),
    daily_cap_amount numeric(28,8) NOT NULL CHECK (daily_cap_amount >= 0),
    currency CHAR(3) NOT NULL DEFAULT 'CNY',
    CONSTRAINT uq_travel_policy_cap UNIQUE (policy_id, city_tier, employee_rank, expense_category)
);

CREATE TABLE IF NOT EXISTS plugin_finance_travel_exception (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    claim_id UUID,
    line_no INT NOT NULL,
    policy_cap_id UUID REFERENCES plugin_finance_travel_policy_cap(id),
    requested_amount numeric(28,8) NOT NULL CHECK (requested_amount > 0),
    standard_cap_amount numeric(28,8) NOT NULL CHECK (standard_cap_amount >= 0),
    excess_amount numeric(28,8) NOT NULL CHECK (excess_amount > 0),
    applicant_person_id UUID NOT NULL,
    approver_person_id UUID,
    status VARCHAR(32) NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    justification VARCHAR(1000) NOT NULL,
    decision_notes VARCHAR(1000),
    decided_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_travel_exception_sod CHECK (approver_person_id IS NULL OR approver_person_id <> applicant_person_id),
    CONSTRAINT ck_travel_exception_excess_math CHECK (requested_amount = standard_cap_amount + excess_amount)
);

-- 2. Expense Pre-Approval Requisition & Budget Pre-Encumbrance (EXP01)
CREATE TABLE IF NOT EXISTS plugin_finance_expense_requisition (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    book_id UUID NOT NULL,
    requisition_no VARCHAR(64) UNIQUE NOT NULL,
    applicant_id UUID NOT NULL,
    department_id UUID NOT NULL,
    project_id UUID,
    business_purpose VARCHAR(500) NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'CNY',
    total_budget_amount numeric(28,8) NOT NULL CHECK (total_budget_amount > 0),
    reserved_amount numeric(28,8) NOT NULL DEFAULT 0 CHECK (reserved_amount >= 0),
    utilized_amount numeric(28,8) NOT NULL DEFAULT 0 CHECK (utilized_amount >= 0),
    status VARCHAR(32) NOT NULL CHECK (status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'CLOSED', 'CANCELLED')),
    approver_id UUID,
    approved_at TIMESTAMPTZ,
    effective_until DATE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    version BIGINT NOT NULL DEFAULT 1,
    CONSTRAINT ck_requisition_budget_cap CHECK (reserved_amount + utilized_amount <= total_budget_amount),
    CONSTRAINT ck_requisition_approver_sod CHECK (approver_id IS NULL OR approver_id <> applicant_id)
);

-- 3. Employee Cash Advance (EXP02)
CREATE TABLE IF NOT EXISTS plugin_finance_expense_advance (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    book_id UUID NOT NULL,
    advance_no VARCHAR(64) UNIQUE NOT NULL,
    employee_id UUID NOT NULL,
    requisition_id UUID REFERENCES plugin_finance_expense_requisition(id),
    purpose VARCHAR(500) NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'CNY',
    principal_amount numeric(28,8) NOT NULL CHECK (principal_amount > 0),
    offset_amount numeric(28,8) NOT NULL DEFAULT 0 CHECK (offset_amount >= 0),
    refunded_amount numeric(28,8) NOT NULL DEFAULT 0 CHECK (refunded_amount >= 0),
    remaining_balance numeric(28,8) NOT NULL CHECK (remaining_balance >= 0),
    status VARCHAR(32) NOT NULL CHECK (status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'PAID', 'PARTIALLY_OFFSET', 'SETTLED', 'CANCELLED')),
    applicant_id UUID NOT NULL,
    approver_id UUID,
    cashier_id UUID,
    payout_ref VARCHAR(128),
    payout_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    version BIGINT NOT NULL DEFAULT 1,
    CONSTRAINT ck_advance_balance_math CHECK (principal_amount = offset_amount + refunded_amount + remaining_balance),
    CONSTRAINT ck_advance_approver_sod CHECK (approver_id IS NULL OR approver_id <> applicant_id),
    CONSTRAINT ck_advance_approver_employee_sod CHECK (approver_id IS NULL OR approver_id <> employee_id),
    CONSTRAINT ck_advance_cashier_sod CHECK (cashier_id IS NULL OR (cashier_id <> applicant_id AND cashier_id <> approver_id)),
    CONSTRAINT ck_advance_cashier_employee_sod CHECK (cashier_id IS NULL OR cashier_id <> employee_id)
);

CREATE TABLE IF NOT EXISTS plugin_finance_expense_advance_refund (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    advance_id UUID NOT NULL REFERENCES plugin_finance_expense_advance(id),
    refund_no VARCHAR(64) UNIQUE NOT NULL,
    refund_amount numeric(28,8) NOT NULL CHECK (refund_amount > 0),
    refund_date DATE NOT NULL,
    receipt_method VARCHAR(32) NOT NULL,
    receipt_ref VARCHAR(128) NOT NULL,
    cashier_person_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

-- 4. Expense Reimbursement Claim, Splits, Partial Recognition & Payables (EXP03)
CREATE TABLE IF NOT EXISTS plugin_finance_expense_claim (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    book_id UUID NOT NULL,
    claim_no VARCHAR(64) UNIQUE NOT NULL,
    employee_id UUID NOT NULL,
    requisition_id UUID REFERENCES plugin_finance_expense_requisition(id),
    title VARCHAR(255) NOT NULL,
    claim_date DATE NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'CNY',
    submitted_amount numeric(28,8) NOT NULL CHECK (submitted_amount > 0),
    approved_amount numeric(28,8) NOT NULL DEFAULT 0 CHECK (approved_amount >= 0),
    disallowed_amount numeric(28,8) NOT NULL DEFAULT 0 CHECK (disallowed_amount >= 0),
    offset_advance_amount numeric(28,8) NOT NULL DEFAULT 0 CHECK (offset_advance_amount >= 0),
    payable_amount numeric(28,8) NOT NULL DEFAULT 0 CHECK (payable_amount >= 0),
    paid_amount numeric(28,8) NOT NULL DEFAULT 0 CHECK (paid_amount >= 0),
    status VARCHAR(32) NOT NULL CHECK (status IN ('DRAFT', 'SUBMITTED', 'REVIEWED', 'APPROVED', 'POSTED', 'PAID', 'SETTLED', 'REJECTED', 'CANCELLED', 'REVERSED')),
    submitter_id UUID NOT NULL,
    reviewer_id UUID,
    approver_id UUID,
    reviewed_at TIMESTAMPTZ,
    approved_at TIMESTAMPTZ,
    journal_id UUID,
    reversal_journal_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    version BIGINT NOT NULL DEFAULT 1,
    CONSTRAINT ck_claim_submitted_math CHECK (submitted_amount = approved_amount + disallowed_amount),
    CONSTRAINT ck_claim_payable_math CHECK (payable_amount = approved_amount - offset_advance_amount),
    CONSTRAINT ck_claim_paid_math CHECK (paid_amount <= payable_amount),
    CONSTRAINT ck_claim_sod_submitter_reviewer CHECK (reviewer_id IS NULL OR reviewer_id <> submitter_id),
    CONSTRAINT ck_claim_sod_submitter_approver CHECK (approver_id IS NULL OR approver_id <> submitter_id),
    CONSTRAINT ck_claim_sod_reviewer_approver CHECK (reviewer_id IS NULL OR approver_id IS NULL OR approver_id <> reviewer_id),
    CONSTRAINT ck_claim_sod_employee_reviewer CHECK (reviewer_id IS NULL OR reviewer_id <> employee_id),
    CONSTRAINT ck_claim_sod_employee_approver CHECK (approver_id IS NULL OR approver_id <> employee_id)
);

CREATE TABLE IF NOT EXISTS plugin_finance_expense_claim_line (
    id UUID PRIMARY KEY,
    claim_id UUID NOT NULL REFERENCES plugin_finance_expense_claim(id) ON DELETE CASCADE,
    line_no INT NOT NULL,
    expense_category VARCHAR(32) NOT NULL,
    department_id UUID NOT NULL,
    project_id UUID,
    invoice_id UUID,
    invoice_code VARCHAR(64),
    invoice_number VARCHAR(64),
    invoice_digest VARCHAR(64),
    submitted_amount numeric(28,8) NOT NULL CHECK (submitted_amount > 0),
    approved_reimbursable_amount numeric(28,8) NOT NULL CHECK (approved_reimbursable_amount >= 0),
    disallowed_amount numeric(28,8) NOT NULL DEFAULT 0 CHECK (disallowed_amount >= 0),
    disallowance_reason VARCHAR(500),
    vat_rate numeric(18,12) NOT NULL DEFAULT 0 CHECK (vat_rate >= 0),
    vat_input_amount numeric(28,8) NOT NULL DEFAULT 0 CHECK (vat_input_amount >= 0),
    net_expense_amount numeric(28,8) NOT NULL CHECK (net_expense_amount >= 0),
    travel_policy_cap_id UUID REFERENCES plugin_finance_travel_policy_cap(id),
    is_exception_approved BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_claim_line_no UNIQUE (claim_id, line_no),
    CONSTRAINT ck_claim_line_submitted_math CHECK (submitted_amount = approved_reimbursable_amount + disallowed_amount),
    CONSTRAINT ck_claim_line_vat_math CHECK (approved_reimbursable_amount = net_expense_amount + vat_input_amount)
);

CREATE TABLE IF NOT EXISTS plugin_finance_expense_claim_advance_offset (
    id UUID PRIMARY KEY,
    claim_id UUID NOT NULL REFERENCES plugin_finance_expense_claim(id) ON DELETE CASCADE,
    advance_id UUID NOT NULL REFERENCES plugin_finance_expense_advance(id),
    offset_amount numeric(28,8) NOT NULL CHECK (offset_amount > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_claim_advance_offset UNIQUE (claim_id, advance_id)
);

CREATE TABLE IF NOT EXISTS plugin_finance_expense_payable_ledger (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    book_id UUID NOT NULL,
    claim_id UUID NOT NULL REFERENCES plugin_finance_expense_claim(id) ON DELETE CASCADE,
    employee_id UUID NOT NULL,
    payable_amount numeric(28,8) NOT NULL CHECK (payable_amount >= 0),
    paid_amount numeric(28,8) NOT NULL DEFAULT 0 CHECK (paid_amount >= 0),
    outstanding_balance numeric(28,8) NOT NULL CHECK (outstanding_balance >= 0),
    settlement_status VARCHAR(32) NOT NULL CHECK (settlement_status IN ('UNPAID', 'PARTIALLY_PAID', 'SETTLED', 'CANCELLED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    version BIGINT NOT NULL DEFAULT 1,
    CONSTRAINT uq_payable_ledger_claim UNIQUE (claim_id),
    CONSTRAINT ck_payable_ledger_math CHECK ((settlement_status = 'CANCELLED' AND outstanding_balance = 0) OR payable_amount = paid_amount + outstanding_balance)
);

CREATE TABLE IF NOT EXISTS plugin_finance_expense_payment (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    payable_id UUID NOT NULL REFERENCES plugin_finance_expense_payable_ledger(id),
    payment_no VARCHAR(64) UNIQUE NOT NULL,
    amount numeric(28,8) NOT NULL CHECK (amount > 0),
    payment_date DATE NOT NULL,
    payment_method VARCHAR(32) NOT NULL,
    payment_ref VARCHAR(128) NOT NULL,
    cashier_id UUID NOT NULL,
    journal_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

-- Indexes for efficient queries
CREATE INDEX IF NOT EXISTS idx_expense_req_tenant_book ON plugin_finance_expense_requisition (tenant_id, book_id, status);
CREATE INDEX IF NOT EXISTS idx_expense_adv_tenant_employee ON plugin_finance_expense_advance (tenant_id, employee_id, status);
CREATE INDEX IF NOT EXISTS idx_expense_claim_tenant_employee ON plugin_finance_expense_claim (tenant_id, employee_id, status);
CREATE INDEX IF NOT EXISTS idx_expense_payable_tenant_employee ON plugin_finance_expense_payable_ledger (tenant_id, employee_id, settlement_status);

-- Forced Row Level Security (RLS) on personal employee records & tenant data
ALTER TABLE plugin_finance_expense_claim ENABLE ROW LEVEL SECURITY;
ALTER TABLE plugin_finance_expense_claim FORCE ROW LEVEL SECURITY;

ALTER TABLE plugin_finance_expense_advance ENABLE ROW LEVEL SECURITY;
ALTER TABLE plugin_finance_expense_advance FORCE ROW LEVEL SECURITY;

ALTER TABLE plugin_finance_expense_payable_ledger ENABLE ROW LEVEL SECURITY;
ALTER TABLE plugin_finance_expense_payable_ledger FORCE ROW LEVEL SECURITY;

ALTER TABLE plugin_finance_expense_requisition ENABLE ROW LEVEL SECURITY;
ALTER TABLE plugin_finance_expense_requisition FORCE ROW LEVEL SECURITY;

ALTER TABLE plugin_finance_travel_policy ENABLE ROW LEVEL SECURITY;
ALTER TABLE plugin_finance_travel_policy FORCE ROW LEVEL SECURITY;

ALTER TABLE plugin_finance_travel_exception ENABLE ROW LEVEL SECURITY;
ALTER TABLE plugin_finance_travel_exception FORCE ROW LEVEL SECURITY;

ALTER TABLE plugin_finance_expense_advance_refund ENABLE ROW LEVEL SECURITY;
ALTER TABLE plugin_finance_expense_advance_refund FORCE ROW LEVEL SECURITY;

ALTER TABLE plugin_finance_expense_payment ENABLE ROW LEVEL SECURITY;
ALTER TABLE plugin_finance_expense_payment FORCE ROW LEVEL SECURITY;

-- Tenant & Employee RLS Policies (Strict nullif: unauthenticated/unset context cannot leak rows)
DROP POLICY IF EXISTS p_claim_isolation ON plugin_finance_expense_claim;
CREATE POLICY p_claim_isolation ON plugin_finance_expense_claim
    USING (
        tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid
        AND (
            current_setting('app.is_finance_reviewer', true) = 'true'
            OR employee_id = nullif(current_setting('app.current_employee_id', true), '')::uuid
        )
    )
    WITH CHECK (
        tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid
    );

DROP POLICY IF EXISTS p_advance_isolation ON plugin_finance_expense_advance;
CREATE POLICY p_advance_isolation ON plugin_finance_expense_advance
    USING (
        tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid
        AND (
            current_setting('app.is_finance_reviewer', true) = 'true'
            OR employee_id = nullif(current_setting('app.current_employee_id', true), '')::uuid
        )
    )
    WITH CHECK (
        tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid
    );

DROP POLICY IF EXISTS p_payable_isolation ON plugin_finance_expense_payable_ledger;
CREATE POLICY p_payable_isolation ON plugin_finance_expense_payable_ledger
    USING (
        tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid
        AND (
            current_setting('app.is_finance_reviewer', true) = 'true'
            OR employee_id = nullif(current_setting('app.current_employee_id', true), '')::uuid
        )
    )
    WITH CHECK (
        tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid
    );

DROP POLICY IF EXISTS p_requisition_isolation ON plugin_finance_expense_requisition;
CREATE POLICY p_requisition_isolation ON plugin_finance_expense_requisition
    USING (
        tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid
        AND (
            current_setting('app.is_finance_reviewer', true) = 'true'
            OR applicant_id = nullif(current_setting('app.current_employee_id', true), '')::uuid
        )
    )
    WITH CHECK (
        tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid
    );

DROP POLICY IF EXISTS p_policy_isolation ON plugin_finance_travel_policy;
CREATE POLICY p_policy_isolation ON plugin_finance_travel_policy
    USING (
        tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid
    )
    WITH CHECK (
        tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid
    );

DROP POLICY IF EXISTS p_exception_isolation ON plugin_finance_travel_exception;
CREATE POLICY p_exception_isolation ON plugin_finance_travel_exception
    USING (
        tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid
        AND (
            current_setting('app.is_finance_reviewer', true) = 'true'
            OR applicant_person_id = nullif(current_setting('app.current_employee_id', true), '')::uuid
        )
    )
    WITH CHECK (
        tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid
    );

DROP POLICY IF EXISTS p_refund_isolation ON plugin_finance_expense_advance_refund;
CREATE POLICY p_refund_isolation ON plugin_finance_expense_advance_refund
    USING (
        tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid
    )
    WITH CHECK (
        tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid
    );

DROP POLICY IF EXISTS p_payment_isolation ON plugin_finance_expense_payment;
CREATE POLICY p_payment_isolation ON plugin_finance_expense_payment
    USING (
        tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid
    )
    WITH CHECK (
        tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid
    );

