package com.lumira.finance.treasury;

import com.lumira.finance.treasury.TreasuryModel.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.*;

/**
 * Pure domain rules and arithmetic validations for Treasury (MD04, TR01, TR02, TR03, TR04).
 */
public final class TreasuryRules {
    private TreasuryRules() {}

    private static final String CNY_REGEX = "^-?\\d+(\\.\\d{1,2})?$";

    public static void validateCnyAmount(String amountStr, String label) {
        if (amountStr == null || !amountStr.trim().matches(CNY_REGEX)) {
            throw new IllegalArgumentException(label + "必须是合规的人民币金额格式（最多两位小数）: " + amountStr);
        }
    }

    public static BigDecimal parseCny(String amountStr, String label) {
        validateCnyAmount(amountStr, label);
        BigDecimal val = new BigDecimal(amountStr.trim()).setScale(8, RoundingMode.HALF_UP);
        return val;
    }

    public static BigDecimal parsePositiveCny(String amountStr, String label) {
        BigDecimal val = parseCny(amountStr, label);
        if (val.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(label + "必须大于零: " + amountStr);
        }
        return val;
    }

    public static BigDecimal parseNonNegativeCny(String amountStr, String label) {
        BigDecimal val = parseCny(amountStr, label);
        if (val.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException(label + "不能为负数: " + amountStr);
        }
        return val;
    }

    public static void requireIndependentPerson(UUID maker, UUID reviewer, String action) {
        if (reviewer == null) {
            throw new IllegalArgumentException("独立自然人职责分离失败：" + action + "必须指定独立的复核/审批人");
        }
        if (maker != null && maker.equals(reviewer)) {
            throw new IllegalStateException("独立自然人职责分离失败：" + action + "操作人与复核/审批人不能为同一人");
        }
    }

    public static NumberingPolicy evaluateNumberingPolicyApproval(NumberingPolicy current, UUID reviewerPersonId) {
        requireIndependentPerson(current.makerPersonId(), reviewerPersonId, "编号策略审批");
        if (current.isApproved()) {
            throw new IllegalStateException("该期间编号策略已批准，无需重复审批");
        }
        return new NumberingPolicy(
                current.id(),
                current.tenantId(),
                current.bookId(),
                current.periodId(),
                current.periodNo(),
                current.orderType(),
                current.prefix(),
                current.pattern(),
                current.currentSequence(),
                true,
                current.makerPersonId(),
                reviewerPersonId,
                current.version() + 1,
                current.createdAt()
        );
    }

    // ==========================================
    // MD04: Bank Account & Numbering Rules
    // ==========================================

    public static void validateAccountCreation(CreateAccountInput in) {
        if (in.accountNo() == null || in.accountNo().trim().isEmpty()) {
            throw new IllegalArgumentException("银行/资金账号不能为空");
        }
        if (in.accountName() == null || in.accountName().trim().isEmpty()) {
            throw new IllegalArgumentException("账户名称不能为空");
        }
        if (in.bankName() == null || in.bankName().trim().isEmpty()) {
            throw new IllegalArgumentException("开户银行不能为空");
        }
        if (in.branchName() == null || in.branchName().trim().isEmpty()) {
            throw new IllegalArgumentException("开户支行不能为空");
        }
        if (in.glAccountId() == null) {
            throw new IllegalArgumentException("关联总账科目ID不能为空");
        }
        try {
            BankAccountType.valueOf(in.accountType());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("无效的账户类型: " + in.accountType());
        }
    }

