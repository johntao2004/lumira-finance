package com.lumira.finance;

import com.lumira.finance.domain.PrecisionRules;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** Full ordinary gross components with real typed parent reliance and reviewed replacement. */
@Service
public class FinanceCashAllocations {
    static final String SCHEMA = "cash-allocation/1";
    static final Set<String> PURPOSES =
            Set.of(
                    "SALES_GOODS_SERVICES_GROSS",
                    "OPERATING_GOODS_SERVICES_GROSS",
                    "OPERATING_EMPLOYEE_PAY_GROSS",
                    "ORDINARY_OPERATING_TAX_ACTUAL",
                    "BORROWING_PRINCIPAL_CASH",
                    "BORROWING_PRINCIPAL_ONLY",
                    "EQUITY_CAPITAL_CASH_GROSS");
    static final Set<String> EXPECTED =
            Set.of(
                    "reviewId",
                    "expectedReviewVersion",
                    "reviewContentDigest",
                    "activeReviewUsageId",
                    "claimId",
                    "claimGeneration",
                    "claimContentDigest",
                    "sourceMatchGeneration");
    static final List<String> LIMITATIONS =
            List.of(
                    "普通同质用途全额组件分配，不代表完整来源覆盖、工作底稿或法定报表",
                    "父依赖为实际APPROVED人审+ACTIVE SOURCE_REVIEW usage/claim，不修改Raw未核实标志",
                    "只支持服务端七种普通用途；复杂资本化工资、利息、未知税款、转账、退款、外币和净额不支持",
                    "政策退役不重算历史分配；新用仍须现金日期适用及现时发布资格",
                    "pendingRelease/pendingReplacement保留旧有效依赖，冻结新增下游依赖；独立决策后才改变效果",
                    "替换保留实际激活收据与旧新决策；后继之后合法释放/再次替换不毁历史",
                    "独立性按账号UUID，未解决同自然人多账号；7×24小时为运营审签到期",
                    "无银行验真、报表消费或交付；本地Outbox为未投递事务信封");
    private final FinanceStore db;
    private final FinanceService finance;
    private final FinanceCashSourceReviews reviews;
    private final FinanceCashPolicies policies;
    private final FinanceCashScopes scopes;
    private final FinanceCashMovements movements;

    public FinanceCashAllocations(
            FinanceStore db,
            FinanceService finance,
            FinanceCashSourceReviews reviews,
            FinanceCashPolicies policies,
            FinanceCashScopes scopes,
            FinanceCashMovements movements) {
        this.db = db;
        this.finance = finance;
        this.reviews = reviews;
        this.policies = policies;
        this.scopes = scopes;
        this.movements = movements;
    }

    static FinanceException fail(String code, String message) {
        return new FinanceException(code, message);
    }

    static String text(Map<String, Object> m, String key, int max) {
        return FinanceCashScopes.text(m, key, max);
    }

    static UUID uuid(Map<String, Object> m, String key) {
        return FinanceService.id(m, key);
    }

    static long positive(Map<String, Object> m, String key) {
        return FinanceCashMatchCorrections.number(m, key, false);
    }

    String digest(Object value) {
        return scopes.digest(value);
    }

