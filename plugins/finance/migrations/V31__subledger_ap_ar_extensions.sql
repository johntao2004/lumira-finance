-- ============================================================================
-- Candidate Migration: AP01 / AR01 / AR02 Subledger Extensions (Lane W06)
-- Target Database: PostgreSQL 18
-- ============================================================================

-- 1. AP Purchase Orders (采购订单)
CREATE TABLE IF NOT EXISTS plugin_finance_ap_po_order (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    book_id UUID NOT NULL,
    year_id UUID NOT NULL,
    po_number VARCHAR(64) NOT NULL,
    supplier_id UUID NOT NULL,
    supplier_revision_id UUID NOT NULL,
    order_date DATE NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'CNY',
    total_net_amount NUMERIC(28,8) NOT NULL CHECK (total_net_amount >= 0),
    total_tax_amount NUMERIC(28,8) NOT NULL CHECK (total_tax_amount >= 0),
    total_gross_amount NUMERIC(28,8) NOT NULL CHECK (total_gross_amount >= 0),
    terms TEXT,
    status VARCHAR(32) NOT NULL CHECK (status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'CANCELLED', 'CLOSED')),
    original_id UUID,
    created_by UUID NOT NULL,
    approved_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 1,
    CONSTRAINT uq_ap_po_number UNIQUE (tenant_id, book_id, year_id, po_number)
);

CREATE INDEX IF NOT EXISTS idx_ap_po_lookup ON plugin_finance_ap_po_order (tenant_id, book_id, year_id, status);

-- 2. AP Purchase Order Lines (采购订单明细)
CREATE TABLE IF NOT EXISTS plugin_finance_ap_po_line (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    po_id UUID NOT NULL REFERENCES plugin_finance_ap_po_order(id) ON DELETE CASCADE,
    line_no INT NOT NULL,
    item_code VARCHAR(64) NOT NULL,
    item_name VARCHAR(256) NOT NULL,
    quantity NUMERIC(28,8) NOT NULL CHECK (quantity > 0),
    unit_price NUMERIC(28,8) NOT NULL CHECK (unit_price >= 0),
    net_amount NUMERIC(28,8) NOT NULL CHECK (net_amount >= 0),
    tax_rate NUMERIC(18,12) NOT NULL CHECK (tax_rate >= 0),
    tax_amount NUMERIC(28,8) NOT NULL CHECK (tax_amount >= 0),
    gross_amount NUMERIC(28,8) NOT NULL CHECK (gross_amount >= 0),
    received_quantity NUMERIC(28,8) NOT NULL DEFAULT 0 CHECK (received_quantity >= 0),
    invoiced_quantity NUMERIC(28,8) NOT NULL DEFAULT 0 CHECK (invoiced_quantity >= 0),
    CONSTRAINT uq_ap_po_line UNIQUE (po_id, line_no)
);

CREATE INDEX IF NOT EXISTS idx_ap_po_line_po ON plugin_finance_ap_po_line (po_id);

-- 3. AP Goods/Service Acceptance (验收单)
CREATE TABLE IF NOT EXISTS plugin_finance_ap_po_acceptance (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    book_id UUID NOT NULL,
    year_id UUID NOT NULL,
    po_id UUID NOT NULL REFERENCES plugin_finance_ap_po_order(id),
    acceptance_number VARCHAR(64) NOT NULL,
    accepted_date DATE NOT NULL,
    accepted_by UUID NOT NULL,
    acceptance_note TEXT,
    status VARCHAR(32) NOT NULL CHECK (status IN ('SUBMITTED', 'ACCEPTED', 'CANCELLED')),
    invoice_status VARCHAR(32) NOT NULL DEFAULT 'UNINVOICED' CHECK (invoice_status IN ('UNINVOICED', 'PARTIALLY_INVOICED', 'FULLY_INVOICED')),
    provisional_status VARCHAR(32) NOT NULL DEFAULT 'NONE' CHECK (provisional_status IN ('NONE', 'PROVISIONALLY_ACCRUED', 'REVERSED', 'SETTLED')),
    original_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 1,
    CONSTRAINT uq_ap_acceptance_number UNIQUE (tenant_id, book_id, year_id, acceptance_number)
);

