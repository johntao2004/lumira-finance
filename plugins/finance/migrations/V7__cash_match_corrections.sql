-- Unified occupancy and reviewed match-only corrections. V1-V6 are immutable.
CREATE TABLE plugin_finance_cash_match_guard (
 tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,match_version bigint NOT NULL DEFAULT 0 CHECK(match_version>=0),
 PRIMARY KEY(tenant_id,book_id,year_id),FOREIGN KEY(tenant_id,book_id,year_id) REFERENCES plugin_finance_year_guard(tenant_id,book_id,year_id));
CREATE TABLE plugin_finance_cash_source_match_guard (
 tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,raw_registration_id uuid NOT NULL,source_generation bigint NOT NULL DEFAULT 0 CHECK(source_generation>=0),
 PRIMARY KEY(tenant_id,book_id,year_id,raw_registration_id),FOREIGN KEY(tenant_id,book_id,year_id,raw_registration_id) REFERENCES plugin_finance_cash_movement_registration(tenant_id,book_id,year_id,id));
CREATE TABLE plugin_finance_cash_match_correction (
 tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,id uuid NOT NULL,legal_entity_id uuid NOT NULL,raw_registration_id uuid NOT NULL,account_id uuid NOT NULL,
 raw_version_at_proposal bigint NOT NULL CHECK(raw_version_at_proposal>0),raw_content_digest text NOT NULL,source_identity_digest text NOT NULL,
 target_journal_id uuid NOT NULL,target_line_no integer NOT NULL CHECK(target_line_no BETWEEN 1 AND 500),target_journal_version bigint NOT NULL CHECK(target_journal_version>0),target_posting_seq bigint NOT NULL CHECK(target_posting_seq>0),target_posting_digest text NOT NULL,
 expected_match_generation bigint NOT NULL CHECK(expected_match_generation>=0),content_version bigint NOT NULL DEFAULT 1 CHECK(content_version=1),content_digest text NOT NULL CHECK(content_digest~'^[a-f0-9]{64}$'),content jsonb NOT NULL,reason text NOT NULL CHECK(length(reason) BETWEEN 1 AND 1000),
 prepared_by uuid NOT NULL,prepared_username text NOT NULL,last_edited_by uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),
 status text NOT NULL DEFAULT 'DRAFT' CHECK(status IN('DRAFT','SUBMITTED','APPROVED','REJECTED','CANCELLED','WITHDRAWN')),version bigint NOT NULL DEFAULT 1 CHECK(version>0),
 submitted_at timestamptz,approved_by uuid,approved_at timestamptz,approved_claim_id uuid,approved_match_generation bigint,decision_by uuid,decision_at timestamptz,decision_reason text,withdrawn_at timestamptz,withdrawn_by uuid,
 PRIMARY KEY(tenant_id,book_id,year_id,id),UNIQUE(tenant_id,book_id,year_id,id,raw_registration_id,target_journal_id,target_line_no,account_id,target_journal_version,target_posting_seq,target_posting_digest,content_digest),
 FOREIGN KEY(tenant_id,book_id,legal_entity_id) REFERENCES plugin_finance_book(tenant_id,id,legal_entity_id),
 FOREIGN KEY(tenant_id,book_id,year_id,raw_registration_id) REFERENCES plugin_finance_cash_movement_registration(tenant_id,book_id,year_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,target_journal_id,target_line_no,account_id) REFERENCES plugin_finance_line(tenant_id,book_id,year_id,journal_id,line_no,account_id),
 FOREIGN KEY(tenant_id,book_id,year_id,target_journal_id,target_posting_seq) REFERENCES plugin_finance_posting(tenant_id,book_id,year_id,journal_id,posting_seq),CHECK(prepared_by=last_edited_by));
