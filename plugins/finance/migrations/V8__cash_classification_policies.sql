-- Independent bounded policy publication only. No cash claim, usage or financial fact is changed.
CREATE TABLE plugin_finance_cash_policy_catalog (
 standard text NOT NULL,template_id text NOT NULL,dictionary_version text NOT NULL,transaction_kind text NOT NULL,business_purpose text NOT NULL,direction text NOT NULL CHECK(direction IN('IN','OUT')),semantic_leaf text NOT NULL,row_id text NOT NULL,
 PRIMARY KEY(standard,template_id,dictionary_version,transaction_kind,business_purpose,direction,semantic_leaf,row_id));
INSERT INTO plugin_finance_cash_policy_catalog VALUES
 ('CAS','CAS-CF-2019-A2','ordinary-cash-policy-dictionary/1','ORDINARY_CUSTOMER_RECEIPT','SALES_GOODS_SERVICES_GROSS','IN','OP_SALES','C01'),
 ('CAS','CAS-CF-2019-A2','ordinary-cash-policy-dictionary/1','ORDINARY_SUPPLIER_PAYMENT','OPERATING_GOODS_SERVICES_GROSS','OUT','OP_PURCHASE','C05'),
 ('CAS','CAS-CF-2019-A2','ordinary-cash-policy-dictionary/1','ORDINARY_PAYROLL','OPERATING_EMPLOYEE_PAY_GROSS','OUT','OP_PAYROLL','C06'),
 ('CAS','CAS-CF-2019-A2','ordinary-cash-policy-dictionary/1','ORDINARY_TAX_PAYMENT','ORDINARY_OPERATING_TAX_ACTUAL','OUT','OP_TAX','C07'),
 ('CAS','CAS-CF-2019-A2','ordinary-cash-policy-dictionary/1','ORDINARY_BORROWING_RECEIPT','BORROWING_PRINCIPAL_CASH','IN','FIN_DEBT_IN','C24'),
 ('CAS','CAS-CF-2019-A2','ordinary-cash-policy-dictionary/1','ORDINARY_PRINCIPAL_REPAYMENT','BORROWING_PRINCIPAL_ONLY','OUT','FIN_PRINCIPAL_OUT','C27'),
 ('CAS','CAS-CF-2019-A2','ordinary-cash-policy-dictionary/1','ORDINARY_CAPITAL_RECEIPT','EQUITY_CAPITAL_CASH_GROSS','IN','FIN_EQUITY_IN','C23'),
 ('SMALL','SBE-CF-2011','ordinary-cash-policy-dictionary/1','ORDINARY_CUSTOMER_RECEIPT','SALES_GOODS_SERVICES_GROSS','IN','SBE_OP_SALES','1'),
 ('SMALL','SBE-CF-2011','ordinary-cash-policy-dictionary/1','ORDINARY_SUPPLIER_PAYMENT','OPERATING_GOODS_SERVICES_GROSS','OUT','SBE_OP_PURCHASE','3'),
 ('SMALL','SBE-CF-2011','ordinary-cash-policy-dictionary/1','ORDINARY_PAYROLL','OPERATING_EMPLOYEE_PAY_GROSS','OUT','SBE_OP_PAYROLL','4'),
 ('SMALL','SBE-CF-2011','ordinary-cash-policy-dictionary/1','ORDINARY_TAX_PAYMENT','ORDINARY_OPERATING_TAX_ACTUAL','OUT','SBE_OP_TAX','5'),
 ('SMALL','SBE-CF-2011','ordinary-cash-policy-dictionary/1','ORDINARY_BORROWING_RECEIPT','BORROWING_PRINCIPAL_CASH','IN','SBE_FIN_DEBT_IN','14'),
 ('SMALL','SBE-CF-2011','ordinary-cash-policy-dictionary/1','ORDINARY_PRINCIPAL_REPAYMENT','BORROWING_PRINCIPAL_ONLY','OUT','SBE_FIN_PRINCIPAL_OUT','16'),
 ('SMALL','SBE-CF-2011','ordinary-cash-policy-dictionary/1','ORDINARY_CAPITAL_RECEIPT','EQUITY_CAPITAL_CASH_GROSS','IN','SBE_FIN_EQUITY_IN','15');
