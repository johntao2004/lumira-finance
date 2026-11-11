package com.lumira.finance;

import static org.junit.jupiter.api.Assertions.*;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.identity.api.AccountOwnerPort;

import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Real PostgreSQL tests, using a unique schema in an explicitly provided dedicated QA database. */
class FinancePostgresTest {
    static JdbcClient jdbc;
    static PGSimpleDataSource source, admin;
    static String schema;
    static FinanceService service;
    static TransactionTemplate tx;
    static Map<String, UUID> users =
            Map.of(
                    "maker",
                    UUID.randomUUID(),
                    "reviewer",
                    UUID.randomUUID(),
                    "outsider",
                    UUID.randomUUID());

    @BeforeAll
    static void setup() throws Exception {
        setup(true);
    }

    static void setup(boolean v4) throws Exception {
        String url = System.getenv("FINANCE_TEST_JDBC_URL");
        requireQaUrl(url);
        if (System.getenv("FINANCE_TEST_DB_USER") == null
                || System.getenv("FINANCE_TEST_DB_PASSWORD") == null)
            throw new IllegalStateException("Explicit finance QA database credentials required");
        admin = new PGSimpleDataSource();
        admin.setURL(url);
        admin.setUser(System.getenv("FINANCE_TEST_DB_USER"));
        admin.setPassword(System.getenv("FINANCE_TEST_DB_PASSWORD"));
        schema = "finance_qa_" + UUID.randomUUID().toString().replace("-", "");
        JdbcClient.create(admin).sql("CREATE SCHEMA " + schema).update();
        source = new PGSimpleDataSource();
        source.setURL(url);
        source.setUser(admin.getUser());
        source.setPassword(admin.getPassword());
        source.setCurrentSchema(schema);
        try (var connection = source.getConnection()) {
            connection
                    .createStatement()
                    .execute(
                            Files.readString(Path.of("../migrations/V1__plugin_initialization.sql"))
                                    + Files.readString(
                                            Path.of(
                                                    "../migrations/V2__audit_scope_cursor_index.sql")));
        }
        if (v4)
            try (var connection = source.getConnection()) {
                connection
                        .createStatement()
                        .execute(
                                Files.readString(
                                                Path.of(
                                                        "../migrations/V3__v4_precision_and_year_guard.sql"))
                                        + Files.readString(
                                                Path.of(
                                                        "../migrations/V4__source_events_and_outbox.sql")));
            }
        jdbc = JdbcClient.create(source);
        AdministrationPort guard =
                new AdministrationPort() {
                    public Set<String> require(String a, String p) {
                        return Set.of(p);
                    }

                    public void requireUser(String a, UUID t, String p) {}

                    public void requireGlobal(String a, String p) {}

                    public boolean hasGlobal(String a, String p) {
                        return true;
                    }
                };
        AccountOwnerPort owner =
                (name, lock) -> {
                    UUID id = users.get(name);
                    if (id == null) throw new IllegalArgumentException();
                    return id;
                };
        service = new FinanceService(new FinanceStore(jdbc), guard, owner, new ObjectMapper());
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    @AfterAll
    static void cleanup() {
        if (schema != null && schema.matches("finance_qa_[a-f0-9]{32}"))
            JdbcClient.create(admin).sql("DROP SCHEMA " + schema + " CASCADE").update();
    }

    static Map<String, Object> write(
            String actor, UUID b, UUID y, String op, UUID target, Map<String, Object> input) {
        Map<String, Object> body = new LinkedHashMap<>(input);
        body.putIfAbsent("idempotencyKey", UUID.randomUUID().toString());
        return (Map<String, Object>)
                tx.execute(status -> service.write(actor, b, y, op, target, body));
    }

    static List<Map<String, Object>> read(String actor, UUID b, UUID y, String resource) {
        return (List<Map<String, Object>>) service.read(actor, b, y, resource, null, null);
    }

    record Fixture(UUID book, UUID year, UUID cash, UUID capital) {}

    static Fixture fixture(String standard) {
        var b =
                write(
                        "maker",
                        null,
                        null,
                        "book",
                        null,
                        Map.of(
                                "name",
                                "QA book",
                                "legalEntityName",
                                "科技企业",
                                "registrationId",
                                UUID.randomUUID().toString(),
                                "standard",
                                standard,
                                "baseCurrency",
                                "CNY",
                                "year",
                                2026));
        UUID book = (UUID) b.get("id"),
                year = (UUID) read("maker", book, null, "years").getFirst().get("id");
        UUID cash =
                (UUID)
                        write(
                                        "maker",
                                        book,
                                        year,
                                        "accounts",
                                        null,
                                        Map.of(
                                                "code",
                                                "1001",
                                                "name",
                                                "库存现金",
                                                "normalSide",
                                                "DEBIT",
                                                "category",
                                                "ASSET"))
                                .get("id");
        UUID capital =
                (UUID)
                        write(
                                        "maker",
                                        book,
                                        year,
                                        "accounts",
                                        null,
                                        Map.of(
                                                "code",
                                                "4001",
                                                "name",
                                                "实收资本",
                                                "normalSide",
                                                "CREDIT",
                                                "category",
                                                "EQUITY"))
                                .get("id");
        write(
                "maker",
                book,
                null,
                "members",
                null,
                Map.of("username", "reviewer", "role", "REVIEWER", "enabled", true));
        return new Fixture(book, year, cash, capital);
    }

    static Map<String, Object> draft(Fixture f) {
        return write(
                "maker",
                f.book,
                f.year,
                "journal",
                null,
                Map.of(
                        "date",
                        "2026-01-10",
                        "summary",
                        "投资款",
                        "lines",
                        List.of(
                                Map.of(
                                        "accountId",
                                        f.cash.toString(),
                                        "debit",
                                        "100.01",
                                        "credit",
                                        "0"),
                                Map.of(
                                        "accountId",
                                        f.capital.toString(),
                                        "debit",
                                        "0",
                                        "credit",
                                        "100.01"))));
    }

    static Map<String, Object> action(String who, Fixture f, UUID j, String action, long v) {
        return write(who, f.book, f.year, action, j, Map.of("expectedVersion", v));
    }

    @Test
    void lifecycleBothStandardsAndImmutableFacts() {
        for (String standard : List.of("CAS", "SMALL")) {
            var f = fixture(standard);
            var j = draft(f);
            UUID id = (UUID) j.get("id");
            action("maker", f, id, "submit", 1);
            assertThrows(FinanceException.class, () -> action("maker", f, id, "approve", 2));
            action("reviewer", f, id, "approve", 2);
            var posted = action("maker", f, id, "post", 3);
            assertEquals("POSTED", posted.get("status"));
            assertThrows(FinanceException.class, () -> action("maker", f, id, "edit", 4));
            assertThrows(
                    Exception.class,
                    () ->
                            jdbc.sql("UPDATE plugin_finance_line SET debit=1 WHERE journal_id=?")
                                    .param(id)
                                    .update());
            assertThrows(
                    Exception.class,
                    () ->
                            jdbc.sql("DELETE FROM plugin_finance_posting WHERE journal_id=?")
                                    .param(id)
                                    .update());
            var trial =
                    (Map<String, Object>)
                            service.read("maker", f.book, f.year, "trial-balance", null, null);
            assertEquals(true, trial.get("balanced"));
            assertEquals("100.01000000", trial.get("debit"));
            assertThrows(
                    FinanceException.class,
                    () -> service.read("outsider", f.book, f.year, "journals", id, null));
        }
    }

    @Test
    void replayStillRequiresCurrentMembership() {
        var f = fixture("CAS");
        write(
                "maker",
                f.book,
                null,
                "members",
                null,
                Map.of("username", "reviewer", "role", "MAKER", "enabled", true));
        Map<String, Object> body =
                Map.of(
                        "idempotencyKey",
                        "replay-key-" + UUID.randomUUID(),
                        "date",
                        "2026-01-10",
                        "summary",
                        "权限回归",
                        "lines",
                        List.of(
                                Map.of("accountId", f.cash.toString(), "debit", "1", "credit", "0"),
                                Map.of(
                                        "accountId",
                                        f.capital.toString(),
                                        "debit",
                                        "0",
                                        "credit",
                                        "1")));
        var first = write("reviewer", f.book, f.year, "journal", null, body);
        assertEquals(
                first.get("id").toString(),
                write("reviewer", f.book, f.year, "journal", null, body).get("id").toString());
        write(
                "maker",
                f.book,
                null,
                "members",
                null,
                Map.of("username", "reviewer", "role", "MAKER", "enabled", false));
        assertThrows(
                FinanceException.class,
                () -> write("reviewer", f.book, f.year, "journal", null, body));
    }

    @Test
    void concurrentPostingOnlyOnce() throws Exception {
        var f = fixture("SMALL");
        UUID j = (UUID) draft(f).get("id");
        action("maker", f, j, "submit", 1);
        action("reviewer", f, j, "approve", 2);
        Map<String, Object> body =
                Map.of("expectedVersion", 3, "idempotencyKey", "concurrent-" + UUID.randomUUID());
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Callable<Map<String, Object>>> calls = new ArrayList<>();
            for (int n = 0; n < 20; n++)
                calls.add(() -> write("maker", f.book, f.year, "post", j, body));
            for (var result : executor.invokeAll(calls))
                assertEquals("POSTED", result.get().get("status"));
        }
        assertEquals(
                1L,
                jdbc.sql("SELECT count(*) FROM plugin_finance_posting WHERE journal_id=?")
                        .param(j)
                        .query(Long.class)
                        .single());
    }

