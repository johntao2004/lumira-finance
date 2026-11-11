package com.lumira.finance;

import com.lumira.platform.common.AuthorizationContextProjectionPort;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

abstract class FinancePayablesAp01Projection extends FinancePayablesAp01Access {
    FinancePayablesAp01Projection(
            FinanceStore d,
            FinanceService f,
            FinanceJournalPersonGate g,
            PlatformTransactionManager t,
            AuthorizationContextProjectionPort a) {
        super(d, f, g, t, a);
    }

    static String time(Object v) {
        return v == null
                ? null
                : v instanceof Timestamp t ? t.toInstant().toString() : v.toString();
    }

    Map<String, Object> publicScope(Call c, Map<String, Object> b, UUID y) {
        var z =
                c.one(
                        "SELECT y.year,y.precision_profile,m.mode FROM plugin_finance_year y JOIN"
                            + " plugin_finance_journal_person_mode m"
                            + " ON(m.tenant_id,m.book_id,m.year_id)=(y.tenant_id,y.book_id,y.id)"
                            + " WHERE y.tenant_id=? AND y.book_id=? AND y.id=?",
                        b.get("tenant_id"),
                        b.get("id"),
                        y);
        return map(
                "tenantId",
                b.get("tenant_id"),
                "legalEntityId",
                b.get("legal_entity_id"),
                "bookId",
                b.get("id"),
                "yearId",
                y,
                "yearNumber",
                z.get("year"),
                "precisionProfile",
                z.get("precision_profile"),
                "journalMode",
                z.get("mode"));
    }

    Map<String, Object> base(Call c, Map<String, Object> b, UUID y) {
        return map(
                "schemaVersion",
                FinancePayablesAp01Input.SCHEMA,
                "scope",
                publicScope(c, b, y),
                "currentActor",
                actor(c, b));
    }

    boolean open(Call c, Map<String, Object> b, UUID y) {
        var z =
                c.one(
                        "SELECT y.status,y.precision_profile,y.precision_review_required,m.mode"
                            + " FROM plugin_finance_year y JOIN plugin_finance_journal_person_mode"
                            + " m ON(m.tenant_id,m.book_id,m.year_id)=(y.tenant_id,y.book_id,y.id)"
                            + " WHERE y.tenant_id=? AND y.book_id=? AND y.id=?",
                        b.get("tenant_id"),
                        b.get("id"),
                        y);
        return "OPEN".equals(z.get("status"))
                && "V4".equals(z.get("precision_profile"))
                && !Boolean.TRUE.equals(z.get("precision_review_required"))
                && "JOURNAL_PERSON_SOD".equals(z.get("mode"));
    }

    Map<String, Object> catalog(Call c, Map<String, Object> b, UUID y) {
        var v = base(c, b, y);
        boolean eligible =
                open(c, b, y) && actor(c, b).get("identityAvailability").equals("AVAILABLE");
        v.putAll(
                map(
                        "supportedPurpose",
                        "ACCOUNTING_CONFIRMATION",
                        "sourceSystem",
                        "V15_CONFIRMED_EXPENSE_AP_V1",
                        "businessKind",
                        "ORDINARY_SUPPLIER_SERVICE_EXPENSE",
                        "parentAmountRule",
                        "FULL_CURRENT_CONFIRMED_NET_ONLY",
                        "expenseProtocolPurpose",
                        "EXPENSE_ALLOCATION",
                        "oldExpenseCatalogIsProtocolLocal",
                        true,
                        "settlementSupported",
                        false,
                        "bankPaidSupported",
                        false,
                        "taxDeductionSupported",
                        false,
                        "fullEnterpriseSubledgerComplete",
                        false,
                        "canExecuteBase",
                        false,
                        "yearCloseEnabled",
                        false,
                        "eligibleForNewUse",
                        eligible,
                        "allowedActions",
                        eligible && allowed(b, "create")
                                ? "OWNER".equals(b.get("role")) && allowed(b, "manage")
                                        ? List.of("CREATE", "CREATE_BASIS")
                                        : List.of("CREATE")
                                : List.of(),
                        "blockers",
                        eligible
                                ? List.of()
                                : List.of(
                                        map(
                                                "code",
                                                "JOURNAL_PERSON_MODE_REQUIRED",
                                                "message",
                                                "仅开放V4人员审签年及当前有效人员可新用"))));
        return v;
    }

    Map<String, Object> capacity(Call c, Map<String, Object> b, UUID y, Object doc) {
        var h =
                c.list(
                        "SELECT h.*,v.version,v.content_digest,v.capacity,v.content FROM"
                            + " plugin_finance_ap_basis_head h LEFT JOIN plugin_finance_ap_basis v"
                            + " ON(v.tenant_id,v.book_id,v.year_id,v.id)=(h.tenant_id,h.book_id,h.year_id,h.current_basis_id)"
                            + " WHERE h.tenant_id=? AND h.book_id=? AND h.year_id=? AND"
                            + " h.document_id=?",
                        b.get("tenant_id"),
                        b.get("id"),
                        y,
                        doc);
        var sums =
                c.one(
                        "SELECT coalesce(sum(amount) FILTER(WHERE"
                                + " state='RESERVED'),0)::numeric(28,8)::text"
                                + " reserved,coalesce(sum(amount) FILTER(WHERE"
                                + " state='COMMITTED'),0)::numeric(28,8)::text committed FROM"
                                + " plugin_finance_ap_usage WHERE tenant_id=? AND book_id=? AND"
                                + " year_id=? AND document_id=?",
                        b.get("tenant_id"),
                        b.get("id"),
                        y,
                        doc);
        var guard =
                c.one(
                        "SELECT generation FROM plugin_finance_ap_guard WHERE tenant_id=? AND"
                                + " book_id=? AND year_id=?",
                        b.get("tenant_id"),
                        b.get("id"),
                        y);
        Map<String, Object> row = h.isEmpty() ? map() : h.getFirst();
        boolean known = row.get("current_basis_id") != null;
        String cap = known ? row.get("capacity").toString() : null;
        Object expected =
                known
                        ? map(
                                "basisId",
                                row.get("current_basis_id"),
                                "basisVersion",
                                count(row.get("version")),
                                "contentDigest",
                                row.get("content_digest"),
                                "documentExpectation",
                                json(row.get("content")).get("expectedDocument"))
                        : null;
        return map(
                "documentId",
                doc,
                "purpose",
                "ACCOUNTING_CONFIRMATION",
                "qualification",
                known ? "ORG_REVIEWED" : "UNKNOWN",
                "currency",
                "CNY",
                "capacity",
                cap,
                "reserved",
                sums.get("reserved"),
                "netCommitted",
                sums.get("committed"),
                "available",
                known
                        ? new BigDecimal(cap)
                                .subtract(new BigDecimal(sums.get("reserved").toString()))
                                .subtract(new BigDecimal(sums.get("committed").toString()))
                                .toPlainString()
                        : null,
                "currentBasis",
                expected,
                "pendingBasisId",
                row.get("pending_basis_id"),
                "latestBasisId",
                row.get("latest_basis_id"),
                "eligibilityVersion",
                count(guard.get("generation")));
    }

