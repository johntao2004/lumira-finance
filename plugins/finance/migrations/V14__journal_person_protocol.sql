-- New per-year manual-NORMAL person protocol. Published V1-V13 are never rewritten.
ALTER TABLE plugin_finance_person_binding ADD UNIQUE(tenant_id,id,account_id,person_id);
CREATE TABLE plugin_finance_journal_person_mode (
 tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,mode text NOT NULL DEFAULT 'ACCOUNT_ONLY' CHECK(mode IN('ACCOUNT_ONLY','JOURNAL_PERSON_SOD')),
 epoch uuid NOT NULL DEFAULT gen_random_uuid(),generation bigint NOT NULL DEFAULT 0 CHECK(generation>=0),activation_id uuid,effective_at timestamptz,
 PRIMARY KEY(tenant_id,book_id,year_id),UNIQUE(tenant_id,book_id,year_id,epoch),
 FOREIGN KEY(tenant_id,book_id,year_id) REFERENCES plugin_finance_year(tenant_id,book_id,id),
 CHECK((mode='ACCOUNT_ONLY' AND activation_id IS NULL AND effective_at IS NULL) OR (mode='JOURNAL_PERSON_SOD' AND activation_id IS NOT NULL AND effective_at IS NOT NULL))
);
INSERT INTO plugin_finance_journal_person_mode(tenant_id,book_id,year_id) SELECT tenant_id,book_id,id FROM plugin_finance_year;
CREATE FUNCTION plugin_finance_journal_person_new_year() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN INSERT INTO plugin_finance_journal_person_mode(tenant_id,book_id,year_id) VALUES(NEW.tenant_id,NEW.book_id,NEW.id);RETURN NEW;END $$;
CREATE TRIGGER finance_journal_person_new_year AFTER INSERT ON plugin_finance_year FOR EACH ROW EXECUTE FUNCTION plugin_finance_journal_person_new_year();
CREATE TABLE plugin_finance_journal_person_activation (
 tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,id uuid NOT NULL,epoch uuid NOT NULL,
 status text NOT NULL DEFAULT 'PENDING' CHECK(status IN('PENDING','APPROVED','REJECTED','CANCELLED')),version bigint NOT NULL DEFAULT 1 CHECK(version>0),
 requester jsonb NOT NULL,requester_account uuid GENERATED ALWAYS AS ((requester->>'accountId')::uuid) STORED,requester_person uuid GENERATED ALWAYS AS ((requester->>'personId')::uuid) STORED,requester_binding uuid GENERATED ALWAYS AS ((requester->>'bindingId')::uuid) STORED,
 requester_username text NOT NULL,reason text NOT NULL CHECK(length(reason) BETWEEN 1 AND 1000),request_digest text NOT NULL CHECK(request_digest ~ '^[a-f0-9]{64}$'),
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,expires_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP+interval '7 days',
 decider jsonb,decider_account uuid GENERATED ALWAYS AS ((decider->>'accountId')::uuid) STORED,decider_person uuid GENERATED ALWAYS AS ((decider->>'personId')::uuid) STORED,decider_binding uuid GENERATED ALWAYS AS ((decider->>'bindingId')::uuid) STORED,
 decided_at timestamptz,decision_reason text,
 PRIMARY KEY(tenant_id,book_id,year_id,id),UNIQUE(tenant_id,book_id,year_id,id,epoch),
 FOREIGN KEY(tenant_id,book_id,year_id,epoch) REFERENCES plugin_finance_journal_person_mode(tenant_id,book_id,year_id,epoch),
 FOREIGN KEY(tenant_id,requester_binding,requester_account,requester_person) REFERENCES plugin_finance_person_binding(tenant_id,id,account_id,person_id),
 FOREIGN KEY(tenant_id,decider_binding,decider_account,decider_person) REFERENCES plugin_finance_person_binding(tenant_id,id,account_id,person_id),
 CHECK((status='PENDING' AND decider IS NULL AND decided_at IS NULL AND decision_reason IS NULL) OR(status<>'PENDING' AND decider IS NOT NULL AND decided_at IS NOT NULL AND length(decision_reason) BETWEEN 1 AND 1000))
);
CREATE UNIQUE INDEX finance_journal_person_one_pending ON plugin_finance_journal_person_activation(tenant_id,book_id,year_id) WHERE status='PENDING';
CREATE INDEX finance_journal_person_activation_latest ON plugin_finance_journal_person_activation(tenant_id,book_id,year_id,created_at DESC,id DESC);
ALTER TABLE plugin_finance_journal_person_mode ADD FOREIGN KEY(tenant_id,book_id,year_id,activation_id,epoch) REFERENCES plugin_finance_journal_person_activation(tenant_id,book_id,year_id,id,epoch) DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE plugin_finance_journal_person_content (
 tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,journal_id uuid NOT NULL,content_version bigint NOT NULL CHECK(content_version>0),
 format text NOT NULL CHECK(format='JOURNAL_PERSON_CONTENT_PG_V1'),snapshot jsonb NOT NULL,canonical_text text NOT NULL,digest text NOT NULL CHECK(digest ~ '^[a-f0-9]{64}$'),
 maker jsonb NOT NULL,maker_account uuid GENERATED ALWAYS AS ((maker->>'accountId')::uuid) STORED,maker_person uuid GENERATED ALWAYS AS ((maker->>'personId')::uuid) STORED,maker_binding uuid GENERATED ALWAYS AS ((maker->>'bindingId')::uuid) STORED,
 editor jsonb NOT NULL,editor_account uuid GENERATED ALWAYS AS ((editor->>'accountId')::uuid) STORED,editor_person uuid GENERATED ALWAYS AS ((editor->>'personId')::uuid) STORED,editor_binding uuid GENERATED ALWAYS AS ((editor->>'bindingId')::uuid) STORED,
 created_journal_version bigint NOT NULL CHECK(created_journal_version>0),created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(tenant_id,book_id,year_id,journal_id,content_version),UNIQUE(tenant_id,book_id,year_id,journal_id,content_version,digest),
 FOREIGN KEY(tenant_id,book_id,year_id,journal_id) REFERENCES plugin_finance_journal(tenant_id,book_id,year_id,id),
 FOREIGN KEY(tenant_id,maker_binding,maker_account,maker_person) REFERENCES plugin_finance_person_binding(tenant_id,id,account_id,person_id),
 FOREIGN KEY(tenant_id,editor_binding,editor_account,editor_person) REFERENCES plugin_finance_person_binding(tenant_id,id,account_id,person_id),
 CHECK(canonical_text=snapshot::text AND digest=encode(sha256(convert_to(canonical_text,'UTF8')),'hex')),
 CHECK(maker_account IS NOT NULL AND maker_person IS NOT NULL AND maker_binding IS NOT NULL AND editor_account IS NOT NULL AND editor_person IS NOT NULL AND editor_binding IS NOT NULL AND editor_account=maker_account AND editor_person=maker_person AND editor_binding=maker_binding)
);
CREATE TABLE plugin_finance_journal_person_approval (
 tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,journal_id uuid NOT NULL,id uuid NOT NULL,content_version bigint NOT NULL,content_digest text NOT NULL,
 approved_journal_version bigint NOT NULL CHECK(approved_journal_version>1),approver jsonb NOT NULL,
 approver_account uuid GENERATED ALWAYS AS ((approver->>'accountId')::uuid) STORED,approver_person uuid GENERATED ALWAYS AS ((approver->>'personId')::uuid) STORED,approver_binding uuid GENERATED ALWAYS AS ((approver->>'bindingId')::uuid) STORED,
 approver_username text NOT NULL,approved_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,expires_at timestamptz NOT NULL,
 PRIMARY KEY(tenant_id,book_id,year_id,id),UNIQUE(tenant_id,book_id,year_id,journal_id,id),UNIQUE(tenant_id,book_id,year_id,journal_id,approved_journal_version),
 FOREIGN KEY(tenant_id,book_id,year_id,journal_id,content_version,content_digest) REFERENCES plugin_finance_journal_person_content(tenant_id,book_id,year_id,journal_id,content_version,digest),
 FOREIGN KEY(tenant_id,approver_binding,approver_account,approver_person) REFERENCES plugin_finance_person_binding(tenant_id,id,account_id,person_id),
 CHECK(approver_account IS NOT NULL AND approver_person IS NOT NULL AND approver_binding IS NOT NULL AND expires_at>approved_at AND expires_at<=approved_at+interval '7 days')
);
CREATE TABLE plugin_finance_journal_person_transition (
 tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,journal_id uuid NOT NULL,journal_version bigint NOT NULL CHECK(journal_version>0),
 previous_version bigint,action text NOT NULL CHECK(action IN('CREATE','EDIT','DOCUMENTS','SUBMIT','WITHDRAW','REJECT','CANCEL','APPROVE','POST','TERMINATE')),
 from_state text,to_state text NOT NULL CHECK(to_state IN('DRAFT','SUBMITTED','APPROVED','POSTED','CANCELLED')),
 content_version bigint NOT NULL,content_digest text NOT NULL,actor jsonb NOT NULL,
 actor_account uuid GENERATED ALWAYS AS ((actor->>'accountId')::uuid) STORED,actor_person uuid GENERATED ALWAYS AS ((actor->>'personId')::uuid) STORED,actor_binding uuid GENERATED ALWAYS AS ((actor->>'bindingId')::uuid) STORED,
 approval_id uuid,reason text,created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(tenant_id,book_id,year_id,journal_id,journal_version),
 FOREIGN KEY(tenant_id,book_id,year_id,journal_id) REFERENCES plugin_finance_journal(tenant_id,book_id,year_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,journal_id,previous_version) REFERENCES plugin_finance_journal_person_transition(tenant_id,book_id,year_id,journal_id,journal_version) DEFERRABLE INITIALLY DEFERRED,
 FOREIGN KEY(tenant_id,book_id,year_id,journal_id,content_version,content_digest) REFERENCES plugin_finance_journal_person_content(tenant_id,book_id,year_id,journal_id,content_version,digest),
 FOREIGN KEY(tenant_id,book_id,year_id,journal_id,approval_id) REFERENCES plugin_finance_journal_person_approval(tenant_id,book_id,year_id,journal_id,id),
 FOREIGN KEY(tenant_id,actor_binding,actor_account,actor_person) REFERENCES plugin_finance_person_binding(tenant_id,id,account_id,person_id),
 CHECK(actor_account IS NOT NULL AND actor_person IS NOT NULL AND actor_binding IS NOT NULL),
 CHECK((journal_version=1 AND previous_version IS NULL AND action='CREATE') OR(journal_version>1 AND previous_version=journal_version-1)),
 CHECK((action='APPROVE')=(approval_id IS NOT NULL)), CHECK((action='CREATE')=(journal_version=1))
);
ALTER TABLE plugin_finance_journal_person_content ADD FOREIGN KEY(tenant_id,book_id,year_id,journal_id,created_journal_version) REFERENCES plugin_finance_journal_person_transition(tenant_id,book_id,year_id,journal_id,journal_version) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE plugin_finance_journal_person_approval ADD FOREIGN KEY(tenant_id,book_id,year_id,journal_id,approved_journal_version) REFERENCES plugin_finance_journal_person_transition(tenant_id,book_id,year_id,journal_id,journal_version) DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE plugin_finance_journal_person_current (
 tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,journal_id uuid NOT NULL,journal_version bigint NOT NULL,content_version bigint NOT NULL,content_digest text NOT NULL,approval_id uuid,
 PRIMARY KEY(tenant_id,book_id,year_id,journal_id),
 FOREIGN KEY(tenant_id,book_id,year_id,journal_id) REFERENCES plugin_finance_journal(tenant_id,book_id,year_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,journal_id,journal_version) REFERENCES plugin_finance_journal_person_transition(tenant_id,book_id,year_id,journal_id,journal_version),
 FOREIGN KEY(tenant_id,book_id,year_id,journal_id,content_version,content_digest) REFERENCES plugin_finance_journal_person_content(tenant_id,book_id,year_id,journal_id,content_version,digest),
 FOREIGN KEY(tenant_id,book_id,year_id,journal_id,approval_id) REFERENCES plugin_finance_journal_person_approval(tenant_id,book_id,year_id,journal_id,id)
);
ALTER TABLE plugin_finance_posting ADD UNIQUE(tenant_id,book_id,year_id,journal_id,posting_seq,digest);
CREATE TABLE plugin_finance_journal_person_post (
 tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,journal_id uuid NOT NULL,id uuid NOT NULL,approval_id uuid NOT NULL,content_version bigint NOT NULL,content_digest text NOT NULL,
 posting_seq bigint NOT NULL,posting_digest text NOT NULL,posted_journal_version bigint NOT NULL,poster jsonb NOT NULL,
 poster_account uuid GENERATED ALWAYS AS ((poster->>'accountId')::uuid) STORED,poster_person uuid GENERATED ALWAYS AS ((poster->>'personId')::uuid) STORED,poster_binding uuid GENERATED ALWAYS AS ((poster->>'bindingId')::uuid) STORED,
 posted_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(tenant_id,book_id,year_id,journal_id),UNIQUE(tenant_id,book_id,year_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,journal_id,posting_seq,posting_digest) REFERENCES plugin_finance_posting(tenant_id,book_id,year_id,journal_id,posting_seq,digest),
 FOREIGN KEY(tenant_id,book_id,year_id,journal_id,approval_id) REFERENCES plugin_finance_journal_person_approval(tenant_id,book_id,year_id,journal_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,journal_id,content_version,content_digest) REFERENCES plugin_finance_journal_person_content(tenant_id,book_id,year_id,journal_id,content_version,digest),
 FOREIGN KEY(tenant_id,book_id,year_id,journal_id,posted_journal_version) REFERENCES plugin_finance_journal_person_transition(tenant_id,book_id,year_id,journal_id,journal_version),
 FOREIGN KEY(tenant_id,poster_binding,poster_account,poster_person) REFERENCES plugin_finance_person_binding(tenant_id,id,account_id,person_id),
 CHECK(poster_account IS NOT NULL AND poster_person IS NOT NULL AND poster_binding IS NOT NULL)
);
CREATE TABLE plugin_finance_journal_person_termination (
 tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,journal_id uuid NOT NULL,id uuid NOT NULL,journal_version bigint NOT NULL,content_version bigint NOT NULL,content_digest text NOT NULL,actor jsonb NOT NULL,
 actor_account uuid GENERATED ALWAYS AS ((actor->>'accountId')::uuid) STORED,actor_person uuid GENERATED ALWAYS AS ((actor->>'personId')::uuid) STORED,actor_binding uuid GENERATED ALWAYS AS ((actor->>'bindingId')::uuid) STORED,
 reason text NOT NULL CHECK(length(reason) BETWEEN 1 AND 1000),decided_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(tenant_id,book_id,year_id,journal_id),UNIQUE(tenant_id,book_id,year_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,journal_id,journal_version) REFERENCES plugin_finance_journal_person_transition(tenant_id,book_id,year_id,journal_id,journal_version),
 FOREIGN KEY(tenant_id,book_id,year_id,journal_id,content_version,content_digest) REFERENCES plugin_finance_journal_person_content(tenant_id,book_id,year_id,journal_id,content_version,digest),
 FOREIGN KEY(tenant_id,actor_binding,actor_account,actor_person) REFERENCES plugin_finance_person_binding(tenant_id,id,account_id,person_id),
 CHECK(actor_account IS NOT NULL AND actor_person IS NOT NULL AND actor_binding IS NOT NULL)
);
CREATE TABLE plugin_finance_journal_person_request (
 actor_id uuid NOT NULL,request_key text NOT NULL,tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,route text NOT NULL,payload_hash text NOT NULL,result_id uuid NOT NULL,
 PRIMARY KEY(actor_id,request_key),FOREIGN KEY(tenant_id,book_id,year_id) REFERENCES plugin_finance_journal_person_mode(tenant_id,book_id,year_id)
);
CREATE TABLE plugin_finance_journal_person_audit (
 tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,id uuid NOT NULL,actor_id uuid NOT NULL,action text NOT NULL,target_id uuid NOT NULL,payload jsonb NOT NULL,created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(tenant_id,book_id,year_id,id),FOREIGN KEY(tenant_id,book_id,year_id) REFERENCES plugin_finance_journal_person_mode(tenant_id,book_id,year_id)
);