    @Test
    void closedPeriodReversalAndPrecision() {
        var f = fixture("CAS");
        UUID j = (UUID) draft(f).get("id");
        action("maker", f, j, "submit", 1);
        action("reviewer", f, j, "approve", 2);
        action("maker", f, j, "post", 3);
        var period = read("maker", f.book, f.year, "periods").getFirst();
        UUID p = (UUID) period.get("id");
        var preview =
                write("maker", f.book, f.year, "close-preview", p, Map.of("expectedVersion", 1));
        write(
                "maker",
                f.book,
                f.year,
                "close",
                p,
                Map.of("expectedVersion", 1, "digest", preview.get("digest")));
        var reversal =
                write(
                        "maker",
                        f.book,
                        f.year,
                        "reversals",
                        j,
                        Map.of("expectedVersion", 4, "date", "2026-02-01", "reason", "本期更正"));
        assertEquals("REVERSAL", reversal.get("type"));
        assertThrows(FinanceException.class, () -> draft(f));
        assertThrows(
                FinanceException.class,
                () ->
                        write(
                                "maker",
                                f.book,
                                f.year,
                                "journal",
                                null,
                                Map.of(
                                        "date",
                                        "2026-02-01",
                                        "summary",
                                        "超精度",
                                        "lines",
                                        List.of(
                                                Map.of(
                                                        "accountId",
                                                        f.cash.toString(),
                                                        "debit",
                                                        "1.0000001",
                                                        "credit",
                                                        "0"),
                                                Map.of(
                                                        "accountId",
                                                        f.capital.toString(),
                                                        "debit",
                                                        "0",
                                                        "credit",
                                                        "1.0000001")))));
    }

