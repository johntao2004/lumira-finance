package com.lumira.finance;

import com.lumira.platform.common.ClientAddressResolver;
import com.lumira.platform.common.RateLimitExceededException;
import com.lumira.platform.common.UploadRateLimitPort;
import com.lumira.platform.modules.ModuleEndpoint;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api/finance/v1/books/{book}/years/{year}/originals")
@ModuleEndpoint("finance")
public class FinanceOriginalController {
    private final FinanceOriginals originals;
    private final UploadRateLimitPort limiter;
    private final ClientAddressResolver addresses;

    public FinanceOriginalController(
            FinanceOriginals originals,
            UploadRateLimitPort limiter,
            ClientAddressResolver addresses) {
        this.originals = originals;
        this.limiter = limiter;
        this.addresses = addresses;
    }

    @PostMapping(consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public Object upload(
            Authentication actor,
            @PathVariable UUID book,
            @PathVariable UUID year,
            @RequestParam String filename,
            @RequestHeader("Idempotency-Key") String key,
            HttpServletRequest request)
            throws IOException {
        return FinanceHttp.respond(
                201,
                () -> {
                    if (!limiter.allow(actor.getName(), addresses.resolve(request)))
                        throw new RateLimitExceededException("uploadRateLimited");
                    return originals.upload(
                            actor.getName(),
                            book,
                            year,
                            filename,
                            key,
                            request.getInputStream(),
                            request.getContentLengthLong());
                });
    }

    @GetMapping
    public Object list(Authentication actor, @PathVariable UUID book, @PathVariable UUID year) {
        return FinanceHttp.respond(
                200,
                () -> {
                    return originals.list(actor.getName(), book, year);
                });
    }

    @GetMapping("/{id}/content")
    public ResponseEntity<?> content(
            Authentication actor,
            @PathVariable UUID book,
            @PathVariable UUID year,
            @PathVariable UUID id) {
        return FinanceHttp.respond(
                200,
                () -> {
                    var file = originals.download(actor.getName(), book, year, id);
                    return ResponseEntity.ok()
                            .contentType(MediaType.APPLICATION_OCTET_STREAM)
                            .contentLength(file.content().length)
                            .header(
                                    HttpHeaders.CONTENT_DISPOSITION,
                                    ContentDisposition.attachment()
                                            .filename(file.filename(), StandardCharsets.UTF_8)
                                            .build()
                                            .toString())
                            .header(HttpHeaders.CACHE_CONTROL, "no-store")
                            .header("X-Content-Type-Options", "nosniff")
                            .header("X-Content-SHA256", file.sha256())
                            .body(
                                    new InputStreamResource(
                                            new ByteArrayInputStream(file.content())));
                });
    }
}
