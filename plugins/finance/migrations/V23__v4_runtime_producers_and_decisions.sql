-- V23: Native AR/Asset journal producer dispatch, AR accounting/tax decisions,
-- Asset ACCOUNTING_CONFIRMATION source quotas, and Payroll verified fact evidence.
-- Preserves V1-V22 published bytes and histories unchanged.

ALTER TABLE plugin_finance_journal DROP CONSTRAINT plugin_finance_journal_type_check;
ALTER TABLE plugin_finance_journal ADD CONSTRAINT plugin_finance_journal_type_check
 CHECK(type IN(
  'NORMAL','REVERSAL','PROFIT_CLOSE','YEAR_END',
  'AP_EXPENSE','AP_EXPENSE_REVERSAL',
  'AR_REVENUE','AR_RECEIPT','AR_ALLOCATION','AR_REVERSAL',
  'ASSET_ACQUIRE','ASSET_DEPRECIATE','ASSET_DISPOSE','ASSET_REVERSE','ASSET_CORRECT_DEPRECIATION'
 ));

CREATE TABLE plugin_finance_v4_governance_request (
 actor_id uuid NOT NULL,
 request_key text NOT NULL CHECK(length(request_key) BETWEEN 1 AND 128),
 tenant_id uuid NOT NULL,
 route text NOT NULL,
 payload_hash text NOT NULL CHECK(payload_hash~'^[a-f0-9]{64}$'),
 response jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(actor_id,request_key)
);
CREATE TRIGGER finance_v4_governance_request_immutable
 BEFORE UPDATE OR DELETE ON plugin_finance_v4_governance_request
 FOR EACH ROW EXECUTE FUNCTION plugin_finance_immutable();

CREATE TABLE plugin_finance_ar_decision (
 tenant_id uuid NOT NULL,
 legal_entity_id uuid NOT NULL,
 book_id uuid NOT NULL,
 year_id uuid NOT NULL,
 id uuid NOT NULL,
 kind text NOT NULL CHECK(kind IN('RECOGNITION','RECEIPT','ALLOCATION','REVERSAL')),
 status text NOT NULL DEFAULT 'PENDING' CHECK(status IN('PENDING','APPROVED','REJECTED','CANCELLED')),
 version bigint NOT NULL DEFAULT 1 CHECK(version IN(1,2)),
 policy_version text NOT NULL CHECK(length(policy_version) BETWEEN 1 AND 80),
 accounting_date date NOT NULL,
 gross numeric(28,8) NOT NULL CHECK(gross>0 AND gross=round(gross,2)),
 net numeric(28,8) NOT NULL CHECK(net>=0 AND net=round(net,2)),
 tax_amount numeric(28,8) NOT NULL CHECK(tax_amount>=0 AND tax_amount=round(tax_amount,2)),
 tax_entity_id uuid,
 tax_entity_revision_id uuid,
 tax_treatment text CHECK(tax_treatment IN('TAXABLE','EXEMPT','ZERO_RATED','OUTSIDE_SCOPE')),
 tax_rule_version text,
 basis_original_id uuid NOT NULL,
 lines jsonb NOT NULL CHECK(jsonb_typeof(lines)='array' AND jsonb_array_length(lines) BETWEEN 2 AND 100),
 content_digest text NOT NULL CHECK(content_digest~'^[a-f0-9]{64}$'),
 prepared_by jsonb NOT NULL,
 approved_by jsonb,
 expires_at timestamptz,
 reason text NOT NULL CHECK(length(reason) BETWEEN 1 AND 1000),
 decision_reason text,
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 decided_at timestamptz,
 PRIMARY KEY(tenant_id,book_id,year_id,id),
 FOREIGN KEY(tenant_id,legal_entity_id) REFERENCES plugin_finance_entity(tenant_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id) REFERENCES plugin_finance_year(tenant_id,book_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,basis_original_id) REFERENCES plugin_finance_original(tenant_id,book_id,year_id,id),
 FOREIGN KEY(tenant_id,legal_entity_id,tax_entity_id,tax_entity_revision_id) REFERENCES plugin_finance_tax_entity_revision(tenant_id,legal_entity_id,tax_entity_id,id),
 CHECK(net+tax_amount=gross),
 CHECK((kind='RECOGNITION')=(tax_entity_id IS NOT NULL AND tax_entity_revision_id IS NOT NULL AND tax_treatment IS NOT NULL AND tax_rule_version IS NOT NULL)),
 CHECK(kind='RECOGNITION' OR tax_amount=0),
 CHECK((status='PENDING' AND version=1 AND approved_by IS NULL AND expires_at IS NULL AND decision_reason IS NULL AND decided_at IS NULL)
    OR (status IN('REJECTED','CANCELLED') AND version=2 AND expires_at IS NULL AND decision_reason IS NOT NULL AND decided_at IS NOT NULL)
    OR (status='APPROVED' AND version=2 AND approved_by IS NOT NULL AND expires_at IS NOT NULL AND decision_reason IS NOT NULL AND decided_at IS NOT NULL)),
 CHECK(prepared_by->>'personId' IS NOT NULL AND prepared_by->>'accountId' IS NOT NULL AND prepared_by->>'bindingId' IS NOT NULL),
 CHECK(approved_by IS NULL OR (approved_by->>'personId' IS NOT NULL AND approved_by->>'accountId' IS NOT NULL AND approved_by->>'bindingId' IS NOT NULL AND prepared_by->>'personId'<>approved_by->>'personId'))
);

CREATE FUNCTION plugin_finance_ar_decision_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'AR_DECISION_IMMUTABLE'; END IF;
 IF TG_OP='INSERT' THEN
  IF NEW.status<>'PENDING' OR NEW.version<>1
    OR NEW.prepared_by IS DISTINCT FROM plugin_finance_journal_person_identity(NEW.tenant_id,(NEW.prepared_by->>'accountId')::uuid,true)
    THEN RAISE EXCEPTION 'AR_DECISION_INVALID'; END IF;
  RETURN NEW;
 END IF;
 IF OLD.status<>'PENDING' OR NEW.version<>2
   OR (to_jsonb(NEW)-ARRAY['status','version','approved_by','expires_at','decision_reason','decided_at']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['status','version','approved_by','expires_at','decision_reason','decided_at'])
   THEN RAISE EXCEPTION 'AR_DECISION_IMMUTABLE'; END IF;
 IF NEW.status='APPROVED' AND NEW.approved_by IS DISTINCT FROM plugin_finance_journal_person_identity(NEW.tenant_id,(NEW.approved_by->>'accountId')::uuid,true)
   THEN RAISE EXCEPTION 'AR_DECISION_IDENTITY_STALE'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER finance_ar_decision_guard
 BEFORE INSERT OR UPDATE OR DELETE ON plugin_finance_ar_decision
 FOR EACH ROW EXECUTE FUNCTION plugin_finance_ar_decision_guard();

