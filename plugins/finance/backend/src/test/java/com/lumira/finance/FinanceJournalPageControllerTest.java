package com.lumira.finance;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionTimedOutException;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

class FinanceJournalPageControllerTest {
    static final UUID B = UUID.randomUUID(), Y = UUID.randomUUID();

    @Test
    void closedQueryTypesPairsAndVerbatimKeyword() {
        var q =
                FinanceJournalPagesPostgresTest.q(
                        "keyword", "  %_\\  ", "dateFrom", "2026-01-01", "dateTo", "2026-01-31");
        var n = FinanceJournalPages.query("journals", q);
        assertEquals("  %_\\  ", n.keyword());
        assertNull(
                FinanceJournalPages.query(
                                "journals", FinanceJournalPagesPostgresTest.q("keyword", ""))
                        .keyword());
        for (var bad :
                List.of(
                        FinanceJournalPagesPostgresTest.q("dateFrom", "2026-01-01"),
                        FinanceJournalPagesPostgresTest.q("keyword", "x"),
                        FinanceJournalPagesPostgresTest.q(
                                "keyword",
                                "😀".repeat(60),
                                "dateFrom",
                                "2026-01-01",
                                "dateTo",
                                "2026-01-31"),
                        FinanceJournalPagesPostgresTest.q("limit", "01"),
                        FinanceJournalPagesPostgresTest.q("status", "null"),
                        FinanceJournalPagesPostgresTest.q("querySql", "evil"))) {
            assertThrows(FinanceException.class, () -> FinanceJournalPages.query("journals", bad));
        }
        q.add("keyword", "duplicate");
        assertThrows(FinanceException.class, () -> FinanceJournalPages.query("journals", q));
        assertThrows(
                FinanceException.class,
                () ->
                        FinanceJournalPages.query(
                                "ledger",
                                FinanceJournalPagesPostgresTest.q("accountId", B, "keyword", "x")));
        assertEquals("%\\%\\_\\\\%", FinanceJournalPages.pattern("%_\\"));
    }

    @Test
    void twoRoutesDispatchAndNewErrorsHaveExactStatuses() {
        var service = mock(FinanceJournalPages.class);
        var c = new FinanceJournalPageController(service);
        var a = FinanceHttpTest.actor();
        var q = FinanceJournalPagesPostgresTest.q();
        when(service.page("maker", B, Y, "journals", q)).thenReturn(Map.of("resource", "journals"));
        assertEquals(
                200,
                ((ResponseEntity<?>) c.journals(a, B.toString(), Y.toString(), q))
                        .getStatusCode()
                        .value());
        for (var e :
                Map.of(
                                "LIST_PAGE_STALE",
                                409,
                                "QUERY_BUDGET_EXCEEDED",
                                422,
                                "LIST_VALUE_UNSUPPORTED",
                                422,
                                "LIST_BASELINE_UNAVAILABLE",
                                503)
                        .entrySet()) {
            doThrow(new FinanceException(e.getKey(), "safe new page failure"))
                    .when(service)
                    .page("maker", B, Y, "journals", q);
            FinanceHttpTest.error(
                    c.journals(a, B.toString(), Y.toString(), q), e.getValue(), e.getKey());
        }
        doThrow(new QueryTimeoutException("private SQL omitted"))
                .when(service)
                .page("maker", B, Y, "ledger", q);
        var error =
                FinanceHttpTest.error(
                        c.ledger(a, B.toString(), Y.toString(), q), 422, "QUERY_BUDGET_EXCEEDED");
        assertFalse(error.toString().contains("private SQL"));
        FinanceHttpTest.error(
                c.journals(a, B.toString().toUpperCase(Locale.ROOT), Y.toString(), q),
                400,
                "INVALID_INPUT");
        assertEquals(
                2,
                Arrays.stream(FinanceJournalPageController.class.getDeclaredMethods())
                        .filter(
                                m ->
                                        m.isAnnotationPresent(
                                                org.springframework.web.bind.annotation.GetMapping
                                                        .class))
                        .count());
    }

    @Test
    void safeOutputAndCanonicalTechnicalStringsNeverRound() {
        assertThrows(FinanceException.class, () -> FinanceJournalPages.accountString("", 100));
        assertEquals("9223372036854775807", FinanceJournalPages.counter("9223372036854775807"));
        for (Object v : List.of("00", "+1", " 1", "1e3", "9223372036854775808", 1L))
            assertThrows(FinanceException.class, () -> FinanceJournalPages.counter(v));
        assertThrows(
                FinanceException.class,
                () -> FinanceJournalPages.safe(9007199254740992L, FinanceJournalPages.MAX_SAFE));
        assertEquals("100.123456", FinanceJournalPages.amount("100.12345600", "LEGACY_6"));
        assertEquals("100.12000000", FinanceJournalPages.amount("100.12", "V4_8"));
        for (String v : List.of("NaN", "Infinity", "-1", "100000000000000000000.00"))
            assertEquals(
                    "LIST_VALUE_UNSUPPORTED",
                    assertThrows(
                                    FinanceException.class,
                                    () -> FinanceJournalPages.amount(v, "V4_8"))
                            .code);
        assertThrows(
                FinanceException.class, () -> FinanceJournalPages.amount("1.12345678", "LEGACY_6"));
        assertThrows(FinanceException.class, () -> FinanceJournalPages.amount("1.00", "UNKNOWN"));
    }

    @Test
    void realTimeoutTypesAndMonotonicDeadlineAreNarrowlyMapped() {
        for (RuntimeException failure :
                List.of(
                        new QueryTimeoutException("timeout"),
                        new TransactionTimedOutException("transaction deadline"),
                        new RuntimeException(new SQLException("SQL hidden", "57014"))))
            assertEquals(
                    "QUERY_BUDGET_EXCEEDED",
                    assertThrows(
                                    FinanceException.class,
                                    () ->
                                            FinanceJournalPages.boundary(
                                                    () -> {
                                                        throw failure;
                                                    }))
                            .code);
        var unrelated = new RuntimeException(new SQLException("not timeout", "23505"));
        assertSame(
                unrelated,
                assertThrows(
                        RuntimeException.class,
                        () ->
                                FinanceJournalPages.boundary(
                                        () -> {
                                            throw unrelated;
                                        })));
        var pages = new FinanceJournalPages(null, null, null, null, null);
        var nanos = new AtomicLong();
        var budget = pages.new Budget(nanos::get);
        nanos.set(9990000000L);
        assertDoesNotThrow(budget::check);
        nanos.set(10000000000L);
        assertEquals(
                "QUERY_BUDGET_EXCEEDED", assertThrows(FinanceException.class, budget::check).code);
    }
}
