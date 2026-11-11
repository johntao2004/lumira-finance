package com.lumira.finance;

import com.lumira.platform.common.AuthorizationContextProjectionPort;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Typed GL producer only; does not close periods/years or execute annual opening transfers. */
@Service
public class FinanceProfitClosing extends FinanceProfitClosingAccess {
    @Autowired
    public FinanceProfitClosing(
            FinanceStore db,
            FinanceService finance,
            FinanceJournalPersonGate gate,
            PlatformTransactionManager transactions,
            AuthorizationContextProjectionPort authorization) {
        super(db, finance, gate, transactions, authorization);
    }

    Map<String, Object> row(Call call, Map<String, Object> b, UUID year, UUID run) {
        return call.one(
                "SELECT * FROM plugin_finance_profit_closing_run WHERE tenant_id=? AND book_id=?"
                    + " AND year_id=? AND id=?",
                b.get("tenant_id"),
                b.get("id"),
                year,
                run);
    }

    Map<String, Object> source(
            Call call,
            Map<String, Object> b,
            UUID year,
            String kind,
            Object cutoff,
            Object reversal) {
        return json(
                call.one(
                                "SELECT"
                                    + " plugin_finance_profit_closing_preview(?,?,?,?,?::date,?)::text"
                                    + " value",
                                b.get("tenant_id"),
                                b.get("id"),
                                year,
                                kind,
                                cutoff.toString(),
                                reversal == null ? null : id(reversal))
                        .get("value"));
    }

    Map<String, Object> preview(
            Call call, Map<String, Object> b, UUID year, HttpServletRequest request) {
        var q = query(request, Set.of("producerKind", "cutoff", "reversesRunId"));
        if (!q.containsKey("producerKind") || !q.containsKey("cutoff"))
            throw FinanceException.invalid("结转预览须指定种类与截止日");
        String kind = FinanceProfitClosingInput.kind(q.get("producerKind"));
        var date = FinanceProfitClosingInput.cutoff(q.get("cutoff"));
        if (kind.equals("YEAR_END") && date.getMonthValue() != 12)
            throw FinanceException.invalid("本年利润转存仅支持12月31日");
        UUID reverse =
                q.containsKey("reversesRunId")
                        ? FinanceProfitClosingInput.uuid(q.get("reversesRunId"))
                        : null;
        if (kind.equals("TRANSFER_REVERSAL") != (reverse != null))
            throw FinanceException.invalid("完整反向须且仅须原Run");
        var value = source(call, b, year, kind, date, reverse);
        var who = actor(call, b);
        return map(
                "schemaVersion",
                FinanceProfitClosingInput.SCHEMA,
                "tenantId",
                b.get("tenant_id"),
                "bookId",
                b.get("id"),
                "yearId",
                year,
                "standard",
                b.get("standard"),
                "producerKind",
                kind,
                "cutoff",
                date.toString(),
                "reversesRunId",
                reverse,
                "outcome",
                value.get("outcome"),
                "source",
                value.get("source"),
                "generatedLines",
                value.get("generatedLines"),
                "generatedDigest",
                value.get("generatedDigest"),
                "currentActor",
                who,
                "blockers",
                value.get("blockers"),
                "allowedActions",
                "PRODUCIBLE".equals(value.get("outcome"))
                                && "AVAILABLE".equals(who.get("identityAvailability"))
                                && allowed(b, "create")
                        ? List.of("create-run")
                        : List.of(),
                "doesNotClosePeriodOrYear",
                true);
    }