    public static BankAccountStatus evaluateAccountTransition(
            BankAccount account, String action, UUID reviewerPersonId, BigDecimal currentBalance) {
        requireIndependentPerson(account.makerPersonId(), reviewerPersonId, "账户状态变更");

        switch (action) {
            case "ACTIVATE" -> {
                if (account.status() != BankAccountStatus.PENDING_REVIEW && account.status() != BankAccountStatus.FROZEN) {
                    throw new IllegalStateException("只有待复核或冻结状态的账户允许启用激活，当前状态: " + account.status());
                }
                return BankAccountStatus.ACTIVE;
            }
            case "FREEZE" -> {
                if (account.status() != BankAccountStatus.ACTIVE) {
                    throw new IllegalStateException("只有正常有效状态的账户允许冻结，当前状态: " + account.status());
                }
                return BankAccountStatus.FROZEN;
            }
            case "UNFREEZE" -> {
                if (account.status() != BankAccountStatus.FROZEN) {
                    throw new IllegalStateException("只有冻结状态的账户允许解冻，当前状态: " + account.status());
                }
                return BankAccountStatus.ACTIVE;
            }
            case "CLOSE" -> {
                if (account.status() != BankAccountStatus.ACTIVE && account.status() != BankAccountStatus.FROZEN) {
                    throw new IllegalStateException("只有正常或冻结账户允许销户，当前状态: " + account.status());
                }
                if (currentBalance != null && currentBalance.compareTo(BigDecimal.ZERO) != 0) {
                    throw new IllegalStateException("账户余额不为零 (" + currentBalance.toPlainString() + ")，禁止销户");
                }
                return BankAccountStatus.CLOSED;
            }
            default -> throw new IllegalArgumentException("未知的账户操作动作: " + action);
        }
    }

    public static String formatTreasuryOrderNo(String prefix, String pattern, int sequence) {
        if (prefix == null || prefix.trim().isEmpty()) {
            prefix = "TR-";
        }
        if (sequence <= 0) {
            throw new IllegalArgumentException("编号序号必须大于0: " + sequence);
        }
        return String.format("%s%s-%04d", prefix, pattern, sequence);
    }

    // ==========================================
    // TR01: Orders & Internal Transfer Pairing
    // ==========================================

    public static void validateTransferPairing(
            BankAccount sourceAccount, BankAccount targetAccount, BigDecimal amount, BigDecimal feeAmount) {
        if (sourceAccount.id().equals(targetAccount.id())) {
            throw new IllegalArgumentException("内部划转两端账户不能相同");
        }
        if (!sourceAccount.tenantId().equals(targetAccount.tenantId())
                || !sourceAccount.bookId().equals(targetAccount.bookId())) {
            throw new IllegalArgumentException("跨法人不能使用同账套内部转账捷径：划转两端必须属于同一租户与账套");
        }
        if (sourceAccount.status() != BankAccountStatus.ACTIVE) {
            throw new IllegalStateException("划出账户非正常激活状态: " + sourceAccount.status());
        }
        if (targetAccount.status() != BankAccountStatus.ACTIVE) {
            throw new IllegalStateException("划入账户非正常激活状态: " + targetAccount.status());
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("划转金额必须大于零");
        }
        if (feeAmount == null || feeAmount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("手续费金额不能为负");
        }
    }

    // ==========================================
    // TR02: Payment Instruction & UNKNOWN Hold
    // ==========================================

    public static void validateInstructionCreation(
            TreasuryOrder order, BankAccount payerAccount, BigDecimal amount) {
        if (order.orderType() != OrderType.PAYMENT && order.orderType() != OrderType.TRANSFER_OUT) {
            throw new IllegalArgumentException("只有付款单或内部划出单允许生成支付指令");
        }
        boolean isAllowedStatus = order.status() == OrderStatus.APPROVED
                || (order.orderType() == OrderType.TRANSFER_OUT && order.status() == OrderStatus.IN_TRANSIT);
        if (!isAllowedStatus) {
            throw new IllegalStateException("关联资金单据未获批准，当前状态: " + order.status());
        }
        if (payerAccount.status() != BankAccountStatus.ACTIVE) {
            throw new IllegalStateException("付款账户非正常有效状态: " + payerAccount.status());
        }
        if (amount == null || amount.compareTo(order.amount()) != 0) {
            throw new IllegalArgumentException("支付指令金额与批准单据金额不一致");
        }
    }

