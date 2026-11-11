package com.lumira.finance;

import com.lumira.platform.common.AuthorizationContextProjectionPort;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.*;

abstract class FinanceTaxEntityProjection extends FinanceTaxEntityAccess {
    static final String SELECT =
            "SELECT p.*,CASE WHEN a.id IS NULL THEN NULL ELSE to_jsonb(a)::text END approved FROM"
                + " plugin_finance_tax_entity_registry p LEFT JOIN"
                + " plugin_finance_tax_entity_revision a"
                + " ON(a.tenant_id,a.legal_entity_id,a.tax_entity_id,a.id)=(p.tenant_id,p.legal_entity_id,p.id,p.approved_revision_id)"
                + " ";

    FinanceTaxEntityProjection(
            FinanceStore db,
            FinanceService finance,
            FinanceJournalPersonGate gate,
            PlatformTransactionManager tx,
            AuthorizationContextProjectionPort auth) {
        super(db, finance, gate, tx, auth);
    }

    Map<String, Object> identity(Map<String, Object> p) {
        return map(
                "country",
                "CN",
                "canonicalTaxRegistrationNo",
                p.get("registration_no"),
                "verification",
                "ORGANIZATION_DECLARED_NOT_GOVERNMENT_VERIFIED");
    }

    Map<String, Object> ref(Map<String, Object> r) {
        return map(
                "id",
                r.get("id"),
                "revision",
                count(r.get("revision_no")),
                "version",
                count(r.get("version")),
                "status",
                r.get("status"),
                "desiredStatus",
                r.get("desired_status"),
                "previousRevisionId",
                r.get("previous_revision_id"),
                "baseApprovedRevisionId",
                r.get("base_approved_revision_id"),
                "contentDigest",
                r.get("content_digest"),
                "preparedBy",
                json(r.get("maker")),
                "createdAt",
                r.get("created_at"));
    }

    Map<String, Object> summary(Map<String, Object> p) {
        var a = p.get("approved") == null ? null : json(p.get("approved"));
        return map(
                "id",
                p.get("id"),
                "governanceHomeBookId",
                p.get("home_book_id"),
                "identity",
                identity(p),
                "version",
                count(p.get("version")),
                "effectiveStatus",
                a == null ? "UNPUBLISHED" : a.get("desired_status"),
                "effectiveDisplayName",
                a == null ? null : json(a.get("content")).get("displayName"),
                "approvedRevision",
                a == null ? null : ref(a),
                "pendingRevisionId",
                p.get("pending_revision_id"),
                "latestRevisionId",
                p.get("latest_revision_id"),
                "masterDataEligible",
                a != null
                        && "ACTIVE".equals(a.get("desired_status"))
                        && p.get("pending_revision_id") == null,
                "financialSourceMappingImplemented",
                false,
                "governmentVerification",
                "NOT_VERIFIED",
                "taxRulesReview",
                "UNREVIEWED",
                "taxEligibilityReview",
                "UNREVIEWED",
                "officialFilingCapability",
                "NOT_VERIFIED",
                "createdBy",
                json(p.get("created_by")).get("accountId"),
                "createdAt",
                p.get("created_at"));
    }

    Map<String, Object> full(Call c, Map<String, Object> b, UUID target) {
        return c.one(
                SELECT + " WHERE p.tenant_id=? AND p.legal_entity_id=? AND p.id=?",
                b.get("tenant_id"),
                b.get("legal_entity_id"),
                target);
    }

    Map<String, Object> publicScope(Map<String, Object> b, UUID home) {
        return map(
                "tenantId",
                b.get("tenant_id"),
                "legalEntityId",
                b.get("legal_entity_id"),
                "currentBookId",
                b.get("id"),
                "governanceHomeBookId",
                home,
                "bothBooksReadAuthorized",
                true);
    }

    void contentRead(
            Call c,
            String principal,
            Map<String, Object> b,
            Map<String, Object> p,
            Map<String, Object> r) {
        evidenceRead(
                c,
                principal,
                b,
                id(p.get("home_book_id")),
                json(json(r.get("content")).get("registrationEvidence")));
    }

