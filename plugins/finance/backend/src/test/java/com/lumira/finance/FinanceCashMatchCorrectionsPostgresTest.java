package com.lumira.finance;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessException;

import java.util.*;
import java.util.concurrent.*;

class FinanceCashMatchCorrectionsPostgresTest {
    static FinanceCashMatchCorrections corrections;
    static FinanceStore db;

    @BeforeAll
    static void setup() throws Exception {
        FinanceCashMovementsPostgresTest.setup();
        db = FinanceCashMovementsPostgresTest.db;
        corrections =
                new FinanceCashMatchCorrections(
                        db,
                        FinancePostgresTest.service,
                        FinanceCashMovementsPostgresTest.movements,
                        FinanceCashScopesPostgresTest.cash);
    }

    @AfterAll
    static void cleanup() {
        FinanceCashMovementsPostgresTest.cleanup();
    }

    record Case(
            FinanceCashMovementsPostgresTest.Fixture original,
            Map<String, Object> raw,
            Map<String, Object> first,
            Map<String, Object> second,
            Map<String, Object> createBody) {}

    static Map<String, Object> target(FinancePostgresTest.Fixture f) {
        UUID journal = (UUID) FinancePostgresTest.draft(f).get("id");
        FinancePostgresTest.action("maker", f, journal, "submit", 1);
        FinancePostgresTest.action("reviewer", f, journal, "approve", 2);
        FinancePostgresTest.action("maker", f, journal, "post", 3);
        return db.one(
                FinanceCashMovements.LEGS + " AND j.id=? AND l.account_id=?",
                FinancePostgresTest.service
                        .originalScope("maker", f.book(), f.year(), "read")
                        .get("tenant_id"),
                f.book(),
                f.year(),
                journal,
                f.cash());
    }

    static Case fixture(String standard) {
        var original = FinanceCashMovementsPostgresTest.fixture(standard);
        var n = FinanceCashMovementsPostgresTest.body(original);
        var registered = FinanceCashMovementsPostgresTest.write("maker", original, null, n);
        var raw =
                FinanceCashMovementsPostgresTest.write(
                        "maker",
                        original,
                        (UUID) registered.get("id"),
                        FinanceCashMovementsPostgresTest.action(registered));
        return new Case(original, raw, target(original.f()), target(original.f()), n);
    }

    static long generation(Case c) {
        return ((Number)
                        corrections
                                .effective(
                                        "reviewer",
                                        c.original().f().book(),
                                        c.original().f().year(),
                                        (UUID) c.raw().get("id"))
                                .get("currentSourceMatchGeneration"))
                .longValue();
    }

    static Map<String, Object> proposal(Case c, Map<String, Object> leg) {
        var n = new LinkedHashMap<String, Object>();
        n.put("schemaVersion", FinanceCashMatchCorrections.SCHEMA);
        n.put("idempotencyKey", UUID.randomUUID().toString());
        n.put("originalRegistrationId", c.raw().get("id").toString());
        n.put("expectedRawVersion", c.raw().get("version"));
        n.put("rawContentDigest", c.raw().get("contentDigest"));
        n.put("expectedSourceMatchGeneration", generation(c));
        n.put("targetJournalId", leg.get("journalId").toString());
        n.put("targetLineNo", leg.get("lineNo"));
        n.put("expectedTargetJournalVersion", leg.get("journalVersion"));
        n.put("expectedTargetPostingSeq", leg.get("postingSeq"));
        n.put(
                "reason",
                "Correct only the wrong cash-leg selection; independent retained cash source"
                    + " remains unchanged");
        return n;
    }

    static Map<String, Object> action(Case c, Map<String, Object> r) {
        return new LinkedHashMap<>(
                Map.of(
                        "schemaVersion",
                        FinanceCashMatchCorrections.SCHEMA,
                        "idempotencyKey",
                        UUID.randomUUID().toString(),
                        "expectedVersion",
                        r.get("version"),
                        "targetContentVersion",
                        1,
                        "contentDigest",
                        r.get("contentDigest"),
                        "expectedSourceMatchGeneration",
                        generation(c),
                        "reason",
                        "Independent immutable cash match decision"));
    }