    Map<String, Object> producer(Call call, Map<String, Object> b, UUID year, UUID journal) {
        var h =
                call.one(
                        "SELECT id,type FROM plugin_finance_journal WHERE tenant_id=? AND book_id=?"
                            + " AND year_id=? AND id=?",
                        b.get("tenant_id"),
                        b.get("id"),
                        year,
                        journal);
        var runs =
                call.list(
                        "SELECT id,producer_kind FROM plugin_finance_profit_closing_run WHERE"
                            + " tenant_id=? AND book_id=? AND year_id=? AND journal_id=?",
                        b.get("tenant_id"),
                        b.get("id"),
                        year,
                        journal);
        boolean found = !runs.isEmpty(), normal = "NORMAL".equals(h.get("type"));
        return map(
                "journalId",
                journal,
                "rawType",
                h.get("type"),
                "resolution",
                found ? "FOUND" : normal ? "NONE" : "UNSUPPORTED",
                "runId",
                found ? runs.getFirst().get("id") : null,
                "producerKind",
                found ? runs.getFirst().get("producer_kind") : null,
                "reason",
                found || normal ? null : "此历史或旧模式凭证没有本协议生产者证明，不得推定为新人员结转Run");
    }

    String currentDigest(
            Call call, Map<String, Object> b, UUID year, Object cutoff, Map<String, String> cache) {
        String date = cutoff.toString();
        if (!cache.containsKey(date)) {
            Object digest =
                    call.one(
                                    "SELECT"
                                        + " plugin_finance_profit_closing_source_digest(?,?,?,?::date)"
                                        + " digest",
                                    b.get("tenant_id"),
                                    b.get("id"),
                                    year,
                                    date)
                            .get("digest");
            cache.put(date, digest == null ? null : digest.toString());
        }
        return cache.get(date);
    }

    Map<String, Object> proposalFrame(Call call, Map<String, Object> b, UUID year) {
        String key = b.get("id") + ":" + year;
        if (!call.proposalFrames.containsKey(key))
            call.proposalFrames.put(
                    key,
                    call.one(
                            "SELECT EXISTS(SELECT 1 FROM plugin_finance_profit_closing_run r"
                                + " WHERE(r.tenant_id,r.book_id,r.year_id)=(?::uuid,?::uuid,?::uuid)"
                                + " AND r.status IN('DRAFT','SUBMITTED','APPROVED')) pending,"
                                + " (SELECT r.id FROM plugin_finance_profit_closing_run r JOIN"
                                + " plugin_finance_profit_closing_post p"
                                + " ON(p.tenant_id,p.book_id,p.year_id,p.run_id)=(r.tenant_id,r.book_id,r.year_id,r.id)"
                                + " WHERE(r.tenant_id,r.book_id,r.year_id)=(?::uuid,?::uuid,?::uuid)"
                                + " AND r.producer_kind IN('PROFIT_LOSS','YEAR_END') AND NOT"
                                + " EXISTS(SELECT 1 FROM"
                                + " plugin_finance_profit_closing_reversal_effect e"
                                + " WHERE(e.tenant_id,e.book_id,e.year_id,e.original_run_id)=(r.tenant_id,r.book_id,r.year_id,r.id))"
                                + " ORDER BY p.posting_seq DESC LIMIT 1) latest_effective",
                            b.get("tenant_id"),
                            b.get("id"),
                            year,
                            b.get("tenant_id"),
                            b.get("id"),
                            year));
        return call.proposalFrames.get(key);
    }

