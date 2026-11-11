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

import java.time.LocalDate;
import java.util.*;

class FinanceReceivablesControllerTest {
    final FinanceReceivables service = mock(FinanceReceivables.class);
    final FinanceReceivablesController controller = new FinanceReceivablesController(service);
    final UUID book = UUID.randomUUID(), year = UUID.randomUUID(), target = UUID.randomUUID();

    Authentication actor() {
        var a = mock(Authentication.class);
        when(a.getName()).thenReturn("ar-maker");
        return a;
    }

    @Test
    void listAndDetailRequireExplicitAsOfDateAndRejectUnknownOrRepeatedQueryParams() {
        var missingAsOf = new MockHttpServletRequest();
        assertEquals(
                400,
                ((ResponseEntity<?>)
                                controller.list(
                                        actor(), book.toString(), year.toString(), missingAsOf))
                        .getStatusCode()
                        .value());

        var unknown = new MockHttpServletRequest();
        unknown.addParameter("asOf", "2026-01-31");
        unknown.addParameter("bypass", "true");
        assertEquals(
                400,
                ((ResponseEntity<?>)
                                controller.list(actor(), book.toString(), year.toString(), unknown))
                        .getStatusCode()
                        .value());

        var repeated = new MockHttpServletRequest();
        repeated.addParameter("asOf", "2026-01-31", "2026-02-28");
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
    void validListAndCreateForwardScopeAndBodyToService() {
        var getReq = new MockHttpServletRequest();
        getReq.addParameter("asOf", "2026-01-31");
        getReq.addParameter("limit", "15");
        var page = Map.of("items", List.of());
        when(service.read(
                        eq("ar-maker"),
                        eq(book),
                        eq(year),
                        isNull(),
                        isNull(),
                        eq(15),
                        eq(LocalDate.of(2026, 1, 31))))
                .thenReturn(page);
        var getRes =
                (ResponseEntity<?>)
                        controller.list(actor(), book.toString(), year.toString(), getReq);
        assertEquals(200, getRes.getStatusCode().value());
        assertSame(page, getRes.getBody());

        var postReq = new MockHttpServletRequest();
        var body = Map.<String, Object>of("kind", "CONTRACT");
        var created = Map.of("id", target.toString());
        when(service.write("ar-maker", book, year, "create", null, body, postReq))
                .thenReturn(created);
        var postRes =
                (ResponseEntity<?>)
                        controller.create(actor(), book.toString(), year.toString(), body, postReq);
        assertEquals(201, postRes.getStatusCode().value());
        assertSame(created, postRes.getBody());
    }

    @Test
    void writeRoutesEnforceBoundedBodyCapsAndRejectQueryParams() {
        var reqWithQuery = new MockHttpServletRequest();
        reqWithQuery.addParameter("force", "1");
        var res =
                (ResponseEntity<?>)
                        controller.action(
                                actor(),
                                book.toString(),
                                year.toString(),
                                target.toString(),
                                "submit",
                                Map.of(),
                                reqWithQuery);
        assertEquals(400, res.getStatusCode().value());

        int postCount = 0;
        for (var method : FinanceReceivablesController.class.getDeclaredMethods()) {
            if (method.getAnnotation(PostMapping.class) == null) continue;
            postCount++;
            var bodyParam =
                    Arrays.stream(method.getParameters())
                            .filter(p -> p.getType() == Map.class)
                            .findFirst()
                            .orElseThrow();
            var cap = bodyParam.getAnnotation(BoundedBody.class);
            assertNotNull(cap, method.getName());
            assertEquals("INVALID_INPUT", cap.errorCode());
            assertEquals(method.getName().equals("create") ? 32768 : 8192, cap.maxBytes());
        }
        assertEquals(7, postCount);
    }
}
