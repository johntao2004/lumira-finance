-- PostgreSQL 18 Migration Candidate for Parallel Candidate Lane W10 (payroll-tax-ext)
-- PAY03: Annual one-time bonus (全年一次性奖金) calculation & election engine
-- PAY04: Natural-person external service remuneration (劳务报酬/稿酬/特许权使用费) & VAT withholding
-- TAX01: Versioned tax policy pack repository, qualification attestation, calculation explain tree
-- TAX05: Statutory filing calendar generator, form cell lock, and conditional schedule attachment check
-- All personal income and tax tables enforce ENABLE + FORCE ROW LEVEL SECURITY.

CREATE TABLE IF NOT EXISTS plugin_finance_payroll_bonus_batch (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    withholding_entity_id uuid NOT NULL,
    tax_year integer NOT NULL CHECK (tax_year BETWEEN 2000 AND 2099),
    bonus_name text NOT NULL,
    pay_date date NOT NULL,
    state text NOT NULL CHECK (state IN ('DRAFT', 'CALCULATED', 'REVIEWED', 'APPROVED', 'LOCKED', 'CANCELLED')),
    preparer_id uuid NOT NULL,
    reviewer_id uuid,
    approver_id uuid,
    locked_at timestamptz,
    external_tax_channel text NOT NULL DEFAULT 'NOT_VERIFIED' CHECK (external_tax_channel = 'NOT_VERIFIED'),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT chk_bonus_batch_sod CHECK (
        (reviewer_id IS NULL OR reviewer_id <> preparer_id) AND
        (approver_id IS NULL OR (approver_id <> preparer_id AND approver_id <> reviewer_id))
    ),
    CONSTRAINT chk_bonus_batch_state_integrity CHECK (
        (state IN ('DRAFT', 'CALCULATED') AND reviewer_id IS NULL AND approver_id IS NULL AND locked_at IS NULL) OR
        (state = 'REVIEWED' AND reviewer_id IS NOT NULL AND approver_id IS NULL AND locked_at IS NULL) OR
        (state = 'APPROVED' AND reviewer_id IS NOT NULL AND approver_id IS NOT NULL AND locked_at IS NULL) OR
        (state = 'LOCKED' AND reviewer_id IS NOT NULL AND approver_id IS NOT NULL AND locked_at IS NOT NULL) OR
        (state = 'CANCELLED')
    )
);

CREATE TABLE IF NOT EXISTS plugin_finance_payroll_bonus_item (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    batch_id uuid NOT NULL REFERENCES plugin_finance_payroll_bonus_batch(id) ON DELETE CASCADE,
    employee_id uuid NOT NULL,
    bonus_amount numeric(28,2) NOT NULL CHECK (bonus_amount > 0),
    calculation_mode text NOT NULL CHECK (calculation_mode IN ('SEPARATE_MONTHLY_QUOTIENT', 'COMBINED_COMPREHENSIVE_INCOME')),
    monthly_quotient numeric(28,2) NOT NULL DEFAULT 0,
    applicable_rate numeric(12,6) NOT NULL DEFAULT 0,
    quick_deduction numeric(28,2) NOT NULL DEFAULT 0,
    withholding_tax numeric(28,2) NOT NULL DEFAULT 0,
    net_amount numeric(28,2) NOT NULL,
    comparison_recommended_mode text NOT NULL CHECK (comparison_recommended_mode IN ('SEPARATE_MONTHLY_QUOTIENT', 'COMBINED_COMPREHENSIVE_INCOME')),
    comparison_tax_savings numeric(28,2) NOT NULL DEFAULT 0,
    explain_tree jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_bonus_batch_employee UNIQUE (batch_id, employee_id)
);

CREATE TABLE IF NOT EXISTS plugin_finance_payroll_bonus_election (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    withholding_entity_id uuid NOT NULL,
    employee_id uuid NOT NULL,
    tax_year integer NOT NULL CHECK (tax_year BETWEEN 2000 AND 2099),
    batch_id uuid NOT NULL REFERENCES plugin_finance_payroll_bonus_batch(id),
    election_mode text NOT NULL CHECK (election_mode = 'SEPARATE_MONTHLY_QUOTIENT'),
    elected_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_bonus_election_year UNIQUE (tenant_id, withholding_entity_id, employee_id, tax_year, election_mode)
);

