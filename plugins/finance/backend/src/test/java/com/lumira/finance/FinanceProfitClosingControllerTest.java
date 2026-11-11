package com.lumira.finance;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.common.BoundedBody;
import com.lumira.platform.modules.ModuleEndpoint;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.*;

/** HTTP boundary checks only; source/effect/SoD invariants require the PostgreSQL suite. */
class FinanceProfitClosingControllerTest {
    final FinanceProfitClosing service = mock(FinanceProfitClosing.class);
    final FinanceProfitClosingController controller = new FinanceProfitClosingController(service);
    final Authentication actor = mock(Authentication.class);
    final UUID book = UUID.randomUUID(), year = UUID.randomUUID(), target = UUID.randomUUID();

    Authentication actor() {
        when(actor.getName()).thenReturn("closing-ordinary-actor");
        return actor;
    }

    @Test
    void queriesAreAcceptedOnlyByPreviewAndListAndOriginalHostRequestIsPreserved() {
        var request = new MockHttpServletRequest();
        request.addParameter("cutoff", "2028-01-31");
        request.addParameter("producerKind", "PROFIT_LOSS");
        var marker = Map.of("sourceMarker", "adapter-not-a-business-proof");
        when(service.read(
                        anyString(),
                        any(),
                        any(),
                        anyString(),
                        nullable(UUID.class),
                        same(request)))
                .thenReturn(marker);
        var response =
                (ResponseEntity<?>)
                        controller.preview(actor(), book.toString(), year.toString(), request);
        assertEquals(200, response.getStatusCode().value());
        assertSame(marker, response.getBody());
        verify(service).read("closing-ordinary-actor", book, year, "preview", null, request);
        reset(service);
        var rejected =
                (ResponseEntity<?>)
                        controller.detail(
                                actor(),
                                book.toString(),
                                year.toString(),
                                target.toString(),
                                request);
        assertEquals(400, rejected.getStatusCode().value());
        verifyNoInteractions(service);
    }

    @Test
    void allCommandsPreserveTargetBodyAndHostEvidenceAndBoundInputSize() throws Exception {
        var request = new MockHttpServletRequest();
        var body =
                Map.<String, Object>of(
                        "schemaVersion", "profit-closing/1", "idempotencyKey", "adapter-key");
        var marker = Map.of("runMarker", "adapter-only");
        when(service.write(
                        anyString(),
                        any(),
                        any(),
                        anyString(),
                        nullable(UUID.class),
                        same(body),
                        same(request)))
                .thenReturn(marker);
        int count = 0;
        for (var method : FinanceProfitClosingController.class.getDeclaredMethods()) {
            var route = method.getAnnotation(PostMapping.class);
            if (route == null) continue;
            ++count;
            String path = route.value()[0];
            boolean create = path.equals("/runs");
            String operation = create ? "create" : path.substring(path.lastIndexOf('/') + 1);
            Object[] args =
                    create
                            ? new Object[] {
                                actor(), book.toString(), year.toString(), body, request
                            }
                            : new Object[] {
                                actor(),
                                book.toString(),
                                year.toString(),
                                target.toString(),
                                body,
                                request
                            };
            var response = (ResponseEntity<?>) method.invoke(controller, args);
            assertEquals(create ? 201 : 200, response.getStatusCode().value(), path);
            assertSame(marker, response.getBody());
            verify(service)
                    .write(
                            "closing-ordinary-actor",
                            book,
                            year,
                            operation,
                            create ? null : target,
                            body,
                            request);
            verifyNoMoreInteractions(service);
            clearInvocations(service);
            var bodyParameter =
                    Arrays.stream(method.getParameters())
                            .filter(p -> p.getAnnotation(BoundedBody.class) != null)
                            .findFirst()
                            .orElseThrow();
            assertEquals(16384, bodyParameter.getAnnotation(BoundedBody.class).maxBytes());
            assertEquals(
                    "INVALID_INPUT", bodyParameter.getAnnotation(BoundedBody.class).errorCode());
        }
        assertEquals(8, count);
        assertEquals(
                "finance",
                FinanceProfitClosingController.class.getAnnotation(ModuleEndpoint.class).value());
    }

    @Test
    void malformedScopeAndQueryPollutionNeverReachFinancialService() {
        var request = new MockHttpServletRequest();
        var malformed =
                (ResponseEntity<?>)
                        controller.detail(
                                actor(), "not-a-uuid", year.toString(), target.toString(), request);
        assertEquals(400, malformed.getStatusCode().value());
        request.addParameter("approvalId", UUID.randomUUID().toString());
        var polluted =
                (ResponseEntity<?>)
                        controller.post(
                                actor(),
                                book.toString(),
                                year.toString(),
                                target.toString(),
                                Map.of(),
                                request);
        assertEquals(400, polluted.getStatusCode().value());
        verifyNoInteractions(service);
    }

    @Test
    void serviceFailuresRemainTypedRatherThanBecomingSuccessfulRuns() {
        var request = new MockHttpServletRequest();
        when(service.read(anyString(), any(), any(), eq("producer"), any(), same(request)))
                .thenThrow(new FinanceException("SCOPE_DENIED", "scope unavailable"));
        var response =
                (ResponseEntity<?>)
                        controller.producer(
                                actor(),
                                book.toString(),
                                year.toString(),
                                target.toString(),
                                request);
        assertEquals(403, response.getStatusCode().value());
        assertEquals("SCOPE_DENIED", ((Map<?, ?>) response.getBody()).get("code"));
        verify(service).read("closing-ordinary-actor", book, year, "producer", target, request);
    }
}
