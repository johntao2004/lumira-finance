package com.lumira.finance.orgfx;

import com.lumira.finance.FinanceTestCredentials;

import com.lumira.finance.orgfx.OrgFxModels.*;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Isolated PostgreSQL 18 integration test for Lane W01 (org-fx).
 * Operates in a dedicated disposable schema test_w01_<uuid> on lumira_qa_finance_kernel.
 * Drops the temporary schema in cleanup.
 */
class OrgFxPostgresTest {
    private static PGSimpleDataSource dataSource;
    private static JdbcClient adminJdbc;
    private static String tempSchema;
    private static OrgFxJdbcStore store;
    private static OrgFxService service;
    private static ObjectMapper mapper;

    @BeforeAll
    static void setUpAll() throws Exception {
        String url = System.getenv("FINANCE_TEST_JDBC_URL");
        if (url == null || url.isBlank()) {
            url = "jdbc:postgresql://127.0.0.1:15489/lumira_qa_finance_kernel";
        }
        String user = FinanceTestCredentials.databaseUser();
        String password = FinanceTestCredentials.databasePassword();

        dataSource = new PGSimpleDataSource();
        dataSource.setURL(url);
        dataSource.setUser(user);
        dataSource.setPassword(password);

        adminJdbc = JdbcClient.create(dataSource);

        tempSchema = "test_w01_" + UUID.randomUUID().toString().replace("-", "");
        adminJdbc.sql("CREATE SCHEMA " + tempSchema).update();

        // Connect specifically with currentSchema
        PGSimpleDataSource tenantSource = new PGSimpleDataSource();
        tenantSource.setURL(url);
        tenantSource.setUser(user);
        tenantSource.setPassword(password);
        tenantSource.setCurrentSchema(tempSchema);

        JdbcClient tenantJdbc = JdbcClient.create(tenantSource);
        DataSourceTransactionManager txManager = new DataSourceTransactionManager(tenantSource);
        mapper = new ObjectMapper();

        store = new OrgFxJdbcStore(tenantJdbc, txManager, mapper);
        service = new OrgFxService(store);

        Path ddlPath = java.util.List.of(
            Path.of("plugins/finance/migrations/V26__org_policy_period_fx.sql"),
            Path.of("../migrations/V26__org_policy_period_fx.sql"),
            Path.of(".runtime/v4-wave2/org-fx/migration-candidate.sql"),
            Path.of("../../../.runtime/v4-wave2/org-fx/migration-candidate.sql"),
            Path.of("migration-candidate.sql")
        ).stream().filter(Files::exists).findFirst().orElseThrow();
        String sql = Files.readString(ddlPath);
        tenantJdbc.sql(sql).update();
    }

    @AfterAll
    static void tearDownAll() {
        if (adminJdbc != null && tempSchema != null) {
            try {
                adminJdbc.sql("DROP SCHEMA IF EXISTS " + tempSchema + " CASCADE").update();
            } catch (Exception ignored) {}
        }
    }

