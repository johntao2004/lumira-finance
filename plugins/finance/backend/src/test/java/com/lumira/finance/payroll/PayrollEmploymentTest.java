package com.lumira.finance.payroll;
import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

class PayrollEmploymentTest {
    @Test void rehireDoesNotDoubleCountMonthAndOldEmployerCannotEnterAccumulator() {
        var s=PayrollCalculationTest.scope();
        var first=new PayrollEmployment.Employment(s.tenant(),s.employee(),s.withholdingEntity(),UUID.randomUUID(),
            LocalDate.of(2026,1,1),LocalDate.of(2026,3,15),PayrollEmployment.Residency.RESIDENT,PayrollCalculationTest.approval(),"a".repeat(64));
        var second=new PayrollEmployment.Employment(s.tenant(),s.employee(),s.withholdingEntity(),UUID.randomUUID(),
            LocalDate.of(2026,3,20),null,PayrollEmployment.Residency.RESIDENT,PayrollCalculationTest.approval(),"b".repeat(64));
        assertEquals(6,PayrollEmployment.distinctMonths(s,LocalDate.of(2026,6,30),List.of(first,second)));
        assertThrows(IllegalArgumentException.class,()->PayrollEmployment.distinctMonths(PayrollCalculationTest.scope(),LocalDate.of(2026,6,30),List.of(first)));
        assertThrows(IllegalArgumentException.class,()->PayrollEmployment.requireWage(s,first,LocalDate.of(2026,3,1),LocalDate.of(2026,3,15),
            LocalDate.of(2026,4,30),PayrollService.Kind.NORMAL,null));
        assertDoesNotThrow(()->PayrollEmployment.requireWage(s,first,LocalDate.of(2026,3,1),LocalDate.of(2026,3,15),
            LocalDate.of(2026,4,30),PayrollService.Kind.ARREARS,"离职补发"));
    }
}
