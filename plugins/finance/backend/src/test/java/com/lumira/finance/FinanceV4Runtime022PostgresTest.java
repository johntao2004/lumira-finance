package com.lumira.finance;

import static org.junit.jupiter.api.Assertions.*;

import com.lumira.finance.domain.ReportSourceEngine.Kind;
import com.lumira.finance.domain.ReportSourceEngine.Semantic;
import com.lumira.finance.tax.TaxObligationModel;
import com.lumira.platform.common.AuthorizationContextProjectionPort;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

/**
 * End-to-end PostgreSQL 18 integration test for V24 (0.22.0):
 * 1. TaxObligations (TAX01/TAX05/TAX06): book binding -> approve -> build -> review -> approve ->
 *    handoff UNKNOWN -> 3rd-person reconcileReceipt ACCEPTED + global receipt uniqueness.
 * 2. ArchiveExports (ARC01/ARC03): ARCHIVE blocked by statutory report rule pack -> DIAGNOSTIC
 *    preflight -> request -> approve -> generate ustar .tar -> verify-archive.mjs cross-language
 *    verification -> receipt DELIVERED.
 * 3. ReportDrafts (RP01/RPT02-05): semantic mapping -> approve -> evaluate against real POSTED
 *    journals -> BLOCKED_DRAFT with exact raw ledger balances while formal /reports stays gated.
 * 4. FinanceStrictJson closed-schema & zero-coercion enforcement on Assets & Payroll controllers.
 */
class FinanceV4Runtime022PostgresTest {
    static FinanceStore db;
    static FinanceTaxEntities taxEntities;
    static FinanceTaxObligations taxObligations;
    static FinanceArchiveExports archiveExports;
    static FinanceReportDrafts reportDrafts;
    static FinanceAssets assets;
    static FinancePayroll payroll;
    static FinancePayrollController payrollController;

    static final Set<String> ALL_WITH_EXPORT;

    static {
        var s = new HashSet<>(FinanceJournalPersonPostgresTest.ALL);
        s.add("platform:finance:export");
        ALL_WITH_EXPORT = Set.copyOf(s);
    }

    static final AuthorizationContextProjectionPort AUTH =
            p ->
                    Optional.of(
                            new AuthorizationContextProjectionPort.Snapshot(
                                    ALL_WITH_EXPORT, ALL_WITH_EXPORT, false, null, 0));

    @BeforeAll
    static void setup() throws Exception {
        FinanceJournalPersonPostgresTest.setup(24);
        db = FinanceJournalPersonPostgresTest.db;
        FinanceEvidenceUsagePostgresTest.db = db;
        FinanceTaxEntityPostgresTest.db = db;

        var txMgr = FinancePostgresTest.tx.getTransactionManager();
        var modern = FinanceJournalPersonPostgresTest.modern;
        var gate = FinanceJournalPersonPostgresTest.gate;

        taxEntities = new FinanceTaxEntities(db, modern, gate, txMgr, AUTH);
        FinanceTaxEntityPostgresTest.service = taxEntities;

        taxObligations = new FinanceTaxObligations(db, modern, gate, txMgr, AUTH);
        archiveExports =
                new FinanceArchiveExports(
                        db, modern, gate, txMgr, AUTH, FinanceJournalPersonPostgresTest.permissions);
        reportDrafts = new FinanceReportDrafts(db, modern, gate, txMgr, AUTH);
        var assetProducer = new FinanceAssetsProducer(db, modern, gate, txMgr, AUTH);
        assets =
                new FinanceAssets(
                        db,
                        modern,
                        gate,
                        txMgr,
                        AUTH,
                        Optional.of(FinancePostgresTest.source),
                        Optional.of(assetProducer),
                        Optional.of(assetProducer));
        payroll = new FinancePayroll(db, modern, gate, txMgr, AUTH, Optional.empty());
        payrollController = new FinancePayrollController(payroll);
    }

    @AfterAll
    static void cleanup() {
        FinanceJournalPersonPostgresTest.cleanup();
    }

