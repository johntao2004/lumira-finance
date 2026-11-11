package com.lumira.finance;

import static org.junit.jupiter.api.Assertions.*;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuthorizationContextProjectionPort;

import org.junit.jupiter.api.*;
import org.springframework.util.LinkedMultiValueMap;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

class FinanceJournalPagesPostgresTest {
    static FinanceStore db;
    static FinanceJournalPages pages;
    static final Set<String> AUTHORITIES =
            Set.of("platform:finance:access", "platform:finance:read");
    static final AtomicReference<AuthorizationContextProjectionPort.Snapshot> snapshot =
            new AtomicReference<>();
    static boolean missing;

    static FinanceJournalPages create(FinanceStore store) {
        AdministrationPort guard =
                new AdministrationPort() {
                    public Set<String> require(String a, String p) {
                        if (!snapshot.get().effectiveAuthorities().contains(p))
                            throw new FinanceException("SCOPE_DENIED", "revoked");
                        return snapshot.get().effectiveAuthorities();
                    }

                    public void requireUser(String a, UUID t, String p) {}

                    public void requireGlobal(String a, String p) {}

                    public boolean hasGlobal(String a, String p) {
                        return false;
                    }
                };
        return new FinanceJournalPages(
                store,
                FinancePostgresTest.service,
                guard,
                a -> missing ? Optional.empty() : Optional.of(snapshot.get()),
                FinancePostgresTest.tx.getTransactionManager());
    }

    static void resetContext() {
        snapshot.set(
                new AuthorizationContextProjectionPort.Snapshot(
                        AUTHORITIES, Set.of(), false, null, 0));
        missing = false;
    }

    @BeforeAll
    static void setup() throws Exception {
        FinanceCashMovementsPostgresTest.setup();
        db = FinanceCashMovementsPostgresTest.db;
        pages = create(db);
    }

    @BeforeEach
    void reset() {
        resetContext();
    }

    @AfterAll
    static void cleanup() {
        FinanceCashMovementsPostgresTest.cleanup();
    }

    static LinkedMultiValueMap<String, String> q(Object... args) {
        var q = new LinkedMultiValueMap<String, String>();
        for (int i = 0; i < args.length; i += 2)
            if (args[i + 1] != null) q.add(args[i].toString(), args[i + 1].toString());
        return q;
    }

    static Map<String, Object> read(
            String actor,
            FinancePostgresTest.Fixture f,
            String resource,
            LinkedMultiValueMap<String, String> q) {
        return pages.page(actor, f.book(), f.year(), resource, q);
    }

    static List<Map<String, Object>> items(Map<String, Object> p) {
        return (List<Map<String, Object>>) p.get("items");
    }

    static void code(String code, Runnable work) {
        assertEquals(code, assertThrows(FinanceException.class, work::run).code);
    }

    static long generation(FinancePostgresTest.Fixture f) {
        return ((Number)
                        db.one(
                                        "SELECT generation n FROM plugin_finance_journal_list_guard"
                                                + " WHERE book_id=? AND year_id=?",
                                        f.book(),
                                        f.year())
                                .get("n"))
                .longValue();
    }

