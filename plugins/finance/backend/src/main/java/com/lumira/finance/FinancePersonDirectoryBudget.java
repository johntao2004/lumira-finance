package com.lumira.finance;

import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.TransactionTimedOutException;

import java.sql.SQLException;
import java.util.*;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** One invocation, no shared/thread-local budget. This is not a connection/commit network SLA. */
final class FinancePersonDirectoryBudget {
    static final long WHOLE_NANOS = 10_000_000_000L;
    final LongSupplier clock;
    final long start;
    final FinanceStore raw;

    FinancePersonDirectoryBudget(FinanceStore raw, LongSupplier clock) {
        this.raw = raw;
        this.clock = clock;
        start = clock.getAsLong();
    }

    static FinanceException exceeded() {
        return new FinanceException(
                "PERSON_DIRECTORY_QUERY_BUDGET_EXCEEDED", "目录查询或事务预算已到期；未返回部分数据，请重新读取权威状态");
    }

    static FinanceException busy() {
        return new FinanceException("PERSON_DIRECTORY_BUSY", "目录当前被其他治理操作占用，请保留原请求键并稍后核对重试");
    }

    long remaining() {
        long elapsed = clock.getAsLong() - start;
        if (elapsed < 0 || elapsed >= WHOLE_NANOS - 1_000_000L) throw exceeded();
        return WHOLE_NANOS - elapsed;
    }

    void check() {
        remaining();
    }

    void configure() {
        long ms = Math.max(1, remaining() / 1_000_000L);
        raw.list(
                "SELECT set_config('statement_timeout',?,true)"
                    + " statement_budget,set_config('lock_timeout',?,true) lock_budget",
                Math.min(3000, ms) + "ms",
                Math.min(2000, ms) + "ms");
        check();
    }

    <T> T spi(Supplier<T> work) {
        configure();
        T v = work.get();
        check();
        return v;
    }

    static RuntimeException translate(RuntimeException failure) {
        for (Throwable t = failure; t != null; t = t.getCause())
            if (t instanceof SQLException sql && "55P03".equals(sql.getSQLState())) return busy();
        for (Throwable t = failure; t != null; t = t.getCause())
            if (t instanceof QueryTimeoutException
                    || t instanceof TransactionTimedOutException
                    || t instanceof java.sql.SQLTimeoutException
                    || t instanceof SQLException sql && "57014".equals(sql.getSQLState()))
                return exceeded();
        return failure;
    }

    static boolean budgetFailure(RuntimeException e) {
        return e instanceof FinanceException f
                && (f.code.equals("PERSON_DIRECTORY_QUERY_BUDGET_EXCEEDED")
                        || f.code.equals("PERSON_DIRECTORY_BUSY"));
    }

    FinanceStore store() {
        return new FinanceStore(raw.jdbc) {
            @Override
            List<Map<String, Object>> list(String sql, Object... args) {
                configure();
                var r = raw.list(sql, args);
                check();
                return r;
            }

            @Override
            int update(String sql, Object... args) {
                configure();
                int n = raw.update(sql, args);
                check();
                return n;
            }
        };
    }
}
