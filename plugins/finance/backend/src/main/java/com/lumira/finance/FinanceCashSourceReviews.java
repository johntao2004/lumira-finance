package com.lumira.finance;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** Human document/fact review and actual typed reliance; never changes a Raw cash assertion. */
@Service
public class FinanceCashSourceReviews {
    static final String SCHEMA = "cash-source-review/1";
    static final Set<String> EXPECTED =
            Set.of(
                    "rawRegistrationId",
                    "rawContentDigest",
                    "claimId",
                    "claimKind",
                    "claimGeneration",
                    "claimContentDigest",
                    "sourceMatchGeneration");
    static final Set<String> CHECKLIST =
            Set.of(
                    "sourceRowInspected",
                    "actualEvidenceBytesInspected",
                    "scopeInstrumentAndAccountInspected",
                    "dateDirectionAndFullAmountMatched",
                    "effectivePostedCashLegMatched",
                    "businessPurposeEvidenceInspected",
                    "noUnsupportedSpecialOrDuplicateFactsKnown",
                    "humanReviewNotBankAuthenticity");
    static final List<String> LIMITATIONS =
            List.of(
                    "HUMAN_DOCUMENT_REVIEW仅为独立人工阅证，不是银行验真或法律税务有效性认证",
                    "Raw仍为UNREVIEWED/UNCLASSIFIED/false；人审不改凭证、来源事实、占用代次或政策",
                    "不依赖政策、不分配、不证明覆盖、不产生报表贡献",
                    "pendingRelease仍保留当前有效人审和ACTIVEusage，冻结未来新增依赖；独立批准释放后才失效",
                    "仅账号UUID独立性，未解决同自然人双账号；7×24小时为运营审签期限",
                    "无allocation/report依赖类型或接口；未来须真实typed消费者及依赖释放协议",
                    "本地Outbox只保留未投递事务信封，无外发或成功通知");
    private final FinanceStore db;
    private final FinanceService finance;
    private final FinanceCashMovements movements;
    private final FinanceCashMatchCorrections corrections;
    private final FinanceCashScopes scopes;

    public FinanceCashSourceReviews(
            FinanceStore db,
            FinanceService finance,
            FinanceCashMovements movements,
            FinanceCashMatchCorrections corrections,
            FinanceCashScopes scopes) {
        this.db = db;
        this.finance = finance;
        this.movements = movements;
        this.corrections = corrections;
        this.scopes = scopes;
    }

    static FinanceException fail(String code, String message) {
        return new FinanceException(code, message);
    }

    static String text(Map<String, Object> in, String key, int max) {
        return FinanceCashScopes.text(in, key, max);
    }

    static long positive(Map<String, Object> in, String key) {
        return FinanceCashMatchCorrections.number(in, key, false);
    }

    static UUID uuid(Map<String, Object> in, String key) {
        return FinanceService.id(in, key);
    }

