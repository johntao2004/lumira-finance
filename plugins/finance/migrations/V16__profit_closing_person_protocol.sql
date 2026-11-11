-- Person-mode GL profit transfers. Applied V1-V15 remain byte-identical.
CREATE TABLE plugin_finance_profit_closing_guard(tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,generation bigint NOT NULL DEFAULT 0 CHECK(generation>=0),PRIMARY KEY(tenant_id,book_id,year_id),FOREIGN KEY(tenant_id,book_id,year_id) REFERENCES plugin_finance_year(tenant_id,book_id,id));
INSERT INTO plugin_finance_profit_closing_guard(tenant_id,book_id,year_id) SELECT tenant_id,book_id,id FROM plugin_finance_year;
CREATE FUNCTION plugin_finance_profit_closing_new_year() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN INSERT INTO plugin_finance_profit_closing_guard(tenant_id,book_id,year_id) VALUES(NEW.tenant_id,NEW.book_id,NEW.id);RETURN NEW;END $$;
CREATE TRIGGER finance_profit_closing_new_year AFTER INSERT ON plugin_finance_year FOR EACH ROW EXECUTE FUNCTION plugin_finance_profit_closing_new_year();
CREATE TABLE plugin_finance_profit_closing_source_slot(tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,id uuid NOT NULL DEFAULT gen_random_uuid(),producer_kind text NOT NULL CHECK(producer_kind IN('PROFIT_LOSS','YEAR_END','TRANSFER_REVERSAL')),cutoff date NOT NULL,source_digest text NOT NULL CHECK(source_digest~'^[a-f0-9]{64}$'),reverses_run_id uuid,latest_run_id uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(tenant_id,book_id,year_id,id),UNIQUE NULLS NOT DISTINCT(tenant_id,book_id,year_id,producer_kind,cutoff,source_digest,reverses_run_id),FOREIGN KEY(tenant_id,book_id,year_id) REFERENCES plugin_finance_year(tenant_id,book_id,id));
CREATE TABLE plugin_finance_profit_closing_run(tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,id uuid NOT NULL DEFAULT gen_random_uuid(),journal_id uuid NOT NULL,operation_id uuid NOT NULL,create_payload_hash text NOT NULL CHECK(create_payload_hash~'^[a-f0-9]{64}$'),source_slot_id uuid NOT NULL,
 producer_kind text NOT NULL CHECK(producer_kind IN('PROFIT_LOSS','YEAR_END','TRANSFER_REVERSAL')),cutoff date NOT NULL,status text NOT NULL DEFAULT 'DRAFT' CHECK(status IN('DRAFT','SUBMITTED','APPROVED','POSTED','CANCELLED')),version bigint NOT NULL DEFAULT 1 CHECK(version>0),content_version bigint NOT NULL DEFAULT 1 CHECK(content_version=1),
 content jsonb NOT NULL,content_digest text NOT NULL CHECK(content_digest~'^[a-f0-9]{64}$'),source_basis jsonb NOT NULL,source_snapshot jsonb NOT NULL,source_digest text NOT NULL CHECK(source_digest~'^[a-f0-9]{64}$'),generated_lines jsonb NOT NULL,generated_digest text NOT NULL CHECK(generated_digest~'^[a-f0-9]{64}$'),
 prepared_by jsonb NOT NULL,last_editor jsonb NOT NULL,predecessor_posted_run_id uuid,supersedes_cancelled_attempt_id uuid,expected_superseded_version bigint,reverses_run_id uuid,expected_reversal_of uuid,current_approval_id uuid,post_id uuid,administrative_termination_id uuid,
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(tenant_id,book_id,year_id,id),UNIQUE(tenant_id,book_id,year_id,journal_id),UNIQUE(tenant_id,book_id,year_id,id,journal_id),UNIQUE(tenant_id,book_id,year_id,operation_id),UNIQUE(tenant_id,book_id,year_id,id,content_version,content_digest),FOREIGN KEY(tenant_id,book_id,year_id,journal_id) REFERENCES plugin_finance_journal(tenant_id,book_id,year_id,id) DEFERRABLE INITIALLY DEFERRED,FOREIGN KEY(tenant_id,book_id,year_id,source_slot_id) REFERENCES plugin_finance_profit_closing_source_slot(tenant_id,book_id,year_id,id),FOREIGN KEY(tenant_id,book_id,year_id,predecessor_posted_run_id) REFERENCES plugin_finance_profit_closing_run(tenant_id,book_id,year_id,id),FOREIGN KEY(tenant_id,book_id,year_id,supersedes_cancelled_attempt_id) REFERENCES plugin_finance_profit_closing_run(tenant_id,book_id,year_id,id),FOREIGN KEY(tenant_id,book_id,year_id,reverses_run_id) REFERENCES plugin_finance_profit_closing_run(tenant_id,book_id,year_id,id),FOREIGN KEY(tenant_id,book_id,year_id,expected_reversal_of) REFERENCES plugin_finance_journal(tenant_id,book_id,year_id,id),CHECK((producer_kind='TRANSFER_REVERSAL')=(reverses_run_id IS NOT NULL AND expected_reversal_of IS NOT NULL)),CHECK((supersedes_cancelled_attempt_id IS NULL)=(expected_superseded_version IS NULL)));
ALTER TABLE plugin_finance_profit_closing_source_slot ADD FOREIGN KEY(tenant_id,book_id,year_id,latest_run_id) REFERENCES plugin_finance_profit_closing_run(tenant_id,book_id,year_id,id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE plugin_finance_profit_closing_source_slot ADD FOREIGN KEY(tenant_id,book_id,year_id,reverses_run_id) REFERENCES plugin_finance_profit_closing_run(tenant_id,book_id,year_id,id);
CREATE UNIQUE INDEX finance_profit_closing_one_pending ON plugin_finance_profit_closing_run(tenant_id,book_id,year_id) WHERE status IN('DRAFT','SUBMITTED','APPROVED');
CREATE TABLE plugin_finance_profit_closing_approval(tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,id uuid NOT NULL DEFAULT gen_random_uuid(),run_id uuid NOT NULL,journal_id uuid NOT NULL,content_version bigint NOT NULL,content_digest text NOT NULL,source_digest text NOT NULL,approved_journal_version bigint NOT NULL,approver jsonb NOT NULL,decided_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,expires_at timestamptz NOT NULL,reason text NOT NULL CHECK(length(reason) BETWEEN 1 AND 1000),PRIMARY KEY(tenant_id,book_id,year_id,id),FOREIGN KEY(tenant_id,book_id,year_id,run_id) REFERENCES plugin_finance_profit_closing_run(tenant_id,book_id,year_id,id),UNIQUE(tenant_id,book_id,year_id,run_id,id),FOREIGN KEY(tenant_id,book_id,year_id,run_id,journal_id) REFERENCES plugin_finance_profit_closing_run(tenant_id,book_id,year_id,id,journal_id),FOREIGN KEY(tenant_id,book_id,year_id,run_id,content_version,content_digest) REFERENCES plugin_finance_profit_closing_run(tenant_id,book_id,year_id,id,content_version,content_digest),CHECK(expires_at>decided_at AND expires_at<=decided_at+interval '7 days'));
CREATE TABLE plugin_finance_profit_closing_post(tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,id uuid NOT NULL DEFAULT gen_random_uuid(),run_id uuid NOT NULL,journal_id uuid NOT NULL,approval_id uuid NOT NULL,posted_journal_version bigint NOT NULL,content_version bigint NOT NULL,content_digest text NOT NULL,source_digest text NOT NULL,included_source_posting_seq bigint NOT NULL,pre_post_year_posting_seq bigint NOT NULL,posting_seq bigint NOT NULL,posting_digest text NOT NULL,poster jsonb NOT NULL,posted_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,effect_id uuid NOT NULL,receipt_id uuid NOT NULL,PRIMARY KEY(tenant_id,book_id,year_id,id),FOREIGN KEY(tenant_id,book_id,year_id,run_id) REFERENCES plugin_finance_profit_closing_run(tenant_id,book_id,year_id,id),UNIQUE(tenant_id,book_id,year_id,run_id),UNIQUE(tenant_id,book_id,year_id,run_id,id),UNIQUE(tenant_id,book_id,year_id,id,journal_id,posting_seq,posting_digest),FOREIGN KEY(tenant_id,book_id,year_id,run_id,approval_id) REFERENCES plugin_finance_profit_closing_approval(tenant_id,book_id,year_id,run_id,id),FOREIGN KEY(tenant_id,book_id,year_id,journal_id,posting_seq,posting_digest) REFERENCES plugin_finance_posting(tenant_id,book_id,year_id,journal_id,posting_seq,digest),CHECK(posting_seq=pre_post_year_posting_seq+1 AND included_source_posting_seq<=pre_post_year_posting_seq));
CREATE TABLE plugin_finance_profit_closing_effect(tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,id uuid NOT NULL DEFAULT gen_random_uuid(),run_id uuid NOT NULL,journal_id uuid NOT NULL,post_id uuid NOT NULL,operation_id uuid NOT NULL,stable_consumer text NOT NULL CHECK(stable_consumer='GL_PROFIT_CLOSING_V1'),source_system text NOT NULL CHECK(source_system='FINANCE_PROFIT_CLOSING_V1'),effect_type text NOT NULL CHECK(effect_type='POST_CLOSING_JOURNAL'),effect_key text NOT NULL CHECK(effect_key~'^[a-f0-9]{64}$'),payload_digest text NOT NULL,created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(tenant_id,book_id,year_id,id),FOREIGN KEY(tenant_id,book_id,year_id,run_id) REFERENCES plugin_finance_profit_closing_run(tenant_id,book_id,year_id,id),UNIQUE(tenant_id,book_id,year_id,effect_key),UNIQUE(tenant_id,book_id,year_id,run_id,id),UNIQUE(tenant_id,book_id,year_id,id,effect_key),FOREIGN KEY(tenant_id,book_id,year_id,run_id,post_id) REFERENCES plugin_finance_profit_closing_post(tenant_id,book_id,year_id,run_id,id) DEFERRABLE INITIALLY DEFERRED);
CREATE TABLE plugin_finance_profit_closing_receipt(tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,id uuid NOT NULL DEFAULT gen_random_uuid(),run_id uuid NOT NULL,journal_id uuid NOT NULL,post_id uuid NOT NULL,effect_id uuid NOT NULL,effect_key text NOT NULL,stable_consumer text NOT NULL CHECK(stable_consumer='GL_PROFIT_CLOSING_V1'),payload_digest text NOT NULL,created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(tenant_id,book_id,year_id,id),FOREIGN KEY(tenant_id,book_id,year_id,run_id) REFERENCES plugin_finance_profit_closing_run(tenant_id,book_id,year_id,id),UNIQUE(tenant_id,book_id,year_id,run_id,id),UNIQUE(tenant_id,book_id,year_id,effect_id),FOREIGN KEY(tenant_id,book_id,year_id,effect_id,effect_key) REFERENCES plugin_finance_profit_closing_effect(tenant_id,book_id,year_id,id,effect_key),FOREIGN KEY(tenant_id,book_id,year_id,run_id,post_id) REFERENCES plugin_finance_profit_closing_post(tenant_id,book_id,year_id,run_id,id));
CREATE TABLE plugin_finance_profit_closing_termination(tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,id uuid NOT NULL DEFAULT gen_random_uuid(),run_id uuid NOT NULL,journal_id uuid NOT NULL,journal_version bigint NOT NULL,content_version bigint NOT NULL,content_digest text NOT NULL,actor jsonb NOT NULL,reason text NOT NULL CHECK(length(reason) BETWEEN 1 AND 1000),created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(tenant_id,book_id,year_id,id),FOREIGN KEY(tenant_id,book_id,year_id,run_id) REFERENCES plugin_finance_profit_closing_run(tenant_id,book_id,year_id,id),UNIQUE(tenant_id,book_id,year_id,run_id,id));
CREATE TABLE plugin_finance_profit_closing_transition(tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,id uuid NOT NULL DEFAULT gen_random_uuid(),run_id uuid NOT NULL,journal_id uuid NOT NULL,journal_version bigint NOT NULL,previous_version bigint,action text NOT NULL CHECK(action IN('CREATE','SUBMIT','APPROVE','POST','REJECT','WITHDRAW','CANCEL','ADMIN_TERMINATE')),from_state text,to_state text NOT NULL,content_version bigint NOT NULL,content_digest text NOT NULL,actor jsonb NOT NULL,reason text NOT NULL CHECK(length(reason) BETWEEN 1 AND 1000),approval_id uuid,post_id uuid,termination_id uuid,created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(tenant_id,book_id,year_id,id),FOREIGN KEY(tenant_id,book_id,year_id,run_id) REFERENCES plugin_finance_profit_closing_run(tenant_id,book_id,year_id,id),UNIQUE(tenant_id,book_id,year_id,run_id,journal_version),FOREIGN KEY(tenant_id,book_id,year_id,run_id,previous_version) REFERENCES plugin_finance_profit_closing_transition(tenant_id,book_id,year_id,run_id,journal_version) DEFERRABLE INITIALLY DEFERRED,FOREIGN KEY(tenant_id,book_id,year_id,run_id,approval_id) REFERENCES plugin_finance_profit_closing_approval(tenant_id,book_id,year_id,run_id,id),FOREIGN KEY(tenant_id,book_id,year_id,run_id,post_id) REFERENCES plugin_finance_profit_closing_post(tenant_id,book_id,year_id,run_id,id),FOREIGN KEY(tenant_id,book_id,year_id,run_id,termination_id) REFERENCES plugin_finance_profit_closing_termination(tenant_id,book_id,year_id,run_id,id),CHECK((action='CREATE')=(journal_version=1)),CHECK((journal_version=1 AND previous_version IS NULL) OR(journal_version>1 AND previous_version=journal_version-1)),CHECK((action='APPROVE')=(approval_id IS NOT NULL)),CHECK((action='POST')=(post_id IS NOT NULL)),CHECK((action='ADMIN_TERMINATE')=(termination_id IS NOT NULL)));
ALTER TABLE plugin_finance_profit_closing_approval ADD FOREIGN KEY(tenant_id,book_id,year_id,run_id,approved_journal_version) REFERENCES plugin_finance_profit_closing_transition(tenant_id,book_id,year_id,run_id,journal_version) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE plugin_finance_profit_closing_post ADD FOREIGN KEY(tenant_id,book_id,year_id,run_id,posted_journal_version) REFERENCES plugin_finance_profit_closing_transition(tenant_id,book_id,year_id,run_id,journal_version) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE plugin_finance_profit_closing_termination ADD FOREIGN KEY(tenant_id,book_id,year_id,run_id,journal_version) REFERENCES plugin_finance_profit_closing_transition(tenant_id,book_id,year_id,run_id,journal_version) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE plugin_finance_profit_closing_run ADD FOREIGN KEY(tenant_id,book_id,year_id,id,current_approval_id) REFERENCES plugin_finance_profit_closing_approval(tenant_id,book_id,year_id,run_id,id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE plugin_finance_profit_closing_run ADD FOREIGN KEY(tenant_id,book_id,year_id,id,post_id) REFERENCES plugin_finance_profit_closing_post(tenant_id,book_id,year_id,run_id,id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE plugin_finance_profit_closing_run ADD FOREIGN KEY(tenant_id,book_id,year_id,id,administrative_termination_id) REFERENCES plugin_finance_profit_closing_termination(tenant_id,book_id,year_id,run_id,id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE plugin_finance_profit_closing_post ADD FOREIGN KEY(tenant_id,book_id,year_id,run_id,effect_id) REFERENCES plugin_finance_profit_closing_effect(tenant_id,book_id,year_id,run_id,id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE plugin_finance_profit_closing_post ADD FOREIGN KEY(tenant_id,book_id,year_id,run_id,receipt_id) REFERENCES plugin_finance_profit_closing_receipt(tenant_id,book_id,year_id,run_id,id) DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE plugin_finance_profit_closing_reversal_effect(tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,id uuid NOT NULL DEFAULT gen_random_uuid(),original_run_id uuid NOT NULL,original_post_id uuid NOT NULL,reversal_run_id uuid NOT NULL,reversal_post_id uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(tenant_id,book_id,year_id,id),UNIQUE(tenant_id,book_id,year_id,original_run_id),UNIQUE(tenant_id,book_id,year_id,reversal_run_id),FOREIGN KEY(tenant_id,book_id,year_id,original_run_id,original_post_id) REFERENCES plugin_finance_profit_closing_post(tenant_id,book_id,year_id,run_id,id),FOREIGN KEY(tenant_id,book_id,year_id,reversal_run_id,reversal_post_id) REFERENCES plugin_finance_profit_closing_post(tenant_id,book_id,year_id,run_id,id));
CREATE TABLE plugin_finance_profit_closing_request(tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,actor_id uuid NOT NULL,request_key text NOT NULL,route text NOT NULL,payload_hash text NOT NULL,run_id uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(actor_id,request_key),FOREIGN KEY(tenant_id,book_id,year_id,run_id) REFERENCES plugin_finance_profit_closing_run(tenant_id,book_id,year_id,id));
CREATE TABLE plugin_finance_profit_closing_audit(tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,id uuid NOT NULL DEFAULT gen_random_uuid(),run_id uuid NOT NULL,action text NOT NULL,actor jsonb NOT NULL,payload jsonb NOT NULL,created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(tenant_id,book_id,year_id,id),FOREIGN KEY(tenant_id,book_id,year_id,run_id) REFERENCES plugin_finance_profit_closing_run(tenant_id,book_id,year_id,id));
CREATE INDEX finance_profit_closing_page ON plugin_finance_profit_closing_run(tenant_id,book_id,year_id,created_at DESC,id DESC);
CREATE INDEX finance_profit_closing_post_order ON plugin_finance_profit_closing_post(tenant_id,book_id,year_id,posting_seq DESC);
CREATE FUNCTION plugin_finance_profit_closing_digest(j jsonb) RETURNS text LANGUAGE sql IMMUTABLE STRICT AS $$ SELECT encode(sha256(convert_to(j::text,'UTF8')),'hex') $$;
CREATE FUNCTION plugin_finance_profit_closing_money(n numeric) RETURNS text LANGUAGE sql IMMUTABLE STRICT AS $$ SELECT(n::numeric(28,8))::text $$;
CREATE FUNCTION plugin_finance_profit_closing_prelock(t uuid) RETURNS void LANGUAGE plpgsql AS $$ BEGIN
 PERFORM 1 FROM plugin_finance_person_realm WHERE tenant_id=t FOR SHARE;IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='JOURNAL_PERSON_IDENTITY_UNAVAILABLE';END IF;
END $$;
CREATE FUNCTION plugin_finance_profit_closing_scope(t uuid,b uuid,y uuid,new_use boolean,d date) RETURNS void LANGUAGE plpgsql AS $$ DECLARE yr plugin_finance_year;md text;BEGIN
 IF current_setting('transaction_isolation')<>'read committed' THEN RAISE EXCEPTION USING ERRCODE='40001',MESSAGE='PROFIT_CLOSING_READ_COMMITTED_REQUIRED';END IF;
 PERFORM plugin_finance_profit_closing_prelock(t);PERFORM 1 FROM plugin_finance_book WHERE tenant_id=t AND id=b FOR UPDATE;IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SCOPE_DENIED';END IF;
 SELECT * INTO yr FROM plugin_finance_year WHERE(tenant_id,book_id,id)=(t,b,y) FOR UPDATE;IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SCOPE_DENIED';END IF;
 PERFORM 1 FROM plugin_finance_year_guard WHERE(tenant_id,book_id,year_id)=(t,b,y) FOR UPDATE;
 SELECT mode INTO md FROM plugin_finance_journal_person_mode WHERE(tenant_id,book_id,year_id)=(t,b,y) FOR SHARE;
 IF md IS DISTINCT FROM 'JOURNAL_PERSON_SOD' OR yr.precision_profile<>'V4' OR yr.precision_review_required THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='JOURNAL_PERSON_MODE_REQUIRED';END IF;
 IF yr.status<>'OPEN' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PERIOD_CLOSED';END IF;
 PERFORM 1 FROM plugin_finance_profit_closing_guard WHERE(tenant_id,book_id,year_id)=(t,b,y) FOR UPDATE;IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='JOURNAL_PERSON_MODE_STALE';END IF;
 IF d IS NOT NULL THEN
  IF extract(year FROM d)::integer<>yr.year THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='INVALID_INPUT';END IF;
  PERFORM 1 FROM plugin_finance_period WHERE(tenant_id,book_id,year_id)=(t,b,y) AND month=extract(month FROM d)::integer AND(NOT new_use OR status='OPEN') FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PERIOD_CLOSED';END IF;
 END IF;
END $$;
CREATE FUNCTION plugin_finance_profit_closing_actor(t uuid,b uuid,y uuid,who jsonb,roles text[]) RETURNS void LANGUAGE plpgsql AS $$ BEGIN
 IF who IS NULL OR who IS DISTINCT FROM plugin_finance_journal_person_identity(t,(who->>'accountId')::uuid,true) OR NOT plugin_finance_journal_person_role(t,b,y,(who->>'accountId')::uuid,roles) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SCOPE_DENIED';END IF;
END $$;
CREATE FUNCTION plugin_finance_profit_closing_preview(t uuid,b uuid,y uuid,kind text,d date,reverses uuid DEFAULT NULL) RETURNS jsonb LANGUAGE plpgsql STABLE AS $$
DECLARE rec_current_account jsonb;yr plugin_finance_year;pr plugin_finance_period;md plugin_finance_journal_person_mode;g plugin_finance_year_guard;accounts jsonb;rows jsonb;basis jsonb;src jsonb;ls jsonb='[]'::jsonb;gd text;sd text;inc bigint;totd numeric;totc numeric;pl numeric;cur numeric;ca jsonb;ra jsonb;cc integer;rc integer;n integer;rr plugin_finance_profit_closing_run;lp uuid;le uuid;r jsonb;v numeric;outcome text;
BEGIN
 IF kind IS NULL OR kind NOT IN('PROFIT_LOSS','YEAR_END','TRANSFER_REVERSAL') OR d IS NULL OR d<>(date_trunc('month',d)+interval '1 month - 1 day')::date OR(kind='YEAR_END' AND to_char(d,'MM-DD')<>'12-31') OR(kind='TRANSFER_REVERSAL') IS DISTINCT FROM(reverses IS NOT NULL) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='INVALID_INPUT';END IF;
 SELECT * INTO yr FROM plugin_finance_year WHERE(tenant_id,book_id,id)=(t,b,y);IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SCOPE_DENIED';END IF;
 SELECT * INTO md FROM plugin_finance_journal_person_mode WHERE(tenant_id,book_id,year_id)=(t,b,y);
 SELECT * INTO pr FROM plugin_finance_period WHERE(tenant_id,book_id,year_id)=(t,b,y) AND month=extract(month FROM d)::integer;
 IF md.mode IS DISTINCT FROM 'JOURNAL_PERSON_SOD' OR yr.precision_profile<>'V4' OR yr.precision_review_required OR yr.status<>'OPEN' OR pr.status IS DISTINCT FROM 'OPEN' OR yr.year<>extract(year FROM d)::integer THEN
  RETURN jsonb_build_object('outcome','BLOCKED','source',NULL,'generatedLines',NULL,'generatedDigest',NULL,'blockers',jsonb_build_array(jsonb_build_object('code','PROFIT_CLOSING_SOURCE_UNSUPPORTED','message','须人员协议V4开放年度及原日开放月份')));END IF;
 SELECT * INTO g FROM plugin_finance_year_guard WHERE(tenant_id,book_id,year_id)=(t,b,y);
 IF NOT FOUND OR g.opening_version<>0 OR g.baseline_version<>0 OR g.source_close_version<>0 OR EXISTS(SELECT 1 FROM plugin_finance_opening WHERE(tenant_id,book_id,year_id)=(t,b,y) AND balance<>0) OR EXISTS(SELECT 1 FROM plugin_finance_journal j LEFT JOIN plugin_finance_posting p ON(p.tenant_id,p.book_id,p.year_id,p.journal_id)=(j.tenant_id,j.book_id,j.year_id,j.id) WHERE(j.tenant_id,j.book_id,j.year_id)=(t,b,y) AND j.status='POSTED' AND j.date<=d AND(p.posting_seq IS NULL OR j.amount_format_version<>'V4_8')) THEN
  RETURN jsonb_build_object('outcome','BLOCKED','source',NULL,'generatedLines',NULL,'generatedDigest',NULL,'blockers',jsonb_build_array(jsonb_build_object('code','PROFIT_CLOSING_SOURCE_UNSUPPORTED','message','不支持未知过账水位、旧精度或期初/累计基准')));END IF;
 SELECT count(*) INTO n FROM plugin_finance_account WHERE(tenant_id,book_id,year_id)=(t,b,y);
 IF n>5000 THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_LIMIT_EXCEEDED';END IF;
 IF EXISTS(SELECT 1 FROM plugin_finance_line l JOIN plugin_finance_journal j ON(j.tenant_id,j.book_id,j.year_id,j.id)=(l.tenant_id,l.book_id,l.year_id,l.journal_id) WHERE(j.tenant_id,j.book_id,j.year_id)=(t,b,y) AND j.status='POSTED' AND j.date<=d AND(l.debit::text IN('NaN','Infinity','-Infinity') OR l.credit::text IN('NaN','Infinity','-Infinity') OR l.debit<>round(l.debit,2) OR l.credit<>round(l.credit,2))) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_SOURCE_UNSUPPORTED';END IF;
 SELECT jsonb_agg(jsonb_build_object('id',a.id,'code',a.code,'name',a.name,'category',a.category,'normalSide',a.normal_side,'enabled',a.enabled,'purpose',a.purpose) ORDER BY a.id) INTO accounts FROM plugin_finance_account a WHERE(a.tenant_id,a.book_id,a.year_id)=(t,b,y);
 accounts=coalesce(accounts,'[]'::jsonb);
 SELECT coalesce(max(p.posting_seq),0) INTO inc FROM plugin_finance_posting p JOIN plugin_finance_journal j ON(j.tenant_id,j.book_id,j.year_id,j.id)=(p.tenant_id,p.book_id,p.year_id,p.journal_id) WHERE(j.tenant_id,j.book_id,j.year_id)=(t,b,y) AND j.status='POSTED' AND j.date<=d;
 SELECT coalesce(jsonb_agg(jsonb_build_object('account',x.account,'opening','0.00000000','debit',plugin_finance_profit_closing_money(x.debit),'credit',plugin_finance_profit_closing_money(x.credit),'balance',plugin_finance_profit_closing_money(x.debit-x.credit)) ORDER BY x.id),'[]'::jsonb),coalesce(sum(x.debit),0),coalesce(sum(x.credit),0) INTO rows,totd,totc FROM(
  SELECT a.id,jsonb_build_object('id',a.id,'code',a.code,'name',a.name,'category',a.category,'normalSide',a.normal_side,'enabled',a.enabled,'purpose',a.purpose) account,coalesce(sum(l.debit) FILTER(WHERE j.status='POSTED' AND j.date<=d),0) debit,coalesce(sum(l.credit) FILTER(WHERE j.status='POSTED' AND j.date<=d),0) credit
  FROM plugin_finance_account a LEFT JOIN plugin_finance_line l ON(l.tenant_id,l.book_id,l.year_id,l.account_id)=(a.tenant_id,a.book_id,a.year_id,a.id) LEFT JOIN plugin_finance_journal j ON(j.tenant_id,j.book_id,j.year_id,j.id)=(l.tenant_id,l.book_id,l.year_id,l.journal_id) WHERE(a.tenant_id,a.book_id,a.year_id)=(t,b,y) GROUP BY a.id,a.code,a.name,a.category,a.normal_side,a.enabled,a.purpose)x;
 IF totd<>totc OR totd>=1e20 OR totc>=1e20 THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_SOURCE_UNSUPPORTED';END IF;
 SELECT count(*) FILTER(WHERE value->>'purpose'='CURRENT_YEAR_PROFIT'),count(*) FILTER(WHERE value->>'purpose'='RETAINED_EARNINGS') INTO cc,rc FROM jsonb_array_elements(accounts);
 SELECT value INTO ca FROM jsonb_array_elements(accounts) WHERE value->>'purpose'='CURRENT_YEAR_PROFIT';SELECT value INTO ra FROM jsonb_array_elements(accounts) WHERE value->>'purpose'='RETAINED_EARNINGS';
 IF cc<>1 OR rc<>1 OR ca->>'category'<>'EQUITY' OR ra->>'category'<>'EQUITY' OR ca->>'normalSide'<>'CREDIT' OR ra->>'normalSide'<>'CREDIT' OR ca->'enabled'<>'true'::jsonb OR ra->'enabled'<>'true'::jsonb OR ca->>'id'=ra->>'id' OR EXISTS(SELECT 1 FROM jsonb_array_elements(rows) z WHERE z->'account'->>'category' IN('REVENUE','EXPENSE') AND z->'account'->'enabled'<>'true'::jsonb AND(z->>'balance')::numeric<>0) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_SOURCE_UNSUPPORTED';END IF;
 SELECT p.run_id INTO lp FROM plugin_finance_profit_closing_post p WHERE(p.tenant_id,p.book_id,p.year_id)=(t,b,y) ORDER BY p.posting_seq DESC LIMIT 1;
 SELECT q.id INTO le FROM plugin_finance_profit_closing_run q JOIN plugin_finance_profit_closing_post p ON(p.tenant_id,p.book_id,p.year_id,p.run_id)=(q.tenant_id,q.book_id,q.year_id,q.id) WHERE(q.tenant_id,q.book_id,q.year_id)=(t,b,y) AND q.producer_kind IN('PROFIT_LOSS','YEAR_END') AND NOT EXISTS(SELECT 1 FROM plugin_finance_profit_closing_reversal_effect e WHERE(e.tenant_id,e.book_id,e.year_id,e.original_run_id)=(t,b,y,q.id)) ORDER BY p.posting_seq DESC LIMIT 1;
 IF EXISTS(SELECT 1 FROM plugin_finance_profit_closing_run q WHERE(q.tenant_id,q.book_id,q.year_id)=(t,b,y) AND q.id=le AND q.cutoff>d) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_REVERSAL_DEPENDENCY';END IF;
 basis=jsonb_build_object('tenantId',t,'bookId',b,'yearId',y,'cutoff',d,'modeEpoch',md.epoch,'modeGeneration',md.generation::text,'periodId',pr.id,'periodVersion',pr.version::text,'includedPostingSeq',inc::text,'openingVersion',g.opening_version::text,'baselineVersion',g.baseline_version::text,'sourceCloseVersion',g.source_close_version::text,'accountSemanticDigest',plugin_finance_profit_closing_digest(accounts));
 sd=plugin_finance_profit_closing_digest(jsonb_build_object('basis',basis,'sourceRows',rows));
 src=jsonb_build_object('format','PROFIT_CLOSING_SOURCE_PG_V1','basis',basis,'sourceDigest',sd,'observedYearPostingSeq',g.posting_seq::text,'sourceRows',rows,'totalDebit',plugin_finance_profit_closing_money(totd),'totalCredit',plugin_finance_profit_closing_money(totc),'balanced',true,'includesPriorPostedClosings',true,'latestPostedRunId',lp,'latestEffectiveTransferRunId',le);
 IF kind='TRANSFER_REVERSAL' THEN
  SELECT * INTO rr FROM plugin_finance_profit_closing_run WHERE(tenant_id,book_id,year_id,id)=(t,b,y,reverses);
  IF NOT FOUND OR rr.status<>'POSTED' OR rr.producer_kind='TRANSFER_REVERSAL' OR rr.cutoff<>d OR rr.id IS DISTINCT FROM le THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_REVERSAL_DEPENDENCY';END IF;
  FOR r IN SELECT value FROM jsonb_array_elements(rr.generated_lines) ORDER BY(value->>'lineNo')::integer LOOP
   SELECT value INTO rec_current_account FROM jsonb_array_elements(accounts) WHERE value->>'id'=r->'account'->>'id';
   IF rec_current_account IS NULL OR rec_current_account->'enabled'<>'true'::jsonb OR(rec_current_account-ARRAY['code','name','enabled']) IS DISTINCT FROM((r->'account')-ARRAY['code','name','enabled']) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_SOURCE_UNSUPPORTED';END IF;
   ls=ls||jsonb_build_array(r||jsonb_build_object('account',rec_current_account,'debit',r->>'credit','credit',r->>'debit','memo','结转全额冲回'));
  END LOOP;
 ELSIF kind='PROFIT_LOSS' THEN
  pl=0;n=0;FOR r IN SELECT value FROM jsonb_array_elements(rows) WHERE value->'account'->>'category' IN('REVENUE','EXPENSE') AND(value->>'balance')::numeric<>0 ORDER BY value->'account'->>'id' LOOP
   v=(r->>'balance')::numeric;pl=pl+v;n=n+1;ls=ls||jsonb_build_array(jsonb_build_object('lineNo',n,'account',r->'account','debit',plugin_finance_profit_closing_money(greatest(-v,0)),'credit',plugin_finance_profit_closing_money(greatest(v,0)),'memo','损益结转'));
  END LOOP;
  IF pl<>0 THEN n=n+1;ls=ls||jsonb_build_array(jsonb_build_object('lineNo',n,'account',ca,'debit',plugin_finance_profit_closing_money(greatest(pl,0)),'credit',plugin_finance_profit_closing_money(greatest(-pl,0)),'memo','损益结转'));END IF;
 ELSE
  IF EXISTS(SELECT 1 FROM jsonb_array_elements(rows) z WHERE z->'account'->>'category' IN('REVENUE','EXPENSE') AND(z->>'balance')::numeric<>0) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_SOURCE_STALE';END IF;
  SELECT(value->>'balance')::numeric INTO cur FROM jsonb_array_elements(rows) WHERE value->'account'->>'id'=ca->>'id';
  IF cur<>0 THEN ls=jsonb_build_array(jsonb_build_object('lineNo',1,'account',ca,'debit',plugin_finance_profit_closing_money(greatest(-cur,0)),'credit',plugin_finance_profit_closing_money(greatest(cur,0)),'memo','本年利润转未分配'),jsonb_build_object('lineNo',2,'account',ra,'debit',plugin_finance_profit_closing_money(greatest(cur,0)),'credit',plugin_finance_profit_closing_money(greatest(-cur,0)),'memo','本年利润转未分配'));END IF;
 END IF;
 IF jsonb_array_length(ls)>500 THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_LIMIT_EXCEEDED';END IF;
 gd=plugin_finance_profit_closing_digest(ls);outcome=CASE WHEN jsonb_array_length(ls)=0 THEN 'NOOP' ELSE 'PRODUCIBLE' END;
 RETURN jsonb_build_object('outcome',outcome,'source',src,'generatedLines',ls,'generatedDigest',gd,'blockers','[]'::jsonb);
EXCEPTION WHEN numeric_value_out_of_range THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_SOURCE_UNSUPPORTED';
END $$;
CREATE FUNCTION plugin_finance_profit_closing_command(t uuid,b uuid,y uuid,who jsonb,op text,target uuid,payload jsonb) RETURNS jsonb LANGUAGE plpgsql AS $$
DECLARE r plugin_finance_profit_closing_run;prev plugin_finance_profit_closing_run;slot plugin_finance_profit_closing_source_slot;ap plugin_finance_profit_closing_approval;orig plugin_finance_profit_closing_run;pv jsonb;src jsonb;ls jsonb;ct jsonb;rec jsonb;ph text;dg text;sd text;pd text;ek text;kind text;state text;action text=op;rid uuid;jid uuid;sid uuid;aid uuid;pid uuid;eid uuid;receipt uuid;term uuid;reverses uuid;pred uuid;sup uuid;period_id uuid;d date;v bigint;seq bigint;beforeseq bigint;num bigint;expiry timestamptz;new_effect boolean=true;
BEGIN
 PERFORM plugin_finance_profit_closing_prelock(t);PERFORM pg_advisory_xact_lock(hashtextextended((who->>'accountId')||':'||(payload->>'idempotencyKey'),0));
 PERFORM plugin_finance_profit_closing_scope(t,b,y,false,NULL);
 PERFORM plugin_finance_profit_closing_actor(t,b,y,who,CASE WHEN op='POST' THEN ARRAY['OWNER','POSTER'] WHEN op IN('APPROVE','REJECT','ADMIN_TERMINATE') THEN ARRAY['OWNER','REVIEWER'] ELSE ARRAY['OWNER','MAKER'] END);
 IF op='CREATE' THEN
  ph=plugin_finance_profit_closing_digest(payload-ARRAY['schemaVersion','idempotencyKey']);
  SELECT * INTO r FROM plugin_finance_profit_closing_run WHERE(tenant_id,book_id,year_id,operation_id)=(t,b,y,(payload->>'operationId')::uuid);
  IF FOUND THEN IF r.create_payload_hash<>ph THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='IDEMPOTENCY_CONFLICT';END IF;RETURN jsonb_build_object('runId',r.id,'journalId',r.journal_id,'newEffect',false);END IF;
  kind=payload->>'producerKind';d=(payload->>'cutoff')::date;reverses=(payload->>'reversesRunId')::uuid;pred=(payload->>'predecessorPostedRunId')::uuid;sup=(payload->>'supersedesCancelledAttemptId')::uuid;
  PERFORM plugin_finance_profit_closing_scope(t,b,y,true,d);
  IF EXISTS(SELECT 1 FROM plugin_finance_profit_closing_run WHERE(tenant_id,book_id,year_id)=(t,b,y) AND status IN('DRAFT','SUBMITTED','APPROVED')) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_RUN_PENDING';END IF;
  pv=plugin_finance_profit_closing_preview(t,b,y,kind,d,reverses);
  IF pv->>'outcome'='NOOP' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_NO_MOVEMENT';END IF;
  IF pv->>'outcome'<>'PRODUCIBLE' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_SOURCE_UNSUPPORTED';END IF;
  src=pv->'source';ls=pv->'generatedLines';sd=src->>'sourceDigest';
  IF payload->'expectedSource' IS DISTINCT FROM src->'basis' OR payload->>'expectedSourceDigest' IS DISTINCT FROM sd OR payload->>'expectedGeneratedDigest' IS DISTINCT FROM pv->>'generatedDigest' OR pred IS DISTINCT FROM(src->>'latestPostedRunId')::uuid THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_SOURCE_STALE';END IF;
  SELECT * INTO slot FROM plugin_finance_profit_closing_source_slot WHERE(tenant_id,book_id,year_id,producer_kind,cutoff,source_digest)=(t,b,y,kind,d,sd) AND reverses_run_id IS NOT DISTINCT FROM reverses FOR UPDATE;
  IF FOUND THEN
   SELECT * INTO prev FROM plugin_finance_profit_closing_run WHERE(tenant_id,book_id,year_id,id)=(t,b,y,slot.latest_run_id);
   IF prev.status<>'CANCELLED' OR sup IS DISTINCT FROM prev.id OR(payload->>'expectedSupersededVersion')::bigint IS DISTINCT FROM prev.version THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_SOURCE_ALREADY_CLAIMED';END IF;
   sid=slot.id;
  ELSE
   SELECT * INTO prev FROM plugin_finance_profit_closing_run WHERE(tenant_id,book_id,year_id,producer_kind,cutoff)=(t,b,y,kind,d) AND reverses_run_id IS NOT DISTINCT FROM reverses ORDER BY created_at DESC,id DESC LIMIT 1;
   IF FOUND AND prev.status='CANCELLED' AND(sup IS DISTINCT FROM prev.id OR(payload->>'expectedSupersededVersion')::bigint IS DISTINCT FROM prev.version) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_SOURCE_ALREADY_CLAIMED';END IF;
   IF sup IS NOT NULL THEN
    SELECT * INTO prev FROM plugin_finance_profit_closing_run WHERE(tenant_id,book_id,year_id,id)=(t,b,y,sup);
    IF NOT FOUND OR prev.status<>'CANCELLED' OR prev.producer_kind<>kind OR prev.cutoff<>d OR prev.reverses_run_id IS DISTINCT FROM reverses OR prev.version IS DISTINCT FROM(payload->>'expectedSupersededVersion')::bigint OR EXISTS(SELECT 1 FROM plugin_finance_profit_closing_run WHERE(tenant_id,book_id,year_id,supersedes_cancelled_attempt_id)=(t,b,y,sup)) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_STATE_CONFLICT';END IF;
   ELSIF payload->'expectedSupersededVersion'<>'null'::jsonb THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='INVALID_INPUT';END IF;
   sid=gen_random_uuid();
  END IF;
  rid=gen_random_uuid();jid=gen_random_uuid();
  IF kind='TRANSFER_REVERSAL' THEN SELECT * INTO orig FROM plugin_finance_profit_closing_run WHERE(tenant_id,book_id,year_id,id)=(t,b,y,reverses);END IF;
  ct=jsonb_build_object('format','CLOSING_PERSON_CONTENT_PG_V1','producerKind',kind,'rawType',CASE kind WHEN 'PROFIT_LOSS' THEN 'PROFIT_CLOSE' WHEN 'YEAR_END' THEN 'YEAR_END' ELSE 'REVERSAL' END,'cutoff',d,'summary',CASE kind WHEN 'PROFIT_LOSS' THEN '损益结转' WHEN 'YEAR_END' THEN '本年利润转未分配利润' ELSE '结转全额冲回' END,'currency','CNY','sourceDigest',sd,'generatedLines',ls,'documents','[]'::jsonb,'reversesJournalId',orig.journal_id,'reason',payload->>'reason');dg=plugin_finance_profit_closing_digest(ct);
  IF slot.id IS NULL THEN INSERT INTO plugin_finance_profit_closing_source_slot(tenant_id,book_id,year_id,id,producer_kind,cutoff,source_digest,reverses_run_id,latest_run_id) VALUES(t,b,y,sid,kind,d,sd,reverses,rid);END IF;
  INSERT INTO plugin_finance_profit_closing_run(tenant_id,book_id,year_id,id,journal_id,operation_id,create_payload_hash,source_slot_id,producer_kind,cutoff,content,content_digest,source_basis,source_snapshot,source_digest,generated_lines,generated_digest,prepared_by,last_editor,predecessor_posted_run_id,supersedes_cancelled_attempt_id,expected_superseded_version,reverses_run_id,expected_reversal_of) VALUES(t,b,y,rid,jid,(payload->>'operationId')::uuid,ph,sid,kind,d,ct,dg,src->'basis',src,sd,ls,pv->>'generatedDigest',who,who,pred,sup,(payload->>'expectedSupersededVersion')::bigint,reverses,orig.journal_id);
  UPDATE plugin_finance_profit_closing_source_slot SET latest_run_id=rid WHERE(tenant_id,book_id,year_id,id)=(t,b,y,sid);
  period_id=(src->'basis'->>'periodId')::uuid;
  INSERT INTO plugin_finance_journal(tenant_id,book_id,year_id,id,period_id,date,type,summary,created_by,closing_basis,amount_format_version) VALUES(t,b,y,jid,period_id,d,ct->>'rawType',ct->>'summary',(who->>'accountId')::uuid,sd,'V4_8');
  FOR rec IN SELECT value FROM jsonb_array_elements(ls) ORDER BY(value->>'lineNo')::integer LOOP
   INSERT INTO plugin_finance_line(tenant_id,book_id,year_id,journal_id,line_no,account_id,debit,credit,memo) VALUES(t,b,y,jid,(rec->>'lineNo')::integer,(rec->'account'->>'id')::uuid,(rec->>'debit')::numeric,(rec->>'credit')::numeric,rec->>'memo');
  END LOOP;
  INSERT INTO plugin_finance_profit_closing_transition(tenant_id,book_id,year_id,run_id,journal_id,journal_version,action,to_state,content_version,content_digest,actor,reason) VALUES(t,b,y,rid,jid,1,'CREATE','DRAFT',1,dg,who,payload->>'reason');
 ELSE
  SELECT * INTO r FROM plugin_finance_profit_closing_run WHERE(tenant_id,book_id,year_id,id)=(t,b,y,target) FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SCOPE_DENIED';END IF;
  rid=r.id;jid=r.journal_id;v=r.version+1;state=r.status;dg=r.content_digest;d=r.cutoff;
  IF r.version IS DISTINCT FROM(payload->>'expectedVersion')::bigint OR r.content_version IS DISTINCT FROM(payload->>'expectedContentVersion')::bigint OR dg IS DISTINCT FROM payload->>'contentDigest' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='VERSION_CONFLICT';END IF;
  PERFORM plugin_finance_profit_closing_scope(t,b,y,op IN('SUBMIT','APPROVE','POST'),d);
  IF op IN('SUBMIT','WITHDRAW','CANCEL') AND who->>'accountId'<>r.prepared_by->>'accountId' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SCOPE_DENIED';END IF;
  IF op IN('APPROVE','REJECT','ADMIN_TERMINATE') AND(who->>'personId'=r.prepared_by->>'personId' OR who->>'personId'=r.last_editor->>'personId') THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SELF_APPROVAL_FORBIDDEN';END IF;
  IF op IN('SUBMIT','APPROVE','POST') THEN
   PERFORM plugin_finance_profit_closing_actor(t,b,y,r.prepared_by,ARRAY['OWNER','MAKER']);
   pv=plugin_finance_profit_closing_preview(t,b,y,r.producer_kind,d,r.reverses_run_id);
   IF pv->>'outcome'<>'PRODUCIBLE' OR pv->'source'->'basis' IS DISTINCT FROM r.source_basis OR pv->'source'->>'sourceDigest' IS DISTINCT FROM r.source_digest OR pv->'generatedLines' IS DISTINCT FROM r.generated_lines THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_SOURCE_STALE';END IF;
  END IF;
  IF op='SUBMIT' THEN
   IF r.status<>'DRAFT' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_STATE_CONFLICT';END IF;state='SUBMITTED';
  ELSIF op='APPROVE' THEN
   IF r.status<>'SUBMITTED' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_STATE_CONFLICT';END IF;
   expiry=CASE WHEN payload->'requestedExpiresAt'='null'::jsonb THEN CURRENT_TIMESTAMP+interval '7 days' ELSE(payload->>'requestedExpiresAt')::timestamptz END;
   IF expiry IS NULL OR expiry<=clock_timestamp() OR expiry>CURRENT_TIMESTAMP+interval '7 days' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='INVALID_INPUT';END IF;
   aid=gen_random_uuid();INSERT INTO plugin_finance_profit_closing_approval(tenant_id,book_id,year_id,id,run_id,journal_id,content_version,content_digest,source_digest,approved_journal_version,approver,expires_at,reason) VALUES(t,b,y,aid,rid,jid,1,dg,r.source_digest,v,who,expiry,payload->>'reason');state='APPROVED';
  ELSIF op='POST' THEN
   IF r.status<>'APPROVED' OR r.current_approval_id IS DISTINCT FROM(payload->>'approvalId')::uuid THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_APPROVAL_STALE';END IF;
   SELECT * INTO ap FROM plugin_finance_profit_closing_approval WHERE(tenant_id,book_id,year_id,id)=(t,b,y,r.current_approval_id);
   PERFORM plugin_finance_profit_closing_actor(t,b,y,ap.approver,ARRAY['OWNER','REVIEWER']);
   IF clock_timestamp()>=ap.expires_at THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_APPROVAL_EXPIRED';END IF;
   SELECT posting_seq INTO beforeseq FROM plugin_finance_year_guard WHERE(tenant_id,book_id,year_id)=(t,b,y);
   UPDATE plugin_finance_year_guard SET posting_seq=posting_seq+1 WHERE(tenant_id,book_id,year_id)=(t,b,y) RETURNING posting_seq INTO seq;
   SELECT coalesce(max(number),0)+1 INTO num FROM plugin_finance_journal WHERE(tenant_id,book_id,year_id)=(t,b,y);
   pd=plugin_finance_profit_closing_digest(jsonb_build_object('format','CLOSING_POST_PG_V1','runId',rid,'contentDigest',dg,'approvalId',ap.id,'approvedJournalVersion',r.version::text,'sourceDigest',r.source_digest));
   INSERT INTO plugin_finance_posting(tenant_id,book_id,year_id,journal_id,journal_version,digest,posted_by,posting_seq) VALUES(t,b,y,jid,r.version,pd,(who->>'accountId')::uuid,seq);
   pid=gen_random_uuid();eid=gen_random_uuid();receipt=gen_random_uuid();ek=plugin_finance_profit_closing_digest(jsonb_build_array(t,b,y,'GL_PROFIT_CLOSING_V1','FINANCE_PROFIT_CLOSING_V1',r.operation_id,'POST_CLOSING_JOURNAL'));
   INSERT INTO plugin_finance_profit_closing_post(tenant_id,book_id,year_id,id,run_id,journal_id,approval_id,posted_journal_version,content_version,content_digest,source_digest,included_source_posting_seq,pre_post_year_posting_seq,posting_seq,posting_digest,poster,effect_id,receipt_id) VALUES(t,b,y,pid,rid,jid,ap.id,v,1,dg,r.source_digest,(r.source_basis->>'includedPostingSeq')::bigint,beforeseq,seq,pd,who,eid,receipt);
   INSERT INTO plugin_finance_profit_closing_effect(tenant_id,book_id,year_id,id,run_id,journal_id,post_id,operation_id,stable_consumer,source_system,effect_type,effect_key,payload_digest) VALUES(t,b,y,eid,rid,jid,pid,r.operation_id,'GL_PROFIT_CLOSING_V1','FINANCE_PROFIT_CLOSING_V1','POST_CLOSING_JOURNAL',ek,dg);
   INSERT INTO plugin_finance_profit_closing_receipt(tenant_id,book_id,year_id,id,run_id,journal_id,post_id,effect_id,effect_key,stable_consumer,payload_digest) VALUES(t,b,y,receipt,rid,jid,pid,eid,ek,'GL_PROFIT_CLOSING_V1',dg);
   IF r.producer_kind='TRANSFER_REVERSAL' THEN
    SELECT post_id INTO aid FROM plugin_finance_profit_closing_run WHERE(tenant_id,book_id,year_id,id)=(t,b,y,r.reverses_run_id);
    INSERT INTO plugin_finance_profit_closing_reversal_effect(tenant_id,book_id,year_id,original_run_id,original_post_id,reversal_run_id,reversal_post_id) VALUES(t,b,y,r.reverses_run_id,aid,rid,pid);
   END IF;
   state='POSTED';new_effect=true;aid=ap.id;
  ELSIF op='WITHDRAW' THEN
   IF r.status<>'SUBMITTED' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_STATE_CONFLICT';END IF;state='DRAFT';
  ELSIF op='REJECT' THEN
   IF r.status NOT IN('SUBMITTED','APPROVED') THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_STATE_CONFLICT';END IF;state='DRAFT';
  ELSIF op='CANCEL' OR op='ADMIN_TERMINATE' THEN
   IF r.status<>'DRAFT' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_STATE_CONFLICT';END IF;state='CANCELLED';
   IF op='ADMIN_TERMINATE' THEN term=gen_random_uuid();INSERT INTO plugin_finance_profit_closing_termination(tenant_id,book_id,year_id,id,run_id,journal_id,journal_version,content_version,content_digest,actor,reason) VALUES(t,b,y,term,rid,jid,v,1,dg,who,payload->>'reason');END IF;
  ELSE RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='INVALID_INPUT';END IF;
  UPDATE plugin_finance_journal SET status=state,version=v,approved_by=CASE WHEN state='APPROVED' THEN(who->>'accountId')::uuid WHEN state='POSTED' THEN approved_by ELSE NULL END,approved_version=CASE WHEN state='APPROVED' THEN v WHEN state='POSTED' THEN approved_version ELSE NULL END,number=CASE WHEN state='POSTED' THEN num ELSE number END,reversal_of=CASE WHEN state='POSTED' THEN r.expected_reversal_of ELSE NULL END WHERE(tenant_id,book_id,year_id,id)=(t,b,y,jid);
  UPDATE plugin_finance_profit_closing_run SET status=state,version=v,current_approval_id=CASE WHEN state IN('APPROVED','POSTED') THEN aid ELSE NULL END,post_id=pid,administrative_termination_id=term,updated_at=CURRENT_TIMESTAMP WHERE(tenant_id,book_id,year_id,id)=(t,b,y,rid);
  INSERT INTO plugin_finance_profit_closing_transition(tenant_id,book_id,year_id,run_id,journal_id,journal_version,previous_version,action,from_state,to_state,content_version,content_digest,actor,reason,approval_id,post_id,termination_id) VALUES(t,b,y,rid,jid,v,v-1,op,r.status,state,1,dg,who,payload->>'reason',CASE WHEN op='APPROVE' THEN aid END,pid,term);
 END IF;
 INSERT INTO plugin_finance_profit_closing_audit(tenant_id,book_id,year_id,run_id,action,actor,payload) VALUES(t,b,y,rid,op,who,jsonb_build_object('journalId',jid,'reason',payload->'reason'));
 RETURN jsonb_build_object('runId',rid,'journalId',jid,'newEffect',new_effect);
END $$;
CREATE FUNCTION plugin_finance_profit_closing_source_digest(t uuid,b uuid,y uuid,d date) RETURNS text LANGUAGE plpgsql STABLE AS $$ DECLARE p jsonb;BEGIN
 p=plugin_finance_profit_closing_preview(t,b,y,'PROFIT_LOSS',d,NULL);RETURN p->'source'->>'sourceDigest';
EXCEPTION WHEN SQLSTATE 'P0001' THEN
 IF SQLERRM IN('PROFIT_CLOSING_SOURCE_UNSUPPORTED','PROFIT_CLOSING_REVERSAL_DEPENDENCY') THEN RETURN NULL;END IF;RAISE;
END $$;

CREATE FUNCTION plugin_finance_profit_closing_snapshot(t uuid,b uuid,y uuid,j uuid) RETURNS jsonb LANGUAGE plpgsql STABLE AS $$ DECLARE r plugin_finance_profit_closing_run;h plugin_finance_journal;n integer;BEGIN
 SELECT * INTO r FROM plugin_finance_profit_closing_run WHERE(tenant_id,book_id,year_id,journal_id)=(t,b,y,j);IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_STATE_CONFLICT';END IF;
 SELECT * INTO h FROM plugin_finance_journal WHERE(tenant_id,book_id,year_id,id)=(t,b,y,j);
 IF NOT FOUND OR h.type IS DISTINCT FROM r.content->>'rawType' OR h.date<>r.cutoff OR h.period_id IS DISTINCT FROM(r.source_basis->>'periodId')::uuid OR h.summary IS DISTINCT FROM r.content->>'summary' OR h.created_by IS DISTINCT FROM(r.prepared_by->>'accountId')::uuid OR h.source_event_id IS NOT NULL OR h.closing_basis IS DISTINCT FROM r.source_digest OR h.amount_format_version<>'V4_8' OR h.reversal_of IS DISTINCT FROM(CASE WHEN h.status='POSTED' THEN r.expected_reversal_of ELSE NULL END) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_STATE_CONFLICT';END IF;
 SELECT count(*) INTO n FROM plugin_finance_line WHERE(tenant_id,book_id,year_id,journal_id)=(t,b,y,j);
 IF n<>jsonb_array_length(r.generated_lines) OR n NOT BETWEEN 2 AND 500 OR EXISTS(SELECT 1 FROM plugin_finance_line l LEFT JOIN LATERAL(SELECT value e FROM jsonb_array_elements(r.generated_lines) WHERE(value->>'lineNo')::integer=l.line_no)g ON true WHERE(l.tenant_id,l.book_id,l.year_id,l.journal_id)=(t,b,y,j) AND(g.e IS NULL OR l.account_id IS DISTINCT FROM(g.e->'account'->>'id')::uuid OR l.debit IS DISTINCT FROM(g.e->>'debit')::numeric OR l.credit IS DISTINCT FROM(g.e->>'credit')::numeric OR l.memo IS DISTINCT FROM g.e->>'memo')) OR EXISTS(SELECT 1 FROM plugin_finance_document WHERE(tenant_id,book_id,year_id,journal_id)=(t,b,y,j)) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_STATE_CONFLICT';END IF;
 RETURN r.content;
END $$;
CREATE FUNCTION plugin_finance_profit_closing_historical_source(t uuid,b uuid,y uuid,rid uuid,own_seq bigint) RETURNS void LANGUAGE plpgsql AS $$ DECLARE r plugin_finance_profit_closing_run;z jsonb;dd numeric;cc numeric;inc bigint;BEGIN
 SELECT * INTO r FROM plugin_finance_profit_closing_run WHERE(tenant_id,book_id,year_id,id)=(t,b,y,rid);
 IF NOT FOUND OR r.source_digest IS DISTINCT FROM plugin_finance_profit_closing_digest(jsonb_build_object('basis',r.source_basis,'sourceRows',r.source_snapshot->'sourceRows')) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_SOURCE_STALE';END IF;
 SELECT coalesce(max(p.posting_seq),0) INTO inc FROM plugin_finance_posting p JOIN plugin_finance_journal j ON(j.tenant_id,j.book_id,j.year_id,j.id)=(p.tenant_id,p.book_id,p.year_id,p.journal_id) WHERE(p.tenant_id,p.book_id,p.year_id)=(t,b,y) AND p.posting_seq<own_seq AND j.status='POSTED' AND j.date<=r.cutoff;
 IF inc IS DISTINCT FROM(r.source_basis->>'includedPostingSeq')::bigint THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_SOURCE_STALE';END IF;
 IF EXISTS(WITH frozen AS(SELECT value z FROM jsonb_array_elements(r.source_snapshot->'sourceRows')),past AS(
  SELECT l.account_id,coalesce(sum(l.debit),0) debit,coalesce(sum(l.credit),0) credit FROM plugin_finance_line l JOIN plugin_finance_journal j ON(j.tenant_id,j.book_id,j.year_id,j.id)=(l.tenant_id,l.book_id,l.year_id,l.journal_id) JOIN plugin_finance_posting p ON(p.tenant_id,p.book_id,p.year_id,p.journal_id)=(j.tenant_id,j.book_id,j.year_id,j.id) WHERE(l.tenant_id,l.book_id,l.year_id)=(t,b,y) AND p.posting_seq<own_seq AND j.status='POSTED' AND j.date<=r.cutoff GROUP BY l.account_id)
 SELECT 1 FROM frozen f FULL JOIN past a ON a.account_id=(f.z->'account'->>'id')::uuid WHERE f.z IS NULL OR f.z->>'opening'<>'0.00000000' OR coalesce(a.debit,0) IS DISTINCT FROM(f.z->>'debit')::numeric OR coalesce(a.credit,0) IS DISTINCT FROM(f.z->>'credit')::numeric OR(coalesce(a.debit,0)-coalesce(a.credit,0)) IS DISTINCT FROM(f.z->>'balance')::numeric) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_SOURCE_STALE';END IF;
 -- Historical account names/period state are deliberately not rejoined to current configuration.
END $$;
CREATE FUNCTION plugin_finance_profit_closing_raw_guard(tab text,operation text,o jsonb,n jsonb) RETURNS void LANGUAGE plpgsql AS $$
DECLARE q jsonb=CASE WHEN operation='DELETE' THEN o ELSE n END;t uuid=(q->>'tenant_id')::uuid;b uuid=(q->>'book_id')::uuid;y uuid=(q->>'year_id')::uuid;j uuid;r plugin_finance_profit_closing_run;h plugin_finance_journal;e jsonb;cleanup boolean;
BEGIN
 j=CASE WHEN tab='plugin_finance_journal' THEN(q->>'id')::uuid ELSE(q->>'journal_id')::uuid END;
 SELECT * INTO r FROM plugin_finance_profit_closing_run WHERE(tenant_id,book_id,year_id,journal_id)=(t,b,y,j);IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_STATE_CONFLICT';END IF;
 IF operation='DELETE' OR tab='plugin_finance_document' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_STATE_CONFLICT';END IF;
 cleanup=tab='plugin_finance_journal' AND operation='UPDATE' AND((n->>'status'='DRAFT' AND o->>'status' IN('SUBMITTED','APPROVED')) OR(n->>'status'='CANCELLED' AND o->>'status'='DRAFT'));
 PERFORM plugin_finance_profit_closing_scope(t,b,y,NOT cleanup,r.cutoff);
 IF tab='plugin_finance_journal' THEN
  IF n->>'type' IS DISTINCT FROM r.content->>'rawType' OR(n->>'date')::date<>r.cutoff OR(n->>'period_id')::uuid IS DISTINCT FROM(r.source_basis->>'periodId')::uuid OR n->>'summary' IS DISTINCT FROM r.content->>'summary' OR n->>'created_by' IS DISTINCT FROM r.prepared_by->>'accountId' OR n->>'source_event_id' IS NOT NULL OR n->>'closing_basis' IS DISTINCT FROM r.source_digest OR n->>'amount_format_version'<>'V4_8' OR(n->>'reversal_of')::uuid IS DISTINCT FROM(CASE WHEN n->>'status'='POSTED' THEN r.expected_reversal_of ELSE NULL END) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_STATE_CONFLICT';END IF;
  IF operation='UPDATE' AND((o-ARRAY['status','version','approved_by','approved_version','number','reversal_of']) IS DISTINCT FROM(n-ARRAY['status','version','approved_by','approved_version','number','reversal_of']) OR o->>'status' IN('POSTED','CANCELLED') OR(n->>'version')::bigint<>(o->>'version')::bigint+1) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_STATE_CONFLICT';END IF;
 ELSE
  SELECT * INTO h FROM plugin_finance_journal WHERE(tenant_id,book_id,year_id,id)=(t,b,y,j);
  SELECT value INTO e FROM jsonb_array_elements(r.generated_lines) WHERE(value->>'lineNo')::integer=(n->>'line_no')::integer;
  IF tab<>'plugin_finance_line' OR operation<>'INSERT' OR h.status<>'DRAFT' OR h.version<>1 OR r.version<>1 OR e IS NULL OR(n->>'account_id')::uuid IS DISTINCT FROM(e->'account'->>'id')::uuid OR(n->>'debit')::numeric IS DISTINCT FROM(e->>'debit')::numeric OR(n->>'credit')::numeric IS DISTINCT FROM(e->>'credit')::numeric OR n->>'memo' IS DISTINCT FROM e->>'memo' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_STATE_CONFLICT';END IF;
 END IF;
END $$;
CREATE FUNCTION plugin_finance_profit_closing_posting_guard(n jsonb) RETURNS void LANGUAGE plpgsql AS $$
DECLARE t uuid=(n->>'tenant_id')::uuid;b uuid=(n->>'book_id')::uuid;y uuid=(n->>'year_id')::uuid;r plugin_finance_profit_closing_run;h plugin_finance_journal;a plugin_finance_profit_closing_approval;pv jsonb;
BEGIN
 SELECT * INTO r FROM plugin_finance_profit_closing_run WHERE(tenant_id,book_id,year_id,journal_id)=(t,b,y,(n->>'journal_id')::uuid);SELECT * INTO h FROM plugin_finance_journal WHERE(tenant_id,book_id,year_id,id)=(t,b,y,r.journal_id);
 SELECT * INTO a FROM plugin_finance_profit_closing_approval WHERE(tenant_id,book_id,year_id,id)=(t,b,y,r.current_approval_id);
 IF r.id IS NULL OR h.status<>'APPROVED' OR r.status<>'APPROVED' OR h.version<>r.version OR h.approved_version<>r.version OR(n->>'journal_version')::bigint<>r.version OR a.id IS NULL OR a.approved_journal_version<>r.version OR a.content_digest<>r.content_digest OR clock_timestamp()>=a.expires_at THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_APPROVAL_STALE';END IF;
 PERFORM plugin_finance_profit_closing_scope(t,b,y,true,r.cutoff);PERFORM plugin_finance_profit_closing_actor(t,b,y,a.approver,ARRAY['OWNER','REVIEWER']);PERFORM plugin_finance_profit_closing_actor(t,b,y,r.prepared_by,ARRAY['OWNER','MAKER']);PERFORM plugin_finance_profit_closing_actor(t,b,y,plugin_finance_journal_person_identity(t,(n->>'posted_by')::uuid,true),ARRAY['OWNER','POSTER']);
 pv=plugin_finance_profit_closing_preview(t,b,y,r.producer_kind,r.cutoff,r.reverses_run_id);
 IF pv->>'outcome'<>'PRODUCIBLE' OR pv->'source'->'basis' IS DISTINCT FROM r.source_basis OR pv->'source'->>'sourceDigest' IS DISTINCT FROM r.source_digest OR pv->'generatedLines' IS DISTINCT FROM r.generated_lines THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_SOURCE_STALE';END IF;
END $$;

CREATE FUNCTION plugin_finance_profit_closing_sidecar_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE n jsonb=to_jsonb(NEW);o jsonb=to_jsonb(OLD);t uuid=(n->>'tenant_id')::uuid;b uuid=(n->>'book_id')::uuid;y uuid=(n->>'year_id')::uuid;r plugin_finance_profit_closing_run;pr plugin_finance_profit_closing_run;pv jsonb;ct jsonb;e plugin_finance_profit_closing_transition;who jsonb;roles text[];newuse boolean=false;
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_STATE_CONFLICT';END IF;
 IF TG_OP='INSERT' AND n?'created_at' AND(n->>'created_at')::timestamptz IS DISTINCT FROM CURRENT_TIMESTAMP THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_STATE_CONFLICT';END IF;
 IF TG_OP='UPDATE' AND TG_TABLE_NAME NOT IN('plugin_finance_profit_closing_run','plugin_finance_profit_closing_source_slot') THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_STATE_CONFLICT';END IF;
 IF TG_TABLE_NAME='plugin_finance_profit_closing_run' THEN
  IF TG_OP='UPDATE' THEN
   IF OLD.status IN('POSTED','CANCELLED') OR NEW.version<>OLD.version+1 OR (o-ARRAY['status','version','current_approval_id','post_id','administrative_termination_id','updated_at']) IS DISTINCT FROM(n-ARRAY['status','version','current_approval_id','post_id','administrative_termination_id','updated_at']) OR NEW.updated_at IS DISTINCT FROM CURRENT_TIMESTAMP THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_STATE_CONFLICT';END IF;
  ELSE
   newuse=true;who=NEW.prepared_by;roles=ARRAY['OWNER','MAKER'];
   IF NEW.create_payload_hash IS DISTINCT FROM plugin_finance_profit_closing_digest(jsonb_build_object('operationId',NEW.operation_id,'producerKind',NEW.producer_kind,'cutoff',NEW.cutoff,'expectedSource',NEW.source_basis,'expectedSourceDigest',NEW.source_digest,'expectedGeneratedDigest',NEW.generated_digest,'predecessorPostedRunId',NEW.predecessor_posted_run_id,'supersedesCancelledAttemptId',NEW.supersedes_cancelled_attempt_id,'expectedSupersededVersion',NEW.expected_superseded_version::text,'reversesRunId',NEW.reverses_run_id,'reason',NEW.content->>'reason')) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='IDEMPOTENCY_CONFLICT';END IF;
   SELECT * INTO pr FROM plugin_finance_profit_closing_run WHERE(tenant_id,book_id,year_id,producer_kind,cutoff)=(t,b,y,NEW.producer_kind,NEW.cutoff) AND reverses_run_id IS NOT DISTINCT FROM NEW.reverses_run_id ORDER BY created_at DESC,id DESC LIMIT 1;
   IF FOUND AND pr.status='CANCELLED' AND(NEW.supersedes_cancelled_attempt_id IS DISTINCT FROM pr.id OR NEW.expected_superseded_version IS DISTINCT FROM pr.version) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_SOURCE_ALREADY_CLAIMED';END IF;
   IF NEW.status<>'DRAFT' OR NEW.version<>1 OR NEW.content_version<>1 OR NEW.current_approval_id IS NOT NULL OR NEW.post_id IS NOT NULL OR NEW.administrative_termination_id IS NOT NULL OR NEW.last_editor IS DISTINCT FROM NEW.prepared_by OR NEW.updated_at IS DISTINCT FROM CURRENT_TIMESTAMP THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_STATE_CONFLICT';END IF;
   PERFORM plugin_finance_profit_closing_scope(t,b,y,true,NEW.cutoff);
   pv=plugin_finance_profit_closing_preview(t,b,y,NEW.producer_kind,NEW.cutoff,NEW.reverses_run_id);
   IF pv->>'outcome'<>'PRODUCIBLE' OR NEW.source_snapshot IS DISTINCT FROM pv->'source' OR NEW.source_basis IS DISTINCT FROM pv->'source'->'basis' OR NEW.source_digest IS DISTINCT FROM pv->'source'->>'sourceDigest' OR NEW.generated_lines IS DISTINCT FROM pv->'generatedLines' OR NEW.generated_digest IS DISTINCT FROM pv->>'generatedDigest' OR NEW.predecessor_posted_run_id IS DISTINCT FROM(pv->'source'->>'latestPostedRunId')::uuid THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_SOURCE_STALE';END IF;
   IF NEW.reverses_run_id IS NOT NULL THEN SELECT * INTO pr FROM plugin_finance_profit_closing_run WHERE(tenant_id,book_id,year_id,id)=(t,b,y,NEW.reverses_run_id);IF NEW.expected_reversal_of IS DISTINCT FROM pr.journal_id THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_REVERSAL_DEPENDENCY';END IF;END IF;
   ct=jsonb_build_object('format','CLOSING_PERSON_CONTENT_PG_V1','producerKind',NEW.producer_kind,'rawType',CASE NEW.producer_kind WHEN 'PROFIT_LOSS' THEN 'PROFIT_CLOSE' WHEN 'YEAR_END' THEN 'YEAR_END' ELSE 'REVERSAL' END,'cutoff',NEW.cutoff,'summary',CASE NEW.producer_kind WHEN 'PROFIT_LOSS' THEN '损益结转' WHEN 'YEAR_END' THEN '本年利润转未分配利润' ELSE '结转全额冲回' END,'currency','CNY','sourceDigest',NEW.source_digest,'generatedLines',NEW.generated_lines,'documents','[]'::jsonb,'reversesJournalId',NEW.expected_reversal_of,'reason',NEW.content->>'reason');
   IF NEW.content IS DISTINCT FROM ct OR NEW.content_digest IS DISTINCT FROM plugin_finance_profit_closing_digest(ct) OR jsonb_typeof(NEW.content->'reason') IS DISTINCT FROM 'string' OR length(NEW.content->>'reason') NOT BETWEEN 1 AND 1000 THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='INVALID_INPUT';END IF;
   IF NEW.supersedes_cancelled_attempt_id IS NOT NULL THEN
    SELECT * INTO pr FROM plugin_finance_profit_closing_run WHERE(tenant_id,book_id,year_id,id)=(t,b,y,NEW.supersedes_cancelled_attempt_id);
    IF NOT FOUND OR pr.status<>'CANCELLED' OR pr.version IS DISTINCT FROM NEW.expected_superseded_version OR pr.producer_kind<>NEW.producer_kind OR pr.cutoff<>NEW.cutoff OR pr.reverses_run_id IS DISTINCT FROM NEW.reverses_run_id THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_STATE_CONFLICT';END IF;
   END IF;
  END IF;
 ELSIF TG_TABLE_NAME='plugin_finance_profit_closing_source_slot' THEN
  IF TG_OP='UPDATE' THEN
   SELECT * INTO r FROM plugin_finance_profit_closing_run WHERE(tenant_id,book_id,year_id,id)=(t,b,y,OLD.latest_run_id);SELECT * INTO pr FROM plugin_finance_profit_closing_run WHERE(tenant_id,book_id,year_id,id)=(t,b,y,NEW.latest_run_id);
   IF NEW IS NOT DISTINCT FROM OLD THEN RETURN NEW;END IF;
   IF (o-'latest_run_id') IS DISTINCT FROM(n-'latest_run_id') OR r.status<>'CANCELLED' OR pr.supersedes_cancelled_attempt_id IS DISTINCT FROM r.id OR pr.expected_superseded_version IS DISTINCT FROM r.version OR pr.source_slot_id<>NEW.id THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_SOURCE_ALREADY_CLAIMED';END IF;
  END IF;
 ELSIF TG_TABLE_NAME='plugin_finance_profit_closing_approval' THEN
  SELECT * INTO r FROM plugin_finance_profit_closing_run WHERE(tenant_id,book_id,year_id,id)=(t,b,y,NEW.run_id);who=NEW.approver;roles=ARRAY['OWNER','REVIEWER'];newuse=true;
  IF r.status<>'SUBMITTED' OR NEW.approved_journal_version<>r.version+1 OR NEW.journal_id<>r.journal_id OR NEW.content_version<>1 OR NEW.content_digest<>r.content_digest OR NEW.source_digest<>r.source_digest OR NEW.decided_at IS DISTINCT FROM CURRENT_TIMESTAMP OR NEW.expires_at<=clock_timestamp() OR NEW.expires_at>CURRENT_TIMESTAMP+interval '7 days' OR who->>'personId'=r.prepared_by->>'personId' OR who->>'personId'=r.last_editor->>'personId' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_APPROVAL_STALE';END IF;
  PERFORM plugin_finance_profit_closing_actor(t,b,y,r.prepared_by,ARRAY['OWNER','MAKER']);pv=plugin_finance_profit_closing_preview(t,b,y,r.producer_kind,r.cutoff,r.reverses_run_id);
  IF pv->'source'->'basis' IS DISTINCT FROM r.source_basis OR pv->'generatedLines' IS DISTINCT FROM r.generated_lines THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_SOURCE_STALE';END IF;
 ELSIF TG_TABLE_NAME='plugin_finance_profit_closing_transition' THEN
  SELECT * INTO r FROM plugin_finance_profit_closing_run WHERE(tenant_id,book_id,year_id,id)=(t,b,y,NEW.run_id);who=NEW.actor;roles=CASE WHEN NEW.action='POST' THEN ARRAY['OWNER','POSTER'] WHEN NEW.action IN('APPROVE','REJECT','ADMIN_TERMINATE') THEN ARRAY['OWNER','REVIEWER'] ELSE ARRAY['OWNER','MAKER'] END;
  IF NEW.journal_id<>r.journal_id OR NEW.content_version<>1 OR NEW.content_digest<>r.content_digest OR NEW.journal_version<>r.version OR NEW.to_state<>r.status THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_STATE_CONFLICT';END IF;
  IF NEW.action='CREATE' THEN
   IF NEW.from_state IS NOT NULL OR NEW.to_state<>'DRAFT' OR NEW.actor IS DISTINCT FROM r.prepared_by THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_STATE_CONFLICT';END IF;
  ELSE
   SELECT * INTO e FROM plugin_finance_profit_closing_transition WHERE(tenant_id,book_id,year_id,run_id,journal_version)=(t,b,y,r.id,NEW.previous_version);
   IF NOT FOUND OR NEW.from_state IS DISTINCT FROM e.to_state THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_STATE_CONFLICT';END IF;
  END IF;
  IF(NEW.action='SUBMIT' AND(NEW.from_state<>'DRAFT' OR NEW.to_state<>'SUBMITTED')) OR(NEW.action='APPROVE' AND(NEW.from_state<>'SUBMITTED' OR NEW.to_state<>'APPROVED')) OR(NEW.action='POST' AND(NEW.from_state<>'APPROVED' OR NEW.to_state<>'POSTED')) OR(NEW.action='REJECT' AND(NEW.from_state NOT IN('SUBMITTED','APPROVED') OR NEW.to_state<>'DRAFT')) OR(NEW.action='WITHDRAW' AND(NEW.from_state<>'SUBMITTED' OR NEW.to_state<>'DRAFT')) OR(NEW.action IN('CANCEL','ADMIN_TERMINATE') AND(NEW.from_state<>'DRAFT' OR NEW.to_state<>'CANCELLED')) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_STATE_CONFLICT';END IF;
  IF NEW.action IN('CREATE','SUBMIT','WITHDRAW','CANCEL') AND who->>'accountId'<>r.prepared_by->>'accountId' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SCOPE_DENIED';END IF;
  IF NEW.action IN('APPROVE','REJECT','ADMIN_TERMINATE') AND(who->>'personId'=r.prepared_by->>'personId' OR who->>'personId'=r.last_editor->>'personId') THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SELF_APPROVAL_FORBIDDEN';END IF;
  newuse=NEW.action IN('CREATE','SUBMIT','APPROVE','POST');
  IF NEW.action='SUBMIT' THEN
   PERFORM plugin_finance_profit_closing_actor(t,b,y,r.prepared_by,ARRAY['OWNER','MAKER']);pv=plugin_finance_profit_closing_preview(t,b,y,r.producer_kind,r.cutoff,r.reverses_run_id);
   IF pv->'source'->'basis' IS DISTINCT FROM r.source_basis OR pv->'generatedLines' IS DISTINCT FROM r.generated_lines THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_SOURCE_STALE';END IF;
  END IF;
 ELSIF TG_TABLE_NAME='plugin_finance_profit_closing_post' THEN
  SELECT * INTO r FROM plugin_finance_profit_closing_run WHERE(tenant_id,book_id,year_id,id)=(t,b,y,NEW.run_id);who=NEW.poster;roles=ARRAY['OWNER','POSTER'];newuse=true;
  IF NEW.posted_at IS DISTINCT FROM CURRENT_TIMESTAMP OR NEW.journal_id<>r.journal_id OR NEW.content_digest<>r.content_digest OR NEW.content_version<>1 OR NEW.source_digest<>r.source_digest OR NEW.included_source_posting_seq<>(r.source_basis->>'includedPostingSeq')::bigint THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_STATE_CONFLICT';END IF;
 ELSIF TG_TABLE_NAME='plugin_finance_profit_closing_termination' THEN
  SELECT * INTO r FROM plugin_finance_profit_closing_run WHERE(tenant_id,book_id,year_id,id)=(t,b,y,NEW.run_id);who=NEW.actor;roles=ARRAY['OWNER','REVIEWER'];
  IF r.status<>'DRAFT' OR NEW.journal_version<>r.version+1 OR NEW.journal_id<>r.journal_id OR NEW.content_digest<>r.content_digest OR NEW.content_version<>1 OR who->>'personId'=r.prepared_by->>'personId' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_STATE_CONFLICT';END IF;
 END IF;
 PERFORM plugin_finance_profit_closing_scope(t,b,y,newuse,CASE WHEN TG_TABLE_NAME='plugin_finance_profit_closing_run' THEN(n->>'cutoff')::date ELSE r.cutoff END);
 IF who IS NOT NULL THEN PERFORM plugin_finance_profit_closing_actor(t,b,y,who,roles);END IF;
 RETURN NEW;
END $$;

CREATE FUNCTION plugin_finance_profit_closing_assert(t uuid,b uuid,y uuid,rid uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE r plugin_finance_profit_closing_run;h plugin_finance_journal;e plugin_finance_profit_closing_transition;ap plugin_finance_profit_closing_approval;p plugin_finance_profit_closing_post;ef plugin_finance_profit_closing_effect;rc plugin_finance_profit_closing_receipt;sl plugin_finance_profit_closing_source_slot;re plugin_finance_profit_closing_reversal_effect;orig plugin_finance_profit_closing_run;opost plugin_finance_profit_closing_post;ls jsonb;z jsonb;ol jsonb;pd text;ek text;
BEGIN
 SELECT * INTO r FROM plugin_finance_profit_closing_run WHERE(tenant_id,book_id,year_id,id)=(t,b,y,rid);
 IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_STATE_CONFLICT';END IF;
 IF plugin_finance_journal_person_lock(t,b,y)<>'JOURNAL_PERSON_SOD' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='JOURNAL_PERSON_MODE_REQUIRED';END IF;
 SELECT * INTO h FROM plugin_finance_journal WHERE(tenant_id,book_id,year_id,id)=(t,b,y,r.journal_id);
 SELECT * INTO e FROM plugin_finance_profit_closing_transition WHERE(tenant_id,book_id,year_id,run_id,journal_version)=(t,b,y,rid,r.version);
 IF h.id IS NULL OR h.version<>r.version OR h.status<>r.status OR e.id IS NULL OR e.journal_id<>h.id OR e.to_state<>h.status OR e.content_digest<>r.content_digest OR e.content_version<>1 OR r.version<>(SELECT count(*) FROM plugin_finance_profit_closing_transition WHERE(tenant_id,book_id,year_id,run_id)=(t,b,y,rid)) OR r.updated_at IS DISTINCT FROM e.created_at OR r.last_editor IS DISTINCT FROM r.prepared_by OR r.content_digest IS DISTINCT FROM plugin_finance_profit_closing_digest(r.content) OR r.generated_digest IS DISTINCT FROM plugin_finance_profit_closing_digest(r.generated_lines) OR r.content->'generatedLines' IS DISTINCT FROM r.generated_lines OR r.source_basis IS DISTINCT FROM r.source_snapshot->'basis' OR r.source_digest IS DISTINCT FROM plugin_finance_profit_closing_digest(jsonb_build_object('basis',r.source_basis,'sourceRows',r.source_snapshot->'sourceRows')) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_STATE_CONFLICT';END IF;
 PERFORM plugin_finance_profit_closing_snapshot(t,b,y,h.id);
 IF NOT EXISTS(SELECT 1 FROM plugin_finance_profit_closing_transition c WHERE(c.tenant_id,c.book_id,c.year_id,c.run_id,c.journal_version)=(t,b,y,rid,1) AND c.action='CREATE' AND c.actor=r.prepared_by AND c.created_at=r.created_at) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_STATE_CONFLICT';END IF;
 SELECT * INTO sl FROM plugin_finance_profit_closing_source_slot WHERE(tenant_id,book_id,year_id,id)=(t,b,y,r.source_slot_id);
 IF NOT FOUND OR sl.producer_kind<>r.producer_kind OR sl.cutoff<>r.cutoff OR sl.source_digest<>r.source_digest OR sl.reverses_run_id IS DISTINCT FROM r.reverses_run_id OR(sl.latest_run_id<>r.id AND r.status<>'CANCELLED') THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_SOURCE_ALREADY_CLAIMED';END IF;
 IF r.status IN('APPROVED','POSTED') THEN
  SELECT * INTO ap FROM plugin_finance_profit_closing_approval WHERE(tenant_id,book_id,year_id,id)=(t,b,y,r.current_approval_id);
  IF NOT FOUND OR ap.run_id<>rid OR ap.journal_id<>h.id OR ap.content_digest<>r.content_digest OR ap.source_digest<>r.source_digest OR ap.content_version<>1 OR h.approved_by IS DISTINCT FROM(ap.approver->>'accountId')::uuid OR h.approved_version IS DISTINCT FROM ap.approved_journal_version THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_APPROVAL_STALE';END IF;

  IF r.status='APPROVED' AND(e.action<>'APPROVE' OR e.approval_id IS DISTINCT FROM ap.id OR ap.approved_journal_version<>r.version OR h.approved_version IS DISTINCT FROM h.version OR e.actor IS DISTINCT FROM ap.approver) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_APPROVAL_STALE';END IF;
 ELSE IF r.current_approval_id IS NOT NULL OR h.approved_by IS NOT NULL OR h.approved_version IS NOT NULL THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_STATE_CONFLICT';END IF;END IF;
 IF r.status='POSTED' THEN
  SELECT * INTO p FROM plugin_finance_profit_closing_post WHERE(tenant_id,book_id,year_id,id)=(t,b,y,r.post_id);
  IF NOT FOUND OR p.run_id<>rid OR p.journal_id<>h.id OR p.approval_id<>ap.id OR p.posted_journal_version<>h.version OR p.content_digest<>r.content_digest OR p.source_digest<>r.source_digest OR p.content_version<>1 OR p.included_source_posting_seq<>(r.source_basis->>'includedPostingSeq')::bigint OR e.action<>'POST' OR e.post_id IS DISTINCT FROM p.id OR e.actor IS DISTINCT FROM p.poster OR e.created_at IS DISTINCT FROM p.posted_at OR h.number IS NULL OR h.number<=0 OR ap.approved_journal_version<>h.version-1 OR NOT EXISTS(SELECT 1 FROM plugin_finance_posting po WHERE(po.tenant_id,po.book_id,po.year_id,po.journal_id,po.posting_seq,po.digest)=(t,b,y,h.id,p.posting_seq,p.posting_digest) AND po.journal_version=ap.approved_journal_version AND po.posted_by=(p.poster->>'accountId')::uuid) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_STATE_CONFLICT';END IF;
  pd=plugin_finance_profit_closing_digest(jsonb_build_object('format','CLOSING_POST_PG_V1','runId',rid,'contentDigest',r.content_digest,'approvalId',ap.id,'approvedJournalVersion',ap.approved_journal_version::text,'sourceDigest',r.source_digest));
  IF pd<>p.posting_digest THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_STATE_CONFLICT';END IF;
  SELECT * INTO ef FROM plugin_finance_profit_closing_effect WHERE(tenant_id,book_id,year_id,id)=(t,b,y,p.effect_id);SELECT * INTO rc FROM plugin_finance_profit_closing_receipt WHERE(tenant_id,book_id,year_id,id)=(t,b,y,p.receipt_id);
  ek=plugin_finance_profit_closing_digest(jsonb_build_array(t,b,y,'GL_PROFIT_CLOSING_V1','FINANCE_PROFIT_CLOSING_V1',r.operation_id,'POST_CLOSING_JOURNAL'));
  IF ef.id IS NULL OR rc.id IS NULL OR ef.run_id<>rid OR ef.journal_id<>h.id OR ef.post_id<>p.id OR ef.operation_id<>r.operation_id OR ef.effect_key<>ek OR ef.payload_digest<>r.content_digest OR rc.run_id<>rid OR rc.journal_id<>h.id OR rc.post_id<>p.id OR rc.effect_id<>ef.id OR rc.effect_key<>ef.effect_key OR rc.payload_digest<>ef.payload_digest OR ef.created_at<>p.posted_at OR rc.created_at<>p.posted_at THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_STATE_CONFLICT';END IF;
  PERFORM plugin_finance_profit_closing_historical_source(t,b,y,rid,p.posting_seq);
  IF r.producer_kind='TRANSFER_REVERSAL' THEN
   SELECT * INTO re FROM plugin_finance_profit_closing_reversal_effect WHERE(tenant_id,book_id,year_id,reversal_run_id)=(t,b,y,rid);SELECT * INTO orig FROM plugin_finance_profit_closing_run WHERE(tenant_id,book_id,year_id,id)=(t,b,y,r.reverses_run_id);SELECT * INTO opost FROM plugin_finance_profit_closing_post WHERE(tenant_id,book_id,year_id,id)=(t,b,y,orig.post_id);
   IF re.id IS NULL OR orig.status<>'POSTED' OR orig.producer_kind='TRANSFER_REVERSAL' OR orig.cutoff<>r.cutoff OR re.original_run_id<>orig.id OR re.original_post_id<>orig.post_id OR re.reversal_post_id<>p.id OR h.reversal_of<>orig.journal_id OR opost.posting_seq>=p.posting_seq OR jsonb_array_length(r.generated_lines)<>jsonb_array_length(orig.generated_lines) OR re.created_at<>p.posted_at THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_REVERSAL_DEPENDENCY';END IF;
   FOR z IN SELECT value FROM jsonb_array_elements(r.generated_lines) LOOP
    SELECT value INTO ol FROM jsonb_array_elements(orig.generated_lines) WHERE value->>'lineNo'=z->>'lineNo';
    IF ol IS NULL OR z->'account'->>'id' IS DISTINCT FROM ol->'account'->>'id' OR z->>'debit' IS DISTINCT FROM ol->>'credit' OR z->>'credit' IS DISTINCT FROM ol->>'debit' OR((z->'account')-ARRAY['code','name','enabled']) IS DISTINCT FROM((ol->'account')-ARRAY['code','name','enabled']) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_REVERSAL_DEPENDENCY';END IF;
   END LOOP;
   IF EXISTS(SELECT 1 FROM plugin_finance_profit_closing_run later JOIN plugin_finance_profit_closing_post lp ON(lp.tenant_id,lp.book_id,lp.year_id,lp.run_id)=(later.tenant_id,later.book_id,later.year_id,later.id) WHERE(later.tenant_id,later.book_id,later.year_id)=(t,b,y) AND later.producer_kind IN('PROFIT_LOSS','YEAR_END') AND lp.posting_seq>opost.posting_seq AND lp.posting_seq<p.posting_seq AND NOT EXISTS(SELECT 1 FROM plugin_finance_profit_closing_reversal_effect le JOIN plugin_finance_profit_closing_post lrp ON(lrp.tenant_id,lrp.book_id,lrp.year_id,lrp.id)=(le.tenant_id,le.book_id,le.year_id,le.reversal_post_id) WHERE(le.tenant_id,le.book_id,le.year_id,le.original_run_id)=(t,b,y,later.id) AND lrp.posting_seq<p.posting_seq)) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_REVERSAL_DEPENDENCY';END IF;
  ELSIF EXISTS(SELECT 1 FROM plugin_finance_profit_closing_reversal_effect WHERE(tenant_id,book_id,year_id,reversal_run_id)=(t,b,y,rid)) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_REVERSAL_DEPENDENCY';END IF;
 ELSIF r.post_id IS NOT NULL OR h.number IS NOT NULL OR EXISTS(SELECT 1 FROM plugin_finance_profit_closing_post WHERE(tenant_id,book_id,year_id,run_id)=(t,b,y,rid)) OR EXISTS(SELECT 1 FROM plugin_finance_posting WHERE(tenant_id,book_id,year_id,journal_id)=(t,b,y,h.id)) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_STATE_CONFLICT';END IF;
 IF(r.administrative_termination_id IS NOT NULL) IS DISTINCT FROM(e.action='ADMIN_TERMINATE') OR e.termination_id IS DISTINCT FROM r.administrative_termination_id THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_STATE_CONFLICT';END IF;
END $$;
CREATE FUNCTION plugin_finance_profit_closing_final() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE n jsonb=to_jsonb(NEW);t uuid=(n->>'tenant_id')::uuid;b uuid=(n->>'book_id')::uuid;y uuid=(n->>'year_id')::uuid;rid uuid;r plugin_finance_profit_closing_run;e plugin_finance_profit_closing_transition;p plugin_finance_profit_closing_post;
BEGIN
 IF TG_TABLE_NAME='plugin_finance_profit_closing_run' THEN rid=(n->>'id')::uuid;ELSIF TG_TABLE_NAME='plugin_finance_profit_closing_source_slot' THEN rid=(n->>'latest_run_id')::uuid;ELSIF TG_TABLE_NAME='plugin_finance_profit_closing_reversal_effect' THEN rid=(n->>'reversal_run_id')::uuid;ELSE rid=(n->>'run_id')::uuid;END IF;
 PERFORM plugin_finance_profit_closing_assert(t,b,y,rid);
 SELECT * INTO r FROM plugin_finance_profit_closing_run WHERE(tenant_id,book_id,year_id,id)=(t,b,y,rid);
 IF TG_TABLE_NAME='plugin_finance_profit_closing_source_slot' THEN
  IF r.source_slot_id IS DISTINCT FROM(n->>'id')::uuid OR r.producer_kind<>n->>'producer_kind' OR r.source_digest<>n->>'source_digest' OR r.cutoff<>(n->>'cutoff')::date OR r.reverses_run_id IS DISTINCT FROM(n->>'reverses_run_id')::uuid THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_SOURCE_ALREADY_CLAIMED';END IF;
 ELSIF TG_TABLE_NAME='plugin_finance_profit_closing_approval' THEN
  SELECT * INTO e FROM plugin_finance_profit_closing_transition WHERE(tenant_id,book_id,year_id,run_id,journal_version)=(t,b,y,rid,(n->>'approved_journal_version')::bigint);
  IF e.action<>'APPROVE' OR e.approval_id IS DISTINCT FROM(n->>'id')::uuid OR e.journal_id<>r.journal_id OR e.content_digest<>n->>'content_digest' OR e.content_version<>(n->>'content_version')::bigint OR e.actor IS DISTINCT FROM n->'approver' OR e.reason IS DISTINCT FROM n->>'reason' OR e.created_at IS DISTINCT FROM(n->>'decided_at')::timestamptz THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_APPROVAL_STALE';END IF;
 ELSIF TG_TABLE_NAME='plugin_finance_profit_closing_termination' THEN
  SELECT * INTO e FROM plugin_finance_profit_closing_transition WHERE(tenant_id,book_id,year_id,run_id,journal_version)=(t,b,y,rid,(n->>'journal_version')::bigint);
  IF e.action<>'ADMIN_TERMINATE' OR e.termination_id IS DISTINCT FROM(n->>'id')::uuid OR e.actor IS DISTINCT FROM n->'actor' OR e.reason IS DISTINCT FROM n->>'reason' OR e.created_at IS DISTINCT FROM(n->>'created_at')::timestamptz THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_STATE_CONFLICT';END IF;
 ELSIF TG_TABLE_NAME IN('plugin_finance_profit_closing_effect','plugin_finance_profit_closing_receipt') THEN
  SELECT * INTO p FROM plugin_finance_profit_closing_post WHERE(tenant_id,book_id,year_id,id)=(t,b,y,(n->>'post_id')::uuid);
  IF r.post_id IS DISTINCT FROM p.id OR p.run_id<>rid OR p.journal_id IS DISTINCT FROM(n->>'journal_id')::uuid OR(TG_TABLE_NAME='plugin_finance_profit_closing_effect' AND p.effect_id IS DISTINCT FROM(n->>'id')::uuid) OR(TG_TABLE_NAME='plugin_finance_profit_closing_receipt' AND p.receipt_id IS DISTINCT FROM(n->>'id')::uuid) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_STATE_CONFLICT';END IF;
 END IF;
 RETURN NULL;
END $$;
CREATE FUNCTION plugin_finance_profit_closing_visible_change() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='INSERT' OR NEW IS DISTINCT FROM OLD THEN UPDATE plugin_finance_profit_closing_guard SET generation=generation+1 WHERE(tenant_id,book_id,year_id)=(NEW.tenant_id,NEW.book_id,NEW.year_id);END IF;RETURN NULL;
END $$;
CREATE UNIQUE INDEX finance_profit_closing_one_superseding ON plugin_finance_profit_closing_run(tenant_id,book_id,year_id,supersedes_cancelled_attempt_id) WHERE supersedes_cancelled_attempt_id IS NOT NULL;
DO $$ DECLARE tbl text;BEGIN
 FOREACH tbl IN ARRAY ARRAY['source_slot','run','approval','post','effect','receipt','termination','transition','reversal_effect','request','audit'] LOOP
  EXECUTE format('CREATE TRIGGER finance_profit_closing_%s_write BEFORE INSERT OR UPDATE OR DELETE ON plugin_finance_profit_closing_%s FOR EACH ROW EXECUTE FUNCTION plugin_finance_profit_closing_sidecar_guard()',tbl,tbl);
  IF tbl NOT IN('request','audit') THEN EXECUTE format('CREATE CONSTRAINT TRIGGER finance_profit_closing_%s_final AFTER INSERT OR UPDATE ON plugin_finance_profit_closing_%s DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION plugin_finance_profit_closing_final()',tbl,tbl);END IF;
  IF tbl IN('source_slot','run','approval','post','termination','reversal_effect') THEN EXECUTE format('CREATE TRIGGER finance_profit_closing_%s_generation AFTER INSERT OR UPDATE ON plugin_finance_profit_closing_%s FOR EACH ROW EXECUTE FUNCTION plugin_finance_profit_closing_visible_change()',tbl,tbl);END IF;
 END LOOP;
END $$;
CREATE FUNCTION plugin_finance_profit_closing_linked(tab text,q jsonb) RETURNS boolean LANGUAGE sql STABLE AS $$ SELECT EXISTS(SELECT 1 FROM plugin_finance_profit_closing_run r WHERE(r.tenant_id,r.book_id,r.year_id,r.journal_id)=((q->>'tenant_id')::uuid,(q->>'book_id')::uuid,(q->>'year_id')::uuid,CASE WHEN tab='plugin_finance_journal' THEN(q->>'id')::uuid ELSE(q->>'journal_id')::uuid END)) $$;
CREATE FUNCTION plugin_finance_profit_closing_watermark(t uuid,b uuid,y uuid,seq bigint,old_seq bigint) RETURNS boolean LANGUAGE plpgsql AS $$ DECLARE r plugin_finance_profit_closing_run;BEGIN
 SELECT q.* INTO r FROM plugin_finance_profit_closing_post p JOIN plugin_finance_posting po ON(po.tenant_id,po.book_id,po.year_id,po.journal_id,po.posting_seq,po.digest)=(p.tenant_id,p.book_id,p.year_id,p.journal_id,p.posting_seq,p.posting_digest) JOIN plugin_finance_profit_closing_run q ON(q.tenant_id,q.book_id,q.year_id,q.id)=(p.tenant_id,p.book_id,p.year_id,p.run_id) WHERE(p.tenant_id,p.book_id,p.year_id,p.posting_seq)=(t,b,y,seq);
 IF NOT FOUND THEN RETURN false;END IF;
 IF seq<>old_seq+1 THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PROFIT_CLOSING_STATE_CONFLICT';END IF;
 PERFORM plugin_finance_profit_closing_assert(t,b,y,r.id);RETURN true;
END $$;

-- Exact typed dispatch prefixes; the original V14 NORMAL body after BEGIN is retained verbatim.
CREATE OR REPLACE FUNCTION plugin_finance_journal_person_snapshot(t uuid,b uuid,y uuid,j uuid) RETURNS jsonb LANGUAGE plpgsql STABLE AS $$
DECLARE h plugin_finance_journal; result jsonb; n integer;
BEGIN
 IF EXISTS(SELECT 1 FROM plugin_finance_profit_closing_run WHERE(tenant_id,book_id,year_id,journal_id)=(t,b,y,j)) THEN RETURN plugin_finance_profit_closing_snapshot(t,b,y,j);END IF;
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
CREATE OR REPLACE FUNCTION plugin_finance_journal_person_assert(t uuid,b uuid,y uuid,j uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE h plugin_finance_journal;cur plugin_finance_journal_person_current;c plugin_finance_journal_person_content;e plugin_finance_journal_person_transition;ap plugin_finance_journal_person_approval;pr plugin_finance_journal_person_post;po plugin_finance_posting;tr plugin_finance_journal_person_termination;
BEGIN
 IF EXISTS(SELECT 1 FROM plugin_finance_profit_closing_run WHERE(tenant_id,book_id,year_id,journal_id)=(t,b,y,j)) THEN PERFORM plugin_finance_profit_closing_assert(t,b,y,(SELECT id FROM plugin_finance_profit_closing_run WHERE(tenant_id,book_id,year_id,journal_id)=(t,b,y,j)));RETURN;END IF;
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
CREATE OR REPLACE FUNCTION plugin_finance_journal_person_raw_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r jsonb;h plugin_finance_journal; old_mode text; new_mode text;
BEGIN
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
CREATE OR REPLACE FUNCTION plugin_finance_journal_person_receipt_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE h plugin_finance_journal;c plugin_finance_journal_person_content;cur plugin_finance_journal_person_current;e plugin_finance_journal_person_transition;ap plugin_finance_journal_person_approval;po plugin_finance_posting;who jsonb;
BEGIN
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
 IF plugin_finance_profit_closing_linked(TG_TABLE_NAME,r) THEN
  IF TG_TABLE_NAME IN('plugin_finance_line','plugin_finance_document') THEN RETURN NULL;END IF;
  PERFORM plugin_finance_profit_closing_assert((r->>'tenant_id')::uuid,(r->>'book_id')::uuid,(r->>'year_id')::uuid,(SELECT id FROM plugin_finance_profit_closing_run WHERE(tenant_id,book_id,year_id,journal_id)=((r->>'tenant_id')::uuid,(r->>'book_id')::uuid,(r->>'year_id')::uuid,CASE WHEN TG_TABLE_NAME='plugin_finance_journal' THEN(r->>'id')::uuid ELSE(r->>'journal_id')::uuid END)));RETURN NULL;
 END IF;
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
CREATE OR REPLACE FUNCTION plugin_finance_journal_person_watermark_final() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.posting_seq IS DISTINCT FROM OLD.posting_seq AND plugin_finance_profit_closing_watermark(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.posting_seq,OLD.posting_seq) THEN RETURN NULL;END IF;
 IF plugin_finance_journal_person_lock(NEW.tenant_id,NEW.book_id,NEW.year_id)='JOURNAL_PERSON_SOD' AND NEW.posting_seq IS DISTINCT FROM OLD.posting_seq THEN
  PERFORM 1 FROM plugin_finance_posting p JOIN plugin_finance_journal_person_post r ON(r.tenant_id,r.book_id,r.year_id,r.journal_id,r.posting_seq,r.posting_digest)=(p.tenant_id,p.book_id,p.year_id,p.journal_id,p.posting_seq,p.digest) WHERE(p.tenant_id,p.book_id,p.year_id,p.posting_seq)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.posting_seq);
  IF NOT FOUND THEN RAISE EXCEPTION 'JOURNAL_PERSON_WATERMARK_WITHOUT_POST'; END IF;
 END IF;
 RETURN NULL;
END $$;

-- Nullable relation fields must form one true pair; an ordinary producer cannot claim a raw reversal.
ALTER TABLE plugin_finance_profit_closing_run ADD CONSTRAINT finance_profit_closing_reversal_pair_ck CHECK((reverses_run_id IS NULL)=(expected_reversal_of IS NULL));
