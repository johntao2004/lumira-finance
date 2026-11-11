package com.lumira.finance;

import static org.junit.jupiter.api.Assertions.*;

import com.lumira.platform.common.AuthorizationContextProjectionPort;

import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.*;

/** Dedicated kernel schema only. All test data and anomaly probes are finance-owned. */
class FinanceCarryReadinessPostgresTest {
    static FinanceCarryReadiness carry;
    static FinanceStore db;

    @BeforeAll
    static void setup() throws Exception {
        FinanceProfitClosingPostgresTest.setup();
        db = FinanceJournalPersonPostgresTest.db;
        AuthorizationContextProjectionPort auth =
                p ->
                        Optional.of(
                                new AuthorizationContextProjectionPort.Snapshot(
                                        FinanceJournalPersonPostgresTest.ALL,
                                        FinanceJournalPersonPostgresTest.ALL,
                                        false,
                                        null,
                                        0));
        carry =
                new FinanceCarryReadiness(
                        db,
                        FinanceJournalPersonPostgresTest.modern,
                        FinanceJournalPersonPostgresTest.gate,
                        FinancePostgresTest.tx.getTransactionManager(),
                        auth);
    }

    @AfterAll
    static void cleanup() {
        FinanceProfitClosingPostgresTest.cleanup();
    }

