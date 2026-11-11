package com.lumira.finance;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.common.BoundedBody;
import com.lumira.platform.modules.ModuleEndpoint;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.util.*;

/** HTTP-adapter tests; live host auth, body enforcement and database proofs have separate tests. */
class FinanceJournalPersonControllerTest {
    private final FinanceJournalPersonSod service = mock(FinanceJournalPersonSod.class);
    private final FinanceJournalPersonController controller =
            new FinanceJournalPersonController(service);
    private final Authentication actor = mock(Authentication.class);
    private final UUID book = UUID.randomUUID(),
            year = UUID.randomUUID(),
            target = UUID.randomUUID();
    private final MockHttpServletRequest request = new MockHttpServletRequest();

    private Authentication actor() {
        when(actor.getName()).thenReturn("synthetic-business-actor");
        return actor;
    }

    @Test
    void canonicalScopeAndHostRequestArePassedToAllReadOperations() {
        for (String operation : List.of("mode", "activation", "context")) {
            UUID id = operation.equals("mode") ? null : target;
            var value = Map.of("operation", operation);
            when(service.read("synthetic-business-actor", book, year, operation, id, request))
                    .thenReturn(value);
            Object result =
                    switch (operation) {
                        case "mode" ->
                                controller.mode(actor(), book.toString(), year.toString(), request);
                        case "activation" ->
                                controller.activation(
                                        actor(),
                                        book.toString(),
                                        year.toString(),
                                        target.toString(),
                                        request);
                        default ->
                                controller.context(
                                        actor(),
                                        book.toString(),
                                        year.toString(),
                                        target.toString(),
                                        request);
                    };
            var response = (ResponseEntity<?>) result;
            assertEquals(200, response.getStatusCode().value());
            assertSame(value, response.getBody());
            verify(service).read("synthetic-business-actor", book, year, operation, id, request);
        }
    }

    @Test
    void queryAndNoncanonicalIdsAreRejectedBeforeServiceWork() {
        request.addParameter("limit", "1", "2");
        var query =
                (ResponseEntity<?>)
                        controller.mode(actor(), book.toString(), year.toString(), request);
        assertEquals(400, query.getStatusCode().value());
        assertEquals("INVALID_INPUT", ((Map<?, ?>) query.getBody()).get("code"));
        request.removeAllParameters();
        var malformed =
                (ResponseEntity<?>)
                        controller.context(
                                actor(), book.toString(), year.toString(), "bad-id", request);
        assertEquals(400, malformed.getStatusCode().value());
        var uppercase =
                (ResponseEntity<?>)
                        controller.mode(
                                actor(),
                                "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA",
                                year.toString(),
                                request);
        assertEquals(400, uppercase.getStatusCode().value());
        verifyNoInteractions(service);
    }

    @Test
    void writesPassOriginalBodyAndUseDistinctActivationAndJournalOperations() {
        var body = new LinkedHashMap<String, Object>();
        body.put("schemaVersion", "journal-person-sod/1");
        body.put("idempotencyKey", "synthetic-request-key");
        for (String operation :
                List.of(
                        "request",
                        "approve",
                        "reject",
                        "cancel",
                        "person-approve",
                        "person-post",
                        "terminate")) {
            UUID id = operation.equals("request") ? null : target;
            var value = Map.of("operation", operation);
            when(service.write(
                            "synthetic-business-actor", book, year, operation, id, body, request))
                    .thenReturn(value);
            Object result =
                    switch (operation) {
                        case "request" ->
                                controller.request(
                                        actor(), book.toString(), year.toString(), body, request);
                        case "approve" ->
                                controller.approve(
                                        actor(),
                                        book.toString(),
                                        year.toString(),
                                        target.toString(),
                                        body,
                                        request);
                        case "reject" ->
                                controller.reject(
                                        actor(),
                                        book.toString(),
                                        year.toString(),
                                        target.toString(),
                                        body,
                                        request);
                        case "cancel" ->
                                controller.cancel(
                                        actor(),
                                        book.toString(),
                                        year.toString(),
                                        target.toString(),
                                        body,
                                        request);
                        case "person-approve" ->
                                controller.personApprove(
                                        actor(),
                                        book.toString(),
                                        year.toString(),
                                        target.toString(),
                                        body,
                                        request);
                        case "person-post" ->
                                controller.personPost(
                                        actor(),
                                        book.toString(),
                                        year.toString(),
                                        target.toString(),
                                        body,
                                        request);
                        default ->
                                controller.terminate(
                                        actor(),
                                        book.toString(),
                                        year.toString(),
                                        target.toString(),
                                        body,
                                        request);
                    };
            var response = (ResponseEntity<?>) result;
            assertEquals(
                    Set.of("request", "person-approve").contains(operation) ? 201 : 200,
                    response.getStatusCode().value());
            assertSame(value, response.getBody());
            verify(service)
                    .write("synthetic-business-actor", book, year, operation, id, body, request);
        }
    }

    @Test
    void queryRejectionAlsoPrecedesWriteAndFinanceErrorsRetainTheirCode() {
        request.addParameter("unexpected", "");
        var invalid =
                (ResponseEntity<?>)
                        controller.request(
                                actor(), book.toString(), year.toString(), Map.of(), request);
        assertEquals(400, invalid.getStatusCode().value());
        verifyNoInteractions(service);
        request.removeAllParameters();
        when(service.read("synthetic-business-actor", book, year, "mode", null, request))
                .thenThrow(
                        new FinanceException(
                                "SELF_APPROVAL_FORBIDDEN", "independent person required"));
        var rejected =
                (ResponseEntity<?>)
                        controller.mode(actor(), book.toString(), year.toString(), request);
        assertEquals(403, rejected.getStatusCode().value());
        assertEquals("SELF_APPROVAL_FORBIDDEN", ((Map<?, ?>) rejected.getBody()).get("code"));
    }

    @Test
    void exactTenRoutesHavePinnedBodyCapsAndFinanceModuleOwnership() {
        assertEquals(
                "finance",
                FinanceJournalPersonController.class.getAnnotation(ModuleEndpoint.class).value());
        Set<String> paths = new HashSet<>();
        int reads = 0, writes = 0;
        for (Method method : FinanceJournalPersonController.class.getDeclaredMethods()) {
            GetMapping get = method.getAnnotation(GetMapping.class);
            PostMapping post = method.getAnnotation(PostMapping.class);
            if (get != null) {
                assertEquals(1, get.value().length);
                assertTrue(paths.add("GET " + get.value()[0]));
                reads++;
            }
            if (post != null) {
                assertEquals(1, post.value().length);
                assertTrue(paths.add("POST " + post.value()[0]));
                writes++;
                var bounds =
                        Arrays.stream(method.getParameters())
                                .map(p -> p.getAnnotation(BoundedBody.class))
                                .filter(Objects::nonNull)
                                .toList();
                assertEquals(1, bounds.size());
                assertEquals(
                        Set.of("request", "personApprove", "personPost").contains(method.getName())
                                ? 16384
                                : 8192,
                        bounds.getFirst().maxBytes());
                assertEquals("INVALID_INPUT", bounds.getFirst().errorCode());
            }
        }
        assertEquals(3, reads);
        assertEquals(7, writes);
        assertEquals(10, paths.size());
    }
}
