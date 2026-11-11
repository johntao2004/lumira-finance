package com.lumira.finance;

import static org.junit.jupiter.api.Assertions.*;

import com.lumira.platform.common.AuthorizationContextProjectionPort;

import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.*;

/** Current V1-V16 schema, actual GL facts and typed receipts; host MFA is a separate live check. */
class FinanceProfitClosingPostgresTest {
    static FinanceProfitClosing closing;
    static FinanceStore db;

    @BeforeAll
    static void setup() throws Exception {
        FinanceJournalPersonPostgresTest.setup();
        db = FinanceJournalPersonPostgresTest.db;
        AuthorizationContextProjectionPort projection =
                p ->
                        Optional.of(
                                new AuthorizationContextProjectionPort.Snapshot(
                                        FinanceJournalPersonPostgresTest.ALL,
                                        FinanceJournalPersonPostgresTest.ALL,
                                        false,
                                        null,
                                        0));
        closing =
                new FinanceProfitClosing(
                        db,
                        FinanceJournalPersonPostgresTest.modern,
                        FinanceJournalPersonPostgresTest.gate,
                        FinancePostgresTest.tx.getTransactionManager(),
                        projection);
    }

    @AfterAll
    static void cleanup() {
        FinanceJournalPersonPostgresTest.cleanup();
    }

    @BeforeEach
    void reset() {
        FinanceJournalPersonPostgresTest.denied.clear();
    }

    record Case(
            FinanceJournalPersonPostgresTest.Case base,
            UUID revenue,
            UUID expense,
            UUID current,
            UUID retained) {
        UUID book() {
            return base.f().book();
        }

        UUID year() {
            return base.f().year();
        }

        UUID tenant() {
            return base.tenant();
        }
    }

    static Map<String, Object> map(Object... pairs) {
        return FinanceJournalPersonGate.map(pairs);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Object value) {
        return (Map<String, Object>) value;
    }

    static UUID id(Object value) {
        return UUID.fromString(value.toString());
    }

    static Map<String, Object> body(Object... pairs) {
        var n =
                map(
                        "schemaVersion",
                        FinanceProfitClosingInput.SCHEMA,
                        "idempotencyKey",
                        UUID.randomUUID().toString());
        n.putAll(map(pairs));
        return n;
    }

    static UUID account(
            FinanceJournalPersonPostgresTest.Case c,
            String code,
            String category,
            String side,
            String purpose) {
        return id(
                FinancePostgresTest.write(
                                "maker",
                                c.f().book(),
                                c.f().year(),
                                "accounts",
                                null,
                                map(
                                        "code",
                                        code,
                                        "name",
                                        "Synthetic " + code,
                                        "normalSide",
                                        side,
                                        "category",
                                        category,
                                        "purpose",
                                        purpose))
                        .get("id"));
    }

    static Case fixture(String standard) {
        var c = FinanceJournalPersonPostgresTest.fixture(standard);
        UUID revenue = account(c, "6001", "REVENUE", "CREDIT", "NONE");
        UUID expense = account(c, "6601", "EXPENSE", "DEBIT", "NONE");
        UUID current = account(c, "4103", "EQUITY", "CREDIT", "CURRENT_YEAR_PROFIT");
        UUID retained = account(c, "4104", "EQUITY", "CREDIT", "RETAINED_EARNINGS");
        FinanceJournalPersonPostgresTest.enable(c);
        return new Case(c, revenue, expense, current, retained);
    }

    static UUID normal(Case c, String date, UUID debit, UUID credit, String amount) {
        var journal =
                FinanceJournalPersonPostgresTest.legacy(
                        "maker",
                        c.base(),
                        "journal",
                        null,
                        map(
                                "date",
                                date,
                                "summary",
                                "Synthetic real GL business",
                                "lines",
                                List.of(
                                        map(
                                                "accountId",
                                                debit.toString(),
                                                "debit",
                                                amount,
                                                "credit",
                                                "0",
                                                "memo",
                                                "business"),
                                        map(
                                                "accountId",
                                                credit.toString(),
                                                "debit",
                                                "0",
                                                "credit",
                                                amount,
                                                "memo",
                                                "business"))));
        UUID j = id(journal.get("id"));
        FinanceJournalPersonPostgresTest.legacy(
                "maker", c.base(), "submit", j, map("expectedVersion", journal.get("version")));
        var context = FinanceJournalPersonPostgresTest.read("maker", c.base(), "context", j);
        FinanceJournalPersonPostgresTest.cmd(
                "reviewer",
                c.base(),
                "person-approve",
                j,
                FinanceJournalPersonPostgresTest.body(
                        "expectedJournalVersion",
                        context.get("journalVersion"),
                        "expectedContentVersion",
                        context.get("contentVersion"),
                        "contentDigest",
                        context.get("contentDigest"),
                        "requestedExpiresAt",
                        Instant.now().plusSeconds(120).toString()));
        context = FinanceJournalPersonPostgresTest.read("maker", c.base(), "context", j);
        FinanceJournalPersonPostgresTest.cmd(
                "maker",
                c.base(),
                "person-post",
                j,
                FinanceJournalPersonPostgresTest.body(
                        "expectedJournalVersion",
                        context.get("journalVersion"),
                        "expectedContentVersion",
                        context.get("contentVersion"),
                        "contentDigest",
                        context.get("contentDigest"),
                        "approvalId",
                        obj(context.get("currentApproval")).get("id").toString()));
        return j;
    }

