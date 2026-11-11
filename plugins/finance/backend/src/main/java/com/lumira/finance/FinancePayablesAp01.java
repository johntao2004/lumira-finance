package com.lumira.finance;

import com.lumira.platform.common.AuthorizationContextProjectionPort;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.*;

/** Actual AP recognition producer; never payment, reimbursement or a complete subledger. */
@Service
public class FinancePayablesAp01 extends FinancePayablesAp01Projection {
    @Autowired
    public FinancePayablesAp01(
            FinanceStore db,
            FinanceService finance,
            FinanceJournalPersonGate gate,
            PlatformTransactionManager tx,
            AuthorizationContextProjectionPort authorization) {
        super(db, finance, gate, tx, authorization);
    }

    public Object read(
            String principal,
            UUID book,
            UUID year,
            String op,
            UUID target,
            HttpServletRequest request) {
        return run(
                false,
                c -> {
                    var b = scope(c, principal, book, year, null);
                    return switch (op) {
                        case "catalog" -> catalog(c, b, year);
                        case "parents", "basis-list", "list", "open-items" ->
                                page(c, principal, b, year, op, request);
                        case "basis-detail" -> basisDetail(c, b, year, target);
                        case "detail" -> detail(c, principal, b, year, target, request);
                        case "open-item" -> itemDetail(c, principal, b, year, target);
                        case "producer" -> producer(c, b, year, target);
                        case "reconciliation" -> reconciliation(c, b, year);
                        default -> throw FinanceException.invalid("未知应付读取操作");
                    };
                });
    }

