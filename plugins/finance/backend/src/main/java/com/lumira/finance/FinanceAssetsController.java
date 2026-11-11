package com.lumira.finance;

import com.lumira.platform.common.BoundedBody;
import com.lumira.platform.modules.ModuleEndpoint;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/finance/v1/books/{book}/years/{year}/assets")
@ModuleEndpoint("finance")
public final class FinanceAssetsController {
    private static final Set<String> ENVELOPE_KEYS =
            Set.of("accountingDate", "idempotencyKey", "payload");
    private final FinanceAssets service;

    public FinanceAssetsController(FinanceAssets service) {
        this.service = service;
    }

    private UUID id(String s) {
        return FinancePersonDirectory.uuid(s);
    }

    private LocalDate accountingDate(String raw) {
        if (raw == null || !raw.matches("\\d{4}-\\d{2}-\\d{2}"))
            throw FinanceException.invalid("必须提供有效的资产会计日期（YYYY-MM-DD）");
        try {
            return LocalDate.parse(raw);
        } catch (RuntimeException e) {
            throw FinanceException.invalid("资产会计日期无效");
        }
    }

    private void query(HttpServletRequest request, Set<String> allowed) {
        request.getParameterMap()
                .forEach(
                        (key, value) -> {
                            if (!allowed.contains(key) || value == null || value.length != 1)
                                throw FinanceException.invalid("查询字段无效或重复");
                        });
    }

    private static String dec(java.math.BigDecimal v) {
        return v == null ? null : v.toPlainString();
    }

    private static Map<String, Object> policyView(com.lumira.finance.assets.AssetModel.Policy p) {
        return FinanceJournalPersonGate.map(
                "framework",
                p.framework(),
                "kind",
                p.kind(),
                "method",
                p.method(),
                "firstPeriod",
                p.firstPeriod(),
                "months",
                p.months(),
                "residual",
                dec(p.residual()),
                "assessmentId",
                p.assessmentId(),
                "basisAccumulated",
                dec(p.basisAccumulated()),
                "basisElapsed",
                p.basisElapsed());
    }

    private static Map<String, Object> cardView(com.lumira.finance.assets.AssetModel.Card c) {
        if (c == null) return null;
        return FinanceJournalPersonGate.map(
                "id",
                c.id(),
                "name",
                c.name(),
                "classification",
                c.classification(),
                "acquired",
                c.acquired().toString(),
                "ready",
                c.ready().toString(),
                "location",
                c.location(),
                "custodian",
                c.custodian(),
                "policy",
                policyView(c.policy()),
                "accounts",
                c.accounts(),
                "cost",
                dec(c.cost()),
                "accumulated",
                dec(c.accumulated()),
                "impairment",
                dec(c.impairment()),
                "elapsed",
                c.elapsed(),
                "lastPeriod",
                c.lastPeriod(),
                "version",
                c.version(),
                "status",
                c.status());
    }

    private static Map<String, Object> traceView(com.lumira.finance.assets.AssetModel.Trace t) {
        if (t == null) return null;
        return FinanceJournalPersonGate.map(
                "base",
                dec(t.base()),
                "unrounded",
                dec(t.unrounded()),
                "recognized",
                dec(t.recognized()),
                "roundingAdjustment",
                dec(t.roundingAdjustment()),
                "remaining",
                dec(t.remaining()),
                "remainingMonths",
                t.remainingMonths());
    }