    Map<String, Object> receipt(Call c, Map<String, Object> b, UUID y, UUID id) {
        var r =
                c.one(
                        "SELECT * FROM plugin_finance_ap_command_receipt WHERE tenant_id=? AND"
                                + " book_id=? AND year_id=? AND id=?",
                        b.get("tenant_id"),
                        b.get("id"),
                        y,
                        id);
        return map(
                "id",
                id,
                "action",
                r.get("action"),
                "actor",
                json(r.get("actor")),
                "createdAt",
                time(r.get("created_at")),
                "reason",
                r.get("reason"),
                "targetId",
                r.get(r.get("kind").equals("BASIS") ? "basis_id" : "run_id"),
                "targetVersion",
                count(r.get("target_version")),
                "contentDigest",
                r.get("content_digest"),
                "fromState",
                r.get("from_state"),
                "toState",
                r.get("to_state"),
                "submissionId",
                r.get("submission_id"),
                "approvalId",
                r.get("approval_id"),
                "postReceiptId",
                r.get("post_id"),
                "releaseId",
                r.get("release_id"));
    }

    Map<String, Object> basisSummary(Map<String, Object> r) {
        return map(
                "id",
                r.get("id"),
                "documentId",
                r.get("document_id"),
                "version",
                count(r.get("version")),
                "revision",
                count(r.get("revision_no")),
                "status",
                r.get("status"),
                "contentDigest",
                r.get("content_digest"),
                "previousProposalId",
                r.get("previous_basis_id"),
                "baseApprovedBasisId",
                r.get("base_approved_basis_id"),
                "maker",
                json(r.get("prepared_by")),
                "createdAt",
                time(r.get("created_at")));
    }

    Map<String, Object> basisDetail(Call c, Map<String, Object> b, UUID y, UUID id) {
        var r = basis(c, b, y, id);
        var v = base(c, b, y);
        var me = actor(c, b);
        boolean mine =
                id(me.get("accountId")).equals(id(json(r.get("prepared_by")).get("accountId")));
        var actions = new ArrayList<String>();
        if (r.get("status").equals("PENDING")) {
            if (mine && "OWNER".equals(b.get("role")) && allowed(b, "create"))
                actions.add("CANCEL_BASIS");
            if (!samePerson(me, json(r.get("prepared_by"))) && allowed(b, "approve")) {
                actions.add("REJECT_BASIS");
                if (open(c, b, y)) actions.add("APPROVE_BASIS");
            }
        }
        Object decision = null;
        if (r.get("decided_by") != null) {
            var q =
                    c.one(
                            "SELECT id FROM plugin_finance_ap_command_receipt WHERE tenant_id=? AND"
                                + " book_id=? AND year_id=? AND basis_id=? AND target_version=?",
                            b.get("tenant_id"),
                            b.get("id"),
                            y,
                            id,
                            r.get("version"));
            decision = receipt(c, b, y, id(q.get("id")));
        }
        v.putAll(
                map(
                        "basis",
                        basisSummary(r),
                        "content",
                        json(r.get("content")),
                        "capacity",
                        capacity(c, b, y, r.get("document_id")),
                        "decision",
                        decision,
                        "allowedActions",
                        actions,
                        "blockers",
                        List.of()));
        return v;
    }

    Map<String, Object> slot(Call c, Map<String, Object> b, UUID y, Object parent, Object chain) {
        var rows =
                c.list(
                        "SELECT s.*,r.version latest_version FROM plugin_finance_ap_source_slot s"
                            + " LEFT JOIN plugin_finance_ap_run r"
                            + " ON(r.tenant_id,r.book_id,r.year_id,r.id)=(s.tenant_id,s.book_id,s.year_id,s.latest_attempt_id)"
                            + " WHERE s.tenant_id=? AND s.book_id=? AND s.year_id=? AND"
                            + " s.parent_allocation_id=?",
                        b.get("tenant_id"),
                        b.get("id"),
                        y,
                        parent);
        var r = rows.isEmpty() ? map() : rows.getFirst();
        return map(
                "parentAllocationId",
                parent,
                "chainId",
                chain,
                "sourceSystem",
                "V15_CONFIRMED_EXPENSE_AP_V1",
                "purpose",
                "ACCOUNTING_CONFIRMATION",
                "slotId",
                r.get("id"),
                "slotVersion",
                rows.isEmpty() ? "0" : count(r.get("version")),
                "latestAttemptId",
                r.get("latest_attempt_id"),
                "latestAttemptVersion",
                count(r.get("latest_version")),
                "activeOriginalConfirmationId",
                r.get("active_original_id"),
                "activeRecognitionUsageId",
                r.get("active_usage_id"),
                "activeOpenItemId",
                r.get("active_open_item_id"));
    }

