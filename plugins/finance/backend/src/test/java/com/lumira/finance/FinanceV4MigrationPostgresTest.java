package com.lumira.finance;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;

class FinanceV4MigrationPostgresTest {
    @BeforeAll
    static void setup() throws Exception {
        FinancePostgresTest.setup(false);
    }

    @AfterAll
    static void cleanup() {
        FinancePostgresTest.cleanup();
    }

    @Test
    void forwardMigrationPreservesLegacyEvidenceAndGatesOldKeysWhileNewBooksUseV4()
            throws Exception {
        var db = new FinanceStore(FinancePostgresTest.jdbc);
        UUID t = UUID.randomUUID(),
                e = UUID.randomUUID(),
                b = UUID.randomUUID(),
                y = UUID.randomUUID(),
                next = UUID.randomUUID(),
                p = UUID.randomUUID(),
                a = UUID.randomUUID(),
                c = UUID.randomUUID(),
                j = UUID.randomUUID(),
                plan = UUID.randomUUID();
        UUID maker = FinancePostgresTest.users.get("maker"),
                reviewer = FinancePostgresTest.users.get("reviewer");
        db.update("INSERT INTO plugin_finance_tenant VALUES(?,'Legacy tenant',?)", t, maker);
        db.update(
                "INSERT INTO plugin_finance_entity VALUES(?,?,'Legacy entity','legacy-tax')", t, e);
        db.update(
                "INSERT INTO"
                    + " plugin_finance_book(tenant_id,id,legal_entity_id,name,standard,base_currency)"
                    + " VALUES(?,?,?,'Legacy book','CAS','CNY')",
                t,
                b,
                e);
        db.update(
                "INSERT INTO plugin_finance_member(tenant_id,book_id,user_id,username,role,enabled)"
                        + " VALUES(?,?,?,'maker','OWNER',true)",
                t,
                b,
                maker);
        db.update(
                "INSERT INTO plugin_finance_year(tenant_id,book_id,id,year)"
                        + " VALUES(?,?,?,2026),(?,?,?,2027)",
                t,
                b,
                y,
                t,
                b,
                next);
        db.update(
                "INSERT INTO plugin_finance_period(tenant_id,book_id,year_id,id,month)"
                        + " VALUES(?,?,?,?,1)",
                t,
                b,
                y,
                p);
        for (UUID yr : List.of(y, next)) {
            db.update(
                    "INSERT INTO"
                        + " plugin_finance_account(tenant_id,book_id,year_id,id,code,name,normal_side,category)"
                        + " VALUES(?,?,?,?,'1001','Cash','DEBIT','ASSET'),(?,?,?,?,'4001','Capital','CREDIT','EQUITY')",
                    t,
                    b,
                    yr,
                    a,
                    t,
                    b,
                    yr,
                    c);
        }
        db.update(
                "INSERT INTO"
                    + " plugin_finance_journal(tenant_id,book_id,year_id,id,period_id,date,summary,created_by)"
                    + " VALUES(?,?,?,?,?,'2026-01-10','six-place historical fact',?)",
                t,
                b,
                y,
                j,
                p,
                maker);
        db.update(
                "INSERT INTO plugin_finance_line"
                        + " VALUES(?,?,?,?,1,?,100.000001,0,''),(?,?,?,?,2,?,0,100.000001,'')",
                t,
                b,
                y,
                j,
                a,
                t,
                b,
                y,
                j,
                c);
        db.update(
                "UPDATE plugin_finance_journal SET"
                    + " status='APPROVED',version=2,approved_by=?,approved_version=2 WHERE id=?",
                reviewer,
                j);
        db.update(
                "INSERT INTO"
                    + " plugin_finance_posting(tenant_id,book_id,year_id,journal_id,journal_version,digest,posted_by)"
                    + " VALUES(?,?,?,?,2,'old-posting-digest',?)",
                t,
                b,
                y,
                j,
                maker);
        db.update(
                "UPDATE plugin_finance_journal SET status='POSTED',version=3,number=1 WHERE id=?",
                j);
        String evidence =
                FinancePostgresTest.service.encode(
                        FinancePostgresTest.service.canonical(
                                Map.of("amount", "100.000001", "version", 2)));
        db.update(
                "INSERT INTO"
                    + " plugin_finance_audit(id,tenant_id,book_id,year_id,actor_id,action,target_id,payload_hash,payload)"
                    + " VALUES(?,?,?,?,?,'LEGACY_APPROVAL',?,?,?::jsonb)",
                UUID.randomUUID(),
                t,
                b,
                y,
                maker,
                j,
                FinanceService.hash(evidence),
                evidence);
        db.update(
                "INSERT INTO"
                    + " plugin_finance_close_plan(tenant_id,book_id,id,source_year_id,target_year_id,status,version,digest,created_by,approved_by,payload)"
                    + " VALUES(?,?,?,?,?,'EXECUTED',3,'old-plan-digest',?,?,?)",
                t,
                b,
                plan,
                y,
                next,
                maker,
                reviewer,
                "{\"balance\":\"100.000001\"}");
        db.update(
                "INSERT INTO plugin_finance_opening"
                        + " VALUES(?,?,?,?,?,100.000001),(?,?,?,?,?,-100.000001)",
                t,
                b,
                next,
                a,
                plan,
                t,
                b,
                next,
                c,
                plan);
        db.update("UPDATE plugin_finance_year SET status='CLOSED' WHERE id=?", y);
        String key = "legacy-post-replay";
        Map<String, Object> body = Map.of("idempotencyKey", key, "expectedVersion", 3);
        String response = "{\"id\":\"" + j + "\",\"status\":\"POSTED\",\"amount\":\"100.000001\"}";
        db.update(
                "INSERT INTO plugin_finance_idempotency VALUES(?,?,?,?,?)",
                maker,
                key,
                b + "/" + y + "/post/" + j,
                FinanceService.hash(
                        FinancePostgresTest.service.encode(
                                FinancePostgresTest.service.canonical(body))),
                response);
        var auditBefore =
                db.list(
                        "SELECT id,payload_hash,payload::text AS payload FROM"
                                + " plugin_finance_audit");
        var cacheBefore = db.list("SELECT * FROM plugin_finance_idempotency");
        var planBefore =
                db.list(
                        "SELECT payload,digest,version,approved_by,status FROM"
                                + " plugin_finance_close_plan");
        var journalBefore =
                db.list(
                        "SELECT id,date,status,version,approved_by,approved_version,number FROM"
                                + " plugin_finance_journal");
        var postingBefore =
                db.list(
                        "SELECT journal_id,journal_version,digest,posted_by,posted_at FROM"
                                + " plugin_finance_posting");
        try (var connection = FinancePostgresTest.source.getConnection()) {
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
        assertEquals(
                auditBefore,
                db.list(
                        "SELECT id,payload_hash,payload::text AS payload FROM"
                                + " plugin_finance_audit"));
        assertEquals(cacheBefore, db.list("SELECT * FROM plugin_finance_idempotency"));
        assertEquals(
                planBefore,
                db.list(
                        "SELECT payload,digest,version,approved_by,status FROM"
                                + " plugin_finance_close_plan"));
        assertEquals(
                journalBefore,
                db.list(
                        "SELECT id,date,status,version,approved_by,approved_version,number FROM"
                                + " plugin_finance_journal"));
        assertEquals(
                postingBefore,
                db.list(
                        "SELECT journal_id,journal_version,digest,posted_by,posted_at FROM"
                                + " plugin_finance_posting"));
        assertEquals(
                0,
                new BigDecimal("100.000001")
                        .compareTo(
                                new BigDecimal(
                                        FinanceService.s(
                                                db.one(
                                                        "SELECT debit FROM plugin_finance_line"
                                                            + " WHERE journal_id=? AND line_no=1",
                                                        j),
                                                "debit"))));
        assertNull(
                db.one("SELECT posting_seq FROM plugin_finance_posting WHERE journal_id=?", j)
                        .get("posting_seq"));
        for (UUID legacy : List.of(y, next)) {
            var row =
                    db.one(
                            "SELECT precision_profile,precision_review_required FROM"
                                    + " plugin_finance_year WHERE id=?",
                            legacy);
            assertEquals("LEGACY_V3", row.get("precision_profile"));
            assertEquals(true, row.get("precision_review_required"));
            assertEquals(
                    "LEGACY_PRECISION_REVIEW_REQUIRED",
                    assertThrows(
                                    FinanceException.class,
                                    () ->
                                            FinancePostgresTest.write(
                                                    "maker",
                                                    b,
                                                    legacy,
                                                    "journal",
                                                    null,
                                                    Map.of(
                                                            "date",
                                                            legacy.equals(y)
                                                                    ? "2026-01-10"
                                                                    : "2027-01-10",
                                                            "summary",
                                                            "must not write",
                                                            "lines",
                                                            List.of())))
                            .code);
        }
        assertEquals(
                "LEGACY_PRECISION_REVIEW_REQUIRED",
                assertThrows(
                                FinanceException.class,
                                () -> FinancePostgresTest.write("maker", b, y, "post", j, body))
                        .code);
        assertEquals(cacheBefore, db.list("SELECT * FROM plugin_finance_idempotency"));
        var legacy =
                (Map<String, Object>)
                        FinancePostgresTest.service.read("maker", b, y, "journals", j, null);
        assertEquals(
                "100.000001",
                ((List<Map<String, Object>>) legacy.get("lines")).getFirst().get("debit"));
        assertFalse(
                ((List<?>)
                                new FinanceAudit(db, FinancePostgresTest.service)
                                        .list("maker", b, y, 20, null)
                                        .get("items"))
                        .isEmpty());
        var fresh = FinancePostgresTest.fixture("SMALL");
        for (String invalid : List.of("1.001", "1.000", "1.00000000"))
            assertEquals(
                    "INVALID_CURRENCY_SCALE",
                    assertThrows(
                                    FinanceException.class,
                                    () ->
                                            FinancePostgresTest.write(
                                                    "maker",
                                                    fresh.book(),
                                                    fresh.year(),
                                                    "journal",
                                                    null,
                                                    Map.of(
                                                            "date",
                                                            "2026-01-10",
                                                            "summary",
                                                            "strict CNY",
                                                            "lines",
                                                            List.of(
                                                                    Map.of(
                                                                            "accountId",
                                                                            fresh.cash().toString(),
                                                                            "debit",
                                                                            invalid,
                                                                            "credit",
                                                                            "0"),
                                                                    Map.of(
                                                                            "accountId",
                                                                            fresh.capital()
                                                                                    .toString(),
                                                                            "debit",
                                                                            "0",
                                                                            "credit",
                                                                            invalid)))))
                            .code);
        assertEquals(
                "AMOUNT_OVERFLOW",
                assertThrows(
                                FinanceException.class,
                                () ->
                                        FinancePostgresTest.write(
                                                "maker",
                                                fresh.book(),
                                                fresh.year(),
                                                "journal",
                                                null,
                                                Map.of(
                                                        "date",
                                                        "2026-01-10",
                                                        "summary",
                                                        "overflow",
                                                        "lines",
                                                        List.of(
                                                                Map.of(
                                                                        "accountId",
                                                                        fresh.cash().toString(),
                                                                        "debit",
                                                                        "100000000000000000000.00",
                                                                        "credit",
                                                                        "0"),
                                                                Map.of(
                                                                        "accountId",
                                                                        fresh.capital().toString(),
                                                                        "debit",
                                                                        "0",
                                                                        "credit",
                                                                        "100000000000000000000.00")))))
                        .code);
        UUID freshPeriod =
                (UUID)
                        FinancePostgresTest.read("maker", fresh.book(), fresh.year(), "periods")
                                .getFirst()
                                .get("id");
        Object freshTenant =
                FinancePostgresTest.service
                        .originalScope("maker", fresh.book(), fresh.year(), "read")
                        .get("tenant_id");
        for (String initialState : List.of("POSTED", "APPROVED", "SUBMITTED"))
            assertThrows(
                    org.springframework.dao.DataAccessException.class,
                    () ->
                            db.update(
                                    "INSERT INTO"
                                        + " plugin_finance_journal(tenant_id,book_id,year_id,id,period_id,date,summary,status,created_by)"
                                        + " VALUES(?,?,?,?,?,'2026-01-10','direct state"
                                        + " bypass',?,?)",
                                    freshTenant,
                                    fresh.book(),
                                    fresh.year(),
                                    UUID.randomUUID(),
                                    freshPeriod,
                                    initialState,
                                    maker));
        UUID freshJournal = (UUID) FinancePostgresTest.draft(fresh).get("id");
        FinancePostgresTest.action("maker", fresh, freshJournal, "submit", 1);
        FinancePostgresTest.action("reviewer", fresh, freshJournal, "approve", 2);
        assertThrows(
                org.springframework.dao.DataAccessException.class,
                () ->
                        FinancePostgresTest.tx.execute(
                                status -> {
                                    db.update(
                                            "UPDATE plugin_finance_line SET debit=CASE WHEN"
                                                    + " line_no=1 THEN 1.001 ELSE 0 END,credit=CASE"
                                                    + " WHEN line_no=2 THEN 1.001 ELSE 0 END WHERE"
                                                    + " journal_id=?",
                                            freshJournal);
                                    long forgedSeq =
                                            ((Number)
                                                            db.one(
                                                                            "UPDATE"
                                                                                + " plugin_finance_year_guard"
                                                                                + " SET posting_seq=posting_seq+1"
                                                                                + " WHERE year_id=?"
                                                                                + " RETURNING"
                                                                                + " posting_seq",
                                                                            fresh.year())
                                                                    .get("posting_seq"))
                                                    .longValue();
                                    db.update(
                                            "INSERT INTO"
                                                + " plugin_finance_posting(tenant_id,book_id,year_id,journal_id,journal_version,digest,posted_by,posting_seq)"
                                                + " VALUES(?,?,?,?,3,'raw-subcent',?,?)",
                                            freshTenant,
                                            fresh.book(),
                                            fresh.year(),
                                            freshJournal,
                                            maker,
                                            forgedSeq);
                                    db.update(
                                            "UPDATE plugin_finance_journal SET"
                                                + " status='POSTED',number=1,version=4 WHERE id=?",
                                            freshJournal);
                                    return null;
                                }));
        assertEquals(
                0L,
                db.one(
                                "SELECT posting_seq FROM plugin_finance_year_guard WHERE year_id=?",
                                fresh.year())
                        .get("posting_seq"));
        assertThrows(
                org.springframework.dao.DataAccessException.class,
                () ->
                        db.update(
                                "INSERT INTO"
                                    + " plugin_finance_posting(tenant_id,book_id,year_id,journal_id,journal_version,digest,posted_by)"
                                    + " VALUES(?,?,?,?,3,'forged-null-seq',?)",
                                freshTenant,
                                fresh.book(),
                                fresh.year(),
                                freshJournal,
                                maker));
        FinancePostgresTest.action("maker", fresh, freshJournal, "post", 3);
        assertEquals(
                1L,
                db.one(
                                "SELECT posting_seq FROM plugin_finance_posting WHERE journal_id=?",
                                freshJournal)
                        .get("posting_seq"));
        var trial =
                (Map<String, Object>)
                        FinancePostgresTest.service.read(
                                "maker", fresh.book(), fresh.year(), "trial-balance", null, null);
        assertEquals(1L, ((Map<?, ?>) trial.get("watermark")).get("postingSeq"));
        assertEquals("100.01000000", trial.get("debit"));
        assertEquals(
                2,
                db.one(
                                "SELECT currency_minor_unit FROM plugin_finance_year WHERE id=?",
                                fresh.year())
                        .get("currency_minor_unit"));
    }
}