    static Map<String, Object> preview(Case c, String kind, String date, UUID reversal) {
        return FinanceJournalPersonPostgresTest.as(
                "maker",
                () -> {
                    var request = FinanceJournalPersonPostgresTest.request();
                    request.addParameter("producerKind", kind);
                    request.addParameter("cutoff", date);
                    if (reversal != null)
                        request.addParameter("reversesRunId", reversal.toString());
                    return obj(closing.read("maker", c.book(), c.year(), "preview", null, request));
                });
    }

    static Map<String, Object> createInput(
            Map<String, Object> preview, Map<String, Object> cancelled) {
        var source = obj(preview.get("source"));
        return body(
                "operationId",
                UUID.randomUUID().toString(),
                "producerKind",
                preview.get("producerKind"),
                "cutoff",
                preview.get("cutoff"),
                "expectedSource",
                source.get("basis"),
                "expectedSourceDigest",
                source.get("sourceDigest"),
                "expectedGeneratedDigest",
                preview.get("generatedDigest"),
                "predecessorPostedRunId",
                source.get("latestPostedRunId"),
                "supersedesCancelledAttemptId",
                cancelled == null ? null : cancelled.get("id").toString(),
                "expectedSupersededVersion",
                cancelled == null ? null : cancelled.get("version"),
                "reversesRunId",
                preview.get("reversesRunId") == null
                        ? null
                        : preview.get("reversesRunId").toString(),
                "reason",
                "Synthetic actual-source GL transfer");
    }

    static Map<String, Object> write(
            String actor, Case c, String op, UUID target, Map<String, Object> n) {
        return FinanceJournalPersonPostgresTest.as(
                actor,
                () ->
                        obj(
                                closing.write(
                                        actor,
                                        c.book(),
                                        c.year(),
                                        op,
                                        target,
                                        n,
                                        FinanceJournalPersonPostgresTest.request())));
    }

    static Map<String, Object> read(String actor, Case c, UUID run) {
        return FinanceJournalPersonPostgresTest.as(
                actor,
                () ->
                        obj(
                                closing.read(
                                        actor,
                                        c.book(),
                                        c.year(),
                                        "detail",
                                        run,
                                        FinanceJournalPersonPostgresTest.request())));
    }

    static Map<String, Object> action(Map<String, Object> run) {
        return body(
                "expectedVersion",
                run.get("version"),
                "expectedContentVersion",
                run.get("contentVersion"),
                "contentDigest",
                run.get("contentDigest"),
                "reason",
                "Synthetic independent workflow decision");
    }

    static Map<String, Object> step(String actor, Case c, String op, Map<String, Object> run) {
        var n = action(run);
        if (op.equals("approve")) n.put("requestedExpiresAt", null);
        if (op.equals("post"))
            n.put("approvalId", obj(run.get("currentApproval")).get("id").toString());
        return write(actor, c, op, id(run.get("id")), n);
    }

    static Map<String, Object> posted(Case c, String kind, String date, UUID reverse) {
        var p = preview(c, kind, date, reverse);
        assertEquals("PRODUCIBLE", p.get("outcome"));
        var run = write("maker", c, "create", null, createInput(p, null));
        return step(
                "maker",
                c,
                "post",
                step("reviewer", c, "approve", step("maker", c, "submit", run)));
    }

    static String balance(Case c, UUID account) {
        return db.one(
                        "SELECT COALESCE(SUM(l.debit-l.credit),0)::numeric(28,8)::text amount FROM"
                            + " plugin_finance_line l JOIN plugin_finance_journal j"
                            + " ON(j.tenant_id,j.book_id,j.year_id,j.id)=(l.tenant_id,l.book_id,l.year_id,l.journal_id)"
                            + " WHERE(l.tenant_id,l.book_id,l.year_id,l.account_id)=(?,?,?,?) AND"
                            + " j.status='POSTED'",
                        c.tenant(),
                        c.book(),
                        c.year(),
                        account)
                .get("amount")
                .toString();
    }

