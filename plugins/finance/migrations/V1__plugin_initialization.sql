CREATE TABLE plugin_finance_tenant (
    id uuid PRIMARY KEY,
    name text NOT NULL,
    owner_id uuid NOT NULL
);
CREATE TABLE plugin_finance_entity (
    tenant_id uuid NOT NULL REFERENCES plugin_finance_tenant(id),
    id uuid NOT NULL,
    name text NOT NULL,
    registration_id text NOT NULL,
    PRIMARY KEY(tenant_id,id)
);
CREATE TABLE plugin_finance_book (
    tenant_id uuid NOT NULL,
    id uuid PRIMARY KEY,
    legal_entity_id uuid NOT NULL,
    name text NOT NULL,
    standard text NOT NULL CHECK(standard IN('CAS','SMALL')),
    base_currency text NOT NULL CHECK(base_currency='CNY'),
    version bigint NOT NULL DEFAULT 1,
    UNIQUE(tenant_id,id),
    FOREIGN KEY(tenant_id,legal_entity_id) REFERENCES plugin_finance_entity(tenant_id,id)
);
CREATE TABLE plugin_finance_member (
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    user_id uuid NOT NULL,
    username text NOT NULL,
    role text NOT NULL CHECK(role IN('OWNER','MAKER','REVIEWER','POSTER','READER')),
    enabled boolean NOT NULL DEFAULT true,
    year_scope text NOT NULL DEFAULT 'ALL' CHECK(year_scope IN('ALL','SELECTED')),
    PRIMARY KEY(tenant_id,book_id,user_id),
    FOREIGN KEY(tenant_id,book_id) REFERENCES plugin_finance_book(tenant_id,id)
);
CREATE TABLE plugin_finance_year (
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    id uuid NOT NULL,
    year integer NOT NULL CHECK(year BETWEEN 1900 AND 2200),
    status text NOT NULL DEFAULT 'OPEN' CHECK(status IN('OPEN','CLOSED')),
    version bigint NOT NULL DEFAULT 1,
    PRIMARY KEY(tenant_id,book_id,id),
    UNIQUE(book_id,year),
    FOREIGN KEY(tenant_id,book_id) REFERENCES plugin_finance_book(tenant_id,id)
);
CREATE TABLE plugin_finance_member_year (
 tenant_id uuid NOT NULL,book_id uuid NOT NULL,user_id uuid NOT NULL,year_id uuid NOT NULL,
 PRIMARY KEY(tenant_id,book_id,user_id,year_id),
 FOREIGN KEY(tenant_id,book_id,user_id) REFERENCES plugin_finance_member(tenant_id,book_id,user_id),
 FOREIGN KEY(tenant_id,book_id,year_id) REFERENCES plugin_finance_year(tenant_id,book_id,id)
);
CREATE TABLE plugin_finance_period (
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    year_id uuid NOT NULL,
    id uuid NOT NULL,
    month integer NOT NULL CHECK(month BETWEEN 1 AND 12),
    status text NOT NULL DEFAULT 'OPEN' CHECK(status IN('OPEN','CLOSED')),
    version bigint NOT NULL DEFAULT 1,
    PRIMARY KEY(tenant_id,book_id,year_id,id),
    UNIQUE(book_id,year_id,month),
    FOREIGN KEY(tenant_id,book_id,year_id) REFERENCES plugin_finance_year(tenant_id,book_id,id)
);
CREATE TABLE plugin_finance_account (
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    year_id uuid NOT NULL,
    id uuid NOT NULL,
    code text NOT NULL,
    name text NOT NULL,
    normal_side text NOT NULL CHECK(normal_side IN('DEBIT','CREDIT')),
    category text NOT NULL CHECK(category IN('ASSET','LIABILITY','EQUITY','REVENUE','EXPENSE')),
    cash_flow_category text CHECK(cash_flow_category IN('OPERATING','INVESTING','FINANCING')),
    enabled boolean NOT NULL DEFAULT true,
    purpose text NOT NULL DEFAULT 'NONE' CHECK(purpose IN('NONE','CURRENT_YEAR_PROFIT','RETAINED_EARNINGS')),
    PRIMARY KEY(tenant_id,book_id,year_id,id),
    UNIQUE(book_id,year_id,code),
    FOREIGN KEY(tenant_id,book_id,year_id) REFERENCES plugin_finance_year(tenant_id,book_id,id)
);
CREATE TABLE plugin_finance_journal (
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    year_id uuid NOT NULL,
    id uuid NOT NULL,
    period_id uuid NOT NULL,
    date date NOT NULL,
    summary text NOT NULL,
    type text NOT NULL DEFAULT 'NORMAL' CHECK(type IN('NORMAL','REVERSAL','PROFIT_CLOSE','YEAR_END')),
    status text NOT NULL DEFAULT 'DRAFT' CHECK(status IN('DRAFT','SUBMITTED','APPROVED','POSTED','CANCELLED')),
    version bigint NOT NULL DEFAULT 1,
    created_by uuid NOT NULL,
    approved_by uuid,
    approved_version bigint,
    number bigint,
    reversal_of uuid,
    closing_basis text,
    PRIMARY KEY(tenant_id,book_id,year_id,id),
    UNIQUE(book_id,year_id,number),
    UNIQUE(book_id,year_id,reversal_of),
    CHECK(approved_by IS NULL OR approved_by<>created_by),
    FOREIGN KEY(tenant_id,book_id,year_id,period_id) REFERENCES plugin_finance_period(tenant_id,book_id,year_id,id),
    FOREIGN KEY(tenant_id,book_id,year_id,reversal_of) REFERENCES plugin_finance_journal(tenant_id,book_id,year_id,id)
);
CREATE TABLE plugin_finance_line (
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    year_id uuid NOT NULL,
    journal_id uuid NOT NULL,
    line_no integer NOT NULL,
    account_id uuid NOT NULL,
    debit numeric(24,6) NOT NULL CHECK(debit>=0),
    credit numeric(24,6) NOT NULL CHECK(credit>=0),
    memo text NOT NULL DEFAULT '',
    PRIMARY KEY(tenant_id,book_id,year_id,journal_id,line_no),
    CHECK((debit>0 AND credit=0) OR (credit>0 AND debit=0)),
    FOREIGN KEY(tenant_id,book_id,year_id,journal_id) REFERENCES plugin_finance_journal(tenant_id,book_id,year_id,id),
    FOREIGN KEY(tenant_id,book_id,year_id,account_id) REFERENCES plugin_finance_account(tenant_id,book_id,year_id,id)
);
CREATE TABLE plugin_finance_posting (
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    year_id uuid NOT NULL,
    journal_id uuid NOT NULL,
    journal_version bigint NOT NULL,
    digest text NOT NULL,
    posted_by uuid NOT NULL,
    posted_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(tenant_id,book_id,year_id,journal_id),
    FOREIGN KEY(tenant_id,book_id,year_id,journal_id) REFERENCES plugin_finance_journal(tenant_id,book_id,year_id,id)
);
CREATE TABLE plugin_finance_document (
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    year_id uuid NOT NULL,
    journal_id uuid NOT NULL,
    id uuid NOT NULL,
    filename text NOT NULL,
    sha256 text NOT NULL CHECK(sha256 ~ '^[a-f0-9]{64}$'),
    source text NOT NULL,
    original_location text NOT NULL,
    PRIMARY KEY(tenant_id,book_id,year_id,id),
    FOREIGN KEY(tenant_id,book_id,year_id,journal_id) REFERENCES plugin_finance_journal(tenant_id,book_id,year_id,id)
);
CREATE TABLE plugin_finance_close_plan (
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    id uuid NOT NULL,
    source_year_id uuid NOT NULL,
    target_year_id uuid NOT NULL,
    status text NOT NULL DEFAULT 'PREVIEW',
    version bigint NOT NULL DEFAULT 1,
    digest text NOT NULL,
    created_by uuid NOT NULL,
    approved_by uuid,
    payload text NOT NULL,
    PRIMARY KEY(tenant_id,book_id,id),
    CHECK(source_year_id<>target_year_id),
    CHECK(approved_by IS NULL OR approved_by<>created_by),
    FOREIGN KEY(tenant_id,book_id,source_year_id) REFERENCES plugin_finance_year(tenant_id,book_id,id),
    FOREIGN KEY(tenant_id,book_id,target_year_id) REFERENCES plugin_finance_year(tenant_id,book_id,id)
);
CREATE TABLE plugin_finance_opening (
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    year_id uuid NOT NULL,
    account_id uuid NOT NULL,
    plan_id uuid NOT NULL,
    balance numeric(24,6) NOT NULL,
    PRIMARY KEY(tenant_id,book_id,year_id,account_id),
    FOREIGN KEY(tenant_id,book_id,year_id,account_id) REFERENCES plugin_finance_account(tenant_id,book_id,year_id,id),
    FOREIGN KEY(tenant_id,book_id,plan_id) REFERENCES plugin_finance_close_plan(tenant_id,book_id,id)
);
CREATE TABLE plugin_finance_idempotency (
    actor_id uuid NOT NULL,
    key text NOT NULL,
    route text NOT NULL,
    payload_hash text NOT NULL,
    response text NOT NULL,
    PRIMARY KEY(actor_id,key)
);
CREATE TABLE plugin_finance_audit (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    year_id uuid,
    actor_id uuid NOT NULL,
    action text NOT NULL,
    target_id uuid NOT NULL,
    payload_hash text NOT NULL,
    payload jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY(tenant_id,book_id) REFERENCES plugin_finance_book(tenant_id,id)
);
CREATE TABLE plugin_finance_tax_profile (
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    vat_identity text NOT NULL,
    income_tax_method text NOT NULL,
    region text NOT NULL,
    notes text NOT NULL,
    status text NOT NULL DEFAULT 'UNREVIEWED' CHECK(status='UNREVIEWED'),
    PRIMARY KEY(tenant_id,book_id),
    FOREIGN KEY(tenant_id,book_id) REFERENCES plugin_finance_book(tenant_id,id)
);
CREATE FUNCTION plugin_finance_immutable() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'FINANCE_IMMUTABLE'; END $$;
CREATE TRIGGER finance_posting_immutable BEFORE UPDATE OR DELETE ON plugin_finance_posting FOR EACH ROW EXECUTE FUNCTION plugin_finance_immutable();
CREATE TRIGGER finance_opening_immutable BEFORE UPDATE OR DELETE ON plugin_finance_opening FOR EACH ROW EXECUTE FUNCTION plugin_finance_immutable();
CREATE TRIGGER finance_audit_immutable BEFORE UPDATE OR DELETE ON plugin_finance_audit FOR EACH ROW EXECUTE FUNCTION plugin_finance_immutable();
CREATE FUNCTION plugin_finance_journal_guard() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF OLD.status='POSTED' THEN RAISE EXCEPTION 'FINANCE_IMMUTABLE'; END IF; RETURN NEW; END $$;
CREATE TRIGGER finance_journal_immutable BEFORE UPDATE OR DELETE ON plugin_finance_journal FOR EACH ROW EXECUTE FUNCTION plugin_finance_journal_guard();
CREATE FUNCTION plugin_finance_child_guard() RETURNS trigger LANGUAGE plpgsql AS $$ DECLARE r record; BEGIN IF TG_OP='UPDATE' THEN PERFORM 1 FROM plugin_finance_journal WHERE tenant_id=OLD.tenant_id AND book_id=OLD.book_id AND year_id=OLD.year_id AND id=OLD.journal_id AND status='POSTED'; IF FOUND THEN RAISE EXCEPTION 'FINANCE_IMMUTABLE'; END IF; END IF; r:=CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END; PERFORM 1 FROM plugin_finance_journal WHERE tenant_id=r.tenant_id AND book_id=r.book_id AND year_id=r.year_id AND id=r.journal_id AND status='POSTED'; IF FOUND THEN RAISE EXCEPTION 'FINANCE_IMMUTABLE'; END IF; IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF; END $$;
CREATE TRIGGER finance_line_immutable BEFORE INSERT OR UPDATE OR DELETE ON plugin_finance_line FOR EACH ROW EXECUTE FUNCTION plugin_finance_child_guard();
CREATE TRIGGER finance_document_immutable BEFORE INSERT OR UPDATE OR DELETE ON plugin_finance_document FOR EACH ROW EXECUTE FUNCTION plugin_finance_child_guard();