    @BeforeEach
    void reset() {
        FinanceJournalPersonPostgresTest.denied.clear();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Object x) {
        return (Map<String, Object>) x;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(Object x) {
        return (List<Map<String, Object>>) x;
    }

    static UUID id(Object x) {
        return UUID.fromString(x.toString());
    }

    @Test
    void taxObligationsFullLifecycleThreePersonSeparationAndReceiptUniqueness() {
        var c = FinanceJournalPersonPostgresTest.fixture("CAS");
        FinanceJournalPersonPostgresTest.enable(c);
        UUID book = c.f().book(), year = c.f().year();

        var taxEnt = FinanceV4ProducersPostgresTest.createApprovedTaxEntity(c);
        UUID taxEntityId = taxEnt.get("id");
        UUID receiptOrig = FinanceV4ProducersPostgresTest.uploadAs("plain", c, "sta-vat-receipt");

        // 1. Propose & independently approve book binding
        var binding =
                FinanceJournalPersonPostgresTest.as(
                        "maker",
                        () ->
                                obj(
                                        taxObligations.proposeBinding(
                                                "maker",
                                                book,
                                                new FinanceTaxObligations.BookBindingInput(
                                                        taxEntityId,
                                                        "STATUTORY_SOURCE",
                                                        "VAT-MAP-2026.1",
                                                        LocalDate.of(2026, 1, 1),
                                                        "bind-" + UUID.randomUUID()),
                                                FinanceJournalPersonPostgresTest.request())));
        assertEquals("PENDING", binding.get("status"));

        assertThrows(
                FinanceException.class,
                () ->
                        FinanceJournalPersonPostgresTest.as(
                                "alias",
                                () ->
                                        taxObligations.approveBinding(
                                                "alias",
                                                book,
                                                taxEntityId,
                                                new FinanceTaxObligations.ApproveBindingInput(
                                                        1L, "bind-self-" + UUID.randomUUID()),
                                                FinanceJournalPersonPostgresTest.request())));

        var approvedBind =
                FinanceJournalPersonPostgresTest.as(
                        "reviewer",
                        () ->
                                obj(
                                        taxObligations.approveBinding(
                                                "reviewer",
                                                book,
                                                taxEntityId,
                                                new FinanceTaxObligations.ApproveBindingInput(
                                                        1L, "bind-app-" + UUID.randomUUID()),
                                                FinanceJournalPersonPostgresTest.request())));
        assertEquals("APPROVED", approvedBind.get("status"));

        // 2. Build INITIAL VAT obligation (maker = person 1)
        String wsDigest = FinanceService.hash("vat-2026-01-worksheet");
        var built =
                FinanceJournalPersonPostgresTest.as(
                        "maker",
                        () ->
                                obj(
                                        taxObligations.build(
                                                "maker",
                                                book,
                                                new FinanceTaxObligations.BuildObligationInput(
                                                        taxEntityId,
                                                        "CN_STA",
                                                        "VAT_GENERAL",
                                                        TaxObligationModel.Role.SELF,
                                                        LocalDate.of(2026, 1, 1),
                                                        LocalDate.of(2026, 1, 31),
                                                        "PERIODIC",
                                                        "CN_VAT_MONTHLY",
                                                        TaxObligationModel.Kind.INITIAL,
                                                        null,
                                                        UUID.randomUUID(),
                                                        List.of(UUID.randomUUID()),
                                                        List.of(),
                                                        wsDigest,
                                                        List.of(
                                                                new FinanceTaxObligations.SourceRefInput(
                                                                        book, year)),
                                                        "2026年1月增值税申报底稿构建",
                                                        "tax-build-" + UUID.randomUUID()),
                                                FinanceJournalPersonPostgresTest.request())));
        UUID obId = id(built.get("obligationId"));
        UUID declId = id(obj(built.get("declaration")).get("id"));
        assertEquals("NOT_VERIFIED", obj(built.get("declaration")).get("officialCapability"));
        assertEquals("NOT_ASSESSED", obj(built.get("declaration")).get("paymentState"));

        // 3. Review -> Approve (reviewer = person 2) -> Handoff UNKNOWN
        FinanceJournalPersonPostgresTest.as(
                "maker",
                () ->
                        taxObligations.transition(
                                "maker",
                                book,
                                obId,
                                declId,
                                "review",
                                new FinanceTaxObligations.TransitionInput(
                                        1L, "提交复核", "tax-rev-" + UUID.randomUUID()),
                                FinanceJournalPersonPostgresTest.request()));

        FinanceJournalPersonPostgresTest.as(
                "reviewer",
                () ->
                        taxObligations.transition(
                                "reviewer",
                                book,
                                obId,
                                declId,
                                "approve",
                                new FinanceTaxObligations.TransitionInput(
                                        2L, "独立批准底稿", "tax-app-" + UUID.randomUUID()),
                                FinanceJournalPersonPostgresTest.request()));

        var unknown =
                FinanceJournalPersonPostgresTest.as(
                        "other",
                        () ->
                                obj(
                                        taxObligations.transition(
                                                "other",
                                                book,
                                                obId,
                                                declId,
                                                "handoff",
                                                new FinanceTaxObligations.TransitionInput(
                                                        3L,
                                                        "转入线下人工报税交接槽位",
                                                        "tax-ho-" + UUID.randomUUID()),
                                                FinanceJournalPersonPostgresTest.request())));
        assertEquals("UNKNOWN", obj(unknown.get("declaration")).get("state"));

        // Preparer ("maker") and approver ("reviewer") MUST be blocked from reconciling receipt!
        String receiptRef = "STA-202601-" + UUID.randomUUID();
        for (String forbiddenActor : List.of("maker", "reviewer")) {
            var sodErr =
                    assertThrows(
                            FinanceException.class,
                            () ->
                                    FinanceJournalPersonPostgresTest.as(
                                            forbiddenActor,
                                            () ->
                                                    taxObligations.reconcileReceipt(
                                                            forbiddenActor,
                                                            book,
                                                            obId,
                                                            declId,
                                                            new FinanceTaxObligations.ReconcileReceiptInput(
                                                                    4L,
                                                                    year,
                                                                    receiptOrig,
                                                                    receiptRef,
                                                                    TaxObligationModel.Outcome.ACCEPTED,
                                                                    "尝试同人核销",
                                                                    "tax-rcpt-bad-" + UUID.randomUUID()),
                                                            FinanceJournalPersonPostgresTest.request())));
            assertEquals("SELF_APPROVAL_FORBIDDEN", sodErr.code);
        }

        // Independent 3rd natural person ("other") reconciles receipt -> ACCEPTED!
        var reconciled =
                FinanceJournalPersonPostgresTest.as(
                        "other",
                        () ->
                                obj(
                                        taxObligations.reconcileReceipt(
                                                "other",
                                                book,
                                                obId,
                                                declId,
                                                new FinanceTaxObligations.ReconcileReceiptInput(
                                                        4L,
                                                        year,
                                                        receiptOrig,
                                                        receiptRef,
                                                        TaxObligationModel.Outcome.ACCEPTED,
                                                        "第三独立自然人核验税务受理回执原件",
                                                        "tax-rcpt-ok-" + UUID.randomUUID()),
                                                FinanceJournalPersonPostgresTest.request())));
        assertEquals("ACCEPTED", obj(reconciled.get("declaration")).get("state"));
        assertEquals(declId.toString(), reconciled.get("acceptedDeclarationId"));
        assertEquals("REQUIRED", reconciled.get("obligationStatus"));
    }

    @Test
    void archiveExportsDiagnosticUstarPackagePassesNodeVerifyArchiveCli() throws Exception {
        var c = FinanceJournalPersonPostgresTest.fixture("CAS");
        FinanceJournalPersonPostgresTest.enable(c);
        UUID book = c.f().book(), year = c.f().year();

        FinanceV4ProducersPostgresTest.uploadAs("maker", c, "voucher-attachment");
        FinanceV4ProducersPostgresTest.postNormalAccrualJournal(c, "5800.00");

        // 1. Formal ARCHIVE preflight reports REPORTS_STATUTORY_RULE_PACK_UNAVAILABLE blocker
        var archivePf =
                FinanceJournalPersonPostgresTest.as(
                        "maker",
                        () ->
                                obj(
                                        archiveExports.preflight(
                                                "maker",
                                                book,
                                                new FinanceArchiveExports.PreflightInput(
                                                        List.of(year), "ARCHIVE"),
                                                FinanceJournalPersonPostgresTest.request())));
        var archiveBlockers = (List<?>) archivePf.get("blockers");
        assertTrue(archiveBlockers.contains("REPORTS_STATUTORY_RULE_PACK_UNAVAILABLE"));

        // 2. DIAGNOSTIC preflight -> createRequest -> independent approve -> generate .tar
        var diagPf =
                FinanceJournalPersonPostgresTest.as(
                        "maker",
                        () ->
                                obj(
                                        archiveExports.preflight(
                                                "maker",
                                                book,
                                                new FinanceArchiveExports.PreflightInput(
                                                        List.of(year), "DIAGNOSTIC"),
                                                FinanceJournalPersonPostgresTest.request())));
        UUID pfId = id(diagPf.get("preflightId"));
        String proposalDigest = diagPf.get("proposalDigest").toString();

        var createdReq =
                FinanceJournalPersonPostgresTest.as(
                        "maker",
                        () ->
                                obj(
                                        archiveExports.createRequest(
                                                "maker",
                                                book,
                                                new FinanceArchiveExports.CreateRequestInput(
                                                        pfId,
                                                        proposalDigest,
                                                        "arc-req-" + UUID.randomUUID()),
                                                FinanceJournalPersonPostgresTest.request())));
        UUID reqId = id(createdReq.get("requestId"));

        // Self-approval by alias must fail
        assertThrows(
                FinanceException.class,
                () ->
                        FinanceJournalPersonPostgresTest.as(
                                "alias",
                                () ->
                                        archiveExports.approve(
                                                "alias",
                                                book,
                                                reqId,
                                                new FinanceArchiveExports.ApproveInput(
                                                        1L,
                                                        proposalDigest,
                                                        "自批阻断",
                                                        "arc-self-" + UUID.randomUUID()),
                                                FinanceJournalPersonPostgresTest.request())));

        FinanceJournalPersonPostgresTest.as(
                "reviewer",
                () ->
                        archiveExports.approve(
                                "reviewer",
                                book,
                                reqId,
                                new FinanceArchiveExports.ApproveInput(
                                        1L,
                                        proposalDigest,
                                        "独立自然人批准诊断导出包",
                                        "arc-app-" + UUID.randomUUID()),
                                FinanceJournalPersonPostgresTest.request()));

        var generated =
                FinanceJournalPersonPostgresTest.as(
                        "other",
                        () ->
                                obj(
                                        archiveExports.generate(
                                                "other",
                                                book,
                                                reqId,
                                                new FinanceArchiveExports.GenerateInput(
                                                        2L, "arc-gen-" + UUID.randomUUID()),
                                                FinanceJournalPersonPostgresTest.request())));
        assertEquals("READY", generated.get("state"));
        assertEquals(Boolean.FALSE, generated.get("legalArchiveComplete"));
        var artifact = obj(generated.get("artifact"));
        String manifestSha = artifact.get("manifestSha256").toString();
        String artifactSha = artifact.get("sha256").toString();

        var dl =
                FinanceJournalPersonPostgresTest.as(
                        "maker", () -> archiveExports.download("maker", book, reqId));
        assertEquals(artifactSha, FinanceArchiveUstar.sha256Hex(dl.bytes()));

        // Cross-verify the generated POSIX ustar .tar using plugins/finance/archive/verify-archive.mjs!
        Path tmpTar = Files.createTempFile("lumira-v4-archive-", ".tar");
        try {
            Files.write(tmpTar, dl.bytes());
            Path cli = Path.of("../archive/verify-archive.mjs").toAbsolutePath().normalize();
            String nodeBin =
                    Files.exists(Path.of("/opt/lumira-tools/node-v24.11.0-linux-x64/bin/node"))
                            ? "/opt/lumira-tools/node-v24.11.0-linux-x64/bin/node"
                            : "node";
            Process proc =
                    new ProcessBuilder(
                                    nodeBin,
                                    cli.toString(),
                                    tmpTar.toString(),
                                    manifestSha)
                            .redirectErrorStream(true)
                            .start();
            String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = proc.waitFor();
            assertEquals(0, code, () -> "verify-archive.mjs failed: " + out);
            assertTrue(out.contains("\"status\": \"PACKAGE_INTEGRITY_VERIFIED\""), out);
            assertTrue(out.contains("\"legalArchiveComplete\": false"), out);

            String reportSha = FinanceService.hash(out);
            var delivered =
                    FinanceJournalPersonPostgresTest.as(
                            "maker",
                            () ->
                                    obj(
                                            archiveExports.receipt(
                                                    "maker",
                                                    book,
                                                    reqId,
                                                    new FinanceArchiveExports.ReceiptInput(
                                                            3L,
                                                            artifactSha,
                                                            manifestSha,
                                                            reportSha,
                                                            "verify-archive.mjs CLI 完整性复核通过",
                                                            "arc-rcpt-" + UUID.randomUUID()),
                                                    FinanceJournalPersonPostgresTest.request())));
            assertEquals("DELIVERED", delivered.get("state"));
        } finally {
            Files.deleteIfExists(tmpTar);
        }
    }

    @Test
    void reportDraftsEvaluatePostedLedgerBalancesWhileKeepingStatutoryPublicationBlocked() {
        var c = FinanceJournalPersonPostgresTest.fixture("CAS");
        FinanceJournalPersonPostgresTest.enable(c);
        UUID book = c.f().book(), year = c.f().year();

        FinanceV4ProducersPostgresTest.postNormalAccrualJournal(c, "3200.00");

        var createdMap =
                FinanceJournalPersonPostgresTest.as(
                        "maker",
                        () ->
                                obj(
                                        reportDrafts.createMapping(
                                                "maker",
                                                book,
                                                year,
                                                new FinanceReportDrafts.CreateMappingInput(
                                                        "CAS-DRAFT-2026.1",
                                                        List.of(
                                                                new FinanceReportDrafts.AccountMappingItem(
                                                                        c.f().cash(),
                                                                        "BS",
                                                                        Semantic.CASH),
                                                                new FinanceReportDrafts.AccountMappingItem(
                                                                        c.f().capital(),
                                                                        "BS",
                                                                        Semantic.CAPITAL)),
                                                        List.of(
                                                                new FinanceReportDrafts.CertifiedRowItem(
                                                                        Kind.BS, "A01")),
                                                        "登记2026年三表底稿科目映射"),
                                                FinanceJournalPersonPostgresTest.request())));
        UUID mappingId = id(createdMap.get("id"));
        assertEquals(Boolean.FALSE, createdMap.get("templateApproved"));

        FinanceJournalPersonPostgresTest.as(
                "reviewer",
                () ->
                        reportDrafts.approveMapping(
                                "reviewer",
                                book,
                                year,
                                mappingId,
                                new FinanceReportDrafts.ApproveMappingInput(1L, "独立批准报表底稿取数映射"),
                                FinanceJournalPersonPostgresTest.request()));

        var evaluated =
                FinanceJournalPersonPostgresTest.as(
                        "maker",
                        () ->
                                obj(
                                        reportDrafts.evaluate(
                                                "maker",
                                                book,
                                                year,
                                                new FinanceReportDrafts.EvaluateDraftInput(
                                                        mappingId,
                                                        LocalDate.of(2026, 1, 1),
                                                        LocalDate.of(2026, 1, 31)))));
        assertEquals("BLOCKED_DRAFT", evaluated.get("status"));
        var gaps = (List<?>) evaluated.get("publicationGaps");
        assertFalse(gaps.isEmpty());

        var sheets = list(evaluated.get("sheets"));
        assertEquals(3, sheets.size());
        var bsCells = list(sheets.get(0).get("cells"));
        var cashCell =
                bsCells.stream()
                        .filter(cell -> "A01".equals(cell.get("rowId")))
                        .findFirst()
                        .orElseThrow();
        assertEquals("3200", cashCell.get("rawValue"));
    }

    @Test
    void strictJsonRejectsMalformedUuidsUnknownNestedFieldsAndScalarCoercionsWith400() {
        var c = FinanceJournalPersonPostgresTest.fixture("CAS");
        FinanceJournalPersonPostgresTest.enable(c);
        UUID book = c.f().book(), year = c.f().year();

        // 1. Asset command with invalid UUID in payload -> must throw INVALID_INPUT (400), NOT 409!
        var assetBadUuid =
                assertThrows(
                        FinanceException.class,
                        () ->
                                FinanceJournalPersonPostgresTest.as(
                                        "maker",
                                        () ->
                                                assets.command(
                                                        "maker",
                                                        book,
                                                        year,
                                                        LocalDate.of(2026, 1, 15),
                                                        "acquire",
                                                        UUID.randomUUID().toString(),
                                                        Map.of("sourceId", "not-a-uuid"),
                                                        FinanceJournalPersonPostgresTest.request())));
        assertEquals("INVALID_INPUT", assetBadUuid.code);

        // 2. PayrollController with unknown nested field or string-to-long coercion -> HTTP 400!
        var auth = new UsernamePasswordAuthenticationToken("maker", "n/a");
        ResponseEntity<?> badPayrollResp =
                (ResponseEntity<?>)
                        FinanceJournalPersonPostgresTest.as(
                                "maker",
                                () ->
                                        payrollController.act(
                                                auth,
                                                book.toString(),
                                                UUID.randomUUID().toString(),
                                                "2026",
                                                UUID.randomUUID().toString(),
                                                Map.of(
                                                        "command",
                                                        Map.of(
                                                                "key",
                                                                UUID.randomUUID().toString(),
                                                                "client",
                                                                "web",
                                                                "expectedVersion",
                                                                "0",
                                                                "injectedUnknown",
                                                                true),
                                                        "action",
                                                        "CALCULATE"),
                                                FinanceJournalPersonPostgresTest.request(),
                                                new MockHttpServletResponse()));
        assertEquals(400, badPayrollResp.getStatusCode().value());
    }
}
