package com.lumira.finance;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class FinanceCashPolicyControllerTest {
    @Test
    void policyBoundaryKeepsStableConflictAndIndependentReviewCodes() {
        var service = mock(FinanceCashPolicies.class);
        var controller = new FinanceCashPolicyController(service);
        for (String code :
                List.of(
                        "CASH_POLICY_SUBMISSION_EXPIRED",
                        "CASH_POLICY_OVERLAP",
                        "CASH_POLICY_STATE_CONFLICT",
                        "CASH_POLICY_RETIREMENT_PENDING")) {
            doThrow(new FinanceException(code, "政策生命周期冲突"))
                    .when(service)
                    .write(anyString(), any(), any(), anyString(), any(), any(), anyMap());
            FinanceHttpTest.error(
                    controller.create(
                            FinanceHttpTest.actor(),
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            Map.of("idempotencyKey", "policy-boundary-test"),
                            null),
                    409,
                    code);
        }
        assertThrows(
                FinanceException.class,
                () ->
                        FinanceCashMatchCorrectionController.body(
                                Map.of("expectedVersion", "1"), null, null));
        assertEquals(
                1L,
                FinanceCashMatchCorrectionController.body(
                                Map.of("expectedVersion", 1), null, "\"1\"")
                        .get("expectedVersion"));
    }
}
