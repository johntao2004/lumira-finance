package com.lumira.finance.statutory;

import com.lumira.finance.FinanceTestCredentials;

import static com.lumira.finance.statutory.StatutoryModels.*;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class FinanceStatutoryPostgresTest {

    private String schemaName;
    private PGSimpleDataSource dataSource;
    private JdbcClient jdbc;
    private StatutoryStore store;
    private StatutoryService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID entityId = UUID.randomUUID();
    private final UUID bookId = UUID.randomUUID();
    private final UUID yearId = UUID.randomUUID();
    private final Actor preparer = new Actor(UUID.randomUUID(), "Preparer User", "ACCOUNTANT");
    private final Actor approver = new Actor(UUID.randomUUID(), "Approver User", "CONTROLLER");

    @BeforeAll
    void setupDatabase() throws Exception {
        String url = System.getenv("FINANCE_TEST_JDBC_URL");
        if (url == null || url.isBlank()) {
            url = "jdbc:postgresql://127.0.0.1:15489/lumira_qa_finance_kernel";
        }
        if (url.contains("lumira_qa_finance_web") || url.contains("lumira_next")) {
            throw new IllegalStateException("Forbidden QA Web / Default database target: " + url);
        }

        String user = FinanceTestCredentials.databaseUser();
        String password = FinanceTestCredentials.databasePassword();

        schemaName = "test_w11_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        PGSimpleDataSource adminDs = new PGSimpleDataSource();
        adminDs.setURL(url);
        adminDs.setUser(user);
        adminDs.setPassword(password);

        try (var conn = adminDs.getConnection()) {
            try (var st = conn.createStatement()) {
                st.execute("CREATE SCHEMA " + schemaName);
            }
        }

        dataSource = new PGSimpleDataSource();
        dataSource.setURL(url);
        dataSource.setUser(user);
        dataSource.setPassword(password);
        dataSource.setCurrentSchema(schemaName);

        Path sqlPath = java.util.List.of(
            Path.of("plugins/finance/migrations/V36__statutory_reports_packages.sql"),
            Path.of("../migrations/V36__statutory_reports_packages.sql"),
            Path.of(".runtime/v4-wave2/statutory-reports/migration-candidate.sql"),
            Path.of("../../../.runtime/v4-wave2/statutory-reports/migration-candidate.sql"),
            Path.of("migration-candidate.sql")
        ).stream().filter(Files::exists).findFirst().orElseThrow();
        String ddl = Files.readString(sqlPath);

        try (var conn = dataSource.getConnection()) {
            try (var st = conn.createStatement()) {
                st.execute(ddl);
            }
        }

        jdbc = JdbcClient.create(dataSource);
        ObjectMapper mapper = new ObjectMapper();
        store = new StatutoryStore(jdbc, mapper);
        service = new StatutoryService(store);
    }

    @AfterAll
    void teardownDatabase() throws Exception {
        if (schemaName != null && schemaName.startsWith("test_w11_")) {
            try (var conn = dataSource.getConnection()) {
                try (var st = conn.createStatement()) {
                    st.execute("DROP SCHEMA IF EXISTS " + schemaName + " CASCADE");
                }
            }
        }
    }

    @Test
    @DisplayName("Verify report package persistence, retrieval, and status workflow in PostgreSQL 18")
    void testPackagePersistenceAndWorkflow() {
        Scope scope = new Scope(tenantId, entityId, bookId, yearId, Standard.CAS_2019, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "CNY");

        UUID packageId = UUID.randomUUID();
        ReportPackage created = service.createAndAssemblePackage(
                packageId,
                scope,
                PackageType.THREE_STATEMENT_PACKAGE,
                FilingTarget.TAX_FILING,
                List.of(
                        new AccountBalance("1002", "银行存款", "BANK-01", "OBJ-CASH", "CASH", new BigDecimal("1000.00"), BigDecimal.ZERO),
                        new AccountBalance("4001", "实收资本", "CAP-01", "OBJ-CAP", "SHARE_CAPITAL", BigDecimal.ZERO, new BigDecimal("1000.00"))
                ),
                List.of(),
                List.of(),
                BigDecimal.ZERO,
                new BigDecimal("1000.00"),
                Map.of("C23", new BigDecimal("1000.00")),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                Map.of(),
                preparer
        );

        assertNotNull(created);
        assertEquals(PackageStatus.DRAFT, created.status());

        // Read back from DB and verify sheets are properly hydrated (preventing data-loss bug)
        Optional<ReportPackage> loaded = store.findPackageById(tenantId, packageId);
        assertTrue(loaded.isPresent());
        assertEquals(packageId, loaded.get().id());
        assertEquals(PackageStatus.DRAFT, loaded.get().status());
        assertFalse(loaded.get().sheets().isEmpty(), "Statements must be loaded from plugin_finance_statutory_statement");
        assertEquals(3, loaded.get().sheets().size(), "Three statement package must contain 3 sheets (BS, PL, CF)");

        // Approve with separate actor
        ReportPackage approved = service.approvePackage(tenantId, packageId, approver);
        assertEquals(PackageStatus.APPROVED, approved.status());

        Optional<ReportPackage> reloaded = store.findPackageById(tenantId, packageId);
        assertTrue(reloaded.isPresent());
        assertEquals(PackageStatus.APPROVED, reloaded.get().status());
        assertEquals(approver.personId(), reloaded.get().approver().personId());
        // Verify sheets are preserved after approval!
        assertEquals(3, reloaded.get().sheets().size(), "Sheets must be fully preserved across approval");

        // Publish package
        Actor publisher = new Actor(UUID.randomUUID(), "Dave Publisher", "DIRECTOR");
        ReportPackage published = service.publishPackage(tenantId, packageId, publisher);
        assertEquals(PackageStatus.PUBLISHED, published.status());

        Optional<ReportPackage> publishedReloaded = store.findPackageById(tenantId, packageId);
        assertTrue(publishedReloaded.isPresent());
        assertEquals(PackageStatus.PUBLISHED, publishedReloaded.get().status());
    }

    @Test
    @DisplayName("Verify Segregation of Duties: Approver cannot be the same person as Preparer")
    void testSegregationOfDuties() {
        Scope scope = new Scope(tenantId, entityId, bookId, yearId, Standard.CAS_2019, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "CNY");

        UUID packageId = UUID.randomUUID();
        service.createAndAssemblePackage(
                packageId,
                scope,
                PackageType.THREE_STATEMENT_PACKAGE,
                FilingTarget.TAX_FILING,
                List.of(
                        new AccountBalance("1002", "银行存款", "BANK-01", "OBJ-CASH", "CASH", new BigDecimal("1000.00"), BigDecimal.ZERO),
                        new AccountBalance("4001", "实收资本", "CAP-01", "OBJ-CAP", "SHARE_CAPITAL", BigDecimal.ZERO, new BigDecimal("1000.00"))
                ),
                List.of(), List.of(),
                BigDecimal.ZERO, new BigDecimal("1000.00"), Map.of("C01", new BigDecimal("1000.00")),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                Map.of(),
                preparer
        );

        // Attempting to approve with the same preparer must throw
        Actor selfApprover = new Actor(preparer.personId(), "Alice Self", "MANAGER");
        assertThrows(IllegalArgumentException.class, () -> service.approvePackage(tenantId, packageId, selfApprover));
    }

    @Test
    @DisplayName("Verify Full Statutory Report Package publication gating blocks incomplete notes or SCE")
    void testPublishAndGatingWorkflow() {
        Scope scope = new Scope(tenantId, entityId, bookId, yearId, Standard.CAS_2019, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "CNY");

        UUID packageId = UUID.randomUUID();
        // Incomplete notes under FULL_STATUTORY_REPORT_PACKAGE -> BLOCKED
        ReportPackage blockedPkg = service.createAndAssemblePackage(
                packageId,
                scope,
                PackageType.FULL_STATUTORY_REPORT_PACKAGE,
                FilingTarget.TAX_FILING,
                List.of(
                        new AccountBalance("1002", "银行存款", "BANK-01", "OBJ-CASH", "CASH", new BigDecimal("1000.00"), BigDecimal.ZERO),
                        new AccountBalance("4001", "实收资本", "CAP-01", "OBJ-CAP", "SHARE_CAPITAL", BigDecimal.ZERO, new BigDecimal("1000.00"))
                ),
                List.of(), List.of(),
                BigDecimal.ZERO, new BigDecimal("1000.00"), Map.of("C23", new BigDecimal("1000.00")),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                Map.of("ENTITY_PROFILE", "Short"), // Missing other 5 mandatory sections
                preparer
        );

        assertEquals(PackageStatus.BLOCKED, blockedPkg.status());
        assertFalse(blockedPkg.publicationGaps().isEmpty());

        // Attempting to approve a BLOCKED package must throw
        assertThrows(IllegalStateException.class, () -> service.approvePackage(tenantId, packageId, approver));

        // Attempting to publish a BLOCKED package must throw
        assertThrows(IllegalStateException.class, () -> service.publishPackage(tenantId, packageId, approver));
    }

    @Test
    @DisplayName("Verify Adjustment Worksheet creation, natural-person SoD, and approval workflow")
    void testAdjustmentWorksheetPersistenceAndSoD() {
        Scope scope = new Scope(tenantId, entityId, bookId, yearId, Standard.CAS_2019, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "CNY");

        UUID packageId = UUID.randomUUID();
        service.createAndAssemblePackage(
                packageId,
                scope,
                PackageType.THREE_STATEMENT_PACKAGE,
                FilingTarget.TAX_FILING,
                List.of(
                        new AccountBalance("1002", "银行存款", "BANK-01", "OBJ-CASH", "CASH", new BigDecimal("1000.00"), BigDecimal.ZERO),
                        new AccountBalance("4001", "实收资本", "CAP-01", "OBJ-CAP", "SHARE_CAPITAL", BigDecimal.ZERO, new BigDecimal("1000.00"))
                ),
                List.of(), List.of(),
                BigDecimal.ZERO, new BigDecimal("1000.00"), Map.of("C01", new BigDecimal("1000.00")),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                Map.of(),
                preparer
        );

        AdjustmentEntry adj = new AdjustmentEntry(
                UUID.randomUUID(),
                StatementKind.BS,
                "A01",
                new BigDecimal("50.00"),
                "利息调整",
                preparer,
                null,
                java.time.Instant.now()
        );

        Worksheet ws = service.createWorksheet(tenantId, packageId, "年末审计前纳税调整底稿", "企业所得税税前扣除调整", List.of(adj), preparer);
        assertNotNull(ws);
        assertEquals("PENDING", ws.status());
        assertEquals(1, ws.adjustments().size());

        // Self-approval must fail SoD
        Actor selfApprover = new Actor(preparer.personId(), "Alice Self", "MANAGER");
        assertThrows(IllegalArgumentException.class, () -> service.approveWorksheet(tenantId, packageId, ws.id(), selfApprover));

        // Independent approval passes
        Worksheet approvedWs = service.approveWorksheet(tenantId, packageId, ws.id(), approver);
        assertNotNull(approvedWs);
        assertEquals("APPROVED", approvedWs.status());
        assertEquals(approver.personId(), approvedWs.approver().personId());
    }

    @Test
    @DisplayName("Verify KPI and Cash Calendar persistence in PostgreSQL 18")
    void testKpiAndCalendarPersistence() {
        KpiMetrics kpi = service.evaluateKpis(
                tenantId, entityId, bookId, yearId, UUID.randomUUID(),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                new BigDecimal("500000.00"), new BigDecimal("100000.00"), new BigDecimal("50000.00"), BigDecimal.ZERO,
                12, new BigDecimal("800000.00"), new BigDecimal("300000.00"), new BigDecimal("200000.00"),
                new BigDecimal("60000.00"), new BigDecimal("40000.00"),
                preparer, approver
        );

        assertNotNull(kpi);
        assertEquals("APPROVED", kpi.status());

        // Cash calendar
        Scope scope = new Scope(tenantId, entityId, bookId, yearId, Standard.CAS_2019, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), "CNY");
        CashCalendar cal = service.generateCashCalendar(
                UUID.randomUUID(), scope, LocalDate.of(2026, 1, 1), 30,
                new BigDecimal("100000.00"), new BigDecimal("20000.00"),
                Map.of(LocalDate.of(2026, 1, 15), new BigDecimal("50000.00")),
                Map.of(LocalDate.of(2026, 1, 10), new BigDecimal("30000.00")),
                approver
        );

        assertNotNull(cal);
        assertEquals("PUBLISHED", cal.status());
    }

    @Test
    @DisplayName("Verify Consolidation Scope and Worksheet persistence in PostgreSQL 18")
    void testConsolidationPersistence() {
        UUID scopeId = UUID.randomUUID();
        ConsolidationScope scope = new ConsolidationScope(
                scopeId,
                "SCOPE_" + UUID.randomUUID().toString().substring(0, 8),
                entityId,
                "测试集团合并范围",
                List.of(new ConsolidationMember(UUID.randomUUID(), "子公司甲", new BigDecimal("70.00"), new BigDecimal("70.00"), ConsolidationMethod.FULL_CONSOLIDATION)),
                "ACTIVE"
        );
        store.saveConsolidationScope(tenantId, scope, preparer);

        StatementSheet sheet = new StatementSheet(StatementKind.BS, "CAS-BS-2019-A2", Map.of(), "PASS", Map.of(), List.of());
        ConsolidationWorksheet ws = service.generateConsolidationWorksheet(
                tenantId, UUID.randomUUID(), scope, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                true, sheet, sheet, sheet, Map.of(), Map.of(), Map.of(),
                null, null, null, null, null, preparer, approver
        );

        assertNotNull(ws);
        assertEquals("APPROVED", ws.status());
    }
}