    String digest(Object value) {
        return scopes.digest(value);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> expected(Object value) {
        if (!(value instanceof Map<?, ?>)) throw FinanceException.invalid("expectedClaim须为封闭对象");
        var in = (Map<String, Object>) value;
        FinanceCashScopes.fields(in, EXPECTED);
        var result = new LinkedHashMap<String, Object>(in);
        for (String key : List.of("rawRegistrationId", "claimId"))
            result.put(key, uuid(in, key).toString());
        for (String key : List.of("claimGeneration", "sourceMatchGeneration"))
            result.put(key, positive(in, key));
        for (String key : List.of("rawContentDigest", "claimContentDigest"))
            if (!(in.get(key) instanceof String s) || !s.matches("[a-f0-9]{64}"))
                throw FinanceException.invalid("claim摘要格式无效");
        if (!(in.get("claimKind") instanceof String kind)
                || !Set.of("RAW_REGISTRATION", "MATCH_CORRECTION").contains(kind))
            throw FinanceException.invalid("claimKind无效");
        return result;
    }

    Map<String, Object> expectedFrom(Map<String, Object> snapshot) {
        var result = new LinkedHashMap<String, Object>();
        for (String key : EXPECTED) result.put(key, snapshot.get(key));
        return expected(result);
    }

    Map<String, Object> author(String actor, UUID book, UUID year, boolean owner) {
        var b = finance.originalScope(actor, book, year, owner ? "manage" : "create");
        if (owner) finance.originalScope(actor, book, year, "create");
        return b;
    }

    Map<String, Object> authorize(String actor, UUID book, UUID year, String op) {
        if (Set.of("create", "submit", "cancel").contains(op))
            return author(actor, book, year, false);
        if (op.equals("release") || op.equals("release-cancel"))
            return author(actor, book, year, true);
        var b = finance.originalScope(actor, book, year, "approve");
        if (op.equals("approve") || op.equals("release-approve"))
            finance.originalScope(actor, book, year, "read");
        return b;
    }

    void currentAuthor(Map<String, Object> b, UUID year, Object name, Object id, boolean owner) {
        try {
            var current = author(name.toString(), (UUID) b.get("id"), year, owner);
            if (!Objects.equals(current.get("actor_id"), id)) throw new IllegalArgumentException();
        } catch (RuntimeException stale) {
            throw fail("APPROVAL_STALE", "当前人审制备人或释放请求人的制备/年度授权已失效");
        }
    }

    void independent(
            UUID actor, Map<String, Object> raw, Map<String, Object> review, Object requester) {
        if (actor.equals(raw.get("prepared_by"))
                || actor.equals(review.get("prepared_by"))
                || actor.equals(review.get("last_edited_by"))
                || actor.equals(requester))
            throw fail("SELF_APPROVAL_FORBIDDEN", "人审或释放复核须独立于来源制备人、人审制备人和请求人");
    }

    static final String SELECT =
            "SELECT r.id,r.raw_registration_id AS \"rawRegistrationId\",r.claim_id AS"
                + " \"claimId\",r.claim_kind AS \"claimKind\",r.claim_generation AS"
                + " \"claimGeneration\",r.content_digest AS \"contentDigest\",r.content_version AS"
                + " \"contentVersion\",r.version,r.status,r.prepared_by AS"
                + " \"preparedBy\",r.created_at AS \"createdAt\",r.submitted_at AS"
                + " \"submittedAt\",r.submission_expires_at AS"
                + " \"submissionExpiresAt\",r.approved_by AS \"approvedBy\",r.approved_at AS"
                + " \"approvedAt\",r.decision_by AS \"decisionBy\",r.decision_at AS"
                + " \"decisionAt\",r.decision_reason AS \"decisionReason\",(SELECT u.id FROM"
                + " plugin_finance_cash_claim_usage u WHERE"
                + " (u.tenant_id,u.book_id,u.year_id,u.id,u.source_review_id)=(r.tenant_id,r.book_id,r.year_id,r.approved_usage_id,r.id)"
                + " AND u.state='ACTIVE') AS \"activeUsageId\",r.released_by AS"
                + " \"releasedBy\",r.released_at AS \"releasedAt\",(r.status='APPROVED' AND"
                + " EXISTS(SELECT 1 FROM plugin_finance_cash_claim_usage u JOIN"
                + " plugin_finance_cash_leg_claim c ON"
                + " (c.tenant_id,c.book_id,c.year_id,c.id)=(u.tenant_id,u.book_id,u.year_id,u.claim_id)"
                + " WHERE"
                + " (u.tenant_id,u.book_id,u.year_id,u.id,u.source_review_id)=(r.tenant_id,r.book_id,r.year_id,r.approved_usage_id,r.id)"
                + " AND u.state='ACTIVE' AND c.state='ACTIVE')) AS \"currentEffective\",false AS"
                + " \"formalReportContributionEnabled\",false AS \"bankAuthenticityVerified\" FROM"
                + " plugin_finance_cash_source_review r WHERE r.tenant_id=? AND r.book_id=? AND"
                + " r.year_id=?";
    static final String RELEASE =
            "SELECT r.id,r.review_id AS \"reviewId\",r.usage_id AS"
                + " \"usageId\",r.expected_review_version AS"
                + " \"expectedReviewVersion\",r.review_content_digest AS"
                + " \"reviewContentDigest\",r.status,r.version,r.content_digest AS"
                + " \"contentDigest\",r.reason,r.requested_by AS \"requestedBy\",r.requested_at AS"
                + " \"requestedAt\",r.review_expires_at AS \"reviewExpiresAt\",r.reviewed_by AS"
                + " \"reviewedBy\",r.reviewed_at AS \"reviewedAt\",r.decision_reason AS"
                + " \"decisionReason\" FROM plugin_finance_cash_source_review_release r WHERE"
                + " r.tenant_id=? AND r.book_id=? AND r.year_id=?";

    Map<String, Object> row(Map<String, Object> b, UUID year, UUID id) {
        return db.one(
                "SELECT *,content::text AS content_text FROM plugin_finance_cash_source_review"
                        + " WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                b.get("tenant_id"),
                b.get("id"),
                year,
                id);
    }

    Map<String, Object> release(Map<String, Object> b, UUID year, UUID review, UUID id) {
        return db.one(
                RELEASE + " AND r.review_id=? AND r.id=?",
                b.get("tenant_id"),
                b.get("id"),
                year,
                review,
                id);
    }

    Map<String, Object> content(Map<String, Object> row) {
        var c = finance.decode((String) row.get("content_text"));
        if (!digest(c).equals(row.get("content_digest")))
            throw fail("APPROVAL_STALE", "不可变人审或释放请求摘要不匹配");
        return c;
    }

    Map<String, Object> watermark(Map<String, Object> b, UUID year, UUID raw) {
        var rows =
                db.list(
                        "SELECT review_version FROM plugin_finance_cash_source_review_guard WHERE"
                                + " tenant_id=? AND book_id=? AND year_id=?",
                        b.get("tenant_id"),
                        b.get("id"),
                        year);
        return Map.of(
                "sourceReviewVersion",
                rows.isEmpty() ? 0L : rows.getFirst().get("review_version"),
                "yearMatchVersion",
                db.one(
                                "SELECT match_version FROM plugin_finance_cash_match_guard WHERE"
                                        + " tenant_id=? AND book_id=? AND year_id=?",
                                b.get("tenant_id"),
                                b.get("id"),
                                year)
                        .get("match_version"),
                "sourceMatchGeneration",
                corrections.generation(b, year, raw),
                "year",
                finance.watermark(b, year));
    }

    Map<String, Object> detail(Map<String, Object> b, UUID year, UUID id) {
        var review = row(b, year, id);
        var c = content(review);
        var result = db.one(SELECT + " AND r.id=?", b.get("tenant_id"), b.get("id"), year, id);
        for (String key :
                List.of(
                        "tenantId",
                        "bookId",
                        "yearId",
                        "legalEntityId",
                        "claimSnapshot",
                        "businessPurposeStatement",
                        "factReviewStatement",
                        "checklist",
                        "evidence",
                        "conclusion",
                        "method",
                        "reason")) result.put(key, c.get(key));
        var pending =
                db.list(
                        RELEASE + " AND r.review_id=? AND r.status='REQUESTED'",
                        b.get("tenant_id"),
                        b.get("id"),
                        year,
                        id);
        result.put("pendingRelease", pending.isEmpty() ? null : pending.getFirst());
        result.put("watermark", watermark(b, year, (UUID) review.get("raw_registration_id")));
        result.put("limitations", LIMITATIONS);
        return result;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> detail(String actor, UUID book, UUID year, UUID id) {
        return detail(finance.originalScope(actor, book, year, "read"), year, id);
    }

    Map<String, Object> snapshot(Map<String, Object> b, UUID year, UUID rawId, UUID claimId) {
        var raw = movements.row(b, year, rawId);
        movements.detail(b, year, rawId);
        var claim =
                db.one(
                        "SELECT * FROM plugin_finance_cash_leg_claim WHERE tenant_id=? AND"
                                + " book_id=? AND year_id=? AND id=? AND raw_registration_id=?",
                        b.get("tenant_id"),
                        b.get("id"),
                        year,
                        claimId,
                        rawId);
        if (!"ACTIVE".equals(claim.get("state")))
            throw fail("CASH_REVIEW_CLAIM_STALE", "来源占用已释放，不能自动改绑其他占用");
        if (!db.list(
                        "SELECT id FROM plugin_finance_cash_match_withdrawal WHERE tenant_id=? AND"
                                + " book_id=? AND year_id=? AND active_claim_id=? AND"
                                + " status='REQUESTED'",
                        b.get("tenant_id"),
                        b.get("id"),
                        year,
                        claimId)
                .isEmpty()) throw fail("CASH_REVIEW_PENDING_WITHDRAWAL", "更正匹配正在独立撤回审签，不能新增人审依赖");
        if ("MATCH_CORRECTION".equals(claim.get("claim_kind"))) {
            var correction = corrections.correction(b, year, (UUID) claim.get("correction_id"));
            corrections.verifiedContent(correction);
            if (!"APPROVED".equals(correction.get("status"))
                    || !claimId.equals(correction.get("approved_claim_id")))
                throw fail("CASH_REVIEW_CLAIM_STALE", "更正不再是当前已批准有效匹配");
        }
        try {
            return finance.decode(
                    db.one(
                                    "SELECT plugin_finance_cash_review_snapshot(?,?,?,?,?)::text AS"
                                            + " value",
                                    b.get("tenant_id"),
                                    b.get("id"),
                                    year,
                                    rawId,
                                    claimId)
                            .get("value")
                            .toString());
        } catch (org.springframework.dao.DataAccessException stale) {
            throw fail("CASH_REVIEW_CLAIM_STALE", "当前来源、原件、科目、范围或已记账事实已偏离快照");
        }
    }

    void bindExpected(Map<String, Object> expected, Map<String, Object> snapshot) {
        if (!digest(expected).equals(digest(expectedFrom(snapshot))))
            throw fail("CASH_REVIEW_CLAIM_STALE", "expectedClaim与不可变人审引用不一致");
    }

    Map<String, Object> page(
            Map<String, Object> b,
            UUID year,
            String kind,
            String sql,
            List<Object> params,
            String alias,
            String stamp,
            String outputStamp,
            int limit,
            String cursor) {
        if (limit < 1 || limit > 100) throw FinanceException.invalid("每页数量须1至100");
        if (cursor != null) {
            try {
                if (cursor.length() > 2000) throw new IllegalArgumentException();
                var c =
                        finance.decode(
                                new String(
                                        Base64.getUrlDecoder().decode(cursor),
                                        StandardCharsets.UTF_8));
                if (!c.keySet().equals(Set.of("v", "book", "year", "kind", "stamp", "id"))
                        || !Integer.valueOf(1).equals(c.get("v"))
                        || !b.get("id").toString().equals(c.get("book"))
                        || !year.toString().equals(c.get("year"))
                        || !kind.equals(c.get("kind"))) throw new IllegalArgumentException();
                var id = UUID.fromString((String) c.get("id"));
                var time = Instant.parse((String) c.get("stamp"));
                if (!id.toString().equals(c.get("id"))
                        || !time.toString().equals(c.get("stamp"))
                        || db.list(
                                        sql
                                                + " AND "
                                                + alias
                                                + ".id=? AND "
                                                + alias
                                                + "."
                                                + stamp
                                                + "=?",
                                        FinanceCashMovements.concat(
                                                params, id, time.atOffset(ZoneOffset.UTC)))
                                .isEmpty()) throw new IllegalArgumentException();
                sql += " AND (" + alias + "." + stamp + "," + alias + ".id)<(?,?)";
                params.add(time.atOffset(ZoneOffset.UTC));
                params.add(id);
            } catch (RuntimeException invalid) {
                throw FinanceException.invalid("人审游标范围、筛选或锚点无效");
            }
        }
        var rows =
                db.list(
                        sql
                                + " ORDER BY "
                                + alias
                                + "."
                                + stamp
                                + " DESC,"
                                + alias
                                + ".id DESC LIMIT ?",
                        FinanceCashMovements.concat(params, limit + 1));
        boolean more = rows.size() > limit;
        var items = new ArrayList<>(rows.subList(0, Math.min(limit, rows.size())));
        String next = null;
        if (more) {
            var last = items.getLast();
            next =
                    Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(
                                    finance.encode(
                                                    Map.of(
                                                            "v",
                                                            1,
                                                            "book",
                                                            b.get("id").toString(),
                                                            "year",
                                                            year.toString(),
                                                            "kind",
                                                            kind,
                                                            "stamp",
                                                            FinancePages.time(
                                                                    last.get(outputStamp)),
                                                            "id",
                                                            last.get("id").toString()))
                                            .getBytes(StandardCharsets.UTF_8));
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("items", items);
        result.put("nextCursor", next);
        return result;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> list(
            String actor,
            UUID book,
            UUID year,
            UUID raw,
            UUID claim,
            String status,
            int limit,
            String cursor) {
        var b = finance.originalScope(actor, book, year, "read");
        if (status != null
                && !Set.of("DRAFT", "SUBMITTED", "APPROVED", "REJECTED", "CANCELLED", "RELEASED")
                        .contains(status)) throw FinanceException.invalid("人审状态无效");
        var p = new ArrayList<Object>(List.of(b.get("tenant_id"), book, year));
        String sql = SELECT;
        if (raw != null) {
            sql += " AND r.raw_registration_id=?";
            p.add(raw);
        }
        if (claim != null) {
            sql += " AND r.claim_id=?";
            p.add(claim);
        }
        if (status != null) {
            sql += " AND r.status=?";
            p.add(status);
        }
        return page(
                b,
                year,
                "REVIEWS:" + raw + ":" + claim + ":" + status,
                sql,
                p,
                "r",
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
        return page(
                b,
                year,
                "RELEASES:" + id,
                RELEASE + " AND r.review_id=?",
                new ArrayList<>(List.of(b.get("tenant_id"), book, year, id)),
                "r",
                "requested_at",
                "requestedAt",
                limit,
                cursor);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> eligible(
            String actor, UUID book, UUID year, UUID raw, UUID claim, int limit, String cursor) {
        var b = finance.originalScope(actor, book, year, "read");
        var p = new ArrayList<Object>(List.of(b.get("tenant_id"), book, year));
        String sql =
                "SELECT c.id,c.raw_registration_id AS \"rawRegistrationId\",c.created_at AS"
                    + " \"createdAt\" FROM plugin_finance_cash_leg_claim c WHERE c.tenant_id=? AND"
                    + " c.book_id=? AND c.year_id=? AND c.state='ACTIVE' AND NOT EXISTS(SELECT 1"
                    + " FROM plugin_finance_cash_match_withdrawal w WHERE"
                    + " (w.tenant_id,w.book_id,w.year_id,w.active_claim_id)=(c.tenant_id,c.book_id,c.year_id,c.id)"
                    + " AND w.status='REQUESTED')";
        if (raw != null) {
            movements.row(b, year, raw);
            sql += " AND c.raw_registration_id=?";
            p.add(raw);
        }
        if (claim != null) {
            db.one(
                    "SELECT id FROM plugin_finance_cash_leg_claim WHERE tenant_id=? AND book_id=?"
                            + " AND year_id=? AND id=?",
                    b.get("tenant_id"),
                    book,
                    year,
                    claim);
            sql += " AND c.id=?";
            p.add(claim);
        }
        var page =
                page(
                        b,
                        year,
                        "ELIGIBLE:" + raw + ":" + claim,
                        sql,
                        p,
                        "c",
                        "created_at",
                        "createdAt",
                        limit,
                        cursor);
        var items = new ArrayList<Map<String, Object>>();
        for (var r : (List<Map<String, Object>>) page.get("items"))
            items.add(snapshot(b, year, (UUID) r.get("rawRegistrationId"), (UUID) r.get("id")));
        page.put("items", items);
        return page;
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> evidence(Map<String, Object> b, UUID year, Object value) {
        if (!(value instanceof List<?> values) || values.isEmpty() || values.size() > 10)
            throw FinanceException.invalid("须保留1至10份真实用途证据");
        var result = new ArrayList<Map<String, Object>>();
        Set<UUID> ids = new HashSet<>();
        boolean purpose = false;
        for (Object v : values) {
            if (!(v instanceof Map<?, ?>)) throw FinanceException.invalid("证据对象无效");
            var in = (Map<String, Object>) v;
            FinanceCashScopes.fields(in, Set.of("originalId", "purpose", "description"));
            UUID id = uuid(in, "originalId");
            String role = text(in, "purpose", 40);
            if (!ids.add(id)
                    || !Set.of("BUSINESS_PURPOSE_SUPPORT", "FACT_CORROBORATION").contains(role))
                throw FinanceException.invalid("证据ID须唯一，role须闭合");
            purpose |= role.equals("BUSINESS_PURPOSE_SUPPORT");
            var o = scopes.evidence(b, year, id);
            result.add(
                    Map.of(
                            "originalId",
                            id.toString(),
                            "purpose",
                            role,
                            "description",
                            text(in, "description", 500),
                            "originalVersion",
                            o.get("version"),
                            "sha256",
                            o.get("sha256"),
                            "filename",
                            o.get("filename")));
        }
        if (!purpose) throw fail("CASH_REVIEW_UNSUPPORTED_FACTS", "必须实际提供普通业务用途支持证据");
        return result;
    }

    @SuppressWarnings("unchecked")
    void revalidateEvidence(Map<String, Object> b, UUID year, Map<String, Object> c) {
        for (var e : (List<Map<String, Object>>) c.get("evidence")) {
            var o = scopes.evidence(b, year, UUID.fromString(e.get("originalId").toString()));
            if (!o.get("sha256").equals(e.get("sha256"))
                    || !o.get("filename").equals(e.get("filename"))
                    || ((Number) o.get("version")).longValue()
                            != ((Number) e.get("originalVersion")).longValue())
                throw fail("APPROVAL_STALE", "人审用途证据版本或原件已失效");
        }
    }

    Map<String, Object> normalize(String op, Map<String, Object> input) {
        Set<String> keys =
                op.equals("create")
                        ? Set.of(
                                "schemaVersion",
                                "idempotencyKey",
                                "expectedClaim",
                                "businessPurposeStatement",
                                "factReviewStatement",
                                "checklist",
                                "evidence",
                                "conclusion",
                                "reason")
                        : op.equals("release")
                                ? Set.of(
                                        "schemaVersion",
                                        "idempotencyKey",
                                        "expectedVersion",
                                        "contentDigest",
                                        "activeUsageId",
                                        "expectedClaim",
                                        "reason")
                                : op.startsWith("release-")
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
                                                "expectedClaim",
                                                "reason");
        FinanceCashScopes.fields(input, keys);
        if (!SCHEMA.equals(input.get("schemaVersion")))
            throw FinanceException.invalid("人审schemaVersion无效");
        var n = new LinkedHashMap<String, Object>(input);
        n.remove("idempotencyKey");
        n.put("reason", text(input, "reason", 1000));
        if (input.containsKey("expectedClaim"))
            n.put("expectedClaim", expected(input.get("expectedClaim")));
        if (op.equals("create")) {
            n.put("businessPurposeStatement", text(input, "businessPurposeStatement", 2000));
            n.put("factReviewStatement", text(input, "factReviewStatement", 4000));
            if (!"SUPPORTED_FOR_PREPARATION".equals(input.get("conclusion"))
                    || !(input.get("checklist") instanceof Map<?, ?> c)
                    || !c.keySet().equals(CHECKLIST)
                    || c.values().stream().anyMatch(v -> !Boolean.TRUE.equals(v)))
                throw fail("CASH_REVIEW_UNSUPPORTED_FACTS", "只开放完整人工阅证确认的普通事实，不支持缺项、未知或复杂事实");
        } else {
            n.put("expectedVersion", positive(input, "expectedVersion"));
            if (!(input.get("contentDigest") instanceof String d) || !d.matches("[a-f0-9]{64}"))
                throw FinanceException.invalid("人审摘要格式无效");
            if (op.equals("release"))
                n.put("activeUsageId", uuid(input, "activeUsageId").toString());
        }
        return n;
    }

    void version(Map<String, Object> n, Map<String, Object> row) {
        if (positive(n, "expectedVersion") != ((Number) row.get("version")).longValue()
                || !n.get("contentDigest").equals(row.get("content_digest")))
            throw fail("VERSION_CONFLICT", "人审或释放请求版本/摘要不匹配");
    }

    void fresh(Map<String, Object> row, String column) {
        if (Boolean.TRUE.equals(
                db.one("SELECT ?::timestamptz<=clock_timestamp() AS expired", row.get(column))
                        .get("expired")))
            throw fail("CASH_REVIEW_SUBMISSION_EXPIRED", "7×24小时运营审签期限已过，可拒绝/取消后新建不可变版本");
    }

    @Transactional
    public Map<String, Object> write(
            String actor,
            UUID book,
            UUID year,
            String op,
            UUID id,
            UUID releaseId,
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
                        "release-cancel")
                .contains(op)) throw FinanceException.invalid("未知人审动作");
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
        Map<String, Object> review = op.equals("create") ? null : row(b, year, id);
        var expectedClaim = (Map<String, Object>) n.get("expectedClaim");
        UUID
                rawId =
                        op.equals("create")
                                ? UUID.fromString(expectedClaim.get("rawRegistrationId").toString())
                                : (UUID) review.get("raw_registration_id"),
                claimId =
                        op.equals("create")
                                ? UUID.fromString(expectedClaim.get("claimId").toString())
                                : (UUID) review.get("claim_id");
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
                        + " year_id=? AND id=? AND raw_registration_id=? FOR UPDATE",
                b.get("tenant_id"),
                book,
                year,
                claimId,
                rawId);
        db.update(
                "INSERT INTO plugin_finance_cash_source_review_guard(tenant_id,book_id,year_id)"
                        + " VALUES(?,?,?) ON CONFLICT DO NOTHING",
                b.get("tenant_id"),
                book,
                year);
        db.one(
                "SELECT review_version FROM plugin_finance_cash_source_review_guard WHERE"
                        + " tenant_id=? AND book_id=? AND year_id=? FOR UPDATE",
                b.get("tenant_id"),
                book,
                year);
        if (review != null) {
            review =
                    db.one(
                            "SELECT *,content::text AS content_text FROM"
                                    + " plugin_finance_cash_source_review WHERE tenant_id=? AND"
                                    + " book_id=? AND year_id=? AND id=? FOR UPDATE",
                            b.get("tenant_id"),
                            book,
                            year,
                            id);
            if (Set.of("submit", "cancel").contains(op) && !user.equals(review.get("prepared_by")))
                throw fail("SCOPE_DENIED", "仅原人审制备人可提交/取消");
            if (op.equals("approve") || op.equals("reject")) independent(user, raw, review, null);
            if (op.equals("approve"))
                currentAuthor(
                        b, year, review.get("prepared_username"), review.get("prepared_by"), false);
        }
        Map<String, Object> request = null;
        if (op.startsWith("release-")) {
            request =
                    db.one(
                            "SELECT *,content::text AS content_text FROM"
                                + " plugin_finance_cash_source_review_release WHERE tenant_id=? AND"
                                + " book_id=? AND year_id=? AND review_id=? AND id=? FOR UPDATE",
                            b.get("tenant_id"),
                            book,
                            year,
                            id,
                            releaseId);
            if (op.equals("release-cancel")) {
                if (!user.equals(request.get("requested_by")))
                    throw fail("SCOPE_DENIED", "仅原释放请求人可取消");
            } else independent(user, raw, review, request.get("requested_by"));
            if (op.equals("release-approve"))
                currentAuthor(
                        b,
                        year,
                        request.get("requested_username"),
                        request.get("requested_by"),
                        true);
        }
        String route = book + "/" + year + "/source-reviews/" + id + "/" + op + "/" + releaseId,
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
                throw fail("IDEMPOTENCY_CONFLICT", "请求键已用于不同人审命令");
            var cached = finance.decode((String) prior.get("response"));
            if (op.startsWith("release"))
                return release(
                        b,
                        year,
                        UUID.fromString(cached.get("reviewId").toString()),
                        UUID.fromString(cached.get("id").toString()));
            return detail(b, year, UUID.fromString(cached.get("id").toString()));
        }
        Map<String, Object> result;
        if (op.equals("create")) {
            var snap = snapshot(b, year, rawId, claimId);
            bindExpected(expectedClaim, snap);
            var existing =
                    db.list(
                            "SELECT status FROM plugin_finance_cash_source_review WHERE tenant_id=?"
                                    + " AND book_id=? AND year_id=? AND claim_id=? AND status"
                                    + " IN('DRAFT','SUBMITTED','APPROVED')",
                            b.get("tenant_id"),
                            book,
                            year,
                            claimId);
            if (!existing.isEmpty())
                throw fail(
                        "APPROVED".equals(existing.getFirst().get("status"))
                                ? "CASH_REVIEW_ALREADY_EFFECTIVE"
                                : "CASH_REVIEW_PENDING_EXISTS",
                        "已有有效或待审人审，须显式终结/独立释放后再建");
            var evidence = evidence(b, year, n.get("evidence"));
            var c = new LinkedHashMap<String, Object>(n);
            c.remove("expectedClaim");
            c.put("claimSnapshot", snap);
            c.put("evidence", evidence);
            c.put("tenantId", b.get("tenant_id").toString());
            c.put("bookId", book.toString());
            c.put("yearId", year.toString());
            c.put("legalEntityId", raw.get("legal_entity_id").toString());
            c.put("preparedBy", user.toString());
            c.put("method", "HUMAN_DOCUMENT_REVIEW");
            c.put("rawSnapshot", finance.decode(raw.get("content_text").toString()));
            id = UUID.randomUUID();
            db.update(
                    "INSERT INTO"
                        + " plugin_finance_cash_source_review(tenant_id,book_id,year_id,id,legal_entity_id,raw_registration_id,claim_id,claim_kind,claim_generation,claim_content_digest,source_match_generation,content_digest,content,prepared_by,prepared_username,last_edited_by)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?)",
                    b.get("tenant_id"),
                    book,
                    year,
                    id,
                    raw.get("legal_entity_id"),
                    rawId,
                    claimId,
                    snap.get("claimKind"),
                    snap.get("claimGeneration"),
                    snap.get("claimContentDigest"),
                    snap.get("sourceMatchGeneration"),
                    digest(c),
                    finance.encode(c),
                    user,
                    actor,
                    user);
            int index = 0;
            for (var e : evidence)
                db.update(
                        "INSERT INTO plugin_finance_cash_source_review_evidence"
                                + " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                        b.get("tenant_id"),
                        book,
                        year,
                        id,
                        ++index,
                        UUID.fromString(e.get("originalId").toString()),
                        e.get("originalVersion"),
                        e.get("sha256"),
                        e.get("filename"),
                        e.get("purpose"),
                        e.get("description"));
            result = detail(b, year, id);
        } else if (op.startsWith("release")) {
            result = releaseCommand(b, year, op, review, request, releaseId, n, actor, user);
        } else {
            version(n, review);
            var c = content(review);
            bindExpected(expectedClaim, (Map<String, Object>) c.get("claimSnapshot"));
            String state = review.get("status").toString();
            if (op.equals("cancel")) {
                if (!Set.of("DRAFT", "SUBMITTED").contains(state))
                    throw fail("CASH_REVIEW_STATE_CONFLICT", "仅待生效人审可取消");
                decision(b, year, id, "CANCELLED", user, n.get("reason"));
            } else if (op.equals("submit")) {
                if (!"DRAFT".equals(state)) throw fail("CASH_REVIEW_STATE_CONFLICT", "仅草稿可提交");
                if (!digest(snapshot(b, year, rawId, claimId))
                        .equals(digest(c.get("claimSnapshot"))))
                    throw fail("CASH_REVIEW_CLAIM_STALE", "来源事实已偏离草稿");
                revalidateEvidence(b, year, c);
                db.update(
                        "UPDATE plugin_finance_cash_source_review SET"
                            + " status='SUBMITTED',version=version+1,submitted_at=now(),submission_expires_at=now()+interval"
                            + " '168 hours' WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                        b.get("tenant_id"),
                        book,
                        year,
                        id);
            } else {
                if ((op.equals("approve") && !"SUBMITTED".equals(state))
                        || (op.equals("reject") && !Set.of("DRAFT", "SUBMITTED").contains(state)))
                    throw fail("CASH_REVIEW_STATE_CONFLICT", "批准仅限已提交；独立拒绝可终结草稿或已提交人审");
                if (op.equals("approve")) {
                    fresh(review, "submission_expires_at");
                    if (!digest(snapshot(b, year, rawId, claimId))
                            .equals(digest(c.get("claimSnapshot"))))
                        throw fail("CASH_REVIEW_CLAIM_STALE", "来源占用或事实已变化");
                    revalidateEvidence(b, year, c);
                    db.update(
                            "UPDATE plugin_finance_cash_source_review SET"
                                + " status='APPROVED',version=version+1,approved_by=?,approved_at=now(),approved_usage_id=?,decision_by=?,decision_at=now(),decision_reason=?"
                                + " WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                            user,
                            UUID.randomUUID(),
                            user,
                            n.get("reason"),
                            b.get("tenant_id"),
                            book,
                            year,
                            id);
                } else decision(b, year, id, "REJECTED", user, n.get("reason"));
            }
            result = detail(b, year, id);
        }
        finance.originalAudit(
                user,
                b,
                year,
                "CASH_SOURCE_REVIEW_" + op.toUpperCase(Locale.ROOT).replace('-', '_'),
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

    void decision(
            Map<String, Object> b, UUID year, UUID id, String status, UUID user, Object reason) {
        db.update(
                "UPDATE plugin_finance_cash_source_review SET"
                    + " status=?,version=version+1,decision_by=?,decision_at=now(),decision_reason=?"
                    + " WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                status,
                user,
                reason,
                b.get("tenant_id"),
                b.get("id"),
                year,
                id);
    }

    Map<String, Object> releaseCommand(
            Map<String, Object> b,
            UUID year,
            String op,
            Map<String, Object> review,
            Map<String, Object> request,
            UUID releaseId,
            Map<String, Object> n,
            String actor,
            UUID user) {
        UUID id = (UUID) review.get("id");
        if (op.equals("release") || op.equals("release-approve")) {
            if (!db.list(
                                    "SELECT id FROM plugin_finance_cash_allocation_dependency WHERE"
                                        + " tenant_id=? AND book_id=? AND year_id=? AND review_id=?"
                                        + " AND state='ACTIVE'",
                                    b.get("tenant_id"),
                                    b.get("id"),
                                    year,
                                    id)
                            .isEmpty()
                    || !db.list(
                                    "SELECT id FROM plugin_finance_cash_allocation_replacement"
                                        + " WHERE tenant_id=? AND book_id=? AND year_id=? AND"
                                        + " review_id=? AND status='REQUESTED'",
                                    b.get("tenant_id"),
                                    b.get("id"),
                                    year,
                                    id)
                            .isEmpty())
                throw fail("CASH_REVIEW_IN_USE", "人审已有实际ACTIVE分配依赖或待审替换，须先独立终结该依赖");
        }
        if (!"APPROVED".equals(review.get("status")))
            throw fail("CASH_REVIEW_STATE_CONFLICT", "仅实际有效APPROVED人审可请求或审签释放");
        var usage =
                db.one(
                        "SELECT * FROM plugin_finance_cash_claim_usage WHERE tenant_id=? AND"
                                + " book_id=? AND year_id=? AND id=? AND source_review_id=? AND"
                                + " state='ACTIVE' FOR UPDATE",
                        b.get("tenant_id"),
                        b.get("id"),
                        year,
                        review.get("approved_usage_id"),
                        id);
        if (op.equals("release")) {
            version(n, review);
            var c = content(review);
            bindExpected(
                    (Map<String, Object>) n.get("expectedClaim"),
                    (Map<String, Object>) c.get("claimSnapshot"));
            if (!usage.get("id").toString().equals(n.get("activeUsageId")))
                throw fail("VERSION_CONFLICT", "须绑定当前ACTIVE usage");
            if (!db.list(
                            RELEASE + " AND r.review_id=? AND r.status='REQUESTED'",
                            b.get("tenant_id"),
                            b.get("id"),
                            year,
                            id)
                    .isEmpty()) throw fail("CASH_REVIEW_RELEASE_PENDING", "已有待审释放请求，旧usage仍保护当前人审");
            var rc =
                    Map.of(
                            "schemaVersion",
                            SCHEMA,
                            "reviewId",
                            id.toString(),
                            "usageId",
                            usage.get("id").toString(),
                            "expectedReviewVersion",
                            review.get("version"),
                            "reviewContentDigest",
                            review.get("content_digest"),
                            "requestedBy",
                            user.toString(),
                            "reason",
                            n.get("reason"));
            releaseId = UUID.randomUUID();
            db.update(
                    "INSERT INTO"
                        + " plugin_finance_cash_source_review_release(tenant_id,book_id,year_id,id,review_id,usage_id,claim_id,raw_registration_id,claim_generation,claim_content_digest,review_content_digest,expected_review_version,content_digest,content,reason,requested_by,requested_username,review_expires_at)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,now()+interval '168"
                        + " hours')",
                    b.get("tenant_id"),
                    b.get("id"),
                    year,
                    releaseId,
                    id,
                    usage.get("id"),
                    usage.get("claim_id"),
                    usage.get("raw_registration_id"),
                    usage.get("claim_generation"),
                    usage.get("claim_content_digest"),
                    review.get("content_digest"),
                    review.get("version"),
                    digest(rc),
                    finance.encode(rc),
                    n.get("reason"),
                    user,
                    actor);
        } else {
            version(n, request);
            content(request);
            if (!"REQUESTED".equals(request.get("status")))
                throw fail("CASH_REVIEW_STATE_CONFLICT", "释放请求已处理");
            if (op.equals("release-approve")) {
                fresh(request, "review_expires_at");
                if (!db.list(
                                "SELECT id FROM plugin_finance_cash_claim_usage WHERE tenant_id=?"
                                    + " AND book_id=? AND year_id=? AND claim_id=? AND id<>? AND"
                                    + " state='ACTIVE'",
                                b.get("tenant_id"),
                                b.get("id"),
                                year,
                                usage.get("claim_id"),
                                usage.get("id"))
                        .isEmpty()) throw fail("CASH_REVIEW_IN_USE", "存在其他有效typed依赖，不能释放");
            }
            db.update(
                    "UPDATE plugin_finance_cash_source_review_release SET"
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
                    releaseId);
        }
        return release(b, year, id, releaseId);
    }
}
