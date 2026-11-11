-- EV03 bounded organization-reviewed expense evidence; no GL, payment or tax effects.
-- Bounded candidate migration; no published migration is rewritten. Runtime validation is separate.
ALTER TABLE plugin_finance_book ADD UNIQUE(tenant_id,id,legal_entity_id);
CREATE TABLE plugin_finance_evidence_scope_guard(tenant_id uuid NOT NULL,legal_entity_id uuid NOT NULL,generation bigint NOT NULL DEFAULT 0 CHECK(generation>=0),PRIMARY KEY(tenant_id,legal_entity_id),FOREIGN KEY(tenant_id,legal_entity_id) REFERENCES plugin_finance_entity(tenant_id,id));
INSERT INTO plugin_finance_evidence_scope_guard(tenant_id,legal_entity_id) SELECT tenant_id,id FROM plugin_finance_entity;
CREATE TABLE plugin_finance_evidence_home_guard(tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,generation bigint NOT NULL DEFAULT 0 CHECK(generation>=0),PRIMARY KEY(tenant_id,book_id,year_id),FOREIGN KEY(tenant_id,book_id,year_id) REFERENCES plugin_finance_year(tenant_id,book_id,id));
INSERT INTO plugin_finance_evidence_home_guard(tenant_id,book_id,year_id) SELECT tenant_id,book_id,id FROM plugin_finance_year;
CREATE FUNCTION plugin_finance_evidence_new_entity() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN INSERT INTO plugin_finance_evidence_scope_guard(tenant_id,legal_entity_id) VALUES(NEW.tenant_id,NEW.id);RETURN NEW;END $$;
CREATE TRIGGER finance_evidence_new_entity AFTER INSERT ON plugin_finance_entity FOR EACH ROW EXECUTE FUNCTION plugin_finance_evidence_new_entity();
CREATE FUNCTION plugin_finance_evidence_new_year() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN INSERT INTO plugin_finance_evidence_home_guard(tenant_id,book_id,year_id) VALUES(NEW.tenant_id,NEW.book_id,NEW.id);RETURN NEW;END $$;
CREATE TRIGGER finance_evidence_new_year AFTER INSERT ON plugin_finance_year FOR EACH ROW EXECUTE FUNCTION plugin_finance_evidence_new_year();
CREATE TABLE plugin_finance_evidence_document(
 tenant_id uuid NOT NULL,legal_entity_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,id uuid NOT NULL DEFAULT gen_random_uuid(),
 home_book_id uuid GENERATED ALWAYS AS(book_id) STORED,home_year_id uuid GENERATED ALWAYS AS(year_id) STORED,
 source_original_id uuid NOT NULL,source_snapshot jsonb NOT NULL,registration_payload_hash text NOT NULL CHECK(registration_payload_hash~'^[a-f0-9]{64}$'),
 registered_fields jsonb NOT NULL,version bigint NOT NULL DEFAULT 1 CHECK(version>0),basis_version bigint NOT NULL DEFAULT 0 CHECK(basis_version>=0),
 current_review_id uuid,latest_review_id uuid NOT NULL,pending_review_id uuid,created_by jsonb NOT NULL,created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(tenant_id,book_id,year_id,id),UNIQUE(tenant_id,legal_entity_id,id),FOREIGN KEY(tenant_id,book_id,legal_entity_id) REFERENCES plugin_finance_book(tenant_id,id,legal_entity_id),FOREIGN KEY(tenant_id,book_id,year_id) REFERENCES plugin_finance_year(tenant_id,book_id,id),FOREIGN KEY(tenant_id,book_id,year_id,source_original_id) REFERENCES plugin_finance_original(tenant_id,book_id,year_id,id)
);
CREATE TABLE plugin_finance_evidence_review(
 tenant_id uuid NOT NULL,legal_entity_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,id uuid NOT NULL DEFAULT gen_random_uuid(),
 document_id uuid NOT NULL,status text NOT NULL DEFAULT 'PENDING' CHECK(status IN('PENDING','APPROVED','REJECTED','CANCELLED')),version bigint NOT NULL DEFAULT 1 CHECK(version>0),
 previous_review_id uuid,previous_basis_version bigint,published_basis_version bigint,content jsonb NOT NULL,content_digest text NOT NULL CHECK(content_digest~'^[a-f0-9]{64}$'),
 basis_original_id uuid NOT NULL,basis_original_snapshot jsonb NOT NULL,prepared_by jsonb NOT NULL,decided_by jsonb,created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 expires_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP+interval '7 days',decided_at timestamptz,decision_reason text CHECK(decision_reason IS NULL OR length(decision_reason) BETWEEN 1 AND 1000),
 PRIMARY KEY(tenant_id,book_id,year_id,id),FOREIGN KEY(tenant_id,book_id,year_id,document_id) REFERENCES plugin_finance_evidence_document(tenant_id,book_id,year_id,id),UNIQUE(tenant_id,book_id,year_id,document_id,id),FOREIGN KEY(tenant_id,book_id,year_id,basis_original_id) REFERENCES plugin_finance_original(tenant_id,book_id,year_id,id),FOREIGN KEY(tenant_id,book_id,year_id,document_id,previous_review_id) REFERENCES plugin_finance_evidence_review(tenant_id,book_id,year_id,document_id,id) DEFERRABLE INITIALLY DEFERRED,CHECK(expires_at>created_at AND expires_at<=created_at+interval '7 days'),CHECK((status='PENDING' AND decided_by IS NULL AND decided_at IS NULL AND decision_reason IS NULL AND published_basis_version IS NULL) OR(status<>'PENDING' AND decided_by IS NOT NULL AND decided_at IS NOT NULL AND decision_reason IS NOT NULL)),CHECK((status='APPROVED')=(published_basis_version IS NOT NULL))
);
CREATE UNIQUE INDEX finance_evidence_review_pending ON plugin_finance_evidence_review(tenant_id,book_id,year_id,document_id) WHERE status='PENDING';
ALTER TABLE plugin_finance_evidence_document ADD FOREIGN KEY(tenant_id,book_id,year_id,id,current_review_id) REFERENCES plugin_finance_evidence_review(tenant_id,book_id,year_id,document_id,id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE plugin_finance_evidence_document ADD FOREIGN KEY(tenant_id,book_id,year_id,id,latest_review_id) REFERENCES plugin_finance_evidence_review(tenant_id,book_id,year_id,document_id,id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE plugin_finance_evidence_document ADD FOREIGN KEY(tenant_id,book_id,year_id,id,pending_review_id) REFERENCES plugin_finance_evidence_review(tenant_id,book_id,year_id,document_id,id) DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE plugin_finance_evidence_identity_alias(tenant_id uuid NOT NULL,legal_entity_id uuid NOT NULL,document_type text NOT NULL,issuer_key text NOT NULL,external_identity_key text NOT NULL,document_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,introduced_by_review_id uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(tenant_id,legal_entity_id,document_type,issuer_key,external_identity_key),FOREIGN KEY(tenant_id,legal_entity_id,document_id) REFERENCES plugin_finance_evidence_document(tenant_id,legal_entity_id,id),FOREIGN KEY(tenant_id,book_id,year_id,document_id,introduced_by_review_id) REFERENCES plugin_finance_evidence_review(tenant_id,book_id,year_id,document_id,id));
CREATE TABLE plugin_finance_evidence_hash_alias(tenant_id uuid NOT NULL,legal_entity_id uuid NOT NULL,sha256 text NOT NULL CHECK(sha256~'^[a-f0-9]{64}$'),document_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,introduced_by_review_id uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(tenant_id,legal_entity_id,sha256),FOREIGN KEY(tenant_id,legal_entity_id,document_id) REFERENCES plugin_finance_evidence_document(tenant_id,legal_entity_id,id),FOREIGN KEY(tenant_id,book_id,year_id,document_id,introduced_by_review_id) REFERENCES plugin_finance_evidence_review(tenant_id,book_id,year_id,document_id,id));
CREATE TABLE plugin_finance_evidence_chain(
 tenant_id uuid NOT NULL,legal_entity_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,id uuid NOT NULL DEFAULT gen_random_uuid(),
 source_system text NOT NULL CHECK(source_system='ORG_MANUAL_EXPENSE_V1'),business_chain_key text NOT NULL,created_by jsonb NOT NULL,created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(tenant_id,book_id,year_id,id),UNIQUE(tenant_id,legal_entity_id,id),UNIQUE(tenant_id,legal_entity_id,source_system,business_chain_key),FOREIGN KEY(tenant_id,book_id,legal_entity_id) REFERENCES plugin_finance_book(tenant_id,id,legal_entity_id),FOREIGN KEY(tenant_id,book_id,year_id) REFERENCES plugin_finance_year(tenant_id,book_id,id)
);
CREATE TABLE plugin_finance_evidence_allocation(
 tenant_id uuid NOT NULL,legal_entity_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,id uuid NOT NULL DEFAULT gen_random_uuid(),
 document_id uuid NOT NULL,chain_id uuid NOT NULL,purpose text NOT NULL DEFAULT 'EXPENSE_ALLOCATION' CHECK(purpose='EXPENSE_ALLOCATION'),source_system text NOT NULL CHECK(source_system='ORG_MANUAL_EXPENSE_V1'),source_operation_key text NOT NULL,
 create_payload_hash text NOT NULL CHECK(create_payload_hash~'^[a-f0-9]{64}$'),status text NOT NULL DEFAULT 'DRAFT' CHECK(status IN('DRAFT','SUBMITTED','CONFIRMED','CANCELLED')),version bigint NOT NULL DEFAULT 1 CHECK(version>0),content_version bigint NOT NULL DEFAULT 1 CHECK(content_version>0),content jsonb NOT NULL,content_digest text NOT NULL CHECK(content_digest~'^[a-f0-9]{64}$'),
 amount numeric(28,8) NOT NULL CHECK(amount>0 AND amount=round(amount,2)),occurred_date date NOT NULL,description text NOT NULL CHECK(length(description) BETWEEN 1 AND 1000),
 basis_review_id uuid NOT NULL,basis_version bigint NOT NULL CHECK(basis_version>0),basis_snapshot jsonb NOT NULL,purpose_original_id uuid NOT NULL,purpose_original_snapshot jsonb NOT NULL,
 prepared_by jsonb NOT NULL,last_editor jsonb NOT NULL,current_submission_id uuid,current_usage_id uuid,current_confirmation_id uuid,pending_reverse_id uuid,latest_reverse_id uuid,
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(tenant_id,book_id,year_id,id),FOREIGN KEY(tenant_id,book_id,year_id,document_id) REFERENCES plugin_finance_evidence_document(tenant_id,book_id,year_id,id),UNIQUE(tenant_id,book_id,year_id,document_id,id),UNIQUE(tenant_id,legal_entity_id,document_id,purpose,source_system,source_operation_key),FOREIGN KEY(tenant_id,book_id,year_id,chain_id) REFERENCES plugin_finance_evidence_chain(tenant_id,book_id,year_id,id),FOREIGN KEY(tenant_id,book_id,year_id,document_id,basis_review_id) REFERENCES plugin_finance_evidence_review(tenant_id,book_id,year_id,document_id,id),FOREIGN KEY(tenant_id,book_id,year_id,purpose_original_id) REFERENCES plugin_finance_original(tenant_id,book_id,year_id,id)
);
CREATE TABLE plugin_finance_evidence_submission(
 tenant_id uuid NOT NULL,legal_entity_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,id uuid NOT NULL DEFAULT gen_random_uuid(),
 document_id uuid NOT NULL,allocation_id uuid NOT NULL,allocation_version bigint NOT NULL,content_version bigint NOT NULL,content_digest text NOT NULL,review_id uuid NOT NULL,basis_version bigint NOT NULL,amount numeric(28,8) NOT NULL CHECK(amount>0 AND amount=round(amount,2)),usage_id uuid NOT NULL,submitted_by jsonb NOT NULL,submitted_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,expires_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP+interval '7 days',
 PRIMARY KEY(tenant_id,book_id,year_id,id),FOREIGN KEY(tenant_id,book_id,year_id,allocation_id) REFERENCES plugin_finance_evidence_allocation(tenant_id,book_id,year_id,id),FOREIGN KEY(tenant_id,book_id,year_id,document_id) REFERENCES plugin_finance_evidence_document(tenant_id,book_id,year_id,id),UNIQUE(tenant_id,book_id,year_id,allocation_id,id),FOREIGN KEY(tenant_id,book_id,year_id,document_id,review_id) REFERENCES plugin_finance_evidence_review(tenant_id,book_id,year_id,document_id,id),CHECK(expires_at>submitted_at AND expires_at<=submitted_at+interval '7 days')
);
CREATE TABLE plugin_finance_evidence_usage(
 tenant_id uuid NOT NULL,legal_entity_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,id uuid NOT NULL DEFAULT gen_random_uuid(),
 document_id uuid NOT NULL,allocation_id uuid NOT NULL,submission_id uuid NOT NULL,amount numeric(28,8) NOT NULL CHECK(amount>0 AND amount=round(amount,2)),state text NOT NULL CHECK(state IN('RESERVED','COMMITTED','RELEASED')),reserved_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,expires_at timestamptz NOT NULL,confirmation_id uuid,committed_at timestamptz,release_id uuid,released_at timestamptz,
 PRIMARY KEY(tenant_id,book_id,year_id,id),FOREIGN KEY(tenant_id,book_id,year_id,document_id) REFERENCES plugin_finance_evidence_document(tenant_id,book_id,year_id,id),FOREIGN KEY(tenant_id,book_id,year_id,allocation_id) REFERENCES plugin_finance_evidence_allocation(tenant_id,book_id,year_id,id),UNIQUE(tenant_id,book_id,year_id,allocation_id,id),UNIQUE(tenant_id,book_id,year_id,submission_id),FOREIGN KEY(tenant_id,book_id,year_id,allocation_id,submission_id) REFERENCES plugin_finance_evidence_submission(tenant_id,book_id,year_id,allocation_id,id),CHECK((state='RESERVED' AND confirmation_id IS NULL AND committed_at IS NULL AND release_id IS NULL AND released_at IS NULL) OR(state='COMMITTED' AND confirmation_id IS NOT NULL AND committed_at IS NOT NULL AND release_id IS NULL AND released_at IS NULL) OR(state='RELEASED' AND confirmation_id IS NULL AND committed_at IS NULL AND release_id IS NOT NULL AND released_at IS NOT NULL))
);
CREATE TABLE plugin_finance_evidence_confirmation(
 tenant_id uuid NOT NULL,legal_entity_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,id uuid NOT NULL DEFAULT gen_random_uuid(),
 document_id uuid NOT NULL,allocation_id uuid NOT NULL,submission_id uuid NOT NULL,usage_id uuid NOT NULL,allocation_version bigint NOT NULL,content_version bigint NOT NULL,content_digest text NOT NULL,amount numeric(28,8) NOT NULL CHECK(amount>0 AND amount=round(amount,2)),confirmed_by jsonb NOT NULL,confirmed_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(tenant_id,book_id,year_id,id),FOREIGN KEY(tenant_id,book_id,year_id,document_id) REFERENCES plugin_finance_evidence_document(tenant_id,book_id,year_id,id),FOREIGN KEY(tenant_id,book_id,year_id,allocation_id) REFERENCES plugin_finance_evidence_allocation(tenant_id,book_id,year_id,id),UNIQUE(tenant_id,book_id,year_id,allocation_id,id),UNIQUE(tenant_id,book_id,year_id,allocation_id),FOREIGN KEY(tenant_id,book_id,year_id,allocation_id,submission_id) REFERENCES plugin_finance_evidence_submission(tenant_id,book_id,year_id,allocation_id,id),FOREIGN KEY(tenant_id,book_id,year_id,allocation_id,usage_id) REFERENCES plugin_finance_evidence_usage(tenant_id,book_id,year_id,allocation_id,id)
);
CREATE TABLE plugin_finance_evidence_release(
 tenant_id uuid NOT NULL,legal_entity_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,id uuid NOT NULL DEFAULT gen_random_uuid(),
 document_id uuid NOT NULL,allocation_id uuid NOT NULL,allocation_version bigint NOT NULL,submission_id uuid,usage_id uuid,action text NOT NULL CHECK(action IN('WITHDRAW','REJECT','ADMIN_TERMINATE','CANCEL')),actor jsonb NOT NULL,reason text NOT NULL CHECK(length(reason) BETWEEN 1 AND 1000),released_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(tenant_id,book_id,year_id,id),FOREIGN KEY(tenant_id,book_id,year_id,document_id) REFERENCES plugin_finance_evidence_document(tenant_id,book_id,year_id,id),FOREIGN KEY(tenant_id,book_id,year_id,allocation_id) REFERENCES plugin_finance_evidence_allocation(tenant_id,book_id,year_id,id),UNIQUE(tenant_id,book_id,year_id,allocation_id,id),FOREIGN KEY(tenant_id,book_id,year_id,allocation_id,submission_id) REFERENCES plugin_finance_evidence_submission(tenant_id,book_id,year_id,allocation_id,id),FOREIGN KEY(tenant_id,book_id,year_id,allocation_id,usage_id) REFERENCES plugin_finance_evidence_usage(tenant_id,book_id,year_id,allocation_id,id),CHECK((submission_id IS NULL)=(usage_id IS NULL))
);
CREATE TABLE plugin_finance_evidence_reverse_request(
 tenant_id uuid NOT NULL,legal_entity_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,id uuid NOT NULL DEFAULT gen_random_uuid(),
 document_id uuid NOT NULL,allocation_id uuid NOT NULL,previous_reverse_request_id uuid,confirmation_id uuid NOT NULL,usage_id uuid NOT NULL,expected_allocation_version bigint NOT NULL,amount numeric(28,8) NOT NULL CHECK(amount>0 AND amount=round(amount,2)),basis_original_id uuid NOT NULL,basis_original_snapshot jsonb NOT NULL,content jsonb NOT NULL,content_digest text NOT NULL,
 status text NOT NULL DEFAULT 'PENDING' CHECK(status IN('PENDING','APPROVED','REJECTED','CANCELLED')),version bigint NOT NULL DEFAULT 1 CHECK(version>0),requested_by jsonb NOT NULL,decided_by jsonb,reason text NOT NULL CHECK(length(reason) BETWEEN 1 AND 1000),created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,expires_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP+interval '7 days',decided_at timestamptz,decision_reason text CHECK(decision_reason IS NULL OR length(decision_reason) BETWEEN 1 AND 1000),reverse_effect_id uuid,
 PRIMARY KEY(tenant_id,book_id,year_id,id),FOREIGN KEY(tenant_id,book_id,year_id,document_id) REFERENCES plugin_finance_evidence_document(tenant_id,book_id,year_id,id),FOREIGN KEY(tenant_id,book_id,year_id,allocation_id) REFERENCES plugin_finance_evidence_allocation(tenant_id,book_id,year_id,id),UNIQUE(tenant_id,book_id,year_id,allocation_id,id),FOREIGN KEY(tenant_id,book_id,year_id,allocation_id,previous_reverse_request_id) REFERENCES plugin_finance_evidence_reverse_request(tenant_id,book_id,year_id,allocation_id,id),FOREIGN KEY(tenant_id,book_id,year_id,allocation_id,confirmation_id) REFERENCES plugin_finance_evidence_confirmation(tenant_id,book_id,year_id,allocation_id,id),FOREIGN KEY(tenant_id,book_id,year_id,allocation_id,usage_id) REFERENCES plugin_finance_evidence_usage(tenant_id,book_id,year_id,allocation_id,id),FOREIGN KEY(tenant_id,book_id,year_id,basis_original_id) REFERENCES plugin_finance_original(tenant_id,book_id,year_id,id),CHECK(expires_at>created_at AND expires_at<=created_at+interval '7 days'),CHECK((status='PENDING' AND decided_by IS NULL AND decided_at IS NULL AND decision_reason IS NULL) OR(status<>'PENDING' AND decided_by IS NOT NULL AND decided_at IS NOT NULL AND decision_reason IS NOT NULL)),CHECK((status='APPROVED')=(reverse_effect_id IS NOT NULL))
);
CREATE UNIQUE INDEX finance_evidence_reverse_pending ON plugin_finance_evidence_reverse_request(tenant_id,book_id,year_id,allocation_id) WHERE status='PENDING';
CREATE TABLE plugin_finance_evidence_reverse_effect(
 tenant_id uuid NOT NULL,legal_entity_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,id uuid NOT NULL DEFAULT gen_random_uuid(),
 document_id uuid NOT NULL,allocation_id uuid NOT NULL,request_id uuid NOT NULL,confirmation_id uuid NOT NULL,usage_id uuid NOT NULL,allocation_version bigint NOT NULL,amount numeric(28,8) NOT NULL CHECK(amount>0 AND amount=round(amount,2)),approved_by jsonb NOT NULL,approved_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(tenant_id,book_id,year_id,id),FOREIGN KEY(tenant_id,book_id,year_id,document_id) REFERENCES plugin_finance_evidence_document(tenant_id,book_id,year_id,id),FOREIGN KEY(tenant_id,book_id,year_id,allocation_id) REFERENCES plugin_finance_evidence_allocation(tenant_id,book_id,year_id,id),UNIQUE(tenant_id,book_id,year_id,allocation_id,id),UNIQUE(tenant_id,book_id,year_id,request_id),FOREIGN KEY(tenant_id,book_id,year_id,allocation_id,request_id) REFERENCES plugin_finance_evidence_reverse_request(tenant_id,book_id,year_id,allocation_id,id),FOREIGN KEY(tenant_id,book_id,year_id,allocation_id,confirmation_id) REFERENCES plugin_finance_evidence_confirmation(tenant_id,book_id,year_id,allocation_id,id),FOREIGN KEY(tenant_id,book_id,year_id,allocation_id,usage_id) REFERENCES plugin_finance_evidence_usage(tenant_id,book_id,year_id,allocation_id,id)
);
ALTER TABLE plugin_finance_evidence_submission ADD FOREIGN KEY(tenant_id,book_id,year_id,allocation_id,usage_id) REFERENCES plugin_finance_evidence_usage(tenant_id,book_id,year_id,allocation_id,id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE plugin_finance_evidence_usage ADD FOREIGN KEY(tenant_id,book_id,year_id,allocation_id,confirmation_id) REFERENCES plugin_finance_evidence_confirmation(tenant_id,book_id,year_id,allocation_id,id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE plugin_finance_evidence_usage ADD FOREIGN KEY(tenant_id,book_id,year_id,allocation_id,release_id) REFERENCES plugin_finance_evidence_release(tenant_id,book_id,year_id,allocation_id,id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE plugin_finance_evidence_reverse_request ADD FOREIGN KEY(tenant_id,book_id,year_id,allocation_id,reverse_effect_id) REFERENCES plugin_finance_evidence_reverse_effect(tenant_id,book_id,year_id,allocation_id,id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE plugin_finance_evidence_allocation ADD FOREIGN KEY(tenant_id,book_id,year_id,id,current_submission_id) REFERENCES plugin_finance_evidence_submission(tenant_id,book_id,year_id,allocation_id,id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE plugin_finance_evidence_allocation ADD FOREIGN KEY(tenant_id,book_id,year_id,id,current_usage_id) REFERENCES plugin_finance_evidence_usage(tenant_id,book_id,year_id,allocation_id,id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE plugin_finance_evidence_allocation ADD FOREIGN KEY(tenant_id,book_id,year_id,id,current_confirmation_id) REFERENCES plugin_finance_evidence_confirmation(tenant_id,book_id,year_id,allocation_id,id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE plugin_finance_evidence_allocation ADD FOREIGN KEY(tenant_id,book_id,year_id,id,pending_reverse_id) REFERENCES plugin_finance_evidence_reverse_request(tenant_id,book_id,year_id,allocation_id,id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE plugin_finance_evidence_allocation ADD FOREIGN KEY(tenant_id,book_id,year_id,id,latest_reverse_id) REFERENCES plugin_finance_evidence_reverse_request(tenant_id,book_id,year_id,allocation_id,id) DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE plugin_finance_evidence_allocation_event(
 tenant_id uuid NOT NULL,legal_entity_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,id uuid NOT NULL DEFAULT gen_random_uuid(),
 document_id uuid NOT NULL,allocation_id uuid NOT NULL,allocation_version bigint NOT NULL,previous_version bigint,action text NOT NULL CHECK(action IN('CREATE','EDIT','SUBMIT','CONFIRM','WITHDRAW','REJECT','ADMIN_TERMINATE','CANCEL','REVERSE')),from_status text,to_status text NOT NULL,content_version bigint NOT NULL,content jsonb NOT NULL,content_digest text NOT NULL,actor jsonb NOT NULL,reason text CHECK(reason IS NULL OR length(reason) BETWEEN 1 AND 1000),submission_id uuid,usage_id uuid,confirmation_id uuid,release_id uuid,reverse_effect_id uuid,created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(tenant_id,book_id,year_id,id),FOREIGN KEY(tenant_id,book_id,year_id,document_id) REFERENCES plugin_finance_evidence_document(tenant_id,book_id,year_id,id),FOREIGN KEY(tenant_id,book_id,year_id,allocation_id) REFERENCES plugin_finance_evidence_allocation(tenant_id,book_id,year_id,id),UNIQUE(tenant_id,book_id,year_id,allocation_id,allocation_version),FOREIGN KEY(tenant_id,book_id,year_id,allocation_id,previous_version) REFERENCES plugin_finance_evidence_allocation_event(tenant_id,book_id,year_id,allocation_id,allocation_version) DEFERRABLE INITIALLY DEFERRED,CHECK((allocation_version=1 AND previous_version IS NULL AND action='CREATE') OR(allocation_version>1 AND previous_version=allocation_version-1))
);
ALTER TABLE plugin_finance_evidence_allocation_event ADD FOREIGN KEY(tenant_id,book_id,year_id,allocation_id,submission_id) REFERENCES plugin_finance_evidence_submission(tenant_id,book_id,year_id,allocation_id,id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE plugin_finance_evidence_allocation_event ADD FOREIGN KEY(tenant_id,book_id,year_id,allocation_id,usage_id) REFERENCES plugin_finance_evidence_usage(tenant_id,book_id,year_id,allocation_id,id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE plugin_finance_evidence_allocation_event ADD FOREIGN KEY(tenant_id,book_id,year_id,allocation_id,confirmation_id) REFERENCES plugin_finance_evidence_confirmation(tenant_id,book_id,year_id,allocation_id,id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE plugin_finance_evidence_allocation_event ADD FOREIGN KEY(tenant_id,book_id,year_id,allocation_id,release_id) REFERENCES plugin_finance_evidence_release(tenant_id,book_id,year_id,allocation_id,id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE plugin_finance_evidence_allocation_event ADD FOREIGN KEY(tenant_id,book_id,year_id,allocation_id,reverse_effect_id) REFERENCES plugin_finance_evidence_reverse_effect(tenant_id,book_id,year_id,allocation_id,id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE plugin_finance_evidence_submission ADD FOREIGN KEY(tenant_id,book_id,year_id,allocation_id,allocation_version) REFERENCES plugin_finance_evidence_allocation_event(tenant_id,book_id,year_id,allocation_id,allocation_version) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE plugin_finance_evidence_confirmation ADD FOREIGN KEY(tenant_id,book_id,year_id,allocation_id,allocation_version) REFERENCES plugin_finance_evidence_allocation_event(tenant_id,book_id,year_id,allocation_id,allocation_version) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE plugin_finance_evidence_release ADD FOREIGN KEY(tenant_id,book_id,year_id,allocation_id,allocation_version) REFERENCES plugin_finance_evidence_allocation_event(tenant_id,book_id,year_id,allocation_id,allocation_version) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE plugin_finance_evidence_reverse_effect ADD FOREIGN KEY(tenant_id,book_id,year_id,allocation_id,allocation_version) REFERENCES plugin_finance_evidence_allocation_event(tenant_id,book_id,year_id,allocation_id,allocation_version) DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE plugin_finance_evidence_request(tenant_id uuid NOT NULL,legal_entity_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,actor_id uuid NOT NULL,request_key text NOT NULL,route text NOT NULL,payload_hash text NOT NULL CHECK(payload_hash~'^[a-f0-9]{64}$'),result_kind text NOT NULL CHECK(result_kind IN('DOCUMENT','REVIEW','ALLOCATION','REVERSE')),document_id uuid NOT NULL,review_id uuid,allocation_id uuid,reverse_request_id uuid,result_id uuid GENERATED ALWAYS AS(CASE result_kind WHEN 'DOCUMENT' THEN document_id WHEN 'REVIEW' THEN review_id WHEN 'ALLOCATION' THEN allocation_id WHEN 'REVERSE' THEN reverse_request_id END) STORED,created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(actor_id,request_key),FOREIGN KEY(tenant_id,book_id,year_id,document_id) REFERENCES plugin_finance_evidence_document(tenant_id,book_id,year_id,id),FOREIGN KEY(tenant_id,book_id,year_id,document_id,review_id) REFERENCES plugin_finance_evidence_review(tenant_id,book_id,year_id,document_id,id),FOREIGN KEY(tenant_id,book_id,year_id,document_id,allocation_id) REFERENCES plugin_finance_evidence_allocation(tenant_id,book_id,year_id,document_id,id),FOREIGN KEY(tenant_id,book_id,year_id,allocation_id,reverse_request_id) REFERENCES plugin_finance_evidence_reverse_request(tenant_id,book_id,year_id,allocation_id,id),CHECK((result_kind='DOCUMENT' AND review_id IS NULL AND allocation_id IS NULL AND reverse_request_id IS NULL) OR(result_kind='REVIEW' AND review_id IS NOT NULL AND allocation_id IS NULL AND reverse_request_id IS NULL) OR(result_kind='ALLOCATION' AND review_id IS NULL AND allocation_id IS NOT NULL AND reverse_request_id IS NULL) OR(result_kind='REVERSE' AND review_id IS NULL AND allocation_id IS NOT NULL AND reverse_request_id IS NOT NULL)));
CREATE TABLE plugin_finance_evidence_audit(
 tenant_id uuid NOT NULL,legal_entity_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,id uuid NOT NULL DEFAULT gen_random_uuid(),
 actor jsonb NOT NULL,action text NOT NULL,document_id uuid NOT NULL,review_id uuid,allocation_id uuid,reverse_request_id uuid,payload jsonb NOT NULL,created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(tenant_id,book_id,year_id,id),FOREIGN KEY(tenant_id,book_id,year_id,document_id) REFERENCES plugin_finance_evidence_document(tenant_id,book_id,year_id,id)
);
CREATE INDEX finance_evidence_document_page ON plugin_finance_evidence_document(tenant_id,book_id,year_id,created_at DESC,id DESC);
CREATE INDEX finance_evidence_allocation_page ON plugin_finance_evidence_allocation(tenant_id,book_id,year_id,created_at DESC,id DESC);
CREATE INDEX finance_evidence_usage_balance ON plugin_finance_evidence_usage(tenant_id,book_id,year_id,document_id,state);
CREATE INDEX finance_evidence_reverse_allocation ON plugin_finance_evidence_reverse_effect(tenant_id,book_id,year_id,allocation_id);
-- Shared ordering: realm -> caller/key advisory -> book -> year/mode -> entity -> document/consumer.
CREATE FUNCTION plugin_finance_evidence_prelock(t uuid,e uuid) RETURNS void LANGUAGE plpgsql AS $$ BEGIN
 PERFORM 1 FROM plugin_finance_person_realm WHERE tenant_id=t FOR SHARE;
 IF NOT FOUND OR NOT EXISTS(SELECT 1 FROM plugin_finance_entity WHERE tenant_id=t AND id=e) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SCOPE_DENIED'; END IF;
END $$;
CREATE FUNCTION plugin_finance_evidence_scope(t uuid,b uuid,y uuid,new_use boolean,d date DEFAULT NULL) RETURNS void LANGUAGE plpgsql AS $$
DECLARE yr plugin_finance_year; ent uuid; md text;
BEGIN
 IF current_setting('transaction_isolation')<>'read committed' THEN RAISE EXCEPTION USING ERRCODE='40001',MESSAGE='EVIDENCE_READ_COMMITTED_REQUIRED'; END IF;
 PERFORM 1 FROM plugin_finance_person_realm WHERE tenant_id=t FOR SHARE;
 IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SCOPE_DENIED'; END IF;
 SELECT legal_entity_id INTO ent FROM plugin_finance_book WHERE tenant_id=t AND id=b FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SCOPE_DENIED'; END IF;
 SELECT * INTO yr FROM plugin_finance_year WHERE tenant_id=t AND book_id=b AND id=y FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SCOPE_DENIED'; END IF;
 PERFORM 1 FROM plugin_finance_year_guard WHERE tenant_id=t AND book_id=b AND year_id=y FOR SHARE;
 SELECT mode INTO md FROM plugin_finance_journal_person_mode WHERE tenant_id=t AND book_id=b AND year_id=y FOR SHARE;
 IF md IS DISTINCT FROM 'JOURNAL_PERSON_SOD' OR yr.precision_profile<>'V4' OR yr.precision_review_required THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='JOURNAL_PERSON_MODE_REQUIRED'; END IF;
 IF new_use AND yr.status<>'OPEN' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PERIOD_CLOSED'; END IF;
 PERFORM 1 FROM plugin_finance_evidence_scope_guard WHERE tenant_id=t AND legal_entity_id=ent FOR UPDATE;
 IF NOT FOUND OR NOT EXISTS(SELECT 1 FROM plugin_finance_evidence_home_guard WHERE tenant_id=t AND book_id=b AND year_id=y) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SCOPE_DENIED'; END IF;
 IF d IS NOT NULL THEN
  IF extract(year FROM d)::integer<>yr.year THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='INVALID_INPUT'; END IF;
  PERFORM 1 FROM plugin_finance_period WHERE tenant_id=t AND book_id=b AND year_id=y AND month=extract(month FROM d)::integer AND (NOT new_use OR status='OPEN') FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='PERIOD_CLOSED'; END IF;
 END IF;
END $$;
CREATE FUNCTION plugin_finance_evidence_digest(j jsonb) RETURNS text LANGUAGE sql IMMUTABLE STRICT AS $$ SELECT encode(sha256(convert_to(j::text,'UTF8')),'hex') $$;
CREATE FUNCTION plugin_finance_evidence_normalize(s text) RETURNS text LANGUAGE plpgsql IMMUTABLE AS $$ BEGIN
 IF s IS NULL OR s~'[\x00-\x1f\x7f-\x9f]' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='INVALID_INPUT'; END IF;
 RETURN translate(btrim(normalize(s,NFC),' '),'abcdefghijklmnopqrstuvwxyz','ABCDEFGHIJKLMNOPQRSTUVWXYZ');
END $$;
CREATE FUNCTION plugin_finance_evidence_money(s text,positive boolean DEFAULT true) RETURNS numeric LANGUAGE plpgsql IMMUTABLE AS $$ DECLARE n numeric;BEGIN
 IF s IS NULL OR s!~'^(0|[1-9][0-9]{0,19})([.][0-9]{1,2})?$' THEN
  IF s~'^(0|[1-9][0-9]{0,19})[.][0-9]{3,}$' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='INVALID_CURRENCY_SCALE'; END IF;
  IF s~'^[0-9]{21,}' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='AMOUNT_OVERFLOW'; END IF;
  RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='INVALID_INPUT';
 END IF;
 n=s::numeric(28,8);IF (positive AND n<=0) OR n<0 THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='INVALID_INPUT'; END IF;RETURN n;
END $$;
CREATE FUNCTION plugin_finance_evidence_money_text(n numeric) RETURNS text LANGUAGE sql IMMUTABLE STRICT AS $$ SELECT n::numeric(28,8)::text $$;
CREATE FUNCTION plugin_finance_evidence_original(t uuid,b uuid,y uuid,o uuid) RETURNS jsonb LANGUAGE plpgsql STABLE AS $$ DECLARE r plugin_finance_original;BEGIN
 SELECT * INTO r FROM plugin_finance_original WHERE(tenant_id,book_id,year_id,id)=(t,b,y,o);
 IF NOT FOUND OR r.sha256<>encode(sha256(r.content),'hex') THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SCOPE_DENIED'; END IF;
 RETURN jsonb_build_object('bookId',b,'yearId',y,'originalId',r.id,'version',r.version::text,'filename',r.filename,'sha256',r.sha256,'verificationStatus','UNVERIFIED_ORIGINAL_BYTES','createdBy',r.created_by);
END $$;
CREATE FUNCTION plugin_finance_evidence_actor(t uuid,b uuid,y uuid,who jsonb,roles text[]) RETURNS void LANGUAGE plpgsql AS $$ BEGIN
 IF who IS NULL OR who IS DISTINCT FROM plugin_finance_journal_person_identity(t,(who->>'accountId')::uuid,true) OR NOT plugin_finance_journal_person_role(t,b,y,(who->>'accountId')::uuid,roles) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SCOPE_DENIED'; END IF;
END $$;
CREATE FUNCTION plugin_finance_evidence_fields(f jsonb) RETURNS jsonb LANGUAGE plpgsql IMMUTABLE AS $$ DECLARE result jsonb;face numeric;cap numeric;BEGIN
 IF jsonb_typeof(f) IS DISTINCT FROM 'object' OR (SELECT array_agg(k ORDER BY k) FROM jsonb_object_keys(f) k) IS DISTINCT FROM ARRAY['allKnownDocumentIdentifiersDeclared','basisOriginalId','documentType','expenseCapacity','externalIdentityKey','faceGross','issuerKey','originalLocation','reason','sameDocumentConfirmed','singleFileSingleDocumentConfirmed','sourceType']::text[] THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='INVALID_INPUT'; END IF;
 IF EXISTS(SELECT 1 FROM jsonb_each(f) v WHERE v.key IN('documentType','issuerKey','externalIdentityKey','faceGross','expenseCapacity','basisOriginalId','reason','sourceType') AND jsonb_typeof(v.value)<>'string') OR (jsonb_typeof(f->'originalLocation') NOT IN('null','string')) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='INVALID_INPUT'; END IF;
 IF f->>'documentType' IS DISTINCT FROM 'ORDINARY_EXPENSE_INVOICE' OR f->'singleFileSingleDocumentConfirmed' IS DISTINCT FROM 'true'::jsonb OR f->'allKnownDocumentIdentifiersDeclared' IS DISTINCT FROM 'true'::jsonb OR f->'sameDocumentConfirmed' IS DISTINCT FROM 'true'::jsonb THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_DOCUMENT_BUNDLE_UNSUPPORTED'; END IF;
 face=plugin_finance_evidence_money(f->>'faceGross');cap=plugin_finance_evidence_money(f->>'expenseCapacity',false);
 IF cap>face OR length(f->>'reason') NOT BETWEEN 1 AND 1000 OR length(plugin_finance_evidence_normalize(f->>'issuerKey')) NOT BETWEEN 1 AND 128 OR length(plugin_finance_evidence_normalize(f->>'externalIdentityKey')) NOT BETWEEN 1 AND 200 OR f->>'sourceType' NOT IN('ELECTRONIC_ORIGINAL','PAPER_SCAN') OR (f->>'sourceType'='PAPER_SCAN' AND coalesce(length(btrim(f->>'originalLocation')),0)=0) OR (f->'originalLocation'<>'null'::jsonb AND length(f->>'originalLocation') NOT BETWEEN 1 AND 500) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='INVALID_INPUT'; END IF;
 result=f||jsonb_build_object('issuerKey',plugin_finance_evidence_normalize(f->>'issuerKey'),'externalIdentityKey',plugin_finance_evidence_normalize(f->>'externalIdentityKey'),'faceGross',plugin_finance_evidence_money_text(face),'expenseCapacity',plugin_finance_evidence_money_text(cap));RETURN result;
END $$;
CREATE FUNCTION plugin_finance_evidence_basis(t uuid,b uuid,y uuid,d uuid) RETURNS jsonb LANGUAGE plpgsql STABLE AS $$ DECLARE doc plugin_finance_evidence_document;r plugin_finance_evidence_review;BEGIN
 SELECT * INTO doc FROM plugin_finance_evidence_document WHERE(tenant_id,book_id,year_id,id)=(t,b,y,d);
 SELECT * INTO r FROM plugin_finance_evidence_review WHERE(tenant_id,book_id,year_id,id)=(t,b,y,doc.current_review_id);
 IF NOT FOUND OR r.status<>'APPROVED' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_BASIS_UNAVAILABLE'; END IF;
 RETURN jsonb_build_object('reviewId',r.id,'basisVersion',r.published_basis_version::text,'contentDigest',r.content_digest,'documentType',r.content->>'documentType','issuerKey',r.content->>'issuerKey','externalIdentityKey',r.content->>'externalIdentityKey','currency','CNY','faceGross',r.content->>'faceGross','expenseCapacity',r.content->>'expenseCapacity','sourceOriginal',doc.source_snapshot,'basisOriginal',r.basis_original_snapshot,'reviewedBy',r.decided_by,'reviewedAt',r.decided_at,'assurance','ORG_REVIEWED','authenticity','UNVERIFIED','sourceType',r.content->>'sourceType','originalLocation',r.content->'originalLocation');
END $$;
CREATE FUNCTION plugin_finance_evidence_net(t uuid,b uuid,y uuid,d uuid) RETURNS numeric LANGUAGE sql STABLE AS $$
 SELECT coalesce((SELECT sum(amount) FROM plugin_finance_evidence_usage WHERE(tenant_id,book_id,year_id,document_id)=(t,b,y,d) AND state='COMMITTED'),0)-coalesce((SELECT sum(amount) FROM plugin_finance_evidence_reverse_effect WHERE(tenant_id,book_id,year_id,document_id)=(t,b,y,d)),0)
$$;
CREATE FUNCTION plugin_finance_evidence_reserved(t uuid,b uuid,y uuid,d uuid) RETURNS numeric LANGUAGE sql STABLE AS $$ SELECT coalesce(sum(amount),0) FROM plugin_finance_evidence_usage WHERE(tenant_id,book_id,year_id,document_id)=(t,b,y,d) AND state='RESERVED' $$;
CREATE FUNCTION plugin_finance_evidence_bump(t uuid,b uuid,y uuid) RETURNS void LANGUAGE plpgsql AS $$ BEGIN
 UPDATE plugin_finance_evidence_scope_guard SET generation=generation+1 WHERE tenant_id=t AND legal_entity_id=(SELECT legal_entity_id FROM plugin_finance_book WHERE tenant_id=t AND id=b);
 UPDATE plugin_finance_evidence_home_guard SET generation=generation+1 WHERE(tenant_id,book_id,year_id)=(t,b,y);
END $$;
CREATE FUNCTION plugin_finance_evidence_document_command(t uuid,b uuid,y uuid,who jsonb,op text,target uuid,child uuid,payload jsonb) RETURNS jsonb LANGUAGE plpgsql AS $$
DECLARE ent uuid;doc plugin_finance_evidence_document;r plugin_finance_evidence_review;f jsonb;src jsonb;bas jsonb;ph text;other_id uuid;did uuid;rid uuid;ver bigint;result_kind text;
BEGIN
 SELECT legal_entity_id INTO ent FROM plugin_finance_book WHERE tenant_id=t AND id=b;
 PERFORM plugin_finance_evidence_prelock(t,ent);
 PERFORM pg_advisory_xact_lock(hashtextextended((who->>'accountId')||':'||(payload->>'idempotencyKey'),0));
 PERFORM plugin_finance_evidence_scope(t,b,y,op IN('REGISTER','REVIEW_PROPOSE','REVIEW_APPROVE'));
 PERFORM plugin_finance_evidence_actor(t,b,y,who,CASE WHEN op IN('REGISTER','REVIEW_PROPOSE','REVIEW_CANCEL') THEN ARRAY['OWNER'] ELSE ARRAY['OWNER','REVIEWER'] END);
 IF op='REGISTER' THEN
  f=plugin_finance_evidence_fields(payload->'fields');src=plugin_finance_evidence_original(t,b,y,(payload->>'originalId')::uuid);bas=plugin_finance_evidence_original(t,b,y,(f->>'basisOriginalId')::uuid);
  ph=plugin_finance_evidence_digest(jsonb_build_object('fields',f,'sourceSha256',src->>'sha256'));
  SELECT document_id INTO other_id FROM plugin_finance_evidence_identity_alias WHERE tenant_id=t AND legal_entity_id=ent AND document_type=f->>'documentType' AND issuer_key=f->>'issuerKey' AND external_identity_key=f->>'externalIdentityKey';
  IF other_id IS NULL THEN SELECT document_id INTO other_id FROM plugin_finance_evidence_hash_alias WHERE tenant_id=t AND legal_entity_id=ent AND sha256=src->>'sha256'; END IF;
  IF other_id IS NOT NULL THEN
   SELECT * INTO doc FROM plugin_finance_evidence_document WHERE tenant_id=t AND legal_entity_id=ent AND id=other_id;
   IF doc.book_id<>b OR doc.year_id<>y THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_HOME_SCOPE_UNSUPPORTED'; END IF;
   IF doc.registration_payload_hash<>ph THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_IDENTITY_CONFLICT'; END IF;
   RETURN jsonb_build_object('kind','DOCUMENT','id',doc.id,'documentId',doc.id,'allocationId',NULL);
  END IF;
  did=gen_random_uuid();rid=gen_random_uuid();
  INSERT INTO plugin_finance_evidence_document(tenant_id,legal_entity_id,book_id,year_id,id,source_original_id,source_snapshot,registration_payload_hash,registered_fields,latest_review_id,pending_review_id,created_by) VALUES(t,ent,b,y,did,(payload->>'originalId')::uuid,src,ph,f,rid,rid,who);
  INSERT INTO plugin_finance_evidence_review(tenant_id,legal_entity_id,book_id,year_id,id,document_id,content,content_digest,basis_original_id,basis_original_snapshot,prepared_by) VALUES(t,ent,b,y,rid,did,f,plugin_finance_evidence_digest(f),(f->>'basisOriginalId')::uuid,bas,who);
  INSERT INTO plugin_finance_evidence_identity_alias VALUES(t,ent,f->>'documentType',f->>'issuerKey',f->>'externalIdentityKey',did,b,y,rid,CURRENT_TIMESTAMP);
  INSERT INTO plugin_finance_evidence_hash_alias VALUES(t,ent,src->>'sha256',did,b,y,rid,CURRENT_TIMESTAMP);
  result_kind='DOCUMENT';target=did;
 ELSE
  SELECT * INTO doc FROM plugin_finance_evidence_document WHERE(tenant_id,book_id,year_id,id)=(t,b,y,target) FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SCOPE_DENIED'; END IF;did=doc.id;
  IF op='REVIEW_PROPOSE' THEN
   IF doc.version<>(payload->>'expectedDocumentVersion')::bigint THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='VERSION_CONFLICT'; END IF;
   IF doc.pending_review_id IS NOT NULL THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_BASIS_REVIEW_PENDING'; END IF;
   f=plugin_finance_evidence_fields(payload->'fields');bas=plugin_finance_evidence_original(t,b,y,(f->>'basisOriginalId')::uuid);rid=gen_random_uuid();
   INSERT INTO plugin_finance_evidence_review(tenant_id,legal_entity_id,book_id,year_id,id,document_id,previous_review_id,previous_basis_version,content,content_digest,basis_original_id,basis_original_snapshot,prepared_by) VALUES(t,ent,b,y,rid,did,doc.latest_review_id,nullif(doc.basis_version,0),f,plugin_finance_evidence_digest(f),(f->>'basisOriginalId')::uuid,bas,who);
   UPDATE plugin_finance_evidence_document SET latest_review_id=rid,pending_review_id=rid,version=version+1 WHERE(tenant_id,book_id,year_id,id)=(t,b,y,did);
  ELSE
   SELECT * INTO r FROM plugin_finance_evidence_review WHERE(tenant_id,book_id,year_id,document_id,id)=(t,b,y,did,child) FOR UPDATE;
   IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SCOPE_DENIED'; END IF;rid=r.id;
   IF r.version<>(payload->>'expectedVersion')::bigint OR r.content_digest IS DISTINCT FROM payload->>'contentDigest' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='VERSION_CONFLICT'; END IF;
   IF r.status<>'PENDING' OR doc.pending_review_id IS DISTINCT FROM r.id THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_CONSUMER_STATE_CONFLICT'; END IF;
   IF op='REVIEW_CANCEL' THEN
    IF who->>'accountId'<>r.prepared_by->>'accountId' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SCOPE_DENIED'; END IF;
   ELSIF who->>'personId'=r.prepared_by->>'personId' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SELF_APPROVAL_FORBIDDEN'; END IF;
   IF op='REVIEW_APPROVE' THEN
    PERFORM plugin_finance_evidence_actor(t,b,y,r.prepared_by,ARRAY['OWNER']);
    IF clock_timestamp()>=r.expires_at THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_REVIEW_EXPIRED'; END IF;
    PERFORM plugin_finance_evidence_original(t,b,y,r.basis_original_id);
    IF plugin_finance_evidence_reserved(t,b,y,did)<>0 THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_ACTIVE_RESERVATIONS'; END IF;
    IF (r.content->>'expenseCapacity')::numeric<plugin_finance_evidence_net(t,b,y,did) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_CAPACITY_BELOW_COMMITTED'; END IF;
    SELECT document_id INTO other_id FROM plugin_finance_evidence_identity_alias WHERE tenant_id=t AND legal_entity_id=ent AND document_type=r.content->>'documentType' AND issuer_key=r.content->>'issuerKey' AND external_identity_key=r.content->>'externalIdentityKey';
    IF other_id IS NOT NULL AND other_id<>did THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_IDENTITY_CONFLICT'; END IF;
    ver=doc.basis_version+1;
    UPDATE plugin_finance_evidence_review SET status='APPROVED',version=version+1,published_basis_version=ver,decided_by=who,decided_at=CURRENT_TIMESTAMP,decision_reason=payload->>'reason' WHERE(tenant_id,book_id,year_id,id)=(t,b,y,rid);
    INSERT INTO plugin_finance_evidence_identity_alias VALUES(t,ent,r.content->>'documentType',r.content->>'issuerKey',r.content->>'externalIdentityKey',did,b,y,rid,CURRENT_TIMESTAMP) ON CONFLICT DO NOTHING;
    UPDATE plugin_finance_evidence_document SET current_review_id=rid,basis_version=ver,pending_review_id=NULL,version=version+1 WHERE(tenant_id,book_id,year_id,id)=(t,b,y,did);
   ELSIF op IN('REVIEW_REJECT','REVIEW_CANCEL') THEN
    UPDATE plugin_finance_evidence_review SET status=CASE op WHEN 'REVIEW_REJECT' THEN 'REJECTED' ELSE 'CANCELLED' END,version=version+1,decided_by=who,decided_at=CURRENT_TIMESTAMP,decision_reason=payload->>'reason' WHERE(tenant_id,book_id,year_id,id)=(t,b,y,rid);
    UPDATE plugin_finance_evidence_document SET pending_review_id=NULL,version=version+1 WHERE(tenant_id,book_id,year_id,id)=(t,b,y,did);
   ELSE RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='INVALID_INPUT';END IF;
  END IF;
  result_kind='REVIEW';target=rid;
 END IF;
 INSERT INTO plugin_finance_evidence_audit(tenant_id,legal_entity_id,book_id,year_id,actor,action,document_id,review_id,payload) VALUES(t,ent,b,y,who,op,did,rid,jsonb_build_object('resultId',target,'reason',payload->'reason'));
 RETURN jsonb_build_object('kind',result_kind,'id',target,'documentId',did,'allocationId',NULL);
END $$;
CREATE FUNCTION plugin_finance_evidence_allocation_command(t uuid,b uuid,y uuid,who jsonb,op text,target uuid,child uuid,payload jsonb) RETURNS jsonb LANGUAGE plpgsql AS $$
DECLARE ent uuid;a plugin_finance_evidence_allocation;doc plugin_finance_evidence_document;rr plugin_finance_evidence_reverse_request;ss plugin_finance_evidence_submission;cf plugin_finance_evidence_confirmation;
 aid uuid;did uuid;cid uuid;sid uuid;uid uuid;fid uuid;lid uuid;rid uuid;eid uuid;bs jsonb;os jsonb;ct jsonb;dg text;ph text;amt numeric;reversed numeric;before_net numeric;nv bigint;cv bigint;old_status text;next_status text;rv_kind text='ALLOCATION';result_id uuid;chainkey text;operationkey text;event_action text;is_new boolean;
BEGIN
 SELECT legal_entity_id INTO ent FROM plugin_finance_book WHERE tenant_id=t AND id=b;
 PERFORM plugin_finance_evidence_prelock(t,ent);PERFORM pg_advisory_xact_lock(hashtextextended((who->>'accountId')||':'||(payload->>'idempotencyKey'),0));
 is_new=op IN('CREATE','EDIT','SUBMIT','CONFIRM');
 PERFORM plugin_finance_evidence_scope(t,b,y,is_new,CASE WHEN op IN('CREATE','EDIT') THEN (payload->>'occurredDate')::date ELSE NULL END);
 PERFORM plugin_finance_evidence_actor(t,b,y,who,CASE WHEN op LIKE 'REVERSE_%' AND op IN('REVERSE_REQUEST','REVERSE_CANCEL') THEN ARRAY['OWNER'] WHEN op IN('CONFIRM','REJECT','ADMIN_TERMINATE','REVERSE_APPROVE','REVERSE_REJECT') THEN ARRAY['OWNER','REVIEWER'] ELSE ARRAY['OWNER','MAKER'] END);
 IF op='CREATE' THEN
  did=(payload->>'documentId')::uuid;
  SELECT * INTO doc FROM plugin_finance_evidence_document WHERE(tenant_id,book_id,year_id,id)=(t,b,y,did) FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SCOPE_DENIED'; END IF;
  IF payload->>'sourceSystem' IS DISTINCT FROM 'ORG_MANUAL_EXPENSE_V1' OR payload->>'purpose' IS DISTINCT FROM 'EXPENSE_ALLOCATION' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_USAGE_PURPOSE_UNSUPPORTED'; END IF;
  chainkey=plugin_finance_evidence_normalize(payload->>'businessChainKey');operationkey=plugin_finance_evidence_normalize(payload->>'sourceOperationKey');
  IF length(chainkey) NOT BETWEEN 1 AND 128 OR length(operationkey) NOT BETWEEN 1 AND 128 THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='INVALID_INPUT'; END IF;
  amt=plugin_finance_evidence_money(payload->>'amount');
  ph=plugin_finance_evidence_digest((payload-ARRAY['schemaVersion','idempotencyKey'])||jsonb_build_object('amount',plugin_finance_evidence_money_text(amt),'businessChainKey',chainkey,'sourceOperationKey',operationkey));
  SELECT * INTO a FROM plugin_finance_evidence_allocation WHERE tenant_id=t AND legal_entity_id=ent AND document_id=did AND purpose='EXPENSE_ALLOCATION' AND source_system='ORG_MANUAL_EXPENSE_V1' AND source_operation_key=operationkey;
  IF FOUND THEN
   IF a.book_id<>b OR a.year_id<>y THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_HOME_SCOPE_UNSUPPORTED'; END IF;
   IF a.create_payload_hash<>ph THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='IDEMPOTENCY_CONFLICT'; END IF;
   RETURN jsonb_build_object('kind','ALLOCATION','id',a.id,'documentId',a.document_id,'allocationId',a.id);
  END IF;
  bs=plugin_finance_evidence_basis(t,b,y,did);
  IF doc.basis_version<>(payload->>'expectedBasisVersion')::bigint THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_BASIS_STALE'; END IF;
  os=plugin_finance_evidence_original(t,b,y,(payload->>'purposeEvidenceOriginalId')::uuid);
  SELECT id,book_id,year_id INTO cid,target,child FROM plugin_finance_evidence_chain WHERE tenant_id=t AND legal_entity_id=ent AND source_system='ORG_MANUAL_EXPENSE_V1' AND business_chain_key=chainkey;
  IF cid IS NOT NULL AND (target<>b OR child<>y) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_HOME_SCOPE_UNSUPPORTED'; END IF;
  IF cid IS NULL THEN cid=gen_random_uuid();INSERT INTO plugin_finance_evidence_chain(tenant_id,legal_entity_id,book_id,year_id,id,source_system,business_chain_key,created_by) VALUES(t,ent,b,y,cid,'ORG_MANUAL_EXPENSE_V1',chainkey,who);END IF;
  aid=gen_random_uuid();nv=1;cv=1;old_status=NULL;next_status='DRAFT';event_action='CREATE';
  ct=jsonb_build_object('documentId',did,'businessChainId',cid,'businessChainKey',chainkey,'sourceSystem','ORG_MANUAL_EXPENSE_V1','sourceOperationKey',operationkey,'purpose','EXPENSE_ALLOCATION','occurredDate',payload->>'occurredDate','amount',plugin_finance_evidence_money_text(amt),'description',payload->>'description','basis',bs,'purposeEvidence',os);dg=plugin_finance_evidence_digest(ct);
  INSERT INTO plugin_finance_evidence_allocation(tenant_id,legal_entity_id,book_id,year_id,id,document_id,chain_id,source_system,source_operation_key,create_payload_hash,content,content_digest,amount,occurred_date,description,basis_review_id,basis_version,basis_snapshot,purpose_original_id,purpose_original_snapshot,prepared_by,last_editor) VALUES(t,ent,b,y,aid,did,cid,'ORG_MANUAL_EXPENSE_V1',operationkey,ph,ct,dg,amt,(payload->>'occurredDate')::date,payload->>'description',(bs->>'reviewId')::uuid,doc.basis_version,bs,(payload->>'purposeEvidenceOriginalId')::uuid,os,who,who);
 ELSE
  -- Entity serialization is already held; lock document before its allocation.
  SELECT document_id INTO did FROM plugin_finance_evidence_allocation WHERE(tenant_id,book_id,year_id,id)=(t,b,y,target);
  SELECT * INTO doc FROM plugin_finance_evidence_document WHERE(tenant_id,book_id,year_id,id)=(t,b,y,did) FOR UPDATE;
  SELECT * INTO a FROM plugin_finance_evidence_allocation WHERE(tenant_id,book_id,year_id,id)=(t,b,y,target) FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SCOPE_DENIED'; END IF;
  aid=a.id;nv=a.version+1;cv=a.content_version;ct=a.content;dg=a.content_digest;amt=a.amount;old_status=a.status;next_status=a.status;
  IF op='REVERSE_REQUEST' THEN
   IF a.version<>(payload->>'expectedAllocationVersion')::bigint OR a.content_digest IS DISTINCT FROM payload->>'allocationContentDigest' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='VERSION_CONFLICT'; END IF;
  ELSIF op NOT IN('REVERSE_APPROVE','REVERSE_REJECT','REVERSE_CANCEL') THEN
   IF a.version<>(payload->>'expectedVersion')::bigint OR a.content_digest IS DISTINCT FROM payload->>'contentDigest' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='VERSION_CONFLICT'; END IF;
  END IF;
  IF op IN('EDIT','SUBMIT','WITHDRAW','CANCEL') AND a.prepared_by->>'accountId'<>who->>'accountId' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SCOPE_DENIED'; END IF;
  IF op IN('CONFIRM','REJECT','ADMIN_TERMINATE') AND (who->>'personId'=a.prepared_by->>'personId' OR who->>'personId'=a.last_editor->>'personId') THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SELF_APPROVAL_FORBIDDEN'; END IF;
  IF op IN('SUBMIT','CONFIRM') THEN
   PERFORM plugin_finance_evidence_scope(t,b,y,true,a.occurred_date);
   IF doc.pending_review_id IS NOT NULL THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_BASIS_REVIEW_PENDING'; END IF;
   IF a.basis_version<>doc.basis_version OR a.basis_snapshot IS DISTINCT FROM plugin_finance_evidence_basis(t,b,y,did) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_BASIS_STALE'; END IF;
   PERFORM plugin_finance_evidence_actor(t,b,y,a.prepared_by,ARRAY['OWNER','MAKER']);PERFORM plugin_finance_evidence_actor(t,b,y,a.last_editor,ARRAY['OWNER','MAKER']);
  END IF;
  IF op='EDIT' THEN
   IF a.status<>'DRAFT' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_CONSUMER_STATE_CONFLICT'; END IF;
   IF doc.basis_version<>(payload->>'expectedBasisVersion')::bigint THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_BASIS_STALE'; END IF;
   bs=plugin_finance_evidence_basis(t,b,y,did);os=plugin_finance_evidence_original(t,b,y,(payload->>'purposeEvidenceOriginalId')::uuid);amt=plugin_finance_evidence_money(payload->>'amount');
   ct=a.content||jsonb_build_object('occurredDate',payload->>'occurredDate','amount',plugin_finance_evidence_money_text(amt),'description',payload->>'description','basis',bs,'purposeEvidence',os);dg=plugin_finance_evidence_digest(ct);cv=a.content_version+CASE WHEN ct IS DISTINCT FROM a.content THEN 1 ELSE 0 END;event_action='EDIT';
   UPDATE plugin_finance_evidence_allocation SET content=ct,content_digest=dg,content_version=cv,amount=amt,occurred_date=(payload->>'occurredDate')::date,description=payload->>'description',basis_review_id=(bs->>'reviewId')::uuid,basis_version=doc.basis_version,basis_snapshot=bs,purpose_original_id=(payload->>'purposeEvidenceOriginalId')::uuid,purpose_original_snapshot=os,last_editor=who WHERE(tenant_id,book_id,year_id,id)=(t,b,y,aid);
  ELSIF op='SUBMIT' THEN
   IF a.status<>'DRAFT' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_CONSUMER_STATE_CONFLICT'; END IF;
   IF plugin_finance_evidence_reserved(t,b,y,did)+plugin_finance_evidence_net(t,b,y,did)+a.amount>(a.basis_snapshot->>'expenseCapacity')::numeric THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_CAPACITY_EXCEEDED'; END IF;
   sid=gen_random_uuid();uid=gen_random_uuid();
   INSERT INTO plugin_finance_evidence_submission(tenant_id,legal_entity_id,book_id,year_id,id,document_id,allocation_id,allocation_version,content_version,content_digest,review_id,basis_version,amount,usage_id,submitted_by) VALUES(t,ent,b,y,sid,did,aid,nv,cv,dg,a.basis_review_id,a.basis_version,amt,uid,who);
   INSERT INTO plugin_finance_evidence_usage(tenant_id,legal_entity_id,book_id,year_id,id,document_id,allocation_id,submission_id,amount,state,expires_at) VALUES(t,ent,b,y,uid,did,aid,sid,amt,'RESERVED',CURRENT_TIMESTAMP+interval '7 days');
   next_status='SUBMITTED';event_action='SUBMIT';
   UPDATE plugin_finance_evidence_allocation SET current_submission_id=sid,current_usage_id=uid WHERE(tenant_id,book_id,year_id,id)=(t,b,y,aid);
  ELSIF op='CONFIRM' THEN
   IF a.status<>'SUBMITTED' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_CONSUMER_STATE_CONFLICT'; END IF;
   sid=a.current_submission_id;uid=a.current_usage_id;SELECT * INTO ss FROM plugin_finance_evidence_submission WHERE(tenant_id,book_id,year_id,id)=(t,b,y,sid);
   IF clock_timestamp()>=ss.expires_at THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_SUBMISSION_EXPIRED'; END IF;
   fid=gen_random_uuid();INSERT INTO plugin_finance_evidence_confirmation(tenant_id,legal_entity_id,book_id,year_id,id,document_id,allocation_id,submission_id,usage_id,allocation_version,content_version,content_digest,amount,confirmed_by) VALUES(t,ent,b,y,fid,did,aid,sid,uid,nv,cv,dg,amt,who);
   UPDATE plugin_finance_evidence_usage SET state='COMMITTED',confirmation_id=fid,committed_at=CURRENT_TIMESTAMP WHERE(tenant_id,book_id,year_id,id)=(t,b,y,uid) AND state='RESERVED';
   IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_CONSUMER_STATE_CONFLICT'; END IF;
   UPDATE plugin_finance_evidence_allocation SET current_confirmation_id=fid WHERE(tenant_id,book_id,year_id,id)=(t,b,y,aid);next_status='CONFIRMED';event_action='CONFIRM';
  ELSIF op IN('WITHDRAW','REJECT','ADMIN_TERMINATE','CANCEL') THEN
   IF (op IN('WITHDRAW','REJECT') AND a.status<>'SUBMITTED') OR(op='CANCEL' AND a.status<>'DRAFT') OR(op='ADMIN_TERMINATE' AND a.status NOT IN('DRAFT','SUBMITTED')) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_CONSUMER_STATE_CONFLICT'; END IF;
   sid=CASE WHEN a.status='SUBMITTED' THEN a.current_submission_id END;uid=CASE WHEN a.status='SUBMITTED' THEN a.current_usage_id END;lid=gen_random_uuid();
   INSERT INTO plugin_finance_evidence_release(tenant_id,legal_entity_id,book_id,year_id,id,document_id,allocation_id,allocation_version,submission_id,usage_id,action,actor,reason) VALUES(t,ent,b,y,lid,did,aid,nv,sid,uid,op,who,payload->>'reason');
   IF uid IS NOT NULL THEN UPDATE plugin_finance_evidence_usage SET state='RELEASED',release_id=lid,released_at=CURRENT_TIMESTAMP WHERE(tenant_id,book_id,year_id,id)=(t,b,y,uid) AND state='RESERVED';IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_CONSUMER_STATE_CONFLICT';END IF;END IF;
   UPDATE plugin_finance_evidence_allocation SET current_submission_id=NULL,current_usage_id=NULL WHERE(tenant_id,book_id,year_id,id)=(t,b,y,aid);
   next_status=CASE WHEN op IN('WITHDRAW','REJECT') THEN 'DRAFT' ELSE 'CANCELLED' END;event_action=op;
  ELSIF op='REVERSE_REQUEST' THEN
   IF a.status<>'CONFIRMED' OR a.pending_reverse_id IS NOT NULL THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_CONSUMER_STATE_CONFLICT'; END IF;
   amt=plugin_finance_evidence_money(payload->>'amount');SELECT coalesce(sum(amount),0) INTO reversed FROM plugin_finance_evidence_reverse_effect WHERE(tenant_id,book_id,year_id,allocation_id)=(t,b,y,aid);before_net=a.amount-reversed;
   IF amt>before_net THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_REVERSE_EXCEEDS_REMAINING'; END IF;
   os=plugin_finance_evidence_original(t,b,y,(payload->>'basisOriginalId')::uuid);SELECT * INTO cf FROM plugin_finance_evidence_confirmation WHERE(tenant_id,book_id,year_id,id)=(t,b,y,a.current_confirmation_id);
   rid=gen_random_uuid();bs=jsonb_build_object('allocationId',aid,'confirmationId',cf.id,'usageId',a.current_usage_id,'amount',plugin_finance_evidence_money_text(amt),'basisOriginal',os,'reason',payload->>'reason','originalPreparedBy',a.prepared_by,'originalConfirmedBy',cf.confirmed_by,'originalConfirmedAmount',plugin_finance_evidence_money_text(a.amount),'alreadyReversedAmount',plugin_finance_evidence_money_text(reversed),'netRemainingBefore',plugin_finance_evidence_money_text(before_net),'netRemainingAfterProposed',plugin_finance_evidence_money_text(before_net-amt),'scopeOfEffect','EVIDENCE_CAPACITY_ONLY_NOT_BANK_REFUND_OR_GL_CORRECTION');
   INSERT INTO plugin_finance_evidence_reverse_request(tenant_id,legal_entity_id,book_id,year_id,id,document_id,allocation_id,previous_reverse_request_id,confirmation_id,usage_id,expected_allocation_version,amount,basis_original_id,basis_original_snapshot,content,content_digest,requested_by,reason) VALUES(t,ent,b,y,rid,did,aid,a.latest_reverse_id,cf.id,a.current_usage_id,a.version,amt,(payload->>'basisOriginalId')::uuid,os,bs,plugin_finance_evidence_digest(bs),who,payload->>'reason');
   UPDATE plugin_finance_evidence_allocation SET pending_reverse_id=rid,latest_reverse_id=rid WHERE(tenant_id,book_id,year_id,id)=(t,b,y,aid);rv_kind='REVERSE';result_id=rid;
  ELSIF op IN('REVERSE_APPROVE','REVERSE_REJECT','REVERSE_CANCEL') THEN
   SELECT * INTO rr FROM plugin_finance_evidence_reverse_request WHERE(tenant_id,book_id,year_id,allocation_id,id)=(t,b,y,aid,child) FOR UPDATE;
   IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SCOPE_DENIED'; END IF;
   IF rr.version<>(payload->>'expectedVersion')::bigint OR rr.content_digest IS DISTINCT FROM payload->>'contentDigest' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='VERSION_CONFLICT'; END IF;
   IF rr.status<>'PENDING' OR a.pending_reverse_id IS DISTINCT FROM rr.id THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_CONSUMER_STATE_CONFLICT'; END IF;
   IF op='REVERSE_CANCEL' THEN IF who->>'accountId'<>rr.requested_by->>'accountId' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SCOPE_DENIED';END IF;
   ELSIF who->>'personId'=rr.requested_by->>'personId' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SELF_APPROVAL_FORBIDDEN';END IF;
   IF op='REVERSE_APPROVE' THEN
    PERFORM plugin_finance_evidence_actor(t,b,y,rr.requested_by,ARRAY['OWNER']);
    IF clock_timestamp()>=rr.expires_at THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_REVIEW_EXPIRED'; END IF;
    SELECT coalesce(sum(amount),0) INTO reversed FROM plugin_finance_evidence_reverse_effect WHERE(tenant_id,book_id,year_id,allocation_id)=(t,b,y,aid);
    IF rr.amount>a.amount-reversed THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_REVERSE_EXCEEDS_REMAINING';END IF;
    eid=gen_random_uuid();INSERT INTO plugin_finance_evidence_reverse_effect(tenant_id,legal_entity_id,book_id,year_id,id,document_id,allocation_id,request_id,confirmation_id,usage_id,allocation_version,amount,approved_by) VALUES(t,ent,b,y,eid,did,aid,rr.id,rr.confirmation_id,rr.usage_id,nv,rr.amount,who);event_action='REVERSE';
   END IF;
   UPDATE plugin_finance_evidence_reverse_request SET status=CASE op WHEN 'REVERSE_APPROVE' THEN 'APPROVED' WHEN 'REVERSE_REJECT' THEN 'REJECTED' ELSE 'CANCELLED' END,version=version+1,decided_by=who,decided_at=CURRENT_TIMESTAMP,decision_reason=payload->>'reason',reverse_effect_id=eid WHERE(tenant_id,book_id,year_id,id)=(t,b,y,rr.id);
   UPDATE plugin_finance_evidence_allocation SET pending_reverse_id=NULL WHERE(tenant_id,book_id,year_id,id)=(t,b,y,aid);rv_kind='REVERSE';result_id=rr.id;
  ELSE RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='INVALID_INPUT'; END IF;
  IF event_action IS NOT NULL THEN UPDATE plugin_finance_evidence_allocation SET status=next_status,version=nv,updated_at=CURRENT_TIMESTAMP WHERE(tenant_id,book_id,year_id,id)=(t,b,y,aid);END IF;
 END IF;
 IF event_action IS NOT NULL THEN
  INSERT INTO plugin_finance_evidence_allocation_event(tenant_id,legal_entity_id,book_id,year_id,document_id,allocation_id,allocation_version,previous_version,action,from_status,to_status,content_version,content,content_digest,actor,reason,submission_id,usage_id,confirmation_id,release_id,reverse_effect_id) VALUES(t,ent,b,y,did,aid,nv,CASE WHEN nv>1 THEN nv-1 END,event_action,old_status,next_status,cv,ct,dg,who,payload->>'reason',sid,uid,fid,lid,eid);
 END IF;
 INSERT INTO plugin_finance_evidence_audit(tenant_id,legal_entity_id,book_id,year_id,actor,action,document_id,allocation_id,reverse_request_id,payload) VALUES(t,ent,b,y,who,op,did,aid,CASE WHEN rv_kind='REVERSE' THEN result_id END,jsonb_build_object('allocationId',aid,'reason',payload->'reason'));
 RETURN jsonb_build_object('kind',rv_kind,'id',coalesce(result_id,aid),'documentId',did,'allocationId',aid);
END $$;
-- All mutable workflow records have immutable economic identity, exact typed effects and final reverse proof.
CREATE FUNCTION plugin_finance_evidence_write_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE n jsonb=to_jsonb(NEW);o jsonb=to_jsonb(OLD);t uuid;b uuid;y uuid;ent uuid;who jsonb;roles text[];a plugin_finance_evidence_allocation;d plugin_finance_evidence_document;f jsonb;newuse boolean=false;
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_CONSUMER_STATE_CONFLICT'; END IF;
 t=(n->>'tenant_id')::uuid;b=(n->>'book_id')::uuid;y=(n->>'year_id')::uuid;ent=(n->>'legal_entity_id')::uuid;
 IF TG_OP='INSERT' AND ((n?'created_at' AND (n->>'created_at')::timestamptz IS DISTINCT FROM CURRENT_TIMESTAMP) OR (TG_TABLE_NAME='plugin_finance_evidence_submission' AND (n->>'submitted_at')::timestamptz IS DISTINCT FROM CURRENT_TIMESTAMP) OR(TG_TABLE_NAME='plugin_finance_evidence_usage' AND (n->>'reserved_at')::timestamptz IS DISTINCT FROM CURRENT_TIMESTAMP) OR(TG_TABLE_NAME='plugin_finance_evidence_confirmation' AND (n->>'confirmed_at')::timestamptz IS DISTINCT FROM CURRENT_TIMESTAMP) OR(TG_TABLE_NAME='plugin_finance_evidence_release' AND (n->>'released_at')::timestamptz IS DISTINCT FROM CURRENT_TIMESTAMP) OR(TG_TABLE_NAME='plugin_finance_evidence_reverse_effect' AND (n->>'approved_at')::timestamptz IS DISTINCT FROM CURRENT_TIMESTAMP)) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_CONSUMER_STATE_CONFLICT';END IF;
 IF TG_OP='UPDATE' AND TG_TABLE_NAME IN('plugin_finance_evidence_identity_alias','plugin_finance_evidence_hash_alias','plugin_finance_evidence_chain','plugin_finance_evidence_submission','plugin_finance_evidence_confirmation','plugin_finance_evidence_release','plugin_finance_evidence_reverse_effect','plugin_finance_evidence_allocation_event','plugin_finance_evidence_request','plugin_finance_evidence_audit') THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_CONSUMER_STATE_CONFLICT'; END IF;
 IF TG_TABLE_NAME='plugin_finance_evidence_document' THEN
  newuse=TG_OP='INSERT';
  IF TG_OP='UPDATE' AND (o-ARRAY['home_book_id','home_year_id','version','basis_version','current_review_id','latest_review_id','pending_review_id']) IS DISTINCT FROM(n-ARRAY['home_book_id','home_year_id','version','basis_version','current_review_id','latest_review_id','pending_review_id']) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_IDENTITY_CONFLICT'; END IF;
  IF TG_OP='INSERT' THEN who=NEW.created_by;roles=ARRAY['OWNER'];IF NEW.version<>1 OR NEW.basis_version<>0 OR NEW.current_review_id IS NOT NULL OR NEW.pending_review_id IS DISTINCT FROM NEW.latest_review_id THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_CONSUMER_STATE_CONFLICT';END IF;END IF;
 ELSIF TG_TABLE_NAME='plugin_finance_evidence_review' THEN
  IF TG_OP='INSERT' THEN newuse=true;who=NEW.prepared_by;roles=ARRAY['OWNER'];IF NEW.status<>'PENDING' OR NEW.version<>1 OR NEW.created_at IS DISTINCT FROM CURRENT_TIMESTAMP THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_CONSUMER_STATE_CONFLICT';END IF;
  ELSE
   IF OLD.status<>'PENDING' OR NEW.status NOT IN('APPROVED','REJECTED','CANCELLED') OR NEW.version<>OLD.version+1 OR (o-ARRAY['status','version','published_basis_version','decided_by','decided_at','decision_reason']) IS DISTINCT FROM(n-ARRAY['status','version','published_basis_version','decided_by','decided_at','decision_reason']) OR NEW.decided_at IS DISTINCT FROM CURRENT_TIMESTAMP THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_CONSUMER_STATE_CONFLICT'; END IF;
   who=NEW.decided_by;roles=CASE WHEN NEW.status='CANCELLED' THEN ARRAY['OWNER'] ELSE ARRAY['OWNER','REVIEWER'] END;newuse=NEW.status='APPROVED';
   IF (NEW.status='CANCELLED' AND who->>'accountId'<>OLD.prepared_by->>'accountId') OR (NEW.status<>'CANCELLED' AND who->>'personId'=OLD.prepared_by->>'personId') THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SELF_APPROVAL_FORBIDDEN'; END IF;
   IF NEW.status='APPROVED' THEN
    PERFORM plugin_finance_evidence_actor(t,b,y,OLD.prepared_by,ARRAY['OWNER']);
    IF clock_timestamp()>=OLD.expires_at THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_REVIEW_EXPIRED';END IF;
   END IF;
  END IF;
  IF NEW.content->>'basisOriginalId' IS DISTINCT FROM NEW.basis_original_id::text OR NEW.content_digest IS DISTINCT FROM plugin_finance_evidence_digest(NEW.content) OR NEW.basis_original_snapshot IS DISTINCT FROM plugin_finance_evidence_original(t,b,y,NEW.basis_original_id) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_BASIS_STALE';END IF;
 ELSIF TG_TABLE_NAME='plugin_finance_evidence_allocation' THEN
  IF TG_OP='INSERT' THEN newuse=true;who=NEW.prepared_by;roles=ARRAY['OWNER','MAKER'];IF NEW.status<>'DRAFT' OR NEW.version<>1 OR NEW.content_version<>1 OR NEW.prepared_by IS DISTINCT FROM NEW.last_editor OR NEW.current_submission_id IS NOT NULL OR NEW.current_usage_id IS NOT NULL OR NEW.current_confirmation_id IS NOT NULL THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_CONSUMER_STATE_CONFLICT';END IF;
  ELSE
   IF (o-ARRAY['status','version','content_version','content','content_digest','amount','occurred_date','description','basis_review_id','basis_version','basis_snapshot','purpose_original_id','purpose_original_snapshot','last_editor','current_submission_id','current_usage_id','current_confirmation_id','pending_reverse_id','latest_reverse_id','updated_at']) IS DISTINCT FROM(n-ARRAY['status','version','content_version','content','content_digest','amount','occurred_date','description','basis_review_id','basis_version','basis_snapshot','purpose_original_id','purpose_original_snapshot','last_editor','current_submission_id','current_usage_id','current_confirmation_id','pending_reverse_id','latest_reverse_id','updated_at']) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_CONSUMER_STATE_CONFLICT';END IF;
   IF OLD.status IN('CONFIRMED','CANCELLED') AND (o-ARRAY['version','pending_reverse_id','latest_reverse_id','updated_at']) IS DISTINCT FROM(n-ARRAY['version','pending_reverse_id','latest_reverse_id','updated_at']) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_CONSUMER_STATE_CONFLICT';END IF;
   IF NEW.content IS DISTINCT FROM OLD.content THEN
    IF OLD.status<>'DRAFT' OR NEW.status<>'DRAFT' OR NEW.content_version<>OLD.content_version+1 THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_CONSUMER_STATE_CONFLICT';END IF;newuse=true;who=NEW.last_editor;roles=ARRAY['OWNER','MAKER'];
   ELSIF NEW.content_version<>OLD.content_version THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_CONSUMER_STATE_CONFLICT';END IF;
  END IF;
  IF NEW.basis_snapshot->>'reviewId' IS DISTINCT FROM NEW.basis_review_id::text OR NEW.basis_snapshot->>'basisVersion' IS DISTINCT FROM NEW.basis_version::text OR NEW.content_digest IS DISTINCT FROM plugin_finance_evidence_digest(NEW.content) OR NEW.content->>'amount' IS DISTINCT FROM plugin_finance_evidence_money_text(NEW.amount) OR NEW.content->>'occurredDate' IS DISTINCT FROM NEW.occurred_date::text OR NEW.content->>'description' IS DISTINCT FROM NEW.description OR NEW.content->'basis' IS DISTINCT FROM NEW.basis_snapshot OR NEW.content->'purposeEvidence' IS DISTINCT FROM NEW.purpose_original_snapshot OR NEW.purpose_original_snapshot IS DISTINCT FROM plugin_finance_evidence_original(t,b,y,NEW.purpose_original_id) OR NEW.content->>'documentId' IS DISTINCT FROM NEW.document_id::text OR NEW.content->>'businessChainId' IS DISTINCT FROM NEW.chain_id::text OR NEW.content->>'sourceOperationKey' IS DISTINCT FROM NEW.source_operation_key OR NEW.content->>'sourceSystem' IS DISTINCT FROM NEW.source_system OR NEW.content->>'purpose' IS DISTINCT FROM NEW.purpose THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_BASIS_STALE';END IF;
 ELSIF TG_TABLE_NAME='plugin_finance_evidence_usage' AND TG_OP='UPDATE' THEN
  IF OLD.state<>'RESERVED' OR NEW.state NOT IN('COMMITTED','RELEASED') OR (o-ARRAY['state','confirmation_id','committed_at','release_id','released_at']) IS DISTINCT FROM(n-ARRAY['state','confirmation_id','committed_at','release_id','released_at']) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_CONSUMER_STATE_CONFLICT';END IF;
 ELSIF TG_TABLE_NAME='plugin_finance_evidence_reverse_request' THEN
  IF TG_OP='INSERT' THEN who=NEW.requested_by;roles=ARRAY['OWNER'];IF NEW.status<>'PENDING' OR NEW.version<>1 OR NEW.created_at IS DISTINCT FROM CURRENT_TIMESTAMP THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_CONSUMER_STATE_CONFLICT';END IF;
  ELSE
   IF OLD.status<>'PENDING' OR NEW.status NOT IN('APPROVED','REJECTED','CANCELLED') OR NEW.version<>OLD.version+1 OR (o-ARRAY['status','version','decided_by','decided_at','decision_reason','reverse_effect_id']) IS DISTINCT FROM(n-ARRAY['status','version','decided_by','decided_at','decision_reason','reverse_effect_id']) OR NEW.decided_at IS DISTINCT FROM CURRENT_TIMESTAMP THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_CONSUMER_STATE_CONFLICT';END IF;
   who=NEW.decided_by;roles=CASE WHEN NEW.status='CANCELLED' THEN ARRAY['OWNER'] ELSE ARRAY['OWNER','REVIEWER'] END;
   IF (NEW.status='CANCELLED' AND who->>'accountId'<>OLD.requested_by->>'accountId') OR (NEW.status<>'CANCELLED' AND who->>'personId'=OLD.requested_by->>'personId') THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SELF_APPROVAL_FORBIDDEN';END IF;
   IF NEW.status='APPROVED' THEN PERFORM plugin_finance_evidence_actor(t,b,y,OLD.requested_by,ARRAY['OWNER']);IF clock_timestamp()>=OLD.expires_at THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_REVIEW_EXPIRED';END IF;END IF;
  END IF;
  IF NEW.content_digest IS DISTINCT FROM plugin_finance_evidence_digest(NEW.content) OR NEW.basis_original_snapshot IS DISTINCT FROM plugin_finance_evidence_original(t,b,y,NEW.basis_original_id) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_BASIS_STALE';END IF;
 END IF;
 PERFORM plugin_finance_evidence_scope(t,b,y,newuse,CASE WHEN TG_TABLE_NAME='plugin_finance_evidence_allocation' AND newuse THEN(n->>'occurred_date')::date END);
 IF ent IS DISTINCT FROM(SELECT legal_entity_id FROM plugin_finance_book WHERE tenant_id=t AND id=b) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SCOPE_DENIED'; END IF;
 IF who IS NOT NULL THEN PERFORM plugin_finance_evidence_actor(t,b,y,who,roles);END IF;
 RETURN NEW;
END $$;
CREATE FUNCTION plugin_finance_evidence_event_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p plugin_finance_evidence_allocation_event;a plugin_finance_evidence_allocation;d plugin_finance_evidence_document;s plugin_finance_evidence_submission;roles text[];
BEGIN
 SELECT * INTO a FROM plugin_finance_evidence_allocation WHERE(tenant_id,book_id,year_id,id)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.allocation_id);
 SELECT * INTO d FROM plugin_finance_evidence_document WHERE(tenant_id,book_id,year_id,id)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.document_id);
 IF a.document_id IS DISTINCT FROM NEW.document_id OR NEW.legal_entity_id IS DISTINCT FROM a.legal_entity_id OR NEW.content_digest IS DISTINCT FROM plugin_finance_evidence_digest(NEW.content) OR NEW.created_at IS DISTINCT FROM CURRENT_TIMESTAMP THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_CONSUMER_STATE_CONFLICT';END IF;
 IF (NEW.action IN('CREATE','EDIT') AND NEW.reason IS NOT NULL) OR(NEW.action NOT IN('CREATE','EDIT') AND (NEW.reason IS NULL OR length(NEW.reason) NOT BETWEEN 1 AND 1000)) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='INVALID_INPUT';END IF;
 IF NEW.action='CREATE' THEN
  IF NEW.allocation_version<>1 OR NEW.from_status IS NOT NULL OR NEW.to_status<>'DRAFT' OR NEW.content_version<>1 OR NEW.actor IS DISTINCT FROM a.prepared_by THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_CONSUMER_STATE_CONFLICT';END IF;
 ELSE
  SELECT * INTO p FROM plugin_finance_evidence_allocation_event WHERE(tenant_id,book_id,year_id,allocation_id,allocation_version)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.allocation_id,NEW.previous_version);
  IF NOT FOUND OR NEW.from_status IS DISTINCT FROM p.to_status OR (NEW.action<>'EDIT' AND (NEW.content IS DISTINCT FROM p.content OR NEW.content_version<>p.content_version)) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_CONSUMER_STATE_CONFLICT';END IF;
 END IF;
 IF (NEW.action='EDIT' AND(NEW.from_status<>'DRAFT' OR NEW.to_status<>'DRAFT' OR NEW.content_version<>p.content_version+(CASE WHEN NEW.content IS DISTINCT FROM p.content THEN 1 ELSE 0 END))) OR(NEW.action='SUBMIT' AND(NEW.from_status<>'DRAFT' OR NEW.to_status<>'SUBMITTED')) OR(NEW.action='CONFIRM' AND(NEW.from_status<>'SUBMITTED' OR NEW.to_status<>'CONFIRMED')) OR(NEW.action IN('WITHDRAW','REJECT') AND(NEW.from_status<>'SUBMITTED' OR NEW.to_status<>'DRAFT')) OR(NEW.action='ADMIN_TERMINATE' AND(NEW.from_status NOT IN('DRAFT','SUBMITTED') OR NEW.to_status<>'CANCELLED')) OR(NEW.action='CANCEL' AND(NEW.from_status<>'DRAFT' OR NEW.to_status<>'CANCELLED')) OR(NEW.action='REVERSE' AND(NEW.from_status<>'CONFIRMED' OR NEW.to_status<>'CONFIRMED')) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_CONSUMER_STATE_CONFLICT';END IF;
 roles=CASE WHEN NEW.action IN('CONFIRM','REJECT','ADMIN_TERMINATE','REVERSE') THEN ARRAY['OWNER','REVIEWER'] ELSE ARRAY['OWNER','MAKER'] END;
 PERFORM plugin_finance_evidence_actor(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.actor,roles);
 IF NEW.action IN('CREATE','EDIT','SUBMIT','WITHDRAW','CANCEL') AND NEW.actor->>'accountId'<>a.prepared_by->>'accountId' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SCOPE_DENIED';END IF;
 IF NEW.action IN('CONFIRM','REJECT','ADMIN_TERMINATE') AND(NEW.actor->>'personId'=a.prepared_by->>'personId' OR NEW.actor->>'personId'=a.last_editor->>'personId') THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SELF_APPROVAL_FORBIDDEN';END IF;
 IF NEW.action IN('CREATE','EDIT','SUBMIT','CONFIRM') THEN
  PERFORM plugin_finance_evidence_scope(NEW.tenant_id,NEW.book_id,NEW.year_id,true,(NEW.content->>'occurredDate')::date);
  IF NEW.content->'basis' IS DISTINCT FROM plugin_finance_evidence_basis(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.document_id) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_BASIS_STALE';END IF;
  IF NEW.action IN('SUBMIT','CONFIRM') THEN
   IF d.pending_review_id IS NOT NULL THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_BASIS_REVIEW_PENDING';END IF;
   PERFORM plugin_finance_evidence_actor(NEW.tenant_id,NEW.book_id,NEW.year_id,a.prepared_by,ARRAY['OWNER','MAKER']);PERFORM plugin_finance_evidence_actor(NEW.tenant_id,NEW.book_id,NEW.year_id,a.last_editor,ARRAY['OWNER','MAKER']);
  END IF;
 END IF;
 IF NEW.action='CONFIRM' THEN SELECT * INTO s FROM plugin_finance_evidence_submission WHERE(tenant_id,book_id,year_id,id)=(NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.submission_id);IF NOT FOUND OR clock_timestamp()>=s.expires_at THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_SUBMISSION_EXPIRED';END IF;END IF;
 IF NEW.action IN('WITHDRAW','REJECT','ADMIN_TERMINATE','CANCEL') AND((NEW.from_status='SUBMITTED' AND(NEW.submission_id IS NULL OR NEW.usage_id IS NULL)) OR(NEW.from_status='DRAFT' AND(NEW.submission_id IS NOT NULL OR NEW.usage_id IS NOT NULL))) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_CONSUMER_STATE_CONFLICT';END IF;
 IF (NEW.action='SUBMIT' AND(NEW.submission_id IS NULL OR NEW.usage_id IS NULL OR NEW.confirmation_id IS NOT NULL OR NEW.release_id IS NOT NULL OR NEW.reverse_effect_id IS NOT NULL)) OR(NEW.action='CONFIRM' AND(NEW.submission_id IS NULL OR NEW.usage_id IS NULL OR NEW.confirmation_id IS NULL OR NEW.release_id IS NOT NULL OR NEW.reverse_effect_id IS NOT NULL)) OR(NEW.action IN('WITHDRAW','REJECT','ADMIN_TERMINATE','CANCEL') AND(NEW.release_id IS NULL OR NEW.confirmation_id IS NOT NULL OR NEW.reverse_effect_id IS NOT NULL)) OR(NEW.action='REVERSE' AND(NEW.reverse_effect_id IS NULL OR NEW.submission_id IS NOT NULL OR NEW.usage_id IS NOT NULL OR NEW.confirmation_id IS NOT NULL OR NEW.release_id IS NOT NULL)) OR(NEW.action IN('CREATE','EDIT') AND(NEW.submission_id IS NOT NULL OR NEW.usage_id IS NOT NULL OR NEW.confirmation_id IS NOT NULL OR NEW.release_id IS NOT NULL OR NEW.reverse_effect_id IS NOT NULL)) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_CONSUMER_STATE_CONFLICT';END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER finance_evidence_event_guard BEFORE INSERT ON plugin_finance_evidence_allocation_event FOR EACH ROW EXECUTE FUNCTION plugin_finance_evidence_event_guard();
CREATE FUNCTION plugin_finance_evidence_final_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE n jsonb=to_jsonb(NEW);t uuid=(n->>'tenant_id')::uuid;b uuid=(n->>'book_id')::uuid;y uuid=(n->>'year_id')::uuid;did uuid;aid uuid;
 d plugin_finance_evidence_document;r plugin_finance_evidence_review;a plugin_finance_evidence_allocation;e plugin_finance_evidence_allocation_event;s plugin_finance_evidence_submission;u plugin_finance_evidence_usage;c plugin_finance_evidence_confirmation;l plugin_finance_evidence_release;rr plugin_finance_evidence_reverse_request;re plugin_finance_evidence_reverse_effect;cnt bigint;net numeric;reserved numeric;cap numeric;
BEGIN
 IF TG_TABLE_NAME='plugin_finance_evidence_document' THEN did=(n->>'id')::uuid;ELSE did=(n->>'document_id')::uuid;END IF;
 IF did IS NULL THEN RETURN NULL;END IF;
 SELECT * INTO d FROM plugin_finance_evidence_document WHERE(tenant_id,book_id,year_id,id)=(t,b,y,did);
 IF NOT FOUND OR d.legal_entity_id IS DISTINCT FROM(n->>'legal_entity_id')::uuid OR d.legal_entity_id IS DISTINCT FROM(SELECT legal_entity_id FROM plugin_finance_book WHERE tenant_id=t AND id=b) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_CONSUMER_STATE_CONFLICT';END IF;
 IF NOT EXISTS(SELECT 1 FROM plugin_finance_evidence_review rootr WHERE(rootr.tenant_id,rootr.book_id,rootr.year_id,rootr.document_id)=(t,b,y,did) AND rootr.previous_review_id IS NULL AND rootr.content=d.registered_fields) OR d.registration_payload_hash IS DISTINCT FROM plugin_finance_evidence_digest(jsonb_build_object('fields',d.registered_fields,'sourceSha256',d.source_snapshot->>'sha256')) OR d.source_snapshot IS DISTINCT FROM plugin_finance_evidence_original(t,b,y,d.source_original_id) OR NOT EXISTS(SELECT 1 FROM plugin_finance_evidence_hash_alias WHERE tenant_id=t AND legal_entity_id=d.legal_entity_id AND document_id=d.id AND sha256=d.source_snapshot->>'sha256' AND book_id=b AND year_id=y) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_IDENTITY_CONFLICT';END IF;
 SELECT count(*)+count(*) FILTER(WHERE status<>'PENDING') INTO cnt FROM plugin_finance_evidence_review WHERE(tenant_id,book_id,year_id,document_id)=(t,b,y,did);
 IF d.version<>cnt OR d.basis_version<>(SELECT count(*) FROM plugin_finance_evidence_review WHERE(tenant_id,book_id,year_id,document_id)=(t,b,y,did) AND status='APPROVED') OR d.pending_review_id IS DISTINCT FROM(SELECT id FROM plugin_finance_evidence_review WHERE(tenant_id,book_id,year_id,document_id)=(t,b,y,did) AND status='PENDING') OR NOT EXISTS(SELECT 1 FROM plugin_finance_evidence_review WHERE(tenant_id,book_id,year_id,document_id,id)=(t,b,y,did,d.latest_review_id)) OR EXISTS(SELECT 1 FROM plugin_finance_evidence_review WHERE(tenant_id,book_id,year_id,document_id,previous_review_id)=(t,b,y,did,d.latest_review_id)) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_BASIS_STALE';END IF;
 IF (d.basis_version=0) IS DISTINCT FROM(d.current_review_id IS NULL) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_BASIS_STALE';END IF;
 IF d.current_review_id IS NOT NULL THEN
  SELECT * INTO r FROM plugin_finance_evidence_review WHERE(tenant_id,book_id,year_id,id)=(t,b,y,d.current_review_id);
  IF r.status<>'APPROVED' OR r.published_basis_version<>d.basis_version OR NOT EXISTS(SELECT 1 FROM plugin_finance_evidence_identity_alias WHERE tenant_id=t AND legal_entity_id=d.legal_entity_id AND document_id=did AND document_type=r.content->>'documentType' AND issuer_key=r.content->>'issuerKey' AND external_identity_key=r.content->>'externalIdentityKey') THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_BASIS_STALE';END IF;
  cap=(r.content->>'expenseCapacity')::numeric;net=plugin_finance_evidence_net(t,b,y,did);reserved=plugin_finance_evidence_reserved(t,b,y,did);
  IF net<0 OR reserved<0 OR net+reserved>cap THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_CAPACITY_EXCEEDED';END IF;
 END IF;
 IF TG_TABLE_NAME='plugin_finance_evidence_review' THEN
  SELECT * INTO r FROM plugin_finance_evidence_review WHERE(tenant_id,book_id,year_id,id)=(t,b,y,(n->>'id')::uuid);
  IF r.document_id<>did OR r.legal_entity_id<>d.legal_entity_id OR(r.previous_review_id IS NULL AND r.previous_basis_version IS NOT NULL) OR(r.status='APPROVED' AND NOT EXISTS(SELECT 1 FROM plugin_finance_evidence_identity_alias WHERE tenant_id=t AND legal_entity_id=d.legal_entity_id AND document_id=did AND document_type=r.content->>'documentType' AND issuer_key=r.content->>'issuerKey' AND external_identity_key=r.content->>'externalIdentityKey')) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_BASIS_STALE';END IF;
 END IF;
 IF TG_TABLE_NAME='plugin_finance_evidence_allocation' THEN aid=(n->>'id')::uuid;ELSE aid=(n->>'allocation_id')::uuid;END IF;
 IF aid IS NULL THEN RETURN NULL;END IF;
 SELECT * INTO a FROM plugin_finance_evidence_allocation WHERE(tenant_id,book_id,year_id,id)=(t,b,y,aid);
 IF NOT FOUND OR a.document_id<>did OR a.legal_entity_id<>d.legal_entity_id THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_CONSUMER_STATE_CONFLICT';END IF;
 SELECT * INTO e FROM plugin_finance_evidence_allocation_event WHERE(tenant_id,book_id,year_id,allocation_id,allocation_version)=(t,b,y,aid,a.version);
 IF NOT FOUND OR e.document_id<>did OR e.to_status<>a.status OR e.content_version<>a.content_version OR e.content_digest<>a.content_digest OR e.content IS DISTINCT FROM a.content OR a.version<>(SELECT count(*) FROM plugin_finance_evidence_allocation_event WHERE(tenant_id,book_id,year_id,allocation_id)=(t,b,y,aid)) OR a.pending_reverse_id IS DISTINCT FROM(SELECT id FROM plugin_finance_evidence_reverse_request WHERE(tenant_id,book_id,year_id,allocation_id)=(t,b,y,aid) AND status='PENDING') OR (a.latest_reverse_id IS NULL) IS DISTINCT FROM (NOT EXISTS(SELECT 1 FROM plugin_finance_evidence_reverse_request WHERE(tenant_id,book_id,year_id,allocation_id)=(t,b,y,aid))) OR EXISTS(SELECT 1 FROM plugin_finance_evidence_reverse_request WHERE(tenant_id,book_id,year_id,allocation_id,previous_reverse_request_id)=(t,b,y,aid,a.latest_reverse_id)) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_CONSUMER_STATE_CONFLICT';END IF;
 IF NOT EXISTS(SELECT 1 FROM plugin_finance_evidence_chain ch WHERE(ch.tenant_id,ch.book_id,ch.year_id,ch.id)=(t,b,y,a.chain_id) AND ch.legal_entity_id=a.legal_entity_id AND ch.source_system=a.source_system AND ch.business_chain_key=a.content->>'businessChainKey') THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_IDENTITY_CONFLICT';END IF;
 IF EXISTS(SELECT 1 FROM plugin_finance_evidence_usage activeu WHERE(activeu.tenant_id,activeu.book_id,activeu.year_id,activeu.allocation_id)=(t,b,y,aid) AND activeu.state IN('RESERVED','COMMITTED') AND(activeu.document_id<>did OR activeu.legal_entity_id<>a.legal_entity_id OR activeu.id IS DISTINCT FROM a.current_usage_id OR activeu.submission_id IS DISTINCT FROM a.current_submission_id OR activeu.amount<>a.amount OR(activeu.state='RESERVED' AND(a.status<>'SUBMITTED' OR a.current_confirmation_id IS NOT NULL)) OR(activeu.state='COMMITTED' AND(a.status<>'CONFIRMED' OR activeu.confirmation_id IS DISTINCT FROM a.current_confirmation_id)))) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_CONSUMER_STATE_CONFLICT';END IF;
 IF NOT EXISTS(SELECT 1 FROM plugin_finance_evidence_allocation_event ce WHERE(ce.tenant_id,ce.book_id,ce.year_id,ce.allocation_id,ce.allocation_version)=(t,b,y,aid,1) AND ce.actor=a.prepared_by AND ce.created_at=a.created_at) OR a.last_editor IS DISTINCT FROM(SELECT le.actor FROM plugin_finance_evidence_allocation_event le WHERE(le.tenant_id,le.book_id,le.year_id,le.allocation_id)=(t,b,y,aid) AND le.action IN('CREATE','EDIT') ORDER BY le.allocation_version DESC LIMIT 1) OR a.updated_at IS DISTINCT FROM e.created_at THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_CONSUMER_STATE_CONFLICT';END IF;
 IF a.status IN('DRAFT','CANCELLED') AND(a.current_submission_id IS NOT NULL OR a.current_usage_id IS NOT NULL OR a.current_confirmation_id IS NOT NULL) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_CONSUMER_STATE_CONFLICT';END IF;
 IF a.status IN('SUBMITTED','CONFIRMED') THEN
  SELECT * INTO u FROM plugin_finance_evidence_usage WHERE(tenant_id,book_id,year_id,id)=(t,b,y,a.current_usage_id);
  IF NOT FOUND OR u.allocation_id<>aid OR u.document_id<>did OR u.submission_id IS DISTINCT FROM a.current_submission_id OR u.amount<>a.amount OR u.state<>(CASE a.status WHEN 'SUBMITTED' THEN 'RESERVED' ELSE 'COMMITTED' END) OR u.confirmation_id IS DISTINCT FROM a.current_confirmation_id THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_CONSUMER_STATE_CONFLICT';END IF;
 END IF;
 -- Check the touched historical typed child, not every historical row in the tenant.
 IF TG_TABLE_NAME='plugin_finance_evidence_submission' THEN
  SELECT * INTO s FROM plugin_finance_evidence_submission WHERE(tenant_id,book_id,year_id,id)=(t,b,y,(n->>'id')::uuid);
  SELECT * INTO u FROM plugin_finance_evidence_usage WHERE(tenant_id,book_id,year_id,id)=(t,b,y,s.usage_id);
  SELECT * INTO e FROM plugin_finance_evidence_allocation_event WHERE(tenant_id,book_id,year_id,allocation_id,allocation_version)=(t,b,y,aid,s.allocation_version);
  IF s.submitted_at IS DISTINCT FROM e.created_at OR u.submission_id IS DISTINCT FROM s.id OR u.allocation_id<>aid OR u.document_id<>did OR u.amount<>s.amount OR e.action<>'SUBMIT' OR e.submission_id IS DISTINCT FROM s.id OR e.usage_id IS DISTINCT FROM u.id OR e.content_digest<>s.content_digest OR e.content_version<>s.content_version OR e.content->'basis'->>'reviewId' IS DISTINCT FROM s.review_id::text OR e.content->'basis'->>'basisVersion' IS DISTINCT FROM s.basis_version::text OR e.content->>'amount' IS DISTINCT FROM plugin_finance_evidence_money_text(s.amount) OR s.submitted_by IS DISTINCT FROM e.actor OR u.reserved_at<>s.submitted_at OR u.expires_at<>s.expires_at THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_CONSUMER_STATE_CONFLICT';END IF;
 ELSIF TG_TABLE_NAME='plugin_finance_evidence_usage' THEN
  SELECT * INTO u FROM plugin_finance_evidence_usage WHERE(tenant_id,book_id,year_id,id)=(t,b,y,(n->>'id')::uuid);
  SELECT * INTO s FROM plugin_finance_evidence_submission WHERE(tenant_id,book_id,year_id,id)=(t,b,y,u.submission_id);
  IF s.usage_id IS DISTINCT FROM u.id OR s.allocation_id<>aid OR s.document_id<>did OR s.amount<>u.amount THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_CONSUMER_STATE_CONFLICT';END IF;
  IF u.state='RESERVED' AND(a.status<>'SUBMITTED' OR a.current_usage_id IS DISTINCT FROM u.id) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_CONSUMER_STATE_CONFLICT';END IF;
  IF u.state='COMMITTED' THEN
   SELECT * INTO c FROM plugin_finance_evidence_confirmation WHERE(tenant_id,book_id,year_id,id)=(t,b,y,u.confirmation_id);
   IF c.usage_id IS DISTINCT FROM u.id OR c.submission_id<>u.submission_id OR c.allocation_id<>aid OR c.document_id<>did OR c.amount<>u.amount OR a.status<>'CONFIRMED' OR a.current_confirmation_id IS DISTINCT FROM c.id OR u.committed_at<>c.confirmed_at THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_CONSUMER_STATE_CONFLICT';END IF;
  ELSIF u.state='RELEASED' THEN
   SELECT * INTO l FROM plugin_finance_evidence_release WHERE(tenant_id,book_id,year_id,id)=(t,b,y,u.release_id);
   IF l.usage_id IS DISTINCT FROM u.id OR l.submission_id<>u.submission_id OR l.allocation_id<>aid OR l.document_id<>did OR l.released_at<>u.released_at THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_CONSUMER_STATE_CONFLICT';END IF;
  END IF;
 ELSIF TG_TABLE_NAME='plugin_finance_evidence_confirmation' THEN
  SELECT * INTO c FROM plugin_finance_evidence_confirmation WHERE(tenant_id,book_id,year_id,id)=(t,b,y,(n->>'id')::uuid);
  SELECT * INTO u FROM plugin_finance_evidence_usage WHERE(tenant_id,book_id,year_id,id)=(t,b,y,c.usage_id);
  SELECT * INTO s FROM plugin_finance_evidence_submission WHERE(tenant_id,book_id,year_id,id)=(t,b,y,c.submission_id);
  SELECT * INTO e FROM plugin_finance_evidence_allocation_event WHERE(tenant_id,book_id,year_id,allocation_id,allocation_version)=(t,b,y,aid,c.allocation_version);
  IF c.confirmed_at IS DISTINCT FROM e.created_at OR u.state<>'COMMITTED' OR u.confirmation_id IS DISTINCT FROM c.id OR u.submission_id<>s.id OR s.usage_id<>u.id OR s.amount<>c.amount OR c.amount<>a.amount OR c.content_digest<>s.content_digest OR c.content_version<>s.content_version OR e.action<>'CONFIRM' OR e.confirmation_id IS DISTINCT FROM c.id OR e.submission_id IS DISTINCT FROM s.id OR e.usage_id IS DISTINCT FROM u.id OR e.content_digest<>c.content_digest OR e.content_version<>c.content_version OR e.actor IS DISTINCT FROM c.confirmed_by THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_CONSUMER_STATE_CONFLICT';END IF;
 ELSIF TG_TABLE_NAME='plugin_finance_evidence_release' THEN
  SELECT * INTO l FROM plugin_finance_evidence_release WHERE(tenant_id,book_id,year_id,id)=(t,b,y,(n->>'id')::uuid);
  SELECT * INTO e FROM plugin_finance_evidence_allocation_event WHERE(tenant_id,book_id,year_id,allocation_id,allocation_version)=(t,b,y,aid,l.allocation_version);
  IF l.released_at IS DISTINCT FROM e.created_at OR (e.from_status='SUBMITTED' AND(l.submission_id IS NULL OR l.usage_id IS NULL)) OR(e.from_status='DRAFT' AND(l.submission_id IS NOT NULL OR l.usage_id IS NOT NULL)) OR e.reason IS DISTINCT FROM l.reason OR e.action<>l.action OR e.release_id IS DISTINCT FROM l.id OR e.usage_id IS DISTINCT FROM l.usage_id OR e.submission_id IS DISTINCT FROM l.submission_id OR e.actor IS DISTINCT FROM l.actor THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_CONSUMER_STATE_CONFLICT';END IF;
  IF l.usage_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM plugin_finance_evidence_usage WHERE(tenant_id,book_id,year_id,id)=(t,b,y,l.usage_id) AND allocation_id=aid AND document_id=did AND submission_id=l.submission_id AND release_id=l.id AND state='RELEASED') THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_CONSUMER_STATE_CONFLICT';END IF;
 END IF;
 IF TG_TABLE_NAME IN('plugin_finance_evidence_reverse_request','plugin_finance_evidence_reverse_effect') THEN
  SELECT * INTO rr FROM plugin_finance_evidence_reverse_request WHERE(tenant_id,book_id,year_id,id)=(t,b,y,CASE WHEN TG_TABLE_NAME='plugin_finance_evidence_reverse_request' THEN(n->>'id')::uuid ELSE(n->>'request_id')::uuid END);
  IF rr.document_id<>did OR rr.allocation_id<>aid OR a.status<>'CONFIRMED' OR rr.confirmation_id IS DISTINCT FROM a.current_confirmation_id OR rr.usage_id IS DISTINCT FROM a.current_usage_id OR rr.content_digest IS DISTINCT FROM plugin_finance_evidence_digest(rr.content) OR rr.content->>'amount' IS DISTINCT FROM plugin_finance_evidence_money_text(rr.amount) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_CONSUMER_STATE_CONFLICT';END IF;
  IF rr.status='APPROVED' THEN
   SELECT * INTO re FROM plugin_finance_evidence_reverse_effect WHERE(tenant_id,book_id,year_id,id)=(t,b,y,rr.reverse_effect_id);
   SELECT * INTO e FROM plugin_finance_evidence_allocation_event WHERE(tenant_id,book_id,year_id,allocation_id,allocation_version)=(t,b,y,aid,re.allocation_version);
   IF re.approved_at IS DISTINCT FROM e.created_at OR re.request_id IS DISTINCT FROM rr.id OR re.document_id<>did OR re.allocation_id<>aid OR re.confirmation_id<>rr.confirmation_id OR re.usage_id<>rr.usage_id OR re.amount<>rr.amount OR re.approved_by IS DISTINCT FROM rr.decided_by OR re.approved_at<>rr.decided_at OR e.action<>'REVERSE' OR e.reverse_effect_id IS DISTINCT FROM re.id OR e.actor IS DISTINCT FROM re.approved_by OR e.reason IS DISTINCT FROM rr.decision_reason THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_CONSUMER_STATE_CONFLICT';END IF;
  ELSIF rr.reverse_effect_id IS NOT NULL OR EXISTS(SELECT 1 FROM plugin_finance_evidence_reverse_effect WHERE(tenant_id,book_id,year_id,request_id)=(t,b,y,rr.id)) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_CONSUMER_STATE_CONFLICT';END IF;
 END IF;
 IF (SELECT coalesce(sum(amount),0) FROM plugin_finance_evidence_reverse_effect WHERE(tenant_id,book_id,year_id,allocation_id)=(t,b,y,aid))>a.amount THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_REVERSE_EXCEEDS_REMAINING';END IF;
 RETURN NULL;
END $$;
-- Uniqueness gives an unbroken, discoverable proposal chain and publication sequence.
CREATE UNIQUE INDEX finance_evidence_review_successor ON plugin_finance_evidence_review(tenant_id,book_id,year_id,document_id,previous_review_id) WHERE previous_review_id IS NOT NULL;
CREATE UNIQUE INDEX finance_evidence_review_root ON plugin_finance_evidence_review(tenant_id,book_id,year_id,document_id) WHERE previous_review_id IS NULL;
CREATE UNIQUE INDEX finance_evidence_review_publication ON plugin_finance_evidence_review(tenant_id,book_id,year_id,document_id,published_basis_version) WHERE published_basis_version IS NOT NULL;
CREATE UNIQUE INDEX finance_evidence_reverse_successor ON plugin_finance_evidence_reverse_request(tenant_id,book_id,year_id,allocation_id,previous_reverse_request_id) WHERE previous_reverse_request_id IS NOT NULL;
CREATE UNIQUE INDEX finance_evidence_reverse_root ON plugin_finance_evidence_reverse_request(tenant_id,book_id,year_id,allocation_id) WHERE previous_reverse_request_id IS NULL;
CREATE FUNCTION plugin_finance_evidence_review_content(f jsonb) RETURNS void LANGUAGE plpgsql IMMUTABLE AS $$ DECLARE v jsonb;BEGIN
 IF f->>'faceGross' !~'^(0|[1-9][0-9]{0,19})[.][0-9]{8}$' OR f->>'expenseCapacity' !~'^(0|[1-9][0-9]{0,19})[.][0-9]{8}$' OR (f->>'faceGross')::numeric<>round((f->>'faceGross')::numeric,2) OR(f->>'expenseCapacity')::numeric<>round((f->>'expenseCapacity')::numeric,2) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='INVALID_INPUT';END IF;
 v=plugin_finance_evidence_fields(f||jsonb_build_object('faceGross',((f->>'faceGross')::numeric(28,2))::text,'expenseCapacity',((f->>'expenseCapacity')::numeric(28,2))::text));
 IF v IS DISTINCT FROM f THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='INVALID_INPUT';END IF;
END $$;
CREATE FUNCTION plugin_finance_evidence_link_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE n jsonb=to_jsonb(NEW);t uuid=(n->>'tenant_id')::uuid;b uuid=(n->>'book_id')::uuid;y uuid=(n->>'year_id')::uuid;r plugin_finance_evidence_review;d plugin_finance_evidence_document;a plugin_finance_evidence_allocation;rr plugin_finance_evidence_reverse_request;original_c jsonb;
BEGIN
 IF TG_TABLE_NAME='plugin_finance_evidence_review' THEN
  SELECT * INTO d FROM plugin_finance_evidence_document WHERE(tenant_id,book_id,year_id,id)=(t,b,y,NEW.document_id);
  PERFORM plugin_finance_evidence_review_content(NEW.content);
  IF TG_OP='INSERT' THEN
   IF NEW.previous_review_id IS DISTINCT FROM (CASE WHEN d.latest_review_id=NEW.id THEN NULL ELSE d.latest_review_id END) OR NEW.previous_basis_version IS DISTINCT FROM nullif(d.basis_version,0) OR d.pending_review_id IS NOT NULL AND d.pending_review_id<>NEW.id THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_BASIS_REVIEW_PENDING';END IF;
  ELSIF NEW.status='APPROVED' THEN
   IF NEW.published_basis_version IS DISTINCT FROM d.basis_version+1 OR d.pending_review_id IS DISTINCT FROM NEW.id THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_BASIS_STALE';END IF;
   IF plugin_finance_evidence_reserved(t,b,y,NEW.document_id)<>0 THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_ACTIVE_RESERVATIONS';END IF;
   IF(NEW.content->>'expenseCapacity')::numeric<plugin_finance_evidence_net(t,b,y,NEW.document_id) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_CAPACITY_BELOW_COMMITTED';END IF;
  END IF;
 ELSIF TG_TABLE_NAME IN('plugin_finance_evidence_identity_alias','plugin_finance_evidence_hash_alias') THEN
  SELECT * INTO r FROM plugin_finance_evidence_review WHERE(tenant_id,book_id,year_id,id)=(t,b,y,(n->>'introduced_by_review_id')::uuid);
  SELECT * INTO d FROM plugin_finance_evidence_document WHERE(tenant_id,book_id,year_id,id)=(t,b,y,(n->>'document_id')::uuid);
  IF r.document_id IS DISTINCT FROM d.id OR d.legal_entity_id IS DISTINCT FROM(n->>'legal_entity_id')::uuid OR r.legal_entity_id<>d.legal_entity_id THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_IDENTITY_CONFLICT';END IF;
  IF TG_TABLE_NAME='plugin_finance_evidence_identity_alias' THEN
   IF n->>'document_type' IS DISTINCT FROM r.content->>'documentType' OR n->>'issuer_key' IS DISTINCT FROM r.content->>'issuerKey' OR n->>'external_identity_key' IS DISTINCT FROM r.content->>'externalIdentityKey' OR(r.status<>'APPROVED' AND NOT(r.previous_review_id IS NULL AND r.status='PENDING')) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_IDENTITY_CONFLICT';END IF;
  ELSE
   IF n->>'sha256' IS DISTINCT FROM d.source_snapshot->>'sha256' OR r.previous_review_id IS NOT NULL THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_IDENTITY_CONFLICT';END IF;
  END IF;
 ELSIF TG_TABLE_NAME='plugin_finance_evidence_reverse_request' AND TG_OP='INSERT' THEN
  SELECT * INTO a FROM plugin_finance_evidence_allocation WHERE(tenant_id,book_id,year_id,id)=(t,b,y,NEW.allocation_id);
  IF a.status<>'CONFIRMED' OR a.pending_reverse_id IS NOT NULL OR NEW.previous_reverse_request_id IS DISTINCT FROM a.latest_reverse_id OR NEW.expected_allocation_version<>a.version OR NEW.confirmation_id IS DISTINCT FROM a.current_confirmation_id OR NEW.usage_id IS DISTINCT FROM a.current_usage_id OR NEW.amount>a.amount-(SELECT coalesce(sum(amount),0) FROM plugin_finance_evidence_reverse_effect WHERE(tenant_id,book_id,year_id,allocation_id)=(t,b,y,a.id)) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_REVERSE_EXCEEDS_REMAINING';END IF;
  IF NEW.content->>'allocationId' IS DISTINCT FROM a.id::text OR NEW.content->>'confirmationId' IS DISTINCT FROM a.current_confirmation_id::text OR NEW.content->>'usageId' IS DISTINCT FROM a.current_usage_id::text OR NEW.content->'basisOriginal' IS DISTINCT FROM NEW.basis_original_snapshot OR NEW.content->>'reason' IS DISTINCT FROM NEW.reason OR NEW.content->'originalPreparedBy' IS DISTINCT FROM a.prepared_by OR NEW.content->'originalConfirmedBy' IS DISTINCT FROM(SELECT confirmed_by FROM plugin_finance_evidence_confirmation WHERE(tenant_id,book_id,year_id,id)=(t,b,y,a.current_confirmation_id)) OR NEW.content->>'originalConfirmedAmount' IS DISTINCT FROM plugin_finance_evidence_money_text(a.amount) OR NEW.content->>'alreadyReversedAmount' IS DISTINCT FROM plugin_finance_evidence_money_text((SELECT coalesce(sum(amount),0) FROM plugin_finance_evidence_reverse_effect WHERE(tenant_id,book_id,year_id,allocation_id)=(t,b,y,a.id))) OR(NEW.content->>'netRemainingBefore')::numeric<>(NEW.content->>'originalConfirmedAmount')::numeric-(NEW.content->>'alreadyReversedAmount')::numeric OR(NEW.content->>'netRemainingAfterProposed')::numeric<>(NEW.content->>'netRemainingBefore')::numeric-NEW.amount OR NEW.content->>'scopeOfEffect' IS DISTINCT FROM 'EVIDENCE_CAPACITY_ONLY_NOT_BANK_REFUND_OR_GL_CORRECTION' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='EVIDENCE_CONSUMER_STATE_CONFLICT';END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER finance_evidence_review_links BEFORE INSERT OR UPDATE ON plugin_finance_evidence_review FOR EACH ROW EXECUTE FUNCTION plugin_finance_evidence_link_guard();
CREATE TRIGGER finance_evidence_identity_links BEFORE INSERT ON plugin_finance_evidence_identity_alias FOR EACH ROW EXECUTE FUNCTION plugin_finance_evidence_link_guard();
CREATE TRIGGER finance_evidence_hash_links BEFORE INSERT ON plugin_finance_evidence_hash_alias FOR EACH ROW EXECUTE FUNCTION plugin_finance_evidence_link_guard();
CREATE TRIGGER finance_evidence_reverse_links BEFORE INSERT ON plugin_finance_evidence_reverse_request FOR EACH ROW EXECUTE FUNCTION plugin_finance_evidence_link_guard();
DO $$ DECLARE tbl text;BEGIN
 FOREACH tbl IN ARRAY ARRAY['document','review','identity_alias','hash_alias','chain','allocation','submission','usage','confirmation','release','reverse_request','reverse_effect','allocation_event','request','audit'] LOOP
  EXECUTE format('CREATE TRIGGER finance_evidence_%s_write BEFORE INSERT OR UPDATE OR DELETE ON plugin_finance_evidence_%s FOR EACH ROW EXECUTE FUNCTION plugin_finance_evidence_write_guard()',tbl,tbl);
  IF tbl NOT IN('chain','request','audit') THEN EXECUTE format('CREATE CONSTRAINT TRIGGER finance_evidence_%s_final AFTER INSERT OR UPDATE ON plugin_finance_evidence_%s DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION plugin_finance_evidence_final_guard()',tbl,tbl); END IF;
 END LOOP;
END $$;
CREATE FUNCTION plugin_finance_evidence_visible_change() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='INSERT' OR to_jsonb(NEW) IS DISTINCT FROM to_jsonb(OLD) THEN PERFORM plugin_finance_evidence_bump(NEW.tenant_id,NEW.book_id,NEW.year_id);END IF;RETURN NULL;
END $$;
DO $$ DECLARE tbl text;BEGIN
 FOREACH tbl IN ARRAY ARRAY['document','review','allocation','submission','usage','confirmation','release','reverse_request','reverse_effect','allocation_event'] LOOP
  EXECUTE format('CREATE TRIGGER finance_evidence_%s_generation AFTER INSERT OR UPDATE ON plugin_finance_evidence_%s FOR EACH ROW EXECUTE FUNCTION plugin_finance_evidence_visible_change()',tbl,tbl);
 END LOOP;
END $$;

CREATE UNIQUE INDEX finance_evidence_one_active_usage ON plugin_finance_evidence_usage(tenant_id,book_id,year_id,allocation_id) WHERE state IN('RESERVED','COMMITTED');
CREATE INDEX finance_evidence_allocation_content_actor ON plugin_finance_evidence_allocation_event(tenant_id,book_id,year_id,allocation_id,allocation_version DESC) WHERE action IN('CREATE','EDIT');