CREATE TABLE IF NOT EXISTS plugin_finance_payroll_labor_batch (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    withholding_entity_id uuid NOT NULL,
    period text NOT NULL CHECK (period ~ '^[0-9]{4}-(?:0[1-9]|1[0-2])$'),
    batch_name text NOT NULL,
    pay_date date NOT NULL,
    state text NOT NULL CHECK (state IN ('DRAFT', 'CALCULATED', 'REVIEWED', 'APPROVED', 'LOCKED', 'CANCELLED')),
    preparer_id uuid NOT NULL,
    reviewer_id uuid,
    approver_id uuid,
    locked_at timestamptz,
    external_tax_channel text NOT NULL DEFAULT 'NOT_VERIFIED' CHECK (external_tax_channel = 'NOT_VERIFIED'),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT chk_labor_batch_sod CHECK (
        (reviewer_id IS NULL OR reviewer_id <> preparer_id) AND
        (approver_id IS NULL OR (approver_id <> preparer_id AND approver_id <> reviewer_id))
    ),
    CONSTRAINT chk_labor_batch_state_integrity CHECK (
        (state IN ('DRAFT', 'CALCULATED') AND reviewer_id IS NULL AND approver_id IS NULL AND locked_at IS NULL) OR
        (state = 'REVIEWED' AND reviewer_id IS NOT NULL AND approver_id IS NULL AND locked_at IS NULL) OR
        (state = 'APPROVED' AND reviewer_id IS NOT NULL AND approver_id IS NOT NULL AND locked_at IS NULL) OR
        (state = 'LOCKED' AND reviewer_id IS NOT NULL AND approver_id IS NOT NULL AND locked_at IS NOT NULL) OR
        (state = 'CANCELLED')
    )
);

CREATE TABLE IF NOT EXISTS plugin_finance_payroll_labor_item (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    batch_id uuid NOT NULL REFERENCES plugin_finance_payroll_labor_batch(id) ON DELETE CASCADE,
    natural_person_id uuid NOT NULL,
    service_category text NOT NULL CHECK (service_category IN ('LABOR_SERVICE', 'AUTHOR_REMUNERATION', 'ROYALTY')),
    gross_payment numeric(28,2) NOT NULL CHECK (gross_payment > 0),
    deduction_amount numeric(28,2) NOT NULL DEFAULT 0,
    taxable_income numeric(28,2) NOT NULL DEFAULT 0,
    iit_rate numeric(12,6) NOT NULL DEFAULT 0,
    iit_quick_deduction numeric(28,2) NOT NULL DEFAULT 0,
    iit_withholding numeric(28,2) NOT NULL DEFAULT 0,
    vat_sales numeric(28,2) NOT NULL DEFAULT 0,
    vat_exempt boolean NOT NULL DEFAULT false,
    vat_rate numeric(12,6) NOT NULL DEFAULT 0,
    vat_reduction numeric(28,2) NOT NULL DEFAULT 0,
    vat_withheld numeric(28,2) NOT NULL DEFAULT 0,
    city_surtax numeric(28,2) NOT NULL DEFAULT 0,
    education_surcharge numeric(28,2) NOT NULL DEFAULT 0,
    local_education_surcharge numeric(28,2) NOT NULL DEFAULT 0,
    total_surcharge_withheld numeric(28,2) NOT NULL DEFAULT 0,
    net_payable numeric(28,2) NOT NULL,
    proxy_invoice_completed boolean NOT NULL DEFAULT false,
    proxy_invoice_ref text,
    explain_tree jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT chk_labor_proxy_invoice CHECK (
        NOT proxy_invoice_completed OR (proxy_invoice_ref IS NOT NULL AND length(trim(proxy_invoice_ref)) > 0)
    )
);

