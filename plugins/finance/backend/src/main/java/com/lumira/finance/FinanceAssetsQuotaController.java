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
@RequestMapping("/api/finance/v1/books/{book}/years/{year}/assets/quotas")
@ModuleEndpoint("finance")
public final class FinanceAssetsQuotaController {
    private final FinanceAssetsProducer producer;

    public FinanceAssetsQuotaController(FinanceAssetsProducer producer) {
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
                    return producer.listQuotas(actor.getName(), id(book), id(year));
                });
    }

    @PostMapping
    public Object create(
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
                    return producer.createQuota(
                            actor.getName(), id(book), id(year), body, request);
                });
    }

    @PostMapping("/{quotaId}/approve")
    public Object approve(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable String quotaId,
            @RequestBody @BoundedBody(maxBytes = 8192, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return FinanceHttp.respond(
                200,
                () -> {
                    query(request, Set.of());
                    return producer.approveQuota(
                            actor.getName(), id(book), id(year), id(quotaId), body, request);
                });
    }
}
