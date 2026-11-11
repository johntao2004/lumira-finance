package com.lumira.finance;

import com.lumira.platform.common.BoundedBody;
import com.lumira.platform.modules.ModuleEndpoint;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/finance/v1/books/{book}/years/{year}/cashflow/scope-revisions")
@ModuleEndpoint("finance")
public class FinanceCashScopeController {
    private final FinanceCashScopes scopes;

    public FinanceCashScopeController(FinanceCashScopes scopes) {
        this.scopes = scopes;
    }

    @GetMapping
    public Object list(
            Authentication actor,
            @PathVariable UUID book,
            @PathVariable UUID year,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor) {
        return FinanceHttp.respond(
                200, () -> scopes.list(actor.getName(), book, year, limit, cursor));
    }

    @GetMapping("/{id}")
    public Object detail(
            Authentication actor,
            @PathVariable UUID book,
            @PathVariable UUID year,
            @PathVariable UUID id) {
        return FinanceHttp.respond(200, () -> scopes.detail(actor.getName(), book, year, id));
    }

    @PostMapping
    public Object create(
            Authentication actor,
            @PathVariable UUID book,
            @PathVariable UUID year,
            @RequestBody @BoundedBody(maxBytes = 150000, errorCode = "INVALID_INPUT")
                    Map<String, Object> input,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return FinanceHttp.respond(
                200,
                () ->
                        scopes.write(
                                actor.getName(),
                                book,
                                year,
                                "create",
                                null,
                                FinanceController.body(input, key, null)));
    }

    @PostMapping({"/{id}/submit", "/{id}/approve", "/{id}/reject"})
    public Object decide(
            Authentication actor,
            @PathVariable UUID book,
            @PathVariable UUID year,
            @PathVariable UUID id,
            jakarta.servlet.http.HttpServletRequest request,
            @RequestBody @BoundedBody(maxBytes = 20000, errorCode = "INVALID_INPUT")
                    Map<String, Object> input,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = "If-Match", required = false) String match) {
        return FinanceHttp.respond(
                200,
                () -> {
                    String path = request.getRequestURI();
                    String operation = path.substring(path.lastIndexOf('/') + 1);
                    return scopes.write(
                            actor.getName(),
                            book,
                            year,
                            operation,
                            id,
                            FinanceController.body(input, key, match));
                });
    }
}