CREATE TRIGGER finance_cash_policy_catalog_frozen BEFORE INSERT OR UPDATE OR DELETE ON plugin_finance_cash_policy_catalog FOR EACH ROW EXECUTE FUNCTION plugin_finance_immutable();
CREATE TABLE plugin_finance_cash_policy_guard (
 tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,publication_version bigint NOT NULL DEFAULT 0 CHECK(publication_version>=0),PRIMARY KEY(tenant_id,book_id,year_id),
 FOREIGN KEY(tenant_id,book_id,year_id) REFERENCES plugin_finance_year_guard(tenant_id,book_id,year_id));
CREATE TABLE plugin_finance_cash_policy_revision (
 tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,id uuid NOT NULL,legal_entity_id uuid NOT NULL,policy_code text NOT NULL CHECK(policy_code~'^[A-Z][A-Z0-9_]{2,49}$'),revision bigint NOT NULL CHECK(revision>0),supersedes_policy_id uuid,
 standard text NOT NULL,template_id text NOT NULL,dictionary_version text NOT NULL,transaction_kind text NOT NULL,business_purpose text NOT NULL,direction text NOT NULL,semantic_leaf text NOT NULL,row_id text NOT NULL,
 report_scope text NOT NULL CHECK(report_scope='SEPARATE'),currency text NOT NULL CHECK(currency='CNY'),transaction_form text NOT NULL CHECK(transaction_form='ORDINARY_GROSS_CASH'),effective_from date NOT NULL,effective_to date NOT NULL CHECK(effective_to>=effective_from),
 content_version bigint NOT NULL DEFAULT 1 CHECK(content_version=1),content_digest text NOT NULL CHECK(content_digest~'^[a-f0-9]{64}$'),content jsonb NOT NULL,prepared_by uuid NOT NULL,prepared_username text NOT NULL,last_edited_by uuid NOT NULL CHECK(last_edited_by=prepared_by),created_at timestamptz NOT NULL DEFAULT now(),
 status text NOT NULL DEFAULT 'DRAFT' CHECK(status IN('DRAFT','SUBMITTED','APPROVED','REJECTED','CANCELLED','RETIRED')),version bigint NOT NULL DEFAULT 1 CHECK(version>0),submitted_at timestamptz,submission_expires_at timestamptz,approved_by uuid,approved_at timestamptz,decision_by uuid,decision_at timestamptz,decision_reason text,retired_by uuid,retired_at timestamptz,
 PRIMARY KEY(tenant_id,book_id,year_id,id),UNIQUE(tenant_id,book_id,year_id,policy_code,revision),FOREIGN KEY(tenant_id,book_id,year_id) REFERENCES plugin_finance_cash_policy_guard(tenant_id,book_id,year_id),
 FOREIGN KEY(tenant_id,book_id,legal_entity_id) REFERENCES plugin_finance_book(tenant_id,id,legal_entity_id),FOREIGN KEY(tenant_id,book_id,year_id,supersedes_policy_id) REFERENCES plugin_finance_cash_policy_revision(tenant_id,book_id,year_id,id),
 FOREIGN KEY(standard,template_id,dictionary_version,transaction_kind,business_purpose,direction,semantic_leaf,row_id) REFERENCES plugin_finance_cash_policy_catalog(standard,template_id,dictionary_version,transaction_kind,business_purpose,direction,semantic_leaf,row_id));