    Map<String, Object> summary(
            Call call,
            Map<String, Object> b,
            UUID year,
            Map<String, Object> run,
            Map<String, String> sourceDigests) {
        var h =
                call.one(
                        "SELECT status,version FROM plugin_finance_journal WHERE tenant_id=? AND"
                            + " book_id=? AND year_id=? AND id=?",
                        b.get("tenant_id"),
                        b.get("id"),
                        year,
                        run.get("journal_id"));
        String digest = currentDigest(call, b, year, run.get("cutoff"), sourceDigests);
        boolean fresh = digest != null && Objects.equals(digest, run.get("source_digest"));
        var reversals =
                call.list(
                        "SELECT reversal_run_id FROM plugin_finance_profit_closing_reversal_effect"
                            + " WHERE tenant_id=? AND book_id=? AND year_id=? AND"
                            + " original_run_id=?",
                        b.get("tenant_id"),
                        b.get("id"),
                        year,
                        run.get("id"));
        Object reversal = reversals.isEmpty() ? null : reversals.getFirst().get("reversal_run_id");
        var who = actor(call, b);
        var maker = json(run.get("prepared_by"));
        var editor = json(run.get("last_editor"));
        boolean active = "AVAILABLE".equals(who.get("identityAvailability"));
        boolean own = b.get("actor_id").toString().equals(maker.get("accountId"));
        boolean independent = !samePerson(who, maker) && !samePerson(who, editor);
        String status = run.get("status").toString();
        var actions = new ArrayList<String>();
        if (active) {
            if (own && allowed(b, "create")) {
                if (status.equals("DRAFT")) {
                    actions.add("cancel");
                    if (fresh) actions.add("submit");
                }
                if (status.equals("SUBMITTED")) actions.add("withdraw");
            }
            if (independent && allowed(b, "approve")) {
                if (status.equals("DRAFT")) actions.add("admin-terminate");
                if (Set.of("SUBMITTED", "APPROVED").contains(status)) actions.add("reject");
                if (status.equals("SUBMITTED") && fresh) actions.add("approve");
            }
            if (status.equals("APPROVED") && fresh && allowed(b, "post")) actions.add("post");
            if (status.equals("POSTED")
                    && !run.get("producer_kind").equals("TRANSFER_REVERSAL")
                    && digest != null
                    && allowed(b, "create")) {
                var frame = proposalFrame(call, b, year);
                if (!Boolean.TRUE.equals(frame.get("pending"))) {
                    actions.add("propose-residual");
                    if (reversal == null
                            && Objects.equals(frame.get("latest_effective"), run.get("id")))
                        actions.add("propose-full-reversal");
                }
            }
        }
        return map(
                "id",
                run.get("id"),
                "journalId",
                run.get("journal_id"),
                "producerKind",
                run.get("producer_kind"),
                "cutoff",
                run.get("cutoff"),
                "status",
                status,
                "journalStatus",
                h.get("status"),
                "version",
                count(run.get("version")),
                "journalVersion",
                count(h.get("version")),
                "contentVersion",
                count(run.get("content_version")),
                "contentDigest",
                run.get("content_digest"),
                "sourceDigest",
                run.get("source_digest"),
                "sourceFresh",
                fresh,
                "economicEffect",
                status.equals("POSTED")
                        ? reversal == null ? "ACTIVE" : "FULLY_REVERSED"
                        : "NOT_POSTED",
                "preparedBy",
                maker,
                "lastEditor",
                editor,
                "predecessorPostedRunId",
                run.get("predecessor_posted_run_id"),
                "supersedesCancelledAttemptId",
                run.get("supersedes_cancelled_attempt_id"),
                "reversesRunId",
                run.get("reverses_run_id"),
                "postedReversalRunId",
                reversal,
                "createdAt",
                run.get("created_at"),
                "allowedActions",
                actions,
                "blockers",
                fresh
                        ? List.of()
                        : List.of(
                                map(
                                        "code",
                                        "PROFIT_CLOSING_SOURCE_STALE",
                                        "message",
                                        "当前来源或资格与冻结依据不同；历史已记账证明仍保留，新生效须重新预览")));
    }

