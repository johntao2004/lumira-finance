package com.lumira.finance;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;

/** Actual typed consumers, deferred final invariants, release history and shared-lock races. */
class FinanceCashSourceReviewsPostgresTest {
    static FinanceCashSourceReviews reviews;
    static FinanceStore db;
    static Map<String, UUID> previousUsers;

    @BeforeAll
    static void setup() throws Exception {
        previousUsers = FinancePostgresTest.users;
        var users = new HashMap<>(previousUsers);
        users.put("handoff", UUID.randomUUID());
        FinancePostgresTest.users = users;
        FinanceCashMovementsPostgresTest.setup();
        db = FinanceCashMovementsPostgresTest.db;
        FinanceCashMatchCorrectionsPostgresTest.db = db;
        FinanceCashMatchCorrectionsPostgresTest.corrections =
                new FinanceCashMatchCorrections(
                        db,
                        FinancePostgresTest.service,
                        FinanceCashMovementsPostgresTest.movements,
                        FinanceCashScopesPostgresTest.cash);
        reviews =
                new FinanceCashSourceReviews(
                        db,
                        FinancePostgresTest.service,
                        FinanceCashMovementsPostgresTest.movements,
                        FinanceCashMatchCorrectionsPostgresTest.corrections,
                        FinanceCashScopesPostgresTest.cash);
    }

    @AfterAll
    static void cleanup() {
        FinanceCashMovementsPostgresTest.cleanup();
        FinancePostgresTest.users = previousUsers;
    }

    @AfterEach
    void restoreTestOnlyTriggerToggles() {
        // Every negative transaction is expected to roll back its DDL. Keep the harness isolated
        // even if a missing invariant makes an assertion fail after an unexpected commit.
        trigger("plugin_finance_cash_source_review", "finance_cash_source_review_guard", true);
        trigger("plugin_finance_cash_source_review", "finance_cash_source_review_event", true);
        trigger("plugin_finance_cash_claim_usage", "finance_cash_usage_guard", true);
        trigger(
                "plugin_finance_cash_source_review_release",
                "finance_cash_source_review_release_guard",
                true);
    }

    record Case(
            FinanceCashMovementsPostgresTest.Fixture original,
            Map<String, Object> raw,
            Map<String, Object> claim,
            UUID evidence,
            FinanceCashMatchCorrectionsPostgresTest.Case corrected,
            Map<String, Object> correction) {}

    static Case fixture(boolean corrected, String standard) {
        FinanceCashMovementsPostgresTest.Fixture original;
        Map<String, Object> raw;
        FinanceCashMatchCorrectionsPostgresTest.Case correctionCase = null;
        Map<String, Object> correction = null;
        if (corrected) {
            correctionCase = FinanceCashMatchCorrectionsPostgresTest.fixture(standard);
            original = correctionCase.original();
            raw = correctionCase.raw();
            correction =
                    FinanceCashMatchCorrectionsPostgresTest.approved(
                            correctionCase, correctionCase.first());
        } else {
            original = FinanceCashMovementsPostgresTest.fixture(standard);
            raw =
                    FinanceCashMovementsPostgresTest.write(
                            "maker",
                            original,
                            null,
                            FinanceCashMovementsPostgresTest.body(original));
        }
        var f = original.f();
        var page =
                reviews.eligible("maker", f.book(), f.year(), (UUID) raw.get("id"), null, 20, null);
        var claim = ((List<Map<String, Object>>) page.get("items")).getFirst();
        return new Case(
                original,
                raw,
                claim,
                FinanceCashPoliciesPostgresTest.original(f, "human-business-purpose"),
                correctionCase,
                correction);
    }