CREATE INDEX finance_cash_policy_cursor ON plugin_finance_cash_policy_revision(tenant_id,book_id,year_id,created_at DESC,id DESC);
CREATE TABLE plugin_finance_cash_policy_evidence (
 tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,policy_id uuid NOT NULL,item_no integer NOT NULL CHECK(item_no BETWEEN 1 AND 10),original_id uuid NOT NULL,original_version bigint NOT NULL CHECK(original_version>0),sha256 text NOT NULL,filename text NOT NULL,purpose text NOT NULL CHECK(purpose IN('POLICY_BASIS','APPLICABILITY_SUPPORT')),description text NOT NULL CHECK(length(description) BETWEEN 1 AND 500),
 PRIMARY KEY(tenant_id,book_id,year_id,policy_id,item_no),UNIQUE(tenant_id,book_id,year_id,policy_id,original_id),
 FOREIGN KEY(tenant_id,book_id,year_id,policy_id) REFERENCES plugin_finance_cash_policy_revision(tenant_id,book_id,year_id,id),FOREIGN KEY(tenant_id,book_id,year_id,original_id,sha256) REFERENCES plugin_finance_original(tenant_id,book_id,year_id,id,sha256));
CREATE TABLE plugin_finance_cash_policy_retirement (
 tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,id uuid NOT NULL,policy_id uuid NOT NULL,expected_policy_version bigint NOT NULL,policy_content_digest text NOT NULL,content_digest text NOT NULL CHECK(content_digest~'^[a-f0-9]{64}$'),content jsonb NOT NULL,reason text NOT NULL CHECK(length(reason) BETWEEN 1 AND 1000),requested_by uuid NOT NULL,requested_username text NOT NULL,requested_at timestamptz NOT NULL DEFAULT now(),review_expires_at timestamptz NOT NULL,
 status text NOT NULL DEFAULT 'REQUESTED' CHECK(status IN('REQUESTED','APPROVED','REJECTED','CANCELLED')),version bigint NOT NULL DEFAULT 1 CHECK(version>0),reviewed_by uuid,reviewed_at timestamptz,decision_reason text,
 PRIMARY KEY(tenant_id,book_id,year_id,id),FOREIGN KEY(tenant_id,book_id,year_id,policy_id) REFERENCES plugin_finance_cash_policy_revision(tenant_id,book_id,year_id,id));
CREATE UNIQUE INDEX finance_cash_policy_one_retirement ON plugin_finance_cash_policy_retirement(tenant_id,book_id,year_id,policy_id) WHERE status='REQUESTED';
CREATE INDEX finance_cash_policy_retire_cursor ON plugin_finance_cash_policy_retirement(tenant_id,book_id,year_id,policy_id,requested_at DESC,id DESC);
CREATE TABLE plugin_finance_cash_policy_outbox (
 tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,id uuid NOT NULL,policy_id uuid NOT NULL,retirement_id uuid,source_version bigint NOT NULL,publication_version bigint NOT NULL,event_type text NOT NULL,payload jsonb NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,book_id,year_id,id),UNIQUE NULLS NOT DISTINCT(tenant_id,book_id,year_id,policy_id,retirement_id,source_version,event_type),FOREIGN KEY(tenant_id,book_id,year_id,policy_id) REFERENCES plugin_finance_cash_policy_revision(tenant_id,book_id,year_id,id),FOREIGN KEY(tenant_id,book_id,year_id,retirement_id) REFERENCES plugin_finance_cash_policy_retirement(tenant_id,book_id,year_id,id));
CREATE TRIGGER finance_cash_policy_outbox_frozen BEFORE UPDATE OR DELETE ON plugin_finance_cash_policy_outbox FOR EACH ROW EXECUTE FUNCTION plugin_finance_immutable();
CREATE FUNCTION plugin_finance_cash_policy_open(t uuid,b uuid,y uuid) RETURNS void LANGUAGE plpgsql AS $$ BEGIN
 PERFORM 1 FROM plugin_finance_year_guard WHERE tenant_id=t AND book_id=b AND year_id=y FOR UPDATE;
 PERFORM 1 FROM plugin_finance_cash_policy_guard WHERE tenant_id=t AND book_id=b AND year_id=y FOR UPDATE;
 PERFORM 1 FROM plugin_finance_year WHERE tenant_id=t AND book_id=b AND id=y AND status='OPEN' AND precision_profile='V4' AND NOT precision_review_required;
 IF NOT FOUND THEN RAISE EXCEPTION 'CASH_POLICY_YEAR_CLOSED';END IF;