    @Test
    void yearCloseCarriesOpeningOnceAndPreservesNextYearFacts() {
        var f = fixture("CAS");
        UUID journal = (UUID) draft(f).get("id");
        action("maker", f, journal, "submit", 1);
        action("reviewer", f, journal, "approve", 2);
        action("maker", f, journal, "post", 3);
        UUID targetYear =
                (UUID) write("maker", f.book, null, "years", null, Map.of("year", 2027)).get("id");
        for (var account : read("maker", f.book, f.year, "accounts"))
            write(
                    "maker",
                    f.book,
                    targetYear,
                    "accounts",
                    null,
                    Map.of(
                            "code",
                            account.get("code"),
                            "name",
                            account.get("name"),
                            "normalSide",
                            account.get("normalSide"),
                            "category",
                            account.get("category")));
        for (var period : read("maker", f.book, f.year, "periods")) {
            UUID p = (UUID) period.get("id");
            var preview =
                    write(
                            "maker",
                            f.book,
                            f.year,
                            "close-preview",
                            p,
                            Map.of("expectedVersion", 1));
            write(
                    "maker",
                    f.book,
                    f.year,
                    "close",
                    p,
                    Map.of("expectedVersion", 1, "digest", preview.get("digest")));
        }
        var plan =
                write(
                        "maker",
                        f.book,
                        f.year,
                        "year-plan",
                        null,
                        Map.of("targetYearId", targetYear.toString()));
        assertEquals("PREVIEW", plan.get("status"));
        UUID planId = (UUID) plan.get("id");
        assertThrows(
                FinanceException.class,
                () ->
                        write(
                                "maker",
                                f.book,
                                f.year,
                                "year-approve",
                                planId,
                                Map.of("expectedVersion", 1, "digest", plan.get("digest"))));
        write(
                "reviewer",
                f.book,
                f.year,
                "year-approve",
                planId,
                Map.of("expectedVersion", 1, "digest", plan.get("digest")));
        Map<String, Object> execution =
                Map.of(
                        "idempotencyKey",
                        "year-exec-" + UUID.randomUUID(),
                        "expectedVersion",
                        2,
                        "digest",
                        plan.get("digest"));
        assertEquals(
                "EXECUTED",
                write("maker", f.book, f.year, "year-execute", planId, execution).get("status"));
        assertEquals(
                "EXECUTED",
                write("maker", f.book, f.year, "year-execute", planId, execution).get("status"));
        assertEquals(
                2L,
                jdbc.sql(
                                "SELECT count(*) FROM plugin_finance_opening WHERE book_id=? AND"
                                        + " year_id=?")
                        .params(f.book, targetYear)
                        .query(Long.class)
                        .single());
        assertThrows(
                FinanceException.class,
                () ->
                        write(
                                "maker",
                                f.book,
                                f.year,
                                "accounts",
                                null,
                                Map.of(
                                        "code",
                                        "9999",
                                        "name",
                                        "closed",
                                        "normalSide",
                                        "DEBIT",
                                        "category",
                                        "ASSET")));
    }

