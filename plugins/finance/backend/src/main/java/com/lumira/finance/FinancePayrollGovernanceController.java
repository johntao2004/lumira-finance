package com.lumira.finance;

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
public final class FinancePayrollGovernanceController {
    private final FinancePayroll service;

    public FinancePayrollGovernanceController(FinancePayroll service) {
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

    @GetMapping
    public Object listSetup(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String entity,
            @PathVariable String year,
            HttpServletRequest request,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, private");
        return FinanceHttp.respond(
                200,
                () -> {
                    query(request, Set.of());
                    return service.listSetup(actor.getName(), id(book), id(entity), year(year));
                });
    }

    @PostMapping("/employees")
    public Object registerEmployee(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String entity,
            @PathVariable String year,
            @RequestBody @BoundedBody(maxBytes = 16384, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, private");
        return FinanceHttp.respond(
                201,
                () -> {
                    query(request, Set.of());
                    return service.registerEmployee(
                            actor.getName(), id(book), id(entity), year(year), body, request);
                });
    }

    @PostMapping("/rule-packs")
    public Object registerRulePack(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String entity,
            @PathVariable String year,
            @RequestBody @BoundedBody(maxBytes = 32768, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, private");
        return FinanceHttp.respond(
                201,
                () -> {
                    query(request, Set.of());
                    return service.registerRulePack(
                            actor.getName(), id(book), id(entity), year(year), body, request);
                });
    }

    @PostMapping("/fact-evidences")
    public Object registerFactEvidence(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String entity,
            @PathVariable String year,
            @RequestBody @BoundedBody(maxBytes = 16384, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, private");
        return FinanceHttp.respond(
                201,
                () -> {
                    query(request, Set.of());
                    return service.registerFactEvidence(
                            actor.getName(), id(book), id(entity), year(year), body, request);
                });
    }

    @PostMapping("/fact-evidences/{evidenceId}/approve")
    public Object approveFactEvidence(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String entity,
            @PathVariable String year,
            @PathVariable String evidenceId,
            @RequestBody @BoundedBody(maxBytes = 8192, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, private");
        return FinanceHttp.respond(
                200,
                () -> {
                    query(request, Set.of());
                    return service.approveFactEvidence(
                            actor.getName(),
                            id(book),
                            id(entity),
                            year(year),
                            id(evidenceId),
                            body,
                            request);
                });
    }

    @GetMapping("/restatements")
    public Object listRestatements(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String entity,
            @PathVariable String year,
            HttpServletRequest request,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, private");
        return FinanceHttp.respond(
                200,
                () -> {
                    query(request, Set.of());
                    return service.listRestatementPlans(
                            actor.getName(), id(book), id(entity), year(year));
                });
    }

    @PostMapping("/restatements")
    public Object createRestatement(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String entity,
            @PathVariable String year,
            @RequestBody @BoundedBody(maxBytes = 65536, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, private");
        return FinanceHttp.respond(
                201,
                () -> {
                    query(request, Set.of());
                    return service.createRestatementPlan(
                            actor.getName(), id(book), id(entity), year(year), body, request);
                });
    }

    @PostMapping("/restatements/{planId}/approve")
    public Object approveRestatement(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String entity,
            @PathVariable String year,
            @PathVariable String planId,
            @RequestBody @BoundedBody(maxBytes = 8192, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, private");
        return FinanceHttp.respond(
                200,
                () -> {
                    query(request, Set.of());
                    return service.approveRestatementPlan(
                            actor.getName(),
                            id(book),
                            id(entity),
                            year(year),
                            id(planId),
                            body,
                            request);
                });
    }
}