END $$;
CREATE FUNCTION plugin_finance_cash_policy_check_evidence(t uuid,b uuid,y uuid,p uuid) RETURNS void LANGUAGE plpgsql AS $$ DECLARE n integer;roles integer;expected integer;BEGIN
 SELECT count(*),count(DISTINCT purpose) INTO n,roles FROM plugin_finance_cash_policy_evidence WHERE tenant_id=t AND book_id=b AND year_id=y AND policy_id=p;
 SELECT jsonb_array_length(content->'evidence') INTO expected FROM plugin_finance_cash_policy_revision WHERE tenant_id=t AND book_id=b AND year_id=y AND id=p;
 IF n<2 OR n>10 OR roles<>2 OR n<>expected THEN RAISE EXCEPTION 'CASH_POLICY_EVIDENCE_INCOMPLETE';END IF;
 IF EXISTS(SELECT 1 FROM plugin_finance_cash_policy_evidence e JOIN plugin_finance_original o ON (o.tenant_id,o.book_id,o.year_id,o.id)=(e.tenant_id,e.book_id,e.year_id,e.original_id) WHERE e.tenant_id=t AND e.book_id=b AND e.year_id=y AND e.policy_id=p AND (e.original_version<>o.version OR e.sha256<>o.sha256 OR e.filename<>o.filename OR encode(sha256(o.content),'hex')<>e.sha256 OR octet_length(o.content)<>o.size_bytes)) THEN RAISE EXCEPTION 'CASH_POLICY_EVIDENCE_STALE';END IF;