    @Test
    void ownerCannotEditAnotherMakersDraftAndRevokedApproverIsStale() {
        var f = fixture("CAS");
        var draft = draft(f);
        UUID j = (UUID) draft.get("id");
        write(
                "maker",
                f.book,
                null,
                "members",
                null,
                Map.of("username", "reviewer", "role", "OWNER", "enabled", true));
        assertThrows(
                FinanceException.class,
                () ->
                        write(
                                "reviewer",
                                f.book,
                                f.year,
                                "edit",
                                j,
                                Map.of(
                                        "expectedVersion",
                                        1,
                                        "date",
                                        "2026-01-10",
                                        "summary",
                                        "绕过自审",
                                        "lines",
                                        draft.get("lines"))));
        assertThrows(FinanceException.class, () -> action("reviewer", f, j, "submit", 1));
        action("maker", f, j, "submit", 1);
        action("reviewer", f, j, "approve", 2);
        write(
                "maker",
                f.book,
                null,
                "members",
                null,
                Map.of("username", "reviewer", "role", "READER", "enabled", true));
        FinanceException stale =
                assertThrows(FinanceException.class, () -> action("maker", f, j, "post", 3));
        assertEquals("APPROVAL_STALE", stale.code);
    }

    @Test
    void recursiveCanonicalIdempotencyAndReadOnlyPreview() {
        var f = fixture("CAS");
        var a = new LinkedHashMap<String, Object>();
        a.put("accountId", f.cash.toString());
        a.put("debit", "1");
        a.put("credit", "0");
        var b = new LinkedHashMap<String, Object>();
        b.put("credit", "0");
        b.put("debit", "1");
        b.put("accountId", f.cash.toString());
        String key = "canonical-" + UUID.randomUUID();
        var input = new LinkedHashMap<String, Object>();
        input.put("idempotencyKey", key);
        input.put("date", "2026-01-10");
        input.put("summary", "canonical");
        input.put(
                "lines",
                List.of(a, Map.of("accountId", f.capital.toString(), "debit", "0", "credit", "1")));
        var first = write("maker", f.book, f.year, "journal", null, input);
        input.put(
                "lines",
                List.of(b, Map.of("accountId", f.capital.toString(), "debit", "0", "credit", "1")));
        assertEquals(
                first.get("id").toString(),
                write("maker", f.book, f.year, "journal", null, input).get("id").toString());
        UUID p = (UUID) read("maker", f.book, f.year, "periods").getFirst().get("id");
        String previewKey = "preview-" + UUID.randomUUID();
        write(
                "maker",
                f.book,
                f.year,
                "close-preview",
                p,
                Map.of("idempotencyKey", previewKey, "expectedVersion", 1));
        assertEquals(
                0L,
                jdbc.sql("SELECT count(*) FROM plugin_finance_idempotency WHERE key=?")
                        .param(previewKey)
                        .query(Long.class)
                        .single());
    }

