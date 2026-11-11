package com.lumira.finance.payrolltax;

import com.lumira.finance.FinanceTestCredentials;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.junit.jupiter.api.Assertions.*;

public class PayrollTaxExtPostgresRlsTest {

    @Test
    @DisplayName("PostgreSQL 18: Isolated schema migration, all 9 tables RLS check, and non-superuser tenant isolation")
    void postgresMigrationRlsAndStoreLifecycle() throws Exception {
        String url = System.getenv("FINANCE_TEST_JDBC_URL");
        if (url == null || url.isBlank()) {
            url = "jdbc:postgresql://127.0.0.1:15489/lumira_qa_finance_kernel";
        }

        if (!url.matches("jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost|\\[::1\\]):[0-9]{1,5}/lumira_qa_finance_[a-z0-9_]+")
                || url.contains("lumira_qa_finance_web")) {
            throw new IllegalStateException("Isolated loopback lumira_qa_finance_* JDBC URL required (never lumira_qa_finance_web)");
        }

        String user = FinanceTestCredentials.databaseUser();
        String pass = FinanceTestCredentials.databasePassword();

        Path migPath = java.util.List.of(
            Path.of(System.getProperty("payroll.tax.ext.migration.candidate", "")),
            Path.of("plugins/finance/migrations/V35__payroll_tax_extensions.sql"),
            Path.of("../migrations/V35__payroll_tax_extensions.sql"),
            Path.of(".runtime/v4-wave2/payroll-tax-ext/migration-candidate.sql"),
            Path.of("../../../.runtime/v4-wave2/payroll-tax-ext/migration-candidate.sql"),
            Path.of("migration-candidate.sql")
        ).stream().filter(p -> !p.toString().isBlank() && Files.exists(p)).findFirst().orElseThrow();

        var adminDs = new PGSimpleDataSource();
        adminDs.setURL(url);
        adminDs.setUser(user);
        adminDs.setPassword(pass);

        String schema = "test_w10_" + UUID.randomUUID().toString().replace("-", "");
        String rlsRole = "test_w10_rls_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        var adminJdbc = JdbcClient.create(adminDs);
        adminJdbc.sql("CREATE SCHEMA " + schema).update();
        adminJdbc.sql("CREATE ROLE " + rlsRole + " NOLOGIN NOSUPERUSER NOBYPASSRLS").update();

        try {
            var tenantDs = new PGSimpleDataSource();
            tenantDs.setURL(url);
            tenantDs.setUser(user);
            tenantDs.setPassword(pass);
            tenantDs.setCurrentSchema(schema);

            try (var c = tenantDs.getConnection()) {
                String ddl = Files.readString(migPath);
                c.createStatement().execute(ddl);
            }

            adminJdbc.sql("GRANT USAGE ON SCHEMA " + schema + " TO " + rlsRole).update();
            adminJdbc.sql("GRANT ALL ON ALL TABLES IN SCHEMA " + schema + " TO " + rlsRole).update();
            adminJdbc.sql("GRANT ALL ON ALL SEQUENCES IN SCHEMA " + schema + " TO " + rlsRole).update();

            // Verify all 9 tables have relrowsecurity AND relforcerowsecurity
            Long rlsTableCount = adminJdbc.sql("""
                SELECT count(*) FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = ?
                  AND c.relname IN (
                      'plugin_finance_payroll_bonus_batch',
                      'plugin_finance_payroll_bonus_item',
                      'plugin_finance_payroll_bonus_election',
                      'plugin_finance_payroll_labor_batch',
                      'plugin_finance_payroll_labor_item',
                      'plugin_finance_tax_policy_pack',
                      'plugin_finance_tax_qualification_attestation',
                      'plugin_finance_tax_calendar_pack',
                      'plugin_finance_tax_form_lock'
                  )
                  AND c.relrowsecurity
                  AND c.relforcerowsecurity
            """)
            .param(schema)
            .query(Long.class)
            .single();

            assertEquals(9L, rlsTableCount, "All 9 tables must have ENABLE and FORCE ROW LEVEL SECURITY");

            // Setup test tenants and entities
            UUID tenantA = UUID.randomUUID();
            UUID tenantB = UUID.randomUUID();
            UUID entityId = UUID.randomUUID();
            UUID empId = UUID.randomUUID();
            UUID preparerA = UUID.randomUUID();
            UUID reviewerA = UUID.randomUUID();
            UUID approverA = UUID.randomUUID();

            var rlsDs = new PGSimpleDataSource();
            rlsDs.setURL(url);
            rlsDs.setUser(user);
            rlsDs.setPassword(pass);
            rlsDs.setCurrentSchema(schema);

            try (var conn = rlsDs.getConnection()) {
                var stmt = conn.createStatement();
                stmt.execute("SET ROLE " + rlsRole);

                // --- 1. Tenant A operations ---
                stmt.execute("SET app.current_tenant = '" + tenantA + "'");

                var store = new PayrollTaxExtJdbcStore(rlsDs);
                var service = new PayrollTaxExtService(store);

                // Create and calculate Bonus batch for Tenant A
                var bonusCmd = new PayrollTaxExtModel.CreateBonusBatchCommand(
                        entityId,
                        2026,
                        "YearEnd_2026_Bonus",
                        LocalDate.of(2026, 12, 25),
                        List.of(new PayrollTaxExtModel.BonusItemEntry(
                                empId,
                                "60000.00",
                                "150000.00",
                                BonusCalculationEngine.BonusMode.SEPARATE_MONTHLY_QUOTIENT
                        ))
                );

                var bonusBatch = service.createAndCalculateBonusBatch(tenantA, preparerA, bonusCmd);
                assertNotNull(bonusBatch.id());

                // SoD check: Reviewer cannot be Preparer
                assertThrows(IllegalStateException.class, () ->
                        service.reviewBonusBatch(tenantA, bonusBatch.id(), preparerA, preparerA, 0L)
                );

                // Independent Reviewer reviews
                service.reviewBonusBatch(tenantA, bonusBatch.id(), reviewerA, preparerA, 0L);

                // SoD check: Approver cannot be Preparer or Reviewer
                assertThrows(IllegalStateException.class, () ->
                        service.approveBonusBatch(tenantA, bonusBatch.id(), preparerA, preparerA, reviewerA, 1L)
                );
                assertThrows(IllegalStateException.class, () ->
                        service.approveBonusBatch(tenantA, bonusBatch.id(), reviewerA, preparerA, reviewerA, 1L)
                );

                // Independent Approver approves
                service.approveBonusBatch(tenantA, bonusBatch.id(), approverA, preparerA, reviewerA, 1L);

                // Lock batch -> records election
                service.lockBonusBatch(tenantA, bonusBatch.id(), entityId, 2026, bonusBatch.items(), 2L);

                // PAY03 Guard: Second separate bonus election in same year for same person MUST throw!
                var bonusCmdDuplicate = new PayrollTaxExtModel.CreateBonusBatchCommand(
                        entityId,
                        2026,
                        "Second_Bonus_Attempt",
                        LocalDate.of(2026, 12, 31),
                        List.of(new PayrollTaxExtModel.BonusItemEntry(
                                empId,
                                "20000.00",
                                "150000.00",
                                BonusCalculationEngine.BonusMode.SEPARATE_MONTHLY_QUOTIENT
                        ))
                );
                IllegalStateException dupEx = assertThrows(IllegalStateException.class, () ->
                        service.createAndCalculateBonusBatch(tenantA, preparerA, bonusCmdDuplicate)
                );
                assertTrue(dupEx.getMessage().contains("BONUS_SEPARATE_CALCULATION_ALREADY_USED"));

                // --- 2. Tenant A Labor Batch Lifecycle & SoD ---
                var laborCmd = new PayrollTaxExtModel.CreateLaborBatchCommand(
                        entityId,
                        "2026-11",
                        "Labor_Consulting_202611",
                        LocalDate.of(2026, 11, 28),
                        List.of(
                                new PayrollTaxExtModel.LaborItemEntry(
                                        UUID.randomUUID(),
                                        LaborRemunerationEngine.RemunerationType.LABOR_SERVICE,
                                        "10000.00",
                                        true,
                                        "0.07",
                                        false,
                                        false,
                                        false,
                                        null
                                ),
                                new PayrollTaxExtModel.LaborItemEntry(
                                        UUID.randomUUID(),
                                        LaborRemunerationEngine.RemunerationType.LABOR_SERVICE,
                                        "25000.00",
                                        true,
                                        "0.07",
                                        false,
                                        false,
                                        true,
                                        "INV-PROXY-TEST-202611-001"
                                )
                        )
                );
                var laborBatch = service.createAndCalculateLaborBatch(tenantA, preparerA, laborCmd);
                assertNotNull(laborBatch.id());
                assertEquals(PayrollTaxExtModel.BatchState.CALCULATED, laborBatch.state());
                assertEquals(2, laborBatch.items().size());

                // SoD check: Reviewer cannot be Preparer
                assertThrows(IllegalStateException.class, () ->
                        service.reviewLaborBatch(tenantA, laborBatch.id(), preparerA, 0L)
                );

                // Independent Reviewer reviews
                service.reviewLaborBatch(tenantA, laborBatch.id(), reviewerA, 0L);

                // SoD check: Approver cannot be Reviewer or Preparer
                assertThrows(IllegalStateException.class, () ->
                        service.approveLaborBatch(tenantA, laborBatch.id(), preparerA, 1L)
                );
                assertThrows(IllegalStateException.class, () ->
                        service.approveLaborBatch(tenantA, laborBatch.id(), reviewerA, 1L)
                );

                // Independent Approver approves
                service.approveLaborBatch(tenantA, laborBatch.id(), approverA, 1L);

                // Lock labor batch
                service.lockLaborBatch(tenantA, laborBatch.id(), 2L);
                var fetchedLabor = service.getLaborBatch(tenantA, laborBatch.id());
                assertEquals(PayrollTaxExtModel.BatchState.LOCKED, fetchedLabor.state());
                assertNotNull(fetchedLabor.lockedAt());

                // --- 3. Calendar Pack & Form Lock persistence ---
                store.insertCalendarPack(
                        tenantA,
                        "CAL_CN_2026_V1",
                        "CN_NATIONAL",
                        2026,
                        "CAL_CN_2026_V1",
                        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                        "APPROVED",
                        Map.of("2026-02-15", "SPRING_FESTIVAL_HOLIDAY", "2026-02-16", "SPRING_FESTIVAL_HOLIDAY")
                );
                var calPackOpt = store.findCalendarPack(tenantA, "CAL_CN_2026_V1");
                assertTrue(calPackOpt.isPresent());
                assertEquals("CAL_CN_2026_V1", calPackOpt.get().packVersion());

                // Form Lock test
                UUID taxObligationId = UUID.randomUUID();
                var lockCmd = new PayrollTaxExtModel.LockFormCommand(
                        taxObligationId,
                        "VAT_GENERAL",
                        1,
                        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                        Map.of("exportSales", "0.00", "inputDeduction", "0.00"),
                        java.util.Set.of()
                );
                UUID formLockId = service.lockFormVersion(tenantA, approverA, lockCmd);
                assertNotNull(formLockId);

                // --- 4. Tenant B isolation verification ---
                stmt.execute("SET app.current_tenant = '" + tenantB + "'");

                // Tenant B must see 0 bonus batches and 0 labor batches
                var rsB = stmt.executeQuery("SELECT count(*) FROM plugin_finance_payroll_bonus_batch");
                assertTrue(rsB.next());
                assertEquals(0L, rsB.getLong(1), "Tenant B must not see Tenant A bonus batches under RLS");

                var rsBLabor = stmt.executeQuery("SELECT count(*) FROM plugin_finance_payroll_labor_batch");
                assertTrue(rsBLabor.next());
                assertEquals(0L, rsBLabor.getLong(1), "Tenant B must not see Tenant A labor batches under RLS");

                var rsBCal = stmt.executeQuery("SELECT count(*) FROM plugin_finance_tax_calendar_pack");
                assertTrue(rsBCal.next());
                assertEquals(0L, rsBCal.getLong(1), "Tenant B must not see Tenant A calendar packs under RLS");

                // Tenant B inserting a row with Tenant A's tenant_id must violate WITH CHECK policy
                assertThrows(Exception.class, () ->
                        stmt.executeUpdate("INSERT INTO plugin_finance_payroll_bonus_batch (" +
                                "id, tenant_id, withholding_entity_id, tax_year, bonus_name, pay_date, state, preparer_id" +
                                ") VALUES ('" + UUID.randomUUID() + "', '" + tenantA + "', '" + entityId + "', 2026, 'Hack', '2026-12-25', 'DRAFT', '" + preparerA + "')")
                );

                // Switch back to Tenant A and verify data integrity
                stmt.execute("SET app.current_tenant = '" + tenantA + "'");
                var rsA = stmt.executeQuery("SELECT count(*) FROM plugin_finance_payroll_bonus_batch");
                assertTrue(rsA.next());
                assertEquals(1L, rsA.getLong(1), "Tenant A must see its own bonus batch");

                var rsALabor = stmt.executeQuery("SELECT count(*) FROM plugin_finance_payroll_labor_batch");
                assertTrue(rsALabor.next());
                assertEquals(1L, rsALabor.getLong(1), "Tenant A must see its own labor batch");

                stmt.execute("RESET ROLE");
            }
        } finally {
            adminJdbc.sql("DROP SCHEMA " + schema + " CASCADE").update();
            adminJdbc.sql("DROP ROLE IF EXISTS " + rlsRole).update();
        }
    }
}