    static void code(String expected, Runnable work) {
        assertEquals(expected, assertThrows(FinanceException.class, work::run).code);
    }

    @Test
    void futurePostingDoesNotStaleButIncludedPostingDoesAndResidualIsOnlyFive() {
        var c = fixture("CAS");
        normal(c, "2026-01-15", c.base().f().cash(), c.revenue(), "100");
        var first = preview(c, "PROFIT_LOSS", "2026-01-31", null);
        var run = write("maker", c, "create", null, createInput(first, null));
        run = step("reviewer", c, "approve", step("maker", c, "submit", run));
        normal(c, "2026-02-15", c.base().f().cash(), c.revenue(), "20");
        assertEquals(
                first.get("source") instanceof Map<?, ?> s ? s.get("sourceDigest") : null,
                obj(preview(c, "PROFIT_LOSS", "2026-01-31", null).get("source"))
                        .get("sourceDigest"));
        assertEquals(Boolean.TRUE, read("maker", c, id(run.get("id"))).get("sourceFresh"));
        // A real NORMAL post must not run the legacy invalidator against the typed header.
        normal(c, "2026-01-20", c.expense(), c.base().f().cash(), "5");
        var stale = read("maker", c, id(run.get("id")));
        assertEquals("APPROVED", stale.get("status"));
        assertEquals(Boolean.FALSE, stale.get("sourceFresh"));
        code("PROFIT_CLOSING_SOURCE_STALE", () -> step("maker", c, "post", stale));
        run = step("reviewer", c, "reject", stale);
        run = step("maker", c, "cancel", run);
        var replacement =
                write(
                        "maker",
                        c,
                        "create",
                        null,
                        createInput(preview(c, "PROFIT_LOSS", "2026-01-31", null), run));
        replacement =
                step(
                        "maker",
                        c,
                        "post",
                        step("reviewer", c, "approve", step("maker", c, "submit", replacement)));
        assertEquals("-95.00000000", balance(c, c.current()));
        normal(c, "2026-01-25", c.base().f().cash(), c.revenue(), "5");
        var supplement = posted(c, "PROFIT_LOSS", "2026-01-31", null);
        assertEquals(replacement.get("id"), supplement.get("predecessorPostedRunId"));
        assertEquals("-100.00000000", balance(c, c.current()));
        var lines = (List<?>) obj(supplement.get("content")).get("generatedLines");
        assertEquals(2, lines.size());
        assertTrue(
                lines.stream()
                        .allMatch(
                                l ->
                                        List.of("0.00000000", "5.00000000")
                                                        .contains(obj(l).get("debit"))
                                                && List.of("0.00000000", "5.00000000")
                                                        .contains(obj(l).get("credit"))));
        assertEquals("NOOP", preview(c, "PROFIT_LOSS", "2026-01-31", null).get("outcome"));
    }

    @Test
    void smallLossAndZeroNetProfitStillTransferEveryResidualAccount() {
        var loss = fixture("SMALL");
        normal(loss, "2026-01-15", loss.base().f().cash(), loss.revenue(), "20");
        normal(loss, "2026-01-16", loss.expense(), loss.base().f().cash(), "60");
        posted(loss, "PROFIT_LOSS", "2026-01-31", null);
        assertEquals("40.00000000", balance(loss, loss.current()));
        posted(loss, "YEAR_END", "2026-12-31", null);
        assertEquals("40.00000000", balance(loss, loss.retained()));
        var zero = fixture("SMALL");
        normal(zero, "2026-01-15", zero.base().f().cash(), zero.revenue(), "20");
        normal(zero, "2026-01-16", zero.expense(), zero.base().f().cash(), "20");
        assertEquals("PRODUCIBLE", preview(zero, "PROFIT_LOSS", "2026-01-31", null).get("outcome"));
        posted(zero, "PROFIT_LOSS", "2026-01-31", null);
        assertEquals("0.00000000", balance(zero, zero.revenue()));
        assertEquals("0.00000000", balance(zero, zero.expense()));
        assertEquals("NOOP", preview(zero, "YEAR_END", "2026-12-31", null).get("outcome"));
    }

