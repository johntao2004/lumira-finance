-- =============================================================================
-- Migration Candidate: Dimensions, Master Data & Comprehensive Budget Control
-- Covers MD01, MD02, MD03, BD01, BD02
-- Strict PostgreSQL 18 DDL: numeric(28,8) amounts, numeric(18,12) ratios, SoD guards
-- =============================================================================

-- -----------------------------------------------------------------------------
-- MD01: Account Auxiliary Dimension Bindings & Deactivation Approval
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS plugin_finance_dim_account_rule (
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    year_id uuid NOT NULL,
    account_id uuid NOT NULL,
    account_code text NOT NULL,
    standard_version text NOT NULL DEFAULT 'CAS_2006' CHECK(standard_version IN ('CAS_2006', 'SMALL_2013')),
    require_department boolean NOT NULL DEFAULT false,
    require_project boolean NOT NULL DEFAULT false,
    require_counterparty boolean NOT NULL DEFAULT false,
    require_employee boolean NOT NULL DEFAULT false,
    require_contract boolean NOT NULL DEFAULT false,
    require_cost_center boolean NOT NULL DEFAULT false,
    currency_policy text NOT NULL DEFAULT 'CNY_ONLY' CHECK(currency_policy IN ('CNY_ONLY', 'MULTI_CURRENCY')),
    status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE', 'DEACTIVATION_PENDING', 'INACTIVE')),
    version bigint NOT NULL DEFAULT 1 CHECK(version > 0),
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, book_id, year_id, account_id)
);

CREATE TABLE IF NOT EXISTS plugin_finance_dim_account_approval (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    year_id uuid NOT NULL,
    account_id uuid NOT NULL,
    proposal_type text NOT NULL CHECK(proposal_type IN ('DEACTIVATE', 'CHANGE_POLICY')),
    proposed_status text NOT NULL CHECK(proposed_status IN ('INACTIVE', 'ACTIVE')),
    maker_person uuid NOT NULL,
    maker_account uuid NOT NULL,
    reviewer_person uuid,
    reviewer_account uuid,
    status text NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING', 'APPROVED', 'REJECTED')),
    decision_reason text,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decided_at timestamptz,
    CHECK((status = 'PENDING') OR (reviewer_person IS NOT NULL AND reviewer_person <> maker_person AND decided_at IS NOT NULL))
);

-- -----------------------------------------------------------------------------
-- MD02: Counterparty Bank Accounts, Bank Reviews & Identity History
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS plugin_finance_counterparty_bank_account (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    legal_entity_id uuid NOT NULL,
    counterparty_id uuid NOT NULL,
    bank_name text NOT NULL CHECK(length(bank_name) BETWEEN 1 AND 200),
    branch_name text CHECK(branch_name IS NULL OR length(branch_name) BETWEEN 1 AND 200),
    bank_account_no text NOT NULL CHECK(length(bank_account_no) BETWEEN 6 AND 64 AND bank_account_no ~ '^[A-Z0-9 -]+$'),
    currency text NOT NULL DEFAULT 'CNY' CHECK(currency = 'CNY'),
    purpose text NOT NULL CHECK(purpose IN ('SETTLEMENT', 'TAX', 'PAYROLL', 'OTHER')),
    is_default boolean NOT NULL DEFAULT false,
    status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('PENDING_REVIEW', 'ACTIVE', 'INACTIVE')),
    version bigint NOT NULL DEFAULT 1 CHECK(version > 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(tenant_id, legal_entity_id, counterparty_id, bank_account_no)
);

CREATE TABLE IF NOT EXISTS plugin_finance_counterparty_bank_review (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    legal_entity_id uuid NOT NULL,
    counterparty_id uuid NOT NULL,
    bank_account_id uuid NOT NULL REFERENCES plugin_finance_counterparty_bank_account(id) ON DELETE CASCADE,
    change_type text NOT NULL CHECK(change_type IN ('CREATE', 'UPDATE', 'DEACTIVATE')),
    proposed_payload jsonb NOT NULL,
    maker_person uuid NOT NULL,
    reviewer_person uuid,
    status text NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING', 'APPROVED', 'REJECTED')),
    review_comment text,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decided_at timestamptz,
    CHECK((status = 'PENDING') OR (reviewer_person IS NOT NULL AND reviewer_person <> maker_person AND decided_at IS NOT NULL))
);

