package com.lumira.finance;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class FinanceCashMatchCorrectionControllerTest {
    @Test
    void strictJsonIntegerVersionsAndHeaderBridge() {
        assertEquals(
                2L,
                FinanceCashMatchCorrectionController.body(
                                Map.of("expectedVersion", 2), null, "\"2\"")
                        .get("expectedVersion"));
        assertThrows(
                FinanceException.class,
                () ->
                        FinanceCashMatchCorrectionController.body(
                                Map.of("expectedVersion", "2"), null, "2"));
        for (String bad :
                List.of("02", "2.0", "-1", "0", "\"2", "2\"", "999999999999999999999999999"))
            assertThrows(
                    FinanceException.class,
                    () -> FinanceCashMatchCorrectionController.body(Map.of(), null, bad));
        assertThrows(
                FinanceException.class,
                () ->
                        FinanceCashMatchCorrections.number(
                                Map.of("targetLineNo", "1"), "targetLineNo", false));
        assertEquals(
                0L,
                FinanceCashMatchCorrections.number(
                        Map.of("expectedSourceMatchGeneration", 0),
                        "expectedSourceMatchGeneration",
                        true));
    }
}
