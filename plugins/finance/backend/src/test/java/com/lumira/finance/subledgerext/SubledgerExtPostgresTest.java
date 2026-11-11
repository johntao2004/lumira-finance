package com.lumira.finance.subledgerext;

import com.lumira.finance.FinanceTestCredentials;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;

import com.lumira.finance.subledgerext.SubledgerExtModels.*;
import com.lumira.finance.subledgerext.SubledgerExtRules.*;

/**
 * Isolated PostgreSQL 18 test executing on lumira_qa_finance_kernel
 * within a dedicated, isolated test_w06_<uuid> schema dropped in teardown.
 */
class SubledgerExtPostgresTest {
    static String jdbcUrl;
    static String dbUser;
    static String dbPass;
    static PGSimpleDataSource adminDs;
    static PGSimpleDataSource testDs;
    static String schemaName;
    static SubledgerExtStore store;
    static SubledgerExtService service;

    static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID BOOK_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    static final UUID YEAR_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    static final UUID MAKER_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    static final UUID REVIEWER_ID = UUID.fromString("00000000-0000-0000-0000-000000000020");

    @BeforeAll
    static void setup() throws Exception {
        jdbcUrl = System.getenv("FINANCE_TEST_JDBC_URL");
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            jdbcUrl = "jdbc:postgresql://127.0.0.1:15489/lumira_qa_finance_kernel";
        }
        if (jdbcUrl.contains("lumira_qa_finance_web") || jdbcUrl.contains("lumira_next")) {
            throw new IllegalStateException("Refusing to run tests against protected database: " + jdbcUrl);
        }

        dbUser = FinanceTestCredentials.databaseUser();
        dbPass = FinanceTestCredentials.databasePassword();

        adminDs = new PGSimpleDataSource();
        adminDs.setURL(jdbcUrl);
        adminDs.setUser(dbUser);
        adminDs.setPassword(dbPass);

        schemaName = "test_w06_" + UUID.randomUUID().toString().replace("-", "");

        try (Connection c = adminDs.getConnection(); Statement stmt = c.createStatement()) {
            stmt.execute("CREATE SCHEMA " + schemaName);
        }

        testDs = new PGSimpleDataSource();
        testDs.setURL(jdbcUrl);
        testDs.setUser(dbUser);
        testDs.setPassword(dbPass);
        testDs.setCurrentSchema(schemaName);

        Path candidateSql = java.util.List.of(
            Path.of("plugins/finance/migrations/V31__subledger_ap_ar_extensions.sql"),
            Path.of("../migrations/V31__subledger_ap_ar_extensions.sql"),
            Path.of(".runtime/v4-wave2/ap-ar-ext/migration-candidate.sql"),
            Path.of("../../../.runtime/v4-wave2/ap-ar-ext/migration-candidate.sql"),
            Path.of("migration-candidate.sql")
        ).stream().filter(Files::exists).findFirst().orElseThrow();
        String ddl = Files.readString(candidateSql);

        try (Connection c = testDs.getConnection(); Statement stmt = c.createStatement()) {
            stmt.execute(ddl);
        }

