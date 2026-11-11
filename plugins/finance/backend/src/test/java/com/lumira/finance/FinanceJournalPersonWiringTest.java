package com.lumira.finance;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.UserDirectoryPort;
import com.lumira.platform.identity.api.AccountOwnerPort;

import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import tools.jackson.databind.ObjectMapper;

/**
 * Real constructor/AOP graph; application SQL, MFA and live plugin start remain separate checks.
 */
class FinanceJournalPersonWiringTest {
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Transactions {}

    static void ports(
            AnnotationConfigApplicationContext context,
            FinanceStore db,
            AdministrationPort permissions,
            AccountOwnerPort owners,
            UserDirectoryPort users,
            PlatformTransactionManager transactions) {
        context.registerBean(FinanceStore.class, () -> db);
        context.registerBean(AdministrationPort.class, () -> permissions);
        context.registerBean(AccountOwnerPort.class, () -> owners);
        context.registerBean(UserDirectoryPort.class, () -> users);
        context.registerBean(PlatformTransactionManager.class, () -> transactions);
        context.registerBean(
                ObjectMapper.class, (java.util.function.Supplier<ObjectMapper>) ObjectMapper::new);
    }

    @Test
    void realProductionGraphRequiresGateWithoutDirectoryCycleOrStartupEffects() {
        var db = mock(FinanceStore.class);
        var permissions = mock(AdministrationPort.class);
        var owners = mock(AccountOwnerPort.class);
        var users = mock(UserDirectoryPort.class);
        var transactions = mock(PlatformTransactionManager.class);
        try (var context = new AnnotationConfigApplicationContext()) {
            ports(context, db, permissions, owners, users, transactions);
            context.register(
                    Transactions.class,
                    FinanceJournalPersonGate.class,
                    FinanceService.class,
                    FinanceJournalPersonSod.class,
                    FinancePersonDirectory.class,
                    FinanceJournalPersonController.class);
            assertDoesNotThrow(context::refresh);
            var finance = context.getBean(FinanceService.class);
            var target = (FinanceService) AopTestUtils.getUltimateTargetObject(finance);
            var gate = context.getBean(FinanceJournalPersonGate.class);
            var sod = context.getBean(FinanceJournalPersonSod.class);
            var directory = context.getBean(FinancePersonDirectory.class);
            assertTrue(AopUtils.isAopProxy(finance));
            assertSame(gate, target.personGate);
            assertSame(
                    gate,
                    finance.personGate(),
                    "The public virtual accessor must delegate to the real AOP target");
            assertFalse(
                    gate.historicalFixture,
                    "Production must not select the old-schema fixture adapter");
            assertSame(db, gate.db);
            assertSame(permissions, gate.permissions);
            assertSame(owners, gate.owners);
            assertSame(finance, sod.finance);
            assertSame(gate, sod.gate);
            assertSame(transactions, sod.transactions);
            assertSame(finance, directory.json);
            assertNotNull(context.getBean(FinanceJournalPersonController.class));
            verifyNoInteractions(db, permissions, owners, users, transactions);
        }
    }

    @Test
    void absentGateFailsInsteadOfSelectingHistoricalFourArgumentConstructor() {
        try (var context = new AnnotationConfigApplicationContext()) {
            ports(
                    context,
                    mock(FinanceStore.class),
                    mock(AdministrationPort.class),
                    mock(AccountOwnerPort.class),
                    mock(UserDirectoryPort.class),
                    mock(PlatformTransactionManager.class));
            context.register(FinanceService.class);
            var failure = assertThrows(BeanCreationException.class, context::refresh);
            assertTrue(
                    failure.getMessage().contains("FinanceJournalPersonGate"),
                    failure.getMessage());
        }
    }
}