CREATE TABLE plugin_finance_ar_effect (
 tenant_id uuid NOT NULL,
 book_id uuid NOT NULL,
 year_id uuid NOT NULL,
 id uuid NOT NULL,
 object_id uuid NOT NULL,
 business_chain_id uuid NOT NULL,
 stable_consumer text NOT NULL CHECK(stable_consumer='finance.ar.v1'),
 native_type text NOT NULL CHECK(native_type IN('AR_REVENUE','AR_RECEIPT','AR_ALLOCATION','AR_REVERSAL')),
 effect_key text NOT NULL CHECK(effect_key~'^[a-f0-9]{64}$'),
 content_digest text NOT NULL CHECK(content_digest~'^[a-f0-9]{64}$'),
 journal_id uuid NOT NULL,
 posting_seq bigint NOT NULL CHECK(posting_seq>0),
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(tenant_id,id),
 UNIQUE(tenant_id,effect_key),
 UNIQUE(tenant_id,id,stable_consumer,effect_key,content_digest),
 UNIQUE(tenant_id,book_id,year_id,object_id),
 FOREIGN KEY(tenant_id,book_id,year_id,object_id) REFERENCES plugin_finance_ar_object(tenant_id,book_id,year_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,journal_id) REFERENCES plugin_finance_posting(tenant_id,book_id,year_id,journal_id)
);
CREATE TRIGGER finance_ar_effect_immutable
 BEFORE UPDATE OR DELETE ON plugin_finance_ar_effect
 FOR EACH ROW EXECUTE FUNCTION plugin_finance_immutable();

CREATE TABLE plugin_finance_ar_effect_receipt (
 tenant_id uuid NOT NULL,
 id uuid NOT NULL,
 effect_id uuid NOT NULL,
 stable_consumer text NOT NULL CHECK(stable_consumer='finance.ar.v1'),
 effect_key text NOT NULL CHECK(effect_key~'^[a-f0-9]{64}$'),
 content_digest text NOT NULL CHECK(content_digest~'^[a-f0-9]{64}$'),
 consumed_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(tenant_id,stable_consumer,effect_key),
 UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,effect_id,stable_consumer,effect_key,content_digest)
  REFERENCES plugin_finance_ar_effect(tenant_id,id,stable_consumer,effect_key,content_digest)
);
CREATE TRIGGER finance_ar_effect_receipt_immutable
 BEFORE UPDATE OR DELETE ON plugin_finance_ar_effect_receipt
 FOR EACH ROW EXECUTE FUNCTION plugin_finance_immutable();

CREATE TABLE plugin_finance_asset_source_quota (
 tenant_id uuid NOT NULL,
 book_id uuid NOT NULL,
 year_id uuid NOT NULL,
 id uuid NOT NULL,
 original_id uuid NOT NULL,
 status text NOT NULL DEFAULT 'PENDING' CHECK(status IN('PENDING','APPROVED','REJECTED','CANCELLED')),
 version bigint NOT NULL DEFAULT 1 CHECK(version>0),
 amount numeric(28,8) NOT NULL CHECK(amount>0 AND amount=round(amount,2)),
 state text NOT NULL DEFAULT 'AVAILABLE' CHECK(state IN('AVAILABLE','RESERVED','CONSUMED')),
 active_plan_id uuid,
 prepared_by jsonb NOT NULL,
 approved_by jsonb,
 reason text NOT NULL CHECK(length(reason) BETWEEN 1 AND 1000),
 decision_reason text,
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 decided_at timestamptz,
 PRIMARY KEY(tenant_id,book_id,year_id,id),
 UNIQUE(tenant_id,book_id,year_id,original_id),
 FOREIGN KEY(tenant_id,book_id,year_id,original_id) REFERENCES plugin_finance_original(tenant_id,book_id,year_id,id),
 FOREIGN KEY(tenant_id,book_id,active_plan_id) REFERENCES plugin_finance_asset_plan(tenant_id,book_id,id),
 CHECK((state='AVAILABLE')=(active_plan_id IS NULL)),
 CHECK(state='AVAILABLE' OR status='APPROVED'),
 CHECK(prepared_by->>'personId' IS NOT NULL AND prepared_by->>'accountId' IS NOT NULL AND prepared_by->>'bindingId' IS NOT NULL),
 CHECK(approved_by IS NULL OR (approved_by->>'personId' IS NOT NULL AND approved_by->>'accountId' IS NOT NULL AND approved_by->>'bindingId' IS NOT NULL AND prepared_by->>'personId'<>approved_by->>'personId'))
);

