package com.lumira.finance;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;

import java.util.*;

class FinanceCashLedgerCoverageControllerTest {
    static final UUID BOOK = UUID.randomUUID(), YEAR = UUID.randomUUID(), SCOPE = UUID.randomUUID();

    static LinkedMultiValueMap<String, String> query() {
        var q = new LinkedMultiValueMap<String, String>();
        q.add("scopeRevisionId", SCOPE.toString());
        return q;
    }

    @Test
    void queryIsClosedAndDuplicateOrMalformedValuesNeverDispatch() {
        var service = mock(FinanceCashLedgerCoverage.class);
        var c = new FinanceCashLedgerCoverageController(service);
        var a = FinanceHttpTest.actor();
        for (String key : List.of("scopeRevisionId", "limit", "cursor", "primaryStage")) {
            var q = query();
            if (!q.containsKey(key)) q.add(key, "first");
            q.add(key, "extra");
            FinanceHttpTest.error(c.preview(a, BOOK, YEAR, q), 400, "INVALID_INPUT");
        }
        var q = query();
        q.add("accountId", UUID.randomUUID().toString());
        FinanceHttpTest.error(c.preview(a, BOOK, YEAR, q), 400, "INVALID_INPUT");
        for (String val : List.of("0", "1010", "1.0", "+1", "01", "null", "")) {
            q = query();
            q.add("limit", val);
            FinanceHttpTest.error(c.preview(a, BOOK, YEAR, q), 400, "INVALID_INPUT");
        }
        FinanceHttpTest.error(
                c.preview(a, BOOK, YEAR, new LinkedMultiValueMap<>()), 400, "INVALID_INPUT");
        verifyNoInteractions(service);
    }

    @Test
    void singleGetDispatchAndStructuredErrorStatusesAreExact() {
        var service = mock(FinanceCashLedgerCoverage.class);
        var c = new FinanceCashLedgerCoverageController(service);
        var a = FinanceHttpTest.actor();
        when(service.preview("maker", BOOK, YEAR, SCOPE, 20, null, null))
                .thenReturn(Map.of("mode", "READ_ONLY_PREVIEW"));
        var r = (ResponseEntity<?>) c.preview(a, BOOK, YEAR, query());
        assertEquals(200, r.getStatusCode().value());
        assertEquals("READ_ONLY_PREVIEW", ((Map<?, ?>) r.getBody()).get("mode"));
        for (var entry :
                Map.of(
                                "CASH_COVERAGE_SCOPE_UNAVAILABLE",
                                409,
                                "CASH_COVERAGE_PREVIEW_STALE",
                                409,
                                "PREVIEW_LIMIT_EXCEEDED",
                                422,
                                "PREVIEW_TOTAL_OVERFLOW",
                                422,
                                "SCOPE_DENIED",
                                403)
                        .entrySet()) {
            doThrow(new FinanceException(entry.getKey(), "Safe coverage error"))
                    .when(service)
                    .preview("maker", BOOK, YEAR, SCOPE, 20, null, null);
            FinanceHttpTest.error(
                    c.preview(a, BOOK, YEAR, query()), entry.getValue(), entry.getKey());
        }
        assertEquals(
                1,
                Arrays.stream(FinanceCashLedgerCoverageController.class.getDeclaredMethods())
                        .filter(
                                m ->
                                        m.isAnnotationPresent(
                                                org.springframework.web.bind.annotation.GetMapping
                                                        .class))
                        .count());
    }

    @Test
    void exactAggregateCapacityAndUnknownAreNotRoundedIntoZero() {
        assertNull(FinanceCashLedgerCoverage.decimal("NaN"));
        assertNull(FinanceCashLedgerCoverage.decimal("Infinity"));
        assertEquals(
                "1234567890123456789012345.12000000",
                FinanceCashLedgerCoverage.total(
                        new java.math.BigDecimal("1234567890123456789012345.12")));
        assertEquals(
                "PREVIEW_TOTAL_OVERFLOW",
                assertThrows(
                                FinanceException.class,
                                () ->
                                        FinanceCashLedgerCoverage.total(
                                                new java.math.BigDecimal(
                                                        "12345678901234567890123456.12")))
                        .code);
        var coverage = new FinanceCashLedgerCoverage(null, null, null, null);
        var row =
                FinanceCashLedgerCoverage.map(
                        "debit",
                        null,
                        "credit",
                        "0.00000000",
                        "direction",
                        "UNKNOWN",
                        "normalizedAmount",
                        null,
                        "primaryStage",
                        "UNSUPPORTED_LEDGER_FACT");
        var summary = coverage.summary(List.of(row), 1);
        assertNull(summary.get("ledgerDebitTotal"));
        assertEquals("PARTIAL_UNSUPPORTED_ROWS", summary.get("directionalTotalsStatus"));
        assertEquals(1L, summary.get("unknownDirectionRowCount"));
    }
}