    Map<String, Object> detail(Call call, Map<String, Object> b, UUID year, UUID target) {
        var run = row(call, b, year, target);
        var result = summary(call, b, year, run, new HashMap<>());
        var approvals =
                call.list(
                        "SELECT * FROM plugin_finance_profit_closing_approval WHERE tenant_id=? AND"
                            + " book_id=? AND year_id=? AND run_id=? ORDER BY"
                            + " approved_journal_version DESC LIMIT 1",
                        b.get("tenant_id"),
                        b.get("id"),
                        year,
                        target);
        Map<String, Object> approval = null;
        if (!approvals.isEmpty()) {
            var a = approvals.getFirst();
            String eligibility =
                    run.get("status").equals("POSTED")
                            ? "CONSUMED"
                            : !Objects.equals(a.get("id"), run.get("current_approval_id"))
                                            || !run.get("status").equals("APPROVED")
                                    ? "SUPERSEDED"
                                    : !((Timestamp) a.get("expires_at"))
                                                    .toInstant()
                                                    .isAfter(Instant.now())
                                            ? "EXPIRED"
                                            : !Boolean.TRUE.equals(result.get("sourceFresh"))
                                                    ? "SOURCE_STALE"
                                                    : "FRESH";
            if (eligibility.equals("FRESH")) {
                try {
                    recheck(call, b, year, json(a.get("approver")), "approve");
                } catch (FinanceException stale) {
                    if (!stale.code.equals("PROFIT_CLOSING_APPROVAL_STALE")) throw stale;
                    eligibility = "ACTOR_STALE";
                }
            }
            approval =
                    map(
                            "id",
                            a.get("id"),
                            "approver",
                            json(a.get("approver")),
                            "contentVersion",
                            count(a.get("content_version")),
                            "contentDigest",
                            a.get("content_digest"),
                            "sourceDigest",
                            a.get("source_digest"),
                            "approvedJournalVersion",
                            count(a.get("approved_journal_version")),
                            "decidedAt",
                            a.get("decided_at"),
                            "expiresAt",
                            a.get("expires_at"),
                            "eligibilityForNewPost",
                            eligibility);
        }
        Map<String, Object> post = null;
        if (run.get("post_id") != null) {
            var p =
                    call.one(
                            "SELECT p.*,e.effect_key FROM plugin_finance_profit_closing_post p JOIN"
                                + " plugin_finance_profit_closing_effect e"
                                + " ON(e.tenant_id,e.book_id,e.year_id,e.id)=(p.tenant_id,p.book_id,p.year_id,p.effect_id)"
                                + " WHERE p.tenant_id=? AND p.book_id=? AND p.year_id=? AND p.id=?",
                            b.get("tenant_id"),
                            b.get("id"),
                            year,
                            run.get("post_id"));
            post =
                    map(
                            "id",
                            p.get("id"),
                            "approvalId",
                            p.get("approval_id"),
                            "poster",
                            json(p.get("poster")),
                            "postedAt",
                            p.get("posted_at"),
                            "postingSeq",
                            count(p.get("posting_seq")),
                            "postingDigest",
                            p.get("posting_digest"),
                            "contentVersion",
                            count(p.get("content_version")),
                            "contentDigest",
                            p.get("content_digest"),
                            "sourceDigest",
                            p.get("source_digest"),
                            "includedSourcePostingSeq",
                            count(p.get("included_source_posting_seq")),
                            "prePostYearPostingSeq",
                            count(p.get("pre_post_year_posting_seq")),
                            "effectKey",
                            p.get("effect_key"));
        }
        Map<String, Object> termination = null;
        if (run.get("administrative_termination_id") != null) {
            var t =
                    call.one(
                            "SELECT * FROM plugin_finance_profit_closing_termination WHERE"
                                + " tenant_id=? AND book_id=? AND year_id=? AND id=?",
                            b.get("tenant_id"),
                            b.get("id"),
                            year,
                            run.get("administrative_termination_id"));
            termination =
                    map(
                            "actor",
                            json(t.get("actor")),
                            "reason",
                            t.get("reason"),
                            "at",
                            t.get("created_at"),
                            "journalVersion",
                            count(t.get("journal_version")),
                            "contentVersion",
                            count(t.get("content_version")),
                            "contentDigest",
                            t.get("content_digest"));
        }
        result.putAll(
                map(
                        "source",
                        json(run.get("source_snapshot")),
                        "content",
                        json(run.get("content")),
                        "currentActor",
                        actor(call, b),
                        "currentApproval",
                        approval,
                        "postProof",
                        post,
                        "administrativeTermination",
                        termination,
                        "yearCloseProtocol",
                        "BLOCKED",
                        "cashPersonSod",
                        "NOT_ENFORCED"));
        if (approval != null && !approval.get("eligibilityForNewPost").equals("FRESH"))
            ((List<?>) result.get("allowedActions")).remove("post");
        return result;
    }

