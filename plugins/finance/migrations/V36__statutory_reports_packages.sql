-- Migration candidate: Statutory Reports (RP01, RPT01-RPT06, RP02, RP03)
-- Covers versioned templates, report packages, statements, worksheets, structured notes,
-- institutional double-entry teaching guides, operating KPIs, cash calendar, and consolidation worksheets.

CREATE TABLE IF NOT EXISTS plugin_finance_statutory_template (
  tenant_id uuid NOT NULL,
  id uuid NOT NULL,
  standard text NOT NULL CHECK(standard IN('CAS_2019','SBE_2011')),
  kind text NOT NULL CHECK(kind IN('BS','PL','CF','SCE','NOTES')),
  template_code text NOT NULL CHECK(length(template_code) BETWEEN 1 AND 64),
  version text NOT NULL CHECK(length(version) BETWEEN 1 AND 64),
  rows_definition jsonb NOT NULL,
  formulas jsonb NOT NULL,
  status text NOT NULL DEFAULT 'APPROVED' CHECK(status IN('DRAFT','APPROVED','DEPRECATED')),
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, standard, kind, version)
);

CREATE TABLE IF NOT EXISTS plugin_finance_statutory_package (
  tenant_id uuid NOT NULL,
  legal_entity_id uuid NOT NULL,
  book_id uuid NOT NULL,
  year_id uuid NOT NULL,
  id uuid NOT NULL,
  package_type text NOT NULL CHECK(package_type IN('THREE_STATEMENT_PACKAGE','FULL_STATUTORY_REPORT_PACKAGE')),
  standard text NOT NULL CHECK(standard IN('CAS_2019','SBE_2011')),
  period_start date NOT NULL,
  period_end date NOT NULL CHECK(period_start <= period_end),
  filing_target text NOT NULL DEFAULT 'TAX_FILING' CHECK(filing_target IN('TAX_FILING','MARKET_SUPERVISION_FILING','STATUTORY_AUDIT_FILING')),
  status text NOT NULL DEFAULT 'DRAFT' CHECK(status IN('DRAFT','REVIEWED','APPROVED','PUBLISHED','BLOCKED')),
  version bigint NOT NULL DEFAULT 1 CHECK(version > 0),
  preparer jsonb NOT NULL,
  reviewer jsonb,
  approver jsonb,
  publication_gaps jsonb NOT NULL DEFAULT '[]'::jsonb,
  content_digest text NOT NULL CHECK(content_digest ~ '^[a-f0-9]{64}$'),
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, id),
  CHECK (approver IS NULL OR approver->>'personId' <> preparer->>'personId'),
  CHECK (reviewer IS NULL OR reviewer->>'personId' <> preparer->>'personId'),
  CHECK (status <> 'PUBLISHED' OR (approver IS NOT NULL AND publication_gaps = '[]'::jsonb))
);

CREATE TABLE IF NOT EXISTS plugin_finance_statutory_statement (
  tenant_id uuid NOT NULL,
  package_id uuid NOT NULL,
  id uuid NOT NULL,
  kind text NOT NULL CHECK(kind IN('BS','PL','CF','SCE','NOTES')),
  template_code text NOT NULL,
  cells jsonb NOT NULL,
  articulation_status text NOT NULL CHECK(articulation_status IN('PASS','FAIL','BLOCKED')),
  articulation_details jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, package_id, id),
  FOREIGN KEY (tenant_id, package_id) REFERENCES plugin_finance_statutory_package(tenant_id, id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS plugin_finance_statutory_worksheet (
  tenant_id uuid NOT NULL,
  package_id uuid NOT NULL,
  id uuid NOT NULL,
  name text NOT NULL CHECK(length(name) BETWEEN 1 AND 256),
  adjustments jsonb NOT NULL DEFAULT '[]'::jsonb,
  before_digest text NOT NULL CHECK(before_digest ~ '^[a-f0-9]{64}$'),
  after_digest text NOT NULL CHECK(after_digest ~ '^[a-f0-9]{64}$'),
  reason text NOT NULL CHECK(length(reason) BETWEEN 1 AND 1000),
  status text NOT NULL DEFAULT 'PENDING' CHECK(status IN('PENDING','APPROVED','REJECTED')),
  preparer jsonb NOT NULL,
  approver jsonb,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, package_id, id),
  FOREIGN KEY (tenant_id, package_id) REFERENCES plugin_finance_statutory_package(tenant_id, id) ON DELETE CASCADE,
  CHECK (approver IS NULL OR approver->>'personId' <> preparer->>'personId')
);

