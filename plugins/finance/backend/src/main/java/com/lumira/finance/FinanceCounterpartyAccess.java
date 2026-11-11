package com.lumira.finance;

import com.lumira.platform.common.AuthorizationContextProjectionPort;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;

import java.sql.SQLException;
import java.util.*;
import java.util.function.Function;

abstract class FinanceCounterpartyAccess extends FinanceProfitClosingAccess {
    static final Set<String> CODES =
            Set.of(
                    "INVALID_INPUT",
                    "SCOPE_DENIED",
                    "SELF_APPROVAL_FORBIDDEN",
                    "VERSION_CONFLICT",
                    "IDEMPOTENCY_CONFLICT",
                    "COUNTERPARTY_IDENTITY_EXISTS",
                    "COUNTERPARTY_HOME_SCOPE_REQUIRED",
                    "COUNTERPARTY_STATE_CONFLICT",
                    "COUNTERPARTY_APPROVAL_STALE",
                    "COUNTERPARTY_IDENTITY_UNAVAILABLE",
                    "COUNTERPARTY_OBSERVATION_UNAVAILABLE",
                    "COUNTERPARTY_LIMIT_EXCEEDED");

    FinanceCounterpartyAccess(
            FinanceStore db,
            FinanceService finance,
            FinanceJournalPersonGate gate,
            PlatformTransactionManager tx,
            AuthorizationContextProjectionPort auth) {
        super(db, finance, gate, tx, auth);
    }

    Object run(boolean write, Function<Call, Object> work) {
        try {
            return transaction(
                    write,
                    call -> {
                        call.one("SELECT set_config('lock_timeout','2000',true) value");
                        return work.apply(call);
                    });
        } catch (RuntimeException failure) {
            for (Throwable x = failure; x != null; x = x.getCause())
                if (x instanceof SQLException sql) {
                    if ("55P03".equals(sql.getSQLState()))
                        throw error("COUNTERPARTY_BUSY", "往来目录被其它事务占用，请稍后按原键重试");
                    if ("57014".equals(sql.getSQLState()))
                        throw error("COUNTERPARTY_QUERY_BUDGET_EXCEEDED", "往来目录查询预算超限，请重新读取");
                    if ("P0001".equals(sql.getSQLState())) {
                        String code =
                                sql.getMessage()
                                        .replaceFirst("^ERROR: ", "")
                                        .split("[\\r\\n]", 2)[0];
                        if (CODES.contains(code))
                            throw error(code, "往来主数据范围、版本或决定不满足条件（" + code + "）");
                    }
                }
            if (failure instanceof FinanceException f && f.code.equals("QUERY_BUDGET_EXCEEDED"))
                throw error("COUNTERPARTY_QUERY_BUDGET_EXCEEDED", "往来目录合作处理预算超限");
            throw failure;
        }
    }

    Map<String, Object> scope(Call call, String principal, UUID book, String op) {
        call.timeout();
        var b = finance.originalBookScope(principal, book, "read");
        call.check();
        if (op != null) {
            call.timeout();
            finance.originalBookScope(
                    principal,
                    book,
                    Set.of("approve", "reject").contains(op) ? "approve" : "create");
            call.check();
            String role = b.get("role").toString();
            if (Set.of("approve", "reject").contains(op)) {
                if (!Set.of("OWNER", "REVIEWER").contains(role))
                    throw error("SCOPE_DENIED", "仅独立复核岗位可作决定");
            } else {
                if (!role.equals("OWNER")) throw error("SCOPE_DENIED", "仅治理账套OWNER可提议或取消");
                call.timeout();
                finance.originalBookScope(principal, book, "manage");
                call.check();
            }
        }
        return b;
    }

    Map<String, Object> party(Call call, Map<String, Object> b, UUID target) {
        return call.one(
                "SELECT * FROM plugin_finance_counterparty_registry WHERE tenant_id=? AND"
                        + " legal_entity_id=? AND id=?",
                b.get("tenant_id"),
                b.get("legal_entity_id"),
                target);
    }

    Map<String, Object> revision(Call call, Map<String, Object> p, UUID revision) {
        return call.one(
                "SELECT * FROM plugin_finance_counterparty_revision WHERE tenant_id=? AND"
                        + " legal_entity_id=? AND counterparty_id=? AND id=?",
                p.get("tenant_id"),
                p.get("legal_entity_id"),
                p.get("id"),
                revision);
    }

