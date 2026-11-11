package com.lumira.finance;

import com.lumira.platform.common.AuthorizationContextProjectionPort;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.transaction.PlatformTransactionManager;

import java.sql.SQLException;
import java.util.*;
import java.util.function.Function;

abstract class FinancePayablesAp01Access extends FinanceProfitClosingAccess {
    static final Set<String> CODES =
            Set.of(
                    "AMOUNT_OVERFLOW",
                    "AP_ACTIVE_RESERVATIONS",
                    "AP_APPROVAL_EXPIRED",
                    "AP_APPROVAL_STALE",
                    "AP_BASIS_PENDING",
                    "AP_BASIS_STALE",
                    "AP_BASIS_UNAVAILABLE",
                    "AP_BUSY",
                    "AP_CAPACITY_BELOW_COMMITTED",
                    "AP_CAPACITY_EXCEEDED",
                    "AP_DEPENDENCY_PRESENT",
                    "AP_LIMIT_EXCEEDED",
                    "AP_OBSERVATION_UNAVAILABLE",
                    "AP_PAGE_STALE",
                    "AP_PARENT_IN_USE",
                    "AP_PENDING_PARENT_REVERSAL",
                    "AP_QUERY_BUDGET_EXCEEDED",
                    "AP_SOURCE_ALREADY_CLAIMED",
                    "AP_SOURCE_STALE",
                    "AP_SOURCE_UNSUPPORTED",
                    "AP_STATE_CONFLICT",
                    "AP_SUBMISSION_EXPIRED",
                    "AP_SUPPLIER_STALE",
                    "IDEMPOTENCY_CONFLICT",
                    "INVALID_CURRENCY_SCALE",
                    "INVALID_INPUT",
                    "JOURNAL_PERSON_IDENTITY_UNAVAILABLE",
                    "JOURNAL_PERSON_MODE_REQUIRED",
                    "LEGACY_PRECISION_REVIEW_REQUIRED",
                    "PERIOD_CLOSED",
                    "PERSON_AUTH_ASSURANCE_REQUIRED",
                    "SCOPE_DENIED",
                    "SELF_APPROVAL_FORBIDDEN",
                    "VERSION_CONFLICT");

    FinancePayablesAp01Access(
            FinanceStore db,
            FinanceService finance,
            FinanceJournalPersonGate gate,
            PlatformTransactionManager tx,
            AuthorizationContextProjectionPort authorization) {
        super(db, finance, gate, tx, authorization);
    }

    Object run(boolean write, Function<Call, Object> work) {
        try {
            return transaction(
                    write,
                    c -> {
                        c.one("SELECT set_config('lock_timeout','2000',true) value");
                        return work.apply(c);
                    });
        } catch (RuntimeException failure) {
            for (Throwable x = failure; x != null; x = x.getCause())
                if (x instanceof SQLException sql) {
                    if ("55P03".equals(sql.getSQLState())) throw error("AP_BUSY", "应付对象被占用，请按原键重试");
                    if ("57014".equals(sql.getSQLState()))
                        throw error("AP_QUERY_BUDGET_EXCEEDED", "应付查询预算超限");
                    if ("P0001".equals(sql.getSQLState())) {
                        String code =
                                sql.getMessage()
                                        .replaceFirst("^ERROR: ", "")
                                        .split("[\\r\\n]", 2)[0];
                        if (CODES.contains(code))
                            throw error(code, "应付范围、版本或决定不满足条件（" + code + "）");
                    }
                }
            if (failure instanceof FinanceException f && f.code.equals("QUERY_BUDGET_EXCEEDED"))
                throw error("AP_QUERY_BUDGET_EXCEEDED", "应付同步处理预算超限");
            throw failure;
        }
    }

    Map<String, Object> scope(Call c, String principal, UUID book, UUID year, String op) {
        String action =
                op == null
                        ? null
                        : Set.of(
                                                "basis-approve",
                                                "basis-reject",
                                                "approve",
                                                "reject",
                                                "admin-terminate")
                                        .contains(op)
                                ? "approve"
                                : op.equals("post") ? "post" : "create";
        var b = c.scope(principal, book, year, action);
        if (op != null) {
            Set<String> roles =
                    action.equals("approve")
                            ? Set.of("OWNER", "REVIEWER")
                            : action.equals("post")
                                    ? Set.of("OWNER", "POSTER")
                                    : Set.of("OWNER", "MAKER");
            if (!roles.contains(b.get("role"))) throw error("SCOPE_DENIED", "当前岗位不允许该应付动作");
        }
        if (op != null && Set.of("basis-create", "basis-cancel").contains(op)) {
            c.timeout();
            finance.originalScope(principal, book, year, "manage");
            c.check();
            if (!"OWNER".equals(b.get("role"))) throw error("SCOPE_DENIED", "会计用途基准仅OWNER可提出或取消");
        }
        return b;
    }

