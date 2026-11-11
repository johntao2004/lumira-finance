-- =============================================================================
-- Migration Candidate: Lane W01 (org-fx) — ORG01, ORG02, ORG03, ORG04, ORG05
-- PostgreSQL 18 Forward Schema Candidate for Enterprise Org, Multi-Date, FX & Tax Catalog
-- All tables are finance-owned with numeric(28,8) money & numeric(28,12) FX rates
-- =============================================================================

-- -----------------------------------------------------------------------------
-- ORG01: Competent Tax Bureaus, Branches & Entity SoD Lifecycle
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS plugin_finance_org_tax_bureau (
    bureau_code text PRIMARY KEY CHECK (length(bureau_code) BETWEEN 1 AND 64),
    bureau_name text NOT NULL CHECK (length(bureau_name) BETWEEN 1 AND 256),
    province_district_code text NOT NULL CHECK (length(province_district_code) BETWEEN 1 AND 32),
    authority_level text NOT NULL CHECK (authority_level IN ('PROVINCIAL_TAX_BUREAU', 'MUNICIPAL_TAX_BUREAU', 'DISTRICT_TAX_BUREAU', 'BRANCH_OFFICE')),
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS plugin_finance_org_entity_lifecycle (
    tenant_id uuid NOT NULL,
    legal_entity_id uuid NOT NULL,
    current_status text NOT NULL DEFAULT 'PREPARING' CHECK (current_status IN ('PREPARING', 'PENDING_CONFIRMATION', 'ACTIVE', 'SUSPENDED', 'TERMINATED')),
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, legal_entity_id)
);

CREATE TABLE IF NOT EXISTS plugin_finance_org_branch (
    tenant_id uuid NOT NULL,
    legal_entity_id uuid NOT NULL,
    id uuid NOT NULL,
    branch_code text NOT NULL CHECK (length(branch_code) BETWEEN 1 AND 64),
    branch_name text NOT NULL CHECK (length(branch_name) BETWEEN 1 AND 256),
    branch_type text NOT NULL CHECK (branch_type IN ('INTERNAL_NON_INDEPENDENT', 'INDEPENDENT_ACCOUNTING_BRANCH', 'CROSS_REGIONAL_BRANCH')),
    tax_filing_mode text NOT NULL CHECK (tax_filing_mode IN ('CONSOLIDATED_HEADQUARTERS', 'LOCAL_INDEPENDENT_FILING')),
    tax_bureau_code text NOT NULL,
    tax_bureau_name text NOT NULL,
    address text,
    status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, legal_entity_id, id),
    UNIQUE (tenant_id, legal_entity_id, branch_code),
    FOREIGN KEY (tenant_id, legal_entity_id) REFERENCES plugin_finance_org_entity_lifecycle(tenant_id, legal_entity_id) ON DELETE RESTRICT,
    FOREIGN KEY (tax_bureau_code) REFERENCES plugin_finance_org_tax_bureau(bureau_code)
);

CREATE TABLE IF NOT EXISTS plugin_finance_org_entity_lifecycle_event (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    legal_entity_id uuid NOT NULL,
    from_status text NOT NULL,
    to_status text NOT NULL,
    reason text NOT NULL CHECK (length(reason) BETWEEN 1 AND 1000),
    maker_account uuid NOT NULL,
    maker_person uuid NOT NULL,
    reviewer_account uuid NOT NULL,
    reviewer_person uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, legal_entity_id, id),
    FOREIGN KEY (tenant_id, legal_entity_id) REFERENCES plugin_finance_org_entity_lifecycle(tenant_id, legal_entity_id) ON DELETE RESTRICT,
    CHECK (maker_person <> reviewer_person)
);

-- -----------------------------------------------------------------------------
-- ORG02: Accounting Standards Eligibility, Policy Versions & Conversion Bridge
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS plugin_finance_org_standard_eligibility (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    legal_entity_id uuid NOT NULL,
    is_publicly_traded boolean NOT NULL DEFAULT false,
    is_financial_institution boolean NOT NULL DEFAULT false,
    is_group_subsidiary_of_public boolean NOT NULL DEFAULT false,
    employee_count integer NOT NULL CHECK (employee_count >= 0),
    annual_revenue numeric(28, 8) NOT NULL CHECK (annual_revenue >= 0),
    total_assets numeric(28, 8) NOT NULL CHECK (total_assets >= 0),
    eligible_for_small boolean NOT NULL,
    evaluated_standard text NOT NULL CHECK (evaluated_standard IN ('CAS', 'SMALL')),
    assessor_account uuid NOT NULL,
    assessor_person uuid NOT NULL,
    evaluation_notes text,
    assessed_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, legal_entity_id, id)
);

