package com.lumira.finance;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuthorizationContextProjectionPort;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

/** Bounded observation only. No effects, consumers, financial writes or purported freeze. */
@Service
public class FinanceCashLedgerCoverage {
    static final String SCHEMA = "ledger-cash-coverage-preview/1";
    static final List<String> STAGES =
            List.of(
                    "UNSUPPORTED_LEDGER_FACT",
                    "SCOPE_LINEAGE_MISMATCH",
                    "NO_ACTIVE_CLAIM",
                    "NO_EFFECTIVE_REVIEW",
                    "NO_EFFECTIVE_ALLOCATION",
                    "DEPENDENCY_PENDING",
                    "SUPPORTED_CHAIN");
    static final Map<String, List<String>> STAGE_ISSUES =
            Map.of(
                    "UNSUPPORTED_LEDGER_FACT",
                    List.of(
                            "MISSING_POSTING",
                            "UNSEQUENCED_POSTING",
                            "INVALID_CASH_LEG",
                            "LEGACY_PRECISION_UNSUPPORTED",
                            "AMOUNT_OVERFLOW",
                            "COMPLEX_FACT_REQUIRES_JUDGMENT",
                            "TYPED_LINK_INTEGRITY_FAILURE"),
                    "SCOPE_LINEAGE_MISMATCH",
                    List.of("SCOPE_LINEAGE_MISMATCH", "STALE_ACCOUNT", "STALE_EVIDENCE"),
                    "NO_ACTIVE_CLAIM",
                    List.of("NO_ACTIVE_CLAIM"),
                    "NO_EFFECTIVE_REVIEW",
                    List.of("NO_EFFECTIVE_REVIEW", "AWAITING_REVIEW"),
                    "NO_EFFECTIVE_ALLOCATION",
                    List.of("NO_EFFECTIVE_ALLOCATION", "AWAITING_ALLOCATION"),
                    "DEPENDENCY_PENDING",
                    List.of(
                            "PENDING_MATCH_WITHDRAWAL",
                            "PENDING_REVIEW_RELEASE",
                            "PENDING_ALLOCATION_RELEASE",
                            "PENDING_REPLACEMENT"));
    static final Set<String> CURSOR_KEYS =
            Set.of(
                    "v",
                    "actorId",
                    "authorizationContextDigest",
                    "tenantId",
                    "book",
                    "year",
                    "scopeRevisionId",
                    "periodStart",
                    "periodEnd",
                    "filterDigest",
                    "contextDigest",
                    "lastDate",
                    "lastJournalId",
                    "lastLineNo");
    static final List<String> LIMITATIONS =
            List.of(
                    "仅受限已记账现金行链路只读观察，不是冻结或已复核工作包",
                    "外部账户/文件/流水总体完整性MISSING；原件元数据不代表银行或全部字节再次鉴真",
                    "收付分开显示，未知不填零；复杂、内转、范围变化尚待判断",
                    "历史政策退役不抹除旧分配；当前效果与新使用冻结分别显示",
                    "无coverage批准、真实consumer、现金余额桥、净利润调节或正式报表放行",
                    "5000行/50000关联元组为本片安全预算，不代表生产容量认证；无数据库RLS完成声明");
    final FinanceStore db;
    final FinanceService finance;
    final AdministrationPort guard;
    final AuthorizationContextProjectionPort authorization;

    public FinanceCashLedgerCoverage(
            FinanceStore db,
            FinanceService finance,
            AdministrationPort guard,
            AuthorizationContextProjectionPort authorization) {
        this.db = db;
        this.finance = finance;
        this.guard = guard;
        this.authorization = authorization;
    }

    static FinanceException error(String code, String message) {
        return new FinanceException(code, message);
    }

    String hash(Object o) {
        return FinanceService.hash(finance.encode(finance.canonical(o)));
    }