    static Map<String, Object> write(
            String actor, Case c, String op, UUID id, UUID wid, Map<String, Object> n) {
        return FinancePostgresTest.tx.execute(
                s ->
                        corrections.write(
                                actor,
                                c.original().f().book(),
                                c.original().f().year(),
                                op,
                                id,
                                wid,
                                n));
    }

    static Map<String, Object> submitted(Case c, Map<String, Object> leg) {
        var r = write("maker", c, "create", null, null, proposal(c, leg));
        return write("maker", c, "submit", (UUID) r.get("id"), null, action(c, r));
    }

    static Map<String, Object> approved(Case c, Map<String, Object> leg) {
        var r = submitted(c, leg);
        return write("reviewer", c, "approve", (UUID) r.get("id"), null, action(c, r));
    }

    static Map<String, Object> request(Case c, Map<String, Object> approved) {
        var n = action(c, approved);
        n.remove("targetContentVersion");
        n.put(
                "activeClaimId",
                ((Map<String, Object>) approved.get("effectiveClaim")).get("id").toString());
        return write("maker", c, "withdraw", (UUID) approved.get("id"), null, n);
    }

    static Map<String, Object> reviewWithdrawal(Case c, Map<String, Object> w) {
        var n = action(c, w);
        n.remove("targetContentVersion");
        return n;
    }

    static void code(String code, Runnable r) {
        assertEquals(code, assertThrows(FinanceException.class, r::run).code);
    }

    @Test
    void bothStandardsApprovalWithdrawalAndRepeatedCorrectionKeepRawAndFinancialEvidence() {
        for (String standard : List.of("CAS", "SMALL")) {
            var c = fixture(standard);
            String before = FinanceCashMovementsPostgresTest.facts(c.original());
            var rawBefore =
                    db.one(
                            "SELECT to_jsonb(r)::text AS value FROM"
                                + " plugin_finance_cash_movement_registration r WHERE id=?",
                            c.raw().get("id"));
            String cache =
                    (String)
                            db.one(
                                            "SELECT response FROM plugin_finance_idempotency WHERE"
                                                + " key=?",
                                            c.createBody().get("idempotencyKey"))
                                    .get("response");
            long start = generation(c);
            assertEquals(2, start);
            var sub = submitted(c, c.first());
            var approveBody = action(c, sub);
            UUID id = (UUID) sub.get("id");
            var a = write("reviewer", c, "approve", id, null, approveBody);
            assertEquals("APPROVED", a.get("status"));
            assertNotNull(a.get("effectiveClaim"));
            assertEquals(start + 1, generation(c));
            assertEquals(
                    "VOIDED",
                    FinanceCashMovementsPostgresTest.write(
                                    "maker", c.original(), null, c.createBody())
                            .get("status"));
            var legs =
                    (List<Map<String, Object>>)
                            FinanceCashMovementsPostgresTest.movements
                                    .eligible(
                                            "maker",
                                            c.original().f().book(),
                                            c.original().f().year(),
                                            (UUID) c.original().scope().get("id"),
                                            c.original().f().cash(),
                                            100,
                                            null)
                                    .get("items");
            var occupied =
                    legs.stream()
                            .filter(l -> l.get("journalId").equals(c.first().get("journalId")))
                            .findFirst()
                            .orElseThrow();
            assertEquals(c.raw().get("id"), occupied.get("activeRegistrationId"));
            assertEquals("MATCH_CORRECTION", occupied.get("activeClaimKind"));
            var w = request(c, a);
            assertEquals(start + 1, generation(c));
            assertNotNull(
                    corrections
                            .detail("maker", c.original().f().book(), c.original().f().year(), id)
                            .get("effectiveClaim"));
            var released =
                    write(
                            "reviewer",
                            c,
                            "withdraw-approve",
                            id,
                            (UUID) w.get("id"),
                            reviewWithdrawal(c, w));
            assertEquals("APPROVED", released.get("status"));
            assertEquals(start + 2, generation(c));
            var current = write("reviewer", c, "approve", id, null, approveBody);
            assertEquals("WITHDRAWN", current.get("status"));
            assertNull(current.get("effectiveClaim"));
            assertNotNull(current.get("approvedBy"));
            var second = approved(c, c.second());
            assertEquals(start + 3, generation(c));
            assertNotEquals(a.get("approvedClaimId"), second.get("approvedClaimId"));
            assertEquals(
                    rawBefore,
                    db.one(
                            "SELECT to_jsonb(r)::text AS value FROM"
                                + " plugin_finance_cash_movement_registration r WHERE id=?",
                            c.raw().get("id")));
            assertEquals(before, FinanceCashMovementsPostgresTest.facts(c.original()));
            assertEquals(
                    cache,
                    db.one(
                                    "SELECT response FROM plugin_finance_idempotency WHERE key=?",
                                    c.createBody().get("idempotencyKey"))
                            .get("response"));
        }
    }

