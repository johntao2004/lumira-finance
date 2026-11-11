CREATE TABLE plugin_finance_source_event (
 tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,id uuid NOT NULL,
 source_system text NOT NULL CHECK(length(source_system) BETWEEN 1 AND 80),source_event_key text NOT NULL CHECK(length(source_event_key) BETWEEN 1 AND 160),
 source_document_id text NOT NULL,source_document_version integer NOT NULL CHECK(source_document_version>0),event_type text NOT NULL,accounting_date date NOT NULL,
 payload_digest text NOT NULL CHECK(payload_digest~'^[a-f0-9]{64}$'),payload jsonb NOT NULL,created_by uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,id),UNIQUE(tenant_id,book_id,year_id,id),UNIQUE(tenant_id,source_system,source_event_key),UNIQUE(tenant_id,id,source_system,source_event_key),
 FOREIGN KEY(tenant_id,book_id,year_id) REFERENCES plugin_finance_year(tenant_id,book_id,id));
ALTER TABLE plugin_finance_journal ADD COLUMN source_event_id uuid;
ALTER TABLE plugin_finance_journal ADD UNIQUE(tenant_id,book_id,year_id,id,source_event_id);
ALTER TABLE plugin_finance_journal ADD FOREIGN KEY(tenant_id,book_id,year_id,source_event_id) REFERENCES plugin_finance_source_event(tenant_id,book_id,year_id,id);
CREATE TABLE plugin_finance_business_effect (
 tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,id uuid NOT NULL,source_event_id uuid NOT NULL,source_system text NOT NULL,source_event_key text NOT NULL,
 stable_consumer text NOT NULL CHECK(stable_consumer='finance-journal-draft-v1'),effect_type text NOT NULL CHECK(effect_type='CREATE_JOURNAL_DRAFT'),effect_key text NOT NULL CHECK(effect_key~'^[a-f0-9]{64}$'),payload_digest text NOT NULL CHECK(payload_digest~'^[a-f0-9]{64}$'),journal_id uuid NOT NULL,mapping_payload jsonb NOT NULL,mapping_rule_version text NOT NULL,handler_version text NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,id),UNIQUE(tenant_id,effect_key),UNIQUE(tenant_id,stable_consumer,source_system,source_event_key,effect_type),UNIQUE(tenant_id,id,stable_consumer,effect_key,payload_digest),
 FOREIGN KEY(tenant_id,book_id,year_id,source_event_id) REFERENCES plugin_finance_source_event(tenant_id,book_id,year_id,id),
 FOREIGN KEY(tenant_id,source_event_id,source_system,source_event_key) REFERENCES plugin_finance_source_event(tenant_id,id,source_system,source_event_key),
 FOREIGN KEY(tenant_id,book_id,year_id,journal_id,source_event_id) REFERENCES plugin_finance_journal(tenant_id,book_id,year_id,id,source_event_id));
CREATE TABLE plugin_finance_consumer_receipt (
 tenant_id uuid NOT NULL,effect_id uuid NOT NULL,stable_consumer text NOT NULL,effect_key text NOT NULL,payload_digest text NOT NULL,handler_version text NOT NULL,consumed_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,stable_consumer,effect_key),FOREIGN KEY(tenant_id,effect_id,stable_consumer,effect_key,payload_digest) REFERENCES plugin_finance_business_effect(tenant_id,id,stable_consumer,effect_key,payload_digest));