    Map<String, Object> summary(Call c, Map<String, Object> b, UUID y, Map<String, Object> r) {
        var linked =
                c.one(
                        "SELECT (SELECT id FROM plugin_finance_ap_run z"
                            + " WHERE(z.tenant_id,z.book_id,z.year_id,z.original_run_id)=(?,?,?,?)"
                            + " AND z.status IN('DRAFT','SUBMITTED','APPROVED') ORDER BY"
                            + " z.created_at DESC LIMIT 1) pending,(SELECT run_id FROM"
                            + " plugin_finance_ap_reverse_effect e"
                            + " WHERE(e.tenant_id,e.book_id,e.year_id,e.original_run_id)=(?,?,?,?))"
                            + " posted",
                        b.get("tenant_id"),
                        b.get("id"),
                        y,
                        r.get("id"),
                        b.get("tenant_id"),
                        b.get("id"),
                        y,
                        r.get("id"));
        String effect = "NONE";
        var us =
                c.list(
                        "SELECT state FROM plugin_finance_ap_usage WHERE tenant_id=? AND book_id=?"
                                + " AND year_id=? AND run_id=? ORDER BY reserved_at DESC LIMIT 1",
                        b.get("tenant_id"),
                        b.get("id"),
                        y,
                        r.get("id"));
        if (!us.isEmpty() && !us.getFirst().get("state").equals("RELEASED"))
            effect = us.getFirst().get("state").toString();
        return map(
                "id",
                r.get("id"),
                "operationId",
                r.get("operation_id"),
                "producerKind",
                r.get("producer_kind"),
                "journalId",
                r.get("journal_id"),
                "rawType",
                r.get("producer_kind"),
                "journalStatus",
                r.get("status"),
                "version",
                count(r.get("version")),
                "contentVersion",
                count(r.get("content_version")),
                "contentDigest",
                r.get("content_digest"),
                "sourceDigest",
                r.get("source_digest"),
                "parentAllocationId",
                r.get("parent_allocation_id"),
                "documentId",
                r.get("document_id"),
                "counterpartyId",
                r.get("supplier_id"),
                "accountingDate",
                r.get("accounting_date").toString(),
                "amount",
                r.get("amount").toString(),
                "currentEffect",
                effect,
                "predecessorAttemptId",
                r.get("predecessor_id"),
                "reversesPayableId",
                r.get("original_run_id"),
                "pendingReversalAttemptId",
                linked.get("pending"),
                "postedReversalAttemptId",
                linked.get("posted"),
                "maker",
                json(r.get("prepared_by")),
                "createdAt",
                time(r.get("created_at")));
    }

    Map<String, Object> submission(Call c, Map<String, Object> b, UUID y, Map<String, Object> r) {
        if (r.get("current_submission_id") == null) return null;
        var s =
                c.one(
                        "SELECT s.*,u.id usage_id,u.state usage_state,d.id dependency_id FROM"
                            + " plugin_finance_ap_submission s LEFT JOIN plugin_finance_ap_usage u"
                            + " ON(u.tenant_id,u.book_id,u.year_id,u.submission_id)=(s.tenant_id,s.book_id,s.year_id,s.id)"
                            + " LEFT JOIN plugin_finance_ap_parent_dependency d"
                            + " ON(d.tenant_id,d.book_id,d.year_id,d.usage_id)=(u.tenant_id,u.book_id,u.year_id,u.id)"
                            + " WHERE s.tenant_id=? AND s.book_id=? AND s.year_id=? AND s.id=?",
                        b.get("tenant_id"),
                        b.get("id"),
                        y,
                        r.get("current_submission_id"));
        return map(
                "id",
                s.get("id"),
                "payableId",
                r.get("id"),
                "contentVersion",
                count(r.get("content_version")),
                "contentDigest",
                s.get("content_digest"),
                "submittedBy",
                json(s.get("actor")),
                "submittedAt",
                time(s.get("submitted_at")),
                "expiresAt",
                time(s.get("expires_at")),
                "recognitionUsageId",
                s.get("usage_id"),
                "parentDependencyId",
                s.get("dependency_id"),
                "state",
                s.get("usage_state") == null
                        ? "REVERSAL_PENDING"
                        : s.get("usage_state").equals("FULLY_REVERSED")
                                ? "COMMITTED"
                                : s.get("usage_state"));
    }

    Map<String, Object> approval(Call c, Map<String, Object> b, UUID y, Map<String, Object> r) {
        if (r.get("current_approval_id") == null) return null;
        var a =
                c.one(
                        "SELECT *,expires_at<=clock_timestamp() expired FROM"
                                + " plugin_finance_ap_approval WHERE tenant_id=? AND book_id=? AND"
                                + " year_id=? AND id=?",
                        b.get("tenant_id"),
                        b.get("id"),
                        y,
                        r.get("current_approval_id"));
        return map(
                "id",
                a.get("id"),
                "payableId",
                r.get("id"),
                "submissionId",
                a.get("submission_id"),
                "contentVersion",
                count(r.get("content_version")),
                "contentDigest",
                a.get("content_digest"),
                "sourceDigest",
                a.get("source_digest"),
                "approvedBy",
                json(a.get("actor")),
                "approvedAt",
                time(a.get("approved_at")),
                "expiresAt",
                time(a.get("expires_at")),
                "state",
                r.get("status").equals("POSTED")
                        ? "EFFECTIVE"
                        : r.get("status").equals("APPROVED")
                                ? Boolean.TRUE.equals(a.get("expired")) ? "EXPIRED" : "EFFECTIVE"
                                : "SUPERSEDED");
    }

