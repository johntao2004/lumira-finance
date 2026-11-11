package com.lumira.finance;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Real PostgreSQL constraints, atomicity, authorization and independent retained-source tests. */
class FinanceCashMovementsPostgresTest {
    static FinanceCashMovements movements;
    static FinanceStore db;

    @BeforeAll
    static void setup() throws Exception {
        FinanceCashScopesPostgresTest.setup();
        db = FinanceCashScopesPostgresTest.db;
        try (var c = FinancePostgresTest.source.getConnection()) {
            c.createStatement()
                    .execute(
                            Files.readString(
                                            Path.of(
                                                    "../migrations/V6__cash_movement_registration.sql"))
                                    + Files.readString(
                                            Path.of("../migrations/V7__cash_match_corrections.sql"))
                                    + Files.readString(
                                            Path.of(
                                                    "../migrations/V8__cash_classification_policies.sql"))
                                    + Files.readString(
                                            Path.of("../migrations/V9__cash_source_reviews.sql"))
                                    + Files.readString(
                                            Path.of("../migrations/V10__cash_allocations.sql"))
                                    + Files.readString(
                                            Path.of("../migrations/V11__journal_list_pages.sql")));
        }
        movements =
                new FinanceCashMovements(
                        db, FinancePostgresTest.service, FinanceCashScopesPostgresTest.cash);
    }

    @AfterAll
    static void cleanup() {
        FinanceCashScopesPostgresTest.cleanup();
    }

    record Fixture(
            FinancePostgresTest.Fixture f,
            Map<String, Object> scope,
            UUID original,
            UUID journal,
            Map<String, Object> leg) {}

    static Fixture fixture(String standard) {
        var f = FinancePostgresTest.fixture(standard);
        var draft = FinanceCashScopesPostgresTest.submitted(f, standard, 1);
        var scope =
                FinanceCashScopesPostgresTest.write(
                        "reviewer",
                        f,
                        "approve",
                        (UUID) draft.get("id"),
                        FinanceCashScopesPostgresTest.action(
                                draft, "Independent qualification review"));
        var j = FinancePostgresTest.draft(f);
        UUID id = (UUID) j.get("id");
        FinancePostgresTest.action("maker", f, id, "submit", 1);
        FinancePostgresTest.action("reviewer", f, id, "approve", 2);
        FinancePostgresTest.action("maker", f, id, "post", 3);
        var page =
                movements.eligible(
                        "maker", f.book(), f.year(), (UUID) scope.get("id"), f.cash(), 20, null);
        var leg = ((List<Map<String, Object>>) page.get("items")).getFirst();
        return new Fixture(f, scope, FinanceCashScopesPostgresTest.original(f), id, leg);
    }

    static Map<String, Object> body(Fixture v) {
        var n = new LinkedHashMap<String, Object>();
        n.put("schemaVersion", FinanceCashMovements.SCHEMA);
        n.put("idempotencyKey", UUID.randomUUID().toString());
        n.put("sourceSystem", "CASH_BOOK_MANUAL_V1");
        n.put("sourceMovementKey", UUID.randomUUID().toString());
        n.put("sourceDocumentId", "cashbook-2026-01");
        n.put("sourceDocumentVersion", 1);
        n.put("evidenceKind", "CASH_BOOK");
        n.put("evidenceOriginalId", v.original().toString());
        n.put("sourceRowReference", "cash-account-A/page1/row1");
        n.put("cashScopeRevisionId", v.scope().get("id").toString());
        n.put("expectedScopeVersion", v.scope().get("scopeVersion"));
        n.put("scopeContentDigest", v.scope().get("contentDigest"));
        n.put("accountId", v.f().cash().toString());
        n.put("occurredDate", "2026-01-10");
        n.put("direction", "IN");
        n.put("amount", "100.01");
        n.put("currency", "CNY");
        n.put("transactionKind", "ORDINARY_CAPITAL_RECEIPT");
        n.put("description", "Independently supplied cashbook row; authenticity unreviewed");
        n.put("journalId", v.journal().toString());
        n.put("lineNo", v.leg().get("lineNo"));
        n.put("expectedJournalVersion", v.leg().get("journalVersion"));
        n.put("expectedPostingSeq", v.leg().get("postingSeq"));
        n.put("reason", "Register independent retained source and exact full cash leg");
        return n;
    }

    static Map<String, Object> write(String actor, Fixture v, UUID id, Map<String, Object> n) {
        return FinancePostgresTest.tx.execute(
                s -> movements.write(actor, v.f().book(), v.f().year(), id, n));
    }

