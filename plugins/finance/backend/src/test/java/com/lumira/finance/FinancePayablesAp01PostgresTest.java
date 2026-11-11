package com.lumira.finance;

import static org.junit.jupiter.api.Assertions.*;

import com.lumira.platform.common.AuthorizationContextProjectionPort;

import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Real current-schema AP facts; host authentication is mocked and requires separate live
 * acceptance.
 */
class FinancePayablesAp01PostgresTest {
    static FinancePayablesAp01 service;
    static FinanceStore db;

    @BeforeAll
    static void setup() throws Exception {
        FinanceEvidenceUsagePostgresTest.setup();
        db = FinanceEvidenceUsagePostgresTest.db;
        for (String file :
                List.of(
                        "V17__counterparty_master_data.sql",
                        "V18__payables_accounting_confirmation.sql"))
            try (var c = FinancePostgresTest.source.getConnection()) {
                c.setAutoCommit(false);
                c.createStatement().execute(Files.readString(Path.of("../migrations/" + file)));
                c.commit();
            }
        AuthorizationContextProjectionPort p =
                a ->
                        Optional.of(
                                new AuthorizationContextProjectionPort.Snapshot(
                                        FinanceJournalPersonPostgresTest.ALL,
                                        FinanceJournalPersonPostgresTest.ALL,
                                        false,
                                        null,
                                        0));
        service =
                new FinancePayablesAp01(
                        db,
                        FinanceJournalPersonPostgresTest.modern,
                        FinanceJournalPersonPostgresTest.gate,
                        FinancePostgresTest.tx.getTransactionManager(),
                        p);
        FinanceCounterpartyPostgresTest.db = db;
        FinanceCounterpartyPostgresTest.service =
                new FinanceCounterparties(
                        db,
                        FinanceJournalPersonPostgresTest.modern,
                        FinanceJournalPersonPostgresTest.gate,
                        FinancePostgresTest.tx.getTransactionManager(),
                        p);
    }

    @AfterAll
    static void cleanup() {
        FinanceEvidenceUsagePostgresTest.cleanup();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Object x) {
        return (Map<String, Object>) x;
    }

    static UUID id(Object x) {
        return UUID.fromString(x.toString());
    }

    static Map<String, Object> map(Object... x) {
        return FinanceJournalPersonGate.map(x);
    }

    static Map<String, Object> body(Object... x) {
        var n =
                map(
                        "schemaVersion",
                        FinancePayablesAp01Input.SCHEMA,
                        "idempotencyKey",
                        UUID.randomUUID().toString(),
                        "reason",
                        "Synthetic independent AP accounting decision");
        n.putAll(map(x));
        return n;
    }

    record Case(
            FinanceEvidenceUsagePostgresTest.Case expense,
            UUID allocation,
            UUID supplier,
            UUID debit,
            UUID credit) {
        UUID book() {
            return expense.book();
        }

        UUID year() {
            return expense.year();
        }

        UUID tenant() {
            return expense.tenant();
        }
    }

    static Case fixture(String standard) {
        var c = FinanceEvidenceUsagePostgresTest.fixture(standard);
        var doc =
                FinanceEvidenceUsagePostgresTest.approveCurrent(
                        c,
                        FinanceEvidenceUsagePostgresTest.register(
                                c, "AP-" + UUID.randomUUID(), "900"));
        var allocation =
                FinanceEvidenceUsagePostgresTest.confirmed(
                        c, doc, "AP-" + UUID.randomUUID(), "600.00");
        var created =
                FinanceCounterpartyPostgresTest.write(
                        "maker",
                        c.book(),
                        "create",
                        null,
                        null,
                        FinanceCounterpartyPostgresTest.create(
                                "AP-" + UUID.randomUUID().toString().toUpperCase(Locale.ROOT)));
        var party = id(obj(created.get("counterparty")).get("id"));
        var pending = FinanceCounterpartyPostgresTest.pending(c.book(), created);
        FinanceCounterpartyPostgresTest.decide("reviewer", c.book(), "approve", pending);
        UUID
                debit =
                        FinanceProfitClosingPostgresTest.account(
                                c.base(), "6609", "EXPENSE", "DEBIT", "NONE"),
                credit =
                        FinanceProfitClosingPostgresTest.account(
                                c.base(), "2209", "LIABILITY", "CREDIT", "NONE");
        return new Case(c, id(obj(allocation.get("allocation")).get("id")), party, debit, credit);
    }

    static Map<String, Object> write(
            String actor, Case c, String op, UUID target, Map<String, Object> n) {
        return FinanceJournalPersonPostgresTest.as(
                actor,
                () ->
                        obj(
                                service.write(
                                        actor,
                                        c.book(),
                                        c.year(),
                                        op,
                                        target,
                                        FinanceJournalPersonPostgresTest.gate.decode(
                                                FinanceJournalPersonPostgresTest.modern.encode(n)),
                                        FinanceJournalPersonPostgresTest.request())));
    }

    static Map<String, Object> read(String actor, Case c, String op, UUID target) {
        return FinanceJournalPersonPostgresTest.as(
                actor,
                () ->
                        obj(
                                service.read(
                                        actor,
                                        c.book(),
                                        c.year(),
                                        op,
                                        target,
                                        FinanceJournalPersonPostgresTest.request())));
    }

    static Map<String, Object> parent(Case c) {
        return ((List<?>) read("maker", c, "parents", null).get("items"))
                .stream()
                        .map(FinancePayablesAp01PostgresTest::obj)
                        .filter(
                                p ->
                                        obj(p.get("parent"))
                                                .get("allocationId")
                                                .toString()
                                                .equals(c.allocation().toString()))
                        .findFirst()
                        .orElseThrow();
    }