    static String hash(Map<String, Object> m, String key) {
        if (!(m.get(key) instanceof String s) || !s.matches("[a-f0-9]{64}"))
            throw FinanceException.invalid(key + "须为SHA256摘要");
        return s;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> expected(Object value) {
        if (!(value instanceof Map<?, ?>)) throw FinanceException.invalid("expectedParent须为封闭对象");
        var m = (Map<String, Object>) value;
        FinanceCashScopes.fields(m, EXPECTED);
        var n = new LinkedHashMap<String, Object>(m);
        for (String k : List.of("reviewId", "activeReviewUsageId", "claimId"))
            n.put(k, uuid(m, k).toString());
        for (String k :
                List.of("expectedReviewVersion", "claimGeneration", "sourceMatchGeneration"))
            n.put(k, positive(m, k));
        hash(m, "reviewContentDigest");
        hash(m, "claimContentDigest");
        return n;
    }

    Map<String, Object> expectedFrom(Map<String, Object> parent) {
        var m = new LinkedHashMap<String, Object>();
        for (String key : EXPECTED) m.put(key, parent.get(key));
        return expected(m);
    }

    void bind(Map<String, Object> requested, Map<String, Object> parent) {
        if (!digest(requested).equals(digest(expectedFrom(parent))))
            throw fail("CASH_ALLOCATION_PARENT_STALE", "expectedParent与冻结人审/usage/claim不一致");
    }

    Map<String, Object> author(String actor, UUID book, UUID year, boolean owner) {
        var b = finance.originalScope(actor, book, year, owner ? "manage" : "create");
        if (owner) finance.originalScope(actor, book, year, "create");
        return b;
    }

    Map<String, Object> authorize(String actor, UUID book, UUID year, String op) {
        if (Set.of("create", "submit", "cancel", "replace", "replace-cancel").contains(op))
            return author(actor, book, year, false);
        if (op.equals("release") || op.equals("release-cancel"))
            return author(actor, book, year, true);
        var b = finance.originalScope(actor, book, year, "approve");
        if (op.endsWith("approve")) finance.originalScope(actor, book, year, "read");
        return b;
    }

    void currentAuthor(Map<String, Object> b, UUID year, Object name, Object id, boolean owner) {
        try {
            var now = author(name.toString(), (UUID) b.get("id"), year, owner);
            if (!Objects.equals(now.get("actor_id"), id)) throw new IllegalArgumentException();
        } catch (RuntimeException stale) {
            throw fail("APPROVAL_STALE", "当前分配制备人或请求人的制备/年度授权已失效");
        }
    }

    void independent(
            UUID actor,
            Map<String, Object> raw,
            Map<String, Object> a,
            Map<String, Object> old,
            Object requester) {
        if (actor.equals(raw.get("prepared_by"))
                || actor.equals(a.get("prepared_by"))
                || actor.equals(a.get("last_edited_by"))
                || actor.equals(requester)
                || old != null && actor.equals(old.get("prepared_by")))
            throw fail("SELF_APPROVAL_FORBIDDEN", "复核须独立于来源制备人、旧新分配制备人和请求人");
    }

    static final String SELECT =
            "SELECT a.id,a.review_id AS \"reviewId\",a.claim_id AS \"claimId\",a.claim_generation"
                + " AS \"claimGeneration\",a.status,a.version,a.content_version AS"
                + " \"contentVersion\",a.content_digest AS \"contentDigest\",a.prepared_by AS"
                + " \"preparedBy\",a.created_at AS \"createdAt\",a.submitted_at AS"
                + " \"submittedAt\",a.submission_expires_at AS"
                + " \"submissionExpiresAt\",a.approved_by AS \"approvedBy\",a.approved_at AS"
                + " \"approvedAt\",a.decision_by AS \"decisionBy\",a.decision_at AS"
                + " \"decisionAt\",a.decision_reason AS \"decisionReason\",(SELECT d.id FROM"
                + " plugin_finance_cash_allocation_dependency d WHERE"
                + " (d.tenant_id,d.book_id,d.year_id,d.id,d.allocation_id)=(a.tenant_id,a.book_id,a.year_id,a.approved_dependency_id,a.id)"
                + " AND d.state='ACTIVE') AS \"activeDependencyId\",(a.status='APPROVED' AND"
                + " EXISTS(SELECT 1 FROM plugin_finance_cash_allocation_effect e JOIN"
                + " plugin_finance_cash_allocation_dependency d ON"
                + " (d.tenant_id,d.book_id,d.year_id,d.id)=(e.tenant_id,e.book_id,e.year_id,e.dependency_id)"
                + " WHERE"
                + " (e.tenant_id,e.book_id,e.year_id,e.review_id,e.allocation_id)=(a.tenant_id,a.book_id,a.year_id,a.review_id,a.id)"
                + " AND d.state='ACTIVE')) AS \"currentEffective\",a.supersedes_allocation_id AS"
                + " \"supersedesAllocationId\",a.superseded_by_allocation_id AS"
                + " \"supersededByAllocationId\",a.released_by AS \"releasedBy\",a.released_at AS"
                + " \"releasedAt\",false AS \"formalReportContributionEnabled\",false AS"
                + " \"coverageVerified\" FROM plugin_finance_cash_allocation a WHERE a.tenant_id=?"
                + " AND a.book_id=? AND a.year_id=?";
    static final String RELEASE =
            "SELECT r.id,r.allocation_id AS \"allocationId\",r.dependency_id AS"
                + " \"dependencyId\",r.allocation_content_digest AS"
                + " \"allocationContentDigest\",r.expected_allocation_version AS"
                + " \"expectedAllocationVersion\",r.status,r.version,r.content_digest AS"
                + " \"contentDigest\",r.reason,r.requested_by AS \"requestedBy\",r.requested_at AS"
                + " \"requestedAt\",r.review_expires_at AS \"reviewExpiresAt\",r.reviewed_by AS"
                + " \"reviewedBy\",r.reviewed_at AS \"reviewedAt\",r.decision_reason AS"
                + " \"decisionReason\" FROM plugin_finance_cash_allocation_release r WHERE"
                + " r.tenant_id=? AND r.book_id=? AND r.year_id=?";
    static final String REPLACE =
            "SELECT r.id,r.old_allocation_id AS \"oldAllocationId\",r.old_dependency_id AS"
                + " \"oldDependencyId\",r.expected_old_version AS"
                + " \"expectedOldVersion\",r.old_content_digest AS"
                + " \"oldContentDigest\",r.new_allocation_id AS"
                + " \"newAllocationId\",r.expected_new_version AS"
                + " \"expectedNewVersion\",r.new_content_digest AS \"newContentDigest\",r.review_id"
                + " AS \"parentReviewId\",r.claim_id AS"
                + " \"claimId\",r.status,r.version,r.content_digest AS"
                + " \"contentDigest\",r.reason,r.requested_by AS \"requestedBy\",r.requested_at AS"
                + " \"requestedAt\",r.review_expires_at AS \"reviewExpiresAt\",r.reviewed_by AS"
                + " \"reviewedBy\",r.reviewed_at AS \"reviewedAt\",r.decision_reason AS"
                + " \"decisionReason\" FROM plugin_finance_cash_allocation_replacement r WHERE"
                + " r.tenant_id=? AND r.book_id=? AND r.year_id=?";

    Map<String, Object> row(Map<String, Object> b, UUID year, UUID id) {
        return db.one(
                "SELECT *,content::text AS content_text FROM plugin_finance_cash_allocation WHERE"
                    + " tenant_id=? AND book_id=? AND year_id=? AND id=?",
                b.get("tenant_id"),
                b.get("id"),
                year,
                id);
    }

    Map<String, Object> content(Map<String, Object> r) {
        var c = finance.decode(r.get("content_text").toString());
        if (!digest(c).equals(r.get("content_digest")))
            throw fail("APPROVAL_STALE", "不可变分配或请求摘要不匹配");
        return c;
    }

    Map<String, Object> release(Map<String, Object> b, UUID year, UUID id, UUID rid) {
        return db.one(
                RELEASE + " AND r.allocation_id=? AND r.id=?",
                b.get("tenant_id"),
                b.get("id"),
                year,
                id,
                rid);
    }

    Map<String, Object> replacement(Map<String, Object> b, UUID year, UUID rid) {
        return db.one(REPLACE + " AND r.id=?", b.get("tenant_id"), b.get("id"), year, rid);
    }

    Map<String, Object> watermark(Map<String, Object> b, UUID year, UUID raw) {
        var wm = new LinkedHashMap<String, Object>(reviews.watermark(b, year, raw));
        var g =
                db.list(
                        "SELECT allocation_version FROM plugin_finance_cash_allocation_guard WHERE"
                            + " tenant_id=? AND book_id=? AND year_id=?",
                        b.get("tenant_id"),
                        b.get("id"),
                        year);
        var p =
                db.list(
                        "SELECT publication_version FROM plugin_finance_cash_policy_guard WHERE"
                            + " tenant_id=? AND book_id=? AND year_id=?",
                        b.get("tenant_id"),
                        b.get("id"),
                        year);
        wm.put("allocationVersion", g.isEmpty() ? 0L : g.getFirst().get("allocation_version"));
        wm.put(
                "policyPublicationVersion",
                p.isEmpty() ? 0L : p.getFirst().get("publication_version"));
        return wm;
    }

    Map<String, Object> detail(Map<String, Object> b, UUID year, UUID id) {
        var a = row(b, year, id);
        var c = content(a);
        var result = db.one(SELECT + " AND a.id=?", b.get("tenant_id"), b.get("id"), year, id);
        for (String key :
                List.of(
                        "tenantId",
                        "bookId",
                        "yearId",
                        "legalEntityId",
                        "parentSnapshot",
                        "lines",
                        "normalizedTotal",
                        "reason")) result.put(key, c.get(key));
        var r =
                db.list(
                        RELEASE + " AND r.allocation_id=? AND r.status='REQUESTED'",
                        b.get("tenant_id"),
                        b.get("id"),
                        year,
                        id);
        var x =
                db.list(
                        REPLACE
                                + " AND (r.old_allocation_id=? OR r.new_allocation_id=?) AND"
                                + " r.status='REQUESTED'",
                        b.get("tenant_id"),
                        b.get("id"),
                        year,
                        id,
                        id);
        result.put("pendingRelease", r.isEmpty() ? null : r.getFirst());
        result.put("pendingReplacement", x.isEmpty() ? null : x.getFirst());
        result.put("watermark", watermark(b, year, (UUID) a.get("raw_registration_id")));
        result.put("limitations", LIMITATIONS);
        return result;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> detail(String actor, UUID book, UUID year, UUID id) {
        return detail(finance.originalScope(actor, book, year, "read"), year, id);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> list(
            String actor,
            UUID book,
            UUID year,
            UUID review,
            UUID claim,
            String status,
            int limit,
            String cursor) {
        var b = finance.originalScope(actor, book, year, "read");
        if (status != null
                && !Set.of(
                                "DRAFT",
                                "SUBMITTED",
                                "APPROVED",
                                "REJECTED",
                                "CANCELLED",
                                "RELEASED",
                                "SUPERSEDED")
                        .contains(status)) throw FinanceException.invalid("分配状态无效");
        String sql = SELECT;
        var p = new ArrayList<Object>(List.of(b.get("tenant_id"), book, year));
        if (review != null) {
            sql += " AND a.review_id=?";
            p.add(review);
        }
        if (claim != null) {
            sql += " AND a.claim_id=?";
            p.add(claim);
        }
        if (status != null) {
            sql += " AND a.status=?";
            p.add(status);
        }
        return reviews.page(
                b,
                year,
                "ALLOCATIONS:" + review + ":" + claim + ":" + status,
                sql,
                p,
                "a",
                "created_at",
                "createdAt",
                limit,
                cursor);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> releases(
            String actor, UUID book, UUID year, UUID id, int limit, String cursor) {
        var b = finance.originalScope(actor, book, year, "read");
        row(b, year, id);
        return reviews.page(
                b,
                year,
                "ALLOCATION_RELEASES:" + id,
                RELEASE + " AND r.allocation_id=?",
                new ArrayList<>(List.of(b.get("tenant_id"), book, year, id)),
                "r",
                "requested_at",
                "requestedAt",
                limit,
                cursor);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> replacements(
            String actor, UUID book, UUID year, UUID id, int limit, String cursor) {
        var b = finance.originalScope(actor, book, year, "read");
        row(b, year, id);
        return reviews.page(
                b,
                year,
                "ALLOCATION_REPLACEMENTS:" + id,
                REPLACE + " AND (r.old_allocation_id=? OR r.new_allocation_id=?)",
                new ArrayList<>(List.of(b.get("tenant_id"), book, year, id, id)),
                "r",
                "requested_at",
                "requestedAt",
                limit,
                cursor);
    }

    Map<String, Object> parent(Map<String, Object> b, UUID year, UUID reviewId) {
        var r = reviews.row(b, year, reviewId);
        reviews.content(r);
        if (!"APPROVED".equals(r.get("status")))
            throw fail("CASH_ALLOCATION_PARENT_STALE", "人审不是实际APPROVED父实体");
        if (!db.list(
                        "SELECT id FROM plugin_finance_cash_source_review_release WHERE tenant_id=?"
                            + " AND book_id=? AND year_id=? AND review_id=? AND status='REQUESTED'",
                        b.get("tenant_id"),
                        b.get("id"),
                        year,
                        reviewId)
                .isEmpty())
            throw fail("CASH_ALLOCATION_PARENT_RELEASE_PENDING", "父人审已有待审释放请求，冻结新分配");
        reviews.revalidateEvidence(b, year, reviews.content(r));
        reviews.snapshot(b, year, (UUID) r.get("raw_registration_id"), (UUID) r.get("claim_id"));
        try {
            return finance.decode(
                    db.one(
                                    "SELECT plugin_finance_cash_allocation_parent(?,?,?,?)::text"
                                        + " value",
                                    b.get("tenant_id"),
                                    b.get("id"),
                                    year,
                                    reviewId)
                            .get("value")
                            .toString());
        } catch (org.springframework.dao.DataAccessException stale) {
            throw fail("CASH_ALLOCATION_PARENT_STALE", "当前父人审、usage、来源占用或事实快照已失效");
        }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> eligible(
            String actor,
            UUID book,
            UUID year,
            UUID reviewId,
            UUID claim,
            int limit,
            String cursor) {
        var b = finance.originalScope(actor, book, year, "read");
        String sql =
                "SELECT r.id,r.created_at AS \"createdAt\" FROM plugin_finance_cash_source_review r"
                    + " JOIN plugin_finance_cash_claim_usage u ON"
                    + " (u.tenant_id,u.book_id,u.year_id,u.id,u.source_review_id)=(r.tenant_id,r.book_id,r.year_id,r.approved_usage_id,r.id)"
                    + " JOIN plugin_finance_cash_leg_claim c ON"
                    + " (c.tenant_id,c.book_id,c.year_id,c.id)=(r.tenant_id,r.book_id,r.year_id,r.claim_id)"
                    + " WHERE r.tenant_id=? AND r.book_id=? AND r.year_id=? AND r.status='APPROVED'"
                    + " AND u.state='ACTIVE' AND c.state='ACTIVE' AND NOT EXISTS(SELECT 1 FROM"
                    + " plugin_finance_cash_source_review_release q WHERE"
                    + " (q.tenant_id,q.book_id,q.year_id,q.review_id)=(r.tenant_id,r.book_id,r.year_id,r.id)"
                    + " AND q.status='REQUESTED') AND NOT EXISTS(SELECT 1 FROM"
                    + " plugin_finance_cash_match_withdrawal q WHERE"
                    + " (q.tenant_id,q.book_id,q.year_id,q.active_claim_id)=(r.tenant_id,r.book_id,r.year_id,r.claim_id)"
                    + " AND q.status='REQUESTED')";
        var p = new ArrayList<Object>(List.of(b.get("tenant_id"), book, year));
        if (reviewId != null) {
            reviews.row(b, year, reviewId);
            sql += " AND r.id=?";
            p.add(reviewId);
        }
        if (claim != null) {
            sql += " AND r.claim_id=?";
            p.add(claim);
        }
        var page =
                reviews.page(
                        b,
                        year,
                        "ALLOCATION_PARENTS:" + reviewId + ":" + claim,
                        sql,
                        p,
                        "r",
                        "created_at",
                        "createdAt",
                        limit,
                        cursor);
        var items = new ArrayList<Map<String, Object>>();
        for (var r : (List<Map<String, Object>>) page.get("items"))
            items.add(parent(b, year, (UUID) r.get("id")));
        page.put("items", items);
        return page;
    }

    static String money(Object value) {
        if (!(value instanceof String literal)
                || !literal.matches("(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?"))
            throw FinanceException.invalid("组件金额须为规范正数字符串");
        try {
            var amount = PrecisionRules.formalCurrencyMoney(literal, 2);
            if (amount.signum() <= 0) throw FinanceException.invalid("组件金额须大于零");
            return amount.toPlainString();
        } catch (IllegalArgumentException invalid) {
            throw fail(
                    "DECIMAL_OVERFLOW".equals(invalid.getMessage())
                            ? "AMOUNT_OVERFLOW"
                            : "INVALID_CURRENCY_SCALE",
                    "组件金额超容量或超过人民币两位小数，不自动舍入");
        }
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> inputLines(Object value) {
        if (!(value instanceof List<?> values) || values.isEmpty() || values.size() > 20)
            throw FinanceException.invalid("组件须为1至20项");
        var rows = new ArrayList<Map<String, Object>>();
        Set<String> keys = new HashSet<>();
        for (Object v : values) {
            if (!(v instanceof Map<?, ?>)) throw FinanceException.invalid("组件对象无效");
            var m = (Map<String, Object>) v;
            FinanceCashScopes.fields(
                    m,
                    Set.of(
                            "componentKey",
                            "amount",
                            "policyId",
                            "expectedPolicyVersion",
                            "policyContentDigest",
                            "businessPurpose",
                            "purposeEvidenceOriginalId",
                            "description",
                            "ordinaryPurposeConfirmed"));
            String key = FinanceSources.exact(m, "componentKey", 80);
            if (!key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,79}") || !keys.add(key))
                throw FinanceException.invalid("componentKey格式无效或重复");
            if (!Boolean.TRUE.equals(m.get("ordinaryPurposeConfirmed")))
                throw fail("CASH_ALLOCATION_UNSUPPORTED_FACTS", "每个组件须明确确认真实普通用途，不能伪装未知/特殊事实");
            var n = new LinkedHashMap<String, Object>(m);
            n.put("amount", money(m.get("amount")));
            n.put("policyId", uuid(m, "policyId").toString());
            n.put("purposeEvidenceOriginalId", uuid(m, "purposeEvidenceOriginalId").toString());
            n.put("expectedPolicyVersion", positive(m, "expectedPolicyVersion"));
            hash(m, "policyContentDigest");
            n.put("businessPurpose", FinanceSources.exact(m, "businessPurpose", 100));
            if (!PURPOSES.contains(n.get("businessPurpose")))
                throw fail("CASH_ALLOCATION_UNSUPPORTED_FACTS", "未知/混合/特殊用途不在首版普通目录");
            n.put("description", text(m, "description", 1000));
            rows.add(n);
        }
        return rows;
    }

    List<Map<String, Object>> prepareLines(
            Map<String, Object> b,
            UUID year,
            Map<String, Object> parent,
            List<Map<String, Object>> inputs) {
        var result = new ArrayList<Map<String, Object>>();
        BigDecimal sum = BigDecimal.ZERO;
        Set<String> purposes = new HashSet<>();
        int no = 0;
        for (var in : inputs) {
            var p = policies.row(b, year, uuid(in, "policyId"));
            policies.content(p);
            if (!"APPROVED".equals(p.get("status"))
                    || ((Number) p.get("version")).longValue()
                            != positive(in, "expectedPolicyVersion")
                    || !p.get("content_digest").equals(in.get("policyContentDigest"))
                    || !p.get("transaction_kind").equals(parent.get("transactionKind"))
                    || !p.get("business_purpose").equals(in.get("businessPurpose"))
                    || !p.get("direction").equals(parent.get("direction"))
                    || LocalDate.parse(parent.get("occurredDate").toString())
                            .isBefore(LocalDate.parse(p.get("effective_from").toString()))
                    || LocalDate.parse(parent.get("occurredDate").toString())
                            .isAfter(LocalDate.parse(p.get("effective_to").toString()))
                    || !db.list(
                                    "SELECT id FROM plugin_finance_cash_policy_retirement WHERE"
                                        + " tenant_id=? AND book_id=? AND year_id=? AND policy_id=?"
                                        + " AND status='REQUESTED'",
                                    b.get("tenant_id"),
                                    b.get("id"),
                                    year,
                                    p.get("id"))
                            .isEmpty())
                throw fail(
                        "CASH_ALLOCATION_POLICY_UNAVAILABLE", "政策须当前已发布、无待退役且精确匹配实际现金日期/方向/普通用途版本");
            try {
                policies.revalidate(b, year, policies.content(p));
            } catch (FinanceException stale) {
                throw fail("CASH_ALLOCATION_POLICY_UNAVAILABLE", "政策依据或适用性证据已失效");
            }
            var policySnapshot =
                    finance.decode(
                            db.one(
                                            "SELECT"
                                                + " plugin_finance_cash_allocation_policy_snapshot(?,?,?,?)::text"
                                                + " value",
                                            b.get("tenant_id"),
                                            b.get("id"),
                                            year,
                                            p.get("id"))
                                    .get("value")
                                    .toString());
            var original = scopes.evidence(b, year, uuid(in, "purposeEvidenceOriginalId"));
            var evidence =
                    Map.of(
                            "originalId",
                            original.get("id"),
                            "version",
                            original.get("version"),
                            "sha256",
                            original.get("sha256"),
                            "filename",
                            original.get("filename"));
            var line = new LinkedHashMap<String, Object>();
            line.put("lineNo", ++no);
            line.put("componentKey", in.get("componentKey"));
            line.put("normalizedAmount", in.get("amount"));
            line.put("policySnapshot", policySnapshot);
            line.put("businessPurpose", in.get("businessPurpose"));
            line.put("purposeEvidence", evidence);
            line.put("description", in.get("description"));
            line.put("ordinaryPurposeConfirmed", true);
            result.add(line);
            sum = sum.add(new BigDecimal(in.get("amount").toString()));
            purposes.add(in.get("businessPurpose").toString());
        }
        if (purposes.size() != 1)
            throw fail("CASH_ALLOCATION_UNSUPPORTED_FACTS", "首版仅支持与来源kind对应的同质普通用途组件");
        if (sum.compareTo(new BigDecimal(parent.get("grossAmount").toString())) != 0)
            throw fail("CASH_ALLOCATION_SUM_MISMATCH", "组件精确和必须等于全部实际现金gross，不得留下残差或超额");
        return result;
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> inputsFrom(Map<String, Object> c) {
        var result = new ArrayList<Map<String, Object>>();
        for (var l : (List<Map<String, Object>>) c.get("lines")) {
            var p = (Map<String, Object>) l.get("policySnapshot");
            var e = (Map<String, Object>) l.get("purposeEvidence");
            result.add(
                    new LinkedHashMap<>(
                            Map.of(
                                    "componentKey",
                                    l.get("componentKey"),
                                    "amount",
                                    l.get("normalizedAmount"),
                                    "policyId",
                                    p.get("policyId"),
                                    "expectedPolicyVersion",
                                    p.get("policyVersion"),
                                    "policyContentDigest",
                                    p.get("contentDigest"),
                                    "businessPurpose",
                                    l.get("businessPurpose"),
                                    "purposeEvidenceOriginalId",
                                    e.get("originalId"),
                                    "description",
                                    l.get("description"),
                                    "ordinaryPurposeConfirmed",
                                    true)));
        }
        return result;
    }

    void revalidate(Map<String, Object> b, UUID year, Map<String, Object> a) {
        var c = content(a);
        var parent = parent(b, year, (UUID) a.get("review_id"));
        if (!digest(parent).equals(digest(c.get("parentSnapshot")))
                || !digest(prepareLines(b, year, parent, inputsFrom(c)))
                        .equals(digest(c.get("lines"))))
            throw fail("CASH_ALLOCATION_PARENT_STALE", "人审或组件的政策/证据事实已变化，不能激活旧快照");
    }

    Map<String, Object> normalize(String op, Map<String, Object> input) {
        Set<String> keys =
                op.equals("create")
                        ? Set.of(
                                "schemaVersion",
                                "idempotencyKey",
                                "expectedParent",
                                "supersedesAllocationId",
                                "expectedSupersededVersion",
                                "supersededContentDigest",
                                "lines",
                                "reason")
                        : op.equals("replace")
                                ? Set.of(
                                        "schemaVersion",
                                        "idempotencyKey",
                                        "oldAllocationId",
                                        "expectedOldVersion",
                                        "oldContentDigest",
                                        "oldDependencyId",
                                        "newAllocationId",
                                        "expectedNewVersion",
                                        "newContentDigest",
                                        "expectedParent",
                                        "reason")
                                : op.equals("release")
                                        ? Set.of(
                                                "schemaVersion",
                                                "idempotencyKey",
                                                "expectedVersion",
                                                "contentDigest",
                                                "activeDependencyId",
                                                "reason")
                                        : op.startsWith("release-") || op.startsWith("replace-")
                                                ? Set.of(
                                                        "schemaVersion",
                                                        "idempotencyKey",
                                                        "expectedVersion",
                                                        "contentDigest",
                                                        "reason")
                                                : Set.of(
                                                        "schemaVersion",
                                                        "idempotencyKey",
                                                        "expectedVersion",
                                                        "contentDigest",
                                                        "expectedParent",
                                                        "reason");
        FinanceCashScopes.fields(input, keys);
        if (!SCHEMA.equals(input.get("schemaVersion")))
            throw FinanceException.invalid("分配schemaVersion无效");
        var n = new LinkedHashMap<String, Object>(input);
        n.remove("idempotencyKey");
        n.put("reason", text(input, "reason", 1000));
        if (input.containsKey("expectedParent"))
            n.put("expectedParent", expected(input.get("expectedParent")));
        for (String k : List.of("expectedVersion", "expectedOldVersion", "expectedNewVersion"))
            if (input.containsKey(k)) n.put(k, positive(input, k));
        for (String k : List.of("contentDigest", "oldContentDigest", "newContentDigest"))
            if (input.containsKey(k)) hash(input, k);
        for (String k :
                List.of(
                        "activeDependencyId",
                        "oldAllocationId",
                        "oldDependencyId",
                        "newAllocationId"))
            if (input.containsKey(k)) n.put(k, uuid(input, k).toString());
        if (op.equals("create")) {
            n.put("lines", inputLines(input.get("lines")));
            if (input.get("supersedesAllocationId") == null) {
                if (input.get("expectedSupersededVersion") != null
                        || input.get("supersededContentDigest") != null)
                    throw FinanceException.invalid("普通分配三个supersedes字段须显式全null");
            } else {
                n.put("supersedesAllocationId", uuid(input, "supersedesAllocationId").toString());
                n.put("expectedSupersededVersion", positive(input, "expectedSupersededVersion"));
                hash(input, "supersededContentDigest");
            }
        }
        return n;
    }

    void version(Map<String, Object> n, Map<String, Object> a) {
        if (positive(n, "expectedVersion") != ((Number) a.get("version")).longValue()
                || !n.get("contentDigest").equals(a.get("content_digest")))
            throw fail("VERSION_CONFLICT", "分配或请求版本/摘要不匹配");
    }

    void fresh(Map<String, Object> a, String column) {
        if (Boolean.TRUE.equals(
                db.one("SELECT ?::timestamptz<=clock_timestamp() expired", a.get(column))
                        .get("expired")))
            throw fail("CASH_ALLOCATION_SUBMISSION_EXPIRED", "7×24小时运营审签时限已过，可拒绝/取消后新建不可变版本");
    }

    void noReplacement(Map<String, Object> b, UUID year, UUID id) {
        if (!db.list(
                        "SELECT id FROM plugin_finance_cash_allocation_replacement WHERE"
                            + " tenant_id=? AND book_id=? AND year_id=? AND (old_allocation_id=? OR"
                            + " new_allocation_id=?) AND status='REQUESTED'",
                        b.get("tenant_id"),
                        b.get("id"),
                        year,
                        id,
                        id)
                .isEmpty()) throw fail("CASH_ALLOCATION_REPLACEMENT_PENDING", "已有待审替换，须先独立终结请求");
    }

    void noRelease(Map<String, Object> b, UUID year, UUID id) {
        if (!db.list(
                        "SELECT id FROM plugin_finance_cash_allocation_release WHERE tenant_id=?"
                            + " AND book_id=? AND year_id=? AND allocation_id=? AND"
                            + " status='REQUESTED'",
                        b.get("tenant_id"),
                        b.get("id"),
                        year,
                        id)
                .isEmpty()) throw fail("CASH_ALLOCATION_RELEASE_PENDING", "已有待审释放，旧依赖仍有效");
    }

    Map<String, Object> effect(Map<String, Object> b, UUID year, UUID review) {
        var rows =
                db.list(
                        "SELECT * FROM plugin_finance_cash_allocation_effect WHERE tenant_id=? AND"
                            + " book_id=? AND year_id=? AND review_id=?",
                        b.get("tenant_id"),
                        b.get("id"),
                        year,
                        review);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    void active(Map<String, Object> b, UUID year, Map<String, Object> a) {
        var e = effect(b, year, (UUID) a.get("review_id"));
        if (!"APPROVED".equals(a.get("status"))
                || e == null
                || !a.get("id").equals(e.get("allocation_id"))
                || !a.get("approved_dependency_id").equals(e.get("dependency_id"))
                || db.list(
                                "SELECT id FROM plugin_finance_cash_allocation_dependency WHERE"
                                    + " tenant_id=? AND book_id=? AND year_id=? AND id=? AND"
                                    + " allocation_id=? AND state='ACTIVE'",
                                b.get("tenant_id"),
                                b.get("id"),
                                year,
                                a.get("approved_dependency_id"),
                                a.get("id"))
                        .isEmpty()) throw fail("CASH_ALLOCATION_STATE_CONFLICT", "分配已非当前实际有效依赖/指针");
    }

    @Transactional
    public Map<String, Object> write(
            String actor,
            UUID book,
            UUID year,
            String op,
            UUID id,
            UUID requestId,
            Map<String, Object> input) {
        if (!Set.of(
                        "create",
                        "submit",
                        "cancel",
                        "approve",
                        "reject",
                        "release",
                        "release-approve",
                        "release-reject",
                        "release-cancel",
                        "replace",
                        "replace-approve",
                        "replace-reject",
                        "replace-cancel")
                .contains(op)) throw FinanceException.invalid("未知分配动作");
        var b = authorize(actor, book, year, op);
        var n = normalize(op, input);
        String key = FinanceSources.exact(input, "idempotencyKey", 100);
        if (key.length() < 8) throw FinanceException.invalid("幂等键至少8字符");
        UUID user = (UUID) b.get("actor_id");
        db.jdbc
                .sql("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")
                .param(user + ":" + key)
                .query((rs, i) -> 0)
                .list();
        db.one(
                "SELECT id FROM plugin_finance_book WHERE tenant_id=? AND id=? FOR UPDATE",
                b.get("tenant_id"),
                book);
        b = authorize(actor, book, year, op);
        finance.lockYearWrites(b, List.of(year));
        Map<String, Object> request = null;
        if (op.startsWith("replace-")) {
            request =
                    db.one(
                            "SELECT *,content::text AS content_text FROM"
                                + " plugin_finance_cash_allocation_replacement WHERE tenant_id=?"
                                + " AND book_id=? AND year_id=? AND id=?",
                            b.get("tenant_id"),
                            book,
                            year,
                            requestId);
            id = (UUID) request.get("new_allocation_id");
        } else if (op.equals("replace")) id = uuid(n, "newAllocationId");
        Map<String, Object> a = op.equals("create") ? null : row(b, year, id);
        Map<String, Object> old = null;
        if (op.equals("replace")) old = row(b, year, uuid(n, "oldAllocationId"));
        else if (op.startsWith("replace-"))
            old = row(b, year, (UUID) request.get("old_allocation_id"));
        else if (op.equals("create") && n.get("supersedesAllocationId") != null)
            old = row(b, year, UUID.fromString(n.get("supersedesAllocationId").toString()));
        UUID reviewId =
                op.equals("create")
                        ? UUID.fromString(
                                ((Map<String, Object>) n.get("expectedParent"))
                                        .get("reviewId")
                                        .toString())
                        : (UUID) a.get("review_id");
        var r = reviews.row(b, year, reviewId);
        UUID rawId = (UUID) r.get("raw_registration_id"), claimId = (UUID) r.get("claim_id");
        var raw = movements.row(b, year, rawId);
        db.one(
                "SELECT registration_version FROM plugin_finance_cash_registration_guard WHERE"
                    + " tenant_id=? AND book_id=? AND year_id=? FOR UPDATE",
                b.get("tenant_id"),
                book,
                year);
        scopes.period(b, year, LocalDate.parse(raw.get("occurred_date").toString()), true);
        db.one(
                "SELECT match_version FROM plugin_finance_cash_match_guard WHERE tenant_id=? AND"
                    + " book_id=? AND year_id=? FOR UPDATE",
                b.get("tenant_id"),
                book,
                year);
        db.one(
                "SELECT id FROM plugin_finance_cash_movement_registration WHERE tenant_id=? AND"
                    + " book_id=? AND year_id=? AND id=? FOR UPDATE",
                b.get("tenant_id"),
                book,
                year,
                rawId);
        db.one(
                "SELECT source_generation FROM plugin_finance_cash_source_match_guard WHERE"
                    + " tenant_id=? AND book_id=? AND year_id=? AND raw_registration_id=? FOR"
                    + " UPDATE",
                b.get("tenant_id"),
                book,
                year,
                rawId);
        db.one(
                "SELECT id FROM plugin_finance_cash_leg_claim WHERE tenant_id=? AND book_id=? AND"
                    + " year_id=? AND id=? FOR UPDATE",
                b.get("tenant_id"),
                book,
                year,
                claimId);
        db.one(
                "SELECT review_version FROM plugin_finance_cash_source_review_guard WHERE"
                    + " tenant_id=? AND book_id=? AND year_id=? FOR UPDATE",
                b.get("tenant_id"),
                book,
                year);
        db.one(
                "SELECT id FROM plugin_finance_cash_source_review WHERE tenant_id=? AND book_id=?"
                    + " AND year_id=? AND id=? FOR UPDATE",
                b.get("tenant_id"),
                book,
                year,
                reviewId);
        if (r.get("approved_usage_id") != null)
            db.one(
                    "SELECT id FROM plugin_finance_cash_claim_usage WHERE tenant_id=? AND book_id=?"
                        + " AND year_id=? AND id=? FOR UPDATE",
                    b.get("tenant_id"),
                    book,
                    year,
                    r.get("approved_usage_id"));
        if (db.list(
                        "SELECT publication_version FROM plugin_finance_cash_policy_guard WHERE"
                            + " tenant_id=? AND book_id=? AND year_id=? FOR UPDATE",
                        b.get("tenant_id"),
                        book,
                        year)
                .isEmpty()) throw fail("CASH_ALLOCATION_POLICY_UNAVAILABLE", "尚无已发布普通政策");
        Set<UUID> policyIds = new TreeSet<>();
        if (op.equals("create")) {
            for (var line : (List<Map<String, Object>>) n.get("lines"))
                policyIds.add(uuid(line, "policyId"));
        } else {
            var c = content(a);
            for (var l : (List<Map<String, Object>>) c.get("lines"))
                policyIds.add(
                        UUID.fromString(
                                ((Map<String, Object>) l.get("policySnapshot"))
                                        .get("policyId")
                                        .toString()));
        }
        for (UUID pid : policyIds)
            db.one(
                    "SELECT id FROM plugin_finance_cash_policy_revision WHERE tenant_id=? AND"
                        + " book_id=? AND year_id=? AND id=? FOR UPDATE",
                    b.get("tenant_id"),
                    book,
                    year,
                    pid);
        db.update(
                "INSERT INTO plugin_finance_cash_allocation_guard(tenant_id,book_id,year_id)"
                    + " VALUES(?,?,?) ON CONFLICT DO NOTHING",
                b.get("tenant_id"),
                book,
                year);
        db.one(
                "SELECT allocation_version FROM plugin_finance_cash_allocation_guard WHERE"
                    + " tenant_id=? AND book_id=? AND year_id=? FOR UPDATE",
                b.get("tenant_id"),
                book,
                year);
        Set<UUID> allocationIds = new TreeSet<>();
        if (a != null) allocationIds.add((UUID) a.get("id"));
        if (old != null) allocationIds.add((UUID) old.get("id"));
        for (UUID aid : allocationIds)
            db.one(
                    "SELECT id FROM plugin_finance_cash_allocation WHERE tenant_id=? AND book_id=?"
                        + " AND year_id=? AND id=? FOR UPDATE",
                    b.get("tenant_id"),
                    book,
                    year,
                    aid);
        if (a != null) a = row(b, year, (UUID) a.get("id"));
        if (old != null) old = row(b, year, (UUID) old.get("id"));
        db.list(
                "SELECT review_id FROM plugin_finance_cash_allocation_effect WHERE tenant_id=? AND"
                    + " book_id=? AND year_id=? AND review_id=? FOR UPDATE",
                b.get("tenant_id"),
                book,
                year,
                reviewId);
        Set<UUID> dependencyIds = new TreeSet<>();
        if (a != null && a.get("approved_dependency_id") != null)
            dependencyIds.add((UUID) a.get("approved_dependency_id"));
        if (old != null && old.get("approved_dependency_id") != null)
            dependencyIds.add((UUID) old.get("approved_dependency_id"));
        for (UUID did : dependencyIds)
            db.one(
                    "SELECT id FROM plugin_finance_cash_allocation_dependency WHERE tenant_id=? AND"
                        + " book_id=? AND year_id=? AND id=? FOR UPDATE",
                    b.get("tenant_id"),
                    book,
                    year,
                    did);
        if (Set.of("submit", "cancel").contains(op) && !user.equals(a.get("prepared_by")))
            throw fail("SCOPE_DENIED", "仅原分配制备人可提交/取消");
        if (op.equals("approve") || op.equals("reject")) independent(user, raw, a, null, null);
        if (op.equals("approve"))
            currentAuthor(b, year, a.get("prepared_username"), a.get("prepared_by"), false);
        if (op.startsWith("release-")) {
            request =
                    db.one(
                            "SELECT *,content::text AS content_text FROM"
                                + " plugin_finance_cash_allocation_release WHERE tenant_id=? AND"
                                + " book_id=? AND year_id=? AND allocation_id=? AND id=? FOR"
                                + " UPDATE",
                            b.get("tenant_id"),
                            book,
                            year,
                            id,
                            requestId);
            if (op.equals("release-cancel")) {
                if (!user.equals(request.get("requested_by")))
                    throw fail("SCOPE_DENIED", "仅原释放请求人可取消");
            } else independent(user, raw, a, null, request.get("requested_by"));
            if (op.equals("release-approve"))
                currentAuthor(
                        b,
                        year,
                        request.get("requested_username"),
                        request.get("requested_by"),
                        true);
        }
        if (op.equals("replace") && !user.equals(a.get("prepared_by")))
            throw fail("SCOPE_DENIED", "仅新分配原制备人可请求替换");
        if (op.startsWith("replace-")) {
            request =
                    db.one(
                            "SELECT *,content::text AS content_text FROM"
                                + " plugin_finance_cash_allocation_replacement WHERE tenant_id=?"
                                + " AND book_id=? AND year_id=? AND id=? FOR UPDATE",
                            b.get("tenant_id"),
                            book,
                            year,
                            requestId);
            if (op.equals("replace-cancel")) {
                if (!user.equals(request.get("requested_by")))
                    throw fail("SCOPE_DENIED", "仅原替换请求人可取消");
            } else independent(user, raw, a, old, request.get("requested_by"));
            if (op.equals("replace-approve")) {
                currentAuthor(b, year, a.get("prepared_username"), a.get("prepared_by"), false);
                currentAuthor(
                        b,
                        year,
                        request.get("requested_username"),
                        request.get("requested_by"),
                        false);
            }
        }
        String route = book + "/" + year + "/allocations/" + id + "/" + op + "/" + requestId,
                hash = digest(n);
        var cache =
                db.list(
                        "SELECT route,payload_hash,response FROM plugin_finance_idempotency WHERE"
                            + " actor_id=? AND key=?",
                        user,
                        key);
        if (!cache.isEmpty()) {
            var prior = cache.getFirst();
            if (!route.equals(prior.get("route")) || !hash.equals(prior.get("payload_hash")))
                throw fail("IDEMPOTENCY_CONFLICT", "请求键已用于不同分配命令");
            var response = finance.decode(prior.get("response").toString());
            if (op.startsWith("replace"))
                return replacement(b, year, UUID.fromString(response.get("id").toString()));
            if (op.startsWith("release"))
                return release(
                        b,
                        year,
                        UUID.fromString(response.get("allocationId").toString()),
                        UUID.fromString(response.get("id").toString()));
            return detail(b, year, UUID.fromString(response.get("id").toString()));
        }
        Map<String, Object> result;
        if (op.equals("create")) {
            id = create(b, year, n, old, actor, user);
            result = detail(b, year, id);
        } else if (op.startsWith("replace")) {
            result = replaceCommand(b, year, op, a, old, request, requestId, n, actor, user);
        } else if (op.startsWith("release")) {
            result = releaseCommand(b, year, op, a, request, requestId, n, actor, user);
        } else {
            version(n, a);
            var c = content(a);
            bind(
                    (Map<String, Object>) n.get("expectedParent"),
                    (Map<String, Object>) c.get("parentSnapshot"));
            String state = a.get("status").toString();
            if (op.equals("cancel") || op.equals("reject")) {
                if (!Set.of("DRAFT", "SUBMITTED").contains(state))
                    throw fail("CASH_ALLOCATION_STATE_CONFLICT", "仅草稿/已提交可终结");
                noReplacement(b, year, id);
                db.update(
                        "UPDATE plugin_finance_cash_allocation SET"
                            + " status=?,version=version+1,decision_by=?,decision_at=now(),decision_reason=?"
                            + " WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                        op.equals("cancel") ? "CANCELLED" : "REJECTED",
                        user,
                        n.get("reason"),
                        b.get("tenant_id"),
                        book,
                        year,
                        id);
            } else if (op.equals("submit")) {
                if (!"DRAFT".equals(state)) throw fail("CASH_ALLOCATION_STATE_CONFLICT", "仅草稿可提交");
                revalidate(b, year, a);
                db.update(
                        "UPDATE plugin_finance_cash_allocation SET"
                            + " status='SUBMITTED',version=version+1,submitted_at=now(),submission_expires_at=now()+interval"
                            + " '168 hours' WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                        b.get("tenant_id"),
                        book,
                        year,
                        id);
            } else {
                if (!"SUBMITTED".equals(state))
                    throw fail("CASH_ALLOCATION_STATE_CONFLICT", "仅已提交可批准");
                if (a.get("supersedes_allocation_id") != null)
                    throw fail("CASH_ALLOCATION_REPLACEMENT_REQUIRED", "替换候选必须通过真实独立替换请求，不得普通批准");
                fresh(a, "submission_expires_at");
                revalidate(b, year, a);
                if (effect(b, year, reviewId) != null)
                    throw fail("CASH_ALLOCATION_ALREADY_EFFECTIVE", "父人审已有当前分配效果");
                db.update(
                        "UPDATE plugin_finance_cash_allocation SET"
                            + " status='APPROVED',version=version+1,approved_by=?,approved_at=now(),approved_dependency_id=?,decision_by=?,decision_at=now(),decision_reason=?"
                            + " WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                        user,
                        UUID.randomUUID(),
                        user,
                        n.get("reason"),
                        b.get("tenant_id"),
                        book,
                        year,
                        id);
            }
            result = detail(b, year, id);
        }
        finance.originalAudit(
                user,
                b,
                year,
                "CASH_ALLOCATION_" + op.toUpperCase(Locale.ROOT).replace('-', '_'),
                (UUID) result.get("id"),
                result);
        db.update(
                "INSERT INTO plugin_finance_idempotency(actor_id,key,route,payload_hash,response)"
                    + " VALUES(?,?,?,?,?)",
                user,
                key,
                route,
                hash,
                finance.encode(result));
        return result;
    }

    UUID create(
            Map<String, Object> b,
            UUID year,
            Map<String, Object> n,
            Map<String, Object> old,
            String actor,
            UUID user) {
        var p =
                parent(
                        b,
                        year,
                        UUID.fromString(
                                ((Map<String, Object>) n.get("expectedParent"))
                                        .get("reviewId")
                                        .toString()));
        bind((Map<String, Object>) n.get("expectedParent"), p);
        UUID reviewId = UUID.fromString(p.get("reviewId").toString());
        if (!db.list(
                        "SELECT id FROM plugin_finance_cash_allocation WHERE tenant_id=? AND"
                            + " book_id=? AND year_id=? AND review_id=? AND status"
                            + " IN('DRAFT','SUBMITTED')",
                        b.get("tenant_id"),
                        b.get("id"),
                        year,
                        reviewId)
                .isEmpty()) throw fail("CASH_ALLOCATION_PENDING_EXISTS", "父人审已有一个待处理分配候选");
        if (old == null) {
            if (effect(b, year, reviewId) != null)
                throw fail("CASH_ALLOCATION_ALREADY_EFFECTIVE", "已有当前分配，须显式释放或替换");
        } else {
            active(b, year, old);
            noRelease(b, year, (UUID) old.get("id"));
            noReplacement(b, year, (UUID) old.get("id"));
            if (!reviewId.equals(old.get("review_id"))
                    || ((Number) n.get("expectedSupersededVersion")).longValue()
                            != ((Number) old.get("version")).longValue()
                    || !n.get("supersededContentDigest").equals(old.get("content_digest")))
                throw fail("VERSION_CONFLICT", "替换候选须精确绑定同父当前旧分配版本/摘要");
        }
        var lines = prepareLines(b, year, p, (List<Map<String, Object>>) n.get("lines"));
        var c = new LinkedHashMap<String, Object>();
        c.put("schemaVersion", SCHEMA);
        c.put("tenantId", b.get("tenant_id").toString());
        c.put("bookId", b.get("id").toString());
        c.put("yearId", year.toString());
        c.put("legalEntityId", b.get("legal_entity_id").toString());
        c.put("parentSnapshot", p);
        c.put("lines", lines);
        c.put("normalizedTotal", p.get("grossAmount"));
        c.put("reason", n.get("reason"));
        c.put("preparedBy", user.toString());
        c.put("supersedesAllocationId", n.get("supersedesAllocationId"));
        c.put("expectedSupersededVersion", n.get("expectedSupersededVersion"));
        c.put("supersededContentDigest", n.get("supersededContentDigest"));
        UUID id = UUID.randomUUID();
        db.update(
                "INSERT INTO"
                    + " plugin_finance_cash_allocation(tenant_id,book_id,year_id,id,legal_entity_id,review_id,review_usage_id,review_content_digest,review_version_at_prepare,claim_id,raw_registration_id,claim_generation,claim_content_digest,source_match_generation,content_digest,content,total,prepared_by,prepared_username,last_edited_by,supersedes_allocation_id,expected_superseded_version,superseded_content_digest)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?,?,?)",
                b.get("tenant_id"),
                b.get("id"),
                year,
                id,
                b.get("legal_entity_id"),
                reviewId,
                UUID.fromString(p.get("activeReviewUsageId").toString()),
                p.get("reviewContentDigest"),
                p.get("expectedReviewVersion"),
                UUID.fromString(p.get("claimId").toString()),
                UUID.fromString(p.get("rawRegistrationId").toString()),
                p.get("claimGeneration"),
                p.get("claimContentDigest"),
                p.get("sourceMatchGeneration"),
                digest(c),
                finance.encode(c),
                new BigDecimal(p.get("grossAmount").toString()),
                user,
                actor,
                user,
                old == null ? null : old.get("id"),
                n.get("expectedSupersededVersion"),
                n.get("supersededContentDigest"));
        for (var l : lines) {
            var policy = (Map<String, Object>) l.get("policySnapshot");
            var evidence = (Map<String, Object>) l.get("purposeEvidence");
            db.update(
                    "INSERT INTO plugin_finance_cash_allocation_line"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?,?,?)",
                    b.get("tenant_id"),
                    b.get("id"),
                    year,
                    id,
                    l.get("lineNo"),
                    l.get("componentKey"),
                    new BigDecimal(l.get("normalizedAmount").toString()),
                    UUID.fromString(policy.get("policyId").toString()),
                    policy.get("contentDigest"),
                    policy.get("policyVersion"),
                    finance.encode(policy),
                    l.get("businessPurpose"),
                    UUID.fromString(evidence.get("originalId").toString()),
                    evidence.get("version"),
                    evidence.get("sha256"),
                    evidence.get("filename"),
                    l.get("description"),
                    true);
        }
        return id;
    }

    Map<String, Object> releaseCommand(
            Map<String, Object> b,
            UUID year,
            String op,
            Map<String, Object> a,
            Map<String, Object> request,
            UUID rid,
            Map<String, Object> n,
            String actor,
            UUID user) {
        UUID id = (UUID) a.get("id");
        active(b, year, a);
        if (op.equals("release")) {
            version(n, a);
            if (!a.get("approved_dependency_id").toString().equals(n.get("activeDependencyId")))
                throw fail("VERSION_CONFLICT", "须绑定实际ACTIVE分配依赖");
            noRelease(b, year, id);
            noReplacement(b, year, id);
            var c =
                    Map.of(
                            "schemaVersion",
                            SCHEMA,
                            "allocationId",
                            id.toString(),
                            "dependencyId",
                            a.get("approved_dependency_id").toString(),
                            "allocationContentDigest",
                            a.get("content_digest"),
                            "expectedAllocationVersion",
                            a.get("version"),
                            "requestedBy",
                            user.toString(),
                            "reason",
                            n.get("reason"));
            rid = UUID.randomUUID();
            db.update(
                    "INSERT INTO"
                        + " plugin_finance_cash_allocation_release(tenant_id,book_id,year_id,id,allocation_id,dependency_id,allocation_content_digest,expected_allocation_version,content_digest,content,reason,requested_by,requested_username,review_expires_at)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,now()+interval '168 hours')",
                    b.get("tenant_id"),
                    b.get("id"),
                    year,
                    rid,
                    id,
                    a.get("approved_dependency_id"),
                    a.get("content_digest"),
                    a.get("version"),
                    digest(c),
                    finance.encode(c),
                    n.get("reason"),
                    user,
                    actor);
        } else {
            version(n, request);
            content(request);
            if (!"REQUESTED".equals(request.get("status")))
                throw fail("CASH_ALLOCATION_STATE_CONFLICT", "释放请求已处理");
            if (op.equals("release-approve")) {
                fresh(request, "review_expires_at");
                noReplacement(b, year, id);
            }
            db.update(
                    "UPDATE plugin_finance_cash_allocation_release SET"
                        + " status=?,version=version+1,reviewed_by=?,reviewed_at=now(),decision_reason=?"
                        + " WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                    op.equals("release-approve")
                            ? "APPROVED"
                            : op.equals("release-reject") ? "REJECTED" : "CANCELLED",
                    user,
                    n.get("reason"),
                    b.get("tenant_id"),
                    b.get("id"),
                    year,
                    rid);
        }
        return release(b, year, id, rid);
    }

    void replacementPair(
            Map<String, Object> b,
            UUID year,
            Map<String, Object> newA,
            Map<String, Object> old,
            Map<String, Object> n,
            boolean requestBody) {
        active(b, year, old);
        noRelease(b, year, (UUID) old.get("id"));
        if (!"SUBMITTED".equals(newA.get("status"))
                || !old.get("id").equals(newA.get("supersedes_allocation_id"))
                || !old.get("review_id").equals(newA.get("review_id"))
                || !old.get("review_usage_id").equals(newA.get("review_usage_id"))
                || ((Number) newA.get("expected_superseded_version")).longValue()
                        != ((Number) old.get("version")).longValue()
                || !newA.get("superseded_content_digest").equals(old.get("content_digest")))
            throw fail("CASH_ALLOCATION_STATE_CONFLICT", "旧当前效果与新已提交同父替换候选已变化");
        if (requestBody) {
            if (positive(n, "expectedOldVersion") != ((Number) old.get("version")).longValue()
                    || !n.get("oldContentDigest").equals(old.get("content_digest"))
                    || !n.get("oldDependencyId")
                            .equals(old.get("approved_dependency_id").toString())
                    || positive(n, "expectedNewVersion")
                            != ((Number) newA.get("version")).longValue()
                    || !n.get("newContentDigest").equals(newA.get("content_digest")))
                throw fail("VERSION_CONFLICT", "旧新分配版本/摘要/依赖不匹配");
            bind(
                    (Map<String, Object>) n.get("expectedParent"),
                    (Map<String, Object>) content(newA).get("parentSnapshot"));
        }
        fresh(newA, "submission_expires_at");
        revalidate(b, year, newA);
    }

    Map<String, Object> replaceCommand(
            Map<String, Object> b,
            UUID year,
            String op,
            Map<String, Object> a,
            Map<String, Object> old,
            Map<String, Object> request,
            UUID rid,
            Map<String, Object> n,
            String actor,
            UUID user) {
        if (op.equals("replace")) {
            replacementPair(b, year, a, old, n, true);
            noReplacement(b, year, (UUID) old.get("id"));
            noReplacement(b, year, (UUID) a.get("id"));
            var c = new LinkedHashMap<String, Object>(n);
            c.remove("expectedParent");
            c.put("parentReviewId", a.get("review_id").toString());
            c.put("claimId", a.get("claim_id").toString());
            c.put("requestedBy", user.toString());
            rid = UUID.randomUUID();
            db.update(
                    "INSERT INTO"
                        + " plugin_finance_cash_allocation_replacement(tenant_id,book_id,year_id,id,old_allocation_id,old_dependency_id,expected_old_version,old_content_digest,new_allocation_id,expected_new_version,new_content_digest,review_id,claim_id,content_digest,content,reason,requested_by,requested_username,review_expires_at)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,now()+interval '168"
                        + " hours')",
                    b.get("tenant_id"),
                    b.get("id"),
                    year,
                    rid,
                    old.get("id"),
                    old.get("approved_dependency_id"),
                    old.get("version"),
                    old.get("content_digest"),
                    a.get("id"),
                    a.get("version"),
                    a.get("content_digest"),
                    a.get("review_id"),
                    a.get("claim_id"),
                    digest(c),
                    finance.encode(c),
                    n.get("reason"),
                    user,
                    actor);
        } else {
            version(n, request);
            content(request);
            if (!"REQUESTED".equals(request.get("status")))
                throw fail("CASH_ALLOCATION_STATE_CONFLICT", "替换请求已处理");
            if (op.equals("replace-approve")) {
                fresh(request, "review_expires_at");
                replacementPair(b, year, a, old, n, false);
                if (((Number) request.get("expected_old_version")).longValue()
                                != ((Number) old.get("version")).longValue()
                        || ((Number) request.get("expected_new_version")).longValue()
                                != ((Number) a.get("version")).longValue())
                    throw fail("VERSION_CONFLICT", "替换决策旧新版本已变化");
            }
            db.update(
                    "UPDATE plugin_finance_cash_allocation_replacement SET"
                        + " status=?,version=version+1,new_dependency_id=?,reviewed_by=?,reviewed_at=now(),decision_reason=?"
                        + " WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                    op.equals("replace-approve")
                            ? "APPROVED"
                            : op.equals("replace-reject") ? "REJECTED" : "CANCELLED",
                    op.equals("replace-approve") ? UUID.randomUUID() : null,
                    user,
                    n.get("reason"),
                    b.get("tenant_id"),
                    b.get("id"),
                    year,
                    rid);
        }
        return replacement(b, year, rid);
    }
}
