package com.lumira.finance;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.UserDirectoryPort;
import com.lumira.platform.identity.api.AccountOwnerPort;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Exercise real Spring constructor selection, not a direct-new service or annotation reflection.
 */
class FinancePersonDirectoryWiringTest {
    @Test
    void hostStyleComponentRegistrationResolvesSixDependencies() {
        try (var context = new AnnotationConfigApplicationContext()) {
            var db = mock(FinanceStore.class);
            var json = mock(FinanceService.class);
            var permissions = mock(AdministrationPort.class);
            var users = mock(UserDirectoryPort.class);
            var owners = mock(AccountOwnerPort.class);
            var transactions = mock(PlatformTransactionManager.class);
            context.registerBean(FinanceStore.class, () -> db);
            context.registerBean(FinanceService.class, () -> json);
            context.registerBean(AdministrationPort.class, () -> permissions);
            context.registerBean(UserDirectoryPort.class, () -> users);
            context.registerBean(AccountOwnerPort.class, () -> owners);
            context.registerBean(PlatformTransactionManager.class, () -> transactions);
            context.register(FinancePersonDirectory.class);
            assertDoesNotThrow(context::refresh);
            var service = context.getBean(FinancePersonDirectory.class);
            assertSame(db, service.db);
            assertSame(json, service.json);
            assertSame(permissions, service.permissions);
            assertSame(users, service.users);
            assertSame(owners, service.owners);
            assertSame(transactions, service.transactions);
            assertNull(service.budget);
        }
    }
}
