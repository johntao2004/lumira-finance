-- Lane W09: GL & Year-End Accounting Lifecycle Candidate Migration
-- Candidate only; root assigns formal migration sequence number.

-- 1. GL01: Cutover & Migration Plan
CREATE TABLE IF NOT EXISTS plugin_finance_cutover_plan (
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    year_id uuid NOT NULL,
    id uuid NOT NULL,
    cutover_date date NOT NULL,
    mode text NOT NULL CHECK (mode IN ('FULL_HISTORY', 'OPENING_PLUS_YTD')),
    status text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'VALIDATED', 'APPROVED', 'LOCKED', 'CANCELLED')),
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    content_digest text NOT NULL CHECK (length(content_digest) = 64),
    source_system text NOT NULL CHECK (length(source_system) <= 100),
    source_watermark text,
    maker jsonb NOT NULL,
    approver jsonb,
    locked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, book_id, id),
    FOREIGN KEY (tenant_id, book_id) REFERENCES plugin_finance_book (tenant_id, id),
    FOREIGN KEY (tenant_id, book_id, year_id) REFERENCES plugin_finance_year (tenant_id, book_id, id),
    CHECK (status NOT IN ('APPROVED', 'LOCKED') OR approver IS NOT NULL)
);

CREATE UNIQUE INDEX IF NOT EXISTS plugin_finance_cutover_plan_one_active
    ON plugin_finance_cutover_plan (tenant_id, book_id, year_id)
    WHERE status IN ('DRAFT', 'VALIDATED', 'APPROVED', 'LOCKED');

CREATE TABLE IF NOT EXISTS plugin_finance_cutover_opening_balance (
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    cutover_plan_id uuid NOT NULL,
    id uuid NOT NULL,
    account_id uuid NOT NULL,
    account_code text NOT NULL,
    account_name text NOT NULL,
    currency text NOT NULL DEFAULT 'CNY',
    direction text NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount numeric(28,8) NOT NULL CHECK (amount >= 0),
    aux_dimensions jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, book_id, cutover_plan_id, id),
    FOREIGN KEY (tenant_id, book_id, cutover_plan_id) REFERENCES plugin_finance_cutover_plan (tenant_id, book_id, id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS plugin_finance_cutover_baseline_item (
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    cutover_plan_id uuid NOT NULL,
    id uuid NOT NULL,
    baseline_type text NOT NULL CHECK (baseline_type IN ('PROFIT_LOSS', 'CASHFLOW', 'TAX', 'PAYROLL')),
    period_month integer NOT NULL CHECK (period_month BETWEEN 1 AND 12),
    category_code text NOT NULL,
    category_name text NOT NULL,
    amount numeric(28,8) NOT NULL,
    aux_payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, book_id, cutover_plan_id, id),
    FOREIGN KEY (tenant_id, book_id, cutover_plan_id) REFERENCES plugin_finance_cutover_plan (tenant_id, book_id, id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS plugin_finance_cutover_tie_out (
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    cutover_plan_id uuid NOT NULL,
    id uuid NOT NULL,
    check_item text NOT NULL CHECK (check_item IN ('TRIAL_BALANCE', 'AR_SUBLEDGER', 'AP_SUBLEDGER', 'ASSET_SUBLEDGER', 'CASHFLOW_TIE_OUT', 'PAYROLL_TIE_OUT')),
    passed boolean NOT NULL,
    gl_amount numeric(28,8) NOT NULL,
    subledger_amount numeric(28,8) NOT NULL,
    difference numeric(28,8) NOT NULL,
    details jsonb NOT NULL DEFAULT '{}'::jsonb,
    evaluated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, book_id, cutover_plan_id, id),
    FOREIGN KEY (tenant_id, book_id, cutover_plan_id) REFERENCES plugin_finance_cutover_plan (tenant_id, book_id, id) ON DELETE CASCADE
);

-- 2. GL02 & GL03: Accounting Rule Templates & Auto-Review Policies
CREATE TABLE IF NOT EXISTS plugin_finance_auto_rule_template (
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    id uuid NOT NULL,
    rule_code text NOT NULL,
    rule_name text NOT NULL,
    business_type text NOT NULL CHECK (business_type IN ('AR_SALES', 'AP_PURCHASE', 'ASSET_DEPRECIATION', 'PAYROLL_EXPENSE', 'CASH_RECEIPT')),
    version integer NOT NULL DEFAULT 1 CHECK (version > 0),
    status text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'APPROVED', 'DEPRECATED')),
    content_digest text NOT NULL CHECK (length(content_digest) = 64),
    line_templates jsonb NOT NULL,
    maker jsonb NOT NULL,
    approver jsonb,
    approved_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, book_id, id),
    UNIQUE (tenant_id, book_id, rule_code, version),
    FOREIGN KEY (tenant_id, book_id) REFERENCES plugin_finance_book (tenant_id, id),
    CHECK (status <> 'APPROVED' OR approver IS NOT NULL)
);

