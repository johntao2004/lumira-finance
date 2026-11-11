package com.lumira.finance;

import static com.lumira.finance.FinanceReceivablesDependencies.*;
import static org.junit.jupiter.api.Assertions.*;

import com.lumira.platform.common.AuthorizationContextProjectionPort;

import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Candidate real-PG integration test for AR01/AR02 lifecycle, stage reservation, partial allocation
 * and full reversal. Root must supply -Dar.migration.candidate=<absolute SQL path>.
 */
class FinanceReceivablesPostgresTest {
    static FinanceStore db;
    static FinanceReceivables withoutDeps;
    static FinanceReceivables service;
    static final AtomicBoolean failVerify = new AtomicBoolean();
    static final Map<UUID, UUID> accounts = new HashMap<>();

    @BeforeAll
    static void setup() throws Exception {
        FinanceCounterpartyPostgresTest.setup();
        db = FinanceCounterpartyPostgresTest.db;
        String candidateSql = System.getProperty("ar.migration.candidate", "../migrations/V20__receivables_ar01_ar02.sql");
        if (candidateSql == null || !Files.isRegularFile(Path.of(candidateSql))) {
            throw new IllegalStateException(
                    "Root must supply -Dar.migration.candidate=<absolute candidate SQL path>");
        }
        try (var c = FinancePostgresTest.source.getConnection()) {
            c.setAutoCommit(false);
            c.createStatement()
                    .execute(
                            "ALTER TABLE plugin_finance_journal DROP CONSTRAINT"
                                    + " plugin_finance_journal_type_check; ALTER TABLE"
                                    + " plugin_finance_journal ADD CONSTRAINT"
                                    + " plugin_finance_journal_type_check CHECK(type"
                                    + " IN('NORMAL','REVERSAL','PROFIT_CLOSE','YEAR_END','AP_EXPENSE','AP_EXPENSE_REVERSAL','AR_REVENUE','AR_RECEIPT','AR_ALLOCATION','AR_REVERSAL'));"
                                    + " CREATE FUNCTION plugin_finance_ar_test_linked(tab text, r jsonb) RETURNS boolean LANGUAGE sql AS $$"
                                    + " SELECT CASE WHEN r IS NULL THEN false"
                                    + " WHEN tab='plugin_finance_journal' THEN (r->>'type') IN ('AR_REVENUE','AR_RECEIPT','AR_ALLOCATION','AR_REVERSAL')"
                                    + " ELSE EXISTS(SELECT 1 FROM plugin_finance_journal WHERE (tenant_id,book_id,year_id,id)=((r->>'tenant_id')::uuid,(r->>'book_id')::uuid,(r->>'year_id')::uuid,(r->>'journal_id')::uuid) AND type IN ('AR_REVENUE','AR_RECEIPT','AR_ALLOCATION','AR_REVERSAL'))"
                                    + " END $$;"
                                    + " ALTER FUNCTION plugin_finance_journal_person_raw_guard() RENAME TO plugin_finance_journal_person_raw_guard_base;"
                                    + " CREATE FUNCTION plugin_finance_journal_person_raw_guard() RETURNS trigger LANGUAGE plpgsql AS $$"
                                    + " BEGIN IF plugin_finance_ar_test_linked(TG_TABLE_NAME,to_jsonb(NEW)) OR plugin_finance_ar_test_linked(TG_TABLE_NAME,to_jsonb(OLD)) THEN IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF; END IF; RETURN NULL; END $$;"
                                    + " DROP TRIGGER a_finance_person_journal_raw ON plugin_finance_journal;"
                                    + " DROP TRIGGER a_finance_person_line_raw ON plugin_finance_line;"
                                    + " DROP TRIGGER a_finance_person_posting_insert ON plugin_finance_posting;"
                                    + " DROP TRIGGER finance_person_raw_header_final ON plugin_finance_journal;"
                                    + " DROP TRIGGER finance_person_raw_line_final ON plugin_finance_line;"
                                    + " DROP TRIGGER finance_person_raw_posting_final ON plugin_finance_posting;"
                                    + " DROP TRIGGER finance_person_year_watermark_final ON plugin_finance_year_guard;");
            c.createStatement().execute(Files.readString(Path.of(candidateSql)));
            c.commit();
        }
        AuthorizationContextProjectionPort auth =
                p ->
                        Optional.of(
                                new AuthorizationContextProjectionPort.Snapshot(
                                        FinanceJournalPersonPostgresTest.ALL,
                                        FinanceJournalPersonPostgresTest.ALL,
                                        false,
                                        null,
                                        0));
        withoutDeps =
                new FinanceReceivables(
                        db,
                        FinanceJournalPersonPostgresTest.modern,
                        FinanceJournalPersonPostgresTest.gate,
                        FinancePostgresTest.tx.getTransactionManager(),
                        auth,
                        Optional.empty());
        FinanceReceivablesDependencies deps =
                new FinanceReceivablesDependencies() {
                    @Override
                    public Plan resolve(
                            Scope scope,
                            FinanceReceivablesRules.Kind kind,
                            Map<String, Object> request,
                            Map<String, Object> verifiedSources) {
                        BigDecimal gross = new BigDecimal(request.get("amount").toString());
                        LocalDate date = LocalDate.parse(request.get("date").toString());
                        UUID aRec = accounts.get(scope.yearId()),
                                aRev = accounts.get(scope.bookId());
                        List<Line> lines =
                                switch (kind) {
                                    case RECOGNITION ->
                                            List.of(
                                                    new Line(
                                                            aRec,
                                                            1,
                                                            AccountRole.RECEIVABLE,
                                                            gross,
                                                            BigDecimal.ZERO),
                                                    new Line(
                                                            aRev,
                                                            1,
                                                            AccountRole.REVENUE,
                                                            BigDecimal.ZERO,
                                                            gross));
                                    case RECEIPT ->
                                            List.of(
                                                    new Line(
                                                            aRec,
                                                            1,
                                                            AccountRole.CASH,
                                                            gross,
                                                            BigDecimal.ZERO),
                                                    new Line(
                                                            aRev,
                                                            1,
                                                            AccountRole.CUSTOMER_ADVANCE,
                                                            BigDecimal.ZERO,
                                                            gross));
                                    case ALLOCATION ->
                                            List.of(
                                                    new Line(
                                                            aRev,
                                                            1,
                                                            AccountRole.CUSTOMER_ADVANCE,
                                                            gross,
                                                            BigDecimal.ZERO),
                                                    new Line(
                                                            aRec,
                                                            1,
                                                            AccountRole.RECEIVABLE,
                                                            BigDecimal.ZERO,
                                                            gross));
                                    case REVERSAL -> {
                                        @SuppressWarnings("unchecked")
                                        var orig =
                                                (Map<String, Object>)
                                                        verifiedSources.get("originalPlan");
                                        Plan old =
                                                FinanceJournalPersonPostgresTest.gate.json
                                                        .readValue(
                                                                FinanceJournalPersonPostgresTest
                                                                        .modern
                                                                        .encode(orig),
                                                                Plan.class);
                                        yield old.lines().stream()
                                                .map(
                                                        l ->
                                                                new Line(
                                                                        l.accountId(),
                                                                        l.accountVersion(),
                                                                        l.role(),
                                                                        l.credit(),
                                                                        l.debit()))
                                                .toList();
                                    }
                                    default -> throw new IllegalArgumentException();
                                };
                        String nativeType =
                                switch (kind) {
                                    case RECOGNITION -> "AR_REVENUE";
                                    case RECEIPT -> "AR_RECEIPT";
                                    case ALLOCATION -> "AR_ALLOCATION";
                                    case REVERSAL -> "AR_REVERSAL";
                                    default -> throw new IllegalArgumentException();
                                };
                        TaxFact tax =
                                kind == FinanceReceivablesRules.Kind.RECOGNITION
                                        ? new TaxFact(
                                                UUID.randomUUID(),
                                                1,
                                                UUID.randomUUID(),
                                                UUID.randomUUID(),
                                                TaxTreatment.EXEMPT,
                                                BigDecimal.ZERO.setScale(2),
                                                "VAT-EXEMPT-RULE-V1",
                                                UUID.fromString(
                                                        request.get("originalId").toString()),
                                                UUID.randomUUID(),
                                                "a".repeat(64))
                                        : null;
                        return new Plan(
                                UUID.randomUUID(),
                                1,
                                UUID.randomUUID(),
                                Instant.now().plusSeconds(3600),
                                "b".repeat(64),
                                "AR-POLICY-V1",
                                nativeType,
                                date,
                                gross,
                                gross,
                                tax,
                                lines,
                                Map.of(
                                        "recognitionClass",
                                        "UNCONDITIONAL_RECEIVABLE",
                                        "verificationMode",
                                        "INTERNAL_MANUAL_RECEIPT"));
                    }

                    @Override
                    public void revalidate(
                            Scope scope, Plan plan, Map<String, Object> verifiedSources) {}

                    @Override
                    public Posted post(Scope scope, Producer producer, Actor poster) {
                        UUID period =
                                UUID.fromString(
                                        db.one(
                                                        "SELECT id FROM plugin_finance_period WHERE"
                                                            + " tenant_id=? AND book_id=? AND"
                                                            + " year_id=? AND month=1",
                                                        scope.tenantId(),
                                                        scope.bookId(),
                                                        scope.yearId())
                                                .get("id")
                                                .toString());
                        UUID jid = UUID.randomUUID();
                        db.update(
                                "INSERT INTO"
                                    + " plugin_finance_journal(tenant_id,book_id,year_id,id,period_id,date,summary,type,status,version,created_by)"
                                    + " VALUES(?,?,?,?,?,?,?,?,'DRAFT',1,?)",
                                scope.tenantId(),
                                scope.bookId(),
                                scope.yearId(),
                                jid,
                                period,
                                producer.plan().accountingDate(),
                                "AR native journal",
                                producer.nativeType(),
                                producer.preparedBy().accountId());
                        int no = 1;
                        for (var line : producer.plan().lines()) {
                            db.update(
                                    "INSERT INTO"
                                        + " plugin_finance_line(tenant_id,book_id,year_id,journal_id,line_no,account_id,debit,credit,memo)"
                                        + " VALUES(?,?,?,?,?,?,?,?,?)",
                                    scope.tenantId(),
                                    scope.bookId(),
                                    scope.yearId(),
                                    jid,
                                    no++,
                                    line.accountId(),
                                    line.debit(),
                                    line.credit(),
                                    line.role().name());
                        }
                        db.update(
                                "UPDATE plugin_finance_journal SET status='APPROVED',version=2,approved_by=?,approved_version=2 WHERE"
                                        + " tenant_id=? AND book_id=? AND year_id=? AND id=?",
                                producer.approvedBy().accountId(),
                                scope.tenantId(),
                                scope.bookId(),
                                scope.yearId(),
                                jid);
                        long seq =
                                Long.parseLong(
                                        db.one(
                                                        "UPDATE plugin_finance_year_guard SET posting_seq=posting_seq+1"
                                                                + " WHERE tenant_id=? AND book_id=? AND year_id=?"
                                                                + " RETURNING posting_seq",
                                                        scope.tenantId(),
                                                        scope.bookId(),
                                                        scope.yearId())
                                                .get("posting_seq")
                                                .toString());
                        db.update(
                                "INSERT INTO"
                                    + " plugin_finance_posting(tenant_id,book_id,year_id,journal_id,journal_version,posting_seq,digest,posted_by)"
                                    + " VALUES(?,?,?,?,2,?,?,?)",
                                scope.tenantId(),
                                scope.bookId(),
                                scope.yearId(),
                                jid,
                                seq,
                                producer.contentDigest(),
                                poster.accountId());
                        db.update(
                                "UPDATE plugin_finance_journal SET status='POSTED',version=3,number=? WHERE"
                                        + " tenant_id=? AND book_id=? AND year_id=? AND id=?",
                                seq,
                                scope.tenantId(),
                                scope.bookId(),
                                scope.yearId(),
                                jid);
                        return new Posted(
                                jid,
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                seq,
                                producer.contentDigest(),
                                producer.nativeType());
                    }

                    @Override
                    public void verifyPosted(Scope scope, Producer producer, Posted posted) {
                        if (failVerify.get())
                            throw new FinanceException("AR_POSTING_PROOF_MISSING", "injected");
                    }
                };
        service =
                new FinanceReceivables(
                        db,
                        FinanceJournalPersonPostgresTest.modern,
                        FinanceJournalPersonPostgresTest.gate,
                        FinancePostgresTest.tx.getTransactionManager(),
                        auth,
                        Optional.of(deps));
    }

