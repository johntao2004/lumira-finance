package com.lumira.finance.fapiao;

/**
 * FP02: Output fapiao issuance application status.
 */
public enum IssuanceAppStatus {
    DRAFT,
    SUBMITTED,
    APPROVED,
    WAITING_CHANNEL,
    ISSUED_MANUAL_CONFIRMED,
    REJECTED,
    CANCELLED
}
