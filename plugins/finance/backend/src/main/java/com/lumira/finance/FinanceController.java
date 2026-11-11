package com.lumira.finance;

import com.lumira.platform.common.BoundedBody;
import com.lumira.platform.modules.ModuleEndpoint;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/finance/v1")
@ModuleEndpoint("finance")
public class FinanceController {
    private final FinanceService service;

    public FinanceController(FinanceService service) {
        this.service = service;
    }

    static Map<String, Object> body(Map<String, Object> input, String key, String match) {
        Map<String, Object> result = new LinkedHashMap<>(input);
        if (key != null) {
            if (result.containsKey("idempotencyKey") && !key.equals(result.get("idempotencyKey")))
                throw FinanceException.invalid("幂等键header与JSON冲突");
            result.put("idempotencyKey", key);
        }
        if (match != null) {
            String v = match.replace("\"", "");
            if (result.containsKey("expectedVersion")
                    && !v.equals(result.get("expectedVersion").toString()))
                throw FinanceException.invalid("版本header与JSON冲突");
            result.put("expectedVersion", v);
        }
        return result;
    }

    @GetMapping("/books")
    public Object books(Authentication a) {
        return FinanceHttp.respond(
                200,
                () -> {
                    return service.read(a.getName(), null, null, "books", null, null);
                });
    }

    @PostMapping("/books")
    public Object book(
            Authentication a,
            @RequestBody @BoundedBody(maxBytes = 20000, errorCode = "INVALID_INPUT")
                    Map<String, Object> in,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return FinanceHttp.respond(
                200,
                () -> {
                    return service.write(
                            a.getName(), null, null, "book", null, body(in, key, null));
                });
    }

    @GetMapping({"/books/{book}/members", "/books/{book}/years", "/books/{book}/tax-profile"})
    public Object bookResources(
            Authentication a,
            @PathVariable UUID book,
            jakarta.servlet.http.HttpServletRequest request) {
        return FinanceHttp.respond(
                200,
                () -> {
                    String resource =
                            request.getRequestURI()
                                    .substring(request.getRequestURI().lastIndexOf('/') + 1);
                    return service.read(a.getName(), book, null, resource, null, null);
                });
    }

    @PostMapping({"/books/{book}/members", "/books/{book}/years", "/books/{book}/tax-profile"})
    public Object bookWrite(
            Authentication a,
            @PathVariable UUID book,
            jakarta.servlet.http.HttpServletRequest request,
            @RequestBody @BoundedBody(maxBytes = 20000, errorCode = "INVALID_INPUT")
                    Map<String, Object> in,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return FinanceHttp.respond(
                200,
                () -> {
                    String op =
                            request.getRequestURI()
                                    .substring(request.getRequestURI().lastIndexOf('/') + 1);
                    return service.write(a.getName(), book, null, op, null, body(in, key, null));
                });
    }

    @GetMapping({
        "/books/{book}/years/{year}/periods",
        "/books/{book}/years/{year}/accounts",
        "/books/{book}/years/{year}/journals",
        "/books/{book}/years/{year}/trial-balance",
        "/books/{book}/years/{year}/ledger",
        "/books/{book}/years/{year}/reports",
        "/books/{book}/years/{year}/year-close-plans"
    })
    public Object yearResources(
            Authentication a,
            @PathVariable UUID book,
            @PathVariable UUID year,
            jakarta.servlet.http.HttpServletRequest request,
            @RequestParam(required = false) UUID accountId) {
        return FinanceHttp.respond(
                200,
                () -> {
                    String resource =
                            request.getRequestURI()
                                    .substring(request.getRequestURI().lastIndexOf('/') + 1);
                    return service.read(a.getName(), book, year, resource, null, accountId);
                });
    }

    @GetMapping("/books/{book}/years/{year}/journals/{journal}")
    public Object journal(
            Authentication a,
            @PathVariable UUID book,
            @PathVariable UUID year,
            @PathVariable UUID journal) {
        return FinanceHttp.respond(
                200,
                () -> {
                    return service.read(a.getName(), book, year, "journals", journal, null);
                });
    }

