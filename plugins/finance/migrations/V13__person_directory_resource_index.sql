-- Finance-owned access path only; published V1-V12 remain unchanged.
CREATE INDEX plugin_finance_person_binding_person_page
ON plugin_finance_person_binding(tenant_id,person_id,created_at,id);
