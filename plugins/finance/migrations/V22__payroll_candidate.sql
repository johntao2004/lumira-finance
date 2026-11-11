-- Candidate only; root assigns next unused migration identity. Never alters V1–V19.
-- Employee is a payroll data subject, not an organization authentication person.
CREATE TABLE plugin_finance_payroll_employee (
 tenant_id uuid NOT NULL,id uuid NOT NULL,employee_number text NOT NULL,
 identity_secret_ref uuid NOT NULL,identity_digest text NOT NULL CHECK(identity_digest~'^[a-f0-9]{64}$'),
 version bigint NOT NULL DEFAULT 1 CHECK(version>0),
 PRIMARY KEY(tenant_id,id),UNIQUE(tenant_id,employee_number),UNIQUE(tenant_id,identity_digest)
);
CREATE TABLE plugin_finance_payroll_employment (
 tenant_id uuid NOT NULL,id uuid NOT NULL,employee_id uuid NOT NULL,tax_entity_id uuid NOT NULL,
 legal_entity_id uuid NOT NULL,starts_on date NOT NULL,ends_on date,revision bigint NOT NULL CHECK(revision>0),
 residency text NOT NULL CHECK(residency IN('RESIDENT','NONRESIDENT','UNKNOWN')),
 evidence_id uuid NOT NULL,evidence_digest text NOT NULL CHECK(evidence_digest~'^[a-f0-9]{64}$'),
 PRIMARY KEY(tenant_id,id,revision),CHECK(ends_on IS NULL OR ends_on>=starts_on),
 FOREIGN KEY(tenant_id,employee_id) REFERENCES plugin_finance_payroll_employee(tenant_id,id),
 FOREIGN KEY(tenant_id,legal_entity_id,tax_entity_id) REFERENCES plugin_finance_tax_entity_registry(tenant_id,legal_entity_id,id)
);
CREATE TABLE plugin_finance_payroll_rule_pack (
 tenant_id uuid NOT NULL,id uuid NOT NULL,version text NOT NULL,kind text NOT NULL CHECK(kind IN('RESIDENT_WAGE','SOCIAL','HOUSING')),
 region text,effective_from date NOT NULL,effective_through date NOT NULL,body jsonb NOT NULL,
 digest text NOT NULL CHECK(digest~'^[a-f0-9]{64}$'),source_evidence uuid NOT NULL,
 approval_evidence uuid NOT NULL,approved_by_person uuid NOT NULL,approved_at timestamptz NOT NULL,
 PRIMARY KEY(tenant_id,id),CHECK(effective_through>=effective_from),CHECK(kind='RESIDENT_WAGE' OR region IS NOT NULL)
);
CREATE TABLE plugin_finance_payroll_year (
 tenant_id uuid NOT NULL,tax_entity_id uuid NOT NULL,tax_year integer NOT NULL CHECK(tax_year BETWEEN 2000 AND 9999),
 payload jsonb NOT NULL CHECK(jsonb_typeof(payload)='object' AND octet_length(payload::text)<=16777216),version bigint NOT NULL DEFAULT 1 CHECK(version>0),
 PRIMARY KEY(tenant_id,tax_entity_id,tax_year)
);
CREATE TABLE plugin_finance_payroll_receipt (
 tenant_id uuid NOT NULL,tax_entity_id uuid NOT NULL,tax_year integer NOT NULL,receipt_key text NOT NULL,
 digest text NOT NULL CHECK(digest~'^[a-f0-9]{64}$'),result jsonb NOT NULL,created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(tenant_id,tax_entity_id,tax_year,receipt_key),
 FOREIGN KEY(tenant_id,tax_entity_id,tax_year) REFERENCES plugin_finance_payroll_year
);
CREATE TABLE plugin_finance_payroll_event (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,tenant_id uuid NOT NULL,tax_entity_id uuid NOT NULL,tax_year integer NOT NULL,
 batch_id uuid NOT NULL,batch_version bigint NOT NULL,actor_account uuid NOT NULL,actor_person uuid NOT NULL,actor_binding uuid NOT NULL,
 action text NOT NULL,payload_digest text NOT NULL CHECK(payload_digest~'^[a-f0-9]{64}$'),created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(tenant_id,tax_entity_id,tax_year,batch_id,batch_version),
 FOREIGN KEY(tenant_id,tax_entity_id,tax_year) REFERENCES plugin_finance_payroll_year,
 FOREIGN KEY(tenant_id,actor_binding,actor_account,actor_person) REFERENCES plugin_finance_person_binding(tenant_id,id,account_id,person_id)
);
CREATE FUNCTION plugin_finance_payroll_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'PAYROLL_IMMUTABLE_RECORD'; END $$;
CREATE TRIGGER payroll_receipt_immutable BEFORE UPDATE OR DELETE ON plugin_finance_payroll_receipt FOR EACH ROW EXECUTE FUNCTION plugin_finance_payroll_immutable();
CREATE TRIGGER payroll_event_immutable BEFORE UPDATE OR DELETE ON plugin_finance_payroll_event FOR EACH ROW EXECUTE FUNCTION plugin_finance_payroll_immutable();
CREATE TRIGGER payroll_rule_immutable BEFORE UPDATE OR DELETE ON plugin_finance_payroll_rule_pack FOR EACH ROW EXECUTE FUNCTION plugin_finance_payroll_immutable();
CREATE TRIGGER payroll_employment_immutable BEFORE UPDATE OR DELETE ON plugin_finance_payroll_employment FOR EACH ROW EXECUTE FUNCTION plugin_finance_payroll_immutable();
CREATE TABLE plugin_finance_payroll_outbox (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),tenant_id uuid NOT NULL,tax_entity_id uuid NOT NULL,tax_year integer NOT NULL,
 batch_id uuid NOT NULL,batch_version bigint NOT NULL,action text NOT NULL,payload_digest text NOT NULL CHECK(payload_digest~'^[a-f0-9]{64}$'),
 status text NOT NULL DEFAULT 'PENDING' CHECK(status IN('PENDING','DELIVERED')),created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(tenant_id,tax_entity_id,tax_year,batch_id,batch_version),
 FOREIGN KEY(tenant_id,tax_entity_id,tax_year) REFERENCES plugin_finance_payroll_year
);
DO $$ DECLARE t text; BEGIN
 FOREACH t IN ARRAY ARRAY['plugin_finance_payroll_employee','plugin_finance_payroll_employment','plugin_finance_payroll_rule_pack','plugin_finance_payroll_year','plugin_finance_payroll_receipt','plugin_finance_payroll_event','plugin_finance_payroll_outbox'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',t);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',t);
  EXECUTE format('CREATE POLICY payroll_tenant ON %I USING (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK (tenant_id=nullif(current_setting(''app.tenant_id'',true),'''')::uuid)',t);
 END LOOP;
END $$;