    Map<String, Object> page(
            Call call,
            String principal,
            Map<String, Object> b,
            UUID year,
            HttpServletRequest request) {
        var q = query(request, Set.of("limit", "cursor"));
        int limit = FinanceEvidenceAccess.limit(q);
        String generation =
                count(
                        call.one(
                                        "SELECT generation FROM plugin_finance_profit_closing_guard"
                                            + " WHERE tenant_id=? AND book_id=? AND year_id=?",
                                        b.get("tenant_id"),
                                        b.get("id"),
                                        year)
                                .get("generation"));
        if (((Number)
                                call.one(
                                                "SELECT count(*) n FROM plugin_finance_account"
                                                    + " WHERE tenant_id=? AND book_id=? AND"
                                                    + " year_id=?",
                                                b.get("tenant_id"),
                                                b.get("id"),
                                                year)
                                        .get("n"))
                        .longValue()
                > 5000) throw error("PROFIT_CLOSING_LIMIT_EXCEEDED", "科目数量超过此有界来源协议预算");
        String frame =
                call.one(
                                "SELECT"
                                    + " encode(sha256(convert_to(jsonb_build_object('guard',to_jsonb(g),'mode',to_jsonb(m),"
                                    + " 'periods',(SELECT"
                                    + " jsonb_agg(jsonb_build_object('id',p.id,'month',p.month,'status',p.status,'version',p.version)"
                                    + " ORDER BY p.month,p.id) FROM plugin_finance_period p"
                                    + " WHERE(p.tenant_id,p.book_id,p.year_id)=(g.tenant_id,g.book_id,g.year_id)),"
                                    + " 'accounts',(SELECT"
                                    + " jsonb_agg(jsonb_build_object('id',a.id,'code',a.code,'name',a.name,'category',a.category,'normalSide',a.normal_side,'enabled',a.enabled,'purpose',a.purpose)"
                                    + " ORDER BY a.id) FROM plugin_finance_account a"
                                    + " WHERE(a.tenant_id,a.book_id,a.year_id)=(g.tenant_id,g.book_id,g.year_id)))::text,'UTF8')),'hex')"
                                    + " digest FROM plugin_finance_year_guard g JOIN"
                                    + " plugin_finance_journal_person_mode m"
                                    + " ON(m.tenant_id,m.book_id,m.year_id)=(g.tenant_id,g.book_id,g.year_id)"
                                    + " WHERE g.tenant_id=? AND g.book_id=? AND g.year_id=?",
                                b.get("tenant_id"),
                                b.get("id"),
                                year)
                        .get("digest")
                        .toString();
        var context =
                map(
                        "schemaVersion",
                        FinanceProfitClosingInput.SCHEMA,
                        "resource",
                        "profit-closing-runs",
                        "actorId",
                        b.get("actor_id").toString(),
                        "tenantId",
                        b.get("tenant_id").toString(),
                        "bookId",
                        b.get("id").toString(),
                        "yearId",
                        year.toString(),
                        "limit",
                        limit,
                        "authorizationDigest",
                        authorizationDigest(call, principal, b),
                        "generation",
                        generation,
                        "sourceFrameDigest",
                        frame);
        Timestamp at = null;
        UUID anchor = null;
        if (q.containsKey("cursor")) {
            var cursor = untoken(q.get("cursor"));
            var keys = new HashSet<>(context.keySet());
            keys.add("createdAt");
            keys.add("runId");
            if (!keys.equals(cursor.keySet())) throw FinanceException.invalid("游标结构无效");
            for (String key : context.keySet())
                if (!Set.of("generation", "authorizationDigest", "sourceFrameDigest").contains(key)
                        && !Objects.equals(context.get(key), cursor.get(key)))
                    throw FinanceException.invalid("游标属于其他范围或调用者");
            if (!Objects.equals(context.get("generation"), cursor.get("generation"))
                    || !Objects.equals(
                            context.get("authorizationDigest"), cursor.get("authorizationDigest"))
                    || !Objects.equals(
                            context.get("sourceFrameDigest"), cursor.get("sourceFrameDigest")))
                throw error("PROFIT_CLOSING_PAGE_STALE", "结转列表、来源或授权上下文变化，请从首屏重读");
            try {
                at = Timestamp.from(Instant.parse(cursor.get("createdAt").toString()));
                anchor = FinanceProfitClosingInput.uuid(cursor.get("runId"));
            } catch (RuntimeException bad) {
                throw FinanceException.invalid("游标位置无效");
            }
        }
        var args = new ArrayList<Object>(List.of(b.get("tenant_id"), b.get("id"), year));
        String sql =
                "SELECT"
                    + " id,journal_id,producer_kind,cutoff,status,version,content_version,content_digest,source_digest,prepared_by,last_editor,predecessor_posted_run_id,supersedes_cancelled_attempt_id,reverses_run_id,created_at"
                    + " FROM plugin_finance_profit_closing_run WHERE tenant_id=? AND book_id=? AND"
                    + " year_id=?";
        if (at != null) {
            sql += " AND(created_at,id)<(?,?)";
            args.add(at);
            args.add(anchor);
        }
        sql += " ORDER BY created_at DESC,id DESC LIMIT ?";
        args.add(limit + 1);
        var rows = call.list(sql, args.toArray());
        boolean more = rows.size() > limit;
        var selected = rows.subList(0, Math.min(limit, rows.size()));
        String next = null;
        if (more) {
            var last = selected.getLast();
            var cursor = new LinkedHashMap<>(context);
            cursor.put("createdAt", ((Timestamp) last.get("created_at")).toInstant().toString());
            cursor.put("runId", last.get("id").toString());
            next = token(cursor);
        }
        var digests = new HashMap<String, String>();
        return map(
                "items",
                selected.stream().map(r -> summary(call, b, year, r, digests)).toList(),
                "nextCursor",
                next,
                "generation",
                generation,
                "currentActor",
                actor(call, b));
    }

