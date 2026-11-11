package com.lumira.finance.payroll;

import com.lumira.platform.common.BoundedBody;
import com.lumira.platform.modules.ModuleEndpoint;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import static com.lumira.finance.payroll.PayrollService.*;

/** Deliberately not component-scanned: register as @RestController only after adapter acceptance. */
@RequestMapping("/api/finance/v1/books/{book}/payroll/{entity}/{year}")
@ModuleEndpoint("finance")
public final class PayrollController {
    public interface ScopeResolver {
        // Verifies current principal/book/withholding entity relationship and derives tenant.
        YearScope resolve(String principal, UUID book, UUID entity, int year);
    }
    public record CreateRequest(Command command, Draft draft) {}
    public record ActionRequest(Command command, Action action) {}
    public record FactRequest(Command command, FactKind kind, UUID reference) {}
    private final PayrollService service;
    private final ScopeResolver resolver;
    public PayrollController(PayrollService service, ScopeResolver resolver) { this.service = service; this.resolver = resolver; }
    private YearScope scope(Authentication actor, UUID book, UUID entity, int year, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, private");
        return resolver.resolve(actor.getName(), book, entity, year);
    }
    @GetMapping("/{id}")
    public PayrollView read(Authentication actor, @PathVariable UUID book, @PathVariable UUID entity,
            @PathVariable int year, @PathVariable UUID id, HttpServletResponse response) {
        Batch batch = service.read(actor.getName(), scope(actor, book, entity, year, response), id);
        if (!batch.draft().book().equals(book)) throw PayrollCalculation.invalid("BOOK_SCOPE");
        return PayrollView.from(batch);
    }
    @PostMapping
    public PayrollView create(Authentication actor, @PathVariable UUID book, @PathVariable UUID entity,
            @PathVariable int year, @RequestBody @BoundedBody(maxBytes=262144, errorCode="INVALID_INPUT") CreateRequest body,
            HttpServletResponse response) {
        if (!book.equals(body.draft().book())) throw PayrollCalculation.invalid("BOOK_SCOPE");
        return PayrollView.from(service.create(actor.getName(), scope(actor, book, entity, year, response), body.command(), body.draft()));
    }
    @PostMapping("/{id}/actions")
    public PayrollView act(Authentication actor, @PathVariable UUID book, @PathVariable UUID entity, @PathVariable int year,
            @PathVariable UUID id, @RequestBody @BoundedBody(maxBytes=4096, errorCode="INVALID_INPUT") ActionRequest body,
            HttpServletResponse response) {
        YearScope resolved = scope(actor, book, entity, year, response);
        if (!service.read(actor.getName(), resolved, id).draft().book().equals(book)) throw PayrollCalculation.invalid("BOOK_SCOPE");
        return PayrollView.from(service.act(actor.getName(), resolved, id, body.command(), body.action()));
    }
    @PostMapping("/{id}/facts")
    public PayrollView fact(Authentication actor, @PathVariable UUID book, @PathVariable UUID entity, @PathVariable int year,
            @PathVariable UUID id, @RequestBody @BoundedBody(maxBytes=4096, errorCode="INVALID_INPUT") FactRequest body,
            HttpServletResponse response) {
        YearScope resolved = scope(actor, book, entity, year, response);
        if (!service.read(actor.getName(), resolved, id).draft().book().equals(book)) throw PayrollCalculation.invalid("BOOK_SCOPE");
        return PayrollView.from(service.attachFact(actor.getName(), resolved, id, body.command(), body.kind(), body.reference()));
    }
}