CREATE FUNCTION plugin_finance_journal_person_identity(t uuid,a uuid,active_required boolean DEFAULT true) RETURNS jsonb LANGUAGE plpgsql STABLE AS $$
DECLARE b plugin_finance_person_binding; r plugin_finance_person_realm;
BEGIN
 SELECT * INTO b FROM plugin_finance_person_binding WHERE tenant_id=t AND account_id=a;
 IF NOT FOUND OR(active_required AND b.status<>'ACTIVE') THEN RAISE EXCEPTION 'JOURNAL_PERSON_IDENTITY_UNAVAILABLE'; END IF;
 SELECT * INTO r FROM plugin_finance_person_realm WHERE tenant_id=t;
 IF NOT FOUND THEN RAISE EXCEPTION 'JOURNAL_PERSON_IDENTITY_UNAVAILABLE'; END IF;
 RETURN jsonb_build_object('accountId',b.account_id,'personId',b.person_id,'bindingId',b.id,'bindingVersion',b.version::text,'authorityId',r.authority_id,'authorityManifestDigest',r.manifest_sha256,'assurance','ORG_ATTESTED');
END $$;
CREATE FUNCTION plugin_finance_journal_person_lock(t uuid,b uuid,y uuid) RETURNS text LANGUAGE plpgsql AS $$
DECLARE result text;
BEGIN
 SELECT mode INTO result FROM plugin_finance_journal_person_mode WHERE tenant_id=t AND book_id=b AND year_id=y FOR SHARE;
 IF NOT FOUND THEN RAISE EXCEPTION 'JOURNAL_PERSON_MODE_BASELINE_UNAVAILABLE'; END IF;
 RETURN result;