    static Map<String, Object> action(Map<String, Object> r) {
        return new LinkedHashMap<>(
                Map.of(
                        "schemaVersion",
                        FinanceCashMovements.SCHEMA,
                        "idempotencyKey",
                        UUID.randomUUID().toString(),
                        "expectedVersion",
                        r.get("version"),
                        "targetContentVersion",
                        r.get("contentVersion"),
                        "contentDigest",
                        r.get("contentDigest"),
                        "reason",
                        "Withdraw erroneous unverified assertion without changing financial"
                                + " facts"));
    }

    static void code(String expected, Runnable work) {
        assertEquals(expected, assertThrows(FinanceException.class, work::run).code);
    }

    static String facts(Fixture v) {
        var list = new ArrayList<Object>();
        for (String table :
                List.of(
                        "plugin_finance_journal",
                        "plugin_finance_line",
                        "plugin_finance_posting",
                        "plugin_finance_opening",
                        "plugin_finance_year_guard",
                        "plugin_finance_cash_scope_guard"))
            list.add(
                    db.list(
                            "SELECT to_jsonb(t)::text AS value FROM "
                                    + table
                                    + " t WHERE book_id=? ORDER BY to_jsonb(t)::text",
                            v.f().book()));
        return FinancePostgresTest.service.encode(list);
    }

    @Test
    void standardsStableReplayVoidTombstoneAndUnchangedFinancialFacts() {
        for (String standard : List.of("CAS", "SMALL")) {
            var v = fixture(standard);
            String before = facts(v);
            var n = body(v);
            var r = write("maker", v, null, n);
            UUID id = (UUID) r.get("id");
            assertEquals("REGISTERED", r.get("status"));
            assertEquals("UNREVIEWED", r.get("verificationStatus"));
            assertEquals(false, r.get("reportContributionEnabled"));
            assertEquals("100.01000000", r.get("normalizedAmount"));
            assertEquals(id, write("maker", v, null, n).get("id"));
            var newKey = new LinkedHashMap<>(n);
            newKey.put("idempotencyKey", UUID.randomUUID().toString());
            assertEquals(id, write("maker", v, null, newKey).get("id"));
            var changed = new LinkedHashMap<>(newKey);
            changed.put("description", "Changed business evidence assertion");
            changed.put("idempotencyKey", UUID.randomUUID().toString());
            code("SOURCE_PAYLOAD_CONFLICT", () -> write("maker", v, null, changed));
            var voided = write("maker", v, id, action(r));
            assertEquals("VOIDED", voided.get("status"));
            assertEquals("VOIDED", write("maker", v, null, n).get("status"));
            assertEquals("VOIDED", write("maker", v, null, newKey).get("status"));
            var reused = body(v);
            reused.put(
                    "evidenceOriginalId", FinanceCashScopesPostgresTest.original(v.f()).toString());
            reused.put("sourceSystem", "CASH_RECEIPT_MANUAL_V1");
            reused.put("evidenceKind", "CASH_RECEIPT");
            code("SOURCE_ALREADY_VOIDED", () -> write("maker", v, null, reused));
            assertEquals(before, facts(v));
            var wm = (Map<String, Object>) voided.get("asOfWatermark");
            assertEquals(2L, ((Number) wm.get("registrationVersion")).longValue());
        }
    }

