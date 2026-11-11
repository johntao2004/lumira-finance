-- Candidate SQL Migration for Lane W02: IAM & OPS (IAM01, IAM02, IAM03, IAM04, OPS01, OPS02)
-- Compatible with PostgreSQL 18.6 and Lumira Finance 4.0.0
-- Strictly additive; preserves published V1-V25 tables and histories unchanged.

-- Safely expand role check constraint on base member table if present in current schema
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_tables WHERE schemaname = current_schema() AND tablename = 'plugin_finance_member'
    ) THEN
        ALTER TABLE plugin_finance_member DROP CONSTRAINT IF EXISTS plugin_finance_member_role_check;
        ALTER TABLE plugin_finance_member ADD CONSTRAINT plugin_finance_member_role_check 
            CHECK (role IN ('OWNER','MAKER','REVIEWER','POSTER','READER','CASHIER','ACCOUNTANT','AUDITOR','APPROVER'));
    END IF;
END $$;

-- ============================================================================
-- 1. IAM01: Enterprise Member Invitation, Attested Source & Person Lifecycle
-- ============================================================================

CREATE TABLE IF NOT EXISTS plugin_finance_iam_invitation (
    tenant_id uuid NOT NULL,
    id uuid NOT NULL,
    email text NOT NULL CHECK (email ~* '^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$'),
    phone text,
    role text NOT NULL CHECK (role IN ('OWNER','ACCOUNTANT','CASHIER','AUDITOR','APPROVER','READER')),
    department_id text,
    project_id text,
    status text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','ACCEPTED','EXPIRED','CANCELLED')),
    invited_by_account uuid NOT NULL,
    invited_by_person uuid,
    token_hash text NOT NULL CHECK (token_hash ~ '^[a-f0-9]{64}$'),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at timestamptz NOT NULL,
    accepted_at timestamptz,
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, token_hash),
    CHECK (expires_at > created_at),
    CHECK ((status = 'PENDING' AND accepted_at IS NULL) OR (status = 'ACCEPTED' AND accepted_at IS NOT NULL) OR (status IN ('EXPIRED','CANCELLED')))
);

CREATE TABLE IF NOT EXISTS plugin_finance_iam_attested_source (
    tenant_id uuid NOT NULL,
    id uuid NOT NULL,
    source_type text NOT NULL CHECK (source_type IN ('HR_IMPORT','SSO_SYNC')),
    provider_name text NOT NULL CHECK (length(provider_name) BETWEEN 1 AND 120),
    attestation_kind text NOT NULL DEFAULT 'ORG_ATTESTED' CHECK (attestation_kind = 'ORG_ATTESTED'),
    source_reference text NOT NULL CHECK (length(source_reference) BETWEEN 1 AND 255),
    source_sha256 text NOT NULL CHECK (source_sha256 ~ '^[a-f0-9]{64}$'),
    record_count integer NOT NULL CHECK (record_count >= 0),
    raw_payload jsonb NOT NULL,
    attested_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, source_sha256)
);

CREATE TABLE IF NOT EXISTS plugin_finance_iam_person_lifecycle (
    tenant_id uuid NOT NULL,
    id uuid NOT NULL,
    person_id uuid NOT NULL,
    account_id uuid,
    employee_no text NOT NULL CHECK (length(employee_no) BETWEEN 1 AND 64),
    display_name text NOT NULL CHECK (length(display_name) BETWEEN 1 AND 120),
    department_id text,
    project_id text,
    id_card text,
    bank_account text,
    salary_amount numeric(28,8),
    state text NOT NULL DEFAULT 'INVITED' CHECK (state IN ('INVITED','ACTIVE','SUSPENDED','OFFBOARDED')),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    offboarded_at timestamptz,
    offboarded_by uuid,
    offboarding_reason text,
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, person_id),
    UNIQUE (tenant_id, employee_no),
    CHECK ((state = 'OFFBOARDED' AND offboarded_at IS NOT NULL AND offboarded_by IS NOT NULL AND offboarding_reason IS NOT NULL)
        OR (state <> 'OFFBOARDED' AND offboarded_at IS NULL))
);

-- ============================================================================
-- 2. IAM02: Department/Project Data-Scope Grants & Sensitive Field Exports
-- ============================================================================

