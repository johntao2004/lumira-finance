package com.lumira.finance;

import com.lumira.platform.common.BoundedBody;
import com.lumira.platform.modules.ModuleEndpoint;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/** Typed evidence and expense HTTP boundary. Financial authorization remains transactional. */
@RestController
@RequestMapping("/api/finance/v1/books/{book}/years/{year}/evidence-usage")
@ModuleEndpoint("finance")
public class FinanceEvidenceUsageController {
    private final FinanceEvidenceDocuments documents;
    private final FinanceExpenseAllocations allocations;

    public FinanceEvidenceUsageController(
            FinanceEvidenceDocuments documents, FinanceExpenseAllocations allocations) {
        this.documents = documents;
        this.allocations = allocations;
    }

    private static UUID id(String value) {
        return value == null ? null : FinancePersonDirectory.uuid(value);
    }

    private static void query(HttpServletRequest request, boolean list) {
        if (!list && !request.getParameterMap().isEmpty())
            throw FinanceException.invalid("此单据操作不接受查询参数");
    }

    private Object read(
            Authentication actor,
            String book,
            String year,
            boolean document,
            String operation,
            String target,
            String child,
            HttpServletRequest request) {
        return FinanceHttp.respond(
                200,
                () -> {
                    query(request, operation.equals("list"));
                    UUID bookId = id(book),
                            yearId = id(year),
                            targetId = id(target),
                            childId = id(child);
                    return document
                            ? documents.read(
                                    actor.getName(),
                                    bookId,
                                    yearId,
                                    operation,
                                    targetId,
                                    childId,
                                    request)
                            : allocations.read(
                                    actor.getName(),
                                    bookId,
                                    yearId,
                                    operation,
                                    targetId,
                                    childId,
                                    request);
                });
    }

    private Object write(
            Authentication actor,
            String book,
            String year,
            boolean document,
            String operation,
            String target,
            String child,
            Map<String, Object> body,
            HttpServletRequest request,
            int success) {
        return FinanceHttp.respond(
                success,
                () -> {
                    query(request, false);
                    UUID bookId = id(book),
                            yearId = id(year),
                            targetId = id(target),
                            childId = id(child);
                    return document
                            ? documents.write(
                                    actor.getName(),
                                    bookId,
                                    yearId,
                                    operation,
                                    targetId,
                                    childId,
                                    body,
                                    request)
                            : allocations.write(
                                    actor.getName(),
                                    bookId,
                                    yearId,
                                    operation,
                                    targetId,
                                    childId,
                                    body,
                                    request);
                });
    }

    @GetMapping("/catalog")
    public Object documentCatalog(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            HttpServletRequest request) {
        return read(actor, book, year, true, "catalog", null, null, request);
    }

    @GetMapping("/documents")
    public Object documentList(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            HttpServletRequest request) {
        return read(actor, book, year, true, "list", null, null, request);
    }

