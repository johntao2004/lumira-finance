CREATE TABLE plugin_finance_cash_scope_guard (
 tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,scope_version bigint NOT NULL DEFAULT 0 CHECK(scope_version>=0),
 PRIMARY KEY(tenant_id,book_id,year_id),FOREIGN KEY(tenant_id,book_id,year_id) REFERENCES plugin_finance_year_guard(tenant_id,book_id,year_id));
CREATE TABLE plugin_finance_cash_scope_revision (
 tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,id uuid NOT NULL,period_id uuid NOT NULL,
 schema_version text NOT NULL CHECK(schema_version='cashflow-working-paper/1'),standard text NOT NULL CHECK(standard IN('CAS','SMALL')),report_scope text NOT NULL CHECK(report_scope='SEPARATE'),currency text NOT NULL CHECK(currency='CNY'),template_id text NOT NULL,
 period_start date NOT NULL,period_end date NOT NULL,status text NOT NULL DEFAULT 'DRAFT' CHECK(status IN('DRAFT','SUBMITTED','APPROVED','REJECTED')),
 version bigint NOT NULL DEFAULT 1 CHECK(version>0),content_version bigint NOT NULL DEFAULT 1 CHECK(content_version=1),content_digest text NOT NULL CHECK(content_digest~'^[a-f0-9]{64}$'),content jsonb NOT NULL,
 prepared_by uuid NOT NULL,prepared_username text NOT NULL,last_edited_by uuid NOT NULL,reason text NOT NULL CHECK(length(reason) BETWEEN 1 AND 1000),
 submitted_at timestamptz,approved_by uuid,approved_at timestamptz,approved_scope_version bigint,decision_by uuid,decided_at timestamptz,decision_reason text,created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,book_id,year_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id) REFERENCES plugin_finance_cash_scope_guard(tenant_id,book_id,year_id),
 FOREIGN KEY(tenant_id,book_id,year_id,period_id) REFERENCES plugin_finance_period(tenant_id,book_id,year_id,id),
 CHECK(period_start=date_trunc('month',period_start)::date AND period_end=(period_start+interval '1 month - 1 day')::date),
 CHECK((standard='CAS' AND template_id='CAS-CF-2019-A2') OR (standard='SMALL' AND template_id='SBE-CF-2011')),
 CHECK(prepared_by=last_edited_by),CHECK(approved_by IS NULL OR (approved_by<>prepared_by AND approved_by<>last_edited_by)),
 CHECK(approved_scope_version IS NULL OR approved_scope_version>0));
CREATE UNIQUE INDEX finance_cash_one_approved_month ON plugin_finance_cash_scope_revision(tenant_id,book_id,year_id,period_start) WHERE status='APPROVED';
CREATE UNIQUE INDEX finance_cash_scope_generation ON plugin_finance_cash_scope_revision(tenant_id,book_id,year_id,approved_scope_version) WHERE approved_scope_version IS NOT NULL;
CREATE INDEX finance_cash_scope_cursor ON plugin_finance_cash_scope_revision(tenant_id,book_id,year_id,created_at DESC,id DESC);
CREATE TABLE plugin_finance_cash_scope_item (
 tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,scope_revision_id uuid NOT NULL,item_no integer NOT NULL CHECK(item_no BETWEEN 1 AND 20),account_id uuid NOT NULL,
 instrument_kind text NOT NULL CHECK(instrument_kind IN('CASH_ON_HAND','DEMAND_DEPOSIT')),single_cash_instrument boolean NOT NULL CHECK(single_cash_instrument),qualification text NOT NULL CHECK(qualification IN('INCLUDED','EXCLUDED')),restriction_kind text NOT NULL CHECK(restriction_kind IN('NONE','RESTRICTED','OTHER')),available_on_demand boolean NOT NULL,eligibility_basis text NOT NULL CHECK(length(eligibility_basis) BETWEEN 1 AND 2000),
 evidence_original_id uuid NOT NULL,original_sha256 text NOT NULL CHECK(original_sha256~'^[a-f0-9]{64}$'),original_filename text NOT NULL,account_version_digest text NOT NULL CHECK(account_version_digest~'^[a-f0-9]{64}$'),account_snapshot jsonb NOT NULL,
 PRIMARY KEY(tenant_id,book_id,year_id,scope_revision_id,item_no),UNIQUE(tenant_id,book_id,year_id,scope_revision_id,account_id),
 FOREIGN KEY(tenant_id,book_id,year_id,scope_revision_id) REFERENCES plugin_finance_cash_scope_revision(tenant_id,book_id,year_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,account_id) REFERENCES plugin_finance_account(tenant_id,book_id,year_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,evidence_original_id) REFERENCES plugin_finance_original(tenant_id,book_id,year_id,id),
 CHECK(qualification<>'INCLUDED' OR (restriction_kind='NONE' AND available_on_demand)));
