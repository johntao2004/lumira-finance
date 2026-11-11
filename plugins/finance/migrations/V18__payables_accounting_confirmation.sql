-- AP01 accounting recognition. V1-V17 are published and immutable.
-- Candidate V18: real accounting consumer, never a settlement or payment producer.
ALTER TABLE plugin_finance_journal DROP CONSTRAINT plugin_finance_journal_type_check;
ALTER TABLE plugin_finance_journal ADD CONSTRAINT plugin_finance_journal_type_check CHECK(type IN('NORMAL','REVERSAL','PROFIT_CLOSE','YEAR_END','AP_EXPENSE','AP_EXPENSE_REVERSAL'));

CREATE TABLE plugin_finance_ap_guard(
 tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,
 epoch uuid NOT NULL DEFAULT gen_random_uuid(),generation bigint NOT NULL DEFAULT 0 CHECK(generation>=0),
 PRIMARY KEY(tenant_id,book_id,year_id),
 FOREIGN KEY(tenant_id,book_id,year_id) REFERENCES plugin_finance_year(tenant_id,book_id,id));
INSERT INTO plugin_finance_ap_guard(tenant_id,book_id,year_id) SELECT tenant_id,book_id,id FROM plugin_finance_year;
CREATE FUNCTION plugin_finance_ap_new_year() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 INSERT INTO plugin_finance_ap_guard(tenant_id,book_id,year_id) VALUES(NEW.tenant_id,NEW.book_id,NEW.id);RETURN NEW;
END $$;
CREATE TRIGGER finance_ap_new_year AFTER INSERT ON plugin_finance_year FOR EACH ROW EXECUTE FUNCTION plugin_finance_ap_new_year();

CREATE TABLE plugin_finance_ap_basis(
 tenant_id uuid NOT NULL,legal_entity_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,id uuid NOT NULL,
 document_id uuid NOT NULL,previous_basis_id uuid,version bigint NOT NULL DEFAULT 1 CHECK(version IN(1,2)),
 status text NOT NULL CHECK(status IN('PENDING','APPROVED','REJECTED','CANCELLED')),
 face_gross numeric(28,8) NOT NULL CHECK(face_gross>=0 AND face_gross=round(face_gross,2)),
 capacity numeric(28,8) NOT NULL CHECK(capacity>=0 AND capacity=round(capacity,2) AND capacity<=face_gross),
 content jsonb NOT NULL,content_digest text NOT NULL CHECK(content_digest~'^[a-f0-9]{64}$'),
 prepared_by jsonb NOT NULL,created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 decided_by jsonb,decided_at timestamptz,decision_reason text,
 PRIMARY KEY(tenant_id,book_id,year_id,id),UNIQUE(tenant_id,book_id,year_id,document_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,document_id) REFERENCES plugin_finance_evidence_document(tenant_id,book_id,year_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,document_id,previous_basis_id) REFERENCES plugin_finance_ap_basis(tenant_id,book_id,year_id,document_id,id),
 CHECK((status='PENDING' AND version=1 AND decided_by IS NULL AND decided_at IS NULL AND decision_reason IS NULL) OR(status<>'PENDING' AND version=2 AND decided_by IS NOT NULL AND decided_at IS NOT NULL AND decision_reason IS NOT NULL AND length(decision_reason) BETWEEN 1 AND 1000)));
CREATE UNIQUE INDEX finance_ap_basis_pending ON plugin_finance_ap_basis(tenant_id,book_id,year_id,document_id) WHERE status='PENDING';
CREATE UNIQUE INDEX finance_ap_basis_successor ON plugin_finance_ap_basis(tenant_id,book_id,year_id,document_id,previous_basis_id) WHERE previous_basis_id IS NOT NULL;
CREATE UNIQUE INDEX finance_ap_basis_root ON plugin_finance_ap_basis(tenant_id,book_id,year_id,document_id) WHERE previous_basis_id IS NULL;

CREATE TABLE plugin_finance_ap_source_slot(
 tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,id uuid NOT NULL,
 parent_allocation_id uuid NOT NULL,chain_id uuid NOT NULL,version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 latest_attempt_id uuid,active_original_id uuid,active_usage_id uuid,active_open_item_id uuid,
 PRIMARY KEY(tenant_id,book_id,year_id,id),UNIQUE(tenant_id,book_id,year_id,parent_allocation_id),
 UNIQUE(tenant_id,book_id,year_id,id,parent_allocation_id),
 FOREIGN KEY(tenant_id,book_id,year_id,parent_allocation_id) REFERENCES plugin_finance_evidence_allocation(tenant_id,book_id,year_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,chain_id) REFERENCES plugin_finance_evidence_chain(tenant_id,book_id,year_id,id));

CREATE TABLE plugin_finance_ap_run(
 tenant_id uuid NOT NULL,legal_entity_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,id uuid NOT NULL,
 slot_id uuid NOT NULL,parent_allocation_id uuid NOT NULL,document_id uuid NOT NULL,parent_confirmation_id uuid NOT NULL,
 operation_id uuid NOT NULL,predecessor_id uuid,predecessor_version bigint,
 producer_kind text NOT NULL CHECK(producer_kind IN('AP_EXPENSE','AP_EXPENSE_REVERSAL')),
 original_run_id uuid,journal_id uuid NOT NULL,
 status text NOT NULL CHECK(status IN('DRAFT','SUBMITTED','APPROVED','POSTED','CANCELLED')),
 version bigint NOT NULL DEFAULT 1 CHECK(version>0),content_version bigint NOT NULL DEFAULT 1 CHECK(content_version=1),
 amount numeric(28,8) NOT NULL CHECK(amount>0 AND amount=round(amount,2)),accounting_date date NOT NULL,due_date date,
 basis_id uuid NOT NULL,supplier_id uuid NOT NULL,supplier_revision_id uuid NOT NULL,
 content jsonb NOT NULL,content_digest text NOT NULL CHECK(content_digest~'^[a-f0-9]{64}$'),
 source_snapshot jsonb NOT NULL,source_digest text NOT NULL CHECK(source_digest~'^[a-f0-9]{64}$'),
 initial_payload jsonb NOT NULL,initial_payload_hash text NOT NULL CHECK(initial_payload_hash~'^[a-f0-9]{64}$'),
 prepared_by jsonb NOT NULL,last_editor jsonb NOT NULL,created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 current_submission_id uuid,current_approval_id uuid,post_id uuid,termination_id uuid,
 PRIMARY KEY(tenant_id,book_id,year_id,id),UNIQUE(tenant_id,book_id,year_id,operation_id),
 UNIQUE(tenant_id,book_id,year_id,journal_id),UNIQUE(tenant_id,book_id,year_id,id,journal_id),
 UNIQUE(tenant_id,book_id,year_id,slot_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,slot_id,parent_allocation_id) REFERENCES plugin_finance_ap_source_slot(tenant_id,book_id,year_id,id,parent_allocation_id),
 FOREIGN KEY(tenant_id,book_id,year_id,parent_allocation_id,parent_confirmation_id) REFERENCES plugin_finance_evidence_confirmation(tenant_id,book_id,year_id,allocation_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,document_id,basis_id) REFERENCES plugin_finance_ap_basis(tenant_id,book_id,year_id,document_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,journal_id) REFERENCES plugin_finance_journal(tenant_id,book_id,year_id,id) DEFERRABLE INITIALLY DEFERRED,
 FOREIGN KEY(tenant_id,book_id,year_id,slot_id,predecessor_id) REFERENCES plugin_finance_ap_run(tenant_id,book_id,year_id,slot_id,id) DEFERRABLE INITIALLY DEFERRED,
 FOREIGN KEY(tenant_id,book_id,year_id,slot_id,original_run_id) REFERENCES plugin_finance_ap_run(tenant_id,book_id,year_id,slot_id,id) DEFERRABLE INITIALLY DEFERRED,
 CHECK((predecessor_id IS NULL)=(predecessor_version IS NULL)),
 CHECK((producer_kind='AP_EXPENSE_REVERSAL')=(original_run_id IS NOT NULL)));
