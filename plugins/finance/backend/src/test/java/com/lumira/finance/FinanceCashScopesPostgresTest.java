package com.lumira.finance;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessException;

import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Actual PostgreSQL coverage, using only the explicit finance QA database and its random schema.
 */
class FinanceCashScopesPostgresTest {
    static FinanceCashScopes cash;
    static FinanceOriginals originals;
    static FinanceStore db;

    @BeforeAll
    static void setup() throws Exception {
        FinancePostgresTest.setup();
        try (var connection = FinancePostgresTest.source.getConnection()) {
            connection
                    .createStatement()
                    .execute(Files.readString(Path.of("../migrations/V5__cash_scope_review.sql")));
        }
        db = new FinanceStore(FinancePostgresTest.jdbc);
        cash = new FinanceCashScopes(db, FinancePostgresTest.service);
        originals =
                new FinanceOriginals(
                        db,
                        FinancePostgresTest.service,
                        (name, lock) -> FinancePostgresTest.users.get(name));
    }

    @AfterAll
    static void cleanup() {
        FinancePostgresTest.cleanup();
    }

    static UUID original(FinancePostgresTest.Fixture f) {
        return (UUID)
                FinancePostgresTest.tx.execute(
                        status -> {
                            try {
                                return originals
                                        .upload(
                                                "maker",
                                                f.book(),
                                                f.year(),
                                                "cash-eligibility.pdf",
                                                UUID.randomUUID().toString(),
                                                new ByteArrayInputStream(
                                                        new byte[] {1, 2, 3, 4, 5}),
                                                5)
                                        .get("id");
                            } catch (java.io.IOException e) {
                                throw new IllegalStateException(e);
                            }
                        });
    }

    static Map<String, Object> body(
            FinancePostgresTest.Fixture f, UUID evidence, String standard, int month) {
        var start = java.time.LocalDate.of(2026, month, 1);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("accountId", f.cash().toString());
        item.put("instrumentKind", "CASH_ON_HAND");
        item.put("singleCashInstrument", true);
        item.put("qualification", "INCLUDED");
        item.put("restrictionKind", "NONE");
        item.put("availableOnDemand", true);
        item.put(
                "eligibilityBasis",
                "Dedicated cash-on-hand ledger and supporting eligibility evidence; not a bank"
                        + " verification verdict");
        item.put("evidenceOriginalId", evidence.toString());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", FinanceCashScopes.SCHEMA);
        result.put("idempotencyKey", UUID.randomUUID().toString());
        result.put("reportScope", "SEPARATE");
        result.put("currency", "CNY");
        result.put("templateId", standard.equals("CAS") ? "CAS-CF-2019-A2" : "SBE-CF-2011");
        result.put("periodStart", start.toString());
        result.put("periodEnd", start.withDayOfMonth(start.lengthOfMonth()).toString());
        result.put("reason", "Review the independently identifiable monthly cash instrument");
        result.put("items", List.of(item));
        return result;
    }

    static Map<String, Object> write(
            String actor,
            FinancePostgresTest.Fixture f,
            String op,
            UUID id,
            Map<String, Object> body) {
        return FinancePostgresTest.tx.execute(
                status -> cash.write(actor, f.book(), f.year(), op, id, body));
    }

    static Map<String, Object> action(Map<String, Object> scope, String reason) {
        return new LinkedHashMap<>(
                Map.of(
                        "schemaVersion",
                        FinanceCashScopes.SCHEMA,
                        "idempotencyKey",
                        UUID.randomUUID().toString(),
                        "expectedVersion",
                        scope.get("version"),
                        "targetContentVersion",
                        scope.get("contentVersion"),
                        "contentDigest",
                        scope.get("contentDigest"),
                        "reason",
                        reason));
    }

    static Map<String, Object> submitted(
            FinancePostgresTest.Fixture f, String standard, int month) {
        var draft = write("maker", f, "create", null, body(f, original(f), standard, month));
        return write(
                "maker",
                f,
                "submit",
                (UUID) draft.get("id"),
                action(draft, "Freeze submitted qualification evidence"));
    }

    static long count(FinancePostgresTest.Fixture f, String table) {
        return ((Number)
                        db.one("SELECT count(*) AS n FROM " + table + " WHERE book_id=?", f.book())
                                .get("n"))
                .longValue();
    }