    static Map<String, Object> map(Object... pairs) {
        var m = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]);
        return m;
    }

    static UUID uuid(Object v) {
        try {
            if (!(v instanceof String s)) throw new IllegalArgumentException();
            UUID id = UUID.fromString(s);
            if (!id.toString().equals(s)) throw new IllegalArgumentException();
            return id;
        } catch (RuntimeException e) {
            throw FinanceException.invalid("UUID须为canonical字符串");
        }
    }

    static long num(Object o) {
        if (!(o instanceof Integer || o instanceof Long) || ((Number) o).longValue() < 1)
            throw FinanceException.invalid("须为正整数");
        return ((Number) o).longValue();
    }

    static String id(Object o) {
        return o == null ? null : o.toString();
    }

    static boolean eq(Object a, Object b) {
        return Objects.equals(id(a), id(b));
    }

    static BigDecimal decimal(Object o) {
        try {
            if (o == null) return null;
            String s = o.toString();
            if (!s.matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?")) return null;
            return new BigDecimal(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String amount(BigDecimal d, int scale) {
        if (d == null) return null;
        try {
            return d.setScale(scale).toPlainString();
        } catch (ArithmeticException e) {
            return null;
        }
    }

    static String total(BigDecimal d) {
        String s = d.setScale(8).toPlainString();
        if (d.precision() - d.scale() > 25 || s.length() > 35)
            throw error("PREVIEW_TOTAL_OVERFLOW", "精确合计超出预览容量，不能截断或舍入");
        return s;
    }

    static void issue(
            List<Map<String, Object>> issues,
            String code,
            String message,
            String type,
            Object related) {
        if (issues.stream()
                .anyMatch(i -> i.get("code").equals(code) && eq(i.get("relatedId"), related)))
            return;
        issues.add(
                map(
                        "code",
                        code,
                        "message",
                        message,
                        "severity",
                        code.equals("COMPLEX_FACT_REQUIRES_JUDGMENT")
                                ? "JUDGMENT_REQUIRED"
                                : "BLOCKING",
                        "relatedType",
                        type,
                        "relatedId",
                        id(related)));
        if (issues.size() > 32) throw error("PREVIEW_LIMIT_EXCEEDED", "单行问题数量超过安全预算");
    }

    static List<Map<String, Object>> matching(
            List<Map<String, Object>> rows, String key, Object value) {
        return rows.stream().filter(r -> eq(r.get(key), value)).toList();
    }

    static Map<String, Object> first(List<Map<String, Object>> rows) {
        return rows.isEmpty() ? null : rows.getFirst();
    }

    static Map<String, Object> byId(List<Map<String, Object>> rows, Object value) {
        return first(matching(rows, "id", value));
    }

    static List<String> ids(List<Map<String, Object>> rows) {
        if (rows.size() > 100) throw error("PREVIEW_LIMIT_EXCEEDED", "关联待审记录超过安全预算");
        return rows.stream().map(r -> id(r.get("id"))).sorted().distinct().toList();
    }

    static final String CTE =
            "WITH ctx AS(SELECT ?::uuid t,?::uuid b,?::uuid y,?::uuid s), pop AS(SELECT j.id"
                + " journal_id,l.line_no,l.account_id,j.date,j.version"
                + " journal_version,j.number,j.type,j.reversal_of,j.amount_format_version,l.debit::text"
                + " debit,l.credit::text credit FROM ctx x JOIN plugin_finance_cash_scope_revision"
                + " s ON(s.tenant_id,s.book_id,s.year_id,s.id)=(x.t,x.b,x.y,x.s) JOIN"
                + " plugin_finance_cash_scope_item i"
                + " ON(i.tenant_id,i.book_id,i.year_id,i.scope_revision_id)=(x.t,x.b,x.y,x.s) AND"
                + " i.qualification='INCLUDED' JOIN plugin_finance_line l"
                + " ON(l.tenant_id,l.book_id,l.year_id,l.account_id)=(x.t,x.b,x.y,i.account_id)"
                + " JOIN plugin_finance_journal j"
                + " ON(j.tenant_id,j.book_id,j.year_id,j.id)=(l.tenant_id,l.book_id,l.year_id,l.journal_id)"
                + " WHERE j.status='POSTED' AND j.date BETWEEN s.period_start AND s.period_end),"
                + " claims AS(SELECT c.id,c.raw_registration_id FROM plugin_finance_cash_leg_claim"
                + " c JOIN ctx x ON(c.tenant_id,c.book_id,c.year_id)=(x.t,x.b,x.y) WHERE"
                + " EXISTS(SELECT 1 FROM pop p"
                + " WHERE(p.journal_id,p.line_no)=(c.journal_id,c.line_no))), raws AS(SELECT r.id"
                + " FROM plugin_finance_cash_movement_registration r JOIN ctx x"
                + " ON(r.tenant_id,r.book_id,r.year_id)=(x.t,x.b,x.y) WHERE r.id IN(SELECT"
                + " raw_registration_id FROM claims) OR EXISTS(SELECT 1 FROM pop p"
                + " WHERE(p.journal_id,p.line_no)=(r.journal_id,r.line_no))), reviews AS(SELECT"
                + " r.id FROM plugin_finance_cash_source_review r JOIN ctx x"
                + " ON(r.tenant_id,r.book_id,r.year_id)=(x.t,x.b,x.y) WHERE r.claim_id IN(SELECT id"
                + " FROM claims)), allocs AS(SELECT a.id FROM plugin_finance_cash_allocation a JOIN"
                + " ctx x ON(a.tenant_id,a.book_id,a.year_id)=(x.t,x.b,x.y) WHERE a.review_id"
                + " IN(SELECT id FROM reviews)), policies AS(SELECT DISTINCT l.policy_id id FROM"
                + " plugin_finance_cash_allocation_line l JOIN ctx x"
                + " ON(l.tenant_id,l.book_id,l.year_id)=(x.t,x.b,x.y) WHERE l.allocation_id"
                + " IN(SELECT id FROM allocs)) ";

    static String scoped(String table, String fields, String predicate, String order) {
        return "SELECT "
                + fields
                + " FROM "
                + table
                + " t JOIN ctx x ON(t.tenant_id,t.book_id,t.year_id)=(x.t,x.b,x.y) WHERE "
                + predicate
                + " ORDER BY "
                + order;
    }

    class Data {
        final Object[] args;
        final Map<String, List<Map<String, Object>>> sets = new LinkedHashMap<>();
        int tuples = 0;

        Data(Map<String, Object> b, UUID year, UUID scope) {
            args = new Object[] {b.get("tenant_id"), b.get("id"), year, scope};
        }

        List<Map<String, Object>> load(String name, String sql, boolean workflow, int maximum) {
            int cap = workflow ? Math.min(maximum, 50000 - tuples) : maximum;
            var rows = db.list(CTE + sql + " LIMIT " + (cap + 1), args);
            if (rows.size() > cap) throw error("PREVIEW_LIMIT_EXCEEDED", "预览总体或关联记录超过安全预算，未返回截断结果");
            if (workflow) tuples += rows.size();
            sets.put(name, rows);
            return rows;
        }

        List<Map<String, Object>> get(String name) {
            return sets.get(name);
        }

        final Map<String, Map<String, List<Map<String, Object>>>> indexes = new HashMap<>();

        List<Map<String, Object>> find(String name, String key, Object value) {
            var index =
                    indexes.computeIfAbsent(
                            name + "/" + key,
                            k -> {
                                var result = new HashMap<String, List<Map<String, Object>>>();
                                for (var row : get(name))
                                    result.computeIfAbsent(
                                                    id(row.get(key)), ignored -> new ArrayList<>())
                                            .add(row);
                                return result;
                            });
            return index.getOrDefault(id(value), List.of());
        }

        Map<String, Object> one(String name, Object value) {
            return first(find(name, "id", value));
        }
    }

    static List<Map<String, Object>> related(Data d, String name, String key, Set<String> values) {
        var rows = new TreeMap<String, Map<String, Object>>();
        for (String v : values) for (var r : d.find(name, key, v)) rows.put(id(r.get("id")), r);
        return new ArrayList<>(rows.values());
    }

    String auth(String actor, Map<String, Object> b, UUID year) {
        Set<String> held = guard.require(actor, "platform:finance:read");
        guard.require(actor, "platform:finance:access");
        var snap =
                authorization
                        .current(actor)
                        .orElseThrow(() -> error("SCOPE_DENIED", "缺少服务器验证的授权上下文"));
        if (!snap.effectiveAuthorities()
                        .containsAll(Set.of("platform:finance:access", "platform:finance:read"))
                || !snap.effectiveAuthorities().equals(held))
            throw error("SCOPE_DENIED", "当前授权上下文不一致");
        var member =
                db.one(
                        "SELECT user_id,role,enabled,year_scope FROM plugin_finance_member WHERE"
                                + " tenant_id=? AND book_id=? AND user_id=? AND enabled",
                        b.get("tenant_id"),
                        b.get("id"),
                        b.get("actor_id"));
        var years =
                db.list(
                        "SELECT year_id FROM plugin_finance_member_year WHERE tenant_id=? AND"
                                + " book_id=? AND user_id=? ORDER BY year_id",
                        b.get("tenant_id"),
                        b.get("id"),
                        b.get("actor_id"));
        return hash(
                map(
                        "actorId",
                        b.get("actor_id"),
                        "member",
                        member,
                        "yearIds",
                        years,
                        "effective",
                        new TreeSet<>(snap.effectiveAuthorities()),
                        "global",
                        new TreeSet<>(snap.effectiveGlobalAuthorities()),
                        "previewActive",
                        snap.previewActive(),
                        "targetRoleId",
                        snap.targetRoleId(),
                        "targetRoleVersion",
                        snap.targetRoleVersion()));
    }

    long counter(Map<String, Object> b, UUID year, String table, String column) {
        var r =
                db.list(
                        "SELECT "
                                + column
                                + " value FROM "
                                + table
                                + " WHERE tenant_id=? AND book_id=? AND year_id=?",
                        b.get("tenant_id"),
                        b.get("id"),
                        year);
        return r.isEmpty() ? 0 : ((Number) r.getFirst().get("value")).longValue();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> preview(
            String actor,
            UUID book,
            UUID year,
            UUID scopeId,
            int limit,
            String cursor,
            String filter) {
        var b = finance.originalScope(actor, book, year, "read");
        String authorizationDigest = auth(actor, b, year);
        if (scopeId == null
                || limit < 1
                || limit > 100
                || filter != null && !STAGES.contains(filter))
            throw FinanceException.invalid("范围、分页或阶段无效");
        var s =
                db.one(
                        "SELECT"
                            + " s.id,s.status,s.standard,s.report_scope,s.currency,s.template_id,s.period_start,s.period_end,s.approved_scope_version,s.content_digest,s.content->>'legalEntityId'"
                            + " entity_id,y.status year_status,p.status period_status,y.year FROM"
                            + " plugin_finance_cash_scope_revision s JOIN plugin_finance_year y"
                            + " ON(y.tenant_id,y.book_id,y.id)=(s.tenant_id,s.book_id,s.year_id)"
                            + " JOIN plugin_finance_period p"
                            + " ON(p.tenant_id,p.book_id,p.year_id,p.id)=(s.tenant_id,s.book_id,s.year_id,s.period_id)"
                            + " WHERE s.tenant_id=? AND s.book_id=? AND s.year_id=? AND s.id=?",
                        b.get("tenant_id"),
                        book,
                        year,
                        scopeId);
        var start = LocalDate.parse(s.get("period_start").toString());
        var end = LocalDate.parse(s.get("period_end").toString());
        if (!"APPROVED".equals(s.get("status"))
                || s.get("approved_scope_version") == null
                || !eq(b.get("legal_entity_id"), s.get("entity_id"))
                || !b.get("standard").equals(s.get("standard"))
                || !"CNY".equals(s.get("currency"))
                || !"SEPARATE".equals(s.get("report_scope"))
                || start.getDayOfMonth() != 1
                || !end.equals(start.plusMonths(1).minusDays(1))
                || start.getYear() != ((Number) s.get("year")).intValue())
            throw error("CASH_COVERAGE_SCOPE_UNAVAILABLE", "需要当前账套年度的已批准完整自然月人民币个别范围");
        var d = new Data(b, year, scopeId);
        var items =
                d.load(
                        "scopeItems",
                        "SELECT i.*,i.account_snapshot::text"
                            + " account_snapshot,s.content#>>ARRAY['items',(i.item_no-1)::text,'originalVersion']"
                            + " original_version FROM plugin_finance_cash_scope_item i JOIN ctx x"
                            + " ON(i.tenant_id,i.book_id,i.year_id,i.scope_revision_id)=(x.t,x.b,x.y,x.s)"
                            + " JOIN plugin_finance_cash_scope_revision s"
                            + " ON(s.tenant_id,s.book_id,s.year_id,s.id)=(x.t,x.b,x.y,x.s) ORDER BY"
                            + " i.item_no",
                        false,
                        20);
        var accounts =
                d.load(
                        "accounts",
                        scoped(
                                "plugin_finance_account",
                                "t.id,t.code,t.name,t.normal_side AS"
                                        + " \"normalSide\",t.category,t.cash_flow_category AS"
                                        + " \"cashFlowCategory\",t.enabled,t.purpose",
                                "t.id IN(SELECT account_id FROM plugin_finance_cash_scope_item"
                                        + " WHERE tenant_id=x.t AND book_id=x.b AND year_id=x.y AND"
                                        + " scope_revision_id=x.s)",
                                "t.id"),
                        false,
                        20);
        var population =
                d.load(
                        "population",
                        "SELECT q.*,p.posting_seq,p.digest posting_digest,p.journal_version"
                            + " posting_journal_version FROM pop q CROSS JOIN ctx x LEFT JOIN"
                            + " plugin_finance_posting p"
                            + " ON(p.tenant_id,p.book_id,p.year_id,p.journal_id)=(x.t,x.b,x.y,q.journal_id)"
                            + " ORDER BY q.date,q.journal_id,q.line_no",
                        false,
                        5000);
        d.load(
                "claims",
                scoped(
                        "plugin_finance_cash_leg_claim",
                        "t.id,t.raw_registration_id,t.claim_kind,t.correction_id,t.claim_generation,t.journal_id,t.line_no,t.account_id,t.journal_version,t.posting_seq,t.posting_digest,t.claim_content_digest,t.state,t.release_generation",
                        "t.id IN(SELECT id FROM claims)",
                        "t.id"),
                true,
                50000);
        d.load(
                "raws",
                scoped(
                        "plugin_finance_cash_movement_registration",
                        "t.id,t.status,t.version,t.content_digest,t.source_system,t.source_movement_key,t.source_document_id,t.source_row_reference,t.source_original_id,t.source_original_sha256,t.source_document_version,t.content#>>'{originalSnapshot,version}'"
                            + " original_version,t.cash_scope_revision_id,t.approved_scope_version,t.scope_content_digest,t.journal_id,t.line_no,t.account_id,t.amount::text"
                            + " amount,t.direction,t.occurred_date,t.transaction_kind,t.matched_journal_version,t.posting_seq,t.posting_digest,(t.content->'accountSnapshot')::text"
                            + " account_snapshot",
                        "t.id IN(SELECT id FROM raws)",
                        "t.id"),
                true,
                50000);
        d.load(
                "generations",
                scoped(
                        "plugin_finance_cash_source_match_guard",
                        "t.raw_registration_id,t.source_generation",
                        "t.raw_registration_id IN(SELECT id FROM raws)",
                        "t.raw_registration_id"),
                true,
                50000);
        d.load(
                "corrections",
                scoped(
                        "plugin_finance_cash_match_correction",
                        "t.id,t.status,t.version,t.content_digest,t.raw_registration_id,t.approved_claim_id,t.target_journal_id,t.target_line_no,t.target_journal_version,t.target_posting_seq,t.target_posting_digest",
                        "t.raw_registration_id IN(SELECT id FROM raws)",
                        "t.id"),
                true,
                50000);
        d.load(
                "reviews",
                scoped(
                        "plugin_finance_cash_source_review",
                        "t.id,t.status,t.version,t.content_digest,t.claim_id,t.raw_registration_id,t.claim_generation,t.claim_content_digest,t.source_match_generation,t.approved_usage_id,t.approved_by,t.approved_at",
                        "t.id IN(SELECT id FROM reviews)",
                        "t.id"),
                true,
                50000);
        d.load(
                "usages",
                scoped(
                        "plugin_finance_cash_claim_usage",
                        "t.id,t.usage_kind,t.source_review_id,t.consumer_content_digest,t.claim_id,t.raw_registration_id,t.claim_generation,t.claim_content_digest,t.state,t.approved_by,t.approved_at,t.release_decision_id",
                        "t.source_review_id IN(SELECT id FROM reviews)",
                        "t.id"),
                true,
                50000);
        d.load(
                "allocations",
                scoped(
                        "plugin_finance_cash_allocation",
                        "t.id,t.status,t.version,t.content_digest,t.review_id,t.review_usage_id,t.review_content_digest,t.claim_id,t.raw_registration_id,t.claim_generation,t.claim_content_digest,t.source_match_generation,t.total::text"
                            + " total,t.approved_dependency_id,t.approved_by,t.approved_at,t.activation_replacement_id,t.release_request_id,t.supersession_request_id",
                        "t.id IN(SELECT id FROM allocs)",
                        "t.id"),
                true,
                50000);
        d.load(
                "dependencies",
                scoped(
                        "plugin_finance_cash_allocation_dependency",
                        "t.id,t.allocation_id,t.consumer_content_digest,t.review_id,t.review_usage_id,t.review_content_digest,t.claim_id,t.raw_registration_id,t.claim_generation,t.claim_content_digest,t.state,t.activated_by,t.activated_at,t.release_request_id,t.replacement_request_id",
                        "t.allocation_id IN(SELECT id FROM allocs)",
                        "t.id"),
                true,
                50000);
        d.load(
                "pointers",
                scoped(
                        "plugin_finance_cash_allocation_effect",
                        "t.review_id,t.allocation_id,t.dependency_id,t.content_digest,t.review_usage_id,t.claim_id,t.raw_registration_id,t.claim_generation,t.claim_content_digest,t.review_content_digest,t.pointer_version",
                        "t.allocation_id IN(SELECT id FROM allocs)",
                        "t.review_id"),
                true,
                50000);
        d.load(
                "reviewEvidence",
                scoped(
                        "plugin_finance_cash_source_review_evidence",
                        "t.review_id,t.item_no,t.original_id,t.original_version,t.sha256",
                        "t.review_id IN(SELECT id FROM reviews)",
                        "t.review_id,t.item_no"),
                true,
                50000);
        d.load(
                "allocationLines",
                scoped(
                        "plugin_finance_cash_allocation_line",
                        "t.allocation_id,t.line_no,t.component_key,t.amount::text"
                            + " amount,t.policy_id,t.policy_content_digest,t.policy_version,t.business_purpose,t.evidence_original_id,t.evidence_version,t.evidence_sha256",
                        "t.allocation_id IN(SELECT id FROM allocs)",
                        "t.allocation_id,t.line_no"),
                true,
                50000);
        d.load(
                "policyEvidence",
                scoped(
                        "plugin_finance_cash_policy_evidence",
                        "t.policy_id,t.item_no,t.original_id,t.original_version,t.sha256",
                        "t.policy_id IN(SELECT id FROM policies)",
                        "t.policy_id,t.item_no"),
                true,
                50000);
        d.load(
                "withdrawals",
                scoped(
                        "plugin_finance_cash_match_withdrawal",
                        "t.id,t.status,t.version,t.content_digest,t.active_claim_id,t.raw_registration_id",
                        "t.raw_registration_id IN(SELECT id FROM raws)",
                        "t.id"),
                true,
                50000);
        d.load(
                "reviewReleases",
                scoped(
                        "plugin_finance_cash_source_review_release",
                        "t.id,t.status,t.version,t.content_digest,t.review_id,t.usage_id",
                        "t.review_id IN(SELECT id FROM reviews)",
                        "t.id"),
                true,
                50000);
        d.load(
                "allocationReleases",
                scoped(
                        "plugin_finance_cash_allocation_release",
                        "t.id,t.status,t.version,t.content_digest,t.allocation_id,t.dependency_id",
                        "t.allocation_id IN(SELECT id FROM allocs)",
                        "t.id"),
                true,
                50000);
        d.load(
                "replacements",
                scoped(
                        "plugin_finance_cash_allocation_replacement",
                        "t.id,t.status,t.version,t.content_digest,t.old_allocation_id,t.new_allocation_id,t.review_id",
                        "t.old_allocation_id IN(SELECT id FROM allocs) OR t.new_allocation_id"
                                + " IN(SELECT id FROM allocs)",
                        "t.id"),
                true,
                50000);
        var originalIds = new TreeSet<String>();
        for (var i : items) originalIds.add(id(i.get("evidence_original_id")));
        for (var r : d.get("raws")) originalIds.add(id(r.get("source_original_id")));
        for (String name : List.of("reviewEvidence", "policyEvidence"))
            for (var e : d.get(name)) originalIds.add(id(e.get("original_id")));
        for (var l : d.get("allocationLines")) originalIds.add(id(l.get("evidence_original_id")));
        if (originalIds.size() > 10000) throw error("PREVIEW_LIMIT_EXCEEDED", "原件元数据超过安全预算");
        var originals =
                originalIds.isEmpty()
                        ? new ArrayList<Map<String, Object>>()
                        : db.list(
                                "SELECT id,version,sha256 FROM plugin_finance_original WHERE"
                                        + " tenant_id=? AND book_id=? AND year_id=? AND"
                                        + " id=ANY(?::uuid[]) ORDER BY id",
                                b.get("tenant_id"),
                                book,
                                year,
                                "{" + String.join(",", originalIds) + "}");
        d.sets.put("originals", originals);
        var scope =
                map(
                        "tenantId",
                        id(b.get("tenant_id")),
                        "legalEntityId",
                        id(b.get("legal_entity_id")),
                        "bookId",
                        book.toString(),
                        "yearId",
                        year.toString(),
                        "scopeRevisionId",
                        scopeId.toString(),
                        "approvedScopeVersion",
                        s.get("approved_scope_version"),
                        "scopeContentDigest",
                        s.get("content_digest"),
                        "standard",
                        s.get("standard"),
                        "templateId",
                        s.get("template_id"),
                        "periodStart",
                        start.toString(),
                        "periodEnd",
                        end.toString(),
                        "currency",
                        "CNY",
                        "reportScope",
                        "SEPARATE",
                        "includedAccountCount",
                        items.stream()
                                .filter(i -> "INCLUDED".equals(i.get("qualification")))
                                .count(),
                        "excludedAccountCount",
                        items.stream()
                                .filter(i -> "EXCLUDED".equals(i.get("qualification")))
                                .count(),
                        "yearStatus",
                        s.get("year_status"),
                        "periodStatus",
                        s.get("period_status"));
        var wm =
                map(
                        "actorId",
                        id(b.get("actor_id")),
                        "authorizationContextDigest",
                        authorizationDigest,
                        "scopeRevisionId",
                        scopeId.toString(),
                        "approvedScopeVersion",
                        s.get("approved_scope_version"),
                        "scopeContentDigest",
                        s.get("content_digest"),
                        "periodStart",
                        start.toString(),
                        "periodEnd",
                        end.toString(),
                        "standard",
                        s.get("standard"),
                        "currency",
                        "CNY",
                        "reportScope",
                        "SEPARATE",
                        "globalScopeVersion",
                        counter(b, year, "plugin_finance_cash_scope_guard", "scope_version"),
                        "year",
                        finance.watermark(b, year),
                        "registrationVersion",
                        counter(
                                b,
                                year,
                                "plugin_finance_cash_registration_guard",
                                "registration_version"),
                        "yearMatchVersion",
                        counter(b, year, "plugin_finance_cash_match_guard", "match_version"),
                        "sourceReviewVersion",
                        counter(
                                b,
                                year,
                                "plugin_finance_cash_source_review_guard",
                                "review_version"),
                        "allocationVersion",
                        counter(
                                b,
                                year,
                                "plugin_finance_cash_allocation_guard",
                                "allocation_version"),
                        "policyPublicationVersion",
                        counter(b, year, "plugin_finance_cash_policy_guard", "publication_version"),
                        "sourceGenerationDigest",
                        hash(d.get("generations")),
                        "workflowDigest",
                        hash(d.sets),
                        "populationDigest",
                        hash(population),
                        "evidenceStateDigest",
                        hash(map("ids", originalIds, "metadata", originals)),
                        "queryRulesVersion",
                        SCHEMA);
        var rows = new ArrayList<Map<String, Object>>();
        for (var p : population) rows.add(row(p, d, scope));
        var filtered =
                rows.stream()
                        .filter(r -> filter == null || filter.equals(r.get("primaryStage")))
                        .toList();
        String filterDigest = hash(map("primaryStage", filter, "limit", limit));
        String context =
                hash(
                        map(
                                "actorId",
                                b.get("actor_id"),
                                "authorizationContextDigest",
                                authorizationDigest,
                                "scope",
                                scope,
                                "filter",
                                filterDigest,
                                "watermark",
                                wm));
        int offset = 0;
        if (cursor != null) {
            var c = cursor(cursor);
            for (String k :
                    List.of(
                            "actorId",
                            "tenantId",
                            "book",
                            "year",
                            "scopeRevisionId",
                            "periodStart",
                            "periodEnd",
                            "filterDigest")) {
                Object expected =
                        switch (k) {
                            case "actorId" -> id(b.get("actor_id"));
                            case "tenantId" -> id(b.get("tenant_id"));
                            case "book" -> book.toString();
                            case "year" -> year.toString();
                            case "scopeRevisionId" -> scopeId.toString();
                            case "periodStart" -> start.toString();
                            case "periodEnd" -> end.toString();
                            default -> filterDigest;
                        };
                if (!Objects.equals(expected, c.get(k)))
                    throw FinanceException.invalid("游标范围、账号或筛选不匹配");
            }
            if (!authorizationDigest.equals(c.get("authorizationContextDigest"))
                    || !context.equals(c.get("contextDigest")))
                throw error("CASH_COVERAGE_PREVIEW_STALE", "取数或授权上下文已变，请丢弃整组旧页并显式重新取首屏");
            boolean found = false;
            for (int i = 0; i < filtered.size(); i++) {
                var r = filtered.get(i);
                if (r.get("accountingDate").equals(c.get("lastDate"))
                        && r.get("journalId").equals(c.get("lastJournalId"))
                        && ((Number) r.get("lineNo")).longValue() == num(c.get("lastLineNo"))) {
                    offset = i + 1;
                    found = true;
                    break;
                }
            }
            if (!found) throw FinanceException.invalid("游标锚点不在当前完整筛选总体");
        }
        var page = filtered.subList(offset, Math.min(filtered.size(), offset + limit));
        String next = null;
        if (offset + page.size() < filtered.size()) {
            var last = page.getLast();
            next =
                    Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(
                                    finance.encode(
                                                    map(
                                                            "v",
                                                            1,
                                                            "actorId",
                                                            id(b.get("actor_id")),
                                                            "authorizationContextDigest",
                                                            authorizationDigest,
                                                            "tenantId",
                                                            id(b.get("tenant_id")),
                                                            "book",
                                                            book.toString(),
                                                            "year",
                                                            year.toString(),
                                                            "scopeRevisionId",
                                                            scopeId.toString(),
                                                            "periodStart",
                                                            start.toString(),
                                                            "periodEnd",
                                                            end.toString(),
                                                            "filterDigest",
                                                            filterDigest,
                                                            "contextDigest",
                                                            context,
                                                            "lastDate",
                                                            last.get("accountingDate"),
                                                            "lastJournalId",
                                                            last.get("journalId"),
                                                            "lastLineNo",
                                                            last.get("lineNo")))
                                            .getBytes(StandardCharsets.UTF_8));
        }
        boolean unknown =
                rows.stream()
                        .anyMatch(
                                r ->
                                        List.of("UNSUPPORTED_LEDGER_FACT", "SCOPE_LINEAGE_MISMATCH")
                                                .contains(r.get("primaryStage")));
        boolean differences =
                rows.stream().anyMatch(r -> !"SUPPORTED_CHAIN".equals(r.get("primaryStage")));
        return map(
                "schemaVersion",
                SCHEMA,
                "mode",
                "READ_ONLY_PREVIEW",
                "pointInTimeOnly",
                true,
                "resultStatus",
                unknown
                        ? "UNDETERMINED"
                        : differences ? "DIFFERENCES_FOUND" : "LEDGER_LINKS_SUPPORTED",
                "scope",
                scope,
                "asOfWatermark",
                wm,
                "contextDigest",
                context,
                "summary",
                summary(rows, filtered.size()),
                "items",
                page,
                "nextCursor",
                next,
                "externalSourceCompleteness",
                "MISSING",
                "coverageVerified",
                false,
                "formalReportContributionEnabled",
                false,
                "reportPublishable",
                false,
                "limitations",
                LIMITATIONS);
    }

    Map<String, Object> cursor(String token) {
        try {
            if (token.isEmpty() || token.length() > 4000 || !token.matches("[A-Za-z0-9_-]+"))
                throw new IllegalArgumentException();
            byte[] bytes = Base64.getUrlDecoder().decode(token);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(token))
                throw new IllegalArgumentException();
            var c = finance.decode(new String(bytes, StandardCharsets.UTF_8));
            if (!c.keySet().equals(CURSOR_KEYS) || !Integer.valueOf(1).equals(c.get("v")))
                throw new IllegalArgumentException();
            for (String k :
                    List.of(
                            "actorId",
                            "tenantId",
                            "book",
                            "year",
                            "scopeRevisionId",
                            "lastJournalId")) uuid(c.get(k));
            for (String k : List.of("periodStart", "periodEnd", "lastDate")) {
                if (!(c.get(k) instanceof String date)
                        || !LocalDate.parse(date).toString().equals(date))
                    throw new IllegalArgumentException();
            }
            for (String k : List.of("authorizationContextDigest", "filterDigest", "contextDigest"))
                if (!(c.get(k) instanceof String h) || !h.matches("[a-f0-9]{64}"))
                    throw new IllegalArgumentException();
            if (num(c.get("lastLineNo")) > 500) throw new IllegalArgumentException();
            return c;
        } catch (RuntimeException bad) {
            throw FinanceException.invalid("游标格式、类型或封闭字段无效");
        }
    }

    boolean evidence(Data d, Object original, Object version, Object sha) {
        var o = d.one("originals", original);
        return o != null && eq(o.get("version"), version) && eq(o.get("sha256"), sha);
    }

    void checkEvidence(
            Data d, List<Map<String, Object>> issues, Object original, Object version, Object sha) {
        if (!evidence(d, original, version, sha))
            issue(issues, "STALE_EVIDENCE", "真实原件存在性/版本/权威摘要与已保存依据不一致", "ORIGINAL", original);
    }

    static boolean tuple(Map<String, Object> a, Map<String, Object> b, String... keys) {
        if (a == null || b == null) return false;
        for (String k : keys) if (!eq(a.get(k), b.get(k))) return false;
        return true;
    }

    static String projectedAmount(Object o, String fmt) {
        BigDecimal n = decimal(o);
        return n == null || n.precision() - n.scale() > 20
                ? null
                : amount(n, fmt.equals("LEGACY_6") ? 6 : 8);
    }

    Map<String, Object> row(Map<String, Object> p, Data d, Map<String, Object> scope) {
        var issues = new ArrayList<Map<String, Object>>();
        Object journal = p.get("journal_id"),
                line = p.get("line_no"),
                account = p.get("account_id");
        String fmt =
                ("V4_8".equals(p.get("amount_format_version"))
                                || "LEGACY_6".equals(p.get("amount_format_version")))
                        ? p.get("amount_format_version").toString()
                        : "UNKNOWN";
        String debit = projectedAmount(p.get("debit"), fmt),
                credit = projectedAmount(p.get("credit"), fmt);
        BigDecimal dr = decimal(debit), cr = decimal(credit);
        String direction = "UNKNOWN", normalized = null;
        if (dr != null
                && cr != null
                && ((dr.signum() > 0 && cr.signum() == 0) || (cr.signum() > 0 && dr.signum() == 0))
                && (!fmt.equals("V4_8")
                        || (dr.stripTrailingZeros().scale() <= 2
                                && cr.stripTrailingZeros().scale() <= 2))) {
            direction = dr.signum() > 0 ? "IN" : "OUT";
            normalized = dr.signum() > 0 ? debit : credit;
        } else issue(issues, "INVALID_CASH_LEG", "现金行不是可解释的单边正数，未知金额/方向不当零", "JOURNAL", journal);
        for (String k : List.of("debit", "credit")) {
            BigDecimal n = decimal(p.get(k));
            if (n != null && n.precision() - n.scale() > 20)
                issue(issues, "AMOUNT_OVERFLOW", "现金行金额超出展示容量", "JOURNAL", journal);
        }
        if (p.get("posting_digest") == null)
            issue(issues, "MISSING_POSTING", "POSTED凭证缺少真实posting事实", "JOURNAL", journal);
        if (p.get("posting_seq") == null)
            issue(issues, "UNSEQUENCED_POSTING", "已记账行没有可复核提交序号，仍保留在总体", "JOURNAL", journal);
        if (!fmt.equals("V4_8"))
            issue(
                    issues,
                    "LEGACY_PRECISION_UNSUPPORTED",
                    "旧精度或未知格式只读诊断，不自动升级历史金额",
                    "JOURNAL",
                    journal);
        if (p.get("posting_journal_version") != null
                && ((Number) p.get("posting_journal_version")).longValue() + 1
                        != ((Number) p.get("journal_version")).longValue())
            issue(issues, "TYPED_LINK_INTEGRITY_FAILURE", "凭证与posting版本链不一致", "JOURNAL", journal);
        if (d.find("population", "journal_id", journal).size() > 1
                || p.get("reversal_of") != null
                || !"NORMAL".equals(p.get("type")))
            issue(
                    issues,
                    "COMPLEX_FACT_REQUIRES_JUDGMENT",
                    "多现金腿/冲销或特殊凭证需专项判断，不能自动当外部普通收付",
                    "JOURNAL",
                    journal);
        var item = first(d.find("scopeItems", "account_id", account));
        var current = d.one("accounts", account);
        if (item == null
                || current == null
                || !Boolean.TRUE.equals(current.get("enabled"))
                || !"ASSET".equals(current.get("category"))
                || !"DEBIT".equals(current.get("normalSide"))
                || !hash(current).equals(item.get("account_version_digest")))
            issue(
                    issues,
                    "STALE_ACCOUNT",
                    "范围年度科目资格/快照与当前事实不一致，未从总体删除",
                    "SCOPE",
                    scope.get("scopeRevisionId"));
        if (item != null) {
            checkEvidence(
                    d,
                    issues,
                    item.get("evidence_original_id"),
                    item.get("original_version"),
                    item.get("original_sha256"));
            if (!Boolean.TRUE.equals(item.get("single_cash_instrument"))
                    || !Boolean.TRUE.equals(item.get("available_on_demand"))
                    || !"NONE".equals(item.get("restriction_kind")))
                issue(
                        issues,
                        "SCOPE_LINEAGE_MISMATCH",
                        "已批准范围项资格记录不一致",
                        "SCOPE",
                        scope.get("scopeRevisionId"));
        }
        var legClaims =
                d.find("claims", "journal_id", journal).stream()
                        .filter(c -> eq(c.get("line_no"), line))
                        .toList();
        var actives = legClaims.stream().filter(c -> "ACTIVE".equals(c.get("state"))).toList();
        if (actives.size() > 1)
            issue(
                    issues,
                    "TYPED_LINK_INTEGRITY_FAILURE",
                    "同一账务现金行有多个ACTIVE claim",
                    "JOURNAL",
                    journal);
        var claim = first(actives);
        var raw = claim == null ? null : d.one("raws", claim.get("raw_registration_id"));
        if (raw == null && !legClaims.isEmpty())
            raw = d.one("raws", legClaims.getFirst().get("raw_registration_id"));
        if (raw == null)
            raw =
                    first(
                            d.get("raws").stream()
                                    .filter(
                                            r ->
                                                    eq(r.get("journal_id"), journal)
                                                            && eq(r.get("line_no"), line))
                                    .toList());
        Map<String, Object> rawOut = null, claimOut = null, reviewOut = null, allocOut = null;
        Map<String, Object> review = null,
                usage = null,
                allocation = null,
                dep = null,
                pointer = null;
        boolean claimOk = claim != null && raw != null;
        if (raw != null) {
            try {
                if (current == null
                        || raw.get("account_snapshot") == null
                        || !hash(current)
                                .equals(
                                        hash(
                                                finance.decode(
                                                        raw.get("account_snapshot").toString()))))
                    issue(issues, "STALE_ACCOUNT", "来源年度科目快照与当前事实不同", "RAW", raw.get("id"));
            } catch (RuntimeException invalidSnapshot) {
                issue(issues, "STALE_ACCOUNT", "来源年度科目快照无法解释", "RAW", raw.get("id"));
            }
            rawOut =
                    map(
                            "id",
                            id(raw.get("id")),
                            "status",
                            raw.get("status"),
                            "version",
                            raw.get("version"),
                            "contentDigest",
                            raw.get("content_digest"),
                            "sourceSystem",
                            raw.get("source_system"),
                            "sourceMovementKey",
                            raw.get("source_movement_key"),
                            "sourceDocumentId",
                            raw.get("source_document_id"),
                            "sourceRowReference",
                            raw.get("source_row_reference"),
                            "sourceOriginalId",
                            id(raw.get("source_original_id")),
                            "sourceOriginalSha256",
                            raw.get("source_original_sha256"),
                            "scopeRevisionId",
                            id(raw.get("cash_scope_revision_id")),
                            "scopeVersion",
                            raw.get("approved_scope_version"),
                            "scopeDigest",
                            raw.get("scope_content_digest"));
            checkEvidence(
                    d,
                    issues,
                    raw.get("source_original_id"),
                    raw.get("original_version"),
                    raw.get("source_original_sha256"));
            if (!eq(raw.get("cash_scope_revision_id"), scope.get("scopeRevisionId"))
                    || !eq(raw.get("approved_scope_version"), scope.get("approvedScopeVersion"))
                    || !eq(raw.get("scope_content_digest"), scope.get("scopeContentDigest"))
                    || !eq(raw.get("account_id"), account)
                    || !eq(raw.get("occurred_date"), p.get("date")))
                issue(
                        issues,
                        "SCOPE_LINEAGE_MISMATCH",
                        "来源冻结范围/账户/日期与本预览范围不一致，未自动重绑",
                        "RAW",
                        raw.get("id"));
            if (!FinanceCashMovements.KINDS.containsKey(raw.get("transaction_kind")))
                issue(
                        issues,
                        "COMPLEX_FACT_REQUIRES_JUDGMENT",
                        "来源交易性质不在普通支持目录",
                        "RAW",
                        raw.get("id"));
        }
        if (claim != null) {
            var generation =
                    first(
                            d.find(
                                    "generations",
                                    "raw_registration_id",
                                    claim.get("raw_registration_id")));
            long g =
                    generation == null
                            ? 0
                            : ((Number) generation.get("source_generation")).longValue();
            if (g < 1)
                issue(
                        issues,
                        "TYPED_LINK_INTEGRITY_FAILURE",
                        "ACTIVE claim缺失来源代次守卫",
                        "CLAIM",
                        claim.get("id"));
            else
                claimOut =
                        map(
                                "id",
                                id(claim.get("id")),
                                "kind",
                                claim.get("claim_kind"),
                                "correctionId",
                                id(claim.get("correction_id")),
                                "state",
                                "ACTIVE",
                                "generation",
                                claim.get("claim_generation"),
                                "contentDigest",
                                claim.get("claim_content_digest"),
                                "sourceMatchGeneration",
                                g);
            claimOk =
                    claimOk
                            && g == ((Number) claim.get("claim_generation")).longValue()
                            && eq(claim.get("account_id"), account)
                            && eq(claim.get("journal_version"), p.get("journal_version"))
                            && eq(claim.get("posting_seq"), p.get("posting_seq"))
                            && eq(claim.get("posting_digest"), p.get("posting_digest"));
            if (raw != null) {
                claimOk =
                        claimOk
                                && normalized != null
                                && decimal(raw.get("amount")) != null
                                && decimal(normalized).compareTo(decimal(raw.get("amount"))) == 0
                                && direction.equals(raw.get("direction"));
                if ("RAW_REGISTRATION".equals(claim.get("claim_kind")))
                    claimOk =
                            claimOk
                                    && "REGISTERED".equals(raw.get("status"))
                                    && eq(
                                            claim.get("claim_content_digest"),
                                            raw.get("content_digest"))
                                    && eq(raw.get("journal_id"), journal)
                                    && eq(raw.get("line_no"), line);
                else {
                    var correction = d.one("corrections", claim.get("correction_id"));
                    claimOk =
                            claimOk
                                    && "VOIDED".equals(raw.get("status"))
                                    && correction != null
                                    && "APPROVED".equals(correction.get("status"))
                                    && eq(correction.get("approved_claim_id"), claim.get("id"))
                                    && eq(
                                            correction.get("content_digest"),
                                            claim.get("claim_content_digest"))
                                    && eq(correction.get("raw_registration_id"), raw.get("id"));
                }
            }
            if (!claimOk)
                issue(
                        issues,
                        "TYPED_LINK_INTEGRITY_FAILURE",
                        "有效claim/来源/账务金额或版本摘要链不一致",
                        "CLAIM",
                        claim.get("id"));
            var approved =
                    d.find("reviews", "claim_id", claim.get("id")).stream()
                            .filter(r -> "APPROVED".equals(r.get("status")))
                            .toList();
            review = first(approved);
            if (approved.size() > 1)
                issue(
                        issues,
                        "TYPED_LINK_INTEGRITY_FAILURE",
                        "同claim多个人审批准记录",
                        "CLAIM",
                        claim.get("id"));
            if (review != null) {
                usage = d.one("usages", review.get("approved_usage_id"));
                boolean activeUsage = usage != null && "ACTIVE".equals(usage.get("state"));
                reviewOut =
                        map(
                                "id",
                                id(review.get("id")),
                                "status",
                                review.get("status"),
                                "version",
                                review.get("version"),
                                "contentDigest",
                                review.get("content_digest"),
                                "activeUsageId",
                                activeUsage ? id(usage.get("id")) : null);
                if (!activeUsage
                        || !"SOURCE_REVIEW".equals(usage.get("usage_kind"))
                        || !eq(usage.get("source_review_id"), review.get("id"))
                        || !eq(usage.get("consumer_content_digest"), review.get("content_digest"))
                        || !tuple(
                                usage,
                                review,
                                "claim_id",
                                "raw_registration_id",
                                "claim_generation",
                                "claim_content_digest",
                                "approved_by",
                                "approved_at")
                        || !eq(
                                review.get("claim_content_digest"),
                                claim.get("claim_content_digest"))
                        || !eq(review.get("claim_generation"), claim.get("claim_generation"))
                        || !eq(review.get("raw_registration_id"), claim.get("raw_registration_id"))
                        || !eq(review.get("source_match_generation"), g)) {
                    issue(
                            issues,
                            "TYPED_LINK_INTEGRITY_FAILURE",
                            "人审批复缺少精确ACTIVE SOURCE_REVIEW依赖",
                            "REVIEW",
                            review.get("id"));
                    usage = null;
                }
                for (var e : d.find("reviewEvidence", "review_id", review.get("id")))
                    checkEvidence(
                            d,
                            issues,
                            e.get("original_id"),
                            e.get("original_version"),
                            e.get("sha256"));
                var currentReview = review;
                pointer = first(d.find("pointers", "review_id", review.get("id")));
                allocation =
                        pointer == null
                                ? first(
                                        d
                                                .find(
                                                        "allocations",
                                                        "review_id",
                                                        currentReview.get("id"))
                                                .stream()
                                                .filter(a -> "APPROVED".equals(a.get("status")))
                                                .toList())
                                : d.one("allocations", pointer.get("allocation_id"));
                if (allocation != null) {
                    dep = d.one("dependencies", allocation.get("approved_dependency_id"));
                    boolean activeDep = dep != null && "ACTIVE".equals(dep.get("state"));
                    var lines = d.find("allocationLines", "allocation_id", allocation.get("id"));
                    allocOut =
                            map(
                                    "id",
                                    id(allocation.get("id")),
                                    "status",
                                    allocation.get("status"),
                                    "version",
                                    allocation.get("version"),
                                    "contentDigest",
                                    allocation.get("content_digest"),
                                    "activeDependencyId",
                                    activeDep ? id(dep.get("id")) : null,
                                    "pointerVersion",
                                    pointer == null ? null : pointer.get("pointer_version"),
                                    "normalizedTotal",
                                    amount(decimal(allocation.get("total")), 8),
                                    "lineCount",
                                    lines.size());
                    boolean valid =
                            activeDep
                                    && pointer != null
                                    && "APPROVED".equals(allocation.get("status"))
                                    && eq(pointer.get("allocation_id"), allocation.get("id"))
                                    && eq(pointer.get("dependency_id"), dep.get("id"))
                                    && eq(
                                            pointer.get("content_digest"),
                                            allocation.get("content_digest"))
                                    && tuple(
                                            pointer,
                                            allocation,
                                            "review_id",
                                            "review_usage_id",
                                            "review_content_digest",
                                            "claim_id",
                                            "raw_registration_id",
                                            "claim_generation",
                                            "claim_content_digest")
                                    && eq(dep.get("activated_by"), allocation.get("approved_by"))
                                    && eq(dep.get("activated_at"), allocation.get("approved_at"))
                                    && eq(dep.get("allocation_id"), allocation.get("id"))
                                    && eq(
                                            dep.get("consumer_content_digest"),
                                            allocation.get("content_digest"))
                                    && tuple(
                                            dep,
                                            allocation,
                                            "review_id",
                                            "review_usage_id",
                                            "review_content_digest",
                                            "claim_id",
                                            "raw_registration_id",
                                            "claim_generation",
                                            "claim_content_digest")
                                    && eq(allocation.get("review_id"), review.get("id"))
                                    && eq(
                                            allocation.get("review_usage_id"),
                                            review.get("approved_usage_id"))
                                    && eq(
                                            allocation.get("review_content_digest"),
                                            review.get("content_digest"))
                                    && eq(allocation.get("source_match_generation"), g)
                                    && normalized != null
                                    && decimal(allocation.get("total"))
                                                    .compareTo(decimal(normalized))
                                            == 0
                                    && lines.size() >= 1
                                    && lines.size() <= 20;
                    BigDecimal sum = BigDecimal.ZERO;
                    for (var l : lines) {
                        BigDecimal value = decimal(l.get("amount"));
                        if (value == null) valid = false;
                        else sum = sum.add(value);
                        checkEvidence(
                                d,
                                issues,
                                l.get("evidence_original_id"),
                                l.get("evidence_version"),
                                l.get("evidence_sha256"));
                        for (var e : d.find("policyEvidence", "policy_id", l.get("policy_id")))
                            checkEvidence(
                                    d,
                                    issues,
                                    e.get("original_id"),
                                    e.get("original_version"),
                                    e.get("sha256"));
                    }
                    if (decimal(allocation.get("total")) == null
                            || sum.compareTo(decimal(allocation.get("total"))) != 0) valid = false;
                    if (!valid) {
                        issue(
                                issues,
                                "TYPED_LINK_INTEGRITY_FAILURE",
                                "当前分配pointer/typed依赖/全额组件不一致",
                                "ALLOCATION",
                                allocation.get("id"));
                        dep = null;
                    }
                }
            }
        } else issue(issues, "NO_ACTIVE_CLAIM", "已记账现金行缺少当前有效来源匹配", "JOURNAL", journal);
        boolean reviewEffective = review != null && usage != null,
                allocationEffective = allocation != null && dep != null && pointer != null;
        if (claim != null && !reviewEffective)
            issue(issues, "NO_EFFECTIVE_REVIEW", "当前来源没有有效人工复核和ACTIVE依赖", "CLAIM", claim.get("id"));
        if (reviewEffective && !allocationEffective)
            issue(
                    issues,
                    "NO_EFFECTIVE_ALLOCATION",
                    "当前人审没有有效全额分配和当前pointer",
                    "REVIEW",
                    review.get("id"));
        Set<String> legClaimIds = new HashSet<>();
        for (var c : legClaims) legClaimIds.add(id(c.get("id")));
        var legReviews = related(d, "reviews", "claim_id", legClaimIds);
        Set<String> reviewIds = new HashSet<>();
        for (var r : legReviews) reviewIds.add(id(r.get("id")));
        var legAllocs = related(d, "allocations", "review_id", reviewIds);
        Set<String> allocIds = new HashSet<>();
        for (var a : legAllocs) allocIds.add(id(a.get("id")));
        var withdrawal =
                related(d, "withdrawals", "active_claim_id", legClaimIds).stream()
                        .filter(w -> "REQUESTED".equals(w.get("status")))
                        .toList();
        var reviewRelease =
                related(d, "reviewReleases", "review_id", reviewIds).stream()
                        .filter(q -> "REQUESTED".equals(q.get("status")))
                        .toList();
        var allocRelease =
                related(d, "allocationReleases", "allocation_id", allocIds).stream()
                        .filter(q -> "REQUESTED".equals(q.get("status")))
                        .toList();
        var replacement =
                related(d, "replacements", "review_id", reviewIds).stream()
                        .filter(q -> "REQUESTED".equals(q.get("status")))
                        .toList();
        var reviewProposals =
                legReviews.stream()
                        .filter(r -> Set.of("DRAFT", "SUBMITTED").contains(r.get("status")))
                        .toList();
        var allocProposals =
                legAllocs.stream()
                        .filter(a -> Set.of("DRAFT", "SUBMITTED").contains(a.get("status")))
                        .toList();
        var pending =
                map(
                        "matchWithdrawalIds",
                        ids(withdrawal),
                        "reviewReleaseIds",
                        ids(reviewRelease),
                        "allocationReleaseIds",
                        ids(allocRelease),
                        "replacementIds",
                        ids(replacement),
                        "reviewProposalIds",
                        ids(reviewProposals),
                        "allocationProposalIds",
                        ids(allocProposals));
        for (var binding :
                List.of(
                        new Object[] {withdrawal, "PENDING_MATCH_WITHDRAWAL", "RELEASE"},
                        new Object[] {reviewRelease, "PENDING_REVIEW_RELEASE", "RELEASE"},
                        new Object[] {allocRelease, "PENDING_ALLOCATION_RELEASE", "RELEASE"},
                        new Object[] {replacement, "PENDING_REPLACEMENT", "REPLACEMENT"}))
            for (var request : (List<Map<String, Object>>) binding[0])
                issue(
                        issues,
                        (String) binding[1],
                        "存在待审变更，当前历史效果保留但新使用冻结",
                        (String) binding[2],
                        request.get("id"));
        if (!reviewEffective && !reviewProposals.isEmpty())
            issue(
                    issues,
                    "AWAITING_REVIEW",
                    "存在待处理人审提案，但不代表已批准",
                    "REVIEW",
                    reviewProposals.getFirst().get("id"));
        if (!allocationEffective && !allocProposals.isEmpty())
            issue(
                    issues,
                    "AWAITING_ALLOCATION",
                    "存在待处理分配提案，但不计入有效分配",
                    "ALLOCATION",
                    allocProposals.getFirst().get("id"));
        String stage = "SUPPORTED_CHAIN";
        for (String candidate : STAGES) {
            if (candidate.equals("SUPPORTED_CHAIN")) break;
            if (issues.stream()
                    .anyMatch(i -> STAGE_ISSUES.get(candidate).contains(i.get("code")))) {
                stage = candidate;
                break;
            }
        }
        return map(
                "journalId",
                id(journal),
                "lineNo",
                line,
                "accountId",
                id(account),
                "accountingDate",
                p.get("date"),
                "journalVersion",
                p.get("journal_version"),
                "postingSeq",
                p.get("posting_seq"),
                "postingDigest",
                p.get("posting_digest"),
                "number",
                p.get("number"),
                "amountFormatVersion",
                fmt,
                "debit",
                debit,
                "credit",
                credit,
                "direction",
                direction,
                "normalizedAmount",
                normalized,
                "primaryStage",
                stage,
                "issues",
                issues,
                "currentChainEffective",
                claimOk && reviewEffective && allocationEffective,
                "newUseFrozen",
                !withdrawal.isEmpty()
                        || !reviewRelease.isEmpty()
                        || !allocRelease.isEmpty()
                        || !replacement.isEmpty(),
                "raw",
                rawOut,
                "claim",
                claimOut,
                "review",
                reviewOut,
                "allocation",
                allocOut,
                "pending",
                pending);
    }

    static class Totals {
        long rows, in, out, unknown;
        BigDecimal inAmount = BigDecimal.ZERO, outAmount = BigDecimal.ZERO;

        void add(Map<String, Object> r) {
            rows++;
            if ("IN".equals(r.get("direction"))) {
                in++;
                inAmount = inAmount.add(decimal(r.get("normalizedAmount")));
            } else if ("OUT".equals(r.get("direction"))) {
                out++;
                outAmount = outAmount.add(decimal(r.get("normalizedAmount")));
            } else unknown++;
        }

        Map<String, Object> json() {
            return map(
                    "rowCount",
                    rows,
                    "inCount",
                    in,
                    "outCount",
                    out,
                    "unknownDirectionCount",
                    unknown,
                    "inAmount",
                    total(inAmount),
                    "outAmount",
                    total(outAmount));
        }
    }

    Map<String, Object> summary(List<Map<String, Object>> rows, int displayCount) {
        var stages = new LinkedHashMap<String, Totals>();
        STAGES.forEach(s -> stages.put(s, new Totals()));
        var all = new Totals();
        BigDecimal debit = BigDecimal.ZERO, credit = BigDecimal.ZERO;
        for (var row : rows) {
            all.add(row);
            stages.get(row.get("primaryStage")).add(row);
            BigDecimal dr = decimal(row.get("debit")), cr = decimal(row.get("credit"));
            debit = debit == null || dr == null ? null : debit.add(dr);
            credit = credit == null || cr == null ? null : credit.add(cr);
        }
        var stageJson = new LinkedHashMap<String, Object>();
        stages.forEach((s, t) -> stageJson.put(s, t.json()));
        return map(
                "populationRowCount",
                all.rows,
                "displayMatchedRowCount",
                displayCount,
                "knownDirectionalRowCount",
                all.in + all.out,
                "unknownDirectionRowCount",
                all.unknown,
                "ledgerDebitTotal",
                debit == null ? null : total(debit),
                "ledgerCreditTotal",
                credit == null ? null : total(credit),
                "ledgerInTotal",
                total(all.inAmount),
                "ledgerOutTotal",
                total(all.outAmount),
                "supportedInTotal",
                total(stages.get("SUPPORTED_CHAIN").inAmount),
                "supportedOutTotal",
                total(stages.get("SUPPORTED_CHAIN").outAmount),
                "directionalTotalsStatus",
                all.unknown > 0 ? "PARTIAL_UNSUPPORTED_ROWS" : "COMPLETE",
                "stageTotals",
                stageJson);
    }
}