    @Test
    void controlledProfitClosingBothStandards() {
        for (String standard : List.of("CAS", "SMALL")) {
            var f = fixture(standard);
            UUID revenue =
                    (UUID)
                            write(
                                            "maker",
                                            f.book,
                                            f.year,
                                            "accounts",
                                            null,
                                            Map.of(
                                                    "code",
                                                    "6001",
                                                    "name",
                                                    "收入",
                                                    "normalSide",
                                                    "CREDIT",
                                                    "category",
                                                    "REVENUE"))
                                    .get("id");
            write(
                    "maker",
                    f.book,
                    f.year,
                    "accounts",
                    null,
                    Map.of(
                            "code",
                            "4103",
                            "name",
                            "本年利润",
                            "normalSide",
                            "CREDIT",
                            "category",
                            "EQUITY",
                            "purpose",
                            "CURRENT_YEAR_PROFIT"));
            write(
                    "maker",
                    f.book,
                    f.year,
                    "accounts",
                    null,
                    Map.of(
                            "code",
                            "4104",
                            "name",
                            "未分配利润",
                            "normalSide",
                            "CREDIT",
                            "category",
                            "EQUITY",
                            "purpose",
                            "RETAINED_EARNINGS"));
            UUID business =
                    (UUID)
                            write(
                                            "maker",
                                            f.book,
                                            f.year,
                                            "journal",
                                            null,
                                            Map.of(
                                                    "date",
                                                    "2026-12-31",
                                                    "summary",
                                                    "年度收入",
                                                    "lines",
                                                    List.of(
                                                            Map.of(
                                                                    "accountId",
                                                                    f.cash.toString(),
                                                                    "debit",
                                                                    "20",
                                                                    "credit",
                                                                    "0"),
                                                            Map.of(
                                                                    "accountId",
                                                                    revenue.toString(),
                                                                    "debit",
                                                                    "0",
                                                                    "credit",
                                                                    "20"))))
                                    .get("id");
            action("maker", f, business, "submit", 1);
            action("reviewer", f, business, "approve", 2);
            action("maker", f, business, "post", 3);
            assertThrows(
                    FinanceException.class,
                    () ->
                            write(
                                    "maker",
                                    f.book,
                                    f.year,
                                    "closing-drafts",
                                    null,
                                    Map.of("date", "2026-12-31", "stage", "YEAR_END")));
            var close =
                    write(
                            "maker",
                            f.book,
                            f.year,
                            "closing-drafts",
                            null,
                            Map.of("date", "2026-12-31", "stage", "PROFIT_LOSS"));
            assertEquals("PROFIT_CLOSE", close.get("type"));
            UUID closeId = (UUID) close.get("id");
            action("maker", f, closeId, "submit", 1);
            action("reviewer", f, closeId, "approve", 2);
            action("maker", f, closeId, "post", 3);
            var carry =
                    write(
                            "maker",
                            f.book,
                            f.year,
                            "closing-drafts",
                            null,
                            Map.of("date", "2026-12-31", "stage", "YEAR_END"));
            UUID carryId = (UUID) carry.get("id");
            action("maker", f, carryId, "submit", 1);
            action("reviewer", f, carryId, "approve", 2);
            action("maker", f, carryId, "post", 3);
            var trial =
                    (Map<String, Object>)
                            service.read("maker", f.book, f.year, "trial-balance", null, null);
            assertEquals(true, trial.get("balanced"));
            var rows = (List<Map<String, Object>>) trial.get("rows");
            assertEquals(
                    "0.00000000",
                    rows.stream()
                            .filter(r -> r.get("code").equals("4103"))
                            .findFirst()
                            .orElseThrow()
                            .get("balance"));
            assertEquals(
                    "-20.00000000",
                    rows.stream()
                            .filter(r -> r.get("code").equals("4104"))
                            .findFirst()
                            .orElseThrow()
                            .get("balance"));
        }
    }

    @Test
    void rejectWithdrawCancelRemainAudited() {
        var f = fixture("CAS");
        UUID j = (UUID) draft(f).get("id");
        action("maker", f, j, "submit", 1);
        action("maker", f, j, "withdraw", 2);
        action("maker", f, j, "submit", 3);
        write(
                "reviewer",
                f.book,
                f.year,
                "reject",
                j,
                Map.of("expectedVersion", 4, "reason", "补充依据"));
        var cancelled =
                write(
                        "maker",
                        f.book,
                        f.year,
                        "cancel",
                        j,
                        Map.of("expectedVersion", 5, "reason", "草稿作废"));
        assertEquals("CANCELLED", cancelled.get("status"));
        assertThrows(FinanceException.class, () -> action("maker", f, j, "submit", 6));
    }

