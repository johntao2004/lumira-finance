package com.lumira.finance.payroll;

import static com.lumira.finance.payroll.PayrollService.*;
import static com.lumira.finance.payroll.PayrollCalculation.*;
import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class PayrollServiceTest {
    final YearScope scope=new YearScope(UUID.randomUUID(),UUID.randomUUID(),2026);
    final UUID employee=UUID.randomUUID(), book=UUID.randomUUID();
    final Map<String,Actor> actors=new HashMap<>();
    final Memory store=new Memory();
    boolean denied;
    final PayrollService service=new PayrollService((p,s,b,permission,employees)-> {
        if(denied) throw new IllegalArgumentException("SCOPE_DENIED");
        return actors.computeIfAbsent(p,k->new Actor(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID()));
    },new Evidence(){
        public RulePack rule(YearScope s,UUID id,LocalDate date){return PayrollCalculationTest.rule();}
        public ExceptionEvidence exception(YearScope s,UUID employee,UUID ref){throw new IllegalArgumentException("NOT_APPROVED");}
        public String validateLine(YearScope s,Draft d,Line l){return "a".repeat(64);}
        public Cumulative opening(YearScope s,UUID employee,LocalDate date){return PayrollCalculationTest.input("0","0");}
        public String dependencies(YearScope s,Draft d){return "b".repeat(64);}
        public Fact fact(YearScope s,Batch b,FactKind kind,UUID ref){
            BigDecimal amount=b.computed().stream().map(c->switch(kind){
                case WITHHOLDING_CONFIRMED,DECLARATION_ACCEPTED->c.tax().currentWithholding();
                case PAYMENT_VERIFIED->c.netPay();case ACCRUAL_POSTED->c.gross().add(c.employerSocial());
            }).reduce(BigDecimal.ZERO,BigDecimal::add);
            return new Fact(kind,ref,"c".repeat(64),amount);
        }
    },store);
    Draft draft(int month){return new Draft(UUID.randomUUID(),book,LocalDate.of(2026,month,28),Kind.NORMAL,null,null,UUID.randomUUID(),
        List.of(new Line(employee,UUID.randomUUID(),"10000","0","0","0","0","0","0","0","0",month,DeductionMode.NORMAL,null,
                UUID.randomUUID(),LocalDate.of(2026,month,1),LocalDate.of(2026,month,28))));}
    Command command(long version){return new Command(UUID.randomUUID(),"test",version);}
    Batch lock(Draft d){
        Batch b=service.create("maker",scope,command(0),d);
        b=service.act("maker",scope,d.id(),command(b.version()),Action.CALCULATE);
        b=service.act("reviewer",scope,d.id(),command(b.version()),Action.REVIEW);
        b=service.act("approver",scope,d.id(),command(b.version()),Action.APPROVE);
        return service.act("approver",scope,d.id(),command(b.version()),Action.LOCK);
    }
    @Test void independentPeopleAndExactRetry() {
        Draft d=draft(1);Command cmd=command(0);
        Batch first=service.create("maker",scope,cmd,d);
        assertEquals(first,service.create("maker",scope,cmd,d));
        Draft changed=new Draft(d.id(),d.book(),d.payDate(),d.kind(),null,"changed",d.rulePack(),d.lines());
        assertThrows(IllegalArgumentException.class,()->service.create("maker",scope,cmd,changed));
        Batch calc=service.act("maker",scope,d.id(),command(1),Action.CALCULATE);
        Actor original=actors.get("maker");actors.put("second-account",new Actor(UUID.randomUUID(),original.person(),UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class,()->service.act("second-account",scope,d.id(),command(calc.version()),Action.REVIEW));
        denied=true;
        assertThrows(IllegalArgumentException.class,()->service.create("maker",scope,cmd,d));
    }
    @Test void cumulativeUsesConfirmedWithholdingNotPaymentOrAcceptance() {
        Batch jan=lock(draft(1));
        Draft feb=draft(2);service.create("maker",scope,command(0),feb);
        assertThrows(IllegalArgumentException.class,()->service.act("maker",scope,feb.id(),command(1),Action.CALCULATE));
        service.attachFact("verifier",scope,jan.draft().id(),command(jan.version()),FactKind.WITHHOLDING_CONFIRMED,UUID.randomUUID());
        Batch result=service.act("maker",scope,feb.id(),command(1),Action.CALCULATE);
        assertEquals(new BigDecimal("150.00"),result.computed().getFirst().tax().currentWithholding());
        assertEquals(new BigDecimal("300.00"),result.computed().getFirst().tax().cumulativeTax());
    }
    @Test void acceptedFactCannotRegressOrDuplicate() {
        Batch b=lock(draft(1));
        Batch accepted=service.attachFact("verifier",scope,b.draft().id(),command(b.version()),FactKind.DECLARATION_ACCEPTED,UUID.randomUUID());
        assertThrows(IllegalArgumentException.class,()->service.attachFact("verifier",scope,b.draft().id(),command(accepted.version()),FactKind.DECLARATION_ACCEPTED,UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class,()->service.act("maker",scope,b.draft().id(),command(accepted.version()),Action.CALCULATE));
        PayrollView view=PayrollView.from(accepted);
        assertEquals("10000.00",view.computed().getFirst().gross());
        assertEquals("150.00",view.computed().getFirst().tax().currentWithholding());
        assertEquals("150.00",view.facts().getFirst().amount());
    }
    @Test void postgresMigrationRlsAndJdbcStoreLifecycle() throws Exception {
        String url=System.getenv("FINANCE_TEST_JDBC_URL"), mig=System.getProperty("payroll.migration.candidate", "../migrations/V22__payroll_candidate.sql");
        if(url==null) return;
        if(!url.matches("jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost|\\[::1\\]):[0-9]{1,5}/lumira_qa_finance_[a-z0-9_]+") || url.contains("lumira_qa_finance_web"))
            throw new IllegalStateException("Isolated loopback lumira_qa_finance_* JDBC URL required (never lumira_qa_finance_web)");
        if(mig==null || !java.nio.file.Files.isRegularFile(java.nio.file.Path.of(mig))) throw new IllegalStateException("Root must supply -Dpayroll.migration.candidate=<absolute candidate SQL path>");
        var admin=new org.postgresql.ds.PGSimpleDataSource();
        admin.setURL(url);admin.setUser(System.getenv("FINANCE_TEST_DB_USER"));admin.setPassword(System.getenv("FINANCE_TEST_DB_PASSWORD"));
        String schema="finance_qa_"+UUID.randomUUID().toString().replace("-","");
        String rlsRole="payroll_rls_"+schema;
        var adminJdbc=org.springframework.jdbc.core.simple.JdbcClient.create(admin);
        adminJdbc.sql("CREATE SCHEMA "+schema).update();
        adminJdbc.sql("CREATE ROLE "+rlsRole+" NOLOGIN NOSUPERUSER NOBYPASSRLS").update();
        try {
            var source=new org.postgresql.ds.PGSimpleDataSource();
            source.setURL(url);source.setUser(admin.getUser());source.setPassword(admin.getPassword());source.setCurrentSchema(schema);
            try(var c=source.getConnection()) {
                for(String f:List.of("V1__plugin_initialization.sql","V2__audit_scope_cursor_index.sql","V3__v4_precision_and_year_guard.sql","V4__source_events_and_outbox.sql","V12__person_directory.sql","V13__person_directory_resource_index.sql","V14__journal_person_protocol.sql","V19__tax_entity_registry.sql"))
                    c.createStatement().execute(java.nio.file.Files.readString(java.nio.file.Path.of("../migrations/"+f)));
                c.createStatement().execute(java.nio.file.Files.readString(java.nio.file.Path.of(mig)));
            }
            adminJdbc.sql("GRANT USAGE ON SCHEMA "+schema+" TO "+rlsRole).update();
            adminJdbc.sql("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA "+schema+" TO "+rlsRole).update();
            adminJdbc.sql("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA "+schema+" TO "+rlsRole).update();

            var jdbc=org.springframework.jdbc.core.simple.JdbcClient.create(source);
            UUID tenantId=scope.tenant(), legalEntityId=UUID.randomUUID(), yearId=UUID.randomUUID(), origId=UUID.randomUUID();
            Map<String,UUID> accounts=Map.of("maker",UUID.randomUUID(),"reviewer",UUID.randomUUID(),"approver",UUID.randomUUID(),"verifier",UUID.randomUUID());
            jdbc.sql("INSERT INTO plugin_finance_tenant(id,name,owner_id) VALUES(?,'QA',?)").params(tenantId,accounts.get("maker")).update();
            var mapper=new tools.jackson.databind.ObjectMapper();
            List<Map<String,Object>> seeds=accounts.entrySet().stream().map(e->Map.<String,Object>of("abilities",List.of("READ","PROPOSE","CONFIRM"),"accountId",e.getValue().toString(),"displayName",e.getKey(),"personReference","REF-"+e.getKey())).toList();
            String manifest=mapper.writeValueAsString(Map.of("attestedAt","2026-01-01T00:00:00Z","authorityId",UUID.randomUUID().toString(),"authorityName","QA","schemaVersion","1","seeds",seeds,"sourceReference","SRC","sourceSha256","b".repeat(64),"tenantId",tenantId.toString(),"verificationMethod","ORGANIZATION_MANUAL"));
            String mHash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            jdbc.sql("SELECT plugin_finance_person_directory_bootstrap(?,?)::text").params(manifest,mHash).query(String.class).single();
            Map<String,Actor> dbActors=new HashMap<>();
            for(var e:accounts.entrySet()) {
                var row=jdbc.sql("SELECT id,person_id FROM plugin_finance_person_binding WHERE tenant_id=? AND account_id=?").params(tenantId,e.getValue()).query().singleRow();
                dbActors.put(e.getKey(),new Actor(e.getValue(),(UUID)row.get("person_id"),(UUID)row.get("id")));
            }

            // Create legal entity, book, year, original evidence, and V19 tax entity so all 7 payroll tables & FKs are exercised
            jdbc.sql("INSERT INTO plugin_finance_entity(tenant_id,id,name,registration_id) VALUES(?,?,'QA法人',?)").params(tenantId,legalEntityId,"ENT-001").update();
            jdbc.sql("INSERT INTO plugin_finance_book(tenant_id,id,legal_entity_id,name,standard,base_currency) VALUES(?,?,?,'QA账套','CAS','CNY')").params(tenantId,book,legalEntityId).update();
            jdbc.sql("INSERT INTO plugin_finance_member(tenant_id,book_id,user_id,username,role,enabled) VALUES(?,?,?,'maker','OWNER',true)").params(tenantId,book,accounts.get("maker")).update();
            jdbc.sql("INSERT INTO plugin_finance_year(tenant_id,book_id,id,year,status) VALUES(?,?,?,2026,'OPEN')").params(tenantId,book,yearId).update();
            byte[] origBytes="tax-reg-evidence".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String origSha=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(origBytes));
            jdbc.sql("INSERT INTO plugin_finance_original(tenant_id,book_id,year_id,id,filename,media_type,content,size_bytes,sha256,created_by) VALUES(?,?,?,?,'tax.pdf','application/pdf',?,?,?,?)")
                .params(tenantId,book,yearId,origId,origBytes,origBytes.length,origSha,accounts.get("maker")).update();

            UUID taxEntityId;
            try(var c=source.getConnection()) {
                c.setAutoCommit(false);
                String whoJson;
                try(var s=c.prepareStatement("SELECT plugin_finance_journal_person_identity(?,?,true)::text")) {
                    s.setObject(1,tenantId);s.setObject(2,accounts.get("maker"));
                    try(var rs=s.executeQuery()) { rs.next(); whoJson=rs.getString(1); }
                }
                Map<String,Object> content=new LinkedHashMap<>();
                content.put("displayName","QA扣缴义务人");
                content.put("entityKind","LEGAL_ENTITY");
                content.put("registrationTypeDeclaration","企业纳税人");
                content.put("residencyDeclaration","RESIDENT");
                content.put("establishedOn","2026-01-01");
                content.put("cancelledOn",null);
                content.put("authorityCodeDeclaration","11100000000");
                content.put("administrativeDivisionDeclaration","110101");
                content.put("organizationVerificationBasis","组织人工核验营业执照");
                Map<String,Object> regEv=new LinkedHashMap<>();
                regEv.put("yearId",yearId.toString());regEv.put("originalId",origId.toString());regEv.put("expectedVersion","1");
                regEv.put("expectedSha256",origSha);regEv.put("sourceType","ELECTRONIC_ORIGINAL");regEv.put("originalLocation",null);
                content.put("registrationEvidence",regEv);
                content.put("singleTaxpayerConfirmed",true);
                content.put("allKnownRegistrationIdentifiersDeclared",true);
                content.put("organizationReviewedNotGovernmentVerified",true);
                Map<String,Object> payload=new LinkedHashMap<>();
                payload.put("schemaVersion","tax-entity/1");
                payload.put("idempotencyKey","tax-create-key-0001");
                payload.put("operationId",UUID.randomUUID().toString());
                payload.put("reason","登记扣缴税务主体");
                payload.put("identity",Map.of("country","CN","canonicalTaxRegistrationNo","91110000123456789A","verification","ORGANIZATION_DECLARED_NOT_GOVERNMENT_VERIFIED"));
                payload.put("content",content);
                String resJson;
                try(var s=c.prepareStatement("SELECT plugin_finance_tax_entity_command(?,?,?,?::jsonb,'CREATE',NULL,NULL,?::jsonb)::text")) {
                    s.setObject(1,tenantId);s.setObject(2,legalEntityId);s.setObject(3,book);s.setString(4,whoJson);s.setString(5,mapper.writeValueAsString(payload));
                    try(var rs=s.executeQuery()) { rs.next(); resJson=rs.getString(1); }
                }
                taxEntityId=UUID.fromString(mapper.readValue(resJson,Map.class).get("taxEntityId").toString());
                try(var s=c.prepareStatement("INSERT INTO plugin_finance_tax_entity_request(actor_id,request_key,tenant_id,legal_entity_id,home_book_id,tax_entity_id,revision_id,receipt_id,route,payload_hash) SELECT (actor->>'accountId')::uuid,'tax-create-key-0001',tenant_id,legal_entity_id,home_book_id,tax_entity_id,revision_id,id,route,payload_hash FROM plugin_finance_tax_entity_command_receipt WHERE tenant_id=? AND tax_entity_id=?")) {
                    s.setObject(1,tenantId);s.setObject(2,taxEntityId);s.executeUpdate();
                }
                c.commit();
            }

            YearScope pgScope=new YearScope(tenantId,taxEntityId,2026);
            jdbc.sql("SELECT set_config('app.tenant_id', ?, false)").param(tenantId.toString()).query(String.class).single();

            // Populate and verify tables 1–3: employee, employment (with FK to employee & tax_entity_registry), and rule_pack
            jdbc.sql("INSERT INTO plugin_finance_payroll_employee(tenant_id,id,employee_number,identity_secret_ref,identity_digest) VALUES(?,?,'EMP-001',?,?)")
                .params(tenantId,employee,UUID.randomUUID(),"d".repeat(64)).update();
            UUID employmentId=UUID.randomUUID();
            jdbc.sql("INSERT INTO plugin_finance_payroll_employment(tenant_id,id,employee_id,tax_entity_id,legal_entity_id,starts_on,ends_on,revision,residency,evidence_id,evidence_digest) VALUES(?,?,?,?,?,DATE '2026-01-01',NULL,1,'RESIDENT',?,?)")
                .params(tenantId,employmentId,employee,taxEntityId,legalEntityId,origId,"e".repeat(64)).update();
            UUID rulePackId=UUID.randomUUID();
            jdbc.sql("INSERT INTO plugin_finance_payroll_rule_pack(tenant_id,id,version,kind,region,effective_from,effective_through,body,digest,source_evidence,approval_evidence,approved_by_person,approved_at) VALUES(?,?,'2026.1','RESIDENT_WAGE',NULL,DATE '2026-01-01',DATE '2026-12-31','{\"monthlyBasicDeduction\":\"5000.00\"}'::jsonb,?,?,?,?,'2026-01-01T00:00:00Z'::timestamptz)")
                .params(tenantId,rulePackId,"f".repeat(64),origId,UUID.randomUUID(),dbActors.get("approver").person()).update();
            // Verify SOCIAL rule pack requires non-null region CHECK constraint
            assertThrows(Exception.class,()->jdbc.sql("INSERT INTO plugin_finance_payroll_rule_pack(tenant_id,id,version,kind,region,effective_from,effective_through,body,digest,source_evidence,approval_evidence,approved_by_person,approved_at) VALUES(?,?,'2026.1','SOCIAL',NULL,DATE '2026-01-01',DATE '2026-12-31','{}'::jsonb,?,?,?,?,'2026-01-01T00:00:00Z'::timestamptz)")
                .params(tenantId,UUID.randomUUID(),"1".repeat(64),origId,UUID.randomUUID(),dbActors.get("approver").person()).update());

            // Exercise PayrollJdbcStore (load, replay, save) across tables 4–7 (year, receipt, event, outbox) for Jan + Feb cumulative tax
            var pgStore=new PayrollJdbcStore(jdbc,new org.springframework.jdbc.datasource.DataSourceTransactionManager(source),mapper);
            var pgService=new PayrollService((p,s,b,perm,emps)->dbActors.get(p),new Evidence(){
                public RulePack rule(YearScope s,UUID id,LocalDate date){return PayrollCalculationTest.rule();}
                public ExceptionEvidence exception(YearScope s,UUID emp,UUID ref){throw new IllegalArgumentException("NOT_APPROVED");}
                public String validateLine(YearScope s,Draft d,Line l){return "a".repeat(64);}
                public Cumulative opening(YearScope s,UUID emp,LocalDate date){return PayrollCalculationTest.input("0","0");}
                public String dependencies(YearScope s,Draft d){return "b".repeat(64);}
                public Fact fact(YearScope s,Batch b,FactKind kind,UUID ref){
                    BigDecimal amount=b.computed().stream().map(c->c.tax().currentWithholding()).reduce(BigDecimal.ZERO,BigDecimal::add);
                    return new Fact(kind,ref,"c".repeat(64),amount);
                }
            },pgStore);

            Draft janDraft=draft(1);Command createCmd=command(0);
            Batch created=pgService.create("maker",pgScope,createCmd,janDraft);
            assertEquals(created,pgService.create("maker",pgScope,createCmd,janDraft));
            Draft mutated=new Draft(janDraft.id(),janDraft.book(),janDraft.payDate(),janDraft.kind(),null,"tampered",janDraft.rulePack(),janDraft.lines());
            assertThrows(IllegalArgumentException.class,()->pgService.create("maker",pgScope,createCmd,mutated));
            Batch calc=pgService.act("maker",pgScope,janDraft.id(),command(created.version()),Action.CALCULATE);
            Batch rev=pgService.act("reviewer",pgScope,janDraft.id(),command(calc.version()),Action.REVIEW);
            Batch app=pgService.act("approver",pgScope,janDraft.id(),command(rev.version()),Action.APPROVE);
            Batch locked=pgService.act("approver",pgScope,janDraft.id(),command(app.version()),Action.LOCK);
            Batch withFact=pgService.attachFact("verifier",pgScope,janDraft.id(),command(locked.version()),FactKind.WITHHOLDING_CONFIRMED,UUID.randomUUID());
            assertEquals(State.LOCKED,withFact.state());
            assertEquals(withFact,pgService.read("maker",pgScope,janDraft.id()));

            // Second month (Feb) loads Jan locked batch from PostgreSQL and computes cumulative tax
            Draft febDraft=draft(2);
            Batch febCreated=pgService.create("maker",pgScope,command(0),febDraft);
            Batch febCalc=pgService.act("maker",pgScope,febDraft.id(),command(febCreated.version()),Action.CALCULATE);
            assertEquals(new BigDecimal("150.00"),febCalc.computed().getFirst().tax().currentWithholding());
            assertEquals(new BigDecimal("300.00"),febCalc.computed().getFirst().tax().cumulativeTax());

            jdbc.sql("SELECT set_config('app.tenant_id', ?, false)").param(tenantId.toString()).query(String.class).single();
            assertEquals(8L,jdbc.sql("SELECT count(*) FROM plugin_finance_payroll_event WHERE tenant_id=? AND tax_entity_id=? AND tax_year=?").params(tenantId,taxEntityId,2026).query(Long.class).single());
            assertEquals(8L,jdbc.sql("SELECT count(*) FROM plugin_finance_payroll_outbox WHERE tenant_id=? AND tax_entity_id=? AND tax_year=? AND status='PENDING'").params(tenantId,taxEntityId,2026).query(Long.class).single());

            // Verify immutability triggers on all 4 append-only tables
            assertThrows(Exception.class,()->jdbc.sql("DELETE FROM plugin_finance_payroll_event WHERE tenant_id=?").param(tenantId).update());
            assertThrows(Exception.class,()->jdbc.sql("UPDATE plugin_finance_payroll_receipt SET digest='00' WHERE tenant_id=?").param(tenantId).update());
            assertThrows(Exception.class,()->jdbc.sql("DELETE FROM plugin_finance_payroll_employment WHERE tenant_id=?").param(tenantId).update());
            assertThrows(Exception.class,()->jdbc.sql("DELETE FROM plugin_finance_payroll_rule_pack WHERE tenant_id=?").param(tenantId).update());
            assertEquals(7L,jdbc.sql("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=? AND c.relname LIKE 'plugin_finance_payroll_%' AND c.relrowsecurity AND c.relforcerowsecurity").param(schema).query(Long.class).single());

            // Verify real tenant RLS enforcement across all 7 tables under a NOSUPERUSER NOBYPASSRLS role
            List<String> allTables=List.of(
                "plugin_finance_payroll_employee",
                "plugin_finance_payroll_employment",
                "plugin_finance_payroll_rule_pack",
                "plugin_finance_payroll_year",
                "plugin_finance_payroll_receipt",
                "plugin_finance_payroll_event",
                "plugin_finance_payroll_outbox"
            );
            UUID foreignTenant=UUID.randomUUID();
            try(var c=source.getConnection()) {
                c.setAutoCommit(false);
                try(var st=c.createStatement()) {
                    st.execute("SET LOCAL ROLE "+rlsRole);
                    try(var ps=c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                        ps.setString(1,tenantId.toString());ps.executeQuery();
                    }
                    for(String t:allTables) {
                        try(var rs=st.executeQuery("SELECT count(*) FROM "+t)) {
                            rs.next();
                            assertTrue(rs.getLong(1)>=1L,"Expected visible rows for own tenant in "+t);
                        }
                    }
                    try(var ps=c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                        ps.setString(1,foreignTenant.toString());ps.executeQuery();
                    }
                    for(String t:allTables) {
                        try(var rs=st.executeQuery("SELECT count(*) FROM "+t)) {
                            rs.next();
                            assertEquals(0L,rs.getLong(1),"Expected 0 rows for foreign tenant in "+t);
                        }
                    }
                    try(var ps=c.prepareStatement("SELECT set_config('app.tenant_id', '', true)")) {
                        ps.executeQuery();
                    }
                    for(String t:allTables) {
                        try(var rs=st.executeQuery("SELECT count(*) FROM "+t)) {
                            rs.next();
                            assertEquals(0L,rs.getLong(1),"Expected 0 rows when app.tenant_id is empty in "+t);
                        }
                    }
                    try(var ps=c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                        ps.setString(1,foreignTenant.toString());ps.executeQuery();
                    }
                    var rlsWriteEx=assertThrows(java.sql.SQLException.class,()->{
                        try(var ps=c.prepareStatement("INSERT INTO plugin_finance_payroll_employee(tenant_id,id,employee_number,identity_secret_ref,identity_digest) VALUES(?,?,'EMP-RLS',?,?)")) {
                            ps.setObject(1,tenantId);ps.setObject(2,UUID.randomUUID());ps.setObject(3,UUID.randomUUID());ps.setString(4,"9".repeat(64));
                            ps.executeUpdate();
                        }
                    });
                    assertEquals("42501",rlsWriteEx.getSQLState());
                } finally {
                    c.rollback();
                }
            }
        } finally {
            adminJdbc.sql("DROP SCHEMA "+schema+" CASCADE").update();
            adminJdbc.sql("DROP ROLE IF EXISTS "+rlsRole).update();
        }
    }
    static final class Memory implements Store {
        final tools.jackson.databind.ObjectMapper mapper=new tools.jackson.databind.ObjectMapper();
        String rawYearJson;
        final Map<String,String> digests=new HashMap<>();final Map<String,String> receipts=new HashMap<>();
        public String digest(Object v){
            try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsString(v).getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
            catch(Exception e){ throw new IllegalStateException(e); }
        }
        public <T>T transaction(YearScope s,Function<Tx,T> work){return work.apply(new Tx(){
            public YearData load(){return rawYearJson==null?new YearData(Map.of()):mapper.readValue(rawYearJson,YearData.class);}
            public Batch replay(String k,String d){if(digests.containsKey(k)&&!digests.get(k).equals(d))throw new IllegalArgumentException("CONFLICT");return receipts.containsKey(k)?mapper.readValue(receipts.get(k),Batch.class):null;}
            public void save(YearData next,String key,String hash,Batch result,Actor actor,String action){rawYearJson=mapper.writeValueAsString(next);digests.put(key,hash);receipts.put(key,mapper.writeValueAsString(result));}
        });}
    }
}
