package com.lumira.finance;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public class FinanceStore {
    final JdbcClient jdbc;

    public FinanceStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<Map<String, Object>> list(String sql, Object... args) {
        return jdbc.sql(sql)
                .params(args)
                .query(
                        (rs, n) -> {
                            Map<String, Object> row = new LinkedHashMap<>();
                            var md = rs.getMetaData();
                            for (int i = 1; i <= md.getColumnCount(); i++) {
                                String key = md.getColumnLabel(i);
                                Object val = rs.getObject(i);
                                row.put(
                                        key,
                                        val instanceof java.math.BigDecimal d
                                                ? d.toPlainString()
                                                : val instanceof java.sql.Date date
                                                        ? date.toLocalDate().toString()
                                                        : val);
                            }
                            return row;
                        })
                .list();
    }

    Map<String, Object> one(String sql, Object... args) {
        var rows = list(sql, args);
        if (rows.isEmpty()) throw new FinanceException("SCOPE_DENIED", "范围不可访问");
        return rows.getFirst();
    }

    int update(String sql, Object... args) {
        return jdbc.sql(sql).params(args).update();
    }
}
