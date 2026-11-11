-- New read baseline only. Existing journals, facts, evidence and V1-V10 remain unchanged.
LOCK TABLE plugin_finance_year,plugin_finance_journal,plugin_finance_line IN SHARE ROW EXCLUSIVE MODE;
CREATE TABLE plugin_finance_journal_list_guard (
 tenant_id uuid NOT NULL,book_id uuid NOT NULL,year_id uuid NOT NULL,
 read_epoch uuid NOT NULL DEFAULT gen_random_uuid(),generation bigint NOT NULL DEFAULT 0 CHECK(generation>=0),
 PRIMARY KEY(tenant_id,book_id,year_id),
 FOREIGN KEY(tenant_id,book_id,year_id) REFERENCES plugin_finance_year(tenant_id,book_id,id));
INSERT INTO plugin_finance_journal_list_guard(tenant_id,book_id,year_id) SELECT tenant_id,book_id,id FROM plugin_finance_year;
CREATE FUNCTION plugin_finance_journal_list_baseline_guard() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'JOURNAL_LIST_BASELINE_PRESERVED';END IF;
 IF TG_OP='INSERT' THEN IF NEW.generation<>0 THEN RAISE EXCEPTION 'JOURNAL_LIST_INITIAL_GENERATION_ZERO';END IF;
 ELSIF (NEW.tenant_id,NEW.book_id,NEW.year_id,NEW.read_epoch) IS DISTINCT FROM (OLD.tenant_id,OLD.book_id,OLD.year_id,OLD.read_epoch) OR NEW.generation<>OLD.generation+1 THEN RAISE EXCEPTION 'JOURNAL_LIST_INVALID_GENERATION_CHANGE';END IF;RETURN NEW;
END $$;
CREATE TRIGGER finance_journal_list_baseline_guard BEFORE INSERT OR UPDATE OR DELETE ON plugin_finance_journal_list_guard FOR EACH ROW EXECUTE FUNCTION plugin_finance_journal_list_baseline_guard();
CREATE FUNCTION plugin_finance_journal_list_year_created() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 INSERT INTO plugin_finance_journal_list_guard(tenant_id,book_id,year_id) VALUES(NEW.tenant_id,NEW.book_id,NEW.id);RETURN NEW;
END $$;
CREATE TRIGGER finance_journal_list_year_created AFTER INSERT ON plugin_finance_year FOR EACH ROW EXECUTE FUNCTION plugin_finance_journal_list_year_created();
CREATE FUNCTION plugin_finance_journal_list_changed() RETURNS trigger LANGUAGE plpgsql AS $$ DECLARE statement text;r record;BEGIN
 IF TG_OP='INSERT' THEN statement:='SELECT DISTINCT tenant_id,book_id,year_id FROM new_rows ORDER BY tenant_id,book_id,year_id';
 ELSIF TG_OP='DELETE' THEN statement:='SELECT DISTINCT tenant_id,book_id,year_id FROM old_rows ORDER BY tenant_id,book_id,year_id';
 ELSE statement:='SELECT DISTINCT tenant_id,book_id,year_id FROM ((SELECT * FROM new_rows EXCEPT SELECT * FROM old_rows) UNION (SELECT * FROM old_rows EXCEPT SELECT * FROM new_rows)) changed ORDER BY tenant_id,book_id,year_id';END IF;
 FOR r IN EXECUTE statement LOOP
  UPDATE plugin_finance_journal_list_guard SET generation=generation+1 WHERE tenant_id=r.tenant_id AND book_id=r.book_id AND year_id=r.year_id;
  IF NOT FOUND THEN RAISE EXCEPTION 'JOURNAL_LIST_BASELINE_MISSING';END IF;
 END LOOP;RETURN NULL;
END $$;
CREATE TRIGGER finance_journal_list_header_insert AFTER INSERT ON plugin_finance_journal REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION plugin_finance_journal_list_changed();
CREATE TRIGGER finance_journal_list_header_update AFTER UPDATE ON plugin_finance_journal REFERENCING OLD TABLE AS old_rows NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION plugin_finance_journal_list_changed();
CREATE TRIGGER finance_journal_list_header_delete AFTER DELETE ON plugin_finance_journal REFERENCING OLD TABLE AS old_rows FOR EACH STATEMENT EXECUTE FUNCTION plugin_finance_journal_list_changed();
CREATE TRIGGER finance_journal_list_line_insert AFTER INSERT ON plugin_finance_line REFERENCING NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION plugin_finance_journal_list_changed();
CREATE TRIGGER finance_journal_list_line_update AFTER UPDATE ON plugin_finance_line REFERENCING OLD TABLE AS old_rows NEW TABLE AS new_rows FOR EACH STATEMENT EXECUTE FUNCTION plugin_finance_journal_list_changed();
CREATE TRIGGER finance_journal_list_line_delete AFTER DELETE ON plugin_finance_line REFERENCING OLD TABLE AS old_rows FOR EACH STATEMENT EXECUTE FUNCTION plugin_finance_journal_list_changed();
CREATE INDEX finance_journal_pages_date ON plugin_finance_journal(tenant_id,book_id,year_id,date,id);
CREATE INDEX finance_journal_pages_status_date ON plugin_finance_journal(tenant_id,book_id,year_id,status,date,id);