CREATE TABLE IF NOT EXISTS plugin_finance_auto_rule_policy (
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    id uuid NOT NULL,
    policy_code text NOT NULL,
    policy_name text NOT NULL,
    version integer NOT NULL DEFAULT 1 CHECK (version > 0),
    status text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'APPROVED', 'DEPRECATED')),
    content_digest text NOT NULL CHECK (length(content_digest) = 64),
    scope_business_types jsonb NOT NULL,
    allowed_account_codes jsonb NOT NULL,
    max_amount numeric(28,8) NOT NULL CHECK (max_amount > 0),
    zero_exception_required boolean NOT NULL DEFAULT true,
    maker jsonb NOT NULL,
    approver jsonb,
    approved_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, book_id, id),
    UNIQUE (tenant_id, book_id, policy_code, version),
    FOREIGN KEY (tenant_id, book_id) REFERENCES plugin_finance_book (tenant_id, id),
    CHECK (status <> 'APPROVED' OR approver IS NOT NULL)
);

CREATE TABLE IF NOT EXISTS plugin_finance_auto_rule_execution (
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    year_id uuid NOT NULL,
    id uuid NOT NULL,
    journal_id uuid NOT NULL,
    source_event_id text NOT NULL,
    source_system text NOT NULL,
    stable_effect_key text NOT NULL CHECK (length(stable_effect_key) = 64),
    template_id uuid NOT NULL,
    template_version integer NOT NULL,
    policy_id uuid,
    policy_version integer,
    policy_digest text,
    auto_reviewed boolean NOT NULL DEFAULT false,
    exception_count integer NOT NULL DEFAULT 0,
    audit_evidence jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, book_id, id),
    UNIQUE (tenant_id, book_id, stable_effect_key),
    FOREIGN KEY (tenant_id, book_id) REFERENCES plugin_finance_book (tenant_id, id),
    FOREIGN KEY (tenant_id, book_id, year_id) REFERENCES plugin_finance_year (tenant_id, book_id, id),
    FOREIGN KEY (tenant_id, book_id, template_id) REFERENCES plugin_finance_auto_rule_template (tenant_id, book_id, id)
);

-- 3. GL06: Period Close Checklist & Gate
CREATE TABLE IF NOT EXISTS plugin_finance_period_close_check_plan (
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    year_id uuid NOT NULL,
    period_id uuid NOT NULL,
    id uuid NOT NULL,
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    status text NOT NULL DEFAULT 'PREVIEWED' CHECK (status IN ('PREVIEWED', 'APPROVED', 'CLOSED', 'CANCELLED', 'REOPENED')),
    content_digest text NOT NULL CHECK (length(content_digest) = 64),
    source_watermark bigint NOT NULL DEFAULT 0,
    all_checks_passed boolean NOT NULL DEFAULT false,
    unposted_journals_count integer NOT NULL DEFAULT 0,
    trial_balance_debit numeric(28,8) NOT NULL DEFAULT 0,
    trial_balance_credit numeric(28,8) NOT NULL DEFAULT 0,
    trial_balance_balanced boolean NOT NULL DEFAULT false,
    bank_reconciliation_complete boolean NOT NULL DEFAULT false,
    ar_tie_out_passed boolean NOT NULL DEFAULT false,
    ap_tie_out_passed boolean NOT NULL DEFAULT false,
    asset_tie_out_passed boolean NOT NULL DEFAULT false,
    inventory_tie_out_passed boolean NOT NULL DEFAULT false,
    payroll_tie_out_passed boolean NOT NULL DEFAULT false,
    cashflow_coverage_complete boolean NOT NULL DEFAULT false,
    tax_worksheet_ready boolean NOT NULL DEFAULT false,
    maker jsonb NOT NULL,
    approver jsonb,
    approved_at timestamptz,
    closed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, book_id, id),
    FOREIGN KEY (tenant_id, book_id) REFERENCES plugin_finance_book (tenant_id, id),
    FOREIGN KEY (tenant_id, book_id, year_id) REFERENCES plugin_finance_year (tenant_id, book_id, id),
    CHECK (status NOT IN ('APPROVED', 'CLOSED') OR approver IS NOT NULL)
);

