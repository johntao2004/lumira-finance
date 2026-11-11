-- True typed source-review reliance and independently approved release. Published V1-V8 unchanged.
DO $$ BEGIN IF EXISTS(SELECT 1 FROM plugin_finance_cash_claim_usage) THEN RAISE EXCEPTION 'CASH_USAGE_UNEXPECTED_PRE_V9_DATA';END IF;END $$;
CREATE TABLE plugin_finance_cash_source_review_guard (
 tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,review_version bigint NOT NULL DEFAULT 0 CHECK(review_version>=0),PRIMARY KEY(tenant_id,book_id,year_id),FOREIGN KEY(tenant_id,book_id,year_id) REFERENCES plugin_finance_year_guard(tenant_id,book_id,year_id));
CREATE TABLE plugin_finance_cash_source_review (
 tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,id uuid NOT NULL,legal_entity_id uuid NOT NULL,raw_registration_id uuid NOT NULL,claim_id uuid NOT NULL,claim_kind text NOT NULL CHECK(claim_kind IN('RAW_REGISTRATION','MATCH_CORRECTION')),claim_generation bigint NOT NULL,claim_content_digest text NOT NULL,source_match_generation bigint NOT NULL,
 content_version bigint NOT NULL DEFAULT 1 CHECK(content_version=1),content_digest text NOT NULL CHECK(content_digest~'^[a-f0-9]{64}$'),content jsonb NOT NULL,prepared_by uuid NOT NULL,prepared_username text NOT NULL,last_edited_by uuid NOT NULL CHECK(last_edited_by=prepared_by),created_at timestamptz NOT NULL DEFAULT now(),
 status text NOT NULL DEFAULT 'DRAFT' CHECK(status IN('DRAFT','SUBMITTED','APPROVED','REJECTED','CANCELLED','RELEASED')),version bigint NOT NULL DEFAULT 1 CHECK(version>0),submitted_at timestamptz,submission_expires_at timestamptz,approved_by uuid,approved_at timestamptz,approved_usage_id uuid,decision_by uuid,decision_at timestamptz,decision_reason text,release_decision_id uuid,released_by uuid,released_at timestamptz,
 PRIMARY KEY(tenant_id,book_id,year_id,id),UNIQUE(tenant_id,book_id,year_id,id,claim_id,raw_registration_id,claim_generation,claim_content_digest,content_digest,approved_usage_id),FOREIGN KEY(tenant_id,book_id,year_id) REFERENCES plugin_finance_cash_source_review_guard(tenant_id,book_id,year_id),
 FOREIGN KEY(tenant_id,book_id,legal_entity_id) REFERENCES plugin_finance_book(tenant_id,id,legal_entity_id),
 FOREIGN KEY(tenant_id,book_id,year_id,claim_id,raw_registration_id,claim_generation,claim_content_digest) REFERENCES plugin_finance_cash_leg_claim(tenant_id,book_id,year_id,id,raw_registration_id,claim_generation,claim_content_digest));
CREATE UNIQUE INDEX finance_cash_review_one_pending ON plugin_finance_cash_source_review(tenant_id,book_id,year_id,claim_id) WHERE status IN('DRAFT','SUBMITTED');
CREATE UNIQUE INDEX finance_cash_review_one_approved ON plugin_finance_cash_source_review(tenant_id,book_id,year_id,claim_id) WHERE status='APPROVED';
CREATE INDEX finance_cash_review_cursor ON plugin_finance_cash_source_review(tenant_id,book_id,year_id,created_at DESC,id DESC);
CREATE TABLE plugin_finance_cash_source_review_evidence (
 tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,review_id uuid NOT NULL,item_no integer NOT NULL CHECK(item_no BETWEEN 1 AND 10),original_id uuid NOT NULL,original_version bigint NOT NULL,sha256 text NOT NULL,filename text NOT NULL,purpose text NOT NULL CHECK(purpose IN('BUSINESS_PURPOSE_SUPPORT','FACT_CORROBORATION')),description text NOT NULL CHECK(length(description) BETWEEN 1 AND 500),
 PRIMARY KEY(tenant_id,book_id,year_id,review_id,item_no),UNIQUE(tenant_id,book_id,year_id,review_id,original_id),FOREIGN KEY(tenant_id,book_id,year_id,review_id) REFERENCES plugin_finance_cash_source_review(tenant_id,book_id,year_id,id),FOREIGN KEY(tenant_id,book_id,year_id,original_id,sha256) REFERENCES plugin_finance_original(tenant_id,book_id,year_id,id,sha256));
