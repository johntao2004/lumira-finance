package com.lumira.finance.payroll;

import java.util.List;
import java.util.UUID;
import static com.lumira.finance.payroll.PayrollService.*;

/** Monetary HTTP values are decimal strings, never JSON numeric doubles. */
public record PayrollView(Draft draft, State state, long version, List<LineView> computed, List<FactView> facts) {
    public record TaxView(String taxable,String basic,String rate,String quick,String cumulativeTax,String currentWithholding,String overwithheld) {}
    public record LineView(UUID employee,String gross,String employerSocial,String netPay,TaxView tax) {}
    public record FactView(FactKind kind,UUID reference,String amount) {}
    public static PayrollView from(Batch batch) {
        return new PayrollView(batch.draft(),batch.state(),batch.version(),batch.computed().stream().map(c -> {
            var t=c.tax();
            return new LineView(c.employee(),c.gross().toPlainString(),c.employerSocial().toPlainString(),c.netPay().toPlainString(),
                new TaxView(t.taxable().toPlainString(),t.basic().toPlainString(),t.rate().toPlainString(),t.quick().toPlainString(),
                    t.cumulativeTax().toPlainString(),t.currentWithholding().toPlainString(),t.overwithheld().toPlainString()));
        }).toList(),batch.facts().stream().map(f->new FactView(f.kind(),f.reference(),f.amount().toPlainString())).toList());
    }
}
