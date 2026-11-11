package com.lumira.finance.archiveai;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * Scoped Finance AI Inquiry/OCR Suggestions (AI01), Anomaly Detection (AI02),
 * and Controlled ActionPlan Engine with Tiered Major-Action Block (AIX01-AIX03).
 */
public final class FinanceAiEngine {

    public static final String EXTERNAL_MODEL_PROVIDER_NOT_CONFIGURED = "NOT_CONFIGURED";

    public record OcrSuggestion(
            String ocrText,
            List<String> tokens,
            String suggestedAccountCode,
            String accountName,
            BigDecimal confidence,
            String externalModelProvider, // strictly NOT_CONFIGURED
            boolean mandatoryHumanReview // strictly true
    ) {}

    public record AnomalyAlert(
            UUID id,
            String ruleCode, // UNBALANCED_JOURNAL, AMOUNT_SPIKE_3SIGMA, ROUND_NUMBER_CLUSTER, WEEKEND_TRANSACTION, DUPLICATE_INVOICE_REF, NEGATIVE_BALANCE
            String severity, // LOW, MEDIUM, HIGH, CRITICAL
            String targetEntityType,
            UUID targetEntityId,
            String description,
            String status, // OPEN, ASSIGNED_FOR_VERIFICATION, RESOLVED, CLOSED_FALSE_POSITIVE
            UUID assignedPersonId,
            String closureReason,
            String closureEvidenceDigest,
            UUID closedBy,
            Instant closedAt
    ) {}

    public record ActionItem(
            UUID actionId,
            String command, // JOURNAL_CREATE_DRAFT, JOURNAL_SUBMIT, JOURNAL_POST, PERIOD_CLOSE, YEAR_CLOSE_EXECUTE, TAX_PACKAGE_BUILD, TAX_SUBMIT
            String risk, // R1, R2, R3, R4
            List<UUID> dependsOn,
            Map<String, Object> arguments
    ) {}

    public record ActionPlan(
            UUID planId,
            UUID tenantId,
            UUID requesterId,
            long version,
            long contentVersion,
            String status, // PROPOSED, DRY_RUN_PASSED, AWAITING_APPROVAL, APPROVED, EXECUTING, SUCCEEDED, PARTIAL, FAILED, EXPIRED, REVOKED, BLOCKED_MAJOR_ACTION
            String policyVersion,
            String currency,
            BigDecimal expectedTotalAmount,
            List<ActionItem> actions,
            String payloadDigest,
            String dryRunDigest,
            UUID approverId,
            Instant approvedAt,
            String executionTokenHash,
            Instant executionTokenExpiresAt,
            Instant expiresAt
    ) {}

    public record InquiryAnswer(
            String query,
            String answer,
            List<String> citations, // e.g. "CAS 4 第十四条", "CAS 22 第四十条"
            String standardVersion,
            boolean draftCreatedOnly
    ) {}

