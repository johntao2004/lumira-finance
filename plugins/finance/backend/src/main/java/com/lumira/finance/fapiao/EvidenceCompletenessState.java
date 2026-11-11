package com.lumira.finance.fapiao;

/**
 * EV01: Evidence completeness state machine.
 */
public enum EvidenceCompletenessState {
    RECEIVED,
    PENDING_VERIFICATION,
    PENDING_COMPLETION,
    USABLE,
    REPLACED
}