CREATE FUNCTION plugin_finance_asset_quota_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'ASSET_QUOTA_IMMUTABLE'; END IF;
 IF TG_OP='INSERT' THEN
  IF NEW.status<>'PENDING' OR NEW.version<>1 OR NEW.state<>'AVAILABLE' OR NEW.active_plan_id IS NOT NULL
    OR NEW.prepared_by IS DISTINCT FROM plugin_finance_journal_person_identity(NEW.tenant_id,(NEW.prepared_by->>'accountId')::uuid,true)
    THEN RAISE EXCEPTION 'ASSET_QUOTA_INVALID'; END IF;
  RETURN NEW;
 END IF;
 IF (to_jsonb(NEW)-ARRAY['status','version','state','active_plan_id','approved_by','decision_reason','decided_at'])
    IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['status','version','state','active_plan_id','approved_by','decision_reason','decided_at'])
    OR NEW.version<>OLD.version+1 THEN RAISE EXCEPTION 'ASSET_QUOTA_IMMUTABLE'; END IF;
 IF OLD.status='PENDING' THEN
  IF NEW.status NOT IN('APPROVED','REJECTED','CANCELLED') OR NEW.state<>'AVAILABLE' THEN RAISE EXCEPTION 'ASSET_QUOTA_STATE'; END IF;
  IF NEW.status='APPROVED' AND NEW.approved_by IS DISTINCT FROM plugin_finance_journal_person_identity(NEW.tenant_id,(NEW.approved_by->>'accountId')::uuid,true)
    THEN RAISE EXCEPTION 'ASSET_QUOTA_IDENTITY_STALE'; END IF;
 ELSE
  IF OLD.status<>'APPROVED' OR NEW.status<>'APPROVED' OR NEW.approved_by IS DISTINCT FROM OLD.approved_by THEN RAISE EXCEPTION 'ASSET_QUOTA_IMMUTABLE'; END IF;
  IF NOT ((OLD.state='AVAILABLE' AND NEW.state='RESERVED')
       OR (OLD.state='RESERVED' AND NEW.state IN('CONSUMED','AVAILABLE'))
       OR (OLD.state='CONSUMED' AND NEW.state='AVAILABLE')) THEN RAISE EXCEPTION 'ASSET_QUOTA_TRANSITION'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER finance_asset_quota_guard
 BEFORE INSERT OR UPDATE OR DELETE ON plugin_finance_asset_source_quota
 FOR EACH ROW EXECUTE FUNCTION plugin_finance_asset_quota_guard();

CREATE TABLE plugin_finance_payroll_fact_evidence (
 tenant_id uuid NOT NULL,
 tax_entity_id uuid NOT NULL,
 tax_year integer NOT NULL CHECK(tax_year BETWEEN 2000 AND 9999),
 book_id uuid NOT NULL,
 year_id uuid NOT NULL,
 id uuid NOT NULL,
 batch_id uuid NOT NULL,
 kind text NOT NULL CHECK(kind IN('PAYMENT_VERIFIED','WITHHOLDING_CONFIRMED','DECLARATION_ACCEPTED')),
 original_id uuid NOT NULL,
 original_sha256 text NOT NULL CHECK(original_sha256~'^[a-f0-9]{64}$'),
 amount numeric(28,2) NOT NULL CHECK(amount>=0),
 status text NOT NULL DEFAULT 'PENDING' CHECK(status IN('PENDING','APPROVED','REJECTED','CANCELLED')),
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
 UNIQUE(tenant_id,tax_entity_id,tax_year,batch_id,kind),
 FOREIGN KEY(tenant_id,book_id,year_id,original_id) REFERENCES plugin_finance_original(tenant_id,book_id,year_id,id),
 FOREIGN KEY(tenant_id,prepared_by_binding,prepared_by_account,prepared_by_person) REFERENCES plugin_finance_person_binding(tenant_id,id,account_id,person_id),
 FOREIGN KEY(tenant_id,approved_by_binding,approved_by_account,approved_by_person) REFERENCES plugin_finance_person_binding(tenant_id,id,account_id,person_id),
 CHECK((status='PENDING' AND version=1 AND approved_by_person IS NULL AND decided_at IS NULL)
    OR (status='APPROVED' AND version=2 AND approved_by_person IS NOT NULL AND approved_by_person<>prepared_by_person AND decided_at IS NOT NULL)
    OR (status IN('REJECTED','CANCELLED') AND version=2 AND decided_at IS NOT NULL))
);
ALTER TABLE plugin_finance_payroll_fact_evidence ENABLE ROW LEVEL SECURITY;
ALTER TABLE plugin_finance_payroll_fact_evidence FORCE ROW LEVEL SECURITY;
CREATE POLICY payroll_tenant ON plugin_finance_payroll_fact_evidence
 USING (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id=nullif(current_setting('app.tenant_id',true),'')::uuid);

CREATE FUNCTION plugin_finance_v4_typed_linked(tab text, q jsonb) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT q IS NOT NULL AND (
  (tab='plugin_finance_journal' AND q->>'type' IN(
   'AR_REVENUE','AR_RECEIPT','AR_ALLOCATION','AR_REVERSAL',
   'ASSET_ACQUIRE','ASSET_DEPRECIATE','ASSET_DISPOSE','ASSET_REVERSE','ASSET_CORRECT_DEPRECIATION'))
  OR (tab<>'plugin_finance_journal' AND (q->>'journal_id') IS NOT NULL AND EXISTS(
   SELECT 1 FROM plugin_finance_journal j
   WHERE (j.tenant_id,j.book_id,j.year_id,j.id)=((q->>'tenant_id')::uuid,(q->>'book_id')::uuid,(q->>'year_id')::uuid,(q->>'journal_id')::uuid)
     AND j.type IN(
      'AR_REVENUE','AR_RECEIPT','AR_ALLOCATION','AR_REVERSAL',
      'ASSET_ACQUIRE','ASSET_DEPRECIATE','ASSET_DISPOSE','ASSET_REVERSE','ASSET_CORRECT_DEPRECIATION')))
 )
$$;

