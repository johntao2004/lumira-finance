package com.lumira.finance;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.common.BoundedBody;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.Map;
import java.util.UUID;

class FinanceCounterpartiesControllerTest {
    final FinanceCounterparties service = mock(FinanceCounterparties.class);
    final FinanceCounterpartiesController controller = new FinanceCounterpartiesController(service);
    final Authentication actor = mock(Authentication.class);
    final UUID book = UUID.randomUUID(), party = UUID.randomUUID(), revision = UUID.randomUUID();

    Authentication actor() {
        when(actor.getName()).thenReturn("organization-master-observer");
        return actor;
    }

    @Test
    void versionReadsPreserveCallerAndRealHostQueryForDualBookAuthorization() {
        var request = new MockHttpServletRequest();
        request.addParameter("limit", "2");
        var result = Map.of("boundaryMarker", "not-a-published-master-proof");
        when(service.read(
                        anyString(),
                        any(),
                        anyString(),
                        nullable(UUID.class),
                        nullable(UUID.class),
                        same(request)))
                .thenReturn(result);
        assertSame(
                result,
                ((ResponseEntity<?>) controller.list(actor(), book.toString(), request)).getBody());
        verify(service).read("organization-master-observer", book, "list", null, null, request);
        assertSame(
                result,
                ((ResponseEntity<?>)
                                controller.revisions(
                                        actor(), book.toString(), party.toString(), request))
                        .getBody());
        verify(service)
                .read("organization-master-observer", book, "revisions", party, null, request);
        assertSame(
                result,
                ((ResponseEntity<?>)
                                controller.revision(
                                        actor(),
                                        book.toString(),
                                        party.toString(),
                                        revision.toString(),
                                        request))
                        .getBody());
        verify(service)
                .read("organization-master-observer", book, "revision", party, revision, request);
        assertSame(
                result,
                ((ResponseEntity<?>)
                                controller.detail(
                                        actor(), book.toString(), party.toString(), request))
                        .getBody());
        verify(service).read("organization-master-observer", book, "detail", party, null, request);
        verifyNoMoreInteractions(service);
    }

    @Test
    void commandsPreserveExactBodyAndRequestWithIndependentHostByteCaps() throws Exception {
        var request = new MockHttpServletRequest();
        var body =
                Map.<String, Object>of(
                        "schemaVersion", "counterparty/1", "idempotencyKey", "boundary-only");
        var result = Map.of("boundaryMarker", "not-a-business-decision");
        when(service.write(
                        anyString(),
                        any(),
                        anyString(),
                        nullable(UUID.class),
                        nullable(UUID.class),
                        same(body),
                        same(request)))
                .thenReturn(result);
        int commands = 0;
        for (var method : FinanceCounterpartiesController.class.getDeclaredMethods()) {
            var route = method.getAnnotation(PostMapping.class);
            if (route == null) continue;
            commands++;
            String operation = method.getName();
            boolean create = operation.equals("create"), propose = operation.equals("propose");
            Object[] args =
                    create
                            ? new Object[] {actor(), book.toString(), body, request}
                            : propose
                                    ? new Object[] {
                                        actor(), book.toString(), party.toString(), body, request
                                    }
                                    : new Object[] {
                                        actor(),
                                        book.toString(),
                                        party.toString(),
                                        revision.toString(),
                                        body,
                                        request
                                    };
            var response = (ResponseEntity<?>) method.invoke(controller, args);
            assertEquals(create || propose ? 201 : 200, response.getStatusCode().value());
            assertSame(result, response.getBody());
            verify(service)
                    .write(
                            "organization-master-observer",
                            book,
                            operation,
                            create ? null : party,
                            create || propose ? null : revision,
                            body,
                            request);
            verifyNoMoreInteractions(service);
            clearInvocations(service);
            var cap =
                    java.util.Arrays.stream(method.getParameters())
                            .map(p -> p.getAnnotation(BoundedBody.class))
                            .filter(java.util.Objects::nonNull)
                            .findFirst()
                            .orElseThrow();
            assertEquals(create || propose ? 16384 : 8192, cap.maxBytes());
            assertEquals("INVALID_INPUT", cap.errorCode());
        }
        assertEquals(5, commands);
    }

    @Test
    void malformedScopesAndWriteQueryPollutionNeverReachMasterData() {
        var request = new MockHttpServletRequest();
        assertEquals(
                400,
                ((ResponseEntity<?>)
                                controller.detail(actor(), "not-a-uuid", party.toString(), request))
                        .getStatusCode()
                        .value());
        request.addParameter("approve", "true");
        assertEquals(
                400,
                ((ResponseEntity<?>)
                                controller.approve(
                                        actor(),
                                        book.toString(),
                                        party.toString(),
                                        revision.toString(),
                                        Map.of(),
                                        request))
                        .getStatusCode()
                        .value());
        verifyNoInteractions(service);
    }

    @Test
    void authorizedIdentityConflictRetainsSupportedSdkReferencesWithoutChangingStableCode() {
        var request = new MockHttpServletRequest();
        var issues =
                java.util.List.<Map<String, Object>>of(
                        Map.of("row", 1, "field", "counterpartyId", "reason", party.toString()),
                        Map.of(
                                "row",
                                1,
                                "field",
                                "governanceHomeBookId",
                                "reason",
                                book.toString()));
        when(service.write(
                        anyString(), any(), eq("create"), isNull(), isNull(), any(), same(request)))
                .thenThrow(
                        new FinanceException("COUNTERPARTY_IDENTITY_EXISTS", "有权读取的既有组织", issues));
        var response =
                (ResponseEntity<?>) controller.create(actor(), book.toString(), Map.of(), request);
        assertEquals(409, response.getStatusCode().value());
        var body = (Map<?, ?>) response.getBody();
        assertEquals("COUNTERPARTY_IDENTITY_EXISTS", body.get("code"));
        assertEquals(body.get("code"), body.get("detail"));
        assertEquals(issues, body.get("issues"));
    }

    @Test
    void currentAuthorizationAndBudgetFailuresRemainErrorsRatherThanPublishedSuccess() {
        var request = new MockHttpServletRequest();
        for (var failure :
                Map.of(
                                "SCOPE_DENIED",
                                403,
                                "COUNTERPARTY_BUSY",
                                409,
                                "COUNTERPARTY_QUERY_BUDGET_EXCEEDED",
                                422)
                        .entrySet()) {
            when(service.read(anyString(), any(), eq("detail"), any(), isNull(), same(request)))
                    .thenThrow(new FinanceException(failure.getKey(), "边界错误"));
            var response =
                    (ResponseEntity<?>)
                            controller.detail(actor(), book.toString(), party.toString(), request);
            assertEquals(failure.getValue(), response.getStatusCode().value());
            assertEquals(failure.getKey(), ((Map<?, ?>) response.getBody()).get("code"));
            assertFalse(((Map<?, ?>) response.getBody()).containsKey("issues"));
        }
    }
}
