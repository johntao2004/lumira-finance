-- Migration Candidate for Lane W12: archive-int-ai
-- PostgreSQL 18 DDL covering:
-- 1. ARC01/ARC02/ARC03: Statutory retention (10y/30y/permanent), borrow/custody transfer, legal hold, dual-person destruction appraisal & supervision manifest, archive completeness
-- 2. INT01: Versioned import templates, deterministic dry-run preview, independent batch approval, control-total reconciliation
-- 3. INT02 & PLT03: Scoped external ERP connector, versioned AccountingEvent ingestion, idempotency replay, watermark sequence ordering, cancellation reversal, Anti-CRM guard
-- 4. INT03: Approved recurring task schedule definitions, month-end/leap-year Feb 28/29 calendar rules, missed-window catch-up, per-period deduplication
-- 5. AI01, AI02, AIX01-03: Local OCR account suggestion, anomaly alerts & false-positive closure audit, ActionPlan DAG dry-run, digest-bound approval, execution token, R4 major-action block
-- 6. PLT01: Max 8-hour non-renewable offline capture lease, client-ciphertext draft envelope, reconnection revocation check, WEB_ONLY_CLIENT registry

-- 1. Archive Governance (ARC01/ARC02/ARC03)
CREATE TABLE IF NOT EXISTS plugin_finance_archive_gov_retention_rule (
  tenant_id uuid NOT NULL,
  category text NOT NULL CHECK(category IN('VOUCHER_GENERAL','LEDGER_BOOK','STATEMENT_ANNUAL','STATEMENT_PERIODIC','BANK_RECONCILIATION','TAX_DECLARATION','CUSTODY_TRANSFER','DESTRUCTION_MANIFEST')),
  statutory_years int NOT NULL CHECK(statutory_years IN(10, 30, 999)),
  statutory_standard text NOT NULL DEFAULT '会计档案管理办法2015',
  description text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(tenant_id, category)
);

CREATE TABLE IF NOT EXISTS plugin_finance_archive_gov_record (
  tenant_id uuid NOT NULL,
  book_id uuid NOT NULL,
  id uuid NOT NULL,
  category text NOT NULL CHECK(category IN('VOUCHER_GENERAL','LEDGER_BOOK','STATEMENT_ANNUAL','STATEMENT_PERIODIC','BANK_RECONCILIATION','TAX_DECLARATION','CUSTODY_TRANSFER','DESTRUCTION_MANIFEST')),
  fiscal_year_id uuid NOT NULL,
  title text NOT NULL,
  statutory_years int NOT NULL CHECK(statutory_years IN(10, 30, 999)),
  archived_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  retention_until_date date NOT NULL,
  custodian_person_id uuid NOT NULL,
  legal_hold boolean NOT NULL DEFAULT false,
  hold_reason text,
  status text NOT NULL DEFAULT 'IN_CUSTODY' CHECK(status IN('IN_CUSTODY','BORROWED','APPRAISED_FOR_DESTRUCTION','DESTROYED')),
  PRIMARY KEY(tenant_id, book_id, id),
  CHECK(NOT (legal_hold = true AND status = 'DESTROYED')),
  CHECK(NOT (statutory_years >= 999 AND status = 'DESTROYED'))
);

CREATE TABLE IF NOT EXISTS plugin_finance_archive_gov_borrow_log (
  tenant_id uuid NOT NULL,
  book_id uuid NOT NULL,
  id uuid NOT NULL,
  record_id uuid NOT NULL,
  borrower_person_id uuid NOT NULL,
  purpose text NOT NULL,
  borrowed_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expected_return_date date NOT NULL,
  returned_at timestamptz,
  return_inspector_person_id uuid,
  status text NOT NULL CHECK(status IN('BORROWED','RETURNED','OVERDUE')),
  PRIMARY KEY(tenant_id, book_id, id),
  FOREIGN KEY(tenant_id, book_id, record_id) REFERENCES plugin_finance_archive_gov_record(tenant_id, book_id, id),
  CHECK(return_inspector_person_id IS NULL OR return_inspector_person_id <> borrower_person_id)
);

