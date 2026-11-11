package com.lumira.finance;

import com.lumira.platform.common.AuthorizationContextProjectionPort;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.util.*;

/** Real typed expense attribution. Confirmation/reversal changes only this evidence capacity. */
@Service
public class FinanceExpenseAllocations extends FinanceEvidenceAccess {
    static final List<String> LIMITATIONS =
            List.of(
                    "费用归属份额是真实单据额度消费者，不代表已报销、付款、总账入账或税务抵扣",
                    "确认后仅独立部分反向更正净份额，不代表银行退款或总账冲销",
                    "仅原账套年度、CNY和已启用人员协议年度；未接入SETTLEMENT/ACCOUNTING_CONFIRMATION/TAX自动消费");

    @Autowired
    public FinanceExpenseAllocations(
            FinanceStore db,
            FinanceService finance,
            FinanceJournalPersonGate gate,
            PlatformTransactionManager transactions,
            AuthorizationContextProjectionPort authorization) {
        super(db, finance, gate, transactions, authorization);
    }

    public Object read(
            String principal,
            UUID book,
            UUID year,
            String op,
            UUID target,
            UUID child,
            HttpServletRequest request) {
        return tx(
                false,
                () -> {
                    var b = scope(principal, book, year, null);
                    return switch (op) {
                        case "list" ->
                                page(
                                        principal,
                                        b,
                                        year,
                                        "allocations",
                                        request,
                                        r -> summary(b, year, r));
                        case "detail" -> detail(b, year, target);
                        case "reverse-detail" -> reverse(b, year, target, child);
                        default -> throw FinanceException.invalid("费用份额读取操作无效");
                    };
                });
    }

    BigDecimal reversed(Map<String, Object> b, UUID year, UUID allocation) {
        return new BigDecimal(
                db.one(
                                "SELECT COALESCE(SUM(amount),0) amount FROM"
                                    + " plugin_finance_evidence_reverse_effect WHERE tenant_id=?"
                                    + " AND book_id=? AND year_id=? AND allocation_id=?",
                                b.get("tenant_id"),
                                b.get("id"),
                                year,
                                allocation)
                        .get("amount")
                        .toString());
    }

    Map<String, Object> usage(Map<String, Object> b, UUID year, Object id) {
        if (id == null) return null;
        var r = row("usage", b, year, (UUID) id);
        return map(
                "id",
                r.get("id"),
                "submissionId",
                r.get("submission_id"),
                "purpose",
                "EXPENSE_ALLOCATION",
                "state",
                r.get("state"),
                "amount",
                money8(r.get("amount")),
                "reservedAt",
                r.get("reserved_at"),
                "expiresAt",
                r.get("expires_at"),
                "releasedAt",
                r.get("released_at"),
                "releaseDecisionId",
                r.get("release_id"),
                "committedAt",
                r.get("committed_at"),
                "confirmationId",
                r.get("confirmation_id"));
    }

    Map<String, Object> summary(Map<String, Object> b, UUID year, Map<String, Object> a) {
        var chain =
                db.one(
                        "SELECT business_chain_key FROM plugin_finance_evidence_chain WHERE"
                            + " tenant_id=? AND book_id=? AND year_id=? AND id=?",
                        b.get("tenant_id"),
                        b.get("id"),
                        year,
                        a.get("chain_id"));
        var maker = json(a.get("prepared_by"));
        var editor = json(a.get("last_editor"));
        Map<String, Object> confirmation =
                a.get("current_confirmation_id") == null
                        ? null
                        : row("confirmation", b, year, (UUID) a.get("current_confirmation_id"));
        Map<String, Object> u = usage(b, year, a.get("current_usage_id"));
        BigDecimal reversed = reversed(b, year, (UUID) a.get("id"));
        BigDecimal net =
                confirmation == null
                        ? BigDecimal.ZERO
                        : new BigDecimal(confirmation.get("amount").toString()).subtract(reversed);
        var actions = new ArrayList<String>();
        boolean identity = "AVAILABLE".equals(actor(b).get("identityAvailability"));
        boolean own = b.get("actor_id").toString().equals(maker.get("accountId"));
        boolean independent = !samePerson(b, maker) && !samePerson(b, editor);
        String status = a.get("status").toString();
        if (identity) {
            if (status.equals("DRAFT")) {
                if (own && allows(b, "create")) {
                    actions.add("cancel");
                    if (openRead(b, year)) {
                        actions.add("edit-allocation");
                        actions.add("submit");
                    }
                }
                if (independent && allows(b, "approve")) actions.add("admin-terminate");
            }
            if (status.equals("SUBMITTED")) {
                if (own && allows(b, "create")) actions.add("withdraw");
                if (independent && allows(b, "approve")) {
                    actions.add("reject");
                    actions.add("admin-terminate");
                    if (openRead(b, year)
                            && u != null
                            && ((java.sql.Timestamp) u.get("expiresAt"))
                                    .toInstant()
                                    .isAfter(java.time.Instant.now())) actions.add("confirm");
                }
            }
            if (status.equals("CONFIRMED")
                    && a.get("pending_reverse_id") == null
                    && net.signum() > 0
                    && allows(b, "manage")
                    && allows(b, "create")) actions.add("request-reverse");
        }
        return map(
                "id",
                a.get("id"),
                "documentId",
                a.get("document_id"),
                "businessChainId",
                a.get("chain_id"),
                "businessChainKey",
                chain.get("business_chain_key"),
                "sourceSystem",
                a.get("source_system"),
                "sourceOperationKey",
                a.get("source_operation_key"),
                "purpose",
                "EXPENSE_ALLOCATION",
                "status",
                status,
                "version",
                count(a.get("version")),
                "contentVersion",
                count(a.get("content_version")),
                "contentDigest",
                a.get("content_digest"),
                "occurredDate",
                a.get("occurred_date"),
                "amount",
                money8(a.get("amount")),
                "basis",
                json(a.get("basis_snapshot")),
                "purposeEvidence",
                json(a.get("purpose_original_snapshot")),
                "description",
                a.get("description"),
                "preparedBy",
                maker,
                "lastEditor",
                editor,
                "submissionId",
                a.get("current_submission_id"),
                "reservationState",
                u == null ? null : u.get("state"),
                "reservationExpiresAt",
                u == null ? null : u.get("expiresAt"),
                "confirmationId",
                a.get("current_confirmation_id"),
                "confirmedBy",
                confirmation == null ? null : json(confirmation.get("confirmed_by")),
                "confirmedAt",
                confirmation == null ? null : confirmation.get("confirmed_at"),
                "reversedAmount",
                money8(reversed),
                "netConfirmedAmount",
                money8(net),
                "pendingReverseRequestId",
                a.get("pending_reverse_id"),
                "latestReverseRequestId",
                a.get("latest_reverse_id"),
                "allowedActions",
                actions,
                "blockers",
                List.of());
    }