CREATE UNIQUE INDEX finance_cash_match_pending_source ON plugin_finance_cash_match_correction(tenant_id,book_id,year_id,raw_registration_id) WHERE status IN('DRAFT','SUBMITTED');
CREATE INDEX finance_cash_match_cursor ON plugin_finance_cash_match_correction(tenant_id,book_id,year_id,created_at DESC,id DESC);
CREATE TABLE plugin_finance_cash_leg_claim (
 tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,id uuid NOT NULL,raw_registration_id uuid NOT NULL,claim_kind text NOT NULL CHECK(claim_kind IN('RAW_REGISTRATION','MATCH_CORRECTION')),correction_id uuid,
 claim_generation bigint NOT NULL CHECK(claim_generation>0),journal_id uuid NOT NULL,line_no integer NOT NULL,account_id uuid NOT NULL,journal_version bigint NOT NULL,posting_seq bigint NOT NULL,posting_digest text NOT NULL,claim_content_digest text NOT NULL,
 created_by uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),origin text NOT NULL DEFAULT 'LIVE' CHECK(origin IN('LIVE','MIGRATION_V7')),
 state text NOT NULL DEFAULT 'ACTIVE' CHECK(state IN('ACTIVE','RELEASED')),released_at timestamptz,released_by uuid,release_reason text,release_generation bigint,
 PRIMARY KEY(tenant_id,book_id,year_id,id),UNIQUE(tenant_id,book_id,year_id,raw_registration_id,claim_generation),UNIQUE(tenant_id,book_id,year_id,id,raw_registration_id,claim_generation,claim_content_digest),
 FOREIGN KEY(tenant_id,book_id,year_id,raw_registration_id) REFERENCES plugin_finance_cash_movement_registration(tenant_id,book_id,year_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,journal_id,line_no,account_id) REFERENCES plugin_finance_line(tenant_id,book_id,year_id,journal_id,line_no,account_id),
 FOREIGN KEY(tenant_id,book_id,year_id,journal_id,posting_seq) REFERENCES plugin_finance_posting(tenant_id,book_id,year_id,journal_id,posting_seq),
 FOREIGN KEY(tenant_id,book_id,year_id,correction_id,raw_registration_id,journal_id,line_no,account_id,journal_version,posting_seq,posting_digest,claim_content_digest) REFERENCES plugin_finance_cash_match_correction(tenant_id,book_id,year_id,id,raw_registration_id,target_journal_id,target_line_no,account_id,target_journal_version,target_posting_seq,target_posting_digest,content_digest),
 CHECK((claim_kind='RAW_REGISTRATION' AND correction_id IS NULL) OR (claim_kind='MATCH_CORRECTION' AND correction_id IS NOT NULL)));
CREATE UNIQUE INDEX finance_cash_claim_one_active_leg ON plugin_finance_cash_leg_claim(tenant_id,book_id,year_id,journal_id,line_no) WHERE state='ACTIVE';
CREATE UNIQUE INDEX finance_cash_claim_one_active_source ON plugin_finance_cash_leg_claim(tenant_id,book_id,year_id,raw_registration_id) WHERE state='ACTIVE';
ALTER TABLE plugin_finance_cash_match_correction ADD CONSTRAINT finance_cash_match_approved_claim FOREIGN KEY(tenant_id,book_id,year_id,approved_claim_id) REFERENCES plugin_finance_cash_leg_claim(tenant_id,book_id,year_id,id) DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE plugin_finance_cash_match_withdrawal (
 tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,id uuid NOT NULL,correction_id uuid NOT NULL,raw_registration_id uuid NOT NULL,active_claim_id uuid NOT NULL,claim_generation bigint NOT NULL,claim_content_digest text NOT NULL,
 expected_correction_version bigint NOT NULL,expected_source_generation bigint NOT NULL,content_digest text NOT NULL CHECK(content_digest~'^[a-f0-9]{64}$'),content jsonb NOT NULL,reason text NOT NULL CHECK(length(reason) BETWEEN 1 AND 1000),requested_by uuid NOT NULL,requested_username text NOT NULL,requested_at timestamptz NOT NULL DEFAULT now(),
 status text NOT NULL DEFAULT 'REQUESTED' CHECK(status IN('REQUESTED','APPROVED','REJECTED')),version bigint NOT NULL DEFAULT 1 CHECK(version>0),reviewed_by uuid,reviewed_at timestamptz,decision_reason text,
 PRIMARY KEY(tenant_id,book_id,year_id,id),FOREIGN KEY(tenant_id,book_id,year_id,correction_id) REFERENCES plugin_finance_cash_match_correction(tenant_id,book_id,year_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,active_claim_id,raw_registration_id,claim_generation,claim_content_digest) REFERENCES plugin_finance_cash_leg_claim(tenant_id,book_id,year_id,id,raw_registration_id,claim_generation,claim_content_digest));
