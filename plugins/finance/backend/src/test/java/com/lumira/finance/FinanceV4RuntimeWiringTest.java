package com.lumira.finance;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.common.*;
import com.lumira.platform.identity.api.AccountOwnerPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;

class FinanceV4RuntimeWiringTest {
    @Test
    void receivablesAssetsAndPayrollControllersWireWithoutStartupDatabaseWork() {
        var db = mock(FinanceStore.class);
        var permissions = mock(AdministrationPort.class);
        var owners = mock(AccountOwnerPort.class);
        var users = mock(UserDirectoryPort.class);
        var tx = mock(PlatformTransactionManager.class);
        var projection = mock(AuthorizationContextProjectionPort.class);
        try (var context = new AnnotationConfigApplicationContext()) {
            FinanceJournalPersonWiringTest.ports(context, db, permissions, owners, users, tx);
            context.registerBean(AuthorizationContextProjectionPort.class, () -> projection);
            context.register(
                    FinanceJournalPersonWiringTest.Transactions.class,
                    FinanceJournalPersonGate.class,
                    FinanceService.class,
                    FinanceReceivables.class,
                    FinanceReceivablesController.class,
                    FinanceAssets.class,
                    FinanceAssetsController.class,
                    FinancePayroll.class,
                    FinancePayrollController.class);
            assertDoesNotThrow(context::refresh);
            assertNotNull(context.getBean(FinanceReceivablesController.class));
            assertNotNull(context.getBean(FinanceAssetsController.class));
            assertNotNull(context.getBean(FinancePayrollController.class));
            verifyNoInteractions(db, permissions, owners, users, tx, projection);
        }
    }

    @Test
    void v4ProducersAndGovernanceControllersWireCleanlyWithoutStartupDatabaseWork() {
        var db = mock(FinanceStore.class);
        var permissions = mock(AdministrationPort.class);
        var owners = mock(AccountOwnerPort.class);
        var users = mock(UserDirectoryPort.class);
        var tx = mock(PlatformTransactionManager.class);
        var projection = mock(AuthorizationContextProjectionPort.class);
        try (var context = new AnnotationConfigApplicationContext()) {
            FinanceJournalPersonWiringTest.ports(context, db, permissions, owners, users, tx);
            context.registerBean(AuthorizationContextProjectionPort.class, () -> projection);
            context.register(
                    FinanceJournalPersonWiringTest.Transactions.class,
                    FinanceJournalPersonGate.class,
                    FinanceService.class,
                    FinanceReceivablesProducer.class,
                    FinanceReceivables.class,
                    FinanceReceivablesController.class,
                    FinanceReceivablesDecisionController.class,
                    FinanceAssetsProducer.class,
                    FinanceAssets.class,
                    FinanceAssetsController.class,
                    FinanceAssetsQuotaController.class,
                    FinancePayroll.class,
                    FinancePayrollController.class,
                    FinancePayrollGovernanceController.class,
                    FinanceTaxObligations.class,
                    FinanceTaxObligationsController.class,
                    FinanceArchiveExports.class,
                    FinanceArchiveExportsController.class,
                    FinanceReportDrafts.class,
                    FinanceReportDraftsController.class,
                    FinancePayableSettlements.class,
                    FinancePayableSettlementsController.class);
            assertDoesNotThrow(context::refresh);
            assertNotNull(context.getBean(FinanceReceivablesDecisionController.class));
            assertNotNull(context.getBean(FinanceAssetsQuotaController.class));
            assertNotNull(context.getBean(FinancePayrollGovernanceController.class));
            assertNotNull(context.getBean(FinanceTaxObligationsController.class));
            assertNotNull(context.getBean(FinanceArchiveExportsController.class));
            assertNotNull(context.getBean(FinanceReportDraftsController.class));
            assertNotNull(context.getBean(FinancePayableSettlementsController.class));
            verifyNoInteractions(db, permissions, owners, users, tx, projection);
        }
    }

