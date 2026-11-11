package com.lumira.finance.assets;

import static com.lumira.finance.assets.AssetModel.*;
import java.sql.Connection;
import java.math.BigDecimal;
import java.util.*;

/** Mandatory trusted adapters: never take actor, qualification or authorization from a JSON body. */
public final class AssetPorts {
    private AssetPorts() {}
    public interface Guard {
        /** Resolve identity afresh; check action, tenant/book/year, client; lock book/year/period in that order.
         * For reads authorize and use a repeatable read snapshot; do not require an OPEN year for reads.
         * For reversals lock source/target years in canonical order before any asset lock. */
        Context enter(Connection connection, String principal, Scope scope, String action, UUID sourcePlan) throws Exception;
        /** Recheck maker/reviewer natural-person binding, current roles and every account mapping.
         * Verify policy assessment id and digest against independently approved immutable evidence.
         * Verify SMALL/CAS equals authoritative book framework. Disallow unsupported impairment/R&D/CIP. */
        void validate(Connection connection, Context context, Plan plan, Actor approver) throws Exception;
    }
    public interface Evidence {
        /** Lock independently approved ACCOUNTING_CONFIRMATION source and reserve exact net amount.
         * Same document already consumed by AP or another acquisition must not be consumed twice. */
        void reserve(Connection connection, Context context, UUID sourceId, UUID plan, BigDecimal amount) throws Exception;
        void consume(Connection connection, Context context, UUID sourceId, UUID plan, BigDecimal amount) throws Exception;
        void release(Connection connection, Context context, UUID sourceId, UUID plan) throws Exception;
        /** Append compensating accounting-confirmation release linked to original posting. */
        void reverse(Connection connection, Context context, UUID originalPlan, UUID reversalPlan) throws Exception;
    }
    public interface Posting {
        /** Produce actual journal, approval, balanced lines, posting sequence, audit and outbox atomically
         * on this connection. Must enforce current journal/person protocol and use supplied nonnegative
         * line directions exactly. An external request, draft journal or independent transaction is invalid. */
        PostReceipt post(Connection connection, Context context, StoredPlan approved) throws Exception;
    }
    public record LedgerBalance(UUID account, BigDecimal debitNet, String watermark) {}
    public interface Ledger {
        /** Authoritative total including manual entries, opening, adjustments and asset producer facts.
         * Lock/read the same consistent snapshot. Never derive this from asset events themselves. */
        List<LedgerBalance> balances(Connection connection, Context context, Set<UUID> mappedAccounts) throws Exception;
    }
}