CREATE UNIQUE INDEX IF NOT EXISTS plugin_finance_period_close_check_one_active
    ON plugin_finance_period_close_check_plan (tenant_id, book_id, year_id, period_id)
    WHERE status IN ('PREVIEWED', 'APPROVED', 'CLOSED');

CREATE TABLE IF NOT EXISTS plugin_finance_period_close_check_item (
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    plan_id uuid NOT NULL,
    id uuid NOT NULL,
    check_category text NOT NULL CHECK (check_category IN ('UNPOSTED_JOURNALS', 'TRIAL_BALANCE', 'BANK_RECONCILIATION', 'AR_SUBLEDGER', 'AP_SUBLEDGER', 'ASSET_SUBLEDGER', 'INVENTORY_SUBLEDGER', 'PAYROLL_SUBLEDGER', 'CASHFLOW_COVERAGE', 'TAX_WORKSHEET')),
    check_code text NOT NULL,
    check_title text NOT NULL,
    passed boolean NOT NULL,
    details jsonb NOT NULL DEFAULT '{}'::jsonb,
    evaluated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, book_id, plan_id, id),
    FOREIGN KEY (tenant_id, book_id, plan_id) REFERENCES plugin_finance_period_close_check_plan (tenant_id, book_id, id) ON DELETE CASCADE
);

-- 4. YEAR01, YEAR02, YEAR03: Carry-Over Batch & Restatement Chain
CREATE TABLE IF NOT EXISTS plugin_finance_carry_batch_plan (
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    source_year_id uuid NOT NULL,
    target_year_id uuid NOT NULL,
    id uuid NOT NULL,
    kind text NOT NULL CHECK (kind IN ('BASE', 'DELTA')),
    status text NOT NULL DEFAULT 'PREVIEWED' CHECK (status IN ('PREVIEWED', 'APPROVED', 'COMMITTED', 'CANCELLED')),
    source_close_version integer NOT NULL CHECK (source_close_version >= 1),
    source_watermark bigint NOT NULL CHECK (source_watermark >= 0),
    target_opening_version integer NOT NULL CHECK (target_opening_version >= 0),
    mapping_version text NOT NULL CHECK (length(mapping_version) <= 80),
    content_version bigint NOT NULL DEFAULT 1 CHECK (content_version > 0),
    content_digest text NOT NULL CHECK (length(content_digest) = 64),
    previous_batch_id uuid,
    reason text NOT NULL CHECK (length(reason) <= 1000),
    maker jsonb NOT NULL,
    approver jsonb,
    committed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, book_id, id),
    UNIQUE (tenant_id, book_id, source_year_id, source_close_version, target_year_id),
    FOREIGN KEY (tenant_id, book_id) REFERENCES plugin_finance_book (tenant_id, id),
    FOREIGN KEY (tenant_id, book_id, source_year_id) REFERENCES plugin_finance_year (tenant_id, book_id, id),
    FOREIGN KEY (tenant_id, book_id, target_year_id) REFERENCES plugin_finance_year (tenant_id, book_id, id),
    FOREIGN KEY (tenant_id, book_id, previous_batch_id) REFERENCES plugin_finance_carry_batch_plan (tenant_id, book_id, id),
    CHECK (status <> 'COMMITTED' OR approver IS NOT NULL),
    CHECK (kind <> 'DELTA' OR previous_batch_id IS NOT NULL)
);

