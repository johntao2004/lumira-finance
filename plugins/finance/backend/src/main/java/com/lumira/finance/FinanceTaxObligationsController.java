package com.lumira.finance;

import com.lumira.platform.common.BoundedBody;
import com.lumira.platform.modules.ModuleEndpoint;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/finance/v1/books/{book}/tax-obligations")
@ModuleEndpoint("finance")
public final class FinanceTaxObligationsController {
    private final FinanceTaxObligations service;

    public FinanceTaxObligationsController(FinanceTaxObligations service) {
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

    @GetMapping
    public Object list(
            Authentication actor, @PathVariable String book, HttpServletRequest request) {
        return FinanceHttp.respond(
                200,
                () -> {
                    query(request, Set.of());
                    return service.list(actor.getName(), id(book));
                });
    }

    @PostMapping("/book-bindings")
    public Object proposeBinding(
            Authentication actor,
            @PathVariable String book,
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
                                    FinanceTaxObligations.BookBindingInput.class,
                                    "账套税务映射登记请求");
                    return service.proposeBinding(actor.getName(), id(book), in, request);
                });
    }

    @PostMapping("/book-bindings/{taxEntityId}/approve")
    public Object approveBinding(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String taxEntityId,
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
                                    FinanceTaxObligations.ApproveBindingInput.class,
                                    "账套税务映射批准请求");
                    return service.approveBinding(
                            actor.getName(), id(book), id(taxEntityId), in, request);
                });
    }

    @PostMapping
    public Object build(
            Authentication actor,
            @PathVariable String book,
            @RequestBody @BoundedBody(maxBytes = 32768, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return FinanceHttp.respond(
                201,
                () -> {
                    query(request, Set.of());
                    var in =
                            FinanceStrictJson.convert(
                                    body,
                                    FinanceTaxObligations.BuildObligationInput.class,
                                    "申报义务构建请求");
                    return service.build(actor.getName(), id(book), in, request);
                });
    }

    @PostMapping("/{obligationId}/declarations/{declarationId}/review")
    public Object review(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String obligationId,
            @PathVariable String declarationId,
            @RequestBody @BoundedBody(maxBytes = 4096, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return transition(actor, book, obligationId, declarationId, "review", body, request);
    }

    @PostMapping("/{obligationId}/declarations/{declarationId}/approve")
    public Object approve(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String obligationId,
            @PathVariable String declarationId,
            @RequestBody @BoundedBody(maxBytes = 4096, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return transition(actor, book, obligationId, declarationId, "approve", body, request);
    }

    @PostMapping("/{obligationId}/declarations/{declarationId}/handoff")
    public Object handoff(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String obligationId,
            @PathVariable String declarationId,
            @RequestBody @BoundedBody(maxBytes = 4096, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return transition(actor, book, obligationId, declarationId, "handoff", body, request);
    }

    @PostMapping("/{obligationId}/declarations/{declarationId}/cancel")
    public Object cancel(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String obligationId,
            @PathVariable String declarationId,
            @RequestBody @BoundedBody(maxBytes = 4096, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return transition(actor, book, obligationId, declarationId, "cancel", body, request);
    }

    @PostMapping("/{obligationId}/declarations/{declarationId}/reconcile-receipt")
    public Object reconcileReceipt(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String obligationId,
            @PathVariable String declarationId,
            @RequestBody @BoundedBody(maxBytes = 8192, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return FinanceHttp.respond(
                200,
                () -> {
                    query(request, Set.of());
                    var in =
                            FinanceStrictJson.convert(
                                    body,
                                    FinanceTaxObligations.ReconcileReceiptInput.class,
                                    "税务回执核销请求");
                    return service.reconcileReceipt(
                            actor.getName(),
                            id(book),
                            id(obligationId),
                            id(declarationId),
                            in,
                            request);
                });
    }

    @GetMapping("/worksheets")
    public Object listWorksheets(
            Authentication actor,
            @PathVariable String book,
            @RequestParam String yearId,
            HttpServletRequest request) {
        return FinanceHttp.respond(
                200,
                () -> {
                    query(request, Set.of("yearId"));
                    return service.listWorksheets(actor.getName(), id(book), id(yearId));
                });
    }

    @PostMapping("/worksheets")
    public Object createWorksheet(
            Authentication actor,
            @PathVariable String book,
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
                                    FinanceTaxObligations.CreateWorksheetInput.class,
                                    "税务计税底稿构建请求");
                    return service.createWorksheet(actor.getName(), id(book), in, request);
                });
    }

    @PostMapping("/worksheets/{worksheetId}/approve")
    public Object approveWorksheet(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String worksheetId,
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
                                    FinanceTaxObligations.ApproveWorksheetInput.class,
                                    "税务计税底稿批准请求");
                    return service.approveWorksheet(
                            actor.getName(), id(book), id(worksheetId), in, request);
                });
    }

    private Object transition(
            Authentication actor,
            String book,
            String obligationId,
            String declarationId,
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
                                    FinanceTaxObligations.TransitionInput.class,
                                    "申报流转请求");
                    return service.transition(
                            actor.getName(),
                            id(book),
                            id(obligationId),
                            id(declarationId),
                            action,
                            in,
                            request);
                });
    }
}
