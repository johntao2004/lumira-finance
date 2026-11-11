package com.lumira.finance;

import com.lumira.platform.common.BoundedBody;
import com.lumira.platform.modules.ModuleEndpoint;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Typed supplier-service expense accounting confirmation; never a payment instruction. */
@RestController
@RequestMapping("/api/finance/v1/books/{book}/years/{year}/payables-ap01")
@ModuleEndpoint("finance")
public class FinancePayablesAp01Controller {
    private static final Map<String, Set<String>> READ_QUERY =
            Map.of(
                    "catalog", Set.of(),
                    "parents", Set.of("limit", "cursor", "documentId"),
                    "basis-list", Set.of("limit", "cursor", "documentId", "status"),
                    "basis-detail", Set.of(),
                    "list", Set.of("limit", "cursor", "status"),
                    "detail", Set.of("historyLimit", "historyCursor"),
                    "producer", Set.of(),
                    "open-items", Set.of("limit", "cursor", "counterpartyId", "status"),
                    "open-item", Set.of(),
                    "reconciliation", Set.of());
    private final FinancePayablesAp01 service;

    public FinancePayablesAp01Controller(FinancePayablesAp01 service) {
        this.service = service;
    }

    private static UUID id(String value) {
        return value == null ? null : FinancePersonDirectory.uuid(value);
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
                    for (var entry : request.getParameterMap().entrySet()) {
                        if (!READ_QUERY.get(operation).contains(entry.getKey())
                                || entry.getValue() == null
                                || entry.getValue().length != 1)
                            throw FinanceException.invalid("应付查询字段无效或重复");
                    }
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
            int status) {
        return FinanceHttp.respond(
                status,
                () -> {
                    if (!request.getParameterMap().isEmpty())
                        throw FinanceException.invalid("应付会计确认写操作不接受查询参数");
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

    @GetMapping("/catalog")
    public Object catalog(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            HttpServletRequest request) {
        return read(actor, book, year, "catalog", null, request);
    }

    @GetMapping("/eligible-parents")
    public Object parents(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            HttpServletRequest request) {
        return read(actor, book, year, "parents", null, request);
    }

    @GetMapping("/accounting-bases")
    public Object basisList(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            HttpServletRequest request) {
        return read(actor, book, year, "basis-list", null, request);
    }

    @GetMapping("/accounting-bases/{basis}")
    public Object basisDetail(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable("basis") String target,
            HttpServletRequest request) {
        return read(actor, book, year, "basis-detail", target, request);
    }

    @GetMapping("/expense-payables")
    public Object list(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            HttpServletRequest request) {
        return read(actor, book, year, "list", null, request);
    }

    @GetMapping("/expense-payables/{payable}")
    public Object detail(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable("payable") String target,
            HttpServletRequest request) {
        return read(actor, book, year, "detail", target, request);
    }

    @GetMapping("/journals/{journal}/producer")
    public Object producer(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable("journal") String target,
            HttpServletRequest request) {
        return read(actor, book, year, "producer", target, request);
    }

    @GetMapping("/open-items")
    public Object openItems(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            HttpServletRequest request) {
        return read(actor, book, year, "open-items", null, request);
    }

    @GetMapping("/open-items/{item}")
    public Object openItem(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable("item") String target,
            HttpServletRequest request) {
        return read(actor, book, year, "open-item", target, request);
    }

    @GetMapping("/reconciliation")
    public Object reconciliation(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            HttpServletRequest request) {
        return read(actor, book, year, "reconciliation", null, request);
    }

    @PostMapping("/accounting-bases")
    public Object basisCreate(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @RequestBody @BoundedBody(maxBytes = 16384, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(actor, book, year, "basis-create", null, body, request, 201);
    }

    @PostMapping("/accounting-bases/{basis}/approve")
    public Object basisApprove(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable("basis") String target,
            @RequestBody @BoundedBody(maxBytes = 8192, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(actor, book, year, "basis-approve", target, body, request, 200);
    }

    @PostMapping("/accounting-bases/{basis}/reject")
    public Object basisReject(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable("basis") String target,
            @RequestBody @BoundedBody(maxBytes = 8192, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(actor, book, year, "basis-reject", target, body, request, 200);
    }

    @PostMapping("/accounting-bases/{basis}/cancel")
    public Object basisCancel(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable("basis") String target,
            @RequestBody @BoundedBody(maxBytes = 8192, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(actor, book, year, "basis-cancel", target, body, request, 200);
    }

    @PostMapping("/expense-payables")
    public Object create(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @RequestBody @BoundedBody(maxBytes = 32768, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(actor, book, year, "create", null, body, request, 201);
    }

    @PostMapping("/expense-payables/{payable}/full-reversal")
    public Object fullReversal(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable("payable") String target,
            @RequestBody @BoundedBody(maxBytes = 16384, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(actor, book, year, "full-reversal", target, body, request, 201);
    }

    @PostMapping("/expense-payables/{payable}/submit")
    public Object submit(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable("payable") String target,
            @RequestBody @BoundedBody(maxBytes = 8192, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(actor, book, year, "submit", target, body, request, 200);
    }

    @PostMapping("/expense-payables/{payable}/approve")
    public Object approve(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable("payable") String target,
            @RequestBody @BoundedBody(maxBytes = 8192, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(actor, book, year, "approve", target, body, request, 200);
    }

    @PostMapping("/expense-payables/{payable}/post")
    public Object post(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable("payable") String target,
            @RequestBody @BoundedBody(maxBytes = 8192, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(actor, book, year, "post", target, body, request, 200);
    }

    @PostMapping("/expense-payables/{payable}/withdraw")
    public Object withdraw(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable("payable") String target,
            @RequestBody @BoundedBody(maxBytes = 8192, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(actor, book, year, "withdraw", target, body, request, 200);
    }

    @PostMapping("/expense-payables/{payable}/reject")
    public Object reject(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable("payable") String target,
            @RequestBody @BoundedBody(maxBytes = 8192, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(actor, book, year, "reject", target, body, request, 200);
    }

    @PostMapping("/expense-payables/{payable}/cancel")
    public Object cancel(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable("payable") String target,
            @RequestBody @BoundedBody(maxBytes = 8192, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(actor, book, year, "cancel", target, body, request, 200);
    }

    @PostMapping("/expense-payables/{payable}/admin-terminate")
    public Object adminTerminate(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable("payable") String target,
            @RequestBody @BoundedBody(maxBytes = 8192, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(actor, book, year, "admin-terminate", target, body, request, 200);
    }
}