    @Test
    void originalPreparerCurrentAuthorityAndIndependentReviewerWithStaleRejection() {
        var c = fixture("CAS");
        var sub = submitted(c, c.first());
        UUID id = (UUID) sub.get("id");
        code(
                "SELF_APPROVAL_FORBIDDEN",
                () -> write("maker", c, "approve", id, null, action(c, sub)));
        FinancePostgresTest.write(
                "maker",
                c.original().f().book(),
                null,
                "members",
                null,
                Map.of("username", "reviewer", "role", "OWNER", "enabled", true));
        code("SCOPE_DENIED", () -> write("reviewer", c, "cancel", id, null, action(c, sub)));
        FinancePostgresTest.write(
                "reviewer",
                c.original().f().book(),
                null,
                "members",
                null,
                Map.of("username", "maker", "role", "MAKER", "enabled", false));
        code("APPROVAL_STALE", () -> write("reviewer", c, "approve", id, null, action(c, sub)));
        assertEquals(
                "REJECTED", write("reviewer", c, "reject", id, null, action(c, sub)).get("status"));
    }

    @Test
    void approvalReplayRechecksPreparerWhileWithdrawalRejectionRetainsClaim() {
        var c = fixture("SMALL");
        var sub = submitted(c, c.first());
        var approveInput = action(c, sub);
        UUID id = (UUID) sub.get("id");
        var a = write("reviewer", c, "approve", id, null, approveInput);
        var w = request(c, a);
        long before = generation(c);
        FinancePostgresTest.write(
                "maker",
                c.original().f().book(),
                null,
                "members",
                null,
                Map.of("username", "reviewer", "role", "OWNER", "enabled", true));
        FinancePostgresTest.write(
                "reviewer",
                c.original().f().book(),
                null,
                "members",
                null,
                Map.of("username", "maker", "role", "MAKER", "enabled", false));
        code("APPROVAL_STALE", () -> write("reviewer", c, "approve", id, null, approveInput));
        code(
                "APPROVAL_STALE",
                () ->
                        write(
                                "reviewer",
                                c,
                                "withdraw-approve",
                                id,
                                (UUID) w.get("id"),
                                reviewWithdrawal(c, w)));
        assertEquals(
                "REJECTED",
                write(
                                "reviewer",
                                c,
                                "withdraw-reject",
                                id,
                                (UUID) w.get("id"),
                                reviewWithdrawal(c, w))
                        .get("status"));
        assertEquals(
                before,
                ((Number)
                                corrections
                                        .effective(
                                                "reviewer",
                                                c.original().f().book(),
                                                c.original().f().year(),
                                                (UUID) c.raw().get("id"))
                                        .get("currentSourceMatchGeneration"))
                        .longValue());
        assertNotNull(
                corrections
                        .detail("reviewer", c.original().f().book(), c.original().f().year(), id)
                        .get("effectiveClaim"));
    }