ALTER TABLE plugin_finance_ap_source_slot ADD FOREIGN KEY(tenant_id,book_id,year_id,id,latest_attempt_id) REFERENCES plugin_finance_ap_run(tenant_id,book_id,year_id,slot_id,id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE plugin_finance_ap_source_slot ADD FOREIGN KEY(tenant_id,book_id,year_id,id,active_original_id) REFERENCES plugin_finance_ap_run(tenant_id,book_id,year_id,slot_id,id) DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX finance_ap_run_page ON plugin_finance_ap_run(tenant_id,book_id,year_id,created_at,id);

CREATE TABLE plugin_finance_ap_submission(
 tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,id uuid NOT NULL,run_id uuid NOT NULL,
 run_version bigint NOT NULL CHECK(run_version>1),content_digest text NOT NULL,source_digest text NOT NULL,
 actor jsonb NOT NULL,submitted_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,expires_at timestamptz NOT NULL,
 PRIMARY KEY(tenant_id,book_id,year_id,id),UNIQUE(tenant_id,book_id,year_id,run_id,id),UNIQUE(tenant_id,book_id,year_id,run_id,run_version),
 FOREIGN KEY(tenant_id,book_id,year_id,run_id) REFERENCES plugin_finance_ap_run(tenant_id,book_id,year_id,id),
 CHECK(expires_at>submitted_at AND expires_at<=submitted_at+interval '7 days'));
CREATE TABLE plugin_finance_ap_approval(
 tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,id uuid NOT NULL,run_id uuid NOT NULL,submission_id uuid NOT NULL,
 run_version bigint NOT NULL CHECK(run_version>1),content_digest text NOT NULL,source_digest text NOT NULL,
 actor jsonb NOT NULL,approved_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,expires_at timestamptz NOT NULL,
 PRIMARY KEY(tenant_id,book_id,year_id,id),UNIQUE(tenant_id,book_id,year_id,run_id,id),UNIQUE(tenant_id,book_id,year_id,run_id,run_version),
 FOREIGN KEY(tenant_id,book_id,year_id,run_id,submission_id) REFERENCES plugin_finance_ap_submission(tenant_id,book_id,year_id,run_id,id),
 CHECK(expires_at>approved_at AND expires_at<=approved_at+interval '7 days'));
CREATE TABLE plugin_finance_ap_usage(
 tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,id uuid NOT NULL,run_id uuid NOT NULL,document_id uuid NOT NULL,basis_id uuid NOT NULL,submission_id uuid NOT NULL,
 amount numeric(28,8) NOT NULL CHECK(amount>0 AND amount=round(amount,2)),
 state text NOT NULL CHECK(state IN('RESERVED','COMMITTED','RELEASED','FULLY_REVERSED')),
 reserved_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,post_id uuid,release_id uuid,reverse_post_id uuid,
 PRIMARY KEY(tenant_id,book_id,year_id,id),UNIQUE(tenant_id,book_id,year_id,run_id,id),UNIQUE(tenant_id,book_id,year_id,submission_id),
 FOREIGN KEY(tenant_id,book_id,year_id,run_id,submission_id) REFERENCES plugin_finance_ap_submission(tenant_id,book_id,year_id,run_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,document_id,basis_id) REFERENCES plugin_finance_ap_basis(tenant_id,book_id,year_id,document_id,id),
 CHECK((state='RESERVED' AND post_id IS NULL AND release_id IS NULL AND reverse_post_id IS NULL) OR(state='COMMITTED' AND post_id IS NOT NULL AND release_id IS NULL AND reverse_post_id IS NULL) OR(state='RELEASED' AND post_id IS NULL AND release_id IS NOT NULL AND reverse_post_id IS NULL) OR(state='FULLY_REVERSED' AND post_id IS NOT NULL AND release_id IS NULL AND reverse_post_id IS NOT NULL)));
CREATE UNIQUE INDEX finance_ap_one_active_usage ON plugin_finance_ap_usage(tenant_id,book_id,year_id,run_id) WHERE state IN('RESERVED','COMMITTED');
CREATE INDEX finance_ap_document_usage ON plugin_finance_ap_usage(tenant_id,book_id,year_id,document_id,state);

CREATE TABLE plugin_finance_ap_parent_dependency(
 tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,id uuid NOT NULL,run_id uuid NOT NULL,usage_id uuid NOT NULL,parent_allocation_id uuid NOT NULL,parent_confirmation_id uuid NOT NULL,
 amount numeric(28,8) NOT NULL CHECK(amount>0 AND amount=round(amount,2)),state text NOT NULL CHECK(state IN('ACTIVE','RELEASED')),
 PRIMARY KEY(tenant_id,book_id,year_id,id),UNIQUE(tenant_id,book_id,year_id,run_id,usage_id),
 FOREIGN KEY(tenant_id,book_id,year_id,run_id,usage_id) REFERENCES plugin_finance_ap_usage(tenant_id,book_id,year_id,run_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,parent_allocation_id,parent_confirmation_id) REFERENCES plugin_finance_evidence_confirmation(tenant_id,book_id,year_id,allocation_id,id));
CREATE UNIQUE INDEX finance_ap_active_parent ON plugin_finance_ap_parent_dependency(tenant_id,book_id,year_id,parent_allocation_id) WHERE state='ACTIVE';

CREATE TABLE plugin_finance_ap_post(
 tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,id uuid NOT NULL,run_id uuid NOT NULL,journal_id uuid NOT NULL,
 submission_id uuid NOT NULL,approval_id uuid NOT NULL,usage_id uuid,
 run_version bigint NOT NULL CHECK(run_version>1),posting_seq bigint NOT NULL CHECK(posting_seq>0),posting_digest text NOT NULL CHECK(posting_digest~'^[a-f0-9]{64}$'),
 content_digest text NOT NULL,source_digest text NOT NULL,actor jsonb NOT NULL,posted_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(tenant_id,book_id,year_id,id),UNIQUE(tenant_id,book_id,year_id,run_id),UNIQUE(tenant_id,book_id,year_id,run_id,id),UNIQUE(tenant_id,book_id,year_id,journal_id),
 FOREIGN KEY(tenant_id,book_id,year_id,run_id,journal_id) REFERENCES plugin_finance_ap_run(tenant_id,book_id,year_id,id,journal_id),
 FOREIGN KEY(tenant_id,book_id,year_id,run_id,submission_id) REFERENCES plugin_finance_ap_submission(tenant_id,book_id,year_id,run_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,run_id,approval_id) REFERENCES plugin_finance_ap_approval(tenant_id,book_id,year_id,run_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,run_id,usage_id) REFERENCES plugin_finance_ap_usage(tenant_id,book_id,year_id,run_id,id) DEFERRABLE INITIALLY DEFERRED);
CREATE TABLE plugin_finance_ap_open_item(
 tenant_id uuid NOT NULL,legal_entity_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,id uuid NOT NULL,run_id uuid NOT NULL,post_id uuid NOT NULL,usage_id uuid NOT NULL,
 supplier_id uuid NOT NULL,supplier_revision_id uuid NOT NULL,amount numeric(28,8) NOT NULL CHECK(amount>0 AND amount=round(amount,2)),due_date date,
 version bigint NOT NULL DEFAULT 1 CHECK(version IN(1,2)),reverse_post_id uuid,created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(tenant_id,book_id,year_id,id),UNIQUE(tenant_id,book_id,year_id,run_id),UNIQUE(tenant_id,book_id,year_id,run_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,run_id,post_id) REFERENCES plugin_finance_ap_post(tenant_id,book_id,year_id,run_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,run_id,usage_id) REFERENCES plugin_finance_ap_usage(tenant_id,book_id,year_id,run_id,id),
 FOREIGN KEY(tenant_id,legal_entity_id,supplier_id,supplier_revision_id) REFERENCES plugin_finance_counterparty_revision(tenant_id,legal_entity_id,counterparty_id,id),
 CHECK((version=1 AND reverse_post_id IS NULL) OR(version=2 AND reverse_post_id IS NOT NULL)));
CREATE TABLE plugin_finance_ap_reverse_effect(
 tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,id uuid NOT NULL,run_id uuid NOT NULL,post_id uuid NOT NULL,
 original_run_id uuid NOT NULL,original_post_id uuid NOT NULL,original_usage_id uuid NOT NULL,open_item_id uuid NOT NULL,
 amount numeric(28,8) NOT NULL CHECK(amount>0 AND amount=round(amount,2)),created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(tenant_id,book_id,year_id,id),UNIQUE(tenant_id,book_id,year_id,original_run_id),UNIQUE(tenant_id,book_id,year_id,run_id),
 FOREIGN KEY(tenant_id,book_id,year_id,run_id,post_id) REFERENCES plugin_finance_ap_post(tenant_id,book_id,year_id,run_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,original_run_id,original_post_id) REFERENCES plugin_finance_ap_post(tenant_id,book_id,year_id,run_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,original_run_id,original_usage_id) REFERENCES plugin_finance_ap_usage(tenant_id,book_id,year_id,run_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,original_run_id,open_item_id) REFERENCES plugin_finance_ap_open_item(tenant_id,book_id,year_id,run_id,id));
ALTER TABLE plugin_finance_ap_run ADD FOREIGN KEY(tenant_id,book_id,year_id,id,current_submission_id) REFERENCES plugin_finance_ap_submission(tenant_id,book_id,year_id,run_id,id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE plugin_finance_ap_run ADD FOREIGN KEY(tenant_id,book_id,year_id,id,current_approval_id) REFERENCES plugin_finance_ap_approval(tenant_id,book_id,year_id,run_id,id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE plugin_finance_ap_run ADD FOREIGN KEY(tenant_id,book_id,year_id,id,post_id) REFERENCES plugin_finance_ap_post(tenant_id,book_id,year_id,run_id,id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE plugin_finance_ap_usage ADD FOREIGN KEY(tenant_id,book_id,year_id,run_id,post_id) REFERENCES plugin_finance_ap_post(tenant_id,book_id,year_id,run_id,id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE plugin_finance_ap_usage ADD FOREIGN KEY(tenant_id,book_id,year_id,reverse_post_id) REFERENCES plugin_finance_ap_post(tenant_id,book_id,year_id,id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE plugin_finance_ap_open_item ADD FOREIGN KEY(tenant_id,book_id,year_id,reverse_post_id) REFERENCES plugin_finance_ap_post(tenant_id,book_id,year_id,id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE plugin_finance_ap_source_slot ADD FOREIGN KEY(tenant_id,book_id,year_id,active_usage_id) REFERENCES plugin_finance_ap_usage(tenant_id,book_id,year_id,id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE plugin_finance_ap_source_slot ADD FOREIGN KEY(tenant_id,book_id,year_id,active_open_item_id) REFERENCES plugin_finance_ap_open_item(tenant_id,book_id,year_id,id) DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE plugin_finance_ap_basis_head(
 tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,document_id uuid NOT NULL,
 latest_basis_id uuid NOT NULL,current_basis_id uuid,pending_basis_id uuid,
 PRIMARY KEY(tenant_id,book_id,year_id,document_id),
 FOREIGN KEY(tenant_id,book_id,year_id,document_id,latest_basis_id) REFERENCES plugin_finance_ap_basis(tenant_id,book_id,year_id,document_id,id) DEFERRABLE INITIALLY DEFERRED,
 FOREIGN KEY(tenant_id,book_id,year_id,document_id,current_basis_id) REFERENCES plugin_finance_ap_basis(tenant_id,book_id,year_id,document_id,id) DEFERRABLE INITIALLY DEFERRED,
 FOREIGN KEY(tenant_id,book_id,year_id,document_id,pending_basis_id) REFERENCES plugin_finance_ap_basis(tenant_id,book_id,year_id,document_id,id) DEFERRABLE INITIALLY DEFERRED);
CREATE TABLE plugin_finance_ap_release(
 tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,id uuid NOT NULL,run_id uuid NOT NULL,submission_id uuid,usage_id uuid,
 action text NOT NULL CHECK(action IN('WITHDRAW','REJECT','CANCEL','ADMIN_TERMINATE')),run_version bigint NOT NULL CHECK(run_version>1),
 actor jsonb NOT NULL,reason text NOT NULL CHECK(length(reason) BETWEEN 1 AND 1000),created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(tenant_id,book_id,year_id,id),UNIQUE(tenant_id,book_id,year_id,run_id,id),UNIQUE(tenant_id,book_id,year_id,run_id,run_version),
 FOREIGN KEY(tenant_id,book_id,year_id,run_id) REFERENCES plugin_finance_ap_run(tenant_id,book_id,year_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,run_id,submission_id) REFERENCES plugin_finance_ap_submission(tenant_id,book_id,year_id,run_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,run_id,usage_id) REFERENCES plugin_finance_ap_usage(tenant_id,book_id,year_id,run_id,id),
 CHECK(usage_id IS NULL OR submission_id IS NOT NULL));
ALTER TABLE plugin_finance_ap_usage ADD FOREIGN KEY(tenant_id,book_id,year_id,run_id,release_id) REFERENCES plugin_finance_ap_release(tenant_id,book_id,year_id,run_id,id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE plugin_finance_ap_run ADD FOREIGN KEY(tenant_id,book_id,year_id,id,termination_id) REFERENCES plugin_finance_ap_release(tenant_id,book_id,year_id,run_id,id) DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE plugin_finance_ap_transition(
 tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,id uuid NOT NULL,run_id uuid NOT NULL,run_version bigint NOT NULL CHECK(run_version>0),previous_version bigint,
 action text NOT NULL CHECK(action IN('CREATE','SUBMIT','APPROVE','POST','WITHDRAW','REJECT','CANCEL','ADMIN_TERMINATE')),
 from_status text,to_status text NOT NULL,actor jsonb NOT NULL,reason text NOT NULL CHECK(length(reason) BETWEEN 1 AND 1000),
 content_digest text NOT NULL,source_digest text NOT NULL,submission_id uuid,approval_id uuid,post_id uuid,release_id uuid,created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(tenant_id,book_id,year_id,id),UNIQUE(tenant_id,book_id,year_id,run_id,run_version),
 FOREIGN KEY(tenant_id,book_id,year_id,run_id) REFERENCES plugin_finance_ap_run(tenant_id,book_id,year_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,run_id,previous_version) REFERENCES plugin_finance_ap_transition(tenant_id,book_id,year_id,run_id,run_version) DEFERRABLE INITIALLY DEFERRED,
 FOREIGN KEY(tenant_id,book_id,year_id,run_id,submission_id) REFERENCES plugin_finance_ap_submission(tenant_id,book_id,year_id,run_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,run_id,approval_id) REFERENCES plugin_finance_ap_approval(tenant_id,book_id,year_id,run_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,run_id,post_id) REFERENCES plugin_finance_ap_post(tenant_id,book_id,year_id,run_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,run_id,release_id) REFERENCES plugin_finance_ap_release(tenant_id,book_id,year_id,run_id,id),
 CHECK((action='CREATE')=(run_version=1)),CHECK((run_version=1 AND previous_version IS NULL AND from_status IS NULL AND to_status='DRAFT') OR(run_version>1 AND previous_version=run_version-1 AND from_status IS NOT NULL)));
CREATE TABLE plugin_finance_ap_request(
 tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,actor_id uuid NOT NULL,request_key text NOT NULL,route text NOT NULL,payload_hash text NOT NULL CHECK(payload_hash~'^[a-f0-9]{64}$'),
 result_kind text NOT NULL CHECK(result_kind IN('BASIS','PAYABLE')),basis_id uuid,run_id uuid,created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(actor_id,request_key),
 FOREIGN KEY(tenant_id,book_id,year_id,basis_id) REFERENCES plugin_finance_ap_basis(tenant_id,book_id,year_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,run_id) REFERENCES plugin_finance_ap_run(tenant_id,book_id,year_id,id),
 CHECK((result_kind='BASIS' AND basis_id IS NOT NULL AND run_id IS NULL) OR(result_kind='PAYABLE' AND run_id IS NOT NULL AND basis_id IS NULL)));
CREATE TABLE plugin_finance_ap_audit(
 tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,id uuid NOT NULL,kind text NOT NULL CHECK(kind IN('BASIS','AP')),basis_id uuid,run_id uuid,transition_id uuid,
 action text NOT NULL,actor jsonb NOT NULL,payload jsonb NOT NULL,created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(tenant_id,book_id,year_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,basis_id) REFERENCES plugin_finance_ap_basis(tenant_id,book_id,year_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,run_id) REFERENCES plugin_finance_ap_run(tenant_id,book_id,year_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,transition_id) REFERENCES plugin_finance_ap_transition(tenant_id,book_id,year_id,id),
 CHECK((kind='BASIS' AND basis_id IS NOT NULL AND run_id IS NULL AND transition_id IS NULL) OR(kind='AP' AND basis_id IS NULL AND run_id IS NOT NULL AND transition_id IS NOT NULL)));

-- Actor snapshots are actual scoped binding references, not arbitrary JSON labels.
CREATE FUNCTION plugin_finance_ap_actor(who jsonb,t uuid) RETURNS void LANGUAGE plpgsql AS $$ BEGIN
 IF who IS NULL OR jsonb_typeof(who) IS DISTINCT FROM 'object' OR (SELECT array_agg(k ORDER BY k) FROM jsonb_object_keys(who) k) IS DISTINCT FROM ARRAY['accountId','assurance','authorityId','authorityManifestDigest','bindingId','bindingVersion','personId']::text[] OR EXISTS(SELECT 1 FROM jsonb_each(who) v WHERE jsonb_typeof(v.value) IS DISTINCT FROM 'string') OR who->>'assurance' IS DISTINCT FROM 'ORG_ATTESTED' OR who->>'bindingVersion' !~'^(0|[1-9][0-9]{0,18})$' OR who->>'authorityManifestDigest' !~'^[a-f0-9]{64}$' OR NOT EXISTS(SELECT 1 FROM plugin_finance_person_realm z WHERE z.tenant_id=t AND z.authority_id=(who->>'authorityId')::uuid AND z.manifest_sha256=who->>'authorityManifestDigest') OR who->>'accountId' IS NULL OR who->>'personId' IS NULL OR who->>'bindingId' IS NULL OR NOT EXISTS(
 SELECT 1 FROM plugin_finance_person_binding b WHERE b.tenant_id=t AND b.id=(who->>'bindingId')::uuid AND b.account_id=(who->>'accountId')::uuid AND b.person_id=(who->>'personId')::uuid) THEN
 RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='JOURNAL_PERSON_IDENTITY_UNAVAILABLE';END IF;
END $$;
CREATE FUNCTION plugin_finance_ap_digest(v jsonb) RETURNS text LANGUAGE sql IMMUTABLE STRICT AS $$ SELECT encode(sha256(convert_to(v::text,'UTF8')),'hex') $$;

CREATE FUNCTION plugin_finance_ap_assert(t uuid,b uuid,y uuid,rid uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE r plugin_finance_ap_run%ROWTYPE;h plugin_finance_journal%ROWTYPE;e plugin_finance_ap_transition%ROWTYPE;p plugin_finance_ap_post%ROWTYPE;u plugin_finance_ap_usage%ROWTYPE;s plugin_finance_ap_submission%ROWTYPE;a plugin_finance_ap_approval%ROWTYPE;
BEGIN
 SELECT * INTO r FROM plugin_finance_ap_run WHERE(tenant_id,book_id,year_id,id)=(t,b,y,rid);IF NOT FOUND THEN RETURN;END IF;
 SELECT * INTO h FROM plugin_finance_journal WHERE(tenant_id,book_id,year_id,id)=(t,b,y,r.journal_id);
 IF NOT FOUND OR h.type IS DISTINCT FROM r.producer_kind OR h.status IS DISTINCT FROM r.status OR h.version<>r.version OR h.date<>r.accounting_date OR h.amount_format_version<>'V4_8' OR h.source_event_id IS NOT NULL OR h.created_by IS DISTINCT FROM(r.prepared_by->>'accountId')::uuid OR r.content_digest<>plugin_finance_ap_digest(r.content) OR r.source_digest<>plugin_finance_ap_digest(r.source_snapshot) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 IF(SELECT count(*) FROM plugin_finance_line l WHERE(l.tenant_id,l.book_id,l.year_id,l.journal_id)=(t,b,y,r.journal_id))<>2 OR EXISTS(SELECT 1 FROM plugin_finance_line l WHERE(l.tenant_id,l.book_id,l.year_id,l.journal_id)=(t,b,y,r.journal_id) AND NOT EXISTS(SELECT 1 FROM jsonb_array_elements(r.content->'generatedLines') x WHERE(x->>'lineNo')::integer=l.line_no AND(x->'account'->>'id')::uuid=l.account_id AND(x->>'debit')::numeric=l.debit AND(x->>'credit')::numeric=l.credit AND x->>'memo'=l.memo)) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 SELECT * INTO e FROM plugin_finance_ap_transition WHERE(tenant_id,book_id,year_id,run_id,run_version)=(t,b,y,rid,r.version);
 IF NOT FOUND OR e.to_status<>r.status OR e.content_digest<>r.content_digest OR e.source_digest<>r.source_digest THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 PERFORM plugin_finance_ap_actor(r.prepared_by,t);PERFORM plugin_finance_ap_actor(r.last_editor,t);PERFORM plugin_finance_ap_actor(e.actor,t);
 IF EXISTS(SELECT 1 FROM plugin_finance_ap_usage q WHERE(q.tenant_id,q.book_id,q.year_id,q.run_id)=(t,b,y,rid) AND q.state IN('RESERVED','COMMITTED') AND(q.submission_id IS DISTINCT FROM r.current_submission_id OR q.amount<>r.amount OR q.document_id<>r.document_id OR q.basis_id<>r.basis_id)) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 IF r.status IN('SUBMITTED','APPROVED') THEN
 SELECT * INTO s FROM plugin_finance_ap_submission WHERE(tenant_id,book_id,year_id,run_id,id)=(t,b,y,rid,r.current_submission_id);
 IF NOT FOUND OR s.content_digest<>r.content_digest OR s.source_digest<>r.source_digest THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_SOURCE_STALE';END IF;
 IF r.producer_kind='AP_EXPENSE' THEN
 SELECT * INTO u FROM plugin_finance_ap_usage WHERE(tenant_id,book_id,year_id,run_id,submission_id)=(t,b,y,rid,s.id);
 IF NOT FOUND OR u.state<>'RESERVED' OR NOT EXISTS(SELECT 1 FROM plugin_finance_ap_parent_dependency d WHERE(d.tenant_id,d.book_id,d.year_id,d.run_id,d.usage_id,d.parent_allocation_id,d.parent_confirmation_id)=(t,b,y,rid,u.id,r.parent_allocation_id,r.parent_confirmation_id) AND d.state='ACTIVE' AND d.amount=r.amount) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 END IF;
 END IF;
 IF r.status='APPROVED' THEN
 SELECT * INTO a FROM plugin_finance_ap_approval WHERE(tenant_id,book_id,year_id,run_id,id)=(t,b,y,rid,r.current_approval_id);
 IF NOT FOUND OR a.id IS DISTINCT FROM e.approval_id OR a.run_version<>r.version OR a.submission_id IS DISTINCT FROM r.current_submission_id OR a.content_digest<>r.content_digest OR a.source_digest<>r.source_digest OR a.expires_at>s.expires_at OR h.approved_by IS DISTINCT FROM(a.actor->>'accountId')::uuid OR h.approved_version<>r.version THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_APPROVAL_STALE';END IF;
 END IF;
 IF r.status='POSTED' THEN
 SELECT * INTO p FROM plugin_finance_ap_post WHERE(tenant_id,book_id,year_id,run_id,id)=(t,b,y,rid,r.post_id);
 IF NOT FOUND OR p.id IS DISTINCT FROM e.post_id OR p.run_version<>r.version OR p.journal_id<>r.journal_id OR p.content_digest<>r.content_digest OR p.source_digest<>r.source_digest OR p.actor IS DISTINCT FROM e.actor OR NOT EXISTS(SELECT 1 FROM plugin_finance_posting z WHERE(z.tenant_id,z.book_id,z.year_id,z.journal_id)=(t,b,y,r.journal_id) AND z.posting_seq=p.posting_seq AND z.digest=p.posting_digest AND z.journal_version=r.version-1 AND z.posted_by=(p.actor->>'accountId')::uuid AND z.posted_at=p.posted_at) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 IF r.producer_kind='AP_EXPENSE' THEN
 SELECT * INTO u FROM plugin_finance_ap_usage WHERE(tenant_id,book_id,year_id,run_id,id)=(t,b,y,rid,p.usage_id);
 IF u.state='COMMITTED' AND NOT EXISTS(SELECT 1 FROM plugin_finance_ap_parent_dependency d WHERE(d.tenant_id,d.book_id,d.year_id,d.run_id,d.usage_id,d.parent_allocation_id,d.parent_confirmation_id)=(t,b,y,rid,u.id,r.parent_allocation_id,r.parent_confirmation_id) AND d.state='ACTIVE' AND d.amount=r.amount) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 IF u.state='FULLY_REVERSED' AND (NOT EXISTS(SELECT 1 FROM plugin_finance_ap_reverse_effect x WHERE(x.tenant_id,x.book_id,x.year_id,x.original_run_id,x.original_post_id,x.original_usage_id,x.post_id)=(t,b,y,rid,p.id,u.id,u.reverse_post_id) AND x.amount=r.amount) OR EXISTS(SELECT 1 FROM plugin_finance_ap_parent_dependency d WHERE(d.tenant_id,d.book_id,d.year_id,d.run_id)=(t,b,y,rid) AND d.state='ACTIVE')) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 IF u.id IS NULL OR u.state NOT IN('COMMITTED','FULLY_REVERSED') OR u.post_id<>p.id OR NOT EXISTS(SELECT 1 FROM plugin_finance_ap_open_item i WHERE(i.tenant_id,i.book_id,i.year_id,i.run_id,i.post_id,i.usage_id)=(t,b,y,rid,p.id,u.id) AND i.amount=r.amount AND i.supplier_id=r.supplier_id AND i.supplier_revision_id=r.supplier_revision_id AND((u.state='COMMITTED' AND i.version=1 AND i.reverse_post_id IS NULL) OR(u.state='FULLY_REVERSED' AND i.version=2 AND i.reverse_post_id=u.reverse_post_id AND EXISTS(SELECT 1 FROM plugin_finance_ap_reverse_effect x WHERE(x.tenant_id,x.book_id,x.year_id,x.original_run_id,x.original_post_id,x.original_usage_id,x.open_item_id,x.post_id)=(t,b,y,rid,p.id,u.id,i.id,i.reverse_post_id) AND x.amount=r.amount)))) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 ELSE
 IF NOT EXISTS(SELECT 1 FROM plugin_finance_ap_reverse_effect x WHERE(x.tenant_id,x.book_id,x.year_id,x.run_id,x.post_id,x.original_run_id)=(t,b,y,rid,p.id,r.original_run_id) AND x.amount=r.amount) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 END IF;
 END IF;
 IF r.status IN('DRAFT','CANCELLED') AND EXISTS(SELECT 1 FROM plugin_finance_ap_usage q WHERE(q.tenant_id,q.book_id,q.year_id,q.run_id)=(t,b,y,rid) AND q.state IN('RESERVED','COMMITTED')) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
END $$;
CREATE FUNCTION plugin_finance_ap_final() RETURNS trigger LANGUAGE plpgsql AS $$ DECLARE rid uuid;BEGIN
 IF TG_TABLE_NAME='plugin_finance_ap_run' THEN rid:=NEW.id;ELSE rid:=(to_jsonb(NEW)->>'run_id')::uuid;END IF;
 PERFORM plugin_finance_ap_assert(NEW.tenant_id,NEW.book_id,NEW.year_id,rid);RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER finance_ap_run_final AFTER INSERT OR UPDATE ON plugin_finance_ap_run DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION plugin_finance_ap_final();
CREATE CONSTRAINT TRIGGER finance_ap_transition_final AFTER INSERT ON plugin_finance_ap_transition DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION plugin_finance_ap_final();
CREATE CONSTRAINT TRIGGER finance_ap_post_final AFTER INSERT ON plugin_finance_ap_post DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION plugin_finance_ap_final();
CREATE CONSTRAINT TRIGGER finance_ap_usage_final AFTER INSERT OR UPDATE ON plugin_finance_ap_usage DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION plugin_finance_ap_final();
CREATE CONSTRAINT TRIGGER finance_ap_open_item_final AFTER INSERT OR UPDATE ON plugin_finance_ap_open_item DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION plugin_finance_ap_final();
CREATE CONSTRAINT TRIGGER finance_ap_parent_final AFTER INSERT OR UPDATE ON plugin_finance_ap_parent_dependency DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION plugin_finance_ap_final();

CREATE FUNCTION plugin_finance_ap_capacity_assert(t uuid,b uuid,y uuid,d uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE h plugin_finance_ap_basis_head%ROWTYPE;v plugin_finance_ap_basis%ROWTYPE;used numeric;reserved numeric;
BEGIN
 SELECT * INTO h FROM plugin_finance_ap_basis_head WHERE(tenant_id,book_id,year_id,document_id)=(t,b,y,d);IF NOT FOUND THEN RETURN;END IF;
 IF NOT EXISTS(SELECT 1 FROM plugin_finance_ap_basis q WHERE(q.tenant_id,q.book_id,q.year_id,q.document_id,q.id)=(t,b,y,d,h.latest_basis_id)) OR(h.current_basis_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM plugin_finance_ap_basis q WHERE(q.tenant_id,q.book_id,q.year_id,q.document_id,q.id)=(t,b,y,d,h.current_basis_id) AND q.status='APPROVED')) OR(h.pending_basis_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM plugin_finance_ap_basis q WHERE(q.tenant_id,q.book_id,q.year_id,q.document_id,q.id)=(t,b,y,d,h.pending_basis_id) AND q.status='PENDING')) OR EXISTS(SELECT 1 FROM plugin_finance_ap_basis q WHERE(q.tenant_id,q.book_id,q.year_id,q.document_id)=(t,b,y,d) AND q.status='PENDING' AND q.id IS DISTINCT FROM h.pending_basis_id) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_BASIS_STALE';END IF;
 SELECT coalesce(sum(amount) FILTER(WHERE state='RESERVED'),0),coalesce(sum(amount) FILTER(WHERE state='COMMITTED'),0) INTO reserved,used FROM plugin_finance_ap_usage WHERE(tenant_id,book_id,year_id,document_id)=(t,b,y,d);
 IF reserved+used>0 AND h.current_basis_id IS NULL THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_BASIS_STALE';END IF;
 SELECT * INTO v FROM plugin_finance_ap_basis WHERE(tenant_id,book_id,year_id,id)=(t,b,y,h.current_basis_id);
 IF FOUND AND reserved+used>v.capacity THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_CAPACITY_EXCEEDED';END IF;
END $$;
CREATE FUNCTION plugin_finance_ap_capacity_final() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 PERFORM plugin_finance_ap_capacity_assert(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.document_id);RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER finance_ap_capacity_usage AFTER INSERT OR UPDATE ON plugin_finance_ap_usage DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION plugin_finance_ap_capacity_final();
CREATE CONSTRAINT TRIGGER finance_ap_capacity_basis AFTER INSERT OR UPDATE ON plugin_finance_ap_basis DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION plugin_finance_ap_capacity_final();
CREATE CONSTRAINT TRIGGER finance_ap_capacity_head AFTER INSERT OR UPDATE ON plugin_finance_ap_basis_head DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION plugin_finance_ap_capacity_final();

CREATE FUNCTION plugin_finance_ap_immutable() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END $$;
CREATE TRIGGER finance_ap_submission_immutable BEFORE UPDATE OR DELETE ON plugin_finance_ap_submission FOR EACH ROW EXECUTE FUNCTION plugin_finance_ap_immutable();
CREATE TRIGGER finance_ap_approval_immutable BEFORE UPDATE OR DELETE ON plugin_finance_ap_approval FOR EACH ROW EXECUTE FUNCTION plugin_finance_ap_immutable();
CREATE TRIGGER finance_ap_post_immutable BEFORE UPDATE OR DELETE ON plugin_finance_ap_post FOR EACH ROW EXECUTE FUNCTION plugin_finance_ap_immutable();
CREATE TRIGGER finance_ap_reverse_immutable BEFORE UPDATE OR DELETE ON plugin_finance_ap_reverse_effect FOR EACH ROW EXECUTE FUNCTION plugin_finance_ap_immutable();
CREATE TRIGGER finance_ap_release_immutable BEFORE UPDATE OR DELETE ON plugin_finance_ap_release FOR EACH ROW EXECUTE FUNCTION plugin_finance_ap_immutable();
CREATE TRIGGER finance_ap_transition_immutable BEFORE UPDATE OR DELETE ON plugin_finance_ap_transition FOR EACH ROW EXECUTE FUNCTION plugin_finance_ap_immutable();
CREATE TRIGGER finance_ap_request_immutable BEFORE UPDATE OR DELETE ON plugin_finance_ap_request FOR EACH ROW EXECUTE FUNCTION plugin_finance_ap_immutable();
CREATE TRIGGER finance_ap_audit_immutable BEFORE UPDATE OR DELETE ON plugin_finance_ap_audit FOR EACH ROW EXECUTE FUNCTION plugin_finance_ap_immutable();

-- Scope and immutable supplier references cannot depend on JSON labels.
ALTER TABLE plugin_finance_ap_basis ADD FOREIGN KEY(tenant_id,book_id,legal_entity_id) REFERENCES plugin_finance_book(tenant_id,id,legal_entity_id);
ALTER TABLE plugin_finance_ap_run ADD FOREIGN KEY(tenant_id,book_id,legal_entity_id) REFERENCES plugin_finance_book(tenant_id,id,legal_entity_id);
ALTER TABLE plugin_finance_ap_run ADD FOREIGN KEY(tenant_id,legal_entity_id,supplier_id,supplier_revision_id) REFERENCES plugin_finance_counterparty_revision(tenant_id,legal_entity_id,counterparty_id,id);
ALTER TABLE plugin_finance_ap_open_item ADD FOREIGN KEY(tenant_id,book_id,legal_entity_id) REFERENCES plugin_finance_book(tenant_id,id,legal_entity_id);
DO $$ DECLARE r record;c text;BEGIN
 FOR r IN SELECT * FROM(VALUES
 ('plugin_finance_ap_basis','prepared_by',false),('plugin_finance_ap_basis','decided_by',true),
 ('plugin_finance_ap_run','prepared_by',false),('plugin_finance_ap_run','last_editor',false),
 ('plugin_finance_ap_submission','actor',false),('plugin_finance_ap_approval','actor',false),
 ('plugin_finance_ap_post','actor',false),('plugin_finance_ap_release','actor',false),
 ('plugin_finance_ap_transition','actor',false),('plugin_finance_ap_audit','actor',false)
 ) v(tab,col,nullable_actor) LOOP
 c:=r.col;
 EXECUTE format('ALTER TABLE %I ADD COLUMN %I uuid GENERATED ALWAYS AS((%I->>''accountId'')::uuid) STORED,ADD COLUMN %I uuid GENERATED ALWAYS AS((%I->>''personId'')::uuid) STORED,ADD COLUMN %I uuid GENERATED ALWAYS AS((%I->>''bindingId'')::uuid) STORED',r.tab,c||'_account',c,c||'_person',c,c||'_binding',c);
 EXECUTE format('ALTER TABLE %I ADD FOREIGN KEY(tenant_id,%I,%I,%I) REFERENCES plugin_finance_person_binding(tenant_id,id,account_id,person_id)',r.tab,c||'_binding',c||'_account',c||'_person');
 EXECUTE format('ALTER TABLE %I ADD CHECK((%I IS NULL AND %L) OR(%I IS NOT NULL AND %I IS NOT NULL AND %I IS NOT NULL AND %I IS NOT NULL))',r.tab,c,r.nullable_actor,c,c||'_account',c||'_person',c||'_binding');
 END LOOP;
END $$;

CREATE FUNCTION plugin_finance_ap_prelock(t uuid) RETURNS void LANGUAGE plpgsql AS $$ BEGIN
 PERFORM 1 FROM plugin_finance_person_realm WHERE tenant_id=t FOR SHARE;
 IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='JOURNAL_PERSON_IDENTITY_UNAVAILABLE';END IF;
END $$;
CREATE FUNCTION plugin_finance_ap_scope(t uuid,b uuid,y uuid,new_use boolean,d date DEFAULT NULL) RETURNS void LANGUAGE plpgsql AS $$
DECLARE yr plugin_finance_year%ROWTYPE;ent uuid;md text;
BEGIN
 SELECT legal_entity_id INTO ent FROM plugin_finance_book WHERE(tenant_id,id)=(t,b) FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SCOPE_DENIED';END IF;
 SELECT * INTO yr FROM plugin_finance_year WHERE(tenant_id,book_id,id)=(t,b,y) FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SCOPE_DENIED';END IF;
 PERFORM 1 FROM plugin_finance_year_guard WHERE(tenant_id,book_id,year_id)=(t,b,y) FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_OBSERVATION_UNAVAILABLE';END IF;
 SELECT mode INTO md FROM plugin_finance_journal_person_mode WHERE(tenant_id,book_id,year_id)=(t,b,y) FOR SHARE;
 IF md IS DISTINCT FROM 'JOURNAL_PERSON_SOD' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='JOURNAL_PERSON_MODE_REQUIRED';END IF;
 IF yr.precision_profile<>'V4' OR yr.precision_review_required THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='LEGACY_PRECISION_REVIEW_REQUIRED';END IF;
 IF yr.status<>'OPEN' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PERIOD_CLOSED';END IF;
 IF d IS NOT NULL THEN
 IF extract(year FROM d)::integer<>yr.year THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='INVALID_INPUT';END IF;
 PERFORM 1 FROM plugin_finance_period WHERE(tenant_id,book_id,year_id)=(t,b,y) AND month=extract(month FROM d)::integer AND(NOT new_use OR status='OPEN') FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PERIOD_CLOSED';END IF;
 END IF;
 PERFORM 1 FROM plugin_finance_counterparty_entity_guard WHERE(tenant_id,legal_entity_id)=(t,ent) FOR SHARE;
 IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_OBSERVATION_UNAVAILABLE';END IF;
 PERFORM 1 FROM plugin_finance_evidence_scope_guard WHERE(tenant_id,legal_entity_id)=(t,ent) FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_OBSERVATION_UNAVAILABLE';END IF;
 PERFORM 1 FROM plugin_finance_ap_guard WHERE(tenant_id,book_id,year_id)=(t,b,y) FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_OBSERVATION_UNAVAILABLE';END IF;
END $$;

CREATE FUNCTION plugin_finance_ap_linked(tab text,q jsonb) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT (tab='plugin_finance_journal' AND q->>'type' IN('AP_EXPENSE','AP_EXPENSE_REVERSAL')) OR EXISTS(
 SELECT 1 FROM plugin_finance_ap_run r WHERE(r.tenant_id,r.book_id,r.year_id,r.journal_id)=((q->>'tenant_id')::uuid,(q->>'book_id')::uuid,(q->>'year_id')::uuid,CASE WHEN tab='plugin_finance_journal' THEN(q->>'id')::uuid ELSE(q->>'journal_id')::uuid END))
$$;
CREATE FUNCTION plugin_finance_ap_raw_guard(tab text,operation text,o jsonb,n jsonb) RETURNS void LANGUAGE plpgsql AS $$
DECLARE q jsonb;r plugin_finance_ap_run%ROWTYPE;j uuid;
BEGIN
 q:=coalesce(n,o);j:=CASE WHEN tab='plugin_finance_journal' THEN(q->>'id')::uuid ELSE(q->>'journal_id')::uuid END;
 SELECT * INTO r FROM plugin_finance_ap_run WHERE(tenant_id,book_id,year_id,journal_id)=((q->>'tenant_id')::uuid,(q->>'book_id')::uuid,(q->>'year_id')::uuid,j);
 IF NOT FOUND OR operation='DELETE' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 PERFORM plugin_finance_ap_scope(r.tenant_id,r.book_id,r.year_id,false,NULL);
 IF tab='plugin_finance_journal' THEN
 IF n->>'type' IS DISTINCT FROM r.producer_kind OR(n->>'date')::date<>r.accounting_date OR n->>'summary' IS DISTINCT FROM r.content->>'summary' OR(n->>'created_by')::uuid IS DISTINCT FROM(r.prepared_by->>'accountId')::uuid OR n->>'source_event_id' IS NOT NULL OR n->>'closing_basis' IS NOT NULL OR n->>'amount_format_version' IS DISTINCT FROM 'V4_8' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 IF NOT EXISTS(SELECT 1 FROM plugin_finance_period p WHERE(p.tenant_id,p.book_id,p.year_id,p.id)=(r.tenant_id,r.book_id,r.year_id,(n->>'period_id')::uuid) AND p.month=extract(month FROM r.accounting_date)::integer) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 IF operation='UPDATE' AND(o->>'status' IN('POSTED','CANCELLED') OR(n->>'version')::bigint<>(o->>'version')::bigint+1 OR(n-ARRAY['status','version','approved_by','approved_version','number','reversal_of']) IS DISTINCT FROM(o-ARRAY['status','version','approved_by','approved_version','number','reversal_of'])) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 IF(n->>'status'='POSTED') THEN
 PERFORM plugin_finance_ap_scope(r.tenant_id,r.book_id,r.year_id,true,r.accounting_date);
 IF(n->>'reversal_of')::uuid IS DISTINCT FROM(CASE WHEN r.producer_kind='AP_EXPENSE_REVERSAL' THEN(SELECT journal_id FROM plugin_finance_ap_run WHERE(tenant_id,book_id,year_id,id)=(r.tenant_id,r.book_id,r.year_id,r.original_run_id)) ELSE NULL END) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 ELSIF n->>'reversal_of' IS NOT NULL THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 ELSE
 IF tab<>'plugin_finance_line' OR operation<>'INSERT' OR r.status<>'DRAFT' OR NOT EXISTS(SELECT 1 FROM jsonb_array_elements(r.content->'generatedLines') l WHERE(l->>'lineNo')::integer=(n->>'line_no')::integer AND(l->'account'->>'id')::uuid=(n->>'account_id')::uuid AND(l->>'debit')::numeric=(n->>'debit')::numeric AND(l->>'credit')::numeric=(n->>'credit')::numeric) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 END IF;
END $$;
CREATE FUNCTION plugin_finance_ap_posting_guard(n jsonb) RETURNS void LANGUAGE plpgsql AS $$
DECLARE r plugin_finance_ap_run%ROWTYPE;a plugin_finance_ap_approval%ROWTYPE;s plugin_finance_ap_submission%ROWTYPE;
BEGIN
 SELECT * INTO r FROM plugin_finance_ap_run WHERE(tenant_id,book_id,year_id,journal_id)=((n->>'tenant_id')::uuid,(n->>'book_id')::uuid,(n->>'year_id')::uuid,(n->>'journal_id')::uuid);
 IF NOT FOUND OR r.status<>'APPROVED' OR r.version<>(n->>'journal_version')::bigint THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_APPROVAL_STALE';END IF;
 SELECT * INTO a FROM plugin_finance_ap_approval WHERE(tenant_id,book_id,year_id,run_id,id)=(r.tenant_id,r.book_id,r.year_id,r.id,r.current_approval_id);
 SELECT * INTO s FROM plugin_finance_ap_submission WHERE(tenant_id,book_id,year_id,run_id,id)=(r.tenant_id,r.book_id,r.year_id,r.id,r.current_submission_id);
 IF a.id IS NULL OR a.expires_at<=clock_timestamp() THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_APPROVAL_EXPIRED';END IF;
 IF s.id IS NULL OR s.expires_at<=clock_timestamp() THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_SUBMISSION_EXPIRED';END IF;
 PERFORM plugin_finance_ap_scope(r.tenant_id,r.book_id,r.year_id,true,r.accounting_date);
 IF NOT plugin_finance_ap_fresh(r.tenant_id,r.book_id,r.year_id,r.id) OR r.prepared_by IS DISTINCT FROM plugin_finance_journal_person_identity(r.tenant_id,(r.prepared_by->>'accountId')::uuid,true) OR NOT plugin_finance_journal_person_role(r.tenant_id,r.book_id,r.year_id,(r.prepared_by->>'accountId')::uuid,ARRAY['OWNER','MAKER']) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_SOURCE_STALE';END IF;
 IF a.actor IS DISTINCT FROM plugin_finance_journal_person_identity(r.tenant_id,(a.actor->>'accountId')::uuid,true) OR NOT plugin_finance_journal_person_role(r.tenant_id,r.book_id,r.year_id,(a.actor->>'accountId')::uuid,ARRAY['OWNER','REVIEWER']) OR NOT plugin_finance_journal_person_role(r.tenant_id,r.book_id,r.year_id,(n->>'posted_by')::uuid,ARRAY['OWNER','POSTER']) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_APPROVAL_STALE';END IF;
END $$;

-- Exact AP dispatch prefix; prior V16 function body retained below.
CREATE OR REPLACE FUNCTION plugin_finance_journal_person_watermark_final() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.posting_seq IS DISTINCT FROM OLD.posting_seq AND EXISTS(SELECT 1 FROM plugin_finance_ap_post a JOIN plugin_finance_posting p ON(p.tenant_id,p.book_id,p.year_id,p.journal_id,p.posting_seq,p.digest)=(a.tenant_id,a.book_id,a.year_id,a.journal_id,a.posting_seq,a.posting_digest) WHERE(a.tenant_id,a.book_id,a.year_id,a.posting_seq)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.posting_seq)) THEN RETURN NULL;END IF;

 IF NEW.posting_seq IS DISTINCT FROM OLD.posting_seq AND plugin_finance_profit_closing_watermark(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.posting_seq,OLD.posting_seq) THEN RETURN NULL;END IF;
 IF plugin_finance_journal_person_lock(NEW.tenant_id,NEW.book_id,NEW.year_id)='JOURNAL_PERSON_SOD' AND NEW.posting_seq IS DISTINCT FROM OLD.posting_seq THEN
  PERFORM 1 FROM plugin_finance_posting p JOIN plugin_finance_journal_person_post r ON(r.tenant_id,r.book_id,r.year_id,r.journal_id,r.posting_seq,r.posting_digest)=(p.tenant_id,p.book_id,p.year_id,p.journal_id,p.posting_seq,p.digest) WHERE(p.tenant_id,p.book_id,p.year_id,p.posting_seq)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.posting_seq);
  IF NOT FOUND THEN RAISE EXCEPTION 'JOURNAL_PERSON_WATERMARK_WITHOUT_POST'; END IF;
 END IF;
 RETURN NULL;
END $$;

-- Exact AP dispatch prefix; prior V16 function body retained below.
CREATE OR REPLACE FUNCTION plugin_finance_journal_person_receipt_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE h plugin_finance_journal;c plugin_finance_journal_person_content;cur plugin_finance_journal_person_current;e plugin_finance_journal_person_transition;ap plugin_finance_journal_person_approval;po plugin_finance_posting;who jsonb;
BEGIN
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

-- Exact AP dispatch prefix; prior V16 function body retained below.
CREATE OR REPLACE FUNCTION plugin_finance_journal_person_final() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r jsonb;t uuid;b uuid;y uuid;j uuid;e plugin_finance_journal_person_transition;
BEGIN
 r:=CASE WHEN TG_OP='DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;IF plugin_finance_ap_linked(TG_TABLE_NAME,r) THEN PERFORM plugin_finance_ap_assert((r->>'tenant_id')::uuid,(r->>'book_id')::uuid,(r->>'year_id')::uuid,(SELECT id FROM plugin_finance_ap_run WHERE(tenant_id,book_id,year_id,journal_id)=((r->>'tenant_id')::uuid,(r->>'book_id')::uuid,(r->>'year_id')::uuid,CASE WHEN TG_TABLE_NAME='plugin_finance_journal' THEN(r->>'id')::uuid ELSE(r->>'journal_id')::uuid END)));RETURN NULL;END IF;

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

-- Exact AP dispatch prefix; prior V16 function body retained below.
CREATE OR REPLACE FUNCTION plugin_finance_journal_person_raw_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r jsonb;h plugin_finance_journal; old_mode text; new_mode text;
BEGIN
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

-- Exact AP dispatch prefix; prior V16 function body retained below.
CREATE OR REPLACE FUNCTION plugin_finance_journal_person_posting_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE h plugin_finance_journal;cur plugin_finance_journal_person_current;ap plugin_finance_journal_person_approval;
BEGIN
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

CREATE FUNCTION plugin_finance_ap_sidecar_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r plugin_finance_ap_run%ROWTYPE;s plugin_finance_ap_submission%ROWTYPE;oldj jsonb;newj jsonb;
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 oldj:=to_jsonb(OLD)-ARRAY['prepared_by_account','prepared_by_person','prepared_by_binding','last_editor_account','last_editor_person','last_editor_binding','decided_by_account','decided_by_person','decided_by_binding','service_original_id','recognition_original_id','basis_original_id'];newj:=to_jsonb(NEW)-ARRAY['prepared_by_account','prepared_by_person','prepared_by_binding','last_editor_account','last_editor_person','last_editor_binding','decided_by_account','decided_by_person','decided_by_binding','service_original_id','recognition_original_id','basis_original_id'];
 IF TG_OP='INSERT' AND newj ? 'created_at' AND(newj->>'created_at')::timestamptz IS DISTINCT FROM CURRENT_TIMESTAMP THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 IF TG_TABLE_NAME='plugin_finance_ap_run' THEN
 IF TG_OP='INSERT' THEN
 IF NEW.version<>1 OR NEW.status<>'DRAFT' OR NEW.current_submission_id IS NOT NULL OR NEW.current_approval_id IS NOT NULL OR NEW.post_id IS NOT NULL OR NEW.termination_id IS NOT NULL OR NEW.content_digest<>plugin_finance_ap_digest(NEW.content) OR NEW.source_digest<>plugin_finance_ap_digest(NEW.source_snapshot) OR NEW.initial_payload_hash<>plugin_finance_ap_digest(NEW.initial_payload) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 ELSE
 IF OLD.status IN('POSTED','CANCELLED') OR NEW.version<>OLD.version+1 OR(newj-ARRAY['version','status','current_submission_id','current_approval_id','post_id','termination_id']) IS DISTINCT FROM(oldj-ARRAY['version','status','current_submission_id','current_approval_id','post_id','termination_id']) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 END IF;
 ELSIF TG_TABLE_NAME='plugin_finance_ap_basis' THEN
 IF TG_OP='INSERT' THEN
 IF NEW.status<>'PENDING' OR NEW.version<>1 OR NEW.content_digest<>plugin_finance_ap_digest(NEW.content) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_BASIS_STALE';END IF;
 ELSE
 IF OLD.status<>'PENDING' OR NEW.status='PENDING' OR(newj-ARRAY['version','status','decided_by','decided_at','decision_reason']) IS DISTINCT FROM(oldj-ARRAY['version','status','decided_by','decided_at','decision_reason']) OR NEW.decided_at IS DISTINCT FROM CURRENT_TIMESTAMP THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_BASIS_STALE';END IF;
 IF NEW.status<>'CANCELLED' AND NEW.prepared_by->>'personId'=NEW.decided_by->>'personId' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SELF_APPROVAL_FORBIDDEN';END IF;
 END IF;
 ELSIF TG_TABLE_NAME IN('plugin_finance_ap_submission','plugin_finance_ap_approval','plugin_finance_ap_transition','plugin_finance_ap_post','plugin_finance_ap_release') THEN
 SELECT * INTO r FROM plugin_finance_ap_run WHERE(tenant_id,book_id,year_id,id)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.run_id);
 IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 PERFORM plugin_finance_ap_actor(newj->'actor',NEW.tenant_id);
 IF TG_TABLE_NAME='plugin_finance_ap_submission' THEN
 IF r.status<>'DRAFT' OR NEW.submitted_at IS DISTINCT FROM CURRENT_TIMESTAMP OR NEW.expires_at<=clock_timestamp() OR NEW.run_version<>r.version+1 OR NEW.actor IS DISTINCT FROM r.prepared_by OR NEW.content_digest<>r.content_digest OR NEW.source_digest<>r.source_digest THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_SOURCE_STALE';END IF;
 ELSIF TG_TABLE_NAME='plugin_finance_ap_approval' THEN
 SELECT * INTO s FROM plugin_finance_ap_submission WHERE(tenant_id,book_id,year_id,run_id,id)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.run_id,NEW.submission_id);
 IF r.status<>'SUBMITTED' OR r.current_submission_id IS DISTINCT FROM NEW.submission_id OR s.id IS NULL OR s.run_version<>r.version OR NEW.approved_at IS DISTINCT FROM CURRENT_TIMESTAMP OR NEW.expires_at<=clock_timestamp() OR NEW.expires_at>s.expires_at OR s.expires_at<=clock_timestamp() OR NEW.run_version<>r.version+1 OR NEW.content_digest<>r.content_digest OR NEW.source_digest<>r.source_digest THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_APPROVAL_STALE';END IF;
 IF NEW.actor->>'personId'=r.prepared_by->>'personId' OR NEW.actor->>'personId'=r.last_editor->>'personId' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SELF_APPROVAL_FORBIDDEN';END IF;
 END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER finance_ap_run_guard BEFORE INSERT OR UPDATE OR DELETE ON plugin_finance_ap_run FOR EACH ROW EXECUTE FUNCTION plugin_finance_ap_sidecar_guard();
CREATE TRIGGER finance_ap_basis_guard BEFORE INSERT OR UPDATE OR DELETE ON plugin_finance_ap_basis FOR EACH ROW EXECUTE FUNCTION plugin_finance_ap_sidecar_guard();
CREATE TRIGGER finance_ap_submission_guard BEFORE INSERT ON plugin_finance_ap_submission FOR EACH ROW EXECUTE FUNCTION plugin_finance_ap_sidecar_guard();
CREATE TRIGGER finance_ap_approval_guard BEFORE INSERT ON plugin_finance_ap_approval FOR EACH ROW EXECUTE FUNCTION plugin_finance_ap_sidecar_guard();
CREATE TRIGGER finance_ap_transition_guard BEFORE INSERT ON plugin_finance_ap_transition FOR EACH ROW EXECUTE FUNCTION plugin_finance_ap_sidecar_guard();
CREATE TRIGGER finance_ap_post_guard BEFORE INSERT ON plugin_finance_ap_post FOR EACH ROW EXECUTE FUNCTION plugin_finance_ap_sidecar_guard();
CREATE TRIGGER finance_ap_release_guard BEFORE INSERT ON plugin_finance_ap_release FOR EACH ROW EXECUTE FUNCTION plugin_finance_ap_sidecar_guard();

-- Forward-only protection of the existing expense consumer: historical V15 bytes stay unchanged.
CREATE FUNCTION plugin_finance_ap_protect_expense() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE currentcap numeric;
BEGIN
 IF TG_TABLE_NAME IN('plugin_finance_evidence_reverse_request','plugin_finance_evidence_reverse_effect') THEN
 IF TG_TABLE_NAME='plugin_finance_evidence_reverse_request' THEN
  IF TG_OP='UPDATE' THEN
   IF NEW.status<>'APPROVED' THEN RETURN NEW;END IF;
  END IF;
 END IF;
 IF EXISTS(SELECT 1 FROM plugin_finance_ap_parent_dependency d WHERE(d.tenant_id,d.book_id,d.year_id,d.parent_allocation_id)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.allocation_id) AND d.state='ACTIVE') THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_PARENT_IN_USE';END IF;
 ELSE
 IF NEW.status<>'APPROVED' THEN RETURN NEW;END IF;
 SELECT v.capacity INTO currentcap FROM plugin_finance_ap_basis_head h JOIN plugin_finance_ap_basis v ON(v.tenant_id,v.book_id,v.year_id,v.id)=(h.tenant_id,h.book_id,h.year_id,h.current_basis_id) WHERE(h.tenant_id,h.book_id,h.year_id,h.document_id)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.document_id);
 IF FOUND AND(NEW.content->>'faceGross')::numeric<currentcap THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_BASIS_STALE';END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER finance_ap_protect_reverse_request BEFORE INSERT OR UPDATE ON plugin_finance_evidence_reverse_request FOR EACH ROW EXECUTE FUNCTION plugin_finance_ap_protect_expense();
CREATE TRIGGER finance_ap_protect_reverse_effect BEFORE INSERT ON plugin_finance_evidence_reverse_effect FOR EACH ROW EXECUTE FUNCTION plugin_finance_ap_protect_expense();
CREATE TRIGGER finance_ap_protect_face_gross BEFORE INSERT OR UPDATE ON plugin_finance_evidence_review FOR EACH ROW EXECUTE FUNCTION plugin_finance_ap_protect_expense();

CREATE FUNCTION plugin_finance_ap_slot_final() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE q plugin_finance_ap_source_slot%ROWTYPE;r plugin_finance_ap_run%ROWTYPE;orig plugin_finance_ap_run%ROWTYPE;
BEGIN
 IF TG_TABLE_NAME='plugin_finance_ap_source_slot' THEN SELECT * INTO q FROM plugin_finance_ap_source_slot WHERE(tenant_id,book_id,year_id,id)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.id);ELSE SELECT * INTO q FROM plugin_finance_ap_source_slot WHERE(tenant_id,book_id,year_id,id)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.slot_id);END IF;
 IF q.latest_attempt_id IS NULL THEN IF q.version<>0 OR q.active_original_id IS NOT NULL OR q.active_usage_id IS NOT NULL OR q.active_open_item_id IS NOT NULL THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;IF EXISTS(SELECT 1 FROM plugin_finance_ap_run z WHERE(z.tenant_id,z.book_id,z.year_id,z.slot_id)=(q.tenant_id,q.book_id,q.year_id,q.id)) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;RETURN NEW;END IF;
 SELECT * INTO r FROM plugin_finance_ap_run WHERE(tenant_id,book_id,year_id,slot_id,id)=(q.tenant_id,q.book_id,q.year_id,q.id,q.latest_attempt_id);
 IF NOT FOUND OR EXISTS(SELECT 1 FROM plugin_finance_ap_run z WHERE(z.tenant_id,z.book_id,z.year_id,z.slot_id,z.predecessor_id)=(q.tenant_id,q.book_id,q.year_id,q.id,r.id)) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 IF q.active_original_id IS NULL THEN
 IF q.active_usage_id IS NOT NULL OR q.active_open_item_id IS NOT NULL OR EXISTS(SELECT 1 FROM plugin_finance_ap_usage u JOIN plugin_finance_ap_run rr ON(rr.tenant_id,rr.book_id,rr.year_id,rr.id)=(u.tenant_id,u.book_id,u.year_id,u.run_id) WHERE(rr.tenant_id,rr.book_id,rr.year_id,rr.slot_id)=(q.tenant_id,q.book_id,q.year_id,q.id) AND u.state IN('RESERVED','COMMITTED')) OR EXISTS(SELECT 1 FROM plugin_finance_ap_parent_dependency d JOIN plugin_finance_ap_run z ON(z.tenant_id,z.book_id,z.year_id,z.id)=(d.tenant_id,d.book_id,d.year_id,d.run_id) WHERE(z.tenant_id,z.book_id,z.year_id,z.slot_id)=(q.tenant_id,q.book_id,q.year_id,q.id) AND d.state='ACTIVE') THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 ELSE
 SELECT * INTO orig FROM plugin_finance_ap_run WHERE(tenant_id,book_id,year_id,slot_id,id)=(q.tenant_id,q.book_id,q.year_id,q.id,q.active_original_id);
 IF NOT FOUND OR orig.producer_kind<>'AP_EXPENSE' OR orig.status NOT IN('SUBMITTED','APPROVED','POSTED') OR NOT EXISTS(SELECT 1 FROM plugin_finance_ap_usage u WHERE(u.tenant_id,u.book_id,u.year_id,u.run_id,u.id)=(q.tenant_id,q.book_id,q.year_id,orig.id,q.active_usage_id) AND u.state IN('RESERVED','COMMITTED')) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 IF(orig.status='POSTED') IS DISTINCT FROM(q.active_open_item_id IS NOT NULL) OR(q.active_open_item_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM plugin_finance_ap_open_item i WHERE(i.tenant_id,i.book_id,i.year_id,i.run_id,i.id)=(q.tenant_id,q.book_id,q.year_id,orig.id,q.active_open_item_id) AND i.reverse_post_id IS NULL)) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER finance_ap_slot_final AFTER INSERT OR UPDATE ON plugin_finance_ap_source_slot DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION plugin_finance_ap_slot_final();