    public Object read(
            String principal,
            UUID book,
            UUID year,
            String op,
            UUID target,
            HttpServletRequest request) {
        return transaction(
                false,
                call -> {
                    var b = call.scope(principal, book, year, null);
                    return switch (op) {
                        case "preview" -> preview(call, b, year, request);
                        case "list" -> page(call, principal, b, year, request);
                        case "detail" -> detail(call, b, year, target);
                        case "producer" -> producer(call, b, year, target);
                        default -> throw FinanceException.invalid("结转读取操作无效");
                    };
                });
    }

    void actionIdentity(
            Call call,
            Map<String, Object> b,
            UUID year,
            String op,
            Map<String, Object> run,
            Map<String, Object> who) {
        var maker = json(run.get("prepared_by"));
        var editor = json(run.get("last_editor"));
        if (Set.of("submit", "withdraw", "cancel").contains(op)) originalAccount(who, maker);
        if (Set.of("approve", "reject", "admin-terminate").contains(op)) {
            independent(who, maker);
            independent(who, editor);
        }
        if (op.equals("approve")) recheck(call, b, year, maker, "create");
        if (op.equals("post")) {
            var a =
                    call.one(
                            "SELECT * FROM plugin_finance_profit_closing_approval WHERE tenant_id=?"
                                + " AND book_id=? AND year_id=? AND run_id=? ORDER BY"
                                + " approved_journal_version DESC LIMIT 1",
                            b.get("tenant_id"),
                            b.get("id"),
                            year,
                            run.get("id"));
            recheck(call, b, year, json(a.get("approver")), "approve");
        }
    }