END $$;
CREATE FUNCTION plugin_finance_journal_person_empty(t uuid,b uuid,y uuid) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM plugin_finance_year a JOIN plugin_finance_book bk ON(bk.tenant_id,bk.id)=(a.tenant_id,a.book_id) JOIN plugin_finance_year_guard g ON(g.tenant_id,g.book_id,g.year_id)=(a.tenant_id,a.book_id,a.id) WHERE a.tenant_id=t AND a.book_id=b AND a.id=y AND a.status='OPEN' AND a.precision_profile='V4' AND NOT a.precision_review_required AND a.currency_minor_unit=2 AND bk.base_currency='CNY' AND g.posting_seq=0 AND g.opening_version=0 AND g.baseline_version=0 AND g.source_close_version=0)
 AND (SELECT count(*)=12 AND count(DISTINCT p.month)=12 AND bool_and(p.status='OPEN') FROM plugin_finance_period p JOIN plugin_finance_year a ON(a.tenant_id,a.book_id,a.id)=(p.tenant_id,p.book_id,p.year_id) WHERE p.tenant_id=t AND p.book_id=b AND p.year_id=y)
 AND NOT EXISTS(SELECT 1 FROM plugin_finance_journal WHERE tenant_id=t AND book_id=b AND year_id=y)
 AND NOT EXISTS(SELECT 1 FROM plugin_finance_posting WHERE tenant_id=t AND book_id=b AND year_id=y)
 AND NOT EXISTS(SELECT 1 FROM plugin_finance_opening WHERE tenant_id=t AND book_id=b AND year_id=y)
 AND NOT EXISTS(SELECT 1 FROM plugin_finance_close_plan WHERE tenant_id=t AND book_id=b AND(source_year_id=y OR target_year_id=y))
 AND NOT EXISTS(SELECT 1 FROM plugin_finance_source_event WHERE tenant_id=t AND book_id=b AND year_id=y)
 AND NOT EXISTS(SELECT 1 FROM plugin_finance_business_effect WHERE tenant_id=t AND book_id=b AND year_id=y)
$$;
CREATE FUNCTION plugin_finance_journal_person_snapshot(t uuid,b uuid,y uuid,j uuid) RETURNS jsonb LANGUAGE plpgsql STABLE AS $$
DECLARE h plugin_finance_journal; result jsonb; n integer;
BEGIN
 SELECT * INTO h FROM plugin_finance_journal WHERE tenant_id=t AND book_id=b AND year_id=y AND id=j;
 IF NOT FOUND OR h.summary IS NULL OR length(h.summary)>500 OR h.amount_format_version<>'V4_8' OR h.type<>'NORMAL' OR h.reversal_of IS NOT NULL OR h.source_event_id IS NOT NULL OR h.closing_basis IS NOT NULL THEN RAISE EXCEPTION 'JOURNAL_PERSON_UNSUPPORTED_PATH'; END IF;
 IF NOT EXISTS(SELECT 1 FROM plugin_finance_period p JOIN plugin_finance_year a ON(a.tenant_id,a.book_id,a.id)=(p.tenant_id,p.book_id,p.year_id) WHERE(p.tenant_id,p.book_id,p.year_id,p.id)=(t,b,y,h.period_id) AND a.year=extract(year FROM h.date)::integer AND p.month=extract(month FROM h.date)::integer) THEN RAISE EXCEPTION 'JOURNAL_PERSON_PERIOD_MISMATCH'; END IF;
 SELECT count(*) INTO n FROM plugin_finance_line WHERE tenant_id=t AND book_id=b AND year_id=y AND journal_id=j;
 IF n NOT BETWEEN 2 AND 500 OR (SELECT min(line_no)<>1 OR max(line_no)<>n FROM plugin_finance_line WHERE tenant_id=t AND book_id=b AND year_id=y AND journal_id=j) OR EXISTS(SELECT 1 FROM plugin_finance_line WHERE tenant_id=t AND book_id=b AND year_id=y AND journal_id=j AND(line_no NOT BETWEEN 1 AND 500 OR memo IS NULL OR length(memo)>500 OR debit::text='NaN' OR credit::text='NaN' OR debit<0 OR credit<0 OR debit<>trunc(debit,2) OR credit<>trunc(credit,2))) THEN RAISE EXCEPTION 'JOURNAL_PERSON_INVALID_CONTENT'; END IF;
 SELECT count(*) INTO n FROM plugin_finance_document WHERE tenant_id=t AND book_id=b AND year_id=y AND journal_id=j;
 IF n>1000 OR EXISTS(SELECT 1 FROM plugin_finance_document d LEFT JOIN plugin_finance_original o ON(o.tenant_id,o.book_id,o.year_id,o.id)=(d.tenant_id,d.book_id,d.year_id,d.original_id) WHERE d.tenant_id=t AND d.book_id=b AND d.year_id=y AND d.journal_id=j AND(d.filename IS NULL OR length(d.filename)>500 OR d.source IS NULL OR length(d.source)>100 OR d.original_location IS NULL OR length(d.original_location)>500 OR o.id IS NULL OR o.sha256<>d.sha256 OR o.filename<>d.filename OR o.created_by<>h.created_by)) THEN RAISE EXCEPTION 'JOURNAL_PERSON_INVALID_EVIDENCE'; END IF;
 SELECT jsonb_build_object('date',h.date,'type',h.type,'summary',h.summary,'currency','CNY','amountFormatVersion','V4_8',
 'lines',(SELECT jsonb_agg(jsonb_build_object('lineNo',line_no,'accountId',account_id,'debit',(debit::numeric(28,8))::text,'credit',(credit::numeric(28,8))::text,'memo',memo) ORDER BY line_no) FROM plugin_finance_line WHERE tenant_id=t AND book_id=b AND year_id=y AND journal_id=j),
 'documents',COALESCE((SELECT jsonb_agg(jsonb_build_object('id',id,'originalId',original_id,'sha256',sha256,'filename',filename,'source',source,'originalLocation',original_location) ORDER BY id) FROM plugin_finance_document WHERE tenant_id=t AND book_id=b AND year_id=y AND journal_id=j),'[]'::jsonb),
 'reversalOf',NULL,'sourceEventId',NULL,'closingBasis',NULL) INTO result;
 RETURN result;
END $$;
CREATE FUNCTION plugin_finance_journal_person_reject_unsupported() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE n jsonb; t uuid; b uuid; y uuid; source uuid; target uuid; r record;
BEGIN
 n:=CASE WHEN TG_OP='DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;t:=(n->>'tenant_id')::uuid;b:=(n->>'book_id')::uuid;
 IF TG_TABLE_NAME='plugin_finance_close_plan' THEN
  source:=(n->>'source_year_id')::uuid;target:=(n->>'target_year_id')::uuid;
  FOR r IN SELECT m.year_id FROM plugin_finance_journal_person_mode m JOIN plugin_finance_year a ON(a.tenant_id,a.book_id,a.id)=(m.tenant_id,m.book_id,m.year_id) WHERE m.tenant_id=t AND m.book_id=b AND m.year_id IN(source,target) ORDER BY a.year,m.year_id LOOP
   IF plugin_finance_journal_person_lock(t,b,r.year_id)='JOURNAL_PERSON_SOD' THEN RAISE EXCEPTION 'JOURNAL_PERSON_UNSUPPORTED_PATH'; END IF;
  END LOOP;
 ELSE
  y:=(n->>'year_id')::uuid;
  IF plugin_finance_journal_person_lock(t,b,y)='JOURNAL_PERSON_SOD' THEN RAISE EXCEPTION 'JOURNAL_PERSON_UNSUPPORTED_PATH'; END IF;
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END $$;
CREATE TRIGGER finance_journal_person_source_event BEFORE INSERT OR UPDATE OR DELETE ON plugin_finance_source_event FOR EACH ROW EXECUTE FUNCTION plugin_finance_journal_person_reject_unsupported();
CREATE TRIGGER finance_journal_person_source_effect BEFORE INSERT OR UPDATE OR DELETE ON plugin_finance_business_effect FOR EACH ROW EXECUTE FUNCTION plugin_finance_journal_person_reject_unsupported();
CREATE TRIGGER finance_journal_person_opening BEFORE INSERT OR UPDATE OR DELETE ON plugin_finance_opening FOR EACH ROW EXECUTE FUNCTION plugin_finance_journal_person_reject_unsupported();
CREATE TRIGGER finance_journal_person_year_close BEFORE INSERT OR UPDATE OR DELETE ON plugin_finance_close_plan FOR EACH ROW EXECUTE FUNCTION plugin_finance_journal_person_reject_unsupported();


