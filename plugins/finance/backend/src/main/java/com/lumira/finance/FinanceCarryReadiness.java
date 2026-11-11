package com.lumira.finance;

import com.lumira.platform.common.AuthorizationContextProjectionPort;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Nonpersisted double-year observation. This service has no financial mutation or approval entry.
 */
@Service
public class FinanceCarryReadiness extends FinanceCarryReadinessProjection {
    @Autowired
    public FinanceCarryReadiness(
            FinanceStore db,
            FinanceService finance,
            FinanceJournalPersonGate gate,
            PlatformTransactionManager tx,
            AuthorizationContextProjectionPort auth) {
        super(db, finance, gate, tx, auth);
    }

    static final Set<String> TOKEN_KEYS =
            Set.of(
                    "schema",
                    "resource",
                    "actor",
                    "tenant",
                    "entity",
                    "book",
                    "source",
                    "target",
                    "context");
    static final Set<String> CURSOR_KEYS =
            Set.of(
                    "schema",
                    "resource",
                    "actor",
                    "tenant",
                    "entity",
                    "book",
                    "source",
                    "target",
                    "context",
                    "filter",
                    "limit",
                    "anchor");
    static final List<String> MONETARY =
            List.of(
                    "sourceOpening",
                    "sourcePostedDebit",
                    "sourcePostedCredit",
                    "sourceEndingBalance",
                    "targetOpening",
                    "targetPostedDebit",
                    "targetPostedCredit",
                    "targetPostedNet",
                    "targetEndingBalance");

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object x) {
        return (Map<String, Object>) x;
    }

    Map<String, Object> tokenContext(
            Map<String, Object> scope, Map<String, Object> actor, String context) {
        return map(
                "schema",
                FinanceCarryReadinessInput.SCHEMA,
                "resource",
                "carry-readiness",
                "actor",
                actor.get("accountId").toString(),
                "tenant",
                scope.get("tenantId").toString(),
                "entity",
                scope.get("legalEntityId").toString(),
                "book",
                scope.get("bookId").toString(),
                "source",
                scope.get("sourceYearId").toString(),
                "target",
                scope.get("targetYearId").toString(),
                "context",
                context);
    }

    Map<String, Object> validateToken(
            String encoded,
            Map<String, Object> expected,
            boolean cursor,
            String filter,
            int limit) {
        var value = untoken(encoded);
        if (!value.keySet().equals(cursor ? CURSOR_KEYS : TOKEN_KEYS))
            throw FinanceException.invalid("观察token结构无效");
        for (String key : TOKEN_KEYS) {
            if (key.equals("context")) continue;
            if (!(value.get(key) instanceof String)
                    || !Objects.equals(value.get(key), expected.get(key)))
                throw FinanceException.invalid("观察token不属于当前资源、账号或双年度范围");
        }
        if (cursor) {
            if (!Objects.equals(filter, value.get("filter"))
                    || !(value.get("limit") instanceof Number n)
                    || n.longValue() != limit
                    || !(value.get("anchor") instanceof String anchor)
                    || !anchor.matches("[ST]:[a-f0-9-]{36}"))
                throw FinanceException.invalid("分页token筛选或位置无效");
            FinanceCarryReadinessInput.uuid(value.get("anchor").toString().substring(2));
        }
        if (!(value.get("context") instanceof String digest) || !digest.matches("[a-f0-9]{64}"))
            throw FinanceException.invalid("观察摘要无效");
        if (!Objects.equals(value.get("context"), expected.get("context")))
            throw error("CARRY_READINESS_STALE", "双年度事实、状态或授权已改变，请重新读取整体预检");
        return value;
    }

    public Object read(
            String principal, UUID book, UUID sourceYear, String op, HttpServletRequest request) {
        var query = FinanceCarryReadinessInput.parse(op, request);
        return transaction(
                false,
                call -> {
                    // Both grants are checked before loading either year's account/fact metadata.
                    var b = call.scope(principal, book, sourceYear, null);
                    var targetScope = call.scope(principal, book, query.target(), null);
                    var s = yearData(call, b, sourceYear);
                    var t = yearData(call, targetScope, query.target());
                    if (t.number != s.number + 1)
                        throw FinanceException.invalid("仅支持同账套连续的源年度和次年度");
                    var scope =
                            map(
                                    "tenantId",
                                    b.get("tenant_id"),
                                    "legalEntityId",
                                    b.get("legal_entity_id"),
                                    "bookId",
                                    book,
                                    "standard",
                                    b.get("standard"),
                                    "baseCurrency",
                                    "CNY",
                                    "sourceYearId",
                                    sourceYear,
                                    "targetYearId",
                                    query.target(),
                                    "sourceYear",
                                    s.number,
                                    "targetYear",
                                    t.number,
                                    "bothYearsReadAuthorized",
                                    true);
                    var plans =
                            call.list(
                                    "SELECT id,source_year_id,target_year_id,status,version::text"
                                        + " version,digest,created_by,approved_by FROM"
                                        + " plugin_finance_close_plan WHERE tenant_id=? AND"
                                        + " book_id=? AND (source_year_id IN(?,?) OR target_year_id"
                                        + " IN(?,?)) ORDER BY id LIMIT 5001",
                                    b.get("tenant_id"),
                                    book,
                                    sourceYear,
                                    query.target(),
                                    sourceYear,
                                    query.target());
                    if (plans.size() > 5000) throw limit();
                    for (var p : plans) {
                        counter(p.get("version"));
                        if (!(p.get("digest") instanceof String x)
                                || !x.matches("[a-f0-9]{64}")
                                || p.get("status").toString().length() > 100) throw limit();
                    }
                    String auth =
                            digest(
                                    map(
                                            "source",
                                            authorizationDigest(call, principal, b),
                                            "target",
                                            authorizationDigest(call, principal, targetScope)));
                    String planDigest = digest(plans);
                    var actor = actor(call, b);
                    String context =
                            digest(
                                    map(
                                            "scope",
                                            scope,
                                            "source",
                                            s.vector,
                                            "target",
                                            t.vector,
                                            "openingSource",
                                            s.openingDigest,
                                            "openingTarget",
                                            t.openingDigest,
                                            "plans",
                                            planDigest,
                                            "authorization",
                                            auth));
                    var binding = tokenContext(scope, actor, context);
                    String observationToken = token(binding);
                    var rows = accountRows(s, t);
                    if (op.equals("summary")) {
                        var observation =
                                map(
                                        "token",
                                        observationToken,
                                        "contextDigest",
                                        context,
                                        "source",
                                        s.vector,
                                        "target",
                                        t.vector,
                                        "legacyPlansDigest",
                                        planDigest,
                                        "authorizationDigest",
                                        auth,
                                        "qualification",
                                        "READ_ONLY_NONPERSISTED_OBSERVATION_NOT_APPROVAL");
                        return map(
                                "schemaVersion",
                                FinanceCarryReadinessInput.SCHEMA,
                                "scope",
                                scope,
                                "observation",
                                observation,
                                "currentActor",
                                actor,
                                "source",
                                s.observation,
                                "target",
                                t.observation,
                                "mappingTotals",
                                mappingTotals(s, t, rows),
                                "legacyPlans",
                                planCounts(plans),
                                "checks",
                                checks(s, t, rows),
                                "overall",
                                "INCOMPLETE_NOT_EXECUTABLE",
                                "canExecuteBase",
                                false,
                                "yearCloseEnabled",
                                false,
                                "formalReportEnabled",
                                false,
                                "persistentSnapshotCreated",
                                false);
                    }
                    // Validate all fixed cursor fields first, even if observation freshness changed
                    // as well.
                    Map<String, Object> cursor =
                            query.cursor() == null ? null : untoken(query.cursor());
                    if (cursor != null)
                        validateFixedCursor(cursor, binding, query.filter(), query.limit());
                    validateToken(query.observation(), binding, false, null, 0);
                    if (cursor != null)
                        validateToken(query.cursor(), binding, true, query.filter(), query.limit());
                    var filtered =
                            rows.stream()
                                    .filter(r -> query.filter().equals("ALL") || nonzero(r))
                                    .toList();
                    int start = 0;
                    if (cursor != null) {
                        int anchor = -1;
                        for (int i = 0; i < filtered.size(); i++)
                            if (filtered.get(i).get("rowKey").equals(cursor.get("anchor"))) {
                                anchor = i;
                                break;
                            }
                        if (anchor < 0) throw FinanceException.invalid("分页锚点不属于本观察总体");
                        start = anchor + 1;
                    }
                    int end = Math.min(start + query.limit(), filtered.size());
                    var items = filtered.subList(start, end);
                    String next = null;
                    if (end < filtered.size()) {
                        var nextData = new LinkedHashMap<>(binding);
                        nextData.put("filter", query.filter());
                        nextData.put("limit", query.limit());
                        nextData.put("anchor", items.getLast().get("rowKey"));
                        next = token(nextData);
                    }
                    return map(
                            "schemaVersion",
                            FinanceCarryReadinessInput.SCHEMA,
                            "scope",
                            scope,
                            "observationToken",
                            observationToken,
                            "contextDigest",
                            context,
                            "currentActor",
                            actor,
                            "filter",
                            query.filter(),
                            "limit",
                            query.limit(),
                            "populationRows",
                            rows.size(),
                            "filteredRows",
                            filtered.size(),
                            "items",
                            items,
                            "nextCursor",
                            next,
                            "canExecuteBase",
                            false,
                            "yearCloseEnabled",
                            false);
                });
    }

    void validateFixedCursor(
            Map<String, Object> c, Map<String, Object> expected, String filter, int limit) {
        if (!c.keySet().equals(CURSOR_KEYS)) throw FinanceException.invalid("分页结构无效");
        for (String k : TOKEN_KEYS)
            if (!k.equals("context")
                    && (!(c.get(k) instanceof String)
                            || !Objects.equals(c.get(k), expected.get(k))))
                throw FinanceException.invalid("分页范围无效");
        if (!Objects.equals(c.get("filter"), filter)
                || !(c.get("limit") instanceof Integer n)
                || n != limit) throw FinanceException.invalid("分页筛选无效");
    }

    List<Map<String, Object>> accountRows(YearData s, YearData t) {
        var targets = new HashMap<String, Map<String, Object>>();
        for (var a : t.accounts) targets.put(a.get("code").toString(), a);
        var used = new HashSet<UUID>();
        var rows = new ArrayList<Map<String, Object>>();
        for (var a : s.accounts) {
            var target = targets.get(a.get("code").toString());
            if (target != null) used.add(id(target.get("id")));
            rows.add(accountRow(s, t, a, target));
        }
        for (var a : t.accounts)
            if (!used.contains(id(a.get("id")))) rows.add(accountRow(s, t, null, a));
        rows.sort(
                (a, b) -> {
                    String ac =
                            (object(
                                            a.get("sourceAccount") != null
                                                    ? a.get("sourceAccount")
                                                    : a.get("targetCandidate")))
                                    .get("code")
                                    .toString();
                    String bc =
                            (object(
                                            b.get("sourceAccount") != null
                                                    ? b.get("sourceAccount")
                                                    : b.get("targetCandidate")))
                                    .get("code")
                                    .toString();
                    int code =
                            Arrays.compareUnsigned(
                                    ac.getBytes(StandardCharsets.UTF_8),
                                    bc.getBytes(StandardCharsets.UTF_8));
                    return code != 0
                            ? code
                            : a.get("rowKey").toString().compareTo(b.get("rowKey").toString());
                });
        return rows;
    }

    Map<String, Object> accountRow(
            YearData s, YearData t, Map<String, Object> a, Map<String, Object> z) {
        UUID aid = a == null ? null : id(a.get("id")), zid = z == null ? null : id(z.get("id"));
        var differences = new ArrayList<String>();
        if (a != null && z != null) {
            if (!a.get("category").equals(z.get("category"))) differences.add("CATEGORY");
            if (!a.get("normalSide").equals(z.get("normalSide"))) differences.add("NORMAL_SIDE");
            if (!a.get("purpose").equals(z.get("purpose"))) differences.add("PURPOSE");
        }
        if (a != null && !Boolean.TRUE.equals(a.get("enabled"))) differences.add("SOURCE_DISABLED");
        if (z != null && !Boolean.TRUE.equals(z.get("enabled"))) differences.add("TARGET_DISABLED");
        String status =
                a == null
                        ? "TARGET_ONLY"
                        : z == null
                                ? "MISSING_TARGET"
                                : differences.stream().anyMatch(x -> !x.endsWith("DISABLED"))
                                        ? "SEMANTIC_CONFLICT"
                                        : !differences.isEmpty()
                                                ? "DISABLED_ACCOUNT"
                                                : "CANDIDATE_COMPATIBLE";
        var sourceEnding = s.value(aid, 3);
        var targetOpening = t.value(zid, 0);
        boolean comparable =
                status.equals("CANDIDATE_COMPATIBLE")
                        && sourceEnding.get("state").equals("KNOWN")
                        && targetOpening.get("state").equals("KNOWN");
        var difference =
                comparable
                        ? known(
                                new BigDecimal(sourceEnding.get("amount").toString())
                                        .subtract(
                                                new BigDecimal(
                                                        targetOpening.get("amount").toString())))
                        : unknown("NOT_COMPARABLE");
        return map(
                "rowKey",
                (a == null ? "T:" : "S:") + (a == null ? zid : aid),
                "sourcePresence",
                a == null ? "MISSING_ACCOUNT" : "PRESENT",
                "targetPresence",
                z == null ? "MISSING_ACCOUNT" : "PRESENT",
                "sourceAccount",
                a,
                "targetCandidate",
                z,
                "candidateStatus",
                status,
                "mappingStatus",
                "NOT_APPROVED",
                "semanticDifferences",
                differences,
                "sourceOpening",
                s.value(aid, 0),
                "sourcePostedDebit",
                s.value(aid, 1),
                "sourcePostedCredit",
                s.value(aid, 2),
                "sourceEndingBalance",
                sourceEnding,
                "targetOpeningRowExists",
                z == null ? null : t.openingAccounts.contains(zid),
                "targetOpening",
                targetOpening,
                "targetPostedDebit",
                t.value(zid, 1),
                "targetPostedCredit",
                t.value(zid, 2),
                "targetPostedNet",
                zid == null
                        ? unknown("MISSING_ACCOUNT")
                        : !t.state.equals("KNOWN")
                                ? unknown(t.state)
                                : known(t.values.get(zid)[1].subtract(t.values.get(zid)[2])),
                "targetEndingBalance",
                t.value(zid, 3),
                "candidateOpeningDifference",
                difference,
                "differenceQualification",
                difference.get("state").equals("KNOWN")
                        ? "UNAPPROVED_CANDIDATE_ONLY"
                        : "NOT_COMPARABLE",
                "isApprovedCarryAmount",
                false);
    }

    static boolean nonzero(Map<String, Object> row) {
        if (!row.get("candidateStatus").equals("CANDIDATE_COMPATIBLE")) return true;
        for (String key : MONETARY) {
            var a = object(row.get(key));
            if (!a.get("state").equals("KNOWN")
                    || new BigDecimal(a.get("amount").toString()).signum() != 0) return true;
        }
        return false;
    }

    Map<String, Object> mappingTotals(YearData s, YearData t, List<Map<String, Object>> rows) {
        var result =
                map(
                        "populationRows",
                        rows.size(),
                        "sourceAccounts",
                        s.accounts.size(),
                        "targetAccounts",
                        t.accounts.size(),
                        "compatibleCandidates",
                        0,
                        "missingTarget",
                        0,
                        "targetOnly",
                        0,
                        "semanticConflicts",
                        0,
                        "disabledCandidates",
                        0,
                        "unknownMonetaryRows",
                        0,
                        "approvalQualification",
                        "NO_APPROVED_MAPPING_MODEL",
                        "dependsOnPageFilter",
                        false);
        for (var r : rows) {
            String key =
                    switch (r.get("candidateStatus").toString()) {
                        case "CANDIDATE_COMPATIBLE" -> "compatibleCandidates";
                        case "MISSING_TARGET" -> "missingTarget";
                        case "TARGET_ONLY" -> "targetOnly";
                        case "SEMANTIC_CONFLICT" -> "semanticConflicts";
                        default -> "disabledCandidates";
                    };
            result.put(key, ((Integer) result.get(key)) + 1);
            if (MONETARY.stream().anyMatch(k -> !object(r.get(k)).get("state").equals("KNOWN")))
                result.put(
                        "unknownMonetaryRows", ((Integer) result.get("unknownMonetaryRows")) + 1);
        }
        return result;
    }

    Map<String, Object> planCounts(List<Map<String, Object>> plans) {
        int preview = 0, approved = 0, executed = 0;
        for (var p : plans)
            switch (p.get("status").toString()) {
                case "PREVIEW" -> preview++;
                case "APPROVED" -> approved++;
                case "EXECUTED" -> executed++;
                default -> {}
            }
        return map(
                "total",
                plans.size(),
                "preview",
                preview,
                "approved",
                approved,
                "executed",
                executed,
                "other",
                plans.size() - preview - approved - executed,
                "qualification",
                "LEGACY_SCALAR_OBSERVATION_NOT_NEW_APPROVED_MAPPING_OR_BASE");
    }

    Map<String, Object> check(
            String id,
            String group,
            String status,
            String message,
            Object count,
            Map<String, Object> amount) {
        return map(
                "id",
                id,
                "group",
                group,
                "status",
                status,
                "blockingForBase",
                true,
                "message",
                message,
                "amountKnowledge",
                amount == null
                        ? "NOT_MEASURED"
                        : amount.get("state").equals("KNOWN") ? "KNOWN" : "UNSUPPORTED_DATA",
                "amount",
                amount == null ? null : amount.get("amount"),
                "observedCount",
                count == null ? null : count.toString());
    }

    static long pending(Map<String, Object> counts) {
        return Long.parseLong(counts.get("draft").toString())
                + Long.parseLong(counts.get("submitted").toString())
                + Long.parseLong(counts.get("approved").toString());
    }

    List<Map<String, Object>> checks(YearData s, YearData t, List<Map<String, Object>> rows) {
        var c = new ArrayList<Map<String, Object>>();
        for (var d : List.of(s, t)) {
            String prefix = d == s ? "SOURCE_" : "TARGET_";
            c.add(
                    check(
                            prefix + "DATA",
                            "LEDGER_OBSERVATION",
                            d.state.equals("KNOWN") ? "PASS" : "UNSUPPORTED",
                            "年度原始账务金额资格：" + d.observation.get("dataQualification"),
                            d.accounts.size(),
                            null));
            c.add(
                    check(
                            prefix + "PERSON_MODE",
                            "WORKFLOW_OBSERVATION",
                            d.vector.get("personMode").equals("JOURNAL_PERSON_SOD")
                                    ? "PASS"
                                    : "UNSUPPORTED",
                            "只观察当前真实年度人员模式；不启用年度承接",
                            null,
                            null));
        }
        long open = s.periods.stream().filter(p -> !p.get("status").equals("CLOSED")).count();
        c.add(
                check(
                        "SOURCE_PERIODS_CLOSED",
                        "WORKFLOW_OBSERVATION",
                        open == 0 ? "PASS" : "FAIL",
                        "源年度尚未关闭期间数",
                        open,
                        null));
        c.add(
                check(
                        "SOURCE_PENDING_JOURNALS",
                        "WORKFLOW_OBSERVATION",
                        pending(s.journals) == 0 ? "PASS" : "FAIL",
                        "源年度未记账凭证数（包含系统结转）",
                        pending(s.journals),
                        null));
        c.add(
                check(
                        "SOURCE_PENDING_CLOSING",
                        "WORKFLOW_OBSERVATION",
                        pending(s.closings) == 0 ? "PASS" : "FAIL",
                        "源年度未完成结转Run数",
                        pending(s.closings),
                        null));
        c.add(
                check(
                        "SOURCE_TRIAL_BALANCED",
                        "LEDGER_OBSERVATION",
                        Boolean.TRUE.equals(s.observation.get("trialBalanced"))
                                ? "PASS"
                                : s.state.equals("KNOWN") ? "FAIL" : "UNSUPPORTED",
                        "原始全年账务试算观察，不代表子账或报表已核对",
                        null,
                        null));
        Object pl = s.observation.get("nonzeroProfitLossAccountCount");
        c.add(
                check(
                        "SOURCE_PROFIT_LOSS_ZERO",
                        "LEDGER_OBSERVATION",
                        pl == null ? "UNSUPPORTED" : ((Integer) pl) == 0 ? "PASS" : "FAIL",
                        "实际损益科目非零余额数",
                        pl,
                        null));
        var profit = object(s.observation.get("currentYearProfitResidual"));
        boolean cyZero =
                profit.get("state").equals("KNOWN")
                        && new BigDecimal(profit.get("amount").toString()).signum() == 0;
        boolean exactlyOne = ((Integer) s.observation.get("currentYearProfitAccountCount")) == 1;
        c.add(
                check(
                        "SOURCE_CURRENT_PROFIT_ZERO",
                        "LEDGER_OBSERVATION",
                        !profit.get("state").equals("KNOWN")
                                ? "UNSUPPORTED"
                                : cyZero && exactlyOne ? "PASS" : "FAIL",
                        "本年利润目的须唯一且实际余额为零",
                        s.observation.get("currentYearProfitAccountCount"),
                        profit));
        c.add(
                check(
                        "TARGET_OPENING_FACTS",
                        "LEDGER_OBSERVATION",
                        t.state.equals("KNOWN") ? "PASS" : "UNSUPPORTED",
                        "仅观察产品既有期初余额行；无行的已存在科目为真实零，不代表正式承接",
                        t.observation.get("openingRowCount"),
                        null));
        c.add(
                check(
                        "TARGET_POSTED_YEAR_MOVEMENTS",
                        "LEDGER_OBSERVATION",
                        t.state.equals("KNOWN") ? "PASS" : "UNSUPPORTED",
                        "目标全年已记账总发生额独立显示，不是期初、不受当前日期截断",
                        t.journals.get("posted"),
                        null));
        long mismatch =
                rows.stream()
                        .filter(r -> !r.get("candidateStatus").equals("CANDIDATE_COMPATIBLE"))
                        .count();
        c.add(
                check(
                        "CANDIDATE_ACCOUNT_MAPPING",
                        "MAPPING_OBSERVATION",
                        mismatch == 0 ? "PASS" : "FAIL",
                        "代码仅产生待审批候选；缺失、目标多余、停用或语义冲突行数",
                        mismatch,
                        null));
        for (String id :
                List.of(
                        "APPROVED_ACCOUNT_MAPPING",
                        "FORMAL_OPENING_BATCH",
                        "YTD_BASELINE_MODEL",
                        "SUBLEDGER_OPEN_ITEMS",
                        "BANK_RECONCILIATION",
                        "EXTERNAL_CASH_UNIVERSE",
                        "ASSET_CONTINUITY",
                        "INVENTORY_QUANTITY",
                        "TAX_CONTINUITY",
                        "FORMAL_REPORT_APPROVAL",
                        "ARCHIVE"))
            c.add(
                    map(
                            "id",
                            id,
                            "group",
                            "MISSING_CAPABILITY",
                            "status",
                            "MISSING",
                            "blockingForBase",
                            true,
                            "message",
                            "当前尚无该完整承接资格模型；零余额不能证明不适用：" + id,
                            "amountKnowledge",
                            "MISSING_MODEL",
                            "amount",
                            null,
                            "observedCount",
                            null));
        return c;
    }
}
