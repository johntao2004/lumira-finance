package com.lumira.finance;

import com.lumira.platform.common.BoundedBody;
import com.lumira.platform.modules.ModuleEndpoint;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Source-derived closing producer boundary; ordinary journal requests retain their own protocol.
 */
@RestController
@RequestMapping("/api/finance/v1/books/{book}/years/{year}/profit-closing")
@ModuleEndpoint("finance")
public class FinanceProfitClosingController {
    private final FinanceProfitClosing service;

    public FinanceProfitClosingController(FinanceProfitClosing service) {
        this.service = service;
    }

    private static UUID id(String value) {
        return value == null ? null : FinancePersonDirectory.uuid(value);
    }

    private static void query(HttpServletRequest request, boolean allowed) {
        if (!allowed && !request.getParameterMap().isEmpty())
            throw FinanceException.invalid("此结转操作不接受查询参数");
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
                    query(request, operation.equals("preview") || operation.equals("list"));
                    return service.read(
                            actor.getName(), id(book), id(year), operation, id(target), request);
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
            int success) {
        return FinanceHttp.respond(
                success,
                () -> {
                    query(request, false);
                    return service.write(
                            actor.getName(),
                            id(book),
                            id(year),
                            operation,
                            id(target),
                            body,
                            request);
                });
    }

    @GetMapping("/preview")
    public Object preview(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            HttpServletRequest request) {
        return read(actor, book, year, "preview", null, request);
    }

    @GetMapping("/runs")
    public Object list(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            HttpServletRequest request) {
        return read(actor, book, year, "list", null, request);
    }

    @GetMapping("/runs/{run}")
    public Object detail(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable String run,
            HttpServletRequest request) {
        return read(actor, book, year, "detail", run, request);
    }

    @GetMapping("/journals/{journal}/producer")
    public Object producer(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable String journal,
            HttpServletRequest request) {
        return read(actor, book, year, "producer", journal, request);
    }

    @PostMapping("/runs")
    public Object create(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @RequestBody @BoundedBody(maxBytes = 16384, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(actor, book, year, "create", null, body, request, 201);
    }

    @PostMapping("/runs/{run}/submit")
    public Object submit(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable String run,
            @RequestBody @BoundedBody(maxBytes = 16384, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(actor, book, year, "submit", run, body, request, 200);
    }

    @PostMapping("/runs/{run}/approve")
    public Object approve(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable String run,
            @RequestBody @BoundedBody(maxBytes = 16384, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(actor, book, year, "approve", run, body, request, 200);
    }

    @PostMapping("/runs/{run}/post")
    public Object post(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable String run,
            @RequestBody @BoundedBody(maxBytes = 16384, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(actor, book, year, "post", run, body, request, 200);
    }

    @PostMapping("/runs/{run}/reject")
    public Object reject(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable String run,
            @RequestBody @BoundedBody(maxBytes = 16384, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(actor, book, year, "reject", run, body, request, 200);
    }

    @PostMapping("/runs/{run}/withdraw")
    public Object withdraw(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable String run,
            @RequestBody @BoundedBody(maxBytes = 16384, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(actor, book, year, "withdraw", run, body, request, 200);
    }

    @PostMapping("/runs/{run}/cancel")
    public Object cancel(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable String run,
            @RequestBody @BoundedBody(maxBytes = 16384, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(actor, book, year, "cancel", run, body, request, 200);
    }

    @PostMapping("/runs/{run}/admin-terminate")
    public Object terminate(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable String run,
            @RequestBody @BoundedBody(maxBytes = 16384, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(actor, book, year, "admin-terminate", run, body, request, 200);
    }
}