CREATE FUNCTION plugin_finance_journal_person_immutable() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'JOURNAL_PERSON_IMMUTABLE_HISTORY'; END $$;
CREATE TRIGGER finance_person_content_immutable BEFORE UPDATE OR DELETE ON plugin_finance_journal_person_content FOR EACH ROW EXECUTE FUNCTION plugin_finance_journal_person_immutable();
CREATE TRIGGER finance_person_approval_immutable BEFORE UPDATE OR DELETE ON plugin_finance_journal_person_approval FOR EACH ROW EXECUTE FUNCTION plugin_finance_journal_person_immutable();
CREATE TRIGGER finance_person_transition_immutable BEFORE UPDATE OR DELETE ON plugin_finance_journal_person_transition FOR EACH ROW EXECUTE FUNCTION plugin_finance_journal_person_immutable();
CREATE TRIGGER finance_person_post_immutable BEFORE UPDATE OR DELETE ON plugin_finance_journal_person_post FOR EACH ROW EXECUTE FUNCTION plugin_finance_journal_person_immutable();
CREATE TRIGGER finance_person_termination_immutable BEFORE UPDATE OR DELETE ON plugin_finance_journal_person_termination FOR EACH ROW EXECUTE FUNCTION plugin_finance_journal_person_immutable();
CREATE TRIGGER finance_person_request_immutable BEFORE UPDATE OR DELETE ON plugin_finance_journal_person_request FOR EACH ROW EXECUTE FUNCTION plugin_finance_journal_person_immutable();
CREATE TRIGGER finance_person_audit_immutable BEFORE UPDATE OR DELETE ON plugin_finance_journal_person_audit FOR EACH ROW EXECUTE FUNCTION plugin_finance_journal_person_immutable();
CREATE FUNCTION plugin_finance_journal_person_role(t uuid,b uuid,y uuid,a uuid,roles text[]) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM plugin_finance_member m WHERE m.tenant_id=t AND m.book_id=b AND m.user_id=a AND m.enabled AND m.role=ANY(roles) AND(m.year_scope='ALL' OR EXISTS(SELECT 1 FROM plugin_finance_member_year g WHERE(g.tenant_id,g.book_id,g.user_id,g.year_id)=(t,b,a,y))))
$$;
CREATE FUNCTION plugin_finance_journal_person_transition_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE h plugin_finance_journal;c plugin_finance_journal_person_content;old_event plugin_finance_journal_person_transition;cur plugin_finance_journal_person_current;ap plugin_finance_journal_person_approval;a uuid;p uuid;
BEGIN
 IF plugin_finance_journal_person_lock(NEW.tenant_id,NEW.book_id,NEW.year_id)<>'JOURNAL_PERSON_SOD' THEN RAISE EXCEPTION 'JOURNAL_PERSON_MODE_REQUIRED'; END IF;
 a:=(NEW.actor->>'accountId')::uuid;p:=(NEW.actor->>'personId')::uuid;
 IF NEW.actor IS DISTINCT FROM plugin_finance_journal_person_identity(NEW.tenant_id,a,true) OR NEW.created_at<>CURRENT_TIMESTAMP THEN RAISE EXCEPTION 'JOURNAL_PERSON_IDENTITY_UNAVAILABLE'; END IF;
 SELECT * INTO h FROM plugin_finance_journal WHERE(tenant_id,book_id,year_id,id)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.journal_id);
 SELECT * INTO c FROM plugin_finance_journal_person_content WHERE(tenant_id,book_id,year_id,journal_id,content_version)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.journal_id,NEW.content_version);
 IF NOT FOUND OR h.version IS DISTINCT FROM NEW.journal_version OR h.status IS DISTINCT FROM NEW.to_state OR c.digest IS DISTINCT FROM NEW.content_digest OR c.snapshot IS DISTINCT FROM plugin_finance_journal_person_snapshot(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.journal_id) OR c.maker_account IS DISTINCT FROM h.created_by THEN RAISE EXCEPTION 'JOURNAL_PERSON_CONTENT_STALE'; END IF;
 SELECT * INTO cur FROM plugin_finance_journal_person_current WHERE(tenant_id,book_id,year_id,journal_id)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.journal_id);
 IF NEW.journal_version=1 THEN
  IF FOUND OR NEW.action<>'CREATE' OR NEW.from_state IS NOT NULL OR NEW.to_state<>'DRAFT' OR c.content_version<>1 OR c.created_journal_version<>1 THEN RAISE EXCEPTION 'JOURNAL_PERSON_INITIAL_TRANSITION'; END IF;
 ELSE
  IF NOT FOUND OR cur.journal_version<>NEW.journal_version-1 THEN RAISE EXCEPTION 'JOURNAL_PERSON_TRANSITION_CHAIN'; END IF;
  SELECT * INTO old_event FROM plugin_finance_journal_person_transition WHERE(tenant_id,book_id,year_id,journal_id,journal_version)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.journal_id,NEW.previous_version);
  IF NOT FOUND OR old_event.to_state IS DISTINCT FROM NEW.from_state THEN RAISE EXCEPTION 'JOURNAL_PERSON_TRANSITION_CHAIN'; END IF;
  IF NEW.action IN('EDIT','DOCUMENTS') THEN
   IF NEW.from_state<>'DRAFT' OR NEW.to_state<>'DRAFT' OR c.content_version NOT IN(cur.content_version,cur.content_version+1) OR(c.content_version=cur.content_version+1 AND c.digest=cur.content_digest) THEN RAISE EXCEPTION 'JOURNAL_PERSON_EDIT_TRANSITION'; END IF;
  ELSIF c.content_version<>cur.content_version THEN RAISE EXCEPTION 'JOURNAL_PERSON_CONTENT_VERSION'; END IF;
 END IF;
 IF NEW.action IN('CREATE','EDIT','DOCUMENTS','SUBMIT','WITHDRAW','CANCEL') THEN
  IF a<>h.created_by OR NOT plugin_finance_journal_person_role(NEW.tenant_id,NEW.book_id,NEW.year_id,a,ARRAY['OWNER','MAKER']) THEN RAISE EXCEPTION 'JOURNAL_PERSON_CREATOR_ONLY'; END IF;
  IF NEW.action='SUBMIT' AND(NEW.from_state<>'DRAFT' OR NEW.to_state<>'SUBMITTED') OR NEW.action='WITHDRAW' AND(NEW.from_state<>'SUBMITTED' OR NEW.to_state<>'DRAFT') OR NEW.action='CANCEL' AND(NEW.from_state<>'DRAFT' OR NEW.to_state<>'CANCELLED') THEN RAISE EXCEPTION 'JOURNAL_PERSON_TRANSITION_STATE'; END IF;
 ELSIF NEW.action IN('APPROVE','REJECT','TERMINATE') THEN
  IF a=h.created_by OR p=c.maker_person OR p=c.editor_person OR NOT plugin_finance_journal_person_role(NEW.tenant_id,NEW.book_id,NEW.year_id,a,ARRAY['OWNER','REVIEWER']) THEN RAISE EXCEPTION 'JOURNAL_PERSON_SELF_APPROVAL'; END IF;
  IF NEW.action='APPROVE' THEN
   SELECT * INTO ap FROM plugin_finance_journal_person_approval WHERE(tenant_id,book_id,year_id,journal_id,id)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.journal_id,NEW.approval_id);
   IF NOT FOUND OR NEW.from_state<>'SUBMITTED' OR NEW.to_state<>'APPROVED' OR ap.approved_journal_version<>NEW.journal_version OR ap.approver<>NEW.actor OR ap.content_version<>NEW.content_version OR ap.content_digest<>NEW.content_digest OR ap.expires_at<=clock_timestamp() OR h.approved_by IS DISTINCT FROM a OR h.approved_version IS DISTINCT FROM NEW.journal_version THEN RAISE EXCEPTION 'JOURNAL_PERSON_APPROVAL_STALE'; END IF;
  ELSIF NEW.action='REJECT' THEN
   IF NEW.from_state NOT IN('SUBMITTED','APPROVED') OR NEW.to_state<>'DRAFT' OR length(COALESCE(NEW.reason,'')) NOT BETWEEN 1 AND 1000 OR h.approved_by IS NOT NULL OR h.approved_version IS NOT NULL THEN RAISE EXCEPTION 'JOURNAL_PERSON_REJECT_STATE'; END IF;
  ELSE
   IF NEW.from_state<>'DRAFT' OR NEW.to_state<>'CANCELLED' OR length(COALESCE(NEW.reason,'')) NOT BETWEEN 1 AND 1000 THEN RAISE EXCEPTION 'JOURNAL_PERSON_TERMINATE_STATE'; END IF;
  END IF;
 ELSE
  IF NEW.action<>'POST' OR NEW.from_state<>'APPROVED' OR NEW.to_state<>'POSTED' OR NOT plugin_finance_journal_person_role(NEW.tenant_id,NEW.book_id,NEW.year_id,a,ARRAY['OWNER','POSTER']) THEN RAISE EXCEPTION 'JOURNAL_PERSON_POST_STATE'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER finance_person_transition_guard BEFORE INSERT ON plugin_finance_journal_person_transition FOR EACH ROW EXECUTE FUNCTION plugin_finance_journal_person_transition_guard();