CREATE UNIQUE INDEX finance_cash_match_pending_withdrawal ON plugin_finance_cash_match_withdrawal(tenant_id,book_id,year_id,correction_id) WHERE status='REQUESTED';
CREATE INDEX finance_cash_match_withdraw_cursor ON plugin_finance_cash_match_withdrawal(tenant_id,book_id,year_id,correction_id,requested_at DESC,id DESC);
-- No approved downstream consumer exists in0.6. This closed interface cannot accept invented
-- polymorphic consumer UUIDs. A future migration must replace the false constraint with real
-- consuming-entity typed FKs and an insert guard sharing Raw/claim locks before enabling usage.
CREATE TABLE plugin_finance_cash_claim_usage (
 tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,claim_id uuid NOT NULL,raw_registration_id uuid NOT NULL,claim_generation bigint NOT NULL,claim_content_digest text NOT NULL,
 FOREIGN KEY(tenant_id,book_id,year_id,claim_id,raw_registration_id,claim_generation,claim_content_digest) REFERENCES plugin_finance_cash_leg_claim(tenant_id,book_id,year_id,id,raw_registration_id,claim_generation,claim_content_digest),CONSTRAINT finance_cash_no_downstream_protocol CHECK(false));
CREATE FUNCTION plugin_finance_cash_match_open(t uuid,b uuid,y uuid,d date) RETURNS void LANGUAGE plpgsql AS $$ BEGIN
 PERFORM 1 FROM plugin_finance_year yr JOIN plugin_finance_period p ON (p.tenant_id,p.book_id,p.year_id)=(yr.tenant_id,yr.book_id,yr.id) WHERE yr.tenant_id=t AND yr.book_id=b AND yr.id=y AND yr.year=extract(year FROM d) AND yr.status='OPEN' AND yr.precision_profile='V4' AND NOT yr.precision_review_required AND p.month=extract(month FROM d) AND p.status='OPEN' FOR UPDATE OF p;
 IF NOT FOUND THEN RAISE EXCEPTION 'CASH_MATCH_PERIOD_CLOSED';END IF;
