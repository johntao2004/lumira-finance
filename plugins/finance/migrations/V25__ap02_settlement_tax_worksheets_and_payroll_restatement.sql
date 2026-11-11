-- V25: AP02/EV03/TR01/TR02 payable settlement & payment instruction with manual bank slip verification,
-- TAX02/TAX03/TAX04 bounded tax calculation worksheets, and PAY02 cross-batch payroll restatement plans.
-- Preserves V1-V24 published bytes and histories unchanged.

ALTER TABLE plugin_finance_journal DROP CONSTRAINT plugin_finance_journal_type_check;
ALTER TABLE plugin_finance_journal ADD CONSTRAINT plugin_finance_journal_type_check
 CHECK(type IN(
  'NORMAL','REVERSAL','PROFIT_CLOSE','YEAR_END',
  'AP_EXPENSE','AP_EXPENSE_REVERSAL','AP_PAYMENT','AP_PAYMENT_REVERSAL',
  'AR_REVENUE','AR_RECEIPT','AR_ALLOCATION','AR_REVERSAL',
  'ASSET_ACQUIRE','ASSET_DEPRECIATE','ASSET_DISPOSE','ASSET_REVERSE','ASSET_CORRECT_DEPRECIATION'
 ));

CREATE TABLE plugin_finance_ap_settlement_basis (
 tenant_id uuid NOT NULL,
 legal_entity_id uuid NOT NULL,
 book_id uuid NOT NULL,
 year_id uuid NOT NULL,
 id uuid NOT NULL,
 original_id uuid NOT NULL,
 original_sha256 text NOT NULL CHECK(original_sha256~'^[a-f0-9]{64}$'),
 purpose text NOT NULL DEFAULT 'SETTLEMENT_EVIDENCE' CHECK(purpose='SETTLEMENT_EVIDENCE'),
 supplier_id uuid NOT NULL,
 supplier_revision_id uuid NOT NULL,
 payee_account_label text NOT NULL CHECK(length(payee_account_label) BETWEEN 1 AND 128),
 bank_channel text NOT NULL DEFAULT 'NOT_VERIFIED' CHECK(bank_channel='NOT_VERIFIED'),
 capacity numeric(28,8) NOT NULL CHECK(capacity>0 AND capacity=round(capacity,2)),
 status text NOT NULL DEFAULT 'PENDING' CHECK(status IN('PENDING','APPROVED','REJECTED','CANCELLED')),
 version bigint NOT NULL DEFAULT 1 CHECK(version IN(1,2)),
 prepared_by jsonb NOT NULL,
 approved_by jsonb,
 reason text NOT NULL CHECK(length(reason) BETWEEN 1 AND 1000),
 decision_reason text,
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 decided_at timestamptz,
 PRIMARY KEY(tenant_id,book_id,year_id,id),
 UNIQUE(tenant_id,book_id,year_id,original_id,purpose),
 FOREIGN KEY(tenant_id,legal_entity_id) REFERENCES plugin_finance_entity(tenant_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id) REFERENCES plugin_finance_year(tenant_id,book_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,original_id) REFERENCES plugin_finance_original(tenant_id,book_id,year_id,id),
 FOREIGN KEY(tenant_id,legal_entity_id,supplier_id,supplier_revision_id)
  REFERENCES plugin_finance_counterparty_revision(tenant_id,legal_entity_id,counterparty_id,id),
 CHECK((status='PENDING' AND version=1 AND approved_by IS NULL AND decision_reason IS NULL AND decided_at IS NULL)
    OR (status IN('REJECTED','CANCELLED') AND version=2 AND decision_reason IS NOT NULL AND decided_at IS NOT NULL)
    OR (status='APPROVED' AND version=2 AND approved_by IS NOT NULL AND decision_reason IS NOT NULL AND decided_at IS NOT NULL)),
 CHECK(prepared_by->>'personId' IS NOT NULL AND prepared_by->>'accountId' IS NOT NULL AND prepared_by->>'bindingId' IS NOT NULL),
 CHECK(approved_by IS NULL OR (approved_by->>'personId' IS NOT NULL AND approved_by->>'accountId' IS NOT NULL AND approved_by->>'bindingId' IS NOT NULL AND prepared_by->>'personId'<>approved_by->>'personId'))
);