CREATE FUNCTION plugin_finance_journal_person_seal(t uuid,b uuid,y uuid,j uuid,who jsonb,command text,approval uuid DEFAULT NULL,why text DEFAULT NULL) RETURNS void LANGUAGE plpgsql AS $$
DECLARE h plugin_finance_journal;cur plugin_finance_journal_person_current;c plugin_finance_journal_person_content;snap jsonb;dig text;cv bigint;maker jsonb;old_state text;post plugin_finance_posting;active_approval uuid;
BEGIN
 IF plugin_finance_journal_person_lock(t,b,y)<>'JOURNAL_PERSON_SOD' THEN RETURN; END IF;
 SELECT * INTO h FROM plugin_finance_journal WHERE(tenant_id,book_id,year_id,id)=(t,b,y,j) FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'JOURNAL_PERSON_CONTENT_STALE'; END IF;
 snap:=plugin_finance_journal_person_snapshot(t,b,y,j);dig:=encode(sha256(convert_to(snap::text,'UTF8')),'hex');
 SELECT * INTO cur FROM plugin_finance_journal_person_current WHERE(tenant_id,book_id,year_id,journal_id)=(t,b,y,j) FOR UPDATE;
 IF NOT FOUND THEN cv:=1;maker:=who;old_state:=NULL;
 ELSE
  IF cur.journal_version=h.version THEN RAISE EXCEPTION 'JOURNAL_PERSON_DUPLICATE_TRANSITION'; END IF;
  SELECT * INTO c FROM plugin_finance_journal_person_content WHERE(tenant_id,book_id,year_id,journal_id,content_version)=(t,b,y,j,cur.content_version);
  maker:=c.maker;cv:=cur.content_version;
  SELECT to_state INTO old_state FROM plugin_finance_journal_person_transition WHERE(tenant_id,book_id,year_id,journal_id,journal_version)=(t,b,y,j,cur.journal_version);
  IF dig<>cur.content_digest THEN
   IF command NOT IN('EDIT','DOCUMENTS') THEN RAISE EXCEPTION 'JOURNAL_PERSON_CONTENT_STALE'; END IF;
   cv:=cv+1;
  END IF;
 END IF;
 IF cur.content_version IS NULL OR cv<>cur.content_version THEN
  INSERT INTO plugin_finance_journal_person_content(tenant_id,book_id,year_id,journal_id,content_version,format,snapshot,canonical_text,digest,maker,editor,created_journal_version) VALUES(t,b,y,j,cv,'JOURNAL_PERSON_CONTENT_PG_V1',snap,snap::text,dig,maker,who,h.version);
 END IF;
 INSERT INTO plugin_finance_journal_person_transition(tenant_id,book_id,year_id,journal_id,journal_version,previous_version,action,from_state,to_state,content_version,content_digest,actor,approval_id,reason) VALUES(t,b,y,j,h.version,CASE WHEN h.version=1 THEN NULL ELSE h.version-1 END,command,old_state,h.status,cv,dig,who,approval,why);
 active_approval:=CASE WHEN command='APPROVE' THEN approval WHEN command='POST' THEN cur.approval_id ELSE NULL END;
 INSERT INTO plugin_finance_journal_person_current(tenant_id,book_id,year_id,journal_id,journal_version,content_version,content_digest,approval_id) VALUES(t,b,y,j,h.version,cv,dig,active_approval) ON CONFLICT(tenant_id,book_id,year_id,journal_id) DO UPDATE SET journal_version=EXCLUDED.journal_version,content_version=EXCLUDED.content_version,content_digest=EXCLUDED.content_digest,approval_id=EXCLUDED.approval_id;
 IF command='POST' THEN
  SELECT * INTO post FROM plugin_finance_posting WHERE(tenant_id,book_id,year_id,journal_id)=(t,b,y,j);
  INSERT INTO plugin_finance_journal_person_post(tenant_id,book_id,year_id,journal_id,id,approval_id,content_version,content_digest,posting_seq,posting_digest,posted_journal_version,poster) VALUES(t,b,y,j,gen_random_uuid(),active_approval,cv,dig,post.posting_seq,post.digest,h.version,who);
 ELSIF command='TERMINATE' THEN
  INSERT INTO plugin_finance_journal_person_termination(tenant_id,book_id,year_id,journal_id,id,journal_version,content_version,content_digest,actor,reason) VALUES(t,b,y,j,gen_random_uuid(),h.version,cv,dig,who,why);
 END IF;
 INSERT INTO plugin_finance_journal_person_audit(tenant_id,book_id,year_id,id,actor_id,action,target_id,payload) VALUES(t,b,y,gen_random_uuid(),(who->>'accountId')::uuid,'JOURNAL_PERSON_'||command,j,jsonb_build_object('journalVersion',h.version::text,'contentVersion',cv::text,'contentDigest',dig,'actor',who,'reason',why));
END $$;