        store = new SubledgerExtStore(testDs);
        service = new SubledgerExtService(store);
    }

    @AfterAll
    static void teardown() {
        if (adminDs != null && schemaName != null) {
            try (Connection c = adminDs.getConnection(); Statement stmt = c.createStatement()) {
                stmt.execute("DROP SCHEMA IF EXISTS " + schemaName + " CASCADE");
            } catch (Exception e) {
                System.err.println("Failed to drop test schema " + schemaName + ": " + e.getMessage());
            }
        }
    }

    @Test
    void testAp01ProcurementAccrualAndVarianceAdjustmentLifecycle() {
        UUID supplierId = UUID.randomUUID();
        UUID supplierRevisionId = UUID.randomUUID();

        // 1. Create Purchase Order (采购订单)
        Map<String, Object> poMap = new LinkedHashMap<>();
        poMap.put("idempotencyKey", "po-key-001");
        poMap.put("poNumber", "PO-2026-11-001");
        poMap.put("supplierId", supplierId.toString());
        poMap.put("supplierRevisionId", supplierRevisionId.toString());
        poMap.put("orderDate", "2026-11-01");
        poMap.put("terms", "Standard 30 Days");
        poMap.put("originalId", null);
        poMap.put("lines", List.of(
                Map.of(
                        "itemCode", "MAT-01",
                        "itemName", "Precision Metal Components",
                        "quantity", "100.00",
                        "unitPrice", "80.00",
                        "taxRate", "0.13"
                )
        ));

        PoOrder po = service.createPo(TENANT_ID, BOOK_ID, YEAR_ID, MAKER_ID, poMap);
        assertNotNull(po.id());
        assertEquals("PO-2026-11-001", po.poNumber());
        assertEquals(PoStatus.DRAFT, po.status());
        assertEquals(new BigDecimal("8000.00000000"), po.totalNetAmount());
        assertEquals(new BigDecimal("1040.00000000"), po.totalTaxAmount());
        assertEquals(new BigDecimal("9040.00000000"), po.totalGrossAmount());
        assertEquals(1, po.lines().size());

        // 2. Approve PO (with independent natural-person approver)
        service.approvePo(TENANT_ID, BOOK_ID, YEAR_ID, REVIEWER_ID, po.id(), Map.of(
                "idempotencyKey", "po-appr-001",
                "reason", "Budget approved"
        ));
        PoOrder approvedPo = store.getPo(po.id()).orElseThrow();
        assertEquals(PoStatus.APPROVED, approvedPo.status());
        assertEquals(REVIEWER_ID, approvedPo.approvedBy());

        // 3. Create Goods Acceptance (验收单)
        UUID poLineId = approvedPo.lines().get(0).id();
        Map<String, Object> accMap = new LinkedHashMap<>();
        accMap.put("idempotencyKey", "acc-key-001");
        accMap.put("poId", po.id().toString());
        accMap.put("acceptanceNumber", "ACC-2026-11-001");
        accMap.put("acceptedDate", "2026-11-10");
        accMap.put("acceptanceNote", "Batch quality verified");
        accMap.put("originalId", null);
        accMap.put("lines", List.of(
                Map.of(
                        "poLineId", poLineId.toString(),
                        "acceptedQuantity", "100.00",
                        "unitCostEstimate", "80.00"
                )
        ));

        PoAcceptance acc = service.createAcceptance(TENANT_ID, BOOK_ID, YEAR_ID, MAKER_ID, accMap);
        assertNotNull(acc.id());
        assertEquals(AcceptanceStatus.ACCEPTED, acc.status());
        assertEquals(InvoiceStatus.UNINVOICED, acc.invoiceStatus());
        assertEquals(ProvisionalStatus.NONE, acc.provisionalStatus());

        // 4. Month-end Provisional Accrual (暂估应付入账, accepted without invoice)
        Map<String, Object> provMap = new LinkedHashMap<>();
        provMap.put("idempotencyKey", "prov-key-001");
        provMap.put("acceptanceId", acc.id().toString());
        provMap.put("poId", po.id().toString());
        provMap.put("accrualPeriod", 11);
        provMap.put("accrualDate", "2026-11-30");
        provMap.put("estimatedNetAmount", "8000.00");
        provMap.put("reason", "Month-end provisional accrual for uninvoiced accepted goods");

        ProvisionalAccrual prov = service.createProvisionalAccrual(TENANT_ID, BOOK_ID, YEAR_ID, MAKER_ID, provMap);
        assertNotNull(prov.id());
        assertEquals(ProvisionalStatus.PROVISIONALLY_ACCRUED, prov.status());
        assertEquals(new BigDecimal("8000.00000000"), prov.estimatedNetAmount());

        PoAcceptance updatedAcc = store.getAcceptance(acc.id()).orElseThrow();
        assertEquals(ProvisionalStatus.PROVISIONALLY_ACCRUED, updatedAcc.provisionalStatus());

        // 5. Next-period Invoice Arrival Settlement & Variance Adjustment (到票冲暂估及差额调整)
        // Actual invoice: unit price 82.00 (price variance: +2.00 * 100 = +200.00), actual net = 8200.00
        Map<String, Object> settleMap = new LinkedHashMap<>();
        settleMap.put("idempotencyKey", "settle-key-001");
        settleMap.put("provisionalId", prov.id().toString());
        settleMap.put("invoiceNumber", "INV-SPEC-8888");
        settleMap.put("invoiceDate", "2026-12-05");
        settleMap.put("actualNetAmount", "8200.00");
        settleMap.put("actualTaxAmount", "1066.00");
        settleMap.put("actualGrossAmount", "9266.00"); // 8200 + 1066 = 9266

        ProvisionalSettlement ps = service.settleProvisionalAccrual(TENANT_ID, BOOK_ID, YEAR_ID, REVIEWER_ID, settleMap);
        assertNotNull(ps.id());
        assertEquals(new BigDecimal("8200.00000000"), ps.actualNetAmount());
        assertEquals(new BigDecimal("200.00000000"), ps.priceVariance());
        assertEquals(new BigDecimal("0.00000000"), ps.quantityVariance());
        assertEquals(new BigDecimal("200.00000000"), ps.totalVariance());

        ProvisionalAccrual settledProv = store.getProvisionalAccrual(prov.id()).orElseThrow();
        assertEquals(ProvisionalStatus.SETTLED, settledProv.status());
        assertEquals(LocalDate.parse("2026-12-05"), settledProv.reversalDate());

        // Verify acceptance and lines are properly updated to FULLY_INVOICED / REVERSED
        PoAcceptance finalAcc = store.getAcceptance(acc.id()).orElseThrow();
        assertEquals(InvoiceStatus.FULLY_INVOICED, finalAcc.invoiceStatus());
        assertEquals(ProvisionalStatus.REVERSED, finalAcc.provisionalStatus());
        assertEquals(new BigDecimal("100.00000000"), finalAcc.lines().get(0).invoicedQuantity());
        assertEquals(new BigDecimal("100.00000000"), store.getPo(po.id()).orElseThrow().lines().get(0).invoicedQuantity());
    }

    @Test
    void testAp01PartialQuantityVarianceDecomposition() {
        UUID supplierId = UUID.randomUUID();
        UUID supplierRevisionId = UUID.randomUUID();

        // Create and approve PO
        PoOrder po = service.createPo(TENANT_ID, BOOK_ID, YEAR_ID, MAKER_ID, Map.of(
                "idempotencyKey", "po-qty-key-001",
                "poNumber", "PO-2026-QTY-001",
                "supplierId", supplierId.toString(),
                "supplierRevisionId", supplierRevisionId.toString(),
                "orderDate", "2026-11-01",
                "terms", "30 Days",
                "originalId", "",
                "lines", List.of(Map.of(
                        "itemCode", "RAW-STEEL",
                        "itemName", "Industrial Steel Sheets",
                        "quantity", "100.00",
                        "unitPrice", "80.00",
                        "taxRate", "0.13"
                ))
        ));
        service.approvePo(TENANT_ID, BOOK_ID, YEAR_ID, REVIEWER_ID, po.id(), Map.of(
                "idempotencyKey", "po-qty-appr-001",
                "reason", "Approved"
        ));

        // Acceptance of 100 units at estimated 80.00 = 8000.00
        UUID poLineId = store.getPo(po.id()).orElseThrow().lines().get(0).id();
        PoAcceptance acc = service.createAcceptance(TENANT_ID, BOOK_ID, YEAR_ID, MAKER_ID, Map.of(
                "idempotencyKey", "acc-qty-key-001",
                "poId", po.id().toString(),
                "acceptanceNumber", "ACC-2026-QTY-001",
                "acceptedDate", "2026-11-15",
                "acceptanceNote", "Inspected",
                "originalId", "",
                "lines", List.of(Map.of(
                        "poLineId", poLineId.toString(),
                        "acceptedQuantity", "100.00",
                        "unitCostEstimate", "80.00"
                ))
        ));

        // Accrual of 8000.00
        ProvisionalAccrual prov = service.createProvisionalAccrual(TENANT_ID, BOOK_ID, YEAR_ID, MAKER_ID, Map.of(
                "idempotencyKey", "prov-qty-key-001",
                "acceptanceId", acc.id().toString(),
                "poId", po.id().toString(),
                "accrualPeriod", 11,
                "accrualDate", "2026-11-30",
                "estimatedNetAmount", "8000.00",
                "reason", "Provisional for month-end"
        ));

        // Settle with invoicedQuantity = 95.00, actual unit price = 82.00
        // actualNet = 95 * 82 = 7790.00, tax = 1012.70, gross = 8802.70
        // Price variance = (82 - 80) * 95 = +190.00
        // Quantity variance = (95 - 100) * 80 = -400.00
        // Total variance = 7790 - 8000 = -210.00
        // Price variance + Quantity variance == Total variance (+190 - 400 == -210)
        ProvisionalSettlement ps = service.settleProvisionalAccrual(TENANT_ID, BOOK_ID, YEAR_ID, REVIEWER_ID, Map.of(
                "idempotencyKey", "settle-qty-key-001",
                "provisionalId", prov.id().toString(),
                "invoiceNumber", "INV-QTY-9999",
                "invoiceDate", "2026-12-08",
                "actualNetAmount", "7790.00",
                "actualTaxAmount", "1012.70",
                "actualGrossAmount", "8802.70",
                "invoicedQuantity", "95.00"
        ));

        assertEquals(new BigDecimal("7790.00000000"), ps.actualNetAmount());
        assertEquals(new BigDecimal("190.00000000"), ps.priceVariance());
        assertEquals(new BigDecimal("-400.00000000"), ps.quantityVariance());
        assertEquals(new BigDecimal("-210.00000000"), ps.totalVariance());
        assertEquals(ps.priceVariance().add(ps.quantityVariance()), ps.totalVariance());
    }

    @Test
    void testAr01ContractAssetAndVariableConsiderationLifecycle() {
        UUID contractId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();

        // 1. CAS 14 Contract Asset Creation
        Map<String, Object> caMap = new LinkedHashMap<>();
        caMap.put("idempotencyKey", "ca-key-001");
        caMap.put("contractId", contractId.toString());
        caMap.put("customerId", customerId.toString());
        caMap.put("stageName", "Stage 1 Milestone Delivery");
        caMap.put("milestoneDate", "2026-11-15");
        caMap.put("assetAmount", "50000.00");
        caMap.put("conditionDescription", "Requires Phase 2 integration acceptance before unconditional billing");

        ContractAsset ca = service.createContractAsset(TENANT_ID, BOOK_ID, YEAR_ID, MAKER_ID, AccountingStandard.CAS, caMap);
        assertNotNull(ca.id());
        assertEquals(ConditionStatus.CONDITIONAL, ca.conditionStatus());
        assertEquals(new BigDecimal("50000.00000000"), ca.assetAmount());

        // 2. SMALL Policy Rejection for Contract Asset
        SubledgerException smallEx = assertThrows(SubledgerException.class,
                () -> service.createContractAsset(TENANT_ID, BOOK_ID, YEAR_ID, MAKER_ID, AccountingStandard.SMALL, caMap));
        assertEquals("SMALL_DOES_NOT_SUPPORT_CONTRACT_ASSET", smallEx.code);

        // 3. Reclassification of Contract Asset to Accounts Receivable (Dr 1122, Cr 1401)
        Map<String, Object> reclassMap = new LinkedHashMap<>();
        reclassMap.put("idempotencyKey", "reclass-key-001");
        reclassMap.put("reclassifiedDate", "2026-12-01");
        reclassMap.put("reason", "Milestone 2 completed; payment right became unconditional");

        service.reclassifyContractAsset(TENANT_ID, BOOK_ID, YEAR_ID, REVIEWER_ID, ca.id(), AccountingStandard.CAS, reclassMap);
        ContractAsset reclassifiedCa = store.getContractAsset(ca.id()).orElseThrow();
        assertEquals(ConditionStatus.FULFILLED, reclassifiedCa.conditionStatus());
        assertEquals(ContractAssetStatus.RECLASSIFIED, reclassifiedCa.status());
        assertNotNull(reclassifiedCa.reclassifiedArId());

        // 4. Constrained Variable Consideration Submission & Approval
        Map<String, Object> vcMap = new LinkedHashMap<>();
        vcMap.put("idempotencyKey", "vc-key-001");
        vcMap.put("contractId", contractId.toString());
        vcMap.put("variableKind", "BONUS");
        vcMap.put("estimatedAmount", "20000.00");
        vcMap.put("confidenceScore", "0.8000");
        vcMap.put("constrainedCapAmount", "16000.00"); // 16000 <= 20000 * 0.80
        vcMap.put("capRationale", "Performance KPI threshold highly probable to be achieved");

        VariableConsideration vc = service.createVariableConsideration(TENANT_ID, BOOK_ID, YEAR_ID, MAKER_ID, AccountingStandard.CAS, vcMap);
        assertNotNull(vc.id());
        assertEquals(VariableStatus.PENDING_APPROVAL, vc.status());

        // Approve variable consideration with independent approver
        service.approveVariableConsideration(TENANT_ID, BOOK_ID, YEAR_ID, REVIEWER_ID, vc.id(), Map.of(
                "idempotencyKey", "vc-appr-001",
                "reason", "Audit review confirms confidence score"
        ));
        VariableConsideration approvedVc = store.getVariableConsideration(vc.id()).orElseThrow();
        assertEquals(VariableStatus.APPROVED, approvedVc.status());
        assertNotNull(approvedVc.approvalDigest());

        // 5. Subsequent Revision of Variable Consideration Cap
        // Revision exceeding estimated amount must be rejected
        SubledgerException exceedEx = assertThrows(SubledgerException.class,
                () -> service.reviseVariableConsideration(TENANT_ID, BOOK_ID, YEAR_ID, REVIEWER_ID, vc.id(), Map.of(
                        "idempotencyKey", "vc-rev-exceed-001",
                        "revisedCapAmount", "25000.00",
                        "revisionReason", "Exceeding estimate is illegal"
                )));
        assertEquals("VARIABLE_CAP_EXCEEDS_ESTIMATED_AMOUNT", exceedEx.code);

        // Valid revision within estimate (18000 <= 20000)
        service.reviseVariableConsideration(TENANT_ID, BOOK_ID, YEAR_ID, REVIEWER_ID, vc.id(), Map.of(
                "idempotencyKey", "vc-rev-001",
                "revisedCapAmount", "18000.00",
                "revisionReason", "Mid-term milestones exceeded client specifications"
        ));
        VariableConsideration revisedVc = store.getVariableConsideration(vc.id()).orElseThrow();
        assertEquals(VariableStatus.REVISED, revisedVc.status());
        assertEquals(new BigDecimal("18000.00000000"), revisedVc.constrainedCapAmount());

        // 6. Multi-Year Contract Performance Continuity Across Consecutive Years
        // Year 1 (2026): 25% progress
        MultiYearContract myc1 = service.syncMultiYearContract(TENANT_ID, BOOK_ID, YEAR_ID, MAKER_ID, Map.of(
                "idempotencyKey", "my-key-001",
                "contractId", contractId.toString(),
                "startYear", 2026,
                "endYear", 2028,
                "totalContractValue", "1200000.00",
                "cumulativeProgressPct", "0.250000000000",
                "syncYear", 2026
        ));
        assertEquals(new BigDecimal("300000.00000000"), myc1.cumulativeRevenueRecognized());
        assertEquals(new BigDecimal("300000.00000000"), myc1.currentYearRevenue());
        assertEquals(new BigDecimal("900000.00000000"), myc1.unrecognizedBalance());

        // Year 1 update in the same year (2026) to 30% progress
        MultiYearContract myc1Update = service.syncMultiYearContract(TENANT_ID, BOOK_ID, YEAR_ID, MAKER_ID, Map.of(
                "idempotencyKey", "my-key-001-update",
                "contractId", contractId.toString(),
                "startYear", 2026,
                "endYear", 2028,
                "totalContractValue", "1200000.00",
                "cumulativeProgressPct", "0.300000000000",
                "syncYear", 2026
        ));
        assertEquals(new BigDecimal("360000.00000000"), myc1Update.cumulativeRevenueRecognized());
        assertEquals(new BigDecimal("360000.00000000"), myc1Update.currentYearRevenue());
        assertEquals(new BigDecimal("840000.00000000"), myc1Update.unrecognizedBalance());

        // Year 2 sync (2027) with progress advancing to 60%
        MultiYearContract myc2 = service.syncMultiYearContract(TENANT_ID, BOOK_ID, YEAR_ID, MAKER_ID, Map.of(
                "idempotencyKey", "my-key-002",
                "contractId", contractId.toString(),
                "startYear", 2026,
                "endYear", 2028,
                "totalContractValue", "1200000.00",
                "cumulativeProgressPct", "0.600000000000",
                "syncYear", 2027
        ));
        assertEquals(new BigDecimal("720000.00000000"), myc2.cumulativeRevenueRecognized());
        assertEquals(new BigDecimal("360000.00000000"), myc2.currentYearRevenue()); // 720k - 360k
        assertEquals(new BigDecimal("480000.00000000"), myc2.unrecognizedBalance());

        // Year 2 contract amendment updating total contract value to 1,500,000 at 70% progress
        MultiYearContract myc2Amended = service.syncMultiYearContract(TENANT_ID, BOOK_ID, YEAR_ID, MAKER_ID, Map.of(
                "idempotencyKey", "my-key-002-amend",
                "contractId", contractId.toString(),
                "startYear", 2026,
                "endYear", 2028,
                "totalContractValue", "1500000.00",
                "cumulativeProgressPct", "0.700000000000",
                "syncYear", 2027
        ));
        // Total expected = 1,500,000 * 0.70 = 1,050,000. Prior before 2027 was 360,000. Current year = 690,000. Unrecognized = 450,000.
        assertEquals(new BigDecimal("1500000.00000000"), myc2Amended.totalContractValue());
        assertEquals(new BigDecimal("1050000.00000000"), myc2Amended.cumulativeRevenueRecognized());
        assertEquals(new BigDecimal("690000.00000000"), myc2Amended.currentYearRevenue());
        assertEquals(new BigDecimal("450000.00000000"), myc2Amended.unrecognizedBalance());

        // Syncing backwards in time (syncYear 2026 after 2027) is rejected
        SubledgerException backwardEx = assertThrows(SubledgerException.class,
                () -> service.syncMultiYearContract(TENANT_ID, BOOK_ID, YEAR_ID, MAKER_ID, Map.of(
                        "idempotencyKey", "my-key-backward",
                        "contractId", contractId.toString(),
                        "startYear", 2026,
                        "endYear", 2028,
                        "totalContractValue", "1500000.00",
                        "cumulativeProgressPct", "0.700000000000",
                        "syncYear", 2026
                )));
        assertEquals("CANNOT_SYNC_PRIOR_YEAR", backwardEx.code);
    }

    @Test
    void testAr02ReceivableValuationEclAndAllowanceLifecycle() {
        UUID customerId = UUID.randomUUID();

        // 1. CAS ECL Provisioning
        Map<String, Object> eclMap = new LinkedHashMap<>();
        eclMap.put("idempotencyKey", "ecl-key-001");
        eclMap.put("period", 11);
        eclMap.put("assessmentType", "AGING_BUCKET");
        eclMap.put("customerId", customerId.toString());
        eclMap.put("receivableAmount", "100000.00");
        eclMap.put("agingBucket", "DAYS_61_90");
        eclMap.put("lossRate", "0.100000000000"); // 10%
        eclMap.put("priorAllowance", "3000.00");
        eclMap.put("operationKind", "PROVISION");
        eclMap.put("evidenceId", null);

        EclRecord ecl = service.createEclRecord(TENANT_ID, BOOK_ID, YEAR_ID, MAKER_ID, AccountingStandard.CAS, eclMap);
        assertNotNull(ecl.id());
        assertEquals(new BigDecimal("10000.00000000"), ecl.provisionAmount());
        assertEquals(new BigDecimal("7000.00000000"), ecl.adjustmentAmount());
        assertEquals(EclStatus.SUBMITTED, ecl.status());

        // Approve ECL provision
        service.approveEclRecord(TENANT_ID, BOOK_ID, YEAR_ID, REVIEWER_ID, ecl.id(), Map.of(
                "idempotencyKey", "ecl-appr-001",
                "reason", "Approved aging bucket ECL provision"
        ));
        EclRecord approvedEcl = store.getEclRecord(ecl.id()).orElseThrow();
        assertEquals(EclStatus.POSTED, approvedEcl.status());
        assertNotNull(approvedEcl.approvalDigest());

        // 2. SMALL Policy Rejection for Bad Debt Provision
        Map<String, Object> smallProvMap = new LinkedHashMap<>(eclMap);
        smallProvMap.put("idempotencyKey", "ecl-small-rej-001");
        SubledgerException smallProvEx = assertThrows(SubledgerException.class,
                () -> service.createEclRecord(TENANT_ID, BOOK_ID, YEAR_ID, MAKER_ID, AccountingStandard.SMALL, smallProvMap));
        assertEquals("SMALL_DOES_NOT_SUPPORT_BAD_DEBT_PROVISION", smallProvEx.code);

        // 3. SMALL Policy Allows Direct Bad Debt Write-off
        Map<String, Object> smallWriteOffMap = new LinkedHashMap<>(eclMap);
        smallWriteOffMap.put("idempotencyKey", "ecl-wo-key-001");
        smallWriteOffMap.put("operationKind", "WRITE_OFF");
        smallWriteOffMap.put("receivableAmount", "5000.00");
        smallWriteOffMap.put("priorAllowance", "0.00");

        EclRecord smallWo = service.createEclRecord(TENANT_ID, BOOK_ID, YEAR_ID, MAKER_ID, AccountingStandard.SMALL, smallWriteOffMap);
        assertNotNull(smallWo.id());
        assertEquals(EclOperationKind.WRITE_OFF, smallWo.operationKind());
        assertEquals(new BigDecimal("-5000.00000000"), smallWo.adjustmentAmount());

        // 4. Bad Debt Write-off Recovery (收回已核销坏账)
        Map<String, Object> recoveryMap = new LinkedHashMap<>(eclMap);
        recoveryMap.put("idempotencyKey", "ecl-rec-key-001");
        recoveryMap.put("operationKind", "WRITE_OFF_RECOVERY");
        recoveryMap.put("receivableAmount", "5000.00");
        recoveryMap.put("priorAllowance", "0.00");

        EclRecord recEcl = service.createEclRecord(TENANT_ID, BOOK_ID, YEAR_ID, MAKER_ID, AccountingStandard.CAS, recoveryMap);
        assertNotNull(recEcl.id());
        assertEquals(EclOperationKind.WRITE_OFF_RECOVERY, recEcl.operationKind());
        assertEquals(new BigDecimal("5000.00000000"), recEcl.provisionAmount());
        assertEquals(new BigDecimal("5000.00000000"), recEcl.adjustmentAmount());

        // 5. Sales Allowance Credit Adjustment (销售折让)
        Map<String, Object> allowMap = new LinkedHashMap<>();
        allowMap.put("idempotencyKey", "allow-key-001");
        allowMap.put("customerId", customerId.toString());
        allowMap.put("contractId", null);
        allowMap.put("receivableId", null);
        allowMap.put("adjustmentType", "SALES_ALLOWANCE");
        allowMap.put("creditNoteNumber", "CN-2026-11-001");
        allowMap.put("adjustmentDate", "2026-11-25");
        allowMap.put("originalReceivableAmount", "10000.00");
        allowMap.put("netAdjustmentAmount", "1000.00");
        allowMap.put("taxAdjustmentAmount", "130.00");
        allowMap.put("grossAdjustmentAmount", "1130.00"); // 1000 + 130 = 1130
        allowMap.put("vatRedInvoiceInfo", "RED-INFO-87654321");
        allowMap.put("reason", "Batch quality concession agreed with customer");

        AllowanceAdjustment adj = service.createAllowanceAdjustment(TENANT_ID, BOOK_ID, YEAR_ID, MAKER_ID, allowMap);
        assertNotNull(adj.id());
        assertEquals(AllowanceStatus.SUBMITTED, adj.status());

        service.approveAllowanceAdjustment(TENANT_ID, BOOK_ID, YEAR_ID, REVIEWER_ID, adj.id(), Map.of(
                "idempotencyKey", "allow-appr-001",
                "reason", "Concession approved by sales director"
        ));
        AllowanceAdjustment approvedAdj = store.getAllowanceAdjustment(adj.id()).orElseThrow();
        assertEquals(AllowanceStatus.POSTED, approvedAdj.status());
        assertNotNull(approvedAdj.approvalDigest());
    }

    @Test
    void testSelfApprovalPrevention() {
        // Self-approval must throw SELF_APPROVAL_FORBIDDEN
        SubledgerException ex = assertThrows(SubledgerException.class,
                () -> SubledgerExtRules.validateIndependentApproval(MAKER_ID, MAKER_ID));
        assertEquals("SELF_APPROVAL_FORBIDDEN", ex.code);
    }

    @Test
    void testIdempotencyReplayAcrossAllSubledgers() {
        UUID supplierId = UUID.randomUUID();
        UUID supplierRevisionId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();

        // 1. PO Creation Replay
        Map<String, Object> poMap = Map.of(
                "idempotencyKey", "idem-po-test-01",
                "poNumber", "PO-IDEM-001",
                "supplierId", supplierId.toString(),
                "supplierRevisionId", supplierRevisionId.toString(),
                "orderDate", "2026-11-01",
                "terms", "30 Days",
                "originalId", "",
                "lines", List.of(Map.of(
                        "itemCode", "IDEM-01",
                        "itemName", "Idempotent Item",
                        "quantity", "10.00",
                        "unitPrice", "100.00",
                        "taxRate", "0.13"
                ))
        );
        PoOrder po1 = service.createPo(TENANT_ID, BOOK_ID, YEAR_ID, MAKER_ID, poMap);
        PoOrder po2 = service.createPo(TENANT_ID, BOOK_ID, YEAR_ID, MAKER_ID, poMap);
        assertEquals(po1.id(), po2.id(), "Replaying PO creation with same idempotency key must return exact same PO");

        // 2. Acceptance Creation Replay
        service.approvePo(TENANT_ID, BOOK_ID, YEAR_ID, REVIEWER_ID, po1.id(), Map.of(
                "idempotencyKey", "idem-po-appr-01",
                "reason", "Approved"
        ));
        // Replay approvePo
        service.approvePo(TENANT_ID, BOOK_ID, YEAR_ID, REVIEWER_ID, po1.id(), Map.of(
                "idempotencyKey", "idem-po-appr-01",
                "reason", "Approved"
        ));

        UUID poLineId = store.getPo(po1.id()).orElseThrow().lines().get(0).id();
        Map<String, Object> accMap = Map.of(
                "idempotencyKey", "idem-acc-test-01",
                "poId", po1.id().toString(),
                "acceptanceNumber", "ACC-IDEM-001",
                "acceptedDate", "2026-11-10",
                "acceptanceNote", "Accepted",
                "originalId", "",
                "lines", List.of(Map.of(
                        "poLineId", poLineId.toString(),
                        "acceptedQuantity", "10.00",
                        "unitCostEstimate", "100.00"
                ))
        );
        PoAcceptance acc1 = service.createAcceptance(TENANT_ID, BOOK_ID, YEAR_ID, MAKER_ID, accMap);
        PoAcceptance acc2 = service.createAcceptance(TENANT_ID, BOOK_ID, YEAR_ID, MAKER_ID, accMap);
        assertEquals(acc1.id(), acc2.id(), "Replaying acceptance must return identical acceptance");

        // 3. Contract Asset Creation Replay
        UUID contractId = UUID.randomUUID();
        Map<String, Object> caMap = Map.of(
                "idempotencyKey", "idem-ca-test-01",
                "contractId", contractId.toString(),
                "customerId", customerId.toString(),
                "stageName", "Stage Idem",
                "milestoneDate", "2026-11-12",
                "assetAmount", "1000.00",
                "conditionDescription", "Inspection required"
        );
        ContractAsset ca1 = service.createContractAsset(TENANT_ID, BOOK_ID, YEAR_ID, MAKER_ID, AccountingStandard.CAS, caMap);
        ContractAsset ca2 = service.createContractAsset(TENANT_ID, BOOK_ID, YEAR_ID, MAKER_ID, AccountingStandard.CAS, caMap);
        assertEquals(ca1.id(), ca2.id(), "Replaying contract asset must return identical contract asset");

        // 4. ECL Record Replay
        Map<String, Object> eclMap = Map.of(
                "idempotencyKey", "idem-ecl-test-01",
                "period", 11,
                "assessmentType", "AGING_BUCKET",
                "customerId", customerId.toString(),
                "receivableAmount", "10000.00",
                "agingBucket", "NOT_DUE",
                "lossRate", "0.005000000000",
                "priorAllowance", "0.00",
                "operationKind", "PROVISION",
                "evidenceId", ""
        );
        EclRecord ecl1 = service.createEclRecord(TENANT_ID, BOOK_ID, YEAR_ID, MAKER_ID, AccountingStandard.CAS, eclMap);
        EclRecord ecl2 = service.createEclRecord(TENANT_ID, BOOK_ID, YEAR_ID, MAKER_ID, AccountingStandard.CAS, eclMap);
        assertEquals(ecl1.id(), ecl2.id(), "Replaying ECL must return identical record");
    }
}