    Map<String, Object> receipt(Call c, Map<String, Object> b, UUID receipt) {
        var x =
                c.one(
                        "SELECT * FROM plugin_finance_tax_entity_command_receipt WHERE tenant_id=?"
                            + " AND legal_entity_id=? AND id=?",
                        b.get("tenant_id"),
                        b.get("legal_entity_id"),
                        receipt);
        return map(
                "id",
                x.get("id"),
                "taxEntityId",
                x.get("tax_entity_id"),
                "revisionId",
                x.get("revision_id"),
                "action",
                x.get("action"),
                "targetVersion",
                count(x.get("target_version")),
                "contentDigest",
                x.get("content_digest"),
                "actor",
                json(x.get("actor")),
                "reason",
                x.get("reason"),
                "createdAt",
                x.get("created_at"));
    }

    Map<String, Object> decision(Call c, Map<String, Object> b, Map<String, Object> r) {
        if (r.get("decision_receipt_id") == null) return null;
        var x = receipt(c, b, id(r.get("decision_receipt_id")));
        return map(
                "actor",
                x.get("actor"),
                "reason",
                x.get("reason"),
                "decidedAt",
                x.get("createdAt"),
                "outcome",
                r.get("status"));
    }

    Map<String, Object> actions(
            Call c,
            String principal,
            Map<String, Object> b,
            Map<String, Object> p,
            Map<String, Object> r,
            Map<String, Object> a,
            HttpServletRequest request) {
        var acts = new ArrayList<String>();
        var blocks = new ArrayList<Map<String, Object>>();
        boolean home = id(b.get("id")).equals(id(p.get("home_book_id"))),
                available = "AVAILABLE".equals(a.get("identityAvailability")),
                recent = false;
        if (!home) blocks.add(map("code", "SCOPE_DENIED", "message", "写入仅在永久治理账套进行"));
        if (!available) blocks.add(map("code", "SCOPE_DENIED", "message", "写入需当前有效组织人员绑定"));
        if (home && available)
            try {
                who(c, b, request);
                recent = true;
            } catch (FinanceException ex) {
                if (!Set.of("PERSON_AUTH_ASSURANCE_REQUIRED", "SCOPE_DENIED").contains(ex.code))
                    throw ex;
                blocks.add(map("code", ex.code, "message", "写入需当前近期TOTP登录会话，不是逐动作step-up"));
            }
        if (home && available && recent) {
            boolean owner =
                    "OWNER".equals(b.get("role"))
                            && allowed(b, "read")
                            && allowed(b, "manage")
                            && allowed(b, "create");
            boolean review =
                    Set.of("OWNER", "REVIEWER").contains(b.get("role"))
                            && allowed(b, "read")
                            && allowed(b, "approve");
            if (owner && p.get("pending_revision_id") == null) acts.add("PROPOSE_REVISION");
            if (r != null && "PENDING".equals(r.get("status"))) {
                var maker = json(r.get("maker"));
                if (owner && id(a.get("accountId")).equals(id(maker.get("accountId"))))
                    acts.add("CANCEL");
                if (review && !samePerson(a, maker)) {
                    acts.add("REJECT");
                    try {
                        recheckMaker(c, b, r);
                        acts.add("APPROVE");
                    } catch (FinanceException ex) {
                        if (!ex.code.equals("TAX_ENTITY_APPROVAL_STALE")) throw ex;
                        blocks.add(map("code", ex.code, "message", "原制备者当前资格失效；独立驳回仍可终结待审"));
                    }
                }
            }
        }
        return map("allowedActions", acts, "blockers", blocks);
    }

    Map<String, Object> detail(
            Call c,
            String principal,
            Map<String, Object> b,
            UUID target,
            HttpServletRequest request) {
        var p = full(c, b, target);
        homeRead(c, principal, b, p);
        var a = actor(c, b);
        Map<String, Object> r =
                p.get("pending_revision_id") == null
                        ? null
                        : revision(c, p, id(p.get("pending_revision_id")));
        if (r != null) contentRead(c, principal, b, p, r);
        Map<String, Object> effective = p.get("approved") == null ? null : json(p.get("approved"));
        if (effective != null) contentRead(c, principal, b, p, effective);
        var act = actions(c, principal, b, p, r, a, request);
        return map(
                "schemaVersion",
                FinanceTaxEntityInput.SCHEMA,
                "scope",
                publicScope(b, id(p.get("home_book_id"))),
                "currentActor",
                a,
                "taxEntity",
                summary(p),
                "effectiveContent",
                effective == null ? null : json(effective.get("content")),
                "allowedActions",
                act.get("allowedActions"),
                "blockers",
                act.get("blockers"));
    }

