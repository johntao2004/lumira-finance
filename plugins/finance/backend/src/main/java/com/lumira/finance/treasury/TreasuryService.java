package com.lumira.finance.treasury;

import com.lumira.finance.treasury.TreasuryModel.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * Transactional domain service for Treasury (MD04, TR01, TR02, TR03, TR04).
 */
@Service
public final class TreasuryService {
    private final DataSource dataSource;
    private final TreasuryRepository repository;
    private final ObjectMapper mapper;

    public TreasuryService(DataSource dataSource, TreasuryRepository repository, ObjectMapper mapper) {
        this.dataSource = dataSource;
        this.repository = repository;
        this.mapper = mapper;
    }

    // ==========================================
    // MD04: Bank Account & Numbering
    // ==========================================

    public BankAccount openAccount(Scope scope, Actor actor, CreateAccountInput in) throws SQLException {
        TreasuryRules.validateAccountCreation(in);
        UUID accountId = UUID.randomUUID();
        BankAccount account = new BankAccount(
                accountId,
                scope.tenantId(),
                scope.bookId(),
                in.accountNo().trim(),
                in.accountName().trim(),
                in.bankName().trim(),
                in.branchName().trim(),
                BankAccountType.valueOf(in.accountType()),
                "CNY",
                in.glAccountId(),
                BankAccountStatus.PENDING_REVIEW,
                actor.personId(),
                null,
                null,
                null,
                1,
                Instant.now(),
                Instant.now()
        );

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                repository.createBankAccount(c, account);
                recordEvent(c, scope, "BANK_ACCOUNT", accountId, "CREATE_ACCOUNT", actor, account);
                c.commit();
                return account;
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    public BankAccount reviewAccount(Scope scope, Actor actor, UUID accountId, ReviewAccountInput in) throws SQLException {
        if (in.action() == null || in.action().trim().isEmpty()) {
            throw new IllegalArgumentException("必须指定复核动作 (ACTIVATE, FREEZE, UNFREEZE, CLOSE)");
        }
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                BankAccount current = repository.findBankAccountById(c, accountId)
                        .orElseThrow(() -> new IllegalArgumentException("银行账户不存在: " + accountId));
                BigDecimal balance = repository.getBankAccountBalance(c, accountId);

                BankAccountStatus nextStatus = TreasuryRules.evaluateAccountTransition(
                        current, in.action().trim().toUpperCase(Locale.ROOT), actor.personId(), balance);

                String frozenReason = in.action().equalsIgnoreCase("FREEZE") ? in.reason() : current.frozenReason();
                String closingReason = in.action().equalsIgnoreCase("CLOSE") ? in.reason() : current.closingReason();

                boolean updated = repository.updateBankAccountStatus(
                        c, accountId, nextStatus, actor.personId(), frozenReason, closingReason, current.version());
                if (!updated) {
                    throw new ConcurrentModificationException("账户状态已由其他事务修改，请刷新后重试");
                }

                BankAccount modified = repository.findBankAccountById(c, accountId)
                        .orElseThrow(() -> new IllegalStateException("账户刷新失败: " + accountId));
                recordEvent(c, scope, "BANK_ACCOUNT", accountId, "REVIEW_ACCOUNT_" + in.action(), actor, modified);
                c.commit();
                return modified;
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    public NumberingPolicy configureNumberingPolicy(Scope scope, Actor actor, ConfigureNumberingInput in) throws SQLException {
        if (in.prefix() == null || in.prefix().trim().isEmpty()) {
            throw new IllegalArgumentException("前缀不能为空");
        }
        if (in.pattern() == null || in.pattern().trim().isEmpty()) {
            throw new IllegalArgumentException("格式模式不能为空");
        }
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                UUID policyId = UUID.randomUUID();
                NumberingPolicy policy = new NumberingPolicy(
                        policyId,
                        scope.tenantId(),
                        scope.bookId(),
                        in.periodId(),
                        in.periodNo(),
                        in.orderType(),
                        in.prefix().trim(),
                        in.pattern().trim(),
                        0,
                        false, // Draft: requires independent natural-person review to activate
                        actor.personId(),
                        null,
                        1,
                        Instant.now()
                );
                repository.createNumberingPolicy(c, policy);
                recordEvent(c, scope, "NUMBERING_POLICY", policyId, "CONFIGURE_NUMBERING", actor, policy);
                c.commit();
                return policy;
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    public NumberingPolicy approveNumberingPolicy(Scope scope, Actor actor, UUID policyId) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                NumberingPolicy current = repository.findNumberingPolicyById(c, policyId)
                        .orElseThrow(() -> new IllegalArgumentException("编号策略不存在: " + policyId));
                NumberingPolicy approved = TreasuryRules.evaluateNumberingPolicyApproval(current, actor.personId());
                boolean ok = repository.updateNumberingPolicyApproval(c, policyId, actor.personId(), current.version());
                if (!ok) throw new ConcurrentModificationException("编号策略并发修改冲突");

                recordEvent(c, scope, "NUMBERING_POLICY", policyId, "APPROVE_NUMBERING_POLICY", actor, approved);
                c.commit();
                return approved;
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    // ==========================================
    // TR01: Treasury Orders & Transfer Pairing
    // ==========================================

    public TreasuryOrder createTreasuryOrder(Scope scope, Actor actor, CreateOrderInput in) throws SQLException {
        OrderType type = OrderType.valueOf(in.orderType());
        BigDecimal amount = TreasuryRules.parsePositiveCny(in.amount(), "单据金额");
        BigDecimal feeAmount = in.feeAmount() == null ? BigDecimal.ZERO : TreasuryRules.parseNonNegativeCny(in.feeAmount(), "手续费金额");

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                BankAccount acct = repository.findBankAccountById(c, in.accountId())
                        .orElseThrow(() -> new IllegalArgumentException("主资金账户不存在: " + in.accountId()));
                if (acct.status() != BankAccountStatus.ACTIVE) {
                    throw new IllegalStateException("账户非正常激活状态，禁止录入资金单据: " + acct.status());
                }

                UUID targetAcctId = in.targetAccountId();
                UUID transferPairId = null;
                if (type == OrderType.TRANSFER_OUT) {
                    if (targetAcctId == null) {
                        throw new IllegalArgumentException("内部划转划出单必须提供目标划入账户ID");
                    }
                    BankAccount targetAcct = repository.findBankAccountById(c, targetAcctId)
                            .orElseThrow(() -> new IllegalArgumentException("目标划入账户不存在: " + targetAcctId));
                    TreasuryRules.validateTransferPairing(acct, targetAcct, amount, feeAmount);
                    transferPairId = UUID.randomUUID();
                }

                // Monotonic numbering policy sequence generation
                String policyOrderType = switch (type) {
                    case RECEIPT -> "RECEIPT";
                    case PAYMENT -> "PAYMENT";
                    case TRANSFER_OUT, TRANSFER_IN -> "TRANSFER";
                };

                Optional<NumberingPolicy> policyOpt = in.periodId() != null
                        ? repository.findApprovedNumberingPolicy(c, scope.tenantId(), scope.bookId(), in.periodId(), policyOrderType)
                        : repository.findApprovedNumberingPolicy(c, scope.tenantId(), scope.bookId(), policyOrderType);

                String orderNo;
                if (policyOpt.isPresent()) {
                    NumberingPolicy p = policyOpt.get();
                    int seq = repository.incrementNumberingSequence(c, p.id());
                    orderNo = TreasuryRules.formatTreasuryOrderNo(p.prefix(), p.pattern(), seq);
                } else {
                    int seq = repository.getNextDefaultOrderSequence(c, scope.tenantId(), scope.bookId(), type);
                    String pattern = in.orderDate().toString().replace("-", "").substring(0, 6);
                    orderNo = TreasuryRules.formatTreasuryOrderNo(type.name().substring(0, 3) + "-", pattern, seq);
                }

                UUID orderId = UUID.randomUUID();
                TreasuryOrder order = new TreasuryOrder(
                        orderId,
                        scope.tenantId(),
                        scope.bookId(),
                        orderNo,
                        type,
                        in.accountId(),
                        in.counterpartyId(),
                        targetAcctId,
                        transferPairId,
                        in.orderDate(),
                        amount,
                        feeAmount,
                        in.feeGlAccountId(),
                        OrderStatus.DRAFT,
                        in.purpose(),
                        actor.personId(),
                        null,
                        null,
                        1,
                        Instant.now(),
                        Instant.now()
                );
                repository.createTreasuryOrder(c, order);

                // If internal transfer, create paired TRANSFER_IN order atomically
                if (type == OrderType.TRANSFER_OUT) {
                    String pairedOrderNo;
                    if (policyOpt.isPresent()) {
                        NumberingPolicy p = policyOpt.get();
                        int seq2 = repository.incrementNumberingSequence(c, p.id());
                        pairedOrderNo = TreasuryRules.formatTreasuryOrderNo(p.prefix(), p.pattern(), seq2);
                    } else {
                        int seq2 = repository.getNextDefaultOrderSequence(c, scope.tenantId(), scope.bookId(), OrderType.TRANSFER_IN);
                        String pattern = in.orderDate().toString().replace("-", "").substring(0, 6);
                        pairedOrderNo = TreasuryRules.formatTreasuryOrderNo("TRI-", pattern, seq2);
                    }

                    TreasuryOrder pairedOrder = new TreasuryOrder(
                            UUID.randomUUID(),
                            scope.tenantId(),
                            scope.bookId(),
                            pairedOrderNo,
                            OrderType.TRANSFER_IN,
                            targetAcctId,
                            null,
                            in.accountId(),
                            transferPairId,
                            in.orderDate(),
                            amount,
                            BigDecimal.ZERO, // fee recorded on transfer-out
                            null,
                            OrderStatus.DRAFT,
                            "Paired Transfer In: " + in.purpose(),
                            actor.personId(),
                            null,
                            null,
                            1,
                            Instant.now(),
                            Instant.now()
                    );
                    repository.createTreasuryOrder(c, pairedOrder);
                }

                recordEvent(c, scope, "TREASURY_ORDER", orderId, "CREATE_ORDER", actor, order);
                c.commit();
                return order;
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    public TreasuryOrder approveTreasuryOrder(Scope scope, Actor actor, UUID orderId) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                TreasuryOrder order = repository.findTreasuryOrderById(c, orderId)
                        .orElseThrow(() -> new IllegalArgumentException("资金单据不存在: " + orderId));
                if (order.status() != OrderStatus.DRAFT) {
                    throw new IllegalStateException("只有草稿单据允许审批，当前状态: " + order.status());
                }
                TreasuryRules.requireIndependentPerson(order.makerPersonId(), actor.personId(), "资金单据审批");

                OrderStatus nextStatus = (order.orderType() == OrderType.TRANSFER_OUT)
                        ? OrderStatus.IN_TRANSIT // 在途资金
                        : OrderStatus.APPROVED;

                boolean updated = repository.updateTreasuryOrderStatus(
                        c, orderId, nextStatus, actor.personId(), null, order.version());
                if (!updated) {
                    throw new ConcurrentModificationException("单据状态并发冲突");
                }

                // If paired transfer, update paired order to IN_TRANSIT too
                if (order.transferPairId() != null) {
                    List<TreasuryOrder> orders = repository.listTreasuryOrders(c, scope.tenantId(), scope.bookId(), null, null);
                    for (TreasuryOrder o : orders) {
                        if (order.transferPairId().equals(o.transferPairId()) && !order.id().equals(o.id())) {
                            repository.updateTreasuryOrderStatus(c, o.id(), OrderStatus.IN_TRANSIT, actor.personId(), null, o.version());
                        }
                    }
                }

                TreasuryOrder approved = repository.findTreasuryOrderById(c, orderId).orElseThrow();
                recordEvent(c, scope, "TREASURY_ORDER", orderId, "APPROVE_ORDER", actor, approved);
                c.commit();
                return approved;
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    // ==========================================
    // TR02: Payment Instructions & UNKNOWN Hold
    // ==========================================

    public PaymentInstruction createPaymentInstruction(Scope scope, Actor actor, CreatePaymentInstructionInput in) throws SQLException {
        BigDecimal amount = TreasuryRules.parsePositiveCny(in.amount(), "支付指令金额");
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                TreasuryOrder order = repository.findTreasuryOrderById(c, in.treasuryOrderId())
                        .orElseThrow(() -> new IllegalArgumentException("关联资金单据不存在: " + in.treasuryOrderId()));
                BankAccount payer = repository.findBankAccountById(c, in.payerAccountId())
                        .orElseThrow(() -> new IllegalArgumentException("付款账户不存在: " + in.payerAccountId()));

                TreasuryRules.validateInstructionCreation(order, payer, amount);

                int seq = repository.getNextPaymentInstructionSequence(c, scope.tenantId(), scope.bookId());
                String dateStr = order.orderDate().toString().replace("-", "");
                String instNo = String.format("PI-%s-%04d", dateStr, seq);
                UUID instId = UUID.randomUUID();
                PaymentInstruction inst = new PaymentInstruction(
                        instId,
                        scope.tenantId(),
                        scope.bookId(),
                        instNo,
                        in.treasuryOrderId(),
                        in.payerAccountId(),
                        in.payeeAccountNo().trim(),
                        in.payeeAccountName().trim(),
                        in.payeeBankName().trim(),
                        amount,
                        InstructionStatus.DRAFT,
                        "NOT_VERIFIED", // explicit NOT_VERIFIED channel
                        actor.personId(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        1,
                        Instant.now(),
                        Instant.now()
                );
                repository.createPaymentInstruction(c, inst);
                recordEvent(c, scope, "PAYMENT_INSTRUCTION", instId, "CREATE_INSTRUCTION", actor, inst);
                c.commit();
                return inst;
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    public PaymentInstruction approvePaymentInstruction(Scope scope, Actor actor, UUID instId) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                PaymentInstruction inst = repository.findPaymentInstructionById(c, instId)
                        .orElseThrow(() -> new IllegalArgumentException("支付指令不存在: " + instId));
                TreasuryRules.validateInstructionApproval(inst, actor.personId());

                PaymentInstruction approved = new PaymentInstruction(
                        inst.id(),
                        inst.tenantId(),
                        inst.bookId(),
                        inst.instructionNo(),
                        inst.treasuryOrderId(),
                        inst.payerAccountId(),
                        inst.payeeAccountNo(),
                        inst.payeeAccountName(),
                        inst.payeeBankName(),
                        inst.amount(),
                        InstructionStatus.APPROVED,
                        inst.bankDirectChannel(),
                        inst.makerPersonId(),
                        actor.personId(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        inst.version() + 1,
                        inst.createdAt(),
                        Instant.now()
                );
                boolean ok = repository.updatePaymentInstruction(c, approved);
                if (!ok) throw new ConcurrentModificationException("支付指令并发修改冲突");

                recordEvent(c, scope, "PAYMENT_INSTRUCTION", instId, "APPROVE_INSTRUCTION", actor, approved);
                c.commit();
                return approved;
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    public PaymentInstruction dispatchPaymentInstruction(Scope scope, Actor actor, UUID instId) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                PaymentInstruction inst = repository.findPaymentInstructionById(c, instId)
                        .orElseThrow(() -> new IllegalArgumentException("支付指令不存在: " + instId));
                // Transitions to DISPATCHED_UNKNOWN with bankDirectChannel=NOT_VERIFIED
                PaymentInstruction dispatched = TreasuryRules.transitionToDispatched(inst);
                boolean ok = repository.updatePaymentInstruction(c, dispatched);
                if (!ok) throw new ConcurrentModificationException("支付指令并发修改冲突");

                recordEvent(c, scope, "PAYMENT_INSTRUCTION", instId, "DISPATCH_INSTRUCTION_UNKNOWN", actor, dispatched);
                c.commit();
                return dispatched;
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    public PaymentInstruction settlePaymentInstruction(
            Scope scope, Actor actor, UUID instId, VerifySettlementReceiptInput in) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                PaymentInstruction inst = repository.findPaymentInstructionById(c, instId)
                        .orElseThrow(() -> new IllegalArgumentException("支付指令不存在: " + instId));

                // Strict UNKNOWN hold verification and SHA-256 check
                PaymentInstruction settled = TreasuryRules.transitionToSettled(
                        inst, in.providerRef(), in.receiptOriginalSha256(), actor.personId());

                boolean ok = repository.updatePaymentInstruction(c, settled);
                if (!ok) throw new ConcurrentModificationException("支付指令并发修改冲突");

                // Update related treasury order to EXECUTED using actual version
                TreasuryOrder order = repository.findTreasuryOrderById(c, settled.treasuryOrderId())
                        .orElseThrow(() -> new IllegalStateException("关联资金单据不存在: " + settled.treasuryOrderId()));

                boolean orderOk = repository.updateTreasuryOrderStatus(
                        c, order.id(), OrderStatus.EXECUTED, actor.personId(), null, order.version());
                if (!orderOk) throw new ConcurrentModificationException("资金单据并发修改冲突");

                // If paired transfer, update paired TRANSFER_IN order to EXECUTED atomically
                if (order.transferPairId() != null) {
                    List<TreasuryOrder> pairedOrders = repository.findOrdersByTransferPairId(c, order.transferPairId());
                    for (TreasuryOrder po : pairedOrders) {
                        if (!po.id().equals(order.id()) && po.status() != OrderStatus.EXECUTED) {
                            boolean pOk = repository.updateTreasuryOrderStatus(
                                    c, po.id(), OrderStatus.EXECUTED, actor.personId(), null, po.version());
                            if (!pOk) throw new ConcurrentModificationException("配对划入单据并发修改冲突");
                        }
                    }
                }

                recordEvent(c, scope, "PAYMENT_INSTRUCTION", instId, "SETTLE_INSTRUCTION", actor, settled);
                c.commit();
                return settled;
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    public PaymentInstruction failPaymentInstruction(
            Scope scope, Actor actor, UUID instId, String failureReason, UUID adjustmentRefId) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                PaymentInstruction inst = repository.findPaymentInstructionById(c, instId)
                        .orElseThrow(() -> new IllegalArgumentException("支付指令不存在: " + instId));

                // Strict UNKNOWN transition to FAILED_RETURNED with retained lineage
                PaymentInstruction failed = TreasuryRules.transitionToFailedReturned(
                        inst, failureReason, adjustmentRefId, actor.personId());

                boolean ok = repository.updatePaymentInstruction(c, failed);
                if (!ok) throw new ConcurrentModificationException("支付指令并发修改冲突");

                // Update related treasury order to FAILED using actual version
                TreasuryOrder order = repository.findTreasuryOrderById(c, failed.treasuryOrderId())
                        .orElseThrow(() -> new IllegalStateException("关联资金单据不存在: " + failed.treasuryOrderId()));

                boolean orderOk = repository.updateTreasuryOrderStatus(
                        c, order.id(), OrderStatus.FAILED, actor.personId(), null, order.version());
                if (!orderOk) throw new ConcurrentModificationException("资金单据并发修改冲突");

                // If paired transfer, fail paired order as well
                if (order.transferPairId() != null) {
                    List<TreasuryOrder> pairedOrders = repository.findOrdersByTransferPairId(c, order.transferPairId());
                    for (TreasuryOrder po : pairedOrders) {
                        if (!po.id().equals(order.id()) && po.status() != OrderStatus.FAILED) {
                            repository.updateTreasuryOrderStatus(
                                    c, po.id(), OrderStatus.FAILED, actor.personId(), null, po.version());
                        }
                    }
                }

                recordEvent(c, scope, "PAYMENT_INSTRUCTION", instId, "FAIL_INSTRUCTION", actor, failed);
                c.commit();
                return failed;
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    // ==========================================
    // TR03: Bank Statement Import & Matching
    // ==========================================

    public BankStatementBatch importBankStatement(Scope scope, Actor actor, ImportStatementInput in) throws SQLException {
        String fileSha256 = TreasuryRules.computeSha256(in.rawContent().getBytes(StandardCharsets.UTF_8));
        StatementFormat format = StatementFormat.valueOf(in.fileFormat().toUpperCase(Locale.ROOT));
        BigDecimal openBal = TreasuryRules.parseCny(in.openingBalance(), "期初余额");
        BigDecimal closeBal = TreasuryRules.parseCny(in.closingBalance(), "期末余额");
        BigDecimal totalCredit = TreasuryRules.parseNonNegativeCny(in.totalCreditAmount(), "贷方收入总额");
        BigDecimal totalDebit = TreasuryRules.parseNonNegativeCny(in.totalDebitAmount(), "借方支出总额");

        List<RawStatementLine> rawLines = format == StatementFormat.CSV
                ? TreasuryRules.parseCsvStatement(in.rawContent())
                : parseJsonStatement(in.rawContent());

        // Validate source totals
        TreasuryRules.reconcileSourceTotals(openBal, closeBal, totalCredit, totalDebit, in.recordCount(), rawLines);

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                // Deduplication of batch
                if (repository.findStatementBatchBySha(c, scope.tenantId(), scope.bookId(), in.accountId(), fileSha256).isPresent()) {
                    throw new IllegalStateException("重复批次不重入：同一对账单文件已导入");
                }

                UUID batchId = UUID.randomUUID();
                BankStatementBatch batch = new BankStatementBatch(
                        batchId,
                        scope.tenantId(),
                        scope.bookId(),
                        in.accountId(),
                        fileSha256,
                        format,
                        in.statementDate(),
                        openBal,
                        closeBal,
                        totalCredit,
                        totalDebit,
                        in.recordCount(),
                        actor.personId(),
                        StatementBatchStatus.IMPORTED,
                        Instant.now()
                );
                repository.createBankStatementBatch(c, batch);

                List<BankStatementLine> lines = new ArrayList<>();
                for (RawStatementLine rl : rawLines) {
                    String lineHash = TreasuryRules.computeLineHash(
                            in.accountId(), rl.transactionDate(), rl.direction(), rl.amount(),
                            rl.balanceAfter(), rl.transactionNo(), rl.counterpartyAccountNo());

                    int matchingCandidates = repository.countMatchingOrders(
                            c, in.accountId(), rl.transactionDate(), rl.amount(), rl.direction());
                    StatementMatchStatus matchStatus = TreasuryRules.evaluateMatchProposal(matchingCandidates);

                    UUID proposedOrderId = null;
                    if (matchStatus == StatementMatchStatus.PROPOSED) {
                        proposedOrderId = repository.findSingleMatchingOrderId(
                                c, in.accountId(), rl.transactionDate(), rl.amount(), rl.direction()).orElse(null);
                    }

                    lines.add(new BankStatementLine(
                            UUID.randomUUID(),
                            scope.tenantId(),
                            scope.bookId(),
                            batchId,
                            in.accountId(),
                            rl.lineNo(),
                            lineHash,
                            rl.transactionDate(),
                            rl.valueDate(),
                            rl.direction(),
                            rl.amount(),
                            rl.balanceAfter(),
                            rl.counterpartyAccountNo(),
                            rl.counterpartyName(),
                            rl.transactionNo(),
                            rl.summary(),
                            matchStatus,
                            proposedOrderId,
                            null,
                            null,
                            null,
                            Instant.now()
                    ));
                }
                repository.createBankStatementLines(c, lines);
                recordEvent(c, scope, "BANK_STATEMENT_BATCH", batchId, "IMPORT_STATEMENT", actor, batch);
                c.commit();
                return batch;
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    public boolean reviewMatch(Scope scope, Actor actor, MatchReviewInput in) throws SQLException {
        StatementMatchStatus status = switch (in.decision().toUpperCase(Locale.ROOT)) {
            case "CONFIRM" -> StatementMatchStatus.MATCHED;
            case "REJECT" -> StatementMatchStatus.UNMATCHED;
            case "AMBIGUOUS" -> StatementMatchStatus.AMBIGUOUS_MULTI_CANDIDATE;
            default -> throw new IllegalArgumentException("未知匹配复核动作: " + in.decision());
        };

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                BankStatementLine line = repository.findStatementLineById(c, in.lineId())
                        .orElseThrow(() -> new IllegalArgumentException("未找到对账单明细行: " + in.lineId()));
                BankStatementBatch batch = repository.findStatementBatchById(c, line.batchId())
                        .orElseThrow(() -> new IllegalStateException("对账单批次记录不存在: " + line.batchId()));

                // Enforce TR03 independent natural-person match review SoD
                TreasuryRules.requireIndependentPerson(batch.importedBy(), actor.personId(), "银行流水匹配复核");

                boolean ok = repository.updateStatementLineMatch(
                        c, in.lineId(), status, in.targetOrderId(), in.targetJournalLineId(), actor.personId());
                if (!ok) throw new IllegalArgumentException("对账单明细行更新失败: " + in.lineId());
                recordEvent(c, scope, "BANK_STATEMENT_LINE", in.lineId(), "REVIEW_MATCH_" + status, actor, in);
                c.commit();
                return true;
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    // ==========================================
    // TR04: Reconciliation Sheet & Cash Forecast
    // ==========================================

    public BankReconciliation createBankReconciliation(
            Scope scope, Actor actor, CreateReconciliationInput in) throws SQLException {
        BigDecimal stmtBal = TreasuryRules.parseCny(in.bankStatementBalance(), "银行对账单余额");
        BigDecimal ledgerBal = TreasuryRules.parseCny(in.ledgerBalance(), "企业总账日记账余额");
        UUID recId = UUID.randomUUID();

        BankReconciliation rec = TreasuryRules.computeBankReconciliation(
                recId,
                scope.tenantId(),
                scope.bookId(),
                in.accountId(),
                in.periodId(),
                in.periodNo(),
                in.cutoffDate(),
                stmtBal,
                ledgerBal,
                in.unreachedItems(),
                actor.personId()
        );

        List<UnreachedItem> unreachedItems = new ArrayList<>();
        for (UnreachedItemInput it : in.unreachedItems()) {
            BigDecimal amt = TreasuryRules.parsePositiveCny(it.amount(), "未达金额");
            int aging = TreasuryRules.calculateAgingDays(it.recordedDate(), in.cutoffDate());
            boolean isWarning = TreasuryRules.isOverdueWarning(aging);

            unreachedItems.add(new UnreachedItem(
                    UUID.randomUUID(),
                    scope.tenantId(),
                    scope.bookId(),
                    recId,
                    in.accountId(),
                    UnreachedItemType.valueOf(it.itemType()),
                    it.recordedDate(),
                    amt,
                    it.summary(),
                    aging,
                    isWarning,
                    UnreachedItemStatus.PENDING,
                    isWarning ? "自动触发长期未达预警（账龄超30天）" : null,
                    it.supplementaryEvidenceId(),
                    Instant.now()
            ));
        }

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                repository.createBankReconciliation(c, rec);
                repository.createUnreachedItems(c, unreachedItems);
                recordEvent(c, scope, "BANK_RECONCILIATION", recId, "CREATE_RECONCILIATION", actor, rec);
                c.commit();
                return rec;
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    public BankReconciliation confirmBankReconciliation(Scope scope, Actor actor, UUID recId) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                BankReconciliation current = repository.findBankReconciliationById(c, recId)
                        .orElseThrow(() -> new IllegalArgumentException("银行余额调节表不存在: " + recId));

                BankReconciliation confirmed = TreasuryRules.evaluateReconciliationConfirmation(current, actor.personId());

                boolean ok = repository.updateBankReconciliationStatus(
                        c, recId, confirmed.status(), confirmed.reviewerPersonId(), current.version());
                if (!ok) throw new ConcurrentModificationException("余额调节表并发修改冲突");

                recordEvent(c, scope, "BANK_RECONCILIATION", recId, "CONFIRM_RECONCILIATION", actor, confirmed);
                c.commit();
                return confirmed;
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    public List<CashForecast> generateCashForecast(Scope scope, Actor actor, GenerateForecastInput in) throws SQLException {
        BigDecimal openCash = TreasuryRules.parseCny(in.openingCash(), "期初现金");
        List<CashForecast> list = new ArrayList<>();
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                for (ForecastTier tier : ForecastTier.values()) {
                    CashForecast cf = TreasuryRules.computeForecast(
                            UUID.randomUUID(),
                            scope.tenantId(),
                            scope.bookId(),
                            in.accountId(),
                            in.horizonDays(),
                            in.forecastDate(),
                            openCash,
                            tier,
                            in.items()
                    );
                    repository.createCashForecast(c, cf);
                    list.add(cf);
                }
                recordEvent(c, scope, "CASH_FORECAST", in.accountId(), "GENERATE_FORECAST", actor, list);
                c.commit();
                return list;
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    // ==========================================
    // Helpers
    // ==========================================

    @SuppressWarnings("unchecked")
    private List<RawStatementLine> parseJsonStatement(String json) {
        try {
            List<Map<String, Object>> list = mapper.readValue(json, List.class);
            List<RawStatementLine> res = new ArrayList<>();
            int no = 1;
            for (Map<String, Object> m : list) {
                LocalDate txDate = LocalDate.parse((String) m.get("transactionDate"));
                LocalDate valDate = m.containsKey("valueDate") && m.get("valueDate") != null
                        ? LocalDate.parse((String) m.get("valueDate")) : txDate;
                StatementDirection dir = StatementDirection.valueOf(((String) m.get("direction")).toUpperCase(Locale.ROOT));
                BigDecimal amt = TreasuryRules.parsePositiveCny(String.valueOf(m.get("amount")), "流水金额");
                BigDecimal bal = TreasuryRules.parseCny(String.valueOf(m.get("balanceAfter")), "交易后余额");
                String cpAcct = (String) m.get("counterpartyAccountNo");
                String cpName = (String) m.get("counterpartyName");
                String txNo = (String) m.get("transactionNo");
                String sum = (String) m.get("summary");
                res.add(new RawStatementLine(no++, txDate, valDate, dir, amt, bal, cpAcct, cpName, txNo, sum));
            }
            return res;
        } catch (Exception e) {
            throw new IllegalArgumentException("无法解析JSON格式银行流水: " + e.getMessage(), e);
        }
    }

    private void recordEvent(
            Connection c, Scope scope, String entityType, UUID entityId, String action, Actor actor, Object payload) throws SQLException {
        try {
            String json = mapper.writeValueAsString(payload);
            repository.recordTreasuryEvent(c, new TreasuryEvent(
                    UUID.randomUUID(),
                    scope.tenantId(),
                    scope.bookId(),
                    entityType,
                    entityId,
                    action,
                    actor.personId(),
                    json,
                    Instant.now()
            ));
        } catch (Exception e) {
            throw new SQLException("事件序列化失败", e);
        }
    }
}