CREATE CONSTRAINT TRIGGER finance_ap_run_slot_final AFTER INSERT OR UPDATE ON plugin_finance_ap_run DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION plugin_finance_ap_slot_final();
CREATE UNIQUE INDEX finance_ap_attempt_successor ON plugin_finance_ap_run(tenant_id,book_id,year_id,slot_id,predecessor_id) WHERE predecessor_id IS NOT NULL;
CREATE UNIQUE INDEX finance_ap_attempt_root ON plugin_finance_ap_run(tenant_id,book_id,year_id,slot_id) WHERE predecessor_id IS NULL;
CREATE FUNCTION plugin_finance_ap_reverse_final() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM plugin_finance_ap_usage u WHERE(u.tenant_id,u.book_id,u.year_id,u.run_id,u.id,u.post_id,u.reverse_post_id)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.original_run_id,NEW.original_usage_id,NEW.original_post_id,NEW.post_id) AND u.state='FULLY_REVERSED' AND u.amount=NEW.amount) OR NOT EXISTS(SELECT 1 FROM plugin_finance_ap_open_item i WHERE(i.tenant_id,i.book_id,i.year_id,i.run_id,i.id,i.reverse_post_id)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.original_run_id,NEW.open_item_id,NEW.post_id) AND i.version=2 AND i.amount=NEW.amount) OR EXISTS(SELECT 1 FROM plugin_finance_ap_parent_dependency d WHERE(d.tenant_id,d.book_id,d.year_id,d.run_id)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.original_run_id) AND d.state='ACTIVE') THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 PERFORM plugin_finance_ap_assert(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.run_id);RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER finance_ap_reverse_final AFTER INSERT ON plugin_finance_ap_reverse_effect DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION plugin_finance_ap_reverse_final();