    static Map<String, Object> basis(Case c) {
        var parent = parent(c);
        var expectation = obj(parent.get("parent")).get("documentExpectation");
        var created =
                write(
                        "maker",
                        c,
                        "basis-create",
                        null,
                        body(
                                "documentExpectation",
                                expectation,
                                "expectedCurrentBasisId",
                                null,
                                "expectedCurrentBasisVersion",
                                null,
                                "accountingCapacity",
                                "800.00",
                                "basisOriginalId",
                                c.expense().basis().toString(),
                                "recognitionEligibilityReason",
                                "组织独立复核会计确认容量，不是费用上限",
                                "organizationReviewedNotTaxVerified",
                                true));
        var current = obj(created.get("current"));
        var b = obj(current.get("basis"));
        return obj(
                write(
                                "reviewer",
                                c,
                                "basis-approve",
                                id(b.get("id")),
                                body(
                                        "expectedVersion",
                                        b.get("version"),
                                        "contentDigest",
                                        b.get("contentDigest")))
                        .get("current"));
    }

    static Map<String, Object> create(Case c) {
        basis(c);
        var p = parent(c);
        var supplier = FinanceCounterpartyPostgresTest.detail(c.book(), c.supplier());
        var party = obj(supplier.get("counterparty"));
        var approved = obj(party.get("effectiveRevision"));
        var expected =
                map(
                        "counterpartyId",
                        c.supplier().toString(),
                        "governanceHomeBookId",
                        c.book().toString(),
                        "registryVersion",
                        party.get("registryVersion"),
                        "approvedRevisionId",
                        approved.get("id").toString(),
                        "approvedRevisionDigest",
                        approved.get("contentDigest"));
        return obj(
                write(
                                "maker",
                                c,
                                "create",
                                null,
                                body(
                                        "operationId",
                                        UUID.randomUUID().toString(),
                                        "expectedPredecessorId",
                                        null,
                                        "expectedPredecessorVersion",
                                        null,
                                        "expectedParent",
                                        p.get("parent"),
                                        "expectedSupplier",
                                        expected,
                                        "expectedAccountingBasis",
                                        obj(p.get("capacity")).get("currentBasis"),
                                        "accountingDate",
                                        "2026-01-15",
                                        "dueDate",
                                        null,
                                        "expenseAccountId",
                                        c.debit().toString(),
                                        "payableAccountId",
                                        c.credit().toString(),
                                        "serviceAcceptanceOriginalId",
                                        c.expense().purpose().toString(),
                                        "recognitionOriginalId",
                                        c.expense().basis().toString(),
                                        "recognitionReason",
                                        "服务已接受后的费用应付会计确认",
                                        "summary",
                                        "真实服务费用应付",
                                        "ordinaryServiceExpenseConfirmed",
                                        true,
                                        "supplierMatchesDocumentConfirmed",
                                        true,
                                        "serviceAcceptedConfirmed",
                                        true,
                                        "noTaxAssetInventoryPayrollConfirmed",
                                        true,
                                        "payableAccountUseConfirmed",
                                        true))
                        .get("current"));
    }

    static Map<String, Object> action(Map<String, Object> d) {
        var p = obj(d.get("payable"));
        return body(
                "expectedVersion",
                p.get("version"),
                "expectedContentVersion",
                p.get("contentVersion"),
                "contentDigest",
                p.get("contentDigest"),
                "sourceDigest",
                p.get("sourceDigest"));
    }

    static Map<String, Object> step(String actor, Case c, String op, Map<String, Object> d) {
        var p = obj(d.get("payable"));
        var n = action(d);
        if (op.equals("submit") || op.equals("approve")) n.put("requestedExpiresAt", null);
        if (op.equals("approve") || op.equals("post"))
            n.put("submissionId", obj(d.get("currentSubmission")).get("id").toString());
        if (op.equals("post"))
            n.put("approvalId", obj(d.get("currentApproval")).get("id").toString());
        return obj(write(actor, c, op, id(p.get("id")), n).get("current"));
    }

    static Map<String, Object> createInput(Case c) {
        var p = parent(c);
        if (obj(p.get("capacity")).get("currentBasis") == null) {
            basis(c);
            p = parent(c);
        }
        var party =
                obj(
                        FinanceCounterpartyPostgresTest.detail(c.book(), c.supplier())
                                .get("counterparty"));
        var approved = obj(party.get("effectiveRevision"));
        var n = FinancePayablesAp01InputTest.create();
        n.putAll(
                map(
                        "expectedParent",
                        p.get("parent"),
                        "expectedAccountingBasis",
                        obj(p.get("capacity")).get("currentBasis"),
                        "expectedSupplier",
                        map(
                                "counterpartyId",
                                c.supplier().toString(),
                                "governanceHomeBookId",
                                party.get("governanceHomeBookId").toString(),
                                "registryVersion",
                                party.get("registryVersion"),
                                "approvedRevisionId",
                                approved.get("id").toString(),
                                "approvedRevisionDigest",
                                approved.get("contentDigest")),
                        "expenseAccountId",
                        c.debit().toString(),
                        "payableAccountId",
                        c.credit().toString(),
                        "serviceAcceptanceOriginalId",
                        c.expense().purpose().toString(),
                        "recognitionOriginalId",
                        c.expense().basis().toString()));
        return n;
    }

    static Map<String, Object> prepare(Case c, Map<String, Object> n) {
        return obj(write("maker", c, "create", null, n).get("current"));
    }

    static Map<String, Object> detailQuery(String actor, Case c, UUID run, Map<String, String> q) {
        return FinanceJournalPersonPostgresTest.as(
                actor,
                () -> {
                    var request = FinanceJournalPersonPostgresTest.request();
                    q.forEach(request::addParameter);
                    return obj(service.read(actor, c.book(), c.year(), "detail", run, request));
                });
    }

    static Map<String, Object> posted(Case c, Map<String, Object> d) {
        return step(
                "maker", c, "post", step("reviewer", c, "approve", step("maker", c, "submit", d)));
    }

    static Map<String, Object> reverseInput(Case c, Map<String, Object> d) {
        var r = obj(d.get("payable"));
        var sl = obj(d.get("currentSourceSlot"));
        var i = obj(d.get("openItem"));
        return body(
                "operationId",
                UUID.randomUUID().toString(),
                "expectedPredecessorId",
                sl.get("latestAttemptId").toString(),
                "expectedPredecessorVersion",
                sl.get("latestAttemptVersion"),
                "expectedOriginalVersion",
                r.get("version"),
                "expectedOriginalContentDigest",
                r.get("contentDigest"),
                "originalPostReceiptId",
                obj(d.get("postReceipt")).get("id").toString(),
                "originalOpenItemId",
                i.get("id").toString(),
                "expectedOpenItemVersion",
                i.get("version"),
                "accountingReasonOriginalId",
                c.expense().reverse().toString());
    }

