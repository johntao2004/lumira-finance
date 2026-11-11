package com.lumira.finance;

import com.lumira.platform.common.BoundedBody;
import com.lumira.platform.modules.ModuleEndpoint;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/finance/v1/books/{book}/years/{year}/cashflow/allocations")
@ModuleEndpoint("finance")
public class FinanceCashAllocationController {
    private final FinanceCashAllocations allocations;

    public FinanceCashAllocationController(FinanceCashAllocations allocations) {
        this.allocations = allocations;
    }

    @GetMapping("/eligible-source-reviews")
    public Object eligible(
            Authentication actor,
            @PathVariable UUID book,
            @PathVariable UUID year,
            @RequestParam(required = false) UUID reviewId,
            @RequestParam(required = false) UUID claimId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor) {
        return FinanceHttp.respond(
                200,
                () ->
                        allocations.eligible(
                                actor.getName(), book, year, reviewId, claimId, limit, cursor));
    }

    @GetMapping
    public Object list(
            Authentication actor,
            @PathVariable UUID book,
            @PathVariable UUID year,
            @RequestParam(required = false) UUID reviewId,
            @RequestParam(required = false) UUID claimId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor) {
        return FinanceHttp.respond(
                200,
                () ->
                        allocations.list(
                                actor.getName(),
                                book,
                                year,
                                reviewId,
                                claimId,
                                status,
                                limit,
                                cursor));
    }

    @GetMapping("/{id}")
    public Object detail(
            Authentication actor,
            @PathVariable UUID book,
            @PathVariable UUID year,
            @PathVariable UUID id) {
        return FinanceHttp.respond(200, () -> allocations.detail(actor.getName(), book, year, id));
    }

    @PostMapping
    public Object create(
            Authentication actor,
            @PathVariable UUID book,
            @PathVariable UUID year,
            @RequestBody @BoundedBody(maxBytes = 100000, errorCode = "INVALID_INPUT")
                    Map<String, Object> input,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return FinanceHttp.respond(
                200,
                () ->
                        allocations.write(
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
                        allocations.write(
                                actor.getName(),
                                book,
                                year,
                                op,
                                id,
                                null,
                                FinanceCashMatchCorrectionController.body(input, key, match)));
    }

    @GetMapping("/{id}/release-requests")
    public Object releases(
            Authentication actor,
            @PathVariable UUID book,
            @PathVariable UUID year,
            @PathVariable UUID id,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor) {
        return FinanceHttp.respond(
                200, () -> allocations.releases(actor.getName(), book, year, id, limit, cursor));
    }

    @PostMapping("/{id}/release-requests")
    public Object requestRelease(
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
                        allocations.write(
                                actor.getName(),
                                book,
                                year,
                                "release",
                                id,
                                null,
                                FinanceCashMatchCorrectionController.body(input, key, match)));
    }

    @PostMapping({
        "/{id}/release-requests/{requestId}/approve",
        "/{id}/release-requests/{requestId}/reject",
        "/{id}/release-requests/{requestId}/cancel"
    })
    public Object releaseDecision(
            Authentication actor,
            @PathVariable UUID book,
            @PathVariable UUID year,
            @PathVariable UUID id,
            @PathVariable UUID requestId,
            jakarta.servlet.http.HttpServletRequest request,
            @RequestBody @BoundedBody(maxBytes = 20000, errorCode = "INVALID_INPUT")
                    Map<String, Object> input,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = "If-Match", required = false) String match) {
        String path = request.getRequestURI();
        String op = "release-" + path.substring(path.lastIndexOf('/') + 1);
        return FinanceHttp.respond(
                200,
                () ->
                        allocations.write(
                                actor.getName(),
                                book,
                                year,
                                op,
                                id,
                                requestId,
                                FinanceCashMatchCorrectionController.body(input, key, match)));
    }

    @PostMapping("/replacement-requests")
    public Object requestReplacement(
            Authentication actor,
            @PathVariable UUID book,
            @PathVariable UUID year,
            @RequestBody @BoundedBody(maxBytes = 20000, errorCode = "INVALID_INPUT")
                    Map<String, Object> input,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return FinanceHttp.respond(
                200,
                () ->
                        allocations.write(
                                actor.getName(),
                                book,
                                year,
                                "replace",
                                null,
                                null,
                                FinanceCashMatchCorrectionController.body(input, key, null)));
    }

    @GetMapping("/{id}/replacement-requests")
    public Object replacements(
            Authentication actor,
            @PathVariable UUID book,
            @PathVariable UUID year,
            @PathVariable UUID id,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor) {
        return FinanceHttp.respond(
                200,
                () -> allocations.replacements(actor.getName(), book, year, id, limit, cursor));
    }

    @PostMapping({
        "/replacement-requests/{requestId}/approve",
        "/replacement-requests/{requestId}/reject",
        "/replacement-requests/{requestId}/cancel"
    })
    public Object replacementDecision(
            Authentication actor,
            @PathVariable UUID book,
            @PathVariable UUID year,
            @PathVariable UUID requestId,
            jakarta.servlet.http.HttpServletRequest request,
            @RequestBody @BoundedBody(maxBytes = 20000, errorCode = "INVALID_INPUT")
                    Map<String, Object> input,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = "If-Match", required = false) String match) {
        String path = request.getRequestURI();
        String op = "replace-" + path.substring(path.lastIndexOf('/') + 1);
        return FinanceHttp.respond(
                200,
                () ->
                        allocations.write(
                                actor.getName(),
                                book,
                                year,
                                op,
                                null,
                                requestId,
                                FinanceCashMatchCorrectionController.body(input, key, match)));
    }
}