END $$;
CREATE FUNCTION plugin_finance_cash_match_target(t uuid,b uuid,y uuid,rid uuid,jid uuid,ln integer,jv bigint,seq bigint,pdigest text) RETURNS void LANGUAGE plpgsql AS $$
DECLARE r record; leg record; sc record; item record; current_account jsonb;
BEGIN
 SELECT * INTO STRICT r FROM plugin_finance_cash_movement_registration WHERE tenant_id=t AND book_id=b AND year_id=y AND id=rid;
 SELECT j.status,j.date,j.version,p.posting_seq,p.digest,l.account_id,l.debit,l.credit INTO leg FROM plugin_finance_journal j JOIN plugin_finance_line l ON (l.tenant_id,l.book_id,l.year_id,l.journal_id)=(j.tenant_id,j.book_id,j.year_id,j.id) JOIN plugin_finance_posting p ON (p.tenant_id,p.book_id,p.year_id,p.journal_id)=(j.tenant_id,j.book_id,j.year_id,j.id) WHERE j.tenant_id=t AND j.book_id=b AND j.year_id=y AND j.id=jid AND l.line_no=ln;
 IF NOT FOUND OR leg.status<>'POSTED' OR leg.date<>r.occurred_date OR leg.version<>jv OR leg.posting_seq IS DISTINCT FROM seq OR leg.digest IS DISTINCT FROM pdigest OR leg.account_id<>r.account_id OR NOT ((r.direction='IN' AND leg.debit=r.amount AND leg.credit=0) OR (r.direction='OUT' AND leg.credit=r.amount AND leg.debit=0)) THEN RAISE EXCEPTION 'CASH_MATCH_TARGET_INVALID';END IF;
 SELECT * INTO sc FROM plugin_finance_cash_scope_revision WHERE tenant_id=t AND book_id=b AND year_id=y AND id=r.cash_scope_revision_id;
 SELECT * INTO item FROM plugin_finance_cash_scope_item WHERE tenant_id=t AND book_id=b AND year_id=y AND scope_revision_id=r.cash_scope_revision_id AND account_id=r.account_id;
 IF sc.status IS DISTINCT FROM 'APPROVED' OR sc.approved_scope_version IS DISTINCT FROM r.approved_scope_version OR sc.content_digest IS DISTINCT FROM r.scope_content_digest OR item.qualification IS DISTINCT FROM 'INCLUDED' THEN RAISE EXCEPTION 'CASH_MATCH_SCOPE_STALE';END IF;
 SELECT jsonb_build_object('id',id,'code',code,'name',name,'normalSide',normal_side,'category',category,'cashFlowCategory',cash_flow_category,'enabled',enabled,'purpose',purpose) INTO current_account FROM plugin_finance_account WHERE tenant_id=t AND book_id=b AND year_id=y AND id=r.account_id AND enabled AND category='ASSET' AND normal_side='DEBIT';
 IF current_account IS DISTINCT FROM item.account_snapshot OR r.content->'accountSnapshot' IS DISTINCT FROM item.account_snapshot THEN RAISE EXCEPTION 'CASH_MATCH_ACCOUNT_STALE';END IF;
 PERFORM 1 FROM plugin_finance_original o WHERE o.tenant_id=t AND o.book_id=b AND o.year_id=y AND o.id=r.source_original_id AND o.sha256=r.source_original_sha256 AND encode(sha256(o.content),'hex')=o.sha256 AND octet_length(o.content)=o.size_bytes;
 IF NOT FOUND THEN RAISE EXCEPTION 'CASH_MATCH_ORIGINAL_STALE';END IF;
 PERFORM 1 FROM plugin_finance_original o WHERE o.tenant_id=t AND o.book_id=b AND o.year_id=y AND o.id=item.evidence_original_id AND o.sha256=item.original_sha256 AND encode(sha256(o.content),'hex')=o.sha256 AND octet_length(o.content)=o.size_bytes;
 IF NOT FOUND THEN RAISE EXCEPTION 'CASH_MATCH_SCOPE_EVIDENCE_STALE';END IF;
END $$;
CREATE FUNCTION plugin_finance_cash_claim_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r record;c record;g bigint;
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'CASH_CLAIM_IMMUTABLE';END IF;
 SELECT * INTO STRICT r FROM plugin_finance_cash_movement_registration WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id AND id=NEW.raw_registration_id FOR UPDATE;
 INSERT INTO plugin_finance_cash_source_match_guard VALUES(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.raw_registration_id,0) ON CONFLICT DO NOTHING;
 SELECT source_generation INTO g FROM plugin_finance_cash_source_match_guard WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id AND raw_registration_id=NEW.raw_registration_id FOR UPDATE;
 IF TG_OP='UPDATE' THEN
  IF (to_jsonb(NEW)-ARRAY['state','released_at','released_by','release_reason','release_generation']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['state','released_at','released_by','release_reason','release_generation']) OR OLD.state<>'ACTIVE' OR NEW.state<>'RELEASED' OR NEW.released_at IS NULL OR NEW.released_by IS NULL OR coalesce(length(NEW.release_reason),0) NOT BETWEEN 1 AND 1000 OR NEW.release_generation IS DISTINCT FROM g+1 THEN RAISE EXCEPTION 'CASH_CLAIM_INVALID_RELEASE';END IF;
  IF EXISTS(SELECT 1 FROM plugin_finance_cash_claim_usage WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id AND claim_id=NEW.id) THEN RAISE EXCEPTION 'CASH_REGISTRATION_IN_USE';END IF;
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
CREATE TRIGGER finance_cash_claim_guard BEFORE INSERT OR UPDATE OR DELETE ON plugin_finance_cash_leg_claim FOR EACH ROW EXECUTE FUNCTION plugin_finance_cash_claim_guard();
CREATE FUNCTION plugin_finance_cash_raw_claim() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE g bigint;
BEGIN
 INSERT INTO plugin_finance_cash_match_guard(tenant_id,book_id,year_id) VALUES(NEW.tenant_id,NEW.book_id,NEW.year_id) ON CONFLICT DO NOTHING;
 PERFORM 1 FROM plugin_finance_cash_match_guard WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id FOR UPDATE;
 INSERT INTO plugin_finance_cash_source_match_guard VALUES(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.id,0) ON CONFLICT DO NOTHING;
 SELECT source_generation INTO g FROM plugin_finance_cash_source_match_guard WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id AND raw_registration_id=NEW.id;
 IF TG_OP='INSERT' THEN
  INSERT INTO plugin_finance_cash_leg_claim(tenant_id,book_id,year_id,id,raw_registration_id,claim_kind,claim_generation,journal_id,line_no,account_id,journal_version,posting_seq,posting_digest,claim_content_digest,created_by) VALUES(NEW.tenant_id,NEW.book_id,NEW.year_id,gen_random_uuid(),NEW.id,'RAW_REGISTRATION',g+1,NEW.journal_id,NEW.line_no,NEW.account_id,NEW.matched_journal_version,NEW.posting_seq,NEW.posting_digest,NEW.content_digest,NEW.prepared_by);
 ELSE
  UPDATE plugin_finance_cash_leg_claim SET state='RELEASED',released_at=NEW.voided_at,released_by=NEW.voided_by,release_reason=NEW.void_reason,release_generation=g+1 WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id AND raw_registration_id=NEW.id AND claim_kind='RAW_REGISTRATION' AND state='ACTIVE';
  IF NOT FOUND THEN RAISE EXCEPTION 'CASH_CLAIM_ACTIVE_ROOT_REQUIRED';END IF;
 END IF;RETURN NEW;