CREATE TABLE IF NOT EXISTS plugin_finance_org_policy_version (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    policy_version_no integer NOT NULL CHECK (policy_version_no >= 1),
    accounting_standard text NOT NULL CHECK (accounting_standard IN ('CAS', 'SMALL')),
    depreciation_method text NOT NULL CHECK (depreciation_method IN ('STRAIGHT_LINE', 'DOUBLE_DECLINING', 'SUM_OF_YEARS_DIGITS')),
    inventory_costing_method text NOT NULL CHECK (inventory_costing_method IN ('WEIGHTED_AVERAGE', 'FIFO', 'INDIVIDUAL_VALUATION')),
    impairment_allowed boolean NOT NULL,
    bad_debt_provision_allowed boolean NOT NULL,
    effective_from date NOT NULL,
    effective_to date,
    status text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'APPROVED', 'SUPERSEDED')),
    maker_account uuid NOT NULL,
    maker_person uuid NOT NULL,
    reviewer_account uuid,
    reviewer_person uuid,
    approval_digest text,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, book_id, id),
    UNIQUE (tenant_id, book_id, policy_version_no),
    CHECK ((effective_to IS NULL) OR (effective_from <= effective_to)),
    CHECK ((status = 'DRAFT' AND reviewer_person IS NULL) OR (status IN ('APPROVED', 'SUPERSEDED') AND reviewer_person IS NOT NULL AND maker_person <> reviewer_person)),
    CHECK ((accounting_standard = 'SMALL' AND impairment_allowed = false) OR (accounting_standard = 'CAS'))
);

CREATE TABLE IF NOT EXISTS plugin_finance_org_conversion_bridge (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    from_standard text NOT NULL CHECK (from_standard IN ('CAS', 'SMALL')),
    to_standard text NOT NULL CHECK (to_standard IN ('CAS', 'SMALL')),
    effective_fiscal_year integer NOT NULL CHECK (effective_fiscal_year BETWEEN 1900 AND 2200),
    status text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'APPROVED', 'POSTED', 'CANCELLED')),
    maker_account uuid NOT NULL,
    maker_person uuid NOT NULL,
    reviewer_account uuid,
    reviewer_person uuid,
    total_adjustment_debit numeric(28, 8) NOT NULL DEFAULT 0 CHECK (total_adjustment_debit >= 0),
    total_adjustment_credit numeric(28, 8) NOT NULL DEFAULT 0 CHECK (total_adjustment_credit >= 0),
    justification text NOT NULL CHECK (length(justification) BETWEEN 1 AND 1000),
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, book_id, id),
    CHECK (from_standard <> to_standard),
    CHECK ((status = 'DRAFT' AND reviewer_person IS NULL) OR (status = 'CANCELLED') OR (status IN ('APPROVED', 'POSTED') AND reviewer_person IS NOT NULL AND maker_person <> reviewer_person))
);

CREATE TABLE IF NOT EXISTS plugin_finance_org_conversion_bridge_item (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    bridge_id uuid NOT NULL,
    account_code text NOT NULL CHECK (length(account_code) BETWEEN 1 AND 64),
    account_name text NOT NULL CHECK (length(account_name) BETWEEN 1 AND 256),
    original_balance numeric(28, 8) NOT NULL,
    adjustment_debit numeric(28, 8) NOT NULL DEFAULT 0 CHECK (adjustment_debit >= 0),
    adjustment_credit numeric(28, 8) NOT NULL DEFAULT 0 CHECK (adjustment_credit >= 0),
    adjusted_balance numeric(28, 8) NOT NULL,
    adjustment_reason text NOT NULL CHECK (length(adjustment_reason) BETWEEN 1 AND 500),
    affected_opening_balance_type text NOT NULL,
    PRIMARY KEY (tenant_id, bridge_id, id),
    FOREIGN KEY (tenant_id, book_id, bridge_id) REFERENCES plugin_finance_org_conversion_bridge(tenant_id, book_id, id) ON DELETE CASCADE
);

