CREATE INDEX finance_audit_scope_cursor_idx
ON plugin_finance_audit(tenant_id,book_id,year_id,created_at DESC,id DESC);
