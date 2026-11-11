package com.lumira.finance;

import com.lumira.platform.common.BoundedBody;
import com.lumira.platform.modules.ModuleEndpoint;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/finance/v1/books/{book}/years/{year}/cashflow/movement-registrations")
@ModuleEndpoint("finance")
public class FinanceCashMovementController {
    private final FinanceCashMovements movements;

    public FinanceCashMovementController(FinanceCashMovements movements) {
        this.movements = movements;
    }

    @GetMapping("/catalog")
    public Object catalog(Authentication actor, @PathVariable UUID book, @PathVariable UUID year) {
        return FinanceHttp.respond(200, () -> movements.catalog(actor.getName(), book, year));
    }

    @GetMapping("/eligible-cash-legs")
    public Object eligible(
            Authentication actor,
            @PathVariable UUID book,
            @PathVariable UUID year,
            @RequestParam UUID cashScopeRevisionId,
            @RequestParam UUID accountId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor) {
        return FinanceHttp.respond(
                200,
                () ->
                        movements.eligible(
                                actor.getName(),
                                book,
                                year,
                                cashScopeRevisionId,
                                accountId,
                                limit,
                                cursor));
    }

    @GetMapping
    public Object list(
            Authentication actor,
            @PathVariable UUID book,
            @PathVariable UUID year,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor) {
        return FinanceHttp.respond(
                200, () -> movements.list(actor.getName(), book, year, status, limit, cursor));
    }

    @GetMapping("/{id}")
    public Object detail(
            Authentication actor,
            @PathVariable UUID book,
            @PathVariable UUID year,
            @PathVariable UUID id) {
        return FinanceHttp.respond(200, () -> movements.detail(actor.getName(), book, year, id));
    }

    @PostMapping
    public Object create(
            Authentication actor,
            @PathVariable UUID book,
            @PathVariable UUID year,
            @RequestBody @BoundedBody(maxBytes = 30000, errorCode = "INVALID_INPUT")
                    Map<String, Object> input,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return FinanceHttp.respond(
                200,
                () ->
                        movements.write(
                                actor.getName(),
                                book,
                                year,
                                null,
                                FinanceController.body(input, key, null)));
    }

    @PostMapping("/{id}/void")
    public Object voidRegistration(
            Authentication actor,
            @PathVariable UUID book,
            @PathVariable UUID year,
            @PathVariable UUID id,
            @RequestBody @BoundedBody(maxBytes = 20000, errorCode = "INVALID_INPUT")
                    Map<String, Object> input,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = "If-Match", required = false) String match) {
        return FinanceHttp.respond(
                200,
                () ->
                        movements.write(
                                actor.getName(),
                                book,
                                year,
                                id,
                                FinanceController.body(input, key, match)));
    }
}