    @PostMapping("/documents")
    public Object documentRegister(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @RequestBody @BoundedBody(maxBytes = 16384, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(actor, book, year, true, "register", null, null, body, request, 201);
    }

    @GetMapping("/documents/{document}")
    public Object documentDetail(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable("document") String target,
            HttpServletRequest request) {
        return read(actor, book, year, true, "detail", target, null, request);
    }

    @PostMapping("/documents/{document}/review-proposals")
    public Object documentReviewCreate(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable("document") String target,
            @RequestBody @BoundedBody(maxBytes = 16384, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(actor, book, year, true, "review-create", target, null, body, request, 201);
    }

    @GetMapping("/documents/{document}/review-proposals/{proposal}")
    public Object documentReviewDetail(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable("document") String target,
            @PathVariable("proposal") String child,
            HttpServletRequest request) {
        return read(actor, book, year, true, "review-detail", target, child, request);
    }

    @PostMapping("/documents/{document}/review-proposals/{proposal}/approve")
    public Object documentReviewApprove(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable("document") String target,
            @PathVariable("proposal") String child,
            @RequestBody @BoundedBody(maxBytes = 16384, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(actor, book, year, true, "review-approve", target, child, body, request, 200);
    }

    @PostMapping("/documents/{document}/review-proposals/{proposal}/reject")
    public Object documentReviewReject(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable("document") String target,
            @PathVariable("proposal") String child,
            @RequestBody @BoundedBody(maxBytes = 16384, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(actor, book, year, true, "review-reject", target, child, body, request, 200);
    }

    @PostMapping("/documents/{document}/review-proposals/{proposal}/cancel")
    public Object documentReviewCancel(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable("document") String target,
            @PathVariable("proposal") String child,
            @RequestBody @BoundedBody(maxBytes = 16384, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(actor, book, year, true, "review-cancel", target, child, body, request, 200);
    }

    @GetMapping("/allocations")
    public Object allocationList(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            HttpServletRequest request) {
        return read(actor, book, year, false, "list", null, null, request);
    }

    @PostMapping("/allocations")
    public Object allocationCreate(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @RequestBody @BoundedBody(maxBytes = 16384, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(actor, book, year, false, "create", null, null, body, request, 201);
    }

    @GetMapping("/allocations/{allocation}")
    public Object allocationDetail(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable("allocation") String target,
            HttpServletRequest request) {
        return read(actor, book, year, false, "detail", target, null, request);
    }

    @PatchMapping("/allocations/{allocation}")
    public Object allocationEdit(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable("allocation") String target,
            @RequestBody @BoundedBody(maxBytes = 20000, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(actor, book, year, false, "edit", target, null, body, request, 200);
    }

    @PostMapping("/allocations/{allocation}/submit")
    public Object allocationSubmit(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable("allocation") String target,
            @RequestBody @BoundedBody(maxBytes = 16384, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(actor, book, year, false, "submit", target, null, body, request, 200);
    }

    @PostMapping("/allocations/{allocation}/confirm")
    public Object allocationConfirm(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable("allocation") String target,
            @RequestBody @BoundedBody(maxBytes = 16384, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(actor, book, year, false, "confirm", target, null, body, request, 200);
    }

    @PostMapping("/allocations/{allocation}/withdraw")
    public Object allocationWithdraw(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable("allocation") String target,
            @RequestBody @BoundedBody(maxBytes = 16384, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(actor, book, year, false, "withdraw", target, null, body, request, 200);
    }

    @PostMapping("/allocations/{allocation}/reject")
    public Object allocationReject(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable("allocation") String target,
            @RequestBody @BoundedBody(maxBytes = 16384, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(actor, book, year, false, "reject", target, null, body, request, 200);
    }

    @PostMapping("/allocations/{allocation}/admin-terminate")
    public Object allocationAdminTerminate(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable("allocation") String target,
            @RequestBody @BoundedBody(maxBytes = 16384, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(actor, book, year, false, "admin-terminate", target, null, body, request, 200);
    }

    @PostMapping("/allocations/{allocation}/cancel")
    public Object allocationCancel(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable("allocation") String target,
            @RequestBody @BoundedBody(maxBytes = 16384, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(actor, book, year, false, "cancel", target, null, body, request, 200);
    }

    @PostMapping("/allocations/{allocation}/reverse-requests")
    public Object allocationReverseCreate(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable("allocation") String target,
            @RequestBody @BoundedBody(maxBytes = 16384, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(actor, book, year, false, "reverse-create", target, null, body, request, 201);
    }

    @GetMapping("/allocations/{allocation}/reverse-requests/{request}")
    public Object allocationReverseDetail(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable("allocation") String target,
            @PathVariable("request") String child,
            HttpServletRequest request) {
        return read(actor, book, year, false, "reverse-detail", target, child, request);
    }

    @PostMapping("/allocations/{allocation}/reverse-requests/{request}/approve")
    public Object allocationReverseApprove(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable("allocation") String target,
            @PathVariable("request") String child,
            @RequestBody @BoundedBody(maxBytes = 16384, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(
                actor, book, year, false, "reverse-approve", target, child, body, request, 200);
    }

    @PostMapping("/allocations/{allocation}/reverse-requests/{request}/reject")
    public Object allocationReverseReject(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable("allocation") String target,
            @PathVariable("request") String child,
            @RequestBody @BoundedBody(maxBytes = 16384, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(actor, book, year, false, "reverse-reject", target, child, body, request, 200);
    }

    @PostMapping("/allocations/{allocation}/reverse-requests/{request}/cancel")
    public Object allocationReverseCancel(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable("allocation") String target,
            @PathVariable("request") String child,
            @RequestBody @BoundedBody(maxBytes = 16384, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(actor, book, year, false, "reverse-cancel", target, child, body, request, 200);
    }
}
