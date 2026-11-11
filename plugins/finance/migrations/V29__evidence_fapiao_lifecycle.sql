-- ============================================================================
-- Candidate Migration: W04 Lane (Evidence & Fapiao Lifecycle)
-- Scope: EV01, EV02, EV03, EV04, FP01, FP02, FP03
-- Preserves V1-V25 published history. Bounded candidate for isolated QA.
-- ============================================================================

-- 1. EV01: Electronic Original Classification & Completeness State Machine
CREATE TABLE IF NOT EXISTS plugin_finance_evidence_completeness (
  tenant_id uuid NOT NULL,
  legal_entity_id uuid NOT NULL,
  id uuid NOT NULL,
  source_original_id uuid NOT NULL,
  classification text NOT NULL CHECK(classification IN(
    'EINVOICE_XML', 'EINVOICE_OFD', 'EINVOICE_PDF',
    'CONTRACT_SCAN', 'BANK_SLIP', 'ACCEPTANCE_NOTE', 'OTHER_PAPER_SCAN'
  )),
  completeness_state text NOT NULL DEFAULT 'RECEIVED' CHECK(completeness_state IN(
    'RECEIVED', 'PENDING_VERIFICATION', 'PENDING_COMPLETION', 'USABLE', 'REPLACED'
  )),
  is_complete boolean NOT NULL DEFAULT false,
  missing_reasons jsonb NOT NULL DEFAULT '[]'::jsonb,
  paper_storage_location text,
  metadata_index jsonb NOT NULL DEFAULT '{}'::jsonb,
  version bigint NOT NULL DEFAULT 1 CHECK(version > 0),
  predecessor_id uuid,
  created_by jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, legal_entity_id, id),
  UNIQUE (tenant_id, legal_entity_id, source_original_id, version),
  FOREIGN KEY (tenant_id, legal_entity_id, predecessor_id)
    REFERENCES plugin_finance_evidence_completeness(tenant_id, legal_entity_id, id) DEFERRABLE INITIALLY DEFERRED
);

-- 2. EV02: Structured Verification, OCR Cross-Check & Review Queue
CREATE TABLE IF NOT EXISTS plugin_finance_evidence_verification (
  tenant_id uuid NOT NULL,
  legal_entity_id uuid NOT NULL,
  id uuid NOT NULL,
  evidence_id uuid NOT NULL,
  verification_method text NOT NULL CHECK(verification_method IN(
    'LOCAL_STRUCTURAL_AND_DIGEST_CHECK', 'OCR_FIELD_CROSS_CHECK', 'MANUAL_INSPECTION'
  )),
  structural_digest text NOT NULL CHECK(structural_digest ~ '^[a-f0-9]{64}$'),
  ocr_digest text CHECK(ocr_digest IS NULL OR ocr_digest ~ '^[a-f0-9]{64}$'),
  comparison_status text NOT NULL CHECK(comparison_status IN(
    'PARSED', 'VERIFIED_MATCH', 'MISMATCH_MANUAL_REVIEW', 'FAILED'
  )),
  field_discrepancies jsonb NOT NULL DEFAULT '{}'::jsonb,
  tax_bureau_verification text NOT NULL DEFAULT 'NOT_VERIFIED' CHECK(tax_bureau_verification = 'NOT_VERIFIED'),
  verifier text NOT NULL,
  verified_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  review_notes text,
  PRIMARY KEY (tenant_id, legal_entity_id, id),
  FOREIGN KEY (tenant_id, legal_entity_id, evidence_id)
    REFERENCES plugin_finance_evidence_completeness(tenant_id, legal_entity_id, id)
);

-- 3. EV03: Extended Quota Buckets (Settlement, Tax Deduction, Reimbursement)
CREATE TABLE IF NOT EXISTS plugin_finance_evidence_extended_quota (
  tenant_id uuid NOT NULL,
  legal_entity_id uuid NOT NULL,
  id uuid NOT NULL,
  evidence_id uuid NOT NULL,
  bucket_type text NOT NULL CHECK(bucket_type IN(
    'SETTLEMENT_QUOTA', 'TAX_DEDUCTION_QUOTA', 'REIMBURSEMENT_QUOTA'
  )),
  currency char(3) NOT NULL DEFAULT 'CNY',
  capacity numeric(28,8) NOT NULL CHECK(capacity >= 0 AND capacity = round(capacity, 2)),
  reserved_amount numeric(28,8) NOT NULL DEFAULT 0 CHECK(reserved_amount >= 0 AND reserved_amount = round(reserved_amount, 2)),
  committed_amount numeric(28,8) NOT NULL DEFAULT 0 CHECK(committed_amount >= 0 AND committed_amount = round(committed_amount, 2)),
  released_amount numeric(28,8) NOT NULL DEFAULT 0 CHECK(released_amount >= 0 AND released_amount = round(released_amount, 2)),
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, legal_entity_id, id),
  UNIQUE (tenant_id, legal_entity_id, evidence_id, bucket_type),
  FOREIGN KEY (tenant_id, legal_entity_id, evidence_id)
    REFERENCES plugin_finance_evidence_completeness(tenant_id, legal_entity_id, id),
  CHECK (reserved_amount + committed_amount <= capacity)
);

