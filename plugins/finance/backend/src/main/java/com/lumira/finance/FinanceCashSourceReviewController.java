package com.lumira.finance;

import com.lumira.platform.common.BoundedBody;
import com.lumira.platform.modules.ModuleEndpoint;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/finance/v1/books/{book}/years/{year}/cashflow/source-reviews")
@ModuleEndpoint("finance")
public class FinanceCashSourceReviewController {
    private final FinanceCashSourceReviews reviews;

    public FinanceCashSourceReviewController(FinanceCashSourceReviews reviews) {
        this.reviews = reviews;
    }

    @GetMapping("/eligible-effective-claims")
    public Object eligible(
            Authentication actor,
            @PathVariable UUID book,
            @PathVariable UUID year,
            @RequestParam(required = false) UUID rawRegistrationId,
            @RequestParam(required = false) UUID claimId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor) {
        return FinanceHttp.respond(
                200,
                () ->
                        reviews.eligible(
                                actor.getName(),
                                book,
                                year,
                                rawRegistrationId,
                                claimId,
                                limit,
                                cursor));
    }

    @GetMapping
    public Object list(
            Authentication actor,
            @PathVariable UUID book,
            @PathVariable UUID year,
            @RequestParam(required = false) UUID rawRegistrationId,
            @RequestParam(required = false) UUID claimId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor) {
        return FinanceHttp.respond(
                200,
                () ->
                        reviews.list(
                                actor.getName(),
                                book,
                                year,
                                rawRegistrationId,
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
        return FinanceHttp.respond(200, () -> reviews.detail(actor.getName(), book, year, id));
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
                        reviews.write(
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
                        reviews.write(
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
                200, () -> reviews.releases(actor.getName(), book, year, id, limit, cursor));
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
                        reviews.write(
                                actor.getName(),
                                book,
                                year,
                                "release",
                                id,
                                null,
                                FinanceCashMatchCorrectionController.body(input, key, match)));
    }

    @PostMapping({
        "/{id}/release-requests/{releaseId}/approve",
        "/{id}/release-requests/{releaseId}/reject",
        "/{id}/release-requests/{releaseId}/cancel"
    })
    public Object reviewRelease(
            Authentication actor,
            @PathVariable UUID book,
            @PathVariable UUID year,
            @PathVariable UUID id,
            @PathVariable UUID releaseId,
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
                        reviews.write(
                                actor.getName(),
                                book,
                                year,
                                op,
                                id,
                                releaseId,
                                FinanceCashMatchCorrectionController.body(input, key, match)));
    }
}
