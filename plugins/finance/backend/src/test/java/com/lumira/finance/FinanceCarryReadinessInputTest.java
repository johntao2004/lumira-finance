package com.lumira.finance;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.math.BigDecimal;
import java.util.*;

class FinanceCarryReadinessInputTest {
    @Test
    void strictQueryAndBoundedPage() {
        var r = new MockHttpServletRequest();
        r.addParameter("targetYearId", UUID.randomUUID().toString());
        assertEquals(20, FinanceCarryReadinessInput.parse("summary", r).limit());
        r.addParameter("observationToken", "opaque");
        r.addParameter("filter", "NONZERO");
        r.addParameter("limit", "100");
        assertEquals(100, FinanceCarryReadinessInput.parse("accounts", r).limit());
        assertThrows(FinanceException.class, () -> FinanceCarryReadinessInput.parse("summary", r));
        r.setParameter("limit", "101");
        assertThrows(FinanceException.class, () -> FinanceCarryReadinessInput.parse("accounts", r));
        r.setParameter("limit", "01");
        assertThrows(FinanceException.class, () -> FinanceCarryReadinessInput.parse("accounts", r));
        r.setParameter("limit", "1");
        r.setParameter("observationToken", "a".repeat(4097));
        assertThrows(FinanceException.class, () -> FinanceCarryReadinessInput.parse("accounts", r));
        r.setParameter("observationToken", "a");
        r.setParameter("targetYearId", "bad");
        assertThrows(FinanceException.class, () -> FinanceCarryReadinessInput.parse("accounts", r));
    }

    @Test
    void moneyNeverRoundsOrConvertsUnknownToZero() {
        assertEquals(
                "0.00000000", FinanceCarryReadinessProjection.known(BigDecimal.ZERO).get("amount"));
        assertNull(FinanceCarryReadinessProjection.unknown("MISSING_ACCOUNT").get("amount"));
        assertEquals(
                "1.00500000",
                FinanceCarryReadinessProjection.known(new BigDecimal("1.005")).get("amount"));
        assertNull(
                FinanceCarryReadinessProjection.known(new BigDecimal("1.000000001")).get("amount"));
        assertNull(FinanceCarryReadinessProjection.known(BigDecimal.TEN.pow(20)).get("amount"));
        assertThrows(
                IllegalArgumentException.class,
                () -> FinanceCarryReadinessProjection.decimal("NaN"));
        assertEquals(
                "1.23000000",
                FinanceCarryReadinessProjection.known(new BigDecimal("1.23")).get("amount"));
    }

    @Test
    void zeroFilterKeepsMissingUnknownAndSemanticDifferences() {
        var row = new LinkedHashMap<String, Object>();
        row.put("candidateStatus", "CANDIDATE_COMPATIBLE");
        for (var key : FinanceCarryReadiness.MONETARY)
            row.put(key, FinanceCarryReadinessProjection.known(BigDecimal.ZERO));
        assertFalse(FinanceCarryReadiness.nonzero(row));
        row.put("targetOpening", FinanceCarryReadinessProjection.unknown("MISSING_ACCOUNT"));
        assertTrue(FinanceCarryReadiness.nonzero(row));
        row.put("targetOpening", FinanceCarryReadinessProjection.known(BigDecimal.ZERO));
        row.put("candidateStatus", "SEMANTIC_CONFLICT");
        assertTrue(FinanceCarryReadiness.nonzero(row));
    }
}
