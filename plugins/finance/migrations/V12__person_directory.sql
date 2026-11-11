-- Finance-owned organization-attested directory. No business workflow person enforcement.
CREATE TABLE plugin_finance_person_realm (
 tenant_id uuid PRIMARY KEY REFERENCES plugin_finance_tenant(id), authority_id uuid NOT NULL UNIQUE,
 authority_name text NOT NULL, manifest_sha256 text NOT NULL CHECK(manifest_sha256 ~ '^[0-9a-f]{64}$'),
 manifest jsonb NOT NULL, generation bigint NOT NULL DEFAULT 0 CHECK(generation>=0), created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE plugin_finance_person (
 tenant_id uuid NOT NULL REFERENCES plugin_finance_person_realm(tenant_id), id uuid NOT NULL,
 person_reference text NOT NULL, display_name text NOT NULL, source_reference text NOT NULL, source_sha256 text NOT NULL CHECK(source_sha256 ~ '^[0-9a-f]{64}$'),
 seed boolean NOT NULL, proposal_id uuid, created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP, version bigint NOT NULL DEFAULT 1 CHECK(version>0),
 PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,person_reference), CHECK(seed=(proposal_id IS NULL))
);
CREATE TABLE plugin_finance_person_binding (
 tenant_id uuid NOT NULL, id uuid NOT NULL, account_id uuid NOT NULL, person_id uuid NOT NULL,
 status text NOT NULL CHECK(status IN('ACTIVE','REVOKED')), governance_seed boolean NOT NULL,
 proposal_id uuid, revoke_proposal_id uuid, created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP, revoked_at timestamptz,
 version bigint NOT NULL DEFAULT 1 CHECK(version>0), PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,account_id), UNIQUE(tenant_id,id,person_id),
 FOREIGN KEY(tenant_id,person_id) REFERENCES plugin_finance_person(tenant_id,id),
 CHECK(governance_seed=(proposal_id IS NULL)), CHECK((status='ACTIVE' AND revoked_at IS NULL AND revoke_proposal_id IS NULL) OR (status='REVOKED' AND revoked_at IS NOT NULL AND revoke_proposal_id IS NOT NULL AND NOT governance_seed))
);
CREATE TABLE plugin_finance_person_governance (
 tenant_id uuid NOT NULL, account_id uuid NOT NULL, binding_id uuid NOT NULL, person_id uuid NOT NULL,
 abilities text[] NOT NULL CHECK(abilities=ARRAY['READ','PROPOSE','CONFIRM']::text[]),
 PRIMARY KEY(tenant_id,account_id), FOREIGN KEY(tenant_id,binding_id,person_id) REFERENCES plugin_finance_person_binding(tenant_id,id,person_id),
 FOREIGN KEY(tenant_id,account_id) REFERENCES plugin_finance_person_binding(tenant_id,account_id)
);
CREATE TABLE plugin_finance_person_proposal (
 tenant_id uuid NOT NULL REFERENCES plugin_finance_person_realm(tenant_id), id uuid NOT NULL,
 operation text NOT NULL CHECK(operation IN('CREATE_PERSON_AND_BIND','BIND_EXISTING_PERSON','REVOKE_BINDING')),
 status text NOT NULL DEFAULT 'PENDING' CHECK(status IN('PENDING','APPROVED','REJECTED','CANCELLED')),
 account_id uuid NOT NULL, person_id uuid, display_name text, source_reference text NOT NULL, source_sha256 text NOT NULL CHECK(source_sha256 ~ '^[0-9a-f]{64}$'),
 content jsonb NOT NULL, content_text text NOT NULL, content_digest text NOT NULL CHECK(content_digest ~ '^[0-9a-f]{64}$'), reason text NOT NULL,
 prepared_by uuid NOT NULL, prepared_username text NOT NULL, prepared_person_id uuid NOT NULL, created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP, expires_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP+interval '7 days',
 version bigint NOT NULL DEFAULT 1 CHECK(version>0), decided_by uuid, decided_person_id uuid, decided_at timestamptz, decision_reason text,
 effect_person_id uuid, effect_binding_id uuid,
 PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,id,effect_person_id), UNIQUE(tenant_id,id,effect_binding_id),
 FOREIGN KEY(tenant_id,prepared_person_id) REFERENCES plugin_finance_person(tenant_id,id), FOREIGN KEY(tenant_id,person_id) REFERENCES plugin_finance_person(tenant_id,id),
 FOREIGN KEY(tenant_id,decided_person_id) REFERENCES plugin_finance_person(tenant_id,id),
 FOREIGN KEY(tenant_id,effect_person_id) REFERENCES plugin_finance_person(tenant_id,id) DEFERRABLE INITIALLY DEFERRED,
 FOREIGN KEY(tenant_id,effect_binding_id) REFERENCES plugin_finance_person_binding(tenant_id,id) DEFERRABLE INITIALLY DEFERRED,
 CHECK((operation='CREATE_PERSON_AND_BIND' AND person_id IS NULL AND display_name IS NOT NULL) OR (operation<>'CREATE_PERSON_AND_BIND' AND person_id IS NOT NULL AND display_name IS NULL)),
 CHECK((status='PENDING' AND decided_by IS NULL AND decided_person_id IS NULL AND decided_at IS NULL AND decision_reason IS NULL AND effect_person_id IS NULL AND effect_binding_id IS NULL) OR (status<>'PENDING' AND decided_by IS NOT NULL AND decided_person_id IS NOT NULL AND decided_at IS NOT NULL AND length(decision_reason)>0)),
 CHECK(status='APPROVED' OR (effect_person_id IS NULL AND effect_binding_id IS NULL)), CHECK(content_text::jsonb=content AND encode(sha256(convert_to(content_text,'UTF8')),'hex')=content_digest)
);
ALTER TABLE plugin_finance_person ADD FOREIGN KEY(tenant_id,proposal_id,id) REFERENCES plugin_finance_person_proposal(tenant_id,id,effect_person_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE plugin_finance_person_binding ADD FOREIGN KEY(tenant_id,proposal_id,id) REFERENCES plugin_finance_person_proposal(tenant_id,id,effect_binding_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE plugin_finance_person_binding ADD FOREIGN KEY(tenant_id,revoke_proposal_id) REFERENCES plugin_finance_person_proposal(tenant_id,id) DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE plugin_finance_person_audit (
 tenant_id uuid NOT NULL REFERENCES plugin_finance_person_realm(tenant_id), id uuid NOT NULL, actor_id uuid,
 action text NOT NULL, target_id uuid NOT NULL, payload jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(tenant_id,id)
);
CREATE TABLE plugin_finance_person_request (
 tenant_id uuid NOT NULL REFERENCES plugin_finance_person_realm(tenant_id), actor_id uuid NOT NULL, request_key text NOT NULL,
 request_hash text NOT NULL, route text NOT NULL, proposal_id uuid NOT NULL,
 PRIMARY KEY(tenant_id,actor_id,request_key), FOREIGN KEY(tenant_id,proposal_id) REFERENCES plugin_finance_person_proposal(tenant_id,id)
);
ALTER TABLE plugin_finance_person ADD CHECK(length(person_reference) BETWEEN 1 AND 128 AND length(display_name) BETWEEN 1 AND 120 AND length(source_reference) BETWEEN 1 AND 500);
ALTER TABLE plugin_finance_person_proposal ADD CHECK(length(source_reference) BETWEEN 1 AND 500 AND length(reason) BETWEEN 1 AND 1000 AND (display_name IS NULL OR length(display_name) BETWEEN 1 AND 120) AND (decision_reason IS NULL OR length(decision_reason) BETWEEN 1 AND 1000));
CREATE INDEX plugin_finance_person_page ON plugin_finance_person(tenant_id,created_at DESC,id DESC);
CREATE INDEX plugin_finance_person_proposal_page ON plugin_finance_person_proposal(tenant_id,created_at DESC,id DESC);
CREATE FUNCTION plugin_finance_person_immutable() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'person directory immutable history'; END $$;
CREATE TRIGGER person_no_update BEFORE UPDATE OR DELETE ON plugin_finance_person FOR EACH ROW EXECUTE FUNCTION plugin_finance_person_immutable();
CREATE TRIGGER person_governance_no_update BEFORE UPDATE OR DELETE ON plugin_finance_person_governance FOR EACH ROW EXECUTE FUNCTION plugin_finance_person_immutable();
CREATE TRIGGER person_audit_no_update BEFORE UPDATE OR DELETE ON plugin_finance_person_audit FOR EACH ROW EXECUTE FUNCTION plugin_finance_person_immutable();
CREATE TRIGGER person_request_no_update BEFORE UPDATE OR DELETE ON plugin_finance_person_request FOR EACH ROW EXECUTE FUNCTION plugin_finance_person_immutable();
CREATE FUNCTION plugin_finance_person_binding_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'binding history retained'; END IF;
 IF TG_OP='INSERT' THEN
  IF NEW.status<>'ACTIVE' OR NEW.version<>1 OR NEW.revoked_at IS NOT NULL THEN RAISE EXCEPTION 'initial binding state'; END IF;
 ELSE
  IF OLD.governance_seed OR OLD.status<>'ACTIVE' OR NEW.status<>'REVOKED' OR NEW.version<>OLD.version+1 OR
   (to_jsonb(NEW)-ARRAY['status','version','revoked_at','revoke_proposal_id']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['status','version','revoked_at','revoke_proposal_id']) THEN RAISE EXCEPTION 'binding transfer or seed revocation forbidden'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER person_binding_guard BEFORE INSERT OR UPDATE OR DELETE ON plugin_finance_person_binding FOR EACH ROW EXECUTE FUNCTION plugin_finance_person_binding_guard();
CREATE FUNCTION plugin_finance_person_proposal_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE b plugin_finance_person_binding; p plugin_finance_person_binding;
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'proposal history retained'; END IF;
 PERFORM 1 FROM plugin_finance_person_realm WHERE tenant_id=NEW.tenant_id FOR UPDATE;
 IF TG_OP='INSERT' THEN
  IF NEW.status<>'PENDING' OR NEW.version<>1 OR NEW.expires_at<>NEW.created_at+interval '7 days' OR NEW.created_at<>CURRENT_TIMESTAMP THEN RAISE EXCEPTION 'initial proposal state'; END IF;
  IF (SELECT array_agg(key ORDER BY key) FROM jsonb_object_keys(NEW.content) key) IS DISTINCT FROM ARRAY['accountId','allKnownAccountsDeclared','displayName','noAccountTransferConfirmed','operation','personId','reason','schemaVersion','sourceReference','sourceSha256']::text[] OR NEW.content->>'schemaVersion' IS DISTINCT FROM '1' THEN RAISE EXCEPTION 'closed proposal content required'; END IF;
  IF NEW.content->>'operation' IS DISTINCT FROM NEW.operation OR NEW.content->>'accountId' IS DISTINCT FROM NEW.account_id::text OR NEW.content->>'personId' IS DISTINCT FROM NEW.person_id::text OR NEW.content->>'displayName' IS DISTINCT FROM NEW.display_name OR NEW.content->>'sourceReference' IS DISTINCT FROM NEW.source_reference OR NEW.content->>'sourceSha256' IS DISTINCT FROM NEW.source_sha256 OR NEW.content->>'reason' IS DISTINCT FROM NEW.reason OR NEW.content->'noAccountTransferConfirmed' IS DISTINCT FROM 'true'::jsonb OR NEW.content->'allKnownAccountsDeclared' IS DISTINCT FROM 'true'::jsonb THEN RAISE EXCEPTION 'proposal content mismatch'; END IF;
 ELSE
  IF NEW.decided_at IS DISTINCT FROM CURRENT_TIMESTAMP OR OLD.status<>'PENDING' OR NEW.status='PENDING' OR NEW.version<>OLD.version+1 OR
   (to_jsonb(NEW)-ARRAY['status','version','decided_by','decided_person_id','decided_at','decision_reason','effect_person_id','effect_binding_id']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['status','version','decided_by','decided_person_id','decided_at','decision_reason','effect_person_id','effect_binding_id']) THEN RAISE EXCEPTION 'proposal immutable or transition invalid'; END IF;
 END IF;
 SELECT * INTO b FROM plugin_finance_person_binding WHERE tenant_id=NEW.tenant_id AND account_id=CASE WHEN TG_OP='INSERT' THEN NEW.prepared_by ELSE NEW.decided_by END AND status='ACTIVE';
 IF NOT FOUND OR NOT EXISTS(SELECT 1 FROM plugin_finance_person_governance g WHERE g.tenant_id=b.tenant_id AND g.account_id=b.account_id AND g.binding_id=b.id AND g.person_id=b.person_id AND (CASE WHEN TG_OP='INSERT' OR NEW.status='CANCELLED' THEN 'PROPOSE' ELSE 'CONFIRM' END)=ANY(g.abilities)) THEN RAISE EXCEPTION 'current governance denied'; END IF;
 IF TG_OP='INSERT' THEN
  IF b.person_id<>NEW.prepared_person_id THEN RAISE EXCEPTION 'maker identity mismatch'; END IF;
 ELSE
  IF b.person_id<>NEW.decided_person_id THEN RAISE EXCEPTION 'decision identity mismatch'; END IF;
  IF NEW.status='CANCELLED' THEN
   IF NEW.decided_by<>NEW.prepared_by THEN RAISE EXCEPTION 'cancel creator only'; END IF;
  ELSE
   IF NEW.decided_by=NEW.account_id OR NEW.decided_person_id=NEW.prepared_person_id OR NEW.decided_person_id=NEW.person_id OR EXISTS(SELECT 1 FROM plugin_finance_person_binding t WHERE t.tenant_id=NEW.tenant_id AND t.account_id=NEW.account_id AND t.person_id=NEW.decided_person_id) THEN RAISE EXCEPTION 'same person confirmation forbidden'; END IF;
  END IF;
  IF NEW.status='APPROVED' THEN
   IF NEW.expires_at<=CURRENT_TIMESTAMP THEN RAISE EXCEPTION 'proposal expired'; END IF;
   IF NOT EXISTS(SELECT 1 FROM plugin_finance_person_binding m JOIN plugin_finance_person_governance g ON (g.tenant_id,g.account_id)=(m.tenant_id,m.account_id) WHERE m.tenant_id=NEW.tenant_id AND m.account_id=NEW.prepared_by AND m.person_id=NEW.prepared_person_id AND m.status='ACTIVE' AND 'PROPOSE'=ANY(g.abilities)) THEN RAISE EXCEPTION 'maker stale'; END IF;
   IF NEW.operation='CREATE_PERSON_AND_BIND' THEN
    IF NEW.effect_person_id IS NULL OR NEW.effect_binding_id IS NULL THEN RAISE EXCEPTION 'missing effects'; END IF;
   ELSIF NEW.operation='BIND_EXISTING_PERSON' THEN
    IF NEW.effect_person_id IS DISTINCT FROM NEW.person_id OR NEW.effect_binding_id IS NULL THEN RAISE EXCEPTION 'missing effects'; END IF;
   ELSE
    SELECT * INTO p FROM plugin_finance_person_binding WHERE tenant_id=NEW.tenant_id AND account_id=NEW.account_id AND person_id=NEW.person_id AND status='ACTIVE';
    IF NOT FOUND OR p.governance_seed OR NEW.effect_binding_id IS DISTINCT FROM p.id OR NEW.effect_person_id IS DISTINCT FROM p.person_id THEN RAISE EXCEPTION 'revoke binding stale or seed protected'; END IF;
   END IF;
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER person_proposal_guard BEFORE INSERT OR UPDATE OR DELETE ON plugin_finance_person_proposal FOR EACH ROW EXECUTE FUNCTION plugin_finance_person_proposal_guard();
CREATE FUNCTION plugin_finance_person_effect() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.status='APPROVED' THEN
  IF NEW.operation='CREATE_PERSON_AND_BIND' THEN
   INSERT INTO plugin_finance_person(tenant_id,id,person_reference,display_name,source_reference,source_sha256,seed,proposal_id) VALUES(NEW.tenant_id,NEW.effect_person_id,NEW.effect_person_id::text,NEW.display_name,NEW.source_reference,NEW.source_sha256,false,NEW.id);
  END IF;
  IF NEW.operation IN('CREATE_PERSON_AND_BIND','BIND_EXISTING_PERSON') THEN
   INSERT INTO plugin_finance_person_binding(tenant_id,id,account_id,person_id,status,governance_seed,proposal_id) VALUES(NEW.tenant_id,NEW.effect_binding_id,NEW.account_id,NEW.effect_person_id,'ACTIVE',false,NEW.id);
  ELSE
   UPDATE plugin_finance_person_binding SET status='REVOKED',version=version+1,revoked_at=NEW.decided_at,revoke_proposal_id=NEW.id WHERE tenant_id=NEW.tenant_id AND id=NEW.effect_binding_id AND status='ACTIVE';
  END IF;
 END IF;
 UPDATE plugin_finance_person_realm SET generation=generation+1 WHERE tenant_id=NEW.tenant_id;
 INSERT INTO plugin_finance_person_audit(tenant_id,id,actor_id,action,target_id,payload) VALUES(NEW.tenant_id,gen_random_uuid(),CASE WHEN TG_OP='INSERT' THEN NEW.prepared_by ELSE NEW.decided_by END,'PERSON_PROPOSAL_'||NEW.status,NEW.id,jsonb_build_object('before',CASE WHEN TG_OP='UPDATE' THEN to_jsonb(OLD) ELSE NULL END,'after',to_jsonb(NEW)));
 RETURN NEW;
END $$;
CREATE TRIGGER person_effect AFTER INSERT OR UPDATE ON plugin_finance_person_proposal FOR EACH ROW EXECUTE FUNCTION plugin_finance_person_effect();
CREATE FUNCTION plugin_finance_person_committed() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r plugin_finance_person_proposal; b plugin_finance_person_binding; p plugin_finance_person; q uuid;
BEGIN
 IF TG_TABLE_NAME='plugin_finance_person_proposal' THEN
  SELECT * INTO r FROM plugin_finance_person_proposal WHERE tenant_id=NEW.tenant_id AND id=NEW.id;
  IF r.status='APPROVED' THEN
   SELECT * INTO b FROM plugin_finance_person_binding WHERE tenant_id=r.tenant_id AND id=r.effect_binding_id;
   IF NOT FOUND OR b.account_id<>r.account_id OR b.person_id<>r.effect_person_id OR (r.operation='REVOKE_BINDING' AND (b.status<>'REVOKED' OR b.revoke_proposal_id IS DISTINCT FROM r.id)) OR (r.operation<>'REVOKE_BINDING' AND b.proposal_id IS DISTINCT FROM r.id) THEN RAISE EXCEPTION 'approved decision missing effect'; END IF;
   IF r.operation='CREATE_PERSON_AND_BIND' THEN
    SELECT * INTO p FROM plugin_finance_person WHERE tenant_id=r.tenant_id AND id=r.effect_person_id;
    IF NOT FOUND OR p.seed OR p.proposal_id IS DISTINCT FROM r.id OR p.display_name IS DISTINCT FROM r.display_name OR p.source_reference IS DISTINCT FROM r.source_reference OR p.source_sha256 IS DISTINCT FROM r.source_sha256 THEN RAISE EXCEPTION 'approved decision missing person'; END IF;
   END IF;
  END IF;
 ELSIF TG_TABLE_NAME='plugin_finance_person' THEN
  IF NOT NEW.seed THEN
   SELECT * INTO r FROM plugin_finance_person_proposal WHERE tenant_id=NEW.tenant_id AND id=NEW.proposal_id;
   IF NOT FOUND OR r.status<>'APPROVED' OR r.operation<>'CREATE_PERSON_AND_BIND' OR r.effect_person_id IS DISTINCT FROM NEW.id OR r.display_name IS DISTINCT FROM NEW.display_name OR r.source_reference IS DISTINCT FROM NEW.source_reference OR r.source_sha256 IS DISTINCT FROM NEW.source_sha256 THEN RAISE EXCEPTION 'person without approved consumer'; END IF;
  END IF;
 ELSE
  SELECT * INTO b FROM plugin_finance_person_binding WHERE tenant_id=NEW.tenant_id AND id=NEW.id;
  IF NOT b.governance_seed THEN
   SELECT * INTO r FROM plugin_finance_person_proposal WHERE tenant_id=b.tenant_id AND id=b.proposal_id;
   IF NOT FOUND OR r.status<>'APPROVED' OR r.operation NOT IN('CREATE_PERSON_AND_BIND','BIND_EXISTING_PERSON') OR r.account_id<>b.account_id OR r.effect_person_id<>b.person_id OR r.effect_binding_id<>b.id THEN RAISE EXCEPTION 'binding without approved decision'; END IF;
  END IF;
  IF b.status='REVOKED' THEN
   SELECT * INTO r FROM plugin_finance_person_proposal WHERE tenant_id=b.tenant_id AND id=b.revoke_proposal_id;
   IF NOT FOUND OR r.status<>'APPROVED' OR r.operation<>'REVOKE_BINDING' OR r.effect_binding_id IS DISTINCT FROM b.id OR r.effect_person_id IS DISTINCT FROM b.person_id OR r.account_id IS DISTINCT FROM b.account_id THEN RAISE EXCEPTION 'revocation without decision'; END IF;
  END IF;
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER person_final AFTER INSERT OR UPDATE ON plugin_finance_person DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION plugin_finance_person_committed();
CREATE CONSTRAINT TRIGGER person_binding_final AFTER INSERT OR UPDATE ON plugin_finance_person_binding DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION plugin_finance_person_committed();
CREATE CONSTRAINT TRIGGER person_proposal_final AFTER INSERT OR UPDATE ON plugin_finance_person_proposal DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION plugin_finance_person_committed();
CREATE FUNCTION plugin_finance_person_directory_bootstrap(manifest_text text, manifest_sha256 text) RETURNS jsonb LANGUAGE plpgsql AS $$
DECLARE m jsonb; t uuid; a uuid; e jsonb; pid uuid; bid uuid; oldhash text; n integer; distinct_people integer; k text;
BEGIN
 IF manifest_text IS NULL OR octet_length(manifest_text)>65536 OR manifest_sha256 IS NULL OR manifest_sha256 !~ '^[0-9a-f]{64}$' OR encode(sha256(convert_to(manifest_text,'UTF8')),'hex')<>manifest_sha256 THEN RAISE EXCEPTION 'bootstrap exact manifest hash invalid'; END IF;
 m:=manifest_text::jsonb;
 IF jsonb_typeof(m)<>'object' OR (SELECT array_agg(key ORDER BY key) FROM jsonb_object_keys(m) key)<>ARRAY['attestedAt','authorityId','authorityName','schemaVersion','seeds','sourceReference','sourceSha256','tenantId','verificationMethod']::text[] OR m->>'schemaVersion' IS DISTINCT FROM '1' OR m->>'verificationMethod' IS DISTINCT FROM 'ORGANIZATION_MANUAL' THEN RAISE EXCEPTION 'bootstrap manifest fields invalid'; END IF;
 FOREACH k IN ARRAY ARRAY['schemaVersion','tenantId','authorityId','authorityName','attestedAt','sourceReference','sourceSha256','verificationMethod'] LOOP IF jsonb_typeof(m->k) IS DISTINCT FROM 'string' OR length(m->>k)=0 OR m->>k ~ '[[:cntrl:]]' OR m->>k IS DISTINCT FROM btrim(m->>k) THEN RAISE EXCEPTION 'manifest text field invalid'; END IF; END LOOP;
 t:=(m->>'tenantId')::uuid; a:=(m->>'authorityId')::uuid; IF t::text<>m->>'tenantId' OR a::text<>m->>'authorityId' OR m->>'attestedAt' !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}([.][0-9]{1,9})?Z$' THEN RAISE EXCEPTION 'canonical UUID or UTC timestamp required'; END IF;
 IF length(m->>'authorityName') NOT BETWEEN 1 AND 120 OR length(m->>'sourceReference') NOT BETWEEN 1 AND 500 OR (m->>'sourceSha256')!~'^[0-9a-f]{64}$' OR jsonb_typeof(m->'seeds')<>'array' THEN RAISE EXCEPTION 'bootstrap provenance invalid'; END IF;
 IF jsonb_typeof(m->'attestedAt') IS DISTINCT FROM 'string' OR (m->>'attestedAt')::timestamptz>CURRENT_TIMESTAMP+interval '30 seconds' THEN RAISE EXCEPTION 'future attestation'; END IF;
 PERFORM 1 FROM plugin_finance_tenant WHERE id=t FOR UPDATE; IF NOT FOUND THEN RAISE EXCEPTION 'tenant not found'; END IF;
 SELECT r.manifest_sha256 INTO oldhash FROM plugin_finance_person_realm r WHERE tenant_id=t;
 IF FOUND THEN
  IF oldhash<>manifest_sha256 THEN RAISE EXCEPTION 'bootstrap already initialized with different manifest'; END IF;
  RETURN jsonb_build_object('tenantId',t,'authorityId',a,'manifestSha256',manifest_sha256,'generation',(SELECT generation::text FROM plugin_finance_person_realm WHERE tenant_id=t),'seedAccountCount',jsonb_array_length(m->'seeds'),'alreadyApplied',true);
 END IF;
 n:=jsonb_array_length(m->'seeds');
 SELECT count(DISTINCT value->>'personReference') INTO distinct_people FROM jsonb_array_elements(m->'seeds');
 IF n NOT BETWEEN 2 AND 20 OR distinct_people<2 OR (SELECT count(DISTINCT value->>'accountId') FROM jsonb_array_elements(m->'seeds'))<>n THEN RAISE EXCEPTION 'at least two distinct organization persons and unique accounts required'; END IF;
 INSERT INTO plugin_finance_person_realm(tenant_id,authority_id,authority_name,manifest_sha256,manifest) VALUES(t,a,m->>'authorityName',manifest_sha256,m);
 FOR e IN SELECT value FROM jsonb_array_elements(m->'seeds') LOOP
  IF jsonb_typeof(e)<>'object' OR (SELECT array_agg(key ORDER BY key) FROM jsonb_object_keys(e) key)<>ARRAY['abilities','accountId','displayName','personReference']::text[] OR e->'abilities' IS DISTINCT FROM '["READ","PROPOSE","CONFIRM"]'::jsonb OR length(e->>'personReference') NOT BETWEEN 1 AND 128 OR length(e->>'displayName') NOT BETWEEN 1 AND 120 OR e->>'personReference' ~ '[[:cntrl:]]' THEN RAISE EXCEPTION 'seed fields invalid'; END IF;
  FOREACH k IN ARRAY ARRAY['personReference','displayName','accountId'] LOOP IF jsonb_typeof(e->k) IS DISTINCT FROM 'string' OR length(e->>k)=0 OR e->>k ~ '[[:cntrl:]]' OR e->>k IS DISTINCT FROM btrim(e->>k) THEN RAISE EXCEPTION 'seed text field invalid'; END IF; END LOOP; IF (e->>'accountId')::uuid::text<>e->>'accountId' THEN RAISE EXCEPTION 'canonical account UUID required'; END IF;
  SELECT id INTO pid FROM plugin_finance_person WHERE tenant_id=t AND person_reference=e->>'personReference';
  IF NOT FOUND THEN
   pid:=gen_random_uuid();
   INSERT INTO plugin_finance_person(tenant_id,id,person_reference,display_name,source_reference,source_sha256,seed) VALUES(t,pid,e->>'personReference',e->>'displayName',m->>'sourceReference',m->>'sourceSha256',true);
  ELSIF EXISTS(SELECT 1 FROM plugin_finance_person WHERE tenant_id=t AND id=pid AND display_name<>e->>'displayName') THEN RAISE EXCEPTION 'same person reference different name'; END IF;
  bid:=gen_random_uuid();
  INSERT INTO plugin_finance_person_binding(tenant_id,id,account_id,person_id,status,governance_seed) VALUES(t,bid,(e->>'accountId')::uuid,pid,'ACTIVE',true);
  INSERT INTO plugin_finance_person_governance(tenant_id,account_id,binding_id,person_id,abilities) VALUES(t,(e->>'accountId')::uuid,bid,pid,ARRAY['READ','PROPOSE','CONFIRM']);
 END LOOP;
 INSERT INTO plugin_finance_person_audit(tenant_id,id,action,target_id,payload) VALUES(t,gen_random_uuid(),'PERSON_DIRECTORY_BOOTSTRAP',a,jsonb_build_object('manifestSha256',manifest_sha256,'verificationMethod','ORGANIZATION_MANUAL','seedAccountCount',n));
 RETURN jsonb_build_object('tenantId',t,'authorityId',a,'manifestSha256',manifest_sha256,'generation','0','seedAccountCount',n,'alreadyApplied',false);
END $$;
CREATE FUNCTION plugin_finance_person_realm_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' OR (to_jsonb(NEW)-'generation') IS DISTINCT FROM (to_jsonb(OLD)-'generation') OR NEW.generation<>OLD.generation+1 THEN RAISE EXCEPTION 'realm provenance immutable'; END IF; RETURN NEW;
END $$;
CREATE TRIGGER person_realm_guard BEFORE UPDATE OR DELETE ON plugin_finance_person_realm FOR EACH ROW EXECUTE FUNCTION plugin_finance_person_realm_guard();
-- Seed provenance is structurally tied to the deployment manifest, never self-created online.
CREATE FUNCTION plugin_finance_person_seed_final() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r plugin_finance_person_realm; p plugin_finance_person; b plugin_finance_person_binding;
BEGIN
 SELECT * INTO r FROM plugin_finance_person_realm WHERE tenant_id=NEW.tenant_id;
 IF TG_TABLE_NAME='plugin_finance_person' THEN
  IF NEW.seed AND NOT EXISTS(SELECT 1 FROM jsonb_array_elements(r.manifest->'seeds') e WHERE e->>'personReference'=NEW.person_reference AND e->>'displayName'=NEW.display_name AND NEW.source_reference=r.manifest->>'sourceReference' AND NEW.source_sha256=r.manifest->>'sourceSha256') THEN RAISE EXCEPTION 'seed person missing deployment provenance'; END IF;
 ELSIF TG_TABLE_NAME='plugin_finance_person_binding' THEN
  IF NEW.governance_seed THEN
   SELECT * INTO p FROM plugin_finance_person WHERE tenant_id=NEW.tenant_id AND id=NEW.person_id;
   IF NOT p.seed OR NOT EXISTS(SELECT 1 FROM jsonb_array_elements(r.manifest->'seeds') e WHERE e->>'accountId'=NEW.account_id::text AND e->>'personReference'=p.person_reference) THEN RAISE EXCEPTION 'seed binding missing deployment provenance'; END IF;
  END IF;
 ELSE
  SELECT * INTO b FROM plugin_finance_person_binding WHERE tenant_id=NEW.tenant_id AND id=NEW.binding_id;
  IF NOT b.governance_seed OR b.account_id<>NEW.account_id OR b.person_id<>NEW.person_id THEN RAISE EXCEPTION 'governance must be deployment seed binding'; END IF;
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER person_seed_final AFTER INSERT ON plugin_finance_person DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION plugin_finance_person_seed_final();
CREATE CONSTRAINT TRIGGER person_seed_binding_final AFTER INSERT ON plugin_finance_person_binding DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION plugin_finance_person_seed_final();
CREATE CONSTRAINT TRIGGER person_seed_governance_final AFTER INSERT ON plugin_finance_person_governance DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION plugin_finance_person_seed_final();