CREATE UNIQUE INDEX finance_year_executed_once ON plugin_finance_close_plan(book_id,source_year_id,target_year_id) WHERE status='EXECUTED';


CREATE FUNCTION plugin_finance_post_guard() RETURNS trigger LANGUAGE plpgsql AS $$ DECLARE d numeric; c numeric; n integer; BEGIN IF NEW.status='POSTED' THEN SELECT coalesce(sum(debit),0),coalesce(sum(credit),0),count(*) INTO d,c,n FROM plugin_finance_line WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id AND journal_id=NEW.id; IF n<2 OR d<>c THEN RAISE EXCEPTION 'UNBALANCED_JOURNAL'; END IF; IF NEW.approved_by IS NULL OR NEW.approved_by=NEW.created_by OR NEW.approved_version<>OLD.version THEN RAISE EXCEPTION 'APPROVAL_STALE'; END IF; PERFORM 1 FROM plugin_finance_period WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id AND id=NEW.period_id AND status='OPEN'; IF NOT FOUND THEN RAISE EXCEPTION 'PERIOD_CLOSED'; END IF; END IF; RETURN NEW; END $$;
CREATE TRIGGER finance_post_guard BEFORE UPDATE ON plugin_finance_journal FOR EACH ROW EXECUTE FUNCTION plugin_finance_post_guard();