CREATE TABLE IF NOT EXISTS plugin_finance_iam_data_scope_grant (
    tenant_id uuid NOT NULL,
    id uuid NOT NULL,
    account_id uuid NOT NULL,
    person_id uuid NOT NULL,
    scope_type text NOT NULL CHECK (scope_type IN ('ALL','DEPARTMENT','PROJECT')),
    department_id text,
    project_id text,
    can_export boolean NOT NULL DEFAULT false,
    can_unmask_sensitive boolean NOT NULL DEFAULT false,
    status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','REVOKED')),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at timestamptz,
    revoked_by uuid,
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    PRIMARY KEY (tenant_id, id),
    CHECK ((scope_type = 'ALL' AND department_id IS NULL AND project_id IS NULL)
        OR (scope_type = 'DEPARTMENT' AND department_id IS NOT NULL)
        OR (scope_type = 'PROJECT' AND project_id IS NOT NULL)),
    CHECK ((status = 'ACTIVE' AND revoked_at IS NULL)
        OR (status = 'REVOKED' AND revoked_at IS NOT NULL))
);

CREATE TABLE IF NOT EXISTS plugin_finance_iam_export_request (
    tenant_id uuid NOT NULL,
    id uuid NOT NULL,
    requested_by_account uuid NOT NULL,
    requested_by_person uuid NOT NULL,
    export_resource text NOT NULL CHECK (export_resource IN ('JOURNALS','PAYROLL','AUDIT_LOG','LEDGER_BALANCE')),
    filter_department_id text,
    filter_project_id text,
    include_sensitive_unmasked boolean NOT NULL DEFAULT false,
    status text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','AUTHORIZED','REJECTED','COMPLETED')),
    rejection_reason text,
    exported_row_count integer,
    file_sha256 text,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at timestamptz,
    PRIMARY KEY (tenant_id, id),
    CHECK ((status = 'REJECTED' AND rejection_reason IS NOT NULL) OR (status <> 'REJECTED'))
);

-- ============================================================================
-- 3. IAM03: Statutory Role Conflict Matrix, Tiered Monetary Matrix & Delegations
-- ============================================================================

CREATE TABLE IF NOT EXISTS plugin_finance_iam_sod_conflict_matrix (
    tenant_id uuid NOT NULL,
    id uuid NOT NULL,
    role_a text NOT NULL CHECK (role_a IN ('CASHIER','ACCOUNTANT','AUDITOR','APPROVER')),
    role_b text NOT NULL CHECK (role_b IN ('CASHIER','ACCOUNTANT','AUDITOR','APPROVER')),
    conflict_severity text NOT NULL DEFAULT 'STRICT_BLOCK' CHECK (conflict_severity IN ('STRICT_BLOCK','WARNING')),
    statutory_basis text NOT NULL,
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, role_a, role_b),
    CHECK (role_a <> role_b)
);

CREATE TABLE IF NOT EXISTS plugin_finance_iam_approval_threshold (
    tenant_id uuid NOT NULL,
    id uuid NOT NULL,
    rule_name text NOT NULL CHECK (length(rule_name) BETWEEN 1 AND 120),
    entity_type text NOT NULL CHECK (entity_type IN ('JOURNAL','PAYMENT_REQUEST','EXPENSE_CLAIM','TAX_DECLARATION')),
    min_amount numeric(28,8) NOT NULL CHECK (min_amount >= 0),
    max_amount numeric(28,8) CHECK (max_amount IS NULL OR max_amount >= min_amount),
    required_stages integer NOT NULL CHECK (required_stages BETWEEN 1 AND 3),
    stage1_role text NOT NULL CHECK (stage1_role IN ('APPROVER','FINANCE_MANAGER','FINANCE_DIRECTOR','TREASURY_BOARD')),
    stage2_role text CHECK (stage2_role IN ('FINANCE_MANAGER','FINANCE_DIRECTOR','TREASURY_BOARD')),
    stage3_role text CHECK (stage3_role = 'TREASURY_BOARD'),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, id),
    CHECK ((required_stages = 1 AND stage2_role IS NULL AND stage3_role IS NULL)
        OR (required_stages = 2 AND stage2_role IS NOT NULL AND stage3_role IS NULL)
        OR (required_stages = 3 AND stage2_role IS NOT NULL AND stage3_role IS NOT NULL))
);

