package com.lumira.finance;

import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.sql.SQLException;
import java.util.*;

@RestControllerAdvice
public class FinanceGlobalExceptionHandler {

    @ExceptionHandler(FinanceException.class)
    public ResponseEntity<Map<String, Object>> handleFinanceException(FinanceException ex) {
        return buildError(ex.getStatusCode().value(), ex.code, ex.getReason(), ex.issues);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatusException(ResponseStatusException ex) {
        if (ex instanceof FinanceException fe) {
            return buildError(fe.getStatusCode().value(), fe.code, fe.getReason(), fe.issues);
        }
        int status = ex.getStatusCode().value();
        String code = status == 403 ? "SCOPE_DENIED" : (status == 404 ? "NOT_FOUND" : (status == 409 ? "CONFLICT" : "INVALID_INPUT"));
        return buildError(status, code, ex.getReason() != null ? ex.getReason() : code, List.of());
    }

    @ExceptionHandler(org.springframework.web.bind.MissingRequestHeaderException.class)
    public ResponseEntity<Map<String, Object>> handleMissingHeader(org.springframework.web.bind.MissingRequestHeaderException ex) {
        return buildError(400, "INVALID_INPUT", ex.getMessage(), List.of());
    }

    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(org.springframework.web.bind.MissingServletRequestParameterException ex) {
        return buildError(400, "INVALID_INPUT", ex.getMessage(), List.of());
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleNotReadable(org.springframework.http.converter.HttpMessageNotReadableException ex) {
        return buildError(400, "INVALID_INPUT", "Malformed or unreadable JSON request body", List.of());
    }

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex) {
        return buildError(400, "INVALID_INPUT", "Invalid request parameter: " + ex.getName(), List.of());
    }

    @ExceptionHandler(NullPointerException.class)
    public ResponseEntity<Map<String, Object>> handleNullPointer(NullPointerException ex) {
        return buildError(400, "INVALID_INPUT", ex.getMessage() != null ? ex.getMessage() : "Required parameter or body field missing", List.of());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgumentException(IllegalArgumentException ex) {
        return handleException(ex);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalStateException(IllegalStateException ex) {
        return handleException(ex);
    }

    @ExceptionHandler(SQLException.class)
    public ResponseEntity<Map<String, Object>> handleSQLException(SQLException ex) {
        return handleException(ex);
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, Object>> handleDataAccessException(DataAccessException ex) {
        return handleException(ex);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception ex) {
        return handleException(ex);
    }

    public static ResponseEntity<Map<String, Object>> handleException(Throwable t) {
        if (t instanceof FinanceException fe) {
            return buildError(fe.getStatusCode().value(), fe.code, fe.getReason(), fe.issues);
        }
        if (t instanceof ResponseStatusException rse) {
            int status = rse.getStatusCode().value();
            String code = status == 403 ? "SCOPE_DENIED" : (status == 404 ? "NOT_FOUND" : (status == 409 ? "CONFLICT" : "INVALID_INPUT"));
            return buildError(status, code, rse.getReason() != null ? rse.getReason() : code, List.of());
        }
        if (t instanceof NullPointerException) {
            return buildError(400, "INVALID_INPUT", t.getMessage() != null ? t.getMessage() : "Required parameter or body field missing", List.of());
        }

        SQLException sqlEx = findSqlException(t);
        if (sqlEx != null) {
            return handleSqlException(sqlEx);
        }

        String msg = t.getMessage() != null ? t.getMessage() : "";
        String upper = msg.toUpperCase();

        // 1. SoD / Authorization / Scope checks -> 403
        if (upper.contains("SOD")
                || upper.contains("SEGREGATION OF DUTIES")
                || upper.contains("SELF_APPROVAL")
                || upper.contains("SELF-APPROVAL")
                || upper.contains("INDEPENDENT_NATURAL_PERSON")
                || upper.contains("APPROVER CANNOT BE THE MAKER")
                || upper.contains("APPROVER CANNOT BE THE CREATOR")
                || upper.contains("APPROVER CANNOT BE THE PREPARER")
                || upper.contains("CHECK_SOD_")
                || msg.contains("职责分离")
                || msg.contains("制单人不得审批")
                || msg.contains("复核人不能为同一人")
                || msg.contains("同一自然人")
                || msg.contains("不可为同一人")
                || msg.contains("不能为同一人")
                || msg.contains("自审")
                || msg.contains("独立自然人")) {
            return buildError(403, "SOD_VIOLATION", msg.isEmpty() ? "Segregation of duties violation" : msg, List.of());
        }

        if (upper.contains("SCOPE_DENIED") || upper.contains("SCOPE DENIED")) {
            return buildError(403, "SCOPE_DENIED", msg.isEmpty() ? "账套范围不可访问" : msg, List.of());
        }

        if (upper.contains("AI_MAJOR_ACTION_BLOCKED")) {
            return buildError(403, "AI_MAJOR_ACTION_BLOCKED", msg, List.of());
        }

        // Anti-CRM guard -> 422
        if (upper.contains("CRM_PAYLOAD_REJECTED")) {
            return buildError(422, "CRM_PAYLOAD_REJECTED", msg, List.of());
        }

        // 2. Not Found -> 404
        if (upper.contains("POLICY_PACK_NOT_FOUND")) {
            return buildError(404, "POLICY_PACK_NOT_FOUND", msg, List.of());
        }
        if (upper.contains("ATTESTATION_NOT_FOUND")) {
            return buildError(404, "ATTESTATION_NOT_FOUND", msg, List.of());
        }
        if (upper.contains("ARCHIVE_RECORD_NOT_FOUND")) {
            return buildError(404, "ARCHIVE_RECORD_NOT_FOUND", msg, List.of());
        }
        if (upper.contains("BORROW_RECORD_NOT_FOUND")) {
            return buildError(404, "BORROW_RECORD_NOT_FOUND", msg, List.of());
        }
        if (upper.contains("NOT_FOUND") || upper.contains("NOT FOUND") || msg.contains("不存在")) {
            return buildError(404, extractCode(msg, "NOT_FOUND"), msg, List.of());
        }

        // 3. Conflict / State -> 409
        if (upper.contains("RECORD_DESTROYED")
                || upper.contains("RECORD_ALREADY_BORROWED")
                || upper.contains("ALREADY_APPROVED")
                || upper.contains("ALREADY_EXISTS")
                || upper.contains("STATE_CONFLICT")
                || upper.contains("VERSION_CONFLICT")
                || upper.contains("CONFLICT")) {
            return buildError(409, extractCode(msg, "STATE_CONFLICT"), msg, List.of());
        }

        // 4. Domain boundary rejections -> 400
        if (upper.contains("SMALL_IMPAIRMENT_BLOCKED")) {
            return buildError(400, "SMALL_IMPAIRMENT_BLOCKED", msg, List.of());
        }
        if (upper.contains("INSUFFICIENT_STOCK")) {
            return buildError(400, "INSUFFICIENT_STOCK", msg, List.of());
        }
        if (upper.contains("BUDGET_EXCEEDED")) {
            return buildError(400, "BUDGET_EXCEEDED", msg, List.of());
        }
        if (upper.contains("LEASE_DURATION_EXCEEDED")) {
            return buildError(400, "LEASE_DURATION_EXCEEDED", msg, List.of());
        }

        if (t instanceof IllegalArgumentException) {
            return buildError(400, extractCode(msg, "INVALID_INPUT"), msg.isEmpty() ? "Invalid input" : msg, List.of());
        }
        if (t instanceof IllegalStateException) {
            return buildError(409, extractCode(msg, "STATE_CONFLICT"), msg.isEmpty() ? "Illegal state" : msg, List.of());
        }

        return buildError(500, "INTERNAL_SERVER_ERROR", msg.isEmpty() ? "操作失败" : msg, List.of());
    }

    private static SQLException findSqlException(Throwable t) {
        Throwable curr = t;
        while (curr != null) {
            if (curr instanceof SQLException se) {
                return se;
            }
            curr = curr.getCause();
        }
        return null;
    }

    private static ResponseEntity<Map<String, Object>> handleSqlException(SQLException sqlEx) {
        String state = sqlEx.getSQLState();
        String msg = sqlEx.getMessage() != null ? sqlEx.getMessage() : "";
        String upper = msg.toUpperCase();

        if ("23514".equals(state)) { // check_violation
            if (upper.contains("SOD") || upper.contains("CHECK_SOD_") || upper.contains("PERSON") || upper.contains("APPROVER")
                    || upper.contains("DESTRUCTION_MANIFEST_CHECK") || upper.contains("SUPERVISOR") || upper.contains("APPRAISER")) {
                return buildError(403, "SOD_VIOLATION", "Check constraint segregation of duties violation", List.of());
            }
            if (upper.contains("CLOUD_DR_STATUS")) {
                return buildError(400, "INVALID_INPUT", "Invalid cloud DR status", List.of());
            }
            return buildError(400, "CHECK_VIOLATION", msg, List.of());
        }
        if ("23505".equals(state)) { // unique_violation
            return buildError(409, "DUPLICATE_KEY_CONFLICT", "Unique constraint violation: duplicate key", List.of());
        }
        if ("23503".equals(state)) { // foreign_key_violation
            return buildError(409, "FOREIGN_KEY_VIOLATION", "Foreign key constraint violation", List.of());
        }
        return buildError(409, "DATABASE_INTEGRITY_VIOLATION", msg, List.of());
    }

    public static String extractCode(String msg, String defaultCode) {
        if (msg == null || msg.isBlank()) return defaultCode;
        int colon = msg.indexOf(':');
        String token = (colon > 0 ? msg.substring(0, colon) : msg).trim();
        if (token.matches("^[A-Z0-9_]{3,64}$")) {
            return token;
        }
        if (msg.contains("SoD") || msg.contains("Segregation of duties")) {
            return "SOD_VIOLATION";
        }
        if (msg.contains("SCOPE_DENIED")) {
            return "SCOPE_DENIED";
        }
        if (msg.contains("POLICY_PACK_NOT_FOUND")) {
            return "POLICY_PACK_NOT_FOUND";
        }
        if (msg.contains("ATTESTATION_NOT_FOUND")) {
            return "ATTESTATION_NOT_FOUND";
        }
        if (msg.contains("ARCHIVE_RECORD_NOT_FOUND")) {
            return "ARCHIVE_RECORD_NOT_FOUND";
        }
        if (msg.contains("SMALL_IMPAIRMENT_BLOCKED")) {
            return "SMALL_IMPAIRMENT_BLOCKED";
        }
        if (msg.contains("INSUFFICIENT_STOCK")) {
            return "INSUFFICIENT_STOCK";
        }
        if (msg.contains("BUDGET_EXCEEDED")) {
            return "BUDGET_EXCEEDED";
        }
        if (msg.contains("CRM_PAYLOAD_REJECTED")) {
            return "CRM_PAYLOAD_REJECTED";
        }
        if (msg.contains("AI_MAJOR_ACTION_BLOCKED")) {
            return "AI_MAJOR_ACTION_BLOCKED";
        }
        if (msg.contains("LEASE_DURATION_EXCEEDED")) {
            return "LEASE_DURATION_EXCEEDED";
        }
        if (msg.contains("不存在")) {
            return "NOT_FOUND";
        }
        return defaultCode;
    }

    public static ResponseEntity<Map<String, Object>> buildError(
            int status, String code, String message, List<Map<String, Object>> issues) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("detail", code);
        body.put("message", message != null && !message.isBlank() ? message : code);
        body.put("traceId", UUID.randomUUID().toString());
        body.put("retryable", false);
        if (issues != null && !issues.isEmpty()) {
            body.put("issues", issues);
        }
        return ResponseEntity.status(status).body(body);
    }
}