CREATE UNIQUE INDEX finance_account_purpose ON plugin_finance_account(tenant_id,book_id,year_id,purpose) WHERE purpose<>'NONE';

CREATE TABLE plugin_finance_original (
    tenant_id uuid NOT NULL,
    book_id uuid NOT NULL,
    year_id uuid NOT NULL,
    id uuid NOT NULL,
    filename text NOT NULL,
    media_type text NOT NULL,
    content bytea NOT NULL,
    size_bytes integer NOT NULL CHECK(size_bytes BETWEEN 1 AND 5242880),
    sha256 text NOT NULL CHECK(sha256 ~ '^[a-f0-9]{64}$'),
    created_by uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 1 CHECK(version=1),
    PRIMARY KEY(tenant_id,book_id,year_id,id),
    CHECK(octet_length(content)=size_bytes),
    FOREIGN KEY(tenant_id,book_id,year_id) REFERENCES plugin_finance_year(tenant_id,book_id,id)
);
CREATE TRIGGER finance_original_immutable BEFORE UPDATE OR DELETE ON plugin_finance_original FOR EACH ROW EXECUTE FUNCTION plugin_finance_immutable();
-- backend integration: create table before document, then add nullable original_id uuid to document
-- FOREIGN KEY(tenant_id,book_id,year_id,original_id) REFERENCES plugin_finance_original(tenant_id,book_id,year_id,id)

ALTER TABLE plugin_finance_document ADD COLUMN original_id uuid NOT NULL;
ALTER TABLE plugin_finance_document ADD FOREIGN KEY(tenant_id,book_id,year_id,original_id) REFERENCES plugin_finance_original(tenant_id,book_id,year_id,id);