CREATE TABLE IF NOT EXISTS plugin_finance_iam_approval_task (
    tenant_id uuid NOT NULL,
    id uuid NOT NULL,
    threshold_id uuid NOT NULL,
    entity_type text NOT NULL,
    entity_id uuid NOT NULL,
    amount numeric(28,8) NOT NULL CHECK (amount >= 0),
    current_stage integer NOT NULL DEFAULT 1 CHECK (current_stage >= 1),
    total_stages integer NOT NULL CHECK (total_stages BETWEEN 1 AND 3),
    status text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','REJECTED','WITHDRAWN','CANCELLED')),
    requested_by_account uuid NOT NULL,
    requested_by_person uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, threshold_id) REFERENCES plugin_finance_iam_approval_threshold (tenant_id, id)
);

CREATE TABLE IF NOT EXISTS plugin_finance_iam_approval_stage_decision (
    tenant_id uuid NOT NULL,
    id uuid NOT NULL,
    task_id uuid NOT NULL,
    stage_order integer NOT NULL CHECK (stage_order BETWEEN 1 AND 3),
    assigned_role text NOT NULL,
    assigned_person_id uuid,
    decision text NOT NULL DEFAULT 'PENDING' CHECK (decision IN ('PENDING','APPROVED','REJECTED')),
    decided_by_account uuid,
    decided_by_person uuid,
    decision_reason text,
    decided_at timestamptz,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, task_id, stage_order),
    FOREIGN KEY (tenant_id, task_id) REFERENCES plugin_finance_iam_approval_task (tenant_id, id),
    CHECK ((decision = 'PENDING' AND decided_at IS NULL AND decision_reason IS NULL)
        OR (decision <> 'PENDING' AND decided_at IS NOT NULL AND decision_reason IS NOT NULL AND decided_by_person IS NOT NULL))
);

CREATE TABLE IF NOT EXISTS plugin_finance_iam_delegation (
    tenant_id uuid NOT NULL,
    id uuid NOT NULL,
    delegator_account uuid NOT NULL,
    delegator_person uuid NOT NULL,
    delegatee_account uuid NOT NULL,
    delegatee_person uuid NOT NULL,
    role text NOT NULL CHECK (role IN ('APPROVER','REVIEWER','POSTER','AUDITOR')),
    effective_from timestamptz NOT NULL,
    effective_to timestamptz NOT NULL,
    status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','REVOKED','EXPIRED')),
    reason text NOT NULL CHECK (length(reason) BETWEEN 1 AND 1000),
    revoked_at timestamptz,
    revoked_by uuid,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, id),
    CHECK (delegator_person <> delegatee_person),
    CHECK (effective_to > effective_from),
    CHECK ((status = 'ACTIVE' AND revoked_at IS NULL) OR (status = 'REVOKED' AND revoked_at IS NOT NULL) OR (status = 'EXPIRED'))
);

-- ============================================================================
-- 4. IAM04: Independent AUDITOR Scope & Dual-Person Emergency Grants
-- ============================================================================

CREATE TABLE IF NOT EXISTS plugin_finance_iam_emergency_grant (
    tenant_id uuid NOT NULL,
    id uuid NOT NULL,
    requester_account uuid NOT NULL,
    requester_person uuid NOT NULL,
    book_id uuid NOT NULL,
    scope_description text NOT NULL CHECK (length(scope_description) BETWEEN 1 AND 500),
    requested_duration_minutes integer NOT NULL CHECK (requested_duration_minutes BETWEEN 5 AND 4320),
    reason text NOT NULL CHECK (length(reason) BETWEEN 1 AND 1000),
    status text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','ACTIVE','EXPIRED','REVOKED','CLOSED')),
    approver1_account uuid,
    approver1_person uuid,
    approver1_at timestamptz,
    approver2_account uuid,
    approver2_person uuid,
    approver2_at timestamptz,
    activated_at timestamptz,
    expires_at timestamptz,
    revoked_at timestamptz,
    revoked_by uuid,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, id),
    CHECK (approver1_person IS NULL OR approver1_person <> requester_person),
    CHECK (approver2_person IS NULL OR (approver2_person <> requester_person AND approver2_person <> approver1_person)),
    CHECK ((status = 'PENDING' AND activated_at IS NULL AND expires_at IS NULL)
        OR (status IN ('ACTIVE','EXPIRED','CLOSED') AND approver1_person IS NOT NULL AND approver2_person IS NOT NULL AND activated_at IS NOT NULL AND expires_at IS NOT NULL)
        OR (status = 'REVOKED' AND revoked_at IS NOT NULL))
);

-- ============================================================================
-- 5. OPS01: Scoped Finance Backup/PITR, Joint Recovery Drill & Rollback Ledger
-- ============================================================================

