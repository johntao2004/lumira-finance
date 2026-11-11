package com.lumira.finance;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;

import java.util.Map;
import java.util.UUID;

class FinanceCarryReadinessControllerTest {
    final FinanceCarryReadiness service = mock(FinanceCarryReadiness.class);
    final FinanceCarryReadinessController controller = new FinanceCarryReadinessController(service);
    final Authentication actor = mock(Authentication.class);
    final UUID book = UUID.randomUUID(), year = UUID.randomUUID();

    @Test
    void routesPreserveAuthenticatedActorAndOriginalQueryForDualYearAuthorization() {
        when(actor.getName()).thenReturn("read-only-observer");
        var request = new MockHttpServletRequest();
        request.addParameter("targetYearId", UUID.randomUUID().toString());
        request.addParameter("observationToken", "current-observation");
        var result = Map.of("boundaryMarker", "not-a-financial-snapshot");
        when(service.read(anyString(), any(), any(), anyString(), same(request)))
                .thenReturn(result);
        var summary =
                (ResponseEntity<?>)
                        controller.summary(actor, book.toString(), year.toString(), request);
        assertEquals(200, summary.getStatusCode().value());
        assertSame(result, summary.getBody());
        verify(service).read("read-only-observer", book, year, "summary", request);
        var accounts =
                (ResponseEntity<?>)
                        controller.accounts(actor, book.toString(), year.toString(), request);
        assertEquals(200, accounts.getStatusCode().value());
        assertSame(result, accounts.getBody());
        verify(service).read("read-only-observer", book, year, "accounts", request);
        verifyNoMoreInteractions(service);
    }

    @Test
    void malformedScopeDoesNotReachFinancialFacts() {
        when(actor.getName()).thenReturn("observer");
        var result =
                (ResponseEntity<?>)
                        controller.summary(
                                actor, "not-a-uuid", year.toString(), new MockHttpServletRequest());
        assertEquals(400, result.getStatusCode().value());
        verifyNoInteractions(service);
    }

    @Test
    void deniedTargetReadCannotBecomeAnEmptySuccessfulObservation() {
        when(actor.getName()).thenReturn("source-only-observer");
        when(service.read(anyString(), any(), any(), eq("accounts"), any()))
                .thenThrow(new FinanceException("SCOPE_DENIED", "两年度读取授权不足"));
        var result =
                (ResponseEntity<?>)
                        controller.accounts(
                                actor,
                                book.toString(),
                                year.toString(),
                                new MockHttpServletRequest());
        assertEquals(403, result.getStatusCode().value());
        assertEquals("SCOPE_DENIED", ((Map<?, ?>) result.getBody()).get("code"));
    }
}
