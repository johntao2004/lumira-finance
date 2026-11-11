package com.lumira.finance.iamops;

import com.lumira.finance.iamops.IamOpsModels.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.*;

public final class IamOpsRules {
    private IamOpsRules() {}

    public static final String ORG_ATTESTED_KIND = "ORG_ATTESTED";
    public static final String LOCAL_DRILL_ONLY = "LOCAL_DRILL_ONLY";

    // ------------------------------------------------------------------------
    // Masking Rules
    // ------------------------------------------------------------------------
    public static String maskPayroll(BigDecimal amount, boolean hasUnmaskPermission) {
        if (amount == null) return "0.00";
        if (hasUnmaskPermission) {
            return amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
        }
        return "***.00";
    }

    public static String maskIdCard(String idCard, boolean hasUnmaskPermission) {
        if (idCard == null || idCard.isBlank()) return "";
        if (hasUnmaskPermission) return idCard.trim();
        String s = idCard.trim();
        if (s.length() < 10) return "******";
        // Keep first 6 and last 4, mask middle with 8 asterisks
        return s.substring(0, 6) + "********" + s.substring(s.length() - 4);
    }

    public static String maskBankAccount(String bankAccount, boolean hasUnmaskPermission) {
        if (bankAccount == null || bankAccount.isBlank()) return "";
        if (hasUnmaskPermission) return bankAccount.trim();
        String s = bankAccount.trim();
        if (s.length() < 10) return "******";
        // Keep first 6 and last 4, mask middle with 6 asterisks
        return s.substring(0, 6) + "******" + s.substring(s.length() - 4);
    }

    // ------------------------------------------------------------------------
    // Canonical Role Normalization
    // ------------------------------------------------------------------------
    public static String canonicalRole(String role) {
        if (role == null || role.isBlank()) return "";
        String r = role.trim().toUpperCase();
        return switch (r) {
            case "REVIEWER" -> "APPROVER";
            case "MAKER", "POSTER" -> "ACCOUNTANT";
            case "VIEWER" -> "READER";
            default -> r;
        };
    }

    // ------------------------------------------------------------------------
    // SoD Conflict Enforcement
    // ------------------------------------------------------------------------
    public static void checkSodConflict(Set<String> existingRoles, String candidateRole, List<SodMatrixRule> matrix) {
        if (candidateRole == null || existingRoles == null || existingRoles.isEmpty() || matrix == null || matrix.isEmpty()) {
            return;
        }
        String normCandidate = canonicalRole(candidateRole);
        Set<String> normExisting = new HashSet<>();
        for (String r : existingRoles) {
            if (r != null && !r.isBlank()) {
                normExisting.add(canonicalRole(r));
            }
        }

        for (SodMatrixRule rule : matrix) {
            if (!rule.isActive()) continue;
            String ruleRoleA = canonicalRole(rule.roleA());
            String ruleRoleB = canonicalRole(rule.roleB());
            boolean matchesA = ruleRoleA.equalsIgnoreCase(normCandidate) && normExisting.contains(ruleRoleB);
            boolean matchesB = ruleRoleB.equalsIgnoreCase(normCandidate) && normExisting.contains(ruleRoleA);
            if (matchesA || matchesB) {
                throw new IllegalStateException(
                        "职责分离（SoD）冲突阻断：" + rule.roleA() + " 与 " + rule.roleB() + " 不得同时兼任。依据：" + rule.statutoryBasis()
                );
            }
        }
    }

    // ------------------------------------------------------------------------
    // Auditor Read-Only Statutory Invariant
    // ------------------------------------------------------------------------
    private static final Set<String> WRITE_ACTIONS = Set.of(
            "CREATE", "POST", "REVERSE", "DELETE", "UPDATE", "WRITE",
            "CREATE_JOURNAL", "POST_JOURNAL", "REVERSE_JOURNAL", "DELETE_JOURNAL"
    );

    public static void checkAuditorReadOnly(Set<String> roles, String action) {
        if (roles == null || action == null) return;
        boolean isAuditor = roles.stream().anyMatch(r -> "AUDITOR".equalsIgnoreCase(canonicalRole(r)));
        if (isAuditor) {
            String normAction = action.trim().toUpperCase();
            if (WRITE_ACTIONS.contains(normAction)) {
                throw new IllegalStateException("ROLE_RESTRICTED_READ_ONLY: 审计人员角色受法定只读限制，严禁执行记账、冲销、修改或删除操作 (action: " + action + ")");
            }
        }
    }