    // AI01: Local OCR Account Suggestion
    public static OcrSuggestion suggestAccountFromOcr(String ocrText) {
        if (ocrText == null || ocrText.isBlank()) {
            return new OcrSuggestion("", List.of(), "660299", "管理费用-其他",
                    new BigDecimal("0.3000"), EXTERNAL_MODEL_PROVIDER_NOT_CONFIGURED, true);
        }

        List<String> tokens = Arrays.stream(ocrText.split("[,\\s;，。]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        String lower = ocrText.toLowerCase(Locale.ROOT);
        String code = "660299";
        String name = "管理费用-其他";
        BigDecimal conf = new BigDecimal("0.5000");

        if (lower.contains("机票") || lower.contains("差旅") || lower.contains("酒店") || lower.contains("住宿") || lower.contains("高铁") || lower.contains("打车")) {
            code = "660201";
            name = "管理费用-差旅费";
            conf = new BigDecimal("0.9500");
        } else if (lower.contains("办公") || lower.contains("文具") || lower.contains("打印") || lower.contains("纸张")) {
            code = "660202";
            name = "管理费用-办公费";
            conf = new BigDecimal("0.9200");
        } else if (lower.contains("技术服务") || lower.contains("云服务") || lower.contains("软件") || lower.contains("咨询")) {
            code = "660203";
            name = "管理费用-技术咨询服务费";
            conf = new BigDecimal("0.9000");
        } else if (lower.contains("销售") || lower.contains("货款") || lower.contains("收入")) {
            code = "600101";
            name = "主营业务收入";
            conf = new BigDecimal("0.9400");
        } else if (lower.contains("利息") || lower.contains("手续费")) {
            code = "660301";
            name = "财务费用-利息支出";
            conf = new BigDecimal("0.9100");
        }

        return new OcrSuggestion(ocrText, tokens, code, name, conf, EXTERNAL_MODEL_PROVIDER_NOT_CONFIGURED, true);
    }

    // AI02: Anomaly Detection
    public static List<AnomalyAlert> detectAnomalies(
            UUID journalId,
            LocalDate accountingDate,
            BigDecimal debitSum,
            BigDecimal creditSum,
            List<BigDecimal> lineAmounts,
            BigDecimal historicalMeanAmount,
            BigDecimal standardDeviation
    ) {
        return detectAnomalies(journalId, accountingDate, debitSum, creditSum, lineAmounts, List.of(), historicalMeanAmount, standardDeviation);
    }

    public static List<AnomalyAlert> detectAnomalies(
            UUID journalId,
            LocalDate accountingDate,
            BigDecimal debitSum,
            BigDecimal creditSum,
            List<BigDecimal> lineAmounts,
            List<String> invoiceRefs,
            BigDecimal historicalMeanAmount,
            BigDecimal standardDeviation
    ) {
        List<AnomalyAlert> alerts = new ArrayList<>();

        // 1. Unbalanced Journal
        if (debitSum.compareTo(creditSum) != 0) {
            alerts.add(new AnomalyAlert(UUID.randomUUID(), "UNBALANCED_JOURNAL", "CRITICAL",
                    "JOURNAL", journalId, "借贷不平: 借方合计=" + debitSum.toPlainString() + ", 贷方合计=" + creditSum.toPlainString(),
                    "OPEN", null, null, null, null, null));
        }

        // 2. Weekend Transaction
        DayOfWeek dow = accountingDate.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            alerts.add(new AnomalyAlert(UUID.randomUUID(), "WEEKEND_TRANSACTION", "LOW",
                    "JOURNAL", journalId, "非工作日会计记账: " + accountingDate + " (" + dow + ")",
                    "OPEN", null, null, null, null, null));
        }

        // 3. Amount Spike (> 3 sigma)
        if (historicalMeanAmount != null && standardDeviation != null && standardDeviation.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal threshold = historicalMeanAmount.add(standardDeviation.multiply(BigDecimal.valueOf(3)));
            for (BigDecimal amt : lineAmounts) {
                if (amt.compareTo(threshold) > 0) {
                    alerts.add(new AnomalyAlert(UUID.randomUUID(), "AMOUNT_SPIKE_3SIGMA", "HIGH",
                            "JOURNAL", journalId, "单行金额异常突增: " + amt.toPlainString() + " 超过3倍标准差阈值 " + threshold.toPlainString(),
                            "OPEN", null, null, null, null, null));
                    break;
                }
            }
        }

        // 4. Round Number Cluster
        int roundCount = 0;
        for (BigDecimal amt : lineAmounts) {
            if (amt.remainder(BigDecimal.valueOf(10000)).compareTo(BigDecimal.ZERO) == 0 && amt.compareTo(BigDecimal.ZERO) > 0) {
                roundCount++;
            }
        }
        if (roundCount >= 3) {
            alerts.add(new AnomalyAlert(UUID.randomUUID(), "ROUND_NUMBER_CLUSTER", "MEDIUM",
                    "JOURNAL", journalId, "多笔大额整数字段聚集: 包含 " + roundCount + " 笔整万元交易",
                    "OPEN", null, null, null, null, null));
        }

        // 5. Negative Balance / Negative Line Amount
        for (BigDecimal amt : lineAmounts) {
            if (amt.compareTo(BigDecimal.ZERO) < 0) {
                alerts.add(new AnomalyAlert(UUID.randomUUID(), "NEGATIVE_BALANCE", "HIGH",
                        "JOURNAL", journalId, "凭证行包含负数借贷金额异常: " + amt.toPlainString(),
                        "OPEN", null, null, null, null, null));
                break;
            }
        }

        // 6. Duplicate Invoice Reference
        if (invoiceRefs != null && !invoiceRefs.isEmpty()) {
            Set<String> seen = new HashSet<>();
            for (String ref : invoiceRefs) {
                if (ref != null && !ref.isBlank()) {
                    if (!seen.add(ref.trim())) {
                        alerts.add(new AnomalyAlert(UUID.randomUUID(), "DUPLICATE_INVOICE_REF", "CRITICAL",
                                "JOURNAL", journalId, "发票凭据编号重复关联异常: " + ref.trim(),
                                "OPEN", null, null, null, null, null));
                        break;
                    }
                }
            }
        }

        return Collections.unmodifiableList(alerts);
    }

    public static AnomalyAlert closeAnomalyAsFalsePositive(
            AnomalyAlert alert,
            UUID closedBy,
            String closureReason,
            String evidenceDigest
    ) {
        Objects.requireNonNull(alert, "alert must not be null");
        Objects.requireNonNull(closedBy, "closedBy must not be null");
        if (closureReason == null || closureReason.isBlank()) {
            throw new IllegalArgumentException("closureReason is mandatory for false-positive closure");
        }

        return new AnomalyAlert(
                alert.id(),
                alert.ruleCode(),
                alert.severity(),
                alert.targetEntityType(),
                alert.targetEntityId(),
                alert.description(),
                "CLOSED_FALSE_POSITIVE",
                alert.assignedPersonId(),
                closureReason,
                evidenceDigest,
                closedBy,
                Instant.now()
        );
    }

    // AIX01: Citation Explainer
    public static InquiryAnswer explainInquiry(String query) {
        String lower = query != null ? query.toLowerCase(Locale.ROOT) : "";
        if (lower.contains("折旧")) {
            return new InquiryAnswer(query,
                    "根据企业会计准则第4号——固定资产，企业应当根据固定资产所含经济利益预期消耗方式选择折旧方法，可选年限平均法、工作量法、双倍余额递减法和年数总和法。折旧方法一经确定，不得随意变更。",
                    List.of("CAS 4 第十四条", "CAS 4 第十五条"),
                    "CAS_2019",
                    true
            );
        } else if (lower.contains("收入") || lower.contains("确认")) {
            return new InquiryAnswer(query,
                    "根据企业会计准则第14号——收入，企业应当在履行了合同中的履约义务，即在客户取得相关商品或服务控制权时确认收入。五步法模型：识别合同、识别单项履约义务、确定交易价格、分摊交易价格、履行义务确认收入。",
                    List.of("CAS 14 第四条", "CAS 14 第五条"),
                    "CAS_2019",
                    true
            );
        } else if (lower.contains("研发") || lower.contains("加计扣除") || lower.contains("税")) {
            return new InquiryAnswer(query,
                    "根据企业所得税法及财税〔2018〕99号，企业开展研发活动中实际发生的研发费用，未形成无形资产计入当期损益的，在按规定据实扣除的基础上，按照实际发生额的比例在税前加计扣除。",
                    List.of("企业所得税法实施条例 第九十五条", "财税〔2018〕99号"),
                    "CIT_2018",
                    true
            );
        } else {
            return new InquiryAnswer(query,
                    "根据企业会计准则基本准则，企业应当以权责发生制为基础进行会计确认、计量和报告。凡是当期已经实现的收入和已经发生或者应当负担的费用，不论款项是否收付，都应当作为当期的收入和费用处理。",
                    List.of("CAS 基本准则 第九条"),
                    "CAS_2019",
                    true
            );
        }
    }

    // AIX02 & AIX03: ActionPlan Dry-Run, Approval & Tiered Major-Action Block
    public static String computeDryRunDigest(List<ActionItem> actions) {
        StringBuilder sb = new StringBuilder();
        for (ActionItem a : actions) {
            sb.append(a.actionId()).append(":").append(a.command()).append(":").append(a.risk()).append(";");
        }
        return sha256Hex(sb.toString());
    }

    public static ActionPlan approveActionPlan(
            ActionPlan plan,
            UUID approverId,
            long expectedVersion,
            String expectedPayloadDigest,
            String expectedDryRunDigest
    ) {
        Objects.requireNonNull(plan, "plan must not be null");
        Objects.requireNonNull(approverId, "approverId must not be null");

        if (approverId.equals(plan.requesterId())) {
            throw new IllegalStateException("SOD_VIOLATION: Requester cannot approve their own ActionPlan");
        }
        if (plan.version() != expectedVersion) {
            throw new IllegalStateException("VERSION_CONFLICT: Expected version " + expectedVersion + " but was " + plan.version());
        }
        if (!plan.payloadDigest().equalsIgnoreCase(expectedPayloadDigest)) {
            throw new IllegalStateException("CONTENT_DIGEST_MISMATCH: Payload digest does not match approved digest");
        }
        if (!plan.dryRunDigest().equalsIgnoreCase(expectedDryRunDigest)) {
            throw new IllegalStateException("DRY_RUN_DIGEST_MISMATCH: Dry-run digest does not match approved dry-run outcome");
        }

        // Issue 300-second short-lived execution token
        Instant now = Instant.now();
        Instant tokenExpiry = now.plusSeconds(300);
        String token = UUID.randomUUID().toString();
        String tokenHash = sha256Hex(token);

        return new ActionPlan(
                plan.planId(),
                plan.tenantId(),
                plan.requesterId(),
                plan.version() + 1,
                plan.contentVersion(),
                "APPROVED",
                plan.policyVersion(),
                plan.currency(),
                plan.expectedTotalAmount(),
                plan.actions(),
                plan.payloadDigest(),
                plan.dryRunDigest(),
                approverId,
                now,
                tokenHash,
                tokenExpiry,
                plan.expiresAt()
        );
    }

    public static void validateActionPlanExecution(ActionPlan plan, String executionToken) {
        Objects.requireNonNull(plan, "plan must not be null");
        if (!"APPROVED".equals(plan.status())) {
            throw new IllegalStateException("ACTION_PLAN_NOT_APPROVED: Plan status is " + plan.status() + ", cannot execute");
        }
        if (plan.executionTokenExpiresAt() == null || Instant.now().isAfter(plan.executionTokenExpiresAt())) {
            throw new IllegalStateException("EXECUTION_TOKEN_EXPIRED: Short-lived execution token has expired");
        }
        String tokenHash = sha256Hex(executionToken != null ? executionToken : "");
        if (!tokenHash.equalsIgnoreCase(plan.executionTokenHash())) {
            throw new IllegalStateException("INVALID_EXECUTION_TOKEN: Provided token does not match authorized execution token");
        }

        // Tiered Major-Action Block (AIX03): Any R4 action or major external operation is strictly prohibited from AI automated execution!
        for (ActionItem act : plan.actions()) {
            String cmd = (act.command() != null) ? act.command().toUpperCase(Locale.ROOT) : "";
            boolean isMajorOperation = cmd.startsWith("TAX_") ||
                    cmd.startsWith("INVOICE_") ||
                    cmd.contains("PAYMENT") ||
                    cmd.contains("DISBURSEMENT");

            if ("R4".equalsIgnoreCase(act.risk()) || isMajorOperation) {
                throw new IllegalStateException("AI_MAJOR_ACTION_BLOCKED: Action " + act.actionId() + " (" + act.command()
                        + ") has risk level R4 or targets external major operations. AI execution of external tax filing, invoice issuing/invalidation, tax payment, or bank disbursement is strictly prohibited. Must be manually executed by authorized human finance officer.");
            }
        }
    }

    public static String sha256Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
