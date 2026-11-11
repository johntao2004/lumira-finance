package com.lumira.finance;

import static org.junit.jupiter.api.Assertions.*;

import com.lumira.platform.common.AuthorizationContextProjectionPort;

import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Current-schema transactions, synthetic ordinary personnel and authoritative uploaded bytes. */
class FinanceEvidenceUsagePostgresTest {
    static FinanceEvidenceDocuments documents;
    static FinanceExpenseAllocations allocations;
    static FinanceStore db;

    @BeforeAll
    static void setup() throws Exception {
        FinanceJournalPersonPostgresTest.setup();
        db = FinanceJournalPersonPostgresTest.db;
        AuthorizationContextProjectionPort projection =
                p ->
                        Optional.of(
                                new AuthorizationContextProjectionPort.Snapshot(
                                        FinanceJournalPersonPostgresTest.ALL,
                                        FinanceJournalPersonPostgresTest.ALL,
                                        false,
                                        null,
                                        0));
        documents =
                new FinanceEvidenceDocuments(
                        db,
                        FinanceJournalPersonPostgresTest.modern,
                        FinanceJournalPersonPostgresTest.gate,
                        FinancePostgresTest.tx.getTransactionManager(),
                        projection);
        allocations =
                new FinanceExpenseAllocations(
                        db,
                        FinanceJournalPersonPostgresTest.modern,
                        FinanceJournalPersonPostgresTest.gate,
                        FinancePostgresTest.tx.getTransactionManager(),
                        projection);
    }

    @AfterAll
    static void cleanup() {
        FinanceJournalPersonPostgresTest.cleanup();
    }

    @BeforeEach
    void reset() {
        FinanceJournalPersonPostgresTest.denied.clear();
    }

    record Case(
            FinanceJournalPersonPostgresTest.Case base,
            UUID source,
            UUID basis,
            UUID purpose,
            UUID reverse) {
        UUID book() {
            return base.f().book();
        }

        UUID year() {
            return base.f().year();
        }

        UUID tenant() {
            return base.tenant();
        }
    }