    static UUID postBusiness(Fixture f, UUID revenue, String date, String amount) {
        UUID journal =
                (UUID)
                        write(
                                        "maker",
                                        f.book,
                                        f.year,
                                        "journal",
                                        null,
                                        Map.of(
                                                "date",
                                                date,
                                                "summary",
                                                "截止日回归",
                                                "lines",
                                                List.of(
                                                        Map.of(
                                                                "accountId",
                                                                f.cash.toString(),
                                                                "debit",
                                                                amount,
                                                                "credit",
                                                                "0"),
                                                        Map.of(
                                                                "accountId",
                                                                revenue.toString(),
                                                                "debit",
                                                                "0",
                                                                "credit",
                                                                amount))))
                                .get("id");
        action("maker", f, journal, "submit", 1);
        action("reviewer", f, journal, "approve", 2);
        action("maker", f, journal, "post", 3);
        return journal;
    }

    @Test
    void monthEndCutoffIgnoresFutureFactsButInvalidatesOnCurrentFacts() {
        var f = fixture("CAS");
        UUID revenue =
                (UUID)
                        write(
                                        "maker",
                                        f.book,
                                        f.year,
                                        "accounts",
                                        null,
                                        Map.of(
                                                "code",
                                                "6001",
                                                "name",
                                                "收入",
                                                "normalSide",
                                                "CREDIT",
                                                "category",
                                                "REVENUE"))
                                .get("id");
        write(
                "maker",
                f.book,
                f.year,
                "accounts",
                null,
                Map.of(
                        "code",
                        "4103",
                        "name",
                        "本年利润",
                        "normalSide",
                        "CREDIT",
                        "category",
                        "EQUITY",
                        "purpose",
                        "CURRENT_YEAR_PROFIT"));
        write(
                "maker",
                f.book,
                f.year,
                "accounts",
                null,
                Map.of(
                        "code",
                        "4104",
                        "name",
                        "未分配利润",
                        "normalSide",
                        "CREDIT",
                        "category",
                        "EQUITY",
                        "purpose",
                        "RETAINED_EARNINGS"));
        postBusiness(f, revenue, "2026-01-10", "20");
        var closing =
                write(
                        "maker",
                        f.book,
                        f.year,
                        "closing-drafts",
                        null,
                        Map.of("date", "2026-01-31", "stage", "PROFIT_LOSS"));
        UUID first = (UUID) closing.get("id");
        action("maker", f, first, "submit", 1);
        action("reviewer", f, first, "approve", 2);
        postBusiness(f, revenue, "2026-02-10", "30");
        var unchanged =
                (Map<String, Object>)
                        service.read("maker", f.book, f.year, "journals", first, null);
        assertEquals("APPROVED", unchanged.get("status"));
        assertEquals(3L, ((Number) unchanged.get("version")).longValue());
        action("maker", f, first, "post", 3);
        postBusiness(f, revenue, "2026-01-15", "5");
        var second =
                write(
                        "maker",
                        f.book,
                        f.year,
                        "closing-drafts",
                        null,
                        Map.of("date", "2026-01-31", "stage", "PROFIT_LOSS"));
        UUID secondId = (UUID) second.get("id");
        action("maker", f, secondId, "submit", 1);
        action("reviewer", f, secondId, "approve", 2);
        postBusiness(f, revenue, "2026-01-20", "2");
        var stale =
                (Map<String, Object>)
                        service.read("maker", f.book, f.year, "journals", secondId, null);
        assertEquals("DRAFT", stale.get("status"));
        assertNull(stale.get("approvedBy"));
        assertEquals(4L, ((Number) stale.get("version")).longValue());
        assertEquals(
                "APPROVAL_STALE",
                assertThrows(FinanceException.class, () -> action("maker", f, secondId, "post", 4))
                        .code);
        assertEquals(
                1L,
                jdbc.sql(
                                "SELECT count(*) FROM plugin_finance_audit WHERE target_id=? AND"
                                        + " action='CLOSING_APPROVAL_INVALIDATED'")
                        .param(secondId)
                        .query(Long.class)
                        .single());
    }

    @Test
    void yearOpeningMappingRequiresMatchingPurpose() {
        var f = fixture("SMALL");
        UUID j = (UUID) draft(f).get("id");
        action("maker", f, j, "submit", 1);
        action("reviewer", f, j, "approve", 2);
        action("maker", f, j, "post", 3);
        UUID target =
                (UUID) write("maker", f.book, null, "years", null, Map.of("year", 2027)).get("id");
        write(
                "maker",
                f.book,
                target,
                "accounts",
                null,
                Map.of("code", "1001", "name", "库存现金", "normalSide", "DEBIT", "category", "ASSET"));
        write(
                "maker",
                f.book,
                target,
                "accounts",
                null,
                Map.of(
                        "code",
                        "4001",
                        "name",
                        "错误用途映射",
                        "normalSide",
                        "CREDIT",
                        "category",
                        "EQUITY",
                        "purpose",
                        "RETAINED_EARNINGS"));
        var snapshot =
                service.yearSnapshot(
                        service.originalScope("maker", f.book, f.year, "read"), f.year, target);
        assertTrue(
                ((List<String>) snapshot.get("blockers"))
                        .stream().anyMatch(message -> message.contains("4001")));
    }