END $$;
-- Backfill only real REGISTERED roots; VOIDED sources receive generation0 with no invented claim.
INSERT INTO plugin_finance_cash_match_guard SELECT DISTINCT tenant_id,book_id,year_id,0 FROM plugin_finance_cash_movement_registration;
INSERT INTO plugin_finance_cash_source_match_guard SELECT tenant_id,book_id,year_id,id,0 FROM plugin_finance_cash_movement_registration;
INSERT INTO plugin_finance_cash_leg_claim(tenant_id,book_id,year_id,id,raw_registration_id,claim_kind,claim_generation,journal_id,line_no,account_id,journal_version,posting_seq,posting_digest,claim_content_digest,created_by,origin)
 SELECT tenant_id,book_id,year_id,gen_random_uuid(),id,'RAW_REGISTRATION',1,journal_id,line_no,account_id,matched_journal_version,posting_seq,posting_digest,content_digest,prepared_by,'MIGRATION_V7' FROM plugin_finance_cash_movement_registration WHERE status='REGISTERED';
CREATE TRIGGER finance_cash_raw_claim AFTER INSERT OR UPDATE OF status ON plugin_finance_cash_movement_registration FOR EACH ROW EXECUTE FUNCTION plugin_finance_cash_raw_claim();
CREATE FUNCTION plugin_finance_cash_correction_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r record;g bigint;target_snapshot jsonb;
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'CASH_MATCH_IMMUTABLE';END IF;
 SELECT * INTO STRICT r FROM plugin_finance_cash_movement_registration WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id AND id=NEW.raw_registration_id FOR UPDATE;
 PERFORM plugin_finance_cash_match_open(NEW.tenant_id,NEW.book_id,NEW.year_id,r.occurred_date);
 SELECT source_generation INTO STRICT g FROM plugin_finance_cash_source_match_guard WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id AND raw_registration_id=NEW.raw_registration_id;
 IF r.status<>'VOIDED' OR (NEW.raw_version_at_proposal,NEW.raw_content_digest,NEW.legal_entity_id,NEW.account_id,NEW.prepared_by,NEW.last_edited_by) IS DISTINCT FROM (r.version,r.content_digest,r.legal_entity_id,r.account_id,r.prepared_by,r.prepared_by) THEN RAISE EXCEPTION 'CASH_MATCH_SOURCE_CHANGED';END IF;
 IF (NEW.target_journal_id,NEW.target_line_no)=(r.journal_id,r.line_no) THEN RAISE EXCEPTION 'CASH_MATCH_TARGET_UNCHANGED';END IF;
 IF TG_OP='INSERT' THEN
  IF NEW.status<>'DRAFT' OR NEW.version<>1 OR NEW.submitted_at IS NOT NULL OR NEW.approved_by IS NOT NULL OR NEW.approved_at IS NOT NULL OR NEW.approved_claim_id IS NOT NULL OR NEW.approved_match_generation IS NOT NULL OR NEW.decision_by IS NOT NULL OR NEW.decision_at IS NOT NULL OR NEW.decision_reason IS NOT NULL OR NEW.withdrawn_by IS NOT NULL OR NEW.withdrawn_at IS NOT NULL THEN RAISE EXCEPTION 'CASH_MATCH_INITIAL_DRAFT_REQUIRED';END IF;
  IF NEW.expected_match_generation<>g OR EXISTS(SELECT 1 FROM plugin_finance_cash_leg_claim WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id AND raw_registration_id=NEW.raw_registration_id AND state='ACTIVE') THEN RAISE EXCEPTION 'CASH_MATCH_GENERATION_OR_EFFECTIVE_CONFLICT';END IF;
 ELSE
  IF (to_jsonb(NEW)-ARRAY['status','version','submitted_at','approved_by','approved_at','approved_claim_id','approved_match_generation','decision_by','decision_at','decision_reason','withdrawn_at','withdrawn_by']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['status','version','submitted_at','approved_by','approved_at','approved_claim_id','approved_match_generation','decision_by','decision_at','decision_reason','withdrawn_at','withdrawn_by']) THEN RAISE EXCEPTION 'CASH_MATCH_CONTENT_IMMUTABLE';END IF;
  IF NEW.version<>OLD.version+1 OR NOT ((OLD.status='DRAFT' AND NEW.status IN('SUBMITTED','CANCELLED')) OR (OLD.status='SUBMITTED' AND NEW.status IN('APPROVED','REJECTED','CANCELLED')) OR (OLD.status='APPROVED' AND NEW.status='WITHDRAWN')) THEN RAISE EXCEPTION 'CASH_MATCH_INVALID_TRANSITION';END IF;
  IF OLD.submitted_at IS NOT NULL AND NEW.submitted_at IS DISTINCT FROM OLD.submitted_at THEN RAISE EXCEPTION 'CASH_MATCH_SUBMISSION_IMMUTABLE';END IF;
  IF NEW.status='SUBMITTED' AND (NEW.submitted_at IS NULL OR NEW.approved_by IS NOT NULL OR NEW.approved_at IS NOT NULL OR NEW.approved_claim_id IS NOT NULL OR NEW.approved_match_generation IS NOT NULL OR NEW.decision_by IS NOT NULL OR NEW.decision_at IS NOT NULL OR NEW.decision_reason IS NOT NULL OR NEW.withdrawn_at IS NOT NULL OR NEW.withdrawn_by IS NOT NULL) THEN RAISE EXCEPTION 'CASH_MATCH_SUBMIT_METADATA';END IF;
  IF NEW.status IN('REJECTED','CANCELLED') AND (NEW.approved_by IS NOT NULL OR NEW.approved_at IS NOT NULL OR NEW.approved_claim_id IS NOT NULL OR NEW.approved_match_generation IS NOT NULL OR NEW.withdrawn_at IS NOT NULL OR NEW.withdrawn_by IS NOT NULL) THEN RAISE EXCEPTION 'CASH_MATCH_TERMINAL_METADATA';END IF;
  IF NEW.status IN('APPROVED','REJECTED','CANCELLED') AND (NEW.decision_by IS NULL OR NEW.decision_at IS NULL OR coalesce(length(NEW.decision_reason),0) NOT BETWEEN 1 AND 1000) THEN RAISE EXCEPTION 'CASH_MATCH_DECISION_REQUIRED';END IF;
  IF NEW.status='CANCELLED' AND NEW.decision_by<>r.prepared_by THEN RAISE EXCEPTION 'CASH_MATCH_PREPARER_REQUIRED';END IF;
  IF NEW.status IN('APPROVED','REJECTED') AND NEW.decision_by IN(r.prepared_by,NEW.prepared_by,NEW.last_edited_by) THEN RAISE EXCEPTION 'CASH_MATCH_INDEPENDENT_REVIEW_REQUIRED';END IF;
  IF NEW.status='APPROVED' AND (NEW.submitted_at IS NULL OR NEW.approved_by IS DISTINCT FROM NEW.decision_by OR NEW.approved_at IS DISTINCT FROM NEW.decision_at OR NEW.approved_claim_id IS NULL OR NEW.approved_match_generation IS DISTINCT FROM g+1 OR NEW.expected_match_generation<>g OR NEW.withdrawn_at IS NOT NULL OR NEW.withdrawn_by IS NOT NULL) THEN RAISE EXCEPTION 'CASH_MATCH_APPROVAL_METADATA';END IF;
  IF NEW.status='WITHDRAWN' THEN
   IF (NEW.approved_by,NEW.approved_at,NEW.approved_claim_id,NEW.approved_match_generation,NEW.decision_by,NEW.decision_at,NEW.decision_reason) IS DISTINCT FROM (OLD.approved_by,OLD.approved_at,OLD.approved_claim_id,OLD.approved_match_generation,OLD.decision_by,OLD.decision_at,OLD.decision_reason) THEN RAISE EXCEPTION 'CASH_MATCH_HISTORIC_APPROVAL_IMMUTABLE';END IF;
   PERFORM 1 FROM plugin_finance_cash_match_withdrawal WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id AND correction_id=NEW.id AND active_claim_id=NEW.approved_claim_id AND status='APPROVED' AND reviewed_by=NEW.withdrawn_by AND reviewed_at=NEW.withdrawn_at;
   IF NOT FOUND OR NEW.withdrawn_by IS NULL OR NEW.withdrawn_at IS NULL THEN RAISE EXCEPTION 'CASH_MATCH_REVIEWED_WITHDRAWAL_REQUIRED';END IF;
  END IF;
 END IF;
 IF TG_OP='INSERT' OR NEW.status='APPROVED' THEN
  PERFORM plugin_finance_cash_match_target(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.raw_registration_id,NEW.target_journal_id,NEW.target_line_no,NEW.target_journal_version,NEW.target_posting_seq,NEW.target_posting_digest);
  IF EXISTS(SELECT 1 FROM plugin_finance_cash_leg_claim WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id AND state='ACTIVE' AND ((journal_id=NEW.target_journal_id AND line_no=NEW.target_line_no) OR raw_registration_id=NEW.raw_registration_id)) THEN RAISE EXCEPTION 'CASH_LEG_ALREADY_REGISTERED';END IF;
 END IF;
 IF TG_OP='INSERT' THEN
  SELECT jsonb_build_object('journalId',j.id,'lineNo',l.line_no,'accountId',l.account_id,'date',j.date,'journalVersion',j.version,'postingSeq',p.posting_seq,'postingDigest',p.digest,'number',j.number,'summary',j.summary,'direction',r.direction,'normalizedAmount',CASE WHEN l.debit>0 THEN l.debit::text ELSE l.credit::text END) INTO target_snapshot FROM plugin_finance_journal j JOIN plugin_finance_line l ON (l.tenant_id,l.book_id,l.year_id,l.journal_id)=(j.tenant_id,j.book_id,j.year_id,j.id) JOIN plugin_finance_posting p ON (p.tenant_id,p.book_id,p.year_id,p.journal_id)=(j.tenant_id,j.book_id,j.year_id,j.id) WHERE j.tenant_id=NEW.tenant_id AND j.book_id=NEW.book_id AND j.year_id=NEW.year_id AND j.id=NEW.target_journal_id AND l.line_no=NEW.target_line_no;
  IF NEW.content->'rawSnapshot' IS DISTINCT FROM r.content OR NEW.content->'originalMatchSnapshot' IS DISTINCT FROM r.content->'matchedLegSnapshot' OR NEW.content->'targetLegSnapshot' IS DISTINCT FROM target_snapshot OR (NEW.content->>'originalRegistrationId',NEW.content->>'rawContentDigest',NEW.content->>'preparedBy',NEW.content->>'sourceIdentityDigest',NEW.content->>'reason') IS DISTINCT FROM (r.id::text,r.content_digest,r.prepared_by::text,NEW.source_identity_digest,NEW.reason) OR (NEW.content->>'expectedSourceMatchGeneration')::bigint IS DISTINCT FROM NEW.expected_match_generation OR (NEW.content->>'expectedRawVersion')::bigint IS DISTINCT FROM NEW.raw_version_at_proposal THEN RAISE EXCEPTION 'CASH_MATCH_SNAPSHOT_MISMATCH';END IF;
 END IF;RETURN NEW;