CREATE TABLE plugin_finance_ap_command_receipt(
 tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,id uuid NOT NULL,kind text NOT NULL CHECK(kind IN('BASIS','PAYABLE')),
 basis_id uuid,run_id uuid,transition_id uuid,action text NOT NULL,target_version bigint NOT NULL CHECK(target_version>0),content_digest text NOT NULL,
 actor jsonb NOT NULL,reason text NOT NULL CHECK(length(reason) BETWEEN 1 AND 1000),from_state text,to_state text NOT NULL,
 submission_id uuid,approval_id uuid,post_id uuid,release_id uuid,created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(tenant_id,book_id,year_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,basis_id) REFERENCES plugin_finance_ap_basis(tenant_id,book_id,year_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,run_id) REFERENCES plugin_finance_ap_run(tenant_id,book_id,year_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,transition_id) REFERENCES plugin_finance_ap_transition(tenant_id,book_id,year_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,run_id,submission_id) REFERENCES plugin_finance_ap_submission(tenant_id,book_id,year_id,run_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,run_id,approval_id) REFERENCES plugin_finance_ap_approval(tenant_id,book_id,year_id,run_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,run_id,post_id) REFERENCES plugin_finance_ap_post(tenant_id,book_id,year_id,run_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,run_id,release_id) REFERENCES plugin_finance_ap_release(tenant_id,book_id,year_id,run_id,id),
 CHECK((kind='BASIS' AND basis_id IS NOT NULL AND run_id IS NULL AND transition_id IS NULL AND submission_id IS NULL AND approval_id IS NULL AND post_id IS NULL AND release_id IS NULL) OR(kind='PAYABLE' AND basis_id IS NULL AND run_id IS NOT NULL AND transition_id IS NOT NULL)));
ALTER TABLE plugin_finance_ap_request ADD COLUMN command_receipt_id uuid NOT NULL;
ALTER TABLE plugin_finance_ap_request ADD FOREIGN KEY(tenant_id,book_id,year_id,command_receipt_id) REFERENCES plugin_finance_ap_command_receipt(tenant_id,book_id,year_id,id);
ALTER TABLE plugin_finance_ap_run ADD COLUMN create_receipt_id uuid;
ALTER TABLE plugin_finance_ap_run ADD FOREIGN KEY(tenant_id,book_id,year_id,create_receipt_id) REFERENCES plugin_finance_ap_command_receipt(tenant_id,book_id,year_id,id) DEFERRABLE INITIALLY DEFERRED;
CREATE TRIGGER finance_ap_command_receipt_immutable BEFORE UPDATE OR DELETE ON plugin_finance_ap_command_receipt FOR EACH ROW EXECUTE FUNCTION plugin_finance_ap_immutable();

CREATE FUNCTION plugin_finance_ap_original(t uuid,b uuid,y uuid,o uuid) RETURNS jsonb LANGUAGE plpgsql STABLE AS $$ DECLARE r plugin_finance_original%ROWTYPE;BEGIN
 SELECT * INTO r FROM plugin_finance_original WHERE(tenant_id,book_id,year_id,id)=(t,b,y,o);
 IF NOT FOUND OR r.sha256<>encode(sha256(r.content),'hex') THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SCOPE_DENIED';END IF;
 RETURN jsonb_build_object('bookId',b,'yearId',y,'id',r.id,'version',r.version::text,'filename',r.filename,'sha256',r.sha256,'createdBy',r.created_by,'authenticity','UNVERIFIED');
END $$;
CREATE FUNCTION plugin_finance_ap_document(t uuid,b uuid,y uuid,d uuid) RETURNS jsonb LANGUAGE plpgsql STABLE AS $$ DECLARE doc plugin_finance_evidence_document%ROWTYPE;r plugin_finance_evidence_review%ROWTYPE;o jsonb;BEGIN
 SELECT * INTO doc FROM plugin_finance_evidence_document WHERE(tenant_id,book_id,year_id,id)=(t,b,y,d);IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SCOPE_DENIED';END IF;
 SELECT * INTO r FROM plugin_finance_evidence_review WHERE(tenant_id,book_id,year_id,document_id,id)=(t,b,y,d,doc.current_review_id);
 IF NOT FOUND OR r.status<>'APPROVED' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_BASIS_UNAVAILABLE';END IF;
 o:=plugin_finance_ap_original(t,b,y,doc.source_original_id);
 RETURN jsonb_build_object('documentId',d,'documentVersion',doc.version::text,'currentReviewId',r.id,'basisVersion',doc.basis_version::text,'basisContentDigest',r.content_digest,'sourceOriginalVersion',o->>'version','sourceOriginalSha256',o->>'sha256');
END $$;
CREATE FUNCTION plugin_finance_ap_parent(t uuid,b uuid,y uuid,p uuid) RETURNS jsonb LANGUAGE plpgsql STABLE AS $$
DECLARE a plugin_finance_evidence_allocation%ROWTYPE;c plugin_finance_evidence_confirmation%ROWTYPE;u plugin_finance_evidence_usage%ROWTYPE;rev numeric;
BEGIN
 SELECT * INTO a FROM plugin_finance_evidence_allocation WHERE(tenant_id,book_id,year_id,id)=(t,b,y,p);IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SCOPE_DENIED';END IF;
 SELECT * INTO c FROM plugin_finance_evidence_confirmation WHERE(tenant_id,book_id,year_id,allocation_id,id)=(t,b,y,p,a.current_confirmation_id);
 SELECT * INTO u FROM plugin_finance_evidence_usage WHERE(tenant_id,book_id,year_id,allocation_id,id)=(t,b,y,p,a.current_usage_id);
 IF a.status<>'CONFIRMED' OR c.id IS NULL OR u.id IS NULL OR u.state<>'COMMITTED' OR c.usage_id<>u.id OR c.amount<>a.amount THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_SOURCE_STALE';END IF;
 SELECT coalesce(sum(amount),0) INTO rev FROM plugin_finance_evidence_reverse_effect WHERE(tenant_id,book_id,year_id,allocation_id,confirmation_id)=(t,b,y,p,c.id);
 IF a.amount-rev<0 THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_SOURCE_STALE';END IF;
 RETURN jsonb_build_object('allocationId',p,'documentId',a.document_id,'chainId',a.chain_id,'version',a.version::text,'contentVersion',a.content_version::text,'contentDigest',a.content_digest,'submissionId',a.current_submission_id,'usageId',u.id,'confirmationId',c.id,'confirmedAmount',c.amount::text,'approvedReversedAmount',rev::numeric(28,8)::text,'netConfirmedAmount',(a.amount-rev)::numeric(28,8)::text,'documentExpectation',plugin_finance_ap_document(t,b,y,a.document_id));
END $$;
CREATE FUNCTION plugin_finance_ap_account(t uuid,b uuid,y uuid,a uuid) RETURNS jsonb LANGUAGE plpgsql STABLE AS $$ DECLARE r plugin_finance_account%ROWTYPE;s jsonb;BEGIN
 SELECT * INTO r FROM plugin_finance_account WHERE(tenant_id,book_id,year_id,id)=(t,b,y,a);IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SCOPE_DENIED';END IF;
 s:=jsonb_build_object('id',r.id,'code',r.code,'name',r.name,'category',r.category,'normalSide',r.normal_side,'purpose',r.purpose,'enabled',r.enabled);RETURN s||jsonb_build_object('semanticDigest',plugin_finance_ap_digest(s));
END $$;

ALTER TABLE plugin_finance_ap_basis ADD COLUMN revision_no bigint NOT NULL DEFAULT 1 CHECK(revision_no>0),ADD COLUMN base_approved_basis_id uuid;
ALTER TABLE plugin_finance_ap_basis ADD FOREIGN KEY(tenant_id,book_id,year_id,document_id,base_approved_basis_id) REFERENCES plugin_finance_ap_basis(tenant_id,book_id,year_id,document_id,id);
CREATE FUNCTION plugin_finance_ap_supplier(t uuid,e uuid,p uuid) RETURNS jsonb LANGUAGE plpgsql STABLE AS $$ DECLARE r plugin_finance_counterparty_registry%ROWTYPE;v plugin_finance_counterparty_revision%ROWTYPE;BEGIN
 SELECT * INTO r FROM plugin_finance_counterparty_registry WHERE(tenant_id,legal_entity_id,id)=(t,e,p);
 SELECT * INTO v FROM plugin_finance_counterparty_revision WHERE(tenant_id,legal_entity_id,counterparty_id,id)=(t,e,p,r.approved_revision_id);
 IF r.id IS NULL OR r.pending_revision_id IS NOT NULL OR v.status IS DISTINCT FROM 'APPROVED' OR v.desired_status IS DISTINCT FROM 'ACTIVE' OR NOT(v.content->'roles' ? 'SUPPLIER') THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_SUPPLIER_STALE';END IF;
 RETURN jsonb_build_object('expected',jsonb_build_object('counterpartyId',p,'governanceHomeBookId',r.home_book_id,'registryVersion',r.version::text,'approvedRevisionId',v.id,'approvedRevisionDigest',v.content_digest),'displayName',v.content->>'displayName','roles',v.content->'roles','registrationJurisdiction',r.jurisdiction,'registrationIdentifier',r.identifier,'verification','ORGANIZATION_DECLARED_NOT_GOVERNMENT_VERIFIED');
END $$;
CREATE FUNCTION plugin_finance_ap_basis_expectation(t uuid,b uuid,y uuid,d uuid) RETURNS jsonb LANGUAGE plpgsql STABLE AS $$ DECLARE h plugin_finance_ap_basis_head%ROWTYPE;v plugin_finance_ap_basis%ROWTYPE;BEGIN
 SELECT * INTO h FROM plugin_finance_ap_basis_head WHERE(tenant_id,book_id,year_id,document_id)=(t,b,y,d);
 IF h.pending_basis_id IS NOT NULL THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_BASIS_PENDING';END IF;
 SELECT * INTO v FROM plugin_finance_ap_basis WHERE(tenant_id,book_id,year_id,id)=(t,b,y,h.current_basis_id);
 IF NOT FOUND OR v.status<>'APPROVED' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_BASIS_UNAVAILABLE';END IF;
 IF v.content->'expectedDocument' IS DISTINCT FROM plugin_finance_ap_document(t,b,y,d) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_BASIS_STALE';END IF;
 RETURN jsonb_build_object('basisId',v.id,'basisVersion',v.version::text,'contentDigest',v.content_digest,'documentExpectation',v.content->'expectedDocument');
END $$;
CREATE FUNCTION plugin_finance_ap_fresh(t uuid,b uuid,y uuid,rid uuid) RETURNS boolean LANGUAGE plpgsql STABLE AS $$
DECLARE r plugin_finance_ap_run%ROWTYPE;orig plugin_finance_ap_run%ROWTYPE;j jsonb;doc plugin_finance_evidence_document%ROWTYPE;a plugin_finance_evidence_allocation%ROWTYPE;line jsonb;
BEGIN
 SELECT * INTO r FROM plugin_finance_ap_run WHERE(tenant_id,book_id,year_id,id)=(t,b,y,rid);IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SCOPE_DENIED';END IF;
 IF r.producer_kind='AP_EXPENSE_REVERSAL' THEN
 SELECT * INTO orig FROM plugin_finance_ap_run WHERE(tenant_id,book_id,year_id,id)=(t,b,y,r.original_run_id);
 RETURN orig.status='POSTED' AND NOT EXISTS(SELECT 1 FROM plugin_finance_ap_reverse_effect x WHERE(x.tenant_id,x.book_id,x.year_id,x.original_run_id)=(t,b,y,orig.id));
 END IF;
 SELECT * INTO a FROM plugin_finance_evidence_allocation WHERE(tenant_id,book_id,year_id,id)=(t,b,y,r.parent_allocation_id);
 SELECT * INTO doc FROM plugin_finance_evidence_document WHERE(tenant_id,book_id,year_id,id)=(t,b,y,r.document_id);
 IF a.pending_reverse_id IS NOT NULL OR doc.pending_review_id IS NOT NULL THEN RETURN false;END IF;
 IF plugin_finance_ap_parent(t,b,y,a.id) IS DISTINCT FROM r.source_snapshot OR plugin_finance_ap_basis_expectation(t,b,y,r.document_id) IS DISTINCT FROM r.content->'accountingBasis' OR plugin_finance_ap_supplier(t,r.legal_entity_id,r.supplier_id) IS DISTINCT FROM r.content->'supplier' THEN RETURN false;END IF;
 FOR line IN SELECT value FROM jsonb_array_elements(r.content->'generatedLines') LOOP
 IF plugin_finance_ap_account(t,b,y,(line->'account'->>'id')::uuid) IS DISTINCT FROM line->'account' THEN RETURN false;END IF;
 END LOOP;
 IF plugin_finance_ap_original(t,b,y,(r.content->'serviceAcceptanceOriginal'->>'id')::uuid) IS DISTINCT FROM r.content->'serviceAcceptanceOriginal' OR plugin_finance_ap_original(t,b,y,(r.content->'recognitionOriginal'->>'id')::uuid) IS DISTINCT FROM r.content->'recognitionOriginal' THEN RETURN false;END IF;
 RETURN true;
EXCEPTION WHEN SQLSTATE 'P0001' THEN
 IF SQLERRM IN('AP_SOURCE_STALE','AP_SUPPLIER_STALE','AP_BASIS_STALE','AP_BASIS_PENDING','AP_BASIS_UNAVAILABLE') THEN RETURN false;END IF;RAISE;
END $$;