    @Test
    void pendingExclusionCancelAndClosedGateBeforeReplay() {
        var c = fixture("CAS");
        var n = proposal(c, c.first());
        var draft = write("maker", c, "create", null, null, n);
        UUID id = (UUID) draft.get("id");
        code(
                "CASH_MATCH_PENDING_EXISTS",
                () -> write("maker", c, "create", null, null, proposal(c, c.second())));
        assertEquals(
                "CANCELLED", write("maker", c, "cancel", id, null, action(c, draft)).get("status"));
        var sub = submitted(c, c.first());
        db.update(
                "UPDATE plugin_finance_account SET enabled=false WHERE book_id=? AND id=?",
                c.original().f().book(),
                c.original().f().cash());
        assertEquals(
                "REJECTED",
                write("reviewer", c, "reject", (UUID) sub.get("id"), null, action(c, sub))
                        .get("status"));
        db.update(
                "UPDATE plugin_finance_period SET status='CLOSED' WHERE book_id=? AND year_id=? AND"
                    + " month=1",
                c.original().f().book(),
                c.original().f().year());
        code("PERIOD_CLOSED", () -> write("maker", c, "create", null, null, n));
        assertEquals(
                "CANCELLED",
                corrections
                        .detail("maker", c.original().f().book(), c.original().f().year(), id)
                        .get("status"));
    }

    @Test
    void rawFactsAndTargetTypesCannotBeOverriddenAndSelectorCursorIsRawBound() {
        var c = fixture("CAS");
        var n = proposal(c, c.first());
        n.put("amount", "1.00");
        code("INVALID_INPUT", () -> write("maker", c, "create", null, null, n));
        var huge = proposal(c, c.first());
        huge.put("targetLineNo", 2147483648L);
        code("INVALID_INPUT", () -> write("maker", c, "create", null, null, huge));
        var old = proposal(c, c.original().leg());
        code("CASH_MATCH_TARGET_UNCHANGED", () -> write("maker", c, "create", null, null, old));
        var wrong = proposal(c, c.first());
        wrong.put("expectedTargetPostingSeq", 999);
        code("CASH_MATCH_TARGET_INVALID", () -> write("maker", c, "create", null, null, wrong));
        var f = c.original().f();
        var page =
                corrections.eligible(
                        "maker", f.book(), f.year(), (UUID) c.raw().get("id"), 1, null);
        String cursor = (String) page.get("nextCursor");
        assertNotNull(cursor);
        var extra = target(f);
        var original2 =
                new FinanceCashMovementsPostgresTest.Fixture(
                        f,
                        c.original().scope(),
                        FinanceCashScopesPostgresTest.original(f),
                        (UUID) extra.get("journalId"),
                        extra);
        var rawBody = FinanceCashMovementsPostgresTest.body(original2);
        rawBody.put("sourceRowReference", "account-A/page2/row2");
        var r = FinanceCashMovementsPostgresTest.write("maker", original2, null, rawBody);
        var voided =
                FinanceCashMovementsPostgresTest.write(
                        "maker",
                        original2,
                        (UUID) r.get("id"),
                        FinanceCashMovementsPostgresTest.action(r));
        code(
                "INVALID_INPUT",
                () ->
                        corrections.eligible(
                                "maker", f.book(), f.year(), (UUID) voided.get("id"), 1, cursor));
    }

