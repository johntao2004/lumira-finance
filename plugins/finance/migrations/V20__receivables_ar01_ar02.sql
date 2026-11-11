-- AR01/AR02 additive candidate. Root assigns migration identity after integration review.
-- Does NOT widen native journal types: the native AR producer is an explicit integration gate.
CREATE TABLE plugin_finance_ar_object (
 tenant_id uuid NOT NULL, legal_entity_id uuid NOT NULL, book_id uuid NOT NULL, year_id uuid NOT NULL,
 id uuid NOT NULL, operation_id uuid NOT NULL, business_key text NOT NULL CHECK(length(business_key) BETWEEN 1 AND 160),
 business_chain_id uuid NOT NULL, kind text NOT NULL CHECK(kind IN('CONTRACT','PERFORMANCE','RECOGNITION','RECEIPT','ALLOCATION','REVERSAL')),
 state text NOT NULL DEFAULT 'DRAFT' CHECK(state IN('DRAFT','SUBMITTED','APPROVED','CONFIRMED','POSTED','CANCELLED')),
 version bigint NOT NULL DEFAULT 1 CHECK(version>0), customer_id uuid NOT NULL, customer_revision_id uuid NOT NULL,
 original_id uuid NOT NULL, accounting_date date NOT NULL, amount numeric(28,8) NOT NULL CHECK(amount>0 AND amount=round(amount,2)),
 content jsonb NOT NULL, content_digest text NOT NULL CHECK(content_digest~'^[a-f0-9]{64}$'), initial_payload jsonb NOT NULL,
 prepared_by jsonb NOT NULL, approved_by jsonb, approval_expires_at timestamptz,
 journal_id uuid, reverses_id uuid, reversed_by uuid, created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(tenant_id,book_id,year_id,id),
 UNIQUE(tenant_id,book_id,year_id,operation_id),
 UNIQUE(tenant_id,legal_entity_id,customer_id,kind,business_key),
 UNIQUE(tenant_id,book_id,year_id,id,customer_id),
 FOREIGN KEY(tenant_id,legal_entity_id) REFERENCES plugin_finance_entity(tenant_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id) REFERENCES plugin_finance_year(tenant_id,book_id,id),
 FOREIGN KEY(tenant_id,legal_entity_id,customer_id,customer_revision_id) REFERENCES plugin_finance_counterparty_revision(tenant_id,legal_entity_id,counterparty_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,original_id) REFERENCES plugin_finance_original(tenant_id,book_id,year_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,business_chain_id) REFERENCES plugin_finance_ar_object(tenant_id,book_id,year_id,id) DEFERRABLE INITIALLY DEFERRED,
 FOREIGN KEY(tenant_id,book_id,year_id,reverses_id,customer_id) REFERENCES plugin_finance_ar_object(tenant_id,book_id,year_id,id,customer_id),
 FOREIGN KEY(tenant_id,book_id,year_id,reversed_by,customer_id) REFERENCES plugin_finance_ar_object(tenant_id,book_id,year_id,id,customer_id) DEFERRABLE INITIALLY DEFERRED,
 FOREIGN KEY(tenant_id,book_id,year_id,journal_id) REFERENCES plugin_finance_journal(tenant_id,book_id,year_id,id),
 CHECK((kind='REVERSAL')=(reverses_id IS NOT NULL)),
 CHECK((state='POSTED')=(journal_id IS NOT NULL)),
 CHECK((state IN('APPROVED','CONFIRMED','POSTED'))=(approved_by IS NOT NULL)),
 CHECK((state='APPROVED')=(approval_expires_at IS NOT NULL)),
 CHECK(state<>'CONFIRMED' OR kind IN('CONTRACT','PERFORMANCE')),
 CHECK(state NOT IN('APPROVED','POSTED') OR kind NOT IN('CONTRACT','PERFORMANCE')),
 CHECK(prepared_by->>'personId' IS NOT NULL AND prepared_by->>'accountId' IS NOT NULL AND prepared_by->>'bindingId' IS NOT NULL),
 CHECK(approved_by IS NULL OR (approved_by->>'personId' IS NOT NULL AND approved_by->>'accountId' IS NOT NULL AND approved_by->>'bindingId' IS NOT NULL AND prepared_by->>'personId'<>approved_by->>'personId'))
);
CREATE INDEX finance_ar_page ON plugin_finance_ar_object(tenant_id,book_id,year_id,id);
CREATE UNIQUE INDEX finance_ar_pending_reverse ON plugin_finance_ar_object(tenant_id,book_id,year_id,reverses_id) WHERE kind='REVERSAL' AND state IN('DRAFT','SUBMITTED','APPROVED','POSTED');
-- Permanent receipt identity survives cancellation, retries, handler upgrades and year changes.
CREATE UNIQUE INDEX finance_ar_receipt_reference ON plugin_finance_ar_object(tenant_id,legal_entity_id,(initial_payload->>'bankAccountReference'),(initial_payload->>'receiptReference')) WHERE kind='RECEIPT';
CREATE UNIQUE INDEX finance_ar_receipt_original ON plugin_finance_ar_object(tenant_id,legal_entity_id,(content->'original'->>'sha256')) WHERE kind='RECEIPT';