CREATE TABLE IF NOT EXISTS plugin_finance_ops_backup_manifest (
    tenant_id uuid NOT NULL,
    id uuid NOT NULL,
    backup_id text NOT NULL CHECK (length(backup_id) BETWEEN 1 AND 128),
    backup_type text NOT NULL CHECK (backup_type IN ('PITR','FULL_SNAPSHOT')),
    db_snapshot_hash text NOT NULL CHECK (db_snapshot_hash ~ '^[a-f0-9]{64}$'),
    original_files_count integer NOT NULL CHECK (original_files_count >= 0),
    original_bytes_total bigint NOT NULL CHECK (original_bytes_total >= 0),
    files_manifest_sha256 text NOT NULL CHECK (files_manifest_sha256 ~ '^[a-f0-9]{64}$'),
    recovery_target_time timestamptz NOT NULL,
    cloud_dr_status text NOT NULL DEFAULT 'LOCAL_DRILL_ONLY' CHECK (cloud_dr_status IN ('LOCAL_DRILL_ONLY','PROD_REPLICATED')),
    status text NOT NULL DEFAULT 'VERIFIED' CHECK (status IN ('VERIFIED','FAILED')),
    verified_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, backup_id)
);

CREATE TABLE IF NOT EXISTS plugin_finance_ops_joint_drill_record (
    tenant_id uuid NOT NULL,
    id uuid NOT NULL,
    manifest_id uuid NOT NULL,
    drill_name text NOT NULL CHECK (length(drill_name) BETWEEN 1 AND 128),
    target_pitr_time timestamptz NOT NULL,
    db_record_count bigint NOT NULL CHECK (db_record_count >= 0),
    db_records_matched boolean NOT NULL,
    original_files_matched boolean NOT NULL,
    original_bytes_matched boolean NOT NULL,
    discrepancy_count integer NOT NULL DEFAULT 0 CHECK (discrepancy_count >= 0),
    discrepancies_detail jsonb NOT NULL DEFAULT '[]'::jsonb,
    status text NOT NULL CHECK (status IN ('SUCCESS','FAILED')),
    executed_by uuid NOT NULL,
    duration_ms bigint NOT NULL CHECK (duration_ms >= 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, manifest_id) REFERENCES plugin_finance_ops_backup_manifest (tenant_id, id),
    CHECK ((status = 'SUCCESS' AND db_records_matched = true AND original_files_matched = true AND original_bytes_matched = true AND discrepancy_count = 0)
        OR (status = 'FAILED' AND (db_records_matched = false OR original_files_matched = false OR original_bytes_matched = false OR discrepancy_count > 0)))
);

CREATE TABLE IF NOT EXISTS plugin_finance_ops_rollback_ledger (
    tenant_id uuid NOT NULL,
    id uuid NOT NULL,
    release_version text NOT NULL CHECK (length(release_version) BETWEEN 1 AND 64),
    migration_name text NOT NULL CHECK (length(migration_name) BETWEEN 1 AND 128),
    rollback_script_sha256 text NOT NULL CHECK (rollback_script_sha256 ~ '^[a-f0-9]{64}$'),
    backward_compatible boolean NOT NULL DEFAULT true,
    readiness_status text NOT NULL DEFAULT 'READY' CHECK (readiness_status IN ('READY','BLOCKED','ROLLED_BACK')),
    block_reason text,
    verified_by uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, release_version, migration_name),
    CHECK ((readiness_status = 'BLOCKED' AND block_reason IS NOT NULL) OR (readiness_status <> 'BLOCKED'))
);

-- ============================================================================
-- 6. OPS02: Support Tickets, Incident Timeline & Customer Diagnostic Grants
-- ============================================================================

CREATE TABLE IF NOT EXISTS plugin_finance_ops_support_ticket (
    tenant_id uuid NOT NULL,
    id uuid NOT NULL,
    ticket_no text NOT NULL CHECK (ticket_no ~ '^TICK-[0-9]{6}-[0-9]{4}$'),
    book_id uuid,
    severity text NOT NULL CHECK (severity IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    category text NOT NULL CHECK (category IN ('ACCOUNTING_ANOMALY','RECONCILIATION_FAILURE','PERMISSION_DEFECT','SYSTEM_OUTAGE')),
    title text NOT NULL CHECK (length(title) BETWEEN 1 AND 200),
    description text NOT NULL CHECK (length(description) BETWEEN 1 AND 4000),
    status text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','IN_PROGRESS','PENDING_CUSTOMER','RESOLVED','CLOSED')),
    created_by_account uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at timestamptz,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, ticket_no)
);

