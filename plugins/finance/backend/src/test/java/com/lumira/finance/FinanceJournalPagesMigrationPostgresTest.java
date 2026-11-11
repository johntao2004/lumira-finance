package com.lumira.finance;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;

/** Real pre-V3 six-place facts retained through V10 and the new V11 baseline. */
class FinanceJournalPagesMigrationPostgresTest {
    @BeforeAll
    static void setup() throws Exception {
        FinancePostgresTest.setup(false);
        FinanceJournalPagesPostgresTest.resetContext();
    }

    @AfterAll
    static void cleanup() {
        FinancePostgresTest.cleanup();
    }

    @Test
    void v10LegacyClosedFactsRemainExactAndGuardZeroIsOnlyNewReadBaseline() throws Exception {
        var db = new FinanceStore(FinancePostgresTest.jdbc);
        UUID t = UUID.randomUUID(),
                e = UUID.randomUUID(),
                book = UUID.randomUUID(),
                year = UUID.randomUUID(),
                period = UUID.randomUUID(),
                cash = UUID.randomUUID(),
                capital = UUID.randomUUID(),
                journal = UUID.randomUUID();
        UUID maker = FinancePostgresTest.users.get("maker"),
                reviewer = FinancePostgresTest.users.get("reviewer");
        db.update("INSERT INTO plugin_finance_tenant VALUES(?,'legacy page fixture',?)", t, maker);
        db.update(
                "INSERT INTO plugin_finance_entity VALUES(?,?,'legacy entity','legacy-pages')",
                t,
                e);
        db.update(
                "INSERT INTO"
                    + " plugin_finance_book(tenant_id,id,legal_entity_id,name,standard,base_currency)"
                    + " VALUES(?,?,?,'legacy book','CAS','CNY')",
                t,
                book,
                e);
        db.update(
                "INSERT INTO plugin_finance_member(tenant_id,book_id,user_id,username,role,enabled)"
                    + " VALUES(?,?,?,'maker','OWNER',true)",
                t,
                book,
                maker);
        db.update(
                "INSERT INTO plugin_finance_year(tenant_id,book_id,id,year) VALUES(?,?,?,2026)",
                t,
                book,
                year);
        db.update(
                "INSERT INTO plugin_finance_period(tenant_id,book_id,year_id,id,month)"
                    + " VALUES(?,?,?,?,1)",
                t,
                book,
                year,
                period);
        db.update(
                "INSERT INTO"
                    + " plugin_finance_account(tenant_id,book_id,year_id,id,code,name,normal_side,category)"
                    + " VALUES(?,?,?,?,'1001','legacy"
                    + " cash','DEBIT','ASSET'),(?,?,?,?,'4001','capital','CREDIT','EQUITY')",
                t,
                book,
                year,
                cash,
                t,
                book,
                year,
                capital);
        db.update(
                "INSERT INTO"
                    + " plugin_finance_journal(tenant_id,book_id,year_id,id,period_id,date,summary,created_by)"
                    + " VALUES(?,?,?,?,?,'2026-01-10','actual six-place legacy',?)",
                t,
                book,
                year,
                journal,
                period,
                maker);
        db.update(
                "INSERT INTO plugin_finance_line"
                    + " VALUES(?,?,?,?,1,?,100.000001,0,''),(?,?,?,?,2,?,0,100.000001,'')",
                t,
                book,
                year,
                journal,
                cash,
                t,
                book,
                year,
                journal,
                capital);
        db.update(
                "UPDATE plugin_finance_journal SET"
                    + " status='APPROVED',version=2,approved_by=?,approved_version=2 WHERE id=?",
                reviewer,
                journal);
        db.update(
                "INSERT INTO"
                    + " plugin_finance_posting(tenant_id,book_id,year_id,journal_id,journal_version,digest,posted_by)"
                    + " VALUES(?,?,?,?,2,?,?)",
                t,
                book,
                year,
                journal,
                FinanceService.hash("retained legacy posting"),
                maker);
        db.update(
                "UPDATE plugin_finance_journal SET status='POSTED',version=3,number=1 WHERE id=?",
                journal);
        db.update("UPDATE plugin_finance_year SET status='CLOSED' WHERE id=?", year);
        db.update("UPDATE plugin_finance_period SET status='CLOSED' WHERE id=?", period);
        for (String file :
                List.of(
                        "V3__v4_precision_and_year_guard.sql",
                        "V4__source_events_and_outbox.sql",
                        "V5__cash_scope_review.sql",
                        "V6__cash_movement_registration.sql",
                        "V7__cash_match_corrections.sql",
                        "V8__cash_classification_policies.sql",
                        "V9__cash_source_reviews.sql",
                        "V10__cash_allocations.sql"))
            try (var c = FinancePostgresTest.source.getConnection()) {
                c.createStatement().execute(Files.readString(Path.of("../migrations/" + file)));
            }
        var before = new ArrayList<Object>();
        for (String table :
                List.of("journal", "line", "posting", "year_guard", "audit", "idempotency"))
            before.add(
                    db.list(
                            "SELECT to_jsonb(t)::text v FROM plugin_finance_"
                                    + table
                                    + " t ORDER BY to_jsonb(t)::text"));
        try (var c = FinancePostgresTest.source.getConnection()) {
            c.setAutoCommit(false);
            try {
                c.createStatement()
                        .execute(
                                Files.readString(
                                        Path.of("../migrations/V11__journal_list_pages.sql")));
                c.commit();
            } catch (Exception failure) {
                c.rollback();
                throw failure;
            }
        }
        var after = new ArrayList<Object>();
        for (String table :
                List.of("journal", "line", "posting", "year_guard", "audit", "idempotency"))
            after.add(
                    db.list(
                            "SELECT to_jsonb(t)::text v FROM plugin_finance_"
                                    + table
                                    + " t ORDER BY to_jsonb(t)::text"));
        assertEquals(before, after);
        assertEquals(
                0L,
                db.one(
                                "SELECT generation FROM plugin_finance_journal_list_guard WHERE"
                                    + " book_id=? AND year_id=?",
                                book,
                                year)
                        .get("generation"));
        var pages = FinanceJournalPagesPostgresTest.create(db);
        var ledger =
                pages.page(
                        "maker",
                        book,
                        year,
                        "ledger",
                        FinanceJournalPagesPostgresTest.q("accountId", cash));
        var row = FinanceJournalPagesPostgresTest.items(ledger).getFirst();
        assertEquals("100.000001", row.get("debit"));
        assertEquals("LEGACY_6", row.get("amountFormatVersion"));
        assertNull(row.get("postingSeq"));
        assertEquals("CLOSED", ((Map<?, ?>) ledger.get("scope")).get("yearStatus"));
        var asOf = (Map<?, ?>) ledger.get("asOf");
        assertEquals("0", ((Map<?, ?>) asOf.get("year")).get("postingSeq"));
        assertEquals(true, ((Map<?, ?>) asOf.get("year")).get("legacyUnsequencedFacts"));
        var list = pages.page("maker", book, year, "journals", FinanceJournalPagesPostgresTest.q());
        assertEquals("0", ((Map<?, ?>) list.get("asOf")).get("generation"));
        assertEquals(
                journal.toString(),
                FinanceJournalPagesPostgresTest.items(list).getFirst().get("id"));
    }
}