CREATE TABLE IF NOT EXISTS plugin_finance_counterparty_identity_history (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    legal_entity_id uuid NOT NULL,
    counterparty_id uuid NOT NULL,
    change_seq bigint NOT NULL CHECK(change_seq > 0),
    old_display_name text NOT NULL,
    new_display_name text NOT NULL,
    old_identifier text NOT NULL,
    new_identifier text NOT NULL,
    reason text NOT NULL CHECK(length(reason) BETWEEN 1 AND 1000),
    evidence_original_id uuid,
    evidence_hash text CHECK(evidence_hash IS NULL OR evidence_hash ~ '^[a-f0-9]{64}$'),
    maker_person uuid NOT NULL,
    approver_person uuid NOT NULL,
    approved_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK(maker_person <> approver_person),
    UNIQUE(tenant_id, legal_entity_id, counterparty_id, change_seq)
);

CREATE TABLE IF NOT EXISTS plugin_finance_dim_framework_contract (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    legal_entity_id uuid NOT NULL,
    counterparty_id uuid NOT NULL,
    contract_no text NOT NULL,
    contract_name text NOT NULL,
    settlement_terms text NOT NULL CHECK(settlement_terms IN ('PREPAYMENT', 'NET30', 'NET60', 'MILESTONE_ACCEPTANCE', 'INSTALLMENT')),
    credit_days integer NOT NULL DEFAULT 0 CHECK(credit_days >= 0),
    penalty_rate_annual numeric(18,12) NOT NULL DEFAULT 0 CHECK(penalty_rate_annual >= 0),
    valid_from date NOT NULL,
    valid_to date,
    status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE', 'EXPIRED', 'TERMINATED')),
    version bigint NOT NULL DEFAULT 1 CHECK(version > 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK(valid_to IS NULL OR valid_to >= valid_from),
    UNIQUE(tenant_id, legal_entity_id, counterparty_id, contract_no)
);

-- -----------------------------------------------------------------------------
-- MD03: Departments, Projects, Contracts, Cost Centers & Cost Allocation
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS plugin_finance_dim_department (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    code text NOT NULL,
    name text NOT NULL,
    parent_id uuid REFERENCES plugin_finance_dim_department(id),
    manager_person_id uuid,
    valid_from date NOT NULL,
    valid_to date,
    status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE', 'INACTIVE')),
    version bigint NOT NULL DEFAULT 1 CHECK(version > 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK(valid_to IS NULL OR valid_to >= valid_from),
    UNIQUE(tenant_id, book_id, code)
);

CREATE TABLE IF NOT EXISTS plugin_finance_dim_project (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    code text NOT NULL,
    name text NOT NULL,
    department_id uuid REFERENCES plugin_finance_dim_department(id),
    manager_person_id uuid,
    valid_from date NOT NULL,
    valid_to date,
    status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE', 'COMPLETED', 'SUSPENDED', 'INACTIVE')),
    version bigint NOT NULL DEFAULT 1 CHECK(version > 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK(valid_to IS NULL OR valid_to >= valid_from),
    UNIQUE(tenant_id, book_id, code)
);

CREATE TABLE IF NOT EXISTS plugin_finance_dim_contract (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    code text NOT NULL,
    name text NOT NULL,
    counterparty_id uuid NOT NULL,
    department_id uuid REFERENCES plugin_finance_dim_department(id),
    project_id uuid REFERENCES plugin_finance_dim_project(id),
    total_amount numeric(28,8) NOT NULL CHECK(total_amount >= 0),
    valid_from date NOT NULL,
    valid_to date,
    status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE', 'COMPLETED', 'TERMINATED')),
    version bigint NOT NULL DEFAULT 1 CHECK(version > 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK(valid_to IS NULL OR valid_to >= valid_from),
    UNIQUE(tenant_id, book_id, code)
);

CREATE TABLE IF NOT EXISTS plugin_finance_dim_cost_center (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    code text NOT NULL,
    name text NOT NULL,
    department_id uuid REFERENCES plugin_finance_dim_department(id),
    valid_from date NOT NULL,
    valid_to date,
    status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE', 'INACTIVE')),
    version bigint NOT NULL DEFAULT 1 CHECK(version > 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK(valid_to IS NULL OR valid_to >= valid_from),
    UNIQUE(tenant_id, book_id, code)
);

CREATE TABLE IF NOT EXISTS plugin_finance_dim_history (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    dimension_type text NOT NULL CHECK(dimension_type IN ('DEPARTMENT', 'PROJECT', 'CONTRACT', 'COST_CENTER')),
    dimension_id uuid NOT NULL,
    revision_no bigint NOT NULL CHECK(revision_no > 0),
    change_type text NOT NULL CHECK(change_type IN ('CREATE', 'UPDATE', 'STATUS_CHANGE')),
    payload jsonb NOT NULL,
    valid_from date NOT NULL,
    valid_to date,
    maker_person uuid NOT NULL,
    approved_by uuid,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK(valid_to IS NULL OR valid_to >= valid_from),
    UNIQUE(tenant_id, book_id, dimension_type, dimension_id, revision_no)
);

CREATE TABLE IF NOT EXISTS plugin_finance_dim_allocation_rule (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    rule_code text NOT NULL,
    rule_name text NOT NULL,
    source_cost_center_id uuid REFERENCES plugin_finance_dim_cost_center(id),
    source_department_id uuid REFERENCES plugin_finance_dim_department(id),
    source_account_code text,
    valid_from date NOT NULL,
    valid_to date,
    status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE', 'INACTIVE')),
    version bigint NOT NULL DEFAULT 1 CHECK(version > 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK(valid_to IS NULL OR valid_to >= valid_from),
    UNIQUE(tenant_id, book_id, rule_code)
);

CREATE TABLE IF NOT EXISTS plugin_finance_dim_allocation_target (
    id uuid PRIMARY KEY,
    rule_id uuid NOT NULL REFERENCES plugin_finance_dim_allocation_rule(id) ON DELETE CASCADE,
    target_cost_center_id uuid REFERENCES plugin_finance_dim_cost_center(id),
    target_department_id uuid REFERENCES plugin_finance_dim_department(id),
    target_project_id uuid REFERENCES plugin_finance_dim_project(id),
    ratio numeric(18,12) NOT NULL CHECK(ratio > 0 AND ratio <= 1),
    priority_order integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- -----------------------------------------------------------------------------
-- BD01: Comprehensive Budget Formulation, Items & Adjustment
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS plugin_finance_budget_version (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    legal_entity_id uuid NOT NULL,
    book_id uuid NOT NULL,
    year_id uuid NOT NULL,
    version_code text NOT NULL,
    version_type text NOT NULL CHECK(version_type IN ('ORIGINAL', 'ADJUSTMENT', 'ROLLING')),
    status text NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED', 'SUPERSEDED')),
    total_budget numeric(28,8) NOT NULL DEFAULT 0 CHECK(total_budget >= 0),
    maker_person uuid NOT NULL,
    reviewer_person uuid,
    approved_at timestamptz,
    reason text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(tenant_id, book_id, year_id, version_code),
    CHECK((status <> 'APPROVED') OR (reviewer_person IS NOT NULL AND reviewer_person <> maker_person AND approved_at IS NOT NULL))
);

CREATE TABLE IF NOT EXISTS plugin_finance_budget_item (
    id uuid PRIMARY KEY,
    budget_version_id uuid NOT NULL REFERENCES plugin_finance_budget_version(id) ON DELETE CASCADE,
    period integer NOT NULL CHECK(period BETWEEN 0 AND 12),
    account_code text NOT NULL,
    department_id uuid REFERENCES plugin_finance_dim_department(id),
    project_id uuid REFERENCES plugin_finance_dim_project(id),
    cost_center_id uuid REFERENCES plugin_finance_dim_cost_center(id),
    counterparty_id uuid,
    contract_id uuid REFERENCES plugin_finance_dim_contract(id),
    amount numeric(28,8) NOT NULL CHECK(amount >= 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS plugin_finance_budget_adjustment (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    year_id uuid NOT NULL,
    source_version_id uuid NOT NULL REFERENCES plugin_finance_budget_version(id),
    target_version_id uuid NOT NULL REFERENCES plugin_finance_budget_version(id),
    adjustment_type text NOT NULL CHECK(adjustment_type IN ('INTERNAL_TRANSFER', 'AUGMENTATION', 'REDUCTION')),
    adjustment_amount numeric(28,8) NOT NULL CHECK(adjustment_amount >= 0),
    justification text NOT NULL,
    maker_person uuid NOT NULL,
    reviewer_person uuid NOT NULL,
    approved_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK(reviewer_person <> maker_person)
);

-- -----------------------------------------------------------------------------
-- BD02: Real-time Budget Control Ledger, Transactions & Rolling Forecast
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS plugin_finance_budget_control_ledger (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    year_id uuid NOT NULL,
    period integer NOT NULL CHECK(period BETWEEN 1 AND 12),
    account_code text NOT NULL,
    department_id uuid REFERENCES plugin_finance_dim_department(id),
    project_id uuid REFERENCES plugin_finance_dim_project(id),
    cost_center_id uuid REFERENCES plugin_finance_dim_cost_center(id),
    budget_amount numeric(28,8) NOT NULL DEFAULT 0 CHECK(budget_amount >= 0),
    reserved_amount numeric(28,8) NOT NULL DEFAULT 0 CHECK(reserved_amount >= 0),
    consumed_amount numeric(28,8) NOT NULL DEFAULT 0 CHECK(consumed_amount >= 0),
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK(budget_amount >= reserved_amount + consumed_amount)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_dim_budget_ctrl_ledger
    ON plugin_finance_budget_control_ledger (
        tenant_id, book_id, year_id, period, account_code,
        COALESCE(department_id, '00000000-0000-0000-0000-000000000000'::uuid),
        COALESCE(project_id, '00000000-0000-0000-0000-000000000000'::uuid),
        COALESCE(cost_center_id, '00000000-0000-0000-0000-000000000000'::uuid)
    );

CREATE TABLE IF NOT EXISTS plugin_finance_budget_transaction (
    id uuid PRIMARY KEY,
    ledger_id uuid NOT NULL REFERENCES plugin_finance_budget_control_ledger(id) ON DELETE CASCADE,
    transaction_type text NOT NULL CHECK(transaction_type IN ('RESERVE', 'RELEASE', 'CONSUME', 'DIRECT_CONSUME', 'ROLLBACK_CONSUME')),
    amount numeric(28,8) NOT NULL CHECK(amount > 0),
    reference_type text NOT NULL,
    reference_id uuid NOT NULL,
    actor_person uuid NOT NULL,
    note text,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS plugin_finance_budget_forecast_snapshot (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    year_id uuid NOT NULL,
    as_of_period integer NOT NULL CHECK(as_of_period BETWEEN 1 AND 12),
    baseline_budget_version_id uuid NOT NULL REFERENCES plugin_finance_budget_version(id),
    ytd_consumed numeric(28,8) NOT NULL DEFAULT 0 CHECK(ytd_consumed >= 0),
    forecasted_remaining numeric(28,8) NOT NULL DEFAULT 0 CHECK(forecasted_remaining >= 0),
    forecast_total numeric(28,8) NOT NULL DEFAULT 0 CHECK(forecast_total >= 0),
    projected_variance numeric(28,8) NOT NULL,
    snapshot_hash text NOT NULL CHECK(snapshot_hash ~ '^[a-f0-9]{64}$'),
    created_by_person uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
