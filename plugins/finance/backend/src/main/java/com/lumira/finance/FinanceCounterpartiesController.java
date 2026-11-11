package com.lumira.finance;

import com.lumira.platform.common.BoundedBody;
import com.lumira.platform.modules.ModuleEndpoint;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/** Organization declarations and independently published versions; no accounting effects. */
@RestController
@RequestMapping("/api/finance/v1/books/{book}/counterparties")
@ModuleEndpoint("finance")
public class FinanceCounterpartiesController {
    private final FinanceCounterparties service;

    public FinanceCounterpartiesController(FinanceCounterparties service) {
        this.service = service;
    }

    private static UUID id(String value) {
        return value == null ? null : FinancePersonDirectory.uuid(value);
    }

    private Object read(
            Authentication actor,
            String book,
            String operation,
            String target,
            String revision,
            HttpServletRequest request) {
        return FinanceHttp.respond(
                200,
                () ->
                        service.read(
                                actor.getName(),
                                id(book),
                                operation,
                                id(target),
                                id(revision),
                                request));
    }

    private Object write(
            Authentication actor,
            String book,
            String operation,
            String target,
            String revision,
            Map<String, Object> body,
            HttpServletRequest request,
            int status) {
        return FinanceHttp.respond(
                status,
                () -> {
                    if (!request.getParameterMap().isEmpty())
                        throw FinanceException.invalid("往来主数据写操作不接受查询参数");
                    return service.write(
                            actor.getName(),
                            id(book),
                            operation,
                            id(target),
                            id(revision),
                            body,
                            request);
                });
    }

    @GetMapping
    public Object list(
            Authentication actor, @PathVariable String book, HttpServletRequest request) {
        return read(actor, book, "list", null, null, request);
    }

    @GetMapping("/{id}")
    public Object detail(
            Authentication actor,
            @PathVariable String book,
            @PathVariable("id") String target,
            HttpServletRequest request) {
        return read(actor, book, "detail", target, null, request);
    }

    @GetMapping("/{id}/revisions")
    public Object revisions(
            Authentication actor,
            @PathVariable String book,
            @PathVariable("id") String target,
            HttpServletRequest request) {
        return read(actor, book, "revisions", target, null, request);
    }

    @GetMapping("/{id}/revisions/{revisionId}")
    public Object revision(
            Authentication actor,
            @PathVariable String book,
            @PathVariable("id") String target,
            @PathVariable("revisionId") String revision,
            HttpServletRequest request) {
        return read(actor, book, "revision", target, revision, request);
    }

    @PostMapping
    public Object create(
            Authentication actor,
            @PathVariable String book,
            @RequestBody @BoundedBody(maxBytes = 16384, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(actor, book, "create", null, null, body, request, 201);
    }

    @PostMapping("/{id}/revision-proposals")
    public Object propose(
            Authentication actor,
            @PathVariable String book,
            @PathVariable("id") String target,
            @RequestBody @BoundedBody(maxBytes = 16384, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(actor, book, "propose", target, null, body, request, 201);
    }

    @PostMapping("/{id}/revisions/{revisionId}/approve")
    public Object approve(
            Authentication actor,
            @PathVariable String book,
            @PathVariable("id") String target,
            @PathVariable("revisionId") String revision,
            @RequestBody @BoundedBody(maxBytes = 8192, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(actor, book, "approve", target, revision, body, request, 200);
    }

    @PostMapping("/{id}/revisions/{revisionId}/reject")
    public Object reject(
            Authentication actor,
            @PathVariable String book,
            @PathVariable("id") String target,
            @PathVariable("revisionId") String revision,
            @RequestBody @BoundedBody(maxBytes = 8192, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(actor, book, "reject", target, revision, body, request, 200);
    }

    @PostMapping("/{id}/revisions/{revisionId}/cancel")
    public Object cancel(
            Authentication actor,
            @PathVariable String book,
            @PathVariable("id") String target,
            @PathVariable("revisionId") String revision,
            @RequestBody @BoundedBody(maxBytes = 8192, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(actor, book, "cancel", target, revision, body, request, 200);
    }
}