CREATE TABLE plugin_finance_ar_capacity (
 tenant_id uuid NOT NULL, book_id uuid NOT NULL, year_id uuid NOT NULL, parent_id uuid NOT NULL,
 purpose text NOT NULL CHECK(purpose IN('PERFORMANCE','ACCOUNTING_CONFIRMATION','SETTLEMENT','RECEIPT_ALLOCATION')),
 capacity numeric(28,8) NOT NULL CHECK(capacity>0), reserved numeric(28,8) NOT NULL DEFAULT 0,
 committed numeric(28,8) NOT NULL DEFAULT 0,
 PRIMARY KEY(tenant_id,book_id,year_id,parent_id,purpose),
 FOREIGN KEY(tenant_id,book_id,year_id,parent_id) REFERENCES plugin_finance_ar_object(tenant_id,book_id,year_id,id),
 CHECK(reserved>=0 AND committed>=0 AND reserved+committed<=capacity)
);
CREATE TABLE plugin_finance_ar_usage (
 tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,id uuid NOT NULL,
 parent_id uuid NOT NULL,consumer_id uuid NOT NULL,purpose text NOT NULL,
 amount numeric(28,8) NOT NULL CHECK(amount>0 AND amount=round(amount,2)),
 state text NOT NULL CHECK(state IN('RESERVED','COMMITTED','RELEASED','REVERSED')), reversal_id uuid,
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(tenant_id,book_id,year_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,parent_id,purpose) REFERENCES plugin_finance_ar_capacity(tenant_id,book_id,year_id,parent_id,purpose),
 FOREIGN KEY(tenant_id,book_id,year_id,consumer_id) REFERENCES plugin_finance_ar_object(tenant_id,book_id,year_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,reversal_id) REFERENCES plugin_finance_ar_object(tenant_id,book_id,year_id,id),
 CHECK((state='REVERSED')=(reversal_id IS NOT NULL)),CHECK(parent_id<>consumer_id)
);
CREATE UNIQUE INDEX finance_ar_active_usage ON plugin_finance_ar_usage(tenant_id,book_id,year_id,parent_id,consumer_id,purpose) WHERE state IN('RESERVED','COMMITTED');
CREATE INDEX finance_ar_usage_parent ON plugin_finance_ar_usage(tenant_id,book_id,year_id,parent_id,state);
CREATE INDEX finance_ar_usage_consumer ON plugin_finance_ar_usage(tenant_id,book_id,year_id,consumer_id,state);
CREATE TABLE plugin_finance_ar_posted (
 tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,object_id uuid NOT NULL,journal_id uuid NOT NULL,proof jsonb NOT NULL,
 PRIMARY KEY(tenant_id,book_id,year_id,object_id),UNIQUE(tenant_id,book_id,year_id,journal_id),
 FOREIGN KEY(tenant_id,book_id,year_id,object_id) REFERENCES plugin_finance_ar_object(tenant_id,book_id,year_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,journal_id) REFERENCES plugin_finance_posting(tenant_id,book_id,year_id,journal_id)
);
CREATE TABLE plugin_finance_ar_request (
 actor_id uuid NOT NULL,request_key text NOT NULL CHECK(length(request_key) BETWEEN 1 AND 128),
 tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,route text NOT NULL,payload_hash text NOT NULL CHECK(payload_hash~'^[a-f0-9]{64}$'),
 object_id uuid NOT NULL,response jsonb NOT NULL,created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(actor_id,request_key),
 FOREIGN KEY(tenant_id,book_id,year_id,object_id) REFERENCES plugin_finance_ar_object(tenant_id,book_id,year_id,id)
);
CREATE TABLE plugin_finance_ar_audit (
 tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,id uuid NOT NULL,object_id uuid NOT NULL,
 action text NOT NULL CHECK(action IN('CREATE','SUBMIT','APPROVE','POST','WITHDRAW','REJECT','CANCEL')),
 actor jsonb NOT NULL,reason text NOT NULL CHECK(length(reason) BETWEEN 1 AND 1000),snapshot jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(tenant_id,book_id,year_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,object_id) REFERENCES plugin_finance_ar_object(tenant_id,book_id,year_id,id)
);
CREATE TRIGGER finance_ar_audit_immutable BEFORE UPDATE OR DELETE ON plugin_finance_ar_audit FOR EACH ROW EXECUTE FUNCTION plugin_finance_immutable();
CREATE TRIGGER finance_ar_request_immutable BEFORE UPDATE OR DELETE ON plugin_finance_ar_request FOR EACH ROW EXECUTE FUNCTION plugin_finance_immutable();
CREATE TRIGGER finance_ar_posted_immutable BEFORE UPDATE OR DELETE ON plugin_finance_ar_posted FOR EACH ROW EXECUTE FUNCTION plugin_finance_immutable();

