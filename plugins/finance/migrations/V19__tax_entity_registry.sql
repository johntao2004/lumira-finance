-- Organization-reviewed taxpayer registration only. No government verification, tax or GL effect.
-- Published V1-V18 remain unchanged. All six tables are finance owned.
CREATE TABLE plugin_finance_tax_entity_home_guard (
 tenant_id uuid NOT NULL,legal_entity_id uuid NOT NULL,book_id uuid NOT NULL,
 epoch uuid NOT NULL DEFAULT gen_random_uuid(),generation bigint NOT NULL DEFAULT 0 CHECK(generation>=0),
 PRIMARY KEY(tenant_id,legal_entity_id,book_id),UNIQUE(tenant_id,book_id),
 FOREIGN KEY(tenant_id,legal_entity_id) REFERENCES plugin_finance_entity(tenant_id,id),
 FOREIGN KEY(tenant_id,book_id) REFERENCES plugin_finance_book(tenant_id,id)
);
INSERT INTO plugin_finance_tax_entity_home_guard(tenant_id,legal_entity_id,book_id) SELECT tenant_id,legal_entity_id,id FROM plugin_finance_book;
CREATE FUNCTION plugin_finance_tax_entity_new_book() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN INSERT INTO plugin_finance_tax_entity_home_guard(tenant_id,legal_entity_id,book_id) VALUES(NEW.tenant_id,NEW.legal_entity_id,NEW.id);RETURN NEW;END $$;
CREATE TRIGGER finance_tax_entity_new_book AFTER INSERT ON plugin_finance_book FOR EACH ROW EXECUTE FUNCTION plugin_finance_tax_entity_new_book();

CREATE TABLE plugin_finance_tax_entity_registry (
 tenant_id uuid NOT NULL,legal_entity_id uuid NOT NULL,id uuid NOT NULL,home_book_id uuid NOT NULL,
 country text NOT NULL CHECK(country='CN'),registration_no text NOT NULL CHECK(registration_no~'^[A-Z0-9][A-Z0-9._/-]{0,63}$'),
 operation_id uuid NOT NULL,initial_payload jsonb NOT NULL,initial_payload_hash text NOT NULL CHECK(initial_payload_hash~'^[a-f0-9]{64}$'),
 created_by jsonb NOT NULL,created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),latest_revision_id uuid,pending_revision_id uuid,approved_revision_id uuid,
 creator_account uuid GENERATED ALWAYS AS((created_by->>'accountId')::uuid) STORED,
 creator_person uuid GENERATED ALWAYS AS((created_by->>'personId')::uuid) STORED,
 creator_binding uuid GENERATED ALWAYS AS((created_by->>'bindingId')::uuid) STORED,
 PRIMARY KEY(tenant_id,legal_entity_id,id),UNIQUE(tenant_id,registration_no),UNIQUE(tenant_id,operation_id),
 UNIQUE(tenant_id,legal_entity_id,id,home_book_id),
 CHECK(creator_account IS NOT NULL AND creator_person IS NOT NULL AND creator_binding IS NOT NULL),
 FOREIGN KEY(tenant_id,legal_entity_id,home_book_id) REFERENCES plugin_finance_tax_entity_home_guard(tenant_id,legal_entity_id,book_id),
 FOREIGN KEY(tenant_id,creator_binding,creator_account,creator_person) REFERENCES plugin_finance_person_binding(tenant_id,id,account_id,person_id)
);
CREATE TABLE plugin_finance_tax_entity_revision (
 tenant_id uuid NOT NULL,legal_entity_id uuid NOT NULL,tax_entity_id uuid NOT NULL,home_book_id uuid NOT NULL,id uuid NOT NULL,
 revision_no bigint NOT NULL CHECK(revision_no>0),version bigint NOT NULL DEFAULT 1 CHECK(version IN(1,2)),
 status text NOT NULL DEFAULT 'PENDING' CHECK(status IN('PENDING','APPROVED','REJECTED','CANCELLED')),
 desired_status text NOT NULL CHECK(desired_status IN('ACTIVE','INACTIVE')),
 previous_revision_id uuid,base_approved_revision_id uuid,base_approved_digest text,
 content jsonb NOT NULL,content_digest text NOT NULL CHECK(content_digest~'^[a-f0-9]{64}$'),
 reason text NOT NULL CHECK(length(reason) BETWEEN 1 AND 1000),maker jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,decision_receipt_id uuid,
 maker_account uuid GENERATED ALWAYS AS((maker->>'accountId')::uuid) STORED,
 maker_person uuid GENERATED ALWAYS AS((maker->>'personId')::uuid) STORED,
 maker_binding uuid GENERATED ALWAYS AS((maker->>'bindingId')::uuid) STORED,
 original_year uuid GENERATED ALWAYS AS((content->'registrationEvidence'->>'yearId')::uuid) STORED,
 original_id uuid GENERATED ALWAYS AS((content->'registrationEvidence'->>'id')::uuid) STORED,
 PRIMARY KEY(tenant_id,legal_entity_id,id),UNIQUE(tenant_id,legal_entity_id,tax_entity_id,id),
 UNIQUE(tenant_id,legal_entity_id,tax_entity_id,revision_no),UNIQUE(tenant_id,legal_entity_id,tax_entity_id,id,content_digest),
 CHECK(maker_account IS NOT NULL AND maker_person IS NOT NULL AND maker_binding IS NOT NULL AND original_year IS NOT NULL AND original_id IS NOT NULL),
 CHECK((base_approved_revision_id IS NULL)=(base_approved_digest IS NULL)),
 CHECK((status='PENDING' AND version=1 AND decision_receipt_id IS NULL) OR(status<>'PENDING' AND version=2 AND decision_receipt_id IS NOT NULL)),
 FOREIGN KEY(tenant_id,legal_entity_id,tax_entity_id,home_book_id) REFERENCES plugin_finance_tax_entity_registry(tenant_id,legal_entity_id,id,home_book_id),
 FOREIGN KEY(tenant_id,legal_entity_id,tax_entity_id,previous_revision_id) REFERENCES plugin_finance_tax_entity_revision(tenant_id,legal_entity_id,tax_entity_id,id),
 FOREIGN KEY(tenant_id,legal_entity_id,tax_entity_id,base_approved_revision_id) REFERENCES plugin_finance_tax_entity_revision(tenant_id,legal_entity_id,tax_entity_id,id),
 FOREIGN KEY(tenant_id,maker_binding,maker_account,maker_person) REFERENCES plugin_finance_person_binding(tenant_id,id,account_id,person_id),
 FOREIGN KEY(tenant_id,home_book_id,original_year,original_id) REFERENCES plugin_finance_original(tenant_id,book_id,year_id,id)
);
CREATE UNIQUE INDEX finance_tax_entity_one_pending ON plugin_finance_tax_entity_revision(tenant_id,legal_entity_id,tax_entity_id) WHERE status='PENDING';
CREATE INDEX finance_tax_entity_home_page ON plugin_finance_tax_entity_registry(tenant_id,legal_entity_id,home_book_id,created_at DESC,id DESC);
CREATE INDEX finance_tax_entity_revisions ON plugin_finance_tax_entity_revision(tenant_id,legal_entity_id,tax_entity_id,revision_no DESC);

