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
class FinanceTransactionPostgresTest {
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
                                                    "../migrations/V2__audit_scope_cursor_index.sql"))
                                    + Files.readString(
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

    static void requireQaUrl(String url) {
        if (url == null
                || !url.matches(
                        "jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]{1,5}/lumira_qa_finance_kernel"))
            throw new IllegalStateException("Only explicit loopback finance kernel QA DB allowed");
    }

    @Test
    void approvedPostingAndMonthCloseSerializeWithoutHalfCommit() throws Exception {
        for (String standard : List.of("CAS", "SMALL")) {
            for (int round = 0; round < 2; round++) {
                Fixture f = fixture(standard);
                UUID journal = (UUID) draft(f).get("id");
                action("maker", f, journal, "submit", 1);
                action("reviewer", f, journal, "approve", 2);
                UUID period = (UUID) read("maker", f.book, f.year, "periods").getFirst().get("id");
                CyclicBarrier start = new CyclicBarrier(2);
                try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
                    Future<String> posting =
                            workers.submit(
                                    () -> {
                                        start.await(10, TimeUnit.SECONDS);
                                        action("maker", f, journal, "post", 3);
                                        return "POSTED";
                                    });
                    Future<String> closing =
                            workers.submit(
                                    () -> {
                                        start.await(10, TimeUnit.SECONDS);
                                        return tx.execute(
                                                status -> {
                                                    Map<String, Object> preview =
                                                            (Map<String, Object>)
                                                                    service.write(
                                                                            "maker",
                                                                            f.book,
                                                                            f.year,
                                                                            "close-preview",
                                                                            period,
                                                                            Map.of(
                                                                                    "expectedVersion",
                                                                                    1,
                                                                                    "idempotencyKey",
                                                                                    UUID.randomUUID()
                                                                                            .toString()));
                                                    if (!Boolean.TRUE.equals(
                                                            preview.get("canClose")))
                                                        return "BLOCKED";
                                                    service.write(
                                                            "maker",
                                                            f.book,
                                                            f.year,
                                                            "close",
                                                            period,
                                                            Map.of(
                                                                    "expectedVersion",
                                                                    1,
                                                                    "digest",
                                                                    preview.get("digest"),
                                                                    "idempotencyKey",
                                                                    UUID.randomUUID().toString()));
                                                    return "CLOSED";
                                                });
                                    });
                    assertEquals("POSTED", posting.get(20, TimeUnit.SECONDS));
                    assertEquals(
                            1L,
                            jdbc.sql(
                                            "SELECT posting_seq FROM plugin_finance_year_guard"
                                                    + " WHERE year_id=?")
                                    .param(f.year)
                                    .query(Long.class)
                                    .single());
                    String closed = closing.get(20, TimeUnit.SECONDS);
                    // Close either observes the approved pending journal or observes the committed
                    // posting.
                    assertTrue(Set.of("CLOSED", "BLOCKED").contains(closed), closed);
                    assertEquals(
                            1L,
                            count(
                                    "SELECT count(*) FROM plugin_finance_posting WHERE"
                                            + " journal_id=?",
                                    journal));
                    assertEquals(
                            2L,
                            count(
                                    "SELECT count(*) FROM plugin_finance_line WHERE journal_id=?",
                                    journal));
                    assertEquals(
                            0,
                            jdbc.sql(
                                            "SELECT sum(debit-credit) FROM plugin_finance_line"
                                                    + " WHERE journal_id=?")
                                    .param(journal)
                                    .query(java.math.BigDecimal.class)
                                    .single()
                                    .signum());
                    assertEquals(
                            "POSTED",
                            jdbc.sql("SELECT status FROM plugin_finance_journal WHERE id=?")
                                    .param(journal)
                                    .query(String.class)
                                    .single());
                    if (!"CLOSED".equals(closed)) closePeriod(f, period);
                    assertEquals(
                            "CLOSED",
                            jdbc.sql("SELECT status FROM plugin_finance_period WHERE id=?")
                                    .param(period)
                                    .query(String.class)
                                    .single());
                    // action() always generates a NEW key; replay of the original post key is
                    // legal.
                    assertThrows(
                            FinanceException.class, () -> action("maker", f, journal, "post", 4));
                    assertEquals(
                            1L,
                            count(
                                    "SELECT count(*) FROM plugin_finance_posting WHERE"
                                            + " journal_id=?",
                                    journal));
                }
            }
        }
    }

    @Test
    void yearExecutionRollsBackThenRetriesAndReplaysWithoutDuplicateOpening() {
        for (String standard : List.of("CAS", "SMALL")) {
            Fixture f = fixture(standard);
            UUID first = (UUID) draft(f).get("id");
            action("maker", f, first, "submit", 1);
            action("reviewer", f, first, "approve", 2);
            action("maker", f, first, "post", 3);
            UUID targetYear =
                    (UUID)
                            write("maker", f.book, null, "years", null, Map.of("year", 2027))
                                    .get("id");
            Map<String, UUID> targetAccounts = new HashMap<>();
            for (var account : read("maker", f.book, f.year, "accounts")) {
                var target =
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
                targetAccounts.put(account.get("code").toString(), (UUID) target.get("id"));
            }
            Fixture next =
                    new Fixture(
                            f.book,
                            targetYear,
                            targetAccounts.get("1001"),
                            targetAccounts.get("4001"));
            UUID nextJournal =
                    (UUID)
                            write(
                                            "maker",
                                            next.book,
                                            next.year,
                                            "journal",
                                            null,
                                            Map.of(
                                                    "date",
                                                    "2027-01-10",
                                                    "summary",
                                                    "Next-year independent capital",
                                                    "lines",
                                                    List.of(
                                                            Map.of(
                                                                    "accountId",
                                                                    next.cash.toString(),
                                                                    "debit",
                                                                    "20.02",
                                                                    "credit",
                                                                    "0"),
                                                            Map.of(
                                                                    "accountId",
                                                                    next.capital.toString(),
                                                                    "debit",
                                                                    "0",
                                                                    "credit",
                                                                    "20.02"))))
                                    .get("id");
            action("maker", next, nextJournal, "submit", 1);
            action("reviewer", next, nextJournal, "approve", 2);
            action("maker", next, nextJournal, "post", 3);
            var targetFactsBefore =
                    jdbc.sql(
                                    "SELECT account_id,debit,credit FROM plugin_finance_line WHERE"
                                            + " journal_id=? ORDER BY account_id")
                            .param(nextJournal)
                            .query()
                            .listOfRows();
            for (var period : read("maker", f.book, f.year, "periods"))
                closePeriod(f, (UUID) period.get("id"));
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
            write(
                    "reviewer",
                    f.book,
                    f.year,
                    "year-approve",
                    planId,
                    Map.of("expectedVersion", 1, "digest", plan.get("digest")));
            String key = "rollback-year-" + UUID.randomUUID();
            Map<String, Object> execution =
                    Map.of(
                            "idempotencyKey",
                            key,
                            "expectedVersion",
                            2,
                            "digest",
                            plan.get("digest"));
            var sourceBefore =
                    jdbc.sql("SELECT status,version FROM plugin_finance_year WHERE id=?")
                            .param(f.year)
                            .query()
                            .singleRow();
            var planBefore =
                    jdbc.sql("SELECT status,version FROM plugin_finance_close_plan WHERE id=?")
                            .param(planId)
                            .query()
                            .singleRow();
            assertThrows(
                    InjectedFailure.class,
                    () ->
                            tx.execute(
                                    status -> {
                                        var executed =
                                                (Map<String, Object>)
                                                        service.write(
                                                                "maker",
                                                                f.book,
                                                                f.year,
                                                                "year-execute",
                                                                planId,
                                                                execution);
                                        assertEquals("EXECUTED", executed.get("status"));
                                        assertEquals(
                                                2L,
                                                count(
                                                        "SELECT count(*) FROM"
                                                                + " plugin_finance_opening WHERE"
                                                                + " year_id=?",
                                                        targetYear));
                                        assertEquals(
                                                "CLOSED_PENDING_ARCHIVE",
                                                jdbc.sql(
                                                                "SELECT status FROM"
                                                                    + " plugin_finance_year WHERE"
                                                                    + " id=?")
                                                        .param(f.year)
                                                        .query(String.class)
                                                        .single());
                                        throw new InjectedFailure();
                                    }));
            assertEquals(
                    0L,
                    jdbc.sql(
                                    "SELECT opening_version FROM plugin_finance_year_guard WHERE"
                                            + " year_id=?")
                            .param(targetYear)
                            .query(Long.class)
                            .single());
            assertEquals(
                    0L,
                    jdbc.sql(
                                    "SELECT source_close_version FROM plugin_finance_year_guard"
                                            + " WHERE year_id=?")
                            .param(f.year)
                            .query(Long.class)
                            .single());
            assertEquals(
                    sourceBefore,
                    jdbc.sql("SELECT status,version FROM plugin_finance_year WHERE id=?")
                            .param(f.year)
                            .query()
                            .singleRow());
            assertEquals(
                    planBefore,
                    jdbc.sql("SELECT status,version FROM plugin_finance_close_plan WHERE id=?")
                            .param(planId)
                            .query()
                            .singleRow());
            assertEquals(
                    0L,
                    count(
                            "SELECT count(*) FROM plugin_finance_opening WHERE year_id=?",
                            targetYear));
            assertEquals(
                    0L, count("SELECT count(*) FROM plugin_finance_idempotency WHERE key=?", key));
            var success = write("maker", f.book, f.year, "year-execute", planId, execution);
            assertEquals("EXECUTED", success.get("status"));
            assertEquals(
                    1L,
                    jdbc.sql(
                                    "SELECT opening_version FROM plugin_finance_year_guard WHERE"
                                            + " year_id=?")
                            .param(targetYear)
                            .query(Long.class)
                            .single());
            assertEquals(
                    1L,
                    jdbc.sql(
                                    "SELECT source_close_version FROM plugin_finance_year_guard"
                                            + " WHERE year_id=?")
                            .param(f.year)
                            .query(Long.class)
                            .single());
            var replay = write("maker", f.book, f.year, "year-execute", planId, execution);
            assertEquals(success.get("id").toString(), replay.get("id").toString());
            assertEquals("EXECUTED", replay.get("status"));
            assertEquals(
                    2L,
                    count(
                            "SELECT count(*) FROM plugin_finance_opening WHERE year_id=?",
                            targetYear));
            assertEquals(
                    1L, count("SELECT count(*) FROM plugin_finance_idempotency WHERE key=?", key));
            assertEquals(
                    "CLOSED_PENDING_ARCHIVE",
                    jdbc.sql("SELECT status FROM plugin_finance_year WHERE id=?")
                            .param(f.year)
                            .query(String.class)
                            .single());
            assertEquals(
                    ((Number) sourceBefore.get("version")).longValue() + 1,
                    jdbc.sql("SELECT version FROM plugin_finance_year WHERE id=?")
                            .param(f.year)
                            .query(Long.class)
                            .single());
            assertEquals(
                    3L,
                    jdbc.sql("SELECT version FROM plugin_finance_close_plan WHERE id=?")
                            .param(planId)
                            .query(Long.class)
                            .single());
            assertEquals(
                    targetFactsBefore,
                    jdbc.sql(
                                    "SELECT account_id,debit,credit FROM plugin_finance_line WHERE"
                                            + " journal_id=? ORDER BY account_id")
                            .param(nextJournal)
                            .query()
                            .listOfRows());
            assertEquals(
                    1L,
                    count(
                            "SELECT count(*) FROM plugin_finance_posting WHERE journal_id=?",
                            nextJournal));
            assertEquals(
                    0,
                    jdbc.sql(
                                    "SELECT debit FROM plugin_finance_line WHERE journal_id=? AND"
                                            + " account_id=?")
                            .params(nextJournal, next.cash)
                            .query(java.math.BigDecimal.class)
                            .single()
                            .compareTo(new java.math.BigDecimal("20.02")));
            assertEquals(
                    0,
                    jdbc.sql(
                                    "SELECT balance FROM plugin_finance_opening WHERE year_id=? AND"
                                            + " account_id=?")
                            .params(targetYear, next.cash)
                            .query(java.math.BigDecimal.class)
                            .single()
                            .compareTo(new java.math.BigDecimal("100.01")));
        }
    }

    static void closePeriod(Fixture f, UUID period) {
        var preview =
                write(
                        "maker",
                        f.book,
                        f.year,
                        "close-preview",
                        period,
                        Map.of("expectedVersion", 1));
        assertEquals(true, preview.get("canClose"), preview.toString());
        write(
                "maker",
                f.book,
                f.year,
                "close",
                period,
                Map.of("expectedVersion", 1, "digest", preview.get("digest")));
    }

    static long count(String sql, Object value) {
        return jdbc.sql(sql).param(value).query(Long.class).single();
    }

    static final class InjectedFailure extends RuntimeException {}
}