CREATE FUNCTION plugin_finance_ar_guard_object() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE src plugin_finance_ar_object%ROWTYPE; v jsonb; x text; k text;
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'AR_IMMUTABLE'; END IF;
 IF TG_OP='INSERT' THEN
  IF NEW.state<>'DRAFT' OR NEW.version<>1 OR NEW.created_at<>CURRENT_TIMESTAMP
    OR NEW.content->>'schema'<>'AR_CNY_V1' OR NEW.content->'request' IS DISTINCT FROM NEW.initial_payload
    OR NEW.kind IS DISTINCT FROM NEW.initial_payload->>'kind'
    OR NEW.amount IS DISTINCT FROM (NEW.initial_payload->>'amount')::numeric
    OR NEW.original_id IS DISTINCT FROM (NEW.initial_payload->>'originalId')::uuid
    OR NEW.customer_id IS DISTINCT FROM (NEW.initial_payload->>'customerId')::uuid
    OR NEW.customer_revision_id IS DISTINCT FROM (NEW.initial_payload->>'customerRevisionId')::uuid
    OR NEW.accounting_date IS DISTINCT FROM (NEW.initial_payload->>'date')::date
    OR NEW.operation_id IS DISTINCT FROM (NEW.initial_payload->>'operationId')::uuid
    OR NEW.business_key IS DISTINCT FROM NEW.initial_payload->>'businessKey'
    OR NOT EXISTS(SELECT 1 FROM plugin_finance_book WHERE tenant_id=NEW.tenant_id AND id=NEW.book_id AND legal_entity_id=NEW.legal_entity_id)
    THEN RAISE EXCEPTION 'AR_STATE_CONFLICT'; END IF;
  IF NEW.prepared_by IS DISTINCT FROM plugin_finance_journal_person_identity(NEW.tenant_id,(NEW.prepared_by->>'accountId')::uuid,true) THEN RAISE EXCEPTION 'AR_IDENTITY_STALE'; END IF;
  IF NOT EXISTS(SELECT 1 FROM plugin_finance_original WHERE (tenant_id,book_id,year_id,id)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.original_id) AND sha256=NEW.content->'original'->>'sha256' AND sha256=encode(sha256(content),'hex')) THEN RAISE EXCEPTION 'AR_ORIGINAL_INVALID'; END IF;
  IF NEW.kind='RECEIPT' AND (NULLIF(NEW.initial_payload->>'bankAccountReference','') IS NULL OR NULLIF(NEW.initial_payload->>'receiptReference','') IS NULL) THEN RAISE EXCEPTION 'AR_RECEIPT_IDENTITY_MISSING'; END IF;
  RETURN NEW;
 END IF;
 IF (to_jsonb(NEW)-ARRAY['state','version','approved_by','approval_expires_at','journal_id','reversed_by']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['state','version','approved_by','approval_expires_at','journal_id','reversed_by']) THEN RAISE EXCEPTION 'AR_IMMUTABLE'; END IF;
 IF NEW.reversed_by IS DISTINCT FROM OLD.reversed_by THEN
  SELECT * INTO src FROM plugin_finance_ar_object WHERE (tenant_id,book_id,year_id,id)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.reversed_by);
  IF OLD.reversed_by IS NOT NULL OR OLD.state<>'POSTED' OR src.state IS DISTINCT FROM 'POSTED' OR src.reverses_id IS DISTINCT FROM NEW.id
    OR (to_jsonb(NEW)-'reversed_by') IS DISTINCT FROM (to_jsonb(OLD)-'reversed_by') THEN RAISE EXCEPTION 'AR_CORRECTION_CONFLICT'; END IF;
  RETURN NEW;
 END IF;
 IF NEW.version<>OLD.version+1 OR NOT (
  (OLD.state='DRAFT' AND NEW.state IN('SUBMITTED','CANCELLED')) OR
  (OLD.state='SUBMITTED' AND NEW.state IN('DRAFT','APPROVED','CONFIRMED','CANCELLED')) OR
  (OLD.state='APPROVED' AND NEW.state IN('DRAFT','POSTED','CANCELLED'))
 ) THEN RAISE EXCEPTION 'AR_STATE_CONFLICT'; END IF;
 IF NEW.state IN('CONFIRMED','APPROVED') AND NEW.approved_by IS DISTINCT FROM plugin_finance_journal_person_identity(NEW.tenant_id,(NEW.approved_by->>'accountId')::uuid,true) THEN RAISE EXCEPTION 'AR_IDENTITY_STALE'; END IF;
 IF NEW.state='POSTED' THEN
  IF NEW.approved_by IS DISTINCT FROM OLD.approved_by OR OLD.approval_expires_at<=CURRENT_TIMESTAMP THEN RAISE EXCEPTION 'AR_APPROVAL_EXPIRED'; END IF;
  k:=CASE NEW.kind WHEN 'RECOGNITION' THEN 'AR_REVENUE' WHEN 'RECEIPT' THEN 'AR_RECEIPT' WHEN 'ALLOCATION' THEN 'AR_ALLOCATION' WHEN 'REVERSAL' THEN 'AR_REVERSAL' ELSE NULL END;
  IF NOT EXISTS(SELECT 1 FROM plugin_finance_ar_posted p JOIN plugin_finance_journal j ON(j.tenant_id,j.book_id,j.year_id,j.id)=(p.tenant_id,p.book_id,p.year_id,p.journal_id) WHERE(p.tenant_id,p.book_id,p.year_id,p.object_id)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.id) AND p.journal_id=NEW.journal_id AND j.status='POSTED' AND j.type=k AND p.proof->>'contentDigest'=NEW.content_digest) THEN RAISE EXCEPTION 'AR_POSTING_PROOF_MISSING'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER finance_ar_object_guard BEFORE INSERT OR UPDATE OR DELETE ON plugin_finance_ar_object FOR EACH ROW EXECUTE FUNCTION plugin_finance_ar_guard_object();

