package com.lumira.finance;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.common.BoundedBody;
import com.lumira.platform.modules.ModuleEndpoint;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.lang.reflect.Method;
import java.util.*;

/** Adapter boundaries only; authorization, quota and transactions have separate PG/live checks. */
class FinanceEvidenceUsageControllerTest {
    private final FinanceEvidenceDocuments documents = mock(FinanceEvidenceDocuments.class);
    private final FinanceExpenseAllocations allocations = mock(FinanceExpenseAllocations.class);
    private final FinanceEvidenceUsageController controller =
            new FinanceEvidenceUsageController(documents, allocations);
    private final Authentication actor = mock(Authentication.class);
    private final UUID book = UUID.randomUUID(),
            year = UUID.randomUUID(),
            target = UUID.randomUUID(),
            child = UUID.randomUUID();
    private final MockHttpServletRequest request = new MockHttpServletRequest();

    private Authentication actor() {
        when(actor.getName()).thenReturn("ordinary-evidence-actor");
        return actor;
    }

    private static String operation(String name) {
        return name.replaceFirst("^(document|allocation)", "")
                .replaceAll("([a-z])([A-Z])", "$1-$2")
                .toLowerCase(Locale.ROOT);
    }

    @Test
    void allRoutesPreserveScopeChildIdentityBodyAndOriginalHostRequest() throws Exception {
        var body =
                Map.<String, Object>of(
                        "schemaVersion", "evidence-usage/1", "idempotencyKey", "test-adapter-key");
        var marker = Map.of("adapterMarker", "not-a-business-proof");
        when(documents.read(
                        anyString(),
                        any(),
                        any(),
                        anyString(),
                        nullable(UUID.class),
                        nullable(UUID.class),
                        same(request)))
                .thenReturn(marker);
        when(allocations.read(
                        anyString(),
                        any(),
                        any(),
                        anyString(),
                        nullable(UUID.class),
                        nullable(UUID.class),
                        same(request)))
                .thenReturn(marker);
        when(documents.write(
                        anyString(),
                        any(),
                        any(),
                        anyString(),
                        nullable(UUID.class),
                        nullable(UUID.class),
                        same(body),
                        same(request)))
                .thenReturn(marker);
        when(allocations.write(
                        anyString(),
                        any(),
                        any(),
                        anyString(),
                        nullable(UUID.class),
                        nullable(UUID.class),
                        same(body),
                        same(request)))
                .thenReturn(marker);
        int checked = 0;
        for (Method method : FinanceEvidenceUsageController.class.getDeclaredMethods()) {
            boolean read = method.isAnnotationPresent(GetMapping.class);
            if (!read
                    && !method.isAnnotationPresent(PostMapping.class)
                    && !method.isAnnotationPresent(PatchMapping.class)) continue;
            var args = new ArrayList<Object>();
            int ids = 0;
            for (var parameter : method.getParameters()) {
                if (parameter.getType() == Authentication.class) args.add(actor());
                else if (parameter.getType() == HttpServletRequest.class) args.add(request);
                else if (Map.class.isAssignableFrom(parameter.getType())) args.add(body);
                else
                    args.add(
                            switch (ids++) {
                                case 0 -> book.toString();
                                case 1 -> year.toString();
                                case 2 -> target.toString();
                                default -> child.toString();
                            });
            }
            String op = operation(method.getName());
            UUID targetId = ids > 2 ? target : null, childId = ids > 3 ? child : null;
            var response = (ResponseEntity<?>) method.invoke(controller, args.toArray());
            assertSame(marker, response.getBody());
            assertEquals(
                    Set.of("register", "review-create", "create", "reverse-create").contains(op)
                            ? 201
                            : 200,
                    response.getStatusCode().value());
            boolean document = method.getName().startsWith("document");
            if (read && document)
                verify(documents)
                        .read(
                                "ordinary-evidence-actor",
                                book,
                                year,
                                op,
                                targetId,
                                childId,
                                request);
            if (read && !document)
                verify(allocations)
                        .read(
                                "ordinary-evidence-actor",
                                book,
                                year,
                                op,
                                targetId,
                                childId,
                                request);
            if (!read && document)
                verify(documents)
                        .write(
                                "ordinary-evidence-actor",
                                book,
                                year,
                                op,
                                targetId,
                                childId,
                                body,
                                request);
            if (!read && !document)
                verify(allocations)
                        .write(
                                "ordinary-evidence-actor",
                                book,
                                year,
                                op,
                                targetId,
                                childId,
                                body,
                                request);
            verifyNoMoreInteractions(documents, allocations);
            clearInvocations(documents, allocations);
            checked++;
        }
        assertEquals(24, checked);
    }

