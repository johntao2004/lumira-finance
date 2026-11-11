package com.lumira.finance;

import com.lumira.platform.common.BoundedBody;
import com.lumira.platform.modules.ModuleEndpoint;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Finance-owned, content-bound journal protocol; legacy journal payloads stay unchanged. */
@RestController
@RequestMapping("/api/finance/v1/books/{book}/years/{year}")
@ModuleEndpoint("finance")
public class FinanceJournalPersonController {
    private final FinanceJournalPersonSod service;

    public FinanceJournalPersonController(FinanceJournalPersonSod service) {
        this.service = service;
    }

    private static void query(HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) throw FinanceException.invalid("人员审签协议不接受查询参数");
    }

    private Object read(
            Authentication actor,
            String book,
            String year,
            String operation,
            String target,
            HttpServletRequest request) {
        return FinanceHttp.respond(
                200,
                () -> {
                    query(request);
                    return service.read(
                            actor.getName(),
                            FinancePersonDirectory.uuid(book),
                            FinancePersonDirectory.uuid(year),
                            operation,
                            target == null ? null : FinancePersonDirectory.uuid(target),
                            request);
                });
    }

    private Object write(
            Authentication actor,
            String book,
            String year,
            String operation,
            String target,
            Map<String, Object> body,
            HttpServletRequest request,
            int successStatus) {
        return FinanceHttp.respond(
                successStatus,
                () -> {
                    query(request);
                    return service.write(
                            actor.getName(),
                            FinancePersonDirectory.uuid(book),
                            FinancePersonDirectory.uuid(year),
                            operation,
                            target == null ? null : FinancePersonDirectory.uuid(target),
                            body,
                            request);
                });
    }

    @GetMapping("/journal-person-sod")
    public Object mode(
            Authentication a,
            @PathVariable String book,
            @PathVariable String year,
            HttpServletRequest request) {
        return read(a, book, year, "mode", null, request);
    }

    @GetMapping("/journal-person-sod/activation-requests/{id}")
    public Object activation(
            Authentication a,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable String id,
            HttpServletRequest request) {
        return read(a, book, year, "activation", id, request);
    }

    @GetMapping("/journals/{id}/person-sod")
    public Object context(
            Authentication a,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable String id,
            HttpServletRequest request) {
        return read(a, book, year, "context", id, request);
    }

    @PostMapping("/journal-person-sod/activation-requests")
    public Object request(
            Authentication a,
            @PathVariable String book,
            @PathVariable String year,
            @RequestBody @BoundedBody(maxBytes = 16384, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(a, book, year, "request", null, body, request, 201);
    }

    @PostMapping("/journal-person-sod/activation-requests/{id}/approve")
    public Object approve(
            Authentication a,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable String id,
            @RequestBody @BoundedBody(maxBytes = 8192, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(a, book, year, "approve", id, body, request, 200);
    }

    @PostMapping("/journal-person-sod/activation-requests/{id}/reject")
    public Object reject(
            Authentication a,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable String id,
            @RequestBody @BoundedBody(maxBytes = 8192, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(a, book, year, "reject", id, body, request, 200);
    }

    @PostMapping("/journal-person-sod/activation-requests/{id}/cancel")
    public Object cancel(
            Authentication a,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable String id,
            @RequestBody @BoundedBody(maxBytes = 8192, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(a, book, year, "cancel", id, body, request, 200);
    }

    @PostMapping("/journals/{id}/person-approvals")
    public Object personApprove(
            Authentication a,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable String id,
            @RequestBody @BoundedBody(maxBytes = 16384, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(a, book, year, "person-approve", id, body, request, 201);
    }

    @PostMapping("/journals/{id}/person-post")
    public Object personPost(
            Authentication a,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable String id,
            @RequestBody @BoundedBody(maxBytes = 16384, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(a, book, year, "person-post", id, body, request, 200);
    }

    @PostMapping("/journals/{id}/terminate-draft")
    public Object terminate(
            Authentication a,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable String id,
            @RequestBody @BoundedBody(maxBytes = 8192, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(a, book, year, "terminate", id, body, request, 200);
    }
}
