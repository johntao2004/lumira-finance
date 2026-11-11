package com.lumira.finance;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuthorizationContextProjectionPort;
import com.lumira.platform.common.UserDirectoryPort;
import com.lumira.platform.identity.api.AccountOwnerPort;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;

/** Real constructor selection and proxied finance dependency; no business work at startup. */
class FinanceEvidenceUsageWiringTest {
    @Test
    void evidenceGraphStartsWithRequiredPublicPortsAndNoStartupQueries() {
        var db = mock(FinanceStore.class);
        var permissions = mock(AdministrationPort.class);
        var owners = mock(AccountOwnerPort.class);
        var users = mock(UserDirectoryPort.class);
        var transactions = mock(PlatformTransactionManager.class);
        var projection = mock(AuthorizationContextProjectionPort.class);
        try (var context = new AnnotationConfigApplicationContext()) {
            FinanceJournalPersonWiringTest.ports(
                    context, db, permissions, owners, users, transactions);
            context.registerBean(AuthorizationContextProjectionPort.class, () -> projection);
            context.register(
                    FinanceJournalPersonWiringTest.Transactions.class,
                    FinanceJournalPersonGate.class,
                    FinanceService.class,
                    FinanceEvidenceDocuments.class,
                    FinanceExpenseAllocations.class,
                    FinanceEvidenceUsageController.class);
            assertDoesNotThrow(context::refresh);
            assertSame(
                    context.getBean(FinanceJournalPersonGate.class),
                    context.getBean(FinanceService.class).personGate());
            assertNotNull(context.getBean(FinanceEvidenceDocuments.class));
            assertNotNull(context.getBean(FinanceExpenseAllocations.class));
            assertNotNull(context.getBean(FinanceEvidenceUsageController.class));
            verifyNoInteractions(db, permissions, owners, users, transactions, projection);
        }
    }

    @Test
    void missingAuthorizationSnapshotPortFailsInsteadOfUsingUnscopedListFallback() {
        try (var context = new AnnotationConfigApplicationContext()) {
            FinanceJournalPersonWiringTest.ports(
                    context,
                    mock(FinanceStore.class),
                    mock(AdministrationPort.class),
                    mock(AccountOwnerPort.class),
                    mock(UserDirectoryPort.class),
                    mock(PlatformTransactionManager.class));
            context.register(
                    FinanceJournalPersonGate.class,
                    FinanceService.class,
                    FinanceEvidenceDocuments.class,
                    FinanceExpenseAllocations.class);
            var failure = assertThrows(BeanCreationException.class, context::refresh);
            assertTrue(
                    failure.getMessage().contains("AuthorizationContextProjectionPort"),
                    failure.getMessage());
        }
    }
}
