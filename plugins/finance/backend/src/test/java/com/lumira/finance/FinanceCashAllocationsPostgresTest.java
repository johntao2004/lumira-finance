package com.lumira.finance;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;

/** Real typed allocation effects and atomic historical replacement chains. */
class FinanceCashAllocationsPostgresTest {
    static FinanceCashAllocations allocations;
    static FinanceStore db;

    @BeforeAll
    static void setup() throws Exception {
        FinanceCashSourceReviewsPostgresTest.setup();
        db = FinanceCashSourceReviewsPostgresTest.db;
        FinanceCashPoliciesPostgresTest.db = db;
        FinanceCashPoliciesPostgresTest.policies =
                new FinanceCashPolicies(
                        db, FinancePostgresTest.service, FinanceCashScopesPostgresTest.cash);
        allocations =
                new FinanceCashAllocations(
                        db,
                        FinancePostgresTest.service,
                        FinanceCashSourceReviewsPostgresTest.reviews,
                        FinanceCashPoliciesPostgresTest.policies,
                        FinanceCashScopesPostgresTest.cash,
                        FinanceCashMovementsPostgresTest.movements);
    }

    @AfterAll
    static void cleanup() {
        FinanceCashSourceReviewsPostgresTest.cleanup();
    }

    record Case(
            FinanceCashSourceReviewsPostgresTest.Case source,
            Map<String, Object> review,
            FinanceCashPoliciesPostgresTest.Case policyCase,
            Map<String, Object> policy) {
        FinancePostgresTest.Fixture f() {
            return source.original().f();
        }
    }