    @Test
    void twoProposalsAndNewRawAgainstCorrectionEachCommitOnlyOneClaim() throws Exception {
        var c = fixture("CAS");
        var pool = Executors.newFixedThreadPool(2);
        try {
            var gate = new CountDownLatch(1);
            var attempts = new ArrayList<Future<Boolean>>();
            for (var target : List.of(c.first(), c.second()))
                attempts.add(
                        pool.submit(
                                () -> {
                                    gate.await();
                                    try {
                                        write(
                                                "maker",
                                                c,
                                                "create",
                                                null,
                                                null,
                                                proposal(c, target));
                                        return true;
                                    } catch (FinanceException e) {
                                        assertEquals("CASH_MATCH_PENDING_EXISTS", e.code);
                                        return false;
                                    }
                                }));
            gate.countDown();
            int winners = 0;
            for (var future : attempts) if (future.get(30, TimeUnit.SECONDS)) winners++;
            assertEquals(1, winners);
        } finally {
            pool.shutdownNow();
        }
        var pending =
                db.one(
                        "SELECT id FROM plugin_finance_cash_match_correction WHERE"
                            + " raw_registration_id=? AND status='DRAFT'",
                        c.raw().get("id"));
        var draft =
                corrections.detail(
                        "maker",
                        c.original().f().book(),
                        c.original().f().year(),
                        (UUID) pending.get("id"));
        write("maker", c, "cancel", (UUID) draft.get("id"), null, action(c, draft));
        var sub = submitted(c, c.first());
        var alternative =
                new FinanceCashMovementsPostgresTest.Fixture(
                        c.original().f(),
                        c.original().scope(),
                        c.original().original(),
                        (UUID) c.first().get("journalId"),
                        c.first());
        var newRaw = FinanceCashMovementsPostgresTest.body(alternative);
        newRaw.put("sourceRowReference", "other/page3/row9");
        var race = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var a =
                    race.submit(
                            () -> {
                                start.await();
                                try {
                                    write(
                                            "reviewer",
                                            c,
                                            "approve",
                                            (UUID) sub.get("id"),
                                            null,
                                            action(c, sub));
                                    return true;
                                } catch (FinanceException e) {
                                    assertEquals("CASH_LEG_ALREADY_REGISTERED", e.code);
                                    return false;
                                }
                            });
            var b =
                    race.submit(
                            () -> {
                                start.await();
                                try {
                                    FinanceCashMovementsPostgresTest.write(
                                            "maker", alternative, null, newRaw);
                                    return true;
                                } catch (FinanceException e) {
                                    assertEquals("CASH_LEG_ALREADY_REGISTERED", e.code);
                                    return false;
                                }
                            });
            start.countDown();
            assertNotEquals(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        } finally {
            race.shutdownNow();
        }
        assertEquals(
                1L,
                ((Number)
                                db.one(
                                                "SELECT count(*) n FROM"
                                                    + " plugin_finance_cash_leg_claim WHERE"
                                                    + " journal_id=? AND state='ACTIVE'",
                                                c.first().get("journalId"))
                                        .get("n"))
                        .longValue());
    }

    @Test
    void claimAndWithdrawalRollbackAreAtomicAndReleasedClaimsStayImmutable() {
        var c = fixture("CAS");
        var sub = submitted(c, c.first());
        long before = generation(c);
        var input = action(c, sub);
        assertThrows(
                IllegalStateException.class,
                () ->
                        FinancePostgresTest.tx.execute(
                                s -> {
                                    corrections.write(
                                            "reviewer",
                                            c.original().f().book(),
                                            c.original().f().year(),
                                            "approve",
                                            (UUID) sub.get("id"),
                                            null,
                                            input);
                                    throw new IllegalStateException(
                                            "injected after claim insertion");
                                }));
        assertEquals(before, generation(c));
        assertNull(
                corrections
                        .detail(
                                "maker",
                                c.original().f().book(),
                                c.original().f().year(),
                                (UUID) sub.get("id"))
                        .get("effectiveClaim"));
        assertTrue(
                db.list(
                                "SELECT key FROM plugin_finance_idempotency WHERE key=?",
                                input.get("idempotencyKey"))
                        .isEmpty());
        var a = write("reviewer", c, "approve", (UUID) sub.get("id"), null, input);
        var w = request(c, a);
        var review = reviewWithdrawal(c, w);
        long activeGeneration = generation(c);
        assertThrows(
                IllegalStateException.class,
                () ->
                        FinancePostgresTest.tx.execute(
                                s -> {
                                    corrections.write(
                                            "reviewer",
                                            c.original().f().book(),
                                            c.original().f().year(),
                                            "withdraw-approve",
                                            (UUID) a.get("id"),
                                            (UUID) w.get("id"),
                                            review);
                                    throw new IllegalStateException("injected after claim release");
                                }));
        assertEquals(activeGeneration, generation(c));
        assertNotNull(
                corrections
                        .detail(
                                "maker",
                                c.original().f().book(),
                                c.original().f().year(),
                                (UUID) a.get("id"))
                        .get("effectiveClaim"));
        write("reviewer", c, "withdraw-approve", (UUID) a.get("id"), (UUID) w.get("id"), review);
        UUID claim = (UUID) a.get("approvedClaimId");
        for (String sql :
                List.of(
                        "DELETE FROM plugin_finance_cash_leg_claim WHERE id=?",
                        "UPDATE plugin_finance_cash_leg_claim SET state='ACTIVE' WHERE id=?",
                        "UPDATE plugin_finance_cash_leg_claim SET"
                            + " claim_kind='RAW_REGISTRATION',correction_id=NULL WHERE id=?"))
            assertThrows(
                    DataAccessException.class,
                    () -> FinancePostgresTest.tx.execute(s -> db.update(sql, claim)));
        assertThrows(
                DataAccessException.class,
                () ->
                        FinancePostgresTest.tx.execute(
                                s ->
                                        db.update(
                                                "INSERT INTO plugin_finance_cash_claim_usage SELECT"
                                                    + " tenant_id,book_id,year_id,id,raw_registration_id,claim_generation,claim_content_digest"
                                                    + " FROM plugin_finance_cash_leg_claim WHERE"
                                                    + " id=?",
                                                claim)));
    }

