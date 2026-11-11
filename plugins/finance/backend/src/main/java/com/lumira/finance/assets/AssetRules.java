package com.lumira.finance.assets;

import static com.lumira.finance.assets.AssetModel.*;
import java.math.*;
import java.time.YearMonth;
import java.util.*;

public final class AssetRules {
    private AssetRules() {}
    public static final BigDecimal ZERO = new BigDecimal("0.00000000");
    public static BigDecimal money(String raw) {
        if (raw == null || !raw.matches("(?:0|[1-9][0-9]{0,19})(?:\\.[0-9]{1,2})?")) throw fail("CNY_TWO_DECIMALS_REQUIRED");
        return internal(new BigDecimal(raw));
    }
    public static BigDecimal internal(BigDecimal n) {
        if (n == null || n.signum() < 0 || n.precision() - n.scale() > 20) throw fail("AMOUNT_RANGE");
        return n.setScale(8, RoundingMode.UNNECESSARY);
    }
    public static void valid(Card c) {
        if (c.id() == null || c.name() == null || c.name().isBlank() || c.name().length()>200
            || c.classification()==null || c.classification().isBlank() || c.location()==null || c.location().isBlank()
            || c.custodian()==null || c.acquired()==null || c.ready()==null || c.ready().isBefore(c.acquired())) throw fail("CARD_DETAILS_REQUIRED");
        Policy p = Objects.requireNonNull(c.policy());
        if (!Set.of("CAS","SMALL").contains(p.framework()) || !Set.of("FIXED","INTANGIBLE_FINITE").contains(p.kind())
            || !"STRAIGHT_LINE".equals(p.method()) || p.assessmentId()==null || p.months()<1 || p.months()>1200) throw fail("APPROVED_POLICY_REQUIRED");
        YearMonth start = YearMonth.parse(p.firstPeriod());
        if (start.isBefore(YearMonth.from(c.ready()))) throw fail("START_BEFORE_READY");
        internal(c.cost()); internal(c.accumulated()); internal(c.impairment()); internal(p.residual()); internal(p.basisAccumulated());
        if(p.basisElapsed()<0 || p.basisElapsed()>c.elapsed() || p.basisElapsed()>=p.months()
            || p.basisAccumulated().compareTo(c.accumulated())>0) throw fail("INVALID_POLICY_BASIS");
        if (c.cost().signum()==0 || c.impairment().signum()!=0) throw fail("IMPAIRMENT_PRODUCER_REQUIRED");
        if (p.residual().compareTo(c.cost())>0 || c.accumulated().add(p.residual()).compareTo(c.cost())>0
            || c.elapsed()<0 || c.elapsed()>p.months()) throw fail("INVALID_ASSET_BALANCE");
        if (c.accounts()==null || c.accounts().cost()==null || c.accounts().accumulated()==null || c.accounts().expense()==null
            || new HashSet<>(List.of(c.accounts().cost(),c.accounts().accumulated(),c.accounts().expense())).size()!=3) throw fail("DISTINCT_ACCOUNT_MAPPING_REQUIRED");
    }
    public static Change depreciate(Card c, String period) {
        valid(c);
        if (!"IN_USE".equals(c.status()) || c.elapsed()>=c.policy().months()) throw fail("ASSET_NOT_DEPRECIABLE");
        String expected = c.lastPeriod()==null ? c.policy().firstPeriod() : YearMonth.parse(c.lastPeriod()).plusMonths(1).toString();
        if (!expected.equals(period)) throw fail("NEXT_PERIOD_REQUIRED");
        BigDecimal remaining = c.cost().subtract(c.policy().residual()).subtract(c.accumulated());
        int count = c.policy().months()-c.elapsed();
        // Fixed approved policy basis; only the final month absorbs rounding remainder.
        BigDecimal base=c.cost().subtract(c.policy().residual()).subtract(c.policy().basisAccumulated());
        BigDecimal raw = base.divide(BigDecimal.valueOf(c.policy().months()-c.policy().basisElapsed()),8,RoundingMode.HALF_UP);
        BigDecimal amount = count==1 ? remaining : raw.setScale(2,RoundingMode.HALF_UP).min(remaining);
        amount=internal(amount);
        Card after = copy(c,c.policy(),c.accumulated().add(amount),c.elapsed()+1,period,c.version()+1,c.status());
        return new Change(c,after,new Trace(remaining,raw,amount,amount.subtract(raw),remaining.subtract(amount),count));
    }
    public static Card copy(Card c, Policy policy, BigDecimal accumulated, int elapsed, String last, long version, String status) {
        return new Card(c.id(),c.name(),c.classification(),c.acquired(),c.ready(),c.location(),c.custodian(),policy,c.accounts(),c.cost(),accumulated,c.impairment(),elapsed,last,version,status);
    }
    public static List<ScheduleRow> schedule(Card card) {
        List<ScheduleRow> rows = new ArrayList<>(); Card c=card;
        while(c.elapsed()<c.policy().months()) {
            String period=c.lastPeriod()==null?c.policy().firstPeriod():YearMonth.parse(c.lastPeriod()).plusMonths(1).toString();
            Change x=depreciate(c,period); rows.add(new ScheduleRow(period,x.trace().recognized(),x.trace().remaining())); c=x.after();
        }
        return List.copyOf(rows);
    }
    public static List<Line> depreciationLines(Change change) {
        BigDecimal n=change.trace().recognized();
        if(n.signum()==0) return List.of();
        return List.of(new Line(change.after().accounts().expense(),n,ZERO),new Line(change.after().accounts().accumulated(),ZERO,n));
    }
    public static List<Line> reverse(List<Line> lines) {
        return lines.stream().map(l->new Line(l.account(),l.credit(),l.debit())).toList();
    }
    public static void balanced(List<Line> lines) {
        if(lines.size()>500) throw fail("LINE_LIMIT");
        BigDecimal d=ZERO,c=ZERO;
        for(Line l:lines) {
            internal(l.debit()); internal(l.credit());
            if(l.account()==null || (l.debit().signum()>0)==(l.credit().signum()>0)) throw fail("ONE_POSITIVE_SIDE_REQUIRED");
            l.debit().setScale(2,RoundingMode.UNNECESSARY); l.credit().setScale(2,RoundingMode.UNNECESSARY);
            d=d.add(l.debit()); c=c.add(l.credit());
        }
        if(d.compareTo(c)!=0) throw fail("UNBALANCED");
    }
    public static void independent(Actor maker, Actor reviewer) {
        if(maker.person()==null || reviewer.person()==null || maker.account().equals(reviewer.account()) || maker.person().equals(reviewer.person())) throw fail("INDEPENDENT_NATURAL_PERSON_REQUIRED");
    }
}