ALTER TABLE plugin_finance_journal_person_activation ADD COLUMN requested_generation bigint NOT NULL DEFAULT 0 CHECK(requested_generation>=0);
CREATE FUNCTION plugin_finance_journal_person_activation_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE m plugin_finance_journal_person_mode;a uuid;p uuid;expected text;
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'JOURNAL_PERSON_IMMUTABLE_HISTORY'; END IF;
 IF NEW.status='APPROVED' AND current_setting('transaction_isolation')<>'read committed' THEN RAISE EXCEPTION 'JOURNAL_PERSON_ACTIVATION_REQUIRES_READ_COMMITTED' USING ERRCODE='40001'; END IF;
 SELECT * INTO m FROM plugin_finance_journal_person_mode WHERE(tenant_id,book_id,year_id)=(NEW.tenant_id,NEW.book_id,NEW.year_id) FOR UPDATE;
 IF NOT FOUND OR m.epoch IS DISTINCT FROM NEW.epoch THEN RAISE EXCEPTION 'JOURNAL_PERSON_MODE_STALE'; END IF;
 IF TG_OP='INSERT' THEN
  IF m.mode<>'ACCOUNT_ONLY' OR NEW.status<>'PENDING' OR NEW.version<>1 OR NEW.created_at<>CURRENT_TIMESTAMP OR NEW.expires_at<>NEW.created_at+interval '7 days' OR NEW.requested_generation<>m.generation OR NOT plugin_finance_journal_person_empty(NEW.tenant_id,NEW.book_id,NEW.year_id) THEN RAISE EXCEPTION 'JOURNAL_PERSON_YEAR_NOT_EMPTY'; END IF;
  a:=(NEW.requester->>'accountId')::uuid;
  IF NEW.requester IS DISTINCT FROM plugin_finance_journal_person_identity(NEW.tenant_id,a,true) OR NOT plugin_finance_journal_person_role(NEW.tenant_id,NEW.book_id,NEW.year_id,a,ARRAY['OWNER']) THEN RAISE EXCEPTION 'JOURNAL_PERSON_IDENTITY_UNAVAILABLE'; END IF;
  NEW.request_digest:=encode(sha256(convert_to(jsonb_build_object('tenantId',NEW.tenant_id,'bookId',NEW.book_id,'yearId',NEW.year_id,'id',NEW.id,'epoch',NEW.epoch,'requestedGeneration',NEW.requested_generation::text,'requester',NEW.requester,'reason',NEW.reason,'createdAt',NEW.created_at,'expiresAt',NEW.expires_at)::text,'UTF8')),'hex');
 ELSE
  IF OLD.status<>'PENDING' OR NEW.status='PENDING' OR NEW.version<>OLD.version+1 OR NEW.decided_at IS DISTINCT FROM CURRENT_TIMESTAMP OR ROW(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.id,NEW.epoch,NEW.requester,NEW.requester_username,NEW.reason,NEW.request_digest,NEW.created_at,NEW.expires_at,NEW.requested_generation) IS DISTINCT FROM ROW(OLD.tenant_id,OLD.book_id,OLD.year_id,OLD.id,OLD.epoch,OLD.requester,OLD.requester_username,OLD.reason,OLD.request_digest,OLD.created_at,OLD.expires_at,OLD.requested_generation) THEN RAISE EXCEPTION 'JOURNAL_PERSON_ACTIVATION_TRANSITION'; END IF;
  a:=(NEW.decider->>'accountId')::uuid;p:=(NEW.decider->>'personId')::uuid;
  IF NEW.decider IS DISTINCT FROM plugin_finance_journal_person_identity(NEW.tenant_id,a,true) THEN RAISE EXCEPTION 'JOURNAL_PERSON_IDENTITY_UNAVAILABLE'; END IF;
  IF NEW.status='CANCELLED' THEN
   IF a<>(NEW.requester->>'accountId')::uuid OR NOT plugin_finance_journal_person_role(NEW.tenant_id,NEW.book_id,NEW.year_id,a,ARRAY['OWNER']) THEN RAISE EXCEPTION 'JOURNAL_PERSON_CANCEL_CREATOR_ONLY'; END IF;
  ELSE
   IF p=(NEW.requester->>'personId')::uuid OR NOT plugin_finance_journal_person_role(NEW.tenant_id,NEW.book_id,NEW.year_id,a,ARRAY['OWNER','REVIEWER']) THEN RAISE EXCEPTION 'JOURNAL_PERSON_SELF_APPROVAL'; END IF;
  END IF;
  IF NEW.status='APPROVED' THEN
   IF m.mode<>'ACCOUNT_ONLY' OR NEW.expires_at<=clock_timestamp() OR NOT plugin_finance_journal_person_empty(NEW.tenant_id,NEW.book_id,NEW.year_id) OR NEW.requester IS DISTINCT FROM plugin_finance_journal_person_identity(NEW.tenant_id,(NEW.requester->>'accountId')::uuid,true) OR NOT plugin_finance_journal_person_role(NEW.tenant_id,NEW.book_id,NEW.year_id,(NEW.requester->>'accountId')::uuid,ARRAY['OWNER']) THEN RAISE EXCEPTION 'JOURNAL_PERSON_ACTIVATION_STALE'; END IF;
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER finance_person_activation_guard BEFORE INSERT OR UPDATE OR DELETE ON plugin_finance_journal_person_activation FOR EACH ROW EXECUTE FUNCTION plugin_finance_journal_person_activation_guard();
CREATE FUNCTION plugin_finance_journal_person_mode_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r plugin_finance_journal_person_activation;
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'JOURNAL_PERSON_MODE_IMMUTABLE'; END IF;
 IF ROW(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.epoch) IS DISTINCT FROM ROW(OLD.tenant_id,OLD.book_id,OLD.year_id,OLD.epoch) OR NEW.generation<>OLD.generation+1 THEN RAISE EXCEPTION 'JOURNAL_PERSON_MODE_IMMUTABLE'; END IF;
 IF NEW.mode IS DISTINCT FROM OLD.mode THEN
  IF current_setting('transaction_isolation')<>'read committed' THEN RAISE EXCEPTION 'JOURNAL_PERSON_ACTIVATION_REQUIRES_READ_COMMITTED' USING ERRCODE='40001'; END IF;
  SELECT * INTO r FROM plugin_finance_journal_person_activation WHERE(tenant_id,book_id,year_id,id)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.activation_id);
  IF NOT FOUND OR OLD.mode<>'ACCOUNT_ONLY' OR NEW.mode<>'JOURNAL_PERSON_SOD' OR r.status<>'APPROVED' OR r.epoch<>NEW.epoch OR NEW.effective_at IS DISTINCT FROM r.decided_at OR NOT plugin_finance_journal_person_empty(NEW.tenant_id,NEW.book_id,NEW.year_id) THEN RAISE EXCEPTION 'JOURNAL_PERSON_ACTIVATION_REQUIRED'; END IF;
 ELSIF ROW(NEW.activation_id,NEW.effective_at) IS DISTINCT FROM ROW(OLD.activation_id,OLD.effective_at) THEN RAISE EXCEPTION 'JOURNAL_PERSON_MODE_IMMUTABLE'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER finance_person_mode_guard BEFORE UPDATE OR DELETE ON plugin_finance_journal_person_mode FOR EACH ROW EXECUTE FUNCTION plugin_finance_journal_person_mode_guard();
CREATE FUNCTION plugin_finance_journal_person_activation_effect() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.status='APPROVED' THEN
  UPDATE plugin_finance_journal_person_mode SET mode='JOURNAL_PERSON_SOD',activation_id=NEW.id,effective_at=NEW.decided_at,generation=generation+1 WHERE(tenant_id,book_id,year_id)=(NEW.tenant_id,NEW.book_id,NEW.year_id);
 ELSE UPDATE plugin_finance_journal_person_mode SET generation=generation+1 WHERE(tenant_id,book_id,year_id)=(NEW.tenant_id,NEW.book_id,NEW.year_id); END IF;
 INSERT INTO plugin_finance_journal_person_audit(tenant_id,book_id,year_id,id,actor_id,action,target_id,payload) VALUES(NEW.tenant_id,NEW.book_id,NEW.year_id,gen_random_uuid(),CASE WHEN TG_OP='INSERT' THEN(NEW.requester->>'accountId')::uuid ELSE(NEW.decider->>'accountId')::uuid END,'JOURNAL_PERSON_ACTIVATION_'||NEW.status,NEW.id,jsonb_build_object('before',CASE WHEN TG_OP='UPDATE' THEN to_jsonb(OLD) ELSE NULL END,'after',to_jsonb(NEW)));
 RETURN NEW;
END $$;
CREATE TRIGGER finance_person_activation_effect AFTER INSERT OR UPDATE ON plugin_finance_journal_person_activation FOR EACH ROW EXECUTE FUNCTION plugin_finance_journal_person_activation_effect();
CREATE FUNCTION plugin_finance_journal_person_activation_final() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE m plugin_finance_journal_person_mode;r plugin_finance_journal_person_activation;
BEGIN
 SELECT * INTO m FROM plugin_finance_journal_person_mode WHERE(tenant_id,book_id,year_id)=(NEW.tenant_id,NEW.book_id,NEW.year_id);
 IF m.mode='JOURNAL_PERSON_SOD' THEN
  SELECT * INTO r FROM plugin_finance_journal_person_activation WHERE(tenant_id,book_id,year_id,id)=(m.tenant_id,m.book_id,m.year_id,m.activation_id);
  IF NOT FOUND OR r.status<>'APPROVED' OR r.epoch<>m.epoch OR r.decided_at<>m.effective_at THEN RAISE EXCEPTION 'JOURNAL_PERSON_MODE_WITHOUT_DECISION'; END IF;
 END IF;
 IF TG_TABLE_NAME='plugin_finance_journal_person_activation' THEN
  SELECT * INTO r FROM plugin_finance_journal_person_activation WHERE(tenant_id,book_id,year_id,id)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.id);
  IF r.status='APPROVED' AND(m.mode<>'JOURNAL_PERSON_SOD' OR m.activation_id IS DISTINCT FROM r.id) THEN RAISE EXCEPTION 'JOURNAL_PERSON_DECISION_WITHOUT_MODE'; END IF;
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER finance_person_mode_final AFTER INSERT OR UPDATE ON plugin_finance_journal_person_mode DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION plugin_finance_journal_person_activation_final();
CREATE CONSTRAINT TRIGGER finance_person_activation_final AFTER INSERT OR UPDATE ON plugin_finance_journal_person_activation DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION plugin_finance_journal_person_activation_final();

