package com.lumira.finance;

import com.lumira.platform.common.BoundedBody;
import com.lumira.platform.modules.ModuleEndpoint;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Organization-reviewed taxpayer registry; never a tax assessment or external submission. */
@RestController
@RequestMapping("/api/finance/v1/books/{book}/tax-entities")
@ModuleEndpoint("finance")
public class FinanceTaxEntityController {
    private static final Map<String, Set<String>> READ_QUERY =
            Map.of(
                    "catalog",
                    Set.of(),
                    "list",
                    Set.of("limit", "cursor", "status"),
                    "detail",
                    Set.of(),
                    "revision-detail",
                    Set.of());
    private final FinanceTaxEntities service;

    public FinanceTaxEntityController(FinanceTaxEntities service) {
        this.service = service;
    }

    private static UUID id(String value) {
        return value == null ? null : FinancePersonDirectory.uuid(value);
    }

    private Object read(
            Authentication actor,
            String book,
            String operation,
            String entity,
            String revision,
            HttpServletRequest request) {
        return FinanceHttp.respond(
                200,
                () -> {
                    for (var e : request.getParameterMap().entrySet())
                        if (!READ_QUERY.get(operation).contains(e.getKey())
                                || e.getValue() == null
                                || e.getValue().length != 1)
                            throw FinanceException.invalid("纳税主体查询字段无效或重复");
                    return service.read(
                            actor.getName(),
                            id(book),
                            operation,
                            id(entity),
                            id(revision),
                            request);
                });
    }

    private Object write(
            Authentication actor,
            String book,
            String operation,
            String entity,
            String revision,
            Map<String, Object> body,
            HttpServletRequest request,
            int status) {
        return FinanceHttp.respond(
                status,
                () -> {
                    if (!request.getParameterMap().isEmpty())
                        throw FinanceException.invalid("纳税主体写操作不接受查询参数");
                    return service.write(
                            actor.getName(),
                            id(book),
                            operation,
                            id(entity),
                            id(revision),
                            body,
                            request);
                });
    }

    @GetMapping("/catalog")
    public Object catalog(
            Authentication actor, @PathVariable("book") String book, HttpServletRequest request) {
        return read(actor, book, "catalog", null, null, request);
    }

    @GetMapping("")
    public Object list(
            Authentication actor, @PathVariable("book") String book, HttpServletRequest request) {
        return read(actor, book, "list", null, null, request);
    }

    @GetMapping("/{id}")
    public Object detail(
            Authentication actor,
            @PathVariable("book") String book,
            @PathVariable("id") String entity,
            HttpServletRequest request) {
        return read(actor, book, "detail", entity, null, request);
    }

    @GetMapping("/{id}/revisions/{revisionId}")
    public Object revisionDetail(
            Authentication actor,
            @PathVariable("book") String book,
            @PathVariable("id") String entity,
            @PathVariable("revisionId") String revision,
            HttpServletRequest request) {
        return read(actor, book, "revision-detail", entity, revision, request);
    }

    @PostMapping("")
    public Object create(
            Authentication actor,
            @PathVariable("book") String book,
            @RequestBody @BoundedBody(maxBytes = 16384, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(actor, book, "create", null, null, body, request, 201);
    }

    @PostMapping("/{id}/revision-proposals")
    public Object proposeRevision(
            Authentication actor,
            @PathVariable("book") String book,
            @PathVariable("id") String entity,
            @RequestBody @BoundedBody(maxBytes = 16384, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(actor, book, "propose-revision", entity, null, body, request, 201);
    }

    @PostMapping("/{id}/revisions/{revisionId}/approve")
    public Object approve(
            Authentication actor,
            @PathVariable("book") String book,
            @PathVariable("id") String entity,
            @PathVariable("revisionId") String revision,
            @RequestBody @BoundedBody(maxBytes = 8192, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(actor, book, "approve", entity, revision, body, request, 200);
    }

    @PostMapping("/{id}/revisions/{revisionId}/reject")
    public Object reject(
            Authentication actor,
            @PathVariable("book") String book,
            @PathVariable("id") String entity,
            @PathVariable("revisionId") String revision,
            @RequestBody @BoundedBody(maxBytes = 8192, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(actor, book, "reject", entity, revision, body, request, 200);
    }

    @PostMapping("/{id}/revisions/{revisionId}/cancel")
    public Object cancel(
            Authentication actor,
            @PathVariable("book") String book,
            @PathVariable("id") String entity,
            @PathVariable("revisionId") String revision,
            @RequestBody @BoundedBody(maxBytes = 8192, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(actor, book, "cancel", entity, revision, body, request, 200);
    }
}
