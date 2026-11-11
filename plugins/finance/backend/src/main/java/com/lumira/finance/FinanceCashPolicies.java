package com.lumira.finance;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** Bounded policy publication, not cash source review, claim consumption or report preparation. */
@Service
public class FinanceCashPolicies {
    static final String SCHEMA = "cash-classification-policy/1";
    static final Set<String> ASSERTIONS =
            Set.of(
                    "ordinaryGrossOnly",
                    "singleLegalEntity",
                    "cnyOnly",
                    "purposeEvidenceRequiredForEachFutureUse",
                    "noAutomaticAccountInference",
                    "noInternalTransferRefundFxControlOrNetting",
                    "noUnsupportedCapitalizedPayrollInterestOrTaxComponents");
    static final List<String> LIMITATIONS =
            List.of(
                    "仅发布普通业务政策；不自动按科目或来源kind推断真实业务用途",
                    "不核实银行真实性、税收资格或逐笔用途证据，不产生人审、分配、覆盖或报表贡献",
                    "期限为7×24小时运营审签时限，不是法定期限；已批准历史不随待审时限失效",
                    "未来消费仍须真实occurredDate在政策范围内且实际年/月开放；本片无消费端点",
                    "复杂资本化工资、利息、未知税款、内部转账、退款、外币及控制交易不支持",
                    "独立性按账号UUID，同自然人双账号尚未解决",
                    "政策Outbox仅保存事务信封，未实现投递或消费者");
    private final FinanceStore db;
    private final FinanceService finance;
    private final FinanceCashScopes scopes;

