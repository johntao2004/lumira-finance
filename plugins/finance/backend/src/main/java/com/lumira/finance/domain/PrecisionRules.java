package com.lumira.finance.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/** V4 storage precision and explicit posting quantization; never rounds an API input implicitly. */
public final class PrecisionRules {
    private PrecisionRules() {}

    public record RoundingTrace(
            String sourceUnrounded,
            int minorUnit,
            RoundingMode roundingMode,
            String ruleVersion,
            BigDecimal result) {}

    public record DivisionTrace(
            String numerator,
            String denominator,
            int precision,
            RoundingMode roundingMode,
            String result) {}

    private static BigDecimal parse(String value, int precision, int scale, boolean signed) {
        if (value == null
                || value.length() > 128
                || !value.matches((signed ? "-?" : "") + "[0-9]+(\\.[0-9]{1," + scale + "})?"))
            throw new IllegalArgumentException("INVALID_DECIMAL_LITERAL");
        BigDecimal result = new BigDecimal(value).setScale(scale, RoundingMode.UNNECESSARY);
        if (result.precision() > precision) throw new IllegalArgumentException("DECIMAL_OVERFLOW");
        return result;
    }

    public static BigDecimal money(String value) {
        return parse(value, 28, 8, true);
    }

    public static BigDecimal exchangeRate(String value) {
        BigDecimal result = parse(value, 28, 12, false);
        if (result.signum() == 0)
            throw new IllegalArgumentException("EXCHANGE_RATE_MUST_BE_POSITIVE");
        return result;
    }

    public static BigDecimal ratio(String value) {
        return parse(value, 18, 12, false);
    }

    private static void minorUnit(int scale) {
        if (scale < 0 || scale > 8)
            throw new IllegalArgumentException("UNSUPPORTED_CURRENCY_MINOR_UNIT");
    }

    /** API boundary: lexical fractional digits are limited, including redundant trailing zeros. */
    public static BigDecimal formalCurrencyMoney(String value, int minorUnit) {
        minorUnit(minorUnit);
        String pattern = minorUnit == 0 ? "[0-9]+" : "[0-9]+(\\.[0-9]{1," + minorUnit + "})?";
        if (value == null || value.length() > 128 || !value.matches(pattern))
            throw new IllegalArgumentException("FORMAL_CURRENCY_PRECISION");
        return money(value);
    }

    /** Numeric boundary: DB scale padding is permitted; nonzero sub-minor-unit digits are not. */
    public static boolean isFormalCurrencyMoney(BigDecimal value, int minorUnit) {
        minorUnit(minorUnit);
        if (value == null || value.signum() < 0) return false;
        try {
            BigDecimal exact = value.setScale(minorUnit, RoundingMode.UNNECESSARY);
            money(exact.toPlainString());
            return true;
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            return false;
        }
    }

    /** Explicit approved node only; caller persists the trace and binds its rule in approval. */
    public static RoundingTrace roundForPosting(
            BigDecimal source, int minorUnit, RoundingMode mode, String ruleVersion) {
        minorUnit(minorUnit);
        if (source == null
                || source.signum() < 0
                || mode == null
                || ruleVersion == null
                || ruleVersion.isBlank())
            throw new IllegalArgumentException("ROUNDING_RULE_REQUIRED");
        BigDecimal rounded = source.setScale(minorUnit, mode);
        BigDecimal result = formalCurrencyMoney(rounded.toPlainString(), minorUnit);
        return new RoundingTrace(source.toPlainString(), minorUnit, mode, ruleVersion, result);
    }

    /** Optional working arithmetic, with an explicit context; this is not a posting amount. */
    public static DivisionTrace divide(
            BigDecimal numerator, BigDecimal denominator, int precision, RoundingMode mode) {
        if (numerator == null
                || denominator == null
                || denominator.signum() == 0
                || precision < 40
                || mode == null) throw new IllegalArgumentException("DIVISION_CONTEXT_REQUIRED");
        BigDecimal result = numerator.divide(denominator, new MathContext(precision, mode));
        return new DivisionTrace(
                numerator.toPlainString(),
                denominator.toPlainString(),
                precision,
                mode,
                result.toPlainString());
    }
}