    static Map<String, Object> reverse(Case c, Map<String, Object> d) {
        return obj(
                write(
                                "maker",
                                c,
                                "full-reversal",
                                id(obj(d.get("payable")).get("id")),
                                reverseInput(c, d))
                        .get("current"));
    }

    static void code(String code, org.junit.jupiter.api.function.Executable work) {
        assertEquals(code, assertThrows(FinanceException.class, work).code);
    }

    static String causes(Throwable x) {
        var text = new StringBuilder();
        for (; x != null; x = x.getCause())
            text.append(x.getClass().getSimpleName())
                    .append(':')
                    .append(x.getMessage())
                    .append('\n');
        return text.toString();
    }

    static void commitDenied(String label, String sql, Object... args) {
        var all = new AtomicBoolean();
        var failure =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                FinancePostgresTest.tx.execute(
                                        s -> {
                                            db.update(sql, args);
                                            all.set(true);
                                            return null;
                                        }));
        assertTrue(
                all.get(),
                "actual SQL must finish before deferred COMMIT denial: " + causes(failure));
        assertTrue(causes(failure).contains("AP_STATE_CONFLICT"), causes(failure));
        System.out.println(
                "AP COMMIT NEGATIVE "
                        + label
                        + " allSQLCompleted="
                        + all
                        + " committed=false cause="
                        + causes(failure));
    }

    static void assertReceiptUsesActualTuple(Case c, Map<String, Object> detail) {
        var payable = obj(detail.get("payable"));
        var receipt = obj(detail.get("postReceipt"));
        var post =
                db.one(
                        "SELECT * FROM plugin_finance_ap_post WHERE tenant_id=? AND book_id=? AND"
                            + " year_id=? AND run_id=?",
                        c.tenant(),
                        c.book(),
                        c.year(),
                        id(payable.get("id")));
        assertEquals(post.get("id"), receipt.get("id"));
        assertEquals(post.get("journal_id"), receipt.get("journalId"));
        assertEquals(post.get("posting_seq").toString(), receipt.get("postingSeq"));
        assertEquals(post.get("submission_id"), receipt.get("submissionId"));
        assertEquals(post.get("approval_id"), receipt.get("approvalId"));
        if (payable.get("producerKind").equals("AP_EXPENSE_REVERSAL")) {
            var effect =
                    db.one(
                            "SELECT * FROM plugin_finance_ap_reverse_effect WHERE tenant_id=? AND"
                                + " book_id=? AND year_id=? AND run_id=?",
                            c.tenant(),
                            c.book(),
                            c.year(),
                            id(payable.get("id")));
            var original =
                    db.one(
                            "SELECT id,post_id,run_id FROM plugin_finance_ap_usage WHERE"
                                + " tenant_id=? AND book_id=? AND year_id=? AND id=?",
                            c.tenant(),
                            c.book(),
                            c.year(),
                            effect.get("original_usage_id"));
            var item =
                    db.one(
                            "SELECT id,post_id,usage_id FROM plugin_finance_ap_open_item WHERE"
                                + " tenant_id=? AND book_id=? AND year_id=? AND id=?",
                            c.tenant(),
                            c.book(),
                            c.year(),
                            effect.get("open_item_id"));
            var dependency =
                    db.one(
                            "SELECT id FROM plugin_finance_ap_parent_dependency WHERE tenant_id=?"
                                + " AND book_id=? AND year_id=? AND run_id=? AND usage_id=?",
                            c.tenant(),
                            c.book(),
                            c.year(),
                            original.get("run_id"),
                            original.get("id"));
            assertEquals(effect.get("post_id"), receipt.get("id"));
            assertEquals(effect.get("original_post_id"), original.get("post_id"));
            assertEquals(effect.get("original_post_id"), item.get("post_id"));
            assertEquals(effect.get("original_usage_id"), item.get("usage_id"));
            assertEquals(original.get("id"), receipt.get("recognitionUsageId"));
            assertEquals(item.get("id"), receipt.get("openItemId"));
            assertEquals(dependency.get("id"), receipt.get("parentDependencyId"));
            assertEquals(effect.get("id"), receipt.get("openItemAdjustmentId"));
            assertEquals(effect.get("original_post_id"), receipt.get("reversesPostReceiptId"));
            assertNull(
                    post.get("usage_id"),
                    "the reversal must not fabricate a second recognition usage");
        } else {
            var usage =
                    db.one(
                            "SELECT id FROM plugin_finance_ap_usage WHERE tenant_id=? AND book_id=?"
                                + " AND year_id=? AND run_id=? AND post_id=?",
                            c.tenant(),
                            c.book(),
                            c.year(),
                            id(payable.get("id")),
                            post.get("id"));
            var item =
                    db.one(
                            "SELECT id FROM plugin_finance_ap_open_item WHERE tenant_id=? AND"
                                + " book_id=? AND year_id=? AND post_id=?",
                            c.tenant(),
                            c.book(),
                            c.year(),
                            post.get("id"));
            var dependency =
                    db.one(
                            "SELECT id FROM plugin_finance_ap_parent_dependency WHERE tenant_id=?"
                                + " AND book_id=? AND year_id=? AND run_id=? AND usage_id=?",
                            c.tenant(),
                            c.book(),
                            c.year(),
                            id(payable.get("id")),
                            usage.get("id"));
            assertEquals(usage.get("id"), receipt.get("recognitionUsageId"));
            assertEquals(item.get("id"), receipt.get("openItemId"));
            assertEquals(dependency.get("id"), receipt.get("parentDependencyId"));
            assertNull(receipt.get("openItemAdjustmentId"));
            assertNull(receipt.get("reversesPostReceiptId"));
        }
    }

    @Test
    void actualCasExpensePayableAndFullReverseAreRealGlNotPayment() {
        var c = fixture("CAS");
        var d = create(c);
        UUID run = id(obj(d.get("payable")).get("id"));
        assertTrue(((List<?>) d.get("allowedActions")).containsAll(List.of("SUBMIT", "CANCEL")));
        assertTrue(
                ((List<?>) read("reviewer", c, "detail", run).get("allowedActions"))
                        .containsAll(List.of("REJECT", "ADMIN_TERMINATE")));
        d = step("maker", c, "submit", d);
        assertTrue(((List<?>) d.get("allowedActions")).contains("WITHDRAW"));
        assertTrue(
                ((List<?>) read("reviewer", c, "detail", run).get("allowedActions"))
                        .contains("APPROVE"));
        assertFalse(
                ((List<?>) read("alias", c, "detail", run).get("allowedActions"))
                        .contains("APPROVE"));
        d = step("reviewer", c, "approve", d);
        assertTrue(
                ((List<?>) read("maker", c, "detail", run).get("allowedActions")).contains("POST"));
        d = step("maker", c, "post", d);
        assertEquals("POSTED", obj(d.get("payable")).get("journalStatus"));
        assertReceiptUsesActualTuple(c, d);
        assertEquals("600.00000000", obj(d.get("openItem")).get("systemOutstanding"));
        var reconciliation = read("maker", c, "reconciliation", null);
        assertEquals("600.00000000", reconciliation.get("typedApGlNetCredit"));
        assertEquals("0.00000000", reconciliation.get("openItemDifference"));
        assertNull(obj(d.get("openItem")).get("externalSettlementAmount"));
        var original = obj(d.get("payable"));
        var slot = obj(d.get("currentSourceSlot"));
        var item = obj(d.get("openItem"));
        var reverse =
                obj(
                        write(
                                        "maker",
                                        c,
                                        "full-reversal",
                                        id(original.get("id")),
                                        body(
                                                "operationId",
                                                UUID.randomUUID().toString(),
                                                "expectedPredecessorId",
                                                slot.get("latestAttemptId").toString(),
                                                "expectedPredecessorVersion",
                                                slot.get("latestAttemptVersion"),
                                                "expectedOriginalVersion",
                                                original.get("version"),
                                                "expectedOriginalContentDigest",
                                                original.get("contentDigest"),
                                                "originalPostReceiptId",
                                                obj(d.get("postReceipt")).get("id").toString(),
                                                "originalOpenItemId",
                                                item.get("id").toString(),
                                                "expectedOpenItemVersion",
                                                item.get("version"),
                                                "accountingReasonOriginalId",
                                                c.expense().reverse().toString()))
                                .get("current"));
        reverse =
                step(
                        "maker",
                        c,
                        "post",
                        step("reviewer", c, "approve", step("maker", c, "submit", reverse)));
        assertEquals("AP_EXPENSE_REVERSAL", obj(reverse.get("payable")).get("producerKind"));
        assertReceiptUsesActualTuple(c, reverse);
        var old = read("maker", c, "detail", id(original.get("id")));
        assertEquals("POSTED", obj(old.get("payable")).get("journalStatus"));
        assertReceiptUsesActualTuple(c, old);
        assertEquals("FULLY_REVERSED", obj(old.get("payable")).get("currentEffect"));
        assertEquals("0.00000000", obj(old.get("openItem")).get("systemOutstanding"));
        assertEquals(
                "0.00000000", read("maker", c, "reconciliation", null).get("typedApGlNetCredit"));
    }

    @Test
    void smallApAndFullReverseContributeToActualExistingProfitSource() {
        var c = fixture("SMALL");
        FinanceProfitClosingPostgresTest.account(
                c.expense().base(), "4103", "EQUITY", "CREDIT", "CURRENT_YEAR_PROFIT");
        FinanceProfitClosingPostgresTest.account(
                c.expense().base(), "4104", "EQUITY", "CREDIT", "RETAINED_EARNINGS");
        var d = posted(c, prepare(c, createInput(c)));
        assertReceiptUsesActualTuple(c, d);
        var preview =
                obj(
                        FinanceJournalPersonPostgresTest.gate.decode(
                                db.one(
                                                "SELECT"
                                                    + " plugin_finance_profit_closing_preview(?,?,?,'PROFIT_LOSS','2026-01-31'::date,NULL)::text"
                                                    + " value",
                                                c.tenant(),
                                                c.book(),
                                                c.year())
                                        .get("value")));
        var source = obj(preview.get("source"));
        var row =
                ((List<?>) source.get("sourceRows"))
                        .stream()
                                .map(FinancePayablesAp01PostgresTest::obj)
                                .filter(
                                        x ->
                                                obj(x.get("account"))
                                                        .get("id")
                                                        .toString()
                                                        .equals(c.debit().toString()))
                                .findFirst()
                                .orElseThrow();
        assertEquals("600.00000000", row.get("balance"));
        assertEquals("PRODUCIBLE", preview.get("outcome"));
        var reversed = posted(c, reverse(c, d));
        assertReceiptUsesActualTuple(c, reversed);
        var after =
                FinanceJournalPersonPostgresTest.gate.decode(
                        db.one(
                                        "SELECT"
                                            + " plugin_finance_profit_closing_preview(?,?,?,'PROFIT_LOSS','2026-01-31'::date,NULL)::text"
                                            + " value",
                                        c.tenant(),
                                        c.book(),
                                        c.year())
                                .get("value"));
        assertEquals("NOOP", after.get("outcome"));
        assertEquals(
                2L,
                ((Number)
                                db.one(
                                                "SELECT count(*) n FROM plugin_finance_posting"
                                                        + " WHERE year_id=?",
                                                c.year())
                                        .get("n"))
                        .longValue());
        assertEquals(
                "0.00000000",
                db.one(
                                "SELECT coalesce(sum(l.debit-l.credit),0)::numeric(28,8)::text n"
                                    + " FROM plugin_finance_line l JOIN plugin_finance_journal j"
                                    + " ON(j.tenant_id,j.book_id,j.year_id,j.id)=(l.tenant_id,l.book_id,l.year_id,l.journal_id)"
                                    + " WHERE j.year_id=? AND j.status='POSTED' AND l.account_id=?",
                                c.year(),
                                c.expense().base().f().cash())
                        .get("n"));
    }

    @Test
    void permanentOperationAndOriginalCommandReceiptSurviveCancelAndNewHttpKeys() throws Exception {
        var c = fixture("CAS");
        var n = createInput(c);
        var other = new LinkedHashMap<>(n);
        other.put("idempotencyKey", UUID.randomUUID().toString());
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var f1 =
                    pool.submit(
                            () -> {
                                start.await();
                                return write("maker", c, "create", null, n);
                            });
            var f2 =
                    pool.submit(
                            () -> {
                                start.await();
                                return write("maker", c, "create", null, other);
                            });
            start.countDown();
            var one = f1.get(20, TimeUnit.SECONDS);
            var two = f2.get(20, TimeUnit.SECONDS);
            assertEquals(
                    obj(one.get("current")).get("payable"), obj(two.get("current")).get("payable"));
            assertEquals(
                    obj(one.get("commandReceipt")).get("id"),
                    obj(two.get("commandReceipt")).get("id"));
            var cancelled = step("maker", c, "cancel", obj(one.get("current")));
            assertEquals("CANCELLED", obj(cancelled.get("payable")).get("journalStatus"));
            var cached = write("maker", c, "create", null, n);
            var freshKey = new LinkedHashMap<>(n);
            freshKey.put("idempotencyKey", UUID.randomUUID().toString());
            var permanent = write("maker", c, "create", null, freshKey);
            assertEquals(
                    obj(one.get("commandReceipt")).get("id"),
                    obj(cached.get("commandReceipt")).get("id"));
            assertEquals(
                    obj(one.get("commandReceipt")).get("id"),
                    obj(permanent.get("commandReceipt")).get("id"));
            assertEquals(
                    "CANCELLED",
                    obj(obj(permanent.get("current")).get("payable")).get("journalStatus"));
            assertEquals(
                    1L,
                    ((Number)
                                    db.one(
                                                    "SELECT count(*) n FROM plugin_finance_ap_run"
                                                            + " WHERE year_id=?",
                                                    c.year())
                                            .get("n"))
                            .longValue());
            UUID run = id(obj(cancelled.get("payable")).get("id"));
            var firstPage = detailQuery("maker", c, run, Map.of("historyLimit", "1"));
            String cursor = obj(firstPage.get("history")).get("nextCursor").toString();
            var secondPage =
                    detailQuery(
                            "maker", c, run, Map.of("historyLimit", "1", "historyCursor", cursor));
            assertEquals(
                    obj(one.get("commandReceipt")).get("id"),
                    obj(((List<?>) obj(secondPage.get("history")).get("items")).getFirst())
                            .get("id"));
            code(
                    "INVALID_INPUT",
                    () ->
                            detailQuery(
                                    "alias",
                                    c,
                                    run,
                                    Map.of("historyLimit", "1", "historyCursor", cursor)));
            var forged = service.untoken(cursor);
            forged.put("anchor", UUID.randomUUID().toString());
            code(
                    "INVALID_INPUT",
                    () ->
                            detailQuery(
                                    "maker",
                                    c,
                                    run,
                                    Map.of(
                                            "historyLimit",
                                            "1",
                                            "historyCursor",
                                            service.token(forged))));
            var next = createInput(c);
            next.put("expectedPredecessorId", run.toString());
            next.put("expectedPredecessorVersion", obj(cancelled.get("payable")).get("version"));
            prepare(c, next);
            code(
                    "AP_PAGE_STALE",
                    () ->
                            detailQuery(
                                    "maker",
                                    c,
                                    run,
                                    Map.of("historyLimit", "1", "historyCursor", cursor)));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void reservedAndCommittedParentDependenciesBlockExpenseReductionUntilRealFullReverse() {
        var c = fixture("CAS");
        var d = step("maker", c, "submit", prepare(c, createInput(c)));
        var parent =
                FinanceEvidenceUsagePostgresTest.allocRead("maker", c.expense(), c.allocation());
        code(
                "AP_PARENT_IN_USE",
                () -> FinanceEvidenceUsagePostgresTest.reverse(c.expense(), parent, "1.00"));
        d = step("maker", c, "post", step("reviewer", c, "approve", d));
        final var committedParent = parent;
        code(
                "AP_PARENT_IN_USE",
                () ->
                        FinanceEvidenceUsagePostgresTest.reverse(
                                c.expense(), committedParent, "1.00"));
        var attempt = reverse(c, d);
        step("maker", c, "cancel", attempt);
        var original = read("maker", c, "detail", id(obj(d.get("payable")).get("id")));
        assertEquals("COMMITTED", obj(original.get("payable")).get("currentEffect"));
        assertEquals("600.00000000", obj(original.get("openItem")).get("systemOutstanding"));
        code(
                "AP_PARENT_IN_USE",
                () ->
                        FinanceEvidenceUsagePostgresTest.reverse(
                                c.expense(), committedParent, "1.00"));
        posted(c, reverse(c, original));
        FinanceEvidenceUsagePostgresTest.reverse(c.expense(), committedParent, "100.00");
        var corrected =
                FinanceEvidenceUsagePostgresTest.allocRead("maker", c.expense(), c.allocation());
        assertEquals("500.00000000", obj(corrected.get("allocation")).get("netConfirmedAmount"));
        FinanceEvidenceUsagePostgresTest.reverse(
                c.expense(),
                FinanceEvidenceUsagePostgresTest.allocRead("maker", c.expense(), c.allocation()),
                "500.00");
        var zero = parent(c);
        assertEquals("0.00000000", obj(zero.get("parent")).get("netConfirmedAmount"));
        assertEquals(false, zero.get("eligibleForPreparation"));
    }

    @Test
    void existingRealRowsCannotBeDetachedOrFalselyReversedAtCommit() {
        var c = fixture("CAS");
        var d = posted(c, prepare(c, createInput(c)));
        UUID run = id(obj(d.get("payable")).get("id"));
        UUID post = id(obj(d.get("postReceipt")).get("id"));
        commitDenied(
                "active parent detached",
                "UPDATE plugin_finance_ap_parent_dependency SET state='RELEASED' WHERE tenant_id=?"
                        + " AND book_id=? AND year_id=? AND run_id=?",
                c.tenant(),
                c.book(),
                c.year(),
                run);
        commitDenied(
                "false usage reverse",
                "UPDATE plugin_finance_ap_usage SET state='FULLY_REVERSED',reverse_post_id=? WHERE"
                        + " tenant_id=? AND book_id=? AND year_id=? AND run_id=?",
                post,
                c.tenant(),
                c.book(),
                c.year(),
                run);
        commitDenied(
                "false open item reverse",
                "UPDATE plugin_finance_ap_open_item SET version=2,reverse_post_id=? WHERE"
                        + " tenant_id=? AND book_id=? AND year_id=? AND run_id=?",
                post,
                c.tenant(),
                c.book(),
                c.year(),
                run);
        var doc =
                FinanceEvidenceUsagePostgresTest.docRead(
                        "maker", c.expense(), id(obj(d.get("payable")).get("documentId")));
        var extra =
                FinanceEvidenceUsagePostgresTest.confirmed(
                        c.expense(), doc, "EXTRA-" + UUID.randomUUID(), "100.00");
        var ap = obj(extra.get("allocation"));
        var p =
                db.one(
                        "SELECT chain_id FROM plugin_finance_evidence_allocation WHERE id=?",
                        id(ap.get("id")));
        commitDenied(
                "empty slot poisonous pointers",
                "INSERT INTO"
                    + " plugin_finance_ap_source_slot(tenant_id,book_id,year_id,id,parent_allocation_id,chain_id,active_usage_id,active_open_item_id)"
                    + " VALUES(?,?,?,?,?,?,?,?)",
                c.tenant(),
                c.book(),
                c.year(),
                UUID.randomUUID(),
                id(ap.get("id")),
                p.get("chain_id"),
                obj(d.get("postReceipt")).get("recognitionUsageId"),
                obj(d.get("openItem")).get("id"));
        assertEquals(
                "600.00000000",
                obj(read("maker", c, "detail", run).get("openItem")).get("systemOutstanding"));
    }

    @Test
    void journalBeforeOpenItemFailureRollsBackEverythingAndSamePostKeyRecovers() {
        var c = fixture("CAS");
        var approved =
                step(
                        "reviewer",
                        c,
                        "approve",
                        step("maker", c, "submit", prepare(c, createInput(c))));
        var n = action(approved);
        n.put("submissionId", obj(approved.get("currentSubmission")).get("id").toString());
        n.put("approvalId", obj(approved.get("currentApproval")).get("id").toString());
        UUID run = id(obj(approved.get("payable")).get("id"));
        var before =
                db.one(
                                "SELECT posting_seq FROM plugin_finance_year_guard WHERE year_id=?",
                                c.year())
                        .get("posting_seq");
        code("SCOPE_DENIED", () -> write("reviewer", c, "post", run, n));
        db.update(
                "CREATE FUNCTION ap_test_item_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
                        + " RAISE EXCEPTION 'AP_TEST_ITEM_FAILURE';END $$");
        db.update(
                "CREATE TRIGGER ap_test_item_fail BEFORE INSERT ON plugin_finance_ap_open_item FOR"
                        + " EACH ROW EXECUTE FUNCTION ap_test_item_fail()");
        try {
            var e = assertThrows(RuntimeException.class, () -> write("maker", c, "post", run, n));
            assertTrue(causes(e).contains("AP_TEST_ITEM_FAILURE"), causes(e));
            assertEquals(
                    before,
                    db.one(
                                    "SELECT posting_seq FROM plugin_finance_year_guard WHERE"
                                            + " year_id=?",
                                    c.year())
                            .get("posting_seq"));
            assertEquals(
                    "APPROVED",
                    obj(read("maker", c, "detail", run).get("payable")).get("journalStatus"));
            assertEquals(
                    0L,
                    ((Number)
                                    db.one(
                                                    "SELECT count(*) n FROM plugin_finance_posting"
                                                            + " WHERE year_id=?",
                                                    c.year())
                                            .get("n"))
                            .longValue());
        } finally {
            db.update("DROP TRIGGER ap_test_item_fail ON plugin_finance_ap_open_item");
            db.update("DROP FUNCTION ap_test_item_fail()");
        }
        var result = write("maker", c, "post", run, n);
        assertEquals("POSTED", obj(obj(result.get("current")).get("payable")).get("journalStatus"));
        assertEquals(
                obj(result.get("commandReceipt")).get("id"),
                obj(write("maker", c, "post", run, n).get("commandReceipt")).get("id"));
    }

    @Test
    void trulyOverlappingTwoParentsShareIndependentAccountingQuota() throws Exception {
        var c = fixture("CAS");
        basis(c);
        var allocation =
                FinanceEvidenceUsagePostgresTest.allocRead("maker", c.expense(), c.allocation());
        var doc =
                FinanceEvidenceUsagePostgresTest.docRead(
                        "maker",
                        c.expense(),
                        id(obj(allocation.get("allocation")).get("documentId")));
        var second =
                FinanceEvidenceUsagePostgresTest.confirmed(
                        c.expense(), doc, "SECOND-CHAIN-" + UUID.randomUUID(), "300.00");
        var c2 =
                new Case(
                        c.expense(),
                        id(obj(second.get("allocation")).get("id")),
                        c.supplier(),
                        c.debit(),
                        c.credit());
        var one = prepare(c, createInput(c));
        var two = prepare(c2, createInput(c2));
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var pids = new ConcurrentLinkedQueue<Integer>();
        try (var lock = FinancePostgresTest.source.getConnection()) {
            lock.setAutoCommit(false);
            try (var statement =
                    lock.prepareStatement(
                            "SELECT id FROM plugin_finance_book WHERE id=? FOR UPDATE")) {
                statement.setObject(1, c.book());
                statement.executeQuery().close();
            }
            var futures = new ArrayList<Future<String>>();
            for (var d : List.of(one, two))
                futures.add(
                        pool.submit(
                                () -> {
                                    try {
                                        return FinanceJournalPersonPostgresTest.as(
                                                "maker",
                                                () ->
                                                        FinancePostgresTest.tx.execute(
                                                                tx -> {
                                                                    pids.add(
                                                                            ((Number)
                                                                                            db.one(
                                                                                                            "SELECT"
                                                                                                                + " pg_backend_pid()"
                                                                                                                + " pid")
                                                                                                    .get(
                                                                                                            "pid"))
                                                                                    .intValue());
                                                                    ready.countDown();
                                                                    step("maker", c, "submit", d);
                                                                    return "OK";
                                                                }));
                                    } catch (FinanceException denied) {
                                        return denied.code;
                                    }
                                }));
            assertTrue(ready.await(1, TimeUnit.SECONDS));
            var ids = pids.toArray(Integer[]::new);
            boolean overlapping = false;
            long deadline = System.nanoTime() + 800_000_000L;
            while (System.nanoTime() < deadline) {
                long waiting =
                        ((Number)
                                        db.one(
                                                        "SELECT count(*) n FROM pg_stat_activity"
                                                                + " WHERE pid IN(?,?) AND"
                                                                + " wait_event_type='Lock'",
                                                        ids[0],
                                                        ids[1])
                                                .get("n"))
                                .longValue();
                if (waiting == 2) {
                    overlapping = true;
                    break;
                }
                Thread.sleep(10);
            }
            lock.commit();
            assertTrue(
                    overlapping, "both real transactions must be observed waiting before release");
            var results = new ArrayList<String>();
            for (var future : futures) results.add(future.get(20, TimeUnit.SECONDS));
            assertEquals(1, Collections.frequency(results, "OK"));
            assertEquals(1, Collections.frequency(results, "AP_CAPACITY_EXCEEDED"));
            var amount =
                    new java.math.BigDecimal(
                            db.one(
                                            "SELECT coalesce(sum(amount),0)::text n FROM"
                                                    + " plugin_finance_ap_usage WHERE year_id=? AND"
                                                    + " state='RESERVED'",
                                            c.year())
                                    .get("n")
                                    .toString());
            assertTrue(
                    amount.signum() > 0 && amount.compareTo(new java.math.BigDecimal("800")) <= 0);
            System.out.println(
                    "AP QUOTA real overlappingBlocked=2 workers=2 results="
                            + results
                            + " reserved="
                            + amount);
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void expiredReservationAndDepartedMakerCanExitClosedMonthWithoutAutoRelease() throws Exception {
        var c = fixture("CAS");
        var d = prepare(c, createInput(c));
        var n = action(d);
        var now = ((java.sql.Timestamp) db.one("SELECT clock_timestamp() t").get("t")).toInstant();
        n.put("requestedExpiresAt", now.plusSeconds(2).toString());
        d = obj(write("maker", c, "submit", id(obj(d.get("payable")).get("id")), n).get("current"));
        var approve = action(d);
        approve.put("submissionId", obj(d.get("currentSubmission")).get("id").toString());
        approve.put("requestedExpiresAt", null);
        UUID run = id(obj(d.get("payable")).get("id"));
        code("SELF_APPROVAL_FORBIDDEN", () -> write("alias", c, "approve", run, approve));
        long deadline = System.nanoTime() + 5_000_000_000L;
        boolean expired = false;
        while (System.nanoTime() < deadline) {
            expired =
                    Boolean.TRUE.equals(
                            db.one(
                                            "SELECT expires_at<=clock_timestamp() expired FROM"
                                                + " plugin_finance_ap_submission WHERE run_id=?",
                                            run)
                                    .get("expired"));
            if (expired) break;
            Thread.sleep(20);
        }
        assertTrue(expired);
        code("AP_SUBMISSION_EXPIRED", () -> write("reviewer", c, "approve", run, approve));
        assertEquals(
                "RESERVED",
                db.one("SELECT state FROM plugin_finance_ap_usage WHERE run_id=?", run)
                        .get("state"));
        // Controlled period fixture, not a claim that the month-close workflow was executed.
        FinancePostgresTest.tx.executeWithoutResult(
                tx -> {
                    db.update(
                            "UPDATE plugin_finance_period SET status='CLOSED',version=version+1"
                                    + " WHERE year_id=? AND month=1",
                            c.year());
                    db.update(
                            "UPDATE plugin_finance_member SET enabled=false WHERE book_id=? AND"
                                    + " username='maker'",
                            c.book());
                });
        try {
            var current = read("reviewer", c, "detail", run);
            assertTrue(((List<?>) current.get("allowedActions")).contains("ADMIN_TERMINATE"));
            var cancelled = step("reviewer", c, "admin-terminate", current);
            assertEquals("CANCELLED", obj(cancelled.get("payable")).get("journalStatus"));
            assertEquals(
                    "RELEASED",
                    db.one("SELECT state FROM plugin_finance_ap_usage WHERE run_id=?", run)
                            .get("state"));
            assertEquals(
                    "OPEN",
                    db.one("SELECT status FROM plugin_finance_year WHERE id=?", c.year())
                            .get("status"));
        } finally {
            db.update(
                    "UPDATE plugin_finance_member SET enabled=true WHERE book_id=? AND"
                            + " username='maker'",
                    c.book());
        }
    }

    @Test
    void foreignHomeReadIsCurrentForCommandsButHistoricalSnapshotAndExitRemainReadable() {
        var initial = fixture("CAS");
        UUID home = FinanceCounterpartyPostgresTest.sameEntityBook(initial.expense().base());
        UUID homeYear =
                id(db.one("SELECT id FROM plugin_finance_year WHERE book_id=?", home).get("id"));
        FinancePostgresTest.write(
                "maker",
                home,
                homeYear,
                "members",
                null,
                map("username", "reviewer", "role", "REVIEWER", "enabled", true));
        var created =
                FinanceCounterpartyPostgresTest.write(
                        "maker",
                        home,
                        "create",
                        null,
                        null,
                        FinanceCounterpartyPostgresTest.create(
                                "FOREIGN-"
                                        + UUID.randomUUID().toString().toUpperCase(Locale.ROOT)));
        var supplier = id(obj(created.get("counterparty")).get("id"));
        FinanceCounterpartyPostgresTest.decide(
                "reviewer",
                home,
                "approve",
                FinanceCounterpartyPostgresTest.pending(home, created));
        var c =
                new Case(
                        initial.expense(),
                        initial.allocation(),
                        supplier,
                        initial.debit(),
                        initial.credit());
        var n = createInput(c);
        var d = prepare(c, n);
        UUID run = id(obj(d.get("payable")).get("id"));
        db.update(
                "UPDATE plugin_finance_member SET enabled=false WHERE book_id=? AND"
                        + " username='maker'",
                home);
        try {
            var historical = read("maker", c, "detail", run);
            assertNull(historical.get("currentSupplierStatus"));
            assertEquals(false, historical.get("sourceFresh"));
            assertFalse(((List<?>) historical.get("allowedActions")).contains("SUBMIT"));
            assertNotNull(obj(historical.get("content")).get("supplier"));
            code("SCOPE_DENIED", () -> write("maker", c, "create", null, n));
        } finally {
            db.update(
                    "UPDATE plugin_finance_member SET enabled=true WHERE book_id=? AND"
                            + " username='maker'",
                    home);
        }
        d = step("maker", c, "submit", d);
        var approve = action(d);
        approve.put("submissionId", obj(d.get("currentSubmission")).get("id").toString());
        approve.put("requestedExpiresAt", null);
        db.update(
                "UPDATE plugin_finance_member SET enabled=false WHERE book_id=? AND"
                        + " username='maker'",
                home);
        try {
            code("AP_APPROVAL_STALE", () -> write("reviewer", c, "approve", run, approve));
            db.update(
                    "UPDATE plugin_finance_member SET enabled=false WHERE book_id=? AND"
                            + " username='reviewer'",
                    home);
            var current = read("reviewer", c, "detail", run);
            assertNull(current.get("currentSupplierStatus"));
            var rejected = step("reviewer", c, "reject", current);
            assertEquals("DRAFT", obj(rejected.get("payable")).get("journalStatus"));
            assertEquals(
                    "RELEASED",
                    db.one("SELECT state FROM plugin_finance_ap_usage WHERE run_id=?", run)
                            .get("state"));
        } finally {
            db.update(
                    "UPDATE plugin_finance_member SET enabled=true WHERE book_id=? AND username"
                            + " IN('maker','reviewer')",
                    home);
        }
    }

    @Test
    void reviewedZeroIsNotUnknownAndRealBasisChangeRequiresFreshImmutableAttempt() {
        var c = fixture("CAS");
        var p = parent(c);
        assertEquals("UNKNOWN", obj(p.get("capacity")).get("qualification"));
        assertNull(obj(p.get("capacity")).get("capacity"));
        var b = basis(c);
        var current = obj(b.get("basis"));
        var doc = obj(b.get("content")).get("expectedDocument");
        var zero =
                write(
                        "maker",
                        c,
                        "basis-create",
                        null,
                        body(
                                "documentExpectation",
                                doc,
                                "expectedCurrentBasisId",
                                current.get("id").toString(),
                                "expectedCurrentBasisVersion",
                                current.get("version"),
                                "accountingCapacity",
                                "0.00",
                                "basisOriginalId",
                                c.expense().basis().toString(),
                                "recognitionEligibilityReason",
                                "独立核实本单暂不具备会计确认额度",
                                "organizationReviewedNotTaxVerified",
                                true));
        var z = obj(obj(zero.get("current")).get("basis"));
        write(
                "reviewer",
                c,
                "basis-approve",
                id(z.get("id")),
                body("expectedVersion", z.get("version"), "contentDigest", z.get("contentDigest")));
        var cap = obj(parent(c).get("capacity"));
        assertEquals("ORG_REVIEWED", cap.get("qualification"));
        assertEquals("0.00000000", cap.get("capacity"));
        var d = prepare(c, createInput(c));
        final var blocked = d;
        code("AP_CAPACITY_EXCEEDED", () -> step("maker", c, "submit", blocked));
        var historyRequest = new MockHttpServletRequest();
        historyRequest.addParameter("historyLimit", "1");
        UUID run = id(obj(d.get("payable")).get("id"));
        var page =
                FinanceJournalPersonPostgresTest.as(
                        "maker",
                        () ->
                                obj(
                                        service.read(
                                                "maker",
                                                c.book(),
                                                c.year(),
                                                "detail",
                                                run,
                                                historyRequest)));
        assertEquals(1, ((List<?>) obj(page.get("history")).get("items")).size());
        var zeroBasis =
                obj(
                        obj(write(
                                                "maker",
                                                c,
                                                "basis-create",
                                                null,
                                                body(
                                                        "documentExpectation",
                                                        doc,
                                                        "expectedCurrentBasisId",
                                                        z.get("id").toString(),
                                                        "expectedCurrentBasisVersion",
                                                        "2",
                                                        "accountingCapacity",
                                                        "800.00",
                                                        "basisOriginalId",
                                                        c.expense().basis().toString(),
                                                        "recognitionEligibilityReason",
                                                        "真实独立改版恢复会计容量",
                                                        "organizationReviewedNotTaxVerified",
                                                        true))
                                        .get("current"))
                                .get("basis"));
        write(
                "reviewer",
                c,
                "basis-approve",
                id(zeroBasis.get("id")),
                body(
                        "expectedVersion",
                        zeroBasis.get("version"),
                        "contentDigest",
                        zeroBasis.get("contentDigest")));
        code("AP_BASIS_STALE", () -> step("maker", c, "submit", blocked));
        var cancelled = step("maker", c, "cancel", d);
        assertEquals("CANCELLED", obj(cancelled.get("payable")).get("journalStatus"));
        var next = createInput(c);
        next.put("expectedPredecessorId", run.toString());
        next.put("expectedPredecessorVersion", obj(cancelled.get("payable")).get("version"));
        var posted = posted(c, prepare(c, next));
        assertEquals("COMMITTED", obj(posted.get("payable")).get("currentEffect"));
    }
}