CREATE TABLE IF NOT EXISTS plugin_finance_tax_policy_pack (
    id text PRIMARY KEY,
    tenant_id uuid NOT NULL,
    tax_type text NOT NULL CHECK (tax_type IN ('VAT', 'SURTAX', 'STAMP_DUTY', 'CIT_RD', 'CIT_SMALL_LOW_PROFIT')),
    version text NOT NULL,
    effective_from date NOT NULL,
    effective_to date NOT NULL,
    digest text NOT NULL CHECK (digest ~ '^[a-f0-9]{64}$'),
    approval_status text NOT NULL CHECK (approval_status IN ('DRAFT', 'APPROVED', 'REVOKED')),
    attestation_required boolean NOT NULL DEFAULT false,
    rules jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE IF NOT EXISTS plugin_finance_tax_qualification_attestation (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    policy_pack_id text NOT NULL REFERENCES plugin_finance_tax_policy_pack(id),
    tax_entity_id uuid NOT NULL,
    qualification_category text NOT NULL CHECK (qualification_category IN ('HIGH_NEW_TECH', 'SMALL_LOW_PROFIT', 'RD_QUALIFIED', 'POLLUTION_PREVENTION', 'SOFTWARE_ENTERPRISE')),
    valid_from date NOT NULL,
    valid_through date NOT NULL,
    attested_by_natural_person uuid NOT NULL,
    attestation_digest text NOT NULL CHECK (attestation_digest ~ '^[a-f0-9]{64}$'),
    qualification_facts jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_tax_qualification_period UNIQUE (tenant_id, policy_pack_id, tax_entity_id, valid_from, valid_through)
);

CREATE TABLE IF NOT EXISTS plugin_finance_tax_calendar_pack (
    id text PRIMARY KEY,
    tenant_id uuid NOT NULL,
    region text NOT NULL,
    tax_year integer NOT NULL CHECK (tax_year BETWEEN 2000 AND 2099),
    pack_version text NOT NULL,
    digest text NOT NULL CHECK (digest ~ '^[a-f0-9]{64}$'),
    approval_status text NOT NULL CHECK (approval_status IN ('DRAFT', 'APPROVED', 'REVOKED')),
    schedule_entries jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE IF NOT EXISTS plugin_finance_tax_form_lock (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL,
    tax_obligation_id uuid NOT NULL,
    form_code text NOT NULL,
    version_number integer NOT NULL,
    is_locked boolean NOT NULL DEFAULT false,
    locked_at timestamptz,
    locked_by_natural_person uuid,
    content_digest text NOT NULL CHECK (content_digest ~ '^[a-f0-9]{64}$'),
    cell_values jsonb NOT NULL,
    mandatory_attachments jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_tax_form_lock UNIQUE (tenant_id, tax_obligation_id, form_code, version_number)
);

-- Force Row Level Security on all 9 personal income and tax extension tables
ALTER TABLE plugin_finance_payroll_bonus_batch ENABLE ROW LEVEL SECURITY;
ALTER TABLE plugin_finance_payroll_bonus_batch FORCE ROW LEVEL SECURITY;
CREATE POLICY bonus_batch_tenant_rls ON plugin_finance_payroll_bonus_batch
    FOR ALL
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE plugin_finance_payroll_bonus_item ENABLE ROW LEVEL SECURITY;
ALTER TABLE plugin_finance_payroll_bonus_item FORCE ROW LEVEL SECURITY;
CREATE POLICY bonus_item_tenant_rls ON plugin_finance_payroll_bonus_item
    FOR ALL
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE plugin_finance_payroll_bonus_election ENABLE ROW LEVEL SECURITY;
ALTER TABLE plugin_finance_payroll_bonus_election FORCE ROW LEVEL SECURITY;
CREATE POLICY bonus_election_tenant_rls ON plugin_finance_payroll_bonus_election
    FOR ALL
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE plugin_finance_payroll_labor_batch ENABLE ROW LEVEL SECURITY;
ALTER TABLE plugin_finance_payroll_labor_batch FORCE ROW LEVEL SECURITY;
CREATE POLICY labor_batch_tenant_rls ON plugin_finance_payroll_labor_batch
    FOR ALL
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE plugin_finance_payroll_labor_item ENABLE ROW LEVEL SECURITY;
ALTER TABLE plugin_finance_payroll_labor_item FORCE ROW LEVEL SECURITY;
CREATE POLICY labor_item_tenant_rls ON plugin_finance_payroll_labor_item
    FOR ALL
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE plugin_finance_tax_policy_pack ENABLE ROW LEVEL SECURITY;
ALTER TABLE plugin_finance_tax_policy_pack FORCE ROW LEVEL SECURITY;
CREATE POLICY tax_policy_pack_tenant_rls ON plugin_finance_tax_policy_pack
    FOR ALL
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE plugin_finance_tax_qualification_attestation ENABLE ROW LEVEL SECURITY;
ALTER TABLE plugin_finance_tax_qualification_attestation FORCE ROW LEVEL SECURITY;
CREATE POLICY tax_qualification_tenant_rls ON plugin_finance_tax_qualification_attestation
    FOR ALL
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE plugin_finance_tax_calendar_pack ENABLE ROW LEVEL SECURITY;
ALTER TABLE plugin_finance_tax_calendar_pack FORCE ROW LEVEL SECURITY;
CREATE POLICY tax_calendar_pack_tenant_rls ON plugin_finance_tax_calendar_pack
    FOR ALL
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);

ALTER TABLE plugin_finance_tax_form_lock ENABLE ROW LEVEL SECURITY;
ALTER TABLE plugin_finance_tax_form_lock FORCE ROW LEVEL SECURITY;
CREATE POLICY tax_form_lock_tenant_rls ON plugin_finance_tax_form_lock
    FOR ALL
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