END $$;
CREATE TRIGGER finance_cash_correction_guard BEFORE INSERT OR UPDATE OR DELETE ON plugin_finance_cash_match_correction FOR EACH ROW EXECUTE FUNCTION plugin_finance_cash_correction_guard();
CREATE FUNCTION plugin_finance_cash_correction_effect() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE g bigint;w record;
BEGIN
 IF NEW.status='APPROVED' THEN
  INSERT INTO plugin_finance_cash_leg_claim(tenant_id,book_id,year_id,id,raw_registration_id,claim_kind,correction_id,claim_generation,journal_id,line_no,account_id,journal_version,posting_seq,posting_digest,claim_content_digest,created_by) VALUES(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.approved_claim_id,NEW.raw_registration_id,'MATCH_CORRECTION',NEW.id,NEW.approved_match_generation,NEW.target_journal_id,NEW.target_line_no,NEW.account_id,NEW.target_journal_version,NEW.target_posting_seq,NEW.target_posting_digest,NEW.content_digest,NEW.approved_by);
 ELSIF NEW.status='WITHDRAWN' THEN
  SELECT source_generation INTO g FROM plugin_finance_cash_source_match_guard WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id AND raw_registration_id=NEW.raw_registration_id;
  SELECT * INTO STRICT w FROM plugin_finance_cash_match_withdrawal WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id AND correction_id=NEW.id AND active_claim_id=NEW.approved_claim_id AND status='APPROVED';
  UPDATE plugin_finance_cash_leg_claim SET state='RELEASED',released_by=w.reviewed_by,released_at=w.reviewed_at,release_reason=w.decision_reason,release_generation=g+1 WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id AND id=NEW.approved_claim_id AND state='ACTIVE';
  IF NOT FOUND THEN RAISE EXCEPTION 'CASH_MATCH_ACTIVE_CLAIM_REQUIRED';END IF;
 END IF;RETURN NEW;