    public static void validateInstructionApproval(PaymentInstruction instruction, UUID approverPersonId) {
        if (instruction.status() != InstructionStatus.DRAFT) {
            throw new IllegalStateException("只有草稿状态的支付指令允许审批，当前状态: " + instruction.status());
        }
        requireIndependentPerson(instruction.makerPersonId(), approverPersonId, "支付指令审批");
    }

    public static PaymentInstruction transitionToDispatched(PaymentInstruction instruction) {
        if (instruction.status() != InstructionStatus.APPROVED) {
            throw new IllegalStateException("只有已批准状态的支付指令允许发送，当前状态: " + instruction.status());
        }
        return new PaymentInstruction(
                instruction.id(),
                instruction.tenantId(),
                instruction.bookId(),
                instruction.instructionNo(),
                instruction.treasuryOrderId(),
                instruction.payerAccountId(),
                instruction.payeeAccountNo(),
                instruction.payeeAccountName(),
                instruction.payeeBankName(),
                instruction.amount(),
                InstructionStatus.DISPATCHED_UNKNOWN, // STRICT UNKNOWN HOLD
                "NOT_VERIFIED", // bankDirectChannel is NOT_VERIFIED
                instruction.makerPersonId(),
                instruction.approverPersonId(),
                java.time.Instant.now(),
                null,
                null,
                null,
                null,
                null,
                null,
                instruction.version() + 1,
                instruction.createdAt(),
                java.time.Instant.now()
        );
    }

    public static PaymentInstruction transitionToSettled(
            PaymentInstruction instruction,
            String providerRef,
            String receiptSha256,
            UUID verifierPersonId) {
        if (instruction.status() != InstructionStatus.DISPATCHED_UNKNOWN) {
            throw new IllegalStateException("只有处于 DISPATCHED_UNKNOWN 状态的支付指令允许通过真实回执结算，当前状态: " + instruction.status());
        }
        requireIndependentPerson(instruction.makerPersonId(), verifierPersonId, "结算回执验真核对");
        if (providerRef == null || providerRef.trim().isEmpty()) {
            throw new IllegalArgumentException("银行交易流水号/渠道参考号不能为空");
        }
        if (receiptSha256 == null || !receiptSha256.matches("^[a-fA-F0-9]{64}$")) {
            throw new IllegalArgumentException("必须提供有效的原始回执文件SHA-256校验和: " + receiptSha256);
        }

        return new PaymentInstruction(
                instruction.id(),
                instruction.tenantId(),
                instruction.bookId(),
                instruction.instructionNo(),
                instruction.treasuryOrderId(),
                instruction.payerAccountId(),
                instruction.payeeAccountNo(),
                instruction.payeeAccountName(),
                instruction.payeeBankName(),
                instruction.amount(),
                InstructionStatus.SETTLED,
                instruction.bankDirectChannel(),
                instruction.makerPersonId(),
                instruction.approverPersonId(),
                instruction.dispatchedAt(),
                receiptSha256.toLowerCase(Locale.ROOT),
                providerRef.trim(),
                verifierPersonId,
                java.time.Instant.now(),
                null,
                null,
                instruction.version() + 1,
                instruction.createdAt(),
                java.time.Instant.now()
        );
    }