ALTER TABLE plugin_finance_cash_claim_usage ADD COLUMN id uuid NOT NULL,ADD COLUMN usage_kind text NOT NULL CHECK(usage_kind='SOURCE_REVIEW'),ADD COLUMN source_review_id uuid NOT NULL,ADD COLUMN consumer_content_digest text NOT NULL,ADD COLUMN state text NOT NULL DEFAULT 'ACTIVE' CHECK(state IN('ACTIVE','RELEASED')),ADD COLUMN approved_by uuid NOT NULL,ADD COLUMN approved_at timestamptz NOT NULL,ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),ADD COLUMN release_decision_id uuid,ADD COLUMN released_by uuid,ADD COLUMN released_at timestamptz;
ALTER TABLE plugin_finance_cash_claim_usage ADD PRIMARY KEY(tenant_id,book_id,year_id,id),ADD UNIQUE(tenant_id,book_id,year_id,source_review_id),ADD UNIQUE(tenant_id,book_id,year_id,id,source_review_id,claim_id,raw_registration_id,claim_generation,claim_content_digest,consumer_content_digest),ADD CONSTRAINT finance_cash_usage_real_consumer FOREIGN KEY(tenant_id,book_id,year_id,source_review_id,claim_id,raw_registration_id,claim_generation,claim_content_digest,consumer_content_digest,id) REFERENCES plugin_finance_cash_source_review(tenant_id,book_id,year_id,id,claim_id,raw_registration_id,claim_generation,claim_content_digest,content_digest,approved_usage_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE plugin_finance_cash_source_review ADD CONSTRAINT finance_cash_review_real_usage FOREIGN KEY(tenant_id,book_id,year_id,approved_usage_id,id,claim_id,raw_registration_id,claim_generation,claim_content_digest,content_digest) REFERENCES plugin_finance_cash_claim_usage(tenant_id,book_id,year_id,id,source_review_id,claim_id,raw_registration_id,claim_generation,claim_content_digest,consumer_content_digest) DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE plugin_finance_cash_source_review_release (
 tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,id uuid NOT NULL,review_id uuid NOT NULL,usage_id uuid NOT NULL,claim_id uuid NOT NULL,raw_registration_id uuid NOT NULL,claim_generation bigint NOT NULL,claim_content_digest text NOT NULL,review_content_digest text NOT NULL,expected_review_version bigint NOT NULL,
 content_digest text NOT NULL CHECK(content_digest~'^[a-f0-9]{64}$'),content jsonb NOT NULL,reason text NOT NULL CHECK(length(reason) BETWEEN 1 AND 1000),requested_by uuid NOT NULL,requested_username text NOT NULL,requested_at timestamptz NOT NULL DEFAULT now(),review_expires_at timestamptz NOT NULL,status text NOT NULL DEFAULT 'REQUESTED' CHECK(status IN('REQUESTED','APPROVED','REJECTED','CANCELLED')),version bigint NOT NULL DEFAULT 1 CHECK(version>0),reviewed_by uuid,reviewed_at timestamptz,decision_reason text,
 PRIMARY KEY(tenant_id,book_id,year_id,id),UNIQUE(tenant_id,book_id,year_id,id,review_id,usage_id,review_content_digest),
 FOREIGN KEY(tenant_id,book_id,year_id,usage_id,review_id,claim_id,raw_registration_id,claim_generation,claim_content_digest,review_content_digest) REFERENCES plugin_finance_cash_claim_usage(tenant_id,book_id,year_id,id,source_review_id,claim_id,raw_registration_id,claim_generation,claim_content_digest,consumer_content_digest));
CREATE UNIQUE INDEX finance_cash_review_one_release ON plugin_finance_cash_source_review_release(tenant_id,book_id,year_id,review_id) WHERE status='REQUESTED';
CREATE INDEX finance_cash_review_release_cursor ON plugin_finance_cash_source_review_release(tenant_id,book_id,year_id,review_id,requested_at DESC,id DESC);
ALTER TABLE plugin_finance_cash_source_review ADD CONSTRAINT finance_cash_review_release_decision FOREIGN KEY(tenant_id,book_id,year_id,release_decision_id,id,approved_usage_id,content_digest) REFERENCES plugin_finance_cash_source_review_release(tenant_id,book_id,year_id,id,review_id,usage_id,review_content_digest) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE plugin_finance_cash_claim_usage ADD CONSTRAINT finance_cash_usage_release_decision FOREIGN KEY(tenant_id,book_id,year_id,release_decision_id,source_review_id,id,consumer_content_digest) REFERENCES plugin_finance_cash_source_review_release(tenant_id,book_id,year_id,id,review_id,usage_id,review_content_digest) DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE plugin_finance_cash_source_review_outbox (
 tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,id uuid NOT NULL,review_id uuid NOT NULL,release_id uuid,source_version bigint NOT NULL,review_version bigint NOT NULL,event_type text NOT NULL,payload jsonb NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),PRIMARY KEY(tenant_id,book_id,year_id,id),UNIQUE NULLS NOT DISTINCT(tenant_id,book_id,year_id,review_id,release_id,source_version,event_type),FOREIGN KEY(tenant_id,book_id,year_id,review_id) REFERENCES plugin_finance_cash_source_review(tenant_id,book_id,year_id,id),FOREIGN KEY(tenant_id,book_id,year_id,release_id) REFERENCES plugin_finance_cash_source_review_release(tenant_id,book_id,year_id,id));
CREATE TRIGGER finance_cash_review_outbox_immutable BEFORE UPDATE OR DELETE ON plugin_finance_cash_source_review_outbox FOR EACH ROW EXECUTE FUNCTION plugin_finance_immutable();
CREATE FUNCTION plugin_finance_cash_review_snapshot(t uuid,b uuid,y uuid,rid uuid,cid uuid) RETURNS jsonb LANGUAGE plpgsql AS $$
DECLARE r record;c record;g bigint;source_original record;scope_original record;result jsonb;
BEGIN
 SELECT * INTO STRICT r FROM plugin_finance_cash_movement_registration WHERE tenant_id=t AND book_id=b AND year_id=y AND id=rid;
 SELECT * INTO STRICT c FROM plugin_finance_cash_leg_claim WHERE tenant_id=t AND book_id=b AND year_id=y AND id=cid AND raw_registration_id=rid AND state='ACTIVE';
 SELECT source_generation INTO STRICT g FROM plugin_finance_cash_source_match_guard WHERE tenant_id=t AND book_id=b AND year_id=y AND raw_registration_id=rid;
 IF g<>c.claim_generation OR (c.claim_kind='RAW_REGISTRATION' AND r.status<>'REGISTERED') OR (c.claim_kind='MATCH_CORRECTION' AND r.status<>'VOIDED') THEN RAISE EXCEPTION 'CASH_REVIEW_CLAIM_STALE';END IF;
 IF c.claim_kind='MATCH_CORRECTION' AND NOT EXISTS(SELECT 1 FROM plugin_finance_cash_match_correction WHERE tenant_id=t AND book_id=b AND year_id=y AND id=c.correction_id AND status='APPROVED' AND approved_claim_id=c.id) THEN RAISE EXCEPTION 'CASH_REVIEW_CORRECTION_STALE';END IF;
 IF EXISTS(SELECT 1 FROM plugin_finance_cash_match_withdrawal WHERE tenant_id=t AND book_id=b AND year_id=y AND active_claim_id=cid AND status='REQUESTED') THEN RAISE EXCEPTION 'CASH_REVIEW_PENDING_WITHDRAWAL';END IF;
 PERFORM plugin_finance_cash_match_target(t,b,y,rid,c.journal_id,c.line_no,c.journal_version,c.posting_seq,c.posting_digest);
 SELECT * INTO STRICT source_original FROM plugin_finance_original WHERE tenant_id=t AND book_id=b AND year_id=y AND id=r.source_original_id;
 SELECT * INTO STRICT scope_original FROM plugin_finance_original WHERE tenant_id=t AND book_id=b AND year_id=y AND id=(r.content->'scopeItemSnapshot'->>'evidenceOriginalId')::uuid;
 IF (source_original.id::text,source_original.version,source_original.sha256,source_original.filename) IS DISTINCT FROM (r.content->'originalSnapshot'->>'id',(r.content->'originalSnapshot'->>'version')::bigint,r.source_original_sha256,r.content->'originalSnapshot'->>'filename') OR (scope_original.version,scope_original.sha256,scope_original.filename) IS DISTINCT FROM ((r.content->'scopeItemSnapshot'->>'originalVersion')::bigint,r.content->'scopeItemSnapshot'->>'originalSha256',r.content->'scopeItemSnapshot'->>'originalFilename') THEN RAISE EXCEPTION 'CASH_REVIEW_ORIGINAL_STALE';END IF;
 result:=jsonb_build_object('rawRegistrationId',r.id,'rawContentDigest',r.content_digest,'claimId',c.id,'claimKind',c.claim_kind,'claimGeneration',c.claim_generation,'claimContentDigest',c.claim_content_digest,'sourceMatchGeneration',g,'correctionId',c.correction_id,'journalId',c.journal_id,'lineNo',c.line_no,'journalVersion',c.journal_version,'postingSeq',c.posting_seq,'postingDigest',c.posting_digest,'accountId',r.account_id,'cashScopeRevisionId',r.cash_scope_revision_id,'cashScopeVersion',r.approved_scope_version,'cashScopeDigest',r.scope_content_digest,'accountSnapshotDigest',r.content->'scopeItemSnapshot'->>'accountVersionDigest','occurredDate',r.occurred_date,'direction',r.direction,'currency',r.currency,'normalizedAmount',r.amount::text,'sourceOriginal',jsonb_build_object('originalId',source_original.id,'version',source_original.version,'sha256',source_original.sha256,'filename',source_original.filename),'scopeOriginal',jsonb_build_object('originalId',scope_original.id,'version',scope_original.version,'sha256',scope_original.sha256,'filename',scope_original.filename),'rawVersion',r.version,'sourceIdentity',jsonb_build_object('sourceSystem',r.source_system,'sourceMovementKey',r.source_movement_key,'sourceDocumentId',r.source_document_id,'sourceDocumentVersion',r.source_document_version,'evidenceKind',r.evidence_kind,'evidenceOriginalId',r.source_original_id,'sourceRowReference',r.source_row_reference,'transactionKind',r.transaction_kind,'description',r.content->>'description','preparedBy',r.prepared_by));
 RETURN result;
