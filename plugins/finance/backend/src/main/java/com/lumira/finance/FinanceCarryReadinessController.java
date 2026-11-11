package com.lumira.finance;

import com.lumira.platform.modules.ModuleEndpoint;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Independent read boundary; an observation never authorizes a year-close or opening write. */
@RestController
@RequestMapping("/api/finance/v1/books/{book}/years/{sourceYear}/carry-readiness")
@ModuleEndpoint("finance")
public class FinanceCarryReadinessController {
    private final FinanceCarryReadiness service;

    public FinanceCarryReadinessController(FinanceCarryReadiness service) {
        this.service = service;
    }

    private Object read(
            Authentication actor,
            String book,
            String sourceYear,
            String operation,
            HttpServletRequest request) {
        return FinanceHttp.respond(
                200,
                () ->
                        service.read(
                                actor.getName(),
                                FinancePersonDirectory.uuid(book),
                                FinancePersonDirectory.uuid(sourceYear),
                                operation,
                                request));
    }

    @GetMapping
    public Object summary(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String sourceYear,
            HttpServletRequest request) {
        return read(actor, book, sourceYear, "summary", request);
    }

    @GetMapping("/accounts")
    public Object accounts(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String sourceYear,
            HttpServletRequest request) {
        return read(actor, book, sourceYear, "accounts", request);
    }
}