    static void requireQaUrl(String url) {
        if (url == null
                || !url.matches(
                        "jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost|\\[::1\\]):[0-9]{1,5}/lumira_qa_finance_[a-z0-9_]+"))
            throw new IllegalStateException(
                    "Explicit loopback finance QA JDBC URL with port and lumira_qa_finance_*"
                            + " database required");
        int port = java.net.URI.create(url.substring(5)).getPort();
        if (port < 1 || port > 65535)
            throw new IllegalStateException("Invalid finance QA database port");
    }

    @Test
    void databaseGuardRejectsRemoteCredentialsQueriesAndWrongProjects() {
        for (String unsafe :
                List.of(
                        "jdbc:postgresql://db.example:15489/lumira_qa_finance_kernel",
                        "jdbc:postgresql://127.0.0.1/lumira_qa_finance_kernel",
                        "jdbc:postgresql://127.0.0.1:15489/lumira_next",
                        "jdbc:postgresql://user:pass@127.0.0.1:15489/lumira_qa_finance_kernel",
                        "jdbc:postgresql://127.0.0.1:15489/lumira_qa_finance_kernel?user=other",
                        "jdbc:postgresql://127.0.0.1:0/lumira_qa_finance_kernel"))
            assertThrows(IllegalStateException.class, () -> requireQaUrl(unsafe));
        assertThrows(IllegalStateException.class, () -> requireQaUrl(null));
        assertDoesNotThrow(
                () -> requireQaUrl("jdbc:postgresql://127.0.0.1:15489/lumira_qa_finance_kernel"));
    }

