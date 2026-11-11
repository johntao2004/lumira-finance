package com.lumira.finance.payroll;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import static com.lumira.finance.payroll.PayrollService.*;

/** Root wires this only after Access/Evidence adapters and isolated database validation. */
public final class PayrollJdbcStore implements Store {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    public PayrollJdbcStore(JdbcClient jdbc, PlatformTransactionManager manager, ObjectMapper mapper) {
        this.jdbc = jdbc; this.transactions = new TransactionTemplate(manager); this.mapper = mapper;
    }
    @Override public String digest(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(mapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("PAYROLL_SERIALIZATION", e); }
    }
    private String json(Object value) {
        String result = mapper.writeValueAsString(value);
        if (result.getBytes(StandardCharsets.UTF_8).length > 16 * 1024 * 1024)
            throw PayrollCalculation.invalid("PAYROLL_YEAR_CAPACITY_REQUIRES_PARTITIONED_STORE");
        return result;
    }
    @Override public <T> T transaction(YearScope scope, Function<Store.Tx, T> work) {
        return transactions.execute(status -> {
            // scope originates from authenticated adapter, not an HTTP body.
            jdbc.sql("SELECT set_config('app.tenant_id', ?, true)").param(scope.tenant().toString()).query(String.class).single();
            jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")
                    .param("payroll:" + scope.tenant() + ":" + scope.entity() + ":" + scope.year()).query().listOfRows();
            return work.apply(new Session(scope));
        });
    }
    private final class Session implements Store.Tx {
        private final YearScope scope;
        Session(YearScope scope) { this.scope = scope; }
        private Object[] scopeArgs() { return new Object[]{scope.tenant(), scope.entity(), scope.year()}; }
        @Override public YearData load() {
            List<String> rows = jdbc.sql("SELECT payload::text FROM plugin_finance_payroll_year WHERE tenant_id=? AND tax_entity_id=? AND tax_year=? FOR UPDATE")
                    .params(scopeArgs()).query(String.class).list();
            return rows.isEmpty() ? new YearData(Map.of()) : mapper.readValue(rows.getFirst(), YearData.class);
        }
        @Override public Batch replay(String key, String digest) {
            var rows = jdbc.sql("SELECT digest,result::text FROM plugin_finance_payroll_receipt WHERE tenant_id=? AND tax_entity_id=? AND tax_year=? AND receipt_key=?")
                    .params(scope.tenant(), scope.entity(), scope.year(), key).query().listOfRows();
            if (rows.isEmpty()) return null;
            if (!digest.equals(rows.getFirst().get("digest"))) throw PayrollCalculation.invalid("IDEMPOTENCY_CONTENT_CONFLICT");
            return mapper.readValue((String) rows.getFirst().get("result"), Batch.class);
        }
        @Override public void save(YearData data, String key, String digest, Batch result, Actor actor, String action) {
            jdbc.sql("INSERT INTO plugin_finance_payroll_year(tenant_id,tax_entity_id,tax_year,payload) VALUES(?,?,?,?::jsonb) ON CONFLICT(tenant_id,tax_entity_id,tax_year) DO UPDATE SET payload=excluded.payload,version=plugin_finance_payroll_year.version+1")
                    .params(scope.tenant(), scope.entity(), scope.year(), json(data)).update();
            jdbc.sql("INSERT INTO plugin_finance_payroll_receipt(tenant_id,tax_entity_id,tax_year,receipt_key,digest,result) VALUES(?,?,?,?,?,?::jsonb)")
                    .params(scope.tenant(), scope.entity(), scope.year(), key, digest, json(result)).update();
            jdbc.sql("INSERT INTO plugin_finance_payroll_event(tenant_id,tax_entity_id,tax_year,batch_id,batch_version,actor_account,actor_person,actor_binding,action,payload_digest) VALUES(?,?,?,?,?,?,?,?,?,?)")
                    .params(scope.tenant(), scope.entity(), scope.year(), result.draft().id(), result.version(), actor.account(), actor.person(), actor.binding(), action, digest(result)).update();
            jdbc.sql("INSERT INTO plugin_finance_payroll_outbox(tenant_id,tax_entity_id,tax_year,batch_id,batch_version,action,payload_digest) VALUES(?,?,?,?,?,?,?)")
                    .params(scope.tenant(), scope.entity(), scope.year(), result.draft().id(), result.version(), action, digest(result)).update();
        }
    }
}