END $$;
CREATE FUNCTION plugin_finance_cash_policy_guard_fn() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE yr integer;std text;entity uuid;previous record;assertions jsonb;
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'CASH_POLICY_IMMUTABLE';END IF;
 PERFORM plugin_finance_cash_policy_open(NEW.tenant_id,NEW.book_id,NEW.year_id);
 SELECT y.year,b.standard,b.legal_entity_id INTO yr,std,entity FROM plugin_finance_year y JOIN plugin_finance_book b ON (b.tenant_id,b.id)=(y.tenant_id,y.book_id) WHERE y.tenant_id=NEW.tenant_id AND y.book_id=NEW.book_id AND y.id=NEW.year_id;
 IF std<>NEW.standard OR entity<>NEW.legal_entity_id OR extract(year FROM NEW.effective_from)<>yr OR extract(year FROM NEW.effective_to)<>yr THEN RAISE EXCEPTION 'CASH_POLICY_SCOPE_DATE_MISMATCH';END IF;
 IF TG_OP='INSERT' THEN
  IF NEW.status<>'DRAFT' OR NEW.version<>1 OR NEW.submitted_at IS NOT NULL OR NEW.submission_expires_at IS NOT NULL OR NEW.approved_by IS NOT NULL OR NEW.approved_at IS NOT NULL OR NEW.decision_by IS NOT NULL OR NEW.decision_at IS NOT NULL OR NEW.decision_reason IS NOT NULL OR NEW.retired_by IS NOT NULL OR NEW.retired_at IS NOT NULL THEN RAISE EXCEPTION 'CASH_POLICY_INITIAL_DRAFT_REQUIRED';END IF;
  SELECT * INTO previous FROM plugin_finance_cash_policy_revision WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id AND policy_code=NEW.policy_code ORDER BY revision DESC LIMIT 1;
  IF FOUND THEN
   IF NEW.revision<>previous.revision+1 OR NEW.supersedes_policy_id IS DISTINCT FROM previous.id OR (NEW.standard,NEW.template_id,NEW.dictionary_version,NEW.transaction_kind,NEW.business_purpose,NEW.direction,NEW.semantic_leaf,NEW.row_id,NEW.report_scope,NEW.currency,NEW.transaction_form) IS DISTINCT FROM (previous.standard,previous.template_id,previous.dictionary_version,previous.transaction_kind,previous.business_purpose,previous.direction,previous.semantic_leaf,previous.row_id,previous.report_scope,previous.currency,previous.transaction_form) THEN RAISE EXCEPTION 'CASH_POLICY_REVISION_CHAIN_INVALID';END IF;
  ELSIF NEW.revision<>1 OR NEW.supersedes_policy_id IS NOT NULL THEN RAISE EXCEPTION 'CASH_POLICY_INITIAL_REVISION_REQUIRED';END IF;
  assertions:=NEW.content->'assertions';
  IF assertions IS DISTINCT FROM '{"ordinaryGrossOnly":true,"singleLegalEntity":true,"cnyOnly":true,"purposeEvidenceRequiredForEachFutureUse":true,"noAutomaticAccountInference":true,"noInternalTransferRefundFxControlOrNetting":true,"noUnsupportedCapitalizedPayrollInterestOrTaxComponents":true}'::jsonb THEN RAISE EXCEPTION 'CASH_POLICY_BOUNDED_ASSERTIONS_REQUIRED';END IF;
  IF (NEW.content->>'schemaVersion',NEW.content->>'tenantId',NEW.content->>'bookId',NEW.content->>'yearId',NEW.content->>'legalEntityId',NEW.content->>'policyCode',NEW.content->>'supersedesPolicyId',NEW.content->>'standard',NEW.content->>'templateId',NEW.content->>'dictionaryVersion',NEW.content->>'transactionKind',NEW.content->>'businessPurpose',NEW.content->>'direction',NEW.content->>'semanticLeaf',NEW.content->>'rowId',NEW.content->>'reportScope',NEW.content->>'currency',NEW.content->>'transactionForm',NEW.content->>'effectiveFrom',NEW.content->>'effectiveTo',NEW.content->>'preparedBy') IS DISTINCT FROM ('cash-classification-policy/1',NEW.tenant_id::text,NEW.book_id::text,NEW.year_id::text,NEW.legal_entity_id::text,NEW.policy_code,NEW.supersedes_policy_id::text,NEW.standard,NEW.template_id,NEW.dictionary_version,NEW.transaction_kind,NEW.business_purpose,NEW.direction,NEW.semantic_leaf,NEW.row_id,NEW.report_scope,NEW.currency,NEW.transaction_form,NEW.effective_from::text,NEW.effective_to::text,NEW.prepared_by::text) OR (NEW.content->>'revision')::bigint IS DISTINCT FROM NEW.revision THEN RAISE EXCEPTION 'CASH_POLICY_SNAPSHOT_MISMATCH';END IF;
 ELSE
  IF (to_jsonb(NEW)-ARRAY['status','version','submitted_at','submission_expires_at','approved_by','approved_at','decision_by','decision_at','decision_reason','retired_by','retired_at']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['status','version','submitted_at','submission_expires_at','approved_by','approved_at','decision_by','decision_at','decision_reason','retired_by','retired_at']) OR NEW.version<>OLD.version+1 OR NOT ((OLD.status='DRAFT' AND NEW.status IN('SUBMITTED','CANCELLED')) OR (OLD.status='SUBMITTED' AND NEW.status IN('APPROVED','REJECTED','CANCELLED')) OR (OLD.status='APPROVED' AND NEW.status='RETIRED')) THEN RAISE EXCEPTION 'CASH_POLICY_CONTENT_OR_TRANSITION_INVALID';END IF;
  IF OLD.submitted_at IS NOT NULL AND (NEW.submitted_at,NEW.submission_expires_at) IS DISTINCT FROM (OLD.submitted_at,OLD.submission_expires_at) THEN RAISE EXCEPTION 'CASH_POLICY_SUBMISSION_IMMUTABLE';END IF;
  IF NEW.status='SUBMITTED' AND (NEW.submitted_at IS NULL OR NEW.submission_expires_at IS DISTINCT FROM NEW.submitted_at+interval '168 hours' OR NEW.approved_by IS NOT NULL OR NEW.approved_at IS NOT NULL OR NEW.decision_by IS NOT NULL OR NEW.decision_at IS NOT NULL OR NEW.decision_reason IS NOT NULL OR NEW.retired_by IS NOT NULL OR NEW.retired_at IS NOT NULL) THEN RAISE EXCEPTION 'CASH_POLICY_SUBMISSION_METADATA';END IF;
  IF NEW.status IN('APPROVED','REJECTED','CANCELLED') AND (NEW.decision_by IS NULL OR NEW.decision_at IS NULL OR coalesce(length(NEW.decision_reason),0) NOT BETWEEN 1 AND 1000) THEN RAISE EXCEPTION 'CASH_POLICY_DECISION_REQUIRED';END IF;
  IF NEW.status='CANCELLED' AND NEW.decision_by<>NEW.prepared_by THEN RAISE EXCEPTION 'CASH_POLICY_ORIGINAL_PREPARER_REQUIRED';END IF;
  IF NEW.status IN('APPROVED','REJECTED') AND NEW.decision_by IN(NEW.prepared_by,NEW.last_edited_by) THEN RAISE EXCEPTION 'CASH_POLICY_INDEPENDENT_REVIEW_REQUIRED';END IF;
  IF NEW.status IN('REJECTED','CANCELLED') AND (NEW.approved_by IS NOT NULL OR NEW.approved_at IS NOT NULL OR NEW.retired_by IS NOT NULL OR NEW.retired_at IS NOT NULL) THEN RAISE EXCEPTION 'CASH_POLICY_TERMINAL_METADATA';END IF;
  IF NEW.status='APPROVED' THEN
   IF NEW.submitted_at IS NULL OR NEW.submission_expires_at IS NULL OR NEW.submission_expires_at<=clock_timestamp() OR NEW.approved_by IS DISTINCT FROM NEW.decision_by OR NEW.approved_at IS DISTINCT FROM NEW.decision_at OR NEW.retired_by IS NOT NULL OR NEW.retired_at IS NOT NULL THEN RAISE EXCEPTION 'CASH_POLICY_APPROVAL_EXPIRED_OR_INVALID';END IF;
   PERFORM plugin_finance_cash_policy_check_evidence(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.id);
   IF EXISTS(SELECT 1 FROM plugin_finance_cash_policy_revision p WHERE p.tenant_id=NEW.tenant_id AND p.book_id=NEW.book_id AND p.year_id=NEW.year_id AND p.id<>NEW.id AND p.status='APPROVED' AND (p.legal_entity_id,p.standard,p.template_id,p.dictionary_version,p.report_scope,p.currency,p.transaction_form,p.transaction_kind,p.business_purpose)=(NEW.legal_entity_id,NEW.standard,NEW.template_id,NEW.dictionary_version,NEW.report_scope,NEW.currency,NEW.transaction_form,NEW.transaction_kind,NEW.business_purpose) AND p.effective_from<=NEW.effective_to AND p.effective_to>=NEW.effective_from) THEN RAISE EXCEPTION 'CASH_POLICY_OVERLAP';END IF;
  END IF;
  IF NEW.status='RETIRED' THEN
   IF (NEW.approved_by,NEW.approved_at,NEW.decision_by,NEW.decision_at,NEW.decision_reason) IS DISTINCT FROM (OLD.approved_by,OLD.approved_at,OLD.decision_by,OLD.decision_at,OLD.decision_reason) THEN RAISE EXCEPTION 'CASH_POLICY_HISTORIC_APPROVAL_IMMUTABLE';END IF;
   PERFORM 1 FROM plugin_finance_cash_policy_retirement WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id AND policy_id=NEW.id AND status='APPROVED' AND reviewed_by=NEW.retired_by AND reviewed_at=NEW.retired_at;
   IF NOT FOUND OR NEW.retired_at IS NULL OR NEW.retired_by IS NULL THEN RAISE EXCEPTION 'CASH_POLICY_REVIEWED_RETIREMENT_REQUIRED';END IF;
  END IF;
 END IF;RETURN NEW;