CREATE TABLE IF NOT EXISTS plugin_finance_ops_incident_timeline (
    tenant_id uuid NOT NULL,
    id uuid NOT NULL,
    ticket_id uuid NOT NULL,
    incident_phase text NOT NULL CHECK (incident_phase IN ('INVESTIGATING','IDENTIFIED','MONITORING','RESOLVED')),
    summary text NOT NULL CHECK (length(summary) BETWEEN 1 AND 255),
    customer_communication text NOT NULL CHECK (length(customer_communication) BETWEEN 1 AND 4000),
    technical_detail text NOT NULL,
    event_timestamp timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    recorded_by uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, ticket_id) REFERENCES plugin_finance_ops_support_ticket (tenant_id, id)
);

CREATE TABLE IF NOT EXISTS plugin_finance_ops_diagnostic_grant (
    tenant_id uuid NOT NULL,
    id uuid NOT NULL,
    ticket_id uuid NOT NULL,
    book_id uuid NOT NULL,
    support_operator_account uuid NOT NULL,
    approved_by_account uuid NOT NULL,
    approved_by_person uuid NOT NULL,
    scope_type text NOT NULL DEFAULT 'READ_ONLY_DIAGNOSTIC' CHECK (scope_type = 'READ_ONLY_DIAGNOSTIC'),
    duration_minutes integer NOT NULL CHECK (duration_minutes BETWEEN 15 AND 480),
    status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','REVOKED','EXPIRED')),
    activated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    revoked_by uuid,
    revocation_reason text,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, ticket_id) REFERENCES plugin_finance_ops_support_ticket (tenant_id, id),
    CHECK (expires_at > activated_at),
    CHECK ((status = 'ACTIVE' AND revoked_at IS NULL)
        OR (status = 'REVOKED' AND revoked_at IS NOT NULL AND revocation_reason IS NOT NULL)
        OR (status = 'EXPIRED'))
);

-- ============================================================================
-- 7. Seed Initial Statutory SoD Matrix & Approval Threshold Defaults
-- ============================================================================

-- Function to seed statutory conflicts for a tenant
CREATE OR REPLACE FUNCTION plugin_finance_iam_seed_defaults(p_tenant_id uuid) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    -- Statutory SoD conflicts
    INSERT INTO plugin_finance_iam_sod_conflict_matrix(tenant_id, id, role_a, role_b, conflict_severity, statutory_basis)
    VALUES
        (p_tenant_id, gen_random_uuid(), 'CASHIER', 'AUDITOR', 'STRICT_BLOCK', '《中华人民共和国会计法》第三十七条：出纳人员不得兼任稽核、会计档案保管和收入、支出、费用、债权债务账目的登记工作。'),
        (p_tenant_id, gen_random_uuid(), 'CASHIER', 'APPROVER', 'STRICT_BLOCK', '内控基本规范职责分离：资金经办人员（出纳）不得兼任资金支付审批人员。'),
        (p_tenant_id, gen_random_uuid(), 'CASHIER', 'ACCOUNTANT', 'STRICT_BLOCK', '会计法第三十七条：出纳人员不得兼任总账会计账目登记工作。'),
        (p_tenant_id, gen_random_uuid(), 'ACCOUNTANT', 'AUDITOR', 'STRICT_BLOCK', '内部控制评价与稽核独立性原则：记账人员不得兼任同范围内部稽核审计。')
    ON CONFLICT (tenant_id, role_a, role_b) DO NOTHING;

    -- Tiered approval thresholds
    INSERT INTO plugin_finance_iam_approval_threshold(tenant_id, id, rule_name, entity_type, min_amount, max_amount, required_stages, stage1_role, stage2_role, stage3_role)
    VALUES
        (p_tenant_id, gen_random_uuid(), '普通支出一审', 'PAYMENT_REQUEST', 0.00000000, 50000.00000000, 1, 'APPROVER', NULL, NULL),
        (p_tenant_id, gen_random_uuid(), '大额支出双审', 'PAYMENT_REQUEST', 50000.00000001, 500000.00000000, 2, 'APPROVER', 'FINANCE_DIRECTOR', NULL),
        (p_tenant_id, gen_random_uuid(), '重大支出三审', 'PAYMENT_REQUEST', 500000.00000001, NULL, 3, 'APPROVER', 'FINANCE_DIRECTOR', 'TREASURY_BOARD')
    ON CONFLICT DO NOTHING;
END;
$$;