-- All ordinary mutations share the pre-existing mode row. They do not advance its generation.
-- API lock order is permission/realm -> advisory -> book -> year/mode -> period/object.
-- Arbitrary direct-SQL row order can deadlock; PostgreSQL must roll back that whole transaction.
CREATE FUNCTION plugin_finance_journal_person_open(t uuid,b uuid,y uuid,p uuid,d date) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
 PERFORM 1 FROM plugin_finance_year WHERE(tenant_id,book_id,id)=(t,b,y) AND status='OPEN' AND precision_profile='V4' AND NOT precision_review_required AND currency_minor_unit=2 AND year=extract(year FROM d)::integer FOR SHARE;
 IF NOT FOUND THEN RAISE EXCEPTION 'JOURNAL_PERSON_YEAR_UNAVAILABLE'; END IF;
 PERFORM 1 FROM plugin_finance_period WHERE(tenant_id,book_id,year_id,id)=(t,b,y,p) AND month=extract(month FROM d)::integer AND status='OPEN' FOR SHARE;
 IF NOT FOUND THEN RAISE EXCEPTION 'JOURNAL_PERSON_PERIOD_MISMATCH_OR_CLOSED'; END IF;
END $$;
CREATE FUNCTION plugin_finance_journal_person_raw_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r jsonb;h plugin_finance_journal; old_mode text; new_mode text;
BEGIN
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
-- Run before the historical DELETE guard, whose RETURN NEW on DELETE suppresses the operation.
CREATE TRIGGER a_finance_person_journal_raw BEFORE INSERT OR UPDATE OR DELETE ON plugin_finance_journal FOR EACH ROW EXECUTE FUNCTION plugin_finance_journal_person_raw_guard();
CREATE TRIGGER a_finance_person_line_raw BEFORE INSERT OR UPDATE OR DELETE ON plugin_finance_line FOR EACH ROW EXECUTE FUNCTION plugin_finance_journal_person_raw_guard();
CREATE TRIGGER a_finance_person_document_raw BEFORE INSERT OR UPDATE OR DELETE ON plugin_finance_document FOR EACH ROW EXECUTE FUNCTION plugin_finance_journal_person_raw_guard();

CREATE FUNCTION plugin_finance_journal_person_receipt_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE h plugin_finance_journal;c plugin_finance_journal_person_content;cur plugin_finance_journal_person_current;e plugin_finance_journal_person_transition;ap plugin_finance_journal_person_approval;po plugin_finance_posting;who jsonb;
BEGIN
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
CREATE TRIGGER finance_person_content_insert BEFORE INSERT ON plugin_finance_journal_person_content FOR EACH ROW EXECUTE FUNCTION plugin_finance_journal_person_receipt_guard();
CREATE TRIGGER finance_person_approval_insert BEFORE INSERT ON plugin_finance_journal_person_approval FOR EACH ROW EXECUTE FUNCTION plugin_finance_journal_person_receipt_guard();
CREATE TRIGGER finance_person_current_guard BEFORE INSERT OR UPDATE OR DELETE ON plugin_finance_journal_person_current FOR EACH ROW EXECUTE FUNCTION plugin_finance_journal_person_receipt_guard();
CREATE TRIGGER finance_person_post_insert BEFORE INSERT ON plugin_finance_journal_person_post FOR EACH ROW EXECUTE FUNCTION plugin_finance_journal_person_receipt_guard();
CREATE TRIGGER finance_person_termination_insert BEFORE INSERT ON plugin_finance_journal_person_termination FOR EACH ROW EXECUTE FUNCTION plugin_finance_journal_person_receipt_guard();