CREATE FUNCTION plugin_finance_ap_basis_command(t uuid,b uuid,y uuid,who jsonb,op text,target uuid,payload jsonb) RETURNS jsonb LANGUAGE plpgsql AS $$
DECLARE d uuid;ent uuid;rid uuid;cid uuid:=gen_random_uuid();h plugin_finance_ap_basis_head%ROWTYPE;r plugin_finance_ap_basis%ROWTYPE;doc plugin_finance_evidence_document%ROWTYPE;v plugin_finance_evidence_review%ROWTYPE;ct jsonb;cap numeric;net numeric;state text;rev bigint;act text;
BEGIN
 IF op NOT IN('basis-create','basis-approve','basis-reject','basis-cancel') THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='INVALID_INPUT';END IF;
 SELECT legal_entity_id INTO ent FROM plugin_finance_book WHERE(tenant_id,id)=(t,b);
 IF op='basis-create' THEN
 d:=(payload->'documentExpectation'->>'documentId')::uuid;
 IF payload->'documentExpectation' IS DISTINCT FROM plugin_finance_ap_document(t,b,y,d) OR payload->'organizationReviewedNotTaxVerified' IS DISTINCT FROM 'true'::jsonb THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_BASIS_STALE';END IF;
 SELECT * INTO doc FROM plugin_finance_evidence_document WHERE(tenant_id,book_id,year_id,id)=(t,b,y,d) FOR UPDATE;
 IF doc.pending_review_id IS NOT NULL THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_BASIS_PENDING';END IF;
 SELECT * INTO v FROM plugin_finance_evidence_review WHERE(tenant_id,book_id,year_id,id)=(t,b,y,doc.current_review_id);
 SELECT * INTO h FROM plugin_finance_ap_basis_head WHERE(tenant_id,book_id,year_id,document_id)=(t,b,y,d) FOR UPDATE;
 IF h.pending_basis_id IS NOT NULL THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_BASIS_PENDING';END IF;
 IF h.current_basis_id IS DISTINCT FROM(payload->>'expectedCurrentBasisId')::uuid THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_BASIS_STALE';END IF;
 IF h.current_basis_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM plugin_finance_ap_basis z WHERE(z.tenant_id,z.book_id,z.year_id,z.id,z.version)=(t,b,y,h.current_basis_id,(payload->>'expectedCurrentBasisVersion')::bigint)) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_BASIS_STALE';END IF;
 cap:=plugin_finance_evidence_money(payload->>'accountingCapacity',false);IF cap>(v.content->>'faceGross')::numeric THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_CAPACITY_EXCEEDED';END IF;
 rid:=gen_random_uuid();SELECT coalesce(max(revision_no),0)+1 INTO rev FROM plugin_finance_ap_basis WHERE(tenant_id,book_id,year_id,document_id)=(t,b,y,d);
 ct:=jsonb_build_object('expectedDocument',payload->'documentExpectation','faceGrossSnapshot',v.content->>'faceGross','accountingCapacity',cap::numeric(28,8)::text,'basisOriginal',plugin_finance_ap_original(t,b,y,(payload->>'basisOriginalId')::uuid),'recognitionEligibilityReason',payload->>'recognitionEligibilityReason','organizationReviewedNotTaxVerified',true);
 INSERT INTO plugin_finance_ap_basis(tenant_id,legal_entity_id,book_id,year_id,id,document_id,previous_basis_id,revision_no,base_approved_basis_id,status,face_gross,capacity,content,content_digest,prepared_by) VALUES(t,ent,b,y,rid,d,h.latest_basis_id,rev,h.current_basis_id,'PENDING',(v.content->>'faceGross')::numeric,cap,ct,plugin_finance_ap_digest(ct),who);
 INSERT INTO plugin_finance_ap_basis_head(tenant_id,book_id,year_id,document_id,latest_basis_id,current_basis_id,pending_basis_id) VALUES(t,b,y,d,rid,h.current_basis_id,rid) ON CONFLICT(tenant_id,book_id,year_id,document_id) DO UPDATE SET latest_basis_id=excluded.latest_basis_id,pending_basis_id=excluded.pending_basis_id;
 state:='PENDING';act:='CREATE_BASIS';
 ELSE
 SELECT * INTO r FROM plugin_finance_ap_basis WHERE(tenant_id,book_id,year_id,id)=(t,b,y,target) FOR UPDATE;IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SCOPE_DENIED';END IF;
 rid:=r.id;d:=r.document_id;ct:=r.content;
 IF r.status<>'PENDING' OR r.version<>(payload->>'expectedVersion')::bigint OR r.content_digest IS DISTINCT FROM payload->>'contentDigest' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='VERSION_CONFLICT';END IF;
 IF op='basis-cancel' THEN
 IF who->>'accountId'<>r.prepared_by->>'accountId' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SCOPE_DENIED';END IF;state:='CANCELLED';act:='CANCEL_BASIS';
 ELSE
 IF who->>'personId'=r.prepared_by->>'personId' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SELF_APPROVAL_FORBIDDEN';END IF;
 IF op='basis-approve' THEN
 IF r.prepared_by IS DISTINCT FROM plugin_finance_journal_person_identity(t,(r.prepared_by->>'accountId')::uuid,true) OR NOT plugin_finance_journal_person_role(t,b,y,(r.prepared_by->>'accountId')::uuid,ARRAY['OWNER']) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_APPROVAL_STALE';END IF;
 IF r.content->'expectedDocument' IS DISTINCT FROM plugin_finance_ap_document(t,b,y,d) OR plugin_finance_ap_original(t,b,y,(r.content->'basisOriginal'->>'id')::uuid) IS DISTINCT FROM r.content->'basisOriginal' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_BASIS_STALE';END IF;
 IF EXISTS(SELECT 1 FROM plugin_finance_ap_usage q WHERE(q.tenant_id,q.book_id,q.year_id,q.document_id)=(t,b,y,d) AND q.state='RESERVED') THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_ACTIVE_RESERVATIONS';END IF;
 SELECT coalesce(sum(amount),0) INTO net FROM plugin_finance_ap_usage q WHERE(q.tenant_id,q.book_id,q.year_id,q.document_id)=(t,b,y,d) AND q.state='COMMITTED';IF r.capacity<net THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_CAPACITY_BELOW_COMMITTED';END IF;
 state:='APPROVED';act:='APPROVE_BASIS';
 ELSE state:='REJECTED';act:='REJECT_BASIS';END IF;
 END IF;
 UPDATE plugin_finance_ap_basis SET status=state,version=2,decided_by=who,decided_at=CURRENT_TIMESTAMP,decision_reason=payload->>'reason' WHERE(tenant_id,book_id,year_id,id)=(t,b,y,rid);
 UPDATE plugin_finance_ap_basis_head SET pending_basis_id=NULL,current_basis_id=CASE WHEN state='APPROVED' THEN rid ELSE current_basis_id END WHERE(tenant_id,book_id,year_id,document_id,pending_basis_id)=(t,b,y,d,rid);
 END IF;
 INSERT INTO plugin_finance_ap_command_receipt(tenant_id,book_id,year_id,id,kind,basis_id,action,target_version,content_digest,actor,reason,from_state,to_state) VALUES(t,b,y,cid,'BASIS',rid,act,CASE WHEN op='basis-create' THEN 1 ELSE 2 END,plugin_finance_ap_digest(ct),who,payload->>'reason',CASE WHEN op='basis-create' THEN NULL ELSE 'PENDING' END,state);
 INSERT INTO plugin_finance_ap_audit(tenant_id,book_id,year_id,id,kind,basis_id,action,actor,payload) VALUES(t,b,y,gen_random_uuid(),'BASIS',rid,act,who,jsonb_build_object('commandReceiptId',cid,'reason',payload->>'reason'));
 RETURN jsonb_build_object('kind','BASIS','id',rid,'documentId',d,'payableId',NULL,'commandReceiptId',cid,'newEffect',true);
END $$;

CREATE FUNCTION plugin_finance_ap_create(t uuid,b uuid,y uuid,who jsonb,op text,target uuid,payload jsonb) RETURNS jsonb LANGUAGE plpgsql AS $$
DECLARE ent uuid;parent jsonb;supplier jsonb;bas jsonb;slot plugin_finance_ap_source_slot%ROWTYPE;prev plugin_finance_ap_run%ROWTYPE;orig plugin_finance_ap_run%ROWTYPE;existing plugin_finance_ap_run%ROWTYPE;item plugin_finance_ap_open_item%ROWTYPE;u plugin_finance_ap_usage%ROWTYPE;
 rid uuid:=gen_random_uuid();jid uuid:=gen_random_uuid();cid uuid:=gen_random_uuid();eid uuid:=gen_random_uuid();p uuid;sid uuid;d uuid;periodid uuid;chain uuid;dep uuid;amount numeric;dt date;due date;ct jsonb;lines jsonb;acc1 jsonb;acc2 jsonb;rowline jsonb;rb jsonb;hash text;reasonoriginal uuid;
BEGIN
 hash:=plugin_finance_ap_digest(payload-'idempotencyKey');
 SELECT * INTO existing FROM plugin_finance_ap_run WHERE(tenant_id,book_id,year_id,operation_id)=(t,b,y,(payload->>'operationId')::uuid);
 IF FOUND THEN
 IF existing.initial_payload_hash<>hash THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='IDEMPOTENCY_CONFLICT';END IF;
 RETURN jsonb_build_object('kind','PAYABLE','id',existing.id,'documentId',existing.document_id,'payableId',existing.id,'commandReceiptId',existing.create_receipt_id,'newEffect',false);
 END IF;
 SELECT legal_entity_id INTO ent FROM plugin_finance_book WHERE(tenant_id,id)=(t,b);
 IF op='create' THEN
 p:=(payload->'expectedParent'->>'allocationId')::uuid;parent:=plugin_finance_ap_parent(t,b,y,p);
 IF parent IS DISTINCT FROM payload->'expectedParent' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_SOURCE_STALE';END IF;
 d:=(parent->>'documentId')::uuid;chain:=(parent->>'chainId')::uuid;
 IF EXISTS(SELECT 1 FROM plugin_finance_evidence_allocation WHERE(tenant_id,book_id,year_id,id)=(t,b,y,p) AND pending_reverse_id IS NOT NULL) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_PENDING_PARENT_REVERSAL';END IF;
 IF EXISTS(SELECT 1 FROM plugin_finance_evidence_document WHERE(tenant_id,book_id,year_id,id)=(t,b,y,d) AND pending_review_id IS NOT NULL) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_BASIS_PENDING';END IF;
 bas:=plugin_finance_ap_basis_expectation(t,b,y,d);IF bas IS DISTINCT FROM payload->'expectedAccountingBasis' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_BASIS_STALE';END IF;
 supplier:=plugin_finance_ap_supplier(t,ent,(payload->'expectedSupplier'->>'counterpartyId')::uuid);IF supplier->'expected' IS DISTINCT FROM payload->'expectedSupplier' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_SUPPLIER_STALE';END IF;
 amount:=(parent->>'netConfirmedAmount')::numeric;IF amount<=0 THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_SOURCE_STALE';END IF;dt:=(payload->>'accountingDate')::date;due:=(payload->>'dueDate')::date;
 acc1:=plugin_finance_ap_account(t,b,y,(payload->>'expenseAccountId')::uuid);acc2:=plugin_finance_ap_account(t,b,y,(payload->>'payableAccountId')::uuid);
 IF acc1->>'category'<>'EXPENSE' OR acc1->>'normalSide'<>'DEBIT' OR acc1->>'enabled'<>'true' OR acc2->>'category'<>'LIABILITY' OR acc2->>'normalSide'<>'CREDIT' OR acc2->>'purpose'<>'NONE' OR acc2->>'enabled'<>'true' OR payload->'ordinaryServiceExpenseConfirmed' IS DISTINCT FROM 'true'::jsonb OR payload->'supplierMatchesDocumentConfirmed' IS DISTINCT FROM 'true'::jsonb OR payload->'serviceAcceptedConfirmed' IS DISTINCT FROM 'true'::jsonb OR payload->'noTaxAssetInventoryPayrollConfirmed' IS DISTINCT FROM 'true'::jsonb OR payload->'payableAccountUseConfirmed' IS DISTINCT FROM 'true'::jsonb THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_UNSUPPORTED_PATH';END IF;
 lines:=jsonb_build_array(jsonb_build_object('lineNo',1,'account',acc1,'debit',amount::numeric(28,8)::text,'credit','0.00000000','memo',payload->>'summary'),jsonb_build_object('lineNo',2,'account',acc2,'debit','0.00000000','credit',amount::numeric(28,8)::text,'memo',payload->>'summary'));
 ELSE
 SELECT * INTO orig FROM plugin_finance_ap_run WHERE(tenant_id,book_id,year_id,id)=(t,b,y,target) FOR UPDATE;
 IF NOT FOUND OR orig.producer_kind<>'AP_EXPENSE' OR orig.status<>'POSTED' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_UNSUPPORTED_PATH';END IF;
 IF orig.version<>(payload->>'expectedOriginalVersion')::bigint OR orig.content_digest IS DISTINCT FROM payload->>'expectedOriginalContentDigest' OR orig.post_id IS DISTINCT FROM(payload->>'originalPostReceiptId')::uuid THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='VERSION_CONFLICT';END IF;
 SELECT * INTO item FROM plugin_finance_ap_open_item WHERE(tenant_id,book_id,year_id,run_id,id)=(t,b,y,target,(payload->>'originalOpenItemId')::uuid);
 IF NOT FOUND OR item.version<>(payload->>'expectedOpenItemVersion')::bigint OR item.reverse_post_id IS NOT NULL THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_DEPENDENCY_PRESENT';END IF;
 SELECT * INTO u FROM plugin_finance_ap_usage WHERE(tenant_id,book_id,year_id,run_id,id)=(t,b,y,target,item.usage_id);IF u.state<>'COMMITTED' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 SELECT id INTO dep FROM plugin_finance_ap_parent_dependency WHERE(tenant_id,book_id,year_id,run_id,usage_id)=(t,b,y,target,u.id) AND state='ACTIVE';IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 parent:=orig.source_snapshot;p:=orig.parent_allocation_id;d:=orig.document_id;chain:=(parent->>'chainId')::uuid;amount:=orig.amount;dt:=orig.accounting_date;due:=orig.due_date;bas:=orig.content->'accountingBasis';supplier:=orig.content->'supplier';
 lines:='[]'::jsonb;FOR rowline IN SELECT value FROM jsonb_array_elements(orig.content->'generatedLines') LOOP
 acc1:=plugin_finance_ap_account(t,b,y,(rowline->'account'->>'id')::uuid);
 IF acc1->>'enabled'<>'true' OR(acc1-ARRAY['code','name','semanticDigest']) IS DISTINCT FROM((rowline->'account')-ARRAY['code','name','semanticDigest']) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_SOURCE_STALE';END IF;
 lines:=lines||jsonb_build_array(rowline||jsonb_build_object('account',acc1,'debit',rowline->>'credit','credit',rowline->>'debit'));
 END LOOP;
 rb:=jsonb_build_object('originalPayableId',orig.id,'originalJournalId',orig.journal_id,'originalPostReceiptId',orig.post_id,'originalContentDigest',orig.content_digest,'originalOpenItemId',item.id,'originalOpenItemVersion',item.version::text,'originalRecognitionUsageId',u.id,'originalParentDependencyId',dep,'amount',amount::numeric(28,8)::text);
 END IF;
 PERFORM plugin_finance_ap_scope(t,b,y,true,dt);
 SELECT * INTO slot FROM plugin_finance_ap_source_slot WHERE(tenant_id,book_id,year_id,parent_allocation_id)=(t,b,y,p) FOR UPDATE;
 IF slot.id IS NULL THEN
 IF payload->>'expectedPredecessorId' IS NOT NULL OR payload->>'expectedPredecessorVersion' IS NOT NULL OR op<>'create' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_SOURCE_ALREADY_CLAIMED';END IF;
 sid:=gen_random_uuid();INSERT INTO plugin_finance_ap_source_slot(tenant_id,book_id,year_id,id,parent_allocation_id,chain_id) VALUES(t,b,y,sid,p,chain);
 ELSE
 sid:=slot.id;SELECT * INTO prev FROM plugin_finance_ap_run WHERE(tenant_id,book_id,year_id,id)=(t,b,y,slot.latest_attempt_id);
 IF prev.id IS DISTINCT FROM(payload->>'expectedPredecessorId')::uuid OR prev.version IS DISTINCT FROM(payload->>'expectedPredecessorVersion')::bigint THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='VERSION_CONFLICT';END IF;
 IF op='create' THEN
 IF slot.active_original_id IS NOT NULL OR(prev.id IS NOT NULL AND prev.status<>'CANCELLED' AND NOT(prev.producer_kind='AP_EXPENSE_REVERSAL' AND prev.status='POSTED')) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_SOURCE_ALREADY_CLAIMED';END IF;
 ELSE
 IF slot.active_original_id IS DISTINCT FROM orig.id OR(prev.id<>orig.id AND NOT(prev.producer_kind='AP_EXPENSE_REVERSAL' AND prev.original_run_id=orig.id AND prev.status='CANCELLED')) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_DEPENDENCY_PRESENT';END IF;
 END IF;
 END IF;
 IF op='create' THEN
 ct:=jsonb_build_object('format','AP_PERSON_CONTENT_PG_V1','producerKind','AP_EXPENSE','rawType','AP_EXPENSE','source',parent,'supplier',supplier,'accountingBasis',bas,'accountingDate',dt,'dueDate',due,'currency','CNY','amount',amount::numeric(28,8)::text,'summary',payload->>'summary','serviceAcceptanceOriginal',plugin_finance_ap_original(t,b,y,(payload->>'serviceAcceptanceOriginalId')::uuid),'recognitionOriginal',plugin_finance_ap_original(t,b,y,(payload->>'recognitionOriginalId')::uuid),'recognitionReason',payload->>'recognitionReason','ordinaryServiceExpenseConfirmed',true,'supplierMatchesDocumentConfirmed',true,'serviceAcceptedConfirmed',true,'noTaxAssetInventoryPayrollConfirmed',true,'payableAccountUseConfirmed',true,'generatedLines',lines,'reversesPayableId',NULL,'reversesJournalId',NULL,'reversalBasis',NULL);
 ELSE
 ct:=orig.content||jsonb_build_object('producerKind','AP_EXPENSE_REVERSAL','rawType','AP_EXPENSE_REVERSAL','generatedLines',lines,'summary','供应商费用应付全额会计反向','recognitionOriginal',plugin_finance_ap_original(t,b,y,(payload->>'accountingReasonOriginalId')::uuid),'recognitionReason',payload->>'reason','reversesPayableId',orig.id,'reversesJournalId',orig.journal_id,'reversalBasis',rb);
 END IF;
 ct:=ct||jsonb_build_object('slotAtPreparation',jsonb_build_object('parentAllocationId',p,'chainId',chain,'sourceSystem','V15_CONFIRMED_EXPENSE_AP_V1','purpose','ACCOUNTING_CONFIRMATION','slotId',sid,'slotVersion',coalesce(slot.version,0)::text,'latestAttemptId',slot.latest_attempt_id,'latestAttemptVersion',prev.version::text,'activeOriginalConfirmationId',slot.active_original_id,'activeRecognitionUsageId',slot.active_usage_id,'activeOpenItemId',slot.active_open_item_id));
 INSERT INTO plugin_finance_ap_run(tenant_id,legal_entity_id,book_id,year_id,id,slot_id,parent_allocation_id,document_id,parent_confirmation_id,operation_id,predecessor_id,predecessor_version,producer_kind,original_run_id,journal_id,status,amount,accounting_date,due_date,basis_id,supplier_id,supplier_revision_id,content,content_digest,source_snapshot,source_digest,initial_payload,initial_payload_hash,prepared_by,last_editor,create_receipt_id) VALUES(t,ent,b,y,rid,sid,p,d,(parent->>'confirmationId')::uuid,(payload->>'operationId')::uuid,slot.latest_attempt_id,prev.version,ct->>'rawType',orig.id,jid,'DRAFT',amount,dt,due,(bas->>'basisId')::uuid,(supplier->'expected'->>'counterpartyId')::uuid,(supplier->'expected'->>'approvedRevisionId')::uuid,ct,plugin_finance_ap_digest(ct),parent,plugin_finance_ap_digest(parent),payload-'idempotencyKey',hash,who,who,cid);
 SELECT id INTO periodid FROM plugin_finance_period WHERE(tenant_id,book_id,year_id)=(t,b,y) AND month=extract(month FROM dt)::integer;
 INSERT INTO plugin_finance_journal(tenant_id,book_id,year_id,id,period_id,date,summary,type,created_by,amount_format_version) VALUES(t,b,y,jid,periodid,dt,ct->>'summary',ct->>'rawType',(who->>'accountId')::uuid,'V4_8');
 FOR rowline IN SELECT value FROM jsonb_array_elements(lines) LOOP INSERT INTO plugin_finance_line(tenant_id,book_id,year_id,journal_id,line_no,account_id,debit,credit,memo) VALUES(t,b,y,jid,(rowline->>'lineNo')::integer,(rowline->'account'->>'id')::uuid,(rowline->>'debit')::numeric,(rowline->>'credit')::numeric,rowline->>'memo');END LOOP;
 UPDATE plugin_finance_ap_source_slot SET latest_attempt_id=rid,version=version+1 WHERE(tenant_id,book_id,year_id,id)=(t,b,y,sid);
 INSERT INTO plugin_finance_ap_transition(tenant_id,book_id,year_id,id,run_id,run_version,action,to_status,actor,reason,content_digest,source_digest) VALUES(t,b,y,eid,rid,1,'CREATE','DRAFT',who,payload->>'reason',plugin_finance_ap_digest(ct),plugin_finance_ap_digest(parent));
 INSERT INTO plugin_finance_ap_command_receipt(tenant_id,book_id,year_id,id,kind,run_id,transition_id,action,target_version,content_digest,actor,reason,to_state) VALUES(t,b,y,cid,'PAYABLE',rid,eid,CASE WHEN op='create' THEN 'CREATE' ELSE 'CREATE_FULL_REVERSAL' END,1,plugin_finance_ap_digest(ct),who,payload->>'reason','DRAFT');
 INSERT INTO plugin_finance_ap_audit(tenant_id,book_id,year_id,id,kind,run_id,transition_id,action,actor,payload) VALUES(t,b,y,gen_random_uuid(),'AP',rid,eid,CASE WHEN op='create' THEN 'CREATE' ELSE 'CREATE_FULL_REVERSAL' END,who,jsonb_build_object('commandReceiptId',cid,'reason',payload->>'reason'));
 RETURN jsonb_build_object('kind','PAYABLE','id',rid,'documentId',d,'payableId',rid,'commandReceiptId',cid,'newEffect',true);
END $$;

CREATE FUNCTION plugin_finance_ap_command(t uuid,b uuid,y uuid,who jsonb,op text,target uuid,payload jsonb) RETURNS jsonb LANGUAGE plpgsql AS $$
DECLARE r plugin_finance_ap_run%ROWTYPE;orig plugin_finance_ap_run%ROWTYPE;s plugin_finance_ap_submission%ROWTYPE;a plugin_finance_ap_approval%ROWTYPE;u plugin_finance_ap_usage%ROWTYPE;i plugin_finance_ap_open_item%ROWTYPE;slot plugin_finance_ap_source_slot%ROWTYPE;
 rid uuid;sid uuid;aid uuid;pid uuid;uid uuid;releaseid uuid;dep uuid;itemid uuid;adjustid uuid;cid uuid:=gen_random_uuid();eid uuid:=gen_random_uuid();ent uuid;v bigint;state text;seq bigint;num bigint;pd text;expiry timestamptz;cap numeric;used numeric;roles text[];