CREATE TABLE plugin_finance_tax_entity_command_receipt (
 tenant_id uuid NOT NULL,legal_entity_id uuid NOT NULL,tax_entity_id uuid NOT NULL,home_book_id uuid NOT NULL,revision_id uuid NOT NULL,id uuid NOT NULL,
 action text NOT NULL CHECK(action IN('CREATE','PROPOSE_REVISION','APPROVE','REJECT','CANCEL')),
 registry_version bigint NOT NULL CHECK(registry_version>0),target_version bigint NOT NULL CHECK(target_version IN(1,2)),
 content_digest text NOT NULL,actor jsonb NOT NULL,reason text NOT NULL CHECK(length(reason) BETWEEN 1 AND 1000),
 payload jsonb NOT NULL,payload_hash text NOT NULL CHECK(payload_hash~'^[a-f0-9]{64}$'),route text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 actor_account uuid GENERATED ALWAYS AS((actor->>'accountId')::uuid) STORED,
 actor_person uuid GENERATED ALWAYS AS((actor->>'personId')::uuid) STORED,
 actor_binding uuid GENERATED ALWAYS AS((actor->>'bindingId')::uuid) STORED,
 PRIMARY KEY(tenant_id,legal_entity_id,id),UNIQUE(tenant_id,legal_entity_id,tax_entity_id,registry_version),
 UNIQUE(tenant_id,legal_entity_id,revision_id,target_version),UNIQUE(tenant_id,legal_entity_id,tax_entity_id,revision_id,id),
 UNIQUE(tenant_id,legal_entity_id,tax_entity_id,home_book_id,revision_id,id,actor_account),
 UNIQUE(tenant_id,legal_entity_id,tax_entity_id,home_book_id,revision_id,id),
 CHECK(actor_account IS NOT NULL AND actor_person IS NOT NULL AND actor_binding IS NOT NULL),
 CHECK((action IN('CREATE','PROPOSE_REVISION') AND target_version=1) OR(action IN('APPROVE','REJECT','CANCEL') AND target_version=2)),
 FOREIGN KEY(tenant_id,legal_entity_id,tax_entity_id,home_book_id) REFERENCES plugin_finance_tax_entity_registry(tenant_id,legal_entity_id,id,home_book_id),
 FOREIGN KEY(tenant_id,legal_entity_id,tax_entity_id,revision_id,content_digest) REFERENCES plugin_finance_tax_entity_revision(tenant_id,legal_entity_id,tax_entity_id,id,content_digest),
 FOREIGN KEY(tenant_id,actor_binding,actor_account,actor_person) REFERENCES plugin_finance_person_binding(tenant_id,id,account_id,person_id)
);
ALTER TABLE plugin_finance_tax_entity_revision ADD FOREIGN KEY(tenant_id,legal_entity_id,tax_entity_id,id,decision_receipt_id) REFERENCES plugin_finance_tax_entity_command_receipt(tenant_id,legal_entity_id,tax_entity_id,revision_id,id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE plugin_finance_tax_entity_registry ADD FOREIGN KEY(tenant_id,legal_entity_id,id,latest_revision_id) REFERENCES plugin_finance_tax_entity_revision(tenant_id,legal_entity_id,tax_entity_id,id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE plugin_finance_tax_entity_registry ADD FOREIGN KEY(tenant_id,legal_entity_id,id,pending_revision_id) REFERENCES plugin_finance_tax_entity_revision(tenant_id,legal_entity_id,tax_entity_id,id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE plugin_finance_tax_entity_registry ADD FOREIGN KEY(tenant_id,legal_entity_id,id,approved_revision_id) REFERENCES plugin_finance_tax_entity_revision(tenant_id,legal_entity_id,tax_entity_id,id) DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE plugin_finance_tax_entity_request (
 actor_id uuid NOT NULL,request_key text NOT NULL CHECK(length(request_key) BETWEEN 8 AND 128),tenant_id uuid NOT NULL,legal_entity_id uuid NOT NULL,
 home_book_id uuid NOT NULL,tax_entity_id uuid NOT NULL,revision_id uuid NOT NULL,receipt_id uuid NOT NULL,
 route text NOT NULL,payload_hash text NOT NULL CHECK(payload_hash~'^[a-f0-9]{64}$'),created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(actor_id,request_key),
 FOREIGN KEY(tenant_id,legal_entity_id,tax_entity_id,home_book_id,revision_id,receipt_id)
 REFERENCES plugin_finance_tax_entity_command_receipt(tenant_id,legal_entity_id,tax_entity_id,home_book_id,revision_id,id),
 FOREIGN KEY(tenant_id,actor_id) REFERENCES plugin_finance_person_binding(tenant_id,account_id)
);
CREATE TABLE plugin_finance_tax_entity_audit (
 tenant_id uuid NOT NULL,legal_entity_id uuid NOT NULL,tax_entity_id uuid NOT NULL,revision_id uuid NOT NULL,receipt_id uuid NOT NULL,
 id uuid NOT NULL DEFAULT gen_random_uuid(),action text NOT NULL,actor jsonb NOT NULL,reason text NOT NULL,
 registry_version bigint NOT NULL,target_version bigint NOT NULL,content_digest text NOT NULL,created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(tenant_id,legal_entity_id,id),UNIQUE(tenant_id,legal_entity_id,receipt_id),
 FOREIGN KEY(tenant_id,legal_entity_id,tax_entity_id,revision_id,receipt_id) REFERENCES plugin_finance_tax_entity_command_receipt(tenant_id,legal_entity_id,tax_entity_id,revision_id,id)
);

CREATE FUNCTION plugin_finance_tax_entity_digest(v jsonb) RETURNS text LANGUAGE sql IMMUTABLE STRICT AS $$ SELECT encode(sha256(convert_to(v::text,'UTF8')),'hex') $$;
CREATE FUNCTION plugin_finance_tax_entity_fields(v jsonb,keys text[]) RETURNS void LANGUAGE plpgsql IMMUTABLE AS $$
BEGIN IF jsonb_typeof(v) IS DISTINCT FROM 'object' OR (SELECT array_agg(k ORDER BY k) FROM jsonb_object_keys(v) k) IS DISTINCT FROM(SELECT array_agg(k ORDER BY k) FROM unnest(keys) k) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='INVALID_INPUT';END IF;END $$;
CREATE FUNCTION plugin_finance_tax_entity_text(v jsonb,maxlen int,nullable boolean DEFAULT false,descriptive boolean DEFAULT false) RETURNS text LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE s text;BEGIN
 IF nullable AND v='null'::jsonb THEN RETURN NULL;END IF;
 IF jsonb_typeof(v) IS DISTINCT FROM 'string' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='INVALID_INPUT';END IF;
 s=normalize(v#>>'{}',NFC);
 IF length(s)<1 OR length(s)>maxlen OR s~U&'[\0001-\0008\000B\000C\000E-\001F\007F-\009F]' OR(NOT descriptive AND s~E'[\t\r\n]') OR translate(s,E' \t\r\n'||U&'\00A0\1680\2000\2001\2002\2003\2004\2005\2006\2007\2008\2009\200A\2028\2029\202F\205F\3000','')='' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='INVALID_INPUT';END IF;RETURN s;
END $$;
CREATE FUNCTION plugin_finance_tax_entity_identity(v jsonb) RETURNS text LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE n text;BEGIN
 PERFORM plugin_finance_tax_entity_fields(v,ARRAY['country','canonicalTaxRegistrationNo','verification']);
 n=plugin_finance_tax_entity_text(v->'canonicalTaxRegistrationNo',64);
 IF v->>'country' IS DISTINCT FROM 'CN' OR v->>'verification' IS DISTINCT FROM 'ORGANIZATION_DECLARED_NOT_GOVERNMENT_VERIFIED' OR n!~'^[A-Z0-9][A-Z0-9._/-]{0,63}$' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='INVALID_INPUT';END IF;RETURN n;
END $$;
CREATE FUNCTION plugin_finance_tax_entity_actor(t uuid,who jsonb,active_required boolean DEFAULT false) RETURNS void LANGUAGE plpgsql AS $$
DECLARE x plugin_finance_person_binding;r plugin_finance_person_realm;BEGIN
 PERFORM plugin_finance_tax_entity_fields(who,ARRAY['accountId','personId','bindingId','bindingVersion','authorityId','authorityManifestDigest','assurance']);
 IF who->>'assurance' IS DISTINCT FROM 'ORG_ATTESTED' OR jsonb_typeof(who->'bindingVersion') IS DISTINCT FROM 'string' OR who->>'bindingVersion'!~'^[1-9][0-9]{0,18}$' OR who->>'authorityManifestDigest'!~'^[a-f0-9]{64}$' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SCOPE_DENIED';END IF;
 SELECT * INTO x FROM plugin_finance_person_binding WHERE tenant_id=t AND id=(who->>'bindingId')::uuid AND account_id=(who->>'accountId')::uuid AND person_id=(who->>'personId')::uuid;
 IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SCOPE_DENIED';END IF;
 SELECT * INTO r FROM plugin_finance_person_realm WHERE tenant_id=t;
 IF NOT FOUND OR (who->>'authorityId')::uuid IS DISTINCT FROM r.authority_id OR who->>'authorityManifestDigest' IS DISTINCT FROM r.manifest_sha256 OR(who->>'bindingVersion')::bigint>x.version OR(who->>'bindingVersion')::bigint<1 THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SCOPE_DENIED';END IF;
 IF active_required AND plugin_finance_journal_person_identity(t,x.account_id,true) IS DISTINCT FROM who THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SCOPE_DENIED';END IF;
END $$;
CREATE FUNCTION plugin_finance_tax_entity_role(t uuid,b uuid,who jsonb,roles text[]) RETURNS void LANGUAGE plpgsql AS $$
BEGIN PERFORM plugin_finance_tax_entity_actor(t,who,true);IF NOT EXISTS(SELECT 1 FROM plugin_finance_member WHERE tenant_id=t AND book_id=b AND user_id=(who->>'accountId')::uuid AND enabled AND role=ANY(roles)) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SCOPE_DENIED';END IF;END $$;
CREATE FUNCTION plugin_finance_tax_entity_lock(t uuid,e uuid,b uuid) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
 PERFORM tenant_id FROM plugin_finance_person_realm WHERE tenant_id=t FOR SHARE;
 IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SCOPE_DENIED';END IF;
 PERFORM id FROM plugin_finance_book WHERE tenant_id=t AND legal_entity_id=e AND id=b FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SCOPE_DENIED';END IF;
 -- A tenant-wide identity lock cannot be partitioned by home, year, entity or namespace.
 PERFORM pg_advisory_xact_lock(hashtextextended('finance-tax-registry:'||t::text,0));
 PERFORM book_id FROM plugin_finance_tax_entity_home_guard WHERE tenant_id=t AND legal_entity_id=e AND book_id=b FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='TAX_ENTITY_STATE_CONFLICT';END IF;
END $$;

CREATE FUNCTION plugin_finance_tax_entity_profile(t uuid,b uuid,v jsonb,frozen boolean DEFAULT false) RETURNS jsonb LANGUAGE plpgsql AS $$
DECLARE n jsonb;ref jsonb;s jsonb;o plugin_finance_original;k text;est text;fin text;loc text;typ text;BEGIN
 PERFORM plugin_finance_tax_entity_fields(v,ARRAY['displayName','entityKind','registrationTypeDeclaration','residencyDeclaration','establishedOn','cancelledOn','authorityCodeDeclaration','administrativeDivisionDeclaration','organizationVerificationBasis','registrationEvidence','singleTaxpayerConfirmed','allKnownRegistrationIdentifiersDeclared','organizationReviewedNotGovernmentVerified']);
 n=v; n=jsonb_set(n,'{displayName}',to_jsonb(plugin_finance_tax_entity_text(v->'displayName',200)));
 n=jsonb_set(n,'{registrationTypeDeclaration}',to_jsonb(plugin_finance_tax_entity_text(v->'registrationTypeDeclaration',100)));
 n=jsonb_set(n,'{organizationVerificationBasis}',to_jsonb(plugin_finance_tax_entity_text(v->'organizationVerificationBasis',1000,false,true)));
 IF v->>'entityKind' IS NULL OR v->>'entityKind' NOT IN('LEGAL_ENTITY','BRANCH','OTHER_REGISTERED_TAXPAYER') OR v->>'residencyDeclaration' IS NULL OR v->>'residencyDeclaration' NOT IN('RESIDENT','NON_RESIDENT','UNKNOWN') THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='INVALID_INPUT';END IF;
 FOREACH k IN ARRAY ARRAY['authorityCodeDeclaration','administrativeDivisionDeclaration'] LOOP n=jsonb_set(n,ARRAY[k],coalesce(to_jsonb(plugin_finance_tax_entity_text(v->k,100,true)),'null'::jsonb));END LOOP;
 FOREACH k IN ARRAY ARRAY['singleTaxpayerConfirmed','allKnownRegistrationIdentifiersDeclared','organizationReviewedNotGovernmentVerified'] LOOP IF v->k IS DISTINCT FROM 'true'::jsonb THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='INVALID_INPUT';END IF;END LOOP;
 FOREACH k IN ARRAY ARRAY['establishedOn','cancelledOn'] LOOP
  IF v->k IS DISTINCT FROM 'null'::jsonb THEN IF jsonb_typeof(v->k) IS DISTINCT FROM 'string' OR v->>k!~'^[0-9]{4}-[0-9]{2}-[0-9]{2}$' OR ((v->>k)::date)::text IS DISTINCT FROM v->>k THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='INVALID_INPUT';END IF;END IF;
 END LOOP;
 est=v->>'establishedOn';fin=v->>'cancelledOn';IF est IS NOT NULL AND fin IS NOT NULL AND fin<est THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='INVALID_INPUT';END IF;
 ref=v->'registrationEvidence';
 IF frozen THEN PERFORM plugin_finance_tax_entity_fields(ref,ARRAY['bookId','yearId','id','version','sha256','filename','createdBy','sourceType','originalLocation','authenticity']);
 ELSE PERFORM plugin_finance_tax_entity_fields(ref,ARRAY['yearId','originalId','expectedVersion','expectedSha256','sourceType','originalLocation']);END IF;
 IF frozen THEN
  FOREACH k IN ARRAY ARRAY['bookId','yearId','id','version','sha256','filename','createdBy','sourceType','authenticity'] LOOP IF jsonb_typeof(ref->k) IS DISTINCT FROM 'string' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='INVALID_INPUT';END IF;END LOOP;
 ELSE
  FOREACH k IN ARRAY ARRAY['yearId','originalId','expectedVersion','expectedSha256','sourceType'] LOOP IF jsonb_typeof(ref->k) IS DISTINCT FROM 'string' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='INVALID_INPUT';END IF;END LOOP;
 END IF;
 typ=ref->>'sourceType';loc=plugin_finance_tax_entity_text(ref->'originalLocation',500,true,true);
 IF typ IS NULL OR typ NOT IN('ELECTRONIC_ORIGINAL','PAPER_SCAN') OR(typ='PAPER_SCAN' AND loc IS NULL) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='INVALID_INPUT';END IF;
 SELECT * INTO o FROM plugin_finance_original WHERE tenant_id=t AND book_id=b AND year_id=(ref->>'yearId')::uuid AND id=coalesce(ref->>'originalId',ref->>'id')::uuid;
 IF NOT FOUND OR o.version::text IS DISTINCT FROM coalesce(ref->>'expectedVersion',ref->>'version') OR o.sha256 IS DISTINCT FROM coalesce(ref->>'expectedSha256',ref->>'sha256') OR octet_length(o.content)<>o.size_bytes OR encode(sha256(o.content),'hex') IS DISTINCT FROM o.sha256 THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='TAX_ENTITY_SOURCE_STALE';END IF;
 IF length(o.filename) NOT BETWEEN 1 AND 500 THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='TAX_ENTITY_SOURCE_STALE';END IF;
 s=jsonb_build_object('bookId',b,'yearId',o.year_id,'id',o.id,'version',o.version::text,'sha256',o.sha256,'filename',o.filename,'createdBy',o.created_by,'sourceType',typ,'originalLocation',loc,'authenticity','UNVERIFIED');
 IF frozen AND s IS DISTINCT FROM ref THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='TAX_ENTITY_SOURCE_STALE';END IF;
 RETURN jsonb_set(n,'{registrationEvidence}',s);
END $$;
CREATE FUNCTION plugin_finance_tax_entity_revision_digest(r plugin_finance_tax_entity_revision) RETURNS text LANGUAGE sql IMMUTABLE AS $$
 SELECT plugin_finance_tax_entity_digest(jsonb_build_object('format','TAX_ENTITY_REVISION_PG_V1','tenantId',r.tenant_id,'legalEntityId',r.legal_entity_id,'taxEntityId',r.tax_entity_id,'revisionId',r.id,'revision',r.revision_no::text,'previousRevisionId',r.previous_revision_id,'baseApprovedRevisionId',r.base_approved_revision_id,'baseApprovedContentDigest',r.base_approved_digest,'desiredStatus',r.desired_status,'content',r.content,'reason',r.reason,'preparedBy',r.maker))
$$;

CREATE FUNCTION plugin_finance_tax_entity_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE n jsonb;o jsonb;p plugin_finance_tax_entity_registry;r plugin_finance_tax_entity_revision;prev plugin_finance_tax_entity_revision;base plugin_finance_tax_entity_revision;c plugin_finance_tax_entity_command_receipt;dg text;v jsonb;num bigint;desired text;BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='TAX_ENTITY_STATE_CONFLICT';END IF;
 n=to_jsonb(NEW);IF TG_OP='UPDATE' THEN o=to_jsonb(OLD);END IF;
 IF TG_TABLE_NAME='plugin_finance_tax_entity_home_guard' THEN
  IF TG_OP='INSERT' THEN
   IF NEW.generation<>0 OR NOT EXISTS(SELECT 1 FROM plugin_finance_book WHERE tenant_id=NEW.tenant_id AND id=NEW.book_id AND legal_entity_id=NEW.legal_entity_id) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='TAX_ENTITY_STATE_CONFLICT';END IF;
  ELSIF n-ARRAY['generation'] IS DISTINCT FROM o-ARRAY['generation'] OR NEW.generation<>OLD.generation+1 THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='TAX_ENTITY_STATE_CONFLICT';END IF;RETURN NEW;
 END IF;
 IF TG_TABLE_NAME='plugin_finance_tax_entity_registry' THEN
  PERFORM plugin_finance_tax_entity_lock(NEW.tenant_id,NEW.legal_entity_id,NEW.home_book_id);
  IF TG_OP='INSERT' THEN
   PERFORM plugin_finance_tax_entity_role(NEW.tenant_id,NEW.home_book_id,NEW.created_by,ARRAY['OWNER']);
   PERFORM plugin_finance_tax_entity_fields(NEW.initial_payload,ARRAY['schemaVersion','operationId','reason','identity','content']);
   PERFORM plugin_finance_tax_entity_text(NEW.initial_payload->'reason',1000,false,true);
   v=plugin_finance_tax_entity_profile(NEW.tenant_id,NEW.home_book_id,NEW.initial_payload->'content');
   IF NEW.initial_payload->>'schemaVersion' IS DISTINCT FROM 'tax-entity/1' OR NEW.operation_id IS DISTINCT FROM(NEW.initial_payload->>'operationId')::uuid OR NEW.country<>'CN' OR NEW.registration_no IS DISTINCT FROM plugin_finance_tax_entity_identity(NEW.initial_payload->'identity') OR NEW.initial_payload_hash IS DISTINCT FROM plugin_finance_tax_entity_digest(NEW.initial_payload) OR NEW.created_at IS DISTINCT FROM CURRENT_TIMESTAMP OR NEW.updated_at IS DISTINCT FROM CURRENT_TIMESTAMP OR NEW.version<>0 OR NEW.latest_revision_id IS NOT NULL OR NEW.pending_revision_id IS NOT NULL OR NEW.approved_revision_id IS NOT NULL THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='TAX_ENTITY_STATE_CONFLICT';END IF;
  ELSE
   IF n-ARRAY['version','latest_revision_id','pending_revision_id','approved_revision_id','updated_at','creator_account','creator_person','creator_binding'] IS DISTINCT FROM o-ARRAY['version','latest_revision_id','pending_revision_id','approved_revision_id','updated_at','creator_account','creator_person','creator_binding'] OR NEW.version<>OLD.version+1 OR NEW.updated_at IS DISTINCT FROM CURRENT_TIMESTAMP THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='TAX_ENTITY_STATE_CONFLICT';END IF;
  END IF;RETURN NEW;
 END IF;
 SELECT * INTO p FROM plugin_finance_tax_entity_registry WHERE tenant_id=(n->>'tenant_id')::uuid AND legal_entity_id=(n->>'legal_entity_id')::uuid AND id=(n->>'tax_entity_id')::uuid;
 IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='TAX_ENTITY_STATE_CONFLICT';END IF;
 PERFORM plugin_finance_tax_entity_lock(p.tenant_id,p.legal_entity_id,p.home_book_id);
 IF TG_TABLE_NAME='plugin_finance_tax_entity_revision' THEN
  IF TG_OP='INSERT' THEN
   PERFORM plugin_finance_tax_entity_role(p.tenant_id,p.home_book_id,NEW.maker,ARRAY['OWNER']);
   SELECT * INTO prev FROM plugin_finance_tax_entity_revision WHERE tenant_id=p.tenant_id AND legal_entity_id=p.legal_entity_id AND id=p.latest_revision_id;
   SELECT * INTO base FROM plugin_finance_tax_entity_revision WHERE tenant_id=p.tenant_id AND legal_entity_id=p.legal_entity_id AND id=p.approved_revision_id;
   v=plugin_finance_tax_entity_profile(p.tenant_id,p.home_book_id,NEW.content,true);
   IF NEW.status<>'PENDING' OR NEW.version<>1 OR NEW.decision_receipt_id IS NOT NULL OR NEW.created_at IS DISTINCT FROM CURRENT_TIMESTAMP OR NEW.home_book_id<>p.home_book_id OR p.pending_revision_id IS NOT NULL OR NEW.revision_no<>coalesce(prev.revision_no,0)+1 OR NEW.previous_revision_id IS DISTINCT FROM prev.id OR NEW.base_approved_revision_id IS DISTINCT FROM base.id OR NEW.base_approved_digest IS DISTINCT FROM base.content_digest OR(base.id IS NULL AND NEW.desired_status<>'ACTIVE') OR NEW.content IS DISTINCT FROM v OR NEW.content_digest IS DISTINCT FROM plugin_finance_tax_entity_revision_digest(NEW) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='TAX_ENTITY_STATE_CONFLICT';END IF;
   PERFORM plugin_finance_tax_entity_text(to_jsonb(NEW.reason),1000,false,true);
   IF prev.id IS NULL AND(NEW.maker IS DISTINCT FROM p.created_by OR NEW.reason IS DISTINCT FROM p.initial_payload->>'reason' OR NEW.content IS DISTINCT FROM plugin_finance_tax_entity_profile(p.tenant_id,p.home_book_id,p.initial_payload->'content')) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='TAX_ENTITY_STATE_CONFLICT';END IF;
  ELSE
   IF n-ARRAY['version','status','decision_receipt_id','maker_account','maker_person','maker_binding','original_year','original_id'] IS DISTINCT FROM o-ARRAY['version','status','decision_receipt_id','maker_account','maker_person','maker_binding','original_year','original_id'] OR OLD.status<>'PENDING' OR NEW.status NOT IN('APPROVED','REJECTED','CANCELLED') OR OLD.version<>1 OR NEW.version<>2 OR NEW.decision_receipt_id IS NULL THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='TAX_ENTITY_STATE_CONFLICT';END IF;
  END IF;RETURN NEW;
 END IF;
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='TAX_ENTITY_STATE_CONFLICT';END IF;
 IF TG_TABLE_NAME='plugin_finance_tax_entity_command_receipt' THEN
  SELECT * INTO r FROM plugin_finance_tax_entity_revision WHERE tenant_id=p.tenant_id AND legal_entity_id=p.legal_entity_id AND tax_entity_id=p.id AND id=NEW.revision_id;
  IF NOT FOUND OR NEW.home_book_id<>p.home_book_id OR NEW.content_digest IS DISTINCT FROM r.content_digest OR NEW.created_at IS DISTINCT FROM CURRENT_TIMESTAMP OR NEW.registry_version<>p.version OR NEW.target_version<>r.version OR NEW.reason IS DISTINCT FROM NEW.payload->>'reason' OR NEW.payload_hash IS DISTINCT FROM plugin_finance_tax_entity_digest(NEW.payload) OR NEW.payload->>'schemaVersion' IS DISTINCT FROM 'tax-entity/1' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='TAX_ENTITY_STATE_CONFLICT';END IF;
  PERFORM plugin_finance_tax_entity_text(to_jsonb(NEW.reason),1000,false,true);
  IF NEW.action IN('CREATE','PROPOSE_REVISION') THEN
   PERFORM plugin_finance_tax_entity_role(p.tenant_id,p.home_book_id,NEW.actor,ARRAY['OWNER']);
   IF NEW.actor IS DISTINCT FROM r.maker OR NEW.reason IS DISTINCT FROM r.reason OR r.version<>1 OR r.status<>'PENDING' OR (NEW.action='CREATE')<>(r.revision_no=1) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='TAX_ENTITY_STATE_CONFLICT';END IF;
   IF NEW.action='CREATE' THEN
    IF NEW.payload IS DISTINCT FROM p.initial_payload OR NEW.route IS DISTINCT FROM 'create/null/null' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='TAX_ENTITY_STATE_CONFLICT';END IF;
   ELSE
    PERFORM plugin_finance_tax_entity_fields(NEW.payload,ARRAY['schemaVersion','reason','expectedEntityVersion','expectedLatestRevisionId','expectedLatestRevisionVersion','expectedApprovedRevisionId','expectedApprovedContentDigest','desiredStatus','content']);
    SELECT * INTO prev FROM plugin_finance_tax_entity_revision WHERE tenant_id=p.tenant_id AND legal_entity_id=p.legal_entity_id AND id=r.previous_revision_id;
    IF (NEW.payload->>'expectedEntityVersion')::bigint<>p.version-1 OR (NEW.payload->>'expectedLatestRevisionId')::uuid IS DISTINCT FROM r.previous_revision_id OR(NEW.payload->>'expectedLatestRevisionVersion')::bigint IS DISTINCT FROM prev.version OR(NEW.payload->>'expectedApprovedRevisionId')::uuid IS DISTINCT FROM r.base_approved_revision_id OR NEW.payload->>'expectedApprovedContentDigest' IS DISTINCT FROM r.base_approved_digest OR NEW.payload->>'desiredStatus' IS DISTINCT FROM r.desired_status OR plugin_finance_tax_entity_profile(p.tenant_id,p.home_book_id,NEW.payload->'content') IS DISTINCT FROM r.content OR NEW.route IS DISTINCT FROM 'propose-revision/'||p.id::text||'/null' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='TAX_ENTITY_STATE_CONFLICT';END IF;
   END IF;
  ELSE
   PERFORM plugin_finance_tax_entity_fields(NEW.payload,ARRAY['schemaVersion','reason','expectedEntityVersion','expectedVersion','contentDigest']);
   IF (NEW.payload->>'expectedEntityVersion')::bigint<>p.version-1 OR(NEW.payload->>'expectedVersion')::bigint<>1 OR NEW.payload->>'contentDigest' IS DISTINCT FROM r.content_digest OR NEW.route IS DISTINCT FROM lower(NEW.action)||'/'||p.id::text||'/'||r.id::text OR r.decision_receipt_id IS DISTINCT FROM NEW.id OR r.status IS DISTINCT FROM(CASE NEW.action WHEN 'APPROVE' THEN 'APPROVED' WHEN 'REJECT' THEN 'REJECTED' ELSE 'CANCELLED' END) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='TAX_ENTITY_STATE_CONFLICT';END IF;
   IF NEW.action='CANCEL' THEN
    PERFORM plugin_finance_tax_entity_role(p.tenant_id,p.home_book_id,NEW.actor,ARRAY['OWNER']);
    IF NEW.actor->>'accountId' IS DISTINCT FROM r.maker->>'accountId' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SCOPE_DENIED';END IF;
   ELSE
    PERFORM plugin_finance_tax_entity_role(p.tenant_id,p.home_book_id,NEW.actor,ARRAY['OWNER','REVIEWER']);
    IF NEW.actor->>'personId' IS NOT DISTINCT FROM r.maker->>'personId' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SELF_APPROVAL_FORBIDDEN';END IF;
    IF NEW.action='APPROVE' THEN
     PERFORM plugin_finance_tax_entity_role(p.tenant_id,p.home_book_id,r.maker,ARRAY['OWNER']);
     PERFORM plugin_finance_tax_entity_profile(p.tenant_id,p.home_book_id,r.content,true);
    END IF;
   END IF;
  END IF;RETURN NEW;
 END IF;
 SELECT * INTO c FROM plugin_finance_tax_entity_command_receipt WHERE tenant_id=p.tenant_id AND legal_entity_id=p.legal_entity_id AND tax_entity_id=p.id AND id=(n->>'receipt_id')::uuid;
 IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='TAX_ENTITY_STATE_CONFLICT';END IF;
 IF TG_TABLE_NAME='plugin_finance_tax_entity_audit' THEN
  IF (NEW.revision_id,NEW.action,NEW.actor,NEW.reason,NEW.registry_version,NEW.target_version,NEW.content_digest,NEW.created_at) IS DISTINCT FROM(c.revision_id,c.action,c.actor,c.reason,c.registry_version,c.target_version,c.content_digest,c.created_at) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='TAX_ENTITY_STATE_CONFLICT';END IF;
 ELSIF TG_TABLE_NAME='plugin_finance_tax_entity_request' THEN
  IF NEW.home_book_id<>p.home_book_id OR NEW.revision_id<>c.revision_id OR NEW.payload_hash IS DISTINCT FROM c.payload_hash OR NEW.route IS DISTINCT FROM c.route OR NEW.created_at IS DISTINCT FROM CURRENT_TIMESTAMP THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='IDEMPOTENCY_CONFLICT';END IF;
 END IF;RETURN NEW;
END $$;

CREATE FUNCTION plugin_finance_tax_entity_assert(t uuid,e uuid,pid uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE p plugin_finance_tax_entity_registry;r plugin_finance_tax_entity_revision;base plugin_finance_tax_entity_revision;pending uuid;cnt bigint;maxn bigint;receipts bigint;BEGIN
 SELECT * INTO p FROM plugin_finance_tax_entity_registry WHERE tenant_id=t AND legal_entity_id=e AND id=pid;
 IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='TAX_ENTITY_STATE_CONFLICT';END IF;
 PERFORM plugin_finance_tax_entity_actor(t,p.created_by);
 SELECT count(*),max(revision_no) INTO cnt,maxn FROM plugin_finance_tax_entity_revision WHERE tenant_id=t AND legal_entity_id=e AND tax_entity_id=pid;
 SELECT count(*) INTO receipts FROM plugin_finance_tax_entity_command_receipt WHERE tenant_id=t AND legal_entity_id=e AND tax_entity_id=pid;
 SELECT * INTO r FROM plugin_finance_tax_entity_revision WHERE tenant_id=t AND legal_entity_id=e AND tax_entity_id=pid ORDER BY revision_no DESC LIMIT 1;
 SELECT id INTO pending FROM plugin_finance_tax_entity_revision WHERE tenant_id=t AND legal_entity_id=e AND tax_entity_id=pid AND status='PENDING';
 SELECT * INTO base FROM plugin_finance_tax_entity_revision WHERE tenant_id=t AND legal_entity_id=e AND tax_entity_id=pid AND status='APPROVED' ORDER BY revision_no DESC LIMIT 1;
 IF cnt=0 OR cnt<>maxn OR p.latest_revision_id IS DISTINCT FROM r.id OR p.pending_revision_id IS DISTINCT FROM pending OR p.approved_revision_id IS DISTINCT FROM base.id OR p.version<>receipts OR receipts<>cnt+(SELECT count(*) FROM plugin_finance_tax_entity_revision WHERE tenant_id=t AND legal_entity_id=e AND tax_entity_id=pid AND status<>'PENDING') THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='TAX_ENTITY_STATE_CONFLICT';END IF;
 IF EXISTS(SELECT 1 FROM plugin_finance_tax_entity_revision v LEFT JOIN plugin_finance_tax_entity_revision prev ON(prev.tenant_id,prev.legal_entity_id,prev.tax_entity_id,prev.id)=(v.tenant_id,v.legal_entity_id,v.tax_entity_id,v.previous_revision_id) WHERE v.tenant_id=t AND v.legal_entity_id=e AND v.tax_entity_id=pid AND ((v.revision_no=1 AND(v.previous_revision_id IS NOT NULL OR v.base_approved_revision_id IS NOT NULL OR v.desired_status<>'ACTIVE' OR v.maker IS DISTINCT FROM p.created_by)) OR(v.revision_no>1 AND(prev.id IS NULL OR prev.revision_no<>v.revision_no-1 OR prev.status='PENDING')) OR v.content_digest IS DISTINCT FROM plugin_finance_tax_entity_revision_digest(v) OR NOT EXISTS(SELECT 1 FROM plugin_finance_tax_entity_command_receipt c WHERE(c.tenant_id,c.legal_entity_id,c.tax_entity_id,c.revision_id,c.target_version)=(t,e,pid,v.id,1) AND c.actor=v.maker AND c.reason=v.reason AND c.content_digest=v.content_digest AND c.created_at=v.created_at AND c.action=CASE WHEN v.revision_no=1 THEN 'CREATE' ELSE 'PROPOSE_REVISION' END) OR(v.status<>'PENDING' AND NOT EXISTS(SELECT 1 FROM plugin_finance_tax_entity_command_receipt c WHERE(c.tenant_id,c.legal_entity_id,c.tax_entity_id,c.revision_id,c.id,c.target_version)=(t,e,pid,v.id,v.decision_receipt_id,2) AND c.content_digest=v.content_digest AND c.action=CASE v.status WHEN 'APPROVED' THEN 'APPROVE' WHEN 'REJECTED' THEN 'REJECT' ELSE 'CANCEL' END)))) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='TAX_ENTITY_STATE_CONFLICT';END IF;
 IF EXISTS(SELECT 1 FROM plugin_finance_tax_entity_command_receipt c WHERE c.tenant_id=t AND c.legal_entity_id=e AND c.tax_entity_id=pid AND (c.registry_version>p.version OR NOT EXISTS(SELECT 1 FROM plugin_finance_tax_entity_audit a WHERE(a.tenant_id,a.legal_entity_id,a.tax_entity_id,a.revision_id,a.receipt_id,a.action,a.actor,a.reason,a.registry_version,a.target_version,a.content_digest,a.created_at)=(t,e,pid,c.revision_id,c.id,c.action,c.actor,c.reason,c.registry_version,c.target_version,c.content_digest,c.created_at)) OR NOT EXISTS(SELECT 1 FROM plugin_finance_tax_entity_request q WHERE(q.tenant_id,q.legal_entity_id,q.tax_entity_id,q.revision_id,q.receipt_id)=(t,e,pid,c.revision_id,c.id)))) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='TAX_ENTITY_STATE_CONFLICT';END IF;
END $$;
CREATE FUNCTION plugin_finance_tax_entity_final() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE n jsonb;pid uuid;BEGIN n=to_jsonb(NEW);IF TG_TABLE_NAME='plugin_finance_tax_entity_registry' THEN pid=NEW.id;ELSE pid=(n->>'tax_entity_id')::uuid;END IF;PERFORM plugin_finance_tax_entity_assert((n->>'tenant_id')::uuid,(n->>'legal_entity_id')::uuid,pid);RETURN NULL;END $$;
CREATE FUNCTION plugin_finance_tax_entity_visible() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN UPDATE plugin_finance_tax_entity_home_guard SET generation=generation+1 WHERE tenant_id=NEW.tenant_id AND legal_entity_id=NEW.legal_entity_id AND book_id=NEW.home_book_id;RETURN NULL;END $$;
CREATE TRIGGER finance_tax_entity_home_guard BEFORE INSERT OR UPDATE OR DELETE ON plugin_finance_tax_entity_home_guard FOR EACH ROW EXECUTE FUNCTION plugin_finance_tax_entity_guard();
DO $$ DECLARE tab text;BEGIN
 FOREACH tab IN ARRAY ARRAY['registry','revision','command_receipt','request','audit'] LOOP
  EXECUTE format('CREATE TRIGGER finance_tax_entity_%s_guard BEFORE INSERT OR UPDATE OR DELETE ON plugin_finance_tax_entity_%s FOR EACH ROW EXECUTE FUNCTION plugin_finance_tax_entity_guard()',tab,tab);
  EXECUTE format('CREATE CONSTRAINT TRIGGER finance_tax_entity_%s_final AFTER INSERT OR UPDATE ON plugin_finance_tax_entity_%s DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION plugin_finance_tax_entity_final()',tab,tab);
 END LOOP;
 FOREACH tab IN ARRAY ARRAY['registry','revision'] LOOP EXECUTE format('CREATE TRIGGER finance_tax_entity_%s_visible AFTER INSERT OR UPDATE ON plugin_finance_tax_entity_%s FOR EACH ROW EXECUTE FUNCTION plugin_finance_tax_entity_visible()',tab,tab);END LOOP;
END $$;

-- One atomic command; Java still authorizes public permissions, year reads and session assurance.
CREATE FUNCTION plugin_finance_tax_entity_command(t uuid,e uuid,b uuid,who jsonb,op text,pid uuid,rid uuid,payload jsonb) RETURNS jsonb LANGUAGE plpgsql AS $$
DECLARE p plugin_finance_tax_entity_registry;r plugin_finance_tax_entity_revision;prev plugin_finance_tax_entity_revision;base plugin_finance_tax_entity_revision;c plugin_finance_tax_entity_command_receipt;ct jsonb;initial jsonb;number text;why text;desired text;cid uuid;opid uuid;dg text;route text;tv bigint;BEGIN
 PERFORM tenant_id FROM plugin_finance_person_realm WHERE tenant_id=t FOR SHARE;
 IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SCOPE_DENIED';END IF;
 PERFORM pg_advisory_xact_lock(hashtextextended((who->>'accountId')||':'||(payload->>'idempotencyKey'),0));
 PERFORM plugin_finance_tax_entity_lock(t,e,b);
 IF op IN('APPROVE','REJECT') THEN PERFORM plugin_finance_tax_entity_role(t,b,who,ARRAY['OWNER','REVIEWER']);ELSE PERFORM plugin_finance_tax_entity_role(t,b,who,ARRAY['OWNER']);END IF;
 initial=payload-'idempotencyKey';why=plugin_finance_tax_entity_text(payload->'reason',1000,false,true);
 IF op='CREATE' THEN
  PERFORM plugin_finance_tax_entity_fields(payload,ARRAY['schemaVersion','idempotencyKey','operationId','identity','content','reason']);
  opid=(payload->>'operationId')::uuid;number=plugin_finance_tax_entity_identity(payload->'identity');
  SELECT * INTO p FROM plugin_finance_tax_entity_registry WHERE tenant_id=t AND operation_id=opid;
  IF FOUND THEN
   IF p.legal_entity_id<>e OR p.home_book_id<>b OR p.initial_payload IS DISTINCT FROM initial THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='IDEMPOTENCY_CONFLICT';END IF;
   SELECT * INTO c FROM plugin_finance_tax_entity_command_receipt WHERE tenant_id=t AND legal_entity_id=e AND tax_entity_id=p.id AND action='CREATE';
   RETURN jsonb_build_object('taxEntityId',p.id,'revisionId',c.revision_id,'commandReceiptId',c.id,'newEffect',false);
  END IF;
  IF EXISTS(SELECT 1 FROM plugin_finance_tax_entity_registry WHERE tenant_id=t AND registration_no=number) THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='TAX_ENTITY_IDENTITY_EXISTS';END IF;
  ct=plugin_finance_tax_entity_profile(t,b,payload->'content');pid=gen_random_uuid();rid=gen_random_uuid();
  INSERT INTO plugin_finance_tax_entity_registry(tenant_id,legal_entity_id,id,home_book_id,country,registration_no,operation_id,initial_payload,initial_payload_hash,created_by) VALUES(t,e,pid,b,'CN',number,opid,initial,plugin_finance_tax_entity_digest(initial),who);
  desired='ACTIVE';route='create/null/null';
 ELSE
  SELECT * INTO p FROM plugin_finance_tax_entity_registry WHERE tenant_id=t AND legal_entity_id=e AND id=pid AND home_book_id=b FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='SCOPE_DENIED';END IF;
  IF p.version IS DISTINCT FROM(payload->>'expectedEntityVersion')::bigint THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='VERSION_CONFLICT';END IF;
  IF op='PROPOSE_REVISION' THEN
   PERFORM plugin_finance_tax_entity_fields(payload,ARRAY['schemaVersion','idempotencyKey','reason','expectedEntityVersion','expectedLatestRevisionId','expectedLatestRevisionVersion','expectedApprovedRevisionId','expectedApprovedContentDigest','desiredStatus','content']);
   SELECT * INTO prev FROM plugin_finance_tax_entity_revision WHERE tenant_id=t AND legal_entity_id=e AND tax_entity_id=pid AND id=p.latest_revision_id;
   SELECT * INTO base FROM plugin_finance_tax_entity_revision WHERE tenant_id=t AND legal_entity_id=e AND tax_entity_id=pid AND id=p.approved_revision_id;
   IF p.pending_revision_id IS NOT NULL THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='TAX_ENTITY_STATE_CONFLICT';END IF;
   IF prev.id IS DISTINCT FROM(payload->>'expectedLatestRevisionId')::uuid OR prev.version IS DISTINCT FROM(payload->>'expectedLatestRevisionVersion')::bigint OR base.id IS DISTINCT FROM(payload->>'expectedApprovedRevisionId')::uuid OR base.content_digest IS DISTINCT FROM payload->>'expectedApprovedContentDigest' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='VERSION_CONFLICT';END IF;
   ct=plugin_finance_tax_entity_profile(t,b,payload->'content');desired=payload->>'desiredStatus';rid=gen_random_uuid();route='propose-revision/'||pid::text||'/null';
   IF desired IS NULL OR desired NOT IN('ACTIVE','INACTIVE') OR(base.id IS NULL AND desired<>'ACTIVE') THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='TAX_ENTITY_STATE_CONFLICT';END IF;
  ELSIF op IN('APPROVE','REJECT','CANCEL') THEN
   PERFORM plugin_finance_tax_entity_fields(payload,ARRAY['schemaVersion','idempotencyKey','reason','expectedEntityVersion','expectedVersion','contentDigest']);
   SELECT * INTO r FROM plugin_finance_tax_entity_revision WHERE tenant_id=t AND legal_entity_id=e AND tax_entity_id=pid AND id=rid FOR UPDATE;
   IF NOT FOUND OR r.status<>'PENDING' OR p.pending_revision_id IS DISTINCT FROM rid THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='TAX_ENTITY_STATE_CONFLICT';END IF;
   IF r.version IS DISTINCT FROM(payload->>'expectedVersion')::bigint OR r.content_digest IS DISTINCT FROM payload->>'contentDigest' THEN RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='VERSION_CONFLICT';END IF;
   cid=gen_random_uuid();route=lower(op)||'/'||pid::text||'/'||rid::text;
   UPDATE plugin_finance_tax_entity_revision SET status=CASE op WHEN 'APPROVE' THEN 'APPROVED' WHEN 'REJECT' THEN 'REJECTED' ELSE 'CANCELLED' END,version=2,decision_receipt_id=cid WHERE tenant_id=t AND legal_entity_id=e AND id=rid;
   UPDATE plugin_finance_tax_entity_registry SET version=version+1,pending_revision_id=NULL,approved_revision_id=CASE WHEN op='APPROVE' THEN rid ELSE approved_revision_id END,updated_at=CURRENT_TIMESTAMP WHERE tenant_id=t AND legal_entity_id=e AND id=pid;
   SELECT * INTO r FROM plugin_finance_tax_entity_revision WHERE tenant_id=t AND legal_entity_id=e AND id=rid;
   SELECT * INTO p FROM plugin_finance_tax_entity_registry WHERE tenant_id=t AND legal_entity_id=e AND id=pid;
   INSERT INTO plugin_finance_tax_entity_command_receipt(tenant_id,legal_entity_id,tax_entity_id,home_book_id,revision_id,id,action,registry_version,target_version,content_digest,actor,reason,payload,payload_hash,route) VALUES(t,e,pid,b,rid,cid,op,p.version,2,r.content_digest,who,why,initial,plugin_finance_tax_entity_digest(initial),route);
   INSERT INTO plugin_finance_tax_entity_audit(tenant_id,legal_entity_id,tax_entity_id,revision_id,receipt_id,action,actor,reason,registry_version,target_version,content_digest) VALUES(t,e,pid,rid,cid,op,who,why,p.version,2,r.content_digest);
   RETURN jsonb_build_object('taxEntityId',pid,'revisionId',rid,'commandReceiptId',cid,'newEffect',true);
  ELSE RAISE EXCEPTION USING ERRCODE='P0001',MESSAGE='INVALID_INPUT';END IF;
 END IF;
 r.tenant_id=t;r.legal_entity_id=e;r.tax_entity_id=pid;r.home_book_id=b;r.id=rid;r.revision_no=coalesce(prev.revision_no,0)+1;r.version=1;r.status='PENDING';r.desired_status=desired;r.previous_revision_id=prev.id;r.base_approved_revision_id=base.id;r.base_approved_digest=base.content_digest;r.content=ct;r.reason=why;r.maker=who;
 dg=plugin_finance_tax_entity_revision_digest(r);
 INSERT INTO plugin_finance_tax_entity_revision(tenant_id,legal_entity_id,tax_entity_id,home_book_id,id,revision_no,desired_status,previous_revision_id,base_approved_revision_id,base_approved_digest,content,content_digest,reason,maker) VALUES(t,e,pid,b,rid,r.revision_no,desired,prev.id,base.id,base.content_digest,ct,dg,why,who);
 UPDATE plugin_finance_tax_entity_registry SET version=version+1,latest_revision_id=rid,pending_revision_id=rid,updated_at=CURRENT_TIMESTAMP WHERE tenant_id=t AND legal_entity_id=e AND id=pid;
 SELECT * INTO p FROM plugin_finance_tax_entity_registry WHERE tenant_id=t AND legal_entity_id=e AND id=pid;
 cid=gen_random_uuid();
 INSERT INTO plugin_finance_tax_entity_command_receipt(tenant_id,legal_entity_id,tax_entity_id,home_book_id,revision_id,id,action,registry_version,target_version,content_digest,actor,reason,payload,payload_hash,route) VALUES(t,e,pid,b,rid,cid,op,p.version,1,dg,who,why,initial,plugin_finance_tax_entity_digest(initial),route);
 INSERT INTO plugin_finance_tax_entity_audit(tenant_id,legal_entity_id,tax_entity_id,revision_id,receipt_id,action,actor,reason,registry_version,target_version,content_digest) VALUES(t,e,pid,rid,cid,op,who,why,p.version,1,dg);
 RETURN jsonb_build_object('taxEntityId',pid,'revisionId',rid,'commandReceiptId',cid,'newEffect',true);
END $$;