    Map<String, Object> postReceipt(Call c, Map<String, Object> b, UUID y, Map<String, Object> r) {
        if (r.get("post_id") == null) return null;
        var p =
                c.one(
                        "SELECT p.*,u.id receipt_usage_id,i.id item_id,d.id dependency_id,x.id"
                            + " adjustment_id,x.original_post_id FROM plugin_finance_ap_post p LEFT"
                            + " JOIN plugin_finance_ap_reverse_effect x"
                            + " ON(x.tenant_id,x.book_id,x.year_id,x.run_id,x.post_id)=(p.tenant_id,p.book_id,p.year_id,p.run_id,p.id)"
                            + " LEFT JOIN plugin_finance_ap_usage u"
                            + " ON(u.tenant_id,u.book_id,u.year_id,u.run_id,u.id)"
                            + " =(p.tenant_id,p.book_id,p.year_id,coalesce(x.original_run_id,p.run_id),coalesce(x.original_usage_id,p.usage_id))"
                            + " LEFT JOIN plugin_finance_ap_open_item i"
                            + " ON(i.tenant_id,i.book_id,i.year_id,i.run_id,i.post_id,i.usage_id)"
                            + " =(p.tenant_id,p.book_id,p.year_id,u.run_id,coalesce(x.original_post_id,p.id),u.id)"
                            + " AND(x.id IS NULL OR i.id=x.open_item_id) LEFT JOIN"
                            + " plugin_finance_ap_parent_dependency d"
                            + " ON(d.tenant_id,d.book_id,d.year_id,d.run_id,d.usage_id)=(p.tenant_id,p.book_id,p.year_id,u.run_id,u.id)"
                            + " WHERE p.tenant_id=? AND p.book_id=? AND p.year_id=? AND p.id=?",
                        b.get("tenant_id"),
                        b.get("id"),
                        y,
                        r.get("post_id"));
        return map(
                "id",
                p.get("id"),
                "payableId",
                r.get("id"),
                "journalId",
                r.get("journal_id"),
                "postingSeq",
                count(p.get("posting_seq")),
                "journalVersion",
                count(p.get("run_version")),
                "contentVersion",
                count(r.get("content_version")),
                "contentDigest",
                r.get("content_digest"),
                "submissionId",
                p.get("submission_id"),
                "approvalId",
                p.get("approval_id"),
                "recognitionUsageId",
                p.get("receipt_usage_id"),
                "openItemId",
                p.get("item_id"),
                "openItemAdjustmentId",
                p.get("adjustment_id"),
                "parentDependencyId",
                p.get("dependency_id"),
                "postedBy",
                json(p.get("actor")),
                "postedAt",
                time(p.get("posted_at")),
                "amount",
                r.get("amount").toString(),
                "reversesPostReceiptId",
                p.get("original_post_id"));
    }

    Map<String, Object> item(Call c, Map<String, Object> b, UUID y, Map<String, Object> i) {
        var r = header(c, b, y, id(i.get("run_id")));
        var content = json(r.get("content"));
        String amount = i.get("amount").toString();
        var reversal =
                c.list(
                        "SELECT id FROM plugin_finance_ap_reverse_effect WHERE tenant_id=? AND"
                                + " book_id=? AND year_id=? AND open_item_id=?",
                        b.get("tenant_id"),
                        b.get("id"),
                        y,
                        i.get("id"));
        boolean reversed = !reversal.isEmpty();
        return map(
                "id",
                i.get("id"),
                "originalPayableId",
                i.get("run_id"),
                "journalId",
                r.get("journal_id"),
                "postReceiptId",
                i.get("post_id"),
                "counterpartyId",
                i.get("supplier_id"),
                "supplierSnapshot",
                content.get("supplier"),
                "documentId",
                r.get("document_id"),
                "parentAllocationId",
                r.get("parent_allocation_id"),
                "chainId",
                json(r.get("source_snapshot")).get("chainId"),
                "currency",
                "CNY",
                "recognizedPrincipal",
                amount,
                "accountingReversedAmount",
                reversed ? amount : "0.00000000",
                "systemOutstanding",
                reversed ? "0.00000000" : amount,
                "systemOutstandingDefinition",
                "PRINCIPAL_MINUS_TYPED_ACCOUNTING_REVERSAL_NOT_EXTERNAL_UNPAID_BALANCE",
                "externalSettlementQualification",
                "UNKNOWN_AP02_NOT_IMPLEMENTED",
                "externalSettlementAmount",
                null,
                "bankPaidStatus",
                null,
                "dueDate",
                i.get("due_date") == null ? null : i.get("due_date").toString(),
                "status",
                reversed ? "ACCOUNTING_REVERSED" : "OPEN",
                "fullReversalAdjustmentId",
                reversed ? reversal.getFirst().get("id") : null,
                "version",
                count(i.get("version")));
    }

    Map<String, Object> detail(
            Call c, String principal, Map<String, Object> b, UUID y, UUID target) {
        return detail(c, principal, b, y, target, null);
    }