END $$;
CREATE FUNCTION plugin_finance_cash_review_evidence_check(t uuid,b uuid,y uuid,rid uuid) RETURNS void LANGUAGE plpgsql AS $$ DECLARE n integer;needed integer;BEGIN
 SELECT jsonb_array_length(content->'evidence') INTO STRICT needed FROM plugin_finance_cash_source_review WHERE tenant_id=t AND book_id=b AND year_id=y AND id=rid;
 SELECT count(*) INTO n FROM plugin_finance_cash_source_review_evidence WHERE tenant_id=t AND book_id=b AND year_id=y AND review_id=rid;
 IF n<1 OR n>10 OR n<>needed OR NOT EXISTS(SELECT 1 FROM plugin_finance_cash_source_review_evidence WHERE tenant_id=t AND book_id=b AND year_id=y AND review_id=rid AND purpose='BUSINESS_PURPOSE_SUPPORT') THEN RAISE EXCEPTION 'CASH_REVIEW_PURPOSE_EVIDENCE_REQUIRED';END IF;
 IF EXISTS(SELECT 1 FROM plugin_finance_cash_source_review_evidence e JOIN plugin_finance_original o ON (o.tenant_id,o.book_id,o.year_id,o.id)=(e.tenant_id,e.book_id,e.year_id,e.original_id) WHERE e.tenant_id=t AND e.book_id=b AND e.year_id=y AND e.review_id=rid AND (e.original_version<>o.version OR e.sha256<>o.sha256 OR e.filename<>o.filename OR encode(sha256(o.content),'hex')<>e.sha256 OR octet_length(o.content)<>o.size_bytes)) THEN RAISE EXCEPTION 'CASH_REVIEW_EVIDENCE_STALE';END IF;