    @BeforeEach
    void reset() {
        FinanceJournalPersonPostgresTest.denied.clear();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Object x) {
        return (Map<String, Object>) x;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> rows(Object x) {
        return (List<Map<String, Object>>) x;
    }

    static Map<String, Object> map(Object... p) {
        return FinanceJournalPersonGate.map(p);
    }

    record Pair(
            FinanceProfitClosingPostgresTest.Case source,
            FinanceProfitClosingPostgresTest.Case target) {}

    static Pair pair(String standard, boolean targetPerson) {
        return pair(standard, targetPerson, true);
    }

    static Pair pair(String standard, boolean targetPerson, boolean sourcePerson) {
        FinanceProfitClosingPostgresTest.Case source;
        if (sourcePerson) source = FinanceProfitClosingPostgresTest.fixture(standard);
        else {
            var baseSource = FinanceJournalPersonPostgresTest.fixture(standard);
            source =
                    new FinanceProfitClosingPostgresTest.Case(
                            baseSource,
                            FinanceProfitClosingPostgresTest.account(
                                    baseSource, "6001", "REVENUE", "CREDIT", "NONE"),
                            FinanceProfitClosingPostgresTest.account(
                                    baseSource, "6601", "EXPENSE", "DEBIT", "NONE"),
                            FinanceProfitClosingPostgresTest.account(
                                    baseSource, "4103", "EQUITY", "CREDIT", "CURRENT_YEAR_PROFIT"),
                            FinanceProfitClosingPostgresTest.account(
                                    baseSource, "4104", "EQUITY", "CREDIT", "RETAINED_EARNINGS"));
        }
        var y =
                FinanceJournalPersonPostgresTest.as(
                        "maker",
                        () ->
                                FinancePostgresTest.write(
                                        "maker",
                                        source.book(),
                                        null,
                                        "years",
                                        null,
                                        map("year", 2027)));
        UUID year = UUID.fromString(y.get("id").toString());
        var f =
                new FinancePostgresTest.Fixture(
                        source.book(), year, UUID.randomUUID(), UUID.randomUUID());
        var base = new FinanceJournalPersonPostgresTest.Case(f, source.tenant());
        UUID cash =
                FinanceProfitClosingPostgresTest.account(base, "1001", "ASSET", "DEBIT", "NONE");
        UUID capital =
                FinanceProfitClosingPostgresTest.account(base, "4001", "EQUITY", "CREDIT", "NONE");
        base =
                new FinanceJournalPersonPostgresTest.Case(
                        new FinancePostgresTest.Fixture(source.book(), year, cash, capital),
                        source.tenant());
        var target =
                new FinanceProfitClosingPostgresTest.Case(
                        base,
                        FinanceProfitClosingPostgresTest.account(
                                base, "6001", "REVENUE", "CREDIT", "NONE"),
                        FinanceProfitClosingPostgresTest.account(
                                base, "6601", "EXPENSE", "DEBIT", "NONE"),
                        FinanceProfitClosingPostgresTest.account(
                                base, "4103", "EQUITY", "CREDIT", "CURRENT_YEAR_PROFIT"),
                        FinanceProfitClosingPostgresTest.account(
                                base, "4104", "EQUITY", "CREDIT", "RETAINED_EARNINGS"));
        if (targetPerson) FinanceJournalPersonPostgresTest.enable(base);
        return new Pair(source, target);
    }

    static Map<String, Object> read(String actor, Pair p, String op, Map<String, String> query) {
        return FinanceJournalPersonPostgresTest.as(
                actor,
                () -> {
                    var request = new MockHttpServletRequest();
                    request.addParameter("targetYearId", p.target.year().toString());
                    query.forEach(request::addParameter);
                    return obj(carry.read(actor, p.source.book(), p.source.year(), op, request));
                });
    }

    static Map<String, Object> summary(Pair p) {
        return read("maker", p, "summary", Map.of());
    }

    static Map<String, Object> page(Pair p, Map<String, Object> summary, Map<String, String> q) {
        var query = new HashMap<>(q);
        query.put("observationToken", obj(summary.get("observation")).get("token").toString());
        return read("maker", p, "accounts", query);
    }

    static Map<String, Object> cashRow(Map<String, Object> page, Pair p) {
        return rows(page.get("items")).stream()
                .filter(
                        r ->
                                r.get("sourceAccount") != null
                                        && obj(r.get("sourceAccount"))
                                                .get("id")
                                                .toString()
                                                .equals(p.source.base().f().cash().toString()))
                .findFirst()
                .orElseThrow();
    }

    static void code(String expected, Runnable run) {
        assertEquals(expected, assertThrows(FinanceException.class, run::run).code);
    }

    static String amount(Map<String, Object> owner, String field) {
        return Objects.toString(obj(owner.get(field)).get("amount"), null);
    }

    static Map<String, String> fingerprint(UUID book) {
        var result = new LinkedHashMap<String, String>();
        for (var table :
                List.of(
                        "plugin_finance_journal",
                        "plugin_finance_line",
                        "plugin_finance_posting",
                        "plugin_finance_opening",
                        "plugin_finance_audit",
                        "plugin_finance_outbox",
                        "plugin_finance_profit_closing_run",
                        "plugin_finance_profit_closing_request",
                        "plugin_finance_close_plan"))
            result.put(
                    table,
                    db.one(
                                    "SELECT md5(COALESCE(string_agg(row_to_json(x)::text,'' ORDER"
                                            + " BY row_to_json(x)::text),'')) digest FROM (SELECT *"
                                            + " FROM "
                                            + table
                                            + " WHERE book_id=?) x",
                                    book)
                            .get("digest")
                            .toString());
        return result;
    }

    @Test
    void bothStandardsObserveActualYearEndAndIndependentFutureTargetMovementsWithoutWrites() {
        for (String standard : List.of("CAS", "SMALL")) {
            var p = pair(standard, true);
            FinanceProfitClosingPostgresTest.normal(
                    p.source, "2026-01-15", p.source.base().f().cash(), p.source.revenue(), "100");
            FinanceProfitClosingPostgresTest.normal(
                    p.source, "2026-01-16", p.source.expense(), p.source.base().f().cash(), "60");
            FinanceProfitClosingPostgresTest.posted(p.source, "PROFIT_LOSS", "2026-01-31", null);
            FinanceProfitClosingPostgresTest.posted(p.source, "YEAR_END", "2026-12-31", null);
            FinanceProfitClosingPostgresTest.normal(
                    p.target, "2027-12-20", p.target.base().f().cash(), p.target.revenue(), "20");
            var before = fingerprint(p.source.book());
            var summary = summary(p);
            var page = page(p, summary, Map.of("limit", "100"));
            var cash = cashRow(page, p);
            assertEquals(standard, obj(summary.get("scope")).get("standard"));
            assertEquals("40.00000000", amount(cash, "sourceEndingBalance"));
            assertEquals("0.00000000", amount(cash, "targetOpening"));
            assertEquals("20.00000000", amount(cash, "targetPostedDebit"));
            assertEquals("20.00000000", amount(cash, "targetEndingBalance"));
            assertEquals("40.00000000", amount(cash, "candidateOpeningDifference"));
            assertEquals(false, summary.get("canExecuteBase"));
            assertEquals(false, page.get("yearCloseEnabled"));
            assertEquals(24, rows(summary.get("checks")).size());
            assertEquals(
                    "MISSING",
                    rows(summary.get("checks")).stream()
                            .filter(r -> r.get("id").equals("FORMAL_OPENING_BATCH"))
                            .findFirst()
                            .orElseThrow()
                            .get("status"));
            assertEquals(before, fingerprint(p.source.book()));
            FinanceJournalPersonPostgresTest.as(
                    "maker",
                    () -> {
                        code(
                                "JOURNAL_PERSON_YEAR_CLOSE_UNSUPPORTED",
                                () ->
                                        FinancePostgresTest.write(
                                                "maker",
                                                p.source.book(),
                                                p.source.year(),
                                                "year-plan",
                                                null,
                                                map("targetYearId", p.target.year().toString())));
                        return null;
                    });
            var plan =
                    db.list(
                            "EXPLAIN (FORMAT TEXT) SELECT l.account_id,SUM(l.debit),SUM(l.credit)"
                                + " FROM plugin_finance_line l JOIN plugin_finance_journal j"
                                + " ON(j.tenant_id,j.book_id,j.year_id,j.id)=(l.tenant_id,l.book_id,l.year_id,l.journal_id)"
                                + " WHERE l.tenant_id=? AND l.book_id=? AND l.year_id=? AND"
                                + " j.status='POSTED' GROUP BY l.account_id",
                            p.source.tenant(),
                            p.source.book(),
                            p.source.year());
            System.out.println("Carry annual aggregate plan " + standard + ": " + plan);
        }
    }

    @Test
    void keysetPagesHaveNoDuplicateAndAllWorkflowOrSemanticChangesInvalidateWholeObservation() {
        var p = pair("CAS", true);
        var initial = summary(p);
        var first = page(p, initial, Map.of("limit", "1"));
        var ids = new HashSet<String>();
        String cursor = null;
        do {
            var q = new HashMap<String, String>();
            q.put("limit", "1");
            if (cursor != null) q.put("cursor", cursor);
            var next = page(p, initial, q);
            for (var r : rows(next.get("items"))) assertTrue(ids.add(r.get("rowKey").toString()));
            cursor = (String) next.get("nextCursor");
        } while (cursor != null);
        assertEquals(((Number) first.get("populationRows")).intValue(), ids.size());
        FinanceJournalPersonPostgresTest.legacy(
                "maker",
                p.source.base(),
                "journal",
                null,
                FinanceJournalPersonPostgresTest.journalInput(p.source.base(), "1"));
        code("CARRY_READINESS_STALE", () -> page(p, initial, Map.of()));
        code(
                "INVALID_INPUT",
                () ->
                        page(
                                p,
                                initial,
                                Map.of(
                                        "limit",
                                        "2",
                                        "cursor",
                                        first.get("nextCursor").toString())));
        var fresh = summary(p);
        FinancePostgresTest.tx.executeWithoutResult(
                s ->
                        db.update(
                                "UPDATE plugin_finance_account SET name='Changed current semantic"
                                        + " name' WHERE id=?",
                                p.target.revenue()));
        code("CARRY_READINESS_STALE", () -> page(p, fresh, Map.of()));
        var beforePeriod = summary(p);
        FinancePostgresTest.tx.executeWithoutResult(
                s ->
                        db.update(
                                "UPDATE plugin_finance_period SET status='CLOSED',version=version+1"
                                        + " WHERE year_id=? AND month=12",
                                p.target.year()));
        code("CARRY_READINESS_STALE", () -> page(p, beforePeriod, Map.of()));
    }

    @Test
    void missingTargetTargetOnlyAndSemanticConflictsAreVisibleAndNeverApproved() {
        var p = pair("SMALL", false);
        FinanceProfitClosingPostgresTest.account(p.source.base(), "9991", "ASSET", "DEBIT", "NONE");
        FinanceProfitClosingPostgresTest.account(
                p.target.base(), "9992", "LIABILITY", "CREDIT", "NONE");
        FinancePostgresTest.tx.executeWithoutResult(
                s ->
                        db.update(
                                "UPDATE plugin_finance_account SET purpose='NONE' WHERE id=?",
                                p.target.retained()));
        var summary = summary(p);
        var all = page(p, summary, Map.of("limit", "100"));
        var nonzero = page(p, summary, Map.of("limit", "100", "filter", "NONZERO"));
        var statuses = rows(all.get("items")).stream().map(r -> r.get("candidateStatus")).toList();
        assertTrue(
                statuses.containsAll(
                        List.of("MISSING_TARGET", "TARGET_ONLY", "SEMANTIC_CONFLICT")));
        assertEquals(3, rows(nonzero.get("items")).size());
        for (var row : rows(nonzero.get("items"))) {
            assertEquals("NOT_APPROVED", row.get("mappingStatus"));
            assertNull(amount(row, "candidateOpeningDifference"));
        }
    }

    @Test
    void targetYearGrantIsIndependentAndTokensCannotCrossActorOrPair() {
        var p = pair("CAS", false);
        var summary = summary(p);
        FinanceJournalPersonPostgresTest.as(
                "maker",
                () ->
                        FinancePostgresTest.write(
                                "maker",
                                p.source.book(),
                                null,
                                "members",
                                null,
                                map(
                                        "username",
                                        "reviewer",
                                        "role",
                                        "REVIEWER",
                                        "enabled",
                                        true,
                                        "yearScope",
                                        "SELECTED",
                                        "yearIds",
                                        List.of(p.source.year().toString()))));
        code("SCOPE_DENIED", () -> read("reviewer", p, "summary", Map.of()));
        var q = Map.of("observationToken", obj(summary.get("observation")).get("token").toString());
        code("INVALID_INPUT", () -> read("other", p, "accounts", q));
        var other = pair("CAS", false);
        code("INVALID_INPUT", () -> read("maker", other, "accounts", q));
        FinanceJournalPersonPostgresTest.denied.add("maker:platform:finance:read");
        code("SCOPE_DENIED", () -> summary(p));
    }

    static void opening(Pair p, UUID account, String amount) {
        FinancePostgresTest.tx.executeWithoutResult(
                s -> {
                    UUID plan = UUID.randomUUID();
                    db.update(
                            "INSERT INTO"
                                + " plugin_finance_close_plan(tenant_id,book_id,id,source_year_id,target_year_id,digest,created_by,payload)"
                                + " VALUES(?,?,?,?,?,?,?,?)",
                            p.source.tenant(),
                            p.source.book(),
                            plan,
                            p.source.year(),
                            p.target.year(),
                            "a".repeat(64),
                            FinancePostgresTest.users.get("maker"),
                            "{}");
                    db.update(
                            "INSERT INTO"
                                + " plugin_finance_opening(tenant_id,book_id,year_id,account_id,plan_id,balance)"
                                + " VALUES(?,?,?,?,?,?::numeric)",
                            p.source.tenant(),
                            p.source.book(),
                            p.target.year(),
                            account,
                            plan,
                            amount);
                });
    }

    @Test
    void actualNaNAndLegacyOrUnsupportedBaselineNullTheWholeAffectedYearOnly() {
        var p = pair("CAS", false, false);
        opening(p, p.target.base().f().cash(), "NaN");
        opening(p, p.target.base().f().capital(), "100");
        var summary = summary(p);
        assertEquals("UNKNOWN_INVALID_DATA", obj(summary.get("target")).get("dataQualification"));
        assertNull(amount(obj(summary.get("target")), "openingCredit"));
        assertEquals("0.00000000", amount(obj(summary.get("source")), "openingDebit"));
        for (var r : rows(page(p, summary, Map.of("limit", "100")).get("items"))) {
            assertNull(amount(r, "targetOpening"));
            if (obj(r.get("targetCandidate"))
                            .get("id")
                            .toString()
                            .equals(p.target.base().f().cash().toString())
                    || obj(r.get("targetCandidate"))
                            .get("id")
                            .toString()
                            .equals(p.target.base().f().capital().toString()))
                assertEquals(true, r.get("targetOpeningRowExists"));
        }
        var legacy = pair("CAS", false);
        FinancePostgresTest.tx.executeWithoutResult(
                s ->
                        db.update(
                                "UPDATE plugin_finance_year SET"
                                    + " precision_profile='LEGACY_V3',precision_review_required=true"
                                    + " WHERE id=?",
                                legacy.target.year()));
        assertEquals(
                "UNSUPPORTED_PRECISION",
                obj(summary(legacy).get("target")).get("dataQualification"));
        var baseline = pair("CAS", false);
        FinancePostgresTest.tx.executeWithoutResult(
                s ->
                        db.update(
                                "UPDATE plugin_finance_year_guard SET baseline_version=1 WHERE"
                                        + " year_id=?",
                                baseline.target.year()));
        assertEquals(
                "UNSUPPORTED_BASELINE",
                obj(summary(baseline).get("target")).get("dataQualification"));
        var fractional = pair("CAS", false, false);
        opening(fractional, fractional.target.base().f().cash(), "1.12345678");
        assertEquals(
                "1.12345678",
                amount(
                        cashRow(
                                page(fractional, summary(fractional), Map.of("limit", "100")),
                                fractional),
                        "targetOpening"));
        var old = summary(fractional);
        opening(fractional, fractional.target.base().f().capital(), "2");
        code("CARRY_READINESS_STALE", () -> page(fractional, old, Map.of()));
    }

    @Test
    void realStatementCancellationReturnsBudgetErrorRatherThanEmptySuccess() {
        var p = pair("CAS", false);
        var delayed =
                new FinanceStore(FinancePostgresTest.jdbc) {
                    boolean delay = true;

                    @Override
                    List<Map<String, Object>> list(String sql, Object... args) {
                        if (delay && sql.contains("SELECT id,code,name")) {
                            delay = false;
                            super.list("SELECT pg_sleep(4)");
                        }
                        return super.list(sql, args);
                    }
                };
        AuthorizationContextProjectionPort auth =
                a ->
                        Optional.of(
                                new AuthorizationContextProjectionPort.Snapshot(
                                        FinanceJournalPersonPostgresTest.ALL,
                                        FinanceJournalPersonPostgresTest.ALL,
                                        false,
                                        null,
                                        0));
        var slow =
                new FinanceCarryReadiness(
                        delayed,
                        FinanceJournalPersonPostgresTest.modern,
                        FinanceJournalPersonPostgresTest.gate,
                        FinancePostgresTest.tx.getTransactionManager(),
                        auth);
        FinanceJournalPersonPostgresTest.as(
                "maker",
                () -> {
                    var request = new MockHttpServletRequest();
                    request.addParameter("targetYearId", p.target.year().toString());
                    code(
                            "QUERY_BUDGET_EXCEEDED",
                            () ->
                                    slow.read(
                                            "maker",
                                            p.source.book(),
                                            p.source.year(),
                                            "summary",
                                            request));
                    return null;
                });
    }
}