    // ------------------------------------------------------------------------
    // Tiered Monetary Threshold Matching
    // ------------------------------------------------------------------------
    public static ApprovalThreshold findThreshold(BigDecimal amount, String entityType, List<ApprovalThreshold> thresholds) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("审批金额须为非负数");
        }
        List<ApprovalThreshold> matching = thresholds.stream()
            .filter(th -> th.entityType().equalsIgnoreCase(entityType))
            .sorted((a, b) -> a.minAmount().compareTo(b.minAmount()))
            .toList();

        if (matching.isEmpty()) {
            throw new IllegalStateException("未匹配到适用的阶梯金额审批规则 (金额: " + amount.toPlainString() + ", 类型: " + entityType + ")");
        }

        // 1. Direct match within [minAmount, maxAmount]
        for (ApprovalThreshold th : matching) {
            boolean aboveMin = amount.compareTo(th.minAmount()) >= 0;
            boolean belowMax = (th.maxAmount() == null) || (amount.compareTo(th.maxAmount()) <= 0);
            if (aboveMin && belowMax) {
                return th;
            }
        }

        // 2. Continuous boundary fallback: if amount exceeds tier i's maxAmount but is less than tier i+1's minAmount,
        // it escalates to the higher tier i+1
        for (int i = 0; i < matching.size() - 1; i++) {
            ApprovalThreshold current = matching.get(i);
            ApprovalThreshold next = matching.get(i + 1);
            if (current.maxAmount() != null
                    && amount.compareTo(current.maxAmount()) > 0
                    && amount.compareTo(next.minAmount()) < 0) {
                return next;
            }
        }

        // 3. Fallback for amounts exceeding all configured tiers if the highest tier has a maxAmount
        ApprovalThreshold last = matching.get(matching.size() - 1);
        if (amount.compareTo(last.minAmount()) >= 0) {
            return last;
        }

        throw new IllegalStateException("未匹配到适用的阶梯金额审批规则 (金额: " + amount.toPlainString() + ", 类型: " + entityType + ")");
    }

    // ------------------------------------------------------------------------
    // Multi-Stage Approver Validation (Distinct Person across stages)
    // ------------------------------------------------------------------------
    public static void validateApprovalStageDecider(ApprovalTask task, int stageOrder, UUID deciderPerson) {
        if (task.requestedByPerson().equals(deciderPerson)) {
            throw new IllegalStateException("职责分离冲突：制单人/经办人不得审核本人发起的任务（禁止自审）");
        }
        if (task.stages() != null) {
            for (ApprovalStageDecision stage : task.stages()) {
                if (stage.stageOrder() < stageOrder && deciderPerson.equals(stage.decidedByPerson())) {
                    throw new IllegalStateException("职责分离冲突：前序审批人不得重复审批后续阶段（禁止同一自然人跨阶段兼审）");
                }
            }
        }
    }

    // ------------------------------------------------------------------------
    // Delegation Validation
    // ------------------------------------------------------------------------
    public static void validateDelegation(
            UUID delegatorPerson,
            UUID delegateePerson,
            OffsetDateTime from,
            OffsetDateTime to,
            Set<String> delegateeRoles,
            String delegatedRole,
            List<SodMatrixRule> matrix
    ) {
        if (delegatorPerson.equals(delegateePerson)) {
            throw new IllegalArgumentException("不得向本人发起转交代理（禁止自我代理）");
        }
        if (to.isBefore(from) || to.isEqual(from)) {
            throw new IllegalArgumentException("代理截止时间必须晚于生效时间");
        }
        // Enforce SoD on delegatee: delegatee cannot hold conflicting roles with the delegated role
        checkSodConflict(delegateeRoles, delegatedRole, matrix);
    }

    // ------------------------------------------------------------------------
    // Emergency Audit Grant Dual Approval Validation
    // ------------------------------------------------------------------------
    public static void validateEmergencyGrantApprovals(UUID requesterPerson, UUID approver1Person, UUID approver2Person) {
        if (approver1Person != null && approver1Person.equals(requesterPerson)) {
            throw new IllegalArgumentException("申请人不得作为紧急审计访问第一审批人（禁止自审）");
        }
        if (approver2Person != null && approver2Person.equals(requesterPerson)) {
            throw new IllegalArgumentException("申请人不得作为紧急审计访问第二审批人（禁止自审）");
        }
        if (approver1Person != null && approver2Person != null && approver1Person.equals(approver2Person)) {
            throw new IllegalArgumentException("双人审批必须由两个不同自然人分别审核（禁止同人兼审）");
        }
    }

    // ------------------------------------------------------------------------
    // Cloud DR Claim Verification
    // ------------------------------------------------------------------------
    public static void validateCloudDrStatus(String cloudDrStatus, boolean isExternallyVerified) {
        if ("PROD_REPLICATED".equalsIgnoreCase(cloudDrStatus) && !isExternallyVerified) {
            throw new IllegalArgumentException("云端灾备状态未经验证前严禁宣称生产复制 (PROD_REPLICATED)，必须标记为 LOCAL_DRILL_ONLY");
        }
    }

    // ------------------------------------------------------------------------
    // Export Scope Validation
    // ------------------------------------------------------------------------
    public static void validateExportAuthorization(
            DataScopeGrant grant,
            String requestedDept,
            String requestedProj,
            boolean requestUnmasked
    ) {
        if (grant == null || !"ACTIVE".equalsIgnoreCase(grant.status())) {
            throw new IllegalStateException("无有效数据范围授权，拒绝导出");
        }
        if (!grant.canExport()) {
            throw new IllegalStateException("当前角色未被授予异步导出权限 (can_export=false)");
        }
        if (requestUnmasked && !grant.canUnmaskSensitive()) {
            throw new IllegalStateException("未被授予敏感字段脱敏导出特权，禁止导出明文薪酬或身份证信息");
        }
        if ("DEPARTMENT".equalsIgnoreCase(grant.scopeType())) {
            if (requestedDept == null || !requestedDept.equals(grant.departmentId())) {
                throw new IllegalStateException("导出部门范围超出授权部门：" + grant.departmentId());
            }
        } else if ("PROJECT".equalsIgnoreCase(grant.scopeType())) {
            if (requestedProj == null || !requestedProj.equals(grant.projectId())) {
                throw new IllegalStateException("导出项目范围超出授权项目：" + grant.projectId());
            }
        }
    }

    // ------------------------------------------------------------------------
    // Diagnostic Grant Active Check
    // ------------------------------------------------------------------------
    public static boolean isDiagnosticGrantActive(DiagnosticGrant grant, OffsetDateTime now) {
        if (grant == null) return false;
        if (!"ACTIVE".equalsIgnoreCase(grant.status())) return false;
        if (grant.revokedAt() != null) return false;
        return now.isAfter(grant.activatedAt()) && now.isBefore(grant.expiresAt());
    }

    // ------------------------------------------------------------------------
    // Helper SHA-256
    // ------------------------------------------------------------------------
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