    @Test
    void twentyCompetingSourceRowsCannotClaimSameCashLegTwice() throws Exception {
        var v = fixture("CAS");
        String before = facts(v);
        var pool = Executors.newFixedThreadPool(5);
        var start = new CountDownLatch(1);
        var results = new ArrayList<Future<Boolean>>();
        try {
            for (int i = 0; i < 20; i++) {
                int row = i;
                results.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    var n = body(v);
                                    n.put("sourceRowReference", "account-A/page1/row" + row);
                                    try {
                                        write("maker", v, null, n);
                                        return true;
                                    } catch (FinanceException e) {
                                        assertTrue(
                                                Set.of(
                                                                "CASH_LEG_ALREADY_REGISTERED",
                                                                "CASH_SOURCE_DUPLICATE_CONFLICT")
                                                        .contains(e.code));
                                        return false;
                                    }
                                }));
            }
            start.countDown();
            int successes = 0;
            for (var f : results) if (f.get(40, TimeUnit.SECONDS)) successes++;
            assertEquals(1, successes);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(
                1L,
                ((Number)
                                db.one(
                                                "SELECT count(*) AS n FROM"
                                                    + " plugin_finance_cash_movement_registration"
                                                    + " WHERE book_id=?",
                                                v.f().book())
                                        .get("n"))
                        .longValue());
        assertEquals(before, facts(v));
    }

    @Test
    void strictSourceAmountScopeEvidenceAndLegBoundaries() {
        var v = fixture("CAS");
        for (var pair : List.of(Map.entry("amount", "100.010"), Map.entry("amount", "100.005"))) {
            var n = body(v);
            n.put(pair.getKey(), pair.getValue());
            code("INVALID_CURRENCY_SCALE", () -> write("maker", v, null, n));
        }
        for (String field :
                List.of("sourceMovementKey", "sourceDocumentId", "sourceRowReference")) {
            for (Object bad :
                    Arrays.asList(
                            null, " leading", "trailing ", "hidden\nrow", "hidden\u007frow")) {
                var n = body(v);
                n.put(field, bad);
                code("INVALID_INPUT", () -> write("maker", v, null, n));
            }
        }
        for (Object bad : Arrays.asList(null, 7, "UNKNOWN")) {
            var n = body(v);
            n.put("sourceSystem", bad);
            n.put("evidenceKind", null);
            code("RULE_VERSION_UNAVAILABLE", () -> write("maker", v, null, n));
            var k = body(v);
            k.put("transactionKind", bad);
            k.put("direction", null);
            code("RULE_VERSION_UNAVAILABLE", () -> write("maker", v, null, k));
        }
        var overflow = body(v);
        overflow.put("amount", "100000000000000000000.01");
        code("AMOUNT_OVERFLOW", () -> write("maker", v, null, overflow));
        for (String key :
                List.of("amount", "occurredDate", "expectedPostingSeq", "expectedJournalVersion")) {
            var n = body(v);
            n.put(
                    key,
                    switch (key) {
                        case "amount" -> "99.99";
                        case "occurredDate" -> "2026-01-11";
                        default -> 99999;
                    });
            code("CASH_MOVEMENT_MATCH_INVALID", () -> write("maker", v, null, n));
        }
        var stale = body(v);
        stale.put("expectedScopeVersion", 999);
        code("CASH_MOVEMENT_SCOPE_STALE", () -> write("maker", v, null, stale));
        var other = fixture("SMALL");
        var cross = body(v);
        cross.put("evidenceOriginalId", other.original().toString());
        code("SCOPE_DENIED", () -> write("maker", v, null, cross));
        var unknown = body(v);
        unknown.put("transactionKind", "INTERNAL_TRANSFER");
        code("RULE_VERSION_UNAVAILABLE", () -> write("maker", v, null, unknown));
        var submitted = FinanceCashScopesPostgresTest.submitted(v.f(), "CAS", 2);
        FinanceCashScopesPostgresTest.write(
                "reviewer",
                v.f(),
                "approve",
                (UUID) submitted.get("id"),
                FinanceCashScopesPostgresTest.action(
                        submitted, "Approve next month independently"));
        assertEquals("REGISTERED", write("maker", v, null, body(v)).get("status"));
    }

    @Test
    void authorizationBeforeReplayAndOriginalPreparerOnlyVoid() {
        var v = fixture("CAS");
        var n = body(v);
        var r = write("maker", v, null, n);
        UUID id = (UUID) r.get("id");
        code("SCOPE_DENIED", () -> write("reviewer", v, id, action(r)));
        code("SCOPE_DENIED", () -> movements.detail("outsider", v.f().book(), v.f().year(), id));
        FinancePostgresTest.write(
                "maker",
                v.f().book(),
                null,
                "members",
                null,
                Map.of("username", "reviewer", "role", "OWNER", "enabled", true));
        code("SCOPE_DENIED", () -> write("reviewer", v, id, action(r)));
        FinancePostgresTest.write(
                "reviewer",
                v.f().book(),
                null,
                "members",
                null,
                Map.of("username", "maker", "role", "MAKER", "enabled", false));
        code("SCOPE_DENIED", () -> write("maker", v, null, n));
    }

    @Test
    void rollbackLeavesNoSourceAuditCacheOrWatermarkAndVoidAllowsStaleAccount() {
        var v = fixture("CAS");
        var n = body(v);
        String before = facts(v);
        long audit =
                ((Number)
                                db.one(
                                                "SELECT count(*) n FROM plugin_finance_audit WHERE"
                                                        + " book_id=?",
                                                v.f().book())
                                        .get("n"))
                        .longValue();
        assertThrows(
                IllegalStateException.class,
                () ->
                        FinancePostgresTest.tx.execute(
                                s -> {
                                    movements.write("maker", v.f().book(), v.f().year(), null, n);
                                    throw new IllegalStateException("injected rollback");
                                }));
        assertEquals(
                0L,
                ((Number)
                                db.one(
                                                "SELECT count(*) n FROM"
                                                    + " plugin_finance_cash_movement_registration"
                                                    + " WHERE book_id=?",
                                                v.f().book())
                                        .get("n"))
                        .longValue());
        assertEquals(
                audit,
                ((Number)
                                db.one(
                                                "SELECT count(*) n FROM plugin_finance_audit WHERE"
                                                        + " book_id=?",
                                                v.f().book())
                                        .get("n"))
                        .longValue());
        assertTrue(
                db.list(
                                "SELECT key FROM plugin_finance_idempotency WHERE key=?",
                                n.get("idempotencyKey"))
                        .isEmpty());
        assertEquals(before, facts(v));
        var r = write("maker", v, null, n);
        db.update(
                "UPDATE plugin_finance_account SET enabled=false WHERE book_id=? AND id=?",
                v.f().book(),
                v.f().cash());
        assertEquals("VOIDED", write("maker", v, (UUID) r.get("id"), action(r)).get("status"));
    }

    @Test
    void strictScopedPaginationAndSqlSnapshotGuards() {
        var v = fixture("CAS");
        var n = body(v);
        var r = write("maker", v, null, n);
        UUID id = (UUID) r.get("id");
        for (String path :
                List.of(
                        "{expectedPostingSeq}",
                        "{sourceDocumentVersion}",
                        "{matchedLegSnapshot,lineNo}",
                        "{matchedLegSnapshot,number}",
                        "{scopeItemSnapshot,singleCashInstrument}")) {
            var error =
                    assertThrows(
                            org.springframework.dao.DataAccessException.class,
                            () ->
                                    FinancePostgresTest.tx.execute(
                                            s ->
                                                    db.update(
                                                            "INSERT INTO"
                                                                + " plugin_finance_cash_movement_registration"
                                                                + " SELECT"
                                                                + " (jsonb_populate_record(NULL::plugin_finance_cash_movement_registration,to_jsonb(r)||jsonb_build_object('id',?::text,'source_movement_key',?::text,'source_row_reference',?::text,'content',jsonb_set(content,?::text[],'null'::jsonb)))).*"
                                                                + " FROM"
                                                                + " plugin_finance_cash_movement_registration"
                                                                + " r WHERE id=?",
                                                            UUID.randomUUID().toString(),
                                                            UUID.randomUUID().toString(),
                                                            UUID.randomUUID().toString(),
                                                            path,
                                                            id)));
            assertTrue(
                    error.getMostSpecificCause().getMessage().contains("SNAPSHOT"),
                    error.getMessage());
        }
        var second = FinancePostgresTest.draft(v.f());
        UUID jid = (UUID) second.get("id");
        FinancePostgresTest.action("maker", v.f(), jid, "submit", 1);
        FinancePostgresTest.action("reviewer", v.f(), jid, "approve", 2);
        FinancePostgresTest.action("maker", v.f(), jid, "post", 3);
        var page =
                movements.eligible(
                        "maker",
                        v.f().book(),
                        v.f().year(),
                        (UUID) v.scope().get("id"),
                        v.f().cash(),
                        1,
                        null);
        String cursor = (String) page.get("nextCursor");
        assertNotNull(cursor);
        var forged =
                FinancePostgresTest.service.decode(
                        new String(
                                Base64.getUrlDecoder().decode(cursor),
                                java.nio.charset.StandardCharsets.UTF_8));
        forged.put("line", forged.get("line").toString());
        String forgedCursor =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(
                                FinancePostgresTest.service
                                        .encode(forged)
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        code(
                "INVALID_INPUT",
                () ->
                        movements.eligible(
                                "maker",
                                v.f().book(),
                                v.f().year(),
                                (UUID) v.scope().get("id"),
                                v.f().cash(),
                                1,
                                forgedCursor));
        var next =
                movements.eligible(
                        "maker",
                        v.f().book(),
                        v.f().year(),
                        (UUID) v.scope().get("id"),
                        v.f().cash(),
                        1,
                        cursor);
        assertNotEquals(
                ((List<Map<String, Object>>) page.get("items")).getFirst().get("journalId"),
                ((List<Map<String, Object>>) next.get("items")).getFirst().get("journalId"));
        code(
                "INVALID_INPUT",
                () ->
                        movements.eligible(
                                "maker",
                                v.f().book(),
                                v.f().year(),
                                (UUID) v.scope().get("id"),
                                v.f().cash(),
                                101,
                                null));
        code(
                "INVALID_INPUT",
                () ->
                        movements.eligible(
                                "maker",
                                v.f().book(),
                                v.f().year(),
                                (UUID) v.scope().get("id"),
                                v.f().cash(),
                                1,
                                "not-a-cursor"));
        db.update(
                "UPDATE plugin_finance_period SET status='CLOSED' WHERE book_id=? AND year_id=? AND"
                        + " month=1",
                v.f().book(),
                v.f().year());
        code("PERIOD_CLOSED", () -> write("maker", v, null, n));
        code("PERIOD_CLOSED", () -> write("maker", v, id, action(r)));
        assertEquals(
                "REGISTERED",
                movements.detail("maker", v.f().book(), v.f().year(), id).get("status"));
    }

    @Test
    void sameTenantFileRowAndStableSourceCannotLeakUnauthorizedOriginalBook() {
        var original = fixture("CAS");
        var source = body(original);
        write("maker", original, null, source);
        var parent =
                db.one(
                        "SELECT tenant_id,legal_entity_id FROM plugin_finance_book WHERE id=?",
                        original.f().book());
        var book =
                FinancePostgresTest.write(
                        "maker",
                        null,
                        null,
                        "book",
                        null,
                        Map.of(
                                "tenantId",
                                parent.get("tenant_id").toString(),
                                "legalEntityId",
                                parent.get("legal_entity_id").toString(),
                                "name",
                                "Second authorized book",
                                "standard",
                                "CAS",
                                "baseCurrency",
                                "CNY",
                                "year",
                                2026));
        UUID bid = (UUID) book.get("id"),
                year =
                        (UUID)
                                FinancePostgresTest.read("maker", bid, null, "years")
                                        .getFirst()
                                        .get("id");
        UUID cash =
                (UUID)
                        FinancePostgresTest.write(
                                        "maker",
                                        bid,
                                        year,
                                        "accounts",
                                        null,
                                        Map.of(
                                                "code",
                                                "1001",
                                                "name",
                                                "Independent cash",
                                                "normalSide",
                                                "DEBIT",
                                                "category",
                                                "ASSET"))
                                .get("id");
        UUID capital =
                (UUID)
                        FinancePostgresTest.write(
                                        "maker",
                                        bid,
                                        year,
                                        "accounts",
                                        null,
                                        Map.of(
                                                "code",
                                                "4001",
                                                "name",
                                                "Capital",
                                                "normalSide",
                                                "CREDIT",
                                                "category",
                                                "EQUITY"))
                                .get("id");
        var f = new FinancePostgresTest.Fixture(bid, year, cash, capital);
        FinancePostgresTest.write(
                "maker",
                bid,
                null,
                "members",
                null,
                Map.of("username", "reviewer", "role", "REVIEWER", "enabled", true));
        var submitted = FinanceCashScopesPostgresTest.submitted(f, "CAS", 1);
        var scope =
                FinanceCashScopesPostgresTest.write(
                        "reviewer",
                        f,
                        "approve",
                        (UUID) submitted.get("id"),
                        FinanceCashScopesPostgresTest.action(
                                submitted, "Independently review second book"));
        UUID journal = (UUID) FinancePostgresTest.draft(f).get("id");
        FinancePostgresTest.action("maker", f, journal, "submit", 1);
        FinancePostgresTest.action("reviewer", f, journal, "approve", 2);
        FinancePostgresTest.action("maker", f, journal, "post", 3);
        var legs =
                (List<Map<String, Object>>)
                        movements
                                .eligible(
                                        "maker", bid, year, (UUID) scope.get("id"), cash, 20, null)
                                .get("items");
        var target =
                new Fixture(
                        f,
                        scope,
                        FinanceCashScopesPostgresTest.original(f),
                        journal,
                        legs.getFirst());
        FinancePostgresTest.write(
                "maker",
                bid,
                null,
                "members",
                null,
                Map.of("username", "reviewer", "role", "OWNER", "enabled", true));
        FinancePostgresTest.write(
                "maker",
                original.f().book(),
                null,
                "members",
                null,
                Map.of("username", "reviewer", "role", "REVIEWER", "enabled", false));
        var sameFileRow = body(target);
        code("SCOPE_DENIED", () -> write("reviewer", target, null, sameFileRow));
        var sameIdentity = body(target);
        sameIdentity.put("sourceMovementKey", source.get("sourceMovementKey"));
        code("SCOPE_DENIED", () -> write("reviewer", target, null, sameIdentity));
        assertTrue(
                db.list(
                                "SELECT id FROM plugin_finance_cash_movement_registration WHERE"
                                        + " book_id=?",
                                bid)
                        .isEmpty());
    }
}
