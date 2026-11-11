package com.lumira.finance.archiveai;

import com.lumira.finance.FinanceTestCredentials;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * JUnit 5 Integration test running against isolated schema in PostgreSQL 18 at 127.0.0.1:15489/lumira_qa_finance_kernel.
 * Unique test_w12_<uuid> schema is created before tests and dropped in @AfterAll finally block.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ArchiveAiIntegrationTest {

    private static final String JDBC_URL = FinanceTestCredentials.jdbcUrl();
    private static final String DB_USER = FinanceTestCredentials.databaseUser();
    private static final String DB_PASS = FinanceTestCredentials.databasePassword();

    private Connection connection;
    private String schemaName;
    private ArchiveAiJdbcStore store;
    private ArchiveAiService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID bookId = UUID.randomUUID();
    private final UUID fiscalYearId = UUID.randomUUID();
    private final UUID legalEntityId = UUID.randomUUID();

    @BeforeAll
    void setUp() throws Exception {
        connection = DriverManager.getConnection(JDBC_URL, DB_USER, DB_PASS);
        connection.setAutoCommit(true);

        schemaName = "test_w12_" + UUID.randomUUID().toString().replace("-", "_");
        try (Statement st = connection.createStatement()) {
            st.execute("CREATE SCHEMA " + schemaName);
            st.execute("SET search_path TO " + schemaName + ", public");

            // Execute migration-candidate.sql
            Path ddlPath = java.util.List.of(
                Path.of(System.getProperty("migration.candidate.path", "")),
                Path.of("plugins/finance/migrations/V37__archive_lifecycle_integration_ai.sql"),
                Path.of("../migrations/V37__archive_lifecycle_integration_ai.sql"),
                Path.of(".runtime/v4-wave2/archive-int-ai/migration-candidate.sql"),
                Path.of("../../../.runtime/v4-wave2/archive-int-ai/migration-candidate.sql"),
                Path.of("/mnt/c/Users/Administrator/Documents/GitHub/lumira-finance/.runtime/v4-wave2/archive-int-ai/migration-candidate.sql"),
                Path.of("c:/Users/Administrator/Documents/GitHub/lumira-finance/.runtime/v4-wave2/archive-int-ai/migration-candidate.sql")
            ).stream().filter(p -> !p.toString().isBlank() && Files.exists(p)).findFirst().orElseThrow();
            String ddl = Files.readString(ddlPath, StandardCharsets.UTF_8);

            // Execute DDL statements directly
            st.execute(ddl);
        }

        store = new ArchiveAiJdbcStore(connection);
        service = new ArchiveAiService(store);
    }

    @AfterAll
    void tearDown() throws Exception {
        if (connection != null && !connection.isClosed()) {
            try (Statement st = connection.createStatement()) {
                st.execute("DROP SCHEMA IF EXISTS " + schemaName + " CASCADE");
            } finally {
                connection.close();
            }
        }
    }

    // --- 1. ARC01/ARC02/ARC03 Tests ---
    @Test
    void testArchiveRetentionClassificationAndGovernance() throws Exception {
        service.initDefaultRetentionRules(tenantId);

        // 1. Retention period calculations per 会计档案管理办法
        assertEquals(30, ArchiveGovModel.RetentionCategory.VOUCHER_GENERAL.statutoryYears());
        assertEquals(30, ArchiveGovModel.RetentionCategory.LEDGER_BOOK.statutoryYears());
        assertEquals(999, ArchiveGovModel.RetentionCategory.STATEMENT_ANNUAL.statutoryYears());
        assertEquals(10, ArchiveGovModel.RetentionCategory.STATEMENT_PERIODIC.statutoryYears());
        assertEquals(10, ArchiveGovModel.RetentionCategory.BANK_RECONCILIATION.statutoryYears());
        assertEquals(10, ArchiveGovModel.RetentionCategory.TAX_DECLARATION.statutoryYears());

        LocalDate voucherExpiry = ArchiveGovModel.calculateRetentionUntilDate(2026, 30);
        assertEquals(LocalDate.of(2056, 12, 31), voucherExpiry);

        LocalDate annualReportExpiry = ArchiveGovModel.calculateRetentionUntilDate(2026, 999);
        assertEquals(LocalDate.of(9999, 12, 31), annualReportExpiry);

        // 2. Insert Archive Record in DB
        UUID recordId1 = UUID.randomUUID();
        UUID custodianId = UUID.randomUUID();
        ArchiveGovModel.ArchiveRecord r1 = service.createArchiveRecord(
                tenantId, bookId, recordId1, ArchiveGovModel.RetentionCategory.VOUCHER_GENERAL,
                fiscalYearId, "2026年第1季度记账凭证档案", 2026, custodianId, false, null
        );
        assertNotNull(r1);
        Optional<ArchiveGovModel.ArchiveRecord> fetched = store.findArchiveRecord(tenantId, bookId, recordId1);
        assertTrue(fetched.isPresent());
        assertEquals("2026年第1季度记账凭证档案", fetched.get().title());
        assertEquals(30, fetched.get().statutoryYears());

        // 3. Borrowing log with SoD validation
        UUID borrowerId = UUID.randomUUID();
        UUID borrowId = UUID.randomUUID();
        ArchiveGovModel.BorrowLog log = service.borrowRecord(
                tenantId, bookId, borrowId, recordId1, borrowerId, "外部税务查账调阅", LocalDate.of(2026, 12, 31)
        );
        assertEquals("BORROWED", log.status());
        // Verify record status transitioned to BORROWED in DB
        assertEquals("BORROWED", store.findArchiveRecord(tenantId, bookId, recordId1).orElseThrow().status());

        // Cannot borrow an already-borrowed record
        assertThrows(IllegalStateException.class, () -> service.borrowRecord(
                tenantId, bookId, UUID.randomUUID(), recordId1, UUID.randomUUID(), "二次调阅冲突", LocalDate.of(2026, 12, 31)
        ));

        // Borrower cannot inspect own return (SoD violation)
        assertThrows(IllegalStateException.class, () -> service.returnRecord(
                tenantId, bookId, borrowId, recordId1, borrowerId
        ));

        // Legitimate return with distinct inspector transitions record back to IN_CUSTODY
        UUID inspectorId = UUID.randomUUID();
        ArchiveGovModel.BorrowLog returnedLog = service.returnRecord(
                tenantId, bookId, borrowId, recordId1, inspectorId
        );
        assertEquals("RETURNED", returnedLog.status());
        assertEquals("IN_CUSTODY", store.findArchiveRecord(tenantId, bookId, recordId1).orElseThrow().status());

        // 4. Custody transfer tri-person segregation
        UUID transferId = UUID.randomUUID();
        UUID transferor = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        UUID supervisor = UUID.randomUUID();
        ArchiveGovModel.CustodyTransfer ct = service.transferCustody(
                tenantId, bookId, transferId, "BATCH-2026-001", List.of(recordId1),
                transferor, recipient, supervisor, LocalDate.of(2026, 11, 15)
        );
        assertNotNull(ct.manifestDigest());
        // Duplicate persons violate SoD
        assertThrows(IllegalStateException.class, () -> ArchiveGovModel.validateCustodyTransfer(transferor, transferor, supervisor));
        assertThrows(IllegalStateException.class, () -> ArchiveGovModel.validateCustodyTransfer(transferor, recipient, transferor));

        // 5. Legal hold and Dual-person Destruction appraisal
        UUID recordId2 = UUID.randomUUID();
        ArchiveGovModel.ArchiveRecord holdRecord = service.createArchiveRecord(
                tenantId, bookId, recordId2, ArchiveGovModel.RetentionCategory.BANK_RECONCILIATION,
                fiscalYearId, "2020年涉税审计银行调节表", 2010, custodianId, true, "2026年增值税专项稽查未结"
        );
        UUID appraiser1 = UUID.randomUUID();
        UUID appraiser2 = UUID.randomUUID();
        UUID supervisor1 = UUID.randomUUID();
        UUID supervisor2 = UUID.randomUUID();

        // Legal hold blocks destruction with LEGAL_HOLD_ACTIVE
        assertThrows(IllegalStateException.class, () -> ArchiveGovModel.validateDestructionAppraisal(
                List.of(holdRecord), appraiser1, appraiser2, supervisor1, supervisor2, LocalDate.of(2026, 11, 10)
        ));

        // Non-empty record appraisal required
        assertThrows(IllegalArgumentException.class, () -> service.appraiseAndSuperviseDestruction(
                tenantId, bookId, UUID.randomUUID(), List.of(),
                appraiser1, appraiser2, supervisor1, supervisor2,
                LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 10), "SECURE_DESTRUCTION"
        ));

        // Valid expired record destruction updates status to DESTROYED in DB
        UUID recordId3 = UUID.randomUUID();
        ArchiveGovModel.ArchiveRecord expiredRecord = service.createArchiveRecord(
                tenantId, bookId, recordId3, ArchiveGovModel.RetentionCategory.STATEMENT_PERIODIC,
                fiscalYearId, "2010年季度财务报表", 2010, custodianId, false, null
        );
        ArchiveGovModel.DestructionManifest destManifest = service.appraiseAndSuperviseDestruction(
                tenantId, bookId, UUID.randomUUID(), List.of(expiredRecord),
                appraiser1, appraiser2, supervisor1, supervisor2,
                LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 10), "SECURE_DESTRUCTION"
        );
        assertNotNull(destManifest);
        assertEquals("DESTROYED", store.findArchiveRecord(tenantId, bookId, recordId3).orElseThrow().status());

        // Destroyed record cannot be borrowed
        assertThrows(IllegalStateException.class, () -> service.borrowRecord(
                tenantId, bookId, UUID.randomUUID(), recordId3, borrowerId, "尝试调阅已销毁档案", LocalDate.of(2026, 12, 31)
        ));

        // 6. Completeness Check Verification Contract
        ArchiveGovModel.CompletenessCheckResult incomplete = service.verifyArchiveCompleteness(false, false, true, true);
        assertFalse(incomplete.completenessSatisfied());
        assertFalse(incomplete.legalArchiveComplete());
        assertTrue(incomplete.blockers().contains("STATUTORY_STATEMENTS_NOT_PUBLISHED"));
        assertTrue(incomplete.blockers().contains("FISCAL_YEAR_NOT_CLOSED"));

        ArchiveGovModel.CompletenessCheckResult complete = service.verifyArchiveCompleteness(true, true, true, true);
        assertTrue(complete.completenessSatisfied());
        assertTrue(complete.legalArchiveComplete());
        assertTrue(complete.blockers().isEmpty());
    }

    // --- 2. INT01 Import Batch Tests ---
    @Test
    void testImportBatchDryRunAndReconciliation() throws Exception {
        List<ImportBatchEngine.FieldMapping> mappings = List.of(
                new ImportBatchEngine.FieldMapping("voucher_no", "voucher_no", "STRING", true),
                new ImportBatchEngine.FieldMapping("account_code", "account_code", "STRING", true),
                new ImportBatchEngine.FieldMapping("debit", "debit", "DECIMAL", true),
                new ImportBatchEngine.FieldMapping("credit", "credit", "DECIMAL", true),
                new ImportBatchEngine.FieldMapping("accounting_date", "accounting_date", "DATE", true)
        );

        // 1. Valid CSV dry run
        String validCsv = "voucher_no,account_code,debit,credit,accounting_date\n"
                + "V001,100201,1000.50,0.00,2026-10-15\n"
                + "V001,600101,0.00,1000.50,2026-10-15\n";

        ImportBatchEngine.DryRunResult validRes = service.dryRunImport(mappings, ",", validCsv);
        assertTrue(validRes.passed());
        assertEquals(2, validRes.totalRows());
        assertEquals(2, validRes.validRows());
        assertEquals(0, validRes.errorRows());
        assertEquals(new BigDecimal("1000.50000000"), validRes.controlTotalDebit());
        assertEquals(new BigDecimal("1000.50000000"), validRes.controlTotalCredit());

        // 2. Invalid CSV dry run (missing required field, decimal error, date error)
        String invalidCsv = "voucher_no,account_code,debit,credit,accounting_date\n"
                + ",100201,not_a_num,0.00,2026-99-99\n"
                + "V002,600101,0.00,500.123456789,2026/10/15\n";

        ImportBatchEngine.DryRunResult invalidRes = service.dryRunImport(mappings, ",", invalidCsv);
        assertFalse(invalidRes.passed());
        assertTrue(invalidRes.errorRows() > 0);
        assertTrue(invalidRes.errors().stream().anyMatch(e -> "REQUIRED_FIELD_EMPTY".equals(e.errorCode())));
        assertTrue(invalidRes.errors().stream().anyMatch(e -> "INVALID_DECIMAL".equals(e.errorCode())));
        assertTrue(invalidRes.errors().stream().anyMatch(e -> "PRECISION_EXCEEDED".equals(e.errorCode())));

        // 3. Batch approval SoD
        UUID preparer = UUID.randomUUID();
        UUID approver = UUID.randomUUID();
        assertDoesNotThrow(() -> ImportBatchEngine.validateBatchApproval(preparer, approver));
        assertThrows(IllegalStateException.class, () -> ImportBatchEngine.validateBatchApproval(preparer, preparer));

        // 4. Control total post-import reconciliation
        ImportBatchEngine.ReconciliationResult reconciled = service.reconcileBatchTotals(
                new BigDecimal("1000.50"), new BigDecimal("1000.50"),
                new BigDecimal("1000.50"), new BigDecimal("1000.50"), 2, 2
        );
        assertTrue(reconciled.matched());
        assertEquals("RECONCILED", reconciled.status());

        ImportBatchEngine.ReconciliationResult mismatch = service.reconcileBatchTotals(
                new BigDecimal("1000.50"), new BigDecimal("1000.50"),
                new BigDecimal("1000.00"), new BigDecimal("1000.50"), 2, 2
        );
        assertFalse(mismatch.matched());
        assertEquals("MISMATCH_DETECTED", mismatch.status());

        // 5. RFC 4180 CSV with quotes, embedded comma, and UTF-8 BOM (\uFEFF)
        String rfc4180Csv = "\uFEFFvoucher_no,account_code,debit,credit,accounting_date\n"
                + "\"V003\",\"100201\",\"520.00\",\"0.00\",\"2026-10-18\"\n"
                + "\"V003\",\"660201\",\"0.00\",\"520.00\",\"2026-10-18\"\n";
        ImportBatchEngine.DryRunResult rfcRes = service.dryRunImport(mappings, ",", rfc4180Csv);
        assertTrue(rfcRes.passed());
        assertEquals(2, rfcRes.validRows());
        assertEquals(0, rfcRes.errorRows());
        assertEquals(new BigDecimal("520.00000000"), rfcRes.controlTotalDebit());
        assertEquals(new BigDecimal("520.00000000"), rfcRes.controlTotalCredit());
    }

    // --- 3. INT02 & PLT03 ERP Events & Anti-CRM Tests ---
    @Test
    void testErpEventIngestionAndAntiCrmGuard() throws Exception {
        UUID connectorId = UUID.randomUUID();
        String apiKey = "secret-erp-key-2026";
        String keyHash = ErpEventEngine.sha256Hex(apiKey);

        ErpEventEngine.ErpConnector connector = new ErpEventEngine.ErpConnector(
                tenantId, connectorId, "SAP-S4HANA-FINANCE", "SAP",
                Set.of("FINANCE_EVENT_WRITE", "INVENTORY_FINANCE"), keyHash, "ACTIVE"
        );
        store.insertConnector(tenantId, connectorId, "SAP-S4HANA-FINANCE", "SAP",
                "[\"FINANCE_EVENT_WRITE\",\"INVENTORY_FINANCE\"]", keyHash, "ACTIVE");

        // 1. Strict Anti-CRM Guard rejection (snake_case, camelCase, PascalCase, kebab-case)
        String crmPayload = "{\"source_system\": \"SAP\", \"event_type\": \"REVENUE_RECOGNIZED\", \"lead_id\": \"CRM-LEAD-99881\", \"opportunity_id\": \"OPP-772\"}";
        assertThrows(IllegalStateException.class, () -> service.ingestAccountingEventWithAntiCrmGuard(
                crmPayload, connector, apiKey, null, 100L, Map.of(), Map.of()
        ));

        String camelCrmPayload = "{\"source_system\": \"SAP\", \"leadId\": \"CRM-LEAD-101\"}";
        assertThrows(IllegalStateException.class, () -> service.ingestAccountingEventWithAntiCrmGuard(
                camelCrmPayload, connector, apiKey, null, 100L, Map.of(), Map.of()
        ));

        String pascalCrmPayload = "{\"source_system\": \"SAP\", \"OpportunityId\": \"OPP-555\"}";
        assertThrows(IllegalStateException.class, () -> service.ingestAccountingEventWithAntiCrmGuard(
                pascalCrmPayload, connector, apiKey, null, 100L, Map.of(), Map.of()
        ));

        String kebabCrmPayload = "{\"source_system\": \"SAP\", \"pipeline-stage\": \"QUALIFIED\"}";
        assertThrows(IllegalStateException.class, () -> service.ingestAccountingEventWithAntiCrmGuard(
                kebabCrmPayload, connector, apiKey, null, 100L, Map.of(), Map.of()
        ));

        // 2. Valid AccountingEvent ingestion
        UUID eventId = UUID.randomUUID();
        String eventJson = "{\"schema_version\":\"1.0.0\",\"event_id\":\"" + eventId + "\",\"source_system\":\"SAP\",\"source_event_id\":\"SAP-EVT-001\"}";
        String payloadDigest = ErpEventEngine.sha256Hex(eventJson);

        ErpEventEngine.AccountingEvent event = new ErpEventEngine.AccountingEvent(
                "1.0.0", eventId, tenantId, "SAP", "SAP-EVT-001", "SAP-INV-2026-001", 1,
                "REVENUE_RECOGNIZED", "CREATE_JOURNAL_DRAFT", legalEntityId, bookId,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1), Instant.now(), "CNY",
                List.of(new ErpEventEngine.EventLine("line1", "REVENUE", BigDecimal.ONE, new BigDecimal("100.00"), new BigDecimal("13.00"), new BigDecimal("113.00"), "GENERAL_TAXABLE")),
                "v1-mapping", List.of(), null, "销售收入确认", payloadDigest
        );

        Map<String, String> digests = new HashMap<>();
        Map<String, UUID> drafts = new HashMap<>();

        // First ingestion succeeds
        service.ingestAccountingEventWithAntiCrmGuard(eventJson, connector, apiKey, event, 10L, digests, drafts);
        assertEquals(10L, store.getLatestWatermark(tenantId, "SAP", bookId));

        // 3. Idempotent replay with identical digest succeeds without duplicate effect
        digests.put("SAP-EVT-001", payloadDigest);
        drafts.put("SAP-EVT-001", UUID.randomUUID());
        assertDoesNotThrow(() -> service.ingestAccountingEventWithAntiCrmGuard(
                eventJson, connector, apiKey, event, 10L, digests, drafts
        ));

        // 4. Conflicting payload with same event id throws EFFECT_PAYLOAD_CONFLICT
        ErpEventEngine.AccountingEvent conflictingEvent = new ErpEventEngine.AccountingEvent(
                "1.0.0", UUID.randomUUID(), tenantId, "SAP", "SAP-EVT-001", "SAP-INV-2026-001", 1,
                "REVENUE_RECOGNIZED", "CREATE_JOURNAL_DRAFT", legalEntityId, bookId,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1), Instant.now(), "CNY",
                List.of(), "v1-mapping", List.of(), null, "篡改负荷", "different-digest-hex"
        );
        assertThrows(IllegalStateException.class, () -> service.ingestAccountingEventWithAntiCrmGuard(
                "{}", connector, apiKey, conflictingEvent, 11L, digests, drafts
        ));

        // 5. Reversal event creates reversal draft
        UUID reversalId = UUID.randomUUID();
        String revDigest = ErpEventEngine.sha256Hex("reversal-001");
        ErpEventEngine.AccountingEvent reversalEvent = new ErpEventEngine.AccountingEvent(
                "1.0.0", reversalId, tenantId, "SAP", "SAP-REV-001", "SAP-INV-2026-001", 2,
                "REVERSAL", "CREATE_JOURNAL_DRAFT", legalEntityId, bookId,
                LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 2), Instant.now(), "CNY",
                List.of(), "v1-mapping", List.of(), "SAP-EVT-001", "退货冲销收入", revDigest
        );
        service.ingestAccountingEventWithAntiCrmGuard("{}", connector, apiKey, reversalEvent, 12L, digests, drafts);
    }

    // --- 4. INT03 Recurring Schedules & Leap-Year Calendar Rules ---
    @Test
    void testRecurringSchedulesAndLeapYearRules() throws Exception {
        // 1. February leap-year calendar rules
        // Leap year 2024 -> Feb 29
        LocalDate feb2024 = service.computeMonthEndDate(2024, 2, "LAST_DAY_OF_MONTH");
        assertEquals(LocalDate.of(2024, 2, 29), feb2024);

        // Normal year 2025 -> Feb 28
        LocalDate feb2025 = service.computeMonthEndDate(2025, 2, "LAST_DAY_OF_MONTH");
        assertEquals(LocalDate.of(2025, 2, 28), feb2025);

        // Leap year 2028 -> Feb 29
        LocalDate feb2028 = service.computeMonthEndDate(2028, 2, "LAST_DAY_OF_MONTH");
        assertEquals(LocalDate.of(2028, 2, 29), feb2028);

        // April -> 30 days
        LocalDate apr2026 = service.computeMonthEndDate(2026, 4, "LAST_DAY_OF_MONTH");
        assertEquals(LocalDate.of(2026, 4, 30), apr2026);

        // March -> 31 days
        LocalDate mar2026 = service.computeMonthEndDate(2026, 3, "LAST_DAY_OF_MONTH");
        assertEquals(LocalDate.of(2026, 3, 31), mar2026);

        // 2. Schedule definition insertion
        UUID scheduleId = UUID.randomUUID();
        ScheduleRuleEngine.ScheduleDefinition sched = new ScheduleRuleEngine.ScheduleDefinition(
                tenantId, bookId, scheduleId, "TASK_MONTHLY_DEPRECIATION", "固定资产月度折旧计提",
                ScheduleRuleEngine.TaskType.ASSET_DEPRECIATION, "MONTH_END", null, "LAST_DAY_OF_MONTH", "ACTIVE", UUID.randomUUID()
        );
        store.insertScheduleDefinition(sched);

        // 3. Missed-window catch-up detection
        UUID p1 = UUID.randomUUID(), p2 = UUID.randomUUID(), p3 = UUID.randomUUID(), p4 = UUID.randomUUID();
        List<ScheduleRuleEngine.PeriodInfo> periods = List.of(
                new ScheduleRuleEngine.PeriodInfo(p1, 1, 2026, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)),
                new ScheduleRuleEngine.PeriodInfo(p2, 2, 2026, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)),
                new ScheduleRuleEngine.PeriodInfo(p3, 3, 2026, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31)),
                new ScheduleRuleEngine.PeriodInfo(p4, 4, 2026, LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30))
        );
        // Only period 1 was executed; currently at period 4 -> periods 2 and 3 are missed
        List<ScheduleRuleEngine.MissedWindow> missed = service.detectMissedScheduleWindows(
                periods, Set.of(p1), 4, "LAST_DAY_OF_MONTH"
        );
        assertEquals(2, missed.size());
        assertEquals(2, missed.get(0).period().periodNumber());
        assertEquals("CATCH_UP", missed.get(0).executionType());
        assertEquals(3, missed.get(1).period().periodNumber());

        // 4. Per-period deduplication
        Set<String> executed = new HashSet<>();
        executed.add(scheduleId + ":" + p1);

        assertThrows(IllegalStateException.class, () -> service.executeScheduleTask(
                tenantId, bookId, UUID.randomUUID(), scheduleId, fiscalYearId, p1, LocalDate.of(2026, 1, 31), "NORMAL", executed
        ));

        assertDoesNotThrow(() -> service.executeScheduleTask(
                tenantId, bookId, UUID.randomUUID(), scheduleId, fiscalYearId, p2, LocalDate.of(2026, 2, 28), "CATCH_UP", executed
        ));

        // 5. Day-of-month clamping (Day 31 clamped on 30-day and 28/29-day months)
        ScheduleRuleEngine.ScheduleDefinition schedDay31 = new ScheduleRuleEngine.ScheduleDefinition(
                tenantId, bookId, UUID.randomUUID(), "TASK_DAY_31", "每月31日固定计提",
                ScheduleRuleEngine.TaskType.EXPENSE_ACCRUAL, "SPECIFIC_DAY", 31, "EXACT_DAY", "ACTIVE", UUID.randomUUID()
        );
        assertEquals(LocalDate.of(2026, 4, 30), service.calculateScheduleExecutionDate(schedDay31, 2026, 4));
        assertEquals(LocalDate.of(2024, 2, 29), service.calculateScheduleExecutionDate(schedDay31, 2024, 2));
        assertEquals(LocalDate.of(2025, 2, 28), service.calculateScheduleExecutionDate(schedDay31, 2025, 2));
        assertEquals(LocalDate.of(2026, 3, 31), service.calculateScheduleExecutionDate(schedDay31, 2026, 3));
    }

    // --- 5. AI01, AI02, AIX01-03 AI Governance Tests ---
    @Test
    void testAiOcrAnomaliesAndActionPlanMajorBlock() throws Exception {
        // 1. Local OCR Account Suggestion (AI01)
        FinanceAiEngine.OcrSuggestion flightSugg = service.getOcrAccountSuggestion("增值税电子发票 机票住宿 差旅报销 850元");
        assertEquals("660201", flightSugg.suggestedAccountCode());
        assertEquals("管理费用-差旅费", flightSugg.accountName());
        assertEquals("NOT_CONFIGURED", flightSugg.externalModelProvider());
        assertTrue(flightSugg.mandatoryHumanReview());
        assertTrue(flightSugg.confidence().compareTo(new BigDecimal("0.9000")) >= 0);

        FinanceAiEngine.OcrSuggestion stationerySugg = service.getOcrAccountSuggestion("办公用品 A4打印纸 晨光文具");
        assertEquals("660202", stationerySugg.suggestedAccountCode());
        assertEquals("管理费用-办公费", stationerySugg.accountName());

        // 2. Anomaly Alert Detection & False-Positive Closure (AI02)
        UUID jId = UUID.randomUUID();
        List<FinanceAiEngine.AnomalyAlert> alerts = service.detectAnomalies(
                jId, LocalDate.of(2026, 10, 18), // Sunday
                new BigDecimal("50000.00"), new BigDecimal("49000.00"), // Unbalanced!
                List.of(new BigDecimal("20000.00"), new BigDecimal("10000.00"), new BigDecimal("10000.00"), new BigDecimal("9000.00")),
                new BigDecimal("5000.00"), new BigDecimal("2000.00") // 20000 > 5000 + 3*2000 = 11000 -> spike!
        );
        assertTrue(alerts.stream().anyMatch(a -> "UNBALANCED_JOURNAL".equals(a.ruleCode())));
        assertTrue(alerts.stream().anyMatch(a -> "WEEKEND_TRANSACTION".equals(a.ruleCode())));
        assertTrue(alerts.stream().anyMatch(a -> "AMOUNT_SPIKE_3SIGMA".equals(a.ruleCode())));

        // False-positive closure audit
        FinanceAiEngine.AnomalyAlert weekendAlert = alerts.stream().filter(a -> "WEEKEND_TRANSACTION".equals(a.ruleCode())).findFirst().orElseThrow();
        store.insertAnomalyAlert(tenantId, bookId, weekendAlert);

        UUID reviewerId = UUID.randomUUID();
        FinanceAiEngine.AnomalyAlert closedAlert = service.closeAnomalyAlertFalsePositive(
                weekendAlert, reviewerId, "电商大促双十一周末特批加班调账，经财务经理审批附件确认", "evidence-digest-hex"
        );
        assertEquals("CLOSED_FALSE_POSITIVE", closedAlert.status());
        assertNotNull(closedAlert.closedAt());

        // 3. AI Financial Inquiry Citation (AIX01)
        FinanceAiEngine.InquiryAnswer ans = service.explainInquiry("固定资产折旧方法如何规定？");
        assertTrue(ans.citations().contains("CAS 4 第十四条"));
        assertTrue(ans.draftCreatedOnly());

        // 4. ActionPlan Dry-Run, Approval & Tiered Major Block (AIX02 & AIX03)
        UUID planId = UUID.randomUUID();
        UUID requester = UUID.randomUUID();
        UUID approver = UUID.randomUUID();

        // Plan with low/medium risk actions (R1, R2, R3)
        UUID a1 = UUID.randomUUID();
        UUID a2 = UUID.randomUUID();
        List<FinanceAiEngine.ActionItem> routineActions = List.of(
                new FinanceAiEngine.ActionItem(a1, "JOURNAL_CREATE_DRAFT", "R1", List.of(), Map.of()),
                new FinanceAiEngine.ActionItem(a2, "JOURNAL_POST", "R3", List.of(a1), Map.of())
        );
        String dryRunDigest = FinanceAiEngine.computeDryRunDigest(routineActions);
        String payloadDigest = ErpEventEngine.sha256Hex("routine-plan");

        FinanceAiEngine.ActionPlan routinePlan = new FinanceAiEngine.ActionPlan(
                planId, tenantId, requester, 1, 1, "PROPOSED", "v4-policy", "CNY",
                new BigDecimal("5000.00"), routineActions, payloadDigest, dryRunDigest,
                null, null, null, null, Instant.now().plusSeconds(3600)
        );

        // Approver cannot be requester
        assertThrows(IllegalStateException.class, () -> service.approveActionPlan(routinePlan, requester, 1, payloadDigest, dryRunDigest));

        // Successful approval produces execution token
        FinanceAiEngine.ActionPlan approvedPlan = service.approveActionPlan(routinePlan, approver, 1, payloadDigest, dryRunDigest);
        assertEquals("APPROVED", approvedPlan.status());
        assertNotNull(approvedPlan.executionTokenHash());

        // 5. Tiered Major-Action Block (AIX03): Plan with R4 TAX_SUBMIT action is STRICTLY BLOCKED from execution
        UUID majorPlanId = UUID.randomUUID();
        UUID a3 = UUID.randomUUID();
        List<FinanceAiEngine.ActionItem> majorActions = List.of(
                new FinanceAiEngine.ActionItem(a3, "TAX_SUBMIT", "R4", List.of(), Map.of())
        );
        String majorDryRun = FinanceAiEngine.computeDryRunDigest(majorActions);
        String majorPayload = ErpEventEngine.sha256Hex("major-plan");

        FinanceAiEngine.ActionPlan majorPlan = new FinanceAiEngine.ActionPlan(
                majorPlanId, tenantId, requester, 1, 1, "PROPOSED", "v4-policy", "CNY",
                new BigDecimal("88000.00"), majorActions, majorPayload, majorDryRun,
                null, null, null, null, Instant.now().plusSeconds(3600)
        );
        FinanceAiEngine.ActionPlan approvedMajorPlan = service.approveActionPlan(majorPlan, approver, 1, majorPayload, majorDryRun);

        // Attempting to execute R4 action throws AI_MAJOR_ACTION_BLOCKED
        assertThrows(IllegalStateException.class, () -> service.executeActionPlan(approvedMajorPlan, "dummy-token"));

        // Additional Major Actions blocked: INVOICE_ISSUE and BANK_PAYMENT
        FinanceAiEngine.ActionPlan invoicePlan = new FinanceAiEngine.ActionPlan(
                UUID.randomUUID(), tenantId, requester, 1, 1, "PROPOSED", "v4-policy", "CNY",
                new BigDecimal("50000.00"),
                List.of(new FinanceAiEngine.ActionItem(UUID.randomUUID(), "INVOICE_ISSUE", "R3", List.of(), Map.of())),
                "inv-p", "inv-d", null, null, null, null, Instant.now().plusSeconds(3600)
        );
        FinanceAiEngine.ActionPlan approvedInvPlan = service.approveActionPlan(invoicePlan, approver, 1, "inv-p", "inv-d");
        assertThrows(IllegalStateException.class, () -> service.executeActionPlan(approvedInvPlan, "dummy-token"));

        FinanceAiEngine.ActionPlan paymentPlan = new FinanceAiEngine.ActionPlan(
                UUID.randomUUID(), tenantId, requester, 1, 1, "PROPOSED", "v4-policy", "CNY",
                new BigDecimal("80000.00"),
                List.of(new FinanceAiEngine.ActionItem(UUID.randomUUID(), "BANK_PAYMENT_DISBURSEMENT", "R3", List.of(), Map.of())),
                "pay-p", "pay-d", null, null, null, null, Instant.now().plusSeconds(3600)
        );
        FinanceAiEngine.ActionPlan approvedPayPlan = service.approveActionPlan(paymentPlan, approver, 1, "pay-p", "pay-d");
        assertThrows(IllegalStateException.class, () -> service.executeActionPlan(approvedPayPlan, "dummy-token"));

        // Additional Anomaly Rules: NEGATIVE_BALANCE & DUPLICATE_INVOICE_REF
        List<FinanceAiEngine.AnomalyAlert> extraAlerts = service.detectAnomalies(
                UUID.randomUUID(), LocalDate.of(2026, 10, 20),
                new BigDecimal("100.00"), new BigDecimal("100.00"),
                List.of(new BigDecimal("100.00")),
                List.of("INV-DUP-001", "INV-DUP-001"),
                new BigDecimal("500.00"), new BigDecimal("100.00")
        );
        assertTrue(extraAlerts.stream().anyMatch(a -> "DUPLICATE_INVOICE_REF".equals(a.ruleCode())));

        List<FinanceAiEngine.AnomalyAlert> negAlerts = service.detectAnomalies(
                UUID.randomUUID(), LocalDate.of(2026, 10, 20),
                new BigDecimal("100.00"), new BigDecimal("100.00"),
                List.of(new BigDecimal("-200.00")),
                new BigDecimal("500.00"), new BigDecimal("100.00")
        );
        assertTrue(negAlerts.stream().anyMatch(a -> "NEGATIVE_BALANCE".equals(a.ruleCode())));
    }

    // --- 6. PLT01 Offline Capture Lease Tests ---
    @Test
    void testOfflineCaptureLeaseAndEncryptedEnvelopes() throws Exception {
        UUID actorId = UUID.randomUUID();
        String deviceFp = "device-sha256-fingerprint-123";

        // 1. Lease exceeding 8 hours (28,800 seconds) is rejected
        assertThrows(IllegalArgumentException.class, () -> service.issueOfflineLease(
                tenantId, actorId, deviceFp, 36000 // 10 hours > 8 hours
        ));

        // 2. Valid lease (<= 8 hours) succeeds and enforces invariants
        OfflineLeaseEngine.OfflineLease lease = service.issueOfflineLease(tenantId, actorId, deviceFp, 28800);
        assertNotNull(lease.leaseId());
        assertEquals("ACTIVE", lease.status());
        assertEquals(28800, lease.maxSeconds());
        assertFalse(lease.renewable()); // strictly non-self-renewable
        assertEquals("WEB_ONLY_CLIENT", lease.clientType()); // WEB_ONLY_CLIENT

        // 3. Store client-ciphertext draft envelope
        String ciphertext = "BASE64-ENCRYPTED-DRAFT-CIPHERTEXT-ABCDEF";
        String iv = "12-BYTE-AES-GCM-IV";
        String tag = "16-BYTE-AUTH-TAG";
        String signature = "CLIENT-SECP256R1-SIGNATURE";
        String payloadSha = ErpEventEngine.sha256Hex(ciphertext);

        OfflineLeaseEngine.DraftEnvelope env = service.syncOfflineDraft(lease, ciphertext, iv, tag, signature, payloadSha);
        assertEquals("STORED", env.syncStatus());
        assertEquals(lease.leaseId(), env.leaseId());

        // 4. Reconnection revocation check: revoked lease rejects sync immediately
        OfflineLeaseEngine.OfflineLease revokedLease = OfflineLeaseEngine.revokeLease(lease, "Device reported lost by employee");
        assertEquals("REVOKED", revokedLease.status());
        assertThrows(IllegalStateException.class, () -> service.syncOfflineDraft(
                revokedLease, ciphertext, iv, tag, signature, payloadSha
        ));

        // 5. Expired lease rejection on reconnect
        OfflineLeaseEngine.OfflineLease expiredLease = new OfflineLeaseEngine.OfflineLease(
                tenantId, UUID.randomUUID(), actorId, deviceFp, OfflineLeaseEngine.CLIENT_TYPE_WEB_ONLY,
                Instant.now().minusSeconds(30000), Instant.now().minusSeconds(100), 28800, false, "EXPIRED", null, null
        );
        assertThrows(IllegalStateException.class, () -> service.syncOfflineDraft(
                expiredLease, ciphertext, iv, tag, signature, payloadSha
        ));
    }

    @Test
    void testArchiveAiRestControllerEndpoints() throws Exception {
        ArchiveAiController controller = new ArchiveAiController(service);

        // 1. Completeness check endpoint
        var compReq = Map.<String, Object>of(
                "statementsPublished", true,
                "fiscalYearClosed", true,
                "originalsVerified", true,
                "auditLogsComplete", true
        );
        var compResp = controller.checkCompleteness(compReq);
        assertEquals(200, compResp.getStatusCode().value());
        assertTrue((Boolean) compResp.getBody().get("completenessSatisfied"));

        // 2. OCR account suggestion endpoint
        var ocrReq = Map.<String, Object>of("ocrText", "发票 差旅住宿 航空机票 1200元");
        var ocrResp = controller.ocrSuggest(ocrReq);
        assertEquals(200, ocrResp.getStatusCode().value());
        assertEquals("660201", ocrResp.getBody().get("suggestedAccountCode"));

        // 3. Financial inquiry explainer endpoint
        var inqReq = Map.<String, Object>of("query", "研发费用加计扣除政策");
        var inqResp = controller.explainInquiry(inqReq);
        assertEquals(200, inqResp.getStatusCode().value());
        assertTrue(inqResp.getBody().get("citations").toString().contains("企业所得税法实施条例"));

        // 4. Batch dry-run endpoint
        var dryRunReq = Map.<String, Object>of(
                "delimiter", ",",
                "csvContent", "voucher_no,debit,credit\nV101,88.00,0.00\nV101,0.00,88.00\n",
                "mappings", List.of(
                        Map.of("sourceColumn", "voucher_no", "targetField", "voucher_no", "dataType", "STRING", "required", true),
                        Map.of("sourceColumn", "debit", "targetField", "debit", "dataType", "DECIMAL", "required", true),
                        Map.of("sourceColumn", "credit", "targetField", "credit", "dataType", "DECIMAL", "required", true)
                )
        );
        var dryRunResp = controller.dryRunBatch(dryRunReq);
        assertEquals(200, dryRunResp.getStatusCode().value());
        assertTrue((Boolean) dryRunResp.getBody().get("passed"));
        assertEquals("88.00000000", dryRunResp.getBody().get("controlTotalDebit"));

        // 5. Offline lease issue and sync-envelope endpoint
        UUID actorId = UUID.randomUUID();
        var leaseReq = Map.<String, Object>of(
                "tenantId", tenantId.toString(),
                "actorId", actorId.toString(),
                "deviceFingerprint", "device-rest-fp",
                "requestedSeconds", 14400
        );
        var leaseResp = controller.issueLease(leaseReq);
        assertEquals(200, leaseResp.getStatusCode().value());
        String leaseIdStr = leaseResp.getBody().get("leaseId").toString();

        var syncReq = Map.<String, Object>of(
                "tenantId", tenantId.toString(),
                "leaseId", leaseIdStr,
                "actorId", actorId.toString(),
                "clientCiphertext", "ENCRYPTED-REST-ENVELOPE",
                "iv", "IV123456",
                "authTag", "TAG123456",
                "clientSignature", "SIG123456",
                "payloadSha256", ErpEventEngine.sha256Hex("ENCRYPTED-REST-ENVELOPE")
        );
        var syncResp = controller.syncEnvelope(syncReq);
        assertEquals(200, syncResp.getStatusCode().value());
        assertEquals("STORED", syncResp.getBody().get("syncStatus"));
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== Starting ArchiveAiIntegrationTest on PostgreSQL 18 ===");
        ArchiveAiIntegrationTest test = new ArchiveAiIntegrationTest();
        int passed = 0;
        int failed = 0;
        try {
            test.setUp();
            System.out.println("Isolated test schema created: " + test.schemaName);

            String[] testMethods = {
                "testArchiveRetentionClassificationAndGovernance",
                "testImportBatchDryRunAndReconciliation",
                "testErpEventIngestionAndAntiCrmGuard",
                "testRecurringSchedulesAndLeapYearRules",
                "testAiOcrAnomaliesAndActionPlanMajorBlock",
                "testOfflineCaptureLeaseAndEncryptedEnvelopes",
                "testArchiveAiRestControllerEndpoints"
            };

            for (String mName : testMethods) {
                try {
                    System.out.print("Running " + mName + "... ");
                    var method = ArchiveAiIntegrationTest.class.getDeclaredMethod(mName);
                    method.invoke(test);
                    System.out.println("PASSED");
                    passed++;
                } catch (Exception ex) {
                    System.out.println("FAILED!");
                    ex.printStackTrace(System.out);
                    failed++;
                }
            }
        } finally {
            System.out.println("Dropping isolated test schema " + test.schemaName + " in finally block...");
            test.tearDown();
            System.out.println("Teardown complete.");
        }

        System.out.println("=== Test Summary: " + passed + " passed, " + failed + " failed ===");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
