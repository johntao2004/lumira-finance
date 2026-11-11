package com.lumira.finance;

import static com.lumira.finance.assets.AssetModel.*;

import com.lumira.finance.assets.*;
import com.lumira.platform.common.AuthorizationContextProjectionPort;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

/** Runtime adapter for AS01/AS02 asset subledger candidate. */
@Service
public final class FinanceAssets extends FinanceProfitClosingAccess {
    private static final Set<String> BAD_INPUT_CODES =
            Set.of(
                    "REQUEST_KEY_REQUIRED",
                    "REASON_REQUIRED",
                    "ACQUISITION_INPUT_REQUIRED",
                    "SPLIT_SOURCE_TOTAL_MISMATCH",
                    "INVALID_MONEY",
                    "NEGATIVE_MONEY",
                    "UNKNOWN_ACTION",
                    "CARD_INVALID",
                    "POLICY_INVALID",
                    "ACCOUNTS_INVALID",
                    "UNBALANCED_LINES");
    private final Optional<DataSource> dataSource;
    private final Optional<AssetPorts.Evidence> evidencePort;
    private final Optional<AssetPorts.Posting> postingPort;
    private final AssetRepository repository;
    private final ThreadLocal<HttpServletRequest> currentRequest = new ThreadLocal<>();

    public FinanceAssets(
            FinanceStore db,
            FinanceService finance,
            FinanceJournalPersonGate gate,
            PlatformTransactionManager transactions,
            AuthorizationContextProjectionPort authorization,
            Optional<DataSource> dataSource,
            Optional<AssetPorts.Evidence> evidencePort,
            Optional<AssetPorts.Posting> postingPort) {
        super(db, finance, gate, transactions, authorization);
        this.dataSource = dataSource;
        this.evidencePort = evidencePort;
        this.postingPort = postingPort;
        this.repository = new AssetRepository(gate.json);
    }

