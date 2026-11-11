package com.lumira.finance.yearend;

import com.lumira.finance.FinanceTestCredentials;

import static com.lumira.finance.yearend.YearendModel.*;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.*;

/**
 * Isolated PostgreSQL 18 schema tests for Lane W09 gl-yearend candidate.
 * Connects to lumira_qa_finance_kernel, creates test_w09_<uuid>, runs candidate DDL and lifecycle tests,
 * and ensures schema is dropped in finally / @AfterAll.
 */
public class YearendPostgresTest {
    private static PGSimpleDataSource adminSource;
    private static PGSimpleDataSource testSource;
    private static String schemaName;
    private static YearendStore store;
    private static YearendService service;

    private static UUID tenantId;
    private static UUID bookId;
    private static UUID year2026Id;
    private static UUID year2027Id;
    private static UUID year2028Id;
    private static UUID period1Id;

    private static final PersonActor MAKER = new PersonActor(UUID.randomUUID(), UUID.randomUUID(), "maker-user");
    private static final PersonActor REVIEWER = new PersonActor(UUID.randomUUID(), UUID.randomUUID(), "reviewer-user");

    @BeforeAll
    static void setup() throws Exception {
        String dbUrl = System.getenv("FINANCE_TEST_JDBC_URL");
        if (dbUrl == null || dbUrl.isBlank()) {
            dbUrl = "jdbc:postgresql://127.0.0.1:15489/lumira_qa_finance_kernel";
        }
        String dbUser = FinanceTestCredentials.databaseUser();
        String dbPass = FinanceTestCredentials.databasePassword();

        adminSource = new PGSimpleDataSource();
        adminSource.setURL(dbUrl);
        adminSource.setUser(dbUser);
        adminSource.setPassword(dbPass);

        schemaName = "test_w09_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection c = adminSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA " + schemaName);
        }

        testSource = new PGSimpleDataSource();
        testSource.setURL(dbUrl);
        testSource.setUser(dbUser);
        testSource.setPassword(dbPass);
        testSource.setCurrentSchema(schemaName);

        // Load base tables in test schema
        try (Connection c = testSource.getConnection(); Statement s = c.createStatement()) {
            String baseTablesSql = """
                CREATE TABLE plugin_finance_tenant (id uuid PRIMARY KEY, name text NOT NULL, owner_id uuid NOT NULL);
                CREATE TABLE plugin_finance_entity (tenant_id uuid NOT NULL REFERENCES plugin_finance_tenant(id), id uuid NOT NULL, name text NOT NULL, registration_id text NOT NULL, PRIMARY KEY(tenant_id, id));
                CREATE TABLE plugin_finance_book (tenant_id uuid NOT NULL, id uuid PRIMARY KEY, legal_entity_id uuid NOT NULL, name text NOT NULL, standard text NOT NULL CHECK(standard IN('CAS','SMALL')), base_currency text NOT NULL CHECK(base_currency='CNY'), version bigint NOT NULL DEFAULT 1, UNIQUE(tenant_id, id), FOREIGN KEY(tenant_id, legal_entity_id) REFERENCES plugin_finance_entity(tenant_id, id));
                CREATE TABLE plugin_finance_year (tenant_id uuid NOT NULL, book_id uuid NOT NULL, id uuid NOT NULL, year integer NOT NULL, status text NOT NULL DEFAULT 'OPEN', version bigint NOT NULL DEFAULT 1, PRIMARY KEY(tenant_id, book_id, id), UNIQUE(book_id, year), FOREIGN KEY(tenant_id, book_id) REFERENCES plugin_finance_book(tenant_id, id));
                CREATE TABLE plugin_finance_period (tenant_id uuid NOT NULL, book_id uuid NOT NULL, year_id uuid NOT NULL, id uuid NOT NULL, month integer NOT NULL, status text NOT NULL DEFAULT 'OPEN', version bigint NOT NULL DEFAULT 1, PRIMARY KEY(tenant_id, book_id, year_id, id), FOREIGN KEY(tenant_id, book_id, year_id) REFERENCES plugin_finance_year(tenant_id, book_id, id));
            """;
            s.execute(baseTablesSql);

            // Seed test fixture
            tenantId = UUID.randomUUID();
            UUID entityId = UUID.randomUUID();
            bookId = UUID.randomUUID();
            year2026Id = UUID.randomUUID();
            year2027Id = UUID.randomUUID();
            year2028Id = UUID.randomUUID();
            period1Id = UUID.randomUUID();

            s.execute(String.format("INSERT INTO plugin_finance_tenant VALUES ('%s', 'Test Tenant', '%s')", tenantId, UUID.randomUUID()));
            s.execute(String.format("INSERT INTO plugin_finance_entity VALUES ('%s', '%s', 'Test Entity', '91110000MA00000001')", tenantId, entityId));
            s.execute(String.format("INSERT INTO plugin_finance_book VALUES ('%s', '%s', '%s', 'Test Book CAS', 'CAS', 'CNY', 1)", tenantId, bookId, entityId));
            s.execute(String.format("INSERT INTO plugin_finance_year VALUES ('%s', '%s', '%s', 2026, 'OPEN', 1)", tenantId, bookId, year2026Id));
            s.execute(String.format("INSERT INTO plugin_finance_year VALUES ('%s', '%s', '%s', 2027, 'OPEN', 1)", tenantId, bookId, year2027Id));
            s.execute(String.format("INSERT INTO plugin_finance_year VALUES ('%s', '%s', '%s', 2028, 'OPEN', 1)", tenantId, bookId, year2028Id));
            s.execute(String.format("INSERT INTO plugin_finance_period VALUES ('%s', '%s', '%s', '%s', 1, 'OPEN', 1)", tenantId, bookId, year2026Id, period1Id));

            // Load candidate migration DDL
            Path candidateSqlPath = java.util.List.of(
                Path.of(System.getProperty("gl.migration.candidate", "")),
                Path.of("plugins/finance/migrations/V34__gl_yearend_lifecycle.sql"),
                Path.of("../migrations/V34__gl_yearend_lifecycle.sql"),
                Path.of(".runtime/v4-wave2/gl-yearend/migration-candidate.sql"),
                Path.of("../../../.runtime/v4-wave2/gl-yearend/migration-candidate.sql"),
                Path.of("migration-candidate.sql")
            ).stream().filter(p -> !p.toString().isBlank() && Files.exists(p)).findFirst().orElseThrow();
            String ddl = Files.readString(candidateSqlPath);
            s.execute(ddl);
        }

        store = new YearendStore(new ObjectMapper());
        service = new YearendService(testSource, store);
    }

    @AfterAll
    static void cleanup() {
        if (schemaName != null && schemaName.startsWith("test_w09_")) {
            try (Connection c = adminSource.getConnection(); Statement s = c.createStatement()) {
                s.execute("DROP SCHEMA IF EXISTS " + schemaName + " CASCADE");
            } catch (Exception e) {
                System.err.println("Failed to drop test schema " + schemaName + ": " + e.getMessage());
            }
        }
    }

    @Test
    public void testCutoverLifecycleAndTieOutGate() throws Exception {
        UUID a1 = UUID.randomUUID();
        UUID a2 = UUID.randomUUID();

        List<OpeningBalanceItem> balances = List.of(
                new OpeningBalanceItem(UUID.randomUUID(), a1, "1001", "Cash", "CNY", "DEBIT", new BigDecimal("10000.00000000"), Map.of()),
                new OpeningBalanceItem(UUID.randomUUID(), a2, "2202", "AP", "CNY", "CREDIT", new BigDecimal("10000.00000000"), Map.of("vendorId", "V001"))
        );

        List<BaselineItem> baselines = List.of(
                new BaselineItem(UUID.randomUUID(), "CASHFLOW", 6, "SALES", "Sales Cash", new BigDecimal("10000.00000000"), Map.of())
        );

        Map<String, BigDecimal> subledgers = Map.of(
                "AP", new BigDecimal("10000.00000000"),
                "AR", BigDecimal.ZERO,
                "ASSET", BigDecimal.ZERO,
                "PAYROLL", BigDecimal.ZERO
        );

        // 1. Propose Cutover in OPENING_PLUS_YTD mode
        CutoverProposal proposal = service.proposeCutover(
                tenantId, bookId, year2026Id, LocalDate.of(2026, 6, 30),
                "OPENING_PLUS_YTD", "KINGDEE_K3", "WM_20260630",
                balances, baselines, subledgers, MAKER
        );

        assertEquals("VALIDATED", proposal.plan().status());
        assertEquals(6, proposal.tieOutResults().size());
        assertTrue(YearendRules.areAllTieOutsPassed(proposal.tieOutResults()));

        // 2. Propose Cutover with mismatched subledger (fails tie-out, status = DRAFT)
        Map<String, BigDecimal> badSubledgers = new HashMap<>(subledgers);
        badSubledgers.put("AP", new BigDecimal("9999.00000000"));
        CutoverProposal draftProposal = service.proposeCutover(
                tenantId, bookId, year2028Id, LocalDate.of(2028, 6, 30),
                "OPENING_PLUS_YTD", "KINGDEE_K3", "WM_20280630_2",
                balances, baselines, badSubledgers, MAKER
        );
        assertEquals("DRAFT", draftProposal.plan().status());
        assertFalse(YearendRules.areAllTieOutsPassed(draftProposal.tieOutResults()));
        // Attempting to approve DRAFT cutover plan with unpassed tie-outs must fail
        assertThrows(IllegalStateException.class, () ->
                service.approveCutover(tenantId, bookId, draftProposal.plan().id(), REVIEWER)
        );

        // 3. Maker cannot approve their own cutover (SoD check)
        assertThrows(IllegalArgumentException.class, () ->
                service.approveCutover(tenantId, bookId, proposal.plan().id(), MAKER)
        );

        // 4. Independent Reviewer approves
        service.approveCutover(tenantId, bookId, proposal.plan().id(), REVIEWER);

        // 4. Lock Cutover
        service.lockCutover(tenantId, bookId, proposal.plan().id(), REVIEWER);

        // 5. Verify status is LOCKED and immutable trigger rejects modifications
        try (Connection c = testSource.getConnection(); Statement s = c.createStatement()) {
            var rs = s.executeQuery(String.format("SELECT status FROM plugin_finance_cutover_plan WHERE id = '%s'", proposal.plan().id()));
            assertTrue(rs.next());
            assertEquals("LOCKED", rs.getString(1));

            // Trigger must block update
            assertThrows(Exception.class, () ->
                    s.execute(String.format("UPDATE plugin_finance_cutover_plan SET mode = 'FULL_HISTORY' WHERE id = '%s'", proposal.plan().id()))
            );
        }
    }

    @Test
    public void testAutoRuleTemplatesAndAutoReviewExecution() throws Exception {
        // 1. Create Rule Template
        AutoRuleTemplate tmpl = service.createRuleTemplate(
                tenantId, bookId, "RULE_SALES_AUTO", "Sales Template", "AR_SALES",
                List.of(new LineRule(UUID.randomUUID(), "1122", "DEBIT", "amount", Map.of(), "Sales")),
                MAKER
        );
        assertEquals("DRAFT", tmpl.status());

        // Maker cannot approve rule template (SoD check)
        assertThrows(IllegalArgumentException.class, () ->
                service.approveRuleTemplate(tenantId, bookId, tmpl.id(), MAKER)
        );

        // Independent reviewer approves rule template
        service.approveRuleTemplate(tenantId, bookId, tmpl.id(), REVIEWER);
        AutoRuleTemplate approvedTmpl = store.findRuleTemplate(testSource.getConnection(), tenantId, bookId, tmpl.id()).orElseThrow();
        assertEquals("APPROVED", approvedTmpl.status());

        // 2. Create Review Policy
        AutoReviewPolicy policy = service.createReviewPolicy(
                tenantId, bookId, "POL_SALES_SMALL", "Small Sales Policy",
                List.of("AR_SALES"), List.of("1122", "6001"),
                new BigDecimal("20000.00000000"), true, MAKER
        );
        assertEquals("DRAFT", policy.status());

        // Maker cannot approve review policy (SoD check)
        assertThrows(IllegalArgumentException.class, () ->
                service.approveReviewPolicy(tenantId, bookId, policy.id(), MAKER)
        );

        // Independent reviewer approves review policy
        service.approveReviewPolicy(tenantId, bookId, policy.id(), REVIEWER);
        AutoReviewPolicy approvedPolicy = store.findReviewPolicy(testSource.getConnection(), tenantId, bookId, policy.id()).orElseThrow();
        assertEquals("APPROVED", approvedPolicy.status());

        // 3. Execute Auto Draft and Review
        AutoRuleExecution exec = service.executeAutoDraftAndReview(
                year2026Id, approvedTmpl, approvedPolicy, UUID.randomUUID(), "EVENT_INV_888", "ERP_SALES",
                new BigDecimal("8500.00000000"), "AR_SALES", List.of("1122", "6001"), MAKER
        );

        assertTrue(exec.autoReviewed());
        assertEquals(0, exec.exceptionCount());
        assertNotNull(exec.stableEffectKey());

        // Verify stored in DB
        try (Connection c = testSource.getConnection(); Statement s = c.createStatement()) {
            var rs = s.executeQuery(String.format("SELECT auto_reviewed, exception_count FROM plugin_finance_auto_rule_execution WHERE id = '%s'", exec.id()));
            assertTrue(rs.next());
            assertTrue(rs.getBoolean("auto_reviewed"));
            assertEquals(0, rs.getInt("exception_count"));

            // Immutability trigger on execution record
            assertThrows(Exception.class, () ->
                    s.execute(String.format("UPDATE plugin_finance_auto_rule_execution SET auto_reviewed = false WHERE id = '%s'", exec.id()))
            );
        }
    }

    @Test
    public void testJournalCorrectionCasVsSmallAndLineWatermark() throws Exception {
        UUID dummyCorrectionJid = UUID.randomUUID();
        UUID dummySourceJid = UUID.randomUUID();

        // 1. CAS prior period adjustment with 6901 passes
        JournalCorrectionRecord casRec = service.createJournalCorrection(
                tenantId, bookId, year2026Id, year2026Id,
                dummySourceJid, 10L, YearendRules.sha256Hex("src"),
                dummyCorrectionJid, "PRIOR_PERIOD_ADJUSTMENT", "CAS",
                "6901", "Correction of 2025 depreciation",
                List.of(new CorrectionAllocation(1, new BigDecimal("200.00000000"), UUID.randomUUID())),
                Map.of(1, new BigDecimal("500.00000000")),
                MAKER
        );
        assertNotNull(casRec.id());
        assertEquals("CAS", casRec.standard());
        assertEquals("6901", casRec.casAccountCode());

        // 2. SMALL standard with 6901 rejected
        assertThrows(IllegalArgumentException.class, () ->
                service.createJournalCorrection(
                        tenantId, bookId, year2026Id, year2026Id,
                        dummySourceJid, 10L, YearendRules.sha256Hex("src"),
                        dummyCorrectionJid, "PRIOR_PERIOD_ADJUSTMENT", "SMALL",
                        "6901", "Should fail on SMALL",
                        List.of(new CorrectionAllocation(1, new BigDecimal("200.00000000"), UUID.randomUUID())),
                        Map.of(1, new BigDecimal("500.00000000")),
                        MAKER
                )
        );

        // 3. Allocation exceeding watermark rejected
        assertThrows(IllegalArgumentException.class, () ->
                service.createJournalCorrection(
                        tenantId, bookId, year2026Id, year2026Id,
                        dummySourceJid, 10L, YearendRules.sha256Hex("src"),
                        dummyCorrectionJid, "PARTIAL_REVERSAL", "CAS",
                        null, "Exceeds watermark",
                        List.of(new CorrectionAllocation(1, new BigDecimal("600.00000000"), UUID.randomUUID())),
                        Map.of(1, new BigDecimal("500.00000000")),
                        MAKER
                )
        );

        // 4. Cross-year prior period error correction (sourceYear=2026, targetYear=2027) succeeds
        JournalCorrectionRecord crossYearRec = service.createJournalCorrection(
                tenantId, bookId, year2027Id, year2026Id,
                dummySourceJid, 10L, YearendRules.sha256Hex("src"),
                UUID.randomUUID(), "PRIOR_PERIOD_ADJUSTMENT", "CAS",
                "6901", "Cross-year adjustment booked in 2027 for 2026",
                List.of(new CorrectionAllocation(1, new BigDecimal("300.00000000"), UUID.randomUUID())),
                Map.of(1, new BigDecimal("500.00000000")),
                MAKER
        );
        assertEquals(year2027Id, crossYearRec.targetYearId());
        assertEquals(year2026Id, crossYearRec.sourceYearId());
    }

    @Test
    public void testPeriodCloseChecklistAndSignOffGate() throws Exception {
        Map<String, BigDecimal> gl = Map.of(
                "AR", new BigDecimal("1000.00000000"),
                "AP", new BigDecimal("1000.00000000"),
                "ASSET", BigDecimal.ZERO,
                "INVENTORY", BigDecimal.ZERO,
                "PAYROLL", BigDecimal.ZERO
        );

        // 1. Preview Period Close with all gates passing
        PeriodCloseCheckPlan plan = service.previewPeriodClose(
                tenantId, bookId, year2026Id, period1Id,
                0, new BigDecimal("1000.00000000"), new BigDecimal("1000.00000000"),
                true, gl, gl, true, true, MAKER
        );

        assertTrue(plan.allChecksPassed());
        assertEquals("PREVIEWED", plan.status());

        // Maker cannot sign off period close (SoD check)
        assertThrows(IllegalArgumentException.class, () ->
                service.executePeriodClose(tenantId, bookId, plan.id(), MAKER)
        );

        // Preview Period Close with unposted journals (gate fails)
        PeriodCloseCheckPlan failedPlan = service.previewPeriodClose(
                tenantId, bookId, year2026Id, UUID.randomUUID(),
                3, new BigDecimal("1000.00000000"), new BigDecimal("1000.00000000"),
                true, gl, gl, true, true, MAKER
        );
        assertFalse(failedPlan.allChecksPassed());
        // Cannot execute close when gate failed
        assertThrows(IllegalStateException.class, () ->
                service.executePeriodClose(tenantId, bookId, failedPlan.id(), REVIEWER)
        );

        // 2. Execute Period Close Sign-Off with independent reviewer
        service.executePeriodClose(tenantId, bookId, plan.id(), REVIEWER);

        try (Connection c = testSource.getConnection(); Statement s = c.createStatement()) {
            var rs = s.executeQuery(String.format("SELECT status FROM plugin_finance_period_close_check_plan WHERE id = '%s'", plan.id()));
            assertTrue(rs.next());
            assertEquals("CLOSED", rs.getString(1));
        }
    }

    @Test
    public void testCarryBatchPlanAndRestatementImpact() throws Exception {
        UUID a1 = UUID.randomUUID();
        UUID a2 = UUID.randomUUID();

        List<CarryBalanceItem> balances = List.of(
                new CarryBalanceItem(UUID.randomUUID(), a1, a1, "1122", "1122", "CNY", "DEBIT",
                        new BigDecimal("25000.00000000"), BigDecimal.ZERO, new BigDecimal("25000.00000000"), Map.of("cust", "C001")),
                new CarryBalanceItem(UUID.randomUUID(), a2, a2, "2202", "2202", "CNY", "CREDIT",
                        new BigDecimal("25000.00000000"), BigDecimal.ZERO, new BigDecimal("25000.00000000"), Map.of("vend", "V001"))
        );

        List<CarrySubledgerItem> subledgers = List.of(
                new CarrySubledgerItem(UUID.randomUUID(), "AR_OPEN_INVOICE", "INV-2026-001", UUID.randomUUID(),
                        LocalDate.of(2026, 11, 15), "CNY", new BigDecimal("25000.00000000"), new BigDecimal("25000.00000000"),
                        BigDecimal.ONE, new BigDecimal("25000.00000000"), Map.of())
        );

        // 1. Preview BASE Carry Batch
        CarryBatchPlan baseBatch = service.previewCarryBatch(
                tenantId, bookId, year2026Id, year2027Id,
                1, 500L, 0, "MAP_V1", "BASE", null, "Initial 2027 opening transfer",
                balances, subledgers, Map.of("BALANCE_SHEET_TOTAL", new BigDecimal("100000.00000000")), MAKER
        );

        assertEquals("BASE", baseBatch.kind());
        assertEquals("PREVIEWED", baseBatch.status());
        assertEquals(3, baseBatch.restatements().size());

        // Maker cannot commit carry batch (SoD check)
        assertThrows(IllegalArgumentException.class, () ->
                service.commitCarryBatch(tenantId, bookId, baseBatch.id(), MAKER)
        );

        // DELTA batch referencing uncommitted previous batch fails
        assertThrows(IllegalStateException.class, () ->
                service.previewCarryBatch(
                        tenantId, bookId, year2026Id, year2027Id,
                        2, 550L, 1, "MAP_V1", "DELTA", baseBatch.id(), "Uncommitted prev batch",
                        balances, subledgers, Map.of(), MAKER
                )
        );

        // DELTA batch referencing non-existent previous batch fails
        UUID nonExistentBatchId = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () ->
                service.previewCarryBatch(
                        tenantId, bookId, year2026Id, year2027Id,
                        2, 550L, 1, "MAP_V1", "DELTA", nonExistentBatchId, "Non-existent prev batch",
                        balances, subledgers, Map.of(), MAKER
                )
        );

        // 2. Commit BASE batch
        service.commitCarryBatch(tenantId, bookId, baseBatch.id(), REVIEWER);

        // 3. DELTA batch requires previousBatchId
        assertThrows(IllegalArgumentException.class, () ->
                service.previewCarryBatch(
                        tenantId, bookId, year2026Id, year2027Id,
                        2, 550L, 1, "MAP_V1", "DELTA", null, "Delta without previous id must fail",
                        balances, subledgers, Map.of(), MAKER
                )
        );

        // DELTA batch with sourceCloseVersion <= previousBatch fails
        assertThrows(IllegalArgumentException.class, () ->
                service.previewCarryBatch(
                        tenantId, bookId, year2026Id, year2027Id,
                        1, 550L, 1, "MAP_V1", "DELTA", baseBatch.id(), "Non-increasing close version must fail",
                        balances, subledgers, Map.of(), MAKER
                )
        );

        // 4. DELTA batch with valid committed previousBatchId and increasing close version succeeds
        CarryBatchPlan deltaBatch = service.previewCarryBatch(
                tenantId, bookId, year2026Id, year2027Id,
                2, 550L, 1, "MAP_V1", "DELTA", baseBatch.id(), "Post-close adjustment delta carry",
                balances, subledgers, Map.of("BALANCE_SHEET_TOTAL", new BigDecimal("100000.00000000")), MAKER
        );
        assertEquals("DELTA", deltaBatch.kind());
        assertEquals(baseBatch.id(), deltaBatch.previousBatchId());

        // 5. Unique constraint check: inserting identical (tenant, book, source_year, source_close_version, target_year) fails
        assertThrows(Exception.class, () ->
                service.previewCarryBatch(
                        tenantId, bookId, year2026Id, year2027Id,
                        1, 500L, 0, "MAP_V1", "BASE", null, "Duplicate batch key",
                        balances, subledgers, Map.of(), MAKER
                )
        );

        // 6. Immutability trigger blocks DELETE on restatement items
        try (Connection c = testSource.getConnection(); Statement s = c.createStatement()) {
            assertThrows(Exception.class, () ->
                    s.execute("DELETE FROM plugin_finance_carry_batch_restatement")
            );
        }
    }

    @Test
    void testYearendControllerBookAccessScopeGuard() throws Exception {
        UUID userAId = UUID.randomUUID();
        try (Connection c = testSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("""
                CREATE TABLE IF NOT EXISTS plugin_finance_member (
                    tenant_id uuid NOT NULL,
                    book_id uuid NOT NULL,
                    user_id uuid NOT NULL,
                    username text NOT NULL,
                    role text NOT NULL,
                    enabled boolean NOT NULL DEFAULT true,
                    year_scope text NOT NULL DEFAULT 'ALL',
                    PRIMARY KEY(tenant_id, book_id, user_id)
                )
            """);
            s.execute(String.format(
                "INSERT INTO plugin_finance_member (tenant_id, book_id, user_id, username, role, enabled) " +
                "VALUES ('%s', '%s', '%s', 'user_a', 'OWNER', true) " +
                "ON CONFLICT (tenant_id, book_id, user_id) DO UPDATE SET enabled = true",
                tenantId, bookId, userAId
            ));
        }

        YearendController controller = new YearendController(service, testSource);
        CutoverCreateRequest req = new CutoverCreateRequest(
                LocalDate.of(2026, 1, 1), "FULL_HISTORY", "LEGACY_ERP", "WM-TEST",
                List.of(), List.of(), Map.of()
        );

        // 1. Plain unprivileged user without book membership is rejected with 403 SCOPE_DENIED
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("plain", "password")
        );
        ResponseEntity<?> plainResp = controller.proposeCutover(
                tenantId, bookId, year2026Id, null, null, req
        );
        assertEquals(403, plainResp.getStatusCode().value());
        assertTrue(plainResp.getBody() instanceof Map);
        assertEquals("SCOPE_DENIED", ((Map<?, ?>) plainResp.getBody()).get("code"));

        // 2. Spoof attempt by plain user passing user_a's ID in header is still rejected with 403
        ResponseEntity<?> spoofResp = controller.proposeCutover(
                tenantId, bookId, year2026Id, userAId.toString(), null, req
        );
        assertEquals(403, spoofResp.getStatusCode().value());
        assertEquals("SCOPE_DENIED", ((Map<?, ?>) spoofResp.getBody()).get("code"));

        // 3. Authorized book member user_a is permitted (not 403)
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("user_a", "password")
        );
        ResponseEntity<?> memberResp = controller.proposeCutover(
                tenantId, bookId, year2026Id, userAId.toString(), userAId.toString(), req
        );
        assertNotEquals(403, memberResp.getStatusCode().value());

        // 4. Admin user is permitted unconditionally (not 403)
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("admin", "password", "ROLE_ADMIN")
        );
        ResponseEntity<?> adminResp = controller.proposeCutover(
                tenantId, bookId, year2026Id, null, null, req
        );
        assertNotEquals(403, adminResp.getStatusCode().value());

        SecurityContextHolder.clearContext();
    }
}