    Map<String, Object> detail(
            Call c,
            String principal,
            Map<String, Object> b,
            UUID y,
            UUID target,
            HttpServletRequest request) {
        var r = header(c, b, y, target);
        var v = base(c, b, y);
        var blockers = new ArrayList<Map<String, Object>>();
        Object supplierStatus = null;
        try {
            supplierRead(c, principal, b, r.get("supplier_id"));
            var z =
                    c.one(
                            "SELECT v.desired_status FROM plugin_finance_counterparty_registry p"
                                + " LEFT JOIN plugin_finance_counterparty_revision v"
                                + " ON(v.tenant_id,v.legal_entity_id,v.id)=(p.tenant_id,p.legal_entity_id,p.approved_revision_id)"
                                + " WHERE p.tenant_id=? AND p.legal_entity_id=? AND p.id=?",
                            b.get("tenant_id"),
                            b.get("legal_entity_id"),
                            r.get("supplier_id"));
            supplierStatus = z.get("desired_status");
        } catch (org.springframework.web.server.ResponseStatusException denied) {
            if (denied.getStatusCode().value() != 403) throw denied;
            blockers.add(map("code", "SCOPE_DENIED", "message", "当前无供应商治理home读取范围；保留原批准快照"));
        }
        boolean reversal = r.get("producer_kind").equals("AP_EXPENSE_REVERSAL");
        boolean fresh = false;
        try {
            if (supplierStatus != null || reversal) {
                var z =
                        c.one(
                                "SELECT plugin_finance_ap_fresh(?,?,?,?) value",
                                b.get("tenant_id"),
                                b.get("id"),
                                y,
                                target);
                fresh = Boolean.TRUE.equals(z.get("value"));
            }
        } catch (FinanceException ex) {
            if (!Set.of(
                            "AP_SOURCE_STALE",
                            "AP_SUPPLIER_STALE",
                            "AP_BASIS_STALE",
                            "AP_BASIS_PENDING",
                            "AP_PENDING_PARENT_REVERSAL",
                            "AP_PARENT_IN_USE",
                            "PERIOD_CLOSED")
                    .contains(ex.code)) throw ex;
            blockers.add(map("code", ex.code, "message", ex.getReason()));
        }
        var me = actor(c, b);
        var maker = json(r.get("prepared_by"));
        boolean mine = id(me.get("accountId")).equals(id(maker.get("accountId")));
        boolean independent = !samePerson(me, maker);
        var actions = new ArrayList<String>();
        String state = r.get("status").toString();
        var gates =
                c.one(
                        "SELECT y.status year_status,p.status period_status, (s.id IS NOT NULL AND"
                            + " s.expires_at>clock_timestamp()) submit_valid, (a.id IS NOT NULL AND"
                            + " a.expires_at>clock_timestamp()) approve_valid FROM"
                            + " plugin_finance_year y JOIN plugin_finance_period p"
                            + " ON(p.tenant_id,p.book_id,p.year_id)=(y.tenant_id,y.book_id,y.id)"
                            + " AND p.month=extract(month FROM ?::date)::integer LEFT JOIN"
                            + " plugin_finance_ap_submission s"
                            + " ON(s.tenant_id,s.book_id,s.year_id,s.id)"
                            + " =(y.tenant_id,y.book_id,y.id,?::uuid) LEFT JOIN"
                            + " plugin_finance_ap_approval a"
                            + " ON(a.tenant_id,a.book_id,a.year_id,a.id)"
                            + " =(y.tenant_id,y.book_id,y.id,?::uuid) WHERE y.tenant_id=? AND"
                            + " y.book_id=? AND y.id=?",
                        r.get("accounting_date").toString(),
                        r.get("current_submission_id"),
                        r.get("current_approval_id"),
                        b.get("tenant_id"),
                        b.get("id"),
                        y);
        boolean yearOpen = "OPEN".equals(gates.get("year_status"));
        boolean newUse = yearOpen && "OPEN".equals(gates.get("period_status"));
        if (state.equals("DRAFT")) {
            if (mine && allowed(b, "create")) {
                actions.add("CANCEL");
                if (fresh && newUse) actions.add("SUBMIT");
            }
            if (independent && allowed(b, "approve")) {
                actions.add("REJECT");
                actions.add("ADMIN_TERMINATE");
            }
        }
        if (state.equals("SUBMITTED")) {
            if (mine && allowed(b, "create")) actions.add("WITHDRAW");
            if (independent && allowed(b, "approve")) {
                actions.add("REJECT");
                if (fresh && newUse && Boolean.TRUE.equals(gates.get("submit_valid")))
                    actions.add("APPROVE");
                actions.add("ADMIN_TERMINATE");
            }
        }
        if (state.equals("APPROVED")) {
            if (independent && allowed(b, "approve")) actions.add("REJECT");
            if (fresh
                    && newUse
                    && Boolean.TRUE.equals(gates.get("submit_valid"))
                    && Boolean.TRUE.equals(gates.get("approve_valid"))
                    && allowed(b, "post")) actions.add("POST");
        }
        var summary = summary(c, b, y, r);
        if (state.equals("POSTED")
                && r.get("producer_kind").equals("AP_EXPENSE")
                && summary.get("postedReversalAttemptId") == null
                && summary.get("pendingReversalAttemptId") == null
                && newUse
                && allowed(b, "create")) actions.add("CREATE_FULL_REVERSAL");
        if (!yearOpen || !"AVAILABLE".equals(me.get("identityAvailability"))) actions.clear();
        var items =
                c.list(
                        "SELECT * FROM plugin_finance_ap_open_item WHERE tenant_id=? AND book_id=?"
                                + " AND year_id=? AND run_id=?",
                        b.get("tenant_id"),
                        b.get("id"),
                        y,
                        target);
        var history = history(c, principal, b, y, target, request);
        var source = json(r.get("source_snapshot"));
        v.putAll(
                map(
                        "payable",
                        summary,
                        "content",
                        json(r.get("content")),
                        "currentSourceSlot",
                        slot(c, b, y, r.get("parent_allocation_id"), source.get("chainId")),
                        "capacity",
                        capacity(c, b, y, r.get("document_id")),
                        "currentSupplierStatus",
                        supplierStatus,
                        "sourceFresh",
                        fresh && supplierStatus != null,
                        "currentSubmission",
                        submission(c, b, y, r),
                        "currentApproval",
                        approval(c, b, y, r),
                        "postReceipt",
                        postReceipt(c, b, y, r),
                        "openItem",
                        items.isEmpty() ? null : item(c, b, y, items.getFirst()),
                        "history",
                        history,
                        "allowedActions",
                        actions,
                        "blockers",
                        blockers));
        return v;
    }