    @Test
    void selectedYearScopeFiltersEveryReadAndWriteAndRevalidatesApprover() {
        var f = fixture("CAS");
        UUID target =
                (UUID) write("maker", f.book, null, "years", null, Map.of("year", 2027)).get("id");
        UUID journal = (UUID) draft(f).get("id");
        write(
                "maker",
                f.book,
                null,
                "members",
                null,
                Map.of(
                        "username",
                        "reviewer",
                        "role",
                        "MAKER",
                        "yearScope",
                        "SELECTED",
                        "yearIds",
                        List.of(f.year.toString()),
                        "enabled",
                        true));
        assertEquals(1, read("reviewer", f.book, null, "years").size());
        for (String resource :
                List.of(
                        "accounts",
                        "periods",
                        "journals",
                        "trial-balance",
                        "ledger",
                        "reports",
                        "year-close-plans",
                        "documents"))
            assertThrows(
                    FinanceException.class,
                    () -> service.read("reviewer", f.book, target, resource, journal, null));
        assertThrows(
                FinanceException.class,
                () -> service.originalScope("reviewer", f.book, target, "read"));
        Map<String, Object> body =
                Map.of(
                        "idempotencyKey",
                        "year-scope-replay-" + UUID.randomUUID(),
                        "date",
                        "2026-01-12",
                        "summary",
                        "年度授权",
                        "lines",
                        List.of(
                                Map.of("accountId", f.cash.toString(), "debit", "1", "credit", "0"),
                                Map.of(
                                        "accountId",
                                        f.capital.toString(),
                                        "debit",
                                        "0",
                                        "credit",
                                        "1")));
        write("reviewer", f.book, f.year, "journal", null, body);
        assertThrows(
                FinanceException.class,
                () ->
                        write(
                                "reviewer",
                                f.book,
                                target,
                                "journal",
                                null,
                                Map.of(
                                        "date",
                                        "2027-01-12",
                                        "summary",
                                        "越年写",
                                        "lines",
                                        body.get("lines"))));
        write(
                "maker",
                f.book,
                null,
                "members",
                null,
                Map.of(
                        "username",
                        "reviewer",
                        "role",
                        "REVIEWER",
                        "yearScope",
                        "SELECTED",
                        "yearIds",
                        List.of(f.year.toString()),
                        "enabled",
                        true));
        action("maker", f, journal, "submit", 1);
        action("reviewer", f, journal, "approve", 2);
        write(
                "maker",
                f.book,
                null,
                "members",
                null,
                Map.of(
                        "username",
                        "reviewer",
                        "role",
                        "REVIEWER",
                        "yearScope",
                        "SELECTED",
                        "yearIds",
                        List.of(target.toString()),
                        "enabled",
                        true));
        assertEquals(
                "APPROVAL_STALE",
                assertThrows(FinanceException.class, () -> action("maker", f, journal, "post", 3))
                        .code);
        assertThrows(
                FinanceException.class,
                () -> write("reviewer", f.book, f.year, "journal", null, body));
        write(
                "maker",
                f.book,
                null,
                "members",
                null,
                Map.of(
                        "username",
                        "reviewer",
                        "role",
                        "OWNER",
                        "yearScope",
                        "SELECTED",
                        "yearIds",
                        List.of(f.year.toString()),
                        "enabled",
                        true));
        assertThrows(
                FinanceException.class,
                () ->
                        write(
                                "reviewer",
                                f.book,
                                f.year,
                                "year-plan",
                                null,
                                Map.of("targetYearId", target.toString())));
        assertThrows(
                FinanceException.class,
                () ->
                        write(
                                "reviewer",
                                f.book,
                                null,
                                "members",
                                null,
                                Map.of(
                                        "username",
                                        "reviewer",
                                        "role",
                                        "OWNER",
                                        "yearScope",
                                        "ALL",
                                        "enabled",
                                        true)));
        assertThrows(
                FinanceException.class,
                () ->
                        write(
                                "reviewer",
                                f.book,
                                null,
                                "members",
                                null,
                                Map.of(
                                        "username",
                                        "outsider",
                                        "role",
                                        "READER",
                                        "yearScope",
                                        "ALL",
                                        "enabled",
                                        true)));
        assertThrows(
                FinanceException.class,
                () ->
                        write(
                                "reviewer",
                                f.book,
                                null,
                                "members",
                                null,
                                Map.of(
                                        "username",
                                        "outsider",
                                        "role",
                                        "READER",
                                        "yearScope",
                                        "SELECTED",
                                        "yearIds",
                                        List.of(target.toString()),
                                        "enabled",
                                        true)));
        var member =
                read("maker", f.book, null, "members").stream()
                        .filter(m -> m.get("username").equals("reviewer"))
                        .findFirst()
                        .orElseThrow();
        assertEquals("SELECTED", member.get("yearScope"));
        assertEquals(List.of(f.year), member.get("yearIds"));
        write(
                "maker",
                f.book,
                null,
                "members",
                null,
                Map.of("username", "reviewer", "role", "READER", "enabled", true));
        assertEquals(2, read("reviewer", f.book, null, "years").size());
        write("maker", f.book, null, "years", null, Map.of("year", 2028));
        assertEquals(3, read("reviewer", f.book, null, "years").size());
        var foreign = fixture("SMALL");
        assertThrows(
                FinanceException.class,
                () ->
                        write(
                                "maker",
                                f.book,
                                null,
                                "members",
                                null,
                                Map.of(
                                        "username",
                                        "reviewer",
                                        "role",
                                        "READER",
                                        "yearScope",
                                        "SELECTED",
                                        "yearIds",
                                        List.of(foreign.year.toString()),
                                        "enabled",
                                        true)));
    }

    @Test
    void accountingDateRemainsCalendarStringAcrossJsonRoundTrip() {
        var fixture = fixture("CAS");
        var created = draft(fixture);
        UUID journalId = (UUID) created.get("id");
        var detail =
                (Map<String, Object>)
                        service.read(
                                "maker",
                                fixture.book(),
                                fixture.year(),
                                "journals",
                                journalId,
                                null);
        assertInstanceOf(
                String.class,
                detail.get("date"),
                "SQL DATE must be a timezone-free calendar string");
        assertEquals("2026-01-10", detail.get("date"));
        assertEquals("2026-01-10", service.decode(service.encode(detail)).get("date"));
        var listing = read("maker", fixture.book(), fixture.year(), "journals");
        assertEquals("2026-01-10", listing.getFirst().get("date"));
        var store = new FinanceStore(jdbc);
        var types =
                store.one(
                        "SELECT DATE '2026-01-10' AS calendar_date, TIMESTAMP '2026-01-10 12:34:56'"
                                + " AS occurred_at");
        assertEquals("2026-01-10", types.get("calendar_date"));
        assertInstanceOf(
                java.sql.Timestamp.class,
                types.get("occurred_at"),
                "Timestamp conversion must remain unchanged");
    }
}