CREATE FUNCTION plugin_finance_ar_usage_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p plugin_finance_ar_object%ROWTYPE; c plugin_finance_ar_object%ROWTYPE; cap plugin_finance_ar_capacity%ROWTYPE; rev plugin_finance_ar_object%ROWTYPE;
        old_reserved numeric:=0;old_committed numeric:=0;new_reserved numeric:=0;new_committed numeric:=0; expected numeric;
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'AR_IMMUTABLE'; END IF;
 SELECT * INTO cap FROM plugin_finance_ar_capacity WHERE(tenant_id,book_id,year_id,parent_id,purpose)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.parent_id,NEW.purpose) FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'AR_CAPACITY_UNAVAILABLE'; END IF;
 SELECT * INTO p FROM plugin_finance_ar_object WHERE(tenant_id,book_id,year_id,id)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.parent_id);
 SELECT * INTO c FROM plugin_finance_ar_object WHERE(tenant_id,book_id,year_id,id)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.consumer_id);
 IF p.customer_id IS DISTINCT FROM c.customer_id THEN RAISE EXCEPTION 'AR_CROSS_CUSTOMER'; END IF;
 IF TG_OP='INSERT' THEN
  IF NEW.state<>'RESERVED' OR c.state<>'DRAFT' OR p.reversed_by IS NOT NULL OR p.state NOT IN('CONFIRMED','POSTED') THEN RAISE EXCEPTION 'AR_STATE_CONFLICT'; END IF;
  expected:=NULL;
  IF NEW.purpose='PERFORMANCE' AND p.kind='CONTRACT' AND c.kind='PERFORMANCE' AND NEW.parent_id=(c.initial_payload->>'contractId')::uuid THEN expected:=c.amount;
  ELSIF NEW.purpose='ACCOUNTING_CONFIRMATION' AND p.kind='PERFORMANCE' AND c.kind='RECOGNITION' AND NEW.parent_id=(c.initial_payload->>'performanceId')::uuid THEN expected:=c.amount;
  ELSIF NEW.purpose='RECEIPT_ALLOCATION' AND p.kind='RECEIPT' AND c.kind='ALLOCATION' AND NEW.parent_id=(c.initial_payload->>'receiptId')::uuid THEN expected:=c.amount;
  ELSIF NEW.purpose='SETTLEMENT' AND p.kind='RECOGNITION' AND c.kind='ALLOCATION' THEN
   SELECT (item->>'amount')::numeric INTO expected FROM jsonb_array_elements(c.initial_payload->'items') item WHERE(item->>'recognitionId')::uuid=NEW.parent_id;
  END IF;
  IF expected IS NULL OR expected<>NEW.amount THEN RAISE EXCEPTION 'AR_CAPACITY_MISMATCH'; END IF;
 ELSE
  IF (to_jsonb(NEW)-ARRAY['state','reversal_id']) IS DISTINCT FROM(to_jsonb(OLD)-ARRAY['state','reversal_id']) OR NOT(
   (OLD.state='RESERVED' AND NEW.state IN('COMMITTED','RELEASED')) OR(OLD.state='COMMITTED' AND NEW.state='REVERSED')) THEN RAISE EXCEPTION 'AR_IMMUTABLE'; END IF;
  old_reserved:=CASE WHEN OLD.state='RESERVED' THEN OLD.amount ELSE 0 END;
  old_committed:=CASE WHEN OLD.state='COMMITTED' THEN OLD.amount ELSE 0 END;
  IF NEW.state='REVERSED' THEN
   SELECT * INTO rev FROM plugin_finance_ar_object WHERE(tenant_id,book_id,year_id,id)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.reversal_id);
   IF rev.state IS DISTINCT FROM 'POSTED' OR rev.reverses_id IS DISTINCT FROM NEW.consumer_id THEN RAISE EXCEPTION 'AR_CORRECTION_CONFLICT'; END IF;
  END IF;
 END IF;
 new_reserved:=CASE WHEN NEW.state='RESERVED' THEN NEW.amount ELSE 0 END;
 new_committed:=CASE WHEN NEW.state='COMMITTED' THEN NEW.amount ELSE 0 END;
 IF cap.reserved-old_reserved+new_reserved+cap.committed-old_committed+new_committed>cap.capacity THEN RAISE EXCEPTION 'AR_CAPACITY_EXCEEDED'; END IF;
 UPDATE plugin_finance_ar_capacity SET reserved=reserved-old_reserved+new_reserved,committed=committed-old_committed+new_committed WHERE(tenant_id,book_id,year_id,parent_id,purpose)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.parent_id,NEW.purpose);
 RETURN NEW;
