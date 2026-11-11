-- V24: Persistence schemas for Tax Obligations (TAX01/TAX05/TAX06), Archive Exports (ARC01/ARC03), and Report Source Drafts (RP01/RPT02-RPT05).
-- Preserves V1-V23 published bytes and histories unchanged. External tax/bank channels remain NOT_VERIFIED; legalArchiveComplete remains false.

-- 1. Tax Obligations & Manual Genuine-Receipt Reconciliation (TAX01/TAX05/TAX06)
CREATE TABLE plugin_finance_tax_entity_book_auth (
  tenant_id uuid NOT NULL,
  legal_entity_id uuid NOT NULL,
  tax_entity_id uuid NOT NULL,
  book_id uuid NOT NULL,
  source_role text NOT NULL CHECK(source_role IN('STATUTORY_SOURCE','AUTHORIZED_ADJUSTMENT_SOURCE','READ_ONLY_REFERENCE')),
  mapping_version text NOT NULL CHECK(length(mapping_version) BETWEEN 1 AND 128),
  effective_from date NOT NULL,
  prepared_by jsonb NOT NULL,
  approved_by jsonb,
  status text NOT NULL DEFAULT 'PENDING' CHECK(status IN('PENDING','APPROVED','CANCELLED')),
  version bigint NOT NULL DEFAULT 1 CHECK(version IN(1,2)),
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(tenant_id,tax_entity_id,book_id),
  FOREIGN KEY(tenant_id,legal_entity_id,tax_entity_id) REFERENCES plugin_finance_tax_entity_registry(tenant_id,legal_entity_id,id),
  FOREIGN KEY(tenant_id,book_id) REFERENCES plugin_finance_book(tenant_id,id),
  CHECK((status='PENDING' AND version=1 AND approved_by IS NULL)
     OR (status='APPROVED' AND version=2 AND approved_by IS NOT NULL AND approved_by->>'personId'<>prepared_by->>'personId')
     OR (status='CANCELLED' AND version=2 AND approved_by IS NULL))
);

CREATE TABLE plugin_finance_tax_obligation (
  tenant_id uuid NOT NULL,
  legal_entity_id uuid NOT NULL,
  tax_entity_id uuid NOT NULL,
  id uuid NOT NULL,
  authority text NOT NULL CHECK(length(authority) BETWEEN 1 AND 128),
  filing_unit text NOT NULL CHECK(length(filing_unit) BETWEEN 1 AND 128),
  role text NOT NULL CHECK(role IN('SELF','WITHHOLDING','COLLECTION_AGENT')),
  period_start date NOT NULL,
  period_end date NOT NULL CHECK(period_start<=period_end),
  occurrence text NOT NULL CHECK(length(occurrence) BETWEEN 1 AND 128),
  scope_code text NOT NULL CHECK(length(scope_code) BETWEEN 1 AND 128),
  identity_key text NOT NULL CHECK(identity_key~'^[a-f0-9]{64}$'),
  obligation_status text NOT NULL DEFAULT 'REQUIRED' CHECK(obligation_status IN('REQUIRED','CONDITIONAL','NOT_APPLICABLE','PENDING_CONFIRMATION')),
  version bigint NOT NULL DEFAULT 1 CHECK(version>0),
  accepted_declaration_id uuid,
  created_by jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(tenant_id,id),
  UNIQUE(tenant_id,tax_entity_id,id),
  UNIQUE(tenant_id,tax_entity_id,authority,filing_unit,role,period_start,period_end,occurrence,scope_code),
  FOREIGN KEY(tenant_id,legal_entity_id,tax_entity_id) REFERENCES plugin_finance_tax_entity_registry(tenant_id,legal_entity_id,id)
);

