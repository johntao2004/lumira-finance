package com.lumira.finance;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.lumira.platform.common.*;
import com.lumira.platform.identity.api.AccountOwnerPort;
import com.lumira.platform.modules.ModuleEndpoint;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Map;

class FinanceReceivablesWiringTest {
    @Test
    void springWiresReceivablesServiceAndControllerInBoundedModeWithoutNativeProducerBean()
            throws Exception {
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
                    FinanceReceivablesController.class);
            context.refresh();

            assertNotNull(context.getBean(FinanceReceivables.class));
            var controller = context.getBean(FinanceReceivablesController.class);
            assertNotNull(controller);
            assertEquals(
                    "finance",
                    FinanceReceivablesController.class.getAnnotation(ModuleEndpoint.class).value());
            assertEquals(
                    32768,
                    FinanceReceivablesController.class
                            .getMethod(
                                    "create",
                                    Authentication.class,
                                    String.class,
                                    String.class,
                                    Map.class,
                                    HttpServletRequest.class)
                            .getParameters()[3]
                            .getAnnotation(BoundedBody.class)
                            .maxBytes());
            assertEquals(
                    8192,
                    FinanceReceivablesController.class
                            .getMethod(
                                    "action",
                                    Authentication.class,
                                    String.class,
                                    String.class,
                                    String.class,
                                    String.class,
                                    Map.class,
                                    HttpServletRequest.class)
                            .getParameters()[5]
                            .getAnnotation(BoundedBody.class)
                            .maxBytes());
        }
    }
}
