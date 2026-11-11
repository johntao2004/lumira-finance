package com.lumira.finance;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessException;

import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

class FinanceCashPoliciesPostgresTest {
    static FinanceCashPolicies policies;
    static FinanceStore db;

    @BeforeAll
    static void setup() throws Exception {
        FinanceCashMovementsPostgresTest.setup();
        db = FinanceCashMovementsPostgresTest.db;
        policies =
                new FinanceCashPolicies(
                        db, FinancePostgresTest.service, FinanceCashScopesPostgresTest.cash);
    }

    @AfterAll
    static void cleanup() {
        FinanceCashMovementsPostgresTest.cleanup();
    }

    record Case(FinancePostgresTest.Fixture f, UUID basis, UUID support) {}

    static UUID original(FinancePostgresTest.Fixture f, String purpose) {
        byte[] bytes =
                (purpose + UUID.randomUUID()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return (UUID)
                FinancePostgresTest.tx.execute(
                        s -> {
                            try {
                                return FinanceCashScopesPostgresTest.originals
                                        .upload(
                                                "maker",
                                                f.book(),
                                                f.year(),
                                                purpose + ".txt",
                                                UUID.randomUUID().toString(),
                                                new ByteArrayInputStream(bytes),
                                                bytes.length)
                                        .get("id");
                            } catch (java.io.IOException e) {
                                throw new IllegalStateException(e);
                            }
                        });
    }

    static Case fixture(String standard) {
        var f = FinancePostgresTest.fixture(standard);
        return new Case(f, original(f, "policy-basis"), original(f, "applicability-support"));
    }

    static Map<String, Object> body(Case c, String code, Map<String, Object> tuple) {
        var n = new LinkedHashMap<String, Object>();
        n.put("schemaVersion", FinanceCashPolicies.SCHEMA);
        n.put("idempotencyKey", UUID.randomUUID().toString());
        n.put("policyCode", code);
        n.put("supersedesPolicyId", null);
        n.put("transactionKind", tuple.get("transactionKind"));
        n.put("businessPurpose", tuple.get("businessPurpose"));
        n.put("reportScope", "SEPARATE");
        n.put("currency", "CNY");
        n.put("transactionForm", "ORDINARY_GROSS_CASH");
        n.put("effectiveFrom", "2026-01-01");
        n.put("effectiveTo", "2026-12-31");
        n.put(
                "policyBasis",
                "Retained policy-basis original and explicit ordinary gross cash purpose");
        n.put(
                "applicabilityStatement",
                "Individual actual-cash use will still require independently supported purpose"
                        + " evidence");
        var assertions = new LinkedHashMap<String, Object>();
        FinanceCashPolicies.ASSERTIONS.forEach(a -> assertions.put(a, true));
        n.put("assertions", assertions);
        n.put(
                "evidence",
                List.of(
                        Map.of(
                                "originalId",
                                c.basis().toString(),
                                "purpose",
                                "POLICY_BASIS",
                                "description",
                                "Retained policy basis"),
                        Map.of(
                                "originalId",
                                c.support().toString(),
                                "purpose",
                                "APPLICABILITY_SUPPORT",
                                "description",
                                "Independent retained applicability document")));
        n.put(
                "reason",
                "Prepare a bounded ordinary cash classification policy without consuming a source");
        return n;
    }

    static Map<String, Object> tuple(Case c) {
        return ((List<Map<String, Object>>)
                        policies.catalog("maker", c.f().book(), c.f().year()).get("items"))
                .getFirst();
    }

    static Map<String, Object> action(Map<String, Object> r) {
        return new LinkedHashMap<>(
                Map.of(
                        "schemaVersion",
                        FinanceCashPolicies.SCHEMA,
                        "idempotencyKey",
                        UUID.randomUUID().toString(),
                        "expectedVersion",
                        r.get("version"),
                        "contentDigest",
                        r.get("contentDigest"),
                        "reason",
                        "Independent bounded policy lifecycle decision"));
    }

    static Map<String, Object> write(
            String actor, Case c, String op, UUID id, UUID rid, Map<String, Object> n) {
        return FinancePostgresTest.tx.execute(
                s -> policies.write(actor, c.f().book(), c.f().year(), op, id, rid, n));
    }

    static Map<String, Object> submitted(Case c, Map<String, Object> n) {
        var d = write("maker", c, "create", null, null, n);
        return write("maker", c, "submit", (UUID) d.get("id"), null, action(d));
    }

    static Map<String, Object> approved(Case c, Map<String, Object> n) {
        var s = submitted(c, n);
        return write("reviewer", c, "approve", (UUID) s.get("id"), null, action(s));
    }

    static void code(String code, Runnable work) {
        assertEquals(code, assertThrows(FinanceException.class, work::run).code);
    }

    static String facts(Case c) {
        var rows = new ArrayList<Object>();
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
                        "plugin_finance_source_event",
                        "plugin_finance_business_effect",
                        "plugin_finance_outbox"))
            rows.add(
                    db.list(
                            "SELECT to_jsonb(t)::text v FROM "
                                    + table
                                    + " t WHERE book_id=? ORDER BY to_jsonb(t)::text",
                            c.f().book()));
        rows.add(
                db.list(
                        "SELECT to_jsonb(r)::text v FROM plugin_finance_consumer_receipt r JOIN"
                                + " plugin_finance_business_effect e ON"
                                + " (r.tenant_id,r.effect_id)=(e.tenant_id,e.id) WHERE e.book_id=?"
                                + " ORDER BY to_jsonb(r)::text",
                        c.f().book()));
        return FinancePostgresTest.service.encode(rows);
    }

    @Test
    void sevenCasAndSmallCatalogTuplesPublishWithoutConsumingAnyCashFacts() {
        for (String standard : List.of("CAS", "SMALL")) {
            var c = fixture(standard);
            var catalog = policies.catalog("maker", c.f().book(), c.f().year());
            var tuples = (List<Map<String, Object>>) catalog.get("items");
            assertEquals(7, tuples.size());
            assertEquals(false, catalog.get("publicationImpliesSourceReview"));
            String before = facts(c);
            int i = 0;
            for (var t : tuples) {
                var n = body(c, "ORDINARY_" + (++i), t);
                var d = write("maker", c, "create", null, null, n);
                assertEquals(0L, ((Number) d.get("policyWatermark")).longValue() - i + 1);
                assertEquals(n.get("reason"), d.get("reason"));
                assertEquals(t.get("rowId"), d.get("rowId"));
                assertEquals(t.get("semanticLeaf"), d.get("semanticLeaf"));
                var s = write("maker", c, "submit", (UUID) d.get("id"), null, action(d));
                assertEquals(
                        Duration.ofHours(168),
                        Duration.between(
                                Instant.parse(FinancePages.time(s.get("submittedAt"))),
                                Instant.parse(FinancePages.time(s.get("submissionExpiresAt")))));
                var a = write("reviewer", c, "approve", (UUID) s.get("id"), null, action(s));
                assertEquals(true, a.get("activeForNewUse"));
                assertEquals(false, a.get("formalReportContributionEnabled"));
                assertEquals(i, ((Number) a.get("policyWatermark")).intValue());
            }
            assertEquals(before, facts(c));
        }
    }

    @Test
    void controlledRetirementAndImmediateRevisionChainPreserveHistoricDecisionAndCachedState() {
        var c = fixture("CAS");
        var n = body(c, "POLICY_CHAIN", tuple(c));
        var s = submitted(c, n);
        var approval = action(s);
        var a = write("reviewer", c, "approve", (UUID) s.get("id"), null, approval);
        UUID id = (UUID) a.get("id");
        var successor = body(c, "POLICY_CHAIN", tuple(c));
        successor.put("supersedesPolicyId", id.toString());
        var next = submitted(c, successor);
        code(
                "CASH_POLICY_OVERLAP",
                () -> write("reviewer", c, "approve", (UUID) next.get("id"), null, action(next)));
        var request = write("maker", c, "retire", id, null, action(a));
        var pending = policies.detail("maker", c.f().book(), c.f().year(), id);
        assertEquals(false, pending.get("activeForNewUse"));
        assertEquals(2L, ((Number) pending.get("policyWatermark")).longValue());
        code(
                "CASH_POLICY_OVERLAP",
                () -> write("reviewer", c, "approve", (UUID) next.get("id"), null, action(next)));
        var cancelled =
                write("maker", c, "retire-cancel", id, (UUID) request.get("id"), action(request));
        assertEquals("CANCELLED", cancelled.get("status"));
        assertEquals(
                true,
                policies.detail("maker", c.f().book(), c.f().year(), id).get("activeForNewUse"));
        var request2 = write("maker", c, "retire", id, null, action(a));
        write("reviewer", c, "retire-approve", id, (UUID) request2.get("id"), action(request2));
        var retired = write("reviewer", c, "approve", id, null, approval);
        assertEquals("RETIRED", retired.get("status"));
        assertEquals(
                ((Number) retired.get("policyWatermark")).longValue(),
                ((Number)
                                db.one(
                                                "SELECT publication_version FROM"
                                                        + " plugin_finance_cash_policy_outbox WHERE"
                                                        + " retirement_id=? AND"
                                                        + " event_type='RETIREMENT_APPROVED'",
                                                request2.get("id"))
                                        .get("publication_version"))
                        .longValue());
        var published = write("reviewer", c, "approve", (UUID) next.get("id"), null, action(next));
        assertEquals(2L, ((Number) published.get("revision")).longValue());
        assertNotNull(policies.detail("maker", c.f().book(), c.f().year(), id).get("approvedBy"));
        var third = body(c, "POLICY_CHAIN", tuple(c));
        third.put("supersedesPolicyId", id.toString());
        code("CASH_POLICY_STATE_CONFLICT", () -> write("maker", c, "create", null, null, third));
    }

    @Test
    void concurrentDifferentCodesCannotBypassInclusiveDimensionDateOverlap() throws Exception {
        var c = fixture("SMALL");
        var a = body(c, "OVERLAP_A", tuple(c));
        a.put("effectiveTo", "2026-06-30");
        var b = body(c, "OVERLAP_B", tuple(c));
        b.put("effectiveFrom", "2026-06-30");
        var sa = submitted(c, a);
        var sb = submitted(c, b);
        var pool = Executors.newFixedThreadPool(2);
        var gate = new CountDownLatch(1);
        try {
            var results = new ArrayList<Future<Boolean>>();
            for (var r : List.of(sa, sb))
                results.add(
                        pool.submit(
                                () -> {
                                    gate.await();
                                    try {
                                        write(
                                                "reviewer",
                                                c,
                                                "approve",
                                                (UUID) r.get("id"),
                                                null,
                                                action(r));
                                        return true;
                                    } catch (FinanceException e) {
                                        assertEquals("CASH_POLICY_OVERLAP", e.code);
                                        return false;
                                    }
                                }));
            gate.countDown();
            int successes = 0;
            for (var r : results) if (r.get(30, TimeUnit.SECONDS)) successes++;
            assertEquals(1, successes);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(
                1L,
                ((Number)
                                db.one(
                                                "SELECT count(*) n FROM"
                                                    + " plugin_finance_cash_policy_revision WHERE"
                                                    + " book_id=? AND status='APPROVED'",
                                                c.f().book())
                                        .get("n"))
                        .longValue());
        var error =
                assertThrows(
                        DataAccessException.class,
                        () ->
                                FinancePostgresTest.tx.execute(
                                        s ->
                                                db.update(
                                                        "UPDATE plugin_finance_cash_policy_revision"
                                                            + " SET status='APPROVED',version=version+1,approved_by=?,approved_at=now(),decision_by=?,decision_at=now(),decision_reason='Direct"
                                                            + " SQL overlap must fail' WHERE"
                                                            + " book_id=? AND status='SUBMITTED'",
                                                        FinancePostgresTest.users.get("reviewer"),
                                                        FinancePostgresTest.users.get("reviewer"),
                                                        c.f().book())));
        assertTrue(error.getMostSpecificCause().getMessage().contains("CASH_POLICY_OVERLAP"));
    }

    static void age(Case c, UUID id, boolean retirement) {
        String
                table =
                        retirement
                                ? "plugin_finance_cash_policy_retirement"
                                : "plugin_finance_cash_policy_revision",
                trigger =
                        retirement
                                ? "finance_cash_policy_retire_guard"
                                : "finance_cash_policy_revision_guard",
                columns =
                        retirement
                                ? "requested_at=now()-interval '169"
                                        + " hours',review_expires_at=now()-interval '1 hour'"
                                : "submitted_at=now()-interval '169"
                                        + " hours',submission_expires_at=now()-interval '1 hour'";
        FinancePostgresTest.tx.execute(
                s -> {
                    db.update("ALTER TABLE " + table + " DISABLE TRIGGER " + trigger);
                    db.update("UPDATE " + table + " SET " + columns + " WHERE id=?", id);
                    db.update("ALTER TABLE " + table + " ENABLE TRIGGER " + trigger);
                    return null;
                });
    }

    @Test
    void expiredPendingCanTerminateButNeverPublishOrRetireAndCashMonthsAreNotPolicyGate() {
        var c = fixture("CAS");
        db.update("UPDATE plugin_finance_period SET status='CLOSED' WHERE book_id=?", c.f().book());
        var s = submitted(c, body(c, "EXPIRED_POLICY", tuple(c)));
        UUID id = (UUID) s.get("id");
        age(c, id, false);
        code(
                "CASH_POLICY_SUBMISSION_EXPIRED",
                () -> write("reviewer", c, "approve", id, null, action(s)));
        assertEquals("REJECTED", write("reviewer", c, "reject", id, null, action(s)).get("status"));
        var next = body(c, "EXPIRED_POLICY", tuple(c));
        next.put("supersedesPolicyId", id.toString());
        var a = approved(c, next);
        assertEquals(true, a.get("activeForNewUse"));
        var r = write("maker", c, "retire", (UUID) a.get("id"), null, action(a));
        age(c, (UUID) r.get("id"), true);
        code(
                "CASH_POLICY_SUBMISSION_EXPIRED",
                () ->
                        write(
                                "reviewer",
                                c,
                                "retire-approve",
                                (UUID) a.get("id"),
                                (UUID) r.get("id"),
                                action(r)));
        assertEquals(
                "REJECTED",
                write(
                                "reviewer",
                                c,
                                "retire-reject",
                                (UUID) a.get("id"),
                                (UUID) r.get("id"),
                                action(r))
                        .get("status"));
        assertEquals(
                true,
                policies.detail("maker", c.f().book(), c.f().year(), (UUID) a.get("id"))
                        .get("activeForNewUse"));
    }

    @Test
    void departedPreparerAndRequesterHaveTruthfulIndependentTerminationAndOwnerHandover() {
        var c = fixture("CAS");
        var s = submitted(c, body(c, "HANDOVER", tuple(c)));
        var approval = action(s);
        var a = write("reviewer", c, "approve", (UUID) s.get("id"), null, approval);
        UUID id = (UUID) a.get("id");
        var r = write("maker", c, "retire", id, null, action(a));
        FinancePostgresTest.write(
                "maker",
                c.f().book(),
                null,
                "members",
                null,
                Map.of("username", "reviewer", "role", "OWNER", "enabled", true));
        FinancePostgresTest.write(
                "maker",
                c.f().book(),
                null,
                "members",
                null,
                Map.of("username", "outsider", "role", "OWNER", "enabled", true));
        FinancePostgresTest.write(
                "reviewer",
                c.f().book(),
                null,
                "members",
                null,
                Map.of("username", "maker", "role", "MAKER", "enabled", false));
        code("APPROVAL_STALE", () -> write("reviewer", c, "approve", id, null, approval));
        code(
                "APPROVAL_STALE",
                () -> write("reviewer", c, "retire-approve", id, (UUID) r.get("id"), action(r)));
        assertEquals(
                "REJECTED",
                write("reviewer", c, "retire-reject", id, (UUID) r.get("id"), action(r))
                        .get("status"));
        var handover = write("outsider", c, "retire", id, null, action(a));
        write("reviewer", c, "retire-approve", id, (UUID) handover.get("id"), action(handover));
        assertEquals(
                "RETIRED",
                policies.detail("reviewer", c.f().book(), c.f().year(), id).get("status"));
    }

    @Test
    void closedInputsEvidenceRolesSourceScopesAndIndependentApprovalsAreEnforced() {
        var c = fixture("SMALL");
        var base = body(c, "INPUT_TEST", tuple(c));
        var injected = new LinkedHashMap<>(base);
        injected.put("rowId", "999");
        code("INVALID_INPUT", () -> write("maker", c, "create", null, null, injected));
        var unsupported = new LinkedHashMap<>(base);
        unsupported.put("businessPurpose", "CAPITALIZED_PAYROLL");
        code(
                "CASH_POLICY_PURPOSE_UNSUPPORTED",
                () -> write("maker", c, "create", null, null, unsupported));
        var missing = new LinkedHashMap<>(base);
        missing.put("assertions", Map.of());
        code(
                "CASH_POLICY_PURPOSE_UNSUPPORTED",
                () -> write("maker", c, "create", null, null, missing));
        var duplicate = new LinkedHashMap<>(base);
        duplicate.put(
                "evidence",
                List.of(
                        Map.of(
                                "originalId",
                                c.basis().toString(),
                                "purpose",
                                "POLICY_BASIS",
                                "description",
                                "Basis"),
                        Map.of(
                                "originalId",
                                c.basis().toString(),
                                "purpose",
                                "APPLICABILITY_SUPPORT",
                                "description",
                                "Duplicate original rejected")));
        code("INVALID_INPUT", () -> write("maker", c, "create", null, null, duplicate));
        var other = fixture("CAS");
        var foreign = new LinkedHashMap<>(base);
        foreign.put(
                "evidence",
                List.of(
                        Map.of(
                                "originalId",
                                other.basis().toString(),
                                "purpose",
                                "POLICY_BASIS",
                                "description",
                                "Foreign original"),
                        Map.of(
                                "originalId",
                                c.support().toString(),
                                "purpose",
                                "APPLICABILITY_SUPPORT",
                                "description",
                                "Support")));
        code("SCOPE_DENIED", () -> write("maker", c, "create", null, null, foreign));
        var s = submitted(c, base);
        code(
                "SELF_APPROVAL_FORBIDDEN",
                () -> write("maker", c, "approve", (UUID) s.get("id"), null, action(s)));
        code(
                "SCOPE_DENIED",
                () -> policies.detail("outsider", c.f().book(), c.f().year(), (UUID) s.get("id")));
        UUID nextYear =
                (UUID)
                        FinancePostgresTest.write(
                                        "maker",
                                        c.f().book(),
                                        null,
                                        "years",
                                        null,
                                        Map.of("year", 2027))
                                .get("id");
        FinancePostgresTest.write(
                "maker",
                c.f().book(),
                null,
                "members",
                null,
                Map.of(
                        "username",
                        "reviewer",
                        "role",
                        "REVIEWER",
                        "enabled",
                        true,
                        "yearScope",
                        "SELECTED",
                        "yearIds",
                        List.of(nextYear.toString())));
        code(
                "SCOPE_DENIED",
                () -> write("reviewer", c, "approve", (UUID) s.get("id"), null, action(s)));
        code("SCOPE_DENIED", () -> policies.catalog("reviewer", c.f().book(), c.f().year()));
    }

    @Test
    void publicationOutboxAuditCacheAndWatermarkRollbackTogetherAndHistoryIsImmutable() {
        var c = fixture("CAS");
        var s = submitted(c, body(c, "ROLLBACK_TEST", tuple(c)));
        var input = action(s);
        long before = ((Number) s.get("policyWatermark")).longValue();
        String facts = facts(c);
        long events =
                ((Number)
                                db.one(
                                                "SELECT count(*) n FROM"
                                                        + " plugin_finance_cash_policy_outbox WHERE"
                                                        + " book_id=?",
                                                c.f().book())
                                        .get("n"))
                        .longValue();
        assertThrows(
                IllegalStateException.class,
                () ->
                        FinancePostgresTest.tx.execute(
                                tx -> {
                                    policies.write(
                                            "reviewer",
                                            c.f().book(),
                                            c.f().year(),
                                            "approve",
                                            (UUID) s.get("id"),
                                            null,
                                            input);
                                    throw new IllegalStateException(
                                            "injected after publication/outbox/audit/cache");
                                }));
        var still = policies.detail("maker", c.f().book(), c.f().year(), (UUID) s.get("id"));
        assertEquals("SUBMITTED", still.get("status"));
        assertEquals(before, ((Number) still.get("policyWatermark")).longValue());
        assertEquals(
                events,
                ((Number)
                                db.one(
                                                "SELECT count(*) n FROM"
                                                        + " plugin_finance_cash_policy_outbox WHERE"
                                                        + " book_id=?",
                                                c.f().book())
                                        .get("n"))
                        .longValue());
        assertTrue(
                db.list(
                                "SELECT key FROM plugin_finance_idempotency WHERE key=?",
                                input.get("idempotencyKey"))
                        .isEmpty());
        assertEquals(facts, facts(c));
        var a = write("reviewer", c, "approve", (UUID) s.get("id"), null, input);
        assertThrows(
                DataAccessException.class,
                () ->
                        FinancePostgresTest.tx.execute(
                                tx ->
                                        db.update(
                                                "UPDATE plugin_finance_cash_policy_revision SET"
                                                    + " content=jsonb_set(content,'{rowId}','\"999\"')"
                                                    + " WHERE id=?",
                                                a.get("id"))));
        assertThrows(
                DataAccessException.class,
                () ->
                        FinancePostgresTest.tx.execute(
                                tx ->
                                        db.update(
                                                "DELETE FROM plugin_finance_cash_policy_outbox"
                                                        + " WHERE book_id=?",
                                                c.f().book())));
        db.update(
                "UPDATE plugin_finance_year SET status='CLOSED_PENDING_ARCHIVE' WHERE id=?",
                c.f().year());
        code(
                "PERIOD_CLOSED",
                () -> write("reviewer", c, "approve", (UUID) s.get("id"), null, input));
        assertEquals(
                "APPROVED",
                policies.detail("maker", c.f().book(), c.f().year(), (UUID) s.get("id"))
                        .get("status"));
    }
}