    static Map<String, Object> body(Case c) {
        var n = new LinkedHashMap<String, Object>();
        n.put("schemaVersion", FinanceCashSourceReviews.SCHEMA);
        n.put("idempotencyKey", UUID.randomUUID().toString());
        n.put("expectedClaim", reviews.expectedFrom(c.claim()));
        n.put(
                "businessPurposeStatement",
                "Ordinary evidenced actual cash purpose; no policy or accounting row inferred");
        n.put(
                "factReviewStatement",
                "Retained source row, qualification evidence and actual full posted cash leg"
                        + " inspected by human");
        var checklist = new LinkedHashMap<String, Object>();
        FinanceCashSourceReviews.CHECKLIST.forEach(k -> checklist.put(k, true));
        n.put("checklist", checklist);
        n.put(
                "evidence",
                List.of(
                        Map.of(
                                "originalId",
                                c.evidence().toString(),
                                "purpose",
                                "BUSINESS_PURPOSE_SUPPORT",
                                "description",
                                "Retained independent business-purpose evidence")));
        n.put("conclusion", "SUPPORTED_FOR_PREPARATION");
        n.put("reason", "Explicit human review, not bank authenticity or report approval");
        return n;
    }

    static Map<String, Object> action(Map<String, Object> r) {
        return new LinkedHashMap<>(
                Map.of(
                        "schemaVersion",
                        FinanceCashSourceReviews.SCHEMA,
                        "idempotencyKey",
                        UUID.randomUUID().toString(),
                        "expectedVersion",
                        r.get("version"),
                        "contentDigest",
                        r.get("contentDigest"),
                        "expectedClaim",
                        reviews.expectedFrom((Map<String, Object>) r.get("claimSnapshot")),
                        "reason",
                        "Independent human review decision"));
    }

    static Map<String, Object> releaseAction(Map<String, Object> r) {
        return new LinkedHashMap<>(
                Map.of(
                        "schemaVersion",
                        FinanceCashSourceReviews.SCHEMA,
                        "idempotencyKey",
                        UUID.randomUUID().toString(),
                        "expectedVersion",
                        r.get("version"),
                        "contentDigest",
                        r.get("contentDigest"),
                        "reason",
                        "Independent typed usage release decision"));
    }

    static Map<String, Object> write(
            String actor, Case c, String op, UUID id, UUID release, Map<String, Object> n) {
        return FinancePostgresTest.tx.execute(
                s ->
                        reviews.write(
                                actor,
                                c.original().f().book(),
                                c.original().f().year(),
                                op,
                                id,
                                release,
                                n));
    }

    static Map<String, Object> submitted(Case c, String maker) {
        var d = write(maker, c, "create", null, null, body(c));
        return write(maker, c, "submit", (UUID) d.get("id"), null, action(d));
    }

    static Map<String, Object> approved(Case c, String maker) {
        var s = submitted(c, maker);
        return write("reviewer", c, "approve", (UUID) s.get("id"), null, action(s));
    }

    static Map<String, Object> request(Case c, Map<String, Object> a, String actor) {
        var n = action(a);
        n.put("activeUsageId", a.get("activeUsageId").toString());
        return write(actor, c, "release", (UUID) a.get("id"), null, n);
    }

    static void code(String code, Runnable r) {
        assertEquals(code, assertThrows(FinanceException.class, r::run).code);
    }

    static String facts(Case c) {
        var f = c.original().f();
        var b = FinancePostgresTest.service.originalScope("reviewer", f.book(), f.year(), "read");
        var list = new ArrayList<Object>();
        for (String table :
                List.of(
                        "plugin_finance_journal",
                        "plugin_finance_line",
                        "plugin_finance_posting",
                        "plugin_finance_opening",
                        "plugin_finance_year_guard",
                        "plugin_finance_original",
                        "plugin_finance_cash_scope_revision",
                        "plugin_finance_cash_scope_guard",
                        "plugin_finance_cash_movement_registration",
                        "plugin_finance_cash_leg_claim",
                        "plugin_finance_cash_match_guard",
                        "plugin_finance_cash_source_match_guard",
                        "plugin_finance_cash_policy_guard",
                        "plugin_finance_cash_policy_revision"))
            list.add(
                    db.list(
                            "SELECT to_jsonb(t)::text v FROM "
                                    + table
                                    + " t WHERE book_id=? ORDER BY to_jsonb(t)::text",
                            f.book()));
        return FinancePostgresTest.service.encode(list);
    }