CREATE TABLE plugin_finance_tax_declaration (
  tenant_id uuid NOT NULL,
  tax_entity_id uuid NOT NULL,
  obligation_id uuid NOT NULL,
  id uuid NOT NULL,
  revision bigint NOT NULL CHECK(revision>0),
  version bigint NOT NULL DEFAULT 1 CHECK(version>0),
  kind text NOT NULL CHECK(kind IN('INITIAL','CORRECTION','CANCELLATION')),
  predecessor_id uuid,
  state text NOT NULL CHECK(state IN('DRAFT','BLOCKED','READY_FOR_REVIEW','APPROVED','UNKNOWN','ACCEPTED','REJECTED','CANCELLED')),
  form_version uuid NOT NULL,
  rules jsonb NOT NULL,
  adjustments jsonb NOT NULL,
  worksheet_digest text NOT NULL CHECK(worksheet_digest~'^[a-f0-9]{64}$'),
  data_digest text NOT NULL CHECK(data_digest~'^[a-f0-9]{64}$'),
  reason text NOT NULL CHECK(length(reason) BETWEEN 1 AND 1000),
  gaps jsonb NOT NULL DEFAULT '[]'::jsonb,
  preparer jsonb NOT NULL,
  approver jsonb,
  approval_expires_at timestamptz,
  active_command_id uuid,
  receipt_ref text,
  payment_state text NOT NULL DEFAULT 'NOT_ASSESSED' CHECK(payment_state='NOT_ASSESSED'),
  official_capability text NOT NULL DEFAULT 'NOT_VERIFIED' CHECK(official_capability='NOT_VERIFIED'),
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(tenant_id,obligation_id,id),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,obligation_id,revision),
  FOREIGN KEY(tenant_id,tax_entity_id,obligation_id) REFERENCES plugin_finance_tax_obligation(tenant_id,tax_entity_id,id),
  FOREIGN KEY(tenant_id,obligation_id,predecessor_id) REFERENCES plugin_finance_tax_declaration(tenant_id,obligation_id,id),
  CHECK((kind='INITIAL')=(predecessor_id IS NULL)),
  CHECK(approver IS NULL OR approver->>'personId'<>preparer->>'personId')
);
ALTER TABLE plugin_finance_tax_obligation ADD FOREIGN KEY(tenant_id,id,accepted_declaration_id) REFERENCES plugin_finance_tax_declaration(tenant_id,obligation_id,id) DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE plugin_finance_tax_declaration_source (
  tenant_id uuid NOT NULL,
  declaration_id uuid NOT NULL,
  tax_entity_id uuid NOT NULL,
  book_id uuid NOT NULL,
  year_id uuid NOT NULL,
  watermark bigint NOT NULL CHECK(watermark>=0),
  opening_version bigint NOT NULL CHECK(opening_version>=0),
  migration_version bigint CHECK(migration_version IS NULL OR migration_version>0),
  mapping_version text NOT NULL,
  source_digest text NOT NULL CHECK(source_digest~'^[a-f0-9]{64}$'),
  PRIMARY KEY(tenant_id,declaration_id,book_id,year_id),
  FOREIGN KEY(tenant_id,declaration_id) REFERENCES plugin_finance_tax_declaration(tenant_id,id),
  FOREIGN KEY(tenant_id,tax_entity_id,book_id) REFERENCES plugin_finance_tax_entity_book_auth(tenant_id,tax_entity_id,book_id),
  FOREIGN KEY(tenant_id,book_id,year_id) REFERENCES plugin_finance_year(tenant_id,book_id,id)
);

CREATE TABLE plugin_finance_tax_attempt (
  tenant_id uuid NOT NULL,
  obligation_id uuid NOT NULL,
  declaration_id uuid NOT NULL,
  command_id uuid NOT NULL,
  operation text NOT NULL CHECK(operation IN('TAX_SUBMIT','MANUAL_HANDOFF')),
  status text NOT NULL CHECK(status IN('PREPARED','APPROVED','SENT','UNKNOWN','ACCEPTED','REJECTED')),
  data_digest text NOT NULL CHECK(data_digest~'^[a-f0-9]{64}$'),
  actor jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  resolved_at timestamptz,
  PRIMARY KEY(tenant_id,command_id),
  FOREIGN KEY(tenant_id,obligation_id,declaration_id) REFERENCES plugin_finance_tax_declaration(tenant_id,obligation_id,id)
);
CREATE UNIQUE INDEX finance_tax_one_active_attempt ON plugin_finance_tax_attempt(tenant_id,obligation_id) WHERE status IN('PREPARED','APPROVED','SENT','UNKNOWN');

