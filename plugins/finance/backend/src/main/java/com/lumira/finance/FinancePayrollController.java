package com.lumira.finance;

import com.lumira.finance.payroll.PayrollController;
import com.lumira.platform.common.BoundedBody;
import com.lumira.platform.modules.ModuleEndpoint;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/finance/v1/books/{book}/payroll/{entity}/{year}")
@ModuleEndpoint("finance")
public final class FinancePayrollController {
    private static final Set<String> CREATE_KEYS = Set.of("command", "draft");
    private static final Set<String> ACTION_KEYS = Set.of("command", "action");
    private static final Set<String> FACT_KEYS = Set.of("command", "kind", "reference");
    private final FinancePayroll service;

    public FinancePayrollController(FinancePayroll service) {
        this.service = service;
    }

    private UUID id(String s) {
        return FinancePersonDirectory.uuid(s);
    }

    private int year(String s) {
        if (s == null || !s.matches("\\d{4}")) throw FinanceException.invalid("薪酬纳税年度格式无效");
        return Integer.parseInt(s);
    }

    private void query(HttpServletRequest request, Set<String> allowed) {
        request.getParameterMap()
                .forEach(
                        (key, value) -> {
                            if (!allowed.contains(key) || value == null || value.length != 1)
                                throw FinanceException.invalid("查询字段无效或重复");
                        });
    }

    @GetMapping("/{batchId}")
    public Object read(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String entity,
            @PathVariable String year,
            @PathVariable String batchId,
            HttpServletRequest request,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, private");
        return FinanceHttp.respond(
                200,
                () -> {
                    query(request, Set.of());
                    return service.read(
                            actor.getName(), id(book), id(entity), year(year), id(batchId));
                });
    }

    @PostMapping
    public Object create(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String entity,
            @PathVariable String year,
            @RequestBody @BoundedBody(maxBytes = 262144, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, private");
        return FinanceHttp.respond(
                201,
                () -> {
                    query(request, Set.of());
                    if (body == null || !CREATE_KEYS.containsAll(body.keySet()))
                        throw FinanceException.invalid("薪酬批次创建字段无效");
                    var req =
                            FinanceStrictJson.convert(
                                    body, PayrollController.CreateRequest.class, "薪酬批次创建请求");
                    return service.create(
                            actor.getName(), id(book), id(entity), year(year), req, request);
                });
    }

    @PostMapping("/{batchId}/actions")
    public Object act(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String entity,
            @PathVariable String year,
            @PathVariable String batchId,
            @RequestBody @BoundedBody(maxBytes = 4096, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, private");
        return FinanceHttp.respond(
                200,
                () -> {
                    query(request, Set.of());
                    if (body == null || !ACTION_KEYS.containsAll(body.keySet()))
                        throw FinanceException.invalid("薪酬动作请求字段无效");
                    var req =
                            FinanceStrictJson.convert(
                                    body, PayrollController.ActionRequest.class, "薪酬动作请求");
                    return service.act(
                            actor.getName(),
                            id(book),
                            id(entity),
                            year(year),
                            id(batchId),
                            req,
                            request);
                });
    }

    @PostMapping("/{batchId}/facts")
    public Object fact(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String entity,
            @PathVariable String year,
            @PathVariable String batchId,
            @RequestBody @BoundedBody(maxBytes = 4096, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, private");
        return FinanceHttp.respond(
                200,
                () -> {
                    query(request, Set.of());
                    if (body == null || !FACT_KEYS.containsAll(body.keySet()))
                        throw FinanceException.invalid("薪酬事实核验请求字段无效");
                    var req =
                            FinanceStrictJson.convert(
                                    body, PayrollController.FactRequest.class, "薪酬事实核验请求");
                    return service.fact(
                            actor.getName(),
                            id(book),
                            id(entity),
                            year(year),
                            id(batchId),
                            req,
                            request);
                });
    }
}
