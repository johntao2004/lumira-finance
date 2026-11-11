package com.lumira.finance.subledgerext;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.*;

/**
 * Pure domain rules, standard validations (CAS vs SMALL), segregation of duties,
 * variance decomposition, and credit loss / allowance calculations.
 */
public final class SubledgerExtRules {
    private SubledgerExtRules() {}

    public enum AccountingStandard { CAS, SMALL }

    public enum PoStatus { DRAFT, SUBMITTED, APPROVED, CANCELLED, CLOSED }
    public enum AcceptanceStatus { SUBMITTED, ACCEPTED, CANCELLED }
    public enum InvoiceStatus { UNINVOICED, PARTIALLY_INVOICED, FULLY_INVOICED }
    public enum ProvisionalStatus { NONE, PROVISIONALLY_ACCRUED, REVERSED, SETTLED }

    public enum ConditionStatus { CONDITIONAL, FULFILLED, WRITTEN_OFF }
    public enum ContractAssetStatus { DRAFT, SUBMITTED, APPROVED, POSTED, RECLASSIFIED }

    public enum VariableKind { BONUS, REBATE, PENALTY, CONCESSION }
    public enum VariableStatus { PENDING_APPROVAL, APPROVED, REVISED, REJECTED }

    public enum MultiYearStatus { ACTIVE, COMPLETED, SUSPENDED }

    public enum AgingBucket { NOT_DUE, DAYS_1_30, DAYS_31_60, DAYS_61_90, DAYS_91_180, DAYS_181_PLUS }
    public enum EclAssessmentType { AGING_BUCKET, INDIVIDUAL_ASSESSMENT }
    public enum EclOperationKind { PROVISION, REVERSAL, WRITE_OFF, WRITE_OFF_RECOVERY }
    public enum EclStatus { DRAFT, SUBMITTED, APPROVED, POSTED, REJECTED }

    public enum AllowanceType { COMMERCIAL_DISCOUNT, SALES_ALLOWANCE, SALES_RETURN }
    public enum AllowanceStatus { DRAFT, SUBMITTED, APPROVED, POSTED, REJECTED }

    public static final class SubledgerException extends RuntimeException {
        public final String code;
        public SubledgerException(String code, String message) {
            super(message != null ? message : code);
            this.code = code;
        }
        public SubledgerException(String code) {
            this(code, code);
        }
    }

    public static void require(boolean condition, String code) {
        if (!condition) throw new SubledgerException(code);
    }

    public static void require(boolean condition, String code, String message) {
        if (!condition) throw new SubledgerException(code, message);
    }

    public static String text(Object input, int maxLen) {
        require(input instanceof String, "INVALID_INPUT", "Expected string input");
        String s = ((String) input).strip();
        require(!s.isBlank() && s.length() <= maxLen, "INVALID_INPUT", "Text length invalid");
        require(s.chars().noneMatch(Character::isISOControl), "INVALID_INPUT", "Control characters forbidden");
        return s;
    }

    public static UUID uuid(Object input) {
        if (input instanceof UUID u) return u;
        String s = text(input, 36);
        require(s.matches("^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$"), "INVALID_INPUT", "Malformed UUID");
        return UUID.fromString(s);
    }

    public static LocalDate date(Object input) {
        if (input instanceof LocalDate d) return d;
        String s = text(input, 10);
        require(s.matches("^\\d{4}-\\d{2}-\\d{2}$"), "INVALID_INPUT", "Date format must be YYYY-MM-DD");
        try {
            return LocalDate.parse(s);
        } catch (Exception ex) {
            throw new SubledgerException("INVALID_INPUT", "Invalid date value");
        }
    }

    public static BigDecimal money(Object input, boolean allowZero) {
        String s = text(input, 25);
        require(s.matches("^(?:0|[1-9]\\d{0,19})(?:\\.\\d{1,2})?$"), "INVALID_CURRENCY_SCALE", "Money must have at most 2 decimal places");
        BigDecimal val = new BigDecimal(s).setScale(8, RoundingMode.UNNECESSARY);
        if (allowZero) {
            require(val.signum() >= 0, "INVALID_INPUT", "Money cannot be negative");
        } else {
            require(val.signum() > 0, "INVALID_INPUT", "Money must be strictly positive");
        }
        return val;
    }

    public static BigDecimal rate(Object input) {
        String s = text(input, 20);
        require(s.matches("^(?:0|[1-9]\\d{0,3})(?:\\.\\d{1,12})?$"), "INVALID_INPUT", "Rate must have at most 12 decimal places");
        BigDecimal val = new BigDecimal(s).setScale(12, RoundingMode.HALF_UP);
        require(val.signum() >= 0, "INVALID_INPUT", "Rate cannot be negative");
        return val;
    }

    public static BigDecimal confidence(Object input) {
        String s = text(input, 10);
        require(s.matches("^(?:0(?:\\.\\d{1,4})?|1(?:\\.0{1,4})?)$"), "INVALID_INPUT", "Confidence must be between 0.0000 and 1.0000");
        return new BigDecimal(s).setScale(4, RoundingMode.HALF_UP);
    }

    public static int integer(Object input, int min, int max) {
        if (input instanceof Number n) {
            int v = n.intValue();
            require(v >= min && v <= max, "INVALID_INPUT", "Integer out of bounds");
            return v;
        }
        String s = text(input, 10);
        try {
            int v = Integer.parseInt(s);
            require(v >= min && v <= max, "INVALID_INPUT", "Integer out of bounds");
            return v;
        } catch (NumberFormatException e) {
            throw new SubledgerException("INVALID_INPUT", "Malformed integer");
        }
    }