CREATE TABLE plugin_finance_tax_receipt (
  tenant_id uuid NOT NULL,
  id uuid NOT NULL,
  obligation_id uuid NOT NULL,
  declaration_id uuid NOT NULL,
  command_id uuid NOT NULL,
  authority text NOT NULL CHECK(length(authority) BETWEEN 1 AND 128),
  provider_ref text NOT NULL CHECK(length(provider_ref) BETWEEN 1 AND 256),
  outcome text NOT NULL CHECK(outcome IN('ACCEPTED','REJECTED')),
  book_id uuid NOT NULL,
  year_id uuid NOT NULL,
  original_id uuid NOT NULL,
  bytes_digest text NOT NULL CHECK(bytes_digest~'^[a-f0-9]{64}$'),
  data_digest text NOT NULL CHECK(data_digest~'^[a-f0-9]{64}$'),
  uploader_person uuid NOT NULL,
  verifier jsonb NOT NULL,
  evidence_ref text NOT NULL CHECK(length(evidence_ref) BETWEEN 1 AND 500),
  verified_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(tenant_id,id),
  UNIQUE(tenant_id,authority,provider_ref),
  FOREIGN KEY(tenant_id,command_id) REFERENCES plugin_finance_tax_attempt(tenant_id,command_id),
  FOREIGN KEY(tenant_id,book_id,year_id,original_id) REFERENCES plugin_finance_original(tenant_id,book_id,year_id,id),
  CHECK((verifier->>'personId')::uuid<>uploader_person)
);
CREATE TRIGGER finance_tax_receipt_immutable BEFORE UPDATE OR DELETE ON plugin_finance_tax_receipt FOR EACH ROW EXECUTE FUNCTION plugin_finance_immutable();

CREATE TABLE plugin_finance_tax_obligation_request (
  tenant_id uuid NOT NULL,
  actor_id uuid NOT NULL,
  operation text NOT NULL,
  resource_key text NOT NULL,
  idempotency_key text NOT NULL CHECK(length(idempotency_key) BETWEEN 1 AND 128),
  payload_hash text NOT NULL CHECK(payload_hash~'^[a-f0-9]{64}$'),
  response jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(tenant_id,actor_id,operation,resource_key,idempotency_key)
);
CREATE TRIGGER finance_tax_request_immutable BEFORE UPDATE OR DELETE ON plugin_finance_tax_obligation_request FOR EACH ROW EXECUTE FUNCTION plugin_finance_immutable();

-- 2. Archive & Offline Ustar Export Requests (ARC01/ARC03)
CREATE TABLE plugin_finance_archive_preflight (
  tenant_id uuid NOT NULL,
  book_id uuid NOT NULL,
  id uuid NOT NULL,
  purpose text NOT NULL CHECK(purpose IN('ARCHIVE','EXIT','DIAGNOSTIC')),
  year_ids jsonb NOT NULL,
  source_vector jsonb NOT NULL,
  coverage jsonb NOT NULL,
  blockers jsonb NOT NULL,
  counts jsonb NOT NULL,
  totals jsonb NOT NULL,
  proposal_digest text NOT NULL CHECK(proposal_digest~'^[a-f0-9]{64}$'),
  prepared_by jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at timestamptz NOT NULL,
  PRIMARY KEY(tenant_id,book_id,id),
  FOREIGN KEY(tenant_id,book_id) REFERENCES plugin_finance_book(tenant_id,id)
);
CREATE TRIGGER finance_archive_preflight_immutable BEFORE UPDATE OR DELETE ON plugin_finance_archive_preflight FOR EACH ROW EXECUTE FUNCTION plugin_finance_immutable();

CREATE TABLE plugin_finance_archive_request (
  tenant_id uuid NOT NULL,
  book_id uuid NOT NULL,
  id uuid NOT NULL,
  preflight_id uuid NOT NULL,
  purpose text NOT NULL CHECK(purpose IN('ARCHIVE','EXIT','DIAGNOSTIC')),
  proposal_digest text NOT NULL CHECK(proposal_digest~'^[a-f0-9]{64}$'),
  state text NOT NULL CHECK(state IN('REQUESTED','APPROVED','READY','DELIVERED','FAILED','CANCELLED')),
  version bigint NOT NULL DEFAULT 1 CHECK(version>0),
  prepared_by jsonb NOT NULL,
  approved_by jsonb,
  approval_reason text,
  manifest_sha256 text CHECK(manifest_sha256 IS NULL OR manifest_sha256~'^[a-f0-9]{64}$'),
  artifact_sha256 text CHECK(artifact_sha256 IS NULL OR artifact_sha256~'^[a-f0-9]{64}$'),
  artifact_bytes bigint CHECK(artifact_bytes IS NULL OR artifact_bytes>0),
  artifact_expires_at timestamptz,
  failure_code text,
  receipt jsonb,
  legal_archive_complete boolean NOT NULL DEFAULT false CHECK(legal_archive_complete=false),
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(tenant_id,book_id,id),
  FOREIGN KEY(tenant_id,book_id,preflight_id) REFERENCES plugin_finance_archive_preflight(tenant_id,book_id,id),
  CHECK(approved_by IS NULL OR approved_by->>'personId'<>prepared_by->>'personId'),
  CHECK(state NOT IN('READY','DELIVERED') OR (manifest_sha256 IS NOT NULL AND artifact_sha256 IS NOT NULL AND artifact_bytes IS NOT NULL))
);