    Map<String, Object> history(
            Call c,
            String principal,
            Map<String, Object> b,
            UUID y,
            UUID run,
            HttpServletRequest request) {
        var q =
                request == null
                        ? Map.<String, String>of()
                        : query(request, Set.of("historyLimit", "historyCursor"));
        int limit = 20;
        if (q.containsKey("historyLimit")) {
            String raw = q.get("historyLimit");
            if (!raw.matches("[1-9][0-9]{0,2}")) throw FinanceException.invalid("历史分页上限无效");
            limit = Integer.parseInt(raw);
            if (limit > 100) throw FinanceException.invalid("历史分页上限100");
        }
        var guard =
                c.one(
                        "SELECT epoch,generation FROM plugin_finance_ap_guard WHERE tenant_id=? AND"
                                + " book_id=? AND year_id=?",
                        b.get("tenant_id"),
                        b.get("id"),
                        y);
        var frame =
                map(
                        "resource",
                        "ap-command-history",
                        "tenantId",
                        b.get("tenant_id").toString(),
                        "bookId",
                        b.get("id").toString(),
                        "yearId",
                        y.toString(),
                        "runId",
                        run.toString(),
                        "actorId",
                        b.get("actor_id").toString(),
                        "limit",
                        limit,
                        "epoch",
                        guard.get("epoch").toString(),
                        "generation",
                        guard.get("generation").toString(),
                        "auth",
                        authorizationDigest(c, principal, b));
        Timestamp at = null;
        UUID anchor = null;
        if (q.containsKey("historyCursor")) {
            if (q.get("historyCursor").length() > 4096) throw FinanceException.invalid("游标过长");
            var old = untoken(q.get("historyCursor"));
            var keys = new HashSet<>(frame.keySet());
            keys.add("at");
            keys.add("anchor");
            if (!keys.equals(old.keySet())) throw FinanceException.invalid("历史游标结构无效");
            for (String key : frame.keySet())
                if (!Set.of("epoch", "generation", "auth").contains(key)
                        && !Objects.equals(frame.get(key), old.get(key)))
                    throw FinanceException.invalid("历史游标属于其他范围、对象或调用者");
            for (String key : List.of("epoch", "generation", "auth"))
                if (!Objects.equals(frame.get(key), old.get(key)))
                    throw error("AP_PAGE_STALE", "历史或授权变化，请重读");
            try {
                at = Timestamp.from(Instant.parse(old.get("at").toString()));
                anchor = id(old.get("anchor"));
            } catch (RuntimeException invalid) {
                throw FinanceException.invalid("历史游标位置无效");
            }
            if (c.list(
                            "SELECT id FROM plugin_finance_ap_command_receipt WHERE tenant_id=? AND"
                                + " book_id=? AND year_id=? AND run_id=? AND id=? AND created_at=?",
                            b.get("tenant_id"),
                            b.get("id"),
                            y,
                            run,
                            anchor,
                            at)
                    .isEmpty()) throw FinanceException.invalid("历史游标锚点不存在");
        }
        var args = new ArrayList<Object>(List.of(b.get("tenant_id"), b.get("id"), y, run));
        String sql =
                "SELECT id,created_at FROM plugin_finance_ap_command_receipt WHERE tenant_id=? AND"
                        + " book_id=? AND year_id=? AND run_id=?";
        if (at != null) {
            sql += " AND(created_at,id)<(?,?)";
            args.add(at);
            args.add(anchor);
        }
        sql += " ORDER BY created_at DESC,id DESC LIMIT ?";
        args.add(limit + 1);
        var rows = c.list(sql, args.toArray());
        var selected = rows.subList(0, Math.min(limit, rows.size()));
        String next = null;
        if (rows.size() > limit) {
            var last = selected.getLast();
            var value = new LinkedHashMap<>(frame);
            value.put("at", time(last.get("created_at")));
            value.put("anchor", last.get("id").toString());
            next = token(value);
        }
        return map(
                "items",
                selected.stream().map(x -> receipt(c, b, y, id(x.get("id")))).toList(),
                "nextCursor",
                next);
    }

    Map<String, Object> itemDetail(
            Call c, String principal, Map<String, Object> b, UUID y, UUID target) {
        var i =
                c.one(
                        "SELECT * FROM plugin_finance_ap_open_item WHERE tenant_id=? AND book_id=?"
                                + " AND year_id=? AND id=?",
                        b.get("tenant_id"),
                        b.get("id"),
                        y,
                        target);
        var v = base(c, b, y);
        var r = header(c, b, y, id(i.get("run_id")));
        var rev =
                c.list(
                        "SELECT r.* FROM plugin_finance_ap_reverse_effect e JOIN"
                            + " plugin_finance_ap_run r"
                            + " ON(r.tenant_id,r.book_id,r.year_id,r.id)=(e.tenant_id,e.book_id,e.year_id,e.run_id)"
                            + " WHERE e.tenant_id=? AND e.book_id=? AND e.year_id=? AND"
                            + " e.open_item_id=?",
                        b.get("tenant_id"),
                        b.get("id"),
                        y,
                        target);
        v.putAll(
                map(
                        "item",
                        item(c, b, y, i),
                        "originalPayable",
                        summary(c, b, y, r),
                        "postedReversal",
                        rev.isEmpty() ? null : summary(c, b, y, rev.getFirst()),
                        "blockers",
                        List.of()));
        return v;
    }

    Map<String, Object> producer(Call c, Map<String, Object> b, UUID y, UUID journal) {
        var j =
                c.one(
                        "SELECT type FROM plugin_finance_journal WHERE tenant_id=? AND book_id=?"
                                + " AND year_id=? AND id=?",
                        b.get("tenant_id"),
                        b.get("id"),
                        y,
                        journal);
        var r =
                c.list(
                        "SELECT id,producer_kind FROM plugin_finance_ap_run WHERE tenant_id=? AND"
                                + " book_id=? AND year_id=? AND journal_id=?",
                        b.get("tenant_id"),
                        b.get("id"),
                        y,
                        journal);
        return map(
                "schemaVersion",
                FinancePayablesAp01Input.SCHEMA,
                "scope",
                publicScope(c, b, y),
                "journalId",
                journal,
                "rawType",
                j.get("type"),
                "resolution",
                r.isEmpty() ? "NONE" : "FOUND",
                "payableId",
                r.isEmpty() ? null : r.getFirst().get("id"),
                "producerKind",
                r.isEmpty() ? null : r.getFirst().get("producer_kind"),
                "reason",
                r.isEmpty() ? "该凭证不是AP01 producer" : null);
    }