    @Test
    void wave2CandidateControllersAndServicesWireCleanlyWithoutStartupDatabaseWork() {
        var db = mock(FinanceStore.class);
        var permissions = mock(AdministrationPort.class);
        var owners = mock(AccountOwnerPort.class);
        var users = mock(UserDirectoryPort.class);
        var tx = mock(PlatformTransactionManager.class);
        var projection = mock(AuthorizationContextProjectionPort.class);
        var dataSource = mock(javax.sql.DataSource.class);
        var jdbc = mock(org.springframework.jdbc.core.simple.JdbcClient.class);
        try (var context = new AnnotationConfigApplicationContext()) {
            FinanceJournalPersonWiringTest.ports(context, db, permissions, owners, users, tx);
            context.registerBean(AuthorizationContextProjectionPort.class, () -> projection);
            context.registerBean(javax.sql.DataSource.class, () -> dataSource);
            context.registerBean(org.springframework.jdbc.core.simple.JdbcClient.class, () -> jdbc);
            context.register(
                    FinanceJournalPersonWiringTest.Transactions.class,
                    FinanceJournalPersonGate.class,
                    FinanceService.class,
                    // V25
                    FinancePayableSettlements.class,
                    FinancePayableSettlementsController.class,
                    // W01 (V26) OrgFx
                    com.lumira.finance.orgfx.OrgFxJdbcStore.class,
                    com.lumira.finance.orgfx.OrgFxService.class,
                    com.lumira.finance.orgfx.OrgFxController.class,
                    // W02 (V27) IamOps
                    com.lumira.finance.iamops.IamOpsStore.class,
                    com.lumira.finance.iamops.IamOpsService.class,
                    com.lumira.finance.iamops.IamOpsController.class,
                    // W03 (V28) DimensionsBudget
                    com.lumira.finance.budget.FinanceBudgetDimensionsStore.class,
                    com.lumira.finance.budget.FinanceBudgetDimensionsService.class,
                    com.lumira.finance.budget.FinanceBudgetDimensionsController.class,
                    // W04 (V29) EvidenceFapiao
                    com.lumira.finance.fapiao.EvidenceFapiaoJdbcStore.class,
                    com.lumira.finance.fapiao.EvidenceFapiaoService.class,
                    com.lumira.finance.fapiao.FinanceEvidenceFapiaoController.class,
                    // W05 (V30) Expense
                    com.lumira.finance.expense.ExpenseJdbcStore.class,
                    com.lumira.finance.expense.TravelPolicyEngine.class,
                    com.lumira.finance.expense.ExpenseRulesEngine.class,
                    com.lumira.finance.expense.ExpenseAccountingAdapter.class,
                    com.lumira.finance.expense.ExpenseService.class,
                    com.lumira.finance.expense.ExpenseController.class,
                    // W06 (V31) SubledgerExt
                    com.lumira.finance.subledgerext.SubledgerExtStore.class,
                    com.lumira.finance.subledgerext.SubledgerExtService.class,
                    com.lumira.finance.subledgerext.SubledgerExtController.class,
                    // W07 (V32) Treasury
                    com.lumira.finance.treasury.TreasuryRepository.class,
                    com.lumira.finance.treasury.TreasuryService.class,
                    com.lumira.finance.treasury.TreasuryController.class,
                    // W08 (V33) InventoryAssets
                    com.lumira.finance.inventory.InventoryService.class,
                    com.lumira.finance.inventory.AssetExtensionService.class,
                    com.lumira.finance.inventory.InventoryRestController.class,
                    // W09 (V34) Yearend
                    com.lumira.finance.yearend.YearendStore.class,
                    com.lumira.finance.yearend.YearendService.class,
                    com.lumira.finance.yearend.YearendController.class,
                    // W10 (V35) PayrollTaxExt
                    com.lumira.finance.payrolltax.PayrollTaxExtJdbcStore.class,
                    com.lumira.finance.payrolltax.PayrollTaxExtService.class,
                    com.lumira.finance.payrolltax.PayrollTaxExtController.class,
                    // W11 (V36) Statutory
                    com.lumira.finance.statutory.StatutoryStore.class,
                    com.lumira.finance.statutory.StatutoryService.class,
                    com.lumira.finance.statutory.StatutoryController.class,
                    // W12 (V37) ArchiveAi
                    com.lumira.finance.archiveai.ArchiveAiJdbcStore.class,
                    com.lumira.finance.archiveai.ArchiveAiService.class,
                    com.lumira.finance.archiveai.ArchiveAiController.class);

            assertDoesNotThrow(context::refresh);

            // V25 assertions
            assertNotNull(context.getBean(FinancePayableSettlementsController.class));
            assertNotNull(context.getBean(FinancePayableSettlements.class));

            // V26 (W01) OrgFx
            assertNotNull(context.getBean(com.lumira.finance.orgfx.OrgFxController.class));
            assertNotNull(context.getBean(com.lumira.finance.orgfx.OrgFxService.class));

            // V27 (W02) IamOps
            assertNotNull(context.getBean(com.lumira.finance.iamops.IamOpsController.class));
            assertNotNull(context.getBean(com.lumira.finance.iamops.IamOpsService.class));

            // V28 (W03) DimensionsBudget
            assertNotNull(context.getBean(com.lumira.finance.budget.FinanceBudgetDimensionsController.class));
            assertNotNull(context.getBean(com.lumira.finance.budget.FinanceBudgetDimensionsService.class));

            // V29 (W04) EvidenceFapiao
            assertNotNull(context.getBean(com.lumira.finance.fapiao.FinanceEvidenceFapiaoController.class));
            assertNotNull(context.getBean(com.lumira.finance.fapiao.EvidenceFapiaoService.class));

            // V30 (W05) Expense
            assertNotNull(context.getBean(com.lumira.finance.expense.ExpenseController.class));
            assertNotNull(context.getBean(com.lumira.finance.expense.ExpenseService.class));

            // V31 (W06) SubledgerExt
            assertNotNull(context.getBean(com.lumira.finance.subledgerext.SubledgerExtController.class));
            assertNotNull(context.getBean(com.lumira.finance.subledgerext.SubledgerExtService.class));

            // V32 (W07) Treasury
            assertNotNull(context.getBean(com.lumira.finance.treasury.TreasuryController.class));
            assertNotNull(context.getBean(com.lumira.finance.treasury.TreasuryService.class));

            // V33 (W08) InventoryAssets
            assertNotNull(context.getBean(com.lumira.finance.inventory.InventoryRestController.class));
            assertNotNull(context.getBean(com.lumira.finance.inventory.InventoryService.class));

            // V34 (W09) Yearend
            assertNotNull(context.getBean(com.lumira.finance.yearend.YearendController.class));
            assertNotNull(context.getBean(com.lumira.finance.yearend.YearendService.class));

            // V35 (W10) PayrollTaxExt
            assertNotNull(context.getBean(com.lumira.finance.payrolltax.PayrollTaxExtController.class));
            assertNotNull(context.getBean(com.lumira.finance.payrolltax.PayrollTaxExtService.class));

            // V36 (W11) Statutory
            assertNotNull(context.getBean(com.lumira.finance.statutory.StatutoryController.class));
            assertNotNull(context.getBean(com.lumira.finance.statutory.StatutoryService.class));

            // V37 (W12) ArchiveAi
            assertNotNull(context.getBean(com.lumira.finance.archiveai.ArchiveAiController.class));
            assertNotNull(context.getBean(com.lumira.finance.archiveai.ArchiveAiService.class));

            verifyNoInteractions(db, permissions, owners, users, tx, projection, dataSource, jdbc);
        }
    }

    @Test
    void missingPublicProjectionFailsClosedForV4Services() {
        for (Class<?> serviceClass :
                java.util.List.of(
                        FinanceReceivables.class,
                        FinanceAssets.class,
                        FinancePayroll.class,
                        FinanceTaxObligations.class,
                        FinanceArchiveExports.class,
                        FinanceReportDrafts.class,
                        FinancePayableSettlements.class)) {
            try (var context = new AnnotationConfigApplicationContext()) {
                FinanceJournalPersonWiringTest.ports(
                        context,
                        mock(FinanceStore.class),
                        mock(AdministrationPort.class),
                        mock(AccountOwnerPort.class),
                        mock(UserDirectoryPort.class),
                        mock(PlatformTransactionManager.class));
                context.register(
                        FinanceJournalPersonGate.class, FinanceService.class, serviceClass);
                var failure = assertThrows(BeanCreationException.class, context::refresh);
                assertTrue(
                        failure.getMessage().contains("AuthorizationContextProjectionPort"),
                        failure.getMessage());
            }
        }
    }
}
