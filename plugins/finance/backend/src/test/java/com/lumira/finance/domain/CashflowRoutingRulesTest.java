package com.lumira.finance.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import static com.lumira.finance.domain.CashflowRoutingRules.*;

/** V4 book02 fixtures ACC04-07/18/19. Approval identities below are synthetic test evidence only. */
public final class CashflowRoutingRulesTest {
    private static final LocalDate DATE = LocalDate.of(2026,7,31);
    private static BigDecimal m(String amount) { return AccountingRules.money(amount); }
    private static void equal(BigDecimal actual,String expected) { if(actual.compareTo(m(expected))!=0) throw new AssertionError(actual); }
    private static void check(boolean condition) { if(!condition) throw new AssertionError(); }
    private static void rejected(String code,Runnable action) {
        try { action.run(); } catch(IllegalArgumentException expected) {
            if(!code.equals(expected.getMessage())) throw new AssertionError(expected); return;
        }
        throw new AssertionError("Expected "+code);
    }
    private static Context context(ReportScope scope) {
        return new Context("parent",scope==ReportScope.INDIVIDUAL?Set.of("parent"):Set.of("parent","subsidiary"),
                scope,"cash-scope-1","CAS-CF-2019-A2",DATE);
    }
    private static Rule rule(Context context,boolean investment) {
        return new Rule("REFERENCE-ONLY","1","APPROVED","fixture-reviewer","book02-V4-fixture",context,
                TransactionForm.SHARE_CONTROL,LocalDate.of(2026,1,1),LocalDate.of(2026,12,31),
                investment?"C18":"C19",investment?"C11":"C14",!investment);
    }
    private static Transaction deal(Action action,String price,String target,String costs) {
        return new Transaction(action,TransactionForm.SHARE_CONTROL,m(price),m(target),m(costs),false,true);
    }
    public static void main(String[] args) {
        Context individual=context(ReportScope.INDIVIDUAL),group=context(ReportScope.CONSOLIDATED);
        rejected("MISSING_REPORT_SCOPE",()->new Context("parent",null,ReportScope.INDIVIDUAL,"cash","CAS-CF-2019-A2",DATE));
        rejected("MISSING_REPORT_SCOPE",()->new Context("parent",Set.of(),ReportScope.INDIVIDUAL,"cash","CAS-CF-2019-A2",DATE));
        rejected("MISSING_REPORT_SCOPE",()->new Context(" ",Set.of("parent"),ReportScope.INDIVIDUAL,"cash","CAS-CF-2019-A2",DATE));
        rejected("MISSING_REPORT_SCOPE",()->new Context("parent",Set.of(" "),ReportScope.INDIVIDUAL,"cash","CAS-CF-2019-A2",DATE));
        java.util.Set<String> nullEntitySet=new java.util.HashSet<>();nullEntitySet.add(null);
        rejected("MISSING_REPORT_SCOPE",()->new Context("parent",nullEntitySet,ReportScope.INDIVIDUAL,"cash","CAS-CF-2019-A2",DATE));
        var acquire=deal(Action.ACQUIRE,"600","100","0");
        var parent=route(individual,rule(individual,false),acquire);
        var consolidated=route(group,rule(group,false),acquire);
        check(parent.row().equals("C19")&&consolidated.row().equals("C19"));
        equal(parent.amount(),"600");equal(consolidated.amount(),"500");
        equal(m("1000").subtract(parent.amount()),"400");
        equal(m("1000").subtract(parent.amount()).add(m("100")),"500");
        check(route(individual,rule(individual,true),acquire).row().equals("C18"));
        equal(route(individual,rule(individual,true),acquire).amount(),"600");
        var disposal=deal(Action.DISPOSE,"600","100","0");
        equal(route(individual,rule(individual,false),disposal).amount(),"600");
        equal(route(group,rule(group,false),disposal).amount(),"500");
        equal(m("1000").add(route(individual,rule(individual,false),disposal).amount()),"1600");
        equal(m("1100").add(route(group,rule(group,false),disposal).amount()),"1600");
        check(route(individual,rule(individual,true),disposal).row().equals("C11"));
        equal(route(individual,rule(individual,false),deal(Action.DISPOSE,"600","100","20")).amount(),"580");
        equal(route(group,rule(group,false),deal(Action.DISPOSE,"600","100","20")).amount(),"480");
        var negativeAcquisition=route(group,rule(group,false),deal(Action.ACQUIRE,"80","100","0"));
        check(negativeAcquisition.row().equals("C15"));equal(negativeAcquisition.amount(),"20");
        var negativeDisposal=route(group,rule(group,false),deal(Action.DISPOSE,"80","100","0"));
        check(negativeDisposal.row().equals("C20"));equal(negativeDisposal.amount(),"20");
        rejected("APPROVED_MAPPING_REQUIRED",()->route(individual,null,acquire));
        rejected("MAPPING_SCOPE_MISMATCH",()->route(group,rule(individual,true),acquire));
        rejected("ACQUISITION_COST_RULE_REQUIRED",()->route(individual,rule(individual,false),deal(Action.ACQUIRE,"600","100","20")));
        rejected("SPECIAL_DISPOSAL_COST_RULE_REQUIRED",()->route(individual,rule(individual,true),deal(Action.DISPOSE,"600","100","20")));
        rejected("SPECIAL_COMBINATION_RULE_REQUIRED",()->route(group,rule(group,false),new Transaction(Action.ACQUIRE,TransactionForm.SHARE_CONTROL,m("600"),m("100"),m("0"),true,true)));
        rejected("CONTROL_EVIDENCE_REQUIRED",()->route(individual,rule(individual,false),new Transaction(Action.ACQUIRE,TransactionForm.SHARE_CONTROL,m("600"),m("100"),m("0"),false,false)));
        Context wrongCash=new Context("parent",Set.of("parent"),ReportScope.INDIVIDUAL,"cash-scope-2","CAS-CF-2019-A2",DATE);
        rejected("MAPPING_SCOPE_MISMATCH",()->route(wrongCash,rule(individual,false),acquire));
        Context expired=new Context("parent",Set.of("parent"),ReportScope.INDIVIDUAL,"cash-scope-1","CAS-CF-2019-A2",LocalDate.of(2027,1,1));
        rejected("MAPPING_OUTSIDE_EFFECTIVE_PERIOD",()->route(expired,rule(individual,false),acquire));
        Context wrongEntity=new Context("other",Set.of("other"),ReportScope.INDIVIDUAL,"cash-scope-1","CAS-CF-2019-A2",DATE);
        rejected("MAPPING_SCOPE_MISMATCH",()->route(wrongEntity,rule(individual,false),acquire));
        Context unknownScope=new Context("parent",Set.of("parent"),null,"cash-scope-1","CAS-CF-2019-A2",DATE);
        rejected("MISSING_REPORT_SCOPE",()->route(unknownScope,rule(individual,false),acquire));
        Rule businessRule=new Rule("REFERENCE-ONLY","1","APPROVED","fixture-reviewer","book02-V4-fixture",individual,
                TransactionForm.ABSORPTION_OR_BUSINESS,LocalDate.of(2026,1,1),LocalDate.of(2026,12,31),"C19","C14",true);
        equal(route(individual,businessRule,new Transaction(Action.ACQUIRE,TransactionForm.ABSORPTION_OR_BUSINESS,m("600"),m("100"),m("0"),false,true)).amount(),"500");
        rejected("COMBINATION_FORM_MISMATCH",()->route(individual,businessRule,acquire));
        Context wrongTemplate=new Context("parent",Set.of("parent"),ReportScope.INDIVIDUAL,"cash-scope-1","SBE-CF-2011",DATE);
        rejected("MISSING_REPORT_SCOPE",()->route(wrongTemplate,rule(individual,false),acquire));
        var unapproved= new Rule("rule","1","DRAFT","fixture-reviewer","source",individual,TransactionForm.SHARE_CONTROL,DATE,DATE,"C19","C14",true);
        rejected("APPROVED_MAPPING_REQUIRED",()->route(individual,unapproved,acquire));
        equal(exchangeEffect(individual,m("100"),new BigDecimal("7.0"),m("100"),new BigDecimal("7.2"),m("0"),false,true),"20");
        rejected("CASH_SCOPE_RECONCILIATION_REQUIRED",()->exchangeEffect(individual,m("100"),new BigDecimal("7.0"),m("100"),new BigDecimal("7.2"),m("0"),true,true));
        rejected("CASHFLOWS_CONFIRMATION_REQUIRED",()->exchangeEffect(individual,m("100"),new BigDecimal("7.0"),m("100"),new BigDecimal("7.2"),m("0"),false,false));
        rejected("CASHFLOWS_CONFIRMATION_REQUIRED",()->exchangeEffect(individual,m("100"),new BigDecimal("7.0"),m("100"),new BigDecimal("7.2"),m("0"),false));
        rejected("INVALID_CASHFLOWS_AMOUNT",()->exchangeEffect(individual,m("100"),new BigDecimal("7.0"),m("100"),new BigDecimal("7.2"),new BigDecimal("0.000000001"),false,true));
        rejected("INVALID_CASHFLOWS_AMOUNT",()->exchangeEffect(individual,m("100"),new BigDecimal("7.0"),m("100"),new BigDecimal("7.2"),new BigDecimal("100000000000000000000"),false,true));
        equal(exchangeEffect(individual,m("100"),new BigDecimal("7.0"),m("100"),new BigDecimal("7.2"),m("-5"),false,true),"25");
        var preciseEffect=exchangeEffect(individual,m("0.00000001"),new BigDecimal("7.000000000001"),m("0.00000001"),new BigDecimal("7.000000000002"),m("0"),false,true);
        check(preciseEffect.compareTo(new BigDecimal("0.00000000000000000001"))==0);
        check(parent.status().equals("DRAFT"));
        taxCases();
        System.out.println("V4 cashflow scope and SBE tax routing assertions passed");
    }
    private static void taxCases() {
        var ordinary = new SbeTaxPresentationRules.Context("entity","ordinary-tax",SbeTaxPresentationRules.TaxKind.ORDINARY_NON_VAT,"SBE-BS-2011",DATE);
        var vat = new SbeTaxPresentationRules.Context("entity","vat-input",SbeTaxPresentationRules.TaxKind.VAT,"SBE-BS-2011",DATE);
        var ordinaryRule = taxRule(ordinary,SbeTaxPresentationRules.Treatment.SBE_GENERAL_NEGATIVE);
        var vatRule = taxRule(vat,SbeTaxPresentationRules.Treatment.REVIEWED_VAT_ASSET);
        var ordinaryResult = SbeTaxPresentationRules.present(ordinary,m("20"),ordinaryRule);
        equal(ordinaryResult.exclusiveRowAmounts().get("36"),"-20");equal(ordinaryResult.exclusiveRowAmounts().get("14"),"0");
        rejected("TAX_PRESENTATION_RULE_REQUIRED",()->SbeTaxPresentationRules.present(vat,m("20"),null));
        var vatResult = SbeTaxPresentationRules.present(vat,m("20"),vatRule);
        equal(vatResult.exclusiveRowAmounts().get("14"),"20");equal(vatResult.exclusiveRowAmounts().get("36"),"0");
        rejected("TAX_PRESENTATION_RULE_SCOPE_MISMATCH",()->SbeTaxPresentationRules.present(vat,m("20"),ordinaryRule));
        rejected("TAX_PRESENTATION_RULE_SCOPE_MISMATCH",()->SbeTaxPresentationRules.present(vat,m("20"),taxRule(vat,SbeTaxPresentationRules.Treatment.SBE_GENERAL_NEGATIVE)));
        var wrongEntity=new SbeTaxPresentationRules.Context("other","vat-input",SbeTaxPresentationRules.TaxKind.VAT,"SBE-BS-2011",DATE);
        rejected("TAX_PRESENTATION_RULE_SCOPE_MISMATCH",()->SbeTaxPresentationRules.present(wrongEntity,m("20"),vatRule));
        var expired=new SbeTaxPresentationRules.Context("entity","vat-input",SbeTaxPresentationRules.TaxKind.VAT,"SBE-BS-2011",LocalDate.of(2027,1,1));
        rejected("TAX_PRESENTATION_RULE_EXPIRED",()->SbeTaxPresentationRules.present(expired,m("20"),vatRule));
        check(vatResult.status().equals("DRAFT"));
    }
    private static SbeTaxPresentationRules.Rule taxRule(SbeTaxPresentationRules.Context c,SbeTaxPresentationRules.Treatment treatment) {
        return new SbeTaxPresentationRules.Rule("REFERENCE-ONLY","1","APPROVED","fixture-reviewer","book02-T41-T43",c.entityId(),c.taxDetailId(),c.taxKind(),c.templateVersion(),LocalDate.of(2026,1,1),LocalDate.of(2026,12,31),treatment);
    }
}