END $$;
CREATE FUNCTION plugin_finance_cash_review_guard_fn() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE raw record;snapshot jsonb;
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'CASH_REVIEW_IMMUTABLE';END IF;
 SELECT * INTO STRICT raw FROM plugin_finance_cash_movement_registration WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id AND id=NEW.raw_registration_id FOR UPDATE;
 PERFORM plugin_finance_cash_match_open(NEW.tenant_id,NEW.book_id,NEW.year_id,raw.occurred_date);
 IF TG_OP='INSERT' THEN
  IF NEW.status<>'DRAFT' OR NEW.version<>1 OR NEW.submitted_at IS NOT NULL OR NEW.submission_expires_at IS NOT NULL OR NEW.approved_by IS NOT NULL OR NEW.approved_at IS NOT NULL OR NEW.approved_usage_id IS NOT NULL OR NEW.decision_by IS NOT NULL OR NEW.decision_at IS NOT NULL OR NEW.decision_reason IS NOT NULL OR NEW.release_decision_id IS NOT NULL OR NEW.released_by IS NOT NULL OR NEW.released_at IS NOT NULL THEN RAISE EXCEPTION 'CASH_REVIEW_INITIAL_DRAFT_REQUIRED';END IF;
  snapshot:=plugin_finance_cash_review_snapshot(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.raw_registration_id,NEW.claim_id);
  IF NEW.content->'claimSnapshot' IS DISTINCT FROM snapshot OR (NEW.claim_kind,NEW.claim_generation,NEW.claim_content_digest,NEW.source_match_generation,NEW.legal_entity_id) IS DISTINCT FROM (snapshot->>'claimKind',(snapshot->>'claimGeneration')::bigint,snapshot->>'claimContentDigest',(snapshot->>'sourceMatchGeneration')::bigint,raw.legal_entity_id) OR NEW.content->>'method' IS DISTINCT FROM 'HUMAN_DOCUMENT_REVIEW' OR NEW.content->>'conclusion' IS DISTINCT FROM 'SUPPORTED_FOR_PREPARATION' OR (NEW.content->>'tenantId',NEW.content->>'bookId',NEW.content->>'yearId',NEW.content->>'legalEntityId',NEW.content->>'preparedBy',NEW.content->>'schemaVersion') IS DISTINCT FROM (NEW.tenant_id::text,NEW.book_id::text,NEW.year_id::text,raw.legal_entity_id::text,NEW.prepared_by::text,'cash-source-review/1') THEN RAISE EXCEPTION 'CASH_REVIEW_SNAPSHOT_MISMATCH';END IF;
  IF NEW.content->'checklist' IS DISTINCT FROM '{"sourceRowInspected":true,"actualEvidenceBytesInspected":true,"scopeInstrumentAndAccountInspected":true,"dateDirectionAndFullAmountMatched":true,"effectivePostedCashLegMatched":true,"businessPurposeEvidenceInspected":true,"noUnsupportedSpecialOrDuplicateFactsKnown":true,"humanReviewNotBankAuthenticity":true}'::jsonb OR coalesce(length(NEW.content->>'reason'),0) NOT BETWEEN 1 AND 1000 OR coalesce(length(NEW.content->>'businessPurposeStatement'),0) NOT BETWEEN 1 AND 2000 OR coalesce(length(NEW.content->>'factReviewStatement'),0) NOT BETWEEN 1 AND 4000 THEN RAISE EXCEPTION 'CASH_REVIEW_CHECKLIST_AND_STATEMENTS_REQUIRED';END IF;
 ELSE
  IF (to_jsonb(NEW)-ARRAY['status','version','submitted_at','submission_expires_at','approved_by','approved_at','approved_usage_id','decision_by','decision_at','decision_reason','release_decision_id','released_by','released_at']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['status','version','submitted_at','submission_expires_at','approved_by','approved_at','approved_usage_id','decision_by','decision_at','decision_reason','release_decision_id','released_by','released_at']) OR NEW.version<>OLD.version+1 OR NOT ((OLD.status='DRAFT' AND NEW.status IN('SUBMITTED','REJECTED','CANCELLED')) OR (OLD.status='SUBMITTED' AND NEW.status IN('APPROVED','REJECTED','CANCELLED')) OR (OLD.status='APPROVED' AND NEW.status='RELEASED')) THEN RAISE EXCEPTION 'CASH_REVIEW_CONTENT_OR_TRANSITION_INVALID';END IF;
  IF OLD.submitted_at IS NOT NULL AND (NEW.submitted_at,NEW.submission_expires_at) IS DISTINCT FROM (OLD.submitted_at,OLD.submission_expires_at) THEN RAISE EXCEPTION 'CASH_REVIEW_SUBMISSION_IMMUTABLE';END IF;
  IF NEW.status='SUBMITTED' AND (NEW.submitted_at IS NULL OR NEW.submission_expires_at IS DISTINCT FROM NEW.submitted_at+interval '168 hours' OR NEW.approved_by IS NOT NULL OR NEW.approved_at IS NOT NULL OR NEW.approved_usage_id IS NOT NULL OR NEW.decision_by IS NOT NULL OR NEW.decision_at IS NOT NULL OR NEW.decision_reason IS NOT NULL OR NEW.release_decision_id IS NOT NULL OR NEW.released_by IS NOT NULL OR NEW.released_at IS NOT NULL) THEN RAISE EXCEPTION 'CASH_REVIEW_SUBMISSION_METADATA';END IF;
  IF NEW.status IN('APPROVED','REJECTED','CANCELLED') AND (NEW.decision_by IS NULL OR NEW.decision_at IS NULL OR coalesce(length(NEW.decision_reason),0) NOT BETWEEN 1 AND 1000) THEN RAISE EXCEPTION 'CASH_REVIEW_DECISION_REQUIRED';END IF;
  IF NEW.status IN('APPROVED','REJECTED') AND NEW.decision_by IN(raw.prepared_by,NEW.prepared_by,NEW.last_edited_by) THEN RAISE EXCEPTION 'CASH_REVIEW_INDEPENDENT_REVIEW_REQUIRED';END IF;
  IF NEW.status='CANCELLED' AND NEW.decision_by<>NEW.prepared_by THEN RAISE EXCEPTION 'CASH_REVIEW_ORIGINAL_MAKER_REQUIRED';END IF;
  IF NEW.status IN('REJECTED','CANCELLED') AND (NEW.approved_by IS NOT NULL OR NEW.approved_at IS NOT NULL OR NEW.approved_usage_id IS NOT NULL OR NEW.release_decision_id IS NOT NULL OR NEW.released_by IS NOT NULL OR NEW.released_at IS NOT NULL) THEN RAISE EXCEPTION 'CASH_REVIEW_TERMINAL_METADATA';END IF;
  IF NEW.status IN('SUBMITTED','APPROVED') THEN snapshot:=plugin_finance_cash_review_snapshot(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.raw_registration_id,NEW.claim_id);IF snapshot IS DISTINCT FROM NEW.content->'claimSnapshot' THEN RAISE EXCEPTION 'CASH_REVIEW_CURRENT_FACTS_STALE';END IF;PERFORM plugin_finance_cash_review_evidence_check(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.id);END IF;
  IF NEW.status='APPROVED' AND (NEW.approved_usage_id IS NULL OR NEW.approved_by IS DISTINCT FROM NEW.decision_by OR NEW.approved_at IS DISTINCT FROM NEW.decision_at OR NEW.submission_expires_at IS NULL OR NEW.submission_expires_at<=clock_timestamp() OR NEW.release_decision_id IS NOT NULL OR NEW.released_by IS NOT NULL OR NEW.released_at IS NOT NULL) THEN RAISE EXCEPTION 'CASH_REVIEW_APPROVAL_EXPIRED_OR_INVALID';END IF;
  IF NEW.status='RELEASED' THEN
   IF (NEW.approved_by,NEW.approved_at,NEW.approved_usage_id,NEW.decision_by,NEW.decision_at,NEW.decision_reason) IS DISTINCT FROM (OLD.approved_by,OLD.approved_at,OLD.approved_usage_id,OLD.decision_by,OLD.decision_at,OLD.decision_reason) OR NEW.release_decision_id IS NULL OR NEW.released_by IS NULL OR NEW.released_at IS NULL THEN RAISE EXCEPTION 'CASH_REVIEW_RELEASE_METADATA';END IF;
   PERFORM 1 FROM plugin_finance_cash_source_review_release WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id AND id=NEW.release_decision_id AND review_id=NEW.id AND usage_id=NEW.approved_usage_id AND status='APPROVED' AND reviewed_by=NEW.released_by AND reviewed_at=NEW.released_at;IF NOT FOUND THEN RAISE EXCEPTION 'CASH_REVIEW_APPROVED_RELEASE_REQUIRED';END IF;
  END IF;
 END IF;RETURN NEW;
