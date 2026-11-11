package com.lumira.finance;

import com.lumira.platform.common.BoundedBody;
import com.lumira.platform.modules.ModuleEndpoint;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/finance/v1/books/{book}/archive-exports")
@ModuleEndpoint("finance")
public final class FinanceArchiveExportsController {
    private final FinanceArchiveExports service;

    public FinanceArchiveExportsController(FinanceArchiveExports service) {
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

    @GetMapping
    public Object list(
            Authentication actor, @PathVariable String book, HttpServletRequest request) {
        return FinanceHttp.respond(
                200,
                () -> {
                    query(request, Set.of());
                    return service.list(actor.getName(), id(book));
                });
    }

    @PostMapping("/preflights")
    public Object preflight(
            Authentication actor,
            @PathVariable String book,
            @RequestBody @BoundedBody(maxBytes = 16384, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return FinanceHttp.respond(
                201,
                () -> {
                    query(request, Set.of());
                    var in =
                            FinanceStrictJson.convert(
                                    body,
                                    FinanceArchiveExports.PreflightInput.class,
                                    "导出预检请求");
                    return service.preflight(actor.getName(), id(book), in, request);
                });
    }

    @PostMapping("/requests")
    public Object createRequest(
            Authentication actor,
            @PathVariable String book,
            @RequestBody @BoundedBody(maxBytes = 8192, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return FinanceHttp.respond(
                201,
                () -> {
                    query(request, Set.of());
                    var in =
                            FinanceStrictJson.convert(
                                    body,
                                    FinanceArchiveExports.CreateRequestInput.class,
                                    "导出申请请求");
                    return service.createRequest(actor.getName(), id(book), in, request);
                });
    }

    @GetMapping("/{requestId}")
    public Object detail(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String requestId,
            HttpServletRequest request) {
        return FinanceHttp.respond(
                200,
                () -> {
                    query(request, Set.of());
                    return service.detail(actor.getName(), id(book), id(requestId));
                });
    }

    @PostMapping("/{requestId}/approve")
    public Object approve(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String requestId,
            @RequestBody @BoundedBody(maxBytes = 8192, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return FinanceHttp.respond(
                200,
                () -> {
                    query(request, Set.of());
                    var in =
                            FinanceStrictJson.convert(
                                    body,
                                    FinanceArchiveExports.ApproveInput.class,
                                    "导出批准请求");
                    return service.approve(
                            actor.getName(), id(book), id(requestId), in, request);
                });
    }

    @PostMapping("/{requestId}/generate")
    public Object generate(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String requestId,
            @RequestBody @BoundedBody(maxBytes = 8192, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return FinanceHttp.respond(
                200,
                () -> {
                    query(request, Set.of());
                    var in =
                            FinanceStrictJson.convert(
                                    body,
                                    FinanceArchiveExports.GenerateInput.class,
                                    "导出生成请求");
                    return service.generate(
                            actor.getName(), id(book), id(requestId), in, request);
                });
    }

    @GetMapping("/{requestId}/download")
    public Object download(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String requestId,
            HttpServletRequest request) {
        if (request.getHeader("Range") != null) {
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header(HttpHeaders.CACHE_CONTROL, "no-store, private")
                    .body(
                            Map.of(
                                    "code",
                                    "RANGE_NOT_SUPPORTED",
                                    "message",
                                    "离线电子档案包要求整包下载并校验SHA-256，不支持Range分段请求"));
        }
        return FinanceHttp.respond(
                200,
                () -> {
                    query(request, Set.of());
                    var dl = service.download(actor.getName(), id(book), id(requestId));
                    return ResponseEntity.ok()
                            .contentType(MediaType.parseMediaType("application/x-tar"))
                            .contentLength(dl.bytes().length)
                            .header(
                                    HttpHeaders.CONTENT_DISPOSITION,
                                    "attachment; filename=\"finance-archive-"
                                            + dl.requestId()
                                            + ".tar\"")
                            .header(HttpHeaders.CACHE_CONTROL, "no-store, private")
                            .header("X-Content-SHA256", dl.artifactSha256())
                            .header("X-Manifest-SHA256", dl.manifestSha256())
                            .body(dl.bytes());
                });
    }

    @PostMapping("/{requestId}/receipt")
    public Object receipt(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String requestId,
            @RequestBody @BoundedBody(maxBytes = 8192, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return FinanceHttp.respond(
                200,
                () -> {
                    query(request, Set.of());
                    var in =
                            FinanceStrictJson.convert(
                                    body,
                                    FinanceArchiveExports.ReceiptInput.class,
                                    "导出接收回执请求");
                    return service.receipt(
                            actor.getName(), id(book), id(requestId), in, request);
                });
    }
}
