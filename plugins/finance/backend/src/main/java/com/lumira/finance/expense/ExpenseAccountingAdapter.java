package com.lumira.finance.expense;

import com.lumira.finance.expense.ExpenseDomain.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * Native EXPENSE_* journal posting and full reversal adapter.
 * Produces balanced debits/credits for claim recognition, payment disbursement, and full reversals.
 */
@Component
public final class ExpenseAccountingAdapter {

    public static final String ACCT_EXPENSE_ADMIN = "6602";
    public static final String ACCT_EXPENSE_SELLING = "6601";
    public static final String ACCT_VAT_INPUT = "22210101";
    public static final String ACCT_OTHER_RECEIVABLE_ADVANCE = "1221";
    public static final String ACCT_OTHER_PAYABLE_EMPLOYEE = "2241";
    public static final String ACCT_BANK_DEPOSIT = "1002";

    public ExpenseJournalPlan generateClaimJournal(
            ExpenseClaim claim,
            List<ExpenseClaimLine> lines,
            UUID bookId
    ) {
        List<JournalLine> journalLines = new ArrayList<>();
        int lineSeq = 1;
        BigDecimal totalDebit = BigDecimal.ZERO.setScale(8, RoundingMode.UNNECESSARY);
        BigDecimal totalCredit = BigDecimal.ZERO.setScale(8, RoundingMode.UNNECESSARY);

        // 1. Debits: Net expense per department/project
        Map<String, BigDecimal> deptNetExpense = new LinkedHashMap<>();
        Map<String, UUID> deptMap = new HashMap<>();
        Map<String, UUID> projMap = new HashMap<>();

        BigDecimal totalVatInput = BigDecimal.ZERO.setScale(8, RoundingMode.UNNECESSARY);

        for (ExpenseClaimLine l : lines) {
            if (l.netExpenseAmount().signum() > 0) {
                String key = (l.departmentId() != null ? l.departmentId().toString() : "GLOBAL")
                        + ":" + (l.projectId() != null ? l.projectId().toString() : "NONE")
                        + ":" + l.expenseCategory().name();
                deptNetExpense.put(key, deptNetExpense.getOrDefault(key, BigDecimal.ZERO.setScale(8, RoundingMode.UNNECESSARY)).add(l.netExpenseAmount()));
                deptMap.put(key, l.departmentId());
                projMap.put(key, l.projectId());
            }
            if (l.vatInputAmount().signum() > 0) {
                totalVatInput = totalVatInput.add(l.vatInputAmount());
            }
        }

        for (Map.Entry<String, BigDecimal> entry : deptNetExpense.entrySet()) {
            BigDecimal amt = entry.getValue();
            journalLines.add(new JournalLine(
                    lineSeq++,
                    ACCT_EXPENSE_ADMIN,
                    "管理费用-费用报销",
                    "DEBIT",
                    amt,
                    deptMap.get(entry.getKey()),
                    projMap.get(entry.getKey()),
                    "报销费用确认: " + claim.title()
            ));
            totalDebit = totalDebit.add(amt);
        }

        // 2. Debit: VAT Input tax (if applicable)
        if (totalVatInput.signum() > 0) {
            journalLines.add(new JournalLine(
                    lineSeq++,
                    ACCT_VAT_INPUT,
                    "应交税费-应交增值税(进项税额)",
                    "DEBIT",
                    totalVatInput,
                    null,
                    null,
                    "报销进项税额: " + claim.title()
            ));
            totalDebit = totalDebit.add(totalVatInput);
        }

        // 3. Credit: Advance offset (if any)
        if (claim.offsetAdvanceAmount().signum() > 0) {
            journalLines.add(new JournalLine(
                    lineSeq++,
                    ACCT_OTHER_RECEIVABLE_ADVANCE,
                    "其他应收款-员工借款冲销",
                    "CREDIT",
                    claim.offsetAdvanceAmount(),
                    null,
                    null,
                    "冲销员工借款: " + claim.claimNo()
            ));
            totalCredit = totalCredit.add(claim.offsetAdvanceAmount());
        }

        // 4. Credit: Other Payables - Employee Reimbursement (net payable)
        if (claim.payableAmount().signum() > 0) {
            journalLines.add(new JournalLine(
                    lineSeq++,
                    ACCT_OTHER_PAYABLE_EMPLOYEE,
                    "其他应付款-应付员工报销款",
                    "CREDIT",
                    claim.payableAmount(),
                    null,
                    null,
                    "应付员工报销款: " + claim.claimNo()
            ));
            totalCredit = totalCredit.add(claim.payableAmount());
        }

        // Validate balance
        if (totalDebit.compareTo(totalCredit) != 0) {
            throw new ValidationException("UNBALANCED_JOURNAL",
                    "Total debits (" + ExpenseDomain.toCny2String(totalDebit) +
                            ") does not equal total credits (" + ExpenseDomain.toCny2String(totalCredit) + ")");
        }

        return new ExpenseJournalPlan(
                UUID.randomUUID(),
                claim.tenantId(),
                bookId,
                "EXPENSE_CLAIM",
                claim.claimDate(),
                "费用报销确认: " + claim.title(),
                claim.id(),
                journalLines,
                totalDebit,
                totalCredit
        );
    }

    public ExpenseJournalPlan generatePaymentJournal(
            ExpensePayment payment,
            ExpensePayableLedger ledger,
            UUID bookId
    ) {
        List<JournalLine> journalLines = new ArrayList<>();
        BigDecimal amt = payment.amount();

        // Debit: Other Payables - Employee
        journalLines.add(new JournalLine(
                1,
                ACCT_OTHER_PAYABLE_EMPLOYEE,
                "其他应付款-应付员工报销款",
                "DEBIT",
                amt,
                null,
                null,
                "报销款对外支付: " + payment.paymentNo()
        ));

        // Credit: Bank Deposit
        journalLines.add(new JournalLine(
                2,
                ACCT_BANK_DEPOSIT,
                "银行存款",
                "CREDIT",
                amt,
                null,
                null,
                "报销银行支付流水: " + payment.paymentRef()
        ));

        return new ExpenseJournalPlan(
                UUID.randomUUID(),
                payment.tenantId(),
                bookId,
                "EXPENSE_PAYMENT",
                payment.paymentDate(),
                "支付报销款: " + payment.paymentNo(),
                payment.id(),
                journalLines,
                amt,
                amt
        );
    }

    public ExpenseJournalPlan generateReversalJournal(
            ExpenseClaim claim,
            ExpenseJournalPlan originalJournal
    ) {
        List<JournalLine> reversedLines = new ArrayList<>();
        int seq = 1;
        for (JournalLine orig : originalJournal.lines()) {
            String invDir = "DEBIT".equalsIgnoreCase(orig.direction()) ? "CREDIT" : "DEBIT";
            reversedLines.add(new JournalLine(
                    seq++,
                    orig.accountCode(),
                    orig.accountName() + " (冲销)",
                    invDir,
                    orig.amount(),
                    orig.departmentId(),
                    orig.projectId(),
                    "红字冲销原分录 #" + orig.lineNumber() + ": " + orig.summary()
            ));
        }

        return new ExpenseJournalPlan(
                UUID.randomUUID(),
                claim.tenantId(),
                claim.bookId(),
                "EXPENSE_REVERSAL",
                LocalDate.now(),
                "全额冲销报销单凭证: " + claim.claimNo(),
                claim.id(),
                reversedLines,
                originalJournal.totalCredit(), // swapped
                originalJournal.totalDebit()
        );
    }
}