    private static Map<String, Object> storedPlanView(
            com.lumira.finance.assets.AssetModel.StoredPlan sp) {
        var p = sp.plan();
        var s = p.scope();
        Map<String, Object> estView = null;
        if (p.estimate() != null) {
            estView =
                    FinanceJournalPersonGate.map(
                            "oldSchedule",
                            p.estimate().oldSchedule().stream()
                                    .map(
                                            r ->
                                                    FinanceJournalPersonGate.map(
                                                            "period",
                                                            r.period(),
                                                            "amount",
                                                            dec(r.amount()),
                                                            "remaining",
                                                            dec(r.remaining())))
                                    .toList(),
                            "newSchedule",
                            p.estimate().newSchedule().stream()
                                    .map(
                                            r ->
                                                    FinanceJournalPersonGate.map(
                                                            "period",
                                                            r.period(),
                                                            "amount",
                                                            dec(r.amount()),
                                                            "remaining",
                                                            dec(r.remaining())))
                                    .toList());
        }
        var planMap =
                FinanceJournalPersonGate.map(
                        "id",
                        p.id(),
                        "scope",
                        FinanceJournalPersonGate.map(
                                "tenant",
                                s.tenant(),
                                "book",
                                s.book(),
                                "year",
                                s.year(),
                                "period",
                                s.period(),
                                "date",
                                s.date().toString()),
                        "kind",
                        p.kind(),
                        "businessKey",
                        p.businessKey(),
                        "sourceId",
                        p.sourceId(),
                        "reason",
                        p.reason(),
                        "maker",
                        p.maker(),
                        "changes",
                        p.changes().stream()
                                .map(
                                        ch ->
                                                FinanceJournalPersonGate.map(
                                                        "before",
                                                        cardView(ch.before()),
                                                        "after",
                                                        cardView(ch.after()),
                                                        "trace",
                                                        traceView(ch.trace())))
                                .toList(),
                        "lines",
                        p.lines().stream()
                                .map(
                                        l ->
                                                FinanceJournalPersonGate.map(
                                                        "account",
                                                        l.account(),
                                                        "debit",
                                                        dec(l.debit()),
                                                        "credit",
                                                        dec(l.credit())))
                                .toList(),
                        "estimate",
                        estView,
                        "reverses",
                        p.reverses(),
                        "sourceAmount",
                        dec(p.sourceAmount()));
        return FinanceJournalPersonGate.map(
                "plan",
                planMap,
                "digest",
                sp.digest(),
                "status",
                sp.status(),
                "approver",
                sp.approver(),
                "journal",
                sp.journal(),
                "version",
                sp.version());
    }

    @GetMapping("/cards")
    public Object cards(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            HttpServletRequest request) {
        return FinanceHttp.respond(
                200,
                () -> {
                    query(request, Set.of("after", "limit", "accountingDate"));
                    int limit = 100;
                    try {
                        if (request.getParameter("limit") != null)
                            limit = Integer.parseInt(request.getParameter("limit"));
                    } catch (NumberFormatException e) {
                        throw FinanceException.invalid("分页大小无效");
                    }
                    String after = request.getParameter("after");
                    return service
                            .cards(
                                    actor.getName(),
                                    id(book),
                                    id(year),
                                    accountingDate(request.getParameter("accountingDate")),
                                    after == null || after.isBlank() ? null : id(after),
                                    limit)
                            .stream()
                            .map(FinanceAssetsController::cardView)
                            .toList();
                });
    }

    @GetMapping("/plans/{planId}")
    public Object detail(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable String planId,
            HttpServletRequest request) {
        return FinanceHttp.respond(
                200,
                () -> {
                    query(request, Set.of("accountingDate"));
                    return storedPlanView(
                            service.detail(
                                    actor.getName(),
                                    id(book),
                                    id(year),
                                    accountingDate(request.getParameter("accountingDate")),
                                    id(planId)));
                });
    }

    @GetMapping("/reconciliation")
    public Object reconciliation(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            HttpServletRequest request) {
        return FinanceHttp.respond(
                200,
                () -> {
                    query(request, Set.of("accountingDate"));
                    return service
                            .reconcile(
                                    actor.getName(),
                                    id(book),
                                    id(year),
                                    accountingDate(request.getParameter("accountingDate")))
                            .stream()
                            .map(
                                    d ->
                                            FinanceJournalPersonGate.map(
                                                    "account",
                                                    d.account(),
                                                    "subledgerDebitNet",
                                                    dec(d.subledgerDebitNet()),
                                                    "ledgerDebitNet",
                                                    dec(d.ledgerDebitNet()),
                                                    "difference",
                                                    dec(d.difference()),
                                                    "watermark",
                                                    d.watermark()))
                            .toList();
                });
    }