CREATE TABLE IF NOT EXISTS plugin_finance_evidence_extended_quota_log (
  tenant_id uuid NOT NULL,
  legal_entity_id uuid NOT NULL,
  id uuid NOT NULL,
  quota_id uuid NOT NULL,
  operation_type text NOT NULL CHECK(operation_type IN('RESERVE', 'COMMIT', 'RELEASE')),
  amount numeric(28,8) NOT NULL CHECK(amount > 0 AND amount = round(amount, 2)),
  business_chain_id uuid NOT NULL,
  operator jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, legal_entity_id, id),
  FOREIGN KEY (tenant_id, legal_entity_id, quota_id)
    REFERENCES plugin_finance_evidence_extended_quota(tenant_id, legal_entity_id, id)
);

-- 4. EV04: Business Evidence Graph (Nodes & Edges)
CREATE TABLE IF NOT EXISTS plugin_finance_evidence_graph_node (
  tenant_id uuid NOT NULL,
  legal_entity_id uuid NOT NULL,
  id uuid NOT NULL,
  node_type text NOT NULL CHECK(node_type IN(
    'CONTRACT', 'PURCHASE_ORDER', 'ACCEPTANCE_NOTE', 'INVOICE', 'BANK_SLIP', 'JOURNAL_ENTRY'
  )),
  business_ref_id text NOT NULL,
  node_label text NOT NULL,
  metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, legal_entity_id, id),
  UNIQUE (tenant_id, legal_entity_id, node_type, business_ref_id)
);

CREATE TABLE IF NOT EXISTS plugin_finance_evidence_graph_edge (
  tenant_id uuid NOT NULL,
  legal_entity_id uuid NOT NULL,
  id uuid NOT NULL,
  source_node_id uuid NOT NULL,
  target_node_id uuid NOT NULL,
  relationship_type text NOT NULL CHECK(relationship_type IN(
    'FULFILLS', 'ACCEPTS', 'BILLED_BY', 'PAID_BY', 'POSTED_BY'
  )),
  amount numeric(28,8) CHECK(amount IS NULL OR (amount >= 0 AND amount = round(amount, 2))),
  metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, legal_entity_id, id),
  UNIQUE (tenant_id, legal_entity_id, source_node_id, target_node_id, relationship_type),
  FOREIGN KEY (tenant_id, legal_entity_id, source_node_id)
    REFERENCES plugin_finance_evidence_graph_node(tenant_id, legal_entity_id, id),
  FOREIGN KEY (tenant_id, legal_entity_id, target_node_id)
    REFERENCES plugin_finance_evidence_graph_node(tenant_id, legal_entity_id, id),
  CHECK (source_node_id <> target_node_id)
);

CREATE TABLE IF NOT EXISTS plugin_finance_evidence_exception (
  tenant_id uuid NOT NULL,
  legal_entity_id uuid NOT NULL,
  id uuid NOT NULL,
  business_chain_id uuid NOT NULL,
  exception_type text NOT NULL CHECK(exception_type IN(
    'MISSING_INVOICE_PROVISIONAL_ESTIMATE', 'MISSING_ACCEPTANCE_NOTE',
    'MISSING_CONTRACT', 'OCR_TAX_MISMATCH', 'TAX_DEDUCTION_PENDING_AUDIT'
  )),
  description text NOT NULL CHECK(length(description) BETWEEN 1 AND 1000),
  provisional_amount numeric(28,8) NOT NULL DEFAULT 0 CHECK(provisional_amount >= 0 AND provisional_amount = round(provisional_amount, 2)),
  responsible_person_id text NOT NULL,
  responsible_person_name text NOT NULL,
  due_date date NOT NULL,
  status text NOT NULL DEFAULT 'REGISTERED' CHECK(status IN(
    'REGISTERED', 'RECTIFYING', 'UNDER_REVIEW', 'APPROVED_EXCEPTION', 'CLOSED'
  )),
  approval_reason text,
  approved_by jsonb,
  approved_at timestamptz,
  created_by jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, legal_entity_id, id),
  CHECK ((status = 'APPROVED_EXCEPTION') = (approved_by IS NOT NULL AND approved_at IS NOT NULL AND approval_reason IS NOT NULL)),
  CHECK (approved_by IS NULL OR (approved_by->>'personId' <> responsible_person_id))
);