END $$;
CREATE TRIGGER finance_cash_policy_revision_guard BEFORE INSERT OR UPDATE OR DELETE ON plugin_finance_cash_policy_revision FOR EACH ROW EXECUTE FUNCTION plugin_finance_cash_policy_guard_fn();
CREATE FUNCTION plugin_finance_cash_policy_evidence_guard() RETURNS trigger LANGUAGE plpgsql AS $$ DECLARE p record;o record;e jsonb;BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'CASH_POLICY_EVIDENCE_IMMUTABLE';END IF;
 SELECT status,content INTO STRICT p FROM plugin_finance_cash_policy_revision WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id AND id=NEW.policy_id;
 SELECT * INTO STRICT o FROM plugin_finance_original WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id AND id=NEW.original_id;
 e:=p.content->'evidence'->(NEW.item_no-1);
 IF p.status<>'DRAFT' OR (NEW.original_version,NEW.sha256,NEW.filename) IS DISTINCT FROM (o.version,o.sha256,o.filename) OR encode(sha256(o.content),'hex')<>NEW.sha256 OR e IS DISTINCT FROM jsonb_build_object('originalId',NEW.original_id,'originalVersion',NEW.original_version,'sha256',NEW.sha256,'filename',NEW.filename,'purpose',NEW.purpose,'description',NEW.description) THEN RAISE EXCEPTION 'CASH_POLICY_EVIDENCE_SNAPSHOT_MISMATCH';END IF;RETURN NEW;