    private AssetService service() {
        DataSource ds =
                dataSource.orElseThrow(
                        () -> error("ASSET_DATASOURCE_UNAVAILABLE", "资产子账数据源尚未就绪"));
        AssetPorts.Guard guard =
                new AssetPorts.Guard() {
                    @Override
                    public Context enter(
                            Connection c, String principal, Scope s, String action, UUID sourcePlan)
                            throws Exception {
                        String perm =
                                switch (action) {
                                    case "read", "reconcile" -> "read";
                                    case "approve" -> "approve";
                                    case "post" -> "post";
                                    default -> "create";
                                };
                        var b = finance.originalScope(principal, s.book(), s.year(), perm);
                        if (!allowed(b, perm) || !s.tenant().equals(id(b.get("tenant_id"))))
                            throw error("SCOPE_DENIED", "当前岗位不允许该资产动作");
                        boolean write = !Set.of("read", "reconcile").contains(action);
                        if (write) {
                            try (var q =
                                    c.prepareStatement(
                                            "SELECT id FROM plugin_finance_book WHERE tenant_id=?"
                                                    + " AND id=? FOR UPDATE")) {
                                q.setObject(1, s.tenant());
                                q.setObject(2, s.book());
                                try (var r = q.executeQuery()) {
                                    if (!r.next()) throw error("SCOPE_DENIED", "账套范围不可访问");
                                }
                            }
                            try (var q =
                                    c.prepareStatement(
                                            "SELECT status,precision_profile,precision_review_required"
                                                + " FROM plugin_finance_year WHERE tenant_id=? AND"
                                                + " book_id=? AND id=? FOR UPDATE")) {
                                q.setObject(1, s.tenant());
                                q.setObject(2, s.book());
                                q.setObject(3, s.year());
                                try (var r = q.executeQuery()) {
                                    if (!r.next()) throw error("SCOPE_DENIED", "会计年度不可访问");
                                    if (!"OPEN".equals(r.getString("status")))
                                        throw error("PERIOD_CLOSED", "会计年度已关闭");
                                    if (!"V4".equals(r.getString("precision_profile"))
                                            || r.getBoolean("precision_review_required"))
                                        throw error(
                                                "LEGACY_PRECISION_REVIEW_REQUIRED",
                                                "该年度尚未完成V4精度确认");
                                }
                            }
                            try (var q =
                                    c.prepareStatement(
                                            "SELECT status FROM plugin_finance_period WHERE"
                                                + " tenant_id=? AND book_id=? AND year_id=? AND"
                                                + " id=? FOR UPDATE")) {
                                q.setObject(1, s.tenant());
                                q.setObject(2, s.book());
                                q.setObject(3, s.year());
                                q.setObject(4, s.period());
                                try (var r = q.executeQuery()) {
                                    if (!r.next() || !"OPEN".equals(r.getString("status")))
                                        throw error("PERIOD_CLOSED", "资产会计期间已关闭或不可访问");
                                }
                            }
                            if (!gate.enabled(b, s.year()))
                                throw error("JOURNAL_PERSON_MODE_REQUIRED", "资产子账写入要求启用自然人审签协议");
                            var who =
                                    gate.current(
                                            id(b.get("actor_id")),
                                            s.tenant(),
                                            Set.of("approve", "post").contains(action),
                                            currentRequest.get());
                            return new Context(
                                    s,
                                    new Actor(id(who.get("accountId")), id(who.get("personId"))),
                                    "finance-web");
                        }
                        try (var q =
                                c.prepareStatement(
                                        "SELECT id FROM plugin_finance_book WHERE tenant_id=? AND"
                                                + " id=?")) {
                            q.setObject(1, s.tenant());
                            q.setObject(2, s.book());
                            try (var r = q.executeQuery()) {
                                if (!r.next()) throw error("SCOPE_DENIED", "账套范围不可访问");
                            }
                        }
                        UUID actorId = id(b.get("actor_id"));
                        return new Context(s, new Actor(actorId, actorId), "finance-web");
                    }

                    @Override
                    public void validate(Connection c, Context ctx, Plan plan, Actor approver)
                            throws Exception {
                        if (!plan.scope().equals(ctx.scope()))
                            throw error("SCOPE_DENIED", "资产计划范围与当前上下文不一致");
                        String standard;
                        try (var q =
                                c.prepareStatement(
                                        "SELECT standard FROM plugin_finance_book WHERE tenant_id=?"
                                                + " AND id=?")) {
                            q.setObject(1, ctx.scope().tenant());
                            q.setObject(2, ctx.scope().book());
                            try (var r = q.executeQuery()) {
                                if (!r.next()) throw error("SCOPE_DENIED", "账套不存在");
                                standard = r.getString(1);
                            }
                        }
                        Set<UUID> requiredAccounts = new LinkedHashSet<>();
                        for (Change ch : plan.changes()) {
                            if (!standard.equals(ch.after().policy().framework()))
                                throw error("ASSET_FRAMEWORK_MISMATCH", "资产会计准则与账套准则不一致");
                            requiredAccounts.add(ch.after().accounts().cost());
                            requiredAccounts.add(ch.after().accounts().accumulated());
                            requiredAccounts.add(ch.after().accounts().expense());
                            if ("ACQUIRE".equals(plan.kind())) {
                                verifyOriginalOnConnection(
                                        c, ctx.scope(), ch.after().policy().assessmentId());
                            }
                        }
                        for (Line l : plan.lines()) requiredAccounts.add(l.account());
                        for (UUID accId : requiredAccounts) {
                            try (var q =
                                    c.prepareStatement(
                                            "SELECT enabled FROM plugin_finance_account WHERE"
                                                + " tenant_id=? AND book_id=? AND year_id=? AND"
                                                + " id=?")) {
                                q.setObject(1, ctx.scope().tenant());
                                q.setObject(2, ctx.scope().book());
                                q.setObject(3, ctx.scope().year());
                                q.setObject(4, accId);
                                try (var r = q.executeQuery()) {
                                    if (!r.next() || !r.getBoolean(1))
                                        throw error("ASSET_ACCOUNT_UNAVAILABLE", "资产映射科目不存在或已停用");
                                }
                            }
                        }
                        if (plan.sourceId() != null && !"ACQUIRE".equals(plan.kind())) {
                            verifyOriginalOnConnection(c, ctx.scope(), plan.sourceId());
                        }
                    }
                };
        AssetPorts.Evidence evidence =
                evidencePort.orElseGet(
                        () ->
                                new AssetPorts.Evidence() {
                                    @Override
                                    public void reserve(
                                            Connection c,
                                            Context ctx,
                                            UUID sourceId,
                                            UUID plan,
                                            BigDecimal amount) {
                                        throw error(
                                                "ASSET_EVIDENCE_PRODUCER_UNAVAILABLE",
                                                "资产购置来源证据配额契约尚未接入；未产生会计效果");
                                    }

                                    @Override
                                    public void consume(
                                            Connection c,
                                            Context ctx,
                                            UUID sourceId,
                                            UUID plan,
                                            BigDecimal amount) {
                                        throw error(
                                                "ASSET_EVIDENCE_PRODUCER_UNAVAILABLE",
                                                "资产购置来源证据消费契约尚未接入；未产生会计效果");
                                    }

                                    @Override
                                    public void release(
                                            Connection c, Context ctx, UUID sourceId, UUID plan) {
                                        throw error(
                                                "ASSET_EVIDENCE_PRODUCER_UNAVAILABLE",
                                                "资产购置来源证据释放契约尚未接入");
                                    }

                                    @Override
                                    public void reverse(
                                            Connection c,
                                            Context ctx,
                                            UUID originalPlan,
                                            UUID reversalPlan) {
                                        throw error(
                                                "ASSET_EVIDENCE_PRODUCER_UNAVAILABLE",
                                                "资产购置来源证据冲销契约尚未接入");
                                    }
                                });
        AssetPorts.Posting posting =
                postingPort.orElseGet(
                        () ->
                                (c, ctx, approved) -> {
                                    throw error(
                                            "ASSET_POSTING_PRODUCER_UNAVAILABLE",
                                            "原生资产凭证记账契约尚未接入；未产生会计效果");
                                });
        AssetPorts.Ledger ledger =
                (c, ctx, mappedAccounts) -> {
                    if (mappedAccounts.isEmpty()) return List.of();
                    long seq = 0L;
                    try (var q =
                            c.prepareStatement(
                                    "SELECT posting_seq FROM plugin_finance_year_guard WHERE"
                                            + " tenant_id=? AND book_id=? AND year_id=?")) {
                        q.setObject(1, ctx.scope().tenant());
                        q.setObject(2, ctx.scope().book());
                        q.setObject(3, ctx.scope().year());
                        try (var r = q.executeQuery()) {
                            if (r.next()) seq = r.getLong(1);
                        }
                    }
                    String wm = "postingSeq:" + seq + ":asOf:" + ctx.scope().date();
                    List<AssetPorts.LedgerBalance> out = new ArrayList<>();
                    for (UUID account : mappedAccounts) {
                        BigDecimal open = BigDecimal.ZERO;
                        try (var q =
                                c.prepareStatement(
                                        "SELECT COALESCE(SUM(balance),0) FROM"
                                            + " plugin_finance_opening WHERE tenant_id=? AND"
                                            + " book_id=? AND year_id=? AND account_id=?")) {
                            q.setObject(1, ctx.scope().tenant());
                            q.setObject(2, ctx.scope().book());
                            q.setObject(3, ctx.scope().year());
                            q.setObject(4, account);
                            try (var r = q.executeQuery()) {
                                if (r.next() && r.getBigDecimal(1) != null)
                                    open = r.getBigDecimal(1);
                            }
                        }
                        BigDecimal move = BigDecimal.ZERO;
                        try (var q =
                                c.prepareStatement(
                                        "SELECT COALESCE(SUM(l.debit-l.credit),0) FROM"
                                            + " plugin_finance_line l JOIN plugin_finance_journal j"
                                            + " ON(j.tenant_id,j.book_id,j.year_id,j.id)=(l.tenant_id,l.book_id,l.year_id,l.journal_id)"
                                            + " WHERE l.tenant_id=? AND l.book_id=? AND l.year_id=?"
                                            + " AND l.account_id=? AND j.status='POSTED' AND"
                                            + " j.date<=?")) {
                            q.setObject(1, ctx.scope().tenant());
                            q.setObject(2, ctx.scope().book());
                            q.setObject(3, ctx.scope().year());
                            q.setObject(4, account);
                            q.setObject(5, ctx.scope().date());
                            try (var r = q.executeQuery()) {
                                if (r.next() && r.getBigDecimal(1) != null)
                                    move = r.getBigDecimal(1);
                            }
                        }
                        out.add(
                                new AssetPorts.LedgerBalance(
                                        account,
                                        open.add(move).setScale(8, RoundingMode.UNNECESSARY),
                                        wm));
                    }
                    return List.copyOf(out);
                };
        return new AssetService(ds, repository, guard, evidence, posting, ledger);
    }

