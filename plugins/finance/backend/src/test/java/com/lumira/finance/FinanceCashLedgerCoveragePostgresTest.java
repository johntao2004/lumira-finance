package com.lumira.finance;

import static org.junit.jupiter.api.Assertions.*;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuthorizationContextProjectionPort;

import org.junit.jupiter.api.*;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

class FinanceCashLedgerCoveragePostgresTest {
    static FinanceCashLedgerCoverage coverage;
    static FinanceStore db;
    static final Set<String> BASE = Set.of("platform:finance:access", "platform:finance:read");
    static AtomicReference<AuthorizationContextProjectionPort.Snapshot> snapshot =
            new AtomicReference<>();
    static boolean absent;

    @BeforeAll
    static void setup() throws Exception {
        FinanceCashAllocationsPostgresTest.setup();
        db = FinanceCashAllocationsPostgresTest.db;
        AdministrationPort guard =
                new AdministrationPort() {
                    public Set<String> require(String a, String p) {
                        if (!snapshot.get().effectiveAuthorities().contains(p))
                            throw new FinanceException("SCOPE_DENIED", "permission revoked");
                        return snapshot.get().effectiveAuthorities();
                    }

                    public void requireUser(String a, UUID u, String p) {}

                    public void requireGlobal(String a, String p) {}

                    public boolean hasGlobal(String a, String p) {
                        return false;
                    }
                };
        coverage =
                new FinanceCashLedgerCoverage(
                        db,
                        FinancePostgresTest.service,
                        guard,
                        a -> absent ? Optional.empty() : Optional.of(snapshot.get()));
    }

    @BeforeEach
    void reset() {
        snapshot.set(
                new AuthorizationContextProjectionPort.Snapshot(BASE, Set.of(), false, null, 0));
        absent = false;
    }

    @AfterAll
    static void cleanup() {
        FinanceCashAllocationsPostgresTest.cleanup();
    }

    static Map<String, Object> preview(
            String actor,
            FinanceCashMovementsPostgresTest.Fixture f,
            int limit,
            String cursor,
            String filter) {
        var tx = new TransactionTemplate(FinancePostgresTest.tx.getTransactionManager());
        tx.setReadOnly(true);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return tx.execute(
                t ->
                        coverage.preview(
                                actor,
                                f.f().book(),
                                f.f().year(),
                                (UUID) f.scope().get("id"),
                                limit,
                                cursor,
                                filter));
    }

    static void code(String code, Runnable r) {
        assertEquals(code, assertThrows(FinanceException.class, r::run).code);
    }

    static Map<String, Object> summary(Map<String, Object> p) {
        return (Map<String, Object>) p.get("summary");
    }

    static List<Map<String, Object>> rows(Map<String, Object> p) {
        return (List<Map<String, Object>>) p.get("items");
    }

    static long n(Map<String, Object> m, String k) {
        return ((Number) m.get(k)).longValue();
    }

    static String facts() {
        var all = new ArrayList<Object>();
        for (var t :
                db.list(
                        "SELECT tablename FROM pg_tables WHERE schemaname=current_schema() AND"
                            + " tablename LIKE 'plugin_finance_%' ORDER BY tablename")) {
            String name = t.get("tablename").toString();
            assertTrue(name.matches("plugin_finance_[a-z_]+"));
            all.add(
                    db.list(
                            "SELECT to_jsonb(t)::text value FROM "
                                    + name
                                    + " t ORDER BY to_jsonb(t)::text"));
        }
        return coverage.hash(all);
    }

    @Test
    void bothStandardsCountAllPostedLegsNotOnlyApprovedAllocationAndNeverWrite() {
        for (String standard : List.of("CAS", "SMALL")) {
            var c = FinanceCashAllocationsPostgresTest.fixture(standard.equals("SMALL"), standard);
            var a = FinanceCashAllocationsPostgresTest.approved(c);
            FinanceCashMatchCorrectionsPostgresTest.target(c.f());
            var f = c.source().original();
            long count =
                    n(
                            db.one(
                                    "SELECT count(*) n FROM plugin_finance_line l JOIN"
                                        + " plugin_finance_journal j"
                                        + " ON(j.tenant_id,j.book_id,j.year_id,j.id)=(l.tenant_id,l.book_id,l.year_id,l.journal_id)"
                                        + " WHERE j.book_id=? AND j.year_id=? AND j.status='POSTED'"
                                        + " AND j.date BETWEEN '2026-01-01' AND '2026-01-31' AND"
                                        + " l.account_id=?",
                                    c.f().book(),
                                    c.f().year(),
                                    c.f().cash()),
                            "n");
            String before = facts();
            var p = preview("maker", f, 100, null, null);
            assertEquals(count, n(summary(p), "populationRowCount"));
            assertEquals("100.01000000", summary(p).get("supportedInTotal"));
            assertTrue(
                    rows(p).stream()
                            .anyMatch(r -> r.get("primaryStage").equals("NO_ACTIVE_CLAIM")));
            assertEquals("MISSING", p.get("externalSourceCompleteness"));
            assertEquals(false, p.get("coverageVerified"));
            assertEquals(false, p.get("formalReportContributionEnabled"));
            assertEquals(false, p.get("reportPublishable"));
            assertEquals("READ_ONLY_PREVIEW", p.get("mode"));
            assertEquals(before, facts());
            var filtered = preview("reviewer", f, 100, null, "SUPPORTED_CHAIN");
            assertEquals(1, rows(filtered).size());
            assertEquals(count, n(summary(filtered), "populationRowCount"));
            assertEquals(1, n(summary(filtered), "displayMatchedRowCount"));
            assertEquals(
                    a.get("id").toString(),
                    ((Map<?, ?>) rows(filtered).getFirst().get("allocation")).get("id"));
        }
    }