END $$;
CREATE TRIGGER finance_cash_policy_evidence_guard BEFORE INSERT OR UPDATE OR DELETE ON plugin_finance_cash_policy_evidence FOR EACH ROW EXECUTE FUNCTION plugin_finance_cash_policy_evidence_guard();
CREATE FUNCTION plugin_finance_cash_policy_retire_guard() RETURNS trigger LANGUAGE plpgsql AS $$ DECLARE p record;BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'CASH_POLICY_RETIREMENT_IMMUTABLE';END IF;
 PERFORM plugin_finance_cash_policy_open(NEW.tenant_id,NEW.book_id,NEW.year_id);
 SELECT * INTO STRICT p FROM plugin_finance_cash_policy_revision WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id AND id=NEW.policy_id FOR UPDATE;
 IF p.status<>'APPROVED' OR p.version<>NEW.expected_policy_version OR p.content_digest<>NEW.policy_content_digest THEN RAISE EXCEPTION 'CASH_POLICY_RETIREMENT_STALE';END IF;
 IF TG_OP='INSERT' THEN
  IF NEW.status<>'REQUESTED' OR NEW.version<>1 OR NEW.review_expires_at IS DISTINCT FROM NEW.requested_at+interval '168 hours' OR NEW.reviewed_by IS NOT NULL OR NEW.reviewed_at IS NOT NULL OR NEW.decision_reason IS NOT NULL THEN RAISE EXCEPTION 'CASH_POLICY_RETIREMENT_INITIAL_REQUIRED';END IF;
  IF (NEW.content->>'policyId',NEW.content->>'policyContentDigest',NEW.content->>'requestedBy',NEW.content->>'reason') IS DISTINCT FROM (NEW.policy_id::text,NEW.policy_content_digest,NEW.requested_by::text,NEW.reason) OR (NEW.content->>'expectedPolicyVersion')::bigint IS DISTINCT FROM NEW.expected_policy_version THEN RAISE EXCEPTION 'CASH_POLICY_RETIREMENT_SNAPSHOT_MISMATCH';END IF;
 ELSE
  IF (to_jsonb(NEW)-ARRAY['status','version','reviewed_by','reviewed_at','decision_reason']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['status','version','reviewed_by','reviewed_at','decision_reason']) OR OLD.status<>'REQUESTED' OR NEW.status NOT IN('APPROVED','REJECTED','CANCELLED') OR NEW.version<>OLD.version+1 OR NEW.reviewed_by IS NULL OR NEW.reviewed_at IS NULL OR coalesce(length(NEW.decision_reason),0) NOT BETWEEN 1 AND 1000 THEN RAISE EXCEPTION 'CASH_POLICY_RETIREMENT_TRANSITION_INVALID';END IF;
  IF NEW.status='CANCELLED' AND NEW.reviewed_by<>NEW.requested_by THEN RAISE EXCEPTION 'CASH_POLICY_RETIREMENT_REQUESTER_REQUIRED';END IF;
  IF NEW.status IN('APPROVED','REJECTED') AND NEW.reviewed_by IN(NEW.requested_by,p.prepared_by,p.last_edited_by) THEN RAISE EXCEPTION 'CASH_POLICY_RETIREMENT_INDEPENDENT_REVIEW_REQUIRED';END IF;
  IF NEW.status='APPROVED' AND NEW.review_expires_at<=clock_timestamp() THEN RAISE EXCEPTION 'CASH_POLICY_RETIREMENT_EXPIRED';END IF;
 END IF;RETURN NEW;