    @Test
    void testBranchAndLifecycleSoD() {
        UUID tenantId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();

        // 1. Lifecycle transitions with distinct natural persons: PREPARING -> PENDING_CONFIRMATION -> ACTIVE
        UUID personA = UUID.randomUUID();
        UUID personB = UUID.randomUUID();
        EntityLifecycleRecord record = service.transitionEntityLifecycle(
                tenantId,
                entityId,
                EntityStatus.PENDING_CONFIRMATION,
                "Entity setup completed, pending legal confirmation",
                Actor.of(UUID.randomUUID(), personA),
                Actor.of(UUID.randomUUID(), personB)
        );
        assertEquals(EntityStatus.PENDING_CONFIRMATION, record.currentStatus());
        assertEquals(1L, record.version());

        EntityLifecycleRecord active = service.transitionEntityLifecycle(
                tenantId,
                entityId,
                EntityStatus.ACTIVE,
                "Legal confirmation received; activating entity",
                Actor.of(UUID.randomUUID(), personB),
                Actor.of(UUID.randomUUID(), personA)
        );
        assertEquals(EntityStatus.ACTIVE, active.currentStatus());
        assertEquals(2L, active.version());

        // 2. Create branch under active entity
        Branch branch = service.createBranch(
                tenantId,
                entityId,
                "SH-HQ-01",
                "Shanghai Headquarters Branch",
                "INDEPENDENT_ACCOUNTING_BRANCH",
                "LOCAL_INDEPENDENT_FILING",
                "TAX-SH-001",
                "国家税务总局上海市浦东新区税务局",
                "Century Avenue 1000"
        );
        assertNotNull(branch.id());

        List<Branch> list = service.listBranches(tenantId, entityId);
        assertEquals(1, list.size());
        assertEquals("SH-HQ-01", list.getFirst().branchCode());

        // 3. Lifecycle transition with SAME person -> SoD error
        Actor same1 = Actor.of(UUID.randomUUID(), personA);
        Actor same2 = Actor.of(UUID.randomUUID(), personA);
        assertThrows(IllegalStateException.class, () ->
                service.transitionEntityLifecycle(
                        tenantId,
                        entityId,
                        EntityStatus.SUSPENDED,
                        "Testing unauthorized self-approval",
                        same1,
                        same2
                )
        );

        // 4. Invalid state transition: ACTIVE -> ACTIVE throws SAME_STATUS
        assertThrows(IllegalStateException.class, () ->
                service.transitionEntityLifecycle(
                        tenantId,
                        entityId,
                        EntityStatus.ACTIVE,
                        "Attempting duplicate active state",
                        Actor.of(UUID.randomUUID(), personA),
                        Actor.of(UUID.randomUUID(), personB)
                )
        );
    }


    @Test
    void testStandardEligibilityAndPolicyVersion() {
        UUID tenantId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        UUID bookId = UUID.randomUUID();

        // 1. Assess standard
        StandardEligibilityAssessment assessment = service.assessStandardEligibility(
                tenantId,
                entityId,
                false,
                false,
                false,
                100,
                new BigDecimal("30000000.00"),
                new BigDecimal("20000000.00"),
                Actor.of(UUID.randomUUID(), UUID.randomUUID()),
                "Normal micro-tech company"
        );
        assertTrue(assessment.eligibleForSmall());

        // 2. Create CAS policy version
        Actor maker = Actor.of(UUID.randomUUID(), UUID.randomUUID());
        PolicyVersion policy = service.createPolicyVersion(
                tenantId,
                bookId,
                1,
                AccountingStandard.CAS,
                "STRAIGHT_LINE",
                "WEIGHTED_AVERAGE",
                true,
                true,
                LocalDate.of(2026, 1, 1),
                null,
                maker
        );
        assertEquals(PolicyStatus.DRAFT, policy.status());

        // 3. Approve policy version with different reviewer
        Actor reviewer = Actor.of(UUID.randomUUID(), UUID.randomUUID());
        PolicyVersion approved = service.approvePolicyVersion(policy, reviewer, "DIGEST-SHA256-ABC");
        assertEquals(PolicyStatus.APPROVED, approved.status());

        // 4. Invariant: SMALL standard cannot allow impairment
        assertThrows(IllegalArgumentException.class, () ->
                service.createPolicyVersion(
                        tenantId,
                        bookId,
                        2,
                        AccountingStandard.SMALL,
                        "STRAIGHT_LINE",
                        "WEIGHTED_AVERAGE",
                        true, // violation: SMALL does not allow impairment
                        false,
                        LocalDate.of(2026, 1, 1),
                        null,
                        maker
                )
        );
    }