CREATE INDEX IF NOT EXISTS idx_ap_acceptance_po ON plugin_finance_ap_po_acceptance (tenant_id, book_id, year_id, po_id);

-- 4. AP Goods/Service Acceptance Lines (验收单明细)
CREATE TABLE IF NOT EXISTS plugin_finance_ap_po_acceptance_line (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    acceptance_id UUID NOT NULL REFERENCES plugin_finance_ap_po_acceptance(id) ON DELETE CASCADE,
    po_line_id UUID NOT NULL REFERENCES plugin_finance_ap_po_line(id),
    line_no INT NOT NULL,
    accepted_quantity NUMERIC(28,8) NOT NULL CHECK (accepted_quantity > 0),
    unit_cost_estimate NUMERIC(28,8) NOT NULL CHECK (unit_cost_estimate >= 0),
    estimated_net_amount NUMERIC(28,8) NOT NULL CHECK (estimated_net_amount >= 0),
    invoiced_quantity NUMERIC(28,8) NOT NULL DEFAULT 0 CHECK (invoiced_quantity >= 0),
    CONSTRAINT uq_ap_acceptance_line UNIQUE (acceptance_id, line_no)
);

-- 5. AP Month-end Provisional Accrual (暂估应付入账)
CREATE TABLE IF NOT EXISTS plugin_finance_ap_provisional_accrual (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    book_id UUID NOT NULL,
    year_id UUID NOT NULL,
    acceptance_id UUID NOT NULL REFERENCES plugin_finance_ap_po_acceptance(id),
    po_id UUID NOT NULL REFERENCES plugin_finance_ap_po_order(id),
    accrual_period INT NOT NULL CHECK (accrual_period BETWEEN 1 AND 12),
    accrual_date DATE NOT NULL,
    estimated_net_amount NUMERIC(28,8) NOT NULL CHECK (estimated_net_amount > 0),
    status VARCHAR(32) NOT NULL CHECK (status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'POSTED', 'PROVISIONALLY_ACCRUED', 'REVERSED', 'SETTLED')),
    journal_id UUID,
    reversal_period INT CHECK (reversal_period BETWEEN 1 AND 12),
    reversal_date DATE,
    reversal_journal_id UUID,
    reason TEXT,
    created_by UUID NOT NULL,
    approved_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 1
);

CREATE INDEX IF NOT EXISTS idx_ap_provisional_lookup ON plugin_finance_ap_provisional_accrual (tenant_id, book_id, year_id, status);

-- 6. AP Provisional Settlement & Variance Adjustment (到票冲暂估及差额调整)
CREATE TABLE IF NOT EXISTS plugin_finance_ap_provisional_settlement (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    book_id UUID NOT NULL,
    year_id UUID NOT NULL,
    provisional_id UUID NOT NULL REFERENCES plugin_finance_ap_provisional_accrual(id),
    invoice_number VARCHAR(64) NOT NULL,
    invoice_date DATE NOT NULL,
    actual_net_amount NUMERIC(28,8) NOT NULL CHECK (actual_net_amount >= 0),
    actual_tax_amount NUMERIC(28,8) NOT NULL CHECK (actual_tax_amount >= 0),
    actual_gross_amount NUMERIC(28,8) NOT NULL CHECK (actual_gross_amount >= 0),
    price_variance NUMERIC(28,8) NOT NULL DEFAULT 0,
    quantity_variance NUMERIC(28,8) NOT NULL DEFAULT 0,
    total_variance NUMERIC(28,8) NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL CHECK (status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'POSTED')),
    journal_id UUID,
    created_by UUID NOT NULL,
    approved_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 1
);

CREATE INDEX IF NOT EXISTS idx_ap_settlement_provisional ON plugin_finance_ap_provisional_settlement (tenant_id, book_id, year_id, provisional_id);

