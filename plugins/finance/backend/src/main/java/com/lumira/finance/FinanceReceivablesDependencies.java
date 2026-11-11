package com.lumira.finance;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * Mandatory integration boundary. Implementations use the SAME local transaction and trusted
 * server records. No network calls, REQUIRES_NEW, caller-supplied verification flags or mock defaults.
 * No bean is supplied by this candidate; missing dependencies fail closed before financial writes.
 */
public interface FinanceReceivablesDependencies {
    record Scope(UUID tenantId, UUID legalEntityId, UUID bookId, UUID yearId, String accountingStandard) {}
    record Actor(UUID accountId, UUID personId, UUID bindingId) {}
    enum TaxTreatment { TAXABLE, EXEMPT, ZERO_RATED, OUTSIDE_SCOPE }
    record TaxFact(UUID decisionId, long version, UUID taxEntityId, UUID taxEntityRevisionId,
            TaxTreatment treatment, BigDecimal taxAmount, String ruleVersion, UUID basisOriginalId,
            UUID approvedByPersonId, String digest) {}
    enum AccountRole { RECEIVABLE, REVENUE, OUTPUT_TAX, CASH, CUSTOMER_ADVANCE }
    record Line(UUID accountId, long accountVersion, AccountRole role, BigDecimal debit, BigDecimal credit) {}
    record Plan(UUID decisionId, long decisionVersion, UUID approvedByPersonId, Instant expiresAt,
            String decisionDigest, String policyVersion, String nativeType, LocalDate accountingDate,
            BigDecimal gross, BigDecimal net, TaxFact taxFact, List<Line> lines,
            Map<String, Object> immutableDependencies) {
        public Plan { lines = List.copyOf(lines); immutableDependencies = Map.copyOf(immutableDependencies); }
    }
    record Producer(UUID id, UUID businessChainId, UUID sourceObjectId, String stableConsumer,
            String effectKey, String contentDigest, String nativeType, Plan plan,
            Actor preparedBy, Actor approvedBy, UUID reversesJournalId) {}
    record Posted(UUID journalId, UUID businessEffectId, UUID consumptionReceiptId,
            UUID outboxId, long postingSequence, String contentDigest, String nativeType) {}

    /** Resolve IDs from immutable policy/tax/accounting decisions; verify all source scope and facts. */
    Plan resolve(Scope scope, FinanceReceivablesRules.Kind kind, Map<String, Object> request,
            Map<String, Object> verifiedSources);

    /** Revalidate decision signatures/versions, permissions, mapped accounts, source watermarks, tax
     * identity and evidence reservations under acquired locks at submit/approve/post, including replay. */
    void revalidate(Scope scope, Plan plan, Map<String, Object> verifiedSources);

    /** Create native journal, approval/content protocol, posting, effect, receipt, audit and outbox
     * atomically. Stable key must not vary with handler version/request key. Never returns before post. */
    Posted post(Scope scope, Producer producer, Actor poster);

    /** Read actual database journal/lines/posting/effect/receipt/outbox, verify them against producer.
     * Throw on absent/mismatched data. A response object alone is not sufficient proof. */
    void verifyPosted(Scope scope, Producer producer, Posted posted);
}