    @Test
    void cursorBindsAuthFactsFullProposalHistoryAndClosedScope() {
        var c = FinanceCashAllocationsPostgresTest.fixture(false, "CAS");
        FinanceCashMatchCorrectionsPostgresTest.target(c.f());
        var f = c.source().original();
        var first = preview("maker", f, 1, null, null);
        String cursor = first.get("nextCursor").toString();
        var second = preview("maker", f, 1, cursor, null);
        assertNotEquals(
                rows(first).getFirst().get("journalId"), rows(second).getFirst().get("journalId"));
        assertEquals(first.get("contextDigest"), second.get("contextDigest"));
        code("INVALID_INPUT", () -> preview("reviewer", f, 1, cursor, null));
        code("INVALID_INPUT", () -> preview("maker", f, 2, cursor, null));
        code("INVALID_INPUT", () -> preview("maker", f, 1, cursor, "SUPPORTED_CHAIN"));
        snapshot.set(
                new AuthorizationContextProjectionPort.Snapshot(
                        BASE, Set.of(), true, UUID.randomUUID(), 3));
        code("CASH_COVERAGE_PREVIEW_STALE", () -> preview("maker", f, 1, cursor, null));
        reset();
        absent = true;
        code("SCOPE_DENIED", () -> preview("maker", f, 1, cursor, null));
        absent = false;
        var d =
                FinanceCashAllocationsPostgresTest.write(
                        "maker",
                        c,
                        "create",
                        null,
                        null,
                        FinanceCashAllocationsPostgresTest.body(c, null));
        assertEquals(0, FinanceCashAllocationsPostgresTest.version(c));
        code("CASH_COVERAGE_PREVIEW_STALE", () -> preview("maker", f, 1, cursor, null));
        var fresh = preview("maker", f, 1, null, null);
        String freshCursor = fresh.get("nextCursor").toString();
        FinanceCashAllocationsPostgresTest.write(
                "maker",
                c,
                "cancel",
                (UUID) d.get("id"),
                null,
                FinanceCashAllocationsPostgresTest.action(d));
        code("CASH_COVERAGE_PREVIEW_STALE", () -> preview("maker", f, 1, freshCursor, null));
        assertNotEquals(
                first.get("contextDigest"),
                preview("maker", f, 1, null, null).get("contextDigest"));
        db.update(
                "UPDATE plugin_finance_period SET status='CLOSED' WHERE book_id=? AND year_id=? AND"
                    + " month=1",
                c.f().book(),
                c.f().year());
        var closed = preview("maker", f, 100, null, null);
        assertEquals("CLOSED", ((Map<?, ?>) closed.get("scope")).get("periodStatus"));
        code("CASH_COVERAGE_PREVIEW_STALE", () -> preview("maker", f, 1, freshCursor, null));
        FinancePostgresTest.write(
                "maker",
                c.f().book(),
                null,
                "members",
                null,
                Map.of("username", "reviewer", "role", "READER", "enabled", false));
        code("SCOPE_DENIED", () -> preview("reviewer", f, 1, null, null));
    }