    @Test
    void cancelledReverseRequiresExplicitAttemptAndOnlyPostedReverseUsesRawUniqueSlot() {
        var c = fixture("CAS");
        normal(c, "2026-01-15", c.base().f().cash(), c.revenue(), "10");
        var original = posted(c, "PROFIT_LOSS", "2026-01-31", null);
        var p = preview(c, "TRANSFER_REVERSAL", "2026-01-31", id(original.get("id")));
        var draft = write("maker", c, "create", null, createInput(p, null));
        assertNull(
                db.one(
                                "SELECT reversal_of FROM plugin_finance_journal WHERE id=?",
                                id(draft.get("journalId")))
                        .get("reversal_of"));
        var cancelled = step("maker", c, "cancel", draft);
        var missing = createInput(p, null);
        code(
                "PROFIT_CLOSING_SOURCE_ALREADY_CLAIMED",
                () -> write("maker", c, "create", null, missing));
        var next = write("maker", c, "create", null, createInput(p, cancelled));
        next =
                step(
                        "maker",
                        c,
                        "post",
                        step("reviewer", c, "approve", step("maker", c, "submit", next)));
        assertEquals(original.get("id"), next.get("reversesRunId"));
        assertEquals(
                1L,
                ((Number)
                                db.one(
                                                "SELECT count(*) n FROM"
                                                    + " plugin_finance_profit_closing_reversal_effect"
                                                    + " WHERE year_id=?",
                                                c.year())
                                        .get("n"))
                        .longValue());
    }

    @Test
    void expiredAndDepartedActorsCanBeReturnedAndTerminatedWithoutRevival() throws Exception {
        var c = fixture("CAS");
        normal(c, "2026-01-15", c.base().f().cash(), c.revenue(), "10");
        var run =
                write(
                        "maker",
                        c,
                        "create",
                        null,
                        createInput(preview(c, "PROFIT_LOSS", "2026-01-31", null), null));
        run = step("maker", c, "submit", run);
        var approve = action(run);
        Instant serverStart =
                ((java.sql.Timestamp) db.one("SELECT clock_timestamp() now").get("now"))
                        .toInstant();
        approve.put("requestedExpiresAt", serverStart.plusSeconds(4).toString());
        run = write("reviewer", c, "approve", id(run.get("id")), approve);
        Map<String, Object> timing;
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(6).toNanos();
        do {
            timing =
                    db.one(
                            "SELECT expires_at::text expires_at,clock_timestamp()::text"
                                + " server_now,expires_at<=clock_timestamp() expired FROM"
                                + " plugin_finance_profit_closing_approval WHERE id=?",
                            id(obj(run.get("currentApproval")).get("id")));
            if (Boolean.TRUE.equals(timing.get("expired"))) break;
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        System.out.println(
                "Closing expiry diagnostic requested="
                        + approve.get("requestedExpiresAt")
                        + " actual="
                        + timing
                        + " javaNow="
                        + Instant.now());
        assertEquals(
                Boolean.TRUE,
                timing.get("expired"),
                "Actual stored approval must expire before attempting POST: " + timing);
        var expired = run;
        code("PROFIT_CLOSING_APPROVAL_EXPIRED", () -> step("maker", c, "post", expired));
        run = step("other", c, "reject", run);
        run = step("reviewer", c, "approve", step("maker", c, "submit", run));
        for (String name : List.of("maker", "reviewer")) {
            FinanceJournalPersonPostgresTest.as(
                    "other",
                    () ->
                            FinancePostgresTest.write(
                                    "other",
                                    c.book(),
                                    null,
                                    "members",
                                    null,
                                    map(
                                            "username",
                                            name,
                                            "role",
                                            name.equals("maker") ? "OWNER" : "REVIEWER",
                                            "enabled",
                                            false)));
        }
        run = step("other", c, "reject", run);
        run = step("other", c, "admin-terminate", run);
        assertEquals("CANCELLED", run.get("status"));
        assertNotNull(run.get("administrativeTermination"));
        assertEquals(
                0L,
                ((Number)
                                db.one(
                                                "SELECT count(*) n FROM"
                                                    + " plugin_finance_profit_closing_post WHERE"
                                                    + " year_id=?",
                                                c.year())
                                        .get("n"))
                        .longValue());
    }

    @Test
    void casActualProfitYearEndAndTypedFullReverseHaveRealFactsAndReadableHistory() {
        var c = fixture("CAS");
        normal(c, "2026-01-15", c.base().f().cash(), c.revenue(), "100");
        normal(c, "2026-01-16", c.expense(), c.base().f().cash(), "60");
        var profit = posted(c, "PROFIT_LOSS", "2026-01-31", null);
        assertEquals("POSTED", profit.get("status"));
        assertNotNull(profit.get("postProof"));
        assertEquals("0.00000000", balance(c, c.revenue()));
        assertEquals("0.00000000", balance(c, c.expense()));
        assertEquals("-40.00000000", balance(c, c.current()));
        assertEquals("NOOP", preview(c, "PROFIT_LOSS", "2026-01-31", null).get("outcome"));
        var year = posted(c, "YEAR_END", "2026-12-31", null);
        assertEquals("0.00000000", balance(c, c.current()));
        assertEquals("-40.00000000", balance(c, c.retained()));
        assertEquals("POSTED", read("reviewer", c, id(profit.get("id"))).get("status"));
        var reversedYear = posted(c, "TRANSFER_REVERSAL", "2026-12-31", id(year.get("id")));
        assertEquals("POSTED", reversedYear.get("status"));
        assertEquals("FULLY_REVERSED", read("maker", c, id(year.get("id"))).get("economicEffect"));
        var reversedProfit = posted(c, "TRANSFER_REVERSAL", "2026-01-31", id(profit.get("id")));
        assertEquals("POSTED", reversedProfit.get("status"));
        assertEquals("-100.00000000", balance(c, c.revenue()));
        assertEquals("60.00000000", balance(c, c.expense()));
        assertEquals("0.00000000", balance(c, c.current()));
        assertEquals("0.00000000", balance(c, c.retained()));
        assertEquals(
                id(year.get("journalId")),
                db.one(
                                "SELECT reversal_of FROM plugin_finance_journal WHERE id=?",
                                id(reversedYear.get("journalId")))
                        .get("reversal_of"));
        assertEquals("POSTED", read("maker", c, id(reversedYear.get("id"))).get("status"));
        assertEquals(
                "FULLY_REVERSED", read("maker", c, id(profit.get("id"))).get("economicEffect"));
        assertEquals(
                4L,
                ((Number)
                                db.one(
                                                "SELECT count(*) n FROM"
                                                    + " plugin_finance_profit_closing_post WHERE"
                                                    + " year_id=?",
                                                c.year())
                                        .get("n"))
                        .longValue());
        assertEquals(
                "OPEN",
                db.one("SELECT status FROM plugin_finance_year WHERE id=?", c.year())
                        .get("status"));
    }

    static void rejectedAtRealCommit(String sql, String expectedState, String expectedGuard)
            throws Exception {
        boolean completed = false, committed = false;
        Throwable rejection = null;
        try (var connection = FinancePostgresTest.source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                connection.createStatement().execute(sql);
                completed = true;
                connection.commit();
                committed = true;
            } catch (Throwable problem) {
                rejection = problem;
                connection.rollback();
            }
        }
        if (rejection instanceof java.sql.SQLException sqlError) {
            System.out.println(
                    "Closing graph rejection SQLSTATE="
                            + sqlError.getSQLState()
                            + " message="
                            + sqlError.getMessage()
                            + " committed="
                            + committed
                            + " allSQLcompleted="
                            + completed);
            assertEquals(
                    expectedState,
                    sqlError.getSQLState(),
                    "Must reach the exact typed guard/check");
            assertTrue(
                    sqlError.getMessage().contains(expectedGuard),
                    "Must reach target proof, not an unrelated fixture rejection: " + sqlError);
        }
        assertNotNull(
                rejection,
                "Illegal typed graph committed=" + committed + ", all SQL completed=" + completed);
        assertInstanceOf(java.sql.SQLException.class, rejection);
    }

