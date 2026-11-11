package com.lumira.finance;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

public final class FinanceException extends ResponseStatusException {
    public final String code;
    public final List<Map<String, Object>> issues;

    public FinanceException(String code, String message) {
        this(code, message, List.of());
    }

    public FinanceException(String code, String message, List<Map<String, Object>> issues) {
        super(
                switch (code) {
                    case "SCOPE_DENIED",
                            "SELF_APPROVAL_FORBIDDEN",
                            "JOURNAL_PERSON_MAKER_ONLY",
                            "ASSET_SCOPE_DENIED",
                            "ASSET_SELF_APPROVAL_FORBIDDEN",
                            "ASSET_MAKER_ONLY",
                            "ASSET_INSUFFICIENT_ROLE",
                            "PERSON_AUTH_ASSURANCE_REQUIRED" ->
                            HttpStatus.FORBIDDEN;
                    case "ASSET_NOT_FOUND", "ASSET_PLAN_NOT_FOUND" -> HttpStatus.NOT_FOUND;
                    case "LIST_BASELINE_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
                    case "PERSON_DIRECTORY_QUERY_BUDGET_EXCEEDED",
                            "TAX_ENTITY_QUERY_BUDGET_EXCEEDED",
                            "TAX_ENTITY_LIMIT_EXCEEDED",
                            "AP_QUERY_BUDGET_EXCEEDED",
                            "AP_LIMIT_EXCEEDED",
                            "AP_SOURCE_UNSUPPORTED",
                            "AR_QUERY_BUDGET_EXCEEDED",
                            "AR_LIMIT_EXCEEDED",
                            "AR_ALLOCATION_TOTAL_MISMATCH",
                            "QUERY_BUDGET_EXCEEDED",
                            "COUNTERPARTY_QUERY_BUDGET_EXCEEDED",
                            "COUNTERPARTY_LIMIT_EXCEEDED",
                            "LIST_VALUE_UNSUPPORTED",
                            "CARRY_READINESS_LIMIT_EXCEEDED" ->
                            HttpStatus.UNPROCESSABLE_ENTITY;
                    case "PREVIEW_LIMIT_EXCEEDED", "PREVIEW_TOTAL_OVERFLOW" ->
                            HttpStatus.UNPROCESSABLE_ENTITY;
                    case "PROFIT_CLOSING_SOURCE_UNSUPPORTED",
                            "PROFIT_CLOSING_LIMIT_EXCEEDED",
                            "ARCHIVE_METADATA_LIMIT",
                            "ARCHIVE_EMPTY_ORIGINAL",
                            "ARCHIVE_ORIGINAL_HASH_MISMATCH",
                            "ARCHIVE_COVERAGE_INCOMPLETE",
                            "ARCHIVE_INTEGRITY_FAILED",
                            "ARCHIVE_DIGEST_MISMATCH",
                            "REPORT_SEMANTIC_BLOCKED" ->
                            HttpStatus.UNPROCESSABLE_ENTITY;
                    case "JOURNAL_PERSON_MODE_REQUIRED",
                            "IDEMPOTENCY_KEY_REUSED_DIFFERENT_PAYLOAD",
                            "ASSET_IDEMPOTENCY_CONFLICT",
                            "ASSET_VERSION_CONFLICT",
                            "ASSET_RESERVATION_CONFLICT",
                            "ASSET_ALREADY_DISPOSED",
                            "ASSET_NOT_IN_USE",
                            "ASSET_PERIOD_NOT_SEQUENTIAL",
                            "ASSET_PERIOD_ALREADY_PROCESSED",
                            "ASSET_APPROVAL_EXPIRED",
                            "ASSET_PLAN_STATE_INVALID",
                            "ASSET_REVERSAL_ORDER_INVALID",
                            "ASSET_ALREADY_REVERSED",
                            "ASSET_EVIDENCE_UNVERIFIED",
                            "ASSET_TAX_BASIS_UNVERIFIED",
                            "ASSET_ACCOUNT_INVALID",
                            "ASSET_LEDGER_MISMATCH",
                            "ASSET_IMMUTABLE",
                            "TAX_ENTITY_IDENTITY_EXISTS",
                            "TAX_ENTITY_STATE_CONFLICT",
                            "TAX_ENTITY_SOURCE_STALE",
                            "TAX_ENTITY_APPROVAL_STALE",
                            "TAX_ENTITY_PAGE_STALE",
                            "TAX_ENTITY_BUSY",
                            "AR_DEPENDENCY_UNAVAILABLE",
                            "AR_DEPENDENCY_PRESENT",
                            "AR_STATE_CONFLICT",
                            "AR_CAPACITY_EXCEEDED",
                            "AR_CUSTOMER_STALE",
                            "AR_BUSINESS_IDENTITY_CONFLICT",
                            "AR_SOURCE_UNAVAILABLE",
                            "AR_PENDING_CORRECTION",
                            "AR_SOURCE_DATE_AFTER_EFFECT",
                            "AR_UNSUPPORTED_CORRECTION",
                            "AR_FULL_REVERSAL_REQUIRED",
                            "AR_DECISION_UNAVAILABLE",
                            "AR_DECISION_MISMATCH",
                            "AR_UNBALANCED_PLAN",
                            "AR_TAX_FACT_UNAVAILABLE",
                            "AR_CONTRACT_ASSET_UNSUPPORTED",
                            "AR_BANK_CHANNEL_UNVERIFIED",
                            "AR_ACCOUNT_ROLE_MISMATCH",
                            "AR_SOURCE_STALE",
                            "AR_APPROVAL_EXPIRED",
                            "AR_REVERSAL_LINES_MISMATCH",
                            "AR_POSTING_PROOF_MISSING",
                            "AR_PERFORMANCE_NOT_OCCURRED",
                            "AP_SOURCE_STALE",
                            "AP_SUPPLIER_STALE",
                            "AP_BASIS_STALE",
                            "AP_BASIS_PENDING",
                            "AP_BASIS_UNAVAILABLE",
                            "AP_ACTIVE_RESERVATIONS",
                            "AP_CAPACITY_BELOW_COMMITTED",
                            "AP_CAPACITY_EXCEEDED",
                            "AP_PARENT_IN_USE",
                            "AP_PENDING_PARENT_REVERSAL",
                            "AP_STATE_CONFLICT",
                            "AP_APPROVAL_STALE",
                            "AP_APPROVAL_EXPIRED",
                            "AP_SUBMISSION_EXPIRED",
                            "AP_SOURCE_ALREADY_CLAIMED",
                            "AP_DEPENDENCY_PRESENT",
                            "AP_PAGE_STALE",
                            "AP_OBSERVATION_UNAVAILABLE",
                            "AP_BUSY",
                            "CARRY_READINESS_STALE",
                            "CARRY_READINESS_OBSERVATION_UNAVAILABLE",
                            "COUNTERPARTY_IDENTITY_EXISTS",
                            "COUNTERPARTY_HOME_SCOPE_REQUIRED",
                            "COUNTERPARTY_STATE_CONFLICT",
                            "COUNTERPARTY_APPROVAL_STALE",
                            "COUNTERPARTY_PAGE_STALE",
                            "COUNTERPARTY_IDENTITY_UNAVAILABLE",
                            "COUNTERPARTY_OBSERVATION_UNAVAILABLE",
                            "COUNTERPARTY_BUSY",
                            "PROFIT_CLOSING_SOURCE_STALE",
                            "PROFIT_CLOSING_SOURCE_ALREADY_CLAIMED",
                            "PROFIT_CLOSING_RUN_PENDING",
                            "PROFIT_CLOSING_NO_MOVEMENT",
                            "PROFIT_CLOSING_APPROVAL_STALE",
                            "PROFIT_CLOSING_APPROVAL_EXPIRED",
                            "PROFIT_CLOSING_STATE_CONFLICT",
                            "PROFIT_CLOSING_REVERSAL_DEPENDENCY",
                            "PROFIT_CLOSING_PAGE_STALE",
                            "EVIDENCE_HOME_SCOPE_UNSUPPORTED",
                            "EVIDENCE_IDENTITY_CONFLICT",
                            "EVIDENCE_BASIS_UNAVAILABLE",
                            "EVIDENCE_BASIS_REVIEW_PENDING",
                            "EVIDENCE_BASIS_STALE",
                            "EVIDENCE_ACTIVE_RESERVATIONS",
                            "EVIDENCE_CAPACITY_BELOW_COMMITTED",
                            "EVIDENCE_CAPACITY_EXCEEDED",
                            "EVIDENCE_SUBMISSION_EXPIRED",
                            "EVIDENCE_CONSUMER_STATE_CONFLICT",
                            "EVIDENCE_REVERSE_EXCEEDS_REMAINING",
                            "EVIDENCE_REVIEW_EXPIRED",
                            "EVIDENCE_PAGE_STALE",
                            "JOURNAL_PERSON_YEAR_NOT_EMPTY",
                            "JOURNAL_PERSON_MODE_STALE",
                            "JOURNAL_PERSON_IDENTITY_UNAVAILABLE",
                            "JOURNAL_PERSON_APPROVAL_STALE",
                            "JOURNAL_PERSON_APPROVAL_EXPIRED",
                            "JOURNAL_PERSON_YEAR_CLOSE_UNSUPPORTED",
                            "PERSON_DIRECTORY_BUSY",
                            "PERSON_DIRECTORY_UNAVAILABLE",
                            "PERSON_DIRECTORY_PAGE_STALE",
                            "PERSON_BINDING_CONFLICT",
                            "PERSON_PROPOSAL_STALE",
                            "PERSON_PROPOSAL_EXPIRED",
                            "PERSON_SEED_PROTECTED",
                            "VERSION_CONFLICT",
                            "LIST_PAGE_STALE",
                            "CASH_COVERAGE_SCOPE_UNAVAILABLE",
                            "CASH_COVERAGE_PREVIEW_STALE",
                            "CASH_ALLOCATION_PARENT_STALE",
                            "CASH_ALLOCATION_PARENT_RELEASE_PENDING",
                            "CASH_ALLOCATION_POLICY_UNAVAILABLE",
                            "CASH_ALLOCATION_SUM_MISMATCH",
                            "CASH_ALLOCATION_PENDING_EXISTS",
                            "CASH_ALLOCATION_ALREADY_EFFECTIVE",
                            "CASH_ALLOCATION_STATE_CONFLICT",
                            "CASH_ALLOCATION_SUBMISSION_EXPIRED",
                            "CASH_ALLOCATION_RELEASE_PENDING",
                            "CASH_ALLOCATION_REPLACEMENT_PENDING",
                            "CASH_ALLOCATION_REPLACEMENT_REQUIRED",
                            "CASH_ALLOCATION_IN_USE",
                            "CASH_REVIEW_CLAIM_STALE",
                            "CASH_REVIEW_PENDING_WITHDRAWAL",
                            "CASH_REVIEW_ALREADY_EFFECTIVE",
                            "CASH_REVIEW_PENDING_EXISTS",
                            "CASH_REVIEW_SUBMISSION_EXPIRED",
                            "CASH_REVIEW_STATE_CONFLICT",
                            "CASH_REVIEW_RELEASE_PENDING",
                            "CASH_REVIEW_IN_USE",
                            "CASH_POLICY_SUBMISSION_EXPIRED",
                            "CASH_POLICY_OVERLAP",
                            "CASH_POLICY_STATE_CONFLICT",
                            "CASH_POLICY_RETIREMENT_PENDING",
                            "CASH_MATCH_RAW_NOT_VOIDED",
                            "CASH_MATCH_SOURCE_CHANGED",
                            "CASH_MATCH_TARGET_INVALID",
                            "CASH_MATCH_TARGET_UNCHANGED",
                            "CASH_MATCH_PENDING_EXISTS",
                            "CASH_MATCH_EFFECTIVE_EXISTS",
                            "CASH_MATCH_GENERATION_STALE",
                            "CASH_MATCH_WITHDRAWAL_PENDING",
                            "CASH_SOURCE_DUPLICATE_CONFLICT",
                            "SOURCE_ALREADY_VOIDED",
                            "CASH_LEG_ALREADY_REGISTERED",
                            "CASH_MOVEMENT_MATCH_INVALID",
                            "CASH_MOVEMENT_SCOPE_STALE",
                            "CASH_REGISTRATION_IN_USE",
                            "CASH_SCOPE_STALE",
                            "CASH_SCOPE_CHANGE_UNSUPPORTED",
                            "CASH_SCOPE_REVIEW_REQUIRED",
                            "SOURCE_PAYLOAD_CONFLICT",
                            "EFFECT_PAYLOAD_CONFLICT",
                            "SOURCE_SCOPE_CONFLICT",
                            "SOURCE_INTEGRITY_FAILED",
                            "OUTBOX_INTEGRITY_FAILED",
                            "OUTBOX_LEASE_ACTIVE",
                            "OUTBOX_RETRY_EXHAUSTED",
                            "ASYNC_REBUILD_REQUIRED",
                            "AUDIT_INTEGRITY_FAILED",
                            "LEGACY_PRECISION_REVIEW_REQUIRED",
                            "IDEMPOTENCY_CONFLICT",
                            "PERIOD_CLOSED",
                            "APPROVAL_STALE" ->
                            HttpStatus.CONFLICT;
                    default ->
                            code != null
                                            && !code.endsWith("_VALIDATION_FAILED")
                                            && !code.endsWith("_INVALID_JSON")
                                            && !code.endsWith("_INVALID_INPUT")
                                            && (code.startsWith("ASSET_")
                                                    || code.startsWith("PAYROLL_")
                                                    || code.startsWith("TAX_")
                                                    || code.startsWith("ARCHIVE_")
                                                    || code.startsWith("REPORT_")
                                                    || Set.of(
                                                                    "STALE_PREDECESSOR",
                                                                    "DRAFT_ALREADY_EXISTS",
                                                                    "APPROVAL_EXPIRED",
                                                                    "EXPORT_STALE")
                                                            .contains(code))
                                    ? HttpStatus.CONFLICT
                                    : HttpStatus.BAD_REQUEST;
                },
                message);
        this.code = code;
        this.issues = issues.stream().map(Map::copyOf).toList();
    }

    static FinanceException invalid(String message) {
        return new FinanceException("INVALID_INPUT", message);
    }
}