    static UUID id(Object value) {
        return UUID.fromString(value.toString());
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    static Map<String, Object> map(Object... pairs) {
        return FinanceEvidenceAccess.map(pairs);
    }

    static Map<String, Object> body(Object... pairs) {
        var in =
                map(
                        "schemaVersion",
                        FinanceEvidenceInput.SCHEMA,
                        "idempotencyKey",
                        UUID.randomUUID().toString());
        in.putAll(map(pairs));
        return in;
    }

    static Case fixture(String standard) {
        var c = FinanceJournalPersonPostgresTest.fixture(standard);
        FinanceJournalPersonPostgresTest.enable(c);
        return new Case(
                c,
                upload(c, "source"),
                upload(c, "basis"),
                upload(c, "purpose"),
                upload(c, "reverse"));
    }

    static UUID upload(FinanceJournalPersonPostgresTest.Case c, String kind) {
        var originals =
                new FinanceOriginals(
                        db,
                        FinanceJournalPersonPostgresTest.modern,
                        FinanceJournalPersonPostgresTest.owners);
        byte[] bytes =
                ("Synthetic EV03 " + kind + " " + UUID.randomUUID())
                        .getBytes(StandardCharsets.UTF_8);
        return FinanceJournalPersonPostgresTest.as(
                "plain",
                () ->
                        FinancePostgresTest.tx.execute(
                                status -> {
                                    try {
                                        return id(
                                                originals
                                                        .upload(
                                                                "plain",
                                                                c.f().book(),
                                                                c.f().year(),
                                                                kind + ".txt",
                                                                UUID.randomUUID().toString(),
                                                                new ByteArrayInputStream(bytes),
                                                                bytes.length)
                                                        .get("id"));
                                    } catch (Exception e) {
                                        throw new IllegalStateException(e);
                                    }
                                }));
    }

    static Map<String, Object> fields(Case c, String key, String cap) {
        var f = FinanceEvidenceInputTest.fields();
        f.put("issuerKey", "Org-" + c.book());
        f.put("externalIdentityKey", key);
        f.put("faceGross", "1000.00");
        f.put("expenseCapacity", cap);
        f.put("basisOriginalId", c.basis().toString());
        return f;
    }

    static Map<String, Object> docs(
            String actor, Case c, String op, UUID target, UUID child, Map<String, Object> in) {
        return FinanceJournalPersonPostgresTest.as(
                actor,
                () ->
                        object(
                                documents.write(
                                        actor,
                                        c.book(),
                                        c.year(),
                                        op,
                                        target,
                                        child,
                                        in,
                                        FinanceJournalPersonPostgresTest.request())));
    }

    static Map<String, Object> alloc(
            String actor, Case c, String op, UUID target, UUID child, Map<String, Object> in) {
        return FinanceJournalPersonPostgresTest.as(
                actor,
                () ->
                        object(
                                allocations.write(
                                        actor,
                                        c.book(),
                                        c.year(),
                                        op,
                                        target,
                                        child,
                                        in,
                                        FinanceJournalPersonPostgresTest.request())));
    }

    static Map<String, Object> docRead(String actor, Case c, UUID doc) {
        return FinanceJournalPersonPostgresTest.as(
                actor,
                () ->
                        object(
                                documents.read(
                                        actor,
                                        c.book(),
                                        c.year(),
                                        "detail",
                                        doc,
                                        null,
                                        FinanceJournalPersonPostgresTest.request())));
    }

    static Map<String, Object> allocRead(String actor, Case c, UUID allocation) {
        return FinanceJournalPersonPostgresTest.as(
                actor,
                () ->
                        object(
                                allocations.read(
                                        actor,
                                        c.book(),
                                        c.year(),
                                        "detail",
                                        allocation,
                                        null,
                                        FinanceJournalPersonPostgresTest.request())));
    }

    static Map<String, Object> action(Map<String, Object> summary) {
        return body(
                "expectedVersion",
                summary.get("version"),
                "contentDigest",
                summary.get("contentDigest"),
                "reason",
                "Synthetic independent organization decision");
    }

    static Map<String, Object> register(Case c, String key, String cap) {
        return docs(
                "maker",
                c,
                "register",
                null,
                null,
                body("originalId", c.source().toString(), "fields", fields(c, key, cap)));
    }

    static Map<String, Object> approveCurrent(Case c, Map<String, Object> detail) {
        var d = object(detail.get("document"));
        UUID doc = id(d.get("id")), proposal = id(d.get("pendingReviewProposalId"));
        var r =
                FinanceJournalPersonPostgresTest.as(
                        "reviewer",
                        () ->
                                object(
                                        documents.read(
                                                "reviewer",
                                                c.book(),
                                                c.year(),
                                                "review-detail",
                                                doc,
                                                proposal,
                                                FinanceJournalPersonPostgresTest.request())));
        docs("reviewer", c, "review-approve", doc, proposal, action(r));
        return docRead("maker", c, doc);
    }

    static Map<String, Object> createBody(Case c, Map<String, Object> d, String op, String amount) {
        var doc = object(d.get("document"));
        return body(
                "documentId",
                doc.get("id").toString(),
                "expectedBasisVersion",
                object(doc.get("currentBasis")).get("basisVersion"),
                "sourceSystem",
                FinanceEvidenceInput.SYSTEM,
                "businessChainKey",
                "CHAIN-" + op,
                "sourceOperationKey",
                op,
                "occurredDate",
                "2026-01-15",
                "amount",
                amount,
                "purpose",
                FinanceEvidenceInput.PURPOSE,
                "purposeEvidenceOriginalId",
                c.purpose().toString(),
                "description",
                "Synthetic expense attribution; no payment or GL effect");
    }

    static Map<String, Object> create(Case c, Map<String, Object> d, String op, String amount) {
        return alloc("plain", c, "create", null, null, createBody(c, d, op, amount));
    }

    static Map<String, Object> step(String actor, Case c, String op, Map<String, Object> detail) {
        var a = object(detail.get("allocation"));
        return alloc(actor, c, op, id(a.get("id")), null, action(a));
    }

    static Map<String, Object> confirmed(Case c, Map<String, Object> d, String op, String amount) {
        return step("reviewer", c, "confirm", step("plain", c, "submit", create(c, d, op, amount)));
    }

    static Map<String, Object> reverse(Case c, Map<String, Object> detail, String amount) {
        var a = object(detail.get("allocation"));
        var request =
                alloc(
                        "maker",
                        c,
                        "reverse-create",
                        id(a.get("id")),
                        null,
                        body(
                                "expectedAllocationVersion",
                                a.get("version"),
                                "allocationContentDigest",
                                a.get("contentDigest"),
                                "amount",
                                amount,
                                "basisOriginalId",
                                c.reverse().toString(),
                                "reason",
                                "Synthetic partial attribution correction, no refund",
                                "scopeOfEffect",
                                FinanceEvidenceInput.REVERSE_SCOPE));
        return alloc(
                "reviewer",
                c,
                "reverse-approve",
                id(a.get("id")),
                id(request.get("id")),
                action(request));
    }

    @Test
    void casAndSmallHaveIndependentBucketsAndRealPartialReverseCurrentReplay() {
        for (String standard : List.of("CAS", "SMALL")) {
            var c = fixture(standard);
            var d = approveCurrent(c, register(c, "INV-" + standard, "900"));
            var buckets = (List<?>) object(d.get("document")).get("buckets");
            assertEquals(3, buckets.size());
            assertEquals("900.00000000", object(buckets.getFirst()).get("capacity"));
            for (int i = 1; i < 3; i++) {
                assertEquals(false, object(buckets.get(i)).get("consumptionSupported"));
                assertNull(object(buckets.get(i)).get("capacity"));
                assertNull(object(buckets.get(i)).get("available"));
            }
            var create = createBody(c, d, "OP-A", "600");
            var a = alloc("plain", c, "create", null, null, create);
            a = step("reviewer", c, "confirm", step("plain", c, "submit", a));
            reverse(c, a, "100");
            create.put("idempotencyKey", UUID.randomUUID().toString());
            var replay = alloc("plain", c, "create", null, null, create);
            assertEquals(
                    object(a.get("allocation")).get("id"),
                    object(replay.get("allocation")).get("id"));
            assertEquals(
                    "500.00000000", object(replay.get("allocation")).get("netConfirmedAmount"));
            assertEquals(1L, count("allocation", c));
            assertEquals(1L, count("confirmation", c));
            assertEquals(1L, count("reverse_effect", c));
            assertEquals(
                    "400.00000000",
                    object(
                                    ((List<?>)
                                                    object(
                                                                    docRead(
                                                                                    "maker",
                                                                                    c,
                                                                                    id(
                                                                                            object(
                                                                                                            d
                                                                                                                    .get(
                                                                                                                            "document"))
                                                                                                    .get(
                                                                                                            "id")))
                                                                            .get("document"))
                                                            .get("buckets"))
                                            .getFirst())
                            .get("available"));
        }
    }

    static long count(String suffix, Case c) {
        return ((Number)
                        db.one(
                                        "SELECT count(*) n FROM plugin_finance_evidence_"
                                                + suffix
                                                + " WHERE tenant_id=? AND book_id=? AND year_id=?",
                                        c.tenant(),
                                        c.book(),
                                        c.year())
                                .get("n"))
                .longValue();
    }

    @Test
    void correctionClearsReservationsKeepsConfirmedAndRequiresExplicitDraftRebase() {
        var c = fixture("CAS");
        var d = approveCurrent(c, register(c, "CORRECTION-OLD", "900"));
        var create = createBody(c, d, "FIRST", "600");
        var a =
                step(
                        "reviewer",
                        c,
                        "confirm",
                        step(
                                "plain",
                                c,
                                "submit",
                                alloc("plain", c, "create", null, null, create)));
        var reserved = step("plain", c, "submit", create(c, d, "SECOND", "300"));
        var draft = create(c, d, "THIRD", "50");
        UUID doc = id(object(d.get("document")).get("id"));
        var proposal =
                docs(
                        "maker",
                        c,
                        "review-create",
                        doc,
                        null,
                        body(
                                "expectedDocumentVersion",
                                object(docRead("maker", c, doc).get("document")).get("version"),
                                "fields",
                                fields(c, "CORRECTION-NEW", "800")));
        var decision = action(proposal);
        assertCode(
                "EVIDENCE_ACTIVE_RESERVATIONS",
                () -> docs("reviewer", c, "review-approve", doc, id(proposal.get("id")), decision));
        step("plain", c, "withdraw", reserved);
        docs("reviewer", c, "review-approve", doc, id(proposal.get("id")), decision);
        assertCode("EVIDENCE_BASIS_STALE", () -> step("plain", c, "submit", draft));
        var currentBasis =
                object(object(docRead("maker", c, doc).get("document")).get("currentBasis"));
        var ds = object(draft.get("allocation"));
        var patched =
                alloc(
                        "plain",
                        c,
                        "edit",
                        id(ds.get("id")),
                        null,
                        body(
                                "expectedVersion",
                                ds.get("version"),
                                "contentDigest",
                                ds.get("contentDigest"),
                                "expectedBasisVersion",
                                currentBasis.get("basisVersion"),
                                "occurredDate",
                                "2026-01-15",
                                "amount",
                                "50",
                                "purposeEvidenceOriginalId",
                                c.purpose().toString(),
                                "description",
                                "Explicit current basis rebase"));
        step("plain", c, "submit", patched);
        reverse(c, a, "100");
        create.put("idempotencyKey", UUID.randomUUID().toString());
        assertEquals(
                "500.00000000",
                object(alloc("plain", c, "create", null, null, create).get("allocation"))
                        .get("netConfirmedAmount"));
        assertEquals(2, ((List<?>) docRead("maker", c, doc).get("identityAliases")).size());
        var lowered = fields(c, "CORRECTION-NEW", "499");
        var low =
                docs(
                        "maker",
                        c,
                        "review-create",
                        doc,
                        null,
                        body(
                                "expectedDocumentVersion",
                                object(docRead("maker", c, doc).get("document")).get("version"),
                                "fields",
                                lowered));
        step("plain", c, "withdraw", allocRead("plain", c, id(ds.get("id"))));
        assertCode(
                "EVIDENCE_CAPACITY_BELOW_COMMITTED",
                () -> docs("reviewer", c, "review-approve", doc, id(low.get("id")), action(low)));
    }

    @Test
    void twentyAttemptsUseAtMostFourWorkersAndShareOnePermanentOperationAndCapacity()
            throws Exception {
        var c = fixture("SMALL");
        var d = approveCurrent(c, register(c, "RACE", "100"));
        var base = createBody(c, d, "STABLE", "10");
        var pool = Executors.newFixedThreadPool(4);
        try {
            var calls = new ArrayList<Callable<UUID>>();
            for (int i = 0; i < 20; i++)
                calls.add(
                        () -> {
                            var request = new LinkedHashMap<>(base);
                            request.put("idempotencyKey", UUID.randomUUID().toString());
                            return id(
                                    object(
                                                    alloc("plain", c, "create", null, null, request)
                                                            .get("allocation"))
                                            .get("id"));
                        });
            var ids = new HashSet<UUID>();
            for (var result : pool.invokeAll(calls)) ids.add(result.get());
            assertEquals(1, ids.size());
            assertEquals(1, count("allocation", c));
            var proposals = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < 20; i++) proposals.add(create(c, d, "CAP-" + i, "10"));
            var submits = new ArrayList<Callable<Boolean>>();
            for (var proposal : proposals)
                submits.add(
                        () -> {
                            try {
                                step("plain", c, "submit", proposal);
                                return true;
                            } catch (FinanceException e) {
                                assertEquals("EVIDENCE_CAPACITY_EXCEEDED", e.code);
                                return false;
                            }
                        });
            int succeeded = 0;
            for (var result : pool.invokeAll(submits)) if (result.get()) succeeded++;
            assertEquals(10, succeeded);
            assertEquals(10, count("usage", c));
            var changed = new LinkedHashMap<>(base);
            changed.put("idempotencyKey", UUID.randomUUID().toString());
            changed.put("businessChainKey", "OTHER-CHAIN");
            assertCode(
                    "IDEMPOTENCY_CONFLICT", () -> alloc("plain", c, "create", null, null, changed));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void departedMakerAndClosedMonthCanTerminateReservedAndReverseConfirmedWithoutGlEffects() {
        var c = fixture("CAS");
        var d = approveCurrent(c, register(c, "EXIT", "900"));
        var confirmed = confirmed(c, d, "COMMITTED", "100");
        var reserved = step("plain", c, "submit", create(c, d, "RESERVED", "200"));
        db.update(
                "UPDATE plugin_finance_member SET enabled=false WHERE tenant_id=? AND book_id=? AND"
                        + " user_id=?",
                c.tenant(),
                c.book(),
                FinancePostgresTest.users.get("plain"));
        db.update(
                "UPDATE plugin_finance_period SET status='CLOSED',version=version+1 WHERE"
                        + " tenant_id=? AND book_id=? AND year_id=? AND month=1",
                c.tenant(),
                c.book(),
                c.year());
        String before = financialFingerprint(c);
        step("reviewer", c, "admin-terminate", reserved);
        reverse(c, confirmed, "25");
        assertEquals(before, financialFingerprint(c));
        assertEquals(
                "CANCELLED",
                object(
                                allocRead(
                                                "reviewer",
                                                c,
                                                id(object(reserved.get("allocation")).get("id")))
                                        .get("allocation"))
                        .get("status"));
        assertEquals(
                0L,
                ((Number)
                                db.one(
                                                "SELECT count(*) n FROM"
                                                    + " plugin_finance_evidence_usage WHERE"
                                                    + " tenant_id=? AND book_id=? AND year_id=? AND"
                                                    + " state='RESERVED'",
                                                c.tenant(),
                                                c.book(),
                                                c.year())
                                        .get("n"))
                        .longValue());
        assertCode("SCOPE_DENIED", () -> step("plain", c, "withdraw", reserved));
    }

    static String financialFingerprint(Case c) {
        var result = new ArrayList<Object>();
        for (String suffix : List.of("journal", "line", "posting", "opening", "original", "audit"))
            result.add(
                    db.one(
                                    "SELECT md5(COALESCE(jsonb_agg(to_jsonb(x) ORDER BY"
                                            + " to_jsonb(x)::text),'[]'::jsonb)::text) h FROM"
                                            + " plugin_finance_"
                                            + suffix
                                            + " x WHERE tenant_id=? AND book_id=?",
                                    c.tenant(),
                                    c.book())
                            .get("h"));
        return result.toString();
    }

    @Test
    void directSqlMissingTypedProofAndInjectedReverseFailureRollbackEverything() {
        var c = fixture("SMALL");
        var d = approveCurrent(c, register(c, "ROLLBACK", "900"));
        var a = confirmed(c, d, "ONE", "100");
        UUID allocation = id(object(a.get("allocation")).get("id"));
        assertThrows(
                RuntimeException.class,
                () ->
                        FinancePostgresTest.tx.execute(
                                status -> {
                                    db.update(
                                            "UPDATE plugin_finance_evidence_allocation SET"
                                                    + " status='DRAFT',version=version+1 WHERE"
                                                    + " tenant_id=? AND book_id=? AND year_id=? AND"
                                                    + " id=?",
                                            c.tenant(),
                                            c.book(),
                                            c.year(),
                                            allocation);
                                    return null;
                                }));
        var request =
                alloc(
                        "maker",
                        c,
                        "reverse-create",
                        allocation,
                        null,
                        body(
                                "expectedAllocationVersion",
                                object(a.get("allocation")).get("version"),
                                "allocationContentDigest",
                                object(a.get("allocation")).get("contentDigest"),
                                "amount",
                                "20",
                                "basisOriginalId",
                                c.reverse().toString(),
                                "reason",
                                "Injected transaction rollback",
                                "scopeOfEffect",
                                FinanceEvidenceInput.REVERSE_SCOPE));
        String before = evidenceFingerprint(c);
        db.update(
                "CREATE FUNCTION ev03_test_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
                        + " RAISE EXCEPTION 'EV03_TEST_REVERSE_ROLLBACK';END $$");
        db.update(
                "CREATE TRIGGER ev03_test_fail AFTER INSERT ON"
                        + " plugin_finance_evidence_reverse_effect FOR EACH ROW EXECUTE FUNCTION"
                        + " ev03_test_fail()");
        try {
            RuntimeException failure =
                    assertThrows(
                            RuntimeException.class,
                            () ->
                                    alloc(
                                            "reviewer",
                                            c,
                                            "reverse-approve",
                                            allocation,
                                            id(request.get("id")),
                                            action(request)));
            var messages = new StringBuilder();
            for (Throwable x = failure; x != null; x = x.getCause())
                messages.append(x.getMessage()).append('\n');
            assertTrue(
                    messages.toString().contains("EV03_TEST_REVERSE_ROLLBACK"),
                    messages.toString());
            assertEquals(before, evidenceFingerprint(c));
        } finally {
            db.update("DROP TRIGGER ev03_test_fail ON plugin_finance_evidence_reverse_effect");
            db.update("DROP FUNCTION ev03_test_fail()");
        }
        alloc("reviewer", c, "reverse-approve", allocation, id(request.get("id")), action(request));
        assertEquals(
                "80.00000000",
                object(allocRead("maker", c, allocation).get("allocation"))
                        .get("netConfirmedAmount"));
    }

    static String evidenceFingerprint(Case c) {
        var result = new ArrayList<Object>();
        for (String suffix :
                List.of(
                        "document",
                        "review",
                        "allocation",
                        "submission",
                        "usage",
                        "confirmation",
                        "release",
                        "reverse_request",
                        "reverse_effect",
                        "allocation_event",
                        "request",
                        "audit",
                        "home_guard"))
            result.add(
                    db.one(
                                    "SELECT md5(COALESCE(jsonb_agg(to_jsonb(x) ORDER BY"
                                            + " to_jsonb(x)::text),'[]'::jsonb)::text) h FROM"
                                            + " plugin_finance_evidence_"
                                            + suffix
                                            + " x WHERE tenant_id=? AND book_id=? AND year_id=?",
                                    c.tenant(),
                                    c.book(),
                                    c.year())
                            .get("h"));
        return result.toString();
    }

    @Test
    void authorizationsAreCheckedBeforeReplayAndIndependentPersonCannotUseAlias() {
        var c = fixture("CAS");
        var registerBody =
                body("originalId", c.source().toString(), "fields", fields(c, "AUTH", "900"));
        var d = docs("maker", c, "register", null, null, registerBody);
        var doc = object(d.get("document"));
        UUID document = id(doc.get("id")), proposal = id(doc.get("pendingReviewProposalId"));
        var r =
                FinanceJournalPersonPostgresTest.as(
                        "maker",
                        () ->
                                object(
                                        documents.read(
                                                "maker",
                                                c.book(),
                                                c.year(),
                                                "review-detail",
                                                document,
                                                proposal,
                                                FinanceJournalPersonPostgresTest.request())));
        assertCode(
                "SELF_APPROVAL_FORBIDDEN",
                () -> docs("alias", c, "review-approve", document, proposal, action(r)));
        FinanceJournalPersonPostgresTest.denied.add("maker:platform:finance:manage");
        assertCode("SCOPE_DENIED", () -> docs("maker", c, "register", null, null, registerBody));
        docs("reviewer", c, "review-reject", document, proposal, action(r));
        assertEquals(
                "REJECTED",
                FinanceJournalPersonPostgresTest.as(
                                "reviewer",
                                () ->
                                        object(
                                                documents.read(
                                                        "reviewer",
                                                        c.book(),
                                                        c.year(),
                                                        "review-detail",
                                                        document,
                                                        proposal,
                                                        FinanceJournalPersonPostgresTest
                                                                .request())))
                        .get("status"));
    }

    @Test
    void paginationBindsFiltersBeforeGenerationAndReadsKeepFinancialFactsUnchanged() {
        var c = fixture("CAS");
        var d = approveCurrent(c, register(c, "PAGE", "900"));
        create(c, d, "A", "10");
        create(c, d, "B", "10");
        create(c, d, "C", "10");
        String before = financialFingerprint(c);
        var request = new MockHttpServletRequest();
        request.addParameter("limit", "1");
        var first =
                FinanceJournalPersonPostgresTest.as(
                        "plain",
                        () ->
                                object(
                                        allocations.read(
                                                "plain", c.book(), c.year(), "list", null, null,
                                                request)));
        assertNotNull(first.get("nextCursor"));
        var next = new MockHttpServletRequest();
        next.addParameter("limit", "1");
        next.addParameter("cursor", first.get("nextCursor").toString());
        var second =
                FinanceJournalPersonPostgresTest.as(
                        "plain",
                        () ->
                                object(
                                        allocations.read(
                                                "plain", c.book(), c.year(), "list", null, null,
                                                next)));
        assertNotEquals(
                object(((List<?>) first.get("items")).getFirst()).get("id"),
                object(((List<?>) second.get("items")).getFirst()).get("id"));
        create(c, d, "D", "10");
        assertCode(
                "EVIDENCE_PAGE_STALE",
                () ->
                        FinanceJournalPersonPostgresTest.as(
                                "plain",
                                () ->
                                        allocations.read(
                                                "plain", c.book(), c.year(), "list", null, null,
                                                next)));
        next.setParameter("limit", "2");
        assertCode(
                "INVALID_INPUT",
                () ->
                        FinanceJournalPersonPostgresTest.as(
                                "plain",
                                () ->
                                        allocations.read(
                                                "plain", c.book(), c.year(), "list", null, null,
                                                next)));
        assertEquals(before, financialFingerprint(c));
    }

    static void assertCode(String code, Runnable work) {
        assertEquals(code, assertThrows(FinanceException.class, work::run).code);
    }

    @Test
    void directSqlRejectCannotAbandonExistingReservedUsage() {
        var c = fixture("CAS");
        var d = approveCurrent(c, register(c, "ORPHAN-RESERVATION", "900"));
        var reserved = step("plain", c, "submit", create(c, d, "RESERVE", "100"));
        UUID allocation = id(object(reserved.get("allocation")).get("id"));
        String before = evidenceFingerprint(c);
        var allStatements = new java.util.concurrent.atomic.AtomicBoolean();
        var committed = new java.util.concurrent.atomic.AtomicBoolean();
        assertThrows(
                RuntimeException.class,
                () -> {
                    FinancePostgresTest.tx.execute(
                            status -> {
                                UUID release = UUID.randomUUID();
                                var who =
                                        FinanceJournalPersonPostgresTest.gate.identity(
                                                c.tenant(),
                                                FinancePostgresTest.users.get("reviewer"));
                                String actor = FinanceJournalPersonPostgresTest.modern.encode(who);
                                db.update(
                                        "INSERT INTO"
                                            + " plugin_finance_evidence_release(tenant_id,legal_entity_id,book_id,year_id,id,document_id,allocation_id,allocation_version,submission_id,usage_id,action,actor,reason)"
                                            + " SELECT"
                                            + " tenant_id,legal_entity_id,book_id,year_id,?::uuid,document_id,id,version+1,NULL,NULL,'REJECT',?::jsonb,'Typed"
                                            + " regression: deliberately omitted live reservation'"
                                            + " FROM plugin_finance_evidence_allocation WHERE"
                                            + " tenant_id=? AND book_id=? AND year_id=? AND id=?",
                                        release,
                                        actor,
                                        c.tenant(),
                                        c.book(),
                                        c.year(),
                                        allocation);
                                db.update(
                                        "UPDATE plugin_finance_evidence_allocation SET"
                                            + " status='DRAFT',version=version+1,current_submission_id=NULL,current_usage_id=NULL,updated_at=CURRENT_TIMESTAMP"
                                            + " WHERE tenant_id=? AND book_id=? AND year_id=? AND"
                                            + " id=?",
                                        c.tenant(),
                                        c.book(),
                                        c.year(),
                                        allocation);
                                db.update(
                                        "INSERT INTO"
                                            + " plugin_finance_evidence_allocation_event(tenant_id,legal_entity_id,book_id,year_id,document_id,allocation_id,allocation_version,previous_version,action,from_status,to_status,content_version,content,content_digest,actor,release_id,reason)"
                                            + " SELECT"
                                            + " tenant_id,legal_entity_id,book_id,year_id,document_id,id,version,version-1,'REJECT','SUBMITTED','DRAFT',content_version,content,content_digest,?::jsonb,?::uuid,'Typed"
                                            + " regression: deliberately omitted live reservation'"
                                            + " FROM plugin_finance_evidence_allocation WHERE"
                                            + " tenant_id=? AND book_id=? AND year_id=? AND id=?",
                                        actor,
                                        release,
                                        c.tenant(),
                                        c.book(),
                                        c.year(),
                                        allocation);
                                allStatements.set(true);
                                return null;
                            });
                    committed.set(true);
                },
                () ->
                        "Illegal sequence committed="
                                + committed.get()
                                + ", all three SQL statements completed="
                                + allStatements.get()
                                + ", orphan RESERVED rows="
                                + db.one(
                                                "SELECT count(*) n FROM"
                                                    + " plugin_finance_evidence_usage u WHERE"
                                                    + " u.tenant_id=? AND u.book_id=? AND"
                                                    + " u.year_id=? AND u.state='RESERVED' AND NOT"
                                                    + " EXISTS(SELECT 1 FROM"
                                                    + " plugin_finance_evidence_allocation a"
                                                    + " WHERE(a.tenant_id,a.book_id,a.year_id,a.id)=(u.tenant_id,u.book_id,u.year_id,u.allocation_id)"
                                                    + " AND a.status='SUBMITTED' AND"
                                                    + " a.current_usage_id=u.id)",
                                                c.tenant(),
                                                c.book(),
                                                c.year())
                                        .get("n"));
        assertFalse(committed.get());
        assertEquals(before, evidenceFingerprint(c));
        assertEquals(
                "SUBMITTED",
                object(allocRead("maker", c, allocation).get("allocation")).get("status"));
    }

    @Test
    void headerEditorAndUpdateTimeCannotChangeWithoutRealVersionedEditProof() {
        var c = fixture("CAS");
        var d = approveCurrent(c, register(c, "HEADER-PROOF", "900"));
        var draft = create(c, d, "HEADER", "10");
        UUID allocation = id(object(draft.get("allocation")).get("id"));
        var other =
                FinanceJournalPersonPostgresTest.gate.identity(
                        c.tenant(), FinancePostgresTest.users.get("other"));
        String before = evidenceFingerprint(c);
        RuntimeException actorFailure =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                FinancePostgresTest.tx.execute(
                                        status -> {
                                            db.update(
                                                    "UPDATE plugin_finance_evidence_allocation SET"
                                                        + " last_editor=?::jsonb WHERE tenant_id=?"
                                                        + " AND book_id=? AND year_id=? AND id=?",
                                                    FinanceJournalPersonPostgresTest.modern.encode(
                                                            other),
                                                    c.tenant(),
                                                    c.book(),
                                                    c.year(),
                                                    allocation);
                                            return null;
                                        }));
        assertConsumerFailure(actorFailure);
        assertEquals(before, evidenceFingerprint(c));
        RuntimeException timeFailure =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                FinancePostgresTest.tx.execute(
                                        status -> {
                                            db.update(
                                                    "UPDATE plugin_finance_evidence_allocation SET"
                                                        + " updated_at=updated_at+interval '1 day'"
                                                        + " WHERE tenant_id=? AND book_id=? AND"
                                                        + " year_id=? AND id=?",
                                                    c.tenant(),
                                                    c.book(),
                                                    c.year(),
                                                    allocation);
                                            return null;
                                        }));
        assertConsumerFailure(timeFailure);
        assertEquals(before, evidenceFingerprint(c));
    }

