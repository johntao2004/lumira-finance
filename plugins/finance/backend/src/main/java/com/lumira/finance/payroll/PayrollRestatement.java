package com.lumira.finance.payroll;

import static com.lumira.finance.payroll.PayrollCalculation.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** Computes a reviewable downstream correction plan; never rewrites accepted periods or pays deltas. */
public final class PayrollRestatement {
    private PayrollRestatement() {}
    public record Period(UUID batch, LocalDate date, int months, DeductionMode mode,
            ExceptionEvidence exception, RulePack rule, Cumulative cumulative,
            BigDecimal originalWithholding, UUID acceptedDeclaration, String sourceDigest) {}
    public record Change(UUID batch, UUID acceptedDeclaration, Result revised,
            BigDecimal originalWithholding, BigDecimal signedDifference, String sourceDigest) {}
    public record Plan(Scope scope, List<Change> changes, BigDecimal totalDifference) {
        public Plan { changes=List.copyOf(changes); }
    }
    public static Plan preview(Scope scope, List<Period> periods) {
        if (periods.isEmpty() || periods.size()>366) throw invalid("RESTATEMENT_PERIODS");
        LocalDate previous=null; Set<UUID> batches=new HashSet<>();
        List<Change> changes=new ArrayList<>(); BigDecimal total=BigDecimal.ZERO.setScale(2);
        for(Period p:periods) {
            if(p.batch()==null || !batches.add(p.batch()) || p.date()==null || p.date().getYear()!=scope.year()
                || (previous!=null && !p.date().isAfter(previous)) || p.originalWithholding()==null
                || p.originalWithholding().signum()<0 || p.originalWithholding().scale()>2
                || p.sourceDigest()==null || !p.sourceDigest().matches("[a-f0-9]{64}")) throw invalid("RESTATEMENT_SOURCE");
            // cumulative.alreadyWithheld is actual confirmed withholding, not a synthetic revised tax.
            // This preserves overwithheld tax rather than inventing a negative refund in later periods.
            Result revised=calculate(scope,p.date(),p.months(),p.mode(),p.exception(),p.rule(),p.cumulative());
            BigDecimal difference=revised.currentWithholding().subtract(p.originalWithholding());
            changes.add(new Change(p.batch(),p.acceptedDeclaration(),revised,p.originalWithholding(),difference,p.sourceDigest()));
            total=total.add(difference);previous=p.date();
        }
        return new Plan(scope,changes,total);
    }
}