    @Test
    void missingPostingNaNAndLegacyFactsStayInDenominator() {
        var f = FinanceCashMovementsPostgresTest.fixture("CAS");
        var missing = FinanceCashMatchCorrectionsPostgresTest.target(f.f());
        var nan = FinanceCashMatchCorrectionsPostgresTest.target(f.f());
        var legacy = FinanceCashMatchCorrectionsPostgresTest.target(f.f());
        try {
            db.update("ALTER TABLE plugin_finance_posting DISABLE TRIGGER USER");
            db.update(
                    "DELETE FROM plugin_finance_posting WHERE journal_id=?",
                    missing.get("journalId"));
            db.update("ALTER TABLE plugin_finance_posting ENABLE TRIGGER USER");
            db.update("ALTER TABLE plugin_finance_line DISABLE TRIGGER USER");
            db.update(
                    "UPDATE plugin_finance_line SET debit='NaN'::numeric WHERE journal_id=? AND"
                        + " account_id=?",
                    nan.get("journalId"),
                    f.f().cash());
            db.update("ALTER TABLE plugin_finance_line ENABLE TRIGGER USER");
            db.update("ALTER TABLE plugin_finance_journal DISABLE TRIGGER USER");
            db.update(
                    "UPDATE plugin_finance_journal SET amount_format_version='LEGACY_6' WHERE id=?",
                    legacy.get("journalId"));
            db.update("ALTER TABLE plugin_finance_journal ENABLE TRIGGER USER");
            var p = preview("maker", f, 100, null, null);
            assertEquals(4, n(summary(p), "populationRowCount"));
            assertEquals("UNDETERMINED", p.get("resultStatus"));
            assertNull(summary(p).get("ledgerDebitTotal"));
            assertEquals("PARTIAL_UNSUPPORTED_ROWS", summary(p).get("directionalTotalsStatus"));
            var nr =
                    rows(p).stream()
                            .filter(r -> r.get("journalId").equals(nan.get("journalId").toString()))
                            .findFirst()
                            .orElseThrow();
            assertNull(nr.get("debit"));
            assertNull(nr.get("normalizedAmount"));
            assertEquals("UNKNOWN", nr.get("direction"));
            assertTrue(
                    rows(p).stream()
                            .flatMap(r -> ((List<Map<String, Object>>) r.get("issues")).stream())
                            .anyMatch(i -> i.get("code").equals("MISSING_POSTING")));
            var lr =
                    rows(p).stream()
                            .filter(
                                    r ->
                                            r.get("journalId")
                                                    .equals(legacy.get("journalId").toString()))
                            .findFirst()
                            .orElseThrow();
            assertEquals("100.010000", lr.get("debit"));
        } finally {
            for (String t : List.of("posting", "line", "journal"))
                db.update("ALTER TABLE plugin_finance_" + t + " ENABLE TRIGGER USER");
        }
    }

    @Test
    void documentVersionIsNotOriginalVersionAndPendingReleaseRetainsEffect() {
        var v = FinanceCashMovementsPostgresTest.fixture("CAS");
        var body = FinanceCashMovementsPostgresTest.body(v);
        body.put("sourceDocumentVersion", 2);
        var raw = FinanceCashMovementsPostgresTest.write("maker", v, null, body);
        var p = preview("maker", v, 100, null, null);
        assertFalse(
                ((List<Map<String, Object>>) rows(p).getFirst().get("issues"))
                        .stream().anyMatch(i -> i.get("code").equals("STALE_EVIDENCE")));
        assertEquals("NO_EFFECTIVE_REVIEW", rows(p).getFirst().get("primaryStage"));
        var c = FinanceCashAllocationsPostgresTest.fixture(false, "SMALL");
        var a = FinanceCashAllocationsPostgresTest.approved(c);
        var req = FinanceCashAllocationsPostgresTest.request(c, a);
        var pending = preview("maker", c.source().original(), 100, null, null);
        var row = rows(pending).getFirst();
        assertEquals(true, row.get("currentChainEffective"));
        assertEquals(true, row.get("newUseFrozen"));
        assertEquals("DEPENDENCY_PENDING", row.get("primaryStage"));
        assertEquals("0.00000000", summary(pending).get("supportedInTotal"));
        FinanceCashAllocationsPostgresTest.write(
                "reviewer",
                c,
                "release-approve",
                (UUID) a.get("id"),
                (UUID) req.get("id"),
                FinanceCashAllocationsPostgresTest.decision(req));
        assertEquals(
                "NO_EFFECTIVE_ALLOCATION",
                rows(preview("maker", c.source().original(), 100, null, null))
                        .getFirst()
                        .get("primaryStage"));
    }