    Map<String, Object> revisionDetail(
            Call c,
            String principal,
            Map<String, Object> b,
            UUID target,
            UUID revision,
            HttpServletRequest request) {
        var p = full(c, b, target);
        homeRead(c, principal, b, p);
        var r = revision(c, p, revision);
        contentRead(c, principal, b, p, r);
        var a = actor(c, b);
        var act = actions(c, principal, b, p, r, a, request);
        return map(
                "schemaVersion",
                FinanceTaxEntityInput.SCHEMA,
                "scope",
                publicScope(b, id(p.get("home_book_id"))),
                "currentActor",
                a,
                "taxEntity",
                summary(p),
                "revision",
                ref(r),
                "identity",
                identity(p),
                "content",
                json(r.get("content")),
                "decision",
                decision(c, b, r),
                "allowedActions",
                act.get("allowedActions"),
                "blockers",
                act.get("blockers"));
    }

    Map<String, Object> catalog(Call c, Map<String, Object> b, HttpServletRequest request) {
        var a = actor(c, b);
        var acts = new ArrayList<String>();
        if ("OWNER".equals(b.get("role"))
                && allowed(b, "manage")
                && allowed(b, "create")
                && "AVAILABLE".equals(a.get("identityAvailability")))
            try {
                who(c, b, request);
                acts.add("CREATE");
            } catch (FinanceException ex) {
                if (!Set.of("PERSON_AUTH_ASSURANCE_REQUIRED", "SCOPE_DENIED").contains(ex.code))
                    throw ex;
            }
        return map(
                "schemaVersion",
                FinanceTaxEntityInput.SCHEMA,
                "scope",
                publicScope(b, id(b.get("id"))),
                "currentActor",
                a,
                "registrationNamespace",
                "ORGANIZATION_DECLARED_TAX_REGISTRATION",
                "country",
                "CN",
                "identityPermanentTenantWide",
                true,
                "registrationImplemented",
                true,
                "taxCalculationImplemented",
                false,
                "taxObligationsImplemented",
                false,
                "governmentVerification",
                "NOT_VERIFIED",
                "taxRulesReview",
                "UNREVIEWED",
                "officialFilingCapability",
                "NOT_VERIFIED",
                "taxAmount",
                null,
                "taxAmountState",
                "NOT_ASSESSED",
                "allowedActions",
                acts,
                "limitations",
                List.of(
                        "仅组织人工登记，不是政府验真或完整TAX01政策规则库。",
                        "ACTIVE仅本地登记档案可选，不证明税率、优惠、申报或缴款资格。",
                        "列表仅当前治理账套；同法人其它账套已知ID读取仍需双账套及原件年度授权。",
                        "历史登记和原件完整保留；停用恢复不是税局注销或恢复。"),
                "taxEligibilityImplemented",
                false,
                "taxFilingImplemented",
                false,
                "taxPaymentImplemented",
                false,
                "officialVerificationImplemented",
                false);
    }

    record Query(int limit, String cursor, String status) {}

    Query query(String op, HttpServletRequest request) {
        var q =
                FinanceEvidenceAccess.query(
                        request,
                        op.equals("list") ? Set.of("limit", "cursor", "status") : Set.of());
        int limit = 20;
        if (q.containsKey("limit")) {
            String s = q.get("limit");
            if (!s.matches("[1-9][0-9]{0,2}") || (limit = Integer.parseInt(s)) > 100)
                throw FinanceException.invalid("分页数量须1..100");
        }
        String status = q.getOrDefault("status", "ALL");
        if (!Set.of("ALL", "UNPUBLISHED", "ACTIVE", "INACTIVE").contains(status))
            throw FinanceException.invalid("状态筛选无效");
        String cursor = q.get("cursor");
        if (cursor != null && (cursor.isEmpty() || cursor.length() > 4096))
            throw FinanceException.invalid("分页token无效");
        return new Query(limit, cursor, status);
    }