    Object page(
            Call c,
            String principal,
            Map<String, Object> b,
            UUID y,
            String op,
            HttpServletRequest request) {
        Set<String> allowed =
                op.equals("parents")
                        ? Set.of("limit", "cursor", "documentId")
                        : op.equals("basis-list")
                                ? Set.of("limit", "cursor", "documentId", "status")
                                : op.equals("open-items")
                                        ? Set.of("limit", "cursor", "counterpartyId", "status")
                                        : Set.of("limit", "cursor", "status");
        var q = query(request, allowed);
        int limit = 20;
        try {
            if (q.containsKey("limit")) {
                if (!q.get("limit").matches("[1-9][0-9]{0,2}"))
                    throw new IllegalArgumentException();
                limit = Integer.parseInt(q.get("limit"));
            }
            if (limit > 100) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw FinanceException.invalid("分页上限1至100");
        }
        String status = q.getOrDefault("status", "ALL");
        Set<String> states =
                op.equals("basis-list")
                        ? Set.of("ALL", "PENDING", "APPROVED", "REJECTED", "CANCELLED")
                        : op.equals("open-items")
                                ? Set.of("ALL", "OPEN", "ACCOUNTING_REVERSED")
                                : Set.of(
                                        "ALL",
                                        "DRAFT",
                                        "SUBMITTED",
                                        "APPROVED",
                                        "POSTED",
                                        "CANCELLED");
        if (!states.contains(status)) throw FinanceException.invalid("状态筛选无效");
        UUID document =
                q.containsKey("documentId")
                        ? FinancePayablesAp01Input.uuid(q.get("documentId"))
                        : null;
        UUID supplier =
                q.containsKey("counterpartyId")
                        ? FinancePayablesAp01Input.uuid(q.get("counterpartyId"))
                        : null;
        var g =
                c.one(
                        "SELECT epoch,generation FROM plugin_finance_ap_guard WHERE tenant_id=? AND"
                                + " book_id=? AND year_id=?",
                        b.get("tenant_id"),
                        b.get("id"),
                        y);
        var context =
                map(
                        "resource",
                        op,
                        "tenantId",
                        b.get("tenant_id").toString(),
                        "bookId",
                        b.get("id").toString(),
                        "yearId",
                        y.toString(),
                        "actorId",
                        b.get("actor_id").toString(),
                        "limit",
                        limit,
                        "status",
                        status,
                        "documentId",
                        document == null ? null : document.toString(),
                        "supplierId",
                        supplier == null ? null : supplier.toString(),
                        "epoch",
                        g.get("epoch").toString(),
                        "generation",
                        g.get("generation").toString(),
                        "auth",
                        authorizationDigest(c, principal, b));
        var sourceGuard =
                c.one(
                        "SELECT generation FROM plugin_finance_evidence_home_guard WHERE"
                                + " tenant_id=? AND book_id=? AND year_id=?",
                        b.get("tenant_id"),
                        b.get("id"),
                        y);
        context.put("sourceGeneration", count(sourceGuard.get("generation")));
        Timestamp at = null;
        UUID anchor = null;
        if (q.containsKey("cursor")) {
            var old = untoken(q.get("cursor"));
            var keys = new HashSet<>(context.keySet());
            keys.add("at");
            keys.add("anchor");
            if (!keys.equals(old.keySet())) throw FinanceException.invalid("游标结构无效");
            for (String key : context.keySet())
                if (!Set.of("epoch", "generation", "auth", "sourceGeneration").contains(key)
                        && !Objects.equals(context.get(key), old.get(key)))
                    throw FinanceException.invalid("游标属于其他调用者/范围/筛选");
            for (String key : List.of("epoch", "generation", "auth", "sourceGeneration"))
                if (!Objects.equals(context.get(key), old.get(key)))
                    throw error("AP_PAGE_STALE", "应付目录或授权变化，请从首屏重读");
            try {
                at = Timestamp.from(Instant.parse(old.get("at").toString()));
                anchor = id(old.get("anchor"));
            } catch (RuntimeException bad) {
                throw FinanceException.invalid("游标位置无效");
            }
        }
        String table =
                op.equals("parents")
                        ? "plugin_finance_evidence_allocation"
                        : op.equals("basis-list")
                                ? "plugin_finance_ap_basis"
                                : op.equals("open-items")
                                        ? "plugin_finance_ap_open_item"
                                        : "plugin_finance_ap_run";
        String sql = "SELECT * FROM " + table + " WHERE tenant_id=? AND book_id=? AND year_id=?";
        var args = new ArrayList<Object>(List.of(b.get("tenant_id"), b.get("id"), y));
        if (document != null) {
            sql += " AND document_id=?";
            args.add(document);
        }
        if (supplier != null) {
            sql += " AND supplier_id=?";
            args.add(supplier);
        }
        if (!status.equals("ALL")) {
            if (op.equals("open-items"))
                sql +=
                        status.equals("OPEN")
                                ? " AND reverse_post_id IS NULL"
                                : " AND reverse_post_id IS NOT NULL";
            else {
                sql += " AND status=?";
                args.add(status);
            }
        }
        if (op.equals("parents")) sql += " AND status='CONFIRMED'";
        if (at != null) {
            var positionArgs = new ArrayList<Object>(args);
            positionArgs.add(anchor);
            positionArgs.add(at);
            if (c.list(
                            sql.replace("SELECT *", "SELECT id") + " AND id=? AND created_at=?",
                            positionArgs.toArray())
                    .isEmpty()) throw FinanceException.invalid("游标锚点不属于当前筛选");
            sql += " AND(created_at,id)<(?,?)";
            args.add(at);
            args.add(anchor);
        }
        sql += " ORDER BY created_at DESC,id DESC LIMIT ?";
        args.add(limit + 1);
        var rows = c.list(sql, args.toArray());
        boolean more = rows.size() > limit;
        var selected = rows.subList(0, Math.min(limit, rows.size()));
        String next = null;
        if (more) {
            var last = selected.getLast();
            var token = new LinkedHashMap<>(context);
            token.put("at", time(last.get("created_at")));
            token.put("anchor", last.get("id").toString());
            next = token(token);
        }
        var items = new ArrayList<Object>();
        for (var r : selected) {
            if (op.equals("basis-list")) items.add(basisSummary(r));
            else if (op.equals("open-items")) items.add(item(c, b, y, r));
            else if (op.equals("parents")) {
                var parent =
                        json(
                                c.one(
                                                "SELECT plugin_finance_ap_parent(?,?,?,?)::text"
                                                        + " value",
                                                b.get("tenant_id"),
                                                b.get("id"),
                                                y,
                                                r.get("id"))
                                        .get("value"));
                var cap = capacity(c, b, y, r.get("document_id"));
                boolean eligible =
                        open(c, b, y)
                                && r.get("pending_reverse_id") == null
                                && new BigDecimal(parent.get("netConfirmedAmount").toString())
                                                .signum()
                                        > 0
                                && cap.get("currentBasis") != null
                                && cap.get("pendingBasisId") == null
                                && "AVAILABLE".equals(actor(c, b).get("identityAvailability"));
                items.add(
                        map(
                                "parent",
                                parent,
                                "sourceSlot",
                                slot(c, b, y, r.get("id"), r.get("chain_id")),
                                "purposeOriginal",
                                originalFromExpense(json(r.get("purpose_original_snapshot"))),
                                "capacity",
                                cap,
                                "pendingParentReverseId",
                                r.get("pending_reverse_id"),
                                "eligibleForPreparation",
                                eligible,
                                "blockers",
                                eligible
                                        ? List.of()
                                        : List.of(
                                                map(
                                                        "code",
                                                        "AP_SOURCE_STALE",
                                                        "message",
                                                        "净份额、会计基准、人员或年度当前不支持新用；保留真实候选"))));
            } else items.add(summary(c, b, y, r));
        }
        var v = base(c, b, y);
        v.putAll(
                map(
                        "epoch",
                        g.get("epoch"),
                        "generation",
                        count(g.get("generation")),
                        "limit",
                        limit,
                        "items",
                        items,
                        "nextCursor",
                        next,
                        "total",
                        null,
                        "totalStatus",
                        "NOT_COMPUTED"));
        return v;
    }