CREATE TABLE IF NOT EXISTS plugin_finance_carry_batch_balance (
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    batch_id uuid NOT NULL,
    id uuid NOT NULL,
    source_account_id uuid NOT NULL,
    target_account_id uuid NOT NULL,
    source_account_code text NOT NULL,
    target_account_code text NOT NULL,
    currency text NOT NULL DEFAULT 'CNY',
    direction text NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    base_amount numeric(28,8) NOT NULL CHECK (base_amount >= 0),
    delta_amount numeric(28,8) NOT NULL DEFAULT 0,
    net_opening_amount numeric(28,8) NOT NULL CHECK (net_opening_amount >= 0),
    aux_dimensions jsonb NOT NULL DEFAULT '{}'::jsonb,
    PRIMARY KEY (tenant_id, book_id, batch_id, id),
    FOREIGN KEY (tenant_id, book_id, batch_id) REFERENCES plugin_finance_carry_batch_plan (tenant_id, book_id, id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS plugin_finance_carry_batch_subledger (
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    batch_id uuid NOT NULL,
    id uuid NOT NULL,
    subledger_kind text NOT NULL CHECK (subledger_kind IN ('AR_OPEN_INVOICE', 'AP_OPEN_BILL', 'EMPLOYEE_ADVANCE', 'FX_BALANCE')),
    business_ref text NOT NULL,
    counterparty_id uuid,
    original_date date NOT NULL,
    currency text NOT NULL DEFAULT 'CNY',
    original_amount numeric(28,8) NOT NULL CHECK (original_amount >= 0),
    remaining_open_amount numeric(28,8) NOT NULL CHECK (remaining_open_amount >= 0),
    fx_rate numeric(28,12) DEFAULT 1.0,
    cny_equivalent numeric(28,8) NOT NULL CHECK (cny_equivalent >= 0),
    aux_payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    PRIMARY KEY (tenant_id, book_id, batch_id, id),
    FOREIGN KEY (tenant_id, book_id, batch_id) REFERENCES plugin_finance_carry_batch_plan (tenant_id, book_id, id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS plugin_finance_carry_batch_restatement (
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    batch_id uuid NOT NULL,
    id uuid NOT NULL,
    restatement_scope text NOT NULL CHECK (restatement_scope IN ('BALANCE_SHEET_COMPARATIVE', 'INCOME_STATEMENT_COMPARATIVE', 'CIT_ANNUAL_ADJUSTMENT', 'TAX_RECONCILIATION')),
    statement_line_code text NOT NULL,
    statement_line_name text NOT NULL,
    pre_correction_amount numeric(28,8) NOT NULL,
    delta_amount numeric(28,8) NOT NULL,
    restated_amount numeric(28,8) NOT NULL,
    impact_summary text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, book_id, batch_id, id),
    FOREIGN KEY (tenant_id, book_id, batch_id) REFERENCES plugin_finance_carry_batch_plan (tenant_id, book_id, id) ON DELETE CASCADE
);

-- 5. GL05: General Journal Reversal & Correction Metadata
CREATE TABLE IF NOT EXISTS plugin_finance_journal_correction (
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    target_year_id uuid NOT NULL,
    id uuid NOT NULL,
    source_year_id uuid NOT NULL,
    source_journal_id uuid NOT NULL,
    source_posting_seq bigint NOT NULL,
    source_digest text NOT NULL CHECK (length(source_digest) = 64),
    correction_journal_id uuid NOT NULL,
    correction_kind text NOT NULL CHECK (correction_kind IN ('FULL_REVERSAL', 'PARTIAL_REVERSAL', 'SUPPLEMENT', 'PRIOR_PERIOD_ADJUSTMENT')),
    standard text NOT NULL CHECK (standard IN ('CAS', 'SMALL')),
    cas_account_code text,
    status text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'POSTED', 'CANCELLED')),
    reason text NOT NULL CHECK (length(reason) BETWEEN 1 AND 1000),
    allocations jsonb NOT NULL DEFAULT '[]'::jsonb,
    maker jsonb NOT NULL,
    approver jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, book_id, id),
    FOREIGN KEY (tenant_id, book_id) REFERENCES plugin_finance_book (tenant_id, id),
    FOREIGN KEY (tenant_id, book_id, target_year_id) REFERENCES plugin_finance_year (tenant_id, book_id, id),
    FOREIGN KEY (tenant_id, book_id, source_year_id) REFERENCES plugin_finance_year (tenant_id, book_id, id),
    CHECK (standard <> 'SMALL' OR cas_account_code IS NULL OR cas_account_code <> '6901'),
    CHECK (status NOT IN ('APPROVED', 'POSTED') OR approver IS NOT NULL)
);