END $$;
CREATE TRIGGER finance_cash_source_review_guard BEFORE INSERT OR UPDATE OR DELETE ON plugin_finance_cash_source_review FOR EACH ROW EXECUTE FUNCTION plugin_finance_cash_review_guard_fn();
CREATE FUNCTION plugin_finance_cash_review_evidence_guard() RETURNS trigger LANGUAGE plpgsql AS $$ DECLARE r record;o record;BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'CASH_REVIEW_EVIDENCE_IMMUTABLE';END IF;
 SELECT * INTO STRICT r FROM plugin_finance_cash_source_review WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id AND id=NEW.review_id;
 SELECT * INTO STRICT o FROM plugin_finance_original WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id AND id=NEW.original_id;
 IF r.status<>'DRAFT' OR (NEW.original_version,NEW.sha256,NEW.filename) IS DISTINCT FROM (o.version,o.sha256,o.filename) OR encode(sha256(o.content),'hex')<>NEW.sha256 OR r.content->'evidence'->(NEW.item_no-1) IS DISTINCT FROM jsonb_build_object('originalId',NEW.original_id,'originalVersion',NEW.original_version,'sha256',NEW.sha256,'filename',NEW.filename,'purpose',NEW.purpose,'description',NEW.description) THEN RAISE EXCEPTION 'CASH_REVIEW_EVIDENCE_SNAPSHOT_MISMATCH';END IF;RETURN NEW;
END $$;
CREATE TRIGGER finance_cash_source_review_evidence_guard BEFORE INSERT OR UPDATE OR DELETE ON plugin_finance_cash_source_review_evidence FOR EACH ROW EXECUTE FUNCTION plugin_finance_cash_review_evidence_guard();
CREATE FUNCTION plugin_finance_cash_usage_guard_fn() RETURNS trigger LANGUAGE plpgsql AS $$ DECLARE r record;c record;BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'CASH_USAGE_HISTORY_IMMUTABLE';END IF;
 PERFORM 1 FROM plugin_finance_cash_movement_registration WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id AND id=NEW.raw_registration_id FOR UPDATE;
 SELECT * INTO STRICT c FROM plugin_finance_cash_leg_claim WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id AND id=NEW.claim_id FOR UPDATE;
 SELECT * INTO STRICT r FROM plugin_finance_cash_source_review WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id AND id=NEW.source_review_id;
 IF TG_OP='INSERT' THEN
  IF NEW.state<>'ACTIVE' OR NEW.release_decision_id IS NOT NULL OR NEW.released_by IS NOT NULL OR NEW.released_at IS NOT NULL OR r.status<>'APPROVED' OR r.approved_usage_id IS DISTINCT FROM NEW.id OR (NEW.approved_by,NEW.approved_at) IS DISTINCT FROM (r.approved_by,r.approved_at) OR c.state<>'ACTIVE' OR EXISTS(SELECT 1 FROM plugin_finance_cash_match_withdrawal WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id AND active_claim_id=NEW.claim_id AND status='REQUESTED') THEN RAISE EXCEPTION 'CASH_USAGE_APPROVED_CONSUMER_REQUIRED';END IF;
 ELSE
  IF (to_jsonb(NEW)-ARRAY['state','release_decision_id','released_by','released_at']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['state','release_decision_id','released_by','released_at']) OR OLD.state<>'ACTIVE' OR NEW.state<>'RELEASED' OR r.status<>'RELEASED' OR (NEW.release_decision_id,NEW.released_by,NEW.released_at) IS DISTINCT FROM (r.release_decision_id,r.released_by,r.released_at) OR NEW.release_decision_id IS NULL OR NEW.released_by IS NULL OR NEW.released_at IS NULL THEN RAISE EXCEPTION 'CASH_USAGE_REVIEWED_RELEASE_REQUIRED';END IF;
 END IF;RETURN NEW;