    Map<String, Object> detail(Map<String, Object> b, UUID year, UUID id) {
        var a = row("allocation", b, year, id);
        var releases =
                db.list(
                        "SELECT * FROM plugin_finance_evidence_release WHERE tenant_id=? AND"
                            + " book_id=? AND year_id=? AND allocation_id=? ORDER BY"
                            + " allocation_version DESC LIMIT 1",
                        b.get("tenant_id"),
                        b.get("id"),
                        year,
                        id);
        Map<String, Object> last = null;
        if (!releases.isEmpty()) {
            var r = releases.getFirst();
            last =
                    map(
                            "id",
                            r.get("id"),
                            "action",
                            r.get("action"),
                            "actor",
                            json(r.get("actor")),
                            "reason",
                            r.get("reason"),
                            "decidedAt",
                            r.get("released_at"),
                            "submissionId",
                            r.get("submission_id"),
                            "usageId",
                            r.get("usage_id"));
        }
        return map(
                "allocation",
                summary(b, year, a),
                "currentUsage",
                usage(b, year, a.get("current_usage_id")),
                "currentActor",
                actor(b),
                "latestTermination",
                last,
                "limitations",
                LIMITATIONS);
    }

    Map<String, Object> reverse(Map<String, Object> b, UUID year, UUID allocation, UUID id) {
        var r = row("reverse_request", b, year, id);
        if (!Objects.equals(allocation, r.get("allocation_id")))
            throw error("SCOPE_DENIED", "范围不可访问");
        var a = row("allocation", b, year, allocation);
        var confirmation = row("confirmation", b, year, (UUID) r.get("confirmation_id"));
        var requester = json(r.get("requested_by"));
        var content = json(r.get("content"));
        var actions = new ArrayList<String>();
        if (pending(r) && "AVAILABLE".equals(actor(b).get("identityAvailability"))) {
            if (allows(b, "approve") && !samePerson(b, requester)) {
                actions.add("reject-reverse");
                if (fresh(r)) actions.add("approve-reverse");
            }
            if (allows(b, "manage")
                    && allows(b, "create")
                    && b.get("actor_id").toString().equals(requester.get("accountId")))
                actions.add("cancel-reverse");
        }
        return map(
                "id",
                r.get("id"),
                "allocationId",
                allocation,
                "documentId",
                r.get("document_id"),
                "businessChainId",
                a.get("chain_id"),
                "purpose",
                "EXPENSE_ALLOCATION",
                "status",
                r.get("status"),
                "version",
                count(r.get("version")),
                "contentDigest",
                r.get("content_digest"),
                "originalConfirmationId",
                r.get("confirmation_id"),
                "originalCommittedUsageId",
                r.get("usage_id"),
                "expectedAllocationVersion",
                count(r.get("expected_allocation_version")),
                "amount",
                money8(r.get("amount")),
                "basisOriginal",
                json(r.get("basis_original_snapshot")),
                "reason",
                r.get("reason"),
                "requestedBy",
                requester,
                "createdAt",
                r.get("created_at"),
                "expiresAt",
                r.get("expires_at"),
                "reviewedBy",
                r.get("decided_by") == null ? null : json(r.get("decided_by")),
                "reviewedAt",
                r.get("decided_at"),
                "decisionReason",
                r.get("decision_reason"),
                "reverseEffectId",
                r.get("reverse_effect_id"),
                "scopeOfEffect",
                FinanceEvidenceInput.REVERSE_SCOPE,
                "allowedActions",
                actions,
                "blockers",
                List.of(),
                "previousReverseRequestId",
                r.get("previous_reverse_request_id"),
                "originalPreparedBy",
                json(a.get("prepared_by")),
                "originalConfirmedBy",
                json(confirmation.get("confirmed_by")),
                "originalConfirmedAmount",
                money8(confirmation.get("amount")),
                "alreadyReversedAmount",
                money8(content.get("alreadyReversedAmount")),
                "netRemainingBefore",
                money8(content.get("netRemainingBefore")),
                "netRemainingAfterProposed",
                money8(content.get("netRemainingAfterProposed")));
    }