    @Test
    void rootAndCorrectionHumanReviewReleaseRereviewPreserveCashFactsAndOnlyActiveUsageBlocks() {
        for (boolean corrected : List.of(false, true)) {
            var c = fixture(corrected, corrected ? "SMALL" : "CAS");
            String before = facts(c);
            var sub = submitted(c, "maker");
            var approve = action(sub);
            UUID id = (UUID) sub.get("id");
            var a = write("reviewer", c, "approve", id, null, approve);
            assertEquals("APPROVED", a.get("status"));
            assertTrue((Boolean) a.get("currentEffective"));
            assertNotNull(a.get("activeUsageId"));
            assertEquals(false, a.get("bankAuthenticityVerified"));
            assertEquals(
                    0L,
                    ((Number)
                                    db.one(
                                                    "SELECT downstream_use_count FROM"
                                                        + " plugin_finance_cash_movement_registration"
                                                        + " WHERE id=?",
                                                    c.raw().get("id"))
                                            .get("downstream_use_count"))
                            .longValue());
            if (!corrected) {
                code(
                        "CASH_REGISTRATION_IN_USE",
                        () ->
                                FinanceCashMovementsPostgresTest.write(
                                        "maker",
                                        c.original(),
                                        (UUID) c.raw().get("id"),
                                        FinanceCashMovementsPostgresTest.action(c.raw())));
                var rawError =
                        assertThrows(
                                RuntimeException.class,
                                () ->
                                        FinancePostgresTest.tx.execute(
                                                s ->
                                                        db.update(
                                                                "UPDATE"
                                                                    + " plugin_finance_cash_movement_registration"
                                                                    + " SET status='VOIDED',version=version+1,voided_by=?,voided_at=now(),void_reason='Old"
                                                                    + " binary direct VOID must not"
                                                                    + " bypass usage' WHERE id=?",
                                                                FinancePostgresTest.users.get(
                                                                        "maker"),
                                                                c.raw().get("id"))));
                assertTrue(rootMessage(rawError).contains("CASH_REGISTRATION_IN_USE"));
            } else
                code(
                        "CASH_REGISTRATION_IN_USE",
                        () ->
                                FinanceCashMatchCorrectionsPostgresTest.request(
                                        c.corrected(), c.correction()));
            var req = request(c, a, "maker");
            var pending =
                    reviews.detail("maker", c.original().f().book(), c.original().f().year(), id);
            assertEquals(true, pending.get("currentEffective"));
            assertNotNull(pending.get("activeUsageId"));
            assertNotNull(pending.get("pendingRelease"));
            write("reviewer", c, "release-approve", id, (UUID) req.get("id"), releaseAction(req));
            var released = write("reviewer", c, "approve", id, null, approve);
            assertEquals("RELEASED", released.get("status"));
            assertEquals(false, released.get("currentEffective"));
            assertNull(released.get("activeUsageId"));
            assertNotNull(
                    db.one(
                                    "SELECT approved_usage_id FROM"
                                            + " plugin_finance_cash_source_review WHERE id=?",
                                    id)
                            .get("approved_usage_id"));
            assertEquals(before, facts(c));
            var again = approved(c, "maker");
            assertNotEquals(a.get("activeUsageId"), again.get("activeUsageId"));
            var r2 = request(c, again, "maker");
            write(
                    "reviewer",
                    c,
                    "release-approve",
                    (UUID) again.get("id"),
                    (UUID) r2.get("id"),
                    releaseAction(r2));
            assertEquals(before, facts(c));
            if (corrected) {
                var w =
                        FinanceCashMatchCorrectionsPostgresTest.request(
                                c.corrected(), c.correction());
                FinanceCashMatchCorrectionsPostgresTest.write(
                        "reviewer",
                        c.corrected(),
                        "withdraw-approve",
                        (UUID) c.correction().get("id"),
                        (UUID) w.get("id"),
                        FinanceCashMatchCorrectionsPostgresTest.reviewWithdrawal(c.corrected(), w));
            } else
                assertEquals(
                        "VOIDED",
                        FinanceCashMovementsPostgresTest.write(
                                        "maker",
                                        c.original(),
                                        (UUID) c.raw().get("id"),
                                        FinanceCashMovementsPostgresTest.action(c.raw()))
                                .get("status"));
        }
    }