    static Map<String, Object> journal(
            FinancePostgresTest.Fixture f, String summary, int cashLines) {
        var lines = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < cashLines; i++)
            lines.add(Map.of("accountId", f.cash().toString(), "debit", "1.00", "credit", "0"));
        lines.add(
                Map.of(
                        "accountId",
                        f.capital().toString(),
                        "debit",
                        "0",
                        "credit",
                        cashLines + ".00"));
        return FinancePostgresTest.write(
                "maker",
                f.book(),
                f.year(),
                "journal",
                null,
                Map.of("date", "2026-01-10", "summary", summary, "lines", lines));
    }

    static Map<String, Object> post(FinancePostgresTest.Fixture f, String summary, int lines) {
        var j = journal(f, summary, lines);
        UUID id = (UUID) j.get("id");
        FinancePostgresTest.action("maker", f, id, "submit", 1);
        FinancePostgresTest.action("reviewer", f, id, "approve", 2);
        FinancePostgresTest.action("maker", f, id, "post", 3);
        return j;
    }

    static void bulk(FinancePostgresTest.Fixture f, int count) {
        var b = FinancePostgresTest.service.originalScope("maker", f.book(), f.year(), "read");
        db.update(
                "INSERT INTO"
                    + " plugin_finance_journal(tenant_id,book_id,year_id,id,period_id,date,summary,created_by)"
                    + " SELECT ?,?,?,gen_random_uuid(),(SELECT id FROM plugin_finance_period WHERE"
                    + " book_id=? AND year_id=? AND month=1),'2026-01-10','bulk fixture '||i,? FROM"
                    + " generate_series(1,?) i",
                b.get("tenant_id"),
                f.book(),
                f.year(),
                f.book(),
                f.year(),
                FinancePostgresTest.users.get("maker"),
                count);
        db.update(
                "INSERT INTO"
                    + " plugin_finance_line(tenant_id,book_id,year_id,journal_id,line_no,account_id,debit,credit)"
                    + " SELECT tenant_id,book_id,year_id,id,1,?,1,0 FROM plugin_finance_journal"
                    + " WHERE book_id=? AND summary LIKE 'bulk fixture %' UNION ALL SELECT"
                    + " tenant_id,book_id,year_id,id,2,?,0,1 FROM plugin_finance_journal WHERE"
                    + " book_id=? AND summary LIKE 'bulk fixture %'",
                f.cash(), f.book(), f.capital(), f.book());
    }

    static String facts() {
        var rows = new ArrayList<Object>();
        for (var t :
                db.list(
                        "SELECT tablename FROM pg_tables WHERE schemaname=current_schema() AND"
                                + " tablename LIKE 'plugin_finance_%' ORDER BY tablename")) {
            String name = t.get("tablename").toString();
            assertTrue(name.matches("plugin_finance_[a-z_]+"));
            rows.add(
                    db.list(
                            "SELECT to_jsonb(t)::text value FROM "
                                    + name
                                    + " t ORDER BY to_jsonb(t)::text"));
        }
        return pages.hash(rows);
    }

    @Test
    void beyondTwoHundredBothDirectionsAndEveryLedgerLineWithoutWrites() {
        for (String standard : List.of("CAS", "SMALL")) {
            var f = FinancePostgresTest.fixture(standard);
            bulk(f, standard.equals("CAS") ? 305 : 21);
            post(f, "many same-account cash legs", 24);
            post(f, "one more cash leg", 1);
            String before = facts();
            var first = read("maker", f, "journals", q("limit", 37));
            assertNull(first.get("totalCount"));
            assertEquals("NOT_COMPUTED", first.get("totalStatus"));
            assertEquals(false, first.get("hasPrevious"));
            var seen = new HashSet<String>();
            var current = first;
            Map<String, Object> second = null;
            while (true) {
                for (var row : items(current)) assertTrue(seen.add(row.get("id").toString()));
                if (!Boolean.TRUE.equals(current.get("hasNext"))) break;
                current =
                        read(
                                "maker",
                                f,
                                "journals",
                                q("limit", 37, "cursor", current.get("nextCursor")));
                if (second == null) second = current;
            }
            assertEquals(standard.equals("CAS") ? 307 : 23, seen.size());
            if (second != null) {
                var back =
                        read(
                                "maker",
                                f,
                                "journals",
                                q("limit", 37, "cursor", second.get("previousCursor")));
                assertEquals(items(first), items(back));
            }
            assertEquals(
                    Math.min(seen.size(), 200),
                    FinancePostgresTest.read("maker", f.book(), f.year(), "journals").size());
            var ledgerFirst = read("reviewer", f, "ledger", q("accountId", f.cash(), "limit", 7));
            var lineKeys = new HashSet<String>();
            current = ledgerFirst;
            Map<String, Object> ledgerSecond = null;
            while (true) {
                for (var row : items(current)) {
                    assertTrue(lineKeys.add(row.get("journalId") + ":" + row.get("lineNo")));
                    assertEquals("V4_8", row.get("amountFormatVersion"));
                    assertEquals("1.00000000", row.get("debit"));
                    assertInstanceOf(String.class, row.get("postingSeq"));
                }
                if (!Boolean.TRUE.equals(current.get("hasNext"))) break;
                current =
                        read(
                                "reviewer",
                                f,
                                "ledger",
                                q(
                                        "accountId",
                                        f.cash(),
                                        "limit",
                                        7,
                                        "cursor",
                                        current.get("nextCursor")));
                if (ledgerSecond == null) ledgerSecond = current;
            }
            assertEquals(25, lineKeys.size());
            assertEquals(
                    items(ledgerFirst),
                    items(
                            read(
                                    "reviewer",
                                    f,
                                    "ledger",
                                    q(
                                            "accountId",
                                            f.cash(),
                                            "limit",
                                            7,
                                            "cursor",
                                            ledgerSecond.get("previousCursor")))));
            assertEquals(before, facts());
            var trial =
                    (Map<?, ?>)
                            FinancePostgresTest.service.read(
                                    "maker", f.book(), f.year(), "trial-balance", null, null);
            assertEquals("25.00000000", trial.get("debit"));
        }
    }

    @Test
    void statementTransitionsNoOpRollbackAndIdempotentReplayAreReal() {
        var f = FinancePostgresTest.fixture("CAS");
        assertEquals(0, generation(f));
        bulk(f, 5);
        assertEquals(2, generation(f));
        long n = generation(f);
        db.update("UPDATE plugin_finance_journal SET summary=summary WHERE book_id=?", f.book());
        db.update("UPDATE plugin_finance_line SET memo=memo WHERE book_id=?", f.book());
        db.update("DELETE FROM plugin_finance_line WHERE book_id=? AND false", f.book());
        assertEquals(n, generation(f));
        db.update(
                "UPDATE plugin_finance_line SET memo='old binary statement' WHERE book_id=?",
                f.book());
        assertEquals(n + 1, generation(f));
        long stable = generation(f);
        assertThrows(
                IllegalStateException.class,
                () ->
                        FinancePostgresTest.tx.execute(
                                t -> {
                                    db.update(
                                            "UPDATE plugin_finance_journal SET summary='rollback'"
                                                    + " WHERE book_id=?",
                                            f.book());
                                    throw new IllegalStateException("rollback");
                                }));
        assertEquals(stable, generation(f));
        var input = new LinkedHashMap<String, Object>();
        input.put("idempotencyKey", UUID.randomUUID().toString());
        input.put("date", "2026-01-11");
        input.put("summary", "idempotent draft");
        input.put(
                "lines",
                List.of(
                        Map.of("accountId", f.cash().toString(), "debit", "1", "credit", "0"),
                        Map.of("accountId", f.capital().toString(), "debit", "0", "credit", "1")));
        FinancePostgresTest.write("maker", f.book(), f.year(), "journal", null, input);
        stable = generation(f);
        FinancePostgresTest.write("maker", f.book(), f.year(), "journal", null, input);
        assertEquals(stable, generation(f));
        db.update(
                "DELETE FROM plugin_finance_line WHERE book_id=? AND journal_id IN(SELECT id FROM"
                        + " plugin_finance_journal WHERE book_id=? AND summary='bulk fixture 1')",
                f.book(),
                f.book());
        assertEquals(stable + 1, generation(f));
        // Published V1's journal BEFORE DELETE returns NEW (null), suppressing draft deletion.
        // Preserve that existing behavior: a suppressed statement has no transition rows or bump.
        assertEquals(
                0,
                db.update(
                        "DELETE FROM plugin_finance_journal WHERE book_id=? AND summary='bulk"
                                + " fixture 1'",
                        f.book()));
        assertEquals(stable + 1, generation(f));
        var year =
                FinancePostgresTest.write(
                        "maker", f.book(), null, "years", null, Map.of("year", 2027));
        assertEquals(
                0L,
                ((Number)
                                db.one(
                                                "SELECT generation n FROM"
                                                        + " plugin_finance_journal_list_guard WHERE"
                                                        + " book_id=? AND year_id=?",
                                                f.book(),
                                                year.get("id"))
                                        .get("n"))
                        .longValue());
    }

    @Test
    void draftStalesJournalButNotPostedLedgerAndScopeAuthCannotBeReused() {
        var f = FinancePostgresTest.fixture("CAS");
        post(f, "first", 1);
        post(f, "second", 1);
        var jp = read("maker", f, "journals", q("limit", 1));
        var lp = read("maker", f, "ledger", q("accountId", f.cash(), "limit", 1));
        journal(f, "unrelated draft", 1);
        code(
                "LIST_PAGE_STALE",
                () -> read("maker", f, "journals", q("limit", 1, "cursor", jp.get("nextCursor"))));
        assertEquals(
                lp.get("contextDigest"),
                read(
                                "maker",
                                f,
                                "ledger",
                                q(
                                        "accountId",
                                        f.cash(),
                                        "limit",
                                        1,
                                        "cursor",
                                        lp.get("nextCursor")))
                        .get("contextDigest"));
        code(
                "INVALID_INPUT",
                () ->
                        read(
                                "reviewer",
                                f,
                                "ledger",
                                q(
                                        "accountId",
                                        f.cash(),
                                        "limit",
                                        1,
                                        "cursor",
                                        lp.get("nextCursor"))));
        code(
                "INVALID_INPUT",
                () ->
                        read(
                                "maker",
                                f,
                                "ledger",
                                q(
                                        "accountId",
                                        f.capital(),
                                        "limit",
                                        1,
                                        "cursor",
                                        lp.get("nextCursor"))));
        post(f, "new posted fact", 1);
        code(
                "LIST_PAGE_STALE",
                () ->
                        read(
                                "maker",
                                f,
                                "ledger",
                                q(
                                        "accountId",
                                        f.cash(),
                                        "limit",
                                        1,
                                        "cursor",
                                        lp.get("nextCursor"))));
        var fresh = read("maker", f, "journals", q("limit", 1));
        snapshot.set(
                new AuthorizationContextProjectionPort.Snapshot(
                        AUTHORITIES, Set.of(), true, UUID.randomUUID(), 3));
        code(
                "LIST_PAGE_STALE",
                () ->
                        read(
                                "maker",
                                f,
                                "journals",
                                q("limit", 1, "cursor", fresh.get("nextCursor"))));
        resetContext();
        missing = true;
        code("SCOPE_DENIED", () -> read("maker", f, "journals", q()));
        missing = false;
        var y =
                FinancePostgresTest.write(
                        "maker", f.book(), null, "years", null, Map.of("year", 2027));
        FinancePostgresTest.write(
                "maker",
                f.book(),
                null,
                "members",
                null,
                Map.of(
                        "username",
                        "reviewer",
                        "role",
                        "READER",
                        "enabled",
                        true,
                        "yearScope",
                        "SELECTED",
                        "yearIds",
                        List.of(f.year().toString())));
        assertFalse(items(read("reviewer", f, "journals", q())).isEmpty());
        code(
                "SCOPE_DENIED",
                () -> pages.page("reviewer", f.book(), (UUID) y.get("id"), "journals", q()));
        db.update(
                "UPDATE plugin_finance_period SET status='CLOSED' WHERE book_id=? AND year_id=? AND"
                        + " month=1",
                f.book(),
                f.year());
        assertFalse(items(read("reviewer", f, "ledger", q("accountId", f.cash()))).isEmpty());
        FinancePostgresTest.write(
                "maker",
                f.book(),
                null,
                "members",
                null,
                Map.of("username", "reviewer", "role", "READER", "enabled", false));
        code("SCOPE_DENIED", () -> read("reviewer", f, "journals", q()));
    }

    @Test
    void ilikeTreatsWildcardsBackslashWhitespaceLiterallyAndUnsafeRowsFail() {
        var f = FinancePostgresTest.fixture("CAS");
        journal(f, "MiXeD %_\\ ending", 1);
        journal(f, "MiXeD AX\\ ending", 1);
        journal(f, "prefix  suffix", 1);
        var found =
                read(
                        "maker",
                        f,
                        "journals",
                        q(
                                "keyword",
                                "mixed %_\\",
                                "dateFrom",
                                "2026-01-01",
                                "dateTo",
                                "2026-01-31"));
        assertEquals(1, items(found).size());
        assertEquals(
                1,
                items(
                                read(
                                        "maker",
                                        f,
                                        "journals",
                                        q(
                                                "keyword",
                                                "  ",
                                                "dateFrom",
                                                "2026-01-01",
                                                "dateTo",
                                                "2026-01-31")))
                        .size());
        assertEquals(3, items(read("maker", f, "journals", q("keyword", ""))).size());
        code("INVALID_INPUT", () -> read("maker", f, "journals", q("keyword", "mixed")));
        code(
                "INVALID_INPUT",
                () ->
                        read(
                                "maker",
                                f,
                                "journals",
                                q(
                                        "keyword",
                                        "😀".repeat(60),
                                        "dateFrom",
                                        "2026-01-01",
                                        "dateTo",
                                        "2026-01-31")));
        code(
                "INVALID_INPUT",
                () ->
                        read(
                                "maker",
                                f,
                                "journals",
                                q("dateFrom", "2026-01-01", "dateTo", "2027-01-01")));
        db.update(
                "UPDATE plugin_finance_journal SET version=9007199254740992 WHERE book_id=? AND"
                        + " summary='prefix  suffix'",
                f.book());
        code("LIST_VALUE_UNSUPPORTED", () -> read("maker", f, "journals", q()));
    }

    @Test
    void trueDatabaseTimeoutFailsWholePageAndBaselineIsNotInvented() throws Exception {
        var f = FinancePostgresTest.fixture("CAS");
        journal(f, "timeout fixture", 1);
        String before = facts();
        long start = System.nanoTime();
        try (var locker = FinancePostgresTest.source.getConnection()) {
            locker.setAutoCommit(false);
            locker.createStatement()
                    .execute("LOCK TABLE plugin_finance_journal IN ACCESS EXCLUSIVE MODE");
            try {
                code("QUERY_BUDGET_EXCEEDED", () -> read("maker", f, "journals", q()));
            } finally {
                locker.rollback();
            }
        }
        long millis = (System.nanoTime() - start) / 1000000;
        assertTrue(millis >= 2500 && millis < 12000, "actual timeout ms=" + millis);
        assertEquals(before, facts());
        assertEquals(1, items(read("maker", f, "journals", q())).size());
        try {
            db.update(
                    "ALTER TABLE plugin_finance_journal_list_guard DISABLE TRIGGER"
                            + " finance_journal_list_baseline_guard");
            db.update("DELETE FROM plugin_finance_journal_list_guard WHERE book_id=?", f.book());
        } finally {
            db.update(
                    "ALTER TABLE plugin_finance_journal_list_guard ENABLE TRIGGER"
                            + " finance_journal_list_baseline_guard");
        }
        code("LIST_BASELINE_UNAVAILABLE", () -> read("maker", f, "journals", q()));
    }

    @Test
    void fiveThousandDeepSeeksAndStatusPlansUseBoundedRows() throws Exception {
        var f = FinancePostgresTest.fixture("CAS");
        bulk(f, 5000);
        db.update(
                "UPDATE plugin_finance_journal SET status='SUBMITTED',version=2 WHERE id IN(SELECT"
                        + " id FROM plugin_finance_journal WHERE book_id=? ORDER BY id LIMIT 20)",
                f.book());
        db.update("ANALYZE plugin_finance_journal");
        db.update("ANALYZE plugin_finance_line");
        var b = FinancePostgresTest.service.originalScope("maker", f.book(), f.year(), "read");
        var anchor =
                db.one(
                        "SELECT date,id FROM plugin_finance_journal WHERE book_id=? ORDER BY"
                                + " date,id OFFSET 2500 LIMIT 1",
                        f.book());
        var plan =
                db.list(
                        "EXPLAIN(ANALYZE,BUFFERS,FORMAT JSON) SELECT j.id,j.date FROM"
                            + " plugin_finance_journal j WHERE tenant_id=? AND book_id=? AND"
                            + " year_id=? AND (date,id)>(?::date,?::uuid) ORDER BY date,id LIMIT"
                            + " 101",
                        b.get("tenant_id"),
                        f.book(),
                        f.year(),
                        anchor.get("date"),
                        anchor.get("id"));
        var statusPlan =
                db.list(
                        "EXPLAIN(ANALYZE,BUFFERS,FORMAT JSON) SELECT j.id,j.date FROM"
                                + " plugin_finance_journal j WHERE tenant_id=? AND book_id=? AND"
                                + " year_id=? AND status='SUBMITTED' ORDER BY date,id LIMIT 101",
                        b.get("tenant_id"),
                        f.book(),
                        f.year());
        assertTrue(plan.toString().contains("finance_journal_pages_date"));
        assertTrue(statusPlan.toString().contains("finance_journal_pages_status_date"));
        long begin = System.nanoTime();
        var first = read("maker", f, "journals", q("limit", 100));
        long millis = (System.nanoTime() - begin) / 1000000;
        assertEquals(100, items(first).size());
        assertNull(first.get("totalCount"));
        post(f, "ledger budget parent", 24);
        post(f, "ledger second parent", 1);
        var ledgerPlan =
                db.list(
                        "EXPLAIN(ANALYZE,BUFFERS,FORMAT JSON) SELECT j.id,l.line_no FROM"
                            + " plugin_finance_journal j JOIN plugin_finance_line l"
                            + " ON(l.tenant_id,l.book_id,l.year_id,l.journal_id)=(j.tenant_id,j.book_id,j.year_id,j.id)"
                            + " WHERE j.tenant_id=? AND j.book_id=? AND j.year_id=? AND"
                            + " j.status='POSTED' AND l.account_id=? AND j.date BETWEEN"
                            + " '2026-01-01' AND '2026-01-31' ORDER BY j.date,j.id,l.line_no LIMIT"
                            + " 101",
                        b.get("tenant_id"),
                        f.book(),
                        f.year(),
                        f.cash());
        long ledgerBegin = System.nanoTime();
        var ledgerPage = read("maker", f, "ledger", q("accountId", f.cash(), "limit", 20));
        long ledgerMillis = (System.nanoTime() - ledgerBegin) / 1000000;
        assertEquals(20, items(ledgerPage).size());
        var fresh = read("maker", f, "journals", q("limit", 100));
        var deep =
                FinancePostgresTest.service.decode(
                        new String(
                                Base64.getUrlDecoder().decode(fresh.get("nextCursor").toString()),
                                java.nio.charset.StandardCharsets.UTF_8));
        deep.put("anchorJournalId", anchor.get("id").toString());
        deep.put("anchorDate", anchor.get("date"));
        deep.put("direction", "PREVIOUS");
        String deepToken =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(
                                FinancePostgresTest.service
                                        .encode(deep)
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(
                100,
                items(read("maker", f, "journals", q("limit", 100, "cursor", deepToken))).size());
        long zeroBegin = System.nanoTime();
        var zero =
                read(
                        "maker",
                        f,
                        "journals",
                        q(
                                "keyword",
                                "not found %_\\ literal",
                                "dateFrom",
                                "2026-01-01",
                                "dateTo",
                                "2026-01-31",
                                "limit",
                                100));
        long zeroMillis = (System.nanoTime() - zeroBegin) / 1000000;
        assertTrue(items(zero).isEmpty());
        assertNull(zero.get("totalCount"));
        Files.writeString(
                Path.of("../../../.runtime/journal-pages-query-plans.txt"),
                "5000 row first page millis="
                        + millis
                        + "\n"
                        + plan
                        + "\nSTATUS\n"
                        + statusPlan
                        + "\nLEDGER pageMillis="
                        + ledgerMillis
                        + "\n"
                        + ledgerPlan
                        + "\nliteral zero hit millis="
                        + zeroMillis);
    }
}