    @SuppressWarnings("unchecked")
    static Case fixture(boolean corrected, String standard) {
        var c = FinanceCashSourceReviewsPostgresTest.fixture(corrected, standard);
        var review = FinanceCashSourceReviewsPostgresTest.approved(c, "maker");
        var f = c.original().f();
        FinancePostgresTest.write(
                "maker",
                f.book(),
                f.year(),
                "members",
                null,
                Map.of("username", "handoff", "role", "OWNER", "enabled", true));
        var pc =
                new FinanceCashPoliciesPostgresTest.Case(
                        f,
                        FinanceCashPoliciesPostgresTest.original(f, "basis"),
                        FinanceCashPoliciesPostgresTest.original(f, "support"));
        var parent =
                (Map<String, Object>)
                        ((List<?>)
                                        allocations
                                                .eligible(
                                                        "maker",
                                                        f.book(),
                                                        f.year(),
                                                        (UUID) review.get("id"),
                                                        null,
                                                        20,
                                                        null)
                                                .get("items"))
                                .getFirst();
        var tuple =
                ((List<Map<String, Object>>)
                                FinanceCashPoliciesPostgresTest.policies
                                        .catalog("maker", f.book(), f.year())
                                        .get("items"))
                        .stream()
                                .filter(
                                        p ->
                                                p.get("transactionKind")
                                                        .equals(parent.get("transactionKind")))
                                .findFirst()
                                .orElseThrow();
        var policy =
                FinanceCashPoliciesPostgresTest.approved(
                        pc,
                        FinanceCashPoliciesPostgresTest.body(
                                pc,
                                "POL"
                                        + UUID.randomUUID()
                                                .toString()
                                                .substring(0, 8)
                                                .toUpperCase(Locale.ROOT),
                                tuple));
        return new Case(c, review, pc, policy);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parent(Case c) {
        return (Map<String, Object>)
                ((List<?>)
                                allocations
                                        .eligible(
                                                "maker",
                                                c.f().book(),
                                                c.f().year(),
                                                (UUID) c.review().get("id"),
                                                null,
                                                20,
                                                null)
                                        .get("items"))
                        .getFirst();
    }

    static Map<String, Object> expected(Map<String, Object> p) {
        var n = new LinkedHashMap<String, Object>();
        FinanceCashAllocations.EXPECTED.forEach(k -> n.put(k, p.get(k)));
        return n;
    }

    static Map<String, Object> body(Case c, Map<String, Object> old) {
        return body(c, old, c.policy());
    }

    static Map<String, Object> body(Case c, Map<String, Object> old, Map<String, Object> policy) {
        var p = parent(c);
        var n = new LinkedHashMap<String, Object>();
        n.put("schemaVersion", FinanceCashAllocations.SCHEMA);
        n.put("idempotencyKey", UUID.randomUUID().toString());
        n.put("expectedParent", expected(p));
        n.put("supersedesAllocationId", old == null ? null : old.get("id").toString());
        n.put("expectedSupersededVersion", old == null ? null : old.get("version"));
        n.put("supersededContentDigest", old == null ? null : old.get("contentDigest"));
        n.put(
                "lines",
                List.of(
                        new LinkedHashMap<>(
                                Map.of(
                                        "componentKey",
                                        "gross",
                                        "amount",
                                        new java.math.BigDecimal(p.get("grossAmount").toString())
                                                .setScale(2)
                                                .toPlainString(),
                                        "policyId",
                                        policy.get("id").toString(),
                                        "expectedPolicyVersion",
                                        policy.get("version"),
                                        "policyContentDigest",
                                        policy.get("contentDigest"),
                                        "businessPurpose",
                                        policy.get("businessPurpose"),
                                        "purposeEvidenceOriginalId",
                                        FinanceCashPoliciesPostgresTest.original(c.f(), "purpose")
                                                .toString(),
                                        "description",
                                        "Actual separately retained ordinary purpose evidence",
                                        "ordinaryPurposeConfirmed",
                                        true))));
        n.put("reason", "Prepare genuine full gross ordinary allocation, no formal report");
        return n;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> action(Map<String, Object> a) {
        var n = decision(a);
        n.put("expectedParent", expected((Map<String, Object>) a.get("parentSnapshot")));
        return n;
    }

    static Map<String, Object> decision(Map<String, Object> a) {
        return new LinkedHashMap<>(
                Map.of(
                        "schemaVersion",
                        FinanceCashAllocations.SCHEMA,
                        "idempotencyKey",
                        UUID.randomUUID().toString(),
                        "expectedVersion",
                        a.get("version"),
                        "contentDigest",
                        a.get("contentDigest"),
                        "reason",
                        "Independent controlled lifecycle decision"));
    }

    static Map<String, Object> write(
            String actor, Case c, String op, UUID id, UUID rid, Map<String, Object> n) {
        return FinancePostgresTest.tx.execute(
                s -> allocations.write(actor, c.f().book(), c.f().year(), op, id, rid, n));
    }

    static Map<String, Object> submitted(Case c, Map<String, Object> n) {
        var d = write("maker", c, "create", null, null, n);
        return write("maker", c, "submit", (UUID) d.get("id"), null, action(d));
    }

    static Map<String, Object> approved(Case c) {
        var s = submitted(c, body(c, null));
        return write("reviewer", c, "approve", (UUID) s.get("id"), null, action(s));
    }

    static Map<String, Object> detail(Case c, Map<String, Object> a) {
        return allocations.detail("reviewer", c.f().book(), c.f().year(), (UUID) a.get("id"));
    }

    static Map<String, Object> request(Case c, Map<String, Object> a) {
        var n = decision(a);
        n.put("activeDependencyId", a.get("activeDependencyId").toString());
        return write("handoff", c, "release", (UUID) a.get("id"), null, n);
    }

    static Map<String, Object> replaceBody(
            Case c, Map<String, Object> old, Map<String, Object> next) {
        var n = new LinkedHashMap<String, Object>();
        n.put("schemaVersion", FinanceCashAllocations.SCHEMA);
        n.put("idempotencyKey", UUID.randomUUID().toString());
        n.put("oldAllocationId", old.get("id").toString());
        n.put("expectedOldVersion", old.get("version"));
        n.put("oldContentDigest", old.get("contentDigest"));
        n.put("oldDependencyId", old.get("activeDependencyId").toString());
        n.put("newAllocationId", next.get("id").toString());
        n.put("expectedNewVersion", next.get("version"));
        n.put("newContentDigest", next.get("contentDigest"));
        n.put("expectedParent", expected(parent(c)));
        n.put("reason", "Atomic same-parent successor, preserve historic activation");
        return n;
    }

    static void code(String key, Runnable r) {
        assertEquals(key, assertThrows(FinanceException.class, r::run).code);
    }

    static long count(Case c, String table) {
        return ((Number)
                        db.one(
                                        "SELECT count(*) n FROM "
                                                + table
                                                + " WHERE book_id=? AND year_id=?",
                                        c.f().book(),
                                        c.f().year())
                                .get("n"))
                .longValue();
    }

    static long version(Case c) {
        return ((Number)
                        db.one(
                                        "SELECT allocation_version n FROM"
                                                + " plugin_finance_cash_allocation_guard WHERE"
                                                + " book_id=? AND year_id=?",
                                        c.f().book(),
                                        c.f().year())
                                .get("n"))
                .longValue();
    }

    @Test
    void realTypedDependencyFullGrossAndIndependentReleaseProtectParent() {
        for (String standard : List.of("CAS", "SMALL")) {
            var c = fixture(standard.equals("SMALL"), standard);
            var input = body(c, null);
            String facts = FinanceCashSourceReviewsPostgresTest.facts(c.source());
            var sub = submitted(c, input);
            var a = write("reviewer", c, "approve", (UUID) sub.get("id"), null, action(sub));
            assertEquals("APPROVED", a.get("status"));
            assertEquals(true, a.get("currentEffective"));
            assertNotNull(a.get("activeDependencyId"));
            assertEquals(1, count(c, "plugin_finance_cash_allocation_activation"));
            assertEquals(facts, FinanceCashSourceReviewsPostgresTest.facts(c.source()));
            code(
                    "CASH_REVIEW_IN_USE",
                    () ->
                            FinanceCashSourceReviewsPostgresTest.request(
                                    c.source(), c.review(), "handoff"));
            var req = request(c, a);
            assertEquals(true, detail(c, a).get("currentEffective"));
            var before = version(c);
            write(
                    "reviewer",
                    c,
                    "release-approve",
                    (UUID) a.get("id"),
                    (UUID) req.get("id"),
                    decision(req));
            assertEquals(before + 1, version(c));
            var released = detail(c, a);
            assertEquals("RELEASED", released.get("status"));
            assertNull(released.get("activeDependencyId"));
            assertEquals(false, released.get("currentEffective"));
            var parentRelease =
                    FinanceCashSourceReviewsPostgresTest.request(c.source(), c.review(), "handoff");
            FinanceCashSourceReviewsPostgresTest.write(
                    "reviewer",
                    c.source(),
                    "release-approve",
                    (UUID) c.review().get("id"),
                    (UUID) parentRelease.get("id"),
                    FinanceCashSourceReviewsPostgresTest.releaseAction(parentRelease));
            assertEquals(1, count(c, "plugin_finance_cash_allocation_activation"));
        }
    }

    @Test
    void chainAToBToCThenReleasePreservesBothHistoricalDecisionsAndPolicies() {
        var c = fixture(false, "CAS");
        var a = approved(c);
        var retirement =
                FinanceCashPoliciesPostgresTest.write(
                        "maker",
                        c.policyCase(),
                        "retire",
                        (UUID) c.policy().get("id"),
                        null,
                        FinanceCashPoliciesPostgresTest.action(c.policy()));
        FinanceCashPoliciesPostgresTest.write(
                "reviewer",
                c.policyCase(),
                "retire-approve",
                (UUID) c.policy().get("id"),
                (UUID) retirement.get("id"),
                FinanceCashPoliciesPostgresTest.action(retirement));
        assertEquals("APPROVED", detail(c, a).get("status"));
        code(
                "CASH_ALLOCATION_POLICY_UNAVAILABLE",
                () -> write("maker", c, "create", null, null, body(c, a)));
        var tuple =
                (Map<String, Object>)
                        ((List<?>)
                                        FinanceCashPoliciesPostgresTest.policies
                                                .catalog("maker", c.f().book(), c.f().year())
                                                .get("items"))
                                .stream()
                                        .filter(
                                                x ->
                                                        ((Map<?, ?>) x)
                                                                .get("transactionKind")
                                                                .equals(
                                                                        c.policy()
                                                                                .get(
                                                                                        "transactionKind")))
                                        .findFirst()
                                        .orElseThrow();
        var successorBody =
                FinanceCashPoliciesPostgresTest.body(
                        c.policyCase(), c.policy().get("policyCode").toString(), tuple);
        successorBody.put("supersedesPolicyId", c.policy().get("id").toString());
        var policy = FinanceCashPoliciesPostgresTest.approved(c.policyCase(), successorBody);
        var b = submitted(c, body(c, a, policy));
        var submittedB = b;
        code(
                "CASH_ALLOCATION_REPLACEMENT_REQUIRED",
                () ->
                        write(
                                "reviewer",
                                c,
                                "approve",
                                (UUID) submittedB.get("id"),
                                null,
                                action(submittedB)));
        var r1 = write("maker", c, "replace", null, null, replaceBody(c, a, b));
        var dec1 = decision(r1);
        long v = version(c);
        write("reviewer", c, "replace-approve", null, (UUID) r1.get("id"), dec1);
        assertEquals(v + 1, version(c));
        assertEquals("SUPERSEDED", detail(c, a).get("status"));
        b = detail(c, b);
        assertEquals("APPROVED", b.get("status"));
        var d = submitted(c, body(c, b, policy));
        var r2 = write("maker", c, "replace", null, null, replaceBody(c, b, d));
        var dec2 = decision(r2);
        v = version(c);
        write("reviewer", c, "replace-approve", null, (UUID) r2.get("id"), dec2);
        assertEquals(v + 1, version(c));
        assertEquals("SUPERSEDED", detail(c, b).get("status"));
        d = detail(c, d);
        var release = request(c, d);
        write(
                "reviewer",
                c,
                "release-approve",
                (UUID) d.get("id"),
                (UUID) release.get("id"),
                decision(release));
        var pr = FinanceCashSourceReviewsPostgresTest.request(c.source(), c.review(), "handoff");
        FinanceCashSourceReviewsPostgresTest.write(
                "reviewer",
                c.source(),
                "release-approve",
                (UUID) c.review().get("id"),
                (UUID) pr.get("id"),
                FinanceCashSourceReviewsPostgresTest.releaseAction(pr));
        assertEquals(
                "APPROVED",
                write("reviewer", c, "replace-approve", null, (UUID) r1.get("id"), dec1)
                        .get("status"));
        assertEquals(
                "APPROVED",
                write("reviewer", c, "replace-approve", null, (UUID) r2.get("id"), dec2)
                        .get("status"));
        assertEquals(3, count(c, "plugin_finance_cash_allocation_activation"));
        assertEquals(3, count(c, "plugin_finance_cash_allocation_dependency"));
        assertEquals(0, count(c, "plugin_finance_cash_allocation_effect"));
        assertEquals(
                2,
                ((List<?>)
                                allocations
                                        .replacements(
                                                "reviewer",
                                                c.f().book(),
                                                c.f().year(),
                                                (UUID) b.get("id"),
                                                20,
                                                null)
                                        .get("items"))
                        .size());
    }

    @Test
    void strictAmountsFrozenParentAndStaleDraftTermination() {
        var c = fixture(false, "CAS");
        for (Object amount :
                List.of("1.000", "1.005", "0", "-1", "01.00", 1, "100000000000000000000.00")) {
            var n = body(c, null);
            ((Map<String, Object>) ((List<?>) n.get("lines")).getFirst()).put("amount", amount);
            assertThrows(FinanceException.class, () -> write("maker", c, "create", null, null, n));
        }
        var n = body(c, null);
        ((Map<String, Object>) ((List<?>) n.get("lines")).getFirst()).put("amount", "1.00");
        code("CASH_ALLOCATION_SUM_MISMATCH", () -> write("maker", c, "create", null, null, n));
        var d = write("maker", c, "create", null, null, body(c, null));
        var pr = FinanceCashSourceReviewsPostgresTest.request(c.source(), c.review(), "handoff");
        code(
                "CASH_ALLOCATION_PARENT_RELEASE_PENDING",
                () -> write("maker", c, "submit", (UUID) d.get("id"), null, action(d)));
        assertEquals(
                "REJECTED",
                write("reviewer", c, "reject", (UUID) d.get("id"), null, action(d)).get("status"));
        FinanceCashSourceReviewsPostgresTest.write(
                "reviewer",
                c.source(),
                "release-reject",
                (UUID) c.review().get("id"),
                (UUID) pr.get("id"),
                FinanceCashSourceReviewsPostgresTest.releaseAction(pr));
        assertEquals("DRAFT", write("maker", c, "create", null, null, body(c, null)).get("status"));
    }

    @Test
    void replacementFailureAfterOldEffectRollsBackEverything() {
        var c = fixture(false, "CAS");
        var a = approved(c);
        var b = submitted(c, body(c, a));
        var req = write("maker", c, "replace", null, null, replaceBody(c, a, b));
        var n = decision(req);
        long v = version(c);
        long receipts = count(c, "plugin_finance_cash_allocation_activation");
        db.update(
                "CREATE FUNCTION allocation_test_fail() RETURNS trigger LANGUAGE plpgsql AS $$"
                        + " BEGIN IF NEW.id='"
                        + b.get("id")
                        + "'::uuid AND NEW.status='APPROVED' THEN IF NOT EXISTS(SELECT 1 FROM"
                        + " plugin_finance_cash_allocation WHERE id='"
                        + a.get("id")
                        + "'::uuid AND status='SUPERSEDED') THEN RAISE EXCEPTION 'expected old"
                        + " supersession first';END IF;RAISE EXCEPTION 'injected after old"
                        + " effect';END IF;RETURN NEW;END $$");
        db.update(
                "CREATE TRIGGER z_allocation_test_fail BEFORE UPDATE ON"
                        + " plugin_finance_cash_allocation FOR EACH ROW EXECUTE FUNCTION"
                        + " allocation_test_fail()");
        try {
            assertThrows(
                    RuntimeException.class,
                    () -> write("reviewer", c, "replace-approve", null, (UUID) req.get("id"), n));
            assertEquals(v, version(c));
            assertEquals(receipts, count(c, "plugin_finance_cash_allocation_activation"));
            assertEquals("APPROVED", detail(c, a).get("status"));
            assertEquals("SUBMITTED", detail(c, b).get("status"));
        } finally {
            db.update("DROP TRIGGER z_allocation_test_fail ON plugin_finance_cash_allocation");
            db.update("DROP FUNCTION allocation_test_fail()");
        }
        assertEquals(
                "APPROVED",
                write("reviewer", c, "replace-approve", null, (UUID) req.get("id"), n)
                        .get("status"));
    }

    @Test
    void deferredGuardRejectsApprovedWithoutRealEffectAndPointerDeletion() {
        var c = fixture(false, "CAS");
        var s = submitted(c, body(c, null));
        var n = action(s);
        assertThrows(
                RuntimeException.class,
                () ->
                        FinancePostgresTest.tx.execute(
                                t -> {
                                    db.update(
                                            "ALTER TABLE plugin_finance_cash_allocation DISABLE"
                                                    + " TRIGGER finance_cash_allocation_effects");
                                    allocations.write(
                                            "reviewer",
                                            c.f().book(),
                                            c.f().year(),
                                            "approve",
                                            (UUID) s.get("id"),
                                            null,
                                            n);
                                    return null;
                                }));
        assertEquals("SUBMITTED", detail(c, s).get("status"));
        var a = write("reviewer", c, "approve", (UUID) s.get("id"), null, n);
        assertThrows(
                RuntimeException.class,
                () ->
                        FinancePostgresTest.tx.execute(
                                t -> {
                                    db.update(
                                            "ALTER TABLE plugin_finance_cash_allocation_effect"
                                                    + " DISABLE TRIGGER"
                                                    + " finance_cash_allocation_pointer_guard");
                                    db.update(
                                            "DELETE FROM plugin_finance_cash_allocation_effect"
                                                    + " WHERE allocation_id=?",
                                            a.get("id"));
                                    return null;
                                }));
        assertEquals(true, detail(c, a).get("currentEffective"));
    }

    @Test
    void releaseAndReplacementRequestsCompeteWithoutDoubleEffect() throws Exception {
        var c = fixture(false, "CAS");
        var a = approved(c);
        var b = submitted(c, body(c, a));
        var replacement = replaceBody(c, a, b);
        var release = decision(a);
        release.put("activeDependencyId", a.get("activeDependencyId").toString());
        var latch = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var f1 =
                    pool.submit(
                            () -> {
                                latch.await();
                                try {
                                    return write("maker", c, "replace", null, null, replacement);
                                } catch (FinanceException e) {
                                    return Map.of("error", e.code);
                                }
                            });
            var f2 =
                    pool.submit(
                            () -> {
                                latch.await();
                                try {
                                    return write(
                                            "handoff",
                                            c,
                                            "release",
                                            (UUID) a.get("id"),
                                            null,
                                            release);
                                } catch (FinanceException e) {
                                    return Map.of("error", e.code);
                                }
                            });
            latch.countDown();
            var r1 = f1.get(45, TimeUnit.SECONDS);
            var r2 = f2.get(45, TimeUnit.SECONDS);
            assertEquals(1, (r1.containsKey("error") ? 0 : 1) + (r2.containsKey("error") ? 0 : 1));
            assertEquals("APPROVED", detail(c, a).get("status"));
            assertEquals(1, count(c, "plugin_finance_cash_allocation_activation"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void currentAuthorReplayHandoffAndOrphanDraftAreNotAuthorityShortcuts() {
        var c = fixture(false, "CAS");
        var f = c.f();
        FinancePostgresTest.write(
                "maker",
                f.book(),
                null,
                "members",
                null,
                Map.of("username", "outsider", "role", "MAKER", "enabled", true));
        var d = write("outsider", c, "create", null, null, body(c, null));
        var s = write("outsider", c, "submit", (UUID) d.get("id"), null, action(d));
        var approval = action(s);
        code(
                "SELF_APPROVAL_FORBIDDEN",
                () -> write("maker", c, "approve", (UUID) s.get("id"), null, approval));
        var a = write("reviewer", c, "approve", (UUID) s.get("id"), null, approval);
        FinancePostgresTest.write(
                "maker",
                f.book(),
                null,
                "members",
                null,
                Map.of("username", "outsider", "role", "MAKER", "enabled", false));
        code(
                "APPROVAL_STALE",
                () -> write("reviewer", c, "approve", (UUID) s.get("id"), null, approval));
        assertEquals("APPROVED", detail(c, a).get("status"));
        var request = request(c, a);
        write(
                "reviewer",
                c,
                "release-approve",
                (UUID) a.get("id"),
                (UUID) request.get("id"),
                decision(request));
        FinancePostgresTest.write(
                "maker",
                f.book(),
                null,
                "members",
                null,
                Map.of("username", "outsider", "role", "MAKER", "enabled", true));
        var orphan = write("outsider", c, "create", null, null, body(c, null));
        FinancePostgresTest.write(
                "maker",
                f.book(),
                null,
                "members",
                null,
                Map.of("username", "outsider", "role", "MAKER", "enabled", false));
        var rejected =
                write("reviewer", c, "reject", (UUID) orphan.get("id"), null, action(orphan));
        assertEquals("REJECTED", rejected.get("status"));
        assertNull(rejected.get("submittedAt"));
        assertEquals(
                "DRAFT", write("handoff", c, "create", null, null, body(c, null)).get("status"));
    }

    @Test
    void expirationAllowsTerminationAndDatabaseRejectsRealDraftConsumer() {
        var c = fixture(false, "CAS");
        var s = submitted(c, body(c, null));
        FinancePostgresTest.tx.execute(
                t -> {
                    db.update(
                            "ALTER TABLE plugin_finance_cash_allocation DISABLE TRIGGER"
                                    + " finance_cash_allocation_guard");
                    db.update(
                            "UPDATE plugin_finance_cash_allocation SET submitted_at=now()-interval"
                                + " '169 hours',submission_expires_at=now()-interval '1 hour' WHERE"
                                + " id=?",
                            s.get("id"));
                    db.update("SET CONSTRAINTS ALL IMMEDIATE");
                    db.update(
                            "ALTER TABLE plugin_finance_cash_allocation ENABLE TRIGGER"
                                    + " finance_cash_allocation_guard");
                    return null;
                });
        code(
                "CASH_ALLOCATION_SUBMISSION_EXPIRED",
                () -> write("reviewer", c, "approve", (UUID) s.get("id"), null, action(s)));
        write("reviewer", c, "reject", (UUID) s.get("id"), null, action(s));
        var d = write("maker", c, "create", null, null, body(c, null));
        UUID dep = UUID.randomUUID();
        var error =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                FinancePostgresTest.tx.execute(
                                        t -> {
                                            db.update(
                                                    "ALTER TABLE plugin_finance_cash_allocation"
                                                            + " DISABLE TRIGGER"
                                                            + " finance_cash_allocation_guard");
                                            db.update(
                                                    "ALTER TABLE"
                                                        + " plugin_finance_cash_allocation_dependency"
                                                        + " DISABLE TRIGGER"
                                                        + " finance_cash_allocation_dependency_guard");
                                            db.update(
                                                    "UPDATE plugin_finance_cash_allocation SET"
                                                        + " approved_dependency_id=? WHERE id=?",
                                                    dep,
                                                    d.get("id"));
                                            db.update(
                                                    "INSERT INTO"
                                                        + " plugin_finance_cash_allocation_dependency(tenant_id,book_id,year_id,id,allocation_id,consumer_content_digest,review_id,review_usage_id,review_content_digest,claim_id,raw_registration_id,claim_generation,claim_content_digest,activated_by,activated_at)"
                                                        + " SELECT"
                                                        + " tenant_id,book_id,year_id,?,id,content_digest,review_id,review_usage_id,review_content_digest,claim_id,raw_registration_id,claim_generation,claim_content_digest,?,now()"
                                                        + " FROM plugin_finance_cash_allocation"
                                                        + " WHERE id=?",
                                                    dep,
                                                    FinancePostgresTest.users.get("reviewer"),
                                                    d.get("id"));
                                            return null;
                                        }));
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        assertTrue(
                cause.getMessage().contains("CASH_ALLOCATION_NON_EFFECTIVE_HAS_DEPENDENCY"),
                cause.getMessage());
        assertEquals(
                0,
                ((Number)
                                db.one(
                                                "SELECT count(*) n FROM"
                                                    + " plugin_finance_cash_allocation_dependency"
                                                    + " WHERE allocation_id=?",
                                                d.get("id"))
                                        .get("n"))
                        .longValue());
    }
}
