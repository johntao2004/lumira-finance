package com.lumira.finance.fapiao;

/**
 * FP03: Red-letter confirmation notice lifecycle status.
 */
public enum RedNoticeStatus {
    DRAFT,
    WAITING_CONFIRMATION,
    CONFIRMED,
    EXPIRED,
    CANCELLED,
    RED_INVOICE_ISSUED
}