-- -----------------------------------------------------------------------------
-- ORG03: Multi-Date Semantics & Period Reopen Protocol
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS plugin_finance_org_period_reopen (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    year_id uuid NOT NULL,
    period_id uuid NOT NULL,
    period_month integer NOT NULL CHECK (period_month BETWEEN 1 AND 12),
    reopen_reason text NOT NULL CHECK (length(reopen_reason) BETWEEN 1 AND 1000),
    status text NOT NULL DEFAULT 'REQUESTED' CHECK (status IN ('REQUESTED', 'APPROVED', 'REJECTED', 'CLOSED_AGAIN')),
    requested_by jsonb NOT NULL,
    approved_by jsonb,
    decision_reason text,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    requester_account uuid GENERATED ALWAYS AS ((requested_by->>'accountId')::uuid) STORED,
    requester_person uuid GENERATED ALWAYS AS ((requested_by->>'personId')::uuid) STORED,
    approver_account uuid GENERATED ALWAYS AS ((approved_by->>'accountId')::uuid) STORED,
    approver_person uuid GENERATED ALWAYS AS ((approved_by->>'personId')::uuid) STORED,
    PRIMARY KEY (tenant_id, book_id, id),
    CHECK ((status = 'REQUESTED' AND approved_by IS NULL) OR (status IN ('APPROVED', 'REJECTED', 'CLOSED_AGAIN') AND approved_by IS NOT NULL AND requester_person <> approver_person))
);

CREATE TABLE IF NOT EXISTS plugin_finance_org_period_audit_trail (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    year_id uuid NOT NULL,
    period_id uuid NOT NULL,
    action text NOT NULL CHECK (action IN ('CLOSE', 'REOPEN_REQUEST', 'REOPEN_APPROVE', 'REOPEN_REJECT', 'RECLOSE')),
    actor_account uuid NOT NULL,
    actor_person uuid NOT NULL,
    details jsonb NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, book_id, id)
);

-- -----------------------------------------------------------------------------
-- ORG04: Foreign Currency, FX Rate Registry, Revaluation & Tax Worksheet
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS plugin_finance_fx_currency (
    currency_code text PRIMARY KEY CHECK (currency_code ~ '^[A-Z]{3}$'),
    currency_name text NOT NULL CHECK (length(currency_name) BETWEEN 1 AND 64),
    symbol text NOT NULL CHECK (length(symbol) BETWEEN 1 AND 8),
    standard_scale integer NOT NULL CHECK (standard_scale BETWEEN 0 AND 4),
    is_base boolean NOT NULL DEFAULT false,
    is_active boolean NOT NULL DEFAULT true
);

CREATE TABLE IF NOT EXISTS plugin_finance_fx_rate_snapshot (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    source_currency text NOT NULL CHECK (source_currency ~ '^[A-Z]{3}$'),
    target_currency text NOT NULL DEFAULT 'CNY' CHECK (target_currency = 'CNY'),
    rate_date date NOT NULL,
    rate_type text NOT NULL CHECK (rate_type IN ('CENTRAL_PARITY', 'SPOT_BUY', 'SPOT_SELL', 'CUSTOM_APPROVED')),
    rate_value numeric(28, 12) NOT NULL CHECK (rate_value > 0),
    quote_source text NOT NULL CHECK (length(quote_source) BETWEEN 1 AND 64),
    maker_person uuid NOT NULL,
    reviewer_person uuid,
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, source_currency, target_currency, rate_date, rate_type),
    CHECK (reviewer_person IS NULL OR maker_person <> reviewer_person),
    CHECK ((rate_type <> 'CUSTOM_APPROVED') OR (reviewer_person IS NOT NULL AND maker_person <> reviewer_person))
);