    @Test
    void malformedOrNoncanonicalNestedIdsFailBeforeService() {
        var malformed =
                (ResponseEntity<?>)
                        controller.allocationReverseDetail(
                                actor(),
                                book.toString(),
                                year.toString(),
                                target.toString(),
                                "invalid",
                                request);
        assertEquals(400, malformed.getStatusCode().value());
        var uppercase =
                (ResponseEntity<?>)
                        controller.documentDetail(
                                actor(),
                                "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA",
                                year.toString(),
                                target.toString(),
                                request);
        assertEquals(400, uppercase.getStatusCode().value());
        verifyNoInteractions(documents, allocations);
    }

    @Test
    void onlyListsPassQueryParametersToTransactionalStrictParser() {
        request.addParameter("limit", "20");
        var detail =
                (ResponseEntity<?>)
                        controller.documentDetail(
                                actor(),
                                book.toString(),
                                year.toString(),
                                target.toString(),
                                request);
        assertEquals(400, detail.getStatusCode().value());
        var write =
                (ResponseEntity<?>)
                        controller.allocationCreate(
                                actor(), book.toString(), year.toString(), Map.of(), request);
        assertEquals(400, write.getStatusCode().value());
        verifyNoInteractions(documents, allocations);
        controller.documentList(actor(), book.toString(), year.toString(), request);
        verify(documents).read("ordinary-evidence-actor", book, year, "list", null, null, request);
        controller.allocationList(actor(), book.toString(), year.toString(), request);
        verify(allocations)
                .read("ordinary-evidence-actor", book, year, "list", null, null, request);
    }

    @Test
    void domainErrorsKeepCodeAndTraceAtHttpBoundary() {
        when(documents.read("ordinary-evidence-actor", book, year, "catalog", null, null, request))
                .thenThrow(new FinanceException("SCOPE_DENIED", "scope changed"));
        var rejected =
                (ResponseEntity<?>)
                        controller.documentCatalog(
                                actor(), book.toString(), year.toString(), request);
        assertEquals(403, rejected.getStatusCode().value());
        var body = (Map<?, ?>) rejected.getBody();
        assertEquals("SCOPE_DENIED", body.get("code"));
        assertNotNull(UUID.fromString((String) body.get("traceId")));
    }

    @Test
    void exactRouteFamilyOwnsBoundedBodiesIncludingLargerPatch() {
        assertEquals(
                "finance",
                FinanceEvidenceUsageController.class.getAnnotation(ModuleEndpoint.class).value());
        assertEquals(
                "/api/finance/v1/books/{book}/years/{year}/evidence-usage",
                FinanceEvidenceUsageController.class.getAnnotation(RequestMapping.class)
                        .value()[0]);
        Set<String> paths = new HashSet<>();
        int reads = 0, writes = 0;
        for (Method method : FinanceEvidenceUsageController.class.getDeclaredMethods()) {
            String verb;
            String[] path;
            if (method.isAnnotationPresent(GetMapping.class)) {
                verb = "GET";
                path = method.getAnnotation(GetMapping.class).value();
                reads++;
            } else if (method.isAnnotationPresent(PostMapping.class)) {
                verb = "POST";
                path = method.getAnnotation(PostMapping.class).value();
                writes++;
            } else if (method.isAnnotationPresent(PatchMapping.class)) {
                verb = "PATCH";
                path = method.getAnnotation(PatchMapping.class).value();
                writes++;
            } else continue;
            assertEquals(1, path.length);
            assertTrue(paths.add(verb + " " + path[0]));
            if (verb.equals("GET")) continue;
            var caps =
                    Arrays.stream(method.getParameters())
                            .map(p -> p.getAnnotation(BoundedBody.class))
                            .filter(Objects::nonNull)
                            .toList();
            assertEquals(1, caps.size());
            assertEquals(verb.equals("PATCH") ? 20000 : 16384, caps.getFirst().maxBytes());
            assertEquals("INVALID_INPUT", caps.getFirst().errorCode());
        }
        assertEquals(7, reads);
        assertEquals(17, writes);
        assertEquals(24, paths.size());
        assertTrue(paths.contains("GET /documents/{document}/review-proposals/{proposal}"));
        assertTrue(paths.contains("GET /allocations/{allocation}/reverse-requests/{request}"));
        assertTrue(paths.contains("POST /allocations/{allocation}/admin-terminate"));
    }
}
