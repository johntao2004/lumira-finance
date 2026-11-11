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
@RequestMapping("/api/finance/v1/books/{book}/years/{year}/receivables/decisions")
@ModuleEndpoint("finance")
public final class FinanceReceivablesDecisionController {
    private final FinanceReceivablesProducer producer;

    public FinanceReceivablesDecisionController(FinanceReceivablesProducer producer) {
        this.producer = producer;
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
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            HttpServletRequest request) {
        return FinanceHttp.respond(
                200,
                () -> {
                    query(request, Set.of());
                    return producer.listDecisions(actor.getName(), id(book), id(year));
                });
    }

    @PostMapping
    public Object create(
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
                    return producer.createDecision(
                            actor.getName(), id(book), id(year), body, request);
                });
    }

    @PostMapping("/{decisionId}/approve")
    public Object approve(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable String decisionId,
            @RequestBody @BoundedBody(maxBytes = 8192, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return FinanceHttp.respond(
                200,
                () -> {
                    query(request, Set.of());
                    return producer.approveDecision(
                            actor.getName(), id(book), id(year), id(decisionId), body, request);
                });
    }
}
