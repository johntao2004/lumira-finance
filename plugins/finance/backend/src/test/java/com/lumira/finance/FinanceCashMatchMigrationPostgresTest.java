package com.lumira.finance;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;

/** Actual nonempty V6-to-V7 upgrade in a dedicated random schema, preserving Raw/cache bytes. */
class FinanceCashMatchMigrationPostgresTest {
    @BeforeAll
    static void setup() throws Exception {
        FinanceCashScopesPostgresTest.setup();
        var db = FinanceCashScopesPostgresTest.db;
        try (var connection = FinancePostgresTest.source.getConnection()) {
            connection
                    .createStatement()
                    .execute(
                            Files.readString(
                                    Path.of("../migrations/V6__cash_movement_registration.sql")));
            // Test-only query adapter lets the current selector read V6 occupancy before V7 exists.
            // It writes no facts or claims and is dropped before the actual forward migration.
            connection
                    .createStatement()
                    .execute(
                            "CREATE VIEW plugin_finance_cash_leg_claim AS SELECT"
                                + " tenant_id,book_id,year_id,id, id AS"
                                + " raw_registration_id,'RAW_REGISTRATION'::text AS"
                                + " claim_kind,NULL::uuid AS"
                                + " correction_id,journal_id,line_no,'ACTIVE'::text AS state FROM"
                                + " plugin_finance_cash_movement_registration WHERE"
                                + " status='REGISTERED'");
        }
        FinanceCashMovementsPostgresTest.db = db;
        FinanceCashMovementsPostgresTest.movements =
                new FinanceCashMovements(
                        db, FinancePostgresTest.service, FinanceCashScopesPostgresTest.cash);
    }

    @AfterAll
    static void cleanup() {
        FinanceCashScopesPostgresTest.cleanup();
    }

    static FinanceStore dbForLegacy() {
        return FinanceCashScopesPostgresTest.db;
    }

    @Test
    void nonemptyBackfillIsTypedAndAtomicWithoutFabricatingVoidedHistory() throws Exception {
        var a = FinanceCashMovementsPostgresTest.fixture("CAS");
        var na = FinanceCashMovementsPostgresTest.body(a);
        var registered = FinanceCashMovementsPostgresTest.write("maker", a, null, na);
        var b = FinanceCashMovementsPostgresTest.fixture("SMALL");
        var nb = FinanceCashMovementsPostgresTest.body(b);
        var rb = FinanceCashMovementsPostgresTest.write("maker", b, null, nb);
        // Explicit V6 fixture transition: current production VOID requires the V9 ACTIVE usage
        // column and must not carry a missing-column fallback for this historical cutoff.
        FinancePostgresTest.tx.execute(
                status -> {
                    dbForLegacy()
                            .update(
                                    "UPDATE plugin_finance_cash_movement_registration SET"
                                        + " status='VOIDED',version=version+1,voided_by=?,voided_at=now(),void_reason='Historical"
                                        + " V6 fixture void' WHERE id=?",
                                    FinancePostgresTest.users.get("maker"),
                                    rb.get("id"));
                    dbForLegacy()
                            .update(
                                    "UPDATE plugin_finance_cash_registration_guard SET"
                                        + " registration_version=registration_version+1 WHERE"
                                        + " book_id=? AND year_id=?",
                                    b.f().book(),
                                    b.f().year());
                    return null;
                });
        var db = FinanceCashScopesPostgresTest.db;
        var facts =
                db.list(
                        "SELECT to_jsonb(r)::text AS value FROM"
                                + " plugin_finance_cash_movement_registration r ORDER BY id");
        var audit =
                db.list(
                        "SELECT to_jsonb(a)::text AS value FROM plugin_finance_audit a ORDER BY"
                                + " id");
        var cache =
                db.list(
                        "SELECT response,payload_hash FROM plugin_finance_idempotency ORDER BY"
                                + " actor_id,key");
        try (var connection = FinancePostgresTest.source.getConnection()) {
            connection.createStatement().execute("DROP VIEW plugin_finance_cash_leg_claim");
            connection.setAutoCommit(false);
            connection
                    .createStatement()
                    .execute(
                            Files.readString(
                                    Path.of("../migrations/V7__cash_match_corrections.sql")));
            connection.commit();
        }
        assertEquals(
                facts,
                db.list(
                        "SELECT to_jsonb(r)::text AS value FROM"
                                + " plugin_finance_cash_movement_registration r ORDER BY id"));
        assertEquals(
                audit,
                db.list(
                        "SELECT to_jsonb(a)::text AS value FROM plugin_finance_audit a ORDER BY"
                                + " id"));
        assertEquals(
                cache,
                db.list(
                        "SELECT response,payload_hash FROM plugin_finance_idempotency ORDER BY"
                                + " actor_id,key"));
        var claims = db.list("SELECT * FROM plugin_finance_cash_leg_claim");
        assertEquals(1, claims.size());
        assertEquals(registered.get("id"), claims.getFirst().get("raw_registration_id"));
        assertEquals("RAW_REGISTRATION", claims.getFirst().get("claim_kind"));
        assertEquals("MIGRATION_V7", claims.getFirst().get("origin"));
        assertEquals("ACTIVE", claims.getFirst().get("state"));
        assertEquals(
                0L,
                ((Number)
                                db.one(
                                                "SELECT source_generation FROM"
                                                        + " plugin_finance_cash_source_match_guard"
                                                        + " WHERE raw_registration_id=?",
                                                rb.get("id"))
                                        .get("source_generation"))
                        .longValue());
        assertEquals(
                "VOIDED",
                FinanceCashMovementsPostgresTest.write("maker", b, null, nb).get("status"));
    }
}