-- Immutability & Guard Triggers
CREATE OR REPLACE FUNCTION plugin_finance_w09_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'W09_IMMUTABLE_RECORD';
END $$;

DROP TRIGGER IF EXISTS trg_w09_execution_immutable ON plugin_finance_auto_rule_execution;
CREATE TRIGGER trg_w09_execution_immutable
    BEFORE UPDATE OR DELETE ON plugin_finance_auto_rule_execution
    FOR EACH ROW EXECUTE FUNCTION plugin_finance_w09_immutable();

DROP TRIGGER IF EXISTS trg_w09_restatement_immutable ON plugin_finance_carry_batch_restatement;
CREATE TRIGGER trg_w09_restatement_immutable
    BEFORE UPDATE OR DELETE ON plugin_finance_carry_batch_restatement
    FOR EACH ROW EXECUTE FUNCTION plugin_finance_w09_immutable();

CREATE OR REPLACE FUNCTION plugin_finance_cutover_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status IN ('LOCKED', 'APPROVED') THEN
            RAISE EXCEPTION 'CUTOVER_PLAN_LOCKED_CANNOT_DELETE';
        END IF;
    ELSIF TG_OP = 'UPDATE' THEN
        IF OLD.status = 'LOCKED' THEN
            RAISE EXCEPTION 'CUTOVER_PLAN_LOCKED_IMMUTABLE';
        END IF;
        IF OLD.status = 'APPROVED' AND NEW.status NOT IN ('LOCKED', 'CANCELLED') THEN
            RAISE EXCEPTION 'CUTOVER_PLAN_APPROVED_TRANSITION_INVALID';
        END IF;
    END IF;
    RETURN NEW;
END $$;

DROP TRIGGER IF EXISTS trg_cutover_guard ON plugin_finance_cutover_plan;
CREATE TRIGGER trg_cutover_guard
    BEFORE UPDATE OR DELETE ON plugin_finance_cutover_plan
    FOR EACH ROW EXECUTE FUNCTION plugin_finance_cutover_guard();

CREATE OR REPLACE FUNCTION plugin_finance_carry_batch_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status = 'COMMITTED' THEN
            RAISE EXCEPTION 'CARRY_BATCH_COMMITTED_IMMUTABLE';
        END IF;
    ELSIF TG_OP = 'UPDATE' THEN
        IF OLD.status = 'COMMITTED' THEN
            RAISE EXCEPTION 'CARRY_BATCH_COMMITTED_IMMUTABLE';
        END IF;
        IF OLD.status = 'APPROVED' AND NEW.status NOT IN ('COMMITTED', 'CANCELLED') THEN
            RAISE EXCEPTION 'CARRY_BATCH_APPROVED_TRANSITION_INVALID';
        END IF;
    END IF;
    RETURN NEW;
END $$;

DROP TRIGGER IF EXISTS trg_carry_batch_guard ON plugin_finance_carry_batch_plan;
CREATE TRIGGER trg_carry_batch_guard
    BEFORE UPDATE OR DELETE ON plugin_finance_carry_batch_plan
    FOR EACH ROW EXECUTE FUNCTION plugin_finance_carry_batch_guard();
