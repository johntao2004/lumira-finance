package com.lumira.finance;

import com.lumira.platform.common.AuthorizationContextProjectionPort;
import com.lumira.platform.identity.api.AuthenticationRequestContext;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Function;

/** Per-call closing protocol adapter; no mutable thread-local or cross-proxy field access. */
abstract class FinanceProfitClosingAccess {
    static final Set<String> SQL_CODES =
            Set.of(
                    "INVALID_INPUT",
                    "SCOPE_DENIED",
                    "SELF_APPROVAL_FORBIDDEN",
                    "VERSION_CONFLICT",
                    "IDEMPOTENCY_CONFLICT",
                    "JOURNAL_PERSON_MODE_REQUIRED",
                    "JOURNAL_PERSON_IDENTITY_UNAVAILABLE",
                    "PERIOD_CLOSED",
                    "PROFIT_CLOSING_UNSUPPORTED_KIND",
                    "PROFIT_CLOSING_SOURCE_STALE",
                    "PROFIT_CLOSING_SOURCE_ALREADY_CLAIMED",
                    "PROFIT_CLOSING_RUN_PENDING",
                    "PROFIT_CLOSING_NO_MOVEMENT",
                    "PROFIT_CLOSING_APPROVAL_STALE",
                    "PROFIT_CLOSING_APPROVAL_EXPIRED",
                    "PROFIT_CLOSING_STATE_CONFLICT",
                    "PROFIT_CLOSING_REVERSAL_DEPENDENCY",
                    "PROFIT_CLOSING_PAGE_STALE",
                    "PROFIT_CLOSING_SOURCE_UNSUPPORTED",
                    "PROFIT_CLOSING_LIMIT_EXCEEDED");
    final FinanceStore db;
    final FinanceService finance;
    final FinanceJournalPersonGate gate;
    final PlatformTransactionManager transactions;
    final AuthorizationContextProjectionPort authorization;

    FinanceProfitClosingAccess(
            FinanceStore db,
            FinanceService finance,
            FinanceJournalPersonGate gate,
            PlatformTransactionManager transactions,
            AuthorizationContextProjectionPort authorization) {
        this.db = db;
        this.finance = finance;
        this.gate = gate;
        this.transactions = transactions;
        this.authorization = authorization;
    }

    static Map<String, Object> map(Object... pairs) {
        return FinanceJournalPersonGate.map(pairs);
    }

    static FinanceException error(String code, String message) {
        return new FinanceException(code, message);
    }

    Map<String, Object> json(Object value) {
        return gate.decode(value);
    }

    static UUID id(Object value) {
        return value instanceof UUID u ? u : FinanceProfitClosingInput.uuid(value);
    }

    static String count(Object value) {
        return value == null ? null : value.toString();
    }

    final class Call {
        final long started = System.nanoTime();
        final boolean write;
        final Map<String, Map<String, Object>> readActors = new HashMap<>();
        final Map<String, Map<String, Object>> proposalFrames = new HashMap<>();

        Call(boolean write) {
            this.write = write;
        }

        void check() {
            if (System.nanoTime() - started > 10_000_000_000L)
                throw error("QUERY_BUDGET_EXCEEDED", "结转同步处理预算已超限；没有返回部分成功或空结果");
        }

        void timeout() {
            check();
            long remaining =
                    Math.max(1, (10_000_000_000L - (System.nanoTime() - started)) / 1_000_000L);
            db.one(
                    "SELECT set_config('statement_timeout',?,true) value",
                    Long.toString(Math.min(3000, remaining)));
            check();
        }

        List<Map<String, Object>> list(String sql, Object... args) {
            timeout();
            var rows = db.list(sql, args);
            check();
            return rows;
        }

        Map<String, Object> one(String sql, Object... args) {
            var rows = list(sql, args);
            if (rows.isEmpty()) throw error("SCOPE_DENIED", "范围不可访问");
            return rows.getFirst();
        }

        int update(String sql, Object... args) {
            timeout();
            int result = db.update(sql, args);
            check();
            return result;
        }

        Map<String, Object> scope(String principal, UUID book, UUID year, String operation) {
            timeout();
            var b = finance.originalScope(principal, book, year, "read");
            check();
            if (operation != null) {
                finance.originalScope(
                        principal,
                        book,
                        year,
                        switch (operation) {
                            case "approve", "reject", "admin-terminate" -> "approve";
                            case "post" -> "post";
                            default -> "create";
                        });
                check();
            }
            return b;
        }