CREATE TABLE IF NOT EXISTS plugin_finance_archive_gov_custody_transfer (
  tenant_id uuid NOT NULL,
  book_id uuid NOT NULL,
  id uuid NOT NULL,
  batch_no text NOT NULL,
  record_ids jsonb NOT NULL,
  transferor_person_id uuid NOT NULL,
  recipient_person_id uuid NOT NULL,
  supervisor_person_id uuid NOT NULL,
  transfer_date date NOT NULL,
  manifest_digest text NOT NULL CHECK(manifest_digest ~ '^[a-f0-9]{64}$'),
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(tenant_id, book_id, id),
  CHECK(transferor_person_id <> recipient_person_id AND transferor_person_id <> supervisor_person_id AND recipient_person_id <> supervisor_person_id)
);

CREATE TABLE IF NOT EXISTS plugin_finance_archive_gov_destruction_manifest (
  tenant_id uuid NOT NULL,
  book_id uuid NOT NULL,
  id uuid NOT NULL,
  record_ids jsonb NOT NULL,
  appraiser1_person_id uuid NOT NULL,
  appraiser2_person_id uuid NOT NULL,
  supervisor1_person_id uuid NOT NULL,
  supervisor2_person_id uuid NOT NULL,
  appraisal_date date NOT NULL,
  destruction_date date,
  method text NOT NULL DEFAULT 'SECURE_DESTRUCTION' CHECK(method IN('SECURE_DESTRUCTION','INCINERATION','CERTIFIED_WIPE')),
  status text NOT NULL CHECK(status IN('APPRAISED','APPROVED_FOR_DESTRUCTION','DESTROYED','FROZEN_BY_HOLD')),
  manifest_digest text NOT NULL CHECK(manifest_digest ~ '^[a-f0-9]{64}$'),
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(tenant_id, book_id, id),
  CHECK(appraiser1_person_id <> appraiser2_person_id AND supervisor1_person_id <> supervisor2_person_id),
  CHECK(appraiser1_person_id <> supervisor1_person_id AND appraiser1_person_id <> supervisor2_person_id AND appraiser2_person_id <> supervisor1_person_id AND appraiser2_person_id <> supervisor2_person_id)
);

-- 2. Import Template & Batch Management (INT01)
CREATE TABLE IF NOT EXISTS plugin_finance_import_template (
  tenant_id uuid NOT NULL,
  id uuid NOT NULL,
  template_code text NOT NULL,
  version bigint NOT NULL DEFAULT 1 CHECK(version > 0),
  target_entity text NOT NULL CHECK(target_entity IN('JOURNAL_BATCH','OPENING_BALANCE','CHART_OF_ACCOUNTS')),
  field_mappings jsonb NOT NULL,
  delimiter text NOT NULL DEFAULT ',',
  created_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(tenant_id, id),
  UNIQUE(tenant_id, template_code, version)
);

CREATE TABLE IF NOT EXISTS plugin_finance_import_batch (
  tenant_id uuid NOT NULL,
  book_id uuid NOT NULL,
  id uuid NOT NULL,
  template_id uuid NOT NULL,
  file_name text NOT NULL,
  file_sha256 text NOT NULL CHECK(file_sha256 ~ '^[a-f0-9]{64}$'),
  status text NOT NULL CHECK(status IN('UPLOADED','DRY_RUN_PASSED','DRY_RUN_FAILED','PENDING_APPROVAL','APPROVED','REJECTED','ACTIVATING','ACTIVATED','RECONCILED','MISMATCH_DETECTED')),
  total_rows int NOT NULL DEFAULT 0,
  valid_rows int NOT NULL DEFAULT 0,
  error_rows int NOT NULL DEFAULT 0,
  control_total_debit numeric(28,8) NOT NULL DEFAULT 0,
  control_total_credit numeric(28,8) NOT NULL DEFAULT 0,
  actual_reconciled_debit numeric(28,8),
  actual_reconciled_credit numeric(28,8),
  row_errors jsonb NOT NULL DEFAULT '[]'::jsonb,
  preparer_person_id uuid NOT NULL,
  approver_person_id uuid,
  approval_reason text,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(tenant_id, book_id, id),
  CHECK(approver_person_id IS NULL OR approver_person_id <> preparer_person_id),
  CHECK(status NOT IN ('APPROVED', 'ACTIVATED', 'RECONCILED') OR approver_person_id IS NOT NULL)
);