    Map<String, Object> originalFromExpense(Map<String, Object> o) {
        return map(
                "bookId",
                o.get("bookId"),
                "yearId",
                o.get("yearId"),
                "id",
                o.get("originalId"),
                "version",
                o.get("version"),
                "filename",
                o.get("filename"),
                "sha256",
                o.get("sha256"),
                "createdBy",
                o.get("createdBy"),
                "authenticity",
                "UNVERIFIED");
    }

    Map<String, Object> reconciliation(Call c, Map<String, Object> b, UUID y) {
        var sums =
                c.one(
                        "SELECT coalesce(sum(amount),0)::text principal,coalesce(sum(amount)"
                            + " FILTER(WHERE reverse_post_id IS NOT NULL),0)::text"
                            + " reversed,coalesce(sum(amount) FILTER(WHERE reverse_post_id IS"
                            + " NULL),0)::text outstanding FROM plugin_finance_ap_open_item WHERE"
                            + " tenant_id=? AND book_id=? AND year_id=?",
                        b.get("tenant_id"),
                        b.get("id"),
                        y);
        var gl =
                c.one(
                        "WITH observed AS(SELECT DISTINCT (l->'account'->>'id')::uuid id FROM"
                            + " plugin_finance_ap_run r CROSS JOIN LATERAL"
                            + " jsonb_array_elements(r.content->'generatedLines') l WHERE"
                            + " r.tenant_id=? AND r.book_id=? AND r.year_id=? AND"
                            + " l->'account'->>'category'='LIABILITY'),facts AS(SELECT"
                            + " l.credit-l.debit n,ap.id typed FROM plugin_finance_line l JOIN"
                            + " plugin_finance_journal j"
                            + " ON(j.tenant_id,j.book_id,j.year_id,j.id)=(l.tenant_id,l.book_id,l.year_id,l.journal_id)"
                            + " JOIN observed o ON o.id=l.account_id LEFT JOIN"
                            + " plugin_finance_ap_run ap"
                            + " ON(ap.tenant_id,ap.book_id,ap.year_id,ap.journal_id)=(j.tenant_id,j.book_id,j.year_id,j.id)"
                            + " WHERE j.tenant_id=? AND j.book_id=? AND j.year_id=? AND"
                            + " j.status='POSTED') SELECT coalesce(sum(n) FILTER(WHERE typed IS NOT"
                            + " NULL),0)::text typed,coalesce(sum(n) FILTER(WHERE typed IS"
                            + " NULL),0)::text untyped,(SELECT count(*) FROM observed) accounts"
                            + " FROM facts",
                        b.get("tenant_id"),
                        b.get("id"),
                        y,
                        b.get("tenant_id"),
                        b.get("id"),
                        y);
        if (((Number) gl.get("accounts")).longValue() > 5000)
            throw error("AP_LIMIT_EXCEEDED", "对账观察的应付科目超过5000上限");
        String principal = representable(sums.get("principal")),
                reversed = representable(sums.get("reversed")),
                outstanding = representable(sums.get("outstanding")),
                typed = representable(gl.get("typed")),
                untyped = representable(gl.get("untyped"));
        boolean known =
                principal != null
                        && reversed != null
                        && outstanding != null
                        && typed != null
                        && untyped != null;
        String difference =
                known
                        ? representable(new BigDecimal(outstanding).subtract(new BigDecimal(typed)))
                        : null;
        known = known && difference != null;
        var v = base(c, b, y);
        v.putAll(
                map(
                        "qualification",
                        known ? "AP_PRODUCER_SUBSET_ONLY" : "UNSUPPORTED_DATA",
                        "principal",
                        known ? principal : null,
                        "accountingReversed",
                        known ? reversed : null,
                        "systemOutstanding",
                        known ? outstanding : null,
                        "typedApGlNetCredit",
                        known ? typed : null,
                        "openItemDifference",
                        known ? difference : null,
                        "unattributedPostedNetOnObservedPayableAccounts",
                        known ? untyped : null,
                        "observedLiabilityAccountCount",
                        gl.get("accounts"),
                        "externalSettlementAmount",
                        null,
                        "completeEnterpriseSubledger",
                        false,
                        "canExecuteBase",
                        false,
                        "blockers",
                        List.of(
                                map(
                                        "code",
                                        "AP_SOURCE_UNSUPPORTED",
                                        "message",
                                        "仅本AP producer贡献对账；外部结算及完整企业往来尚未实现"))));
        return v;
    }

    static String representable(Object value) {
        if (value == null) return null;
        try {
            var n =
                    new BigDecimal(value.toString())
                            .setScale(8, java.math.RoundingMode.UNNECESSARY);
            if (n.precision() - n.scale() > 20) return null;
            return n.toPlainString();
        } catch (NumberFormatException | ArithmeticException invalid) {
            return null;
        }
    }
}
