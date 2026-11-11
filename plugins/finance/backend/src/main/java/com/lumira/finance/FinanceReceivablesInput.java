package com.lumira.finance;

import java.util.*;
import static com.lumira.finance.FinanceReceivablesRules.*;

/** Closed command schema; server-derived identity, scope, tax and accounts are never body fields. */
final class FinanceReceivablesInput {
    private FinanceReceivablesInput() {}
    static Map<String, Object> normalize(String action, Map<String, Object> raw) {
        require(raw != null, "INVALID_INPUT");
        var out = new LinkedHashMap<String, Object>();
        out.put("idempotencyKey", text(raw.get("idempotencyKey"), 128));
        if (!action.equals("create")) {
            fields(raw, Set.of("idempotencyKey", "expectedVersion", "reason"));
            require(Set.of("submit", "approve", "post", "withdraw", "reject", "cancel").contains(action), "INVALID_INPUT");
            out.put("expectedVersion", Long.toString(version(raw.get("expectedVersion"))));
            out.put("reason", text(raw.get("reason"), 1000));
            return out;
        }
        Kind kind;
        try { kind = Kind.valueOf(text(raw.get("kind"), 30)); }
        catch (IllegalArgumentException ex) { throw new Rejected("INVALID_INPUT"); }
        var keys = new HashSet<>(Set.of("idempotencyKey", "operationId", "kind", "customerId",
                "customerRevisionId", "originalId", "reason", "businessKey", "date", "amount"));
        keys.addAll(switch (kind) {
            case CONTRACT -> Set.of("terms");
            case PERFORMANCE -> Set.of("contractId", "performanceRule", "acceptedOn");
            case RECOGNITION -> Set.of("performanceId", "decisionId", "dueDate");
            case RECEIPT -> Set.of("receiptDecisionId", "bankAccountReference", "receiptReference");
            case ALLOCATION -> Set.of("receiptId", "items", "allocationDecisionId");
            case REVERSAL -> Set.of("originalObjectId", "correctionDecisionId");
        });
        fields(raw, keys);
        out.put("kind", kind.name());
        for (String key : List.of("operationId", "customerId", "customerRevisionId", "originalId"))
            out.put(key, uuid(raw.get(key)).toString());
        out.put("businessKey", text(raw.get("businessKey"), 160));
        out.put("reason", text(raw.get("reason"), 1000));
        out.put("date", date(raw.get("date")).toString());
        // Canonical representation is two decimals only after validating the raw input.
        out.put("amount", money(raw.get("amount"), false).setScale(2).toPlainString());
        for (String key : keys) {
            if (out.containsKey(key)) continue;
            if (key.endsWith("Id")) out.put(key, uuid(raw.get(key)).toString());
            else if (key.equals("dueDate") || key.equals("acceptedOn")) out.put(key, date(raw.get(key)).toString());
            else if (key.equals("items")) {
                require(raw.get(key) instanceof List<?>, "INVALID_INPUT");
                var list = (List<?>) raw.get(key);
                require(!list.isEmpty() && list.size() <= 50, "AR_LIMIT_EXCEEDED");
                var items = new TreeMap<String, Map<String, Object>>();
                java.math.BigDecimal total = java.math.BigDecimal.ZERO;
                for (Object item : list) {
                    require(item instanceof Map<?, ?>, "INVALID_INPUT");
                    var m = (Map<?, ?>) item;
                    require(m.keySet().equals(Set.of("recognitionId", "amount")), "INVALID_INPUT");
                    String id = uuid(m.get("recognitionId")).toString();
                    var value = money(m.get("amount"), false);
                    require(items.put(id, Map.of("recognitionId", id, "amount", value.setScale(2).toPlainString())) == null,
                            "AR_DUPLICATE_ITEM");
                    total = total.add(value);
                }
                require(total.compareTo(money(raw.get("amount"), false)) == 0, "AR_ALLOCATION_TOTAL_MISMATCH");
                out.put(key, List.copyOf(items.values()));
            } else out.put(key, text(raw.get(key), key.equals("terms") ? 4000 : 1000));
        }
        if (kind == Kind.PERFORMANCE)
            require(!date(out.get("acceptedOn")).isAfter(date(out.get("date"))), "AR_PERFORMANCE_NOT_OCCURRED");
        if (kind == Kind.RECOGNITION)
            require(!date(out.get("dueDate")).isBefore(date(out.get("date"))), "INVALID_INPUT");
        return out;
    }
    static void fields(Map<String, Object> raw, Set<String> keys) {
        require(raw.keySet().equals(keys), "INVALID_INPUT");
    }
}