    @Test
    void testPeriodReopenAndAuditTrail() {
        UUID tenantId = UUID.randomUUID();
        UUID bookId = UUID.randomUUID();
        UUID yearId = UUID.randomUUID();
        UUID periodId = UUID.randomUUID();

        UUID personMaker = UUID.randomUUID();
        UUID personReviewer = UUID.randomUUID();

        // 1. Request reopen
        PeriodReopenRecord requested = service.requestPeriodReopen(
                tenantId,
                bookId,
                yearId,
                periodId,
                1,
                "Prior period late invoice adjustment required",
                Actor.of(UUID.randomUUID(), personMaker)
        );
        assertEquals(ReopenStatus.REQUESTED, requested.status());

        // 2. Same-person approval rejected
        Actor samePersonReviewer = Actor.of(UUID.randomUUID(), personMaker);
        IllegalStateException sodEx = assertThrows(IllegalStateException.class, () ->
                service.approvePeriodReopen(tenantId, bookId, periodId, samePersonReviewer, "Self approval"));
        assertTrue(sodEx.getMessage().contains("SOD_VIOLATION"));

        // 3. Reject reopen with different reviewer
        Actor validReviewer = Actor.of(UUID.randomUUID(), personReviewer);
        PeriodReopenRecord rejected = service.rejectPeriodReopen(
                tenantId,
                bookId,
                periodId,
                validReviewer,
                "Supporting tax declaration evidence missing"
        );
        assertEquals(ReopenStatus.REJECTED, rejected.status());
        assertEquals("Supporting tax declaration evidence missing", rejected.decisionReason());

        // 4. Request reopen again for same period
        PeriodReopenRecord requestedAgain = service.requestPeriodReopen(
                tenantId,
                bookId,
                yearId,
                periodId,
                1,
                "Resubmitting with signed auditor confirmation",
                Actor.of(UUID.randomUUID(), personMaker)
        );
        assertEquals(ReopenStatus.REQUESTED, requestedAgain.status());

        // 5. Approve reopen with different reviewer
        PeriodReopenRecord approved = service.approvePeriodReopen(
                tenantId,
                bookId,
                periodId,
                validReviewer,
                "Auditor confirmation verified and accepted"
        );
        assertEquals(ReopenStatus.APPROVED, approved.status());

        // 6. Reclose period
        PeriodReopenRecord reclosed = service.reclosePeriod(
                tenantId,
                bookId,
                periodId,
                validReviewer,
                "Adjustments completed and period re-locked"
        );
        assertEquals(ReopenStatus.CLOSED_AGAIN, reclosed.status());
    }


    @Test
    void testFxRateAndRevaluationPrecision() {
        UUID tenantId = UUID.randomUUID();
        UUID bookId = UUID.randomUUID();
        UUID yearId = UUID.randomUUID();
        UUID periodId = UUID.randomUUID();

        // 1. Register rate snapshot (numeric(28,12))
        BigDecimal rate = new BigDecimal("7.123456789012");
        FxRateSnapshot snapshot = service.registerFxRateSnapshot(
                tenantId,
                "USD",
                LocalDate.of(2026, 1, 31),
                FxRateType.CENTRAL_PARITY,
                rate,
                "PBOC_OFFICIAL",
                Actor.of(UUID.randomUUID(), UUID.randomUUID()),
                null
        );
        assertNotNull(snapshot.id());

        // 2. Create Revaluation Plan
        List<FxRevaluationItem> items = List.of(
                new FxRevaluationItem(
                        null, null, "1002.02", "USD Bank Account", "USD",
                        new BigDecimal("10000.00000000"),
                        new BigDecimal("70000.00000000"),
                        rate,
                        BigDecimal.ZERO, BigDecimal.ZERO, "6603"
                )
        );

        FxRevaluationPlan plan = service.createRevaluationPlan(
                tenantId,
                bookId,
                yearId,
                periodId,
                LocalDate.of(2026, 1, 31),
                snapshot.id(),
                Actor.of(UUID.randomUUID(), UUID.randomUUID()),
                items
        );

        assertNotNull(plan.id());
        assertEquals(1, plan.items().size());
        // restated: 10000 * 7.123456789012 = 71234.56789012
        assertEquals(new BigDecimal("71234.56789012"), plan.items().getFirst().restatedCnyBalance());
        // gain/loss: 71234.56789012 - 70000 = 1234.56789012
        assertEquals(new BigDecimal("1234.56789012"), plan.items().getFirst().gainLossAmount());
        assertEquals(new BigDecimal("1234.56789012"), plan.totalGainLoss());
    }

