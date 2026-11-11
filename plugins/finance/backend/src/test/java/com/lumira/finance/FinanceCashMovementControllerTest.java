package com.lumira.finance;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.*;

class FinanceCashMovementControllerTest {
    @Test
    void scopedMovementErrorsKeepStableHttpCodesAndSanitizeSql() {
        var service = mock(FinanceCashMovements.class);
        var controller = new FinanceCashMovementController(service);
        for (String code :
                List.of(
                        "CASH_SOURCE_DUPLICATE_CONFLICT",
                        "SOURCE_ALREADY_VOIDED",
                        "CASH_LEG_ALREADY_REGISTERED",
                        "CASH_MOVEMENT_MATCH_INVALID",
                        "CASH_MOVEMENT_SCOPE_STALE",
                        "CASH_REGISTRATION_IN_USE")) {
            doThrow(new FinanceException(code, "现金来源冲突"))
                    .when(service)
                    .write(anyString(), any(), any(), any(), anyMap());
            FinanceHttpTest.error(
                    controller.create(
                            FinanceHttpTest.actor(),
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            Map.of("idempotencyKey", "cash-boundary-test"),
                            null),
                    409,
                    code);
        }
        doThrow(new DataIntegrityViolationException("secret SQL credentials"))
                .when(service)
                .write(anyString(), any(), any(), any(), anyMap());
        var result =
                FinanceHttpTest.error(
                        controller.create(
                                FinanceHttpTest.actor(),
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                Map.of("idempotencyKey", "cash-boundary-test"),
                                null),
                        409,
                        "VERSION_CONFLICT");
        assertFalse(result.toString().contains("secret SQL"));
    }
}