    public static PaymentInstruction transitionToFailedReturned(
            PaymentInstruction instruction,
            String failureReason,
            UUID adjustmentRefId,
            UUID verifierPersonId) {
        if (instruction.status() != InstructionStatus.DISPATCHED_UNKNOWN) {
            throw new IllegalStateException("只有处于 DISPATCHED_UNKNOWN 状态的支付指令允许登记失败退回，当前状态: " + instruction.status());
        }
        requireIndependentPerson(instruction.makerPersonId(), verifierPersonId, "付款失败核验登记");
        if (failureReason == null || failureReason.trim().isEmpty()) {
            throw new IllegalArgumentException("失败退回原因不能为空");
        }

        return new PaymentInstruction(
                instruction.id(),
                instruction.tenantId(),
                instruction.bookId(),
                instruction.instructionNo(),
                instruction.treasuryOrderId(),
                instruction.payerAccountId(),
                instruction.payeeAccountNo(),
                instruction.payeeAccountName(),
                instruction.payeeBankName(),
                instruction.amount(),
                InstructionStatus.FAILED_RETURNED,
                instruction.bankDirectChannel(),
                instruction.makerPersonId(),
                instruction.approverPersonId(),
                instruction.dispatchedAt(),
                null,
                null,
                verifierPersonId,
                java.time.Instant.now(),
                failureReason.trim(),
                adjustmentRefId,
                instruction.version() + 1,
                instruction.createdAt(),
                java.time.Instant.now()
        );
    }

    // ==========================================
    // TR03: Bank Statement Import & Matching
    // ==========================================

