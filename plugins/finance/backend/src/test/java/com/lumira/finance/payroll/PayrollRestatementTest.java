package com.lumira.finance.payroll;
import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

class PayrollRestatementTest {
    @Test void acceptedReferencesRetainedAndNegativeDifferenceNeverBecomesPayment() {
        UUID accepted=UUID.randomUUID();
        var period=new PayrollRestatement.Period(UUID.randomUUID(),LocalDate.of(2026,1,31),1,
            PayrollCalculation.DeductionMode.NORMAL,null,PayrollCalculationTest.rule(),
            PayrollCalculationTest.input("5000","0"),new BigDecimal("150.00"),accepted,"a".repeat(64));
        var plan=PayrollRestatement.preview(PayrollCalculationTest.scope(),List.of(period));
        assertEquals(accepted,plan.changes().getFirst().acceptedDeclaration());
        assertEquals(new BigDecimal("-150.00"),plan.totalDifference());
        assertEquals(new BigDecimal("0.00"),plan.changes().getFirst().revised().currentWithholding());
        assertThrows(IllegalArgumentException.class,()->PayrollRestatement.preview(PayrollCalculationTest.scope(),List.of(period,period)));
    }
}
