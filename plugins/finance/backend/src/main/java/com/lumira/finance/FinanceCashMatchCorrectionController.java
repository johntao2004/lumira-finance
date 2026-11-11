package com.lumira.finance;

import com.lumira.platform.common.BoundedBody;
import com.lumira.platform.modules.ModuleEndpoint;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/finance/v1/books/{book}/years/{year}/cashflow")
@ModuleEndpoint("finance")
public class FinanceCashMatchCorrectionController {
    private final FinanceCashMatchCorrections corrections;

    public FinanceCashMatchCorrectionController(FinanceCashMatchCorrections corrections) {
        this.corrections = corrections;
    }

    static Map<String, Object> body(Map<String, Object> input, String key, String match) {
        if (input.containsKey("expectedVersion")
                && !(input.get("expectedVersion") instanceof Integer
                        || input.get("expectedVersion") instanceof Long))
            throw FinanceException.invalid("expectedVersion须为JSON整数");
        String token = match;
        if (token != null) {
            String quote = Character.toString(34);
            if (token.startsWith(quote) && token.endsWith(quote) && token.length() > 1)
                token = token.substring(1, token.length() - 1);
            if (!token.matches("[1-9][0-9]*")) throw FinanceException.invalid("If-Match须为正整数版本");
        }
        var result = FinanceController.body(input, key, match);
        if (token != null) {
            try {
                result.put("expectedVersion", Long.parseLong(token));
            } catch (NumberFormatException bad) {
                throw FinanceException.invalid("If-Match版本溢出");
            }
        }
        return result;
    }

    @GetMapping("/match-corrections")
    public Object list(
            Authentication actor,
            @PathVariable UUID book,
            @PathVariable UUID year,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor) {
        return FinanceHttp.respond(
                200, () -> corrections.list(actor.getName(), book, year, status, limit, cursor));
    }

    @GetMapping("/match-corrections/eligible-cash-legs")
    public Object eligible(
            Authentication actor,
            @PathVariable UUID book,
            @PathVariable UUID year,
            @RequestParam UUID originalRegistrationId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor) {
        return FinanceHttp.respond(
                200,
                () ->
                        corrections.eligible(
                                actor.getName(),
                                book,
                                year,
                                originalRegistrationId,
                                limit,
                                cursor));
    }

    @GetMapping("/match-corrections/{id}")
    public Object detail(
            Authentication actor,
            @PathVariable UUID book,
            @PathVariable UUID year,
            @PathVariable UUID id) {
        return FinanceHttp.respond(200, () -> corrections.detail(actor.getName(), book, year, id));
    }

    @GetMapping("/movement-registrations/{rawId}/effective-match")
    public Object effective(
            Authentication actor,
            @PathVariable UUID book,
            @PathVariable UUID year,
            @PathVariable UUID rawId) {
        return FinanceHttp.respond(
                200, () -> corrections.effective(actor.getName(), book, year, rawId));
    }

    @PostMapping("/match-corrections")
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
                        corrections.write(
                                actor.getName(),
                                book,
                                year,
                                "create",
                                null,
                                null,
                                body(input, key, null)));
    }

    @PostMapping({
        "/match-corrections/{id}/submit",
        "/match-corrections/{id}/approve",
        "/match-corrections/{id}/reject",
        "/match-corrections/{id}/cancel"
    })
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
                        corrections.write(
                                actor.getName(),
                                book,
                                year,
                                op,
                                id,
                                null,
                                body(input, key, match)));
    }

    @PostMapping("/match-corrections/{id}/withdrawal-requests")
    public Object requestWithdrawal(
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
                        corrections.write(
                                actor.getName(),
                                book,
                                year,
                                "withdraw",
                                id,
                                null,
                                body(input, key, match)));
    }

    @GetMapping("/match-corrections/{id}/withdrawal-requests")
    public Object withdrawals(
            Authentication actor,
            @PathVariable UUID book,
            @PathVariable UUID year,
            @PathVariable UUID id,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor) {
        return FinanceHttp.respond(
                200, () -> corrections.withdrawals(actor.getName(), book, year, id, limit, cursor));
    }

    @PostMapping({
        "/match-corrections/{id}/withdrawal-requests/{withdrawalId}/approve",
        "/match-corrections/{id}/withdrawal-requests/{withdrawalId}/reject"
    })
    public Object reviewWithdrawal(
            Authentication actor,
            @PathVariable UUID book,
            @PathVariable UUID year,
            @PathVariable UUID id,
            @PathVariable UUID withdrawalId,
            jakarta.servlet.http.HttpServletRequest request,
            @RequestBody @BoundedBody(maxBytes = 20000, errorCode = "INVALID_INPUT")
                    Map<String, Object> input,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = "If-Match", required = false) String match) {
        String path = request.getRequestURI();
        String op = "withdraw-" + path.substring(path.lastIndexOf('/') + 1);
        return FinanceHttp.respond(
                200,
                () ->
                        corrections.write(
                                actor.getName(),
                                book,
                                year,
                                op,
                                id,
                                withdrawalId,
                                body(input, key, match)));
    }
}