    public Scope resolveScope(String principal, UUID book, UUID year, LocalDate accountingDate) {
        if (accountingDate == null) throw FinanceException.invalid("必须提供明确的资产会计日期");
        var b = finance.originalScope(principal, book, year, "read");
        UUID tenant = id(b.get("tenant_id"));
        var yr =
                db.one(
                        "SELECT year FROM plugin_finance_year WHERE tenant_id=? AND book_id=? AND"
                                + " id=?",
                        tenant,
                        book,
                        year);
        if (yr == null || ((Number) yr.get("year")).intValue() != accountingDate.getYear())
            throw FinanceException.invalid("资产会计日期不在所选会计年度内");
        var pr =
                db.one(
                        "SELECT id FROM plugin_finance_period WHERE tenant_id=? AND book_id=? AND"
                                + " year_id=? AND month=?",
                        tenant,
                        book,
                        year,
                        accountingDate.getMonthValue());
        if (pr == null) throw FinanceException.invalid("资产会计日期对应的会计期间不存在");
        return new Scope(tenant, book, year, id(pr.get("id")), accountingDate);
    }

    private RuntimeException translate(Exception e) {
        if (e instanceof FinanceException f) return f;
        if (e instanceof IllegalArgumentException iae) {
            String code = iae.getMessage() == null ? "INVALID_INPUT" : iae.getMessage();
            if ("DENIED".equals(code)
                    || "PLAN_YEAR_SCOPE_DENIED".equals(code)
                    || "TRUSTED_CONTEXT_REQUIRED".equals(code)
                    || "ORIGINAL_MAKER_REQUIRED".equals(code))
                return error("SCOPE_DENIED", "资产范围或原制单人校验未通过（" + code + "）");
            if (code.contains("SELF_APPROVAL"))
                return error("SELF_APPROVAL_FORBIDDEN", "同一自然人不得自审资产计划");
            if (BAD_INPUT_CODES.contains(code))
                return FinanceException.invalid("资产请求参数无效（" + code + "）");
            return error("ASSET_" + code, "资产状态、版本或会计规则校验未通过（" + code + "）");
        }
        return error("ASSET_OPERATION_REJECTED", "资产操作未通过校验");
    }