    @Test
    void testTaxCalendarAndCatalogRules() {
        // Template insertion
        TaxCalendarTemplate template = new TaxCalendarTemplate(
                UUID.randomUUID(),
                "CN_SHANGHAI",
                2026,
                1,
                "VAT",
                LocalDate.of(2026, 2, 15),
                LocalDate.of(2026, 2, 23),
                "SPRING_FESTIVAL_EXTENSION",
                "STA Announcement 2026-01"
        );
        store.insertTaxCalendarTemplate(template);

        List<ResolvedTaxObligation> obligations = service.resolveTaxObligations(
                "CN_SHANGHAI",
                2026,
                1,
                "GENERAL_TAXPAYER",
                "AUDIT_COLLECTION",
                false
        );

        assertFalse(obligations.isEmpty());
        ResolvedTaxObligation vat = obligations.stream()
                .filter(o -> "VAT_GENERAL_MONTHLY".equals(o.obligationCode()))
                .findFirst()
                .orElseThrow();

        assertEquals(LocalDate.of(2026, 2, 23), vat.deadline());
        assertTrue(vat.isExtended());
    }

    @Test
    void testConversionBridgePersistence() {
        UUID tenantId = UUID.randomUUID();
        UUID bookId = UUID.randomUUID();
        Actor maker = Actor.of(UUID.randomUUID(), UUID.randomUUID());
        Actor reviewer = Actor.of(UUID.randomUUID(), UUID.randomUUID());

        List<ConversionBridgeItem> items = List.of(
                new ConversionBridgeItem(
                        null, null, "1501", "Fixed Assets",
                        new BigDecimal("2000000.00000000"),
                        new BigDecimal("100000.00000000"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        "Component depreciation method adjustment",
                        "OPENING_RETAINED_EARNINGS"
                )
        );

        ConversionBridge bridge = service.createConversionBridge(
                tenantId,
                bookId,
                AccountingStandard.SMALL,
                AccountingStandard.CAS,
                2026,
                false,
                maker,
                reviewer,
                "Entity scale expanded past statutory micro thresholds",
                items
        );

        assertNotNull(bridge.id());
        assertEquals("APPROVED", bridge.status());

        Optional<ConversionBridge> found = service.findConversionBridge(tenantId, bookId, bridge.id());
        assertTrue(found.isPresent());
        assertEquals(new BigDecimal("100000.00000000"), found.get().totalAdjustmentDebit());
        assertEquals(1, found.get().items().size());
        assertEquals(new BigDecimal("2100000.00000000"), found.get().items().getFirst().adjustedBalance());

        List<ConversionBridge> list = service.listConversionBridges(tenantId, bookId);
        assertFalse(list.isEmpty());
        assertTrue(list.stream().anyMatch(b -> b.id().equals(bridge.id())));
    }

    @Test
    void testTaxBureausAndCurrenciesSeeding() {
        List<TaxBureau> bureaus = service.listTaxBureaus();
        assertFalse(bureaus.isEmpty());
        assertTrue(bureaus.stream().anyMatch(b -> "TAX-SH-001".equals(b.bureauCode())));
        assertTrue(bureaus.stream().anyMatch(b -> "TAX-BJ-001".equals(b.bureauCode())));

        List<FxCurrency> currencies = store.listCurrencies();
        assertFalse(currencies.isEmpty());
        assertTrue(currencies.stream().anyMatch(c -> "CNY".equals(c.currencyCode()) && c.isBase()));
        assertTrue(currencies.stream().anyMatch(c -> "USD".equals(c.currencyCode()) && !c.isBase()));
        assertTrue(currencies.stream().anyMatch(c -> "EUR".equals(c.currencyCode())));
    }
}


