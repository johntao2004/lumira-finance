package com.lumira.finance.assets;

import static com.lumira.finance.assets.AssetModel.*;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/** All calls use the caller's single JDBC transaction; no hidden connection or network side effects. */
public final class AssetRepository {
    private final ObjectMapper json;
    public AssetRepository(ObjectMapper json) { this.json=Objects.requireNonNull(json); }
    public String encode(Object value) throws Exception { return json.writeValueAsString(value); }
    public <T> T decode(String value, Class<T> type) throws Exception { return json.readValue(value,type); }
    public String hash(Object value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(value).getBytes(StandardCharsets.UTF_8)));
    }
    static PreparedStatement statement(Connection c,String sql,Object... args) throws SQLException {
        PreparedStatement s=c.prepareStatement(sql);
        for(int i=0;i<args.length;i++) s.setObject(i+1,args[i]);
        return s;
    }
    static int update(Connection c,String sql,Object... args) throws SQLException {
        try(PreparedStatement s=statement(c,sql,args)) { return s.executeUpdate(); }
    }
    public Card card(Connection c, Scope scope, UUID id, boolean lock) throws Exception {
        try(var s=statement(c,"SELECT snapshot::text FROM plugin_finance_asset_card WHERE tenant_id=? AND book_id=? AND id=?"+(lock?" FOR UPDATE":""),scope.tenant(),scope.book(),id);var rs=s.executeQuery()) {
            if(!rs.next()) throw fail("ASSET_SCOPE_DENIED"); return decode(rs.getString(1),Card.class);
        }
    }
    public StoredPlan plan(Connection c,Scope scope,UUID id,boolean lock) throws Exception {
        try(var s=statement(c,"SELECT payload::text,digest,status,approver::text,journal_id,version FROM plugin_finance_asset_plan WHERE tenant_id=? AND book_id=? AND id=?"+(lock?" FOR UPDATE":""),scope.tenant(),scope.book(),id);var rs=s.executeQuery()) {
            if(!rs.next()) throw fail("PLAN_SCOPE_DENIED");
            Plan p=decode(rs.getString(1),Plan.class);
            if(!hash(p).equals(rs.getString(2))) throw fail("PLAN_DIGEST_CORRUPT");
            return new StoredPlan(p,rs.getString(2),rs.getString(3),rs.getString(4)==null?null:decode(rs.getString(4),Actor.class),(UUID)rs.getObject(5),rs.getLong(6));
        }
    }
    public Receipt replay(Connection c, Context ctx,String action,String resource,String key,String digest) throws Exception {
        // Guard holds the book lock: same-book concurrent requests serialize; PK guards other scopes.
        try(var s=statement(c,"SELECT digest,result::text FROM plugin_finance_asset_request WHERE tenant_id=? AND client_id=? AND operation=? AND resource=? AND request_key=?",ctx.scope().tenant(),ctx.client(),action,resource,key);var rs=s.executeQuery()) {
            if(!rs.next()) return null;
            if(!digest.equals(rs.getString(1))) throw fail("IDEMPOTENCY_CONFLICT");
            return decode(rs.getString(2),Receipt.class);
        }
    }
    public void receipt(Connection c,Context ctx,String action,String resource,String key,String digest,Receipt result) throws Exception {
        update(c,"INSERT INTO plugin_finance_asset_request VALUES(?,?,?,?,?,?,?::jsonb)",ctx.scope().tenant(),ctx.client(),action,resource,key,digest,encode(result));
    }
    public void insert(Connection c,Plan p) throws Exception {
        update(c,"INSERT INTO plugin_finance_asset_plan(tenant_id,book_id,year_id,id,business_key,digest,accounting_date,kind,status,payload,reverses) VALUES(?,?,?,?,?,?,?,?,'SUBMITTED',?::jsonb,?)",p.scope().tenant(),p.scope().book(),p.scope().year(),p.id(),p.businessKey(),hash(p),p.scope().date(),p.kind(),encode(p),p.reverses());
        for(Change ch:p.changes()) update(c,"INSERT INTO plugin_finance_asset_reservation VALUES(?,?,?,?)",p.scope().tenant(),p.scope().book(),ch.after().id(),p.id());
    }
    public void transition(Connection c,StoredPlan old,String status,Actor approver,UUID journal) throws Exception {
        if(update(c,"UPDATE plugin_finance_asset_plan SET status=?,approver=?::jsonb,journal_id=?,version=version+1 WHERE tenant_id=? AND book_id=? AND id=? AND version=?",status,approver==null?null:encode(approver),journal,old.plan().scope().tenant(),old.plan().scope().book(),old.plan().id(),old.version())!=1) throw fail("VERSION_CONFLICT");
    }
    public void release(Connection c,Plan p) throws Exception {
        update(c,"DELETE FROM plugin_finance_asset_reservation WHERE tenant_id=? AND book_id=? AND plan_id=?",p.scope().tenant(),p.scope().book(),p.id());
    }
    public void apply(Connection c,Plan p) throws Exception {
        for(Change x:p.changes()) {
            Card n=x.after();
            if(x.before()==null) update(c,"INSERT INTO plugin_finance_asset_card VALUES(?,?,?,?,?,?,?,?::jsonb)",p.scope().tenant(),p.scope().book(),n.id(),n.version(),n.status(),n.cost(),n.accumulated(),encode(n));
            else if(update(c,"UPDATE plugin_finance_asset_card SET version=?,status=?,cost=?,accumulated=?,snapshot=?::jsonb WHERE tenant_id=? AND book_id=? AND id=? AND version=?",n.version(),n.status(),n.cost(),n.accumulated(),encode(n),p.scope().tenant(),p.scope().book(),n.id(),x.before().version())!=1) throw fail("ASSET_VERSION_CONFLICT");
            update(c,"INSERT INTO plugin_finance_asset_event(tenant_id,book_id,asset_id,plan_id,asset_version,kind,accounting_period,snapshot) VALUES(?,?,?,?,?,?,?,?::jsonb)",p.scope().tenant(),p.scope().book(),n.id(),p.id(),n.version(),p.kind(),p.kind().equals("DEPRECIATE")?n.lastPeriod():null,encode(x));
        }
    }
    public void audit(Connection c,Context ctx,Plan p,String action) throws Exception {
        update(c,"INSERT INTO plugin_finance_asset_audit(id,tenant_id,book_id,plan_id,action,actor,digest) VALUES(?,?,?,?,?,?::jsonb,?)",UUID.randomUUID(),ctx.scope().tenant(),ctx.scope().book(),p.id(),action,encode(ctx.actor()),hash(p));
    }
    public void verifyPosting(Connection c,Plan p,UUID journal) throws Exception {
        if(journal==null) throw fail("ACTUAL_POSTING_REQUIRED");
        try(var s=statement(c,"SELECT 1 FROM plugin_finance_posting p JOIN plugin_finance_journal j ON (j.tenant_id,j.book_id,j.year_id,j.id)=(p.tenant_id,p.book_id,p.year_id,p.journal_id) WHERE p.tenant_id=? AND p.book_id=? AND p.year_id=? AND p.journal_id=? AND j.status='POSTED' AND j.date=? AND j.period_id=?",p.scope().tenant(),p.scope().book(),p.scope().year(),journal,p.scope().date(),p.scope().period());var rs=s.executeQuery()) {
            if(!rs.next()) throw fail("ACTUAL_POSTING_REQUIRED");
        }
        List<Line> actual=new ArrayList<>();
        try(var s=statement(c,"SELECT account_id,debit,credit FROM plugin_finance_line WHERE tenant_id=? AND book_id=? AND year_id=? AND journal_id=? ORDER BY line_no",p.scope().tenant(),p.scope().book(),p.scope().year(),journal);var rs=s.executeQuery()) {
            while(rs.next()) actual.add(new Line((UUID)rs.getObject(1),rs.getBigDecimal(2).setScale(8),rs.getBigDecimal(3).setScale(8)));
        }
        if(!actual.equals(p.lines())) throw fail("POSTING_LINES_MISMATCH");
    }
    public List<Card> cards(Connection c,Scope scope,UUID after,int limit) throws Exception {
        if(limit<1 || limit>100) throw fail("PAGE_LIMIT");
        List<Card> result=new ArrayList<>();
        try(var s=statement(c,"SELECT snapshot::text FROM plugin_finance_asset_card WHERE tenant_id=? AND book_id=? AND (?::uuid IS NULL OR id>?::uuid) ORDER BY id LIMIT ?",scope.tenant(),scope.book(),after,after,limit);var rs=s.executeQuery()) {
            while(rs.next()) result.add(decode(rs.getString(1),Card.class));
        }
        return List.copyOf(result);
    }
    public void chronological(Connection c,Scope scope,UUID id) throws Exception {
        try(var s=statement(c,"SELECT max(p.accounting_date) FROM plugin_finance_asset_event e JOIN plugin_finance_asset_plan p ON (p.tenant_id,p.book_id,p.id)=(e.tenant_id,e.book_id,e.plan_id) WHERE e.tenant_id=? AND e.book_id=? AND e.asset_id=?",scope.tenant(),scope.book(),id);var rs=s.executeQuery()) {
            if(rs.next() && rs.getDate(1)!=null && rs.getDate(1).toLocalDate().isAfter(scope.date())) throw fail("ASSET_BACKDATE_REQUIRES_CORRECTION");
        }
    }
    public List<Card> asOfCards(Connection c,Scope scope) throws Exception {
        List<Card> result=new ArrayList<>();
        try(var s=statement(c,"SELECT DISTINCT ON (e.asset_id) (e.snapshot->'after')::text FROM plugin_finance_asset_event e JOIN plugin_finance_asset_plan p ON (p.tenant_id,p.book_id,p.id)=(e.tenant_id,e.book_id,e.plan_id) WHERE e.tenant_id=? AND e.book_id=? AND p.accounting_date<=? ORDER BY e.asset_id,e.asset_version DESC",scope.tenant(),scope.book(),scope.date());var rs=s.executeQuery()) {
            while(rs.next()) result.add(decode(rs.getString(1),Card.class));
        }
        return List.copyOf(result);
    }
}