    void actionIdentity(
            Call c,
            Map<String, Object> b,
            UUID year,
            String op,
            UUID target,
            Map<String, Object> who) {
        if (target == null) return;
        if (op.startsWith("basis-")) {
            var r = basis(c, b, year, target);
            var maker = json(r.get("prepared_by"));
            if (op.equals("basis-cancel")) originalAccount(who, maker);
            else independent(who, maker);
            if (op.equals("basis-approve")) maker(c, b, year, maker, true);
            return;
        }
        var r = header(c, b, year, target);
        var original = json(r.get("prepared_by"));
        if (Set.of("submit", "withdraw", "cancel").contains(op)) originalAccount(who, original);
        if (Set.of("approve", "reject", "admin-terminate").contains(op)) {
            independent(who, original);
            independent(who, json(r.get("last_editor")));
        }
        if (Set.of("submit", "approve", "post").contains(op)) {
            maker(c, b, year, original, false);
            if (r.get("producer_kind").equals("AP_EXPENSE"))
                makerSupplier(c, b, year, original, r.get("supplier_id"));
        }
        if (op.equals("post")) {
            var a =
                    c.one(
                            "SELECT actor FROM plugin_finance_ap_approval WHERE tenant_id=? AND"
                                    + " book_id=? AND year_id=? AND run_id=? AND id=?",
                            b.get("tenant_id"),
                            b.get("id"),
                            year,
                            target,
                            r.get("current_approval_id"));
            try {
                recheck(c, b, year, json(a.get("actor")), "approve");
                if (r.get("producer_kind").equals("AP_EXPENSE"))
                    makerSupplier(c, b, year, json(a.get("actor")), r.get("supplier_id"));
            } catch (FinanceException ex) {
                if (ex.code.equals("PROFIT_CLOSING_APPROVAL_STALE"))
                    throw error("AP_APPROVAL_STALE", "批准者当前资格已失效");
                throw ex;
            }
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
        var n = FinancePayablesAp01Input.normalized(op, input);
        return run(
                true,
                c -> {
                    var b =
                            locked(
                                    c,
                                    principal,
                                    book,
                                    year,
                                    op,
                                    n.get("idempotencyKey").toString());
                    var who = identity(c, b, request);
                    if (Set.of("submit", "approve", "post").contains(op)) {
                        var current = header(c, b, year, target);
                        if (current.get("producer_kind").equals("AP_EXPENSE"))
                            supplierRead(c, principal, b, current.get("supplier_id"));
                    }
                    actionIdentity(c, b, year, op, target, who);
                    String route = op + "/" + target;
                    var payload = new LinkedHashMap<>(n);
                    payload.remove("idempotencyKey");
                    String hash = FinanceService.hash(finance.encode(finance.canonical(payload)));
                    var cache =
                            c.list(
                                    "SELECT * FROM plugin_finance_ap_request WHERE actor_id=? AND"
                                            + " request_key=?",
                                    b.get("actor_id"),
                                    n.get("idempotencyKey"));
                    if (!cache.isEmpty()) {
                        var old = cache.getFirst();
                        if (!Objects.equals(old.get("tenant_id"), b.get("tenant_id"))
                                || !Objects.equals(old.get("book_id"), book)
                                || !Objects.equals(old.get("year_id"), year)
                                || !Objects.equals(old.get("route"), route)
                                || !Objects.equals(old.get("payload_hash"), hash))
                            throw error("IDEMPOTENCY_CONFLICT", "同请求键正文或范围发生变化");
                        if (old.get("result_kind").equals("PAYABLE")
                                && Set.of("create", "submit", "approve", "post").contains(op)) {
                            var prior = header(c, b, year, id(old.get("run_id")));
                            if (prior.get("producer_kind").equals("AP_EXPENSE"))
                                supplierRead(c, principal, b, prior.get("supplier_id"));
                        }
                        return commandResult(
                                c,
                                principal,
                                b,
                                year,
                                old.get("result_kind").toString(),
                                id(old.get("command_receipt_id")),
                                id(
                                        old.get(
                                                old.get("result_kind").equals("BASIS")
                                                        ? "basis_id"
                                                        : "run_id")),
                                true);
                    }
                    if (op.equals("create"))
                        supplierRead(
                                c,
                                principal,
                                b,
                                FinancePayablesAp01Input.expectation(
                                                n.get("expectedSupplier"), "SupplierExpectation")
                                        .get("counterpartyId"));
                    if (Set.of("submit", "approve", "post").contains(op)) {
                        var r = header(c, b, year, target);
                        if (r.get("producer_kind").equals("AP_EXPENSE"))
                            supplierRead(c, principal, b, r.get("supplier_id"));
                    }
                    var value =
                            json(
                                    c.one(
                                                    "SELECT"
                                                        + " plugin_finance_ap_command(?,?,?,?::jsonb,?,?,?::jsonb)::text"
                                                        + " value",
                                                    b.get("tenant_id"),
                                                    book,
                                                    year,
                                                    finance.encode(who),
                                                    op,
                                                    target,
                                                    finance.encode(n))
                                            .get("value"));
                    String kind = value.get("kind").toString();
                    UUID resource = id(value.get("id"));
                    UUID receipt = id(value.get("commandReceiptId"));
                    if (Boolean.TRUE.equals(value.get("newEffect")) && kind.equals("PAYABLE")) {
                        var r = header(c, b, year, resource);
                        c.timeout();
                        finance.originalAudit(
                                (UUID) b.get("actor_id"),
                                b,
                                year,
                                "AP_" + op.toUpperCase(Locale.ROOT).replace('-', '_'),
                                id(r.get("journal_id")),
                                map(
                                        "payableId",
                                        resource,
                                        "commandReceiptId",
                                        receipt,
                                        "reason",
                                        n.get("reason")));
                        c.check();
                        if (op.equals("post")) {
                            c.timeout();
                            FinanceOutbox.enqueue(
                                    db,
                                    finance,
                                    b,
                                    year,
                                    "JOURNAL_POSTED",
                                    id(r.get("journal_id")),
                                    (UUID) b.get("actor_id"));
                            c.check();
                        }
                    }
                    c.update(
                            "INSERT INTO"
                                + " plugin_finance_ap_request(tenant_id,book_id,year_id,actor_id,request_key,route,payload_hash,result_kind,basis_id,run_id,command_receipt_id)"
                                + " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                            b.get("tenant_id"),
                            book,
                            year,
                            b.get("actor_id"),
                            n.get("idempotencyKey"),
                            route,
                            hash,
                            kind,
                            kind.equals("BASIS") ? resource : null,
                            kind.equals("PAYABLE") ? resource : null,
                            receipt);
                    return commandResult(
                            c,
                            principal,
                            b,
                            year,
                            kind,
                            receipt,
                            resource,
                            !Boolean.TRUE.equals(value.get("newEffect")));
                });
    }

    Object commandResult(
            Call c,
            String principal,
            Map<String, Object> b,
            UUID year,
            String kind,
            UUID receipt,
            UUID resource,
            boolean replayed) {
        return map(
                "commandReceipt",
                receipt(c, b, year, receipt),
                "current",
                kind.equals("BASIS")
                        ? basisDetail(c, b, year, resource)
                        : detail(c, principal, b, year, resource),
                "replayed",
                replayed);
    }
}
