-- Root assigns a fresh migration identity; never edit V1-V19. Candidate, not applied.
CREATE TABLE plugin_finance_asset_card (
 tenant_id uuid NOT NULL, book_id uuid NOT NULL, id uuid NOT NULL,
 version bigint NOT NULL CHECK(version>0), status text NOT NULL CHECK(status IN('IN_USE','DISPOSED','REVERSED')),
 cost numeric(28,8) NOT NULL CHECK(cost>=0), accumulated numeric(28,8) NOT NULL CHECK(accumulated>=0 AND accumulated<=cost),
 snapshot jsonb NOT NULL, PRIMARY KEY(tenant_id,book_id,id),
 FOREIGN KEY(tenant_id,book_id) REFERENCES plugin_finance_book(tenant_id,id)
);
CREATE TABLE plugin_finance_asset_plan (
 tenant_id uuid NOT NULL, book_id uuid NOT NULL, year_id uuid NOT NULL, id uuid NOT NULL,
 business_key text NOT NULL CHECK(length(business_key)<=300), digest text NOT NULL CHECK(length(digest)=64), accounting_date date NOT NULL,
 kind text NOT NULL CHECK(kind IN('ACQUIRE','DEPRECIATE','ESTIMATE','DISPOSE','REVERSE','CORRECT_DEPRECIATION')),
 status text NOT NULL CHECK(status IN('SUBMITTED','APPROVED','POSTED','CANCELLED')),
 version bigint NOT NULL DEFAULT 1, payload jsonb NOT NULL, approver jsonb,
 journal_id uuid, reverses uuid, created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,book_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id) REFERENCES plugin_finance_year(tenant_id,book_id,id),
 FOREIGN KEY(tenant_id,book_id,reverses) REFERENCES plugin_finance_asset_plan(tenant_id,book_id,id),
 FOREIGN KEY(tenant_id,book_id,year_id,journal_id) REFERENCES plugin_finance_posting(tenant_id,book_id,year_id,journal_id),
 CHECK(status NOT IN('APPROVED','POSTED') OR approver IS NOT NULL)
);
CREATE UNIQUE INDEX plugin_finance_asset_business_once ON plugin_finance_asset_plan(tenant_id,book_id,business_key) WHERE status<>'CANCELLED';
CREATE UNIQUE INDEX plugin_finance_asset_one_reversal ON plugin_finance_asset_plan(tenant_id,book_id,reverses) WHERE status<>'CANCELLED';
CREATE TABLE plugin_finance_asset_reservation (
 tenant_id uuid NOT NULL, book_id uuid NOT NULL, asset_id uuid NOT NULL, plan_id uuid NOT NULL,
 PRIMARY KEY(tenant_id,book_id,asset_id),
 FOREIGN KEY(tenant_id,book_id,plan_id) REFERENCES plugin_finance_asset_plan(tenant_id,book_id,id)
);
CREATE TABLE plugin_finance_asset_event (
 tenant_id uuid NOT NULL, book_id uuid NOT NULL, asset_id uuid NOT NULL, plan_id uuid NOT NULL,
 asset_version bigint NOT NULL, kind text NOT NULL, accounting_period text, snapshot jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(tenant_id,book_id,asset_id,asset_version),
 FOREIGN KEY(tenant_id,book_id,asset_id) REFERENCES plugin_finance_asset_card(tenant_id,book_id,id),
 FOREIGN KEY(tenant_id,book_id,plan_id) REFERENCES plugin_finance_asset_plan(tenant_id,book_id,id)
);
-- Reversal does not erase the original period slot. Correction must be an explicit new protocol.
CREATE UNIQUE INDEX plugin_finance_asset_period_once ON plugin_finance_asset_event(tenant_id,book_id,asset_id,accounting_period) WHERE kind='DEPRECIATE';
CREATE TABLE plugin_finance_asset_request (
 tenant_id uuid NOT NULL, client_id text NOT NULL, operation text NOT NULL, resource text NOT NULL,
 request_key text NOT NULL, digest text NOT NULL CHECK(length(digest)=64), result jsonb NOT NULL,
 PRIMARY KEY(tenant_id,client_id,operation,resource,request_key)
);
CREATE TABLE plugin_finance_asset_audit (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, book_id uuid NOT NULL, plan_id uuid NOT NULL,
 action text NOT NULL, actor jsonb NOT NULL, digest text NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(tenant_id,book_id,plan_id) REFERENCES plugin_finance_asset_plan(tenant_id,book_id,id)
);
CREATE FUNCTION plugin_finance_asset_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'ASSET_IMMUTABLE'; END $$;
CREATE TRIGGER plugin_finance_asset_event_immutable BEFORE UPDATE OR DELETE ON plugin_finance_asset_event FOR EACH ROW EXECUTE FUNCTION plugin_finance_asset_immutable();
CREATE TRIGGER plugin_finance_asset_audit_immutable BEFORE UPDATE OR DELETE ON plugin_finance_asset_audit FOR EACH ROW EXECUTE FUNCTION plugin_finance_asset_immutable();
CREATE TRIGGER plugin_finance_asset_request_immutable BEFORE UPDATE OR DELETE ON plugin_finance_asset_request FOR EACH ROW EXECUTE FUNCTION plugin_finance_asset_immutable();
CREATE FUNCTION plugin_finance_asset_plan_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' OR OLD.status IN('POSTED','CANCELLED') THEN RAISE EXCEPTION 'ASSET_PLAN_IMMUTABLE'; END IF;
 IF NEW.payload<>OLD.payload OR NEW.digest<>OLD.digest OR NEW.business_key<>OLD.business_key OR NEW.kind<>OLD.kind
    OR NEW.tenant_id<>OLD.tenant_id OR NEW.book_id<>OLD.book_id OR NEW.year_id<>OLD.year_id OR NEW.id<>OLD.id
    OR NEW.reverses IS DISTINCT FROM OLD.reverses OR NEW.accounting_date<>OLD.accounting_date OR NEW.version<>OLD.version+1 THEN RAISE EXCEPTION 'ASSET_PLAN_CONTENT_IMMUTABLE'; END IF;
 IF NOT ((OLD.status='SUBMITTED' AND NEW.status IN('APPROVED','CANCELLED')) OR (OLD.status='APPROVED' AND NEW.status IN('POSTED','CANCELLED'))) THEN RAISE EXCEPTION 'ASSET_PLAN_TRANSITION'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER plugin_finance_asset_plan_guard BEFORE UPDATE OR DELETE ON plugin_finance_asset_plan FOR EACH ROW EXECUTE FUNCTION plugin_finance_asset_plan_guard();