    @Test
    void independentCasAndSmallMonthlyReviewPreservesEvidenceAndApprovedFacts() {
        for (String standard : List.of("CAS", "SMALL")) {
            var f = FinancePostgresTest.fixture(standard);
            UUID evidence = original(f);
            var draft = write("maker", f, "create", null, body(f, evidence, standard, 1));
            assertEquals("DRAFT", draft.get("status"));
            assertEquals(1L, ((Number) draft.get("contentVersion")).longValue());
            var item = ((List<Map<String, Object>>) draft.get("items")).getFirst();
            assertEquals(true, item.get("singleCashInstrument"));
            assertEquals(
                    FinanceOriginals.digest(new byte[] {1, 2, 3, 4, 5}),
                    item.get("originalSha256"));
            assertEquals(
                    FinanceService.hash(
                            FinancePostgresTest.service.encode(
                                    FinancePostgresTest.service.canonical(
                                            item.get("accountSnapshot")))),
                    item.get("accountVersionDigest"));
            UUID id = (UUID) draft.get("id");
            for (String forgedEntity : List.of("null", "\"" + UUID.randomUUID() + "\"")) {
                assertThrows(
                        DataAccessException.class,
                        () ->
                                db.update(
                                        "INSERT INTO"
                                            + " plugin_finance_cash_scope_revision(tenant_id,book_id,year_id,id,period_id,schema_version,standard,report_scope,currency,template_id,period_start,period_end,content_digest,content,prepared_by,prepared_username,last_edited_by,reason)"
                                            + " SELECT"
                                            + " tenant_id,book_id,year_id,?,period_id,schema_version,standard,report_scope,currency,template_id,period_start,period_end,content_digest,jsonb_set(content,'{legalEntityId}',?::jsonb),prepared_by,prepared_username,last_edited_by,reason"
                                            + " FROM plugin_finance_cash_scope_revision WHERE id=?",
                                        UUID.randomUUID(),
                                        forgedEntity,
                                        id));
            }

            assertThrows(
                    DataAccessException.class,
                    () ->
                            db.update(
                                    "UPDATE plugin_finance_cash_scope_revision SET"
                                        + " status='SUBMITTED',submitted_at=now(),version=2,approved_by=?"
                                        + " WHERE id=?",
                                    FinancePostgresTest.users.get("reviewer"),
                                    id));
            var sub =
                    write("maker", f, "submit", id, action(draft, "Submitted by actual preparer"));
            assertEquals("SUBMITTED", sub.get("status"));
            assertEquals(
                    "SELF_APPROVAL_FORBIDDEN",
                    assertThrows(
                                    FinanceException.class,
                                    () ->
                                            write(
                                                    "maker",
                                                    f,
                                                    "approve",
                                                    id,
                                                    action(sub, "Cannot self approve")))
                            .code);
            var approved =
                    write(
                            "reviewer",
                            f,
                            "approve",
                            id,
                            action(sub, "Independently reviewed declared instrument and evidence"));
            assertEquals("APPROVED", approved.get("status"));
            assertEquals(1L, ((Number) approved.get("scopeVersion")).longValue());
            assertEquals(FinancePostgresTest.users.get("reviewer"), approved.get("approvedBy"));
            assertThrows(
                    DataAccessException.class,
                    () ->
                            db.update(
                                    "UPDATE plugin_finance_cash_scope_revision SET"
                                            + " reason='tampered' WHERE id=?",
                                    id));
            assertThrows(
                    DataAccessException.class,
                    () ->
                            db.update(
                                    "DELETE FROM plugin_finance_cash_scope_item WHERE"
                                            + " scope_revision_id=?",
                                    id));
            assertEquals(
                    "CASH_SCOPE_CHANGE_UNSUPPORTED",
                    assertThrows(
                                    FinanceException.class,
                                    () ->
                                            write(
                                                    "maker",
                                                    f,
                                                    "create",
                                                    null,
                                                    body(f, evidence, standard, 1)))
                            .code);
            var feb = body(f, evidence, standard, 2);
            var excluded = ((List<Map<String, Object>>) feb.get("items")).getFirst();
            excluded.put("qualification", "EXCLUDED");
            excluded.put("restrictionKind", "RESTRICTED");
            excluded.put("availableOnDemand", false);
            var febDraft = write("maker", f, "create", null, feb);
            var febSub =
                    write(
                            "maker",
                            f,
                            "submit",
                            (UUID) febDraft.get("id"),
                            action(febDraft, "Submit separately evidenced February exclusion"));
            var febApproved =
                    write(
                            "reviewer",
                            f,
                            "approve",
                            (UUID) febDraft.get("id"),
                            action(febSub, "Review exclusion without inventing cash availability"));
            assertEquals(2L, ((Number) febApproved.get("scopeVersion")).longValue());
            assertEquals(0, count(f, "plugin_finance_posting"));
            assertEquals(0, count(f, "plugin_finance_opening"));
            assertEquals(
                    2L,
                    ((Map<String, Object>)
                                    cash.list("maker", f.book(), f.year(), 20, null)
                                            .get("asOfWatermark"))
                            .get("scopeVersion"));
        }
    }