BEGIN
 IF op IS NULL OR op NOT IN('basis-create','basis-approve','basis-reject','basis-cancel','create','full-reversal','submit','approve','post','withdraw','reject','cancel','admin-terminate') OR jsonb_typeof(payload) IS DISTINCT FROM 'object' OR payload->>'schemaVersion' IS DISTINCT FROM 'payables-ap01/1' OR payload->>'reason' IS NULL OR length(payload->>'reason') NOT BETWEEN 1 AND 1000 OR payload->>'reason' !~'[^[:space:]]' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='INVALID_INPUT';END IF;
 PERFORM plugin_finance_ap_prelock(t);PERFORM plugin_finance_ap_actor(who,t);
 PERFORM pg_advisory_xact_lock(hashtextextended((who->>'accountId')||':'||(payload->>'idempotencyKey'),0));
 PERFORM plugin_finance_ap_scope(t,b,y,false,NULL);
 IF who IS DISTINCT FROM plugin_finance_journal_person_identity(t,(who->>'accountId')::uuid,true) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='JOURNAL_PERSON_IDENTITY_UNAVAILABLE';END IF;
 roles:=CASE WHEN op IN('basis-create','basis-cancel') THEN ARRAY['OWNER'] WHEN op IN('basis-approve','basis-reject','approve','reject','admin-terminate') THEN ARRAY['OWNER','REVIEWER'] WHEN op='post' THEN ARRAY['OWNER','POSTER'] ELSE ARRAY['OWNER','MAKER'] END;
 IF NOT plugin_finance_journal_person_role(t,b,y,(who->>'accountId')::uuid,roles) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SCOPE_DENIED';END IF;
 IF op LIKE 'basis-%' THEN RETURN plugin_finance_ap_basis_command(t,b,y,who,op,target,payload);END IF;
 IF op IN('create','full-reversal') THEN RETURN plugin_finance_ap_create(t,b,y,who,op,target,payload);END IF;
 SELECT * INTO r FROM plugin_finance_ap_run WHERE(tenant_id,book_id,year_id,id)=(t,b,y,target) FOR UPDATE;IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SCOPE_DENIED';END IF;
 rid:=r.id;v:=r.version+1;state:=r.status;sid:=r.current_submission_id;aid:=r.current_approval_id;
 IF r.version IS DISTINCT FROM(payload->>'expectedVersion')::bigint OR r.content_version IS DISTINCT FROM(payload->>'expectedContentVersion')::bigint OR r.content_digest IS DISTINCT FROM payload->>'contentDigest' OR r.source_digest IS DISTINCT FROM payload->>'sourceDigest' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='VERSION_CONFLICT';END IF;
 IF op IN('submit','withdraw','cancel') AND who->>'accountId'<>r.prepared_by->>'accountId' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SCOPE_DENIED';END IF;
 IF op IN('approve','reject','admin-terminate') AND(who->>'personId'=r.prepared_by->>'personId' OR who->>'personId'=r.last_editor->>'personId') THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SELF_APPROVAL_FORBIDDEN';END IF;
 IF op IN('submit','approve','post') THEN
 PERFORM plugin_finance_ap_scope(t,b,y,true,r.accounting_date);
 IF r.prepared_by IS DISTINCT FROM plugin_finance_journal_person_identity(t,(r.prepared_by->>'accountId')::uuid,true) OR NOT plugin_finance_journal_person_role(t,b,y,(r.prepared_by->>'accountId')::uuid,ARRAY['OWNER','MAKER']) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_APPROVAL_STALE';END IF;
 PERFORM plugin_finance_ap_require_fresh(t,b,y,rid);
 END IF;
 SELECT * INTO slot FROM plugin_finance_ap_source_slot WHERE(tenant_id,book_id,year_id,id)=(t,b,y,r.slot_id) FOR UPDATE;
 IF op='submit' THEN
 IF r.status<>'DRAFT' OR slot.latest_attempt_id<>r.id THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 expiry:=coalesce((payload->>'requestedExpiresAt')::timestamptz,CURRENT_TIMESTAMP+interval '7 days');IF expiry<=clock_timestamp() OR expiry>CURRENT_TIMESTAMP+interval '7 days' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='INVALID_INPUT';END IF;
 sid:=gen_random_uuid();INSERT INTO plugin_finance_ap_submission(tenant_id,book_id,year_id,id,run_id,run_version,content_digest,source_digest,actor,expires_at) VALUES(t,b,y,sid,rid,v,r.content_digest,r.source_digest,who,expiry);
 IF r.producer_kind='AP_EXPENSE' THEN
 IF slot.active_original_id IS NOT NULL THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_SOURCE_ALREADY_CLAIMED';END IF;
 SELECT capacity INTO cap FROM plugin_finance_ap_basis WHERE(tenant_id,book_id,year_id,id)=(t,b,y,r.basis_id);
 SELECT coalesce(sum(amount),0) INTO used FROM plugin_finance_ap_usage q WHERE(q.tenant_id,q.book_id,q.year_id,q.document_id)=(t,b,y,r.document_id) AND q.state IN('RESERVED','COMMITTED');IF used+r.amount>cap THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_CAPACITY_EXCEEDED';END IF;
 uid:=gen_random_uuid();dep:=gen_random_uuid();
 INSERT INTO plugin_finance_ap_usage(tenant_id,book_id,year_id,id,run_id,document_id,basis_id,submission_id,amount,state) VALUES(t,b,y,uid,rid,r.document_id,r.basis_id,sid,r.amount,'RESERVED');
 INSERT INTO plugin_finance_ap_parent_dependency(tenant_id,book_id,year_id,id,run_id,usage_id,parent_allocation_id,parent_confirmation_id,amount,state) VALUES(t,b,y,dep,rid,uid,r.parent_allocation_id,r.parent_confirmation_id,r.amount,'ACTIVE');
 UPDATE plugin_finance_ap_source_slot SET active_original_id=rid,active_usage_id=uid,version=version+1 WHERE(tenant_id,book_id,year_id,id)=(t,b,y,r.slot_id);
 ELSE IF slot.active_original_id IS DISTINCT FROM r.original_run_id THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_DEPENDENCY_PRESENT';END IF;
 END IF;state:='SUBMITTED';
 ELSIF op='approve' THEN
 IF r.status<>'SUBMITTED' OR sid IS DISTINCT FROM(payload->>'submissionId')::uuid THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 SELECT * INTO s FROM plugin_finance_ap_submission WHERE(tenant_id,book_id,year_id,run_id,id)=(t,b,y,rid,sid);IF s.expires_at<=clock_timestamp() THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_SUBMISSION_EXPIRED';END IF;
 expiry:=coalesce((payload->>'requestedExpiresAt')::timestamptz,least(CURRENT_TIMESTAMP+interval '7 days',s.expires_at));IF expiry<=clock_timestamp() OR expiry>s.expires_at OR expiry>CURRENT_TIMESTAMP+interval '7 days' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='INVALID_INPUT';END IF;
 aid:=gen_random_uuid();INSERT INTO plugin_finance_ap_approval(tenant_id,book_id,year_id,id,run_id,submission_id,run_version,content_digest,source_digest,actor,expires_at) VALUES(t,b,y,aid,rid,sid,v,r.content_digest,r.source_digest,who,expiry);state:='APPROVED';
 ELSIF op='post' THEN
 IF r.status<>'APPROVED' OR aid IS DISTINCT FROM(payload->>'approvalId')::uuid OR sid IS DISTINCT FROM(payload->>'submissionId')::uuid THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_APPROVAL_STALE';END IF;
 SELECT * INTO a FROM plugin_finance_ap_approval WHERE(tenant_id,book_id,year_id,id)=(t,b,y,aid);SELECT * INTO s FROM plugin_finance_ap_submission WHERE(tenant_id,book_id,year_id,id)=(t,b,y,sid);
 IF a.expires_at<=clock_timestamp() THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_APPROVAL_EXPIRED';END IF;IF s.expires_at<=clock_timestamp() THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_SUBMISSION_EXPIRED';END IF;
 IF a.actor IS DISTINCT FROM plugin_finance_journal_person_identity(t,(a.actor->>'accountId')::uuid,true) OR NOT plugin_finance_journal_person_role(t,b,y,(a.actor->>'accountId')::uuid,ARRAY['OWNER','REVIEWER']) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_APPROVAL_STALE';END IF;
 UPDATE plugin_finance_year_guard SET posting_seq=posting_seq+1 WHERE(tenant_id,book_id,year_id)=(t,b,y) RETURNING posting_seq INTO seq;
 SELECT coalesce(max(number),0)+1 INTO num FROM plugin_finance_journal WHERE(tenant_id,book_id,year_id)=(t,b,y);
 pd:=plugin_finance_ap_digest(jsonb_build_object('format','AP_POST_PG_V1','runId',rid,'contentDigest',r.content_digest,'sourceDigest',r.source_digest,'approvalId',aid,'approvedVersion',r.version::text));
 INSERT INTO plugin_finance_posting(tenant_id,book_id,year_id,journal_id,journal_version,digest,posted_by,posting_seq) VALUES(t,b,y,r.journal_id,r.version,pd,(who->>'accountId')::uuid,seq);
 pid:=gen_random_uuid();
 IF r.producer_kind='AP_EXPENSE' THEN SELECT * INTO u FROM plugin_finance_ap_usage q WHERE(q.tenant_id,q.book_id,q.year_id,q.run_id,q.submission_id)=(t,b,y,rid,sid) AND q.state='RESERVED' FOR UPDATE;IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;uid:=u.id;END IF;
 INSERT INTO plugin_finance_ap_post(tenant_id,book_id,year_id,id,run_id,journal_id,submission_id,approval_id,usage_id,run_version,posting_seq,posting_digest,content_digest,source_digest,actor) VALUES(t,b,y,pid,rid,r.journal_id,sid,aid,uid,v,seq,pd,r.content_digest,r.source_digest,who);
 IF r.producer_kind='AP_EXPENSE' THEN
 itemid:=gen_random_uuid();INSERT INTO plugin_finance_ap_open_item(tenant_id,legal_entity_id,book_id,year_id,id,run_id,post_id,usage_id,supplier_id,supplier_revision_id,amount,due_date) VALUES(t,r.legal_entity_id,b,y,itemid,rid,pid,uid,r.supplier_id,r.supplier_revision_id,r.amount,r.due_date);
 UPDATE plugin_finance_ap_usage SET state='COMMITTED',post_id=pid WHERE(tenant_id,book_id,year_id,id)=(t,b,y,uid);
 UPDATE plugin_finance_ap_source_slot SET active_open_item_id=itemid,version=version+1 WHERE(tenant_id,book_id,year_id,id)=(t,b,y,r.slot_id);
 ELSE
 SELECT * INTO orig FROM plugin_finance_ap_run WHERE(tenant_id,book_id,year_id,id)=(t,b,y,r.original_run_id);SELECT * INTO i FROM plugin_finance_ap_open_item WHERE(tenant_id,book_id,year_id,run_id)=(t,b,y,orig.id) FOR UPDATE;
 IF i.reverse_post_id IS NOT NULL OR i.version::text IS DISTINCT FROM r.content->'reversalBasis'->>'originalOpenItemVersion' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_DEPENDENCY_PRESENT';END IF;
 adjustid:=gen_random_uuid();INSERT INTO plugin_finance_ap_reverse_effect(tenant_id,book_id,year_id,id,run_id,post_id,original_run_id,original_post_id,original_usage_id,open_item_id,amount) VALUES(t,b,y,adjustid,rid,pid,orig.id,orig.post_id,i.usage_id,i.id,r.amount);
 UPDATE plugin_finance_ap_usage SET state='FULLY_REVERSED',reverse_post_id=pid WHERE(tenant_id,book_id,year_id,id)=(t,b,y,i.usage_id) AND plugin_finance_ap_usage.state='COMMITTED';
 UPDATE plugin_finance_ap_open_item SET version=2,reverse_post_id=pid WHERE(tenant_id,book_id,year_id,id)=(t,b,y,i.id);
 UPDATE plugin_finance_ap_parent_dependency SET state='RELEASED' WHERE(tenant_id,book_id,year_id,run_id)=(t,b,y,orig.id) AND plugin_finance_ap_parent_dependency.state='ACTIVE';
 UPDATE plugin_finance_ap_source_slot SET active_original_id=NULL,active_usage_id=NULL,active_open_item_id=NULL,version=version+1 WHERE(tenant_id,book_id,year_id,id)=(t,b,y,r.slot_id);
 END IF;state:='POSTED';
 ELSE
 IF op='withdraw' THEN IF r.status<>'SUBMITTED' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;state:='DRAFT';
 ELSIF op='reject' THEN IF r.status NOT IN('DRAFT','SUBMITTED','APPROVED') THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;state:='DRAFT';
 ELSIF op='cancel' THEN IF r.status<>'DRAFT' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;state:='CANCELLED';
 ELSIF op='admin-terminate' THEN IF r.status NOT IN('DRAFT','SUBMITTED') THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;state:='CANCELLED';
 ELSE RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='INVALID_INPUT';END IF;
 SELECT * INTO u FROM plugin_finance_ap_usage q WHERE(q.tenant_id,q.book_id,q.year_id,q.run_id,q.submission_id)=(t,b,y,rid,sid) AND q.state='RESERVED' FOR UPDATE;uid:=u.id;
 releaseid:=gen_random_uuid();INSERT INTO plugin_finance_ap_release(tenant_id,book_id,year_id,id,run_id,submission_id,usage_id,action,run_version,actor,reason) VALUES(t,b,y,releaseid,rid,sid,uid,upper(replace(op,'-','_')),v,who,payload->>'reason');
 IF uid IS NOT NULL THEN
 UPDATE plugin_finance_ap_usage SET state='RELEASED',release_id=releaseid WHERE(tenant_id,book_id,year_id,id)=(t,b,y,uid);
 UPDATE plugin_finance_ap_parent_dependency SET state='RELEASED' WHERE(tenant_id,book_id,year_id,run_id)=(t,b,y,rid) AND plugin_finance_ap_parent_dependency.state='ACTIVE';
 UPDATE plugin_finance_ap_source_slot SET active_original_id=NULL,active_usage_id=NULL,active_open_item_id=NULL,version=version+1 WHERE(tenant_id,book_id,year_id,id,active_original_id)=(t,b,y,r.slot_id,rid);
 END IF;
 END IF;
 UPDATE plugin_finance_journal SET status=state,version=v,approved_by=CASE WHEN state='APPROVED' THEN(who->>'accountId')::uuid WHEN state='POSTED' THEN approved_by ELSE NULL END,approved_version=CASE WHEN state='APPROVED' THEN v WHEN state='POSTED' THEN approved_version ELSE NULL END,number=CASE WHEN state='POSTED' THEN num ELSE number END,reversal_of=CASE WHEN state='POSTED' AND r.producer_kind='AP_EXPENSE_REVERSAL' THEN(SELECT journal_id FROM plugin_finance_ap_run WHERE(tenant_id,book_id,year_id,id)=(t,b,y,r.original_run_id)) ELSE NULL END WHERE(tenant_id,book_id,year_id,id)=(t,b,y,r.journal_id);
 UPDATE plugin_finance_ap_run SET status=state,version=v,current_submission_id=CASE WHEN state IN('SUBMITTED','APPROVED','POSTED') THEN sid ELSE NULL END,current_approval_id=CASE WHEN state IN('APPROVED','POSTED') THEN aid ELSE NULL END,post_id=pid,termination_id=CASE WHEN op='admin-terminate' THEN releaseid END WHERE(tenant_id,book_id,year_id,id)=(t,b,y,rid);
 INSERT INTO plugin_finance_ap_transition(tenant_id,book_id,year_id,id,run_id,run_version,previous_version,action,from_status,to_status,actor,reason,content_digest,source_digest,submission_id,approval_id,post_id,release_id) VALUES(t,b,y,eid,rid,v,r.version,upper(replace(op,'-','_')),r.status,state,who,payload->>'reason',r.content_digest,r.source_digest,CASE WHEN op IN('submit','approve','post') THEN sid END,CASE WHEN op IN('approve','post') THEN aid END,pid,releaseid);
 INSERT INTO plugin_finance_ap_command_receipt(tenant_id,book_id,year_id,id,kind,run_id,transition_id,action,target_version,content_digest,actor,reason,from_state,to_state,submission_id,approval_id,post_id,release_id) VALUES(t,b,y,cid,'PAYABLE',rid,eid,upper(replace(op,'-','_')),v,r.content_digest,who,payload->>'reason',r.status,state,CASE WHEN op IN('submit','approve','post') THEN sid END,CASE WHEN op IN('approve','post') THEN aid END,pid,releaseid);
 INSERT INTO plugin_finance_ap_audit(tenant_id,book_id,year_id,id,kind,run_id,transition_id,action,actor,payload) VALUES(t,b,y,gen_random_uuid(),'AP',rid,eid,upper(replace(op,'-','_')),who,jsonb_build_object('commandReceiptId',cid,'reason',payload->>'reason'));
 RETURN jsonb_build_object('kind','PAYABLE','id',rid,'documentId',r.document_id,'payableId',rid,'commandReceiptId',cid,'newEffect',true);
END $$;

CREATE FUNCTION plugin_finance_ap_run_source_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE original plugin_finance_ap_run%ROWTYPE;sl plugin_finance_ap_source_slot%ROWTYPE;latest plugin_finance_ap_run%ROWTYPE;parent jsonb;supp jsonb;basis jsonb;ls jsonb;one jsonb;two jsonb;n numeric;doc plugin_finance_evidence_document%ROWTYPE;
BEGIN
 IF TG_OP<>'INSERT' THEN RETURN NEW;END IF;
 PERFORM plugin_finance_ap_scope(NEW.tenant_id,NEW.book_id,NEW.year_id,true,NEW.accounting_date);
 IF NEW.prepared_by IS DISTINCT FROM plugin_finance_journal_person_identity(NEW.tenant_id,(NEW.prepared_by->>'accountId')::uuid,true) OR NEW.last_editor IS DISTINCT FROM NEW.prepared_by OR NOT plugin_finance_journal_person_role(NEW.tenant_id,NEW.book_id,NEW.year_id,(NEW.prepared_by->>'accountId')::uuid,ARRAY['OWNER','MAKER']) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_APPROVAL_STALE';END IF;
 SELECT * INTO sl FROM plugin_finance_ap_source_slot WHERE(tenant_id,book_id,year_id,id)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.slot_id) FOR UPDATE;
 SELECT * INTO latest FROM plugin_finance_ap_run WHERE(tenant_id,book_id,year_id,id)=(NEW.tenant_id,NEW.book_id,NEW.year_id,sl.latest_attempt_id);
 IF NEW.predecessor_id IS DISTINCT FROM sl.latest_attempt_id OR NEW.predecessor_version IS DISTINCT FROM latest.version OR NEW.parent_allocation_id<>sl.parent_allocation_id THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 IF NEW.content->>'format' IS DISTINCT FROM 'AP_PERSON_CONTENT_PG_V1' OR NEW.content->>'producerKind' IS DISTINCT FROM NEW.producer_kind OR NEW.content->>'rawType' IS DISTINCT FROM NEW.producer_kind OR NEW.content->'source' IS DISTINCT FROM NEW.source_snapshot OR(NEW.content->>'amount')::numeric IS DISTINCT FROM NEW.amount OR(NEW.content->>'accountingDate')::date IS DISTINCT FROM NEW.accounting_date OR(NEW.content->>'dueDate')::date IS DISTINCT FROM NEW.due_date THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 IF NEW.producer_kind='AP_EXPENSE' THEN
 IF sl.active_original_id IS NOT NULL OR(latest.id IS NOT NULL AND latest.status<>'CANCELLED' AND NOT(latest.producer_kind='AP_EXPENSE_REVERSAL' AND latest.status='POSTED')) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_SOURCE_ALREADY_CLAIMED';END IF;
 parent:=plugin_finance_ap_parent(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.parent_allocation_id);
 basis:=plugin_finance_ap_basis_expectation(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.document_id);
 supp:=plugin_finance_ap_supplier(NEW.tenant_id,NEW.legal_entity_id,NEW.supplier_id);
 IF parent IS DISTINCT FROM NEW.source_snapshot OR(parent->>'netConfirmedAmount')::numeric<>NEW.amount OR(parent->>'documentId')::uuid<>NEW.document_id OR(parent->>'confirmationId')::uuid<>NEW.parent_confirmation_id OR basis IS DISTINCT FROM NEW.content->'accountingBasis' OR(basis->>'basisId')::uuid<>NEW.basis_id OR supp IS DISTINCT FROM NEW.content->'supplier' OR(supp->'expected'->>'approvedRevisionId')::uuid<>NEW.supplier_revision_id OR NEW.content->'reversalBasis'<>'null'::jsonb OR NEW.content->>'reversesPayableId' IS NOT NULL OR NEW.content->>'reversesJournalId' IS NOT NULL THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_SOURCE_STALE';END IF;
 ls:=NEW.content->'generatedLines';IF jsonb_typeof(ls) IS DISTINCT FROM 'array' OR jsonb_array_length(ls)<>2 THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_UNSUPPORTED_PATH';END IF;
 one:=ls->0;two:=ls->1;
 IF(one->>'lineNo')::integer<>1 OR(two->>'lineNo')::integer<>2 OR(one->>'debit')::numeric<>NEW.amount OR(one->>'credit')::numeric<>0 OR(two->>'debit')::numeric<>0 OR(two->>'credit')::numeric<>NEW.amount OR one->'account'->>'category'<>'EXPENSE' OR one->'account'->>'normalSide'<>'DEBIT' OR two->'account'->>'category'<>'LIABILITY' OR two->'account'->>'normalSide'<>'CREDIT' OR two->'account'->>'purpose'<>'NONE' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_UNSUPPORTED_PATH';END IF;
 FOR one IN SELECT value FROM jsonb_array_elements(ls) LOOP IF plugin_finance_ap_account(NEW.tenant_id,NEW.book_id,NEW.year_id,(one->'account'->>'id')::uuid) IS DISTINCT FROM one->'account' OR one->'account'->>'enabled'<>'true' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_SOURCE_STALE';END IF;END LOOP;
 IF NEW.content->'ordinaryServiceExpenseConfirmed' IS DISTINCT FROM 'true'::jsonb OR NEW.content->'supplierMatchesDocumentConfirmed' IS DISTINCT FROM 'true'::jsonb OR NEW.content->'serviceAcceptedConfirmed' IS DISTINCT FROM 'true'::jsonb OR NEW.content->'noTaxAssetInventoryPayrollConfirmed' IS DISTINCT FROM 'true'::jsonb OR NEW.content->'payableAccountUseConfirmed' IS DISTINCT FROM 'true'::jsonb THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='INVALID_INPUT';END IF;
 ELSE
 SELECT * INTO original FROM plugin_finance_ap_run WHERE(tenant_id,book_id,year_id,id)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.original_run_id);
 IF (latest.id IS DISTINCT FROM original.id AND NOT(latest.producer_kind='AP_EXPENSE_REVERSAL' AND latest.original_run_id=original.id AND latest.status='CANCELLED')) OR original.status<>'POSTED' OR original.producer_kind<>'AP_EXPENSE' OR sl.active_original_id IS DISTINCT FROM original.id OR original.amount<>NEW.amount OR original.accounting_date<>NEW.accounting_date OR original.source_snapshot IS DISTINCT FROM NEW.source_snapshot OR original.basis_id<>NEW.basis_id OR original.supplier_id<>NEW.supplier_id OR original.supplier_revision_id<>NEW.supplier_revision_id OR NEW.content->'accountingBasis' IS DISTINCT FROM original.content->'accountingBasis' OR NEW.content->'supplier' IS DISTINCT FROM original.content->'supplier' OR(NEW.content->>'reversesPayableId')::uuid IS DISTINCT FROM original.id OR(NEW.content->>'reversesJournalId')::uuid IS DISTINCT FROM original.journal_id THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_DEPENDENCY_PRESENT';END IF;
 IF jsonb_array_length(NEW.content->'generatedLines')<>2 THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 FOR one IN SELECT value FROM jsonb_array_elements(NEW.content->'generatedLines') LOOP
 SELECT value INTO two FROM jsonb_array_elements(original.content->'generatedLines') WHERE value->>'lineNo'=one->>'lineNo';
 IF NOT FOUND OR one->'account'->>'id' IS DISTINCT FROM two->'account'->>'id' OR one->>'debit' IS DISTINCT FROM two->>'credit' OR one->>'credit' IS DISTINCT FROM two->>'debit' OR((one->'account')-ARRAY['name','code','semanticDigest']) IS DISTINCT FROM((two->'account')-ARRAY['name','code','semanticDigest']) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 END LOOP;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER finance_ap_run_source BEFORE INSERT ON plugin_finance_ap_run FOR EACH ROW EXECUTE FUNCTION plugin_finance_ap_run_source_guard();