    void homeRead(Call call, String principal, Map<String, Object> current, Map<String, Object> p) {
        var home = scope(call, principal, (UUID) p.get("home_book_id"), null);
        if (!Objects.equals(home.get("tenant_id"), current.get("tenant_id"))
                || !Objects.equals(home.get("legal_entity_id"), current.get("legal_entity_id")))
            throw error("SCOPE_DENIED", "法人范围不一致");
    }

    Map<String, Object> who(Call call, Map<String, Object> b, HttpServletRequest request) {
        try {
            call.timeout();
            var who =
                    gate.current(
                            (UUID) b.get("actor_id"), (UUID) b.get("tenant_id"), true, request);
            call.check();
            return who;
        } catch (FinanceException f) {
            if (f.code.equals("JOURNAL_PERSON_IDENTITY_UNAVAILABLE"))
                throw error("COUNTERPARTY_IDENTITY_UNAVAILABLE", "需当前有效普通组织人员绑定");
            throw f;
        }
    }

    void recheckMaker(Call call, Map<String, Object> b, Map<String, Object> r) {
        try {
            var maker = json(r.get("maker"));
            var m =
                    call.one(
                            "SELECT username FROM plugin_finance_member WHERE tenant_id=? AND"
                                    + " book_id=? AND user_id=? AND enabled AND role='OWNER'",
                            b.get("tenant_id"),
                            b.get("id"),
                            id(maker.get("accountId")));
            String name = m.get("username").toString();
            var resolved = scope(call, name, (UUID) b.get("id"), "propose");
            if (!Objects.equals(resolved.get("actor_id"), id(maker.get("accountId"))))
                throw error("COUNTERPARTY_APPROVAL_STALE", "原制备账号身份与当前公开解析不一致");
            call.timeout();
            var current = gate.identity((UUID) b.get("tenant_id"), id(maker.get("accountId")));
            call.check();
            if (!current.equals(maker)) throw error("COUNTERPARTY_APPROVAL_STALE", "原制备人员绑定已改变");
        } catch (RuntimeException problem) {
            if (problem instanceof FinanceException f) {
                if (Set.of(
                                "SCOPE_DENIED",
                                "JOURNAL_PERSON_IDENTITY_UNAVAILABLE",
                                "COUNTERPARTY_IDENTITY_UNAVAILABLE",
                                "COUNTERPARTY_APPROVAL_STALE")
                        .contains(f.code))
                    throw error("COUNTERPARTY_APPROVAL_STALE", "原制备人当前治理权限或人员绑定已失效，可独立驳回");
            } else if (problem instanceof ResponseStatusException s
                    && (s.getStatusCode().value() == 403 || s.getStatusCode().value() == 404))
                throw error("COUNTERPARTY_APPROVAL_STALE", "原制备人当前公开权限已失效，可独立驳回");
            throw problem;
        }
    }

    void actionIdentity(
            Call call,
            Map<String, Object> b,
            String op,
            Map<String, Object> r,
            Map<String, Object> who) {
        var maker = json(r.get("maker"));
        if (Set.of("approve", "reject").contains(op)) {
            if (Objects.equals(who.get("personId"), maker.get("personId")))
                throw error("SELF_APPROVAL_FORBIDDEN", "同一自然人不得通过其他账号自审");
            if (op.equals("approve")) recheckMaker(call, b, r);
        } else if (op.equals("cancel")
                && !Objects.equals(who.get("accountId"), maker.get("accountId")))
            throw error("SCOPE_DENIED", "仅原制备账号可取消");
    }

    Map<String, Object> guard(Call call, Map<String, Object> b, UUID home) {
        var rows =
                call.list(
                        "SELECT epoch,generation::text generation FROM"
                                + " plugin_finance_counterparty_home_guard WHERE tenant_id=? AND"
                                + " legal_entity_id=? AND book_id=?",
                        b.get("tenant_id"),
                        b.get("legal_entity_id"),
                        home);
        if (rows.size() != 1)
            throw error("COUNTERPARTY_OBSERVATION_UNAVAILABLE", "往来目录真实读基线缺失，不能补零");
        return rows.getFirst();
    }

    String hash(Object value) {
        return FinanceService.hash(finance.encode(finance.canonical(value)));
    }
}