    public List<Card> cards(
            String principal, UUID book, UUID year, LocalDate date, UUID after, int limit) {
        if (limit < 1 || limit > 100) throw FinanceException.invalid("分页大小无效");
        Scope s = resolveScope(principal, book, year, date);
        try {
            return service().cards(principal, s, after, limit);
        } catch (Exception e) {
            throw translate(e);
        }
    }

    public StoredPlan detail(String principal, UUID book, UUID year, LocalDate date, UUID id) {
        Scope s = resolveScope(principal, book, year, date);
        try {
            return service().detail(principal, s, id);
        } catch (Exception e) {
            throw translate(e);
        }
    }

    public List<AssetService.Difference> reconcile(
            String principal, UUID book, UUID year, LocalDate date) {
        Scope s = resolveScope(principal, book, year, date);
        try {
            return service().reconcile(principal, s);
        } catch (Exception e) {
            throw translate(e);
        }
    }

    public Receipt command(
            String principal,
            UUID book,
            UUID year,
            LocalDate date,
            String action,
            String key,
            Map<String, Object> payload,
            HttpServletRequest request) {
        Scope s = resolveScope(principal, book, year, date);
        currentRequest.set(request);
        try {
            return switch (action) {
                case "acquire" ->
                        service()
                                .acquire(
                                        principal,
                                        s,
                                        key,
                                        FinanceStrictJson.convert(
                                                payload, AssetService.Acquire.class, "资产取得负载"));
                case "depreciate" ->
                        service()
                                .depreciate(
                                        principal,
                                        s,
                                        key,
                                        FinanceStrictJson.convert(
                                                payload,
                                                AssetService.Depreciate.class,
                                                "资产折旧摊销负载"));
                case "estimate" ->
                        service()
                                .estimate(
                                        principal,
                                        s,
                                        key,
                                        FinanceStrictJson.convert(
                                                payload,
                                                AssetService.Estimate.class,
                                                "资产估计变更负载"));
                case "dispose" ->
                        service()
                                .dispose(
                                        principal,
                                        s,
                                        key,
                                        FinanceStrictJson.convert(
                                                payload, AssetService.Dispose.class, "资产处置负载"));
                case "reverse" ->
                        service()
                                .reverse(
                                        principal,
                                        s,
                                        key,
                                        FinanceStrictJson.convert(
                                                payload, AssetService.Reverse.class, "资产冲销负载"));
                case "correct-depreciation" ->
                        service()
                                .correctDepreciation(
                                        principal,
                                        s,
                                        key,
                                        FinanceStrictJson.convert(
                                                payload,
                                                AssetService.CorrectDepreciation.class,
                                                "资产折旧差错更正负载"));
                case "approve", "post", "cancel" ->
                        service()
                                .transition(
                                        principal,
                                        s,
                                        action,
                                        key,
                                        FinanceStrictJson.convert(
                                                payload,
                                                AssetService.Transition.class,
                                                "资产计划流转负载"));
                default -> throw FinanceException.invalid("不支持的资产动作");
            };
        } catch (Exception e) {
            throw translate(e);
        } finally {
            currentRequest.remove();
        }
    }

    private static void verifyOriginalOnConnection(Connection c, Scope scope, UUID originalId)
            throws Exception {
        if (originalId == null)
            throw error("ASSET_ORIGINAL_INVALID", "资产评估或处置批准原件编号不能为空");
        try (var q =
                c.prepareStatement(
                        "SELECT 1 FROM plugin_finance_original WHERE tenant_id=? AND book_id=?"
                                + " AND year_id=? AND id=? AND sha256=encode(sha256(content),'hex')")) {
            q.setObject(1, scope.tenant());
            q.setObject(2, scope.book());
            q.setObject(3, scope.year());
            q.setObject(4, originalId);
            try (var r = q.executeQuery()) {
                if (!r.next())
                    throw error("ASSET_ORIGINAL_INVALID", "资产评估或处置批准原件不存在或摘要不符");
            }
        }
    }
}