    @Test
    void concurrentMonthlyApprovalsProduceOneEffectiveScopeAndReplayRechecksAuthority()
            throws Exception {
        var f = FinancePostgresTest.fixture("CAS");
        var a = submitted(f, "CAS", 3);
        var b = submitted(f, "CAS", 3);
        var winner = new AtomicReference<Map<String, Object>>();
        var gate = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Callable<String>> attempts = new ArrayList<>();
            for (var candidate : List.of(a, b)) {
                attempts.add(
                        () -> {
                            var request = action(candidate, "Independent competing monthly review");
                            gate.await(10, TimeUnit.SECONDS);
                            try {
                                var result =
                                        write(
                                                "reviewer",
                                                f,
                                                "approve",
                                                (UUID) candidate.get("id"),
                                                request);
                                winner.set(Map.of("request", request, "result", result));
                                return "APPROVED";
                            } catch (FinanceException denied) {
                                return denied.code;
                            }
                        });
            }
            Set<String> results = new HashSet<>();
            for (var future : pool.invokeAll(attempts)) results.add(future.get());
            assertEquals(Set.of("APPROVED", "CASH_SCOPE_CHANGE_UNSUPPORTED"), results);
        }
        var result = (Map<String, Object>) winner.get().get("result");
        var request = (Map<String, Object>) winner.get().get("request");
        assertEquals(
                "APPROVED",
                write("reviewer", f, "approve", (UUID) result.get("id"), request).get("status"));
        assertEquals(
                1L,
                db.one(
                                "SELECT scope_version FROM plugin_finance_cash_scope_guard WHERE"
                                        + " book_id=?",
                                f.book())
                        .get("scope_version"));
        assertEquals(
                1L,
                db.one(
                                "SELECT count(*) AS n FROM plugin_finance_cash_scope_revision WHERE"
                                        + " book_id=? AND status='APPROVED'",
                                f.book())
                        .get("n"));
        FinancePostgresTest.write(
                "maker",
                f.book(),
                null,
                "members",
                null,
                Map.of("username", "reviewer", "role", "READER", "enabled", true));
        assertEquals(
                "SCOPE_DENIED",
                assertThrows(
                                FinanceException.class,
                                () ->
                                        write(
                                                "reviewer",
                                                f,
                                                "approve",
                                                (UUID) result.get("id"),
                                                request))
                        .code);
    }

    @Test
    void stalePreparerOrAccountBlocksApprovalButIndependentRejectCanTerminateIt() {
        var f = FinancePostgresTest.fixture("SMALL");
        var sub = submitted(f, "SMALL", 4);
        db.update(
                "UPDATE plugin_finance_account SET name='Changed after frozen scope' WHERE id=? AND"
                        + " year_id=?",
                f.cash(),
                f.year());
        assertEquals(
                "CASH_SCOPE_STALE",
                assertThrows(
                                FinanceException.class,
                                () ->
                                        write(
                                                "reviewer",
                                                f,
                                                "approve",
                                                (UUID) sub.get("id"),
                                                action(sub, "Must not approve changed account")))
                        .code);
        assertEquals(
                "REJECTED",
                write(
                                "reviewer",
                                f,
                                "reject",
                                (UUID) sub.get("id"),
                                action(sub, "Reject stale account snapshot"))
                        .get("status"));
        var next = submitted(f, "SMALL", 5);
        FinancePostgresTest.write(
                "maker",
                f.book(),
                null,
                "members",
                null,
                Map.of("username", "reviewer", "role", "OWNER", "enabled", true));
        FinancePostgresTest.write(
                "reviewer",
                f.book(),
                null,
                "members",
                null,
                Map.of("username", "maker", "role", "OWNER", "enabled", false));
        assertEquals(
                "APPROVAL_STALE",
                assertThrows(
                                FinanceException.class,
                                () ->
                                        write(
                                                "reviewer",
                                                f,
                                                "approve",
                                                (UUID) next.get("id"),
                                                action(next, "Preparer authority no longer valid")))
                        .code);
        assertEquals(
                "REJECTED",
                write(
                                "reviewer",
                                f,
                                "reject",
                                (UUID) next.get("id"),
                                action(
                                        next,
                                        "Reject after offboarding without treating it as effective"
                                                + " scope"))
                        .get("status"));
        assertEquals(
                0L,
                db.one(
                                "SELECT scope_version FROM plugin_finance_cash_scope_guard WHERE"
                                        + " book_id=?",
                                f.book())
                        .get("scope_version"));
    }

    @Test
    void closedSchemasQualificationsAndRealEvidenceRejectUnsupportedOrCrossScopeInputs() {
        var f = FinancePostgresTest.fixture("CAS");
        UUID evidence = original(f);
        com.lumira.platform.common.AdministrationPort restrictedGuard =
                new com.lumira.platform.common.AdministrationPort() {
                    public Set<String> require(String actor, String permission) {
                        if (permission.equals("platform:finance:create"))
                            throw new org.springframework.web.server.ResponseStatusException(
                                    org.springframework.http.HttpStatus.FORBIDDEN,
                                    "permissionDenied");
                        return Set.of(permission);
                    }

                    public void requireUser(String actor, UUID user, String permission) {}

                    public void requireGlobal(String actor, String permission) {}

                    public boolean hasGlobal(String actor, String permission) {
                        return false;
                    }
                };
        var restrictedCash =
                new FinanceCashScopes(
                        db,
                        new FinanceService(
                                db,
                                restrictedGuard,
                                (name, lock) -> FinancePostgresTest.users.get(name),
                                new tools.jackson.databind.ObjectMapper()));
        assertEquals(
                403,
                assertThrows(
                                org.springframework.web.server.ResponseStatusException.class,
                                () ->
                                        FinancePostgresTest.tx.execute(
                                                status ->
                                                        restrictedCash.write(
                                                                "maker",
                                                                f.book(),
                                                                f.year(),
                                                                "create",
                                                                null,
                                                                body(f, evidence, "CAS", 6))))
                        .getStatusCode()
                        .value());
        var missing = body(f, evidence, "CAS", 6);
        ((List<Map<String, Object>>) missing.get("items"))
                .getFirst()
                .remove("singleCashInstrument");
        assertThrows(FinanceException.class, () -> write("maker", f, "create", null, missing));
        var no = body(f, evidence, "CAS", 6);
        ((List<Map<String, Object>>) no.get("items")).getFirst().put("singleCashInstrument", false);
        assertEquals(
                "CASH_SCOPE_REVIEW_REQUIRED",
                assertThrows(FinanceException.class, () -> write("maker", f, "create", null, no))
                        .code);
        var restricted = body(f, evidence, "CAS", 6);
        ((List<Map<String, Object>>) restricted.get("items"))
                .getFirst()
                .put("restrictionKind", "RESTRICTED");
        assertThrows(FinanceException.class, () -> write("maker", f, "create", null, restricted));
        var wrong = body(f, evidence, "SMALL", 6);
        assertThrows(FinanceException.class, () -> write("maker", f, "create", null, wrong));
        var partial = body(f, evidence, "CAS", 6);
        partial.put("periodEnd", "2026-06-29");
        assertThrows(FinanceException.class, () -> write("maker", f, "create", null, partial));
        var other = FinancePostgresTest.fixture("CAS");
        var foreign = body(f, original(other), "CAS", 6);
        assertEquals(
                "SCOPE_DENIED",
                assertThrows(
                                FinanceException.class,
                                () -> write("maker", f, "create", null, foreign))
                        .code);
        var forged = body(f, evidence, "CAS", 6);
        ((List<Map<String, Object>>) forged.get("items"))
                .getFirst()
                .put("originalSha256", "0".repeat(64));
        assertThrows(FinanceException.class, () -> write("maker", f, "create", null, forged));
        UUID fake = UUID.randomUUID();
        Object tenant =
                FinancePostgresTest.service
                        .originalScope("maker", f.book(), f.year(), "manage")
                        .get("tenant_id");
        db.update(
                "INSERT INTO"
                    + " plugin_finance_original(tenant_id,book_id,year_id,id,filename,media_type,content,size_bytes,sha256,created_by)"
                    + " VALUES(?,?,?,?,'bad.pdf','application/pdf',?,3,?,?)",
                tenant,
                f.book(),
                f.year(),
                fake,
                new byte[] {9, 8, 7},
                "0".repeat(64),
                FinancePostgresTest.users.get("maker"));
        assertEquals(
                "CASH_SCOPE_STALE",
                assertThrows(
                                FinanceException.class,
                                () -> write("maker", f, "create", null, body(f, fake, "CAS", 6)))
                        .code);
        assertEquals(0, count(f, "plugin_finance_cash_scope_revision"));
    }

    @Test
    void readScopesPaginationClosedAndLegacyGatesApplyBeforeCachedWrites() {
        var f = FinancePostgresTest.fixture("CAS");
        UUID evidence = original(f);
        var input = body(f, evidence, "CAS", 7);
        var created = write("maker", f, "create", null, input);
        assertEquals(
                created.get("id").toString(),
                write("maker", f, "create", null, input).get("id").toString());
        write("maker", f, "create", null, body(f, evidence, "CAS", 8));
        var page = cash.list("maker", f.book(), f.year(), 1, null);
        assertNotNull(page.get("nextCursor"));
        var second = cash.list("maker", f.book(), f.year(), 1, (String) page.get("nextCursor"));
        assertNotEquals(
                ((List<Map<String, Object>>) page.get("items")).getFirst().get("id"),
                ((List<Map<String, Object>>) second.get("items")).getFirst().get("id"));
        var other = FinancePostgresTest.fixture("SMALL");
        assertThrows(
                FinanceException.class,
                () ->
                        cash.list(
                                "maker",
                                other.book(),
                                other.year(),
                                1,
                                (String) page.get("nextCursor")));
        assertThrows(
                FinanceException.class,
                () -> cash.detail("outsider", f.book(), f.year(), (UUID) created.get("id")));
        FinancePostgresTest.write(
                "maker",
                f.book(),
                null,
                "members",
                null,
                Map.of("username", "outsider", "role", "READER", "enabled", true));
        assertEquals(
                2,
                ((List<?>) cash.list("outsider", f.book(), f.year(), 20, null).get("items"))
                        .size());
        assertThrows(
                FinanceException.class,
                () -> write("outsider", f, "create", null, body(f, evidence, "CAS", 9)));
        db.update(
                "UPDATE plugin_finance_period SET status='CLOSED' WHERE book_id=? AND year_id=? AND"
                        + " month=7",
                f.book(),
                f.year());
        assertEquals(
                "PERIOD_CLOSED",
                assertThrows(FinanceException.class, () -> write("maker", f, "create", null, input))
                        .code);
        db.update(
                "UPDATE plugin_finance_year SET"
                    + " precision_profile='LEGACY_V3',precision_review_required=true WHERE id=?",
                f.year());
        assertEquals(
                "LEGACY_PRECISION_REVIEW_REQUIRED",
                assertThrows(
                                FinanceException.class,
                                () ->
                                        write(
                                                "maker",
                                                f,
                                                "create",
                                                null,
                                                body(f, evidence, "CAS", 9)))
                        .code);
        assertEquals(
                "DRAFT",
                cash.detail("outsider", f.book(), f.year(), (UUID) created.get("id"))
                        .get("status"));
    }
}
