package com.lumira.finance;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.common.BoundedBody;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.*;

class FinancePayablesAp01ControllerTest {
    final FinancePayablesAp01 service = mock(FinancePayablesAp01.class);
    final FinancePayablesAp01Controller controller = new FinancePayablesAp01Controller(service);
    final UUID book = UUID.randomUUID(), year = UUID.randomUUID(), target = UUID.randomUUID();

    Authentication actor() {
        var a = mock(Authentication.class);
        when(a.getName()).thenReturn("ordinary-payable-maker");
        return a;
    }

    @Test
    void unknownAndRepeatedReadsCannotReachAQueryThatSilentlyIgnoresThem() {
        var unknown = new MockHttpServletRequest();
        unknown.addParameter("execute", "true");
        assertEquals(
                400,
                ((ResponseEntity<?>)
                                controller.catalog(
                                        actor(), book.toString(), year.toString(), unknown))
                        .getStatusCode()
                        .value());
        var repeated = new MockHttpServletRequest();
        repeated.addParameter("historyLimit", "1", "2");
        assertEquals(
                400,
                ((ResponseEntity<?>)
                                controller.detail(
                                        actor(),
                                        book.toString(),
                                        year.toString(),
                                        target.toString(),
                                        repeated))
                        .getStatusCode()
                        .value());
        verifyNoInteractions(service);
    }

    @Test
    void actualQueryAndHistoryCursorReachTheScopeAwareReadAdapter() {
        var request = new MockHttpServletRequest();
        request.addParameter("historyLimit", "2");
        request.addParameter("historyCursor", "opaque-not-a-frontend-history");
        var answer = Map.of("boundaryMarker", "not-an-accounting-proof");
        when(service.read(anyString(), any(), any(), anyString(), any(), same(request)))
                .thenReturn(answer);
        var response =
                (ResponseEntity<?>)
                        controller.detail(
                                actor(),
                                book.toString(),
                                year.toString(),
                                target.toString(),
                                request);
        assertEquals(200, response.getStatusCode().value());
        assertSame(answer, response.getBody());
        verify(service).read("ordinary-payable-maker", book, year, "detail", target, request);
    }

    @Test
    void newTypedReversalPreservesTheExactBodyAndCallerRatherThanPostingTheOriginalJournal() {
        var request = new MockHttpServletRequest();
        var input = new LinkedHashMap<String, Object>();
        input.put("operationId", UUID.randomUUID().toString());
        input.put("expectedOpenItemVersion", "2");
        var result = Map.of("boundaryMarker", "typed-command-result-only");
        when(service.write(
                        anyString(),
                        any(),
                        any(),
                        eq("full-reversal"),
                        any(),
                        same(input),
                        same(request)))
                .thenReturn(result);
        var response =
                (ResponseEntity<?>)
                        controller.fullReversal(
                                actor(),
                                book.toString(),
                                year.toString(),
                                target.toString(),
                                input,
                                request);
        assertEquals(201, response.getStatusCode().value());
        assertSame(result, response.getBody());
        verify(service)
                .write(
                        "ordinary-payable-maker",
                        book,
                        year,
                        "full-reversal",
                        target,
                        input,
                        request);
    }

    @Test
    void everyTypedMutationRejectsQueryInjectionBeforeAnyBusinessAdapterCall() throws Exception {
        var request = new MockHttpServletRequest();
        request.addParameter("approve", "true");
        int checked = 0;
        for (var method : FinancePayablesAp01Controller.class.getDeclaredMethods()) {
            if (method.getAnnotation(PostMapping.class) == null) continue;
            var values = new ArrayList<Object>();
            for (var parameter : method.getParameters()) {
                if (parameter.getType() == Authentication.class) values.add(actor());
                else if (parameter.getType() == HttpServletRequest.class) values.add(request);
                else if (parameter.getType() == Map.class) values.add(Map.of());
                else
                    values.add(
                            parameter.getName().equals("year") ? year.toString() : book.toString());
            }
            var response = (ResponseEntity<?>) method.invoke(controller, values.toArray());
            assertEquals(400, response.getStatusCode().value(), method.getName());
            checked++;
        }
        assertEquals(13, checked);
        verifyNoInteractions(service);
    }

    @Test
    void malformedScopeCannotReachTheBusinessAdapter() {
        var response =
                (ResponseEntity<?>)
                        controller.catalog(
                                actor(),
                                "not-a-book-uuid",
                                year.toString(),
                                new MockHttpServletRequest());
        assertEquals(400, response.getStatusCode().value());
        verifyNoInteractions(service);
    }

    @Test
    void hostCapsProtectAllFinancialMutationBodiesBeforeTheyBecomeMaps() {
        int count = 0;
        for (var method : FinancePayablesAp01Controller.class.getDeclaredMethods()) {
            if (method.getAnnotation(PostMapping.class) == null) continue;
            count++;
            var body =
                    Arrays.stream(method.getParameters())
                            .filter(p -> p.getType() == Map.class)
                            .findFirst()
                            .orElseThrow();
            var cap = body.getAnnotation(BoundedBody.class);
            assertNotNull(cap, method.getName());
            assertEquals("INVALID_INPUT", cap.errorCode());
            assertEquals(
                    switch (method.getName()) {
                        case "create" -> 32768;
                        case "basisCreate", "fullReversal" -> 16384;
                        default -> 8192;
                    },
                    cap.maxBytes(),
                    method.getName());
        }
        assertEquals(13, count);
    }

    @Test
    void sdkReceivesStructuredConflictWithoutInventedSupplierDiscoveryReferences() {
        when(service.write(anyString(), any(), any(), anyString(), any(), anyMap(), any()))
                .thenThrow(
                        new FinanceException(
                                "AP_BUSY",
                                "actual typed operation may be retried with unchanged body"));
        var response =
                (ResponseEntity<?>)
                        controller.post(
                                actor(),
                                book.toString(),
                                year.toString(),
                                target.toString(),
                                Map.of(),
                                new MockHttpServletRequest());
        assertEquals(409, response.getStatusCode().value());
        var body = (Map<?, ?>) response.getBody();
        assertEquals("AP_BUSY", body.get("detail"));
        assertEquals(Set.of("code", "detail", "message", "traceId", "retryable"), body.keySet());
        assertEquals(false, body.get("retryable"));
    }
}