CREATE FUNCTION plugin_finance_v4_typed_raw_guard(tab text, operation text, o jsonb, n jsonb) RETURNS void LANGUAGE plpgsql AS $$
DECLARE q jsonb; h plugin_finance_journal%ROWTYPE; yr plugin_finance_year%ROWTYPE; t uuid; b uuid; y uuid;
BEGIN
 q:=coalesce(n,o);
 IF operation='DELETE' OR tab='plugin_finance_document' THEN
  RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='V4_TYPED_JOURNAL_IMMUTABLE';
 END IF;
 t:=(q->>'tenant_id')::uuid; b:=(q->>'book_id')::uuid; y:=(q->>'year_id')::uuid;
 IF plugin_finance_journal_person_lock(t,b,y)<>'JOURNAL_PERSON_SOD' THEN
  RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='JOURNAL_PERSON_MODE_REQUIRED';
 END IF;
 SELECT * INTO yr FROM plugin_finance_year WHERE(tenant_id,book_id,id)=(t,b,y);
 IF NOT FOUND OR yr.status<>'OPEN' OR yr.precision_profile<>'V4' OR yr.precision_review_required THEN
  RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='LEGACY_PRECISION_REVIEW_REQUIRED';
 END IF;
 IF tab='plugin_finance_journal' THEN
  IF n->>'source_event_id' IS NOT NULL OR n->>'closing_basis' IS NOT NULL OR n->>'amount_format_version' IS DISTINCT FROM 'V4_8' THEN
   RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='V4_TYPED_JOURNAL_INVALID';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM plugin_finance_period p WHERE(p.tenant_id,p.book_id,p.year_id,p.id)=(t,b,y,(n->>'period_id')::uuid) AND p.status='OPEN' AND p.month=extract(month FROM (n->>'date')::date)::integer AND yr.year=extract(year FROM (n->>'date')::date)::integer) THEN
   RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PERIOD_CLOSED';
  END IF;
  PERFORM plugin_finance_journal_person_identity(t,(n->>'created_by')::uuid,true);
  IF NOT plugin_finance_journal_person_role(t,b,y,(n->>'created_by')::uuid,ARRAY['OWNER','MAKER']) THEN
   RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SCOPE_DENIED';
  END IF;
  IF operation='UPDATE' THEN
   IF o->>'status' IN('POSTED','CANCELLED')
     OR (n->>'version')::bigint<>(o->>'version')::bigint+1
     OR (n-ARRAY['status','version','approved_by','approved_version','number','reversal_of'])
        IS DISTINCT FROM (o-ARRAY['status','version','approved_by','approved_version','number','reversal_of']) THEN
    RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='V4_TYPED_JOURNAL_STATE_CONFLICT';
   END IF;
   IF n->>'status'='APPROVED' THEN
    IF n->>'approved_by' IS NULL OR (n->>'approved_by')::uuid=(n->>'created_by')::uuid OR (n->>'approved_version')::bigint<>2 THEN
     RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SELF_APPROVAL_FORBIDDEN';
    END IF;
    PERFORM plugin_finance_journal_person_identity(t,(n->>'approved_by')::uuid,true);
    IF NOT plugin_finance_journal_person_role(t,b,y,(n->>'approved_by')::uuid,ARRAY['OWNER','REVIEWER']) THEN
     RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SCOPE_DENIED';
    END IF;
   ELSIF n->>'status'='POSTED' THEN
    IF o->>'status'<>'APPROVED' OR (n->>'version')::bigint<>3 OR n->>'number' IS NULL THEN
     RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='V4_TYPED_JOURNAL_POST_CONFLICT';
    END IF;
   ELSE
    RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='V4_TYPED_JOURNAL_STATE_CONFLICT';
   END IF;
  END IF;
 ELSIF tab='plugin_finance_line' THEN
  SELECT * INTO h FROM plugin_finance_journal WHERE(tenant_id,book_id,year_id,id)=(t,b,y,(n->>'journal_id')::uuid);
  IF NOT FOUND OR operation<>'INSERT' OR h.status<>'DRAFT' THEN
   RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='V4_TYPED_JOURNAL_LINE_CONFLICT';
  END IF;
 END IF;
END $$;

CREATE FUNCTION plugin_finance_v4_typed_posting_guard(n jsonb) RETURNS void LANGUAGE plpgsql AS $$
DECLARE h plugin_finance_journal%ROWTYPE; t uuid; b uuid; y uuid;
BEGIN
 t:=(n->>'tenant_id')::uuid; b:=(n->>'book_id')::uuid; y:=(n->>'year_id')::uuid;
 SELECT * INTO h FROM plugin_finance_journal WHERE(tenant_id,book_id,year_id,id)=(t,b,y,(n->>'journal_id')::uuid);
 IF NOT FOUND OR h.status<>'APPROVED' OR h.version<>(n->>'journal_version')::bigint THEN
  RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='V4_TYPED_POSTING_APPROVAL_STALE';
 END IF;
 PERFORM plugin_finance_journal_person_identity(t,(n->>'posted_by')::uuid,true);
 IF NOT plugin_finance_journal_person_role(t,b,y,(n->>'posted_by')::uuid,ARRAY['OWNER','POSTER']) THEN
  RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SCOPE_DENIED';
 END IF;
END $$;

CREATE FUNCTION plugin_finance_v4_typed_assert(t uuid, b uuid, y uuid, jid uuid) RETURNS void LANGUAGE plpgsql AS $$
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
 ELSE
  IF NOT EXISTS(
   SELECT 1 FROM plugin_finance_asset_plan pl
   WHERE (pl.tenant_id,pl.book_id,pl.year_id,pl.journal_id)=(t,b,y,jid) AND pl.status='POSTED') THEN
   RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='ASSET_POSTING_PROOF_MISSING';
  END IF;
 END IF;
END $$;

CREATE FUNCTION plugin_finance_v4_typed_watermark(t uuid, b uuid, y uuid, new_seq bigint, old_seq bigint) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT new_seq=old_seq+1 AND EXISTS(
  SELECT 1 FROM plugin_finance_posting p
  JOIN plugin_finance_journal j ON (j.tenant_id,j.book_id,j.year_id,j.id)=(p.tenant_id,p.book_id,p.year_id,p.journal_id)
  WHERE (p.tenant_id,p.book_id,p.year_id,p.posting_seq)=(t,b,y,new_seq)
    AND j.status='POSTED'
    AND j.type IN(
     'AR_REVENUE','AR_RECEIPT','AR_ALLOCATION','AR_REVERSAL',
     'ASSET_ACQUIRE','ASSET_DEPRECIATE','ASSET_DISPOSE','ASSET_REVERSE','ASSET_CORRECT_DEPRECIATION')
 )
$$;