CREATE TABLE IF NOT EXISTS plugin_finance_statutory_notes (
  tenant_id uuid NOT NULL,
  package_id uuid NOT NULL,
  id uuid NOT NULL,
  sections jsonb NOT NULL,
  mandatory_complete boolean NOT NULL DEFAULT false,
  missing_sections jsonb NOT NULL DEFAULT '[]'::jsonb,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, package_id, id),
  FOREIGN KEY (tenant_id, package_id) REFERENCES plugin_finance_statutory_package(tenant_id, id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS plugin_finance_statutory_teaching_guide (
  tenant_id uuid NOT NULL,
  id uuid NOT NULL,
  guide_code text NOT NULL CHECK(length(guide_code) BETWEEN 1 AND 64),
  standard text NOT NULL CHECK(standard IN('CAS_2019','SBE_2011')),
  topic text NOT NULL CHECK(topic IN('DOUBLE_ENTRY_RULES','CONTRA_ACCOUNTS','CANONICAL_VOUCHERS')),
  title text NOT NULL CHECK(length(title) BETWEEN 1 AND 256),
  content jsonb NOT NULL,
  examples jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, standard, guide_code)
);

CREATE TABLE IF NOT EXISTS plugin_finance_kpi_snapshot (
  tenant_id uuid NOT NULL,
  legal_entity_id uuid NOT NULL,
  book_id uuid NOT NULL,
  year_id uuid NOT NULL,
  id uuid NOT NULL,
  period_start date NOT NULL,
  period_end date NOT NULL CHECK(period_start <= period_end),
  burn_rate numeric(28,8) NOT NULL,
  runway_months numeric(28,8) NOT NULL,
  rd_intensity numeric(28,12) NOT NULL,
  gross_margin numeric(28,12) NOT NULL,
  dso_days numeric(28,8) NOT NULL,
  dpo_days numeric(28,8) NOT NULL,
  metrics_detail jsonb NOT NULL,
  prepared_by jsonb NOT NULL,
  approved_by jsonb,
  status text NOT NULL DEFAULT 'DRAFT' CHECK(status IN('DRAFT','APPROVED')),
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, id),
  CHECK (approved_by IS NULL OR approved_by->>'personId' <> prepared_by->>'personId')
);

CREATE TABLE IF NOT EXISTS plugin_finance_cash_calendar (
  tenant_id uuid NOT NULL,
  legal_entity_id uuid NOT NULL,
  book_id uuid NOT NULL,
  id uuid NOT NULL,
  projection_date date NOT NULL,
  horizon_days int NOT NULL CHECK(horizon_days IN(30, 60, 90)),
  opening_cash_balance numeric(28,8) NOT NULL,
  safe_buffer_amount numeric(28,8) NOT NULL,
  daily_forecasts jsonb NOT NULL,
  min_projected_balance numeric(28,8) NOT NULL,
  buffer_breached boolean NOT NULL DEFAULT false,
  status text NOT NULL DEFAULT 'DRAFT' CHECK(status IN('DRAFT','PUBLISHED')),
  publisher jsonb,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, id),
  CHECK (status <> 'PUBLISHED' OR publisher IS NOT NULL)
);

CREATE TABLE IF NOT EXISTS plugin_finance_consolidation_scope (
  tenant_id uuid NOT NULL,
  id uuid NOT NULL,
  scope_code text NOT NULL CHECK(length(scope_code) BETWEEN 1 AND 64),
  parent_entity_id uuid NOT NULL,
  scope_name text NOT NULL CHECK(length(scope_name) BETWEEN 1 AND 256),
  members jsonb NOT NULL,
  version bigint NOT NULL DEFAULT 1 CHECK(version > 0),
  status text NOT NULL DEFAULT 'ACTIVE' CHECK(status IN('ACTIVE','ARCHIVED')),
  created_by jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, scope_code)
);

CREATE TABLE IF NOT EXISTS plugin_finance_consolidation_worksheet (
  tenant_id uuid NOT NULL,
  scope_id uuid NOT NULL,
  id uuid NOT NULL,
  period_start date NOT NULL,
  period_end date NOT NULL CHECK(period_start <= period_end),
  is_management_rollup boolean NOT NULL DEFAULT true,
  rollup_data jsonb NOT NULL,
  elimination_entries jsonb NOT NULL DEFAULT '[]'::jsonb,
  consolidated_sheets jsonb NOT NULL,
  minority_interests jsonb NOT NULL,
  status text NOT NULL DEFAULT 'DRAFT' CHECK(status IN('DRAFT','REVIEWED','APPROVED')),
  preparer jsonb NOT NULL,
  approver jsonb,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id, scope_id) REFERENCES plugin_finance_consolidation_scope(tenant_id, id),
  CHECK (approver IS NULL OR approver->>'personId' <> preparer->>'personId')
);