END $$;
CREATE TRIGGER finance_cash_policy_retire_guard BEFORE INSERT OR UPDATE OR DELETE ON plugin_finance_cash_policy_retirement FOR EACH ROW EXECUTE FUNCTION plugin_finance_cash_policy_retire_guard();
CREATE FUNCTION plugin_finance_cash_policy_event() RETURNS trigger LANGUAGE plpgsql AS $$ DECLARE pv bigint;pid uuid;rid uuid;BEGIN
 IF TG_TABLE_NAME='plugin_finance_cash_policy_revision' THEN pid:=NEW.id;rid:=NULL;ELSE pid:=NEW.policy_id;rid:=NEW.id;END IF;
 IF rid IS NOT NULL AND NEW.status='APPROVED' THEN UPDATE plugin_finance_cash_policy_revision SET status='RETIRED',version=version+1,retired_by=NEW.reviewed_by,retired_at=NEW.reviewed_at WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id AND id=NEW.policy_id AND status='APPROVED';IF NOT FOUND THEN RAISE EXCEPTION 'CASH_POLICY_RETIREMENT_STALE';END IF;END IF;
 IF (rid IS NULL AND NEW.status IN('APPROVED','RETIRED')) OR (rid IS NOT NULL AND NEW.status IN('REQUESTED','REJECTED','CANCELLED')) THEN UPDATE plugin_finance_cash_policy_guard SET publication_version=publication_version+1 WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id RETURNING publication_version INTO pv;ELSE SELECT publication_version INTO pv FROM plugin_finance_cash_policy_guard WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id;END IF;
 INSERT INTO plugin_finance_cash_policy_outbox VALUES(NEW.tenant_id,NEW.book_id,NEW.year_id,gen_random_uuid(),pid,rid,NEW.version,pv,CASE WHEN rid IS NULL THEN 'POLICY_' ELSE 'RETIREMENT_' END||NEW.status,to_jsonb(NEW),now());
RETURN NEW;
END $$;
CREATE TRIGGER finance_cash_policy_event AFTER INSERT OR UPDATE OF status ON plugin_finance_cash_policy_revision FOR EACH ROW EXECUTE FUNCTION plugin_finance_cash_policy_event();
CREATE TRIGGER finance_cash_policy_retirement_event AFTER INSERT OR UPDATE OF status ON plugin_finance_cash_policy_retirement FOR EACH ROW EXECUTE FUNCTION plugin_finance_cash_policy_event();