CREATE FUNCTION plugin_finance_cash_header_guard() RETURNS trigger LANGUAGE plpgsql AS $$ DECLARE actual_count integer; BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'CASH_SCOPE_IMMUTABLE'; END IF;
 PERFORM 1 FROM plugin_finance_year_guard WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id FOR UPDATE;
 PERFORM 1 FROM plugin_finance_cash_scope_guard WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id FOR UPDATE;
 PERFORM 1 FROM plugin_finance_year y JOIN plugin_finance_book b ON b.tenant_id=y.tenant_id AND b.id=y.book_id JOIN plugin_finance_period p ON p.tenant_id=y.tenant_id AND p.book_id=y.book_id AND p.year_id=y.id WHERE y.tenant_id=NEW.tenant_id AND y.book_id=NEW.book_id AND y.id=NEW.year_id AND b.standard=NEW.standard AND NEW.content->>'legalEntityId'=b.legal_entity_id::text AND y.status='OPEN' AND y.precision_profile='V4' AND NOT y.precision_review_required AND p.id=NEW.period_id AND p.status='OPEN' AND p.month=extract(month FROM NEW.period_start) AND y.year=extract(year FROM NEW.period_start) FOR UPDATE OF p;
 IF NOT FOUND THEN RAISE EXCEPTION 'CASH_SCOPE_YEAR_OR_PERIOD_CLOSED'; END IF;
 IF (NEW.content->>'tenantId',NEW.content->>'bookId',NEW.content->>'yearId',NEW.content->>'standard',NEW.content->>'reportScope',NEW.content->>'currency',NEW.content->>'templateId',NEW.content->>'periodStart',NEW.content->>'periodEnd',NEW.content->>'preparedBy',NEW.content->>'lastEditedBy',NEW.content->>'reason',NEW.content->>'schemaVersion') IS DISTINCT FROM (NEW.tenant_id::text,NEW.book_id::text,NEW.year_id::text,NEW.standard,NEW.report_scope,NEW.currency,NEW.template_id,NEW.period_start::text,NEW.period_end::text,NEW.prepared_by::text,NEW.last_edited_by::text,NEW.reason,NEW.schema_version) THEN RAISE EXCEPTION 'CASH_SCOPE_HEADER_SNAPSHOT_MISMATCH'; END IF;
 IF TG_OP='INSERT' THEN IF NEW.status<>'DRAFT' OR NEW.version<>1 OR NEW.content_version<>1 OR NEW.approved_by IS NOT NULL OR NEW.approved_scope_version IS NOT NULL OR NEW.submitted_at IS NOT NULL OR NEW.approved_at IS NOT NULL OR NEW.decision_by IS NOT NULL OR NEW.decided_at IS NOT NULL OR NEW.decision_reason IS NOT NULL THEN RAISE EXCEPTION 'CASH_SCOPE_INITIAL_DRAFT_REQUIRED'; END IF;RETURN NEW;END IF;
 IF OLD.status IN('APPROVED','REJECTED') THEN RAISE EXCEPTION 'CASH_SCOPE_IMMUTABLE'; END IF;
 IF (NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.id,NEW.period_id,NEW.schema_version,NEW.standard,NEW.report_scope,NEW.currency,NEW.template_id,NEW.period_start,NEW.period_end,NEW.content_version,NEW.content_digest,NEW.prepared_by,NEW.prepared_username,NEW.last_edited_by,NEW.reason,NEW.created_at) IS DISTINCT FROM (OLD.tenant_id,OLD.book_id,OLD.year_id,OLD.id,OLD.period_id,OLD.schema_version,OLD.standard,OLD.report_scope,OLD.currency,OLD.template_id,OLD.period_start,OLD.period_end,OLD.content_version,OLD.content_digest,OLD.prepared_by,OLD.prepared_username,OLD.last_edited_by,OLD.reason,OLD.created_at) OR NEW.content IS DISTINCT FROM OLD.content THEN RAISE EXCEPTION 'CASH_SCOPE_CONTENT_IMMUTABLE'; END IF;
 IF NEW.version<>OLD.version+1 OR NOT ((OLD.status='DRAFT' AND NEW.status='SUBMITTED') OR (OLD.status='SUBMITTED' AND NEW.status IN('APPROVED','REJECTED'))) THEN RAISE EXCEPTION 'CASH_SCOPE_INVALID_TRANSITION'; END IF;
 SELECT count(*) INTO actual_count FROM plugin_finance_cash_scope_item WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id AND scope_revision_id=NEW.id;
 IF actual_count<1 OR actual_count>20 OR actual_count<>jsonb_array_length(NEW.content->'items') THEN RAISE EXCEPTION 'CASH_SCOPE_ITEMS_INCOMPLETE'; END IF;
 IF NEW.submitted_at IS NULL OR (OLD.status='SUBMITTED' AND NEW.submitted_at IS DISTINCT FROM OLD.submitted_at) THEN RAISE EXCEPTION 'CASH_SCOPE_SUBMISSION_METADATA_REQUIRED'; END IF;
 IF NEW.status='SUBMITTED' AND (NEW.approved_by IS NOT NULL OR NEW.approved_at IS NOT NULL OR NEW.approved_scope_version IS NOT NULL OR NEW.decision_by IS NOT NULL OR NEW.decided_at IS NOT NULL OR NEW.decision_reason IS NOT NULL) THEN RAISE EXCEPTION 'CASH_SCOPE_PREMATURE_REVIEW_METADATA'; END IF;
 IF NEW.status='REJECTED' AND (NEW.approved_by IS NOT NULL OR NEW.approved_at IS NOT NULL OR NEW.approved_scope_version IS NOT NULL) THEN RAISE EXCEPTION 'CASH_SCOPE_REJECTED_IS_NOT_APPROVED'; END IF;
 IF NEW.status='APPROVED' AND (NEW.decision_by IS DISTINCT FROM NEW.approved_by OR NEW.decided_at IS DISTINCT FROM NEW.approved_at OR NEW.approved_scope_version IS NULL) THEN RAISE EXCEPTION 'CASH_SCOPE_APPROVAL_METADATA_MISMATCH'; END IF;
 IF NEW.status IN('APPROVED','REJECTED') AND (NEW.decision_by IS NULL OR NEW.decision_by=NEW.prepared_by OR NEW.decision_by=NEW.last_edited_by OR NEW.decided_at IS NULL OR coalesce(length(NEW.decision_reason),0)=0) THEN RAISE EXCEPTION 'CASH_SCOPE_INDEPENDENT_REVIEW_REQUIRED'; END IF;
 IF NEW.status='APPROVED' THEN
  IF NEW.approved_by IS NULL OR NEW.approved_by=NEW.prepared_by OR NEW.approved_by=NEW.last_edited_by OR NEW.approved_at IS NULL THEN RAISE EXCEPTION 'CASH_SCOPE_INDEPENDENT_APPROVAL_REQUIRED'; END IF;
  PERFORM 1 FROM plugin_finance_cash_scope_guard WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id AND scope_version=NEW.approved_scope_version;
  IF NOT FOUND THEN RAISE EXCEPTION 'CASH_SCOPE_VERSION_REQUIRED'; END IF;
 END IF;RETURN NEW;