    @Test
    void twoConcurrentDeliveriesOfOneOperationProduceOnlyOneDraftAndAudit() throws Exception {
        var c = fixture("CAS");
        normal(c, "2026-01-15", c.base().f().cash(), c.revenue(), "10");
        var input = createInput(preview(c, "PROFIT_LOSS", "2026-01-31", null), null);
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var tasks = new ArrayList<java.util.concurrent.Future<Map<String, Object>>>();
            for (int i = 0; i < 2; i++) {
                var delivery = new LinkedHashMap<>(input);
                delivery.put("idempotencyKey", UUID.randomUUID().toString());
                tasks.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return write("maker", c, "create", null, delivery);
                                }));
            }
            start.countDown();
            var first = tasks.get(0).get(15, java.util.concurrent.TimeUnit.SECONDS);
            var second = tasks.get(1).get(15, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(first.get("id"), second.get("id"));
            assertEquals(
                    1L,
                    ((Number)
                                    db.one(
                                                    "SELECT count(*) n FROM"
                                                        + " plugin_finance_profit_closing_run WHERE"
                                                        + " year_id=?",
                                                    c.year())
                                            .get("n"))
                            .longValue());
            assertEquals(
                    1L,
                    ((Number)
                                    db.one(
                                                    "SELECT count(*) n FROM"
                                                        + " plugin_finance_profit_closing_transition"
                                                        + " WHERE year_id=? AND action='CREATE'",
                                                    c.year())
                                            .get("n"))
                            .longValue());
            var otherOperation = new LinkedHashMap<>(input);
            otherOperation.put("operationId", UUID.randomUUID().toString());
            otherOperation.put("idempotencyKey", UUID.randomUUID().toString());
            code(
                    "PROFIT_CLOSING_RUN_PENDING",
                    () -> write("maker", c, "create", null, otherOperation));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void closedMonthStillAllowsIndependentReturnAndAdministrativeExit() {
        var c = fixture("CAS");
        normal(c, "2026-01-15", c.base().f().cash(), c.revenue(), "10");
        var run =
                write(
                        "maker",
                        c,
                        "create",
                        null,
                        createInput(preview(c, "PROFIT_LOSS", "2026-01-31", null), null));
        run = step("reviewer", c, "approve", step("maker", c, "submit", run));
        // Scoped SQL fixture sets only the period state; this does not claim a month-close workflow
        // acceptance.
        FinancePostgresTest.tx.executeWithoutResult(
                s ->
                        db.update(
                                "UPDATE plugin_finance_period SET status='CLOSED',version=version+1"
                                    + " WHERE year_id=? AND month=1",
                                c.year()));
        run = step("other", c, "reject", run);
        run = step("other", c, "admin-terminate", run);
        assertEquals("CANCELLED", run.get("status"));
        assertEquals(
                "OPEN",
                db.one("SELECT status FROM plugin_finance_year WHERE id=?", c.year())
                        .get("status"));
    }

    @Test
    void auditFailureAfterTypedCreateRollsBackAllFactsAndSameKeyCanRecover() {
        var c = fixture("CAS");
        normal(c, "2026-01-15", c.base().f().cash(), c.revenue(), "10");
        var input = createInput(preview(c, "PROFIT_LOSS", "2026-01-31", null), null);
        var tables =
                List.of(
                        "plugin_finance_journal",
                        "plugin_finance_line",
                        "plugin_finance_profit_closing_run",
                        "plugin_finance_profit_closing_source_slot",
                        "plugin_finance_profit_closing_transition",
                        "plugin_finance_profit_closing_request",
                        "plugin_finance_audit",
                        "plugin_finance_outbox");
        var counts = new LinkedHashMap<String, Long>();
        for (String table : tables)
            counts.put(
                    table,
                    ((Number)
                                    db.one(
                                                    "SELECT count(*) n FROM "
                                                            + table
                                                            + " WHERE year_id=?",
                                                    c.year())
                                            .get("n"))
                            .longValue());
        db.update(
                "CREATE FUNCTION closing_test_audit_fail() RETURNS trigger LANGUAGE plpgsql AS $$"
                    + " BEGIN IF NEW.year_id='"
                        + c.year()
                        + "'::uuid AND NEW.action='JOURNAL_PROFIT_CLOSING_CREATE' THEN RAISE"
                        + " EXCEPTION 'CLOSING_TEST_AFTER_TYPED_CREATE'; END IF; RETURN NEW; END"
                        + " $$");
        db.update(
                "CREATE TRIGGER closing_test_audit_fail BEFORE INSERT ON plugin_finance_audit FOR"
                    + " EACH ROW EXECUTE FUNCTION closing_test_audit_fail()");
        try {
            Throwable failure =
                    assertThrows(
                            RuntimeException.class, () -> write("maker", c, "create", null, input));
            var causes = new StringBuilder();
            for (Throwable current = failure; current != null; current = current.getCause())
                causes.append(current.getMessage());
            assertTrue(
                    causes.toString().contains("CLOSING_TEST_AFTER_TYPED_CREATE"),
                    causes.toString());
            for (String table : tables)
                assertEquals(
                        counts.get(table),
                        ((Number)
                                        db.one(
                                                        "SELECT count(*) n FROM "
                                                                + table
                                                                + " WHERE year_id=?",
                                                        c.year())
                                                .get("n"))
                                .longValue(),
                        table);
        } finally {
            db.update("DROP TRIGGER closing_test_audit_fail ON plugin_finance_audit");
            db.update("DROP FUNCTION closing_test_audit_fail()");
        }
        assertEquals("DRAFT", write("maker", c, "create", null, input).get("status"));
        assertEquals(
                1L,
                ((Number)
                                db.one(
                                                "SELECT count(*) n FROM"
                                                    + " plugin_finance_profit_closing_request WHERE"
                                                    + " year_id=?",
                                                c.year())
                                        .get("n"))
                        .longValue());
    }

    static String scopeSql(String sql, Case c) {
        return sql.replace("__TENANT__", c.tenant().toString())
                .replace("__BOOK__", c.book().toString())
                .replace("__YEAR__", c.year().toString());
    }

    @Test
    void ordinaryProducerCannotBindAReversalOriginalAtRealCommit() throws Exception {
        var c = fixture("CAS");
        UUID normal = normal(c, "2026-01-15", c.base().f().cash(), c.revenue(), "10");
        var run =
                write(
                        "maker",
                        c,
                        "create",
                        null,
                        createInput(preview(c, "PROFIT_LOSS", "2026-01-31", null), null));
        run = step("maker", c, "cancel", run);
        String sql =
"""
-- Kernel-only RED candidate. DO NOT run against application QA.
-- Backend substitutes __TENANT__/__BOOK__/__YEAR__/__CANCELLED_RUN__/__REAL_NORMAL_JOURNAL__.
-- Prerequisite: genuine PROFIT_LOSS CREATE then genuine CANCEL, with no later GL/account/period changes.
-- Source ORIGINAL normal journal must already exist before that genuine preview/create.
-- Run all below inside the backend test's READ COMMITTED transaction, then real COMMIT.
DO $$
DECLARE t uuid='__TENANT__';b uuid='__BOOK__';y uuid='__YEAR__';oldid uuid='__CANCELLED_RUN__';fake_original uuid='__REAL_NORMAL_JOURNAL__';
 oldr plugin_finance_profit_closing_run;oldh plugin_finance_journal;who jsonb;rid uuid=gen_random_uuid();jid uuid=gen_random_uuid();oper uuid=gen_random_uuid();ct jsonb;dg text;ph text;request_fields jsonb;
BEGIN
 SELECT * INTO oldr FROM plugin_finance_profit_closing_run WHERE(tenant_id,book_id,year_id,id)=(t,b,y,oldid);
 IF oldr.status<>'CANCELLED' OR oldr.producer_kind<>'PROFIT_LOSS' THEN RAISE EXCEPTION 'Fixture must be genuinely cancelled ordinary transfer';END IF;
 SELECT * INTO oldh FROM plugin_finance_journal WHERE(tenant_id,book_id,year_id,id)=(t,b,y,oldr.journal_id);
 who=plugin_finance_journal_person_identity(t,(oldr.prepared_by->>'accountId')::uuid,true);
 ct=jsonb_set(oldr.content,'{reversesJournalId}',to_jsonb(fake_original));dg=plugin_finance_profit_closing_digest(ct);
 request_fields=jsonb_build_object('operationId',oper,'producerKind','PROFIT_LOSS','cutoff',oldr.cutoff,'expectedSource',oldr.source_basis,'expectedSourceDigest',oldr.source_digest,'expectedGeneratedDigest',oldr.generated_digest,'predecessorPostedRunId',oldr.predecessor_posted_run_id,'supersedesCancelledAttemptId',oldr.id,'expectedSupersededVersion',oldr.version::text,'reversesRunId',NULL,'reason',oldr.content->>'reason');
 ph=plugin_finance_profit_closing_digest(request_fields);
 INSERT INTO plugin_finance_profit_closing_run(tenant_id,book_id,year_id,id,journal_id,operation_id,create_payload_hash,source_slot_id,producer_kind,cutoff,content,content_digest,source_basis,source_snapshot,source_digest,generated_lines,generated_digest,prepared_by,last_editor,predecessor_posted_run_id,supersedes_cancelled_attempt_id,expected_superseded_version,reverses_run_id,expected_reversal_of)
 VALUES(t,b,y,rid,jid,oper,ph,oldr.source_slot_id,'PROFIT_LOSS',oldr.cutoff,ct,dg,oldr.source_basis,oldr.source_snapshot,oldr.source_digest,oldr.generated_lines,oldr.generated_digest,who,who,oldr.predecessor_posted_run_id,oldr.id,oldr.version,NULL,fake_original);
 UPDATE plugin_finance_profit_closing_source_slot SET latest_run_id=rid WHERE(tenant_id,book_id,year_id,id)=(t,b,y,oldr.source_slot_id);
 INSERT INTO plugin_finance_journal(tenant_id,book_id,year_id,id,period_id,date,type,summary,created_by,closing_basis,amount_format_version)
 VALUES(t,b,y,jid,oldh.period_id,oldr.cutoff,'PROFIT_CLOSE',ct->>'summary',(who->>'accountId')::uuid,oldr.source_digest,'V4_8');
 INSERT INTO plugin_finance_line(tenant_id,book_id,year_id,journal_id,line_no,account_id,debit,credit,memo)
 SELECT tenant_id,book_id,year_id,jid,line_no,account_id,debit,credit,memo FROM plugin_finance_line WHERE(tenant_id,book_id,year_id,journal_id)=(t,b,y,oldr.journal_id);
 INSERT INTO plugin_finance_profit_closing_transition(tenant_id,book_id,year_id,run_id,journal_id,journal_version,action,to_state,content_version,content_digest,actor,reason)
 VALUES(t,b,y,rid,jid,1,'CREATE','DRAFT',1,dg,who,ct->>'reason');
END $$;
-- COMMIT must be real. Before fix, candidate hole is ordinary producer with nonnull expected_reversal_of
-- yet reverses_run_id NULL. After pair guard, the same graph should be rejected without disabling triggers.

""";
        rejectedAtRealCommit(
                scopeSql(sql, c)
                        .replace("__CANCELLED_RUN__", run.get("id").toString())
                        .replace("__REAL_NORMAL_JOURNAL__", normal.toString()),
                "23514",
                "finance_profit_closing_reversal_pair_ck");
    }

    @Test
    void currentApprovalMustBeTheExactCurrentTransitionDecisionAtRealCommit() throws Exception {
        var c = fixture("CAS");
        normal(c, "2026-01-15", c.base().f().cash(), c.revenue(), "10");
        var run =
                write(
                        "maker",
                        c,
                        "create",
                        null,
                        createInput(preview(c, "PROFIT_LOSS", "2026-01-31", null), null));
        run = step("reviewer", c, "approve", step("maker", c, "submit", run));
        run = step("reviewer", c, "reject", run);
        run = step("maker", c, "submit", run);
        assertEquals("5", run.get("version"));
        String sql =
"""
-- Kernel-only RED candidate, never application QA. Real COMMIT is required.
-- Substitute __TENANT__/__BOOK__/__YEAR__/__RUN__.
-- Prerequisite genuine source run CREATE(v1)->SUBMIT(v2)->APPROVE(v3)->REJECT(v4)->SUBMIT(v5),
-- with unchanged source and a current independent reviewer. No final genuine APPROVE before this DO.
DO $$
DECLARE t uuid='__TENANT__';b uuid='__BOOK__';y uuid='__YEAR__';rid uuid='__RUN__';r plugin_finance_profit_closing_run;oldap plugin_finance_profit_closing_approval;who jsonb;newap uuid=gen_random_uuid();why text='new genuine independent decision but forged current old pointer';nv bigint;
BEGIN
 SELECT * INTO r FROM plugin_finance_profit_closing_run WHERE(tenant_id,book_id,year_id,id)=(t,b,y,rid);
 IF r.status<>'SUBMITTED' OR r.version<>5 THEN RAISE EXCEPTION 'Fixture must be genuine SUBMITTED v5';END IF;
 SELECT * INTO oldap FROM plugin_finance_profit_closing_approval WHERE(tenant_id,book_id,year_id,run_id,approved_journal_version)=(t,b,y,rid,3);
 IF NOT FOUND THEN RAISE EXCEPTION 'Fixture old approval3 missing';END IF;
 who=plugin_finance_journal_person_identity(t,(oldap.approver->>'accountId')::uuid,true);nv=r.version+1;
 INSERT INTO plugin_finance_profit_closing_approval(tenant_id,book_id,year_id,id,run_id,journal_id,content_version,content_digest,source_digest,approved_journal_version,approver,expires_at,reason)
 VALUES(t,b,y,newap,rid,r.journal_id,r.content_version,r.content_digest,r.source_digest,nv,who,CURRENT_TIMESTAMP+interval '1 day',why);
 -- Deliberately retain the superseded old proof in current header/run while the real new decision is separate.
 UPDATE plugin_finance_journal SET status='APPROVED',version=nv,approved_by=(oldap.approver->>'accountId')::uuid,approved_version=oldap.approved_journal_version
 WHERE(tenant_id,book_id,year_id,id)=(t,b,y,r.journal_id);
 UPDATE plugin_finance_profit_closing_run SET status='APPROVED',version=nv,current_approval_id=oldap.id,updated_at=CURRENT_TIMESTAMP
 WHERE(tenant_id,book_id,year_id,id)=(t,b,y,rid);
 INSERT INTO plugin_finance_profit_closing_transition(tenant_id,book_id,year_id,run_id,journal_id,journal_version,previous_version,action,from_state,to_state,content_version,content_digest,actor,reason,approval_id)
 VALUES(t,b,y,rid,r.journal_id,nv,r.version,'APPROVE','SUBMITTED','APPROVED',r.content_version,r.content_digest,who,why,newap);
END $$;
-- Before fix this may COMMIT an APPROVED projection whose current AP is v3 although current transition/AP is v6.
-- Existing POST guard should already deny a subsequent post; the defect under test is false current approval proof.

""";
        rejectedAtRealCommit(
                scopeSql(sql, c).replace("__RUN__", run.get("id").toString()),
                "P0001",
                "PROFIT_CLOSING_APPROVAL_STALE");
    }
}