CREATE FUNCTION plugin_finance_ap_visible() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 UPDATE plugin_finance_ap_guard SET generation=generation+1 WHERE(tenant_id,book_id,year_id)=(NEW.tenant_id,NEW.book_id,NEW.year_id);RETURN NEW;
END $$;
DO $$ DECLARE tab text;BEGIN FOREACH tab IN ARRAY ARRAY['basis','basis_head','source_slot','run','usage','open_item','reverse_effect'] LOOP
 EXECUTE format('CREATE TRIGGER finance_ap_%s_visible AFTER INSERT OR UPDATE ON plugin_finance_ap_%s FOR EACH ROW EXECUTE FUNCTION plugin_finance_ap_visible()',tab,tab);
 END LOOP;END $$;

CREATE FUNCTION plugin_finance_ap_transition_protocol() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r plugin_finance_ap_run%ROWTYPE;prev plugin_finance_ap_transition%ROWTYPE;roles text[];
BEGIN
 SELECT * INTO r FROM plugin_finance_ap_run WHERE(tenant_id,book_id,year_id,id)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.run_id);
 IF NOT FOUND OR NEW.run_version<>r.version OR NEW.to_status<>r.status OR NEW.content_digest<>r.content_digest OR NEW.source_digest<>r.source_digest OR NEW.created_at IS DISTINCT FROM CURRENT_TIMESTAMP THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 IF NEW.action='CREATE' THEN
 IF NEW.run_version<>1 OR NEW.from_status IS NOT NULL OR NEW.to_status<>'DRAFT' OR NEW.actor IS DISTINCT FROM r.prepared_by OR NEW.submission_id IS NOT NULL OR NEW.approval_id IS NOT NULL OR NEW.post_id IS NOT NULL OR NEW.release_id IS NOT NULL THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 ELSE
 SELECT * INTO prev FROM plugin_finance_ap_transition WHERE(tenant_id,book_id,year_id,run_id,run_version)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.run_id,NEW.previous_version);
 IF NOT FOUND OR prev.to_status IS DISTINCT FROM NEW.from_status OR NOT((NEW.action='SUBMIT' AND NEW.from_status='DRAFT' AND NEW.to_status='SUBMITTED') OR(NEW.action='APPROVE' AND NEW.from_status='SUBMITTED' AND NEW.to_status='APPROVED') OR(NEW.action='POST' AND NEW.from_status='APPROVED' AND NEW.to_status='POSTED') OR(NEW.action='WITHDRAW' AND NEW.from_status='SUBMITTED' AND NEW.to_status='DRAFT') OR(NEW.action='REJECT' AND NEW.from_status IN('DRAFT','SUBMITTED','APPROVED') AND NEW.to_status='DRAFT') OR(NEW.action='CANCEL' AND NEW.from_status='DRAFT' AND NEW.to_status='CANCELLED') OR(NEW.action='ADMIN_TERMINATE' AND NEW.from_status IN('DRAFT','SUBMITTED') AND NEW.to_status='CANCELLED')) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 END IF;
 roles:=CASE WHEN NEW.action IN('APPROVE','REJECT','ADMIN_TERMINATE') THEN ARRAY['OWNER','REVIEWER'] WHEN NEW.action='POST' THEN ARRAY['OWNER','POSTER'] ELSE ARRAY['OWNER','MAKER'] END;
 IF NEW.actor IS DISTINCT FROM plugin_finance_journal_person_identity(NEW.tenant_id,(NEW.actor->>'accountId')::uuid,true) OR NOT plugin_finance_journal_person_role(NEW.tenant_id,NEW.book_id,NEW.year_id,(NEW.actor->>'accountId')::uuid,roles) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SCOPE_DENIED';END IF;
 IF NEW.action IN('SUBMIT','WITHDRAW','CANCEL') AND NEW.actor->>'accountId' IS DISTINCT FROM r.prepared_by->>'accountId' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SCOPE_DENIED';END IF;
 IF NEW.action IN('APPROVE','REJECT','ADMIN_TERMINATE') AND(NEW.actor->>'personId'=r.prepared_by->>'personId' OR NEW.actor->>'personId'=r.last_editor->>'personId') THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SELF_APPROVAL_FORBIDDEN';END IF;
 IF NEW.action IN('SUBMIT','APPROVE','POST') THEN
 IF NOT plugin_finance_ap_fresh(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.run_id) AND NEW.action<>'POST' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_SOURCE_STALE';END IF;
 IF NEW.submission_id IS DISTINCT FROM r.current_submission_id OR(NEW.action IN('APPROVE','POST') AND NEW.approval_id IS DISTINCT FROM r.current_approval_id) OR(NEW.action='POST' AND NEW.post_id IS DISTINCT FROM r.post_id) OR NEW.release_id IS NOT NULL THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 ELSE
 IF NEW.action<>'CREATE' AND NEW.release_id IS NULL THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER finance_ap_transition_protocol BEFORE INSERT ON plugin_finance_ap_transition FOR EACH ROW EXECUTE FUNCTION plugin_finance_ap_transition_protocol();

ALTER TABLE plugin_finance_ap_run ADD COLUMN service_original_id uuid GENERATED ALWAYS AS((content->'serviceAcceptanceOriginal'->>'id')::uuid) STORED,ADD COLUMN recognition_original_id uuid GENERATED ALWAYS AS((content->'recognitionOriginal'->>'id')::uuid) STORED;
ALTER TABLE plugin_finance_ap_run ADD CHECK(service_original_id IS NOT NULL AND recognition_original_id IS NOT NULL),ADD FOREIGN KEY(tenant_id,book_id,year_id,service_original_id) REFERENCES plugin_finance_original(tenant_id,book_id,year_id,id),ADD FOREIGN KEY(tenant_id,book_id,year_id,recognition_original_id) REFERENCES plugin_finance_original(tenant_id,book_id,year_id,id);
ALTER TABLE plugin_finance_ap_basis ADD COLUMN basis_original_id uuid GENERATED ALWAYS AS((content->'basisOriginal'->>'id')::uuid) STORED;
ALTER TABLE plugin_finance_ap_basis ADD CHECK(basis_original_id IS NOT NULL),ADD FOREIGN KEY(tenant_id,book_id,year_id,basis_original_id) REFERENCES plugin_finance_original(tenant_id,book_id,year_id,id);
ALTER TABLE plugin_finance_ap_audit ADD COLUMN command_receipt_id uuid GENERATED ALWAYS AS((payload->>'commandReceiptId')::uuid) STORED;
ALTER TABLE plugin_finance_ap_audit ADD CHECK(command_receipt_id IS NOT NULL),ADD FOREIGN KEY(tenant_id,book_id,year_id,command_receipt_id) REFERENCES plugin_finance_ap_command_receipt(tenant_id,book_id,year_id,id);
CREATE UNIQUE INDEX finance_ap_audit_receipt ON plugin_finance_ap_audit(tenant_id,book_id,year_id,command_receipt_id);
CREATE UNIQUE INDEX finance_ap_receipt_transition ON plugin_finance_ap_command_receipt(tenant_id,book_id,year_id,transition_id) WHERE transition_id IS NOT NULL;
CREATE UNIQUE INDEX finance_ap_receipt_basis_version ON plugin_finance_ap_command_receipt(tenant_id,book_id,year_id,basis_id,target_version) WHERE basis_id IS NOT NULL;
DO $$ DECLARE tab text;BEGIN FOREACH tab IN ARRAY ARRAY['run','usage','parent_dependency','open_item','reverse_effect'] LOOP EXECUTE format('ALTER TABLE %I ADD CHECK(amount::text NOT IN(''NaN'',''Infinity'',''-Infinity''))','plugin_finance_ap_'||tab);END LOOP;END $$;
ALTER TABLE plugin_finance_ap_basis ADD CHECK(capacity::text NOT IN('NaN','Infinity','-Infinity') AND face_gross::text NOT IN('NaN','Infinity','-Infinity'));

CREATE UNIQUE INDEX finance_ap_basis_revision ON plugin_finance_ap_basis(tenant_id,book_id,year_id,document_id,revision_no);
CREATE FUNCTION plugin_finance_ap_basis_protocol() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE doc plugin_finance_evidence_document%ROWTYPE;review plugin_finance_evidence_review%ROWTYPE;head plugin_finance_ap_basis_head%ROWTYPE;previous plugin_finance_ap_basis%ROWTYPE;who jsonb;net numeric;
BEGIN
 PERFORM plugin_finance_ap_scope(NEW.tenant_id,NEW.book_id,NEW.year_id,false,NULL);
 IF TG_OP='INSERT' THEN
 who:=NEW.prepared_by;
 IF who IS DISTINCT FROM plugin_finance_journal_person_identity(NEW.tenant_id,(who->>'accountId')::uuid,true) OR NOT plugin_finance_journal_person_role(NEW.tenant_id,NEW.book_id,NEW.year_id,(who->>'accountId')::uuid,ARRAY['OWNER']) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SCOPE_DENIED';END IF;
 SELECT * INTO head FROM plugin_finance_ap_basis_head WHERE(tenant_id,book_id,year_id,document_id)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.document_id);
 SELECT * INTO previous FROM plugin_finance_ap_basis WHERE(tenant_id,book_id,year_id,id)=(NEW.tenant_id,NEW.book_id,NEW.year_id,head.latest_basis_id);
 IF head.pending_basis_id IS NOT NULL OR NEW.previous_basis_id IS DISTINCT FROM head.latest_basis_id OR NEW.base_approved_basis_id IS DISTINCT FROM head.current_basis_id OR NEW.revision_no<>coalesce(previous.revision_no,0)+1 THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_BASIS_STALE';END IF;
 ELSE
 who:=NEW.decided_by;
 IF who IS DISTINCT FROM plugin_finance_journal_person_identity(NEW.tenant_id,(who->>'accountId')::uuid,true) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='JOURNAL_PERSON_IDENTITY_UNAVAILABLE';END IF;
 IF NEW.status='CANCELLED' THEN
 IF who->>'accountId' IS DISTINCT FROM NEW.prepared_by->>'accountId' OR NOT plugin_finance_journal_person_role(NEW.tenant_id,NEW.book_id,NEW.year_id,(who->>'accountId')::uuid,ARRAY['OWNER']) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SCOPE_DENIED';END IF;
 ELSE
 IF who->>'personId'=NEW.prepared_by->>'personId' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SELF_APPROVAL_FORBIDDEN';END IF;
 IF NOT plugin_finance_journal_person_role(NEW.tenant_id,NEW.book_id,NEW.year_id,(who->>'accountId')::uuid,ARRAY['OWNER','REVIEWER']) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SCOPE_DENIED';END IF;
 END IF;
 IF NEW.status='APPROVED' THEN
 IF NEW.prepared_by IS DISTINCT FROM plugin_finance_journal_person_identity(NEW.tenant_id,(NEW.prepared_by->>'accountId')::uuid,true) OR NOT plugin_finance_journal_person_role(NEW.tenant_id,NEW.book_id,NEW.year_id,(NEW.prepared_by->>'accountId')::uuid,ARRAY['OWNER']) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_APPROVAL_STALE';END IF;
 IF EXISTS(SELECT 1 FROM plugin_finance_ap_usage u WHERE(u.tenant_id,u.book_id,u.year_id,u.document_id)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.document_id) AND u.state='RESERVED') THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_ACTIVE_RESERVATIONS';END IF;
 SELECT coalesce(sum(u.amount),0) INTO net FROM plugin_finance_ap_usage u WHERE(u.tenant_id,u.book_id,u.year_id,u.document_id)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.document_id) AND u.state='COMMITTED';
 IF NEW.capacity<net THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_CAPACITY_BELOW_COMMITTED';END IF;
 END IF;
 END IF;
 IF TG_OP='INSERT' OR NEW.status='APPROVED' THEN
 SELECT * INTO doc FROM plugin_finance_evidence_document WHERE(tenant_id,book_id,year_id,id)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.document_id);
 SELECT * INTO review FROM plugin_finance_evidence_review WHERE(tenant_id,book_id,year_id,id)=(NEW.tenant_id,NEW.book_id,NEW.year_id,doc.current_review_id);
 IF doc.pending_review_id IS NOT NULL OR NEW.content->'expectedDocument' IS DISTINCT FROM plugin_finance_ap_document(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.document_id) OR(NEW.content->>'accountingCapacity')::numeric IS DISTINCT FROM NEW.capacity OR(NEW.content->>'faceGrossSnapshot')::numeric IS DISTINCT FROM NEW.face_gross OR NEW.face_gross IS DISTINCT FROM(review.content->>'faceGross')::numeric OR NEW.capacity>NEW.face_gross OR NEW.content->'organizationReviewedNotTaxVerified' IS DISTINCT FROM 'true'::jsonb OR NEW.content->'basisOriginal' IS DISTINCT FROM plugin_finance_ap_original(NEW.tenant_id,NEW.book_id,NEW.year_id,(NEW.content->'basisOriginal'->>'id')::uuid) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_BASIS_STALE';END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER finance_ap_basis_protocol BEFORE INSERT OR UPDATE ON plugin_finance_ap_basis FOR EACH ROW EXECUTE FUNCTION plugin_finance_ap_basis_protocol();
CREATE FUNCTION plugin_finance_ap_basis_proof_final() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v plugin_finance_ap_basis%ROWTYPE;head plugin_finance_ap_basis_head%ROWTYPE;receipt plugin_finance_ap_command_receipt%ROWTYPE;d uuid;
BEGIN
 d:=NEW.document_id;
 SELECT * INTO head FROM plugin_finance_ap_basis_head WHERE(tenant_id,book_id,year_id,document_id)=(NEW.tenant_id,NEW.book_id,NEW.year_id,d);
 IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_BASIS_STALE';END IF;
 IF head.current_basis_id IS DISTINCT FROM(SELECT q.id FROM plugin_finance_ap_basis q WHERE(q.tenant_id,q.book_id,q.year_id,q.document_id)=(head.tenant_id,head.book_id,head.year_id,d) AND q.status='APPROVED' ORDER BY q.revision_no DESC LIMIT 1) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_BASIS_STALE';END IF;
 IF EXISTS(SELECT 1 FROM plugin_finance_ap_basis q JOIN plugin_finance_ap_basis latest ON(latest.tenant_id,latest.book_id,latest.year_id,latest.id)=(head.tenant_id,head.book_id,head.year_id,head.latest_basis_id) WHERE(q.tenant_id,q.book_id,q.year_id,q.document_id)=(head.tenant_id,head.book_id,head.year_id,d) AND q.revision_no>latest.revision_no) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_BASIS_STALE';END IF;
 FOR v IN SELECT * FROM plugin_finance_ap_basis q WHERE(q.tenant_id,q.book_id,q.year_id,q.document_id)=(NEW.tenant_id,NEW.book_id,NEW.year_id,d) AND q.id IN(head.latest_basis_id,head.current_basis_id,head.pending_basis_id,CASE WHEN TG_TABLE_NAME='plugin_finance_ap_basis' THEN(to_jsonb(NEW)->>'id')::uuid ELSE NULL END) LOOP
 SELECT * INTO receipt FROM plugin_finance_ap_command_receipt q WHERE(q.tenant_id,q.book_id,q.year_id,q.basis_id,q.target_version)=(v.tenant_id,v.book_id,v.year_id,v.id,1);
 IF NOT FOUND OR receipt.action<>'CREATE_BASIS' OR receipt.actor IS DISTINCT FROM v.prepared_by OR receipt.to_state<>'PENDING' OR receipt.from_state IS NOT NULL OR receipt.created_at IS DISTINCT FROM v.created_at OR receipt.content_digest<>v.content_digest THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 IF v.status<>'PENDING' THEN
 SELECT * INTO receipt FROM plugin_finance_ap_command_receipt q WHERE(q.tenant_id,q.book_id,q.year_id,q.basis_id,q.target_version)=(v.tenant_id,v.book_id,v.year_id,v.id,2);
 IF NOT FOUND OR receipt.action IS DISTINCT FROM (CASE v.status WHEN 'APPROVED' THEN 'APPROVE_BASIS' WHEN 'REJECTED' THEN 'REJECT_BASIS' ELSE 'CANCEL_BASIS' END) OR receipt.actor IS DISTINCT FROM v.decided_by OR receipt.from_state<>'PENDING' OR receipt.to_state<>v.status OR receipt.reason IS DISTINCT FROM v.decision_reason OR receipt.created_at IS DISTINCT FROM v.decided_at OR receipt.content_digest<>v.content_digest THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 END IF;
 END LOOP;
 RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER finance_ap_basis_proof_final AFTER INSERT OR UPDATE ON plugin_finance_ap_basis DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION plugin_finance_ap_basis_proof_final();
CREATE CONSTRAINT TRIGGER finance_ap_basis_head_proof_final AFTER INSERT OR UPDATE ON plugin_finance_ap_basis_head DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION plugin_finance_ap_basis_proof_final();