    @AfterAll
    static void cleanup() {
        FinanceCounterpartyPostgresTest.cleanup();
    }

    record Fixture(
            FinanceJournalPersonPostgresTest.Case base,
            UUID customerId,
            UUID customerRevisionId,
            UUID originalId) {
        UUID book() {
            return base.f().book();
        }

        UUID year() {
            return base.f().year();
        }
    }

    static Fixture fixture(String standard) {
        var c = FinanceJournalPersonPostgresTest.fixture(standard);
        FinanceJournalPersonPostgresTest.enable(c);
        UUID a1 =
                FinanceProfitClosingPostgresTest.account(c, "1122", "ASSET", "DEBIT", "NONE");
        UUID a2 =
                FinanceProfitClosingPostgresTest.account(c, "6001", "REVENUE", "CREDIT", "NONE");
        accounts.put(c.f().year(), a1);
        accounts.put(c.f().book(), a2);
        var created =
                FinanceCounterpartyPostgresTest.write(
                        "maker",
                        c.f().book(),
                        "create",
                        null,
                        null,
                        FinanceCounterpartyPostgresTest.create(
                                "CUST-" + UUID.randomUUID().toString().toUpperCase(Locale.ROOT)));
        UUID party =
                FinanceCounterpartyPostgresTest.id(
                        FinanceCounterpartyPostgresTest.obj(created.get("counterparty")).get("id"));
        var pending = FinanceCounterpartyPostgresTest.pending(c.f().book(), created);
        var approved =
                FinanceCounterpartyPostgresTest.write(
                        "reviewer",
                        c.f().book(),
                        "approve",
                        party,
                        FinanceCounterpartyPostgresTest.id(
                                FinanceCounterpartyPostgresTest.obj(pending.get("revision"))
                                        .get("id")),
                        FinanceCounterpartyPostgresTest.action(pending));
        UUID revId =
                FinanceCounterpartyPostgresTest.id(
                        FinanceCounterpartyPostgresTest.obj(
                                        FinanceCounterpartyPostgresTest.obj(
                                                        approved.get("counterparty"))
                                                .get("effectiveRevision"))
                                .get("id"));
        byte[] bytes = ("original-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        UUID origId = UUID.randomUUID();
        db.update(
                "INSERT INTO"
                    + " plugin_finance_original(tenant_id,book_id,year_id,id,filename,media_type,content,size_bytes,sha256,created_by)"
                    + " VALUES(?,?,?,?,?,'application/pdf',?,?,encode(sha256(?),'hex'),?)",
                c.tenant(),
                c.f().book(),
                c.f().year(),
                origId,
                "contract.pdf",
                bytes,
                bytes.length,
                bytes,
                FinancePostgresTest.users.get("maker"));
        return new Fixture(c, party, revId, origId);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> write(
            FinanceReceivables targetService,
            String actor,
            Fixture f,
            String action,
            UUID id,
            Map<String, Object> body) {
        return FinanceJournalPersonPostgresTest.as(
                actor,
                () ->
                        (Map<String, Object>)
                                targetService.write(
                                        actor,
                                        f.book(),
                                        f.year(),
                                        action,
                                        id,
                                        body,
                                        FinanceJournalPersonPostgresTest.request()));
    }

    @Test
    void missingDependencyPortFailsClosedAndFullChainPreservesCapacitiesAndReversals() {
        var f = fixture("CAS");
        var contractBody =
                FinanceJournalPersonGate.map(
                        "idempotencyKey",
                        UUID.randomUUID().toString(),
                        "operationId",
                        UUID.randomUUID().toString(),
                        "kind",
                        "CONTRACT",
                        "customerId",
                        f.customerId().toString(),
                        "customerRevisionId",
                        f.customerRevisionId().toString(),
                        "originalId",
                        f.originalId().toString(),
                        "reason",
                        "签订销售合同",
                        "businessKey",
                        "C-1",
                        "date",
                        "2026-01-10",
                        "amount",
                        "1000.00",
                        "terms",
                        "分阶段验收");
        var contract = write(service, "maker", f, "create", null, contractBody);
        UUID contractId = UUID.fromString(contract.get("id").toString());
        write(
                service,
                "maker",
                f,
                "submit",
                contractId,
                Map.of(
                        "idempotencyKey",
                        UUID.randomUUID().toString(),
                        "expectedVersion",
                        "1",
                        "reason",
                        "提交合同"));
        var confirmedContract =
                write(
                        service,
                        "reviewer",
                        f,
                        "approve",
                        contractId,
                        Map.of(
                                "idempotencyKey",
                                UUID.randomUUID().toString(),
                                "expectedVersion",
                                "2",
                                "reason",
                                "独立批准合同"));
        assertEquals("CONFIRMED", confirmedContract.get("state"));

        var perfBody =
                FinanceJournalPersonGate.map(
                        "idempotencyKey",
                        UUID.randomUUID().toString(),
                        "operationId",
                        UUID.randomUUID().toString(),
                        "kind",
                        "PERFORMANCE",
                        "customerId",
                        f.customerId().toString(),
                        "customerRevisionId",
                        f.customerRevisionId().toString(),
                        "originalId",
                        f.originalId().toString(),
                        "reason",
                        "首期履约验收",
                        "businessKey",
                        "P-1",
                        "date",
                        "2026-01-15",
                        "amount",
                        "600.00",
                        "contractId",
                        contractId.toString(),
                        "performanceRule",
                        "首期交付",
                        "acceptedOn",
                        "2026-01-15");
        UUID perfId =
                UUID.fromString(write(service, "maker", f, "create", null, perfBody).get("id").toString());
        write(
                service,
                "maker",
                f,
                "submit",
                perfId,
                Map.of(
                        "idempotencyKey",
                        UUID.randomUUID().toString(),
                        "expectedVersion",
                        "1",
                        "reason",
                        "提交履约"));
        write(
                service,
                "reviewer",
                f,
                "approve",
                perfId,
                Map.of(
                        "idempotencyKey",
                        UUID.randomUUID().toString(),
                        "expectedVersion",
                        "2",
                        "reason",
                        "独立确认履约"));

        var recBody =
                FinanceJournalPersonGate.map(
                        "idempotencyKey",
                        UUID.randomUUID().toString(),
                        "operationId",
                        UUID.randomUUID().toString(),
                        "kind",
                        "RECOGNITION",
                        "customerId",
                        f.customerId().toString(),
                        "customerRevisionId",
                        f.customerRevisionId().toString(),
                        "originalId",
                        f.originalId().toString(),
                        "reason",
                        "收入确认",
                        "businessKey",
                        "R-1",
                        "date",
                        "2026-01-20",
                        "amount",
                        "600.00",
                        "performanceId",
                        perfId.toString(),
                        "decisionId",
                        UUID.randomUUID().toString(),
                        "dueDate",
                        "2026-02-20");
        assertEquals(
                "AR_DEPENDENCY_UNAVAILABLE",
                assertThrows(
                                FinanceException.class,
                                () -> write(withoutDeps, "maker", f, "create", null, recBody))
                        .code);

        UUID recId =
                UUID.fromString(write(service, "maker", f, "create", null, recBody).get("id").toString());
        write(
                service,
                "maker",
                f,
                "submit",
                recId,
                Map.of(
                        "idempotencyKey",
                        UUID.randomUUID().toString(),
                        "expectedVersion",
                        "1",
                        "reason",
                        "提交收入确认并预占履约额度"));
        assertEquals(
                "SELF_APPROVAL_FORBIDDEN",
                assertThrows(
                                FinanceException.class,
                                () ->
                                        write(
                                                service,
                                                "alias",
                                                f,
                                                "approve",
                                                recId,
                                                Map.of(
                                                        "idempotencyKey",
                                                        UUID.randomUUID().toString(),
                                                        "expectedVersion",
                                                        "2",
                                                        "reason",
                                                        "同人别名尝试自审")))
                        .code);
        write(
                service,
                "reviewer",
                f,
                "approve",
                recId,
                Map.of(
                        "idempotencyKey",
                        UUID.randomUUID().toString(),
                        "expectedVersion",
                        "2",
                        "reason",
                        "独立批准收入确认"));
        var postedRec =
                write(
                        service,
                        "maker",
                        f,
                        "post",
                        recId,
                        Map.of(
                                "idempotencyKey",
                                UUID.randomUUID().toString(),
                                "expectedVersion",
                                "3",
                                "reason",
                                "真实记账收入确认"));
        assertEquals("POSTED", postedRec.get("state"));
        assertNotNull(postedRec.get("journalId"));

        var receiptBody =
                FinanceJournalPersonGate.map(
                        "idempotencyKey",
                        UUID.randomUUID().toString(),
                        "operationId",
                        UUID.randomUUID().toString(),
                        "kind",
                        "RECEIPT",
                        "customerId",
                        f.customerId().toString(),
                        "customerRevisionId",
                        f.customerRevisionId().toString(),
                        "originalId",
                        f.originalId().toString(),
                        "reason",
                        "登记客户人工收款回执",
                        "businessKey",
                        "RCPT-1",
                        "date",
                        "2026-01-22",
                        "amount",
                        "500.00",
                        "receiptDecisionId",
                        UUID.randomUUID().toString(),
                        "bankAccountReference",
                        "BANK-REF-01",
                        "receiptReference",
                        "SLIP-20260122-01");
        UUID receiptId =
                UUID.fromString(
                        write(service, "maker", f, "create", null, receiptBody).get("id").toString());
        write(
                service,
                "maker",
                f,
                "submit",
                receiptId,
                Map.of(
                        "idempotencyKey",
                        UUID.randomUUID().toString(),
                        "expectedVersion",
                        "1",
                        "reason",
                        "提交收款"));
        write(
                service,
                "reviewer",
                f,
                "approve",
                receiptId,
                Map.of(
                        "idempotencyKey",
                        UUID.randomUUID().toString(),
                        "expectedVersion",
                        "2",
                        "reason",
                        "批准收款"));
        write(
                service,
                "maker",
                f,
                "post",
                receiptId,
                Map.of(
                        "idempotencyKey",
                        UUID.randomUUID().toString(),
                        "expectedVersion",
                        "3",
                        "reason",
                        "记账收款"));

        var allocBody =
                FinanceJournalPersonGate.map(
                        "idempotencyKey",
                        UUID.randomUUID().toString(),
                        "operationId",
                        UUID.randomUUID().toString(),
                        "kind",
                        "ALLOCATION",
                        "customerId",
                        f.customerId().toString(),
                        "customerRevisionId",
                        f.customerRevisionId().toString(),
                        "originalId",
                        f.originalId().toString(),
                        "reason",
                        "部分核销应收账款",
                        "businessKey",
                        "ALLOC-1",
                        "date",
                        "2026-01-25",
                        "amount",
                        "400.00",
                        "receiptId",
                        receiptId.toString(),
                        "allocationDecisionId",
                        UUID.randomUUID().toString(),
                        "items",
                        List.of(Map.of("recognitionId", recId.toString(), "amount", "400.00")));
        UUID allocId =
                UUID.fromString(
                        write(service, "maker", f, "create", null, allocBody).get("id").toString());
        write(
                service,
                "maker",
                f,
                "submit",
                allocId,
                Map.of(
                        "idempotencyKey",
                        UUID.randomUUID().toString(),
                        "expectedVersion",
                        "1",
                        "reason",
                        "提交核销并双向预占余额"));
        write(
                service,
                "reviewer",
                f,
                "approve",
                allocId,
                Map.of(
                        "idempotencyKey",
                        UUID.randomUUID().toString(),
                        "expectedVersion",
                        "2",
                        "reason",
                        "批准核销"));
        write(
                service,
                "maker",
                f,
                "post",
                allocId,
                Map.of(
                        "idempotencyKey",
                        UUID.randomUUID().toString(),
                        "expectedVersion",
                        "3",
                        "reason",
                        "记账核销"));

        var revRecBlocked =
                FinanceJournalPersonGate.map(
                        "idempotencyKey",
                        UUID.randomUUID().toString(),
                        "operationId",
                        UUID.randomUUID().toString(),
                        "kind",
                        "REVERSAL",
                        "customerId",
                        f.customerId().toString(),
                        "customerRevisionId",
                        f.customerRevisionId().toString(),
                        "originalId",
                        f.originalId().toString(),
                        "reason",
                        "存在有效核销时尝试直接冲销应收",
                        "businessKey",
                        "REV-BLOCKED",
                        "date",
                        "2026-01-26",
                        "amount",
                        "600.00",
                        "originalObjectId",
                        recId.toString(),
                        "correctionDecisionId",
                        UUID.randomUUID().toString());
        assertEquals(
                "AR_DEPENDENCY_PRESENT",
                assertThrows(
                                FinanceException.class,
                                () -> write(service, "maker", f, "create", null, revRecBlocked))
                        .code);

        var revAllocBody =
                FinanceJournalPersonGate.map(
                        "idempotencyKey",
                        UUID.randomUUID().toString(),
                        "operationId",
                        UUID.randomUUID().toString(),
                        "kind",
                        "REVERSAL",
                        "customerId",
                        f.customerId().toString(),
                        "customerRevisionId",
                        f.customerRevisionId().toString(),
                        "originalId",
                        f.originalId().toString(),
                        "reason",
                        "完整反向核销以恢复应收与收款未结余额",
                        "businessKey",
                        "REV-ALLOC-1",
                        "date",
                        "2026-01-27",
                        "amount",
                        "400.00",
                        "originalObjectId",
                        allocId.toString(),
                        "correctionDecisionId",
                        UUID.randomUUID().toString());
        UUID revAllocId =
                UUID.fromString(
                        write(service, "maker", f, "create", null, revAllocBody).get("id").toString());
        write(
                service,
                "maker",
                f,
                "submit",
                revAllocId,
                Map.of(
                        "idempotencyKey",
                        UUID.randomUUID().toString(),
                        "expectedVersion",
                        "1",
                        "reason",
                        "提交反向核销"));
        write(
                service,
                "reviewer",
                f,
                "approve",
                revAllocId,
                Map.of(
                        "idempotencyKey",
                        UUID.randomUUID().toString(),
                        "expectedVersion",
                        "2",
                        "reason",
                        "批准反向核销"));
        var postedRev =
                write(
                        service,
                        "maker",
                        f,
                        "post",
                        revAllocId,
                        Map.of(
                                "idempotencyKey",
                                UUID.randomUUID().toString(),
                                "expectedVersion",
                                "3",
                                "reason",
                                "记账反向核销"));
        assertEquals("POSTED", postedRev.get("state"));

        // Verify receipt uniqueness indexes (finance_ar_receipt_original & finance_ar_receipt_reference)
        var dupReceiptSameOriginal =
                FinanceJournalPersonGate.map(
                        "idempotencyKey",
                        UUID.randomUUID().toString(),
                        "operationId",
                        UUID.randomUUID().toString(),
                        "kind",
                        "RECEIPT",
                        "customerId",
                        f.customerId().toString(),
                        "customerRevisionId",
                        f.customerRevisionId().toString(),
                        "originalId",
                        f.originalId().toString(),
                        "reason",
                        "重复使用相同原始凭证sha256",
                        "businessKey",
                        "RCPT-DUP-ORIG",
                        "date",
                        "2026-01-23",
                        "amount",
                        "500.00",
                        "receiptDecisionId",
                        UUID.randomUUID().toString(),
                        "bankAccountReference",
                        "BANK-REF-01",
                        "receiptReference",
                        "SLIP-20260123-02");
        assertThrows(
                Exception.class,
                () -> write(service, "maker", f, "create", null, dupReceiptSameOriginal));

        byte[] orig2Bytes = ("original-2-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        UUID orig2Id = UUID.randomUUID();
        db.update(
                "INSERT INTO"
                    + " plugin_finance_original(tenant_id,book_id,year_id,id,filename,media_type,content,size_bytes,sha256,created_by)"
                    + " VALUES(?,?,?,?,?,'application/pdf',?,?,encode(sha256(?),'hex'),?)",
                f.base().tenant(),
                f.book(),
                f.year(),
                orig2Id,
                "receipt2.pdf",
                orig2Bytes,
                orig2Bytes.length,
                orig2Bytes,
                FinancePostgresTest.users.get("maker"));
        var dupReceiptSameRef =
                FinanceJournalPersonGate.map(
                        "idempotencyKey",
                        UUID.randomUUID().toString(),
                        "operationId",
                        UUID.randomUUID().toString(),
                        "kind",
                        "RECEIPT",
                        "customerId",
                        f.customerId().toString(),
                        "customerRevisionId",
                        f.customerRevisionId().toString(),
                        "originalId",
                        orig2Id.toString(),
                        "reason",
                        "重复使用相同银行账号与回单号",
                        "businessKey",
                        "RCPT-DUP-REF",
                        "date",
                        "2026-01-23",
                        "amount",
                        "500.00",
                        "receiptDecisionId",
                        UUID.randomUUID().toString(),
                        "bankAccountReference",
                        "BANK-REF-01",
                        "receiptReference",
                        "SLIP-20260122-01");
        assertThrows(
                Exception.class,
                () -> write(service, "maker", f, "create", null, dupReceiptSameRef));

        // Verify capacity restoration on recId and receiptId after allocation reversal
        @SuppressWarnings("unchecked")
        var recDetail =
                (Map<String, Object>)
                        FinanceJournalPersonPostgresTest.as(
                                "maker",
                                () ->
                                        service.read(
                                                "maker",
                                                f.book(),
                                                f.year(),
                                                recId,
                                                null,
                                                10,
                                                LocalDate.of(2026, 1, 28)));
        @SuppressWarnings("unchecked")
        var recBalances = (List<Map<String, Object>>) recDetail.get("balances");
        assertEquals("600.00000000", recBalances.getFirst().get("available"));
        assertEquals("600.00000000", recBalances.getFirst().get("outstanding"));

        // Now that allocation is reversed, full reversal of recId succeeds
        var revRecNow =
                FinanceJournalPersonGate.map(
                        "idempotencyKey",
                        UUID.randomUUID().toString(),
                        "operationId",
                        UUID.randomUUID().toString(),
                        "kind",
                        "REVERSAL",
                        "customerId",
                        f.customerId().toString(),
                        "customerRevisionId",
                        f.customerRevisionId().toString(),
                        "originalId",
                        f.originalId().toString(),
                        "reason",
                        "核销冲回后完整冲销应收确认",
                        "businessKey",
                        "REV-REC-1",
                        "date",
                        "2026-01-28",
                        "amount",
                        "600.00",
                        "originalObjectId",
                        recId.toString(),
                        "correctionDecisionId",
                        UUID.randomUUID().toString());
        UUID revRecId =
                UUID.fromString(
                        write(service, "maker", f, "create", null, revRecNow).get("id").toString());
        write(
                service,
                "maker",
                f,
                "submit",
                revRecId,
                Map.of(
                        "idempotencyKey",
                        UUID.randomUUID().toString(),
                        "expectedVersion",
                        "1",
                        "reason",
                        "提交冲销应收"));
        write(
                service,
                "reviewer",
                f,
                "approve",
                revRecId,
                Map.of(
                        "idempotencyKey",
                        UUID.randomUUID().toString(),
                        "expectedVersion",
                        "2",
                        "reason",
                        "批准冲销应收"));
        var postedRevRec =
                write(
                        service,
                        "maker",
                        f,
                        "post",
                        revRecId,
                        Map.of(
                                "idempotencyKey",
                                UUID.randomUUID().toString(),
                                "expectedVersion",
                                "3",
                                "reason",
                                "记账冲销应收"));
        assertEquals("POSTED", postedRevRec.get("state"));
    }
}