CREATE OR REPLACE FUNCTION plugin_finance_journal_person_watermark_final() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.posting_seq IS DISTINCT FROM OLD.posting_seq AND plugin_finance_v4_typed_watermark(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.posting_seq,OLD.posting_seq) THEN RETURN NULL; END IF;
 IF NEW.posting_seq IS DISTINCT FROM OLD.posting_seq AND EXISTS(SELECT 1 FROM plugin_finance_ap_post a JOIN plugin_finance_posting p ON(p.tenant_id,p.book_id,p.year_id,p.journal_id,p.posting_seq,p.digest)=(a.tenant_id,a.book_id,a.year_id,a.journal_id,a.posting_seq,a.posting_digest) WHERE(a.tenant_id,a.book_id,a.year_id,a.posting_seq)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.posting_seq)) THEN RETURN NULL;END IF;
 IF NEW.posting_seq IS DISTINCT FROM OLD.posting_seq AND plugin_finance_profit_closing_watermark(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.posting_seq,OLD.posting_seq) THEN RETURN NULL;END IF;
 IF plugin_finance_journal_person_lock(NEW.tenant_id,NEW.book_id,NEW.year_id)='JOURNAL_PERSON_SOD' AND NEW.posting_seq IS DISTINCT FROM OLD.posting_seq THEN
  PERFORM 1 FROM plugin_finance_posting p JOIN plugin_finance_journal_person_post r ON(r.tenant_id,r.book_id,r.year_id,r.journal_id,r.posting_seq,r.posting_digest)=(p.tenant_id,p.book_id,p.year_id,p.journal_id,p.posting_seq,p.digest) WHERE(p.tenant_id,p.book_id,p.year_id,p.posting_seq)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.posting_seq);
  IF NOT FOUND THEN RAISE EXCEPTION 'JOURNAL_PERSON_WATERMARK_WITHOUT_POST'; END IF;
 END IF;
 RETURN NULL;
END $$;