CREATE TABLE plugin_finance_ap_settlement_order (
 tenant_id uuid NOT NULL,
 legal_entity_id uuid NOT NULL,
 book_id uuid NOT NULL,
 year_id uuid NOT NULL,
 id uuid NOT NULL,
 kind text NOT NULL CHECK(kind IN('PAYMENT','REVERSAL')),
 state text NOT NULL DEFAULT 'DRAFT' CHECK(state IN('DRAFT','SUBMITTED','APPROVED','POSTED','CANCELLED')),
 version bigint NOT NULL DEFAULT 1 CHECK(version>=1),
 open_item_id uuid NOT NULL,
 payable_run_id uuid NOT NULL,
 settlement_basis_id uuid NOT NULL,
 supplier_id uuid NOT NULL,
 supplier_revision_id uuid NOT NULL,
 payable_account_id uuid NOT NULL,
 cash_account_id uuid NOT NULL,
 bank_slip_original_id uuid NOT NULL,
 bank_slip_sha256 text NOT NULL CHECK(bank_slip_sha256~'^[a-f0-9]{64}$'),
 payment_instruction_ref text NOT NULL CHECK(length(payment_instruction_ref) BETWEEN 1 AND 128),
 verification_mode text NOT NULL DEFAULT 'INTERNAL_MANUAL_PAYMENT_SLIP' CHECK(verification_mode='INTERNAL_MANUAL_PAYMENT_SLIP'),
 bank_channel text NOT NULL DEFAULT 'NOT_VERIFIED' CHECK(bank_channel='NOT_VERIFIED'),
 accounting_date date NOT NULL,
 amount numeric(28,8) NOT NULL CHECK(amount>0 AND amount=round(amount,2)),
 summary text NOT NULL CHECK(length(summary) BETWEEN 1 AND 500),
 reason text NOT NULL CHECK(length(reason) BETWEEN 1 AND 1000),
 content_digest text NOT NULL CHECK(content_digest~'^[a-f0-9]{64}$'),
 prepared_by jsonb NOT NULL,
 approved_by jsonb,
 approval_expires_at timestamptz,
 posted_by jsonb,
 journal_id uuid,
 posting_seq bigint,
 reverses_id uuid,
 reversed_by_id uuid,
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(tenant_id,book_id,year_id,id),
 UNIQUE(tenant_id,book_id,year_id,payment_instruction_ref),
 FOREIGN KEY(tenant_id,legal_entity_id) REFERENCES plugin_finance_entity(tenant_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id) REFERENCES plugin_finance_year(tenant_id,book_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,open_item_id) REFERENCES plugin_finance_ap_open_item(tenant_id,book_id,year_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,payable_run_id) REFERENCES plugin_finance_ap_run(tenant_id,book_id,year_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,settlement_basis_id) REFERENCES plugin_finance_ap_settlement_basis(tenant_id,book_id,year_id,id),
 FOREIGN KEY(tenant_id,legal_entity_id,supplier_id,supplier_revision_id)
  REFERENCES plugin_finance_counterparty_revision(tenant_id,legal_entity_id,counterparty_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,payable_account_id) REFERENCES plugin_finance_account(tenant_id,book_id,year_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,cash_account_id) REFERENCES plugin_finance_account(tenant_id,book_id,year_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,bank_slip_original_id) REFERENCES plugin_finance_original(tenant_id,book_id,year_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,journal_id) REFERENCES plugin_finance_journal(tenant_id,book_id,year_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,reverses_id) REFERENCES plugin_finance_ap_settlement_order(tenant_id,book_id,year_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,reversed_by_id) REFERENCES plugin_finance_ap_settlement_order(tenant_id,book_id,year_id,id),
 CHECK((kind='REVERSAL')=(reverses_id IS NOT NULL)),
 CHECK((state='POSTED')=(journal_id IS NOT NULL AND posting_seq IS NOT NULL AND posted_by IS NOT NULL)),
 CHECK(prepared_by->>'personId' IS NOT NULL AND prepared_by->>'accountId' IS NOT NULL AND prepared_by->>'bindingId' IS NOT NULL),
 CHECK(approved_by IS NULL OR (approved_by->>'personId' IS NOT NULL AND approved_by->>'accountId' IS NOT NULL AND approved_by->>'bindingId' IS NOT NULL AND prepared_by->>'personId'<>approved_by->>'personId'))
);
CREATE INDEX finance_ap_settlement_open_item_idx
 ON plugin_finance_ap_settlement_order(tenant_id,book_id,year_id,open_item_id,state);