    @GetMapping("/books/{book}/years/{year}/journals/{journal}/documents")
    public Object documents(
            Authentication a,
            @PathVariable UUID book,
            @PathVariable UUID year,
            @PathVariable UUID journal) {
        return FinanceHttp.respond(
                200,
                () -> {
                    return service.read(a.getName(), book, year, "documents", journal, null);
                });
    }

    @PostMapping({
        "/books/{book}/years/{year}/accounts",
        "/books/{book}/years/{year}/journals",
        "/books/{book}/years/{year}/year-close-plans",
        "/books/{book}/years/{year}/closing-drafts"
    })
    public Object yearWrite(
            Authentication a,
            @PathVariable UUID book,
            @PathVariable UUID year,
            jakarta.servlet.http.HttpServletRequest request,
            @RequestBody @BoundedBody(maxBytes = 200000, errorCode = "INVALID_INPUT")
                    Map<String, Object> in,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return FinanceHttp.respond(
                200,
                () -> {
                    String path = request.getRequestURI();
                    String op =
                            path.endsWith("/journals")
                                    ? "journal"
                                    : path.endsWith("/accounts")
                                            ? "accounts"
                                            : path.endsWith("/closing-drafts")
                                                    ? "closing-drafts"
                                                    : "year-plan";
                    return service.write(a.getName(), book, year, op, null, body(in, key, null));
                });
    }

    @PatchMapping("/books/{book}/years/{year}/journals/{journal}")
    public Object edit(
            Authentication a,
            @PathVariable UUID book,
            @PathVariable UUID year,
            @PathVariable UUID journal,
            @RequestBody @BoundedBody(maxBytes = 200000, errorCode = "INVALID_INPUT")
                    Map<String, Object> in,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = "If-Match", required = false) String match) {
        return FinanceHttp.respond(
                200,
                () -> {
                    return service.write(
                            a.getName(), book, year, "edit", journal, body(in, key, match));
                });
    }

    @PostMapping({
        "/books/{book}/years/{year}/journals/{target}/withdraw",
        "/books/{book}/years/{year}/journals/{target}/reject",
        "/books/{book}/years/{year}/journals/{target}/cancel",
        "/books/{book}/years/{year}/journals/{target}/submit",
        "/books/{book}/years/{year}/journals/{target}/approve",
        "/books/{book}/years/{year}/journals/{target}/post",
        "/books/{book}/years/{year}/journals/{target}/reversals",
        "/books/{book}/years/{year}/journals/{target}/documents",
        "/books/{book}/years/{year}/periods/{target}/close-preview",
        "/books/{book}/years/{year}/periods/{target}/close",
        "/books/{book}/years/{year}/year-close-plans/{target}/approve",
        "/books/{book}/years/{year}/year-close-plans/{target}/execute"
    })
    public Object action(
            Authentication a,
            @PathVariable UUID book,
            @PathVariable UUID year,
            @PathVariable UUID target,
            jakarta.servlet.http.HttpServletRequest request,
            @RequestBody @BoundedBody(maxBytes = 200000, errorCode = "INVALID_INPUT")
                    Map<String, Object> in,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = "If-Match", required = false) String match) {
        return FinanceHttp.respond(
                200,
                () -> {
                    String path = request.getRequestURI(),
                            op = path.substring(path.lastIndexOf('/') + 1);
                    if (path.contains("/year-close-plans/"))
                        op = op.equals("approve") ? "year-approve" : "year-execute";
                    return service.write(a.getName(), book, year, op, target, body(in, key, match));
                });
    }

    @GetMapping("/books/{book}/years/{year}/year-close-plans/{plan}")
    public Object plan(
            Authentication a,
            @PathVariable UUID book,
            @PathVariable UUID year,
            @PathVariable UUID plan) {
        return FinanceHttp.respond(
                200,
                () -> {
                    return service.read(a.getName(), book, year, "year-close-plans", plan, null);
                });
    }
}