    // --- Variance Math ---
    public static BigDecimal calculatePriceVariance(BigDecimal estimatedUnitPrice, BigDecimal actualUnitPrice, BigDecimal invoicedQuantity) {
        // Price Variance = (actualUnitPrice - estimatedUnitPrice) * invoicedQuantity
        return actualUnitPrice.subtract(estimatedUnitPrice)
                .multiply(invoicedQuantity)
                .setScale(8, RoundingMode.HALF_UP);
    }

    public static BigDecimal calculateQuantityVariance(BigDecimal acceptedQuantity, BigDecimal invoicedQuantity, BigDecimal estimatedUnitPrice) {
        // Quantity Variance = (invoicedQuantity - acceptedQuantity) * estimatedUnitPrice
        return invoicedQuantity.subtract(acceptedQuantity)
                .multiply(estimatedUnitPrice)
                .setScale(8, RoundingMode.HALF_UP);
    }

    public static BigDecimal calculateTotalVariance(BigDecimal provisionalNetAmount, BigDecimal actualNetAmount) {
        return actualNetAmount.subtract(provisionalNetAmount).setScale(8, RoundingMode.HALF_UP);
    }

    // --- ECL / Bad Debt Math ---
    public static BigDecimal defaultLossRate(AgingBucket bucket) {
        return switch (bucket) {
            case NOT_DUE -> new BigDecimal("0.005000000000");
            case DAYS_1_30 -> new BigDecimal("0.020000000000");
            case DAYS_31_60 -> new BigDecimal("0.050000000000");
            case DAYS_61_90 -> new BigDecimal("0.100000000000");
            case DAYS_91_180 -> new BigDecimal("0.300000000000");
            case DAYS_181_PLUS -> new BigDecimal("0.500000000000");
        };
    }

    public static BigDecimal calculateEclProvision(BigDecimal receivableAmount, BigDecimal lossRate) {
        require(receivableAmount.signum() >= 0, "INVALID_INPUT");
        require(lossRate.signum() >= 0 && lossRate.compareTo(BigDecimal.ONE) <= 0, "INVALID_INPUT");
        return receivableAmount.multiply(lossRate).setScale(8, RoundingMode.HALF_UP);
    }

    public static BigDecimal calculateEclAdjustment(BigDecimal targetProvision, BigDecimal priorAllowance) {
        return targetProvision.subtract(priorAllowance).setScale(8, RoundingMode.HALF_UP);
    }

    // --- Variable Consideration Constraint Math ---
    public static BigDecimal calculateConstrainedCap(BigDecimal estimatedAmount, BigDecimal confidenceScore) {
        require(estimatedAmount.signum() >= 0, "INVALID_INPUT");
        require(confidenceScore.signum() >= 0 && confidenceScore.compareTo(BigDecimal.ONE) <= 0, "INVALID_INPUT");
        return estimatedAmount.multiply(confidenceScore).setScale(8, RoundingMode.HALF_UP);
    }

    // --- Multi-Year Contract Revenue Continuity Math ---
    public static BigDecimal calculateMultiYearRevenue(BigDecimal totalContractValue, BigDecimal cumulativeProgressPct, BigDecimal priorRecognized) {
        require(totalContractValue.signum() >= 0, "INVALID_INPUT");
        require(cumulativeProgressPct.signum() >= 0 && cumulativeProgressPct.compareTo(BigDecimal.ONE) <= 0, "INVALID_INPUT");
        BigDecimal cumulativeExpected = totalContractValue.multiply(cumulativeProgressPct).setScale(8, RoundingMode.HALF_UP);
        BigDecimal currentYearRevenue = cumulativeExpected.subtract(priorRecognized).setScale(8, RoundingMode.HALF_UP);
        require(currentYearRevenue.signum() >= 0, "REVENUE_REVERSAL_EXCEEDS_PRIOR", "Cumulative revenue cannot be lower than prior recognized");
        return currentYearRevenue;
    }

    // --- Standards Policy Validation ---
    public static void validateContractAssetAllowed(AccountingStandard standard) {
        if (standard == AccountingStandard.SMALL) {
            throw new SubledgerException("SMALL_DOES_NOT_SUPPORT_CONTRACT_ASSET",
                    "Small Enterprise Accounting Standard (SMALL) does not support CAS 14 Contract Assets");
        }
    }

    public static void validateBadDebtProvisionAllowed(AccountingStandard standard, EclOperationKind kind) {
        if (standard == AccountingStandard.SMALL) {
            if (kind == EclOperationKind.PROVISION || kind == EclOperationKind.REVERSAL) {
                throw new SubledgerException("SMALL_DOES_NOT_SUPPORT_BAD_DEBT_PROVISION",
                        "Small Enterprise Accounting Standard (SMALL) does not accrue Bad Debt Provisions; only direct write-off is permitted");
            }
        }
    }

    // --- Segregation of Duties (SOD) ---
    public static void validateIndependentApproval(UUID preparerId, UUID approverId) {
        require(preparerId != null, "INVALID_INPUT", "Preparer ID required");
        require(approverId != null, "INVALID_INPUT", "Approver ID required");
        if (preparerId.equals(approverId)) {
            throw new SubledgerException("SELF_APPROVAL_FORBIDDEN",
                    "Segregation of Duties violated: Approver cannot be the same person as Preparer");
        }
    }

    public static String computeApprovalDigest(UUID approverId, String resourceId, String amount, String date) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String raw = String.join(":", approverId.toString(), resourceId, amount, date);
            byte[] hash = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm missing", e);
        }
    }
}