    static String rootMessage(Throwable e) {
        while (e.getCause() != null) e = e.getCause();
        return String.valueOf(e.getMessage());
    }

    static void trigger(String table, String trigger, boolean enabled) {
        db.update(
                "ALTER TABLE "
                        + table
                        + " "
                        + (enabled ? "ENABLE" : "DISABLE")
                        + " TRIGGER "
                        + trigger);
    }

    @Test
    void trueConsumerForeignKeysAndDeferredBothSidesRejectPartialOrFakeCommittedEffects() {
        var c = fixture(false, "CAS");
        var s = submitted(c, "maker");
        UUID id = (UUID) s.get("id");
        var missing =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                FinancePostgresTest.tx.execute(
                                        tx -> {
                                            trigger(
                                                    "plugin_finance_cash_source_review",
                                                    "finance_cash_source_review_event",
                                                    false);
                                            db.update(
                                                    "UPDATE plugin_finance_cash_source_review SET"
                                                        + " status='APPROVED',version=version+1,approved_by=?,approved_at=now(),approved_usage_id=?,decision_by=?,decision_at=now(),decision_reason='Simulated"
                                                        + " lost effect insertion' WHERE id=?",
                                                    FinancePostgresTest.users.get("reviewer"),
                                                    UUID.randomUUID(),
                                                    FinancePostgresTest.users.get("reviewer"),
                                                    id);
                                            // Intentionally reach COMMIT; deferred invariant must
                                            // fail and roll back this DDL too.
                                            return null;
                                        }));
        assertTrue(
                rootMessage(missing).contains("usage") || rootMessage(missing).contains("USAGE"));
        assertEquals(
                "SUBMITTED",
                reviews.detail("maker", c.original().f().book(), c.original().f().year(), id)
                        .get("status"));
        var fake =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                FinancePostgresTest.tx.execute(
                                        tx -> {
                                            trigger(
                                                    "plugin_finance_cash_claim_usage",
                                                    "finance_cash_usage_guard",
                                                    false);
                                            db.update(
                                                    "INSERT INTO"
                                                        + " plugin_finance_cash_claim_usage(tenant_id,book_id,year_id,claim_id,raw_registration_id,claim_generation,claim_content_digest,id,usage_kind,source_review_id,consumer_content_digest,state,approved_by,approved_at)"
                                                        + " SELECT"
                                                        + " tenant_id,book_id,year_id,claim_id,raw_registration_id,claim_generation,claim_content_digest,?,'SOURCE_REVIEW',?,content_digest,'ACTIVE',?,now()"
                                                        + " FROM plugin_finance_cash_source_review"
                                                        + " WHERE id=?",
                                                    UUID.randomUUID(),
                                                    UUID.randomUUID(),
                                                    FinancePostgresTest.users.get("reviewer"),
                                                    id);
                                            db.update(
                                                    "SET CONSTRAINTS"
                                                            + " finance_cash_usage_real_consumer"
                                                            + " IMMEDIATE");
                                            return null;
                                        }));
        assertTrue(rootMessage(fake).contains("finance_cash_usage_real_consumer"));
        var a = write("reviewer", c, "approve", id, null, action(s));
        var badState =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                FinancePostgresTest.tx.execute(
                                        tx -> {
                                            trigger(
                                                    "plugin_finance_cash_claim_usage",
                                                    "finance_cash_usage_guard",
                                                    false);
                                            db.update(
                                                    "UPDATE plugin_finance_cash_claim_usage SET"
                                                            + " state='RELEASED' WHERE id=?",
                                                    a.get("activeUsageId"));
                                            // Intentionally reach COMMIT; deferred invariant must
                                            // fail and roll back this DDL too.
                                            return null;
                                        }));
        assertTrue(rootMessage(badState).contains("INVARIANT"));
        assertTrue(
                (Boolean)
                        reviews.detail(
                                        "maker",
                                        c.original().f().book(),
                                        c.original().f().year(),
                                        id)
                                .get("currentEffective"));
        var wrongRelease =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                FinancePostgresTest.tx.execute(
                                        tx -> {
                                            trigger(
                                                    "plugin_finance_cash_source_review",
                                                    "finance_cash_source_review_guard",
                                                    false);
                                            trigger(
                                                    "plugin_finance_cash_source_review",
                                                    "finance_cash_source_review_event",
                                                    false);
                                            db.update(
                                                    "UPDATE plugin_finance_cash_source_review SET"
                                                        + " status='RELEASED',version=version+1,release_decision_id=?,released_by=?,released_at=now()"
                                                        + " WHERE id=?",
                                                    UUID.randomUUID(),
                                                    FinancePostgresTest.users.get("reviewer"),
                                                    id);
                                            db.update(
                                                    "SET CONSTRAINTS"
                                                        + " finance_cash_review_release_decision"
                                                        + " IMMEDIATE");
                                            return null;
                                        }));
        assertTrue(rootMessage(wrongRelease).contains("finance_cash_review_release_decision"));
        var realRequest = request(c, a, "maker");
        write(
                "reviewer",
                c,
                "release-approve",
                id,
                (UUID) realRequest.get("id"),
                releaseAction(realRequest));
        var another = approved(c, "maker");
        var unrelated =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                FinancePostgresTest.tx.execute(
                                        tx -> {
                                            trigger(
                                                    "plugin_finance_cash_source_review",
                                                    "finance_cash_source_review_guard",
                                                    false);
                                            trigger(
                                                    "plugin_finance_cash_source_review",
                                                    "finance_cash_source_review_event",
                                                    false);
                                            db.update(
                                                    "UPDATE plugin_finance_cash_source_review SET"
                                                        + " status='RELEASED',version=version+1,release_decision_id=?,released_by=?,released_at=now()"
                                                        + " WHERE id=?",
                                                    realRequest.get("id"),
                                                    FinancePostgresTest.users.get("reviewer"),
                                                    another.get("id"));
                                            db.update(
                                                    "SET CONSTRAINTS"
                                                        + " finance_cash_review_release_decision"
                                                        + " IMMEDIATE");
                                            return null;
                                        }));
        assertTrue(rootMessage(unrelated).contains("finance_cash_review_release_decision"));
    }

    @Test
    void activeUsageCannotBeCommittedAgainstRealDraftEvenWhenForwardReverseForeignKeysAloneMatch() {
        var c = fixture(false, "CAS");
        var d = write("maker", c, "create", null, null, body(c));
        UUID id = (UUID) d.get("id"), usage = UUID.randomUUID();
        var failure =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                FinancePostgresTest.tx.execute(
                                        tx -> {
                                            trigger(
                                                    "plugin_finance_cash_source_review",
                                                    "finance_cash_source_review_guard",
                                                    false);
                                            trigger(
                                                    "plugin_finance_cash_claim_usage",
                                                    "finance_cash_usage_guard",
                                                    false);
                                            db.update(
                                                    "UPDATE plugin_finance_cash_source_review SET"
                                                            + " approved_usage_id=? WHERE id=?",
                                                    usage,
                                                    id);
                                            db.update(
                                                    "INSERT INTO"
                                                        + " plugin_finance_cash_claim_usage(tenant_id,book_id,year_id,claim_id,raw_registration_id,claim_generation,claim_content_digest,id,usage_kind,source_review_id,consumer_content_digest,state,approved_by,approved_at)"
                                                        + " SELECT"
                                                        + " tenant_id,book_id,year_id,claim_id,raw_registration_id,claim_generation,claim_content_digest,?,'SOURCE_REVIEW',id,content_digest,'ACTIVE',?,now()"
                                                        + " FROM plugin_finance_cash_source_review"
                                                        + " WHERE id=?",
                                                    usage,
                                                    FinancePostgresTest.users.get("reviewer"),
                                                    id);
                                            db.update(
                                                    "SET CONSTRAINTS"
                                                        + " finance_cash_usage_real_consumer,finance_cash_review_real_usage"
                                                        + " IMMEDIATE");
                                            // Intentionally reach COMMIT; deferred invariant must
                                            // fail and roll back this DDL too.
                                            // Intentionally reach COMMIT; deferred invariant must
                                            // fail and roll back this DDL too.
                                            return null;
                                        }));
        assertTrue(rootMessage(failure).contains("NON_EFFECTIVE_HAS_USAGE"));
        assertEquals(
                "DRAFT",
                reviews.detail("maker", c.original().f().book(), c.original().f().year(), id)
                        .get("status"));
    }

    @Test
    void reviewActivationVersusRawVoidAndMatchWithdrawalSerializeOneValidOutcome()
            throws Exception {
        for (boolean corrected : List.of(false, true)) {
            var c = fixture(corrected, "CAS");
            var s = submitted(c, "maker");
            var pool = Executors.newFixedThreadPool(2);
            var gate = new CountDownLatch(1);
            try {
                var a =
                        pool.submit(
                                () -> {
                                    gate.await();
                                    try {
                                        write(
                                                "reviewer",
                                                c,
                                                "approve",
                                                (UUID) s.get("id"),
                                                null,
                                                action(s));
                                        return true;
                                    } catch (FinanceException e) {
                                        assertTrue(
                                                Set.of(
                                                                "CASH_REVIEW_CLAIM_STALE",
                                                                "CASH_REVIEW_PENDING_WITHDRAWAL")
                                                        .contains(e.code));
                                        return false;
                                    }
                                });
                var b =
                        pool.submit(
                                () -> {
                                    gate.await();
                                    try {
                                        if (corrected)
                                            FinanceCashMatchCorrectionsPostgresTest.request(
                                                    c.corrected(), c.correction());
                                        else
                                            FinanceCashMovementsPostgresTest.write(
                                                    "maker",
                                                    c.original(),
                                                    (UUID) c.raw().get("id"),
                                                    FinanceCashMovementsPostgresTest.action(
                                                            c.raw()));
                                        return true;
                                    } catch (FinanceException e) {
                                        assertEquals("CASH_REGISTRATION_IN_USE", e.code);
                                        return false;
                                    }
                                });
                gate.countDown();
                assertNotEquals(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
            } finally {
                pool.shutdownNow();
            }
            var current =
                    reviews.detail(
                            "maker",
                            c.original().f().book(),
                            c.original().f().year(),
                            (UUID) s.get("id"));
            if ("SUBMITTED".equals(current.get("status")))
                assertEquals(
                        "REJECTED",
                        write("reviewer", c, "reject", (UUID) s.get("id"), null, action(current))
                                .get("status"));
        }
    }

    @Test
    void historicalRawCreatorMayLeaveAndNewOwnerCanReleaseAfterReviewMakerDeparture() {
        var c = fixture(false, "SMALL");
        var f = c.original().f();
        FinancePostgresTest.write(
                "maker",
                f.book(),
                null,
                "members",
                null,
                Map.of("username", "outsider", "role", "MAKER", "enabled", true));
        FinancePostgresTest.write(
                "maker",
                f.book(),
                null,
                "members",
                null,
                Map.of("username", "handoff", "role", "OWNER", "enabled", true));
        FinancePostgresTest.write(
                "maker",
                f.book(),
                null,
                "members",
                null,
                Map.of("username", "reviewer", "role", "OWNER", "enabled", true));
        var s = submitted(c, "outsider");
        code(
                "SELF_APPROVAL_FORBIDDEN",
                () -> write("maker", c, "approve", (UUID) s.get("id"), null, action(s)));
        FinancePostgresTest.write(
                "handoff",
                f.book(),
                null,
                "members",
                null,
                Map.of("username", "maker", "role", "MAKER", "enabled", false));
        var approval = action(s);
        var a = write("reviewer", c, "approve", (UUID) s.get("id"), null, approval);
        FinancePostgresTest.write(
                "handoff",
                f.book(),
                null,
                "members",
                null,
                Map.of("username", "outsider", "role", "MAKER", "enabled", false));
        code(
                "APPROVAL_STALE",
                () -> write("reviewer", c, "approve", (UUID) s.get("id"), null, approval));
        var release = request(c, a, "handoff");
        write(
                "reviewer",
                c,
                "release-approve",
                (UUID) a.get("id"),
                (UUID) release.get("id"),
                releaseAction(release));
        assertEquals(
                "RELEASED",
                reviews.detail("handoff", f.book(), f.year(), (UUID) a.get("id")).get("status"));
        var rereview = approved(c, "handoff");
        assertTrue((Boolean) rereview.get("currentEffective"));
    }

    @Test
    void independentReviewerCanRejectDepartedMakersDraftWithoutFabricatingSubmission() {
        var c = fixture(false, "CAS");
        var f = c.original().f();
        FinancePostgresTest.write(
                "maker",
                f.book(),
                null,
                "members",
                null,
                Map.of("username", "outsider", "role", "MAKER", "enabled", true));
        FinancePostgresTest.write(
                "maker",
                f.book(),
                null,
                "members",
                null,
                Map.of("username", "handoff", "role", "OWNER", "enabled", true));
        var d = write("outsider", c, "create", null, null, body(c));
        FinancePostgresTest.write(
                "maker",
                f.book(),
                null,
                "members",
                null,
                Map.of("username", "outsider", "role", "MAKER", "enabled", false));
        var rejected = write("reviewer", c, "reject", (UUID) d.get("id"), null, action(d));
        assertEquals("REJECTED", rejected.get("status"));
        assertNull(rejected.get("submittedAt"));
        assertNull(rejected.get("submissionExpiresAt"));
        assertNull(rejected.get("activeUsageId"));
        assertEquals("DRAFT", write("handoff", c, "create", null, null, body(c)).get("status"));
    }

    @Test
    void staleEvidenceAndClaimCanRejectCancelAndExpiredReleaseCanTerminate() {
        var c = fixture(false, "CAS");
        var d = write("maker", c, "create", null, null, body(c));
        var bad = action(d);
        ((Map<String, Object>) bad.get("expectedClaim")).put("claimGeneration", 999);
        code(
                "CASH_REVIEW_CLAIM_STALE",
                () -> write("maker", c, "submit", (UUID) d.get("id"), null, bad));
        write("maker", c, "cancel", (UUID) d.get("id"), null, action(d));
        var s = submitted(c, "maker");
        FinanceCashMovementsPostgresTest.write(
                "maker",
                c.original(),
                (UUID) c.raw().get("id"),
                FinanceCashMovementsPostgresTest.action(c.raw()));
        code(
                "CASH_REVIEW_CLAIM_STALE",
                () -> write("reviewer", c, "approve", (UUID) s.get("id"), null, action(s)));
        assertEquals(
                "REJECTED",
                write("reviewer", c, "reject", (UUID) s.get("id"), null, action(s)).get("status"));
        var fresh = fixture(false, "SMALL");
        var expiredReview = submitted(fresh, "maker");
        FinancePostgresTest.tx.execute(
                tx -> {
                    trigger(
                            "plugin_finance_cash_source_review",
                            "finance_cash_source_review_guard",
                            false);
                    db.update(
                            "UPDATE plugin_finance_cash_source_review SET"
                                + " submitted_at=now()-interval '169"
                                + " hours',submission_expires_at=now()-interval '1 hour' WHERE"
                                + " id=?",
                            expiredReview.get("id"));
                    db.update("SET CONSTRAINTS ALL IMMEDIATE");
                    trigger(
                            "plugin_finance_cash_source_review",
                            "finance_cash_source_review_guard",
                            true);
                    return null;
                });
        code(
                "CASH_REVIEW_SUBMISSION_EXPIRED",
                () ->
                        write(
                                "reviewer",
                                fresh,
                                "approve",
                                (UUID) expiredReview.get("id"),
                                null,
                                action(expiredReview)));
        assertEquals(
                "REJECTED",
                write(
                                "reviewer",
                                fresh,
                                "reject",
                                (UUID) expiredReview.get("id"),
                                null,
                                action(expiredReview))
                        .get("status"));
        var a = approved(fresh, "maker");
        var r = request(fresh, a, "maker");
        FinancePostgresTest.tx.execute(
                tx -> {
                    trigger(
                            "plugin_finance_cash_source_review_release",
                            "finance_cash_source_review_release_guard",
                            false);
                    db.update(
                            "UPDATE plugin_finance_cash_source_review_release SET"
                                + " requested_at=now()-interval '169"
                                + " hours',review_expires_at=now()-interval '1 hour' WHERE id=?",
                            r.get("id"));
                    db.update("SET CONSTRAINTS ALL IMMEDIATE");
                    trigger(
                            "plugin_finance_cash_source_review_release",
                            "finance_cash_source_review_release_guard",
                            true);
                    return null;
                });
        code(
                "CASH_REVIEW_SUBMISSION_EXPIRED",
                () ->
                        write(
                                "reviewer",
                                fresh,
                                "release-approve",
                                (UUID) a.get("id"),
                                (UUID) r.get("id"),
                                releaseAction(r)));
        assertEquals(
                "CANCELLED",
                write(
                                "maker",
                                fresh,
                                "release-cancel",
                                (UUID) a.get("id"),
                                (UUID) r.get("id"),
                                releaseAction(r))
                        .get("status"));
        assertTrue(
                (Boolean)
                        reviews.detail(
                                        "maker",
                                        fresh.original().f().book(),
                                        fresh.original().f().year(),
                                        (UUID) a.get("id"))
                                .get("currentEffective"));
    }

    @Test
    void claimAndReviewWatermarksOutboxAuditCacheAllRollbackAtConsumerAndReleaseBoundaries() {
        var c = fixture(false, "CAS");
        var s = submitted(c, "maker");
        var input = action(s);
        String before = facts(c);
        assertThrows(
                IllegalStateException.class,
                () ->
                        FinancePostgresTest.tx.execute(
                                tx -> {
                                    reviews.write(
                                            "reviewer",
                                            c.original().f().book(),
                                            c.original().f().year(),
                                            "approve",
                                            (UUID) s.get("id"),
                                            null,
                                            input);
                                    throw new IllegalStateException(
                                            "injected after real typed usage and audit/cache");
                                }));
        assertEquals(
                "SUBMITTED",
                reviews.detail(
                                "maker",
                                c.original().f().book(),
                                c.original().f().year(),
                                (UUID) s.get("id"))
                        .get("status"));
        assertTrue(
                db.list(
                                "SELECT id FROM plugin_finance_cash_claim_usage WHERE"
                                        + " source_review_id=?",
                                s.get("id"))
                        .isEmpty());
        assertTrue(
                db.list(
                                "SELECT key FROM plugin_finance_idempotency WHERE key=?",
                                input.get("idempotencyKey"))
                        .isEmpty());
        assertEquals(before, facts(c));
        var a = write("reviewer", c, "approve", (UUID) s.get("id"), null, input);
        var r = request(c, a, "maker");
        var releaseInput = releaseAction(r);
        assertThrows(
                IllegalStateException.class,
                () ->
                        FinancePostgresTest.tx.execute(
                                tx -> {
                                    reviews.write(
                                            "reviewer",
                                            c.original().f().book(),
                                            c.original().f().year(),
                                            "release-approve",
                                            (UUID) a.get("id"),
                                            (UUID) r.get("id"),
                                            releaseInput);
                                    throw new IllegalStateException("injected after usage release");
                                }));
        assertNotNull(
                reviews.detail(
                                "maker",
                                c.original().f().book(),
                                c.original().f().year(),
                                (UUID) a.get("id"))
                        .get("activeUsageId"));
        write(
                "reviewer",
                c,
                "release-approve",
                (UUID) a.get("id"),
                (UUID) r.get("id"),
                releaseInput);
        var released =
                reviews.detail(
                        "maker",
                        c.original().f().book(),
                        c.original().f().year(),
                        (UUID) a.get("id"));
        var wm = (Map<String, Object>) released.get("watermark");
        assertEquals(
                ((Number) wm.get("sourceReviewVersion")).longValue(),
                ((Number)
                                db.one(
                                                "SELECT review_version FROM"
                                                    + " plugin_finance_cash_source_review_outbox"
                                                    + " WHERE release_id=? AND"
                                                    + " event_type='RELEASE_APPROVED'",
                                                r.get("id"))
                                        .get("review_version"))
                        .longValue());
        assertEquals(before, facts(c));
    }
}