-- 7. AR CAS 14 Contract Assets (合同资产)
CREATE TABLE IF NOT EXISTS plugin_finance_ar_contract_asset (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    book_id UUID NOT NULL,
    year_id UUID NOT NULL,
    contract_id UUID NOT NULL,
    customer_id UUID NOT NULL,
    stage_name VARCHAR(128) NOT NULL,
    milestone_date DATE NOT NULL,
    asset_amount NUMERIC(28,8) NOT NULL CHECK (asset_amount > 0),
    condition_description TEXT NOT NULL,
    condition_status VARCHAR(32) NOT NULL CHECK (condition_status IN ('CONDITIONAL', 'FULFILLED', 'WRITTEN_OFF')),
    reclassified_date DATE,
    reclassified_ar_id UUID,
    status VARCHAR(32) NOT NULL CHECK (status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'POSTED', 'RECLASSIFIED')),
    journal_id UUID,
    created_by UUID NOT NULL,
    approved_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 1
);

CREATE INDEX IF NOT EXISTS idx_ar_contract_asset ON plugin_finance_ar_contract_asset (tenant_id, book_id, year_id, contract_id);

-- 8. AR Constrained Variable Consideration (可变对价上限审签)
CREATE TABLE IF NOT EXISTS plugin_finance_ar_contract_asset_variable (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    book_id UUID NOT NULL,
    year_id UUID NOT NULL,
    contract_id UUID NOT NULL,
    variable_kind VARCHAR(32) NOT NULL CHECK (variable_kind IN ('BONUS', 'REBATE', 'PENALTY', 'CONCESSION')),
    estimated_amount NUMERIC(28,8) NOT NULL CHECK (estimated_amount >= 0),
    confidence_score NUMERIC(5,4) NOT NULL CHECK (confidence_score >= 0 AND confidence_score <= 1.0000),
    constrained_cap_amount NUMERIC(28,8) NOT NULL CHECK (constrained_cap_amount >= 0),
    cap_rationale TEXT NOT NULL,
    status VARCHAR(32) NOT NULL CHECK (status IN ('PENDING_APPROVAL', 'APPROVED', 'REVISED', 'REJECTED')),
    preparer_id UUID NOT NULL,
    approved_by UUID,
    approved_at TIMESTAMPTZ,
    approval_digest VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 1
);

CREATE INDEX IF NOT EXISTS idx_ar_var_consideration ON plugin_finance_ar_contract_asset_variable (tenant_id, book_id, year_id, contract_id);

-- 9. AR Multi-Year Contract Performance Continuity (多年合同履约连续性)
CREATE TABLE IF NOT EXISTS plugin_finance_ar_contract_asset_multi_year (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    book_id UUID NOT NULL,
    contract_id UUID NOT NULL,
    start_year INT NOT NULL,
    end_year INT NOT NULL CHECK (end_year >= start_year),
    total_contract_value NUMERIC(28,8) NOT NULL CHECK (total_contract_value >= 0),
    cumulative_progress_pct NUMERIC(18,12) NOT NULL DEFAULT 0 CHECK (cumulative_progress_pct >= 0 AND cumulative_progress_pct <= 1.0),
    cumulative_revenue_recognized NUMERIC(28,8) NOT NULL DEFAULT 0 CHECK (cumulative_revenue_recognized >= 0),
    current_year_revenue NUMERIC(28,8) NOT NULL DEFAULT 0 CHECK (current_year_revenue >= 0),
    unrecognized_balance NUMERIC(28,8) NOT NULL CHECK (unrecognized_balance >= 0),
    last_sync_year INT NOT NULL,
    status VARCHAR(32) NOT NULL CHECK (status IN ('ACTIVE', 'COMPLETED', 'SUSPENDED')),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 1,
    CONSTRAINT uq_ar_multi_year_contract UNIQUE (tenant_id, book_id, contract_id)
);