END $$;
CREATE TRIGGER finance_cash_correction_effect AFTER UPDATE OF status ON plugin_finance_cash_match_correction FOR EACH ROW EXECUTE FUNCTION plugin_finance_cash_correction_effect();
CREATE FUNCTION plugin_finance_cash_withdrawal_guard() RETURNS trigger LANGUAGE plpgsql AS $$
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
 IF NEW.status<>'REJECTED' AND EXISTS(SELECT 1 FROM plugin_finance_cash_claim_usage WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id AND claim_id=NEW.active_claim_id) THEN RAISE EXCEPTION 'CASH_REGISTRATION_IN_USE';END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER finance_cash_withdrawal_guard BEFORE INSERT OR UPDATE OR DELETE ON plugin_finance_cash_match_withdrawal FOR EACH ROW EXECUTE FUNCTION plugin_finance_cash_withdrawal_guard();
CREATE FUNCTION plugin_finance_cash_withdrawal_effect() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF NEW.status='APPROVED' THEN UPDATE plugin_finance_cash_match_correction SET status='WITHDRAWN',version=version+1,withdrawn_by=NEW.reviewed_by,withdrawn_at=NEW.reviewed_at WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id AND id=NEW.correction_id AND status='APPROVED' AND approved_claim_id=NEW.active_claim_id;IF NOT FOUND THEN RAISE EXCEPTION 'CASH_MATCH_WITHDRAWAL_STALE';END IF;END IF;RETURN NEW;
END $$;
CREATE TRIGGER finance_cash_withdrawal_effect AFTER UPDATE OF status ON plugin_finance_cash_match_withdrawal FOR EACH ROW EXECUTE FUNCTION plugin_finance_cash_withdrawal_effect();