    Map<String, Object> identity(Call c, Map<String, Object> b, HttpServletRequest request) {
        c.timeout();
        var w = gate.current((UUID) b.get("actor_id"), (UUID) b.get("tenant_id"), true, request);
        c.check();
        return w;
    }

    Map<String, Object> locked(
            Call c, String principal, UUID book, UUID year, String op, String key) {
        var b = scope(c, principal, book, year, op);
        c.list("SELECT plugin_finance_ap_prelock(?)", b.get("tenant_id"));
        c.list(
                "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                b.get("actor_id") + ":" + key);
        c.list("SELECT plugin_finance_ap_scope(?,?,?,false,NULL)", b.get("tenant_id"), book, year);
        return scope(c, principal, book, year, op);
    }

    Map<String, Object> header(Call c, Map<String, Object> b, UUID year, UUID target) {
        return c.one(
                "SELECT * FROM plugin_finance_ap_run WHERE tenant_id=? AND book_id=? AND year_id=?"
                        + " AND id=?",
                b.get("tenant_id"),
                b.get("id"),
                year,
                target);
    }

    Map<String, Object> basis(Call c, Map<String, Object> b, UUID year, UUID target) {
        return c.one(
                "SELECT * FROM plugin_finance_ap_basis WHERE tenant_id=? AND book_id=? AND"
                        + " year_id=? AND id=?",
                b.get("tenant_id"),
                b.get("id"),
                year,
                target);
    }

    void supplierRead(Call c, String principal, Map<String, Object> b, Object supplier) {
        var p =
                c.one(
                        "SELECT home_book_id FROM plugin_finance_counterparty_registry WHERE"
                                + " tenant_id=? AND legal_entity_id=? AND id=?",
                        b.get("tenant_id"),
                        b.get("legal_entity_id"),
                        id(supplier));
        c.timeout();
        finance.originalBookScope(principal, id(p.get("home_book_id")), "read");
        c.check();
    }

    void makerSupplier(
            Call c,
            Map<String, Object> b,
            UUID year,
            Map<String, Object> identity,
            Object supplier) {
        try {
            var member =
                    c.one(
                            "SELECT username FROM plugin_finance_member WHERE tenant_id=? AND"
                                    + " book_id=? AND user_id=? AND enabled",
                            b.get("tenant_id"),
                            b.get("id"),
                            id(identity.get("accountId")));
            var party =
                    c.one(
                            "SELECT home_book_id FROM plugin_finance_counterparty_registry WHERE"
                                + " tenant_id=? AND legal_entity_id=? AND id=?",
                            b.get("tenant_id"),
                            b.get("legal_entity_id"),
                            id(supplier));
            c.timeout();
            var actual =
                    finance.originalBookScope(
                            member.get("username").toString(),
                            id(party.get("home_book_id")),
                            "read");
            c.check();
            if (!Objects.equals(actual.get("actor_id"), id(identity.get("accountId"))))
                throw error("SCOPE_DENIED", "原制备/批准者账号归属变化");
        } catch (org.springframework.web.server.ResponseStatusException ex) {
            if (ex.getStatusCode().value() != 403 && ex.getStatusCode().value() != 404) throw ex;
            throw error("AP_APPROVAL_STALE", "原制备/批准者当前供应商治理home读取资格已失效");
        }
    }

    void maker(
            Call c, Map<String, Object> b, UUID year, Map<String, Object> original, boolean owner) {
        try {
            recheck(c, b, year, original, "create");
            if (owner) {
                var m =
                        c.one(
                                "SELECT username FROM plugin_finance_member WHERE tenant_id=? AND"
                                        + " book_id=? AND user_id=? AND enabled",
                                b.get("tenant_id"),
                                b.get("id"),
                                id(original.get("accountId")));
                c.timeout();
                var actual =
                        finance.originalScope(
                                m.get("username").toString(), id(b.get("id")), year, "manage");
                c.check();
                if (!"OWNER".equals(actual.get("role")))
                    throw error("SCOPE_DENIED", "原基准制备者不再是OWNER");
            }
        } catch (FinanceException e) {
            if (Set.of("PROFIT_CLOSING_APPROVAL_STALE", "SCOPE_DENIED").contains(e.code))
                throw error("AP_APPROVAL_STALE", "原制备者当前资格已失效，可独立退出");
            throw e;
        }
    }
}
