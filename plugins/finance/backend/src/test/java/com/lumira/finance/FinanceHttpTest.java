package com.lumira.finance;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.lumira.platform.common.ClientAddressResolver;
import com.lumira.platform.common.RateLimitExceededException;
import com.lumira.platform.common.UploadRateLimitPort;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

class FinanceHttpTest {
    static Authentication actor() {
        Authentication actor = mock(Authentication.class);
        when(actor.getName()).thenReturn("maker");
        return actor;
    }

    static Map<?, ?> error(Object response, int expected, String code) {
        assertInstanceOf(ResponseEntity.class, response);
        var entity = (ResponseEntity<?>) response;
        assertEquals(expected, entity.getStatusCode().value());
        var body = (Map<?, ?>) entity.getBody();
        assertEquals(code, body.get("code"));
        assertEquals(code, body.get("detail"));
        assertEquals(false, body.get("retryable"));
        assertDoesNotThrow(() -> UUID.fromString(body.get("traceId").toString()));
        assertNotNull(body.get("message"));
        return body;
    }

    @Test
    void controllerSelfApprovalHasStructuredCodeWithoutAdvice() {
        FinanceService service = mock(FinanceService.class);
        when(service.write(anyString(), any(), any(), anyString(), any(), anyMap()))
                .thenThrow(new FinanceException("SELF_APPROVAL_FORBIDDEN", "制单人不得自审"));
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI())
                .thenReturn("/api/finance/v1/books/a/years/b/journals/c/approve");
        var response =
                new FinanceController(service)
                        .action(
                                actor(),
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                request,
                                Map.of("idempotencyKey", "boundary-test", "expectedVersion", 2),
                                null,
                                null);
        assertEquals("制单人不得自审", error(response, 403, "SELF_APPROVAL_FORBIDDEN").get("message"));
    }

    @Test
    void sqlFailureIsSanitizedAtControllerBoundary() {
        FinanceService service = mock(FinanceService.class);
        when(service.read(anyString(), any(), any(), anyString(), any(), any()))
                .thenThrow(
                        new DataIntegrityViolationException(
                                "SELECT secret_password FROM private_table; credential=hidden"));
        var body = error(new FinanceController(service).books(actor()), 409, "VERSION_CONFLICT");
        assertFalse(body.toString().contains("private_table"));
        assertFalse(body.toString().contains("hidden"));
    }

    @Test
    void headerValidationAlsoRunsInsideBoundary() {
        FinanceService service = mock(FinanceService.class);
        error(
                new FinanceController(service)
                        .book(actor(), Map.of("idempotencyKey", "different-key"), "header-key"),
                400,
                "INVALID_INPUT");
        verifyNoInteractions(service);
    }

    @Test
    void genericIllegalArgumentIsSanitizedAndPlatformStatusIsPreserved() {
        FinanceService service = mock(FinanceService.class);
        when(service.read(anyString(), any(), any(), anyString(), any(), any()))
                .thenThrow(new IllegalArgumentException("secret format"));
        error(new FinanceController(service).books(actor()), 400, "INVALID_INPUT");
        var original = new ResponseStatusException(HttpStatus.UNAUTHORIZED, "platform-owned");
        reset(service);
        when(service.read(anyString(), any(), any(), anyString(), any(), any()))
                .thenThrow(original);
        assertSame(
                original,
                assertThrows(
                        ResponseStatusException.class,
                        () -> new FinanceController(service).books(actor())));
    }

    @Test
    void downloadFailureReturnsJsonAndUploadIoIsSanitized() throws IOException {
        FinanceOriginals originals = mock(FinanceOriginals.class);
        UploadRateLimitPort limiter = mock(UploadRateLimitPort.class);
        ClientAddressResolver addresses = mock(ClientAddressResolver.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(addresses.resolve(request)).thenReturn("127.0.0.1");
        when(limiter.allow("maker", "127.0.0.1")).thenReturn(true);
        when(request.getInputStream()).thenThrow(new IOException("private disk location"));
        var controller = new FinanceOriginalController(originals, limiter, addresses);
        var body =
                error(
                        controller.upload(
                                actor(),
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                "file.pdf",
                                "upload-key",
                                request),
                        400,
                        "INVALID_UPLOAD");
        assertFalse(body.toString().contains("private disk"));
        when(originals.download(anyString(), any(), any(), any()))
                .thenThrow(new FinanceException("SCOPE_DENIED", "范围不可访问"));
        error(
                controller.content(
                        actor(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()),
                403,
                "SCOPE_DENIED");
        when(limiter.allow("maker", "127.0.0.1")).thenReturn(false);
        assertThrows(
                RateLimitExceededException.class,
                () ->
                        controller.upload(
                                actor(),
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                "file.pdf",
                                "upload-key",
                                request));
    }

    @Test
    void sourceAndOutboxConflictStatusesRemain409() {
        for (String code :
                java.util.List.of(
                        "SOURCE_PAYLOAD_CONFLICT",
                        "EFFECT_PAYLOAD_CONFLICT",
                        "SOURCE_SCOPE_CONFLICT",
                        "SOURCE_INTEGRITY_FAILED",
                        "OUTBOX_INTEGRITY_FAILED",
                        "OUTBOX_LEASE_ACTIVE",
                        "OUTBOX_RETRY_EXHAUSTED",
                        "ASYNC_REBUILD_REQUIRED"))
            error(
                    FinanceHttp.respond(
                            200,
                            () -> {
                                throw new FinanceException(code, "controlled conflict");
                            }),
                    409,
                    code);
    }
}