CREATE TABLE IF NOT EXISTS plugin_finance_fx_revaluation_plan (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    year_id uuid NOT NULL,
    period_id uuid NOT NULL,
    revaluation_date date NOT NULL,
    closing_rate_snapshot_id uuid NOT NULL,
    status text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'APPROVED', 'POSTED', 'CANCELLED')),
    total_gain_loss numeric(28, 8) NOT NULL DEFAULT 0,
    maker_account uuid NOT NULL,
    maker_person uuid NOT NULL,
    reviewer_account uuid,
    reviewer_person uuid,
    journal_entry_id uuid,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, book_id, id),
    CHECK ((status = 'DRAFT' AND reviewer_person IS NULL) OR (status = 'CANCELLED') OR (status IN ('APPROVED', 'POSTED') AND reviewer_person IS NOT NULL AND maker_person <> reviewer_person))
);

CREATE TABLE IF NOT EXISTS plugin_finance_fx_revaluation_item (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    plan_id uuid NOT NULL,
    account_code text NOT NULL CHECK (length(account_code) BETWEEN 1 AND 64),
    account_name text NOT NULL CHECK (length(account_name) BETWEEN 1 AND 256),
    currency_code text NOT NULL CHECK (currency_code ~ '^[A-Z]{3}$'),
    foreign_balance numeric(28, 8) NOT NULL,
    carrying_cny_balance numeric(28, 8) NOT NULL,
    closing_rate numeric(28, 12) NOT NULL CHECK (closing_rate > 0),
    restated_cny_balance numeric(28, 8) NOT NULL,
    gain_loss_amount numeric(28, 8) NOT NULL,
    exchange_gain_loss_account text NOT NULL CHECK (length(exchange_gain_loss_account) BETWEEN 1 AND 64),
    PRIMARY KEY (tenant_id, plan_id, id),
    FOREIGN KEY (tenant_id, book_id, plan_id) REFERENCES plugin_finance_fx_revaluation_plan(tenant_id, book_id, id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS plugin_finance_fx_tax_worksheet (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    period_id uuid NOT NULL,
    transaction_ref text NOT NULL CHECK (length(transaction_ref) BETWEEN 1 AND 128),
    original_currency text NOT NULL CHECK (original_currency ~ '^[A-Z]{3}$'),
    original_amount numeric(28, 8) NOT NULL,
    accounting_fx_rate numeric(28, 12) NOT NULL CHECK (accounting_fx_rate > 0),
    accounting_cny_amount numeric(28, 8) NOT NULL,
    tax_fx_rate_type text NOT NULL CHECK (tax_fx_rate_type IN ('TRANSACTION_DAY_MID', 'MONTH_FIRST_DAY_MID')),
    tax_fx_rate numeric(28, 12) NOT NULL CHECK (tax_fx_rate > 0),
    tax_cny_amount numeric(28, 8) NOT NULL,
    tax_accounting_diff numeric(28, 8) NOT NULL,
    explanation text NOT NULL CHECK (length(explanation) BETWEEN 1 AND 1000),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, book_id, id)
);

-- -----------------------------------------------------------------------------
-- ORG05: Regional Tax Calendar Templates & Tax Obligation Catalog
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS plugin_finance_tax_calendar_template (
    id uuid NOT NULL,
    region_code text NOT NULL CHECK (length(region_code) BETWEEN 1 AND 64),
    tax_year integer NOT NULL CHECK (tax_year BETWEEN 1900 AND 2200),
    period_month integer NOT NULL CHECK (period_month BETWEEN 1 AND 12),
    tax_category text NOT NULL CHECK (tax_category IN ('VAT', 'CIT_PREPAY', 'CIT_ANNUAL', 'IIT', 'STAMP', 'SURCHARGE')),
    statutory_deadline date NOT NULL,
    extended_deadline date NOT NULL,
    extension_reason text,
    official_announcement_ref text,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (region_code, tax_year, period_month, tax_category),
    CHECK (statutory_deadline <= extended_deadline)
);

CREATE TABLE IF NOT EXISTS plugin_finance_tax_catalog_rule (
    obligation_code text PRIMARY KEY CHECK (length(obligation_code) BETWEEN 1 AND 64),
    tax_category text NOT NULL CHECK (tax_category IN ('VAT', 'CIT_PREPAY', 'CIT_ANNUAL', 'IIT', 'STAMP', 'SURCHARGE')),
    tax_type_name text NOT NULL CHECK (length(tax_type_name) BETWEEN 1 AND 128),
    filing_frequency text NOT NULL CHECK (filing_frequency IN ('MONTHLY', 'QUARTERLY', 'ANNUALLY', 'TRANSACTIONAL')),
    default_role text NOT NULL CHECK (default_role IN ('SELF', 'WITHHOLDING', 'COLLECTION_AGENT')),
    statutory_filing_unit text NOT NULL CHECK (length(statutory_filing_unit) BETWEEN 1 AND 64),
    applicable_taxpayer_type text NOT NULL CHECK (applicable_taxpayer_type IN ('GENERAL_TAXPAYER', 'SMALL_SCALE', 'ALL')),
    applicable_cit_mode text NOT NULL CHECK (applicable_cit_mode IN ('AUDIT_COLLECTION', 'VERIFIED_COLLECTION', 'NOT_APPLICABLE')),
    is_active boolean NOT NULL DEFAULT true
);

-- -----------------------------------------------------------------------------
-- Default Seed Data for Base Currencies & Standard Tax Catalog Rules
-- -----------------------------------------------------------------------------

INSERT INTO plugin_finance_fx_currency (currency_code, currency_name, symbol, standard_scale, is_base, is_active)
VALUES
    ('CNY', 'Chinese Yuan', '¥', 2, true, true),
    ('USD', 'US Dollar', '$', 2, false, true),
    ('EUR', 'Euro', '€', 2, false, true),
    ('HKD', 'Hong Kong Dollar', 'HK$', 2, false, true),
    ('JPY', 'Japanese Yen', '¥', 0, false, true),
    ('GBP', 'British Pound', '£', 2, false, true)
ON CONFLICT (currency_code) DO NOTHING;

INSERT INTO plugin_finance_tax_catalog_rule (
    obligation_code, tax_category, tax_type_name, filing_frequency, default_role,
    statutory_filing_unit, applicable_taxpayer_type, applicable_cit_mode, is_active
) VALUES
    ('VAT_GENERAL_MONTHLY', 'VAT', '增值税一般纳税人申报', 'MONTHLY', 'SELF', 'VAT_GENERAL', 'GENERAL_TAXPAYER', 'NOT_APPLICABLE', true),
    ('VAT_SMALL_QUARTERLY', 'VAT', '增值税小规模纳税人季度申报', 'QUARTERLY', 'SELF', 'VAT_SMALL', 'SMALL_SCALE', 'NOT_APPLICABLE', true),
    ('CIT_PREPAY_QUARTERLY', 'CIT_PREPAY', '企业所得税月（季）度预缴纳税申报', 'QUARTERLY', 'SELF', 'CIT_A200000', 'ALL', 'AUDIT_COLLECTION', true),
    ('CIT_ANNUAL_SETTLEMENT', 'CIT_ANNUAL', '企业所得税年度汇算清缴', 'ANNUALLY', 'SELF', 'CIT_A100000', 'ALL', 'AUDIT_COLLECTION', true),
    ('IIT_SALARY_MONTHLY', 'IIT', '个人所得税扣缴申报（综合所得）', 'MONTHLY', 'WITHHOLDING', 'IIT_WITHHOLDING', 'ALL', 'NOT_APPLICABLE', true),
    ('NATURAL_VAT_WITHHOLD', 'VAT', '自然人增值税代扣代缴', 'TRANSACTIONAL', 'WITHHOLDING', 'VAT_WITHHOLD', 'ALL', 'NOT_APPLICABLE', true)
ON CONFLICT (obligation_code) DO NOTHING;

INSERT INTO plugin_finance_org_tax_bureau (bureau_code, bureau_name, province_district_code, authority_level, is_active)
VALUES
    ('TAX-SH-001', '国家税务总局上海市浦东新区税务局', '310115', 'DISTRICT_TAX_BUREAU', true),
    ('TAX-BJ-001', '国家税务总局北京市海淀区税务局', '110108', 'DISTRICT_TAX_BUREAU', true),
    ('TAX-SZ-001', '国家税务总局深圳市南山区税务局', '440305', 'DISTRICT_TAX_BUREAU', true),
    ('TAX-GZ-001', '国家税务总局广州市天河区税务局', '440106', 'DISTRICT_TAX_BUREAU', true),
    ('TAX-HZ-001', '国家税务总局杭州市滨江区税务局', '330108', 'DISTRICT_TAX_BUREAU', true)
ON CONFLICT (bureau_code) DO NOTHING;
