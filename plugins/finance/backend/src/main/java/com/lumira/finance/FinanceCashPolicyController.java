package com.lumira.finance;

import com.lumira.platform.common.BoundedBody;
import com.lumira.platform.modules.ModuleEndpoint;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/finance/v1/books/{book}/years/{year}/cashflow/classification-policies")
@ModuleEndpoint("finance")
public class FinanceCashPolicyController {
    private final FinanceCashPolicies policies;

    public FinanceCashPolicyController(FinanceCashPolicies policies) {
        this.policies = policies;
    }

    @GetMapping("/catalog")
    public Object catalog(Authentication actor, @PathVariable UUID book, @PathVariable UUID year) {
        return FinanceHttp.respond(200, () -> policies.catalog(actor.getName(), book, year));
    }

    @GetMapping
    public Object list(
            Authentication actor,
            @PathVariable UUID book,
            @PathVariable UUID year,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String transactionKind,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor) {
        return FinanceHttp.respond(
                200,
                () ->
                        policies.list(
                                actor.getName(),
                                book,
                                year,
                                status,
                                transactionKind,
                                limit,
                                cursor));
    }

    @GetMapping("/{id}")
    public Object detail(
            Authentication actor,
            @PathVariable UUID book,
            @PathVariable UUID year,
            @PathVariable UUID id) {
        return FinanceHttp.respond(200, () -> policies.detail(actor.getName(), book, year, id));
    }

    @PostMapping
    public Object create(
            Authentication actor,
            @PathVariable UUID book,
            @PathVariable UUID year,
            @RequestBody @BoundedBody(maxBytes = 60000, errorCode = "INVALID_INPUT")
                    Map<String, Object> input,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return FinanceHttp.respond(
                200,
                () ->
                        policies.write(
                                actor.getName(),
                                book,
                                year,
                                "create",
                                null,
                                null,
                                FinanceCashMatchCorrectionController.body(input, key, null)));
    }

    @PostMapping({"/{id}/submit", "/{id}/approve", "/{id}/reject", "/{id}/cancel"})
    public Object action(
            Authentication actor,
            @PathVariable UUID book,
            @PathVariable UUID year,
            @PathVariable UUID id,
            jakarta.servlet.http.HttpServletRequest request,
            @RequestBody @BoundedBody(maxBytes = 20000, errorCode = "INVALID_INPUT")
                    Map<String, Object> input,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = "If-Match", required = false) String match) {
        String path = request.getRequestURI();
        String op = path.substring(path.lastIndexOf('/') + 1);
        return FinanceHttp.respond(
                200,
                () ->
                        policies.write(
                                actor.getName(),
                                book,
                                year,
                                op,
                                id,
                                null,
                                FinanceCashMatchCorrectionController.body(input, key, match)));
    }

    @GetMapping("/{id}/retirement-requests")
    public Object retirements(
            Authentication actor,
            @PathVariable UUID book,
            @PathVariable UUID year,
            @PathVariable UUID id,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor) {
        return FinanceHttp.respond(
                200, () -> policies.retirements(actor.getName(), book, year, id, limit, cursor));
    }

    @PostMapping("/{id}/retirement-requests")
    public Object requestRetirement(
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
                        policies.write(
                                actor.getName(),
                                book,
                                year,
                                "retire",
                                id,
                                null,
                                FinanceCashMatchCorrectionController.body(input, key, match)));
    }

    @PostMapping({
        "/{id}/retirement-requests/{retirementId}/approve",
        "/{id}/retirement-requests/{retirementId}/reject",
        "/{id}/retirement-requests/{retirementId}/cancel"
    })
    public Object reviewRetirement(
            Authentication actor,
            @PathVariable UUID book,
            @PathVariable UUID year,
            @PathVariable UUID id,
            @PathVariable UUID retirementId,
            jakarta.servlet.http.HttpServletRequest request,
            @RequestBody @BoundedBody(maxBytes = 20000, errorCode = "INVALID_INPUT")
                    Map<String, Object> input,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = "If-Match", required = false) String match) {
        String path = request.getRequestURI();
        String op = "retire-" + path.substring(path.lastIndexOf('/') + 1);
        return FinanceHttp.respond(
                200,
                () ->
                        policies.write(
                                actor.getName(),
                                book,
                                year,
                                op,
                                id,
                                retirementId,
                                FinanceCashMatchCorrectionController.body(input, key, match)));
    }
}
