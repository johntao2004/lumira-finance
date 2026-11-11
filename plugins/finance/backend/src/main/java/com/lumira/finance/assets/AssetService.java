package com.lumira.finance.assets;

import static com.lumira.finance.assets.AssetModel.*;
import static com.lumira.finance.assets.AssetRules.*;
import javax.sql.DataSource;
import java.sql.Connection;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;

/** Candidate application service. Mandatory adapters are deliberately not auto-registered. */
public final class AssetService {
    private final DataSource data;
    private final AssetRepository repo;
    private final AssetPorts.Guard guard;
    private final AssetPorts.Evidence evidence;
    private final AssetPorts.Posting posting;
    private final AssetPorts.Ledger ledger;
    public AssetService(DataSource data,AssetRepository repo,AssetPorts.Guard guard,
                        AssetPorts.Evidence evidence,AssetPorts.Posting posting,AssetPorts.Ledger ledger) {
        this.data=Objects.requireNonNull(data);this.repo=Objects.requireNonNull(repo);
        this.guard=Objects.requireNonNull(guard);this.evidence=Objects.requireNonNull(evidence);
        this.posting=Objects.requireNonNull(posting);this.ledger=Objects.requireNonNull(ledger);
    }
    public record Acquire(UUID sourceId,String sourceAmount,String expensedAmount,UUID expenseAccount,
                          UUID creditAccount,String sourceKind,List<InputCard> cards,String reason) {}
    public record Depreciate(UUID assetId,long expectedVersion,String reason) {}
    public record Estimate(UUID assetId,long expectedVersion,int remainingMonths,String residual,
                           UUID assessmentId,String reason) {}
    public record Dispose(UUID assetId,long expectedVersion,String proceeds,UUID proceedsAccount,
                          UUID lossAccount,UUID gainAccount,UUID approvalEvidence,String reason) {}
    public record Reverse(UUID originalPlan,long expectedPlanVersion,String reason) {}
    public record CorrectDepreciation(UUID originalPlan,long expectedPlanVersion,String correctedAmount,UUID assessmentId,String reason) {}
    public record Transition(UUID planId,long expectedVersion,String reason) {}
    private interface Work { Receipt run(Connection c,Context ctx) throws Exception; }
    private Receipt command(String principal,Scope s,String action,String resource,String key,Object input,UUID source,Work work) throws Exception {
        if(key==null || !key.matches("[A-Za-z0-9_-]{16,100}")) throw fail("REQUEST_KEY_REQUIRED");
        try(Connection c=data.getConnection()) {
            c.setAutoCommit(false);
            try {
                Context ctx=guard.enter(c,principal,s,action,source);
                if(!s.equals(ctx.scope()) || ctx.actor()==null || ctx.actor().person()==null || ctx.client()==null) throw fail("TRUSTED_CONTEXT_REQUIRED");
                String digest=repo.hash(List.of(s,ctx.actor(),input));
                Receipt old=repo.replay(c,ctx,action,resource,key,digest);
                if(old!=null) {
                    StoredPlan existing=repo.plan(c,s,old.planId(),false);
                    guard.validate(c,ctx,existing.plan(),existing.approver());
                    c.commit();return old;
                }
                Receipt result=work.run(c,ctx);
                repo.receipt(c,ctx,action,resource,key,digest,result);c.commit();return result;
            } catch(Exception e) { c.rollback();throw e; }
        }
    }
    private static String reason(String reason) {
        if(reason==null || reason.isBlank() || reason.length()>1000) throw fail("REASON_REQUIRED");return reason;
    }
    private Receipt propose(Connection c,Context ctx,Plan p) throws Exception {
        for(Change x:p.changes()) if(x.before()!=null) repo.chronological(c,ctx.scope(),x.before().id());
        balanced(p.lines());guard.validate(c,ctx,p,null);repo.insert(c,p);
        if(p.kind().equals("ACQUIRE")) evidence.reserve(c,ctx,p.sourceId(),p.id(),p.sourceAmount());
        repo.audit(c,ctx,p,"SUBMIT");return new Receipt(p.id(),"SUBMITTED",null,1);
    }
    public Receipt acquire(String principal,Scope s,String key,Acquire in) throws Exception {
        return command(principal,s,"acquire",s.book().toString(),key,in,null,(c,ctx)->{
            if(!"PURCHASE".equals(in.sourceKind())) throw fail("CIP_OR_RD_ASSESSMENT_PRODUCER_REQUIRED");
            if(in.sourceId()==null || in.cards()==null || in.cards().isEmpty() || in.cards().size()>100 || in.creditAccount()==null) throw fail("ACQUISITION_INPUT_REQUIRED");
            BigDecimal total=money(in.sourceAmount()),expensed=money(in.expensedAmount()),sum=expensed;
            List<Change> changes=new ArrayList<>();List<Line> lines=new ArrayList<>();
            for(InputCard i:in.cards()) {
                InputPolicy ip=Objects.requireNonNull(i.policy());
                Policy p=new Policy(ip.framework(),ip.kind(),ip.method(),ip.firstPeriod(),ip.months(),money(ip.residual()),ip.assessmentId(),ZERO,0);
                Card card=new Card(UUID.randomUUID(),i.name(),i.classification(),i.acquired(),i.ready(),i.location(),i.custodian(),p,i.accounts(),money(i.cost()),ZERO,ZERO,0,null,1,"IN_USE");
                valid(card);
                if(card.ready().isAfter(s.date())) throw fail("NOT_READY_FOR_RECOGNITION");
                if(YearMonth.parse(p.firstPeriod()).isBefore(YearMonth.from(s.date()))) throw fail("LATE_ACQUISITION_REQUIRES_CORRECTION");
                changes.add(new Change(null,card,null));sum=sum.add(card.cost());lines.add(new Line(card.accounts().cost(),card.cost(),ZERO));
            }
            if(sum.compareTo(total)!=0 || total.signum()==0) throw fail("SPLIT_SOURCE_TOTAL_MISMATCH");
            if(expensed.signum()>0) lines.add(new Line(Objects.requireNonNull(in.expenseAccount()),expensed,ZERO));
            lines.add(new Line(in.creditAccount(),ZERO,total));
            Plan p=new Plan(UUID.randomUUID(),s,"ACQUIRE","ACQUIRE/"+in.sourceId(),in.sourceId(),reason(in.reason()),ctx.actor(),List.copyOf(changes),List.copyOf(lines),null,null,total);
            return propose(c,ctx,p);
        });
    }
    private Card current(Connection c,Scope s,UUID id,long expected) throws Exception {
        Card card=repo.card(c,s,id,true);
        if(card.version()!=expected) throw fail("ASSET_VERSION_CONFLICT");
        if(!card.status().equals("IN_USE")) throw fail("ASSET_INACTIVE");return card;
    }
    public Receipt depreciate(String principal,Scope s,String key,Depreciate in) throws Exception {
        return command(principal,s,"depreciate",in.assetId().toString(),key,in,null,(c,ctx)->{
            Card old=current(c,s,in.assetId(),in.expectedVersion());
            Change change=AssetRules.depreciate(old,YearMonth.from(s.date()).toString());
            Plan p=new Plan(UUID.randomUUID(),s,"DEPRECIATE","DEPRECIATE/"+old.id()+"/"+change.after().lastPeriod(),null,reason(in.reason()),ctx.actor(),List.of(change),depreciationLines(change),null,null,ZERO);
            return propose(c,ctx,p);
        });
    }
    public Receipt estimate(String principal,Scope s,String key,Estimate in) throws Exception {
        return command(principal,s,"estimate",in.assetId().toString(),key,in,null,(c,ctx)->{
            Card old=current(c,s,in.assetId(),in.expectedVersion());
            String next=old.lastPeriod()==null?old.policy().firstPeriod():YearMonth.parse(old.lastPeriod()).plusMonths(1).toString();
            if(!next.equals(YearMonth.from(s.date()).toString()) || in.remainingMonths()<1 || in.remainingMonths()>1200) throw fail("PROSPECTIVE_NEXT_PERIOD_REQUIRED");
            Policy prior=old.policy();
            Policy policy=new Policy(prior.framework(),prior.kind(),prior.method(),prior.firstPeriod(),old.elapsed()+in.remainingMonths(),money(in.residual()),in.assessmentId(),old.accumulated(),old.elapsed());
            Card after=copy(old,policy,old.accumulated(),old.elapsed(),old.lastPeriod(),old.version()+1,old.status());valid(after);
            Plan p=new Plan(UUID.randomUUID(),s,"ESTIMATE","ESTIMATE/"+old.id()+"/"+old.version(),in.assessmentId(),reason(in.reason()),ctx.actor(),List.of(new Change(old,after,null)),List.of(),new EstimatePreview(schedule(old),schedule(after)),null,ZERO);
            return propose(c,ctx,p);
        });
    }
    public Receipt dispose(String principal,Scope s,String key,Dispose in) throws Exception {
        return command(principal,s,"dispose",in.assetId().toString(),key,in,null,(c,ctx)->{
            Card old=current(c,s,in.assetId(),in.expectedVersion());valid(old);
            if(in.approvalEvidence()==null) throw fail("DISPOSAL_ASSESSMENT_REQUIRED");
            // Explicit approved disposal month policy is enforced by guard; no cash/tax inference here.
            BigDecimal proceeds=money(in.proceeds()),net=old.cost().subtract(old.accumulated()),difference=net.subtract(proceeds);
            List<Line> lines=new ArrayList<>();
            if(proceeds.signum()>0) lines.add(new Line(in.proceedsAccount(),proceeds,ZERO));
            if(old.accumulated().signum()>0) lines.add(new Line(old.accounts().accumulated(),old.accumulated(),ZERO));
            if(difference.signum()>0) lines.add(new Line(in.lossAccount(),difference,ZERO));
            lines.add(new Line(old.accounts().cost(),ZERO,old.cost()));
            if(difference.signum()<0) lines.add(new Line(in.gainAccount(),ZERO,difference.negate()));
            Card after=copy(old,old.policy(),old.accumulated(),old.elapsed(),old.lastPeriod(),old.version()+1,"DISPOSED");
            Plan p=new Plan(UUID.randomUUID(),s,"DISPOSE","DISPOSE/"+old.id()+"/"+old.version(),in.approvalEvidence(),reason(in.reason()),ctx.actor(),List.of(new Change(old,after,null)),List.copyOf(lines),null,null,ZERO);
            return propose(c,ctx,p);
        });
    }
    public Receipt reverse(String principal,Scope s,String key,Reverse in) throws Exception {
        return command(principal,s,"reverse",in.originalPlan().toString(),key,in,in.originalPlan(),(c,ctx)->{
            StoredPlan original=repo.plan(c,s,in.originalPlan(),true);
            if(original.version()!=in.expectedPlanVersion() || !original.status().equals("POSTED") || original.plan().kind().equals("REVERSE")) throw fail("POSTED_ORIGINAL_REQUIRED");
            if(Set.of("DEPRECIATE","CORRECT_DEPRECIATION").contains(original.plan().kind())) throw fail("LINKED_DEPRECIATION_CORRECTION_REQUIRED");
            if(s.date().isBefore(original.plan().scope().date())) throw fail("REVERSAL_BEFORE_ORIGINAL");
            List<Change> changes=new ArrayList<>();
            for(Change x:original.plan().changes().stream().sorted(Comparator.comparing(a->a.after().id())).toList()) {
                Card now=repo.card(c,s,x.after().id(),true);
                if(now.version()!=x.after().version()) throw fail("DEPENDENT_ASSET_EVENTS_EXIST");
                Card after=x.before()==null?copy(now,now.policy(),now.accumulated(),now.elapsed(),now.lastPeriod(),now.version()+1,"REVERSED")
                    :copy(x.before(),x.before().policy(),x.before().accumulated(),x.before().elapsed(),x.before().lastPeriod(),now.version()+1,x.before().status());
                changes.add(new Change(now,after,null));
            }
            Plan p=new Plan(UUID.randomUUID(),s,"REVERSE","REVERSE/"+in.originalPlan(),null,reason(in.reason()),ctx.actor(),List.copyOf(changes),AssetRules.reverse(original.plan().lines()),null,in.originalPlan(),ZERO);
            return propose(c,ctx,p);
        });
    }
    public Receipt correctDepreciation(String principal,Scope s,String key,CorrectDepreciation in) throws Exception {
        return command(principal,s,"correct-depreciation",in.originalPlan().toString(),key,in,in.originalPlan(),(c,ctx)->{
            StoredPlan original=repo.plan(c,s,in.originalPlan(),true);
            if(original.version()!=in.expectedPlanVersion() || !original.status().equals("POSTED") || !original.plan().kind().equals("DEPRECIATE") || in.assessmentId()==null) throw fail("POSTED_DEPRECIATION_AND_ASSESSMENT_REQUIRED");
            if(s.date().isBefore(original.plan().scope().date())) throw fail("CORRECTION_BEFORE_ORIGINAL");
            Change x=original.plan().changes().getFirst();
            Card now=repo.card(c,s,x.after().id(),true);
            if(now.version()!=x.after().version()) throw fail("DEPENDENT_ASSET_EVENTS_EXIST");
            BigDecimal amount=money(in.correctedAmount()),base=x.before().cost().subtract(x.before().policy().residual()).subtract(x.before().accumulated());
            if(amount.compareTo(base)>0) throw fail("CORRECTION_EXCEEDS_REMAINING_BASE");
            if(now.elapsed()==now.policy().months() && amount.compareTo(base)!=0) throw fail("FINAL_PERIOD_MUST_EXHAUST_BASE");
            Card after=copy(now,now.policy(),x.before().accumulated().add(amount),now.elapsed(),now.lastPeriod(),now.version()+1,now.status());
            Change change=new Change(now,after,new Trace(base,amount,amount,ZERO,base.subtract(amount),now.policy().months()-x.before().elapsed()));
            List<Line> lines=new ArrayList<>(AssetRules.reverse(original.plan().lines()));lines.addAll(depreciationLines(change));
            Plan p=new Plan(UUID.randomUUID(),s,"CORRECT_DEPRECIATION","CORRECT_DEPRECIATION/"+in.originalPlan(),in.assessmentId(),reason(in.reason()),ctx.actor(),List.of(change),List.copyOf(lines),null,in.originalPlan(),ZERO);
            return propose(c,ctx,p);
        });
    }
    public Receipt transition(String principal,Scope s,String action,String key,Transition in) throws Exception {
        if(!Set.of("approve","post","cancel").contains(action)) throw fail("UNKNOWN_ACTION");
        return command(principal,s,action,in.planId().toString(),key,in,in.planId(),(c,ctx)->{
            StoredPlan p=repo.plan(c,s,in.planId(),true);
            if(!p.plan().scope().equals(s)) throw fail("PLAN_SCOPE_MISMATCH");
            if(p.version()!=in.expectedVersion()) throw fail("PLAN_VERSION_CONFLICT");reason(in.reason());
            if(action.equals("approve")) {
                if(!p.status().equals("SUBMITTED")) throw fail("PLAN_NOT_SUBMITTED");
                independent(p.plan().maker(),ctx.actor());guard.validate(c,ctx,p.plan(),ctx.actor());
                repo.transition(c,p,"APPROVED",ctx.actor(),null);
            } else if(action.equals("cancel")) {
                if(!Set.of("SUBMITTED","APPROVED").contains(p.status())) throw fail("PLAN_NOT_CANCELLABLE");
                if(!p.plan().maker().equals(ctx.actor())) throw fail("ORIGINAL_MAKER_REQUIRED");
                if(p.plan().kind().equals("ACQUIRE")) evidence.release(c,ctx,p.plan().sourceId(),p.plan().id());
                repo.transition(c,p,"CANCELLED",p.approver(),null);repo.release(c,p.plan());
            } else {
                if(!p.status().equals("APPROVED")) throw fail("PLAN_NOT_APPROVED");
                independent(p.plan().maker(),p.approver());guard.validate(c,ctx,p.plan(),p.approver());
                for(Change ch:p.plan().changes().stream().sorted(Comparator.comparing(a->a.after().id())).toList()) {
                    if(ch.before()!=null && !repo.card(c,s,ch.before().id(),true).equals(ch.before())) throw fail("ASSET_SNAPSHOT_STALE");
                    if(ch.before()!=null) repo.chronological(c,s,ch.before().id());
                }
                UUID journal=null;
                if(!p.plan().lines().isEmpty()) {
                    PostReceipt result=posting.post(c,ctx,p);journal=result.journalId();repo.verifyPosting(c,p.plan(),journal);
                }
                if(p.plan().kind().equals("ACQUIRE")) evidence.consume(c,ctx,p.plan().sourceId(),p.plan().id(),p.plan().sourceAmount());
                if(p.plan().kind().equals("REVERSE") && repo.plan(c,s,p.plan().reverses(),false).plan().kind().equals("ACQUIRE")) evidence.reverse(c,ctx,p.plan().reverses(),p.plan().id());
                repo.apply(c,p.plan());repo.transition(c,p,"POSTED",p.approver(),journal);repo.release(c,p.plan());
            }
            repo.audit(c,ctx,p.plan(),action.toUpperCase(Locale.ROOT));
            StoredPlan done=repo.plan(c,s,in.planId(),false);return new Receipt(done.plan().id(),done.status(),done.journal(),done.version());
        });
    }
    public List<Card> cards(String principal,Scope s,UUID after,int limit) throws Exception {
        try(Connection c=data.getConnection()) {
            c.setAutoCommit(false);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setReadOnly(true);
            try { guard.enter(c,principal,s,"read",null);var result=repo.cards(c,s,after,limit);c.commit();return result; }
            catch(Exception e) {c.rollback();throw e;}
        }
    }
    public record Difference(UUID account,@com.fasterxml.jackson.annotation.JsonFormat(shape=com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) BigDecimal subledgerDebitNet,@com.fasterxml.jackson.annotation.JsonFormat(shape=com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) BigDecimal ledgerDebitNet,@com.fasterxml.jackson.annotation.JsonFormat(shape=com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) BigDecimal difference,String watermark) {}
    public StoredPlan detail(String principal,Scope s,UUID id) throws Exception {
        try(Connection c=data.getConnection()) {
            c.setAutoCommit(false);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setReadOnly(true);
            try {guard.enter(c,principal,s,"read",null);var p=repo.plan(c,s,id,false);
                if(!p.plan().scope().year().equals(s.year())) throw fail("PLAN_YEAR_SCOPE_DENIED");
                c.commit();return p;
            } catch(Exception e) {c.rollback();throw e;}
        }
    }
    public List<Difference> reconcile(String principal,Scope s) throws Exception {
        try(Connection c=data.getConnection()) {
            c.setAutoCommit(false);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setReadOnly(true);
            try {
                Context ctx=guard.enter(c,principal,s,"reconcile",null);
                Map<UUID,BigDecimal> expected=new TreeMap<>();
                for(Card card:repo.asOfCards(c,s)) {
                    expected.putIfAbsent(card.accounts().cost(),ZERO);expected.putIfAbsent(card.accounts().accumulated(),ZERO);
                    if(card.status().equals("IN_USE")) {
                        expected.merge(card.accounts().cost(),card.cost(),BigDecimal::add);
                        expected.merge(card.accounts().accumulated(),card.accumulated().negate(),BigDecimal::add);
                    }
                }
                Map<UUID,AssetPorts.LedgerBalance> balances=new HashMap<>();
                for(var b:ledger.balances(c,ctx,expected.keySet())) if(balances.put(b.account(),b)!=null) throw fail("DUPLICATE_LEDGER_BALANCE");
                List<Difference> result=new ArrayList<>();
                for(var e:expected.entrySet()) {
                    var b=balances.get(e.getKey());
                    if(b==null || b.debitNet()==null || b.watermark()==null) throw fail("LEDGER_BALANCE_UNKNOWN");
                    result.add(new Difference(e.getKey(),e.getValue(),b.debitNet(),e.getValue().subtract(b.debitNet()),b.watermark()));
                }
                c.commit();return List.copyOf(result);
            } catch(Exception e) {c.rollback();throw e;}
        }
    }
}