END $$;
CREATE TRIGGER finance_ar_usage_guard BEFORE INSERT OR UPDATE OR DELETE ON plugin_finance_ar_usage FOR EACH ROW EXECUTE FUNCTION plugin_finance_ar_usage_guard();

CREATE FUNCTION plugin_finance_ar_capacity_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p plugin_finance_ar_object%ROWTYPE;k text;
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'AR_IMMUTABLE'; END IF;
 IF TG_OP='UPDATE' THEN
  IF(to_jsonb(NEW)-ARRAY['reserved','committed']) IS DISTINCT FROM(to_jsonb(OLD)-ARRAY['reserved','committed']) THEN RAISE EXCEPTION 'AR_IMMUTABLE'; END IF;
 ELSE
  SELECT * INTO p FROM plugin_finance_ar_object WHERE(tenant_id,book_id,year_id,id)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.parent_id);
  k:=CASE p.kind WHEN 'CONTRACT' THEN 'PERFORMANCE' WHEN 'PERFORMANCE' THEN 'ACCOUNTING_CONFIRMATION' WHEN 'RECOGNITION' THEN 'SETTLEMENT' WHEN 'RECEIPT' THEN 'RECEIPT_ALLOCATION' ELSE NULL END;
  IF k IS DISTINCT FROM NEW.purpose OR p.amount IS DISTINCT FROM NEW.capacity OR p.state NOT IN('CONFIRMED','POSTED') OR NEW.reserved<>0 OR NEW.committed<>0 THEN RAISE EXCEPTION 'AR_CAPACITY_MISMATCH'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER finance_ar_capacity_guard BEFORE INSERT OR UPDATE OR DELETE ON plugin_finance_ar_capacity FOR EACH ROW EXECUTE FUNCTION plugin_finance_ar_capacity_guard();

