package com.lumira.finance;

import com.lumira.platform.common.BoundedBody;
import com.lumira.platform.modules.ModuleEndpoint;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/finance/v1/books/{book}/years/{year}/payable-settlements")
@ModuleEndpoint("finance")
public final class FinancePayableSettlementsController {
    private final FinancePayableSettlements service;

    public FinancePayableSettlementsController(FinancePayableSettlements service) {
        this.service = service;
    }

    private UUID id(String s) {
        return FinancePersonDirectory.uuid(s);
    }

    private void query(HttpServletRequest request, Set<String> allowed) {
        request.getParameterMap()
                .forEach(
                        (key, value) -> {
                            if (!allowed.contains(key) || value == null || value.length != 1)
                                throw FinanceException.invalid("查询字段无效或重复");
                        });
    }

    private LocalDate parseDate(String raw) {
        if (raw == null) return null;
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            throw FinanceException.invalid("日期格式无效");
        }
    }

    @GetMapping
    public Object overview(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @RequestParam(required = false) String asOf,
            HttpServletRequest request) {
        return FinanceHttp.respond(
                200,
                () -> {
                    query(request, Set.of("asOf"));
                    return service.overview(
                            actor.getName(), id(book), id(year), parseDate(asOf));
                });
    }

    @PostMapping("/bases")
    public Object createBasis(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @RequestBody @BoundedBody(maxBytes = 8192, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return FinanceHttp.respond(
                201,
                () -> {
                    query(request, Set.of());
                    var in =
                            FinanceStrictJson.convert(
                                    body,
                                    FinancePayableSettlements.CreateSettlementBasisInput.class,
                                    "结算依据配额申请");
                    return service.createBasis(
                            actor.getName(), id(book), id(year), in, request);
                });
    }

    @PostMapping("/bases/{basisId}/approve")
    public Object approveBasis(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable String basisId,
            @RequestBody @BoundedBody(maxBytes = 4096, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return FinanceHttp.respond(
                200,
                () -> {
                    query(request, Set.of());
                    var in =
                            FinanceStrictJson.convert(
                                    body,
                                    FinancePayableSettlements.ApproveSettlementBasisInput.class,
                                    "结算依据配额批准");
                    return service.approveBasis(
                            actor.getName(), id(book), id(year), id(basisId), in, request);
                });
    }

    @PostMapping("/orders")
    public Object createOrder(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @RequestBody @BoundedBody(maxBytes = 16384, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return FinanceHttp.respond(
                201,
                () -> {
                    query(request, Set.of());
                    var in =
                            FinanceStrictJson.convert(
                                    body,
                                    FinancePayableSettlements.CreateSettlementOrderInput.class,
                                    "应付结算单创建");
                    return service.createOrder(
                            actor.getName(), id(book), id(year), in, request);
                });
    }

    @PostMapping("/orders/{orderId}/submit")
    public Object submitOrder(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable String orderId,
            @RequestBody @BoundedBody(maxBytes = 4096, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return transition(actor, book, year, orderId, "submit", body, request);
    }

    @PostMapping("/orders/{orderId}/approve")
    public Object approveOrder(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable String orderId,
            @RequestBody @BoundedBody(maxBytes = 4096, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return transition(actor, book, year, orderId, "approve", body, request);
    }

    @PostMapping("/orders/{orderId}/post")
    public Object postOrder(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable String orderId,
            @RequestBody @BoundedBody(maxBytes = 4096, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return transition(actor, book, year, orderId, "post", body, request);
    }

    @PostMapping("/orders/{orderId}/cancel")
    public Object cancelOrder(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable String orderId,
            @RequestBody @BoundedBody(maxBytes = 4096, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return transition(actor, book, year, orderId, "cancel", body, request);
    }

    private Object transition(
            Authentication actor,
            String book,
            String year,
            String orderId,
            String action,
            Map<String, Object> body,
            HttpServletRequest request) {
        return FinanceHttp.respond(
                200,
                () -> {
                    query(request, Set.of());
                    var in =
                            FinanceStrictJson.convert(
                                    body,
                                    FinancePayableSettlements.TransitionSettlementOrderInput.class,
                                    "应付结算单流转");
                    return service.transitionOrder(
                            actor.getName(),
                            id(book),
                            id(year),
                            id(orderId),
                            action,
                            in,
                            request);
                });
    }
}