-- 3. ERP Connector, AccountingEvent & Anti-CRM (INT02 & PLT03)
CREATE TABLE IF NOT EXISTS plugin_finance_erp_connector (
  tenant_id uuid NOT NULL,
  id uuid NOT NULL,
  connector_name text NOT NULL,
  system_type text NOT NULL CHECK(system_type IN('SAP','ORACLE','YONYOU','KINGDEE','CUSTOM_ERP')),
  allowed_scopes jsonb NOT NULL,
  auth_key_hash text NOT NULL CHECK(auth_key_hash ~ '^[a-f0-9]{64}$'),
  status text NOT NULL CHECK(status IN('ACTIVE','SUSPENDED','REVOKED')),
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(tenant_id, id)
);

CREATE TABLE IF NOT EXISTS plugin_finance_erp_event_watermark (
  tenant_id uuid NOT NULL,
  source_system text NOT NULL,
  book_id uuid NOT NULL,
  latest_watermark bigint NOT NULL DEFAULT 0,
  updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(tenant_id, source_system, book_id)
);

CREATE TABLE IF NOT EXISTS plugin_finance_erp_event_inbox (
  tenant_id uuid NOT NULL,
  event_id uuid NOT NULL,
  source_system text NOT NULL,
  source_event_id text NOT NULL,
  source_document_id text NOT NULL,
  source_document_version int NOT NULL CHECK(source_document_version > 0),
  event_type text NOT NULL CHECK(event_type IN('REVENUE_RECOGNIZED','PURCHASE_ACCEPTED','CASH_RECEIVED','CASH_PAID','ASSET_DEPRECIATED','PAYROLL_ACCRUED','REVERSAL','ADJUSTMENT')),
  effect_type text NOT NULL CHECK(effect_type = 'CREATE_JOURNAL_DRAFT'),
  legal_entity_id uuid NOT NULL,
  book_id uuid NOT NULL,
  watermark bigint NOT NULL,
  payload_digest text NOT NULL CHECK(payload_digest ~ '^[a-f0-9]{64}$'),
  payload jsonb NOT NULL,
  draft_journal_id uuid,
  reverses_source_event_id text,
  status text NOT NULL CHECK(status IN('INGESTED','DRAFT_CREATED','REVERSAL_CREATED','DUPLICATE_IGNORED','WATERMARK_OUT_OF_ORDER')),
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(tenant_id, event_id),
  UNIQUE(tenant_id, source_system, source_event_id)
);

CREATE TABLE IF NOT EXISTS plugin_finance_erp_anti_crm_log (
  tenant_id uuid NOT NULL,
  id uuid NOT NULL,
  source_system text NOT NULL,
  rejected_payload_sample text NOT NULL,
  detected_crm_fields jsonb NOT NULL,
  reason text NOT NULL,
  rejected_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(tenant_id, id)
);