    @Test
    void oldBinaryStyleDirectRawInsertCannotBypassUnifiedCorrectionOccupancy() {
        var c = fixture("CAS");
        var a = approved(c, c.first());
        var raw =
                FinanceCashMovementsPostgresTest.movements.row(
                        FinancePostgresTest.service.originalScope(
                                "maker", c.original().f().book(), c.original().f().year(), "read"),
                        c.original().f().year(),
                        (UUID) c.raw().get("id"));
        var content = FinancePostgresTest.service.decode((String) raw.get("content_text"));
        String key = UUID.randomUUID().toString(), reference = "different-source/page9/row1";
        content.put("sourceMovementKey", key);
        content.put("sourceRowReference", reference);
        content.put("journalId", c.first().get("journalId").toString());
        content.put("lineNo", c.first().get("lineNo"));
        content.put("expectedJournalVersion", c.first().get("journalVersion"));
        content.put("expectedPostingSeq", c.first().get("postingSeq"));
        content.put("matchedLegSnapshot", a.get("targetLegSnapshot"));
        var replacement = new LinkedHashMap<String, Object>();
        replacement.put("id", UUID.randomUUID().toString());
        replacement.put("source_movement_key", key);
        replacement.put("source_row_reference", reference);
        replacement.put("journal_id", c.first().get("journalId").toString());
        replacement.put("line_no", c.first().get("lineNo"));
        replacement.put("matched_journal_version", c.first().get("journalVersion"));
        replacement.put("posting_seq", c.first().get("postingSeq"));
        replacement.put("posting_digest", c.first().get("postingDigest"));
        replacement.put("status", "REGISTERED");
        replacement.put("version", 1);
        replacement.put("voided_by", null);
        replacement.put("voided_at", null);
        replacement.put("void_reason", null);
        replacement.put("content", content);
        replacement.put("content_digest", corrections.digest(content));
        replacement.put("business_digest", corrections.digest(content));
        long before = generation(c);
        var allBefore =
                db.list(
                        "SELECT to_jsonb(r)::text v FROM plugin_finance_cash_movement_registration"
                            + " r WHERE book_id=? ORDER BY id",
                        c.original().f().book());
        var error =
                assertThrows(
                        DataAccessException.class,
                        () ->
                                FinancePostgresTest.tx.execute(
                                        s ->
                                                db.update(
                                                        "INSERT INTO"
                                                            + " plugin_finance_cash_movement_registration"
                                                            + " SELECT"
                                                            + " (jsonb_populate_record(NULL::plugin_finance_cash_movement_registration,to_jsonb(r)||?::jsonb)).*"
                                                            + " FROM"
                                                            + " plugin_finance_cash_movement_registration"
                                                            + " r WHERE id=?",
                                                        FinancePostgresTest.service.encode(
                                                                replacement),
                                                        c.raw().get("id"))));
        assertTrue(
                error.getMostSpecificCause()
                        .getMessage()
                        .contains("finance_cash_claim_one_active_leg"),
                error.getMessage());
        assertEquals(before, generation(c));
        assertEquals(
                allBefore,
                db.list(
                        "SELECT to_jsonb(r)::text v FROM plugin_finance_cash_movement_registration"
                            + " r WHERE book_id=? ORDER BY id",
                        c.original().f().book()));
        assertEquals(
                a.get("approvedClaimId"),
                ((Map<String, Object>)
                                corrections
                                        .effective(
                                                "maker",
                                                c.original().f().book(),
                                                c.original().f().year(),
                                                (UUID) c.raw().get("id"))
                                        .get("effectiveClaim"))
                        .get("id"));
    }
}
