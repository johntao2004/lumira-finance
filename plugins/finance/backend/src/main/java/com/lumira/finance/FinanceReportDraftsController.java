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
@RequestMapping("/api/finance/v1/books/{book}/years/{year}/report-drafts")
@ModuleEndpoint("finance")
public final class FinanceReportDraftsController {
    private final FinanceReportDrafts service;

    public FinanceReportDraftsController(FinanceReportDrafts service) {
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

    @GetMapping("/mappings")
    public Object listMappings(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            HttpServletRequest request) {
        return FinanceHttp.respond(
                200,
                () -> {
                    query(request, Set.of());
                    return service.listMappings(actor.getName(), id(book), id(year));
                });
    }

    @PostMapping("/mappings")
    public Object createMapping(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
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
                                    FinanceReportDrafts.CreateMappingInput.class,
                                    "报表科目映射创建请求");
                    return service.createMapping(actor.getName(), id(book), id(year), in, request);
                });
    }

    @PostMapping("/mappings/{mappingId}/approve")
    public Object approveMapping(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable String mappingId,
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
                                    FinanceReportDrafts.ApproveMappingInput.class,
                                    "报表科目映射批准请求");
                    return service.approveMapping(
                            actor.getName(), id(book), id(year), id(mappingId), in, request);
                });
    }

    @PostMapping("/evaluate")
    public Object evaluate(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
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
                                    FinanceReportDrafts.EvaluateDraftInput.class,
                                    "报表底稿试算请求");
                    return service.evaluate(actor.getName(), id(book), id(year), in);
                });
    }
}