CREATE FUNCTION plugin_finance_ap_open_item_settlement_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target_item_id uuid;
BEGIN
 IF TG_TABLE_NAME='plugin_finance_ap_reverse_effect' THEN
  target_item_id:=NEW.open_item_id;
 ELSE
  target_item_id:=NEW.id;
 END IF;
 IF EXISTS(
  SELECT 1 FROM plugin_finance_ap_settlement_order so
  WHERE (so.tenant_id,so.book_id,so.year_id,so.open_item_id)=(NEW.tenant_id,NEW.book_id,NEW.year_id,target_item_id)
    AND so.kind='PAYMENT'
    AND (so.state IN('DRAFT','SUBMITTED','APPROVED') OR (so.state='POSTED' AND so.reversed_by_id IS NULL))
 ) THEN
  RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_DEPENDENCY_PRESENT';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER finance_ap_reverse_effect_settlement_guard
 BEFORE INSERT ON plugin_finance_ap_reverse_effect
 FOR EACH ROW EXECUTE FUNCTION plugin_finance_ap_open_item_settlement_guard();
CREATE TRIGGER finance_ap_open_item_settlement_guard
 BEFORE UPDATE ON plugin_finance_ap_open_item
 FOR EACH ROW EXECUTE FUNCTION plugin_finance_ap_open_item_settlement_guard();

CREATE OR REPLACE FUNCTION plugin_finance_v4_typed_linked(tab text, q jsonb) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT q IS NOT NULL AND (
  (tab='plugin_finance_journal' AND q->>'type' IN(
   'AP_PAYMENT','AP_PAYMENT_REVERSAL',
   'AR_REVENUE','AR_RECEIPT','AR_ALLOCATION','AR_REVERSAL',
   'ASSET_ACQUIRE','ASSET_DEPRECIATE','ASSET_DISPOSE','ASSET_REVERSE','ASSET_CORRECT_DEPRECIATION'))
  OR (tab<>'plugin_finance_journal' AND (q->>'journal_id') IS NOT NULL AND EXISTS(
   SELECT 1 FROM plugin_finance_journal j
   WHERE (j.tenant_id,j.book_id,j.year_id,j.id)=((q->>'tenant_id')::uuid,(q->>'book_id')::uuid,(q->>'year_id')::uuid,(q->>'journal_id')::uuid)
     AND j.type IN(
      'AP_PAYMENT','AP_PAYMENT_REVERSAL',
      'AR_REVENUE','AR_RECEIPT','AR_ALLOCATION','AR_REVERSAL',
      'ASSET_ACQUIRE','ASSET_DEPRECIATE','ASSET_DISPOSE','ASSET_REVERSE','ASSET_CORRECT_DEPRECIATION')))
 )
$$;