-- 4. Recurring Schedule Definitions & Missed Windows (INT03)
CREATE TABLE IF NOT EXISTS plugin_finance_schedule_definition (
  tenant_id uuid NOT NULL,
  book_id uuid NOT NULL,
  id uuid NOT NULL,
  task_code text NOT NULL,
  task_name text NOT NULL,
  task_type text NOT NULL CHECK(task_type IN('ASSET_DEPRECIATION','EXPENSE_ACCRUAL','FX_REVALUATION','MONTH_END_CHECK')),
  schedule_type text NOT NULL CHECK(schedule_type IN('MONTH_END','PERIOD_END','DAY_OF_MONTH')),
  day_of_month int CHECK(day_of_month BETWEEN 1 AND 31),
  leap_year_feb_rule text NOT NULL DEFAULT 'LAST_DAY_OF_MONTH' CHECK(leap_year_feb_rule IN('LAST_DAY_OF_MONTH','EXACT_28_ALWAYS')),
  parameters jsonb NOT NULL DEFAULT '{}'::jsonb,
  status text NOT NULL CHECK(status IN('ACTIVE','PAUSED','CANCELLED')),
  approved_by uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(tenant_id, book_id, id),
  UNIQUE(tenant_id, book_id, task_code)
);

CREATE TABLE IF NOT EXISTS plugin_finance_schedule_execution (
  tenant_id uuid NOT NULL,
  book_id uuid NOT NULL,
  id uuid NOT NULL,
  schedule_id uuid NOT NULL,
  fiscal_year_id uuid NOT NULL,
  period_id uuid NOT NULL,
  scheduled_date date NOT NULL,
  execution_type text NOT NULL CHECK(execution_type IN('NORMAL','CATCH_UP')),
  status text NOT NULL CHECK(status IN('PENDING','EXECUTING','SUCCESS','FAILED','SKIPPED_DUPLICATE')),
  result_summary jsonb,
  executed_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(tenant_id, book_id, id),
  UNIQUE(tenant_id, book_id, schedule_id, fiscal_year_id, period_id)
);

-- 5. AI Suggestions, Anomaly Detection & ActionPlan (AI01, AI02, AIX01-03)
CREATE TABLE IF NOT EXISTS plugin_finance_ai_account_suggestion (
  tenant_id uuid NOT NULL,
  book_id uuid NOT NULL,
  id uuid NOT NULL,
  ocr_text text NOT NULL,
  extracted_tokens jsonb NOT NULL,
  suggested_account_code text NOT NULL,
  confidence numeric(5,4) NOT NULL CHECK(confidence BETWEEN 0.0000 AND 1.0000),
  external_model_provider text NOT NULL DEFAULT 'NOT_CONFIGURED' CHECK(external_model_provider = 'NOT_CONFIGURED'),
  mandatory_human_review boolean NOT NULL DEFAULT true CHECK(mandatory_human_review = true),
  review_status text NOT NULL DEFAULT 'PENDING_REVIEW' CHECK(review_status IN('PENDING_REVIEW','ACCEPTED','MODIFIED','REJECTED')),
  reviewed_by uuid,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(tenant_id, book_id, id)
);

CREATE TABLE IF NOT EXISTS plugin_finance_ai_anomaly_alert (
  tenant_id uuid NOT NULL,
  book_id uuid NOT NULL,
  id uuid NOT NULL,
  rule_code text NOT NULL CHECK(rule_code IN('UNBALANCED_JOURNAL','AMOUNT_SPIKE_3SIGMA','ROUND_NUMBER_CLUSTER','WEEKEND_TRANSACTION','DUPLICATE_INVOICE_REF','NEGATIVE_BALANCE')),
  severity text NOT NULL CHECK(severity IN('LOW','MEDIUM','HIGH','CRITICAL')),
  target_entity_type text NOT NULL,
  target_entity_id uuid NOT NULL,
  details jsonb NOT NULL,
  status text NOT NULL CHECK(status IN('OPEN','ASSIGNED_FOR_VERIFICATION','RESOLVED','CLOSED_FALSE_POSITIVE')),
  assigned_person_id uuid,
  closure_reason text,
  closure_evidence_digest text CHECK(closure_evidence_digest IS NULL OR closure_evidence_digest ~ '^[a-f0-9]{64}$'),
  closed_by uuid,
  closed_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(tenant_id, book_id, id),
  CHECK(status <> 'CLOSED_FALSE_POSITIVE' OR (closure_reason IS NOT NULL AND closed_by IS NOT NULL))
);

