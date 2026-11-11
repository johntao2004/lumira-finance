package com.lumira.finance.expense;

import com.lumira.finance.expense.ExpenseDomain.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class ExpenseDomainTest {

    @Test
    void testCnyValidationStrictScale() {
        assertEquals("100.00", ExpenseDomain.toCny2String(ExpenseDomain.cny("100", false)));
        assertEquals("100.50", ExpenseDomain.toCny2String(ExpenseDomain.cny("100.5", false)));
        assertEquals("100.55", ExpenseDomain.toCny2String(ExpenseDomain.cny("100.55", false)));
        assertEquals("0.00", ExpenseDomain.toCny2String(ExpenseDomain.cny("0.00", true)));

        // Rejections
        assertThrows(ValidationException.class, () -> ExpenseDomain.cny("0.00", false)); // strictly positive required
        assertThrows(ValidationException.class, () -> ExpenseDomain.cny("-50.00", true)); // negative rejected
        assertThrows(ValidationException.class, () -> ExpenseDomain.cny("100.555", false)); // > 2 decimals rejected
        assertThrows(ValidationException.class, () -> ExpenseDomain.cny("1e2", false)); // scientific notation rejected
        assertThrows(ValidationException.class, () -> ExpenseDomain.cny("abc", false));
        assertThrows(ValidationException.class, () -> ExpenseDomain.cny(null, false));
    }

    @Test
    void testStoredPrecisionScale() {
        BigDecimal val = ExpenseDomain.cny("123.45", false);
        assertEquals("123.45000000", ExpenseDomain.toStoredPlainString(val));
    }

    @Test
    void testSegregationOfDutiesCheck() {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();

        // Distinct persons should pass
        assertDoesNotThrow(() -> ExpenseDomain.checkSod(p1, p2, "Test SoD"));

        // Same person should throw ValidationException
        ValidationException ex = assertThrows(ValidationException.class,
                () -> ExpenseDomain.checkSod(p1, p1, "Approval"));
        assertEquals("SOD_VIOLATION", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("cannot approve or review their own action"));
    }
}