-- Point checks of the final committed graph, from both raw facts and every consumer side.
-- Historical receipts bind their original transition, not an eternally APPROVED current header.
CREATE FUNCTION plugin_finance_journal_person_assert(t uuid,b uuid,y uuid,j uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE h plugin_finance_journal;cur plugin_finance_journal_person_current;c plugin_finance_journal_person_content;e plugin_finance_journal_person_transition;ap plugin_finance_journal_person_approval;pr plugin_finance_journal_person_post;po plugin_finance_posting;tr plugin_finance_journal_person_termination;
BEGIN
 IF plugin_finance_journal_person_lock(t,b,y)<>'JOURNAL_PERSON_SOD' THEN RETURN; END IF;
 SELECT * INTO h FROM plugin_finance_journal WHERE(tenant_id,book_id,year_id,id)=(t,b,y,j);
 IF NOT FOUND THEN RAISE EXCEPTION 'JOURNAL_PERSON_RAW_WITHOUT_HEADER'; END IF;
 SELECT * INTO cur FROM plugin_finance_journal_person_current WHERE(tenant_id,book_id,year_id,journal_id)=(t,b,y,j);
 IF NOT FOUND OR cur.journal_version<>h.version THEN RAISE EXCEPTION 'JOURNAL_PERSON_RAW_WITHOUT_CURRENT'; END IF;
 SELECT * INTO c FROM plugin_finance_journal_person_content WHERE(tenant_id,book_id,year_id,journal_id,content_version)=(t,b,y,j,cur.content_version);
 IF NOT FOUND OR c.digest<>cur.content_digest OR c.maker_account<>h.created_by OR c.snapshot IS DISTINCT FROM plugin_finance_journal_person_snapshot(t,b,y,j) THEN RAISE EXCEPTION 'JOURNAL_PERSON_CURRENT_CONTENT_MISMATCH'; END IF;
 SELECT * INTO e FROM plugin_finance_journal_person_transition WHERE(tenant_id,book_id,year_id,journal_id,journal_version)=(t,b,y,j,h.version);
 IF NOT FOUND OR e.to_state<>h.status OR e.content_version<>c.content_version OR e.content_digest<>c.digest THEN RAISE EXCEPTION 'JOURNAL_PERSON_CURRENT_TRANSITION_MISMATCH'; END IF;
 IF h.status IN('SUBMITTED','APPROVED','POSTED') THEN
  IF (SELECT sum(debit)<>sum(credit) FROM plugin_finance_line WHERE(tenant_id,book_id,year_id,journal_id)=(t,b,y,j)) THEN RAISE EXCEPTION 'JOURNAL_PERSON_UNBALANCED'; END IF;
 END IF;
 IF h.status IN('APPROVED','POSTED') THEN
  SELECT * INTO ap FROM plugin_finance_journal_person_approval WHERE(tenant_id,book_id,year_id,journal_id,id)=(t,b,y,j,cur.approval_id);
  IF NOT FOUND OR ap.content_version<>c.content_version OR ap.content_digest<>c.digest OR h.approved_by IS DISTINCT FROM ap.approver_account OR h.approved_version IS DISTINCT FROM ap.approved_journal_version OR ap.approver_person IN(c.maker_person,c.editor_person) THEN RAISE EXCEPTION 'JOURNAL_PERSON_CURRENT_APPROVAL_MISMATCH'; END IF;
  IF h.status='APPROVED' AND(e.action<>'APPROVE' OR e.approval_id IS DISTINCT FROM ap.id OR h.version<>ap.approved_journal_version) THEN RAISE EXCEPTION 'JOURNAL_PERSON_APPROVED_WITHOUT_RECEIPT'; END IF;
 ELSE
  IF cur.approval_id IS NOT NULL OR h.approved_by IS NOT NULL OR h.approved_version IS NOT NULL THEN RAISE EXCEPTION 'JOURNAL_PERSON_STALE_APPROVAL_POINTER'; END IF;
 END IF;
 SELECT * INTO pr FROM plugin_finance_journal_person_post WHERE(tenant_id,book_id,year_id,journal_id)=(t,b,y,j);
 IF h.status='POSTED' THEN
  IF NOT FOUND OR e.action<>'POST' OR pr.posted_journal_version<>h.version OR pr.approval_id IS DISTINCT FROM ap.id OR pr.content_version<>c.content_version OR pr.content_digest<>c.digest OR pr.poster IS DISTINCT FROM e.actor THEN RAISE EXCEPTION 'JOURNAL_PERSON_POSTED_WITHOUT_RECEIPT'; END IF;
  SELECT * INTO po FROM plugin_finance_posting WHERE(tenant_id,book_id,year_id,journal_id)=(t,b,y,j);
  IF NOT FOUND OR po.posting_seq IS DISTINCT FROM pr.posting_seq OR po.digest IS DISTINCT FROM pr.posting_digest OR po.posted_by<>pr.poster_account OR po.journal_version<>ap.approved_journal_version OR h.version<>po.journal_version+1 THEN RAISE EXCEPTION 'JOURNAL_PERSON_POSTING_FACT_MISMATCH'; END IF;
 ELSE
  IF FOUND OR EXISTS(SELECT 1 FROM plugin_finance_posting WHERE(tenant_id,book_id,year_id,journal_id)=(t,b,y,j)) THEN RAISE EXCEPTION 'JOURNAL_PERSON_NONPOSTED_HAS_POSTING'; END IF;
 END IF;
 SELECT * INTO tr FROM plugin_finance_journal_person_termination WHERE(tenant_id,book_id,year_id,journal_id)=(t,b,y,j);
 IF e.action='TERMINATE' THEN
  IF NOT FOUND OR h.status<>'CANCELLED' OR tr.journal_version<>h.version OR tr.content_version<>c.content_version OR tr.content_digest<>c.digest OR tr.actor IS DISTINCT FROM e.actor OR tr.reason IS DISTINCT FROM e.reason THEN RAISE EXCEPTION 'JOURNAL_PERSON_TERMINATION_MISMATCH'; END IF;
 ELSIF FOUND THEN RAISE EXCEPTION 'JOURNAL_PERSON_TERMINATION_WITHOUT_EFFECT'; END IF;
END $$;
CREATE FUNCTION plugin_finance_journal_person_final() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r jsonb;t uuid;b uuid;y uuid;j uuid;e plugin_finance_journal_person_transition;
BEGIN
 IF TG_OP='DELETE' THEN r:=to_jsonb(OLD); ELSE r:=to_jsonb(NEW); END IF;
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
CREATE CONSTRAINT TRIGGER finance_person_raw_header_final AFTER INSERT OR UPDATE OR DELETE ON plugin_finance_journal DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION plugin_finance_journal_person_final();
CREATE CONSTRAINT TRIGGER finance_person_raw_line_final AFTER INSERT OR UPDATE OR DELETE ON plugin_finance_line DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION plugin_finance_journal_person_final();
CREATE CONSTRAINT TRIGGER finance_person_raw_document_final AFTER INSERT OR UPDATE OR DELETE ON plugin_finance_document DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION plugin_finance_journal_person_final();
CREATE CONSTRAINT TRIGGER finance_person_raw_posting_final AFTER INSERT OR UPDATE OR DELETE ON plugin_finance_posting DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION plugin_finance_journal_person_final();
CREATE CONSTRAINT TRIGGER finance_person_content_final AFTER INSERT ON plugin_finance_journal_person_content DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION plugin_finance_journal_person_final();
CREATE CONSTRAINT TRIGGER finance_person_approval_final AFTER INSERT ON plugin_finance_journal_person_approval DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION plugin_finance_journal_person_final();
CREATE CONSTRAINT TRIGGER finance_person_transition_final AFTER INSERT ON plugin_finance_journal_person_transition DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION plugin_finance_journal_person_final();
CREATE CONSTRAINT TRIGGER finance_person_current_final AFTER INSERT OR UPDATE OR DELETE ON plugin_finance_journal_person_current DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION plugin_finance_journal_person_final();
CREATE CONSTRAINT TRIGGER finance_person_post_final AFTER INSERT ON plugin_finance_journal_person_post DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION plugin_finance_journal_person_final();
CREATE CONSTRAINT TRIGGER finance_person_termination_final AFTER INSERT ON plugin_finance_journal_person_termination DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION plugin_finance_journal_person_final();


CREATE FUNCTION plugin_finance_journal_person_posting_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE h plugin_finance_journal;cur plugin_finance_journal_person_current;ap plugin_finance_journal_person_approval;
BEGIN
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
CREATE TRIGGER a_finance_person_posting_insert BEFORE INSERT ON plugin_finance_posting FOR EACH ROW EXECUTE FUNCTION plugin_finance_journal_person_posting_guard();

CREATE FUNCTION plugin_finance_journal_person_scope_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE t uuid;b uuid;y uuid;r record;m text;oldr jsonb;newr jsonb;
BEGIN
 IF TG_OP='DELETE' THEN oldr:=to_jsonb(OLD);newr:=oldr; ELSE newr:=to_jsonb(NEW);IF TG_OP='UPDATE' THEN oldr:=to_jsonb(OLD);ELSE oldr:=newr;END IF;END IF;
 t:=(oldr->>'tenant_id')::uuid;
 IF TG_TABLE_NAME='plugin_finance_book' THEN
  b:=(oldr->>'id')::uuid;
  FOR r IN SELECT q.year_id FROM plugin_finance_journal_person_mode q JOIN plugin_finance_year a ON(a.tenant_id,a.book_id,a.id)=(q.tenant_id,q.book_id,q.year_id) WHERE q.tenant_id=t AND q.book_id=b ORDER BY a.year,q.year_id LOOP
   m:=plugin_finance_journal_person_lock(t,b,r.year_id);
   IF m='JOURNAL_PERSON_SOD' AND(TG_OP='DELETE' OR(oldr-ARRAY['name','version']) IS DISTINCT FROM(newr-ARRAY['name','version'])) THEN RAISE EXCEPTION 'JOURNAL_PERSON_BOOK_LINEAGE_IMMUTABLE'; END IF;
  END LOOP;
 ELSE
  b:=(oldr->>'book_id')::uuid;y:=CASE WHEN TG_TABLE_NAME='plugin_finance_year' THEN(oldr->>'id')::uuid ELSE(oldr->>'year_id')::uuid END;
  m:=plugin_finance_journal_person_lock(t,b,y);
  IF m<>'JOURNAL_PERSON_SOD' THEN IF TG_OP='DELETE' THEN RETURN OLD;ELSE RETURN NEW;END IF;END IF;
  IF TG_OP='DELETE' THEN RAISE EXCEPTION 'JOURNAL_PERSON_SCOPE_IMMUTABLE'; END IF;
  IF TG_TABLE_NAME='plugin_finance_year' THEN
   IF(oldr-ARRAY['version']) IS DISTINCT FROM(newr-ARRAY['version']) OR NEW.status<>'OPEN' OR NEW.precision_profile<>'V4' OR NEW.precision_review_required OR NEW.currency_minor_unit<>2 THEN RAISE EXCEPTION 'JOURNAL_PERSON_YEAR_LINEAGE_IMMUTABLE'; END IF;
  ELSIF TG_TABLE_NAME='plugin_finance_period' THEN
   IF TG_OP='INSERT' OR(oldr-ARRAY['status','version']) IS DISTINCT FROM(newr-ARRAY['status','version']) OR(NEW.status IS DISTINCT FROM OLD.status AND NOT(OLD.status='OPEN' AND NEW.status='CLOSED' AND NEW.version=OLD.version+1)) THEN RAISE EXCEPTION 'JOURNAL_PERSON_PERIOD_LINEAGE_IMMUTABLE'; END IF;
  ELSE
   IF(oldr-ARRAY['posting_seq']) IS DISTINCT FROM(newr-ARRAY['posting_seq']) OR NEW.opening_version<>0 OR NEW.baseline_version<>0 OR NEW.source_close_version<>0 OR(NEW.posting_seq<>OLD.posting_seq AND NEW.posting_seq<>OLD.posting_seq+1) THEN RAISE EXCEPTION 'JOURNAL_PERSON_BASELINE_UNSUPPORTED'; END IF;
  END IF;
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD;ELSE RETURN NEW;END IF;
END $$;
CREATE TRIGGER finance_person_book_scope BEFORE UPDATE OR DELETE ON plugin_finance_book FOR EACH ROW EXECUTE FUNCTION plugin_finance_journal_person_scope_guard();
CREATE TRIGGER finance_person_year_scope BEFORE UPDATE OR DELETE ON plugin_finance_year FOR EACH ROW EXECUTE FUNCTION plugin_finance_journal_person_scope_guard();
CREATE TRIGGER finance_person_period_scope BEFORE INSERT OR UPDATE OR DELETE ON plugin_finance_period FOR EACH ROW EXECUTE FUNCTION plugin_finance_journal_person_scope_guard();
CREATE TRIGGER finance_person_year_guard_scope BEFORE UPDATE OR DELETE ON plugin_finance_year_guard FOR EACH ROW EXECUTE FUNCTION plugin_finance_journal_person_scope_guard();
CREATE FUNCTION plugin_finance_journal_person_watermark_final() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF plugin_finance_journal_person_lock(NEW.tenant_id,NEW.book_id,NEW.year_id)='JOURNAL_PERSON_SOD' AND NEW.posting_seq IS DISTINCT FROM OLD.posting_seq THEN
  PERFORM 1 FROM plugin_finance_posting p JOIN plugin_finance_journal_person_post r ON(r.tenant_id,r.book_id,r.year_id,r.journal_id,r.posting_seq,r.posting_digest)=(p.tenant_id,p.book_id,p.year_id,p.journal_id,p.posting_seq,p.digest) WHERE(p.tenant_id,p.book_id,p.year_id,p.posting_seq)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.posting_seq);
  IF NOT FOUND THEN RAISE EXCEPTION 'JOURNAL_PERSON_WATERMARK_WITHOUT_POST'; END IF;
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER finance_person_year_watermark_final AFTER UPDATE ON plugin_finance_year_guard DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION plugin_finance_journal_person_watermark_final();




