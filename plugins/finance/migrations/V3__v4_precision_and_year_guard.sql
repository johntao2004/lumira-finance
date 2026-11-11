ALTER TABLE plugin_finance_line ALTER COLUMN debit TYPE numeric(28,8), ALTER COLUMN credit TYPE numeric(28,8);
ALTER TABLE plugin_finance_opening ALTER COLUMN balance TYPE numeric(28,8);
ALTER TABLE plugin_finance_journal ADD COLUMN amount_format_version text NOT NULL DEFAULT 'LEGACY_6' CHECK(amount_format_version IN('LEGACY_6','V4_8'));
ALTER TABLE plugin_finance_journal ALTER COLUMN amount_format_version SET DEFAULT 'V4_8';
ALTER TABLE plugin_finance_opening ADD COLUMN amount_format_version text NOT NULL DEFAULT 'LEGACY_6' CHECK(amount_format_version IN('LEGACY_6','V4_8'));
ALTER TABLE plugin_finance_opening ALTER COLUMN amount_format_version SET DEFAULT 'V4_8';
ALTER TABLE plugin_finance_year ADD COLUMN currency_minor_unit integer NOT NULL DEFAULT 2 CHECK(currency_minor_unit=2), ADD COLUMN precision_profile text NOT NULL DEFAULT 'V4' CHECK(precision_profile IN('LEGACY_V3','V4')), ADD COLUMN precision_review_required boolean NOT NULL DEFAULT false;
UPDATE plugin_finance_year y SET precision_profile='LEGACY_V3',precision_review_required=true WHERE EXISTS(SELECT 1 FROM plugin_finance_journal j WHERE j.tenant_id=y.tenant_id AND j.book_id=y.book_id AND j.year_id=y.id) OR EXISTS(SELECT 1 FROM plugin_finance_opening o WHERE o.tenant_id=y.tenant_id AND o.book_id=y.book_id AND o.year_id=y.id);
ALTER TABLE plugin_finance_year DROP CONSTRAINT plugin_finance_year_status_check;
ALTER TABLE plugin_finance_year ADD CONSTRAINT plugin_finance_year_status_check CHECK(status IN('OPEN','CLOSING','CLOSED_PENDING_ARCHIVE','CLOSED'));
CREATE TABLE plugin_finance_year_guard(tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,posting_seq bigint NOT NULL DEFAULT 0 CHECK(posting_seq>=0),opening_version bigint NOT NULL DEFAULT 0 CHECK(opening_version>=0),baseline_version bigint NOT NULL DEFAULT 0 CHECK(baseline_version>=0),source_close_version bigint NOT NULL DEFAULT 0 CHECK(source_close_version>=0),PRIMARY KEY(tenant_id,book_id,year_id),FOREIGN KEY(tenant_id,book_id,year_id) REFERENCES plugin_finance_year(tenant_id,book_id,id));
INSERT INTO plugin_finance_year_guard(tenant_id,book_id,year_id) SELECT tenant_id,book_id,id FROM plugin_finance_year;
ALTER TABLE plugin_finance_posting ADD COLUMN posting_seq bigint CHECK(posting_seq>0);
CREATE UNIQUE INDEX finance_posting_year_watermark ON plugin_finance_posting(tenant_id,book_id,year_id,posting_seq) WHERE posting_seq IS NOT NULL;
CREATE OR REPLACE FUNCTION plugin_finance_post_guard() RETURNS trigger LANGUAGE plpgsql AS $$ DECLARE d numeric; c numeric; n integer; BEGIN IF NEW.status='POSTED' THEN
PERFORM 1 FROM plugin_finance_year WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND id=NEW.year_id AND status='OPEN' AND NOT precision_review_required AND precision_profile='V4'; IF NOT FOUND THEN RAISE EXCEPTION 'LEGACY_PRECISION_REVIEW_REQUIRED'; END IF;
SELECT coalesce(sum(debit),0),coalesce(sum(credit),0),count(*) INTO d,c,n FROM plugin_finance_line WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id AND journal_id=NEW.id; IF n<2 OR d<>c THEN RAISE EXCEPTION 'UNBALANCED_JOURNAL'; END IF;
PERFORM 1 FROM plugin_finance_line WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id AND journal_id=NEW.id AND (debit<>trunc(debit,2) OR credit<>trunc(credit,2)); IF FOUND THEN RAISE EXCEPTION 'INVALID_CURRENCY_SCALE'; END IF;
IF NEW.approved_by IS NULL OR NEW.approved_by=NEW.created_by OR NEW.approved_version<>OLD.version THEN RAISE EXCEPTION 'APPROVAL_STALE'; END IF;
PERFORM 1 FROM plugin_finance_period WHERE tenant_id=NEW.tenant_id AND book_id=NEW.book_id AND year_id=NEW.year_id AND id=NEW.period_id AND status='OPEN'; IF NOT FOUND THEN RAISE EXCEPTION 'PERIOD_CLOSED'; END IF;
PERFORM 1 FROM plugin_finance_posting p JOIN plugin_finance_year_guard g ON g.tenant_id=p.tenant_id AND g.book_id=p.book_id AND g.year_id=p.year_id WHERE p.tenant_id=NEW.tenant_id AND p.book_id=NEW.book_id AND p.year_id=NEW.year_id AND p.journal_id=NEW.id AND p.posting_seq=g.posting_seq; IF NOT FOUND THEN RAISE EXCEPTION 'POSTING_WATERMARK_REQUIRED'; END IF;
END IF; RETURN NEW; END $$;
CREATE FUNCTION plugin_finance_journal_initial_guard() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
IF NEW.status<>'DRAFT' OR NEW.version<>1 OR NEW.approved_by IS NOT NULL OR NEW.approved_version IS NOT NULL OR NEW.number IS NOT NULL THEN RAISE EXCEPTION 'INITIAL_JOURNAL_MUST_BE_DRAFT'; END IF;
RETURN NEW; END $$;
CREATE TRIGGER finance_journal_initial_guard BEFORE INSERT ON plugin_finance_journal FOR EACH ROW EXECUTE FUNCTION plugin_finance_journal_initial_guard();
CREATE FUNCTION plugin_finance_posting_insert_guard() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
IF NEW.posting_seq IS NULL THEN RAISE EXCEPTION 'POSTING_WATERMARK_REQUIRED'; END IF;
PERFORM 1 FROM plugin_finance_year_guard g JOIN plugin_finance_year y ON y.tenant_id=g.tenant_id AND y.book_id=g.book_id AND y.id=g.year_id JOIN plugin_finance_journal j ON j.tenant_id=g.tenant_id AND j.book_id=g.book_id AND j.year_id=g.year_id WHERE g.tenant_id=NEW.tenant_id AND g.book_id=NEW.book_id AND g.year_id=NEW.year_id AND j.id=NEW.journal_id AND j.status='APPROVED' AND j.version=NEW.journal_version AND g.posting_seq=NEW.posting_seq AND NOT y.precision_review_required AND y.status='OPEN';
IF NOT FOUND THEN RAISE EXCEPTION 'POSTING_WATERMARK_OR_APPROVAL_INVALID'; END IF; RETURN NEW; END $$;
CREATE TRIGGER finance_posting_insert_guard BEFORE INSERT ON plugin_finance_posting FOR EACH ROW EXECUTE FUNCTION plugin_finance_posting_insert_guard();