    public Object write(
            String principal,
            UUID book,
            UUID year,
            String op,
            UUID target,
            Map<String, Object> input,
            HttpServletRequest request) {
        var n = FinanceProfitClosingInput.normalized(op, input);
        return transaction(
                true,
                call -> {
                    var b =
                            call.locked(
                                    principal, book, year, op, n.get("idempotencyKey").toString());
                    var who = call.who(b, op, request);
                    if (!op.equals("create"))
                        actionIdentity(call, b, year, op, row(call, b, year, target), who);
                    String route = op + "/" + target;
                    var payload = new LinkedHashMap<>(n);
                    payload.remove("idempotencyKey");
                    String hash = FinanceService.hash(finance.encode(finance.canonical(payload)));
                    var cache =
                            call.list(
                                    "SELECT * FROM plugin_finance_profit_closing_request WHERE"
                                        + " actor_id=? AND request_key=?",
                                    b.get("actor_id"),
                                    n.get("idempotencyKey"));
                    UUID result;
                    if (!cache.isEmpty()) {
                        var c = cache.getFirst();
                        if (!Objects.equals(c.get("tenant_id"), b.get("tenant_id"))
                                || !Objects.equals(c.get("book_id"), book)
                                || !Objects.equals(c.get("year_id"), year)
                                || !Objects.equals(c.get("route"), route)
                                || !Objects.equals(c.get("payload_hash"), hash))
                            throw error("IDEMPOTENCY_CONFLICT", "请求键已用于其他内容或范围");
                        result = (UUID) c.get("run_id");
                    } else {
                        var value =
                                json(
                                        call.one(
                                                        "SELECT"
                                                            + " plugin_finance_profit_closing_command(?,?,?,?::jsonb,?,?,?::jsonb)::text"
                                                            + " value",
                                                        b.get("tenant_id"),
                                                        book,
                                                        year,
                                                        finance.encode(who),
                                                        op.toUpperCase(Locale.ROOT)
                                                                .replace('-', '_'),
                                                        target,
                                                        finance.encode(n))
                                                .get("value"));
                        result = id(value.get("runId"));
                        UUID journal = id(value.get("journalId"));
                        if (Boolean.TRUE.equals(value.get("newEffect"))) {
                            if (op.equals("post")) {
                                finance.invalidateClosing(
                                        (UUID) b.get("actor_id"), b, year, journal);
                                call.check();
                                FinanceOutbox.enqueue(
                                        db,
                                        finance,
                                        b,
                                        year,
                                        "JOURNAL_POSTED",
                                        journal,
                                        (UUID) b.get("actor_id"));
                            }
                            finance.originalAudit(
                                    (UUID) b.get("actor_id"),
                                    b,
                                    year,
                                    op.equals("post")
                                            ? "JOURNAL_POST"
                                            : "JOURNAL_PROFIT_CLOSING_"
                                                    + op.toUpperCase(Locale.ROOT).replace('-', '_'),
                                    journal,
                                    map("runId", result, "command", n, "actor", who));
                            call.check();
                        }
                        call.update(
                                "INSERT INTO"
                                    + " plugin_finance_profit_closing_request(tenant_id,book_id,year_id,actor_id,request_key,route,payload_hash,run_id)"
                                    + " VALUES(?,?,?,?,?,?,?,?)",
                                b.get("tenant_id"),
                                book,
                                year,
                                b.get("actor_id"),
                                n.get("idempotencyKey"),
                                route,
                                hash,
                                result);
                    }
                    return detail(call, b, year, result);
                });
    }
}