    @PostMapping("/acquisitions")
    public Object acquisitions(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @RequestBody @BoundedBody(maxBytes = 65536, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(actor, book, year, "acquire", null, body, request);
    }

    @PostMapping("/depreciations")
    public Object depreciations(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @RequestBody @BoundedBody(maxBytes = 8192, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(actor, book, year, "depreciate", null, body, request);
    }

    @PostMapping("/estimate-changes")
    public Object estimateChanges(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @RequestBody @BoundedBody(maxBytes = 8192, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(actor, book, year, "estimate", null, body, request);
    }

    @PostMapping("/disposals")
    public Object disposals(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @RequestBody @BoundedBody(maxBytes = 8192, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(actor, book, year, "dispose", null, body, request);
    }

    @PostMapping("/reversals")
    public Object reversals(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @RequestBody @BoundedBody(maxBytes = 8192, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(actor, book, year, "reverse", null, body, request);
    }

    @PostMapping("/depreciation-corrections")
    public Object depreciationCorrections(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @RequestBody @BoundedBody(maxBytes = 8192, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(actor, book, year, "correct-depreciation", null, body, request);
    }

    @PostMapping("/plans/{planId}/approve")
    public Object approvePlan(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable String planId,
            @RequestBody @BoundedBody(maxBytes = 8192, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return transition(actor, book, year, planId, "approve", body, request);
    }

    @PostMapping("/plans/{planId}/post")
    public Object postPlan(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable String planId,
            @RequestBody @BoundedBody(maxBytes = 8192, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return transition(actor, book, year, planId, "post", body, request);
    }

    @PostMapping("/plans/{planId}/cancel")
    public Object cancelPlan(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable String planId,
            @RequestBody @BoundedBody(maxBytes = 8192, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return transition(actor, book, year, planId, "cancel", body, request);
    }

    public Object transition(
            Authentication actor,
            @PathVariable String book,
            @PathVariable String year,
            @PathVariable String planId,
            @PathVariable String action,
            @RequestBody @BoundedBody(maxBytes = 8192, errorCode = "INVALID_INPUT")
                    Map<String, Object> body,
            HttpServletRequest request) {
        return write(actor, book, year, action, id(planId), body, request);
    }

    @SuppressWarnings("unchecked")
    private Object write(
            Authentication actor,
            String book,
            String year,
            String action,
            UUID pathPlanId,
            Map<String, Object> body,
            HttpServletRequest request) {
        return FinanceHttp.respond(
                200,
                () -> {
                    query(request, Set.of());
                    if (body == null
                            || !ENVELOPE_KEYS.containsAll(body.keySet())
                            || !(body.get("accountingDate") instanceof String dateStr)
                            || !(body.get("idempotencyKey") instanceof String keyStr)
                            || !(body.get("payload") instanceof Map<?, ?> rawPayload))
                        throw FinanceException.invalid("资产命令信封必须仅包含 accountingDate、idempotencyKey 和 payload");
                    var payload = new LinkedHashMap<>((Map<String, Object>) rawPayload);
                    if (pathPlanId != null) {
                        Object bodyPlanId = payload.get("planId");
                        if (bodyPlanId != null && !pathPlanId.toString().equals(bodyPlanId.toString()))
                            throw FinanceException.invalid("路径计划编号与请求体不一致");
                        payload.put("planId", pathPlanId.toString());
                    }
                    return service.command(
                            actor.getName(),
                            id(book),
                            id(year),
                            accountingDate(dateStr),
                            action,
                            keyStr,
                            payload,
                            request);
                });
    }
}