        Map<String, Object> locked(String principal, UUID book, UUID year, String op, String key) {
            var b = scope(principal, book, year, op);
            list("SELECT plugin_finance_profit_closing_prelock(?)", b.get("tenant_id"));
            list(
                    "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                    b.get("actor_id") + ":" + key);
            one(
                    "SELECT id FROM plugin_finance_book WHERE tenant_id=? AND id=? FOR UPDATE",
                    b.get("tenant_id"),
                    book);
            b = scope(principal, book, year, op);
            finance.lockYearWrites(b, List.of(year));
            check();
            if (!gate.enabled(b, year))
                throw error("JOURNAL_PERSON_MODE_REQUIRED", "该结转协议只支持明确启用人员审签的V4年度");
            check();
            return b;
        }

        Map<String, Object> who(Map<String, Object> b, String op, HttpServletRequest request) {
            timeout();
            var who =
                    gate.current(
                            (UUID) b.get("actor_id"),
                            (UUID) b.get("tenant_id"),
                            Set.of("approve", "post", "reject", "admin-terminate").contains(op),
                            request);
            check();
            return who;
        }
    }

    Object transaction(boolean write, Function<Call, Object> work) {
        var call = new Call(write);
        var tx = new TransactionTemplate(transactions);
        tx.setReadOnly(!write);
        tx.setTimeout(10);
        tx.setIsolationLevel(
                write
                        ? TransactionDefinition.ISOLATION_READ_COMMITTED
                        : TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try {
            Object value =
                    tx.execute(
                            status -> {
                                call.timeout();
                                if (write)
                                    TransactionSynchronizationManager.registerSynchronization(
                                            new TransactionSynchronization() {
                                                @Override
                                                public void beforeCommit(boolean readOnly) {
                                                    call.check();
                                                }
                                            });
                                Object result = work.apply(call);
                                call.check();
                                return result;
                            });
            if (!write)
                call
                        .check(); // A successful committed financial write is never relabelled
                                  // rollback.
            return value;
        } catch (RuntimeException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql) {
                    if ("57014".equals(sql.getSQLState()))
                        throw error("QUERY_BUDGET_EXCEEDED", "结转查询或处理预算已超限，请重新预览后重试");
                    if ("P0001".equals(sql.getSQLState())) {
                        String code =
                                sql.getMessage()
                                        .replaceFirst("^ERROR: ", "")
                                        .split("[\\r\\n]", 2)[0];
                        if (SQL_CODES.contains(code))
                            throw error(code, "结转来源、审签或当前状态不满足操作条件（" + code + "）");
                    }
                }
                if (cause instanceof QueryTimeoutException
                        || cause instanceof TransactionTimedOutException)
                    throw error("QUERY_BUDGET_EXCEEDED", "结转查询或事务预算已超限，请重新预览后重试");
            }
            throw failure;
        }
    }

    Map<String, Object> actor(Call call, Map<String, Object> b) {
        String key = b.get("tenant_id") + ":" + b.get("actor_id");
        if (!call.write && call.readActors.containsKey(key)) return call.readActors.get(key);
        var rows =
                call.list(
                        "SELECT x.person_id,x.status FROM plugin_finance_person_binding x JOIN"
                            + " plugin_finance_person p"
                            + " ON(p.tenant_id,p.id)=(x.tenant_id,x.person_id) JOIN"
                            + " plugin_finance_person_realm r ON r.tenant_id=x.tenant_id WHERE"
                            + " x.tenant_id=? AND x.account_id=?",
                        b.get("tenant_id"),
                        b.get("actor_id"));
        boolean configured =
                !call.list(
                                "SELECT tenant_id FROM plugin_finance_person_realm WHERE"
                                    + " tenant_id=?",
                                b.get("tenant_id"))
                        .isEmpty();
        String status =
                !configured
                        ? "UNCONFIGURED"
                        : rows.isEmpty()
                                ? "UNBOUND"
                                : "ACTIVE".equals(rows.getFirst().get("status"))
                                        ? "AVAILABLE"
                                        : "REVOKED";
        var projected =
                map(
                        "accountId",
                        b.get("actor_id"),
                        "personId",
                        status.equals("AVAILABLE") ? rows.getFirst().get("person_id") : null,
                        "identityAvailability",
                        status);
        if (!call.write) call.readActors.put(key, projected);
        return projected;
    }

    boolean allowed(Map<String, Object> b, String action) {
        var context = AuthenticationRequestContext.current();
        if (context == null
                || context.authorization() == null
                || !Objects.equals(context.userId(), b.get("actor_id"))
                || !context.authorization()
                        .effectiveAuthorities()
                        .contains("platform:finance:" + action)) return false;
        String role = b.get("role").toString();
        return action.equals("read")
                || role.equals("OWNER")
                || action.equals("create") && role.equals("MAKER")
                || action.equals("approve") && role.equals("REVIEWER")
                || action.equals("post") && role.equals("POSTER");
    }

    boolean samePerson(Map<String, Object> actor, Map<String, Object> identity) {
        return actor.get("personId") != null
                && actor.get("personId").toString().equals(identity.get("personId").toString());
    }