-- 5. FP01: Input & Output Fapiao Pool (进销项发票池)
CREATE TABLE IF NOT EXISTS plugin_finance_fapiao_pool (
  tenant_id uuid NOT NULL,
  legal_entity_id uuid NOT NULL,
  id uuid NOT NULL,
  direction text NOT NULL CHECK(direction IN('INPUT', 'OUTPUT')),
  invoice_code text NOT NULL DEFAULT '',
  fapiao_number text NOT NULL CHECK(length(fapiao_number) BETWEEN 8 AND 32),
  fapiao_type text NOT NULL CHECK(fapiao_type IN(
    'SPECIAL_VAT', 'GENERAL_VAT', 'ELECTRONIC_SPECIAL', 'ELECTRONIC_GENERAL', 'TOLL_INVOICE'
  )),
  issue_date date NOT NULL,
  buyer_tax_no text NOT NULL CHECK(length(buyer_tax_no) BETWEEN 15 AND 20),
  buyer_name text NOT NULL,
  seller_tax_no text NOT NULL CHECK(length(seller_tax_no) BETWEEN 15 AND 20),
  seller_name text NOT NULL,
  untaxed_amount numeric(28,8) NOT NULL CHECK(untaxed_amount = round(untaxed_amount, 2)),
  tax_rate numeric(18,12) NOT NULL CHECK(tax_rate >= 0),
  tax_amount numeric(28,8) NOT NULL CHECK(tax_amount = round(tax_amount, 2)),
  total_amount numeric(28,8) NOT NULL CHECK(total_amount = round(total_amount, 2)),
  original_document_id uuid,
  tax_bureau_verification text NOT NULL DEFAULT 'NOT_VERIFIED' CHECK(tax_bureau_verification = 'NOT_VERIFIED'),
  lifecycle_status text NOT NULL DEFAULT 'UNVERIFIED_LOCAL' CHECK(lifecycle_status IN(
    'UNVERIFIED_LOCAL', 'MATCHED', 'CERTIFIED_DECLARED', 'TRANSFERRED_OUT', 'VOIDED', 'RED_OFFSET'
  )),
  ledger_journal_id uuid,
  tax_worksheet_id uuid,
  reconciliation_status text NOT NULL DEFAULT 'UNRECONCILED' CHECK(reconciliation_status IN(
    'UNRECONCILED', 'RECONCILED', 'DISCREPANCY'
  )),
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, legal_entity_id, id),
  UNIQUE (tenant_id, legal_entity_id, invoice_code, fapiao_number),
  CHECK (untaxed_amount + tax_amount = total_amount)
);

-- 6. FP02: Output Fapiao Issuance Application (开票申请与渠道接入)
CREATE TABLE IF NOT EXISTS plugin_finance_fapiao_issuance_app (
  tenant_id uuid NOT NULL,
  legal_entity_id uuid NOT NULL,
  id uuid NOT NULL,
  application_number text NOT NULL CHECK(length(application_number) BETWEEN 1 AND 64),
  applicant jsonb NOT NULL,
  application_date date NOT NULL,
  contract_id text,
  customer_id text NOT NULL,
  buyer_tax_no text NOT NULL CHECK(length(buyer_tax_no) BETWEEN 15 AND 20),
  buyer_name text NOT NULL,
  buyer_address_phone text,
  buyer_bank_account text,
  buyer_qualification_status text NOT NULL DEFAULT 'PENDING_REVIEW' CHECK(buyer_qualification_status IN(
    'PENDING_REVIEW', 'QUALIFIED', 'DISQUALIFIED'
  )),
  qualification_notes text,
  reviewer jsonb,
  reviewed_at timestamptz,
  untaxed_amount numeric(28,8) NOT NULL CHECK(untaxed_amount > 0 AND untaxed_amount = round(untaxed_amount, 2)),
  tax_amount numeric(28,8) NOT NULL CHECK(tax_amount >= 0 AND tax_amount = round(tax_amount, 2)),
  total_amount numeric(28,8) NOT NULL CHECK(total_amount > 0 AND total_amount = round(total_amount, 2)),
  items jsonb NOT NULL DEFAULT '[]'::jsonb,
  status text NOT NULL DEFAULT 'DRAFT' CHECK(status IN(
    'DRAFT', 'SUBMITTED', 'APPROVED', 'WAITING_CHANNEL', 'ISSUED_MANUAL_CONFIRMED', 'REJECTED', 'CANCELLED'
  )),
  external_tax_channel text NOT NULL DEFAULT 'NOT_VERIFIED' CHECK(external_tax_channel = 'NOT_VERIFIED'),
  issued_fapiao_id uuid,
  manual_confirmation_notes text,
  confirmed_by jsonb,
  confirmed_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, legal_entity_id, id),
  UNIQUE (tenant_id, legal_entity_id, application_number),
  FOREIGN KEY (tenant_id, legal_entity_id, issued_fapiao_id)
    REFERENCES plugin_finance_fapiao_pool(tenant_id, legal_entity_id, id) DEFERRABLE INITIALLY DEFERRED,
  CHECK (untaxed_amount + tax_amount = total_amount),
  CHECK ((status = 'ISSUED_MANUAL_CONFIRMED') = (issued_fapiao_id IS NOT NULL AND confirmed_by IS NOT NULL AND confirmed_at IS NOT NULL))
);