-- 10. AR Expected Credit Loss (ECL) & Bad-Debt Provision (坏账准备与核销)
CREATE TABLE IF NOT EXISTS plugin_finance_ar_allowance_ecl (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    book_id UUID NOT NULL,
    year_id UUID NOT NULL,
    period INT NOT NULL CHECK (period BETWEEN 1 AND 12),
    assessment_type VARCHAR(32) NOT NULL CHECK (assessment_type IN ('AGING_BUCKET', 'INDIVIDUAL_ASSESSMENT')),
    customer_id UUID NOT NULL,
    receivable_amount NUMERIC(28,8) NOT NULL CHECK (receivable_amount >= 0),
    aging_bucket VARCHAR(32) NOT NULL CHECK (aging_bucket IN ('NOT_DUE', 'DAYS_1_30', 'DAYS_31_60', 'DAYS_61_90', 'DAYS_91_180', 'DAYS_181_PLUS')),
    loss_rate NUMERIC(18,12) NOT NULL CHECK (loss_rate >= 0 AND loss_rate <= 1.0),
    provision_amount NUMERIC(28,8) NOT NULL CHECK (provision_amount >= 0),
    prior_allowance NUMERIC(28,8) NOT NULL DEFAULT 0 CHECK (prior_allowance >= 0),
    adjustment_amount NUMERIC(28,8) NOT NULL,
    operation_kind VARCHAR(32) NOT NULL CHECK (operation_kind IN ('PROVISION', 'REVERSAL', 'WRITE_OFF', 'WRITE_OFF_RECOVERY')),
    status VARCHAR(32) NOT NULL CHECK (status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'POSTED', 'REJECTED')),
    preparer_id UUID NOT NULL,
    approver_id UUID,
    approval_digest VARCHAR(128),
    evidence_id UUID,
    journal_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 1
);

CREATE INDEX IF NOT EXISTS idx_ar_ecl_lookup ON plugin_finance_ar_allowance_ecl (tenant_id, book_id, year_id, customer_id);

-- 11. AR Sales Allowance, Discount & Return Adjustments (销售折让与退回)
CREATE TABLE IF NOT EXISTS plugin_finance_ar_allowance_adjustment (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    book_id UUID NOT NULL,
    year_id UUID NOT NULL,
    customer_id UUID NOT NULL,
    contract_id UUID,
    receivable_id UUID,
    adjustment_type VARCHAR(32) NOT NULL CHECK (adjustment_type IN ('COMMERCIAL_DISCOUNT', 'SALES_ALLOWANCE', 'SALES_RETURN')),
    credit_note_number VARCHAR(64) NOT NULL,
    adjustment_date DATE NOT NULL,
    original_receivable_amount NUMERIC(28,8) NOT NULL CHECK (original_receivable_amount >= 0),
    net_adjustment_amount NUMERIC(28,8) NOT NULL CHECK (net_adjustment_amount >= 0),
    tax_adjustment_amount NUMERIC(28,8) NOT NULL CHECK (tax_adjustment_amount >= 0),
    gross_adjustment_amount NUMERIC(28,8) NOT NULL CHECK (gross_adjustment_amount >= 0),
    vat_red_invoice_info VARCHAR(128),
    reason TEXT NOT NULL,
    status VARCHAR(32) NOT NULL CHECK (status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'POSTED', 'REJECTED')),
    preparer_id UUID NOT NULL,
    approver_id UUID,
    approval_digest VARCHAR(128),
    journal_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 1,
    CONSTRAINT uq_ar_credit_note UNIQUE (tenant_id, book_id, year_id, credit_note_number)
);

CREATE INDEX IF NOT EXISTS idx_ar_allowance_lookup ON plugin_finance_ar_allowance_adjustment (tenant_id, book_id, year_id, customer_id);

-- 12. Subledger Extension Idempotency (请求与效果防重)
CREATE TABLE IF NOT EXISTS plugin_finance_subledger_ext_idempotency (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    book_id UUID NOT NULL,
    year_id UUID NOT NULL,
    client_key VARCHAR(128) NOT NULL,
    operation VARCHAR(64) NOT NULL,
    resource_id UUID NOT NULL,
    response_payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_subledger_ext_idempotency UNIQUE (tenant_id, book_id, year_id, client_key, operation)
);