    Map<String, Object> page(Call c, String principal, Map<String, Object> b, Query q) {
        var g = guard(c, b, id(b.get("id")));
        var a = actor(c, b);
        var frame =
                map(
                        "schema",
                        FinanceTaxEntityInput.SCHEMA,
                        "resource",
                        "list",
                        "tenant",
                        b.get("tenant_id").toString(),
                        "entity",
                        b.get("legal_entity_id").toString(),
                        "book",
                        b.get("id").toString(),
                        "home",
                        b.get("id").toString(),
                        "actor",
                        b.get("actor_id").toString(),
                        "status",
                        q.status,
                        "limit",
                        q.limit,
                        "epoch",
                        g.get("epoch").toString(),
                        "generation",
                        g.get("generation"),
                        "auth",
                        authorizationDigest(c, principal, b));
        Map<String, Object> cursor = null;
        if (q.cursor != null) {
            cursor = untoken(q.cursor);
            var keys = new HashSet<>(frame.keySet());
            keys.add("anchorId");
            keys.add("anchorValue");
            if (!cursor.keySet().equals(keys)) throw FinanceException.invalid("分页结构无效");
            for (String k : frame.keySet())
                if (!Set.of("epoch", "generation", "auth").contains(k)
                        && !Objects.equals(frame.get(k), cursor.get(k)))
                    throw FinanceException.invalid("分页资源、账号或筛选不匹配");
            for (String k : List.of("epoch", "generation", "auth"))
                if (!Objects.equals(frame.get(k), cursor.get(k)))
                    throw error("TAX_ENTITY_PAGE_STALE", "目录或授权上下文已改变，请重新读取");
            FinanceTaxEntityInput.uuid(cursor.get("anchorId"));
            try {
                Instant.parse(cursor.get("anchorValue").toString());
            } catch (RuntimeException bad) {
                throw FinanceException.invalid("分页时间无效");
            }
        }
        String filter =
                switch (q.status) {
                    case "UNPUBLISHED" -> " AND p.approved_revision_id IS NULL";
                    case "ALL" -> "";
                    default -> " AND a.desired_status='" + q.status + "'";
                };
        var args =
                new ArrayList<Object>(
                        List.of(b.get("tenant_id"), b.get("legal_entity_id"), b.get("id")));
        String base =
                SELECT
                        + " WHERE p.tenant_id=? AND p.legal_entity_id=? AND p.home_book_id=?"
                        + filter;
        String seek = "";
        if (cursor != null) {
            var anchor = new ArrayList<>(args);
            anchor.add(id(cursor.get("anchorId")));
            anchor.add(cursor.get("anchorValue"));
            if (c.list(base + " AND p.id=? AND p.created_at=?::timestamptz", anchor.toArray())
                            .size()
                    != 1) throw FinanceException.invalid("分页锚点不属于当前目录");
            seek = " AND(p.created_at,p.id)<(?::timestamptz,?::uuid)";
            args.add(cursor.get("anchorValue"));
            args.add(id(cursor.get("anchorId")));
        }
        args.add(q.limit + 1);
        var rows =
                c.list(
                        base + seek + " ORDER BY p.created_at DESC,p.id DESC LIMIT ?",
                        args.toArray());
        boolean more = rows.size() > q.limit;
        if (more) rows = rows.subList(0, q.limit);
        String next = null;
        if (more) {
            var last = rows.getLast();
            var n = new LinkedHashMap<>(frame);
            n.put("anchorId", last.get("id").toString());
            n.put(
                    "anchorValue",
                    ((java.sql.Timestamp) last.get("created_at")).toInstant().toString());
            next = token(n);
        }
        return map(
                "schemaVersion",
                FinanceTaxEntityInput.SCHEMA,
                "tenantId",
                b.get("tenant_id"),
                "legalEntityId",
                b.get("legal_entity_id"),
                "currentBookId",
                b.get("id"),
                "currentActor",
                a,
                "listingScope",
                "GOVERNANCE_HOME_BOOK_ONLY",
                "readEpoch",
                g.get("epoch"),
                "generation",
                g.get("generation"),
                "limit",
                q.limit,
                "items",
                rows.stream().map(this::summary).toList(),
                "nextCursor",
                next,
                "total",
                null,
                "totalStatus",
                "NOT_COMPUTED");
    }
}
