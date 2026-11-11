package com.lumira.finance.payroll;

import static com.lumira.finance.payroll.PayrollCalculation.*;
import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

class PayrollCalculationTest {
    static BigDecimal d(String value) { return new BigDecimal(value); }
    static Approval approval() { return new Approval(UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64)); }
    static Scope scope() { return new Scope(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 2026); }
    static RulePack rule() {
        String[][] rows = {{"36000","0.03","0"},{"144000","0.10","2520"},{"300000","0.20","16920"},
            {"420000","0.25","31920"},{"660000","0.30","52920"},{"960000","0.35","85920"},{null,"0.45","181920"}};
        return new RulePack("SUPPLIED_REFERENCE_ONLY", "a".repeat(64), LocalDate.of(2026,1,1), LocalDate.of(2026,12,31),
            approval(), Arrays.stream(rows).map(r -> new Bracket(r[0] == null ? null : d(r[0]), d(r[1]), d(r[2]))).toList(), d("5000"));
    }
    static Cumulative input(String income, String prepaid) { return new Cumulative(income,"0","0","0","0","0","0",prepaid); }
    static Result calc(String income, String prepaid) { return calculate(scope(), LocalDate.of(2026,1,31), 1, DeductionMode.NORMAL, null, rule(), input(income,prepaid)); }
    @Test void bracketsAndCentRounding() {
        assertEquals(d("1080.00"),calc("41000","0").currentWithholding());
        assertEquals(d("1080.01"),calc("41000.05","0").currentWithholding());
        assertEquals(d("11880.00"),calc("149000","0").currentWithholding());
        assertEquals(d("43080.00"),calc("305000","0").currentWithholding());
        assertEquals(d("73080.00"),calc("425000","0").currentWithholding());
        assertEquals(d("145080.00"),calc("665000","0").currentWithholding());
        assertEquals(d("250080.00"),calc("965000","0").currentWithholding());
        assertEquals(d("250080.45"),calc("965001","0").currentWithholding());
    }
    @Test void negativeWithholdingNeverBecomesRefund() {
        Result r=calc("5000","100");
        assertEquals(d("0.00"),r.currentWithholding()); assertEquals(d("100.00"),r.overwithheld());
    }
    @Test void rawCnyPrecisionAndNonFiniteRejected() {
        for(String raw:List.of("1.001","NaN","Infinity","1e3","-0.01","01"," 1","+1"))
            assertThrows(IllegalArgumentException.class,()->money(raw));
    }
    @Test void exceptionalDeductionNeedsScopedProfessionalEvidence() {
        Scope s=scope(); LocalDate date=LocalDate.of(2026,6,30);
        assertThrows(IllegalArgumentException.class,()->calculate(s,date,1,DeductionMode.FIRST_INCOME_YEAR,null,rule(),input("30000","0")));
        ExceptionEvidence e=new ExceptionEvidence(s,DeductionMode.FIRST_INCOME_YEAR,approval(),date,"b".repeat(64),"c".repeat(64));
        Result r=calculate(s,date,1,DeductionMode.FIRST_INCOME_YEAR,e,rule(),input("30000","0"));
        assertEquals(d("30000"),r.basic()); assertEquals(d("0.00"),r.currentWithholding());
        assertThrows(IllegalArgumentException.class,()->calculate(scope(),date,1,DeductionMode.FIRST_INCOME_YEAR,e,rule(),input("30000","0")));
        assertThrows(IllegalArgumentException.class,()->calculate(s,date,1,DeductionMode.PRIOR_YEAR_LE60K,e,rule(),input("30000","0")));
    }
    @Test void yearAndPolicyValidityFailClosed() {
        assertThrows(IllegalArgumentException.class,()->calculate(scope(),LocalDate.of(2027,1,31),1,DeductionMode.NORMAL,null,rule(),input("10000","0")));
        assertThrows(IllegalArgumentException.class,()->calculate(scope(),LocalDate.of(2026,1,31),2,DeductionMode.NORMAL,null,rule(),input("10000","0")));
    }
    @Test void regionalContributionsHaveNoNationalFallback() {
        LocalDate date=LocalDate.of(2026,1,1);
        assertThrows(IllegalArgumentException.class,()->contribution(date,"REGION_A","PENSION","1000",null));
        ContributionRule rule=new ContributionRule("REGION_A","PENSION",date,date.plusMonths(11),d("3000"),d("15000"),d("0.08"),d("0.16"),approval(),"d".repeat(64));
        Contributions c=contribution(date,"REGION_A","PENSION","1000",rule);
        assertEquals(d("240.00"),c.employee()); assertEquals(d("480.00"),c.employer());
        assertThrows(IllegalArgumentException.class,()->contribution(date,"REGION_B","PENSION","1000",rule));
    }
}