    public FinanceCashPolicies(FinanceStore db, FinanceService finance, FinanceCashScopes scopes) {
        this.db = db;
        this.finance = finance;
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

    Map<String, Object> author(String actor, UUID book, UUID year) {
        var b = finance.originalScope(actor, book, year, "manage");
        finance.originalScope(actor, book, year, "create");
        return b;
    }

    Map<String, Object> authorize(String actor, UUID book, UUID year, String op) {
        if (Set.of("create", "submit", "cancel", "retire", "retire-cancel").contains(op))
            return author(actor, book, year);
        var b = finance.originalScope(actor, book, year, "approve");
        if (op.equals("approve") || op.equals("retire-approve"))
            finance.originalScope(actor, book, year, "read");
        return b;
    }

    void currentAuthor(Map<String, Object> b, UUID year, Object name, Object expected) {
        try {
            var a = author(name.toString(), (UUID) b.get("id"), year);
            if (!Objects.equals(a.get("actor_id"), expected)) throw new IllegalArgumentException();
        } catch (RuntimeException stale) {
            throw fail("APPROVAL_STALE", "当前原制备人或退役请求人的OWNER/管理/制单/年度授权已失效");
        }
    }

    void independent(UUID actor, Map<String, Object> row, Object requester) {
        if (actor.equals(row.get("prepared_by"))
                || actor.equals(row.get("last_edited_by"))
                || actor.equals(requester))
            throw fail("SELF_APPROVAL_FORBIDDEN", "政策发布和退役须由独立于制备人及请求人的账号复核");
    }

    static final String SELECT =
            "SELECT p.id,p.policy_code AS \"policyCode\",p.revision,p.supersedes_policy_id AS"
                + " \"supersedesPolicyId\",p.status,p.version,p.content_version AS"
                + " \"contentVersion\",p.content_digest AS \"contentDigest\",p.transaction_kind AS"
                + " \"transactionKind\",p.business_purpose AS"
                + " \"businessPurpose\",p.direction,p.effective_from AS"
                + " \"effectiveFrom\",p.effective_to AS \"effectiveTo\",p.prepared_by AS"
                + " \"preparedBy\",p.created_at AS \"createdAt\",p.submitted_at AS"
                + " \"submittedAt\",p.submission_expires_at AS"
                + " \"submissionExpiresAt\",p.approved_by AS \"approvedBy\",p.approved_at AS"
                + " \"approvedAt\",p.decision_by AS \"decisionBy\",p.decision_at AS"
                + " \"decisionAt\",p.decision_reason AS \"decisionReason\",p.retired_by AS"
                + " \"retiredBy\",p.retired_at AS \"retiredAt\",(p.status='APPROVED' AND NOT"
                + " EXISTS(SELECT 1 FROM plugin_finance_cash_policy_retirement r WHERE"
                + " (r.tenant_id,r.book_id,r.year_id,r.policy_id)=(p.tenant_id,p.book_id,p.year_id,p.id)"
                + " AND r.status='REQUESTED')) AS \"activeForNewUse\",false AS"
                + " \"formalReportContributionEnabled\" FROM plugin_finance_cash_policy_revision p"
                + " WHERE p.tenant_id=? AND p.book_id=? AND p.year_id=?";
    static final String RETIRE =
            "SELECT r.id,r.policy_id AS \"policyId\",r.expected_policy_version AS"
                + " \"expectedPolicyVersion\",r.policy_content_digest AS"
                + " \"policyContentDigest\",r.status,r.version,r.content_digest AS"
                + " \"contentDigest\",r.reason,r.requested_by AS \"requestedBy\",r.requested_at AS"
                + " \"requestedAt\",r.review_expires_at AS \"reviewExpiresAt\",r.reviewed_by AS"
                + " \"reviewedBy\",r.reviewed_at AS \"reviewedAt\",r.decision_reason AS"
                + " \"decisionReason\" FROM plugin_finance_cash_policy_retirement r WHERE"
                + " r.tenant_id=? AND r.book_id=? AND r.year_id=?";

    Map<String, Object> row(Map<String, Object> b, UUID year, UUID id) {
        return db.one(
                "SELECT *,content::text AS content_text FROM plugin_finance_cash_policy_revision"
                    + " WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                b.get("tenant_id"),
                b.get("id"),
                year,
                id);
    }

    Map<String, Object> retirement(Map<String, Object> b, UUID year, UUID policy, UUID id) {
        return db.one(
                RETIRE + " AND r.policy_id=? AND r.id=?",
                b.get("tenant_id"),
                b.get("id"),
                year,
                policy,
                id);
    }

    Map<String, Object> content(Map<String, Object> row) {
        var c = finance.decode((String) row.get("content_text"));
        if (!digest(c).equals(row.get("content_digest")))
            throw fail("APPROVAL_STALE", "不可变政策或退役请求内容摘要不一致");
        return c;
    }

    long watermark(Map<String, Object> b, UUID year) {
        return ((Number)
                        db.one(
                                        "SELECT publication_version FROM"
                                            + " plugin_finance_cash_policy_guard WHERE tenant_id=?"
                                            + " AND book_id=? AND year_id=?",
                                        b.get("tenant_id"),
                                        b.get("id"),
                                        year)
                                .get("publication_version"))
                .longValue();
    }

    Map<String, Object> detail(Map<String, Object> b, UUID year, UUID id) {
        var r = row(b, year, id);
        var c = content(r);
        var result = db.one(SELECT + " AND p.id=?", b.get("tenant_id"), b.get("id"), year, id);
        for (String key :
                List.of(
                        "tenantId",
                        "bookId",
                        "yearId",
                        "legalEntityId",
                        "standard",
                        "templateId",
                        "dictionaryVersion",
                        "semanticLeaf",
                        "rowId",
                        "reportScope",
                        "currency",
                        "transactionForm",
                        "policyBasis",
                        "applicabilityStatement",
                        "assertions",
                        "evidence",
                        "reason")) result.put(key, c.get(key));
        var pending =
                db.list(
                        "SELECT id FROM plugin_finance_cash_policy_retirement WHERE tenant_id=? AND"
                            + " book_id=? AND year_id=? AND policy_id=? AND status='REQUESTED'",
                        b.get("tenant_id"),
                        b.get("id"),
                        year,
                        id);
        result.put("pendingRetirementId", pending.isEmpty() ? null : pending.getFirst().get("id"));
        result.put("policyWatermark", watermark(b, year));
        result.put("limitations", LIMITATIONS);
        return result;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> detail(String actor, UUID book, UUID year, UUID id) {
        return detail(finance.originalScope(actor, book, year, "read"), year, id);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> catalog(String actor, UUID book, UUID year) {
        var b = finance.originalScope(actor, book, year, "read");
        var rows =
                db.list(
                        "SELECT template_id,dictionary_version,transaction_kind AS"
                            + " \"transactionKind\",business_purpose AS"
                            + " \"businessPurpose\",direction,semantic_leaf AS"
                            + " \"semanticLeaf\",row_id AS \"rowId\" FROM"
                            + " plugin_finance_cash_policy_catalog WHERE standard=? ORDER BY"
                            + " transaction_kind",
                        b.get("standard"));
        if (rows.size() != 7) throw fail("RULE_VERSION_UNAVAILABLE", "普通现金政策目录不可用");
        String template = rows.getFirst().get("template_id").toString(),
                dictionary = rows.getFirst().get("dictionary_version").toString();
        for (var r : rows) {
            r.remove("template_id");
            r.remove("dictionary_version");
        }
        return Map.of(
                "schemaVersion",
                SCHEMA,
                "standard",
                b.get("standard"),
                "templateId",
                template,
                "dictionaryVersion",
                dictionary,
                "items",
                rows,
                "publicationImpliesSourceReview",
                false,
                "formalReportContributionEnabled",
                false);
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
                UUID id = UUID.fromString((String) c.get("id"));
                Instant time = Instant.parse((String) c.get("stamp"));
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
                throw FinanceException.invalid("政策分页游标范围、过滤条件或锚点无效");
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
            String status,
            String kind,
            int limit,
            String cursor) {
        var b = finance.originalScope(actor, book, year, "read");
        if (status != null
                && !Set.of("DRAFT", "SUBMITTED", "APPROVED", "REJECTED", "CANCELLED", "RETIRED")
                        .contains(status)) throw FinanceException.invalid("政策状态无效");
        if (kind != null && !FinanceCashMovements.KINDS.containsKey(kind))
            throw fail("CASH_POLICY_PURPOSE_UNSUPPORTED", "政策业务种类不支持");
        String sql = SELECT;
        var p = new ArrayList<Object>(List.of(b.get("tenant_id"), book, year));
        if (status != null) {
            sql += " AND p.status=?";
            p.add(status);
        }
        if (kind != null) {
            sql += " AND p.transaction_kind=?";
            p.add(kind);
        }
        return page(
                b,
                year,
                "POLICIES:" + status + ":" + kind,
                sql,
                p,
                "p",
                "created_at",
                "createdAt",
                limit,
                cursor);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> retirements(
            String actor, UUID book, UUID year, UUID id, int limit, String cursor) {
        var b = finance.originalScope(actor, book, year, "read");
        row(b, year, id);
        return page(
                b,
                year,
                "RETIREMENTS:" + id,
                RETIRE + " AND r.policy_id=?",
                new ArrayList<>(List.of(b.get("tenant_id"), book, year, id)),
                "r",
                "requested_at",
                "requestedAt",
                limit,
                cursor);
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> evidence(Map<String, Object> b, UUID year, Object input) {
        if (!(input instanceof List<?> values) || values.size() < 2 || values.size() > 10)
            throw FinanceException.invalid("政策须保留2至10份不同原件，至少各一份政策依据及适用性支持");
        var result = new ArrayList<Map<String, Object>>();
        Set<UUID> ids = new HashSet<>();
        Set<String> roles = new HashSet<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?>)) throw FinanceException.invalid("政策证据结构无效");
            var v = (Map<String, Object>) value;
            FinanceCashScopes.fields(v, Set.of("originalId", "purpose", "description"));
            UUID id = uuid(v, "originalId");
            String purpose = text(v, "purpose", 40);
            if (!Set.of("POLICY_BASIS", "APPLICABILITY_SUPPORT").contains(purpose) || !ids.add(id))
                throw FinanceException.invalid("证据须为不同原件且role闭合");
            roles.add(purpose);
            var o = scopes.evidence(b, year, id);
            result.add(
                    Map.of(
                            "originalId",
                            id.toString(),
                            "originalVersion",
                            o.get("version"),
                            "sha256",
                            o.get("sha256"),
                            "filename",
                            o.get("filename"),
                            "purpose",
                            purpose,
                            "description",
                            text(v, "description", 500)));
        }
        if (roles.size() != 2) throw FinanceException.invalid("政策依据及适用性支持两类证据均必需");
        return result;
    }

    @SuppressWarnings("unchecked")
    void revalidate(Map<String, Object> b, UUID year, Map<String, Object> c) {
        for (var e : (List<Map<String, Object>>) c.get("evidence")) {
            var current = scopes.evidence(b, year, UUID.fromString(e.get("originalId").toString()));
            if (!e.get("sha256").equals(current.get("sha256"))
                    || !e.get("filename").equals(current.get("filename"))
                    || ((Number) e.get("originalVersion")).longValue()
                            != ((Number) current.get("version")).longValue())
                throw fail("APPROVAL_STALE", "政策依据或适用性原件版本已失效");
        }
    }

    static final Set<String> CREATE =
            Set.of(
                    "schemaVersion",
                    "idempotencyKey",
                    "policyCode",
                    "supersedesPolicyId",
                    "transactionKind",
                    "businessPurpose",
                    "reportScope",
                    "currency",
                    "transactionForm",
                    "effectiveFrom",
                    "effectiveTo",
                    "policyBasis",
                    "applicabilityStatement",
                    "assertions",
                    "evidence",
                    "reason");

    Map<String, Object> normalize(String op, Map<String, Object> input) {
        FinanceCashScopes.fields(
                input,
                op.equals("create")
                        ? CREATE
                        : Set.of(
                                "schemaVersion",
                                "idempotencyKey",
                                "expectedVersion",
                                "contentDigest",
                                "reason"));
        if (!SCHEMA.equals(input.get("schemaVersion")))
            throw FinanceException.invalid("政策schemaVersion无效");
        var n = new LinkedHashMap<String, Object>(input);
        n.remove("idempotencyKey");
        n.put("reason", text(input, "reason", 1000));
        if (!op.equals("create")) {
            n.put("expectedVersion", positive(input, "expectedVersion"));
            if (!(input.get("contentDigest") instanceof String d) || !d.matches("[a-f0-9]{64}"))
                throw FinanceException.invalid("政策内容摘要无效");
            return n;
        }
        String code = FinanceSources.exact(input, "policyCode", 50);
        if (!code.matches("[A-Z][A-Z0-9_]{2,49}")) throw FinanceException.invalid("policyCode格式无效");
        n.put("policyCode", code);
        n.put("transactionKind", FinanceSources.exact(input, "transactionKind", 60));
        n.put("businessPurpose", FinanceSources.exact(input, "businessPurpose", 60));
        n.put("effectiveFrom", FinanceCashScopes.date(input, "effectiveFrom").toString());
        n.put("effectiveTo", FinanceCashScopes.date(input, "effectiveTo").toString());
        if (input.get("supersedesPolicyId") != null)
            n.put("supersedesPolicyId", uuid(input, "supersedesPolicyId").toString());
        n.put("policyBasis", text(input, "policyBasis", 4000));
        n.put("applicabilityStatement", text(input, "applicabilityStatement", 2000));
        if (!"SEPARATE".equals(input.get("reportScope"))
                || !"CNY".equals(input.get("currency"))
                || !"ORDINARY_GROSS_CASH".equals(input.get("transactionForm")))
            throw fail("CASH_POLICY_PURPOSE_UNSUPPORTED", "本片仅支持个别主体人民币普通全额现金用途");
        if (!(input.get("assertions") instanceof Map<?, ?> a)
                || !a.keySet().equals(ASSERTIONS)
                || a.values().stream().anyMatch(v -> !Boolean.TRUE.equals(v)))
            throw fail("CASH_POLICY_PURPOSE_UNSUPPORTED", "必须明确确认全部受支持边界；复杂工资、税款、利息及资本交易不可伪装为普通用途");
        return n;
    }

    void version(Map<String, Object> n, Map<String, Object> r) {
        if (positive(n, "expectedVersion") != ((Number) r.get("version")).longValue()
                || !n.get("contentDigest").equals(r.get("content_digest")))
            throw fail("VERSION_CONFLICT", "政策或退役请求版本/不可变摘要不匹配");
    }

    void fresh(Map<String, Object> row, String column) {
        if (Boolean.TRUE.equals(
                db.one("SELECT ?::timestamptz<=clock_timestamp() AS expired", row.get(column))
                        .get("expired")))
            throw fail("CASH_POLICY_SUBMISSION_EXPIRED", "7×24小时运营审签时限已过；可拒绝/取消后新建不可变修订或请求");
    }

    @Transactional
    public Map<String, Object> write(
            String actor,
            UUID book,
            UUID year,
            String op,
            UUID id,
            UUID retirementId,
            Map<String, Object> input) {
        if (!Set.of(
                        "create",
                        "submit",
                        "approve",
                        "reject",
                        "cancel",
                        "retire",
                        "retire-approve",
                        "retire-reject",
                        "retire-cancel")
                .contains(op)) throw FinanceException.invalid("未知政策动作");
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
        var yr =
                db.one(
                        "SELECT year,status FROM plugin_finance_year WHERE tenant_id=? AND"
                            + " book_id=? AND id=?",
                        b.get("tenant_id"),
                        book,
                        year);
        if (!"OPEN".equals(yr.get("status")))
            throw fail("PERIOD_CLOSED", "政策生命周期要求V4开放年度，不选择或锁定现金月份");
        db.update(
                "INSERT INTO plugin_finance_cash_policy_guard(tenant_id,book_id,year_id)"
                    + " VALUES(?,?,?) ON CONFLICT DO NOTHING",
                b.get("tenant_id"),
                book,
                year);
        db.one(
                "SELECT publication_version FROM plugin_finance_cash_policy_guard WHERE tenant_id=?"
                    + " AND book_id=? AND year_id=? FOR UPDATE",
                b.get("tenant_id"),
                book,
                year);
        Map<String, Object> policy =
                op.equals("create")
                        ? null
                        : db.one(
                                "SELECT *,content::text AS content_text FROM"
                                    + " plugin_finance_cash_policy_revision WHERE tenant_id=? AND"
                                    + " book_id=? AND year_id=? AND id=? FOR UPDATE",
                                b.get("tenant_id"),
                                book,
                                year,
                                id);
        Map<String, Object> request = null;
        if (Set.of("submit", "cancel").contains(op) && !user.equals(policy.get("prepared_by")))
            throw fail("SCOPE_DENIED", "仅原政策制备人可提交或取消");
        if (op.equals("approve") || op.equals("reject")) independent(user, policy, null);
        if (op.equals("approve"))
            currentAuthor(b, year, policy.get("prepared_username"), policy.get("prepared_by"));
        if (op.startsWith("retire-")) {
            request =
                    db.one(
                            "SELECT *,content::text AS content_text FROM"
                                + " plugin_finance_cash_policy_retirement WHERE tenant_id=? AND"
                                + " book_id=? AND year_id=? AND policy_id=? AND id=? FOR UPDATE",
                            b.get("tenant_id"),
                            book,
                            year,
                            id,
                            retirementId);
            if (op.equals("retire-cancel")) {
                if (!user.equals(request.get("requested_by")))
                    throw fail("SCOPE_DENIED", "仅原退役请求人可取消请求");
            } else independent(user, policy, request.get("requested_by"));
            if (op.equals("retire-approve"))
                currentAuthor(
                        b, year, request.get("requested_username"), request.get("requested_by"));
        }
        String
                route =
                        book
                                + "/"
                                + year
                                + "/classification-policies/"
                                + id
                                + "/"
                                + op
                                + "/"
                                + retirementId,
                hash = digest(n);
        var cached =
                db.list(
                        "SELECT route,payload_hash,response FROM plugin_finance_idempotency WHERE"
                            + " actor_id=? AND key=?",
                        user,
                        key);
        if (!cached.isEmpty()) {
            var prior = cached.getFirst();
            if (!route.equals(prior.get("route")) || !hash.equals(prior.get("payload_hash")))
                throw fail("IDEMPOTENCY_CONFLICT", "请求键已用于不同政策内容");
            var old = finance.decode((String) prior.get("response"));
            if (op.startsWith("retire"))
                return retirement(
                        b,
                        year,
                        UUID.fromString(old.get("policyId").toString()),
                        UUID.fromString(old.get("id").toString()));
            return detail(b, year, UUID.fromString(old.get("id").toString()));
        }
        Map<String, Object> result;
        if (op.equals("create")) {
            id = create(b, year, n, actor, user, ((Number) yr.get("year")).intValue());
            result = detail(b, year, id);
        } else if (op.startsWith("retire")) {
            result = retire(b, year, op, policy, request, retirementId, n, actor, user);
        } else {
            version(n, policy);
            content(policy);
            String state = (String) policy.get("status");
            if (op.equals("submit")) {
                if (!"DRAFT".equals(state))
                    throw fail("CASH_POLICY_STATE_CONFLICT", "仅草稿可提交；到期需新修订");
                db.update(
                        "UPDATE plugin_finance_cash_policy_revision SET"
                            + " status='SUBMITTED',version=version+1,submitted_at=now(),submission_expires_at=now()+interval"
                            + " '168 hours' WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                        b.get("tenant_id"),
                        book,
                        year,
                        id);
            } else if (op.equals("cancel")) {
                if (!Set.of("DRAFT", "SUBMITTED").contains(state))
                    throw fail("CASH_POLICY_STATE_CONFLICT", "仅未发布提案可取消");
                decision(b, year, id, "CANCELLED", user, n.get("reason"));
            } else {
                if (!"SUBMITTED".equals(state))
                    throw fail("CASH_POLICY_STATE_CONFLICT", "仅已提交提案可审签");
                if (op.equals("approve")) {
                    fresh(policy, "submission_expires_at");
                    revalidate(b, year, content(policy));
                    if (!db.list(
                                    "SELECT id FROM plugin_finance_cash_policy_revision WHERE"
                                        + " tenant_id=? AND book_id=? AND year_id=? AND id<>? AND"
                                        + " status='APPROVED' AND transaction_kind=? AND"
                                        + " business_purpose=? AND effective_from<=? AND"
                                        + " effective_to>=?",
                                    b.get("tenant_id"),
                                    book,
                                    year,
                                    id,
                                    policy.get("transaction_kind"),
                                    policy.get("business_purpose"),
                                    LocalDate.parse(policy.get("effective_to").toString()),
                                    LocalDate.parse(policy.get("effective_from").toString()))
                            .isEmpty())
                        throw fail("CASH_POLICY_OVERLAP", "同维度已有日期交叠的已发布政策；待审退役仍占用，须先独立批准退役");
                    db.update(
                            "UPDATE plugin_finance_cash_policy_revision SET"
                                + " status='APPROVED',version=version+1,approved_by=?,approved_at=now(),decision_by=?,decision_at=now(),decision_reason=?"
                                + " WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                            user,
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
                "CASH_POLICY_" + op.toUpperCase(Locale.ROOT).replace('-', '_'),
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
            Map<String, Object> b, UUID year, UUID id, String status, UUID actor, Object reason) {
        db.update(
                "UPDATE plugin_finance_cash_policy_revision SET"
                    + " status=?,version=version+1,decision_by=?,decision_at=now(),decision_reason=?"
                    + " WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                status,
                actor,
                reason,
                b.get("tenant_id"),
                b.get("id"),
                year,
                id);
    }

    UUID create(
            Map<String, Object> b,
            UUID year,
            Map<String, Object> n,
            String actor,
            UUID user,
            int yearNumber) {
        var tuple =
                db.list(
                        "SELECT * FROM plugin_finance_cash_policy_catalog WHERE standard=? AND"
                            + " transaction_kind=? AND business_purpose=?",
                        b.get("standard"),
                        text(n, "transactionKind", 60),
                        text(n, "businessPurpose", 60));
        if (tuple.size() != 1)
            throw fail("CASH_POLICY_PURPOSE_UNSUPPORTED", "业务种类与具体用途必须精确匹配服务端七项目录，禁止未知工资/税款/资本化内容");
        var t = tuple.getFirst();
        LocalDate from = LocalDate.parse(n.get("effectiveFrom").toString()),
                to = LocalDate.parse(n.get("effectiveTo").toString());
        if (from.getYear() != yearNumber || to.getYear() != yearNumber || to.isBefore(from))
            throw FinanceException.invalid("政策日期范围须为路径年度内有效民事日期区间");
        var history =
                db.list(
                        "SELECT id,revision,transaction_kind,business_purpose FROM"
                            + " plugin_finance_cash_policy_revision WHERE tenant_id=? AND book_id=?"
                            + " AND year_id=? AND policy_code=? ORDER BY revision DESC LIMIT 1",
                        b.get("tenant_id"),
                        b.get("id"),
                        year,
                        n.get("policyCode"));
        long revision = 1;
        if (history.isEmpty()) {
            if (n.get("supersedesPolicyId") != null)
                throw fail("CASH_POLICY_STATE_CONFLICT", "新policyCode首修订不得虚构前序");
        } else {
            var last = history.getFirst();
            if (!last.get("id").toString().equals(n.get("supersedesPolicyId"))
                    || !last.get("transaction_kind").equals(n.get("transactionKind"))
                    || !last.get("business_purpose").equals(n.get("businessPurpose")))
                throw fail("CASH_POLICY_STATE_CONFLICT", "继任须指同code最新修订（包括拒绝/取消历史）并保持分类维度");
            revision = ((Number) last.get("revision")).longValue() + 1;
        }
        var evidence = evidence(b, year, n.get("evidence"));
        var c = new LinkedHashMap<String, Object>(n);
        c.put("evidence", evidence);
        c.put("tenantId", b.get("tenant_id").toString());
        c.put("bookId", b.get("id").toString());
        c.put("yearId", year.toString());
        c.put("legalEntityId", b.get("legal_entity_id").toString());
        c.put("revision", revision);
        c.put("preparedBy", user.toString());
        c.put("standard", b.get("standard"));
        c.put("templateId", t.get("template_id"));
        c.put("dictionaryVersion", t.get("dictionary_version"));
        c.put("direction", t.get("direction"));
        c.put("semanticLeaf", t.get("semantic_leaf"));
        c.put("rowId", t.get("row_id"));
        UUID id = UUID.randomUUID();
        db.update(
                "INSERT INTO"
                    + " plugin_finance_cash_policy_revision(tenant_id,book_id,year_id,id,legal_entity_id,policy_code,revision,supersedes_policy_id,standard,template_id,dictionary_version,transaction_kind,business_purpose,direction,semantic_leaf,row_id,report_scope,currency,transaction_form,effective_from,effective_to,content_digest,content,prepared_by,prepared_username,last_edited_by)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?)",
                b.get("tenant_id"),
                b.get("id"),
                year,
                id,
                b.get("legal_entity_id"),
                n.get("policyCode"),
                revision,
                n.get("supersedesPolicyId") == null
                        ? null
                        : UUID.fromString(n.get("supersedesPolicyId").toString()),
                b.get("standard"),
                t.get("template_id"),
                t.get("dictionary_version"),
                n.get("transactionKind"),
                n.get("businessPurpose"),
                t.get("direction"),
                t.get("semantic_leaf"),
                t.get("row_id"),
                "SEPARATE",
                "CNY",
                "ORDINARY_GROSS_CASH",
                from,
                to,
                digest(c),
                finance.encode(c),
                user,
                actor,
                user);
        int index = 0;
        for (var e : evidence)
            db.update(
                    "INSERT INTO plugin_finance_cash_policy_evidence VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    b.get("tenant_id"),
                    b.get("id"),
                    year,
                    id,
                    ++index,
                    UUID.fromString(e.get("originalId").toString()),
                    e.get("originalVersion"),
                    e.get("sha256"),
                    e.get("filename"),
                    e.get("purpose"),
                    e.get("description"));
        return id;
    }

    Map<String, Object> retire(
            Map<String, Object> b,
            UUID year,
            String op,
            Map<String, Object> policy,
            Map<String, Object> request,
            UUID rid,
            Map<String, Object> n,
            String actor,
            UUID user) {
        UUID id = (UUID) policy.get("id");
        if (!"APPROVED".equals(policy.get("status")))
            throw fail("CASH_POLICY_STATE_CONFLICT", "仅当前APPROVED政策可请求或审签退役");
        if (op.equals("retire")) {
            version(n, policy);
            var pending =
                    db.list(
                            "SELECT id FROM plugin_finance_cash_policy_retirement WHERE tenant_id=?"
                                + " AND book_id=? AND year_id=? AND policy_id=? AND"
                                + " status='REQUESTED'",
                            b.get("tenant_id"),
                            b.get("id"),
                            year,
                            id);
            if (!pending.isEmpty())
                throw fail("CASH_POLICY_RETIREMENT_PENDING", "已有待审退役请求；政策仍已发布但冻结未来新用");
            var c =
                    Map.of(
                            "schemaVersion",
                            SCHEMA,
                            "policyId",
                            id.toString(),
                            "expectedPolicyVersion",
                            policy.get("version"),
                            "policyContentDigest",
                            policy.get("content_digest"),
                            "requestedBy",
                            user.toString(),
                            "reason",
                            n.get("reason"));
            rid = UUID.randomUUID();
            db.update(
                    "INSERT INTO"
                        + " plugin_finance_cash_policy_retirement(tenant_id,book_id,year_id,id,policy_id,expected_policy_version,policy_content_digest,content_digest,content,reason,requested_by,requested_username,review_expires_at)"
                        + " VALUES(?,?,?,?,?,?,?,?,?::jsonb,?,?,?,now()+interval '168 hours')",
                    b.get("tenant_id"),
                    b.get("id"),
                    year,
                    rid,
                    id,
                    policy.get("version"),
                    policy.get("content_digest"),
                    digest(c),
                    finance.encode(c),
                    n.get("reason"),
                    user,
                    actor);
        } else {
            version(n, request);
            content(request);
            if (!"REQUESTED".equals(request.get("status")))
                throw fail("CASH_POLICY_STATE_CONFLICT", "退役请求已处理");
            if (op.equals("retire-approve")) fresh(request, "review_expires_at");
            String state =
                    op.equals("retire-approve")
                            ? "APPROVED"
                            : op.equals("retire-reject") ? "REJECTED" : "CANCELLED";
            db.update(
                    "UPDATE plugin_finance_cash_policy_retirement SET"
                        + " status=?,version=version+1,reviewed_by=?,reviewed_at=now(),decision_reason=?"
                        + " WHERE tenant_id=? AND book_id=? AND year_id=? AND id=?",
                    state,
                    user,
                    n.get("reason"),
                    b.get("tenant_id"),
                    b.get("id"),
                    year,
                    rid);
        }
        return retirement(b, year, id, rid);
    }
}
