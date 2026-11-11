package com.lumira.finance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Exact CNY and state rules. No invoice/receipt-based inference of revenue or tax. */
public final class FinanceReceivablesRules {
    private FinanceReceivablesRules() {}

    public enum Kind { CONTRACT, PERFORMANCE, RECOGNITION, RECEIPT, ALLOCATION, REVERSAL }
    public enum State { DRAFT, SUBMITTED, APPROVED, CONFIRMED, POSTED, CANCELLED }
    public enum Purpose { PERFORMANCE, ACCOUNTING_CONFIRMATION, SETTLEMENT, RECEIPT_ALLOCATION }

    public static final class Rejected extends IllegalArgumentException {
        public final String code;
        Rejected(String code) { super(code); this.code = code; }
    }

    static void require(boolean condition, String code) {
        if (!condition) throw new Rejected(code);
    }

    public static String text(Object input, int maximum) {
        require(input instanceof String, "INVALID_INPUT");
        String value = (String) input;
        require(!value.isBlank() && value.length() <= maximum && value.equals(value.strip())
                && value.chars().noneMatch(c -> Character.isISOControl(c)), "INVALID_INPUT");
        return value;
    }

    public static UUID uuid(Object input) {
        String value = text(input, 36);
        require(value.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"), "INVALID_INPUT");
        return UUID.fromString(value);
    }

    public static LocalDate date(Object input) {
        String value = text(input, 10);
        require(value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"), "INVALID_INPUT");
        try { return LocalDate.parse(value); }
        catch (java.time.DateTimeException e) { throw new Rejected("INVALID_INPUT"); }
    }

    public static BigDecimal money(Object input, boolean zeroAllowed) {
        String value = text(input, 23);
        require(value.matches("(?:0|[1-9][0-9]{0,19})(?:\\.[0-9]{1,2})?"), "INVALID_CURRENCY_SCALE");
        BigDecimal amount = new BigDecimal(value);
        require(zeroAllowed ? amount.signum() >= 0 : amount.signum() > 0, "INVALID_INPUT");
        return amount.setScale(8);
    }

    public static String stored(BigDecimal value) {
        require(value.signum() >= 0 && value.precision() - value.scale() <= 20, "AMOUNT_OVERFLOW");
        return value.setScale(8, java.math.RoundingMode.UNNECESSARY).toPlainString();
    }

    public static long version(Object value) {
        String raw = text(value, 19);
        require(raw.matches("[1-9][0-9]{0,18}"), "INVALID_INPUT");
        try { return Long.parseLong(raw); }
        catch (NumberFormatException e) { throw new Rejected("INVALID_INPUT"); }
    }

    public static State transition(Kind kind, State state, String action) {
        return switch (action) {
            case "submit" -> { require(state == State.DRAFT, "AR_STATE_CONFLICT"); yield State.SUBMITTED; }
            case "approve" -> {
                require(state == State.SUBMITTED, "AR_STATE_CONFLICT");
                yield kind == Kind.CONTRACT || kind == Kind.PERFORMANCE ? State.CONFIRMED : State.APPROVED;
            }
            case "post" -> { require(state == State.APPROVED, "AR_STATE_CONFLICT"); yield State.POSTED; }
            case "withdraw", "reject" -> {
                require(state == State.SUBMITTED || state == State.APPROVED, "AR_STATE_CONFLICT");
                yield State.DRAFT;
            }
            case "cancel" -> {
                require(state == State.DRAFT || state == State.SUBMITTED || state == State.APPROVED,
                        "AR_STATE_CONFLICT"); yield State.CANCELLED;
            }
            default -> throw new Rejected("INVALID_INPUT");
        };
    }

    public static String aging(LocalDate due, LocalDate asOf, BigDecimal outstanding) {
        require(outstanding.signum() >= 0, "AR_CAPACITY_EXCEEDED");
        if (outstanding.signum() == 0) return "SETTLED";
        long days = ChronoUnit.DAYS.between(due, asOf);
        return days <= 0 ? "NOT_DUE" : days <= 30 ? "DAYS_1_30" : days <= 60 ? "DAYS_31_60"
                : days <= 90 ? "DAYS_61_90" : days <= 180 ? "DAYS_91_180" : "DAYS_181_PLUS";
    }

    public static void independent(UUID preparer, UUID approver) {
        require(preparer != null && approver != null && !preparer.equals(approver), "SELF_APPROVAL_FORBIDDEN");
    }

    public static BigDecimal remaining(BigDecimal capacity, Collection<BigDecimal> usages) {
        BigDecimal used = usages.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        require(used.signum() >= 0 && used.compareTo(capacity) <= 0, "AR_CAPACITY_EXCEEDED");
        return capacity.subtract(used).setScale(8);
    }
}
