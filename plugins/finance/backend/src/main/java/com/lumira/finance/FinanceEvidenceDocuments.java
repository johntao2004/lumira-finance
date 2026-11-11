package com.lumira.finance;

import com.lumira.platform.common.AuthorizationContextProjectionPort;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.util.*;

/** Permanent single-document registry and independently reviewed organization expense capacity. */
@Service
public class FinanceEvidenceDocuments extends FinanceEvidenceAccess {
    static final List<String> LIMITATIONS =
            List.of(
                    "组织人工复核金额依据，不代表政府验真、税务抵扣或费用已报销、付款、入账",
                    "只支持单文件单普通费用单据、CNY及原账套年度引用；永久同法人查重不允许重新上传重置额度",
                    "三个用途分别计量；首片只启用费用归属份额，其余用途额度未知且不自动消费",
                    "旧凭证附件仍是reference-only，本协议不改其canonical、审签或原有wire");

    @Autowired
    public FinanceEvidenceDocuments(
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
                        case "catalog" -> catalog(b, year);
                        case "list" ->
                                page(
                                        principal,
                                        b,
                                        year,
                                        "documents",
                                        request,
                                        r -> summary(b, year, r));
                        case "detail" -> detail(b, year, target);
                        case "review-detail" -> review(b, year, target, child);
                        default -> throw FinanceException.invalid("单据读取操作无效");
                    };
                });
    }

    Map<String, Object> catalog(Map<String, Object> b, UUID year) {
        var who = actor(b);
        boolean eligible = openRead(b, year) && "AVAILABLE".equals(who.get("identityAvailability"));
        return map(
                "schemaVersion",
                FinanceEvidenceInput.SCHEMA,
                "tenantId",
                b.get("tenant_id"),
                "legalEntityId",
                b.get("legal_entity_id"),
                "bookId",
                b.get("id"),
                "yearId",
                year,
                "journalMode",
                modeRead(b, year).get("mode"),
                "currentActor",
                who,
                "supportedDocumentType",
                "ORDINARY_EXPENSE_INVOICE",
                "sourceSystem",
                FinanceEvidenceInput.SYSTEM,
                "supportedConsumptionPurpose",
                FinanceEvidenceInput.PURPOSE,
                "unsupportedConsumptionPurposes",
                List.of("SETTLEMENT", "ACCOUNTING_CONFIRMATION", "TAX_EVIDENCE"),
                "crossHomeScopeSupported",
                false,
                "governmentAuthenticityVerified",
                false,
                "bankRefundOrGlCorrectionSupported",
                false,
                "eligibleForNewUse",
                eligible,
                "blockers",
                eligible
                        ? List.of()
                        : List.of(
                                map(
                                        "code",
                                        "NEW_USE_UNAVAILABLE",
                                        "message",
                                        "新用须V4人员协议开放年度及当前有效人员绑定；具体动作仍分别校验岗位与权限")));
    }

    Map<String, Object> summary(Map<String, Object> b, UUID year, Map<String, Object> doc) {
        Map<String, Object> current =
                doc.get("current_review_id") == null
                        ? null
                        : basis(
                                b,
                                year,
                                row("review", b, year, (UUID) doc.get("current_review_id")));
        var fields = current == null ? json(doc.get("registered_fields")) : current;
        var sums =
                db.one(
                        "SELECT COALESCE(SUM(amount) FILTER(WHERE state='RESERVED'),0) reserved,"
                            + " COALESCE(SUM(amount) FILTER(WHERE state='COMMITTED'),0) -(SELECT"
                            + " COALESCE(SUM(amount),0) FROM plugin_finance_evidence_reverse_effect"
                            + " WHERE tenant_id=? AND book_id=? AND year_id=? AND document_id=?)"
                            + " net FROM plugin_finance_evidence_usage WHERE tenant_id=? AND"
                            + " book_id=? AND year_id=? AND document_id=?",
                        b.get("tenant_id"),
                        b.get("id"),
                        year,
                        doc.get("id"),
                        b.get("tenant_id"),
                        b.get("id"),
                        year,
                        doc.get("id"));
        BigDecimal reserved = new BigDecimal(sums.get("reserved").toString()),
                net = new BigDecimal(sums.get("net").toString());
        BigDecimal capacity =
                current == null ? null : new BigDecimal(current.get("expenseCapacity").toString());
        String bucketVersion =
                count(
                        db.one(
                                        "SELECT generation FROM plugin_finance_evidence_home_guard"
                                            + " WHERE tenant_id=? AND book_id=? AND year_id=?",
                                        b.get("tenant_id"),
                                        b.get("id"),
                                        year)
                                .get("generation"));
        var buckets = new ArrayList<Map<String, Object>>();
        buckets.add(
                map(
                        "purpose",
                        "EXPENSE_ALLOCATION",
                        "consumptionSupported",
                        true,
                        "qualification",
                        current == null ? "UNKNOWN" : "ORG_REVIEWED",
                        "currency",
                        "CNY",
                        "capacity",
                        capacity == null ? null : money8(capacity),
                        "reserved",
                        money8(reserved),
                        "netCommitted",
                        money8(net),
                        "available",
                        capacity == null ? null : money8(capacity.subtract(reserved).subtract(net)),
                        "version",
                        bucketVersion));
        for (String purpose : List.of("SETTLEMENT", "ACCOUNTING_CONFIRMATION"))
            buckets.add(
                    map(
                            "purpose",
                            purpose,
                            "consumptionSupported",
                            false,
                            "qualification",
                            "UNSUPPORTED",
                            "currency",
                            "CNY",
                            "capacity",
                            null,
                            "reserved",
                            null,
                            "netCommitted",
                            null,
                            "available",
                            null,
                            "version",
                            bucketVersion));
        var actions = new ArrayList<String>();
        boolean available = "AVAILABLE".equals(actor(b).get("identityAvailability"));
        if (available && openRead(b, year) && doc.get("pending_review_id") == null) {
            if (allows(b, "manage") && allows(b, "create")) actions.add("create-review");
            if (current != null && allows(b, "create")) actions.add("create-allocation");
        }
        return map(
                "id",
                doc.get("id"),
                "tenantId",
                b.get("tenant_id"),
                "legalEntityId",
                b.get("legal_entity_id"),
                "homeBookId",
                b.get("id"),
                "homeYearId",
                year,
                "version",
                count(doc.get("version")),
                "sourceOriginal",
                json(doc.get("source_snapshot")),
                "currentBasis",
                current,
                "pendingReviewProposalId",
                doc.get("pending_review_id"),
                "latestReviewProposalId",
                doc.get("latest_review_id"),
                "newUseFrozen",
                doc.get("pending_review_id") != null,
                "authenticity",
                "UNVERIFIED",
                "buckets",
                buckets,
                "allowedActions",
                actions,
                "blockers",
                List.of(),
                "sourceType",
                fields.get("sourceType"),
                "originalLocation",
                fields.get("originalLocation"));
    }

    Map<String, Object> detail(Map<String, Object> b, UUID year, UUID id) {
        var doc = row("document", b, year, id);
        var aliases =
                db.list(
                        "SELECT document_type AS \"documentType\",issuer_key AS"
                            + " \"issuerKey\",external_identity_key AS"
                            + " \"externalIdentityKey\",created_at AS \"recordedAt\" FROM"
                            + " plugin_finance_evidence_identity_alias WHERE tenant_id=? AND"
                            + " legal_entity_id=? AND document_id=? ORDER BY"
                            + " created_at,issuer_key,external_identity_key LIMIT 101",
                        b.get("tenant_id"),
                        b.get("legal_entity_id"),
                        id);
        return map(
                "document",
                summary(b, year, doc),
                "currentActor",
                actor(b),
                "identityAliases",
                aliases.subList(0, Math.min(100, aliases.size())),
                "aliasesTruncated",
                aliases.size() > 100,
                "limitations",
                LIMITATIONS);
    }

    Map<String, Object> review(Map<String, Object> b, UUID year, UUID document, UUID id) {
        var r = row("review", b, year, id);
        if (!Objects.equals(document, r.get("document_id"))) throw error("SCOPE_DENIED", "范围不可访问");
        var doc = row("document", b, year, document);
        var maker = json(r.get("prepared_by"));
        var actions = new ArrayList<String>();
        var who = actor(b);
        if (pending(r) && "AVAILABLE".equals(who.get("identityAvailability"))) {
            if (allows(b, "approve") && !samePerson(b, maker)) {
                actions.add("reject-review");
                if (fresh(r) && openRead(b, year)) actions.add("approve-review");
            }
            if (allows(b, "manage")
                    && allows(b, "create")
                    && b.get("actor_id").toString().equals(maker.get("accountId")))
                actions.add("cancel-review");
        }
        return map(
                "id",
                r.get("id"),
                "documentId",
                document,
                "status",
                r.get("status"),
                "version",
                count(r.get("version")),
                "contentDigest",
                r.get("content_digest"),
                "previousBasisVersion",
                count(r.get("previous_basis_version")),
                "fields",
                json(r.get("content")),
                "sourceOriginal",
                json(doc.get("source_snapshot")),
                "basisOriginal",
                json(r.get("basis_original_snapshot")),
                "preparedBy",
                maker,
                "createdAt",
                r.get("created_at"),
                "expiresAt",
                r.get("expires_at"),
                "decidedBy",
                r.get("decided_by") == null ? null : json(r.get("decided_by")),
                "decidedAt",
                r.get("decided_at"),
                "decisionReason",
                r.get("decision_reason"),
                "publishedBasisVersion",
                count(r.get("published_basis_version")),
                "allowedActions",
                actions,
                "blockers",
                List.of(),
                "previousReviewProposalId",
                r.get("previous_review_id"));
    }

    void collisions(String principal, Map<String, Object> b, UUID year, Map<String, Object> n) {
        if (!(n.get("fields") instanceof Map<?, ?> fields)) return;
        var hits =
                new ArrayList<>(
                        db.list(
                                "SELECT d.book_id,d.year_id FROM"
                                    + " plugin_finance_evidence_identity_alias a JOIN"
                                    + " plugin_finance_evidence_document d"
                                    + " ON(d.tenant_id,d.legal_entity_id,d.id)=(a.tenant_id,a.legal_entity_id,a.document_id)"
                                    + " WHERE a.tenant_id=? AND a.legal_entity_id=? AND"
                                    + " a.document_type=? AND a.issuer_key=? AND"
                                    + " a.external_identity_key=?",
                                b.get("tenant_id"),
                                b.get("legal_entity_id"),
                                fields.get("documentType"),
                                fields.get("issuerKey"),
                                fields.get("externalIdentityKey")));
        if (n.containsKey("originalId")) {
            var o = original(b, year, n.get("originalId"));
            hits.addAll(
                    db.list(
                            "SELECT d.book_id,d.year_id FROM plugin_finance_evidence_hash_alias a"
                                + " JOIN plugin_finance_evidence_document d"
                                + " ON(d.tenant_id,d.legal_entity_id,d.id)=(a.tenant_id,a.legal_entity_id,a.document_id)"
                                + " WHERE a.tenant_id=? AND a.legal_entity_id=? AND a.sha256=?",
                            b.get("tenant_id"),
                            b.get("legal_entity_id"),
                            o.get("sha256")));
        }
        for (var hit : hits)
            homeCollision(principal, b, year, (UUID) hit.get("book_id"), (UUID) hit.get("year_id"));
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
                    if (op.equals("review-create")) row("document", b, year, target);
                    if (op.startsWith("review-") && !op.equals("review-create")) {
                        var r = row("review", b, year, child);
                        if (!Objects.equals(target, r.get("document_id")))
                            throw error("SCOPE_DENIED", "范围不可访问");
                        var maker = json(r.get("prepared_by"));
                        if (op.equals("review-cancel")) sameAccount(who, maker);
                        else independent(who, maker);
                        if (op.equals("review-approve")) recheckMaker(b, year, maker, true);
                    }
                    String route = "document/" + op + "/" + target + "/" + child;
                    var result = cached(b, year, route, n);
                    if (result == null) {
                        collisions(principal, b, year, n);
                        if (op.equals("review-approve"))
                            collisions(
                                    principal,
                                    b,
                                    year,
                                    map(
                                            "fields",
                                            json(row("review", b, year, child).get("content"))));
                        String sqlOp =
                                switch (op) {
                                    case "register" -> "REGISTER";
                                    case "review-create" -> "REVIEW_PROPOSE";
                                    case "review-approve" -> "REVIEW_APPROVE";
                                    case "review-reject" -> "REVIEW_REJECT";
                                    case "review-cancel" -> "REVIEW_CANCEL";
                                    default -> throw FinanceException.invalid("单据写操作无效");
                                };
                        result = command("document", b, year, who, sqlOp, target, child, n);
                        remember(b, year, route, n, result);
                    }
                    UUID doc = FinanceEvidenceInput.uuid(result.get("documentId"));
                    return result.get("kind").equals("REVIEW")
                            ? review(b, year, doc, FinanceEvidenceInput.uuid(result.get("id")))
                            : detail(b, year, doc);
                });
    }
}