    @Test
    void typedSubmissionAndUsageCannotJointlyForgeFutureReservationClock() {
        var c = fixture("SMALL");
        var d = approveCurrent(c, register(c, "RECEIPT-CLOCK", "900"));
        var draft = create(c, d, "FUTURE-TIME", "100");
        UUID allocation = id(object(draft.get("allocation")).get("id"));
        String before = evidenceFingerprint(c);
        RuntimeException rejected =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                FinancePostgresTest.tx.execute(
                                        status -> {
                                            UUID submission = UUID.randomUUID(),
                                                    usage = UUID.randomUUID();
                                            db.update(
                                                    "INSERT INTO"
                                                        + " plugin_finance_evidence_submission(tenant_id,legal_entity_id,book_id,year_id,id,document_id,allocation_id,allocation_version,content_version,content_digest,review_id,basis_version,amount,usage_id,submitted_by,submitted_at,expires_at)"
                                                        + " SELECT"
                                                        + " tenant_id,legal_entity_id,book_id,year_id,?::uuid,document_id,id,version+1,content_version,content_digest,basis_review_id,basis_version,amount,?::uuid,prepared_by,CURRENT_TIMESTAMP+interval"
                                                        + " '1 day',CURRENT_TIMESTAMP+interval '8"
                                                        + " days' FROM"
                                                        + " plugin_finance_evidence_allocation"
                                                        + " WHERE tenant_id=? AND book_id=? AND"
                                                        + " year_id=? AND id=?",
                                                    submission,
                                                    usage,
                                                    c.tenant(),
                                                    c.book(),
                                                    c.year(),
                                                    allocation);
                                            db.update(
                                                    "INSERT INTO"
                                                        + " plugin_finance_evidence_usage(tenant_id,legal_entity_id,book_id,year_id,id,document_id,allocation_id,submission_id,amount,state,reserved_at,expires_at)"
                                                        + " SELECT"
                                                        + " tenant_id,legal_entity_id,book_id,year_id,?::uuid,document_id,id,?::uuid,amount,'RESERVED',CURRENT_TIMESTAMP+interval"
                                                        + " '1 day',CURRENT_TIMESTAMP+interval '8"
                                                        + " days' FROM"
                                                        + " plugin_finance_evidence_allocation"
                                                        + " WHERE tenant_id=? AND book_id=? AND"
                                                        + " year_id=? AND id=?",
                                                    usage,
                                                    submission,
                                                    c.tenant(),
                                                    c.book(),
                                                    c.year(),
                                                    allocation);
                                            db.update(
                                                    "UPDATE plugin_finance_evidence_allocation SET"
                                                        + " status='SUBMITTED',version=version+1,current_submission_id=?,current_usage_id=?,updated_at=CURRENT_TIMESTAMP"
                                                        + " WHERE tenant_id=? AND book_id=? AND"
                                                        + " year_id=? AND id=?",
                                                    submission,
                                                    usage,
                                                    c.tenant(),
                                                    c.book(),
                                                    c.year(),
                                                    allocation);
                                            db.update(
                                                    "INSERT INTO"
                                                        + " plugin_finance_evidence_allocation_event(tenant_id,legal_entity_id,book_id,year_id,document_id,allocation_id,allocation_version,previous_version,action,from_status,to_status,content_version,content,content_digest,actor,submission_id,usage_id,reason)"
                                                        + " SELECT"
                                                        + " tenant_id,legal_entity_id,book_id,year_id,document_id,id,version,version-1,'SUBMIT','DRAFT','SUBMITTED',content_version,content,content_digest,prepared_by,?::uuid,?::uuid,'Typed"
                                                        + " regression: future reservation time'"
                                                        + " FROM plugin_finance_evidence_allocation"
                                                        + " WHERE tenant_id=? AND book_id=? AND"
                                                        + " year_id=? AND id=?",
                                                    submission,
                                                    usage,
                                                    c.tenant(),
                                                    c.book(),
                                                    c.year(),
                                                    allocation);
                                            return null;
                                        }));
        assertConsumerFailure(rejected);
        assertEquals(before, evidenceFingerprint(c));
        assertEquals(0, count("usage", c));
    }

    static void assertConsumerFailure(RuntimeException rejected) {
        var chain = new StringBuilder();
        for (Throwable x = rejected; x != null; x = x.getCause())
            chain.append(x.getMessage()).append('\n');
        assertTrue(chain.toString().contains("EVIDENCE_CONSUMER_STATE_CONFLICT"), chain.toString());
    }

    static Case anotherHome(Case original, int number) {
        UUID entity =
                (UUID)
                        db.one(
                                        "SELECT legal_entity_id FROM plugin_finance_book WHERE"
                                                + " id=?",
                                        original.book())
                                .get("legal_entity_id");
        var book =
                FinancePostgresTest.write(
                        "maker",
                        null,
                        null,
                        "book",
                        null,
                        map(
                                "tenantId",
                                original.tenant().toString(),
                                "legalEntityId",
                                entity.toString(),
                                "name",
                                "Other EV03 home",
                                "legalEntityName",
                                "Synthetic organization",
                                "registrationId",
                                UUID.randomUUID().toString(),
                                "standard",
                                "CAS",
                                "baseCurrency",
                                "CNY",
                                "year",
                                number));
        UUID b = id(book.get("id"));
        UUID y = id(db.one("SELECT id FROM plugin_finance_year WHERE book_id=?", b).get("id"));
        for (var member :
                Map.of("reviewer", "REVIEWER", "plain", "MAKER", "other", "OWNER").entrySet())
            FinancePostgresTest.write(
                    "maker",
                    b,
                    null,
                    "members",
                    null,
                    map("username", member.getKey(), "role", member.getValue(), "enabled", true));
        var c =
                new FinanceJournalPersonPostgresTest.Case(
                        new FinancePostgresTest.Fixture(b, y, null, null), original.tenant());
        FinanceJournalPersonPostgresTest.enable(c);
        return new Case(
                c,
                upload(c, "different-scan"),
                upload(c, "basis"),
                upload(c, "purpose"),
                upload(c, "reverse"));
    }

    @Test
    void permanentLegalAliasesRejectDifferentScanAcrossBookAndYearWithoutLeakingForeignHome() {
        var original = fixture("CAS");
        var detail = approveCurrent(original, register(original, " Original-Key ", "900"));
        var doc = object(detail.get("document"));
        UUID document = id(doc.get("id"));
        var review =
                docs(
                        "maker",
                        original,
                        "review-create",
                        document,
                        null,
                        body(
                                "expectedDocumentVersion",
                                doc.get("version"),
                                "fields",
                                fields(original, "New-Key", "800")));
        docs(
                "reviewer",
                original,
                "review-approve",
                document,
                id(review.get("id")),
                action(review));
        assertEquals(
                2, ((List<?>) docRead("maker", original, document).get("identityAliases")).size());
        var other = anotherHome(original, 2027);
        String issuer = object(doc.get("currentBasis")).get("issuerKey").toString();
        db.update(
                "UPDATE plugin_finance_member SET enabled=false WHERE tenant_id=? AND book_id=? AND"
                        + " user_id=?",
                original.tenant(),
                original.book(),
                FinancePostgresTest.users.get("other"));
        for (String key : List.of(" original-key ", " new-key ")) {
            var foreign = fields(other, key, "900");
            foreign.put("issuerKey", " " + issuer.toLowerCase(Locale.ROOT) + " ");
            var input = body("originalId", other.source().toString(), "fields", foreign);
            assertCode("SCOPE_DENIED", () -> docs("other", other, "register", null, null, input));
            assertEquals(0, count("document", other));
            assertEquals(0, count("review", other));
        }
        db.update(
                "UPDATE plugin_finance_member SET enabled=true WHERE tenant_id=? AND book_id=? AND"
                        + " user_id=?",
                original.tenant(),
                original.book(),
                FinancePostgresTest.users.get("other"));
        for (String key : List.of(" original-key ", " new-key ")) {
            var foreign = fields(other, key, "900");
            foreign.put("issuerKey", issuer.toLowerCase(Locale.ROOT));
            assertCode(
                    "EVIDENCE_HOME_SCOPE_UNSUPPORTED",
                    () ->
                            docs(
                                    "other",
                                    other,
                                    "register",
                                    null,
                                    null,
                                    body(
                                            "originalId",
                                            other.source().toString(),
                                            "fields",
                                            foreign)));
        }
        assertEquals(0, count("document", other));
        assertEquals(0, count("usage", other));
        assertEquals(1, count("document", original));
    }

    @Test
    void reviewedZeroCapacityIsKnownZeroRatherThanUnreviewedUnknown() {
        var c = fixture("SMALL");
        var registered = register(c, "ZERO-CAP", "0");
        var before =
                object(((List<?>) object(registered.get("document")).get("buckets")).getFirst());
        assertEquals("UNKNOWN", before.get("qualification"));
        assertNull(before.get("capacity"));
        assertNull(before.get("available"));
        var approved = approveCurrent(c, registered);
        var after = object(((List<?>) object(approved.get("document")).get("buckets")).getFirst());
        assertEquals("ORG_REVIEWED", after.get("qualification"));
        assertEquals("0.00000000", after.get("capacity"));
        assertEquals("0.00000000", after.get("available"));
        var draft = create(c, approved, "ZERO-CAP-POSITIVE-DRAFT", "0.01");
        assertCode("EVIDENCE_CAPACITY_EXCEEDED", () -> step("plain", c, "submit", draft));
        assertEquals(0, count("usage", c));
        assertEquals(0, count("submission", c));
    }

    @Test
    void directSqlEconomicallyValidDraftInsertMustFailAtCommitWithoutRealCreateEvent() {
        var c = fixture("CAS");
        var d = approveCurrent(c, register(c, "DEFERRED", "900"));
        for (String key :
                List.of("sourceType", "reason", "issuerKey", "externalIdentityKey", "faceGross")) {
            var invalid = fields(c, "NULL-CHECK", "900");
            invalid.put(key, null);
            var rejected =
                    assertThrows(
                            RuntimeException.class,
                            () ->
                                    FinancePostgresTest.tx.execute(
                                            status ->
                                                    db.one(
                                                            "SELECT"
                                                                + " plugin_finance_evidence_fields(?::jsonb)::text"
                                                                + " value",
                                                            FinanceJournalPersonPostgresTest.modern
                                                                    .encode(invalid))));
            var chain = new StringBuilder();
            for (Throwable x = rejected; x != null; x = x.getCause())
                chain.append(x.getMessage()).append('\n');
            assertTrue(chain.toString().contains("INVALID_INPUT"), chain.toString());
        }
        var draft = create(c, d, "LEGITIMATE", "10");
        UUID original = id(object(draft.get("allocation")).get("id"));
        String before = evidenceFingerprint(c);
        var inserted = new java.util.concurrent.atomic.AtomicBoolean();
        RuntimeException failure =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                FinancePostgresTest.tx.execute(
                                        status -> {
                                            UUID fake = UUID.randomUUID();
                                            db.update(
                                                    "INSERT INTO plugin_finance_evidence_allocation"
                                                        + " SELECT"
                                                        + " (jsonb_populate_record(NULL::plugin_finance_evidence_allocation,"
                                                        + " to_jsonb(a)||jsonb_build_object('id',?::uuid,'created_at',CURRENT_TIMESTAMP,'updated_at',CURRENT_TIMESTAMP,'source_operation_key','RAW-MISSING-PROOF','content',"
                                                        + " a.content||jsonb_build_object('sourceOperationKey','RAW-MISSING-PROOF'),'content_digest',"
                                                        + " plugin_finance_evidence_digest(a.content||jsonb_build_object('sourceOperationKey','RAW-MISSING-PROOF'))))).*"
                                                        + " FROM plugin_finance_evidence_allocation"
                                                        + " a WHERE tenant_id=? AND book_id=? AND"
                                                        + " year_id=? AND id=?",
                                                    fake,
                                                    c.tenant(),
                                                    c.book(),
                                                    c.year(),
                                                    original);
                                            inserted.set(true);
                                            return null;
                                        }));
        assertTrue(
                inserted.get(),
                "INSERT must pass scalar/scope checks; this test targets deferred missing proof");
        var causes = new StringBuilder();
        for (Throwable x = failure; x != null; x = x.getCause())
            causes.append(x.getMessage()).append('\n');
        assertTrue(
                causes.toString().contains("EVIDENCE_CONSUMER_STATE_CONFLICT"), causes.toString());
        assertEquals(before, evidenceFingerprint(c));
        assertEquals(1, count("allocation", c));
    }

    @Test
    void transactionStartedBeforeExpiryButQueuedApprovalUsesActualDecisionClock() throws Exception {
        var c = fixture("CAS");
        var d = approveCurrent(c, register(c, "SHORT-TTL", "900"));
        UUID doc = id(object(d.get("document")).get("id")), proposal = UUID.randomUUID();
        // Legal typed fixture, not an update to immutable history and not an API configurable TTL.
        FinancePostgresTest.tx.execute(
                status -> {
                    db.update(
                            "INSERT INTO plugin_finance_evidence_review SELECT"
                                + " (jsonb_populate_record(NULL::plugin_finance_evidence_review,"
                                + " to_jsonb(r)||jsonb_build_object('id',?::uuid,'status','PENDING','version',1,"
                                + " 'previous_review_id',r.id,'previous_basis_version',d.basis_version,'published_basis_version',NULL,"
                                + " 'decided_by',NULL,'decided_at',NULL,'decision_reason',NULL,'created_at',CURRENT_TIMESTAMP,"
                                + " 'expires_at',CURRENT_TIMESTAMP+interval '2 seconds'))).* FROM"
                                + " plugin_finance_evidence_document d JOIN"
                                + " plugin_finance_evidence_review r"
                                + " ON(r.tenant_id,r.book_id,r.year_id,r.id)=(d.tenant_id,d.book_id,d.year_id,d.current_review_id)"
                                + " WHERE d.tenant_id=? AND d.book_id=? AND d.year_id=? AND d.id=?",
                            proposal,
                            c.tenant(),
                            c.book(),
                            c.year(),
                            doc);
                    db.update(
                            "UPDATE plugin_finance_evidence_document SET"
                                + " latest_review_id=?,pending_review_id=?,version=version+1 WHERE"
                                + " tenant_id=? AND book_id=? AND year_id=? AND id=?",
                            proposal,
                            proposal,
                            c.tenant(),
                            c.book(),
                            c.year(),
                            doc);
                    return null;
                });
        var r =
                FinanceJournalPersonPostgresTest.as(
                        "reviewer",
                        () ->
                                object(
                                        documents.read(
                                                "reviewer",
                                                c.book(),
                                                c.year(),
                                                "review-detail",
                                                doc,
                                                proposal,
                                                FinanceJournalPersonPostgresTest.request())));
        var lockAcquired = new CountDownLatch(1);
        var transactionStarted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var blocker =
                    pool.submit(
                            () ->
                                    FinancePostgresTest.tx.execute(
                                            status -> {
                                                FinanceJournalPersonPostgresTest.gate.prelock(
                                                        c.tenant());
                                                db.one(
                                                        "SELECT id FROM plugin_finance_book WHERE"
                                                            + " tenant_id=? AND id=? FOR UPDATE",
                                                        c.tenant(),
                                                        c.book());
                                                lockAcquired.countDown();
                                                try {
                                                    if (!release.await(8, TimeUnit.SECONDS))
                                                        throw new IllegalStateException(
                                                                "test lock not released");
                                                } catch (InterruptedException e) {
                                                    Thread.currentThread().interrupt();
                                                    throw new IllegalStateException(e);
                                                }
                                                return null;
                                            }));
            assertTrue(lockAcquired.await(3, TimeUnit.SECONDS));
            var approval =
                    pool.submit(
                            () ->
                                    FinanceJournalPersonPostgresTest.as(
                                            "reviewer",
                                            () ->
                                                    FinancePostgresTest.tx.execute(
                                                            status -> {
                                                                var time =
                                                                        db.one(
                                                                                "SELECT"
                                                                                    + " CURRENT_TIMESTAMP"
                                                                                    + " started,expires_at"
                                                                                    + " FROM"
                                                                                    + " plugin_finance_evidence_review"
                                                                                    + " WHERE"
                                                                                    + " tenant_id=?"
                                                                                    + " AND book_id=?"
                                                                                    + " AND year_id=?"
                                                                                    + " AND id=?",
                                                                                c.tenant(),
                                                                                c.book(),
                                                                                c.year(),
                                                                                proposal);
                                                                assertTrue(
                                                                        ((java.sql.Timestamp)
                                                                                        time.get(
                                                                                                "started"))
                                                                                .before(
                                                                                        (java.sql
                                                                                                        .Timestamp)
                                                                                                time
                                                                                                        .get(
                                                                                                                "expires_at")),
                                                                        "fixture transaction must"
                                                                                + " genuinely start"
                                                                                + " before expiry");
                                                                transactionStarted.countDown();
                                                                return docs(
                                                                        "reviewer",
                                                                        c,
                                                                        "review-approve",
                                                                        doc,
                                                                        proposal,
                                                                        action(r));
                                                            })));
            assertTrue(transactionStarted.await(3, TimeUnit.SECONDS));
            Thread.sleep(2300);
            assertEquals(
                    true,
                    db.one(
                                    "SELECT clock_timestamp()>=expires_at expired FROM"
                                        + " plugin_finance_evidence_review WHERE tenant_id=? AND"
                                        + " book_id=? AND year_id=? AND id=?",
                                    c.tenant(),
                                    c.book(),
                                    c.year(),
                                    proposal)
                            .get("expired"));
            release.countDown();
            blocker.get(4, TimeUnit.SECONDS);
            var failure =
                    assertThrows(ExecutionException.class, () -> approval.get(5, TimeUnit.SECONDS));
            assertInstanceOf(FinanceException.class, failure.getCause());
            assertEquals("EVIDENCE_REVIEW_EXPIRED", ((FinanceException) failure.getCause()).code);
            docs("reviewer", c, "review-reject", doc, proposal, action(r));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
