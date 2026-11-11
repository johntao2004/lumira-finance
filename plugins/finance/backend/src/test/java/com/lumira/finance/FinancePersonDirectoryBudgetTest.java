package com.lumira.finance;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.dao.*;
import org.springframework.transaction.TransactionTimedOutException;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

class FinancePersonDirectoryBudgetTest {
    @Test
    void onlyActualTimeoutsAndActualLockStateMap() {
        for (RuntimeException e :
                List.of(
                        new QueryTimeoutException("timeout"),
                        new TransactionTimedOutException("expired"),
                        new DataAccessResourceFailureException(
                                "wrapped", new SQLException("cancel", "57014"))))
            assertEquals(
                    "PERSON_DIRECTORY_QUERY_BUDGET_EXCEEDED",
                    ((FinanceException) FinancePersonDirectoryBudget.translate(e)).code);
        var lock = new CannotAcquireLockException("busy", new SQLException("lock", "55P03"));
        assertEquals(
                "PERSON_DIRECTORY_BUSY",
                ((FinanceException) FinancePersonDirectoryBudget.translate(lock)).code);
        var other =
                new DataIntegrityViolationException(
                        "constraint", new SQLException("constraint", "23505"));
        assertSame(other, FinancePersonDirectoryBudget.translate(other));
        assertEquals(422, FinancePersonDirectoryBudget.exceeded().getStatusCode().value());
        assertEquals(409, FinancePersonDirectoryBudget.busy().getStatusCode().value());
    }

    @Test
    void IndependentDeadlineAndRemainingCaps() {
        var clock = new AtomicLong();
        var raw = mock(FinanceStore.class);
        when(raw.list(anyString(), any(), any())).thenReturn(List.of());
        var one = new FinancePersonDirectoryBudget(raw, clock::get);
        clock.set(8_500_000_000L);
        one.configure();
        verify(raw).list(anyString(), eq("1500ms"), eq("1500ms"));
        var two = new FinancePersonDirectoryBudget(raw, clock::get);
        clock.set(10_100_000_000L);
        assertThrows(FinanceException.class, one::check);
        assertDoesNotThrow(two::check);
    }

    @Test
    void SpiDeadlineIsCheckedAfterSynchronousReturn() {
        var clock = new AtomicLong();
        var raw = mock(FinanceStore.class);
        when(raw.list(anyString(), any(), any())).thenReturn(List.of());
        var budget = new FinancePersonDirectoryBudget(raw, clock::get);
        assertThrows(
                FinanceException.class,
                () ->
                        budget.spi(
                                () -> {
                                    clock.set(11_000_000_000L);
                                    return "late";
                                }));
    }
}