    void independent(Map<String, Object> who, Map<String, Object> maker) {
        if (Objects.equals(who.get("personId"), maker.get("personId")))
            throw error("SELF_APPROVAL_FORBIDDEN", "同一自然人不得通过其他账号自审");
    }

    void originalAccount(Map<String, Object> who, Map<String, Object> maker) {
        if (!Objects.equals(who.get("accountId"), maker.get("accountId")))
            throw error("SCOPE_DENIED", "此动作仅原制备账号可执行");
    }

    void recheck(
            Call call,
            Map<String, Object> b,
            UUID year,
            Map<String, Object> identity,
            String action) {
        try {
            var member =
                    call.one(
                            "SELECT username FROM plugin_finance_member WHERE tenant_id=? AND"
                                + " book_id=? AND user_id=? AND enabled",
                            b.get("tenant_id"),
                            b.get("id"),
                            id(identity.get("accountId")));
            String principal = member.get("username").toString();
            var actual =
                    call.scope(
                            principal,
                            (UUID) b.get("id"),
                            year,
                            action.equals("approve") ? "approve" : "create");
            if (!Objects.equals(actual.get("actor_id"), id(identity.get("accountId")))
                    || !identity.equals(
                            gate.identity(
                                    (UUID) b.get("tenant_id"), id(identity.get("accountId")))))
                throw new IllegalArgumentException();
            call.check();
        } catch (RuntimeException invalid) {
            if (invalid instanceof DataAccessException || invalid instanceof TransactionException)
                throw invalid;
            if (invalid instanceof FinanceException f
                    && !Set.of(
                                    "SCOPE_DENIED",
                                    "JOURNAL_PERSON_IDENTITY_UNAVAILABLE",
                                    "APPROVAL_STALE")
                            .contains(f.code)) throw f;
            if (invalid instanceof ResponseStatusException status
                    && !(invalid instanceof FinanceException)
                    && status.getStatusCode().value() != 403
                    && status.getStatusCode().value() != 404) throw status;
            if (!(invalid instanceof FinanceException)
                    && !(invalid instanceof ResponseStatusException)
                    && !(invalid instanceof IllegalArgumentException)) throw invalid;
            throw error("PROFIT_CLOSING_APPROVAL_STALE", "原制备/批准者当前权限或人员绑定已失效；可独立退回或终结");
        }
    }

    String authorizationDigest(Call call, String principal, Map<String, Object> b) {
        call.check();
        var current =
                authorization
                        .current(principal)
                        .orElseThrow(() -> error("SCOPE_DENIED", "公开授权上下文不可用"));
        call.check();
        var years =
                call.list(
                        "SELECT year_id FROM plugin_finance_member_year WHERE tenant_id=? AND"
                            + " book_id=? AND user_id=? ORDER BY year_id",
                        b.get("tenant_id"),
                        b.get("id"),
                        b.get("actor_id"));
        var realm =
                call.list(
                        "SELECT authority_id,manifest_sha256,generation FROM"
                            + " plugin_finance_person_realm WHERE tenant_id=?",
                        b.get("tenant_id"));
        var binding =
                call.list(
                        "SELECT id,person_id,status,version FROM plugin_finance_person_binding"
                            + " WHERE tenant_id=? AND account_id=?",
                        b.get("tenant_id"),
                        b.get("actor_id"));
        return FinanceService.hash(
                finance.encode(
                        finance.canonical(
                                map(
                                        "actor",
                                        b.get("actor_id"),
                                        "authorities",
                                        new TreeSet<>(current.effectiveAuthorities()),
                                        "global",
                                        new TreeSet<>(current.effectiveGlobalAuthorities()),
                                        "preview",
                                        current.previewActive(),
                                        "targetRole",
                                        current.targetRoleId(),
                                        "targetVersion",
                                        current.targetRoleVersion(),
                                        "role",
                                        b.get("role"),
                                        "yearScope",
                                        b.get("year_scope"),
                                        "years",
                                        years,
                                        "realm",
                                        realm,
                                        "binding",
                                        binding))));
    }

    static Map<String, String> query(HttpServletRequest request, Set<String> allowed) {
        return FinanceEvidenceAccess.query(request, allowed);
    }

    String token(Map<String, Object> value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        finance.encode(finance.canonical(value)).getBytes(StandardCharsets.UTF_8));
    }

    Map<String, Object> untoken(String value) {
        try {
            if (value.length() > 4096 || !value.matches("[A-Za-z0-9_-]+"))
                throw new IllegalArgumentException();
            return finance.decode(
                    new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8));
        } catch (RuntimeException bad) {
            throw FinanceException.invalid("分页游标无效");
        }
    }
}