-- 7. FP03: Red-Letter Confirmation Notice (红字信息确认单) & Tax/Accounting Linkage
CREATE TABLE IF NOT EXISTS plugin_finance_fapiao_red_notice (
  tenant_id uuid NOT NULL,
  legal_entity_id uuid NOT NULL,
  id uuid NOT NULL,
  red_notice_number text NOT NULL CHECK(length(red_notice_number) BETWEEN 10 AND 32),
  original_fapiao_id uuid NOT NULL,
  direction text NOT NULL CHECK(direction IN('OUTPUT_RED', 'INPUT_RED')),
  initiator text NOT NULL CHECK(initiator IN('BUYER_INITIATED', 'SELLER_INITIATED')),
  reason text NOT NULL CHECK(reason IN('SALES_RETURN', 'DISCOUNT_ALLOWANCE', 'SALE_CANCELLED')),
  red_untaxed_amount numeric(28,8) NOT NULL CHECK(red_untaxed_amount < 0 AND red_untaxed_amount = round(red_untaxed_amount, 2)),
  red_tax_amount numeric(28,8) NOT NULL CHECK(red_tax_amount <= 0 AND red_tax_amount = round(red_tax_amount, 2)),
  red_total_amount numeric(28,8) NOT NULL CHECK(red_total_amount < 0 AND red_total_amount = round(red_total_amount, 2)),
  status text NOT NULL DEFAULT 'DRAFT' CHECK(status IN(
    'DRAFT', 'WAITING_CONFIRMATION', 'CONFIRMED', 'EXPIRED', 'CANCELLED', 'RED_INVOICE_ISSUED'
  )),
  registered_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP + interval '72 hours',
  confirmed_at timestamptz,
  credit_fapiao_id uuid,
  accounting_revenue_reversal_amount numeric(28,8) NOT NULL DEFAULT 0 CHECK(accounting_revenue_reversal_amount <= 0 AND accounting_revenue_reversal_amount = round(accounting_revenue_reversal_amount, 2)),
  vat_adjustment_amount numeric(28,8) NOT NULL DEFAULT 0 CHECK(vat_adjustment_amount <= 0 AND vat_adjustment_amount = round(vat_adjustment_amount, 2)),
  revenue_reversal_journal_id uuid,
  vat_adjustment_journal_id uuid,
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, legal_entity_id, id),
  UNIQUE (tenant_id, legal_entity_id, red_notice_number),
  FOREIGN KEY (tenant_id, legal_entity_id, original_fapiao_id)
    REFERENCES plugin_finance_fapiao_pool(tenant_id, legal_entity_id, id),
  FOREIGN KEY (tenant_id, legal_entity_id, credit_fapiao_id)
    REFERENCES plugin_finance_fapiao_pool(tenant_id, legal_entity_id, id) DEFERRABLE INITIALLY DEFERRED,
  CHECK (red_untaxed_amount + red_tax_amount = red_total_amount),
  CHECK (expires_at > registered_at AND expires_at <= registered_at + interval '72 hours 1 minute'),
  CHECK ((status IN ('CONFIRMED', 'RED_INVOICE_ISSUED')) = (confirmed_at IS NOT NULL))
);
