package com.lumira.finance.fapiao;

import com.lumira.finance.FinanceTestCredentials;

import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class FinanceEvidenceFapiaoPostgresTest {
    private PGSimpleDataSource dataSource;
    private EvidenceFapiaoJdbcStore store;
    private EvidenceFapiaoService service;
    private String schemaName;
    private UUID tenantId;
    private UUID legalEntityId;

    @BeforeAll
    void setupAll() throws Exception {
        String testJdbcUrl = System.getenv("FINANCE_TEST_JDBC_URL");
        if (testJdbcUrl == null || testJdbcUrl.isBlank()) {
            testJdbcUrl = "jdbc:postgresql://127.0.0.1:15489/lumira_qa_finance_kernel";
        }
        if (testJdbcUrl.contains("lumira_qa_finance_web") || testJdbcUrl.contains("lumira_next")) {
            throw new IllegalStateException("Protected QA / production database cannot be used for isolated tests: " + testJdbcUrl);
        }

        String user = FinanceTestCredentials.databaseUser();
        String pass = FinanceTestCredentials.databasePassword();

        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(testJdbcUrl);
        dataSource.setUser(user);
        dataSource.setPassword(pass);

        tenantId = UUID.randomUUID();
        legalEntityId = UUID.randomUUID();
        schemaName = "test_w04_" + UUID.randomUUID().toString().replace("-", "");

        store = new EvidenceFapiaoJdbcStore(dataSource);
        service = new EvidenceFapiaoService(store);

        Path candidateSqlPath = java.util.List.of(
            Path.of(System.getProperty("fapiao.migration.candidate", "")),
            Path.of("plugins/finance/migrations/V29__evidence_fapiao_lifecycle.sql"),
            Path.of("../migrations/V29__evidence_fapiao_lifecycle.sql"),
            Path.of(".runtime/v4-wave2/evidence-fapiao/migration-candidate.sql"),
            Path.of("../../../.runtime/v4-wave2/evidence-fapiao/migration-candidate.sql"),
            Path.of("migration-candidate.sql")
        ).stream().filter(p -> !p.toString().isBlank() && Files.exists(p)).findFirst().orElseThrow();
        String ddl = Files.readString(candidateSqlPath);

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("CREATE SCHEMA " + schemaName + ";");
                s.execute("SET search_path TO " + schemaName + ", public;");
                s.execute(ddl);
                c.commit();
            } catch (Exception ex) {
                c.rollback();
                throw ex;
            }
        }
    }

    @AfterAll
    void tearDownAll() {
        if (dataSource != null && schemaName != null) {
            try (Connection c = dataSource.getConnection()) {
                c.setAutoCommit(true);
                try (Statement s = c.createStatement()) {
                    s.execute("DROP SCHEMA IF EXISTS " + schemaName + " CASCADE;");
                }
            } catch (Exception e) {
                System.err.println("Failed to drop test schema " + schemaName + ": " + e.getMessage());
            }
        }
    }

    private Connection getSearchPathConnection() throws Exception {
        Connection c = dataSource.getConnection();
        try (Statement s = c.createStatement()) {
            s.execute("SET search_path TO " + schemaName + ", public;");
        }
        return c;
    }

    // =========================================================================
    // EV01
    // =========================================================================

    @Test
    @DisplayName("EV01: Electronic original classification & completeness state machine with version immutability")
    void testEV01_EvidenceCompletenessAndReplacement() throws Exception {
        try (Connection c = getSearchPathConnection()) {
            c.setAutoCommit(false);
            try {
                // 1. XML invoice original -> complete & USABLE
                UUID originalId1 = UUID.randomUUID();
                var rec1 = service.registerCompleteness(
                        c, tenantId, legalEntityId, originalId1,
                        EvidenceClassification.EINVOICE_XML,
                        null,
                        Map.of("fileHash", "abc123sha256", "originalFilename", "invoice_001.xml"),
                        Map.of("personId", "P01", "role", "ACCOUNTANT")
                );
                assertNotNull(rec1.id());
                assertTrue(rec1.isComplete());
                assertEquals(EvidenceCompletenessState.USABLE, rec1.completenessState());
                assertEquals(1L, rec1.version());

                // 2. Paper scan with missing location -> PENDING_COMPLETION
                UUID originalId2 = UUID.randomUUID();
                var rec2 = service.registerCompleteness(
                        c, tenantId, legalEntityId, originalId2,
                        EvidenceClassification.OTHER_PAPER_SCAN,
                        null, // missing storage location
                        Map.of("isScreenshotOnly", true),
                        Map.of("personId", "P02", "role", "CLERK")
                );
                assertFalse(rec2.isComplete());
                assertEquals(EvidenceCompletenessState.PENDING_COMPLETION, rec2.completenessState());
                assertTrue(rec2.missingReasons().contains("SCREENSHOT_ONLY_MISSING_ORIGINAL_XML_OR_OFD"));

                // 3. Replace incomplete original with complete one -> immutable history check
                UUID newOriginalId = UUID.randomUUID();
                var rec3 = service.replaceEvidence(
                        c, tenantId, legalEntityId, rec2.id(), newOriginalId,
                        EvidenceClassification.EINVOICE_XML,
                        null,
                        Map.of("fileHash", "def456sha256"),
                        Map.of("personId", "P01", "role", "ACCOUNTANT")
                );
                assertEquals(2L, rec3.version());
                assertEquals(rec2.id(), rec3.predecessorId());
                assertTrue(rec3.isComplete());
                assertEquals(EvidenceCompletenessState.USABLE, rec3.completenessState());

                // Verify previous record is marked REPLACED
                var prevCheck = store.getCompleteness(c, tenantId, legalEntityId, rec2.id()).orElseThrow();
                assertEquals(EvidenceCompletenessState.REPLACED, prevCheck.completenessState());

                // 4. Complete metadata export index generation (EV01)
                var exportIndex = service.generateMetadataExportIndex(c, tenantId, legalEntityId, rec1.id());
                assertNotNull(exportIndex);
                assertEquals(rec1.id(), exportIndex.evidenceId());
                assertTrue(exportIndex.catalogIndexCode().startsWith("CAT-"));
                assertEquals(EvidenceClassification.EINVOICE_XML, exportIndex.classification());
                assertEquals("abc123sha256", exportIndex.sha256());
                assertEquals("invoice_001.xml", exportIndex.filename());
                assertTrue(exportIndex.isComplete());
                assertEquals(EvidenceCompletenessState.USABLE, exportIndex.completenessStatus());

                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    // =========================================================================
    // EV02
    // =========================================================================

    @Test
    @DisplayName("EV02: Deterministic XML parser, math check, OCR cross-check and explicit NOT_VERIFIED provenance")
    void testEV02_StructuredVerificationAndOcrCrossCheck() throws Exception {
        String xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Invoice>
                <InvoiceCode>1100223344</InvoiceCode>
                <InvoiceNumber>24110000000000000099</InvoiceNumber>
                <IssueDate>2026-11-10</IssueDate>
                <BuyerTaxNo>91110000MA00112233</BuyerTaxNo>
                <BuyerName>测试采购方科技有限公司</BuyerName>
                <SellerTaxNo>91310000MA99887766</SellerTaxNo>
                <SellerName>测试销售方软件有限公司</SellerName>
                <UntaxedAmount>2000.00</UntaxedAmount>
                <TaxRate>0.13</TaxRate>
                <TaxAmount>260.00</TaxAmount>
                <TotalAmount>2260.00</TotalAmount>
                <InvoiceType>ELECTRONIC_SPECIAL</InvoiceType>
            </Invoice>
            """;

        try (Connection c = getSearchPathConnection()) {
            c.setAutoCommit(false);
            try {
                UUID sourceOrigId = UUID.randomUUID();
                // 1. Initial completeness record
                var comp = service.registerCompleteness(
                        c, tenantId, legalEntityId, sourceOrigId,
                        EvidenceClassification.EINVOICE_XML, null, Map.of(), Map.of("personId", "P01")
                );
                UUID evidenceId = comp.id();

                // 2. Parse & Verify XML
                var parseResult = service.parseAndVerifyXml(c, tenantId, legalEntityId, evidenceId, xml, "AUTO_PARSER");
                assertEquals(new BigDecimal("2260.00"), parseResult.structuredData().totalAmount());
                assertEquals("NOT_VERIFIED", parseResult.verificationRecord().taxBureauVerification());
                assertEquals("PARSED", parseResult.verificationRecord().comparisonStatus());

                // 3. Exact OCR cross-check
                String ocrDigestMatch = FapiaoXmlParser.computeRawSha256("ocr-match".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                FapiaoOcrComparator.OcrResult ocrMatch = new FapiaoOcrComparator.OcrResult(
                        "24110000000000000099",
                        "91110000MA00112233",
                        "91310000MA99887766",
                        new BigDecimal("2000.00"),
                        new BigDecimal("260.00"),
                        new BigDecimal("2260.00"),
                        ocrDigestMatch
                );
                var matchCmp = service.crossCheckOcr(c, tenantId, legalEntityId, evidenceId, parseResult.structuredData(), ocrMatch, "OCR_ENGINE");
                assertTrue(matchCmp.isMatch());
                assertEquals("VERIFIED_MATCH", matchCmp.status());
                assertEquals("NOT_VERIFIED", matchCmp.taxBureauVerification());

                // 4. Mismatch OCR cross-check (different tax amount)
                String ocrDigestMismatch = FapiaoXmlParser.computeRawSha256("ocr-mismatch".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                FapiaoOcrComparator.OcrResult ocrMismatch = new FapiaoOcrComparator.OcrResult(
                        "24110000000000000099",
                        "91110000MA00112233",
                        "91310000MA99887766",
                        new BigDecimal("2000.00"),
                        new BigDecimal("250.00"), // Mismatch!
                        new BigDecimal("2260.00"),
                        ocrDigestMismatch
                );
                var mismatchCmp = service.crossCheckOcr(c, tenantId, legalEntityId, evidenceId, parseResult.structuredData(), ocrMismatch, "OCR_ENGINE");
                assertFalse(mismatchCmp.isMatch());
                assertEquals("MISMATCH_MANUAL_REVIEW", mismatchCmp.status());
                assertEquals("NOT_VERIFIED", mismatchCmp.taxBureauVerification());
                assertTrue(mismatchCmp.discrepancies().containsKey("taxAmount"));

                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    // =========================================================================
    // EV03
    // =========================================================================

    @Test
    @DisplayName("EV03: Extended quota buckets isolated from V15/V18 without summing across buckets")
    void testEV03_ExtendedQuotaBucketsIsolation() throws Exception {
        try (Connection c = getSearchPathConnection()) {
            c.setAutoCommit(false);
            try {
                UUID sourceOrigId = UUID.randomUUID();
                var comp = service.registerCompleteness(
                        c, tenantId, legalEntityId, sourceOrigId,
                        EvidenceClassification.EINVOICE_XML, null, Map.of(), Map.of("personId", "P01")
                );
                UUID evidenceId = comp.id();

                // Initialize 3 orthogonal quota buckets for the same 1000 RMB invoice:
                // 1000.00 for settlement, 130.00 for tax deduction, 1000.00 for reimbursement
                var qSettlement = service.initExtendedQuota(c, tenantId, legalEntityId, evidenceId, ExtendedQuotaBucket.SETTLEMENT_QUOTA, new BigDecimal("1000.00"));
                var qTax = service.initExtendedQuota(c, tenantId, legalEntityId, evidenceId, ExtendedQuotaBucket.TAX_DEDUCTION_QUOTA, new BigDecimal("130.00"));
                var qReimb = service.initExtendedQuota(c, tenantId, legalEntityId, evidenceId, ExtendedQuotaBucket.REIMBURSEMENT_QUOTA, new BigDecimal("1000.00"));

                assertEquals(new BigDecimal("1000.00"), qSettlement.availableAmount());
                assertEquals(new BigDecimal("130.00"), qTax.availableAmount());
                assertEquals(new BigDecimal("1000.00"), qReimb.availableAmount());

                UUID chainId = UUID.randomUUID();
                Map<String, Object> operator = Map.of("personId", "P01");

                // Reserve 600.00 in SETTLEMENT_QUOTA
                var resQ = service.reserveQuota(c, tenantId, legalEntityId, evidenceId, ExtendedQuotaBucket.SETTLEMENT_QUOTA, new BigDecimal("600.00"), chainId, operator);
                assertEquals(new BigDecimal("600.00"), resQ.reservedAmount());
                assertEquals(new BigDecimal("400.00"), resQ.availableAmount());

                // Over-reserve beyond remaining 400.00 fails
                assertThrows(IllegalStateException.class, () ->
                        service.reserveQuota(c, tenantId, legalEntityId, evidenceId, ExtendedQuotaBucket.SETTLEMENT_QUOTA, new BigDecimal("450.00"), chainId, operator)
                );

                // Commit 400.00 of the reserved settlement quota
                var comQ = service.commitQuota(c, tenantId, legalEntityId, evidenceId, ExtendedQuotaBucket.SETTLEMENT_QUOTA, new BigDecimal("400.00"), chainId, operator);
                assertEquals(new BigDecimal("200.00"), comQ.reservedAmount());
                assertEquals(new BigDecimal("400.00"), comQ.committedAmount());
                assertEquals(new BigDecimal("400.00"), comQ.availableAmount());

                // Release remaining 200.00 reserved
                var relQ = service.releaseQuota(c, tenantId, legalEntityId, evidenceId, ExtendedQuotaBucket.SETTLEMENT_QUOTA, new BigDecimal("200.00"), chainId, operator);
                assertEquals(new BigDecimal("0.00"), relQ.reservedAmount());
                assertEquals(new BigDecimal("400.00"), relQ.committedAmount());
                assertEquals(new BigDecimal("600.00"), relQ.availableAmount());

                // Tax deduction quota remains completely independent and intact
                var currentTax = store.getQuotaForUpdate(c, tenantId, legalEntityId, evidenceId, ExtendedQuotaBucket.TAX_DEDUCTION_QUOTA).orElseThrow();
                assertEquals(new BigDecimal("130.00"), currentTax.availableAmount());

                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    // =========================================================================
    // EV04
    // =========================================================================

    @Test
    @DisplayName("EV04: Business evidence graph linking & missing-evidence exception tracker with SoD approval")
    void testEV04_BusinessEvidenceGraphAndExceptionApproval() throws Exception {
        try (Connection c = getSearchPathConnection()) {
            c.setAutoCommit(false);
            try {
                // 1. Build evidence network: Contract -> PO -> Acceptance -> Invoice -> Slip
                var nodeContract = service.addGraphNode(c, tenantId, legalEntityId, GraphNodeType.CONTRACT, "CNT-2026-001", "采购框架合同", Map.of());
                var nodePO = service.addGraphNode(c, tenantId, legalEntityId, GraphNodeType.PURCHASE_ORDER, "PO-2026-001", "采购订单001", Map.of());
                var nodeAccept = service.addGraphNode(c, tenantId, legalEntityId, GraphNodeType.ACCEPTANCE_NOTE, "ACC-2026-001", "货物验收单001", Map.of());
                var nodeInvoice = service.addGraphNode(c, tenantId, legalEntityId, GraphNodeType.INVOICE, "INV-2026-001", "增值税发票001", Map.of());
                var nodeSlip = service.addGraphNode(c, tenantId, legalEntityId, GraphNodeType.BANK_SLIP, "SLIP-2026-001", "银行付款水单001", Map.of());

                service.addGraphEdge(c, tenantId, legalEntityId, nodePO.id(), nodeContract.id(), GraphEdgeType.FULFILLS, new BigDecimal("50000.00"), Map.of());
                service.addGraphEdge(c, tenantId, legalEntityId, nodeAccept.id(), nodePO.id(), GraphEdgeType.ACCEPTS, new BigDecimal("50000.00"), Map.of());
                service.addGraphEdge(c, tenantId, legalEntityId, nodeAccept.id(), nodeInvoice.id(), GraphEdgeType.BILLED_BY, new BigDecimal("50000.00"), Map.of());
                service.addGraphEdge(c, tenantId, legalEntityId, nodeInvoice.id(), nodeSlip.id(), GraphEdgeType.PAID_BY, new BigDecimal("50000.00"), Map.of());

                var nodes = store.listGraphNodes(c, tenantId, legalEntityId);
                var edges = store.listGraphEdges(c, tenantId, legalEntityId);
                assertEquals(5, nodes.size());
                assertEquals(4, edges.size());

                // 2. Cycle detection: adding edge from nodeContract -> nodeAccept must fail as it creates a directed cycle
                assertThrows(IllegalStateException.class, () ->
                        service.addGraphEdge(c, tenantId, legalEntityId, nodeContract.id(), nodeAccept.id(), GraphEdgeType.FULFILLS, null, Map.of())
                );

                // 3. Multi-hop lineage path tracing from Invoice:
                // Upstream should include Acceptance Note, Downstream should include Bank Slip
                var lineage = service.traceEvidencePath(c, tenantId, legalEntityId, nodeInvoice.id());
                assertNotNull(lineage);
                assertEquals(nodeInvoice.id(), lineage.startNodeId());
                assertTrue(lineage.upstreamNodes().stream().anyMatch(n -> n.id().equals(nodeAccept.id())));
                assertTrue(lineage.downstreamNodes().stream().anyMatch(n -> n.id().equals(nodeSlip.id())));

                // 4. Missing-evidence exception tracking
                UUID chainId = UUID.randomUUID();
                var ex = service.registerException(
                        c, tenantId, legalEntityId, chainId,
                        EvidenceExceptionType.MISSING_INVOICE_PROVISIONAL_ESTIMATE,
                        "已验收货物但供应商发票尚未开具，进行暂估入账",
                        new BigDecimal("50000.00"),
                        "USER_CLERK_01",
                        "张经办",
                        LocalDate.of(2026, 12, 31),
                        Map.of("personId", "USER_CLERK_01")
                );
                assertEquals(EvidenceExceptionStatus.REGISTERED, ex.status());
                assertEquals(new BigDecimal("50000.00"), ex.provisionalAmount());

                // SoD check: Responsible person cannot approve own exception
                assertThrows(IllegalStateException.class, () ->
                        service.approveException(c, tenantId, legalEntityId, ex.id(), "自审违规", Map.of("personId", "USER_CLERK_01"))
                );

                // Independent accountant approves exception
                service.approveException(c, tenantId, legalEntityId, ex.id(), "经核验验收单有效，同意暂估入账，限期补票", Map.of("personId", "USER_AUDITOR_99"));
                var approvedEx = store.getException(c, tenantId, legalEntityId, ex.id()).orElseThrow();
                assertEquals(EvidenceExceptionStatus.APPROVED_EXCEPTION, approvedEx.status());
                assertNotNull(approvedEx.approvedAt());

                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    // =========================================================================
    // FP01
    // =========================================================================

    @Test
    @DisplayName("FP01: Input & Output fapiao pool, unique code+number guard, and lifecycle transitions")
    void testFP01_InputOutputFapiaoPoolAndDuplicateGuard() throws Exception {
        try (Connection c = getSearchPathConnection()) {
            c.setAutoCommit(false);
            try {
                // 1. Register input fapiao
                var fapiao = service.registerFapiao(
                        c, tenantId, legalEntityId,
                        FapiaoDirection.INPUT,
                        "011002200111",
                        "24110000000000008888",
                        FapiaoType.ELECTRONIC_SPECIAL,
                        LocalDate.of(2026, 11, 10),
                        "91110000MA00112233", "采购方A",
                        "91310000MA99887766", "销售方B",
                        new BigDecimal("10000.00"),
                        new BigDecimal("0.13"),
                        new BigDecimal("1300.00"),
                        new BigDecimal("11300.00"),
                        null, null, null
                );
                assertNotNull(fapiao.id());
                assertEquals("NOT_VERIFIED", fapiao.taxBureauVerification());
                assertEquals(FapiaoLifecycleStatus.UNVERIFIED_LOCAL, fapiao.lifecycleStatus());

                // 2. Duplicate invoice code + number guard
                assertThrows(IllegalStateException.class, () ->
                        service.registerFapiao(
                                c, tenantId, legalEntityId,
                                FapiaoDirection.INPUT,
                                "011002200111",
                                "24110000000000008888", // duplicate!
                                FapiaoType.ELECTRONIC_SPECIAL,
                                LocalDate.of(2026, 11, 10),
                                "91110000MA00112233", "采购方A",
                                "91310000MA99887766", "销售方B",
                                new BigDecimal("10000.00"),
                                new BigDecimal("0.13"),
                                new BigDecimal("1300.00"),
                                new BigDecimal("11300.00"),
                                null, null, null
                        )
                );

                // 3. Lifecycle transitions
                service.updateFapiaoLifecycleStatus(c, tenantId, legalEntityId, fapiao.id(), FapiaoLifecycleStatus.MATCHED);
                var fpMatched = store.getFapiao(c, tenantId, legalEntityId, fapiao.id()).orElseThrow();
                assertEquals(FapiaoLifecycleStatus.MATCHED, fpMatched.lifecycleStatus());

                service.updateFapiaoLifecycleStatus(c, tenantId, legalEntityId, fapiao.id(), FapiaoLifecycleStatus.CERTIFIED_DECLARED);
                var fpDeclared = store.getFapiao(c, tenantId, legalEntityId, fapiao.id()).orElseThrow();
                assertEquals(FapiaoLifecycleStatus.CERTIFIED_DECLARED, fpDeclared.lifecycleStatus());

                service.updateFapiaoLifecycleStatus(c, tenantId, legalEntityId, fapiao.id(), FapiaoLifecycleStatus.TRANSFERRED_OUT);
                var fpTransfer = store.getFapiao(c, tenantId, legalEntityId, fapiao.id()).orElseThrow();
                assertEquals(FapiaoLifecycleStatus.TRANSFERRED_OUT, fpTransfer.lifecycleStatus());

                // 4. Ledger and tax worksheet reconciliation
                UUID journalId = UUID.randomUUID();
                UUID taxSheetId = UUID.randomUUID();
                var reconciled = service.reconcileFapiao(c, tenantId, legalEntityId, fapiao.id(), journalId, taxSheetId);
                assertEquals("RECONCILED", reconciled.reconciliationStatus());
                assertEquals(journalId, reconciled.ledgerJournalId());
                assertEquals(taxSheetId, reconciled.taxWorksheetId());

                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    // =========================================================================
    // FP02
    // =========================================================================

    @Test
    @DisplayName("FP02: Output fapiao issuance app, buyer qualification review, and manual issuance confirmation")
    void testFP02_OutputFapiaoIssuanceApplicationAndManualConfirmation() throws Exception {
        try (Connection c = getSearchPathConnection()) {
            c.setAutoCommit(false);
            try {
                // 1. Submit issuance application
                var app = service.submitIssuanceApp(
                        c, tenantId, legalEntityId,
                        "APP-2026-0001",
                        Map.of("personId", "SALES_01"),
                        LocalDate.of(2026, 11, 10),
                        "CONTRACT-001",
                        "CUST-001",
                        "91110000MA00112233",
                        "某客户科技有限责任公司",
                        "北京市朝阳区 010-88888888",
                        "招商银行北京分行 1234567890",
                        new BigDecimal("20000.00"),
                        new BigDecimal("2600.00"),
                        new BigDecimal("22600.00"),
                        List.of(Map.of("goodsServiceName", "云服务", "taxAmount", "2600.00"))
                );
                assertEquals(IssuanceAppStatus.SUBMITTED, app.status());
                assertEquals("NOT_VERIFIED", app.externalTaxChannel());
                assertEquals(BuyerQualificationStatus.PENDING_REVIEW, app.buyerQualificationStatus());

                // 2. Cannot manually issue while buyer qualification is pending
                assertThrows(IllegalStateException.class, () ->
                        service.confirmManualIssuance(
                                c, tenantId, legalEntityId, app.id(),
                                "24110000000000009999", "",
                                LocalDate.of(2026, 11, 10),
                                "91310000MA99887766", "本公司",
                                null, "发票已开具", Map.of("personId", "BILLER_01")
                        )
                );

                // 3. Review buyer qualification -> QUALIFIED
                service.reviewBuyerQualification(c, tenantId, legalEntityId, app.id(), BuyerQualificationStatus.QUALIFIED, "税号及开户行核验无误", Map.of("personId", "TAX_ACCOUNTANT"));
                var reviewedApp = store.getIssuanceApp(c, tenantId, legalEntityId, app.id()).orElseThrow();
                assertEquals(BuyerQualificationStatus.QUALIFIED, reviewedApp.buyerQualificationStatus());

                // 4. Confirm manual issuance -> binds output fapiao to FP01 pool
                var issuedFapiao = service.confirmManualIssuance(
                        c, tenantId, legalEntityId, app.id(),
                        "24110000000000009999", "",
                        LocalDate.of(2026, 11, 10),
                        "91310000MA99887766", "本公司",
                        null, "已在电子税务局人工完成数电票开具并上传系统归档", Map.of("personId", "BILLER_01")
                );
                assertNotNull(issuedFapiao.id());
                assertEquals("24110000000000009999", issuedFapiao.fapiaoNumber());
                assertEquals(FapiaoDirection.OUTPUT, issuedFapiao.direction());
                assertEquals(FapiaoLifecycleStatus.MATCHED, issuedFapiao.lifecycleStatus());

                var finalizedApp = store.getIssuanceApp(c, tenantId, legalEntityId, app.id()).orElseThrow();
                assertEquals(IssuanceAppStatus.ISSUED_MANUAL_CONFIRMED, finalizedApp.status());
                assertEquals(issuedFapiao.id(), finalizedApp.issuedFapiaoId());

                // 5. Double confirmation is rejected
                assertThrows(IllegalStateException.class, () ->
                        service.confirmManualIssuance(
                                c, tenantId, legalEntityId, app.id(),
                                "24110000000000009998", "",
                                LocalDate.of(2026, 11, 10),
                                "91310000MA99887766", "本公司",
                                null, "重复开票应当被拒绝", Map.of("personId", "BILLER_01")
                        )
                );

                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    // =========================================================================
    // FP03
    // =========================================================================

    @Test
    @DisplayName("FP03: Red-letter confirmation notice, 72h window expiration, credit note limits, and separate reversals")
    void testFP03_RedNotice72hExpirationAndCreditLinkage() throws Exception {
        try (Connection c = getSearchPathConnection()) {
            c.setAutoCommit(false);
            try {
                // 1. Create original blue fapiao (30000 untaxed, 3900 tax, 33900 total)
                var blueFapiao = service.registerFapiao(
                        c, tenantId, legalEntityId,
                        FapiaoDirection.OUTPUT,
                        "",
                        "24110000000000007777",
                        FapiaoType.ELECTRONIC_SPECIAL,
                        LocalDate.of(2026, 11, 10),
                        "91110000MA00112233", "某客户公司",
                        "91310000MA99887766", "本公司",
                        new BigDecimal("30000.00"),
                        new BigDecimal("0.13"),
                        new BigDecimal("3900.00"),
                        new BigDecimal("33900.00"),
                        null, null, null
                );

                // 2. Register red notice exceeding blue limit fails
                assertThrows(IllegalStateException.class, () ->
                        service.registerRedNotice(
                                c, tenantId, legalEntityId,
                                "RED-NOTICE-0001",
                                blueFapiao.id(),
                                "OUTPUT_RED", "SELLER_INITIATED", "SALES_RETURN",
                                new BigDecimal("-35000.00"), // exceeds 30000!
                                new BigDecimal("-4550.00"),
                                new BigDecimal("-39550.00")
                        )
                );

                // 3. Register valid partial red notice (-10000 untaxed, -1300 tax, -11300 total)
                var redNotice = service.registerRedNotice(
                        c, tenantId, legalEntityId,
                        "RED-NOTICE-0002",
                        blueFapiao.id(),
                        "OUTPUT_RED", "SELLER_INITIATED", "SALES_RETURN",
                        new BigDecimal("-10000.00"),
                        new BigDecimal("-1300.00"),
                        new BigDecimal("-11300.00")
                );
                assertEquals(RedNoticeStatus.WAITING_CONFIRMATION, redNotice.status());
                assertTrue(redNotice.expiresAt().isAfter(redNotice.registeredAt()));

                // 4. Test 72-hour expiration rule: confirming past 72h window throws exception and transitions to EXPIRED
                Instant past72h = redNotice.registeredAt().plus(Duration.ofHours(73));
                assertThrows(IllegalStateException.class, () ->
                        service.confirmRedNotice(c, tenantId, legalEntityId, redNotice.id(), past72h)
                );
                var expiredNotice = store.getRedNotice(c, tenantId, legalEntityId, redNotice.id()).orElseThrow();
                assertEquals(RedNoticeStatus.EXPIRED, expiredNotice.status());

                // 5. Register fresh red notice and confirm within 72h window
                var redNoticeFresh = service.registerRedNotice(
                        c, tenantId, legalEntityId,
                        "RED-NOTICE-0003",
                        blueFapiao.id(),
                        "OUTPUT_RED", "SELLER_INITIATED", "DISCOUNT_ALLOWANCE",
                        new BigDecimal("-10000.00"),
                        new BigDecimal("-1300.00"),
                        new BigDecimal("-11300.00")
                );
                Instant within72h = redNoticeFresh.registeredAt().plus(Duration.ofHours(24));
                service.confirmRedNotice(c, tenantId, legalEntityId, redNoticeFresh.id(), within72h);
                var confirmedNotice = store.getRedNotice(c, tenantId, legalEntityId, redNoticeFresh.id()).orElseThrow();
                assertEquals(RedNoticeStatus.CONFIRMED, confirmedNotice.status());

                // 6. Register red credit invoice into pool (-10000 untaxed, -1300 tax, -11300 total)
                var creditFapiao = service.registerFapiao(
                        c, tenantId, legalEntityId,
                        FapiaoDirection.OUTPUT,
                        "",
                        "24110000000000007778",
                        FapiaoType.ELECTRONIC_SPECIAL,
                        LocalDate.of(2026, 11, 10),
                        "91110000MA00112233", "某客户公司",
                        "91310000MA99887766", "本公司",
                        new BigDecimal("-10000.00"),
                        new BigDecimal("0.13"),
                        new BigDecimal("-1300.00"),
                        new BigDecimal("-11300.00"),
                        null, null, null
                );

                // 7. Link credit fapiao and record separate accounting revenue reversal vs VAT adjustment
                UUID revJournalId = UUID.randomUUID();
                UUID vatJournalId = UUID.randomUUID();

                // Linking with mismatched amount should fail
                var wrongCreditFapiao = service.registerFapiao(
                        c, tenantId, legalEntityId,
                        FapiaoDirection.OUTPUT, "", "24110000000000007779", FapiaoType.ELECTRONIC_SPECIAL,
                        LocalDate.of(2026, 11, 10), "91110000MA00112233", "某客户公司", "91310000MA99887766", "本公司",
                        new BigDecimal("-5000.00"), new BigDecimal("0.13"), new BigDecimal("-650.00"), new BigDecimal("-5650.00"),
                        null, null, null
                );
                assertThrows(IllegalArgumentException.class, () ->
                        service.linkCreditFapiao(c, tenantId, legalEntityId, redNoticeFresh.id(), wrongCreditFapiao.id(),
                                new BigDecimal("-10000.00"), new BigDecimal("-1300.00"), revJournalId, vatJournalId)
                );

                service.linkCreditFapiao(
                        c, tenantId, legalEntityId, redNoticeFresh.id(),
                        creditFapiao.id(),
                        new BigDecimal("-10000.00"), // accounting revenue reversal
                        new BigDecimal("-1300.00"),  // VAT adjustment
                        revJournalId, vatJournalId
                );

                var linkedNotice = store.getRedNotice(c, tenantId, legalEntityId, redNoticeFresh.id()).orElseThrow();
                assertEquals(RedNoticeStatus.RED_INVOICE_ISSUED, linkedNotice.status());
                assertEquals(new BigDecimal("-10000.00"), linkedNotice.accountingRevenueReversalAmount());
                assertEquals(new BigDecimal("-1300.00"), linkedNotice.vatAdjustmentAmount());
                assertEquals(revJournalId, linkedNotice.revenueReversalJournalId());
                assertEquals(vatJournalId, linkedNotice.vatAdjustmentJournalId());

                // Partial offset: blue fapiao (30000) only had 10000 offset, so status is NOT yet RED_OFFSET
                var partialBlue = store.getFapiao(c, tenantId, legalEntityId, blueFapiao.id()).orElseThrow();
                assertNotEquals(FapiaoLifecycleStatus.RED_OFFSET, partialBlue.lifecycleStatus());

                // 8. Cumulative check: blue fapiao has remaining 20000.00 untaxed.
                // Attempting to register red notice for -25000.00 untaxed must be rejected!
                assertThrows(IllegalStateException.class, () ->
                        service.registerRedNotice(
                                c, tenantId, legalEntityId, "RED-NOTICE-0004-FAIL", blueFapiao.id(),
                                "OUTPUT_RED", "SELLER_INITIATED", "SALES_RETURN",
                                new BigDecimal("-25000.00"), new BigDecimal("-3250.00"), new BigDecimal("-28250.00")
                        )
                );

                // 9. Register second partial red notice for exact remaining balance (-20000 untaxed, -2600 tax)
                var redNoticeFinal = service.registerRedNotice(
                        c, tenantId, legalEntityId, "RED-NOTICE-0005-FINAL", blueFapiao.id(),
                        "OUTPUT_RED", "SELLER_INITIATED", "SALES_RETURN",
                        new BigDecimal("-20000.00"), new BigDecimal("-2600.00"), new BigDecimal("-22600.00")
                );
                service.confirmRedNotice(c, tenantId, legalEntityId, redNoticeFinal.id(), within72h);

                var creditFapiaoFinal = service.registerFapiao(
                        c, tenantId, legalEntityId,
                        FapiaoDirection.OUTPUT, "", "24110000000000007780", FapiaoType.ELECTRONIC_SPECIAL,
                        LocalDate.of(2026, 11, 10), "91110000MA00112233", "某客户公司", "91310000MA99887766", "本公司",
                        new BigDecimal("-20000.00"), new BigDecimal("0.13"), new BigDecimal("-2600.00"), new BigDecimal("-22600.00"),
                        null, null, null
                );

                service.linkCreditFapiao(
                        c, tenantId, legalEntityId, redNoticeFinal.id(),
                        creditFapiaoFinal.id(),
                        new BigDecimal("-20000.00"), new BigDecimal("-2600.00"),
                        revJournalId, vatJournalId
                );

                // Now that total issued red notices = 10000 + 20000 = 30000 (100% covered), blue fapiao transitions to RED_OFFSET
                var fullyOffsetBlue = store.getFapiao(c, tenantId, legalEntityId, blueFapiao.id()).orElseThrow();
                assertEquals(FapiaoLifecycleStatus.RED_OFFSET, fullyOffsetBlue.lifecycleStatus());

                // Attempting to register another red notice when remaining balance is 0 fails
                assertThrows(IllegalStateException.class, () ->
                        service.registerRedNotice(
                                c, tenantId, legalEntityId, "RED-NOTICE-0006-OVER", blueFapiao.id(),
                                "OUTPUT_RED", "SELLER_INITIATED", "SALES_RETURN",
                                new BigDecimal("-100.00"), new BigDecimal("-13.00"), new BigDecimal("-113.00")
                        )
                );

                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    @Test
    @DisplayName("EV01: Metadata Export Index Determinism and Catalog Format")
    void testEV01_MetadataExportIndex() throws Exception {
        try (Connection c = getSearchPathConnection()) {
            c.setAutoCommit(false);
            try {
                UUID sourceOrigId = UUID.randomUUID();
                var comp = service.registerCompleteness(
                        c, tenantId, legalEntityId, sourceOrigId,
                        EvidenceClassification.EINVOICE_PDF, "档案室01-箱02",
                        Map.of(
                                "originalFilename", "electronic_fapiao_sample.pdf",
                                "contentType", "application/pdf",
                                "byteSize", 1048576,
                                "fileHash", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
                        ),
                        Map.of("personId", "AUDITOR_1")
                );

                var exportIndex = service.generateMetadataExportIndex(c, tenantId, legalEntityId, comp.id());
                assertNotNull(exportIndex);
                assertEquals(comp.id(), exportIndex.evidenceId());
                assertEquals("electronic_fapiao_sample.pdf", exportIndex.filename());
                assertEquals("application/pdf", exportIndex.contentType());
                assertEquals(1048576L, exportIndex.byteSize());
                assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", exportIndex.sha256());
                assertEquals(EvidenceClassification.EINVOICE_PDF, exportIndex.classification());
                assertEquals("档案室01-箱02", exportIndex.paperStorageLocation());
                assertEquals(EvidenceCompletenessState.USABLE, exportIndex.completenessStatus());
                assertTrue(exportIndex.isComplete());
                assertEquals(1L, exportIndex.version());

                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    @Test
    @DisplayName("EV04: Multi-hop Evidence Graph Traversal and Directed Cycle Prevention")
    void testEV04_GraphTraversalAndCyclePrevention() throws Exception {
        try (Connection c = getSearchPathConnection()) {
            c.setAutoCommit(false);
            try {
                // Nodes: Contract -> PO -> Acceptance -> Invoice -> Slip -> Journal
                var nContract = service.addGraphNode(c, tenantId, legalEntityId, GraphNodeType.CONTRACT, "CT-2026-X", "框架合同X", Map.of());
                var nPO = service.addGraphNode(c, tenantId, legalEntityId, GraphNodeType.PURCHASE_ORDER, "PO-2026-X", "订单X", Map.of());
                var nAccept = service.addGraphNode(c, tenantId, legalEntityId, GraphNodeType.ACCEPTANCE_NOTE, "AC-2026-X", "验收单X", Map.of());
                var nInvoice = service.addGraphNode(c, tenantId, legalEntityId, GraphNodeType.INVOICE, "INV-2026-X", "发票X", Map.of());
                var nSlip = service.addGraphNode(c, tenantId, legalEntityId, GraphNodeType.BANK_SLIP, "BS-2026-X", "银行水单X", Map.of());
                var nJournal = service.addGraphNode(c, tenantId, legalEntityId, GraphNodeType.JOURNAL_ENTRY, "JE-2026-X", "总账凭证X", Map.of());

                service.addGraphEdge(c, tenantId, legalEntityId, nPO.id(), nContract.id(), GraphEdgeType.FULFILLS, null, Map.of());
                service.addGraphEdge(c, tenantId, legalEntityId, nAccept.id(), nPO.id(), GraphEdgeType.ACCEPTS, null, Map.of());
                service.addGraphEdge(c, tenantId, legalEntityId, nAccept.id(), nInvoice.id(), GraphEdgeType.BILLED_BY, null, Map.of());
                service.addGraphEdge(c, tenantId, legalEntityId, nInvoice.id(), nSlip.id(), GraphEdgeType.PAID_BY, null, Map.of());
                service.addGraphEdge(c, tenantId, legalEntityId, nSlip.id(), nJournal.id(), GraphEdgeType.POSTED_BY, null, Map.of());

                // Direct cycle attempt: Journal -> Accept
                assertThrows(IllegalStateException.class, () ->
                        service.addGraphEdge(c, tenantId, legalEntityId, nJournal.id(), nAccept.id(), GraphEdgeType.POSTED_BY, null, Map.of())
                );

                // Multi-hop lineage from Acceptance Note
                var lineage = service.traceEvidencePath(c, tenantId, legalEntityId, nAccept.id());
                assertNotNull(lineage);
                assertEquals(nAccept.id(), lineage.startNodeId());
                assertTrue(lineage.downstreamNodes().stream().anyMatch(n -> n.id().equals(nPO.id())));
                assertTrue(lineage.downstreamNodes().stream().anyMatch(n -> n.id().equals(nContract.id())));
                assertTrue(lineage.downstreamNodes().stream().anyMatch(n -> n.id().equals(nInvoice.id())));
                assertTrue(lineage.downstreamNodes().stream().anyMatch(n -> n.id().equals(nSlip.id())));
                assertTrue(lineage.downstreamNodes().stream().anyMatch(n -> n.id().equals(nJournal.id())));

                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    @Test
    @DisplayName("FP03: Concurrent and Cumulative Red Notice Limit Boundary Enforcement")
    void testFP03_CumulativeRedNoticeLimitEnforcement() throws Exception {
        try (Connection c = getSearchPathConnection()) {
            c.setAutoCommit(false);
            try {
                // Blue invoice with untaxed 50000.00, tax 6500.00, total 56500.00
                var blue = service.registerFapiao(
                        c, tenantId, legalEntityId,
                        FapiaoDirection.OUTPUT, "", "24110000000000006666", FapiaoType.ELECTRONIC_SPECIAL,
                        LocalDate.of(2026, 11, 10), "91110000MA00112233", "买方6", "91310000MA99887766", "卖方6",
                        new BigDecimal("50000.00"), new BigDecimal("0.13"), new BigDecimal("6500.00"), new BigDecimal("56500.00"),
                        null, null, null
                );

                // 1. First partial notice: -20000.00 untaxed
                var r1 = service.registerRedNotice(
                        c, tenantId, legalEntityId, "RN-20261110-50K-1", blue.id(),
                        "OUTPUT_RED", "SELLER_INITIATED", "SALES_RETURN",
                        new BigDecimal("-20000.00"), new BigDecimal("-2600.00"), new BigDecimal("-22600.00")
                );
                assertNotNull(r1);

                // 2. Second partial notice: -20000.00 untaxed (cumulative = 40000.00, within 50000.00)
                var r2 = service.registerRedNotice(
                        c, tenantId, legalEntityId, "RN-20261110-50K-2", blue.id(),
                        "OUTPUT_RED", "SELLER_INITIATED", "SALES_RETURN",
                        new BigDecimal("-20000.00"), new BigDecimal("-2600.00"), new BigDecimal("-22600.00")
                );
                assertNotNull(r2);

                // 3. Third partial notice requesting -15000.00 untaxed (40000 + 15000 = 55000 > 50000) MUST FAIL!
                assertThrows(IllegalStateException.class, () ->
                        service.registerRedNotice(
                                c, tenantId, legalEntityId, "RN-20261110-50K-3-OVER", blue.id(),
                                "OUTPUT_RED", "SELLER_INITIATED", "SALES_RETURN",
                                new BigDecimal("-15000.00"), new BigDecimal("-1950.00"), new BigDecimal("-16950.00")
                        )
                );

                // 4. Exact remaining -10000.00 untaxed succeeds
                var r3 = service.registerRedNotice(
                        c, tenantId, legalEntityId, "RN-20261110-50K-3-EXACT", blue.id(),
                        "OUTPUT_RED", "SELLER_INITIATED", "SALES_RETURN",
                        new BigDecimal("-10000.00"), new BigDecimal("-1300.00"), new BigDecimal("-11300.00")
                );
                assertNotNull(r3);

                // 5. Any further red notice fails because remaining balance is 0.00
                assertThrows(IllegalStateException.class, () ->
                        service.registerRedNotice(
                                c, tenantId, legalEntityId, "RN-20261110-50K-4-ZERO", blue.id(),
                                "OUTPUT_RED", "SELLER_INITIATED", "SALES_RETURN",
                                new BigDecimal("-0.01"), BigDecimal.ZERO, new BigDecimal("-0.01")
                        )
                );

                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }
}
