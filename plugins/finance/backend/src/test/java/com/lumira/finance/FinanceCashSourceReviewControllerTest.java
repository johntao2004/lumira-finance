package com.lumira.finance;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class FinanceCashSourceReviewControllerTest {
    @Test
    void sourceReviewBoundaryUsesStableCodesAndStrictTypedConcurrency() {
        var service = mock(FinanceCashSourceReviews.class);
        var controller = new FinanceCashSourceReviewController(service);
        for (String code :
                List.of(
                        "CASH_REVIEW_CLAIM_STALE",
                        "CASH_REVIEW_PENDING_WITHDRAWAL",
                        "CASH_REVIEW_ALREADY_EFFECTIVE",
                        "CASH_REVIEW_PENDING_EXISTS",
                        "CASH_REVIEW_SUBMISSION_EXPIRED",
                        "CASH_REVIEW_STATE_CONFLICT",
                        "CASH_REVIEW_RELEASE_PENDING",
                        "CASH_REVIEW_IN_USE")) {
            doThrow(new FinanceException(code, "人审状态冲突"))
                    .when(service)
                    .write(anyString(), any(), any(), anyString(), any(), any(), anyMap());
            FinanceHttpTest.error(
                    controller.create(
                            FinanceHttpTest.actor(),
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            Map.of("idempotencyKey", "human-review-boundary"),
                            null),
                    409,
                    code);
        }
        assertThrows(
                FinanceException.class,
                () ->
                        FinanceCashMatchCorrectionController.body(
                                Map.of("expectedVersion", "1"), null, "1"));
        assertEquals(
                1L,
                FinanceCashMatchCorrectionController.body(Map.of(), null, "\"1\"")
                        .get("expectedVersion"));
    }
}