CREATE OR REPLACE FUNCTION plugin_finance_journal_person_receipt_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE h plugin_finance_journal;c plugin_finance_journal_person_content;cur plugin_finance_journal_person_current;e plugin_finance_journal_person_transition;ap plugin_finance_journal_person_approval;po plugin_finance_posting;who jsonb;
BEGIN
 IF plugin_finance_v4_typed_linked(TG_TABLE_NAME,to_jsonb(NEW)) OR plugin_finance_v4_typed_linked(TG_TABLE_NAME,to_jsonb(OLD)) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='V4_TYPED_JOURNAL_CONFLICT'; END IF;
 IF plugin_finance_ap_linked(TG_TABLE_NAME,to_jsonb(NEW)) OR plugin_finance_ap_linked(TG_TABLE_NAME,to_jsonb(OLD)) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 IF plugin_finance_profit_closing_linked(TG_TABLE_NAME,to_jsonb(NEW)) OR plugin_finance_profit_closing_linked(TG_TABLE_NAME,to_jsonb(OLD)) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_STATE_CONFLICT';END IF;
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'JOURNAL_PERSON_IMMUTABLE_HISTORY'; END IF;
 IF plugin_finance_journal_person_lock(NEW.tenant_id,NEW.book_id,NEW.year_id)<>'JOURNAL_PERSON_SOD' THEN RAISE EXCEPTION 'JOURNAL_PERSON_MODE_REQUIRED'; END IF;
 SELECT * INTO h FROM plugin_finance_journal WHERE(tenant_id,book_id,year_id,id)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.journal_id);
 IF NOT FOUND THEN RAISE EXCEPTION 'JOURNAL_PERSON_CONTENT_STALE'; END IF;
 PERFORM plugin_finance_journal_person_open(h.tenant_id,h.book_id,h.year_id,h.period_id,h.date);
 IF TG_TABLE_NAME='plugin_finance_journal_person_current' THEN
  IF TG_OP='DELETE' THEN RAISE EXCEPTION 'JOURNAL_PERSON_IMMUTABLE_HISTORY'; END IF;
  IF TG_OP='UPDATE' AND(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.journal_id) IS DISTINCT FROM(OLD.tenant_id,OLD.book_id,OLD.year_id,OLD.journal_id) THEN RAISE EXCEPTION 'JOURNAL_PERSON_SCOPE_IMMUTABLE'; END IF;
  SELECT * INTO cur FROM plugin_finance_journal_person_current WHERE(tenant_id,book_id,year_id,journal_id)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.journal_id);
  IF FOUND THEN IF NEW.journal_version<>cur.journal_version+1 THEN RAISE EXCEPTION 'JOURNAL_PERSON_TRANSITION_CHAIN'; END IF; ELSIF NEW.journal_version<>1 THEN RAISE EXCEPTION 'JOURNAL_PERSON_TRANSITION_CHAIN'; END IF;
  SELECT * INTO e FROM plugin_finance_journal_person_transition WHERE(tenant_id,book_id,year_id,journal_id,journal_version)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.journal_id,NEW.journal_version);
  IF NOT FOUND OR h.version<>NEW.journal_version OR e.content_version<>NEW.content_version OR e.content_digest<>NEW.content_digest OR e.to_state<>h.status THEN RAISE EXCEPTION 'JOURNAL_PERSON_CURRENT_WITHOUT_TRANSITION'; END IF;
  IF h.status='APPROVED' AND NEW.approval_id IS DISTINCT FROM e.approval_id THEN RAISE EXCEPTION 'JOURNAL_PERSON_APPROVAL_STALE'; END IF;
  IF h.status NOT IN('APPROVED','POSTED') AND NEW.approval_id IS NOT NULL THEN RAISE EXCEPTION 'JOURNAL_PERSON_APPROVAL_STALE'; END IF;
  IF h.status='POSTED' AND(cur.approval_id IS NULL OR NEW.approval_id IS DISTINCT FROM cur.approval_id OR NEW.approval_id IS NULL) THEN RAISE EXCEPTION 'JOURNAL_PERSON_APPROVAL_STALE'; END IF;
  RETURN NEW;
 END IF;
 SELECT * INTO c FROM plugin_finance_journal_person_content WHERE(tenant_id,book_id,year_id,journal_id,content_version)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.journal_id,NEW.content_version);
 IF TG_TABLE_NAME='plugin_finance_journal_person_content' THEN
  IF NEW.created_at IS DISTINCT FROM CURRENT_TIMESTAMP OR NEW.created_journal_version<>h.version OR NEW.snapshot IS DISTINCT FROM plugin_finance_journal_person_snapshot(h.tenant_id,h.book_id,h.year_id,h.id) OR (NEW.maker->>'accountId')::uuid<>h.created_by OR NEW.editor IS DISTINCT FROM plugin_finance_journal_person_identity(h.tenant_id,(NEW.editor->>'accountId')::uuid,true) THEN RAISE EXCEPTION 'JOURNAL_PERSON_CONTENT_STALE'; END IF;
  SELECT * INTO cur FROM plugin_finance_journal_person_current WHERE(tenant_id,book_id,year_id,journal_id)=(h.tenant_id,h.book_id,h.year_id,h.id);
  IF FOUND THEN
   SELECT * INTO c FROM plugin_finance_journal_person_content WHERE(tenant_id,book_id,year_id,journal_id,content_version)=(h.tenant_id,h.book_id,h.year_id,h.id,cur.content_version);
   IF NEW.content_version<>cur.content_version+1 OR NEW.digest=cur.content_digest OR NEW.maker IS DISTINCT FROM c.maker THEN RAISE EXCEPTION 'JOURNAL_PERSON_CONTENT_VERSION'; END IF;
  ELSIF NEW.content_version<>1 OR h.version<>1 OR NEW.maker IS DISTINCT FROM plugin_finance_journal_person_identity(h.tenant_id,h.created_by,true) THEN RAISE EXCEPTION 'JOURNAL_PERSON_CONTENT_VERSION'; END IF;
  RETURN NEW;
 END IF;
 IF NOT FOUND OR c.digest IS DISTINCT FROM NEW.content_digest THEN RAISE EXCEPTION 'JOURNAL_PERSON_CONTENT_STALE'; END IF;
 IF TG_TABLE_NAME='plugin_finance_journal_person_approval' THEN
  who:=NEW.approver;
  IF h.status<>'SUBMITTED' OR NEW.approved_journal_version<>h.version+1 OR NEW.approved_at IS DISTINCT FROM CURRENT_TIMESTAMP THEN RAISE EXCEPTION 'JOURNAL_PERSON_APPROVAL_STALE'; END IF;
 ELSE
  IF TG_TABLE_NAME='plugin_finance_journal_person_post' THEN SELECT * INTO e FROM plugin_finance_journal_person_transition WHERE(tenant_id,book_id,year_id,journal_id,journal_version)=(h.tenant_id,h.book_id,h.year_id,h.id,NEW.posted_journal_version); ELSE SELECT * INTO e FROM plugin_finance_journal_person_transition WHERE(tenant_id,book_id,year_id,journal_id,journal_version)=(h.tenant_id,h.book_id,h.year_id,h.id,NEW.journal_version); END IF;
  IF NOT FOUND OR e.content_version<>NEW.content_version OR e.content_digest<>NEW.content_digest THEN RAISE EXCEPTION 'JOURNAL_PERSON_RECEIPT_WITHOUT_TRANSITION'; END IF;
  IF TG_TABLE_NAME='plugin_finance_journal_person_post' THEN
   who:=NEW.poster;
   SELECT * INTO po FROM plugin_finance_posting WHERE(tenant_id,book_id,year_id,journal_id)=(h.tenant_id,h.book_id,h.year_id,h.id);
   SELECT * INTO ap FROM plugin_finance_journal_person_approval WHERE(tenant_id,book_id,year_id,id)=(h.tenant_id,h.book_id,h.year_id,NEW.approval_id);
   IF e.action<>'POST' OR h.status<>'POSTED' OR NEW.posted_journal_version<>h.version OR NEW.posted_at IS DISTINCT FROM CURRENT_TIMESTAMP OR po.posting_seq IS DISTINCT FROM NEW.posting_seq OR po.digest IS DISTINCT FROM NEW.posting_digest OR po.posted_by IS DISTINCT FROM (NEW.poster->>'accountId')::uuid OR po.journal_version<>ap.approved_journal_version OR ap.content_version<>NEW.content_version OR ap.content_digest<>NEW.content_digest OR ap.expires_at<=clock_timestamp() OR ap.approver IS DISTINCT FROM plugin_finance_journal_person_identity(h.tenant_id,ap.approver_account,true) OR NOT plugin_finance_journal_person_role(h.tenant_id,h.book_id,h.year_id,ap.approver_account,ARRAY['OWNER','REVIEWER']) THEN RAISE EXCEPTION 'JOURNAL_PERSON_POST_WITHOUT_APPROVAL'; END IF;
  ELSE
   who:=NEW.actor;
   IF e.action<>'TERMINATE' OR h.status<>'CANCELLED' OR NEW.journal_version<>h.version OR NEW.decided_at IS DISTINCT FROM CURRENT_TIMESTAMP OR e.reason IS DISTINCT FROM NEW.reason THEN RAISE EXCEPTION 'JOURNAL_PERSON_TERMINATION_WITHOUT_TRANSITION'; END IF;
  END IF;
  IF e.actor IS DISTINCT FROM who THEN RAISE EXCEPTION 'JOURNAL_PERSON_RECEIPT_ACTOR_MISMATCH'; END IF;
 END IF;
 IF who IS DISTINCT FROM plugin_finance_journal_person_identity(h.tenant_id,(who->>'accountId')::uuid,true) THEN RAISE EXCEPTION 'JOURNAL_PERSON_IDENTITY_UNAVAILABLE'; END IF;
 IF TG_TABLE_NAME<>'plugin_finance_journal_person_post' AND((who->>'personId')::uuid IN(c.maker_person,c.editor_person) OR NOT plugin_finance_journal_person_role(h.tenant_id,h.book_id,h.year_id,(who->>'accountId')::uuid,ARRAY['OWNER','REVIEWER'])) THEN RAISE EXCEPTION 'JOURNAL_PERSON_SELF_APPROVAL'; END IF;
 RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION plugin_finance_journal_person_final() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r jsonb;t uuid;b uuid;y uuid;j uuid;e plugin_finance_journal_person_transition;