CREATE TABLE plugin_finance_outbox (
 tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,id uuid NOT NULL,event_type text NOT NULL CHECK(event_type IN('SOURCE_JOURNAL_DRAFT_CREATED','JOURNAL_POSTED','JOURNAL_CHANGED')),aggregate_id uuid NOT NULL,aggregate_version bigint NOT NULL,
 payload jsonb NOT NULL,payload_digest text NOT NULL CHECK(payload_digest~'^[a-f0-9]{64}$'),created_by uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),
 state text NOT NULL DEFAULT 'PENDING' CHECK(state IN('PENDING','PROCESSING','DONE','FAILED')),attempts integer NOT NULL DEFAULT 0 CHECK(attempts>=0),next_attempt_at timestamptz NOT NULL DEFAULT now(),lease_token uuid,lease_until timestamptz,last_error_code text,processed_at timestamptz,
 PRIMARY KEY(id),UNIQUE(tenant_id,book_id,year_id,id),UNIQUE(tenant_id,book_id,year_id,event_type,aggregate_id,aggregate_version),
 FOREIGN KEY(tenant_id,book_id,year_id,aggregate_id) REFERENCES plugin_finance_journal(tenant_id,book_id,year_id,id));
CREATE INDEX finance_outbox_due ON plugin_finance_outbox(state,next_attempt_at,lease_until,created_at,id) WHERE state IN('PENDING','PROCESSING');
CREATE INDEX finance_source_scope_cursor ON plugin_finance_source_event(tenant_id,book_id,year_id,created_at DESC,id DESC);
CREATE INDEX finance_outbox_scope_cursor ON plugin_finance_outbox(tenant_id,book_id,year_id,created_at DESC,id DESC);
CREATE TABLE plugin_finance_projection_receipt (
 tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,event_id uuid NOT NULL,stable_consumer text NOT NULL CHECK(stable_consumer='finance-journal-view-v1'),projection_version integer NOT NULL CHECK(projection_version=1),consumed_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,stable_consumer,projection_version,event_id),FOREIGN KEY(tenant_id,book_id,year_id,event_id) REFERENCES plugin_finance_outbox(tenant_id,book_id,year_id,id));
CREATE TABLE plugin_finance_journal_projection (
 tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,journal_id uuid NOT NULL,status text NOT NULL,date date NOT NULL,summary text NOT NULL,number bigint,source_version bigint NOT NULL,posting_seq bigint,payload_digest text NOT NULL,updated_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,book_id,year_id,journal_id),FOREIGN KEY(tenant_id,book_id,year_id,journal_id) REFERENCES plugin_finance_journal(tenant_id,book_id,year_id,id));
CREATE INDEX finance_projection_scope_cursor ON plugin_finance_journal_projection(tenant_id,book_id,year_id,updated_at DESC,journal_id DESC);
CREATE TRIGGER finance_source_immutable BEFORE UPDATE OR DELETE ON plugin_finance_source_event FOR EACH ROW EXECUTE FUNCTION plugin_finance_immutable();
CREATE TRIGGER finance_effect_immutable BEFORE UPDATE OR DELETE ON plugin_finance_business_effect FOR EACH ROW EXECUTE FUNCTION plugin_finance_immutable();
CREATE TRIGGER finance_consumer_receipt_immutable BEFORE UPDATE OR DELETE ON plugin_finance_consumer_receipt FOR EACH ROW EXECUTE FUNCTION plugin_finance_immutable();
CREATE FUNCTION plugin_finance_outbox_envelope_guard() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'OUTBOX_ENVELOPE_IMMUTABLE'; END IF;
 IF (NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.id,NEW.event_type,NEW.aggregate_id,NEW.aggregate_version,NEW.payload_digest,NEW.created_by,NEW.created_at) IS DISTINCT FROM (OLD.tenant_id,OLD.book_id,OLD.year_id,OLD.id,OLD.event_type,OLD.aggregate_id,OLD.aggregate_version,OLD.payload_digest,OLD.created_by,OLD.created_at) OR NEW.payload IS DISTINCT FROM OLD.payload THEN RAISE EXCEPTION 'OUTBOX_ENVELOPE_IMMUTABLE'; END IF;RETURN NEW;
END $$;
CREATE TRIGGER finance_outbox_envelope_immutable BEFORE UPDATE OR DELETE ON plugin_finance_outbox FOR EACH ROW EXECUTE FUNCTION plugin_finance_outbox_envelope_guard();
