package com.lumira.finance;

import static com.lumira.finance.assets.AssetModel.*;
import static org.junit.jupiter.api.Assertions.*;
import com.lumira.finance.assets.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Candidate real-PG transaction tests; root must run serially against the explicit QA database.
 * Authentication/evidence approval are synthetic adapters. Not live security/browser acceptance. */
class FinanceAssetsPostgresTest {
    static AssetRepository repo;
    static AssetService service;
    static AssetService postedService;
    static final UUID aliasAccount=UUID.randomUUID();
    static final AtomicBoolean failReservation=new AtomicBoolean();
    @BeforeAll static void setup() throws Exception {
        FinancePostgresTest.setup();
        String migration=System.getProperty("asset.migration.candidate", "../migrations/V21__assets_subledger.sql");
        if(migration==null || !Files.isRegularFile(Path.of(migration))) throw new IllegalStateException("Root must supply -Dasset.migration.candidate=<absolute candidate SQL path>");
        try(var c=FinancePostgresTest.source.getConnection()) {
            c.createStatement().execute(Files.readString(Path.of(migration)));
            c.createStatement().execute("CREATE TABLE asset_test_quota(source_id uuid PRIMARY KEY, plan_id uuid NOT NULL, amount numeric(28,8) NOT NULL, consumed boolean NOT NULL DEFAULT false)");
        }
        repo=new AssetRepository(new ObjectMapper());
        AssetPorts.Guard guard=new AssetPorts.Guard() {
            public Context enter(Connection c,String principal,Scope s,String action,UUID source) throws Exception {
                UUID user=principal.equals("maker-alias")?aliasAccount:FinancePostgresTest.users.get(principal);
                if(user==null || principal.equals("outsider")) throw new IllegalArgumentException("DENIED");
                if(!c.isReadOnly()) {
                    try(var q=c.prepareStatement("SELECT id FROM plugin_finance_book WHERE tenant_id=? AND id=? FOR UPDATE")) {
                        q.setObject(1,s.tenant());q.setObject(2,s.book());try(var r=q.executeQuery()){if(!r.next())throw new IllegalArgumentException("DENIED");}
                    }
                } else {
                    try(var q=c.prepareStatement("SELECT id FROM plugin_finance_book WHERE tenant_id=? AND id=?")) {
                        q.setObject(1,s.tenant());q.setObject(2,s.book());try(var r=q.executeQuery()){if(!r.next())throw new IllegalArgumentException("DENIED");}
                    }
                }
                return new Context(s,new Actor(user,principal.equals("maker-alias")?FinancePostgresTest.users.get("maker"):user),"TEST_WEB");
            }
            public void validate(Connection c,Context x,Plan p,Actor approver) {
                if(approver!=null) AssetRules.independent(p.maker(),approver);
            }
        };
        AssetPorts.Evidence evidence=new AssetPorts.Evidence() {
            public void reserve(Connection c,Context x,UUID source,UUID plan,BigDecimal amount) throws Exception {
                try(var q=c.prepareStatement("INSERT INTO asset_test_quota(source_id,plan_id,amount) VALUES(?,?,?)")) {q.setObject(1,source);q.setObject(2,plan);q.setBigDecimal(3,amount);q.executeUpdate();}
                if(failReservation.get()) throw new IllegalStateException("INJECTED_FAILURE_AFTER_QUOTA_WRITE");
            }
            public void consume(Connection c,Context x,UUID source,UUID plan,BigDecimal amount) throws Exception {
                try(var q=c.prepareStatement("UPDATE asset_test_quota SET consumed=true WHERE source_id=? AND plan_id=? AND amount=? AND NOT consumed")) {q.setObject(1,source);q.setObject(2,plan);q.setBigDecimal(3,amount);if(q.executeUpdate()!=1)throw new IllegalStateException("QUOTA_STALE");}
            }
            public void release(Connection c,Context x,UUID source,UUID plan) throws Exception {
                try(var q=c.prepareStatement("DELETE FROM asset_test_quota WHERE source_id=? AND plan_id=? AND NOT consumed")) {q.setObject(1,source);q.setObject(2,plan);q.executeUpdate();}
            }
            public void reverse(Connection c,Context x,UUID original,UUID reversal) throws Exception {
                try(var q=c.prepareStatement("UPDATE asset_test_quota SET consumed=false WHERE plan_id=? AND consumed")) {
                    q.setObject(1,original);
                    if(q.executeUpdate()!=1) throw new IllegalStateException("QUOTA_REVERSAL_MISSING");
                }
            }
        };
        service=new AssetService(FinancePostgresTest.source,repo,guard,evidence,
            (c,x,p)->new PostReceipt(UUID.randomUUID()), // A false posting receipt MUST be rejected.
            (c,x,a)->{throw new IllegalStateException("LEDGER_NOT_WIRED");});
        AssetPorts.Posting realPosting=(c,x,stored)->{
            Plan plan=stored.plan();
            Scope s=plan.scope();
            UUID jid=UUID.randomUUID();
            try(var q=c.prepareStatement("INSERT INTO plugin_finance_journal(tenant_id,book_id,year_id,id,period_id,date,summary,type,status,version,created_by) VALUES(?,?,?,?,?,?,?,'NORMAL','DRAFT',1,?)")) {
                q.setObject(1,s.tenant());q.setObject(2,s.book());q.setObject(3,s.year());q.setObject(4,jid);
                q.setObject(5,s.period());q.setObject(6,s.date());q.setString(7,"AS:"+plan.businessKey());q.setObject(8,plan.maker().account());
                q.executeUpdate();
            }
            int lineNo=1;
            for(Line l:plan.lines()) {
                try(var q=c.prepareStatement("INSERT INTO plugin_finance_line(tenant_id,book_id,year_id,journal_id,line_no,account_id,debit,credit,memo) VALUES(?,?,?,?,?,?,?,?,?)")) {
                    q.setObject(1,s.tenant());q.setObject(2,s.book());q.setObject(3,s.year());q.setObject(4,jid);
                    q.setInt(5,lineNo++);q.setObject(6,l.account());q.setBigDecimal(7,l.debit());q.setBigDecimal(8,l.credit());q.setString(9,plan.kind());
                    q.executeUpdate();
                }
            }
            try(var q=c.prepareStatement("UPDATE plugin_finance_journal SET status='APPROVED',version=2,approved_by=?,approved_version=2 WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?")) {
                q.setObject(1,stored.approver().account());q.setObject(2,s.tenant());q.setObject(3,s.book());q.setObject(4,s.year());q.setObject(5,jid);
                q.executeUpdate();
            }
            long seq;
            try(var q=c.prepareStatement("UPDATE plugin_finance_year_guard SET posting_seq=posting_seq+1 WHERE tenant_id=? AND book_id=? AND year_id=? RETURNING posting_seq")) {
                q.setObject(1,s.tenant());q.setObject(2,s.book());q.setObject(3,s.year());
                try(var rs=q.executeQuery()) { rs.next(); seq=rs.getLong(1); }
            }
            try(var q=c.prepareStatement("INSERT INTO plugin_finance_posting(tenant_id,book_id,year_id,journal_id,journal_version,posting_seq,digest,posted_by) VALUES(?,?,?,?,2,?,?,?)")) {
                q.setObject(1,s.tenant());q.setObject(2,s.book());q.setObject(3,s.year());q.setObject(4,jid);
                q.setLong(5,seq);q.setString(6,stored.digest());q.setObject(7,x.actor().account());
                q.executeUpdate();
            }
            try(var q=c.prepareStatement("UPDATE plugin_finance_journal SET status='POSTED',version=3,number=? WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?")) {
                q.setLong(1,seq);q.setObject(2,s.tenant());q.setObject(3,s.book());q.setObject(4,s.year());q.setObject(5,jid);
                q.executeUpdate();
            }
            return new PostReceipt(jid);
        };
        AssetPorts.Ledger realLedger=(c,x,accountIds)->{
            Scope s=x.scope();
            String wm;
            try(var q=c.prepareStatement("SELECT posting_seq FROM plugin_finance_year_guard WHERE tenant_id=? AND book_id=? AND year_id=?")) {
                q.setObject(1,s.tenant());q.setObject(2,s.book());q.setObject(3,s.year());
                try(var rs=q.executeQuery()) { rs.next(); wm="seq:"+rs.getLong(1); }
            }
            List<AssetPorts.LedgerBalance> out=new ArrayList<>();
            for(UUID acc:accountIds) {
                try(var q=c.prepareStatement("SELECT COALESCE(sum(l.debit - l.credit), 0)::numeric(28,8) FROM plugin_finance_line l JOIN plugin_finance_journal j ON (j.tenant_id,j.book_id,j.year_id,j.id)=(l.tenant_id,l.book_id,l.year_id,l.journal_id) WHERE l.tenant_id=? AND l.book_id=? AND l.year_id=? AND l.account_id=? AND j.status='POSTED' AND j.date<=?")) {
                    q.setObject(1,s.tenant());q.setObject(2,s.book());q.setObject(3,s.year());q.setObject(4,acc);q.setObject(5,s.date());
                    try(var rs=q.executeQuery()) {
                        rs.next();
                        out.add(new AssetPorts.LedgerBalance(acc,rs.getBigDecimal(1).setScale(8),wm));
                    }
                }
            }
            return out;
        };
        postedService=new AssetService(FinancePostgresTest.source,repo,guard,evidence,realPosting,realLedger);
    }
    @AfterAll static void cleanup(){FinancePostgresTest.cleanup();}
    record Fixture(Scope scope,AssetService.Acquire input) {}
    static Fixture fixture() {
        var f=FinancePostgresTest.fixture("CAS");
        UUID tenant=FinancePostgresTest.jdbc.sql("SELECT tenant_id FROM plugin_finance_book WHERE id=?").param(f.book()).query(UUID.class).single();
        UUID period=FinancePostgresTest.jdbc.sql("SELECT id FROM plugin_finance_period WHERE book_id=? AND year_id=? ORDER BY month LIMIT 1").params(f.book(),f.year()).query(UUID.class).single();
        Scope scope=new Scope(tenant,f.book(),f.year(),period,LocalDate.of(2026,1,31));
        var policy=new InputPolicy("CAS","FIXED","STRAIGHT_LINE","2026-01",3,"0",UUID.randomUUID());
        var card=new InputCard("QA equipment","EQUIPMENT",LocalDate.of(2026,1,1),LocalDate.of(2026,1,1),"QA",UUID.randomUUID(),policy,new Accounts(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID()),"100");
        return new Fixture(scope,new AssetService.Acquire(UUID.randomUUID(),"100","0",null,f.capital(),"PURCHASE",List.of(card),"Synthetic approved purchase"));
    }
    static UUID createAccount(UUID book,UUID year,String code,String name,String side,String category) {
        return (UUID)FinancePostgresTest.write("maker",book,year,"accounts",null,Map.of("code",code,"name",name,"normalSide",side,"category",category)).get("id");
    }
    static Receipt approveAndPost(Scope s,UUID planId) throws Exception {
        postedService.transition("reviewer",s,"approve",key(),new AssetService.Transition(planId,1,"Approve plan"));
        return postedService.transition("maker",s,"post",key(),new AssetService.Transition(planId,2,"Post plan"));
    }
    static String key(){return UUID.randomUUID().toString();}
    static long count(String table,UUID book) {
        return FinancePostgresTest.jdbc.sql("SELECT count(*) FROM "+table+" WHERE book_id=?").param(book).query(Long.class).single();
    }
    @Test void concurrentSameRequestCommitsExactlyOnePlan() throws Exception {
        var f=fixture();String key=key();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->service.acquire("maker",f.scope(),key,f.input()));
            var b=pool.submit(()->service.acquire("maker",f.scope(),key,f.input()));
            assertEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
        }
        assertEquals(1,count("plugin_finance_asset_plan",f.scope().book()));
        assertEquals(0,count("plugin_finance_asset_card",f.scope().book()));
    }
    @Test void newRequestKeyCannotDuplicateSource() throws Exception {
        var f=fixture();service.acquire("maker",f.scope(),key(),f.input());
        assertThrows(Exception.class,()->service.acquire("maker",f.scope(),key(),f.input()));
        assertEquals(1,count("plugin_finance_asset_plan",f.scope().book()));
    }
    @Test void samePersonAliasCannotApproveAndVersionIsChecked() throws Exception {
        var f=fixture();var p=service.acquire("maker",f.scope(),key(),f.input());
        var command=new AssetService.Transition(p.planId(),1,"Review");
        assertThrows(IllegalArgumentException.class,()->service.transition("maker-alias",f.scope(),"approve",key(),command));
        var approved=service.transition("reviewer",f.scope(),"approve",key(),command);
        assertEquals("APPROVED",approved.status());
        assertThrows(IllegalArgumentException.class,()->service.transition("reviewer",f.scope(),"approve",key(),command));
    }
    @Test void quotaFailureRollsBackPlanReservationAuditAndRequest() throws Exception {
        var f=fixture();failReservation.set(true);
        try {assertThrows(IllegalStateException.class,()->service.acquire("maker",f.scope(),key(),f.input()));}
        finally {failReservation.set(false);}
        for(String table:List.of("plugin_finance_asset_plan","plugin_finance_asset_reservation","plugin_finance_asset_audit")) assertEquals(0,count(table,f.scope().book()));
        assertEquals(0,FinancePostgresTest.jdbc.sql("SELECT count(*) FROM asset_test_quota WHERE source_id=?").param(f.input().sourceId()).query(Long.class).single());
        assertEquals("SUBMITTED",service.acquire("maker",f.scope(),key(),f.input()).status());
    }
    @Test void nonexistentPostingReceiptCannotActivateAsset() throws Exception {
        var f=fixture();var p=service.acquire("maker",f.scope(),key(),f.input());
        service.transition("reviewer",f.scope(),"approve",key(),new AssetService.Transition(p.planId(),1,"Review"));
        assertThrows(IllegalArgumentException.class,()->service.transition("maker",f.scope(),"post",key(),new AssetService.Transition(p.planId(),2,"Post")));
        assertEquals(0,count("plugin_finance_asset_card",f.scope().book()));
        try(var c=FinancePostgresTest.source.getConnection()){assertEquals("APPROVED",repo.plan(c,f.scope(),p.planId(),false).status());}
    }
    @Test void replayRechecksAuthority() throws Exception {
        var f=fixture();String k=key();service.acquire("maker",f.scope(),k,f.input());
        assertThrows(IllegalArgumentException.class,()->service.acquire("outsider",f.scope(),k,f.input()));
    }
    @Test void fullLifecycleAcquireDepreciatePeriodUniquenessEstimateCorrectDisposeReverseAndReconcile() throws Exception {
        var base=FinancePostgresTest.fixture("CAS");
        UUID tenant=FinancePostgresTest.jdbc.sql("SELECT tenant_id FROM plugin_finance_book WHERE id=?").param(base.book()).query(UUID.class).single();
        UUID janPeriod=FinancePostgresTest.jdbc.sql("SELECT id FROM plugin_finance_period WHERE book_id=? AND year_id=? AND month=1").params(base.book(),base.year()).query(UUID.class).single();
        UUID febPeriod=FinancePostgresTest.jdbc.sql("SELECT id FROM plugin_finance_period WHERE book_id=? AND year_id=? AND month=2").params(base.book(),base.year()).query(UUID.class).single();
        Scope janScope=new Scope(tenant,base.book(),base.year(),janPeriod,LocalDate.of(2026,1,31));
        Scope febScope=new Scope(tenant,base.book(),base.year(),febPeriod,LocalDate.of(2026,2,28));

        UUID costAcc=createAccount(base.book(),base.year(),"1601","固定资产原值","DEBIT","ASSET");
        UUID accumAcc=createAccount(base.book(),base.year(),"1602","累计折旧","CREDIT","ASSET");
        UUID expAcc=createAccount(base.book(),base.year(),"6602","管理费用折旧","DEBIT","EXPENSE");
        UUID lossAcc=createAccount(base.book(),base.year(),"6711","资产处置损失","DEBIT","EXPENSE");
        UUID gainAcc=createAccount(base.book(),base.year(),"6115","资产处置收益","CREDIT","REVENUE");
        Accounts accounts=new Accounts(costAcc,accumAcc,expAcc);

        // 1. Acquire and post primary asset (100.00, 3 months, 0 residual)
        var policy=new InputPolicy("CAS","FIXED","STRAIGHT_LINE","2026-01",3,"0",UUID.randomUUID());
        var inputCard=new InputCard("数控机床A","EQUIPMENT",LocalDate.of(2026,1,5),LocalDate.of(2026,1,5),"车间一",UUID.randomUUID(),policy,accounts,"100.00");
        var acq=postedService.acquire("maker",janScope,key(),new AssetService.Acquire(UUID.randomUUID(),"100.00","0",null,base.capital(),"PURCHASE",List.of(inputCard),"购入设备"));
        var postedAcq=approveAndPost(janScope,acq.planId());
        assertEquals("POSTED",postedAcq.status());
        assertNotNull(postedAcq.journalId());

        List<Card> cards=postedService.cards("maker",janScope,null,10);
        assertEquals(1,cards.size());
        UUID assetId=cards.getFirst().id();
        assertEquals("IN_USE",cards.getFirst().status());
        assertEquals(new BigDecimal("100.00000000"),cards.getFirst().cost());
        assertEquals(new BigDecimal("0.00000000"),cards.getFirst().accumulated());
        for(var d:postedService.reconcile("maker",janScope)) assertEquals(new BigDecimal("0.00000000"),d.difference());

        // 2. Acquire a second asset and immediately reverse its ACQUIRE plan (verifying quota reversal and REVERSED status)
        var secondInput=new InputCard("测试仪器B","EQUIPMENT",LocalDate.of(2026,1,6),LocalDate.of(2026,1,6),"实验室",UUID.randomUUID(),policy,accounts,"50.00");
        UUID secondSource=UUID.randomUUID();
        var acq2=postedService.acquire("maker",janScope,key(),new AssetService.Acquire(secondSource,"50.00","0",null,base.capital(),"PURCHASE",List.of(secondInput),"购入测试仪器"));
        approveAndPost(janScope,acq2.planId());
        assertEquals(true,FinancePostgresTest.jdbc.sql("SELECT consumed FROM asset_test_quota WHERE source_id=?").param(secondSource).query(Boolean.class).single());
        var revAcq2=postedService.reverse("maker",janScope,key(),new AssetService.Reverse(acq2.planId(),3,"冲销误记购置"));
        approveAndPost(janScope,revAcq2.planId());
        assertEquals(false,FinancePostgresTest.jdbc.sql("SELECT consumed FROM asset_test_quota WHERE source_id=?").param(secondSource).query(Boolean.class).single());
        for(var d:postedService.reconcile("maker",janScope)) assertEquals(new BigDecimal("0.00000000"),d.difference());

        // 3. Depreciate primary asset in 2026-01 (33.33) and verify period uniqueness constraint (SQLSTATE 23505) + immutability triggers
        var dep1=postedService.depreciate("maker",janScope,key(),new AssetService.Depreciate(assetId,1,"计提1月折旧"));
        approveAndPost(janScope,dep1.planId());
        Card afterDep1=postedService.cards("maker",janScope,null,10).stream().filter(c->c.id().equals(assetId)).findFirst().orElseThrow();
        assertEquals(2L,afterDep1.version());
        assertEquals(new BigDecimal("33.33000000"),afterDep1.accumulated());
        assertEquals("2026-01",afterDep1.lastPeriod());
        for(var d:postedService.reconcile("maker",janScope)) assertEquals(new BigDecimal("0.00000000"),d.difference());

        // Verify unique index plugin_finance_asset_period_once rejects duplicate DEPRECIATE event for (tenant, book, asset, '2026-01') with SQLSTATE 23505
        try(var c=FinancePostgresTest.source.getConnection()) {
            var dupEx=assertThrows(java.sql.SQLException.class,()->{
                try(var s=c.prepareStatement("INSERT INTO plugin_finance_asset_event(tenant_id,book_id,asset_id,plan_id,asset_version,kind,accounting_period,snapshot) VALUES(?,?,?,?,99,'DEPRECIATE','2026-01','{}'::jsonb)")) {
                    s.setObject(1,tenant);s.setObject(2,base.book());s.setObject(3,assetId);s.setObject(4,dep1.planId());
                    s.executeUpdate();
                }
            });
            assertEquals("23505",dupEx.getSQLState());
        }
        // Verify immutability triggers on event, audit, request, and posted plan
        assertThrows(Exception.class,()->FinancePostgresTest.jdbc.sql("DELETE FROM plugin_finance_asset_event WHERE book_id=?").param(base.book()).update());
        assertThrows(Exception.class,()->FinancePostgresTest.jdbc.sql("DELETE FROM plugin_finance_asset_audit WHERE book_id=?").param(base.book()).update());
        assertThrows(Exception.class,()->FinancePostgresTest.jdbc.sql("DELETE FROM plugin_finance_asset_request WHERE tenant_id=?").param(tenant).update());
        assertThrows(Exception.class,()->FinancePostgresTest.jdbc.sql("UPDATE plugin_finance_asset_plan SET status='CANCELLED',version=version+1 WHERE id=?").param(dep1.planId()).update());

        // 4. Direct reversal of DEPRECIATE is blocked; linked correctDepreciation (33.33 -> 30.00) succeeds
        assertEquals("LINKED_DEPRECIATION_CORRECTION_REQUIRED",
            assertThrows(IllegalArgumentException.class,()->postedService.reverse("maker",janScope,key(),new AssetService.Reverse(dep1.planId(),3,"直接冲销折旧被禁"))).getMessage());
        var corr=postedService.correctDepreciation("maker",janScope,key(),new AssetService.CorrectDepreciation(dep1.planId(),3,"30.00",UUID.randomUUID(),"更正1月折旧额"));
        approveAndPost(janScope,corr.planId());
        Card afterCorr=postedService.cards("maker",janScope,null,10).stream().filter(c->c.id().equals(assetId)).findFirst().orElseThrow();
        assertEquals(3L,afterCorr.version());
        assertEquals(new BigDecimal("30.00000000"),afterCorr.accumulated());
        for(var d:postedService.reconcile("maker",janScope)) assertEquals(new BigDecimal("0.00000000"),d.difference());

        // 5. Prospective estimate change in 2026-02 (remainingMonths=2, residual=10.00) + 2026-02 depreciation (30.00 -> accumulated 60.00)
        var est=postedService.estimate("maker",febScope,key(),new AssetService.Estimate(assetId,3,2,"10.00",UUID.randomUUID(),"会计估计变更残值10元"));
        approveAndPost(febScope,est.planId());
        var dep2=postedService.depreciate("maker",febScope,key(),new AssetService.Depreciate(assetId,4,"计提2月折旧"));
        approveAndPost(febScope,dep2.planId());
        Card afterDep2=postedService.cards("maker",febScope,null,10).stream().filter(c->c.id().equals(assetId)).findFirst().orElseThrow();
        assertEquals(5L,afterDep2.version());
        assertEquals(new BigDecimal("60.00000000"),afterDep2.accumulated());
        assertEquals("2026-02",afterDep2.lastPeriod());
        for(var d:postedService.reconcile("maker",febScope)) assertEquals(new BigDecimal("0.00000000"),d.difference());

        // 6. Dispose in 2026-02 (proceeds 35.00, net book value 40.00, loss 5.00), then reverse the disposal
        var disp=postedService.dispose("maker",febScope,key(),new AssetService.Dispose(assetId,5,"35.00",base.cash(),lossAcc,gainAcc,UUID.randomUUID(),"出售设备"));
        approveAndPost(febScope,disp.planId());
        Card afterDisp=postedService.cards("maker",febScope,null,10).stream().filter(c->c.id().equals(assetId)).findFirst().orElseThrow();
        assertEquals("DISPOSED",afterDisp.status());
        assertEquals(6L,afterDisp.version());

        var revDisp=postedService.reverse("maker",febScope,key(),new AssetService.Reverse(disp.planId(),3,"冲销误操作处置"));
        approveAndPost(febScope,revDisp.planId());
        Card afterRevDisp=postedService.cards("maker",febScope,null,10).stream().filter(c->c.id().equals(assetId)).findFirst().orElseThrow();
        assertEquals("IN_USE",afterRevDisp.status());
        assertEquals(7L,afterRevDisp.version());
        assertEquals(new BigDecimal("60.00000000"),afterRevDisp.accumulated());
        for(var d:postedService.reconcile("maker",febScope)) assertEquals(new BigDecimal("0.00000000"),d.difference());
    }
}