CREATE FUNCTION plugin_finance_ap_receipt_final() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE q plugin_finance_ap_command_receipt%ROWTYPE;e plugin_finance_ap_transition%ROWTYPE;r plugin_finance_ap_run%ROWTYPE;a plugin_finance_ap_audit%ROWTYPE;rid uuid;
BEGIN
 rid:=CASE WHEN TG_TABLE_NAME='plugin_finance_ap_command_receipt' THEN(to_jsonb(NEW)->>'id')::uuid ELSE(to_jsonb(NEW)->>'command_receipt_id')::uuid END;
 SELECT * INTO q FROM plugin_finance_ap_command_receipt WHERE(tenant_id,book_id,year_id,id)=(NEW.tenant_id,NEW.book_id,NEW.year_id,rid);
 IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 PERFORM plugin_finance_ap_actor(q.actor,q.tenant_id);
 SELECT * INTO a FROM plugin_finance_ap_audit WHERE(tenant_id,book_id,year_id,command_receipt_id)=(q.tenant_id,q.book_id,q.year_id,q.id);
 IF NOT FOUND OR a.action<>q.action OR a.actor IS DISTINCT FROM q.actor OR a.payload->>'reason' IS DISTINCT FROM q.reason OR a.created_at IS DISTINCT FROM q.created_at OR a.basis_id IS DISTINCT FROM q.basis_id OR a.run_id IS DISTINCT FROM q.run_id OR a.transition_id IS DISTINCT FROM q.transition_id THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 IF q.kind='PAYABLE' THEN
 SELECT * INTO e FROM plugin_finance_ap_transition WHERE(tenant_id,book_id,year_id,id)=(q.tenant_id,q.book_id,q.year_id,q.transition_id);
 SELECT * INTO r FROM plugin_finance_ap_run WHERE(tenant_id,book_id,year_id,id)=(q.tenant_id,q.book_id,q.year_id,q.run_id);
 IF e.run_id IS DISTINCT FROM q.run_id OR e.run_version<>q.target_version OR e.actor IS DISTINCT FROM q.actor OR e.reason<>q.reason OR e.from_status IS DISTINCT FROM q.from_state OR e.to_status<>q.to_state OR e.created_at IS DISTINCT FROM q.created_at OR e.content_digest<>q.content_digest OR e.submission_id IS DISTINCT FROM q.submission_id OR e.approval_id IS DISTINCT FROM q.approval_id OR e.post_id IS DISTINCT FROM q.post_id OR e.release_id IS DISTINCT FROM q.release_id OR NOT(q.action=e.action OR(e.action='CREATE' AND q.action='CREATE_FULL_REVERSAL')) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 IF q.target_version=1 AND r.create_receipt_id IS DISTINCT FROM q.id THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER finance_ap_receipt_final AFTER INSERT ON plugin_finance_ap_command_receipt DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION plugin_finance_ap_receipt_final();
CREATE CONSTRAINT TRIGGER finance_ap_audit_final AFTER INSERT ON plugin_finance_ap_audit DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION plugin_finance_ap_receipt_final();
CREATE FUNCTION plugin_finance_ap_transition_receipt_final() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE q plugin_finance_ap_command_receipt%ROWTYPE;
BEGIN
 SELECT * INTO q FROM plugin_finance_ap_command_receipt WHERE(tenant_id,book_id,year_id,transition_id)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.id);
 IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER finance_ap_transition_receipt_final AFTER INSERT ON plugin_finance_ap_transition DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION plugin_finance_ap_transition_receipt_final();

CREATE FUNCTION plugin_finance_ap_closed(v jsonb,keys text[]) RETURNS boolean LANGUAGE sql IMMUTABLE AS $$ SELECT jsonb_typeof(v)='object' AND(SELECT array_agg(k ORDER BY k) FROM jsonb_object_keys(v) k)=(SELECT array_agg(k ORDER BY k) FROM unnest(keys) k) $$;
CREATE FUNCTION plugin_finance_ap_text(v jsonb,maxlen integer) RETURNS boolean LANGUAGE sql IMMUTABLE AS $$ SELECT jsonb_typeof(v)='string' AND length(v#>>'{}') BETWEEN 1 AND maxlen AND(v#>>'{}')~'[^[:space:]]' AND(v#>>'{}')!~'[\x00-\x08\x0b\x0c\x0e-\x1f\x7f-\x9f]' $$;
CREATE FUNCTION plugin_finance_ap_content_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE ct jsonb;line jsonb;sl plugin_finance_ap_source_slot%ROWTYPE;latest plugin_finance_ap_run%ROWTYPE;original plugin_finance_ap_run%ROWTYPE;i plugin_finance_ap_open_item%ROWTYPE;dep uuid;expected jsonb;
BEGIN
 ct:=NEW.content;
 IF TG_TABLE_NAME='plugin_finance_ap_basis' THEN
 IF plugin_finance_ap_closed(ct,ARRAY['expectedDocument','faceGrossSnapshot','accountingCapacity','basisOriginal','recognitionEligibilityReason','organizationReviewedNotTaxVerified']) IS DISTINCT FROM true OR plugin_finance_ap_text(ct->'recognitionEligibilityReason',1000) IS DISTINCT FROM true THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='INVALID_INPUT';END IF;RETURN NEW;
 END IF;
 IF plugin_finance_ap_closed(ct,ARRAY['format','producerKind','rawType','source','slotAtPreparation','supplier','accountingBasis','accountingDate','dueDate','currency','amount','summary','serviceAcceptanceOriginal','recognitionOriginal','recognitionReason','ordinaryServiceExpenseConfirmed','supplierMatchesDocumentConfirmed','serviceAcceptedConfirmed','noTaxAssetInventoryPayrollConfirmed','generatedLines','reversesPayableId','reversesJournalId','reversalBasis','payableAccountUseConfirmed']) IS DISTINCT FROM true OR ct->>'currency' IS DISTINCT FROM 'CNY' OR plugin_finance_ap_text(ct->'summary',500) IS DISTINCT FROM true OR plugin_finance_ap_text(ct->'recognitionReason',1000) IS DISTINCT FROM true THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='INVALID_INPUT';END IF;
 IF ct->'serviceAcceptanceOriginal' IS DISTINCT FROM plugin_finance_ap_original(NEW.tenant_id,NEW.book_id,NEW.year_id,(ct->'serviceAcceptanceOriginal'->>'id')::uuid) OR ct->'recognitionOriginal' IS DISTINCT FROM plugin_finance_ap_original(NEW.tenant_id,NEW.book_id,NEW.year_id,(ct->'recognitionOriginal'->>'id')::uuid) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_SOURCE_STALE';END IF;
 FOR line IN SELECT value FROM jsonb_array_elements(ct->'generatedLines') LOOP
 IF plugin_finance_ap_closed(line,ARRAY['lineNo','account','debit','credit','memo']) IS DISTINCT FROM true OR plugin_finance_ap_text(line->'memo',500) IS DISTINCT FROM true OR plugin_finance_ap_account(NEW.tenant_id,NEW.book_id,NEW.year_id,(line->'account'->>'id')::uuid) IS DISTINCT FROM line->'account' OR line->'account'->>'enabled' IS DISTINCT FROM 'true' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_SOURCE_STALE';END IF;
 END LOOP;
 SELECT * INTO sl FROM plugin_finance_ap_source_slot WHERE(tenant_id,book_id,year_id,id)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.slot_id);
 SELECT * INTO latest FROM plugin_finance_ap_run WHERE(tenant_id,book_id,year_id,id)=(NEW.tenant_id,NEW.book_id,NEW.year_id,sl.latest_attempt_id);
 expected:=jsonb_build_object('parentAllocationId',NEW.parent_allocation_id,'chainId',sl.chain_id,'sourceSystem','V15_CONFIRMED_EXPENSE_AP_V1','purpose','ACCOUNTING_CONFIRMATION','slotId',sl.id,'slotVersion',sl.version::text,'latestAttemptId',sl.latest_attempt_id,'latestAttemptVersion',latest.version::text,'activeOriginalConfirmationId',sl.active_original_id,'activeRecognitionUsageId',sl.active_usage_id,'activeOpenItemId',sl.active_open_item_id);
 IF ct->'slotAtPreparation' IS DISTINCT FROM expected OR(NEW.initial_payload->>'operationId')::uuid IS DISTINCT FROM NEW.operation_id OR(NEW.initial_payload->>'expectedPredecessorId')::uuid IS DISTINCT FROM NEW.predecessor_id OR(NEW.initial_payload->>'expectedPredecessorVersion')::bigint IS DISTINCT FROM NEW.predecessor_version THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 IF NEW.producer_kind='AP_EXPENSE' THEN
 IF NEW.initial_payload->'expectedParent' IS DISTINCT FROM NEW.source_snapshot OR NEW.initial_payload->'expectedSupplier' IS DISTINCT FROM ct->'supplier'->'expected' OR NEW.initial_payload->'expectedAccountingBasis' IS DISTINCT FROM ct->'accountingBasis' OR(NEW.initial_payload->>'accountingDate')::date IS DISTINCT FROM NEW.accounting_date OR(NEW.initial_payload->>'dueDate')::date IS DISTINCT FROM NEW.due_date OR(NEW.initial_payload->>'expenseAccountId')::uuid IS DISTINCT FROM(ct->'generatedLines'->0->'account'->>'id')::uuid OR(NEW.initial_payload->>'payableAccountId')::uuid IS DISTINCT FROM(ct->'generatedLines'->1->'account'->>'id')::uuid OR NEW.initial_payload->>'summary' IS DISTINCT FROM ct->>'summary' OR NEW.initial_payload->>'recognitionReason' IS DISTINCT FROM ct->>'recognitionReason' OR(NEW.initial_payload->>'serviceAcceptanceOriginalId')::uuid IS DISTINCT FROM(ct->'serviceAcceptanceOriginal'->>'id')::uuid OR(NEW.initial_payload->>'recognitionOriginalId')::uuid IS DISTINCT FROM(ct->'recognitionOriginal'->>'id')::uuid THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 ELSE
 SELECT * INTO original FROM plugin_finance_ap_run WHERE(tenant_id,book_id,year_id,id)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.original_run_id);
 SELECT * INTO i FROM plugin_finance_ap_open_item WHERE(tenant_id,book_id,year_id,run_id)=(NEW.tenant_id,NEW.book_id,NEW.year_id,original.id);
 SELECT id INTO dep FROM plugin_finance_ap_parent_dependency WHERE(tenant_id,book_id,year_id,run_id,usage_id)=(NEW.tenant_id,NEW.book_id,NEW.year_id,original.id,i.usage_id) AND state='ACTIVE';
 expected:=jsonb_build_object('originalPayableId',original.id,'originalJournalId',original.journal_id,'originalPostReceiptId',original.post_id,'originalContentDigest',original.content_digest,'originalOpenItemId',i.id,'originalOpenItemVersion',i.version::text,'originalRecognitionUsageId',i.usage_id,'originalParentDependencyId',dep,'amount',original.amount::text);
 IF i.id IS NULL OR dep IS NULL OR i.reverse_post_id IS NOT NULL OR ct->'reversalBasis' IS DISTINCT FROM expected OR(NEW.initial_payload->>'originalPostReceiptId')::uuid IS DISTINCT FROM original.post_id OR NEW.initial_payload->>'expectedOriginalContentDigest' IS DISTINCT FROM original.content_digest OR(NEW.initial_payload->>'expectedOriginalVersion')::bigint IS DISTINCT FROM original.version OR(NEW.initial_payload->>'originalOpenItemId')::uuid IS DISTINCT FROM i.id OR(NEW.initial_payload->>'expectedOpenItemVersion')::bigint IS DISTINCT FROM i.version OR(NEW.initial_payload->>'accountingReasonOriginalId')::uuid IS DISTINCT FROM(ct->'recognitionOriginal'->>'id')::uuid OR NEW.initial_payload->>'reason' IS DISTINCT FROM ct->>'recognitionReason' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_DEPENDENCY_PRESENT';END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER finance_ap_basis_content BEFORE INSERT ON plugin_finance_ap_basis FOR EACH ROW EXECUTE FUNCTION plugin_finance_ap_content_guard();
CREATE TRIGGER finance_ap_run_content BEFORE INSERT ON plugin_finance_ap_run FOR EACH ROW EXECUTE FUNCTION plugin_finance_ap_content_guard();

CREATE FUNCTION plugin_finance_ap_mutable_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE oldj jsonb;newj jsonb;
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 oldj:=to_jsonb(OLD);newj:=to_jsonb(NEW);
 IF TG_TABLE_NAME='plugin_finance_ap_guard' THEN
 IF newj-'generation' IS DISTINCT FROM oldj-'generation' OR NEW.generation<>OLD.generation+1 THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 ELSIF TG_TABLE_NAME='plugin_finance_ap_source_slot' THEN
 IF(newj-ARRAY['latest_attempt_id','active_original_id','active_usage_id','active_open_item_id','version']) IS DISTINCT FROM(oldj-ARRAY['latest_attempt_id','active_original_id','active_usage_id','active_open_item_id','version']) OR NEW.version<>OLD.version+1 THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 ELSIF TG_TABLE_NAME='plugin_finance_ap_parent_dependency' THEN
 IF newj-'state' IS DISTINCT FROM oldj-'state' OR OLD.state<>'ACTIVE' OR NEW.state<>'RELEASED' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 ELSIF TG_TABLE_NAME='plugin_finance_ap_usage' THEN
 IF(newj-ARRAY['state','post_id','release_id','reverse_post_id']) IS DISTINCT FROM(oldj-ARRAY['state','post_id','release_id','reverse_post_id']) OR NOT((OLD.state='RESERVED' AND NEW.state IN('COMMITTED','RELEASED')) OR(OLD.state='COMMITTED' AND NEW.state='FULLY_REVERSED')) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 ELSIF TG_TABLE_NAME='plugin_finance_ap_open_item' THEN
 IF(newj-ARRAY['version','reverse_post_id']) IS DISTINCT FROM(oldj-ARRAY['version','reverse_post_id']) OR OLD.version<>1 OR NEW.version<>2 OR NEW.reverse_post_id IS NULL THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 END IF;RETURN NEW;
END $$;
CREATE TRIGGER finance_ap_guard_mutable BEFORE UPDATE OR DELETE ON plugin_finance_ap_guard FOR EACH ROW EXECUTE FUNCTION plugin_finance_ap_mutable_guard();
CREATE TRIGGER finance_ap_slot_mutable BEFORE UPDATE OR DELETE ON plugin_finance_ap_source_slot FOR EACH ROW EXECUTE FUNCTION plugin_finance_ap_mutable_guard();
CREATE TRIGGER finance_ap_parent_mutable BEFORE UPDATE OR DELETE ON plugin_finance_ap_parent_dependency FOR EACH ROW EXECUTE FUNCTION plugin_finance_ap_mutable_guard();
CREATE TRIGGER finance_ap_usage_mutable BEFORE UPDATE OR DELETE ON plugin_finance_ap_usage FOR EACH ROW EXECUTE FUNCTION plugin_finance_ap_mutable_guard();
CREATE TRIGGER finance_ap_item_mutable BEFORE UPDATE OR DELETE ON plugin_finance_ap_open_item FOR EACH ROW EXECUTE FUNCTION plugin_finance_ap_mutable_guard();
CREATE TRIGGER finance_ap_head_nodelete BEFORE DELETE ON plugin_finance_ap_basis_head FOR EACH ROW EXECUTE FUNCTION plugin_finance_ap_immutable();

ALTER TABLE plugin_finance_evidence_allocation ADD UNIQUE(tenant_id,book_id,year_id,id,chain_id);
ALTER TABLE plugin_finance_ap_source_slot ADD FOREIGN KEY(tenant_id,book_id,year_id,parent_allocation_id,chain_id) REFERENCES plugin_finance_evidence_allocation(tenant_id,book_id,year_id,id,chain_id);
CREATE FUNCTION plugin_finance_ap_child_proof_final() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r plugin_finance_ap_run%ROWTYPE;u plugin_finance_ap_usage%ROWTYPE;e plugin_finance_ap_transition%ROWTYPE;rel plugin_finance_ap_release%ROWTYPE;q jsonb;
BEGIN
 q:=to_jsonb(NEW);
 SELECT * INTO r FROM plugin_finance_ap_run WHERE(tenant_id,book_id,year_id,id)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.run_id);
 IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 IF TG_TABLE_NAME='plugin_finance_ap_parent_dependency' THEN
 SELECT to_jsonb(d) INTO q FROM plugin_finance_ap_parent_dependency d WHERE(d.tenant_id,d.book_id,d.year_id,d.id)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.id);
 SELECT * INTO u FROM plugin_finance_ap_usage WHERE(tenant_id,book_id,year_id,id)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.usage_id);
 IF u.run_id IS DISTINCT FROM r.id OR NEW.parent_allocation_id<>r.parent_allocation_id OR NEW.parent_confirmation_id<>r.parent_confirmation_id OR NEW.amount<>r.amount OR u.amount<>r.amount OR(q->>'state'='ACTIVE' AND u.state NOT IN('RESERVED','COMMITTED')) OR(q->>'state'='RELEASED' AND u.state NOT IN('RELEASED','FULLY_REVERSED')) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 ELSIF TG_TABLE_NAME='plugin_finance_ap_usage' THEN
 SELECT * INTO u FROM plugin_finance_ap_usage WHERE(tenant_id,book_id,year_id,id)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.id);
 IF r.producer_kind<>'AP_EXPENSE' OR u.document_id<>r.document_id OR u.basis_id<>r.basis_id OR u.amount<>r.amount THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 IF u.state='RELEASED' THEN
 SELECT * INTO rel FROM plugin_finance_ap_release WHERE(tenant_id,book_id,year_id,run_id,id)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.run_id,u.release_id);
 IF rel.usage_id IS DISTINCT FROM u.id OR rel.submission_id IS DISTINCT FROM u.submission_id THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 END IF;
 ELSIF TG_TABLE_NAME IN('plugin_finance_ap_submission','plugin_finance_ap_approval','plugin_finance_ap_release') THEN
 SELECT * INTO e FROM plugin_finance_ap_transition WHERE(tenant_id,book_id,year_id,run_id,run_version)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.run_id,(q->>'run_version')::bigint);
 IF e.id IS NULL OR e.actor IS DISTINCT FROM q->'actor' OR e.created_at IS DISTINCT FROM coalesce((q->>'submitted_at')::timestamptz,(q->>'approved_at')::timestamptz,(q->>'created_at')::timestamptz) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 IF TG_TABLE_NAME='plugin_finance_ap_submission' THEN
 IF e.action<>'SUBMIT' OR e.submission_id IS DISTINCT FROM NEW.id OR NEW.content_digest<>r.content_digest OR NEW.source_digest<>r.source_digest THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 ELSIF TG_TABLE_NAME='plugin_finance_ap_approval' THEN
 IF e.action<>'APPROVE' OR e.approval_id IS DISTINCT FROM NEW.id OR e.submission_id IS DISTINCT FROM NEW.submission_id OR NEW.content_digest<>r.content_digest OR NEW.source_digest<>r.source_digest THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_APPROVAL_STALE';END IF;
 ELSE
 IF e.release_id IS DISTINCT FROM NEW.id OR e.action<>NEW.action OR e.reason<>NEW.reason OR(e.from_status='DRAFT' AND(NEW.submission_id IS NOT NULL OR NEW.usage_id IS NOT NULL)) OR(e.from_status IN('SUBMITTED','APPROVED') AND NEW.submission_id IS NULL) OR(e.from_status IN('SUBMITTED','APPROVED') AND r.producer_kind='AP_EXPENSE' AND NEW.usage_id IS NULL) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_STATE_CONFLICT';END IF;
 END IF;
 END IF;RETURN NEW;
END $$;
DO $$ DECLARE tab text;BEGIN FOREACH tab IN ARRAY ARRAY['parent_dependency','usage','submission','approval','release'] LOOP EXECUTE format('CREATE CONSTRAINT TRIGGER finance_ap_%s_child_final AFTER INSERT OR UPDATE ON plugin_finance_ap_%s DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION plugin_finance_ap_child_proof_final()',tab,tab);END LOOP;END $$;

CREATE FUNCTION plugin_finance_ap_require_fresh(t uuid,b uuid,y uuid,rid uuid) RETURNS void LANGUAGE plpgsql STABLE AS $$
DECLARE r plugin_finance_ap_run%ROWTYPE;q plugin_finance_evidence_allocation%ROWTYPE;d plugin_finance_evidence_document%ROWTYPE;j jsonb;
BEGIN
 SELECT * INTO r FROM plugin_finance_ap_run WHERE(tenant_id,book_id,year_id,id)=(t,b,y,rid);IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SCOPE_DENIED';END IF;
 IF r.producer_kind='AP_EXPENSE_REVERSAL' THEN IF NOT plugin_finance_ap_fresh(t,b,y,rid) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_DEPENDENCY_PRESENT';END IF;RETURN;END IF;
 SELECT * INTO q FROM plugin_finance_evidence_allocation WHERE(tenant_id,book_id,year_id,id)=(t,b,y,r.parent_allocation_id);
 SELECT * INTO d FROM plugin_finance_evidence_document WHERE(tenant_id,book_id,year_id,id)=(t,b,y,r.document_id);
 IF q.pending_reverse_id IS NOT NULL THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_PENDING_PARENT_REVERSAL';END IF;
 IF d.pending_review_id IS NOT NULL THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_BASIS_PENDING';END IF;
 IF plugin_finance_ap_parent(t,b,y,q.id) IS DISTINCT FROM r.source_snapshot THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_SOURCE_STALE';END IF;
 IF plugin_finance_ap_basis_expectation(t,b,y,r.document_id) IS DISTINCT FROM r.content->'accountingBasis' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_BASIS_STALE';END IF;
 IF plugin_finance_ap_supplier(t,r.legal_entity_id,r.supplier_id) IS DISTINCT FROM r.content->'supplier' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_SUPPLIER_STALE';END IF;
 IF NOT plugin_finance_ap_fresh(t,b,y,rid) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AP_SOURCE_STALE';END IF;
END $$;

CREATE INDEX finance_ap_basis_approved_order ON plugin_finance_ap_basis(tenant_id,book_id,year_id,document_id,revision_no DESC) WHERE status='APPROVED';

CREATE UNIQUE INDEX finance_ap_one_pending_reverse ON plugin_finance_ap_run(tenant_id,book_id,year_id,slot_id) WHERE producer_kind='AP_EXPENSE_REVERSAL' AND status IN('DRAFT','SUBMITTED','APPROVED');
