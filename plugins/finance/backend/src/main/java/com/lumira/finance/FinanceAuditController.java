package com.lumira.finance;

import com.lumira.platform.modules.ModuleEndpoint;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/finance/v1/books/{book}/years/{year}/audit-events")
@ModuleEndpoint("finance")
public class FinanceAuditController {
    private final FinanceAudit audit;

    public FinanceAuditController(FinanceAudit audit) {
        this.audit = audit;
    }

    @GetMapping
    public Object list(
            Authentication actor,
            @PathVariable UUID book,
            @PathVariable UUID year,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor) {
        return FinanceHttp.respond(
                200, () -> audit.list(actor.getName(), book, year, limit, cursor));
    }
}