BEGIN
 r:=CASE WHEN TG_OP='DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
 IF plugin_finance_v4_typed_linked(TG_TABLE_NAME,r) THEN
  PERFORM plugin_finance_v4_typed_assert((r->>'tenant_id')::uuid,(r->>'book_id')::uuid,(r->>'year_id')::uuid,CASE WHEN TG_TABLE_NAME='plugin_finance_journal' THEN(r->>'id')::uuid ELSE(r->>'journal_id')::uuid END);
  RETURN NULL;
 END IF;
 IF plugin_finance_ap_linked(TG_TABLE_NAME,r) THEN PERFORM plugin_finance_ap_assert((r->>'tenant_id')::uuid,(r->>'book_id')::uuid,(r->>'year_id')::uuid,(SELECT id FROM plugin_finance_ap_run WHERE(tenant_id,book_id,year_id,journal_id)=((r->>'tenant_id')::uuid,(r->>'book_id')::uuid,(r->>'year_id')::uuid,CASE WHEN TG_TABLE_NAME='plugin_finance_journal' THEN(r->>'id')::uuid ELSE(r->>'journal_id')::uuid END)));RETURN NULL;END IF;
 IF plugin_finance_profit_closing_linked(TG_TABLE_NAME,r) THEN
  IF TG_TABLE_NAME IN('plugin_finance_line','plugin_finance_document') THEN RETURN NULL;END IF;
  PERFORM plugin_finance_profit_closing_assert((r->>'tenant_id')::uuid,(r->>'book_id')::uuid,(r->>'year_id')::uuid,(SELECT id FROM plugin_finance_profit_closing_run WHERE(tenant_id,book_id,year_id,journal_id)=((r->>'tenant_id')::uuid,(r->>'book_id')::uuid,(r->>'year_id')::uuid,CASE WHEN TG_TABLE_NAME='plugin_finance_journal' THEN(r->>'id')::uuid ELSE(r->>'journal_id')::uuid END)));RETURN NULL;
 END IF;
 t:=(r->>'tenant_id')::uuid;b:=(r->>'book_id')::uuid;y:=(r->>'year_id')::uuid;
 j:=CASE WHEN TG_TABLE_NAME='plugin_finance_journal' THEN(r->>'id')::uuid ELSE(r->>'journal_id')::uuid END;
 IF plugin_finance_journal_person_lock(t,b,y)<>'JOURNAL_PERSON_SOD' THEN RETURN NULL; END IF;
 PERFORM plugin_finance_journal_person_assert(t,b,y,j);
 IF TG_TABLE_NAME='plugin_finance_journal_person_content' THEN
  SELECT * INTO e FROM plugin_finance_journal_person_transition WHERE(tenant_id,book_id,year_id,journal_id,journal_version)=(t,b,y,j,NEW.created_journal_version);
  IF NOT FOUND OR e.action NOT IN('CREATE','EDIT','DOCUMENTS') OR e.content_version<>NEW.content_version OR e.content_digest<>NEW.digest OR e.actor IS DISTINCT FROM NEW.editor THEN RAISE EXCEPTION 'JOURNAL_PERSON_ORPHAN_CONTENT'; END IF;
 ELSIF TG_TABLE_NAME='plugin_finance_journal_person_approval' THEN
  SELECT * INTO e FROM plugin_finance_journal_person_transition WHERE(tenant_id,book_id,year_id,journal_id,journal_version)=(t,b,y,j,NEW.approved_journal_version);
  IF NOT FOUND OR e.action<>'APPROVE' OR e.approval_id IS DISTINCT FROM NEW.id OR e.content_version<>NEW.content_version OR e.content_digest<>NEW.content_digest OR e.actor IS DISTINCT FROM NEW.approver THEN RAISE EXCEPTION 'JOURNAL_PERSON_ORPHAN_APPROVAL'; END IF;
 ELSIF TG_TABLE_NAME='plugin_finance_journal_person_transition' THEN
  PERFORM 1 FROM plugin_finance_journal_person_current WHERE(tenant_id,book_id,year_id,journal_id)=(t,b,y,j) AND journal_version>=NEW.journal_version;
  IF NOT FOUND THEN RAISE EXCEPTION 'JOURNAL_PERSON_ORPHAN_TRANSITION'; END IF;
 END IF;
 RETURN NULL;
END $$;