    public static String computeSha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm missing", e);
        }
    }

    public static String computeLineHash(
            UUID accountId,
            LocalDate transactionDate,
            StatementDirection direction,
            BigDecimal amount,
            BigDecimal balanceAfter,
            String transactionNo,
            String counterpartyAccountNo) {
        String raw = String.join("|",
                accountId.toString(),
                transactionDate.toString(),
                direction.name(),
                amount.setScale(8, RoundingMode.HALF_UP).toPlainString(),
                balanceAfter.setScale(8, RoundingMode.HALF_UP).toPlainString(),
                transactionNo == null ? "" : transactionNo.trim(),
                counterpartyAccountNo == null ? "" : counterpartyAccountNo.trim()
        );
        return computeSha256(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static List<String> parseCsvLine(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '\"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '\"') {
                    sb.append('\"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (ch == ',' && !inQuotes) {
                tokens.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(ch);
            }
        }
        tokens.add(sb.toString());
        return tokens;
    }

    public static List<RawStatementLine> parseCsvStatement(String csv) {
        List<RawStatementLine> lines = new ArrayList<>();
        String[] rows = csv.split("\\r?\\n");
        int lineNo = 1;
        for (String row : rows) {
            String trimmed = row.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("transactionDate")) {
                continue; // skip header or empty
            }
            List<String> cols = parseCsvLine(trimmed);
            if (cols.size() < 9) {
                throw new IllegalArgumentException("CSV行 " + lineNo + " 格式不合规，缺少必需列: " + trimmed);
            }
            LocalDate txDate = LocalDate.parse(cols.get(0).trim());
            LocalDate valDate = cols.get(1).trim().isEmpty() ? txDate : LocalDate.parse(cols.get(1).trim());
            StatementDirection dir = StatementDirection.valueOf(cols.get(2).trim().toUpperCase(Locale.ROOT));
            BigDecimal amount = parsePositiveCny(cols.get(3).trim(), "流水金额");
            BigDecimal balanceAfter = parseCny(cols.get(4).trim(), "交易后余额");
            String cpAcct = cols.get(5).trim();
            String cpName = cols.get(6).trim();
            String txNo = cols.get(7).trim();
            String summary = cols.get(8).trim();

            lines.add(new RawStatementLine(lineNo++, txDate, valDate, dir, amount, balanceAfter, cpAcct, cpName, txNo, summary));
        }
        return lines;
    }

    public static void reconcileSourceTotals(
            BigDecimal openingBalance,
            BigDecimal closingBalance,
            BigDecimal totalCredit,
            BigDecimal totalDebit,
            int recordCount,
            List<RawStatementLine> lines) {
        if (lines.size() != recordCount) {
            throw new IllegalArgumentException(String.format("明细行数 (%d) 与申报记录数 (%d) 不一致", lines.size(), recordCount));
        }
        BigDecimal sumCredit = BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
        BigDecimal sumDebit = BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
        for (RawStatementLine l : lines) {
            if (l.direction() == StatementDirection.CREDIT) {
                sumCredit = sumCredit.add(l.amount());
            } else if (l.direction() == StatementDirection.DEBIT) {
                sumDebit = sumDebit.add(l.amount());
            }
        }
        if (sumCredit.compareTo(totalCredit) != 0) {
            throw new IllegalArgumentException(String.format("解析贷方收入合计 (%s) 与文件总额 (%s) 不符", sumCredit.toPlainString(), totalCredit.toPlainString()));
        }
        if (sumDebit.compareTo(totalDebit) != 0) {
            throw new IllegalArgumentException(String.format("解析借方支出合计 (%s) 与文件总额 (%s) 不符", sumDebit.toPlainString(), totalDebit.toPlainString()));
        }
        BigDecimal computedClosing = openingBalance.add(totalCredit).subtract(totalDebit).setScale(8, RoundingMode.HALF_UP);
        if (computedClosing.compareTo(closingBalance.setScale(8, RoundingMode.HALF_UP)) != 0) {
            throw new IllegalArgumentException(String.format("期初期末余额对账不平：期初 %s + 贷方 %s - 借方 %s = %s != 期末 %s",
                    openingBalance.toPlainString(), totalCredit.toPlainString(), totalDebit.toPlainString(), computedClosing.toPlainString(), closingBalance.toPlainString()));
        }
    }

    public static StatementMatchStatus evaluateMatchProposal(int matchingCandidateCount) {
        if (matchingCandidateCount == 0) {
            return StatementMatchStatus.UNMATCHED;
        } else if (matchingCandidateCount == 1) {
            return StatementMatchStatus.PROPOSED;
        } else {
            return StatementMatchStatus.AMBIGUOUS_MULTI_CANDIDATE; // 相同金额多候选转人工
        }
    }

    // ==========================================
    // TR04: Reconciliation Sheet & Cash Forecast
    // ==========================================

    public static BankReconciliation computeBankReconciliation(
            UUID id,
            UUID tenantId,
            UUID bookId,
            UUID accountId,
            UUID periodId,
            int periodNo,
            LocalDate cutoffDate,
            BigDecimal bankStatementBalance,
            BigDecimal ledgerBalance,
            List<UnreachedItemInput> items,
            UUID preparerPersonId) {
        BigDecimal entRecBankUnrec = BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
        BigDecimal entPaidBankUnpaid = BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
        BigDecimal bankCredEntUnrec = BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
        BigDecimal bankDebEntUnrec = BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);

        for (UnreachedItemInput item : items) {
            UnreachedItemType type = UnreachedItemType.valueOf(item.itemType());
            BigDecimal amt = parsePositiveCny(item.amount(), "未达金额");
            switch (type) {
                case ENTERPRISE_RECORDED_BANK_UNRECORDED -> entRecBankUnrec = entRecBankUnrec.add(amt);
                case ENTERPRISE_PAID_BANK_UNPAID -> entPaidBankUnpaid = entPaidBankUnpaid.add(amt);
                case BANK_CREDITED_ENTERPRISE_UNRECORDED -> {
                    if (item.supplementaryEvidenceId() == null) {
                        throw new IllegalArgumentException("银行已收企业未收项目必须关联补录依据原件ID");
                    }
                    bankCredEntUnrec = bankCredEntUnrec.add(amt);
                }
                case BANK_DEBITED_ENTERPRISE_UNRECORDED -> bankDebEntUnrec = bankDebEntUnrec.add(amt);
            }
        }

        BigDecimal adjustedBankBalance = bankStatementBalance
                .add(entRecBankUnrec)
                .subtract(entPaidBankUnpaid)
                .setScale(8, RoundingMode.HALF_UP);

        BigDecimal adjustedLedgerBalance = ledgerBalance
                .add(bankCredEntUnrec)
                .subtract(bankDebEntUnrec)
                .setScale(8, RoundingMode.HALF_UP);

        boolean isBalanced = adjustedBankBalance.compareTo(adjustedLedgerBalance) == 0;

        return new BankReconciliation(
                id,
                tenantId,
                bookId,
                accountId,
                periodId,
                periodNo,
                cutoffDate,
                bankStatementBalance.setScale(8, RoundingMode.HALF_UP),
                entRecBankUnrec,
                entPaidBankUnpaid,
                adjustedBankBalance,
                ledgerBalance.setScale(8, RoundingMode.HALF_UP),
                bankCredEntUnrec,
                bankDebEntUnrec,
                adjustedLedgerBalance,
                isBalanced,
                ReconciliationStatus.DRAFT,
                preparerPersonId,
                null,
                1,
                java.time.Instant.now(),
                java.time.Instant.now()
        );
    }

    public static BankReconciliation evaluateReconciliationConfirmation(
            BankReconciliation current, UUID reviewerPersonId) {
        requireIndependentPerson(current.preparerPersonId(), reviewerPersonId, "余额调节表确认复核");
        if (current.status() != ReconciliationStatus.DRAFT) {
            throw new IllegalStateException("只有草稿状态的余额调节表允许复核确认，当前状态: " + current.status());
        }
        if (!current.isBalanced()) {
            throw new IllegalStateException("银行余额调节表两端未平衡，禁止确认");
        }
        return new BankReconciliation(
                current.id(),
                current.tenantId(),
                current.bookId(),
                current.accountId(),
                current.periodId(),
                current.periodNo(),
                current.cutoffDate(),
                current.bankStatementBalance(),
                current.enterpriseRecordedBankUnrecorded(),
                current.enterprisePaidBankUnpaid(),
                current.adjustedBankBalance(),
                current.ledgerBalance(),
                current.bankCreditedEnterpriseUnrecorded(),
                current.bankDebitedEnterpriseUnrecorded(),
                current.adjustedLedgerBalance(),
                current.isBalanced(),
                ReconciliationStatus.CONFIRMED,
                current.preparerPersonId(),
                reviewerPersonId,
                current.version() + 1,
                current.createdAt(),
                java.time.Instant.now()
        );
    }

    public static int calculateAgingDays(LocalDate recordedDate, LocalDate cutoffDate) {
        long days = cutoffDate.toEpochDay() - recordedDate.toEpochDay();
        return (int) Math.max(0, days);
    }

    public static boolean isOverdueWarning(int agingDays) {
        return agingDays > 30; // 长期未清项预警阈值：30天
    }

    public static CashForecast computeForecast(
            UUID id,
            UUID tenantId,
            UUID bookId,
            UUID accountId,
            int horizonDays,
            LocalDate forecastDate,
            BigDecimal openingCash,
            ForecastTier tier,
            List<ForecastItemInput> items) {
        if (horizonDays != 7 && horizonDays != 14 && horizonDays != 30 && horizonDays != 90) {
            throw new IllegalArgumentException("预测区间仅支持 7, 14, 30 或 90 天: " + horizonDays);
        }
        BigDecimal inflow = BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
        BigDecimal outflow = BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);

        for (ForecastItemInput it : items) {
            if (ForecastTier.valueOf(it.tier()) == tier) {
                if (it.inflow() != null && !it.inflow().trim().isEmpty()) {
                    inflow = inflow.add(parseNonNegativeCny(it.inflow(), "预计流入"));
                }
                if (it.outflow() != null && !it.outflow().trim().isEmpty()) {
                    outflow = outflow.add(parseNonNegativeCny(it.outflow(), "预计流出"));
                }
            }
        }

        BigDecimal closingCash = openingCash.add(inflow).subtract(outflow).setScale(8, RoundingMode.HALF_UP);

        return new CashForecast(
                id,
                tenantId,
                bookId,
                accountId,
                horizonDays,
                tier,
                forecastDate,
                openingCash.setScale(8, RoundingMode.HALF_UP),
                inflow,
                outflow,
                closingCash,
                true, // Strictly read-only forecast: never writes back to official GL
                "Forecast horizon: " + horizonDays + "d, tier: " + tier,
                java.time.Instant.now()
        );
    }
}
