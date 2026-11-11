package com.lumira.finance;

import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/** Explicit HTTP boundary: plugin child-container advice is not part of the host resolver. */
final class FinanceHttp {
    private FinanceHttp() {}

    @FunctionalInterface
    interface Work {
        Object run() throws IOException;
    }

    static ResponseEntity<?> respond(int successStatus, Work work) {
        try {
            Object value = work.run();
            return value instanceof ResponseEntity<?> response
                    ? response
                    : ResponseEntity.status(successStatus).body(value);
        } catch (FinanceException error) {
            return failure(
                    error.getStatusCode().value(), error.code, error.getReason(), error.issues);
        } catch (DataAccessException error) {
            return failure(409, "VERSION_CONFLICT", "数据冲突或约束不满足，请刷新后核对");
        } catch (IllegalArgumentException error) {
            return failure(400, "INVALID_INPUT", "字段类型或格式无效");
        } catch (IOException error) {
            return failure(400, "INVALID_UPLOAD", "原件读取失败，请重新上传");
        }
    }

    private static ResponseEntity<?> failure(int status, String code, String message) {
        return failure(status, code, message, java.util.List.of());
    }

    private static ResponseEntity<?> failure(
            int status, String code, String message, java.util.List<Map<String, Object>> issues) {
        var body =
                new java.util.LinkedHashMap<String, Object>(
                        Map.of(
                                "code",
                                code,
                                "detail",
                                code,
                                "message",
                                message == null ? "操作失败" : message,
                                "traceId",
                                UUID.randomUUID().toString(),
                                "retryable",
                                false));
        if (!issues.isEmpty()) body.put("issues", issues);
        return ResponseEntity.status(status).body(body);
    }
}