END $$;
CREATE TRIGGER finance_cash_scope_header_guard BEFORE INSERT OR UPDATE OR DELETE ON plugin_finance_cash_scope_revision FOR EACH ROW EXECUTE FUNCTION plugin_finance_cash_header_guard();
CREATE FUNCTION plugin_finance_cash_item_guard() RETURNS trigger LANGUAGE plpgsql AS $$ DECLARE header record; expected jsonb; BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'CASH_SCOPE_ITEM_IMMUTABLE'; END IF;
 SELECT status,content INTO header FROM plugin_finance_cash_scope_revision WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id AND id=NEW.scope_revision_id;
 IF NOT FOUND OR header.status<>'DRAFT' THEN RAISE EXCEPTION 'CASH_SCOPE_ITEMS_FROZEN'; END IF;
 expected:=header.content->'items'->(NEW.item_no-1);
 IF expected IS NULL OR (expected->>'accountId',expected->>'instrumentKind',(expected->>'singleCashInstrument')::boolean,expected->>'qualification',expected->>'restrictionKind',(expected->>'availableOnDemand')::boolean,expected->>'eligibilityBasis',expected->>'evidenceOriginalId',expected->>'originalSha256',expected->>'originalFilename',expected->>'accountVersionDigest') IS DISTINCT FROM (NEW.account_id::text,NEW.instrument_kind,NEW.single_cash_instrument,NEW.qualification,NEW.restriction_kind,NEW.available_on_demand,NEW.eligibility_basis,NEW.evidence_original_id::text,NEW.original_sha256,NEW.original_filename,NEW.account_version_digest) OR expected->'accountSnapshot' IS DISTINCT FROM NEW.account_snapshot THEN RAISE EXCEPTION 'CASH_SCOPE_ITEM_SNAPSHOT_MISMATCH'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER finance_cash_scope_item_guard BEFORE INSERT OR UPDATE OR DELETE ON plugin_finance_cash_scope_item FOR EACH ROW EXECUTE FUNCTION plugin_finance_cash_item_guard();
