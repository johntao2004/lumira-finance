package com.lumira.finance;

import com.lumira.platform.common.BoundedBody;
import com.lumira.platform.modules.ModuleEndpoint;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/finance/v1/books/{book}/years/{year}")
@ModuleEndpoint("finance")
public class FinanceIntegrationController {
    private final FinanceSources sources;
    private final FinanceOutbox outbox;

    public FinanceIntegrationController(FinanceSources sources, FinanceOutbox outbox) {
        this.sources = sources;
        this.outbox = outbox;
    }

    @GetMapping("/source-events")
    public Object sources(
            Authentication actor,
            @PathVariable UUID book,
            @PathVariable UUID year,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor) {
        return FinanceHttp.respond(
                200, () -> sources.list(actor.getName(), book, year, limit, cursor));
    }

    @GetMapping("/source-events/{id}")
    public Object source(
            Authentication actor,
            @PathVariable UUID book,
            @PathVariable UUID year,
            @PathVariable UUID id) {
        return FinanceHttp.respond(200, () -> sources.detail(actor.getName(), book, year, id));
    }

    @PostMapping("/source-events")
    public Object ingest(
            Authentication actor,
            @PathVariable UUID book,
            @PathVariable UUID year,
            @RequestBody @BoundedBody(maxBytes = 200000, errorCode = "INVALID_INPUT")
                    Map<String, Object> input,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return FinanceHttp.respond(
                200,
                () ->
                        sources.ingest(
                                actor.getName(),
                                book,
                                year,
                                FinanceController.body(input, key, null)));
    }

    @GetMapping("/outbox")
    public Object outbox(
            Authentication actor,
            @PathVariable UUID book,
            @PathVariable UUID year,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor) {
        return FinanceHttp.respond(
                200, () -> outbox.list(actor.getName(), book, year, limit, cursor));
    }

    @GetMapping("/outbox/{id}")
    public Object event(
            Authentication actor,
            @PathVariable UUID book,
            @PathVariable UUID year,
            @PathVariable UUID id) {
        return FinanceHttp.respond(200, () -> outbox.detail(actor.getName(), book, year, id));
    }

    @PostMapping("/outbox/{id}/retry")
    public Object retry(
            Authentication actor,
            @PathVariable UUID book,
            @PathVariable UUID year,
            @PathVariable UUID id,
            @RequestBody @BoundedBody(maxBytes = 20000, errorCode = "INVALID_INPUT")
                    Map<String, Object> input,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return FinanceHttp.respond(
                200,
                () ->
                        outbox.retry(
                                actor.getName(),
                                book,
                                year,
                                id,
                                FinanceController.body(input, key, null)));
    }

    @PostMapping("/outbox/rebuild-projection")
    public Object rebuild(
            Authentication actor,
            @PathVariable UUID book,
            @PathVariable UUID year,
            @RequestBody @BoundedBody(maxBytes = 20000, errorCode = "INVALID_INPUT")
                    Map<String, Object> input,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return FinanceHttp.respond(
                200,
                () ->
                        outbox.rebuild(
                                actor.getName(),
                                book,
                                year,
                                FinanceController.body(input, key, null)));
    }

    @GetMapping("/journal-projection")
    public Object projection(
            Authentication actor,
            @PathVariable UUID book,
            @PathVariable UUID year,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor) {
        return FinanceHttp.respond(
                200, () -> outbox.projection(actor.getName(), book, year, limit, cursor));
    }
}
