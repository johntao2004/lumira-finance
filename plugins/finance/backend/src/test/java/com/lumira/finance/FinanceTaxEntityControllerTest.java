package com.lumira.finance;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.*;

class FinanceTaxEntityControllerTest {
    final FinanceTaxEntities service = mock(FinanceTaxEntities.class);
    final FinanceTaxEntityController controller = new FinanceTaxEntityController(service);
    final UUID book = UUID.randomUUID(), entity = UUID.randomUUID(), revision = UUID.randomUUID();

    Authentication actor() {
        return new UsernamePasswordAuthenticationToken("ordinary-registry-owner", "unused");
    }

    @Test
    void unknownAndRepeatedReadFieldsCannotReachTheAdapter() {
        var unknown = new MockHttpServletRequest();
        unknown.addParameter("execute", "true");
        assertEquals(
                400,
                ((ResponseEntity<?>) controller.catalog(actor(), book.toString(), unknown))
                        .getStatusCode()
                        .value());
        var repeated = new MockHttpServletRequest();
        repeated.addParameter("limit", "1", "2");
        assertEquals(
                400,
                ((ResponseEntity<?>) controller.list(actor(), book.toString(), repeated))
                        .getStatusCode()
                        .value());
        var revisionQuery = new MockHttpServletRequest();
        revisionQuery.addParameter("yearId", UUID.randomUUID().toString());
        assertEquals(
                400,
                ((ResponseEntity<?>)
                                controller.revisionDetail(
                                        actor(),
                                        book.toString(),
                                        entity.toString(),
                                        revision.toString(),
                                        revisionQuery))
                        .getStatusCode()
                        .value());
        verifyNoInteractions(service);
    }

    @Test
    void revisionReadPreservesCallerAndBothRealIdentifiers() {
        var request = new MockHttpServletRequest();
        var result = Map.of("boundaryMarker", "not-a-source-proof");
        when(service.read(anyString(), any(), eq("revision-detail"), any(), any(), same(request)))
                .thenReturn(result);
        var response =
                (ResponseEntity<?>)
                        controller.revisionDetail(
                                actor(),
                                book.toString(),
                                entity.toString(),
                                revision.toString(),
                                request);
        assertEquals(200, response.getStatusCode().value());
        assertSame(result, response.getBody());
        verify(service)
                .read(
                        "ordinary-registry-owner",
                        book,
                        "revision-detail",
                        entity,
                        revision,
                        request);
    }

    @Test
    void everyMutationRejectsQueryInjectionBeforeBusinessWork() throws Exception {
        var request = new MockHttpServletRequest();
        request.addParameter("approve", "true");
        int checked = 0;
        for (var method : FinanceTaxEntityController.class.getDeclaredMethods()) {
            if (method.getAnnotation(PostMapping.class) == null) continue;
            var types = method.getParameterTypes();
            var args = new Object[types.length];
            int identifiers = 0;
            for (int i = 0; i < types.length; i++) {
                if (types[i] == Authentication.class) args[i] = actor();
                else if (types[i] == String.class)
                    args[i] =
                            switch (identifiers++) {
                                case 0 -> book.toString();
                                case 1 -> entity.toString();
                                default -> revision.toString();
                            };
                else if (types[i] == Map.class)
                    args[i] = Map.of("boundaryMarker", "never-consumed");
                else if (types[i] == HttpServletRequest.class) args[i] = request;
                else fail("Unexpected controller parameter " + types[i]);
            }
            assertEquals(
                    400,
                    ((ResponseEntity<?>) method.invoke(controller, args)).getStatusCode().value());
            checked++;
        }
        assertEquals(5, checked);
        verifyNoInteractions(service);
    }

    @Test
    void createPassesTheExactCommandBodyWithoutInventingATenantOrFinancialYear() {
        var request = new MockHttpServletRequest();
        var body = new LinkedHashMap<String, Object>();
        body.put("operationId", UUID.randomUUID().toString());
        body.put("idempotencyKey", "original-request-key");
        var result = Map.of("boundaryMarker", "adapter-result-only");
        when(service.write(
                        anyString(),
                        any(),
                        eq("create"),
                        isNull(),
                        isNull(),
                        same(body),
                        same(request)))
                .thenReturn(result);
        var response =
                (ResponseEntity<?>) controller.create(actor(), book.toString(), body, request);
        assertEquals(201, response.getStatusCode().value());
        assertSame(result, response.getBody());
        verify(service).write("ordinary-registry-owner", book, "create", null, null, body, request);
        assertEquals(Set.of("operationId", "idempotencyKey"), body.keySet());
    }

    @Test
    void typedIdentityConflictPreservesTheTwoPublicSdkIssueReferences() {
        var request = new MockHttpServletRequest();
        var issues =
                List.<Map<String, Object>>of(
                        Map.of(
                                "row",
                                1,
                                "field",
                                "existingTaxEntityId",
                                "reason",
                                entity.toString()),
                        Map.of(
                                "row",
                                1,
                                "field",
                                "governanceHomeBookId",
                                "reason",
                                book.toString()));
        when(service.read(anyString(), any(), eq("detail"), any(), isNull(), same(request)))
                .thenThrow(
                        new FinanceException(
                                "TAX_ENTITY_IDENTITY_EXISTS",
                                "existing authorized identity",
                                issues));
        var response =
                (ResponseEntity<?>)
                        controller.detail(actor(), book.toString(), entity.toString(), request);
        assertEquals(409, response.getStatusCode().value());
        assertEquals(issues, ((Map<?, ?>) response.getBody()).get("issues"));
    }

    @Test
    void declaredTaxBudgetsAndConflictsHaveTheirContractStatus() {
        for (String code : List.of("TAX_ENTITY_QUERY_BUDGET_EXCEEDED", "TAX_ENTITY_LIMIT_EXCEEDED"))
            assertEquals(422, new FinanceException(code, "budget").getStatusCode().value(), code);
        for (String code :
                List.of(
                        "TAX_ENTITY_IDENTITY_EXISTS",
                        "TAX_ENTITY_STATE_CONFLICT",
                        "TAX_ENTITY_SOURCE_STALE",
                        "TAX_ENTITY_APPROVAL_STALE",
                        "TAX_ENTITY_PAGE_STALE",
                        "TAX_ENTITY_BUSY"))
            assertEquals(409, new FinanceException(code, "conflict").getStatusCode().value(), code);
    }
}
