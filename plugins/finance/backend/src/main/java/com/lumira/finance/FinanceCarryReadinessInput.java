package com.lumira.finance;

import jakarta.servlet.http.HttpServletRequest;

import java.util.*;

final class FinanceCarryReadinessInput {
    static final String SCHEMA = "carry-readiness/1";

    record Query(UUID target, String observation, String filter, int limit, String cursor) {}

    static Query parse(String op, HttpServletRequest request) {
        boolean page = op.equals("accounts");
        if (!page && !op.equals("summary")) throw FinanceException.invalid("未知预检操作");
        var q =
                FinanceEvidenceAccess.query(
                        request,
                        page
                                ? Set.of(
                                        "targetYearId",
                                        "observationToken",
                                        "filter",
                                        "limit",
                                        "cursor")
                                : Set.of("targetYearId"));
        UUID target = uuid(q.get("targetYearId"));
        String observation = page ? bounded(q.get("observationToken")) : null;
        String filter = q.getOrDefault("filter", "ALL");
        if (!Set.of("ALL", "NONZERO").contains(filter)) throw FinanceException.invalid("账户筛选无效");
        int limit = 20;
        if (q.containsKey("limit")) {
            String literal = q.get("limit");
            if (!literal.matches("[1-9][0-9]{0,2}")) throw FinanceException.invalid("分页数量无效");
            limit = Integer.parseInt(literal);
            if (limit > 100) throw FinanceException.invalid("分页最多100项");
        }
        return new Query(
                target,
                observation,
                filter,
                limit,
                q.containsKey("cursor") ? bounded(q.get("cursor")) : null);
    }

    static UUID uuid(Object value) {
        try {
            return FinancePersonDirectory.uuid(value);
        } catch (IllegalArgumentException bad) {
            throw FinanceException.invalid("UUID须为规范小写完整值");
        }
    }

    static String bounded(String value) {
        if (value == null
                || value.isEmpty()
                || value.length() > 4096
                || !value.matches("[A-Za-z0-9_-]+")) throw FinanceException.invalid("观察或分页token无效");
        return value;
    }
}