CREATE TABLE plugin_finance_archive_artifact (
  tenant_id uuid NOT NULL,
  book_id uuid NOT NULL,
  request_id uuid NOT NULL,
  manifest_sha256 text NOT NULL CHECK(manifest_sha256~'^[a-f0-9]{64}$'),
  artifact_sha256 text NOT NULL CHECK(artifact_sha256~'^[a-f0-9]{64}$'),
  size_bytes bigint NOT NULL CHECK(size_bytes>0),
  content bytea NOT NULL,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(tenant_id,book_id,request_id),
  FOREIGN KEY(tenant_id,book_id,request_id) REFERENCES plugin_finance_archive_request(tenant_id,book_id,id),
  CHECK(octet_length(content)=size_bytes AND encode(sha256(content),'hex')=artifact_sha256)
);
CREATE TRIGGER finance_archive_artifact_immutable BEFORE UPDATE OR DELETE ON plugin_finance_archive_artifact FOR EACH ROW EXECUTE FUNCTION plugin_finance_immutable();

CREATE TABLE plugin_finance_archive_idempotency (
  tenant_id uuid NOT NULL,
  book_id uuid NOT NULL,
  actor_id uuid NOT NULL,
  route text NOT NULL,
  idempotency_key text NOT NULL CHECK(length(idempotency_key) BETWEEN 1 AND 128),
  payload_hash text NOT NULL CHECK(payload_hash~'^[a-f0-9]{64}$'),
  response jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(tenant_id,book_id,actor_id,route,idempotency_key)
);
CREATE TRIGGER finance_archive_idempotency_immutable BEFORE UPDATE OR DELETE ON plugin_finance_archive_idempotency FOR EACH ROW EXECUTE FUNCTION plugin_finance_immutable();

-- 3. Report Source Draft Semantic Mappings (RP01/RPT02-RPT05)
CREATE TABLE plugin_finance_report_mapping (
  tenant_id uuid NOT NULL,
  book_id uuid NOT NULL,
  year_id uuid NOT NULL,
  id uuid NOT NULL,
  mapping_version text NOT NULL CHECK(length(mapping_version) BETWEEN 1 AND 128),
  standard text NOT NULL CHECK(standard IN('CAS_2019','SBE_2011')),
  mappings jsonb NOT NULL,
  certified_rows jsonb NOT NULL DEFAULT '[]'::jsonb,
  content_digest text NOT NULL CHECK(content_digest~'^[a-f0-9]{64}$'),
  status text NOT NULL DEFAULT 'PENDING' CHECK(status IN('PENDING','APPROVED','CANCELLED')),
  version bigint NOT NULL DEFAULT 1 CHECK(version IN(1,2)),
  template_approved boolean NOT NULL DEFAULT false CHECK(template_approved=false),
  reason text NOT NULL CHECK(length(reason) BETWEEN 1 AND 1000),
  prepared_by jsonb NOT NULL,
  approved_by jsonb,
  approved_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(tenant_id,book_id,year_id,id),
  UNIQUE(tenant_id,book_id,year_id,mapping_version),
  FOREIGN KEY(tenant_id,book_id,year_id) REFERENCES plugin_finance_year(tenant_id,book_id,id),
  CHECK((status='PENDING' AND version=1 AND approved_by IS NULL)
     OR (status='APPROVED' AND version=2 AND approved_by IS NOT NULL AND approved_by->>'personId'<>prepared_by->>'personId')
     OR (status='CANCELLED' AND version=2 AND approved_by IS NULL))
);