CREATE OR REPLACE FUNCTION plugin_finance_v4_typed_assert(t uuid, b uuid, y uuid, jid uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE h plugin_finance_journal%ROWTYPE;
BEGIN
 SELECT * INTO h FROM plugin_finance_journal WHERE(tenant_id,book_id,year_id,id)=(t,b,y,jid);
 IF NOT FOUND THEN RETURN; END IF;
 IF h.status<>'POSTED' OR h.version<>3
   OR NOT EXISTS(SELECT 1 FROM plugin_finance_posting p WHERE(p.tenant_id,p.book_id,p.year_id,p.journal_id)=(t,b,y,jid) AND p.journal_version=2 AND p.posting_seq IS NOT NULL) THEN
  RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='V4_TYPED_UNPOSTED_JOURNAL_FORBIDDEN';
 END IF;
 IF h.type LIKE 'AR_%' THEN
  IF NOT EXISTS(
   SELECT 1 FROM plugin_finance_ar_object o
   JOIN plugin_finance_ar_posted ap ON (ap.tenant_id,ap.book_id,ap.year_id,ap.object_id,ap.journal_id)=(o.tenant_id,o.book_id,o.year_id,o.id,o.journal_id)
   WHERE (o.tenant_id,o.book_id,o.year_id,o.journal_id)=(t,b,y,jid) AND o.state='POSTED') THEN
   RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AR_POSTING_PROOF_MISSING';
  END IF;
 ELSIF h.type IN('AP_PAYMENT','AP_PAYMENT_REVERSAL') THEN
  IF NOT EXISTS(
   SELECT 1 FROM plugin_finance_ap_settlement_order so
   WHERE (so.tenant_id,so.book_id,so.year_id,so.journal_id)=(t,b,y,jid) AND so.state='POSTED') THEN
   RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_SETTLEMENT_POSTING_PROOF_MISSING';
  END IF;
 ELSE
  IF NOT EXISTS(
   SELECT 1 FROM plugin_finance_asset_plan pl
   WHERE (pl.tenant_id,pl.book_id,pl.year_id,pl.journal_id)=(t,b,y,jid) AND pl.status='POSTED') THEN
   RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='ASSET_POSTING_PROOF_MISSING';
  END IF;
 END IF;
END $$;

CREATE OR REPLACE FUNCTION plugin_finance_v4_typed_watermark(t uuid, b uuid, y uuid, new_seq bigint, old_seq bigint) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT new_seq=old_seq+1 AND EXISTS(
  SELECT 1 FROM plugin_finance_posting p
  JOIN plugin_finance_journal j ON (j.tenant_id,j.book_id,j.year_id,j.id)=(p.tenant_id,p.book_id,p.year_id,p.journal_id)
  WHERE (p.tenant_id,p.book_id,p.year_id,p.posting_seq)=(t,b,y,new_seq)
    AND j.status='POSTED'
    AND j.type IN(
     'AP_PAYMENT','AP_PAYMENT_REVERSAL',
     'AR_REVENUE','AR_RECEIPT','AR_ALLOCATION','AR_REVERSAL',
     'ASSET_ACQUIRE','ASSET_DEPRECIATE','ASSET_DISPOSE','ASSET_REVERSE','ASSET_CORRECT_DEPRECIATION')
 )
$$;

CREATE TABLE plugin_finance_tax_worksheet (
 tenant_id uuid NOT NULL,
 legal_entity_id uuid NOT NULL,
 book_id uuid NOT NULL,
 year_id uuid NOT NULL,
 tax_entity_id uuid NOT NULL,
 id uuid NOT NULL,
 worksheet_code text NOT NULL CHECK(length(worksheet_code) BETWEEN 1 AND 128),
 kind text NOT NULL CHECK(kind IN('VAT_SMALL','VAT_GENERAL_SURCHARGE','CIT_ADJUSTMENT_AND_PREPAY')),
 period_start date NOT NULL,
 period_end date NOT NULL CHECK(period_end>=period_start),
 rule_version text NOT NULL CHECK(length(rule_version) BETWEEN 1 AND 128),
 basis_original_id uuid NOT NULL,
 basis_original_sha256 text NOT NULL CHECK(basis_original_sha256~'^[a-f0-9]{64}$'),
 inputs jsonb NOT NULL,
 outputs jsonb NOT NULL,
 worksheet_digest text NOT NULL CHECK(worksheet_digest~'^[a-f0-9]{64}$'),
 status text NOT NULL DEFAULT 'PENDING' CHECK(status IN('PENDING','APPROVED','REJECTED','CANCELLED')),
 version bigint NOT NULL DEFAULT 1 CHECK(version IN(1,2)),
 prepared_by jsonb NOT NULL,
 approved_by jsonb,
 reason text NOT NULL CHECK(length(reason) BETWEEN 1 AND 1000),
 decision_reason text,
 official_capability text NOT NULL DEFAULT 'NOT_VERIFIED' CHECK(official_capability='NOT_VERIFIED'),
 payment_state text NOT NULL DEFAULT 'NOT_ASSESSED' CHECK(payment_state='NOT_ASSESSED'),
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 decided_at timestamptz,
 PRIMARY KEY(tenant_id,book_id,year_id,id),
 UNIQUE(tenant_id,book_id,year_id,worksheet_code),
 FOREIGN KEY(tenant_id,legal_entity_id) REFERENCES plugin_finance_entity(tenant_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id) REFERENCES plugin_finance_year(tenant_id,book_id,id),
 FOREIGN KEY(tenant_id,legal_entity_id,tax_entity_id) REFERENCES plugin_finance_tax_entity_registry(tenant_id,legal_entity_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,basis_original_id) REFERENCES plugin_finance_original(tenant_id,book_id,year_id,id),
 CHECK((status='PENDING' AND version=1 AND approved_by IS NULL AND decision_reason IS NULL AND decided_at IS NULL)
    OR (status IN('REJECTED','CANCELLED') AND version=2 AND decision_reason IS NOT NULL AND decided_at IS NOT NULL)
    OR (status='APPROVED' AND version=2 AND approved_by IS NOT NULL AND decision_reason IS NOT NULL AND decided_at IS NOT NULL)),
 CHECK(prepared_by->>'personId' IS NOT NULL AND prepared_by->>'accountId' IS NOT NULL AND prepared_by->>'bindingId' IS NOT NULL),
 CHECK(approved_by IS NULL OR (approved_by->>'personId' IS NOT NULL AND approved_by->>'accountId' IS NOT NULL AND approved_by->>'bindingId' IS NOT NULL AND prepared_by->>'personId'<>approved_by->>'personId'))
);

CREATE TABLE plugin_finance_payroll_restatement_plan (
 tenant_id uuid NOT NULL,
 tax_entity_id uuid NOT NULL,
 tax_year integer NOT NULL CHECK(tax_year BETWEEN 2000 AND 2100),
 book_id uuid NOT NULL,
 year_id uuid NOT NULL,
 id uuid NOT NULL,
 target_batch_id uuid NOT NULL,
 rule_pack_id uuid NOT NULL,
 plan_payload jsonb NOT NULL,
 plan_digest text NOT NULL CHECK(plan_digest~'^[a-f0-9]{64}$'),
 total_tax_delta numeric(20,2) NOT NULL,
 total_overwithheld numeric(20,2) NOT NULL CHECK(total_overwithheld>=0),
 requires_prior_amendment boolean NOT NULL,
 status text NOT NULL DEFAULT 'PENDING' CHECK(status IN('PENDING','APPROVED','REJECTED')),
 version bigint NOT NULL DEFAULT 1 CHECK(version IN(1,2)),
 prepared_by_account uuid NOT NULL,
 prepared_by_person uuid NOT NULL,
 prepared_by_binding uuid NOT NULL,
 approved_by_account uuid,
 approved_by_person uuid,
 approved_by_binding uuid,
 reason text NOT NULL CHECK(length(reason) BETWEEN 1 AND 1000),
 decision_reason text,
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 decided_at timestamptz,
 PRIMARY KEY(tenant_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id) REFERENCES plugin_finance_year(tenant_id,book_id,id),
 FOREIGN KEY(tenant_id,tax_entity_id,tax_year)
  REFERENCES plugin_finance_payroll_year(tenant_id,tax_entity_id,tax_year),
 FOREIGN KEY(tenant_id,rule_pack_id)
  REFERENCES plugin_finance_payroll_rule_pack(tenant_id,id),
 FOREIGN KEY(tenant_id,prepared_by_binding,prepared_by_account,prepared_by_person)
  REFERENCES plugin_finance_person_binding(tenant_id,id,account_id,person_id),
 FOREIGN KEY(tenant_id,approved_by_binding,approved_by_account,approved_by_person)
  REFERENCES plugin_finance_person_binding(tenant_id,id,account_id,person_id),
 CHECK((status='PENDING' AND version=1 AND approved_by_person IS NULL AND decision_reason IS NULL AND decided_at IS NULL)
    OR (status IN('APPROVED','REJECTED') AND version=2 AND approved_by_person IS NOT NULL AND decision_reason IS NOT NULL AND decided_at IS NOT NULL)),
 CHECK(approved_by_person IS NULL OR approved_by_person<>prepared_by_person)
);
ALTER TABLE plugin_finance_payroll_restatement_plan ENABLE ROW LEVEL SECURITY;
ALTER TABLE plugin_finance_payroll_restatement_plan FORCE ROW LEVEL SECURITY;
CREATE POLICY payroll_tenant ON plugin_finance_payroll_restatement_plan
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);