    @Test
    void suspectedInternalCashNeverNetsOrBecomesSupported() {
        var f = FinanceCashMovementsPostgresTest.fixture("CAS");
        var input = new LinkedHashMap<String, Object>();
        input.put("date", "2026-01-11");
        input.put("summary", "Cash transfer requires explicit future protocol");
        input.put(
                "lines",
                List.of(
                        Map.of(
                                "accountId",
                                f.f().cash().toString(),
                                "debit",
                                "100.00",
                                "credit",
                                "0"),
                        Map.of(
                                "accountId",
                                f.f().cash().toString(),
                                "debit",
                                "0",
                                "credit",
                                "100.00")));
        var j =
                FinancePostgresTest.write(
                        "maker", f.f().book(), f.f().year(), "journal", null, input);
        UUID id = (UUID) j.get("id");
        FinancePostgresTest.action("maker", f.f(), id, "submit", 1);
        FinancePostgresTest.action("reviewer", f.f(), id, "approve", 2);
        FinancePostgresTest.action("maker", f.f(), id, "post", 3);
        var p = preview("maker", f, 100, null, null);
        assertEquals("200.01000000", summary(p).get("ledgerInTotal"));
        assertEquals("100.00000000", summary(p).get("ledgerOutTotal"));
        for (var r : rows(p))
            if (r.get("journalId").equals(id.toString())) {
                assertEquals("UNSUPPORTED_LEDGER_FACT", r.get("primaryStage"));
                assertTrue(
                        ((List<Map<String, Object>>) r.get("issues"))
                                .stream()
                                        .anyMatch(
                                                i ->
                                                        i.get("code")
                                                                .equals(
                                                                        "COMPLEX_FACT_REQUIRES_JUDGMENT")));
            }
    }

    @Test
    void boundedFiveThousandRowPlanAndWholeScopeOverflow() throws Exception {
        var f = FinanceCashMovementsPostgresTest.fixture("CAS");
        var b =
                FinancePostgresTest.service.originalScope(
                        "maker", f.f().book(), f.f().year(), "read");
        try {
            db.update("ALTER TABLE plugin_finance_journal DISABLE TRIGGER USER");
            db.update(
                    "INSERT INTO"
                        + " plugin_finance_journal(tenant_id,book_id,year_id,id,period_id,date,summary,status,version,created_by)"
                        + " SELECT ?,?,?,gen_random_uuid(),(SELECT id FROM plugin_finance_period"
                        + " WHERE book_id=? AND year_id=? AND month=1),'2026-01-12','bounded"
                        + " coverage fixture','POSTED',4,? FROM generate_series(1,4999)",
                    b.get("tenant_id"),
                    f.f().book(),
                    f.f().year(),
                    f.f().book(),
                    f.f().year(),
                    FinancePostgresTest.users.get("maker"));
            db.update("ALTER TABLE plugin_finance_journal ENABLE TRIGGER USER");
            db.update("ALTER TABLE plugin_finance_line DISABLE TRIGGER USER");
            db.update(
                    "INSERT INTO"
                        + " plugin_finance_line(tenant_id,book_id,year_id,journal_id,line_no,account_id,debit,credit)"
                        + " SELECT tenant_id,book_id,year_id,id,1,?,1,0 FROM plugin_finance_journal"
                        + " WHERE book_id=? AND summary='bounded coverage fixture'",
                    f.f().cash(),
                    f.f().book());
            db.update("ALTER TABLE plugin_finance_line ENABLE TRIGGER USER");
            long begin = System.nanoTime();
            var p = preview("maker", f, 100, null, null);
            long millis = (System.nanoTime() - begin) / 1000000;
            assertEquals(5000, n(summary(p), "populationRowCount"));
            assertEquals(100, rows(p).size());
            assertTrue(millis < 30000, "5000-row bounded preview ms=" + millis);
            var plan =
                    db.list(
                            "EXPLAIN(ANALYZE,BUFFERS,FORMAT JSON) "
                                    + FinanceCashLedgerCoverage.CTE
                                    + "SELECT q.*,p.posting_seq FROM pop q CROSS JOIN ctx x LEFT"
                                    + " JOIN plugin_finance_posting p"
                                    + " ON(p.tenant_id,p.book_id,p.year_id,p.journal_id)=(x.t,x.b,x.y,q.journal_id)"
                                    + " ORDER BY q.date,q.journal_id,q.line_no LIMIT 5001",
                            b.get("tenant_id"),
                            f.f().book(),
                            f.f().year(),
                            f.scope().get("id"));
            Files.writeString(
                    Path.of("../../../.runtime/cash-coverage-query-plan.txt"),
                    "previewMillis=" + millis + "\n" + plan.toString());
            db.update("ALTER TABLE plugin_finance_line DISABLE TRIGGER USER");
            db.update(
                    "INSERT INTO"
                        + " plugin_finance_line(tenant_id,book_id,year_id,journal_id,line_no,account_id,debit,credit)"
                        + " SELECT tenant_id,book_id,year_id,id,2,?,1,0 FROM plugin_finance_journal"
                        + " WHERE book_id=? AND summary='bounded coverage fixture' LIMIT 1",
                    f.f().cash(),
                    f.f().book());
            db.update("ALTER TABLE plugin_finance_line ENABLE TRIGGER USER");
            code("PREVIEW_LIMIT_EXCEEDED", () -> preview("maker", f, 100, null, null));
        } finally {
            for (String t : List.of("line", "journal"))
                db.update("ALTER TABLE plugin_finance_" + t + " ENABLE TRIGGER USER");
        }
    }
}