    void actionIdentity(
            Map<String, Object> b,
            UUID year,
            String op,
            UUID target,
            UUID child,
            Map<String, Object> who) {
        if (op.equals("create")) return;
        var a = row("allocation", b, year, target);
        var maker = json(a.get("prepared_by"));
        var editor = json(a.get("last_editor"));
        switch (op) {
            case "edit", "submit", "withdraw", "cancel" -> sameAccount(who, maker);
            case "confirm", "reject", "admin-terminate" -> {
                independent(who, maker);
                independent(who, editor);
            }
            case "reverse-approve", "reverse-reject", "reverse-cancel" -> {
                var r = row("reverse_request", b, year, child);
                if (!target.equals(r.get("allocation_id"))) throw error("SCOPE_DENIED", "范围不可访问");
                var requester = json(r.get("requested_by"));
                if (op.equals("reverse-cancel")) sameAccount(who, requester);
                else independent(who, requester);
                if (op.equals("reverse-approve")) recheckMaker(b, year, requester, true);
            }
            default -> {}
        }
        if (op.equals("confirm")) {
            recheckMaker(b, year, maker, false);
            recheckMaker(b, year, editor, false);
        }
    }

    public Object write(
            String principal,
            UUID book,
            UUID year,
            String op,
            UUID target,
            UUID child,
            Map<String, Object> input,
            HttpServletRequest request) {
        var n = FinanceEvidenceInput.normalize(op, input);
        return tx(
                true,
                () -> {
                    var b = locked(principal, book, year, op, n.get("idempotencyKey").toString());
                    var who = who(b, op, request);
                    actionIdentity(b, year, op, target, child, who);
                    String route = "allocation/" + op + "/" + target + "/" + child;
                    var result = cached(b, year, route, n);
                    if (result == null) {
                        if (op.equals("create")) {
                            row(
                                    "document",
                                    b,
                                    year,
                                    FinanceEvidenceInput.uuid(n.get("documentId")));
                            var chains =
                                    db.list(
                                            "SELECT book_id,year_id FROM"
                                                + " plugin_finance_evidence_chain WHERE tenant_id=?"
                                                + " AND legal_entity_id=? AND source_system=? AND"
                                                + " business_chain_key=?",
                                            b.get("tenant_id"),
                                            b.get("legal_entity_id"),
                                            n.get("sourceSystem"),
                                            n.get("businessChainKey"));
                            for (var chain : chains)
                                homeCollision(
                                        principal,
                                        b,
                                        year,
                                        (UUID) chain.get("book_id"),
                                        (UUID) chain.get("year_id"));
                        }
                        String sqlOp =
                                switch (op) {
                                    case "create" -> "CREATE";
                                    case "edit" -> "EDIT";
                                    case "submit" -> "SUBMIT";
                                    case "confirm" -> "CONFIRM";
                                    case "withdraw" -> "WITHDRAW";
                                    case "reject" -> "REJECT";
                                    case "admin-terminate" -> "ADMIN_TERMINATE";
                                    case "cancel" -> "CANCEL";
                                    case "reverse-create" -> "REVERSE_REQUEST";
                                    case "reverse-approve" -> "REVERSE_APPROVE";
                                    case "reverse-reject" -> "REVERSE_REJECT";
                                    case "reverse-cancel" -> "REVERSE_CANCEL";
                                    default -> throw FinanceException.invalid("费用份额操作无效");
                                };
                        result = command("allocation", b, year, who, sqlOp, target, child, n);
                        remember(b, year, route, n, result);
                    }
                    UUID allocation = FinanceEvidenceInput.uuid(result.get("allocationId"));
                    return result.get("kind").equals("REVERSE")
                            ? reverse(
                                    b,
                                    year,
                                    allocation,
                                    FinanceEvidenceInput.uuid(result.get("id")))
                            : detail(b, year, allocation);
                });
    }
}