CREATE TABLE IF NOT EXISTS plugin_finance_ai_action_plan (
  tenant_id uuid NOT NULL,
  plan_id uuid NOT NULL,
  schema_version text NOT NULL DEFAULT '1.0.0',
  requester_id uuid NOT NULL,
  version bigint NOT NULL DEFAULT 1 CHECK(version > 0),
  content_version bigint NOT NULL DEFAULT 1 CHECK(content_version > 0),
  status text NOT NULL CHECK(status IN('PROPOSED','DRY_RUN_PASSED','AWAITING_APPROVAL','APPROVED','EXECUTING','SUCCEEDED','PARTIAL','FAILED','EXPIRED','REVOKED','BLOCKED_MAJOR_ACTION')),
  policy_version text NOT NULL,
  currency text NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
  expected_total_amount numeric(28,8) NOT NULL,
  actions jsonb NOT NULL,
  payload_digest text NOT NULL CHECK(payload_digest ~ '^[a-f0-9]{64}$'),
  dry_run_digest text NOT NULL CHECK(dry_run_digest ~ '^[a-f0-9]{64}$'),
  execution_budget jsonb NOT NULL,
  approval_ids jsonb NOT NULL DEFAULT '[]'::jsonb,
  approver_id uuid,
  approved_at timestamptz,
  execution_token_hash text,
  execution_token_expires_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at timestamptz NOT NULL,
  PRIMARY KEY(tenant_id, plan_id),
  CHECK(approver_id IS NULL OR approver_id <> requester_id)
);

CREATE TABLE IF NOT EXISTS plugin_finance_ai_action_execution_log (
  tenant_id uuid NOT NULL,
  plan_id uuid NOT NULL,
  action_id uuid NOT NULL,
  command text NOT NULL,
  risk text NOT NULL CHECK(risk IN('R1','R2','R3','R4')),
  status text NOT NULL CHECK(status IN('SUCCESS','FAILED','SKIPPED','BLOCKED')),
  result jsonb,
  executed_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(tenant_id, plan_id, action_id),
  CHECK(NOT (risk = 'R4' AND status = 'SUCCESS'))
);

-- 6. Offline Capture Lease & Encrypted Envelope (PLT01)
CREATE TABLE IF NOT EXISTS plugin_finance_offline_lease (
  tenant_id uuid NOT NULL,
  lease_id uuid NOT NULL,
  actor_id uuid NOT NULL,
  device_fingerprint text NOT NULL,
  client_type text NOT NULL DEFAULT 'WEB_ONLY_CLIENT' CHECK(client_type = 'WEB_ONLY_CLIENT'),
  issued_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at timestamptz NOT NULL,
  max_seconds int NOT NULL CHECK(max_seconds <= 28800),
  renewable boolean NOT NULL DEFAULT false CHECK(renewable = false),
  status text NOT NULL CHECK(status IN('ACTIVE','EXPIRED','REVOKED')),
  revoked_at timestamptz,
  revocation_reason text,
  PRIMARY KEY(tenant_id, lease_id),
  CHECK(status <> 'REVOKED' OR (revoked_at IS NOT NULL AND revocation_reason IS NOT NULL))
);

CREATE TABLE IF NOT EXISTS plugin_finance_offline_draft_envelope (
  tenant_id uuid NOT NULL,
  envelope_id uuid NOT NULL,
  lease_id uuid NOT NULL,
  client_ciphertext text NOT NULL,
  iv text NOT NULL,
  auth_tag text NOT NULL,
  client_signature text NOT NULL,
  payload_sha256 text NOT NULL CHECK(payload_sha256 ~ '^[a-f0-9]{64}$'),
  sync_status text NOT NULL CHECK(sync_status IN('STORED','DECRYPTED_FOR_REVIEW','REJECTED_LEASE_REVOKED','MERGED_INTO_DRAFT')),
  synced_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(tenant_id, envelope_id),
  FOREIGN KEY(tenant_id, lease_id) REFERENCES plugin_finance_offline_lease(tenant_id, lease_id)
);