CREATE FUNCTION plugin_finance_ar_consistent_capacity() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE cap plugin_finance_ar_capacity%ROWTYPE; r numeric;c numeric;
BEGIN
 SELECT * INTO cap FROM plugin_finance_ar_capacity WHERE(tenant_id,book_id,year_id,parent_id,purpose)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.parent_id,NEW.purpose);
 SELECT COALESCE(sum(amount) FILTER(WHERE state='RESERVED'),0),COALESCE(sum(amount) FILTER(WHERE state='COMMITTED'),0) INTO r,c FROM plugin_finance_ar_usage WHERE(tenant_id,book_id,year_id,parent_id,purpose)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.parent_id,NEW.purpose);
 IF cap.reserved IS DISTINCT FROM r OR cap.committed IS DISTINCT FROM c THEN RAISE EXCEPTION 'AR_CAPACITY_INCONSISTENT'; END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER finance_ar_capacity_consistent AFTER INSERT OR UPDATE ON plugin_finance_ar_capacity DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION plugin_finance_ar_consistent_capacity();
CREATE CONSTRAINT TRIGGER finance_ar_usage_consistent AFTER INSERT OR UPDATE ON plugin_finance_ar_usage DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION plugin_finance_ar_consistent_capacity();

CREATE FUNCTION plugin_finance_ar_consistent_object() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r plugin_finance_ar_object%ROWTYPE; wanted text; n integer; total numeric;
BEGIN
 SELECT * INTO r FROM plugin_finance_ar_object WHERE(tenant_id,book_id,year_id,id)=(NEW.tenant_id,NEW.book_id,NEW.year_id,CASE WHEN TG_TABLE_NAME='plugin_finance_ar_usage' THEN (to_jsonb(NEW)->>'consumer_id')::uuid ELSE NEW.id END);
 wanted:=CASE WHEN r.reversed_by IS NOT NULL THEN 'REVERSED' WHEN r.state IN('SUBMITTED','APPROVED') THEN 'RESERVED' WHEN r.state IN('CONFIRMED','POSTED') THEN 'COMMITTED' ELSE NULL END;
 IF r.kind IN('PERFORMANCE','RECOGNITION','ALLOCATION') THEN
  SELECT count(*),COALESCE(sum(amount),0) INTO n,total FROM plugin_finance_ar_usage WHERE(tenant_id,book_id,year_id,consumer_id)=(r.tenant_id,r.book_id,r.year_id,r.id) AND state=wanted;
  IF wanted IS NOT NULL AND (n<>CASE WHEN r.kind='ALLOCATION' THEN 1+jsonb_array_length(r.initial_payload->'items') ELSE 1 END OR total<>r.amount*CASE WHEN r.kind='ALLOCATION' THEN 2 ELSE 1 END) THEN RAISE EXCEPTION 'AR_USAGE_INCOMPLETE'; END IF;
  IF EXISTS(SELECT 1 FROM plugin_finance_ar_usage WHERE(tenant_id,book_id,year_id,consumer_id)=(r.tenant_id,r.book_id,r.year_id,r.id) AND state IN('RESERVED','COMMITTED') AND state IS DISTINCT FROM wanted) THEN RAISE EXCEPTION 'AR_USAGE_INCONSISTENT'; END IF;
 END IF;
 IF r.reversed_by IS NOT NULL AND EXISTS(SELECT 1 FROM plugin_finance_ar_usage WHERE(tenant_id,book_id,year_id,parent_id)=(r.tenant_id,r.book_id,r.year_id,r.id) AND state IN('RESERVED','COMMITTED')) THEN RAISE EXCEPTION 'AR_DEPENDENCY_PRESENT'; END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER finance_ar_object_consistent AFTER INSERT OR UPDATE ON plugin_finance_ar_object DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION plugin_finance_ar_consistent_object();
CREATE CONSTRAINT TRIGGER finance_ar_usage_object_consistent AFTER INSERT OR UPDATE ON plugin_finance_ar_usage DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION plugin_finance_ar_consistent_object();
