package com.lumira.finance.fapiao;

/**
 * FP01: Fapiao pool lifecycle status.
 */
public enum FapiaoLifecycleStatus {
    UNVERIFIED_LOCAL,
    MATCHED,
    CERTIFIED_DECLARED,
    TRANSFERRED_OUT,
    VOIDED,
    RED_OFFSET
}