END $$;
CREATE TRIGGER finance_cash_usage_guard BEFORE INSERT OR UPDATE OR DELETE ON plugin_finance_cash_claim_usage FOR EACH ROW EXECUTE FUNCTION plugin_finance_cash_usage_guard_fn();
CREATE FUNCTION plugin_finance_cash_review_committed(t uuid,b uuid,y uuid,rid uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE r record;u record;c record;decision record;g bigint;n integer;
BEGIN
 SELECT * INTO STRICT r FROM plugin_finance_cash_source_review WHERE tenant_id=t AND book_id=b AND year_id=y AND id=rid;
 SELECT count(*) INTO n FROM plugin_finance_cash_claim_usage WHERE tenant_id=t AND book_id=b AND year_id=y AND source_review_id=rid;
 IF r.status NOT IN('APPROVED','RELEASED') THEN IF n<>0 OR r.approved_usage_id IS NOT NULL THEN RAISE EXCEPTION 'CASH_REVIEW_NON_EFFECTIVE_HAS_USAGE';END IF;RETURN;END IF;
 SELECT * INTO u FROM plugin_finance_cash_claim_usage WHERE tenant_id=t AND book_id=b AND year_id=y AND id=r.approved_usage_id AND source_review_id=rid;
 IF n<>1 OR NOT FOUND OR (u.claim_id,u.raw_registration_id,u.claim_generation,u.claim_content_digest,u.consumer_content_digest,u.approved_by,u.approved_at) IS DISTINCT FROM (r.claim_id,r.raw_registration_id,r.claim_generation,r.claim_content_digest,r.content_digest,r.approved_by,r.approved_at) THEN RAISE EXCEPTION 'CASH_REVIEW_COMMITTED_TYPED_USAGE_REQUIRED';END IF;
 IF r.status='APPROVED' THEN
  SELECT * INTO STRICT c FROM plugin_finance_cash_leg_claim WHERE tenant_id=t AND book_id=b AND year_id=y AND id=r.claim_id;
  SELECT source_generation INTO STRICT g FROM plugin_finance_cash_source_match_guard WHERE tenant_id=t AND book_id=b AND year_id=y AND raw_registration_id=r.raw_registration_id;
  IF u.state<>'ACTIVE' OR c.state<>'ACTIVE' OR c.claim_kind<>r.claim_kind OR c.claim_generation<>r.claim_generation OR g<>r.source_match_generation OR r.release_decision_id IS NOT NULL OR EXISTS(SELECT 1 FROM plugin_finance_cash_match_withdrawal WHERE tenant_id=t AND book_id=b AND year_id=y AND active_claim_id=r.claim_id AND status='REQUESTED') THEN RAISE EXCEPTION 'CASH_REVIEW_ACTIVE_RELIANCE_INVARIANT';END IF;
 ELSE
  SELECT * INTO decision FROM plugin_finance_cash_source_review_release WHERE tenant_id=t AND book_id=b AND year_id=y AND id=r.release_decision_id AND review_id=r.id AND usage_id=u.id AND review_content_digest=r.content_digest AND status='APPROVED';
  IF NOT FOUND OR u.state<>'RELEASED' OR (u.release_decision_id,u.released_by,u.released_at) IS DISTINCT FROM (r.release_decision_id,r.released_by,r.released_at) OR (decision.reviewed_by,decision.reviewed_at) IS DISTINCT FROM (r.released_by,r.released_at) THEN RAISE EXCEPTION 'CASH_REVIEW_RELEASED_RELIANCE_INVARIANT';END IF;
 END IF;
END $$;
CREATE FUNCTION plugin_finance_cash_review_final_trigger() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_TABLE_NAME='plugin_finance_cash_source_review' THEN PERFORM plugin_finance_cash_review_committed(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.id);
 ELSE PERFORM plugin_finance_cash_review_committed(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.source_review_id);END IF;RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER finance_cash_review_final AFTER INSERT OR UPDATE ON plugin_finance_cash_source_review DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION plugin_finance_cash_review_final_trigger();
CREATE CONSTRAINT TRIGGER finance_cash_usage_final AFTER INSERT OR UPDATE ON plugin_finance_cash_claim_usage DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION plugin_finance_cash_review_final_trigger();
CREATE FUNCTION plugin_finance_cash_review_claim_final() RETURNS trigger LANGUAGE plpgsql AS $$ DECLARE r record;BEGIN
 FOR r IN SELECT id FROM plugin_finance_cash_source_review WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id AND claim_id=NEW.id AND status='APPROVED' LOOP PERFORM plugin_finance_cash_review_committed(NEW.tenant_id,NEW.book_id,NEW.year_id,r.id);END LOOP;RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER finance_cash_claim_reliance_final AFTER INSERT OR UPDATE ON plugin_finance_cash_leg_claim DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION plugin_finance_cash_review_claim_final();
CREATE FUNCTION plugin_finance_cash_review_release_guard() RETURNS trigger LANGUAGE plpgsql AS $$ DECLARE r record;raw record;u record;BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'CASH_REVIEW_RELEASE_HISTORY_IMMUTABLE';END IF;
 SELECT * INTO STRICT raw FROM plugin_finance_cash_movement_registration WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id AND id=NEW.raw_registration_id FOR UPDATE;
 PERFORM plugin_finance_cash_match_open(NEW.tenant_id,NEW.book_id,NEW.year_id,raw.occurred_date);
 SELECT * INTO STRICT r FROM plugin_finance_cash_source_review WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id AND id=NEW.review_id;
 SELECT * INTO STRICT u FROM plugin_finance_cash_claim_usage WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id AND id=NEW.usage_id FOR UPDATE;
 IF r.status<>'APPROVED' OR r.version<>NEW.expected_review_version OR r.content_digest<>NEW.review_content_digest OR r.approved_usage_id<>NEW.usage_id OR u.state<>'ACTIVE' THEN RAISE EXCEPTION 'CASH_REVIEW_RELEASE_TARGET_STALE';END IF;
 IF TG_OP='INSERT' THEN
  IF NEW.status<>'REQUESTED' OR NEW.version<>1 OR NEW.review_expires_at IS DISTINCT FROM NEW.requested_at+interval '168 hours' OR NEW.reviewed_by IS NOT NULL OR NEW.reviewed_at IS NOT NULL OR NEW.decision_reason IS NOT NULL THEN RAISE EXCEPTION 'CASH_REVIEW_INITIAL_RELEASE_REQUEST_REQUIRED';END IF;
  IF (NEW.content->>'reviewId',NEW.content->>'usageId',NEW.content->>'reviewContentDigest',NEW.content->>'requestedBy',NEW.content->>'reason') IS DISTINCT FROM (r.id::text,u.id::text,r.content_digest,NEW.requested_by::text,NEW.reason) OR (NEW.content->>'expectedReviewVersion')::bigint IS DISTINCT FROM r.version THEN RAISE EXCEPTION 'CASH_REVIEW_RELEASE_SNAPSHOT_MISMATCH';END IF;
 ELSE
  IF (to_jsonb(NEW)-ARRAY['status','version','reviewed_by','reviewed_at','decision_reason']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['status','version','reviewed_by','reviewed_at','decision_reason']) OR OLD.status<>'REQUESTED' OR NEW.status NOT IN('APPROVED','REJECTED','CANCELLED') OR NEW.version<>OLD.version+1 OR NEW.reviewed_by IS NULL OR NEW.reviewed_at IS NULL OR coalesce(length(NEW.decision_reason),0) NOT BETWEEN 1 AND 1000 THEN RAISE EXCEPTION 'CASH_REVIEW_RELEASE_TRANSITION_INVALID';END IF;
  IF NEW.status='CANCELLED' AND NEW.reviewed_by<>NEW.requested_by THEN RAISE EXCEPTION 'CASH_REVIEW_RELEASE_ORIGINAL_REQUESTER_REQUIRED';END IF;
  IF NEW.status IN('APPROVED','REJECTED') AND NEW.reviewed_by IN(NEW.requested_by,raw.prepared_by,r.prepared_by,r.last_edited_by) THEN RAISE EXCEPTION 'CASH_REVIEW_RELEASE_INDEPENDENT_APPROVER_REQUIRED';END IF;
  IF NEW.status='APPROVED' AND NEW.review_expires_at<=clock_timestamp() THEN RAISE EXCEPTION 'CASH_REVIEW_RELEASE_EXPIRED';END IF;
 END IF;
 IF NEW.status NOT IN('REJECTED','CANCELLED') AND EXISTS(SELECT 1 FROM plugin_finance_cash_claim_usage WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id AND claim_id=NEW.claim_id AND id<>NEW.usage_id AND state='ACTIVE') THEN RAISE EXCEPTION 'CASH_REVIEW_IN_USE';END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER finance_cash_source_review_release_guard BEFORE INSERT OR UPDATE OR DELETE ON plugin_finance_cash_source_review_release FOR EACH ROW EXECUTE FUNCTION plugin_finance_cash_review_release_guard();
CREATE FUNCTION plugin_finance_cash_review_event() RETURNS trigger LANGUAGE plpgsql AS $$ DECLARE rid uuid;release_id uuid;rv bigint;BEGIN
 IF TG_TABLE_NAME='plugin_finance_cash_source_review' THEN rid:=NEW.id;release_id:=NULL;
  IF NEW.status='APPROVED' THEN
   INSERT INTO plugin_finance_cash_claim_usage(tenant_id,book_id,year_id,claim_id,raw_registration_id,claim_generation,claim_content_digest,id,usage_kind,source_review_id,consumer_content_digest,state,approved_by,approved_at) VALUES(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.claim_id,NEW.raw_registration_id,NEW.claim_generation,NEW.claim_content_digest,NEW.approved_usage_id,'SOURCE_REVIEW',NEW.id,NEW.content_digest,'ACTIVE',NEW.approved_by,NEW.approved_at);
  ELSIF NEW.status='RELEASED' THEN
   UPDATE plugin_finance_cash_claim_usage SET state='RELEASED',release_decision_id=NEW.release_decision_id,released_by=NEW.released_by,released_at=NEW.released_at WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id AND id=NEW.approved_usage_id AND state='ACTIVE';IF NOT FOUND THEN RAISE EXCEPTION 'CASH_REVIEW_ACTIVE_USAGE_REQUIRED';END IF;
  END IF;
  IF NEW.status IN('APPROVED','RELEASED') THEN UPDATE plugin_finance_cash_source_review_guard SET review_version=review_version+1 WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id;END IF;
 ELSE rid:=NEW.review_id;release_id:=NEW.id;
  IF NEW.status IN('REQUESTED','REJECTED','CANCELLED') THEN UPDATE plugin_finance_cash_source_review_guard SET review_version=review_version+1 WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id;END IF;
  IF NEW.status='APPROVED' THEN UPDATE plugin_finance_cash_source_review SET status='RELEASED',version=version+1,release_decision_id=NEW.id,released_by=NEW.reviewed_by,released_at=NEW.reviewed_at WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id AND id=NEW.review_id AND status='APPROVED' AND approved_usage_id=NEW.usage_id;IF NOT FOUND THEN RAISE EXCEPTION 'CASH_REVIEW_RELEASE_TARGET_STALE';END IF;END IF;
 END IF;
 SELECT review_version INTO STRICT rv FROM plugin_finance_cash_source_review_guard WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id;
 INSERT INTO plugin_finance_cash_source_review_outbox VALUES(NEW.tenant_id,NEW.book_id,NEW.year_id,gen_random_uuid(),rid,release_id,NEW.version,rv,CASE WHEN release_id IS NULL THEN 'REVIEW_' ELSE 'RELEASE_' END||NEW.status,to_jsonb(NEW),now());RETURN NEW;
END $$;
CREATE TRIGGER finance_cash_source_review_event AFTER INSERT OR UPDATE OF status ON plugin_finance_cash_source_review FOR EACH ROW EXECUTE FUNCTION plugin_finance_cash_review_event();
CREATE TRIGGER finance_cash_source_review_release_event AFTER INSERT OR UPDATE OF status ON plugin_finance_cash_source_review_release FOR EACH ROW EXECUTE FUNCTION plugin_finance_cash_review_event();
CREATE FUNCTION plugin_finance_cash_raw_active_usage() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF NEW.status='VOIDED' AND EXISTS(SELECT 1 FROM plugin_finance_cash_claim_usage WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id AND raw_registration_id=NEW.id AND state='ACTIVE') THEN RAISE EXCEPTION 'CASH_REGISTRATION_IN_USE';END IF;RETURN NEW;
END $$;
CREATE TRIGGER finance_cash_raw_active_usage BEFORE UPDATE OF status ON plugin_finance_cash_movement_registration FOR EACH ROW EXECUTE FUNCTION plugin_finance_cash_raw_active_usage();

-- V7 identities preserved; released usage history no longer blocks current capacity.
CREATE OR REPLACE FUNCTION plugin_finance_cash_claim_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r record;c record;g bigint;
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'CASH_CLAIM_IMMUTABLE';END IF;
 SELECT * INTO STRICT r FROM plugin_finance_cash_movement_registration WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id AND id=NEW.raw_registration_id FOR UPDATE;
 INSERT INTO plugin_finance_cash_source_match_guard VALUES(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.raw_registration_id,0) ON CONFLICT DO NOTHING;
 SELECT source_generation INTO g FROM plugin_finance_cash_source_match_guard WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id AND raw_registration_id=NEW.raw_registration_id FOR UPDATE;
 IF TG_OP='UPDATE' THEN
  IF (to_jsonb(NEW)-ARRAY['state','released_at','released_by','release_reason','release_generation']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['state','released_at','released_by','release_reason','release_generation']) OR OLD.state<>'ACTIVE' OR NEW.state<>'RELEASED' OR NEW.released_at IS NULL OR NEW.released_by IS NULL OR coalesce(length(NEW.release_reason),0) NOT BETWEEN 1 AND 1000 OR NEW.release_generation IS DISTINCT FROM g+1 THEN RAISE EXCEPTION 'CASH_CLAIM_INVALID_RELEASE';END IF;
  IF EXISTS(SELECT 1 FROM plugin_finance_cash_claim_usage WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id AND claim_id=NEW.id AND state='ACTIVE') THEN RAISE EXCEPTION 'CASH_REGISTRATION_IN_USE';END IF;
  IF NEW.claim_kind='RAW_REGISTRATION' THEN IF r.status<>'VOIDED' OR NEW.released_by<>r.prepared_by THEN RAISE EXCEPTION 'CASH_CLAIM_RAW_RELEASE_INVALID';END IF;
  ELSE
   PERFORM 1 FROM plugin_finance_cash_match_correction cc JOIN plugin_finance_cash_match_withdrawal w ON (w.tenant_id,w.book_id,w.year_id,w.correction_id)=(cc.tenant_id,cc.book_id,cc.year_id,cc.id) WHERE cc.tenant_id=NEW.tenant_id AND cc.book_id=NEW.book_id AND cc.year_id=NEW.year_id AND cc.id=NEW.correction_id AND cc.status='WITHDRAWN' AND w.active_claim_id=NEW.id AND w.status='APPROVED' AND w.reviewed_by=NEW.released_by;
   IF NOT FOUND THEN RAISE EXCEPTION 'CASH_CLAIM_REVIEWED_WITHDRAWAL_REQUIRED';END IF;
  END IF;
 ELSE
  IF NEW.state<>'ACTIVE' OR NEW.claim_generation IS DISTINCT FROM g+1 OR NEW.released_at IS NOT NULL OR NEW.released_by IS NOT NULL OR NEW.release_reason IS NOT NULL OR NEW.release_generation IS NOT NULL THEN RAISE EXCEPTION 'CASH_CLAIM_INITIAL_ACTIVE_REQUIRED';END IF;
  IF NEW.claim_kind='RAW_REGISTRATION' THEN
   IF r.status<>'REGISTERED' OR (NEW.journal_id,NEW.line_no,NEW.account_id,NEW.journal_version,NEW.posting_seq,NEW.posting_digest,NEW.claim_content_digest,NEW.created_by) IS DISTINCT FROM (r.journal_id,r.line_no,r.account_id,r.matched_journal_version,r.posting_seq,r.posting_digest,r.content_digest,r.prepared_by) THEN RAISE EXCEPTION 'CASH_CLAIM_ROOT_MISMATCH';END IF;
  ELSE
   SELECT * INTO c FROM plugin_finance_cash_match_correction WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id AND id=NEW.correction_id;
   IF r.status<>'VOIDED' OR c.status IS DISTINCT FROM 'APPROVED' OR c.approved_claim_id IS DISTINCT FROM NEW.id OR c.approved_match_generation IS DISTINCT FROM NEW.claim_generation OR c.approved_by IS DISTINCT FROM NEW.created_by OR c.approved_by=r.prepared_by OR c.approved_by=c.prepared_by THEN RAISE EXCEPTION 'CASH_CLAIM_APPROVED_CORRECTION_REQUIRED';END IF;
   PERFORM plugin_finance_cash_match_target(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.raw_registration_id,NEW.journal_id,NEW.line_no,NEW.journal_version,NEW.posting_seq,NEW.posting_digest);
  END IF;
 END IF;
 UPDATE plugin_finance_cash_source_match_guard SET source_generation=g+1 WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id AND raw_registration_id=NEW.raw_registration_id;
 UPDATE plugin_finance_cash_match_guard SET match_version=match_version+1 WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id;
 RETURN NEW;
END $$;

-- V7 identities preserved; released usage history no longer blocks current capacity.
CREATE OR REPLACE FUNCTION plugin_finance_cash_withdrawal_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r record;c record;cl record;g bigint;
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'CASH_MATCH_WITHDRAWAL_IMMUTABLE';END IF;
 SELECT * INTO STRICT r FROM plugin_finance_cash_movement_registration WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id AND id=NEW.raw_registration_id FOR UPDATE;
 PERFORM plugin_finance_cash_match_open(NEW.tenant_id,NEW.book_id,NEW.year_id,r.occurred_date);
 SELECT * INTO STRICT c FROM plugin_finance_cash_match_correction WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id AND id=NEW.correction_id;
 SELECT * INTO STRICT cl FROM plugin_finance_cash_leg_claim WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id AND id=NEW.active_claim_id FOR UPDATE;
 SELECT source_generation INTO g FROM plugin_finance_cash_source_match_guard WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id AND raw_registration_id=NEW.raw_registration_id;
 IF c.status<>'APPROVED' OR c.approved_claim_id<>NEW.active_claim_id OR c.raw_registration_id<>NEW.raw_registration_id OR cl.state<>'ACTIVE' OR cl.correction_id<>c.id OR NEW.requested_by<>r.prepared_by OR NEW.expected_correction_version<>c.version OR NEW.expected_source_generation<>g THEN RAISE EXCEPTION 'CASH_MATCH_WITHDRAWAL_TARGET_STALE';END IF;
 IF TG_OP='INSERT' THEN
  IF NEW.status<>'REQUESTED' OR NEW.version<>1 OR NEW.reviewed_by IS NOT NULL OR NEW.reviewed_at IS NOT NULL OR NEW.decision_reason IS NOT NULL THEN RAISE EXCEPTION 'CASH_MATCH_INITIAL_WITHDRAWAL_REQUIRED';END IF;
  IF (NEW.content->>'correctionId',NEW.content->>'originalRegistrationId',NEW.content->>'activeClaimId',NEW.content->>'requestedBy',NEW.content->>'reason') IS DISTINCT FROM (c.id::text,r.id::text,cl.id::text,r.prepared_by::text,NEW.reason) OR (NEW.content->>'expectedCorrectionVersion')::bigint IS DISTINCT FROM c.version OR (NEW.content->>'expectedSourceMatchGeneration')::bigint IS DISTINCT FROM g THEN RAISE EXCEPTION 'CASH_MATCH_WITHDRAWAL_SNAPSHOT_MISMATCH';END IF;
 ELSE
  IF (to_jsonb(NEW)-ARRAY['status','version','reviewed_by','reviewed_at','decision_reason']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['status','version','reviewed_by','reviewed_at','decision_reason']) OR OLD.status<>'REQUESTED' OR NEW.status NOT IN('APPROVED','REJECTED') OR NEW.version<>OLD.version+1 OR NEW.reviewed_by IS NULL OR NEW.reviewed_by IN(r.prepared_by,c.prepared_by,c.last_edited_by) OR NEW.reviewed_at IS NULL OR coalesce(length(NEW.decision_reason),0) NOT BETWEEN 1 AND 1000 THEN RAISE EXCEPTION 'CASH_MATCH_WITHDRAWAL_REVIEW_INVALID';END IF;
 END IF;
 IF NEW.status<>'REJECTED' AND EXISTS(SELECT 1 FROM plugin_finance_cash_claim_usage WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id AND claim_id=NEW.active_claim_id AND state='ACTIVE') THEN RAISE EXCEPTION 'CASH_REGISTRATION_IN_USE';END IF;
 RETURN NEW;
END $$;

CREATE FUNCTION plugin_finance_cash_review_release_final() RETURNS trigger LANGUAGE plpgsql AS $$ DECLARE r record;u record;BEGIN
 IF NEW.status='APPROVED' THEN
  SELECT * INTO STRICT r FROM plugin_finance_cash_source_review WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id AND id=NEW.review_id;
  SELECT * INTO STRICT u FROM plugin_finance_cash_claim_usage WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id AND id=NEW.usage_id;
  IF r.status<>'RELEASED' OR u.state<>'RELEASED' OR r.release_decision_id IS DISTINCT FROM NEW.id OR u.release_decision_id IS DISTINCT FROM NEW.id THEN RAISE EXCEPTION 'CASH_REVIEW_RELEASE_COMMITTED_PAIR_REQUIRED';END IF;
  PERFORM plugin_finance_cash_review_committed(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.review_id);
 END IF;RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER finance_cash_review_release_final AFTER INSERT OR UPDATE ON plugin_finance_cash_source_review_release DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION plugin_finance_cash_review_release_final();
-- Remove the closed V7 interface only after typed forward/reverse/release FKs and all guards exist.
ALTER TABLE plugin_finance_cash_claim_usage DROP CONSTRAINT finance_cash_no_downstream_protocol;