CREATE OR REPLACE FUNCTION plugin_finance_journal_person_raw_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r jsonb;h plugin_finance_journal; old_mode text; new_mode text;
BEGIN
 IF plugin_finance_v4_typed_linked(TG_TABLE_NAME,to_jsonb(NEW)) OR plugin_finance_v4_typed_linked(TG_TABLE_NAME,to_jsonb(OLD)) THEN
  PERFORM plugin_finance_v4_typed_raw_guard(TG_TABLE_NAME,TG_OP,to_jsonb(OLD),to_jsonb(NEW));
  IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
 END IF;
 IF plugin_finance_ap_linked(TG_TABLE_NAME,to_jsonb(NEW)) OR plugin_finance_ap_linked(TG_TABLE_NAME,to_jsonb(OLD)) THEN PERFORM plugin_finance_ap_raw_guard(TG_TABLE_NAME,TG_OP,to_jsonb(OLD),to_jsonb(NEW));IF TG_OP='DELETE' THEN RETURN OLD;ELSE RETURN NEW;END IF;END IF;
 IF plugin_finance_profit_closing_linked(TG_TABLE_NAME,to_jsonb(NEW)) OR plugin_finance_profit_closing_linked(TG_TABLE_NAME,to_jsonb(OLD)) THEN
  PERFORM plugin_finance_profit_closing_raw_guard(TG_TABLE_NAME,TG_OP,to_jsonb(OLD),to_jsonb(NEW));
  IF TG_OP='DELETE' THEN RETURN OLD;ELSE RETURN NEW;END IF;
 END IF;
 IF TG_OP<>'INSERT' THEN
  r:=to_jsonb(OLD);old_mode:=plugin_finance_journal_person_lock((r->>'tenant_id')::uuid,(r->>'book_id')::uuid,(r->>'year_id')::uuid);
 END IF;
 IF TG_OP<>'DELETE' THEN
  r:=to_jsonb(NEW);new_mode:=plugin_finance_journal_person_lock((r->>'tenant_id')::uuid,(r->>'book_id')::uuid,(r->>'year_id')::uuid);
 END IF;
 IF COALESCE(old_mode,'ACCOUNT_ONLY')<>'JOURNAL_PERSON_SOD' AND COALESCE(new_mode,'ACCOUNT_ONLY')<>'JOURNAL_PERSON_SOD' THEN
  IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
 END IF;
 IF TG_OP='UPDATE' AND (OLD.tenant_id,OLD.book_id,OLD.year_id) IS DISTINCT FROM (NEW.tenant_id,NEW.book_id,NEW.year_id) THEN RAISE EXCEPTION 'JOURNAL_PERSON_SCOPE_IMMUTABLE'; END IF;
 IF TG_TABLE_NAME='plugin_finance_journal' THEN
  IF TG_OP='DELETE' THEN RAISE EXCEPTION 'JOURNAL_PERSON_IMMUTABLE_HISTORY'; END IF;
  IF NEW.type<>'NORMAL' OR NEW.reversal_of IS NOT NULL OR NEW.source_event_id IS NOT NULL OR NEW.closing_basis IS NOT NULL OR NEW.amount_format_version<>'V4_8' THEN RAISE EXCEPTION 'JOURNAL_PERSON_UNSUPPORTED_PATH'; END IF;
  IF TG_OP='UPDATE' THEN
   IF NEW.id<>OLD.id OR NEW.created_by<>OLD.created_by OR (to_jsonb(NEW)-ARRAY['date','period_id','summary','status','version','approved_by','approved_version','number']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['date','period_id','summary','status','version','approved_by','approved_version','number']) THEN RAISE EXCEPTION 'JOURNAL_PERSON_HEADER_IMMUTABLE'; END IF;
   IF NEW IS NOT DISTINCT FROM OLD THEN RETURN NEW; END IF;
   IF OLD.status IN('POSTED','CANCELLED') OR NEW.version<>OLD.version+1 THEN RAISE EXCEPTION 'JOURNAL_PERSON_HEADER_VERSION'; END IF;
  ELSE
   PERFORM plugin_finance_journal_person_identity(NEW.tenant_id,NEW.created_by,true);
   IF NOT plugin_finance_journal_person_role(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.created_by,ARRAY['OWNER','MAKER']) THEN RAISE EXCEPTION 'JOURNAL_PERSON_CREATOR_ONLY'; END IF;
  END IF;
  PERFORM plugin_finance_journal_person_open(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.period_id,NEW.date);
 ELSE
  IF TG_OP='DELETE' THEN r:=to_jsonb(OLD); ELSE r:=to_jsonb(NEW); END IF;
  IF TG_OP='UPDATE' AND OLD.journal_id IS DISTINCT FROM NEW.journal_id THEN RAISE EXCEPTION 'JOURNAL_PERSON_SCOPE_IMMUTABLE'; END IF;
  SELECT * INTO h FROM plugin_finance_journal WHERE(tenant_id,book_id,year_id,id)=((r->>'tenant_id')::uuid,(r->>'book_id')::uuid,(r->>'year_id')::uuid,(r->>'journal_id')::uuid) FOR UPDATE;
  IF NOT FOUND OR h.status<>'DRAFT' THEN RAISE EXCEPTION 'JOURNAL_PERSON_CONTENT_REQUIRES_DRAFT'; END IF;
  PERFORM plugin_finance_journal_person_open(h.tenant_id,h.book_id,h.year_id,h.period_id,h.date);
  IF TG_TABLE_NAME='plugin_finance_document' AND TG_OP<>'DELETE' THEN
   PERFORM 1 FROM plugin_finance_original o WHERE(o.tenant_id,o.book_id,o.year_id,o.id)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.original_id) AND o.created_by=h.created_by AND o.sha256=NEW.sha256 AND o.filename=NEW.filename;
   IF NOT FOUND THEN RAISE EXCEPTION 'JOURNAL_PERSON_INVALID_EVIDENCE'; END IF;
  END IF;
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END $$;

CREATE OR REPLACE FUNCTION plugin_finance_journal_person_posting_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE h plugin_finance_journal;cur plugin_finance_journal_person_current;ap plugin_finance_journal_person_approval;
BEGIN
 IF plugin_finance_v4_typed_linked('plugin_finance_posting',to_jsonb(NEW)) THEN PERFORM plugin_finance_v4_typed_posting_guard(to_jsonb(NEW)); RETURN NEW; END IF;
 IF plugin_finance_ap_linked('plugin_finance_posting',to_jsonb(NEW)) THEN PERFORM plugin_finance_ap_posting_guard(to_jsonb(NEW));RETURN NEW;END IF;
 IF plugin_finance_profit_closing_linked('plugin_finance_posting',to_jsonb(NEW)) THEN PERFORM plugin_finance_profit_closing_posting_guard(to_jsonb(NEW));RETURN NEW;END IF;
 IF plugin_finance_journal_person_lock(NEW.tenant_id,NEW.book_id,NEW.year_id)<>'JOURNAL_PERSON_SOD' THEN RETURN NEW; END IF;
 SELECT * INTO h FROM plugin_finance_journal WHERE(tenant_id,book_id,year_id,id)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.journal_id);
 SELECT * INTO cur FROM plugin_finance_journal_person_current WHERE(tenant_id,book_id,year_id,journal_id)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.journal_id);
 IF NOT FOUND OR h.status<>'APPROVED' OR h.version<>NEW.journal_version OR cur.journal_version<>h.version THEN RAISE EXCEPTION 'JOURNAL_PERSON_POST_WITHOUT_APPROVAL'; END IF;
 SELECT * INTO ap FROM plugin_finance_journal_person_approval WHERE(tenant_id,book_id,year_id,journal_id,id)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.journal_id,cur.approval_id);
 IF NOT FOUND OR ap.approved_journal_version<>h.version OR ap.content_version<>cur.content_version OR ap.content_digest<>cur.content_digest OR ap.expires_at<=clock_timestamp() OR ap.approver IS DISTINCT FROM plugin_finance_journal_person_identity(h.tenant_id,ap.approver_account,true) OR NOT plugin_finance_journal_person_role(h.tenant_id,h.book_id,h.year_id,ap.approver_account,ARRAY['OWNER','REVIEWER']) OR NOT plugin_finance_journal_person_role(h.tenant_id,h.book_id,h.year_id,NEW.posted_by,ARRAY['OWNER','POSTER']) THEN RAISE EXCEPTION 'JOURNAL_PERSON_POST_WITHOUT_APPROVAL'; END IF;
 PERFORM plugin_finance_journal_person_identity(NEW.tenant_id,NEW.posted_by,true);
 PERFORM plugin_finance_journal_person_open(h.tenant_id,h.book_id,h.year_id,h.period_id,h.date);
 RETURN NEW;
END $$;
