package com.lumira.finance;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuthorizationContextProjectionPort;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

/** Bounded ARC01/ARC03 archive & ustar export service; legalArchiveComplete is always false. */
@Service
public final class FinanceArchiveExports extends FinanceProfitClosingAccess {
    private static final List<String> FAMILIES =
            List.of(
                    "scope",
                    "accounting",
                    "evidence",
                    "cash",
                    "people-organizations",
                    "reports",
                    "tax-assets-contracts",
                    "audit",
                    "reading-kit");
    private final AdministrationPort permissions;

    public record PreflightInput(List<UUID> yearIds, String purpose) {}

    public record CreateRequestInput(
            UUID preflightId, String proposalDigest, String idempotencyKey) {}

    public record ApproveInput(
            long version, String proposalDigest, String reason, String idempotencyKey) {}

    public record GenerateInput(long version, String idempotencyKey) {}

    public record ReceiptInput(
            long version,
            String artifactSha256,
            String manifestSha256,
            String verificationReportSha256,
            String reason,
            String idempotencyKey) {}

    public record DownloadedArtifact(
            UUID requestId, byte[] bytes, String artifactSha256, String manifestSha256) {}

    public FinanceArchiveExports(
            FinanceStore db,
            FinanceService finance,
            FinanceJournalPersonGate gate,
            PlatformTransactionManager tx,
            AuthorizationContextProjectionPort authorization,
            AdministrationPort permissions) {
        super(db, finance, gate, tx, authorization);
        this.permissions = permissions;
    }

    private void requireExportPermission(String principal) {
        permissions.require(principal, "platform:finance:export");
    }

    private static void requireKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{8,128}"))
            throw FinanceException.invalid("幂等请求键格式无效");
    }

    private static void requireDigest(String d, String label) {
        if (d == null || !d.matches("[a-f0-9]{64}"))
            throw FinanceException.invalid(label + "必须是64位小写SHA-256摘要");
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 1000)
            throw FinanceException.invalid("操作原因不能为空且不超过1000字符");
    }

    public Object list(String principal, UUID book) {
        requireExportPermission(principal);
        return transaction(
                false,
                c -> {
                    var b = finance.originalBookScope(principal, book, "read");
                    UUID tenant = id(b.get("tenant_id"));
                    var rows =
                            c.list(
                                    "SELECT r.id,r.preflight_id,r.purpose,r.proposal_digest,r.state,"
                                        + "r.version,r.manifest_sha256,r.artifact_sha256,r.artifact_bytes,"
                                        + "r.artifact_expires_at,r.legal_archive_complete,r.created_at,"
                                        + "p.year_ids,p.blockers FROM plugin_finance_archive_request r "
                                        + "JOIN plugin_finance_archive_preflight p "
                                        + "ON (p.tenant_id,p.book_id,p.id)=(r.tenant_id,r.book_id,r.preflight_id) "
                                        + "WHERE r.tenant_id=? AND r.book_id=? ORDER BY r.created_at DESC, r.id DESC LIMIT 50",
                                    tenant,
                                    book);
                    List<Map<String, Object>> items = new ArrayList<>();
                    for (var r : rows) {
                        items.add(summaryRow(r));
                    }
                    return map(
                            "items",
                            items,
                            "legalArchiveComplete",
                            false,
                            "retentionDestructionEnabled",
                            false);
                });
    }

    public Object preflight(
            String principal, UUID book, PreflightInput in, HttpServletRequest request) {
        requireExportPermission(principal);
        if (in.yearIds() == null
                || in.yearIds().isEmpty()
                || in.yearIds().size() > 20
                || new HashSet<>(in.yearIds()).size() != in.yearIds().size())
            throw FinanceException.invalid("yearIds必须为1至20个不重复的年度UUID");
        if (in.purpose() == null
                || !Set.of("ARCHIVE", "EXIT", "DIAGNOSTIC").contains(in.purpose()))
            throw FinanceException.invalid("导出目的必须为 ARCHIVE、EXIT 或 DIAGNOSTIC");
        List<UUID> sortedYears =
                in.yearIds().stream().sorted(Comparator.comparing(UUID::toString)).toList();
        return transaction(
                true,
                c -> {
                    var b = finance.originalBookScope(principal, book, "read");
                    UUID tenant = id(b.get("tenant_id"));
                    UUID actorId = id(b.get("actor_id"));
                    var who = gate.current(actorId, tenant, false, request);
                    SnapshotSummary snap = inspectSnapshot(c, principal, tenant, book, sortedYears, in.purpose());
                    UUID preflightId = UUID.randomUUID();
                    Instant expiresAt = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
                    Map<String, Object> proposal =
                            map(
                                    "tenantId",
                                    tenant.toString(),
                                    "bookId",
                                    book.toString(),
                                    "yearIds",
                                    sortedYears.stream().map(UUID::toString).toList(),
                                    "purpose",
                                    in.purpose(),
                                    "sourceVector",
                                    snap.sourceVector,
                                    "coverage",
                                    snap.coverage,
                                    "counts",
                                    snap.counts,
                                    "totals",
                                    snap.totals);
                    String proposalDigest =
                            FinanceArchiveUstar.sha256Hex(
                                    FinanceArchiveUstar.canonicalJson(proposal)
                                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    c.update(
                            "INSERT INTO plugin_finance_archive_preflight("
                                + "tenant_id,book_id,id,purpose,year_ids,source_vector,coverage,blockers,"
                                + "counts,totals,proposal_digest,prepared_by,expires_at) "
                                + "VALUES(?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?,?::jsonb,?)",
                            tenant,
                            book,
                            preflightId,
                            in.purpose(),
                            finance.encode(sortedYears.stream().map(UUID::toString).toList()),
                            finance.encode(snap.sourceVector),
                            finance.encode(snap.coverage),
                            finance.encode(snap.blockers),
                            finance.encode(snap.counts),
                            finance.encode(snap.totals),
                            proposalDigest,
                            finance.encode(who),
                            Timestamp.from(expiresAt));
                    return map(
                            "preflightId",
                            preflightId.toString(),
                            "purpose",
                            in.purpose(),
                            "scope",
                            map(
                                    "tenantId",
                                    tenant.toString(),
                                    "bookId",
                                    book.toString(),
                                    "yearIds",
                                    sortedYears.stream().map(UUID::toString).toList()),
                            "sourceVector",
                            snap.sourceVector,
                            "coverage",
                            snap.coverage,
                            "blockers",
                            snap.blockers,
                            "counts",
                            snap.counts,
                            "totals",
                            snap.totals,
                            "proposalDigest",
                            proposalDigest,
                            "expiresAt",
                            FinanceArchiveUstar.formatInstantMs(expiresAt));
                });
    }

    public Object createRequest(
            String principal, UUID book, CreateRequestInput in, HttpServletRequest request) {
        requireExportPermission(principal);
        if (in.preflightId() == null) throw FinanceException.invalid("preflightId不能为空");
        requireDigest(in.proposalDigest(), "proposalDigest");
        requireKey(in.idempotencyKey());
        return transaction(
                true,
                c -> {
                    var b = finance.originalBookScope(principal, book, "create");
                    if (!allowed(b, "create")) throw error("SCOPE_DENIED", "需要制单权限以发起导出申请");
                    UUID tenant = id(b.get("tenant_id"));
                    UUID actorId = id(b.get("actor_id"));
                    var who = gate.current(actorId, tenant, false, request);
                    String route = "CREATE_REQUEST";
                    String payloadHash =
                            FinanceService.hash(
                                    in.preflightId() + ":" + in.proposalDigest());
                    var cached = replay(c, tenant, book, actorId, route, in.idempotencyKey(), payloadHash);
                    if (cached != null) return cached;

                    var pf =
                            c.one(
                                    "SELECT * FROM plugin_finance_archive_preflight WHERE tenant_id=? AND book_id=? AND id=?",
                                    tenant,
                                    book,
                                    in.preflightId());
                    if (!in.proposalDigest().equals(pf.get("proposal_digest")))
                        throw error("EXPORT_STALE", "导出预检摘要不匹配");
                    Instant expires = ((Timestamp) pf.get("expires_at")).toInstant();
                    if (!expires.isAfter(Instant.now()))
                        throw error("EXPORT_STALE", "导出预检已过期，请重新执行预检");
                    reauthorizeYears(principal, book, parseYearIds(pf.get("year_ids")));

                    UUID reqId = UUID.randomUUID();
                    c.update(
                            "INSERT INTO plugin_finance_archive_request("
                                + "tenant_id,book_id,id,preflight_id,purpose,proposal_digest,state,version,prepared_by) "
                                + "VALUES(?,?,?,?,?,?,'REQUESTED',1,?::jsonb)",
                            tenant,
                            book,
                            reqId,
                            in.preflightId(),
                            pf.get("purpose"),
                            in.proposalDigest(),
                            finance.encode(who));
                    var res = detailEnvelope(c, principal, tenant, book, reqId);
                    saveReplay(c, tenant, book, actorId, route, in.idempotencyKey(), payloadHash, res);
                    return res;
                });
    }

    public Object approve(
            String principal,
            UUID book,
            UUID requestId,
            ApproveInput in,
            HttpServletRequest request) {
        requireExportPermission(principal);
        requireDigest(in.proposalDigest(), "proposalDigest");
        requireReason(in.reason());
        requireKey(in.idempotencyKey());
        return transaction(
                true,
                c -> {
                    var b = finance.originalBookScope(principal, book, "approve");
                    if (!allowed(b, "approve")) throw error("SCOPE_DENIED", "需要独立审签权限以批准导出");
                    UUID tenant = id(b.get("tenant_id"));
                    UUID actorId = id(b.get("actor_id"));
                    var who = gate.current(actorId, tenant, true, request);
                    String route = "APPROVE:" + requestId;
                    String payloadHash =
                            FinanceService.hash(
                                    in.version() + ":" + in.proposalDigest() + ":" + in.reason());
                    var cached = replay(c, tenant, book, actorId, route, in.idempotencyKey(), payloadHash);
                    if (cached != null) return cached;

                    var req =
                            c.one(
                                    "SELECT * FROM plugin_finance_archive_request WHERE tenant_id=? AND book_id=? AND id=? FOR UPDATE",
                                    tenant,
                                    book,
                                    requestId);
                    if (((Number) req.get("version")).longValue() != in.version())
                        throw error("VERSION_CONFLICT", "导出申请版本冲突");
                    if (!"REQUESTED".equals(req.get("state")))
                        throw error("ARCHIVE_STATE_CONFLICT", "仅待审导出申请可批准");
                    if (!in.proposalDigest().equals(req.get("proposal_digest")))
                        throw error("EXPORT_STALE", "批准绑定的预检摘要不一致");
                    independent(who, json(req.get("prepared_by")));

                    var pf =
                            c.one(
                                    "SELECT * FROM plugin_finance_archive_preflight WHERE tenant_id=? AND book_id=? AND id=?",
                                    tenant,
                                    book,
                                    req.get("preflight_id"));
                    if (!((Timestamp) pf.get("expires_at")).toInstant().isAfter(Instant.now()))
                        throw error("EXPORT_STALE", "导出预检已过期");
                    List<UUID> yearIds = parseYearIds(pf.get("year_ids"));
                    SnapshotSummary fresh =
                            inspectSnapshot(c, principal, tenant, book, yearIds, req.get("purpose").toString());
                    if (!finance.encode(finance.canonical(fresh.sourceVector))
                            .equals(finance.encode(finance.canonical(jsonList(pf.get("source_vector"))))))
                        throw error("EXPORT_STALE", "账务来源水位已变化，须重新预检并审批");

                    c.update(
                            "UPDATE plugin_finance_archive_request SET state='APPROVED',version=version+1,"
                                + "approved_by=?::jsonb,approval_reason=? WHERE tenant_id=? AND book_id=? AND id=?",
                            finance.encode(who),
                            in.reason(),
                            tenant,
                            book,
                            requestId);
                    var res = detailEnvelope(c, principal, tenant, book, requestId);
                    saveReplay(c, tenant, book, actorId, route, in.idempotencyKey(), payloadHash, res);
                    return res;
                });
    }

    public Object generate(
            String principal,
            UUID book,
            UUID requestId,
            GenerateInput in,
            HttpServletRequest request) {
        requireExportPermission(principal);
        requireKey(in.idempotencyKey());
        return transaction(
                true,
                c -> {
                    var b = finance.originalBookScope(principal, book, "export");
                    if (!allowed(b, "post") && !allowed(b, "create"))
                        throw error("SCOPE_DENIED", "需要导出执行权限");
                    UUID tenant = id(b.get("tenant_id"));
                    UUID actorId = id(b.get("actor_id"));
                    gate.current(actorId, tenant, false, request);
                    String route = "GENERATE:" + requestId;
                    String payloadHash = FinanceService.hash("gen:" + in.version());
                    var cached = replay(c, tenant, book, actorId, route, in.idempotencyKey(), payloadHash);
                    if (cached != null) return cached;

                    var req =
                            c.one(
                                    "SELECT * FROM plugin_finance_archive_request WHERE tenant_id=? AND book_id=? AND id=? FOR UPDATE",
                                    tenant,
                                    book,
                                    requestId);
                    if (((Number) req.get("version")).longValue() != in.version())
                        throw error("VERSION_CONFLICT", "导出申请版本冲突");
                    if (!"APPROVED".equals(req.get("state")))
                        throw error("ARCHIVE_STATE_CONFLICT", "导出申请尚未独立批准或已执行");

                    var pf =
                            c.one(
                                    "SELECT * FROM plugin_finance_archive_preflight WHERE tenant_id=? AND book_id=? AND id=?",
                                    tenant,
                                    book,
                                    req.get("preflight_id"));
                    List<UUID> yearIds = parseYearIds(pf.get("year_ids"));
                    String purpose = req.get("purpose").toString();
                    SnapshotSummary fresh = inspectSnapshot(c, principal, tenant, book, yearIds, purpose);
                    if (!finance.encode(finance.canonical(fresh.sourceVector))
                            .equals(finance.encode(finance.canonical(jsonList(pf.get("source_vector"))))))
                        throw error("EXPORT_STALE", "账务来源水位已变化，不能合并跨版本快照");
                    if (!"DIAGNOSTIC".equals(purpose) && !fresh.blockers.isEmpty()) {
                        throw new FinanceException(
                                "ARCHIVE_COVERAGE_INCOMPLETE",
                                "正式归档或退订全量包存在未完备数据族阻断项: " + String.join(", ", fresh.blockers));
                    }

                    FinanceArchiveUstar.BuiltArchive built =
                            buildUstarPackage(c, tenant, book, requestId, yearIds, fresh);
                    Instant artifactExpires =
                            Instant.now().plus(24, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
                    c.update(
                            "UPDATE plugin_finance_archive_request SET state='READY',version=version+1,"
                                + "manifest_sha256=?,artifact_sha256=?,artifact_bytes=?,artifact_expires_at=? "
                                + "WHERE tenant_id=? AND book_id=? AND id=?",
                            built.manifestSha256(),
                            built.artifactSha256(),
                            (long) built.tarBytes().length,
                            Timestamp.from(artifactExpires),
                            tenant,
                            book,
                            requestId);
                    c.update(
                            "INSERT INTO plugin_finance_archive_artifact("
                                + "tenant_id,book_id,request_id,manifest_sha256,artifact_sha256,size_bytes,content) "
                                + "VALUES(?,?,?,?,?,?,?)",
                            tenant,
                            book,
                            requestId,
                            built.manifestSha256(),
                            built.artifactSha256(),
                            (long) built.tarBytes().length,
                            built.tarBytes());
                    var res = detailEnvelope(c, principal, tenant, book, requestId);
                    saveReplay(c, tenant, book, actorId, route, in.idempotencyKey(), payloadHash, res);
                    return res;
                });
    }

    public Object detail(String principal, UUID book, UUID requestId) {
        requireExportPermission(principal);
        return transaction(
                false,
                c -> {
                    var b = finance.originalBookScope(principal, book, "read");
                    return detailEnvelope(c, principal, id(b.get("tenant_id")), book, requestId);
                });
    }

    public DownloadedArtifact download(String principal, UUID book, UUID requestId) {
        requireExportPermission(principal);
        return (DownloadedArtifact)
                transaction(
                        false,
                        c -> {
                            var b = finance.originalBookScope(principal, book, "read");
                            UUID tenant = id(b.get("tenant_id"));
                            var req =
                                    c.one(
                                            "SELECT * FROM plugin_finance_archive_request WHERE tenant_id=? AND book_id=? AND id=?",
                                            tenant,
                                            book,
                                            requestId);
                            if (!Set.of("READY", "DELIVERED").contains(req.get("state")))
                                throw error("ARCHIVE_ARTIFACT_NOT_READY", "导出包尚未生成完成");
                            var pf =
                                    c.one(
                                            "SELECT year_ids FROM plugin_finance_archive_preflight WHERE tenant_id=? AND book_id=? AND id=?",
                                            tenant,
                                            book,
                                            req.get("preflight_id"));
                            reauthorizeYears(principal, book, parseYearIds(pf.get("year_ids")));
                            var art =
                                    c.one(
                                            "SELECT content,artifact_sha256,manifest_sha256,size_bytes "
                                                + "FROM plugin_finance_archive_artifact WHERE tenant_id=? AND book_id=? AND request_id=?",
                                            tenant,
                                            book,
                                            requestId);
                            byte[] bytes = (byte[]) art.get("content");
                            String expectedSha = art.get("artifact_sha256").toString();
                            if (bytes.length != ((Number) art.get("size_bytes")).intValue()
                                    || !FinanceArchiveUstar.sha256Hex(bytes).equals(expectedSha))
                                throw new FinanceException("ARCHIVE_INTEGRITY_FAILED", "导出包字节摘要复核失败");
                            return new DownloadedArtifact(
                                    requestId, bytes, expectedSha, art.get("manifest_sha256").toString());
                        });
    }

    public Object receipt(
            String principal,
            UUID book,
            UUID requestId,
            ReceiptInput in,
            HttpServletRequest request) {
        requireExportPermission(principal);
        requireDigest(in.artifactSha256(), "artifactSha256");
        requireDigest(in.manifestSha256(), "manifestSha256");
        requireDigest(in.verificationReportSha256(), "verificationReportSha256");
        requireReason(in.reason());
        requireKey(in.idempotencyKey());
        return transaction(
                true,
                c -> {
                    var b = finance.originalBookScope(principal, book, "read");
                    UUID tenant = id(b.get("tenant_id"));
                    UUID actorId = id(b.get("actor_id"));
                    var who = gate.current(actorId, tenant, false, request);
                    String route = "RECEIPT:" + requestId;
                    String payloadHash =
                            FinanceService.hash(
                                    in.version()
                                            + ":"
                                            + in.artifactSha256()
                                            + ":"
                                            + in.manifestSha256()
                                            + ":"
                                            + in.verificationReportSha256()
                                            + ":"
                                            + in.reason());
                    var cached = replay(c, tenant, book, actorId, route, in.idempotencyKey(), payloadHash);
                    if (cached != null) return cached;

                    var req =
                            c.one(
                                    "SELECT * FROM plugin_finance_archive_request WHERE tenant_id=? AND book_id=? AND id=? FOR UPDATE",
                                    tenant,
                                    book,
                                    requestId);
                    if (((Number) req.get("version")).longValue() != in.version())
                        throw error("VERSION_CONFLICT", "导出申请版本冲突");
                    if (!"READY".equals(req.get("state")))
                        throw error("ARCHIVE_STATE_CONFLICT", "仅 READY 状态的导出包可登记接收回执");
                    if (!in.artifactSha256().equals(req.get("artifact_sha256"))
                            || !in.manifestSha256().equals(req.get("manifest_sha256")))
                        throw new FinanceException("ARCHIVE_DIGEST_MISMATCH", "接收回执摘要与受控导出包不一致");
                    var pf =
                            c.one(
                                    "SELECT year_ids FROM plugin_finance_archive_preflight WHERE tenant_id=? AND book_id=? AND id=?",
                                    tenant,
                                    book,
                                    req.get("preflight_id"));
                    reauthorizeYears(principal, book, parseYearIds(pf.get("year_ids")));

                    Map<String, Object> receiptJson =
                            map(
                                    "recipient",
                                    who,
                                    "artifactSha256",
                                    in.artifactSha256(),
                                    "manifestSha256",
                                    in.manifestSha256(),
                                    "verificationReportSha256",
                                    in.verificationReportSha256(),
                                    "reason",
                                    in.reason(),
                                    "deliveredAt",
                                    FinanceArchiveUstar.formatInstantMs(Instant.now()));
                    c.update(
                            "UPDATE plugin_finance_archive_request SET state='DELIVERED',version=version+1,receipt=?::jsonb "
                                + "WHERE tenant_id=? AND book_id=? AND id=?",
                            finance.encode(receiptJson),
                            tenant,
                            book,
                            requestId);
                    var res = detailEnvelope(c, principal, tenant, book, requestId);
                    saveReplay(c, tenant, book, actorId, route, in.idempotencyKey(), payloadHash, res);
                    return res;
                });
    }

    private record SnapshotSummary(
            int precision,
            List<Map<String, Object>> sourceVector,
            List<Map<String, Object>> coverage,
            List<String> blockers,
            List<Map<String, Object>> counts,
            List<Map<String, Object>> totals) {}

    private SnapshotSummary inspectSnapshot(
            Call c, String principal, UUID tenant, UUID book, List<UUID> yearIds, String purpose) {
        reauthorizeYears(principal, book, yearIds);
        int precision = 8;
        long totalJournals = 0;
        long totalLines = 0;
        long totalOriginals = 0;
        long totalAudits = 0;
        BigDecimal sumDebit = BigDecimal.ZERO;
        BigDecimal sumCredit = BigDecimal.ZERO;
        List<String> wmParts = new ArrayList<>();

        for (UUID y : yearIds) {
            var yr =
                    c.one(
                            "SELECT precision_profile FROM plugin_finance_year WHERE tenant_id=? AND book_id=? AND id=?",
                            tenant,
                            book,
                            y);
            if ("LEGACY_V3".equals(yr.get("precision_profile"))) precision = 6;
            var g =
                    c.one(
                            "SELECT posting_seq,opening_version,baseline_version FROM plugin_finance_year_guard WHERE tenant_id=? AND book_id=? AND year_id=?",
                            tenant,
                            book,
                            y);
            wmParts.add(
                    y
                            + ":"
                            + g.get("posting_seq")
                            + ":"
                            + g.get("opening_version")
                            + ":"
                            + g.get("baseline_version"));
            totalJournals +=
                    ((Number)
                                    c.one(
                                                    "SELECT count(*) c FROM plugin_finance_journal WHERE tenant_id=? AND book_id=? AND year_id=?",
                                                    tenant,
                                                    book,
                                                    y)
                                            .get("c"))
                            .longValue();
            var lineAgg =
                    c.one(
                            "SELECT count(*) c, COALESCE(SUM(debit),0) d, COALESCE(SUM(credit),0) cr "
                                + "FROM plugin_finance_line WHERE tenant_id=? AND book_id=? AND year_id=?",
                            tenant,
                            book,
                            y);
            totalLines += ((Number) lineAgg.get("c")).longValue();
            sumDebit = sumDebit.add(new BigDecimal(lineAgg.get("d").toString()));
            sumCredit = sumCredit.add(new BigDecimal(lineAgg.get("cr").toString()));
            totalOriginals +=
                    ((Number)
                                    c.one(
                                                    "SELECT count(*) c FROM plugin_finance_original WHERE tenant_id=? AND book_id=? AND year_id=?",
                                                    tenant,
                                                    book,
                                                    y)
                                            .get("c"))
                            .longValue();
            totalAudits +=
                    ((Number)
                                    c.one(
                                                    "SELECT count(*) c FROM plugin_finance_audit WHERE tenant_id=? AND book_id=? AND year_id=?",
                                                    tenant,
                                                    book,
                                                    y)
                                            .get("c"))
                            .longValue();
        }
        String vectorDigest = FinanceService.hash(String.join("|", wmParts));
        List<Map<String, Object>> sourceVector = new ArrayList<>();
        List<Map<String, Object>> coverage = new ArrayList<>();
        for (String fam : FAMILIES) {
            sourceVector.add(
                    map(
                            "family",
                            fam,
                            "epoch",
                            null,
                            "generation",
                            String.valueOf(totalJournals + totalLines + totalOriginals),
                            "digest",
                            FinanceService.hash(fam + ":" + vectorDigest)));
            if ("reports".equals(fam)) {
                coverage.add(
                        map(
                                "family",
                                fam,
                                "status",
                                "UNSUPPORTED",
                                "reason",
                                "法定正式三表规则包尚未审定发布（RULE_VERSION_UNAVAILABLE）",
                                "evidence",
                                "ReportSourceEngine:BLOCKED_DRAFT"));
            } else {
                coverage.add(
                        map(
                                "family",
                                fam,
                                "status",
                                "AVAILABLE",
                                "reason",
                                "当前授权账套与年度快照可读",
                                "evidence",
                                "snapshot:" + vectorDigest.substring(0, 16)));
            }
        }
        List<String> blockers =
                "DIAGNOSTIC".equals(purpose)
                        ? List.of()
                        : List.of("REPORTS_STATUTORY_RULE_PACK_UNAVAILABLE");
        List<Map<String, Object>> counts =
                List.of(
                        map("dataset", "journals", "value", String.valueOf(totalJournals), "status", "KNOWN"),
                        map("dataset", "lines", "value", String.valueOf(totalLines), "status", "KNOWN"),
                        map("dataset", "originals", "value", String.valueOf(totalOriginals), "status", "KNOWN"),
                        map("dataset", "audits", "value", String.valueOf(totalAudits), "status", "KNOWN"));
        List<Map<String, Object>> totals =
                List.of(
                        map(
                                "dataset",
                                "ledger-lines",
                                "currency",
                                "CNY",
                                "precision",
                                precision,
                                "debit",
                                sumDebit.setScale(precision, RoundingMode.UNNECESSARY).toPlainString(),
                                "credit",
                                sumCredit.setScale(precision, RoundingMode.UNNECESSARY).toPlainString(),
                                "status",
                                "KNOWN"));
        return new SnapshotSummary(precision, sourceVector, coverage, blockers, counts, totals);
    }

    private FinanceArchiveUstar.BuiltArchive buildUstarPackage(
            Call c,
            UUID tenant,
            UUID book,
            UUID requestId,
            List<UUID> yearIds,
            SnapshotSummary snap) {
        int precision = snap.precision;
        List<Map<String, Object>> dictionary =
                List.of(
                        map(
                                "type",
                                "scope-year",
                                "fields",
                                List.of(
                                        map("name", "yearNumber", "type", "string", "nullable", false),
                                        map("name", "status", "type", "string", "nullable", false)),
                                "requireAudit",
                                true,
                                "requireOriginal",
                                false),
                        map(
                                "type",
                                "ledger-line",
                                "fields",
                                List.of(
                                        map("name", "journalId", "type", "string", "nullable", false),
                                        map("name", "accountId", "type", "string", "nullable", false),
                                        map("name", "debit", "type", "decimal", "nullable", false),
                                        map("name", "credit", "type", "decimal", "nullable", false)),
                                "requireAudit",
                                false,
                                "requireOriginal",
                                false),
                        map(
                                "type",
                                "original-anchor",
                                "fields",
                                List.of(map("name", "filename", "type", "string", "nullable", false)),
                                "requireAudit",
                                false,
                                "requireOriginal",
                                true));

        List<Map<String, Object>> records = new ArrayList<>();
        List<Map<String, Object>> audits = new ArrayList<>();
        List<Map<String, Object>> originalsIndex = new ArrayList<>();
        List<FinanceArchiveUstar.OriginalPayload> originalPayloads = new ArrayList<>();
        int origSeq = 0;

        for (UUID y : yearIds) {
            var yr =
                    c.one(
                            "SELECT year,status FROM plugin_finance_year WHERE tenant_id=? AND book_id=? AND id=?",
                            tenant,
                            book,
                            y);
            String yearRecId = "year:" + y;
            String syntAuditId = "audit:year:" + y;
            Map<String, Object> auditPayload =
                    map(
                            "bookId",
                            book.toString(),
                            "yearId",
                            y.toString(),
                            "year",
                            yr.get("year").toString(),
                            "status",
                            yr.get("status").toString());
            String auditPayloadSha =
                    FinanceArchiveUstar.sha256Hex(
                            FinanceArchiveUstar.canonicalJson(auditPayload)
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            audits.add(
                    map(
                            "id",
                            syntAuditId,
                            "recordId",
                            yearRecId,
                            "action",
                            "SNAPSHOT_YEAR_OBSERVED",
                            "at",
                            "2037-01-01T00:00:00.000Z",
                            "payload",
                            auditPayload,
                            "payloadSha256",
                            auditPayloadSha));
            records.add(
                    map(
                            "id",
                            yearRecId,
                            "type",
                            "scope-year",
                            "yearId",
                            y.toString(),
                            "values",
                            map(
                                    "yearNumber",
                                    yr.get("year").toString(),
                                    "status",
                                    yr.get("status").toString()),
                            "refs",
                            List.of(),
                            "originalIds",
                            List.of(),
                            "auditIds",
                            List.of(syntAuditId)));

            var lines =
                    c.list(
                            "SELECT journal_id,line_no,account_id,debit,credit FROM plugin_finance_line "
                                + "WHERE tenant_id=? AND book_id=? AND year_id=? ORDER BY journal_id,line_no LIMIT 2000",
                            tenant,
                            book,
                            y);
            for (var ln : lines) {
                String recId = "line:" + ln.get("journal_id") + ":" + ln.get("line_no");
                String dStr =
                        new BigDecimal(ln.get("debit").toString())
                                .setScale(precision, RoundingMode.UNNECESSARY)
                                .toPlainString();
                String cStr =
                        new BigDecimal(ln.get("credit").toString())
                                .setScale(precision, RoundingMode.UNNECESSARY)
                                .toPlainString();
                records.add(
                        map(
                                "id",
                                recId,
                                "type",
                                "ledger-line",
                                "yearId",
                                y.toString(),
                                "values",
                                map(
                                        "journalId",
                                        ln.get("journal_id").toString(),
                                        "accountId",
                                        ln.get("account_id").toString(),
                                        "debit",
                                        dStr,
                                        "credit",
                                        cStr),
                                "refs",
                                List.of(yearRecId),
                                "originalIds",
                                List.of(),
                                "auditIds",
                                List.of()));
            }

            var origRows =
                    c.list(
                            "SELECT id,filename,media_type,size_bytes,sha256,content FROM plugin_finance_original "
                                + "WHERE tenant_id=? AND book_id=? AND year_id=? ORDER BY id LIMIT 100",
                            tenant,
                            book,
                            y);
            for (var o : origRows) {
                byte[] content = (byte[]) o.get("content");
                if (content == null || content.length == 0) continue;
                origSeq++;
                String oid = "orig:" + o.get("id");
                String safePath = "originals/original-" + origSeq + ".bin";
                String sha = o.get("sha256").toString();
                originalsIndex.add(
                        map(
                                "id",
                                oid,
                                "path",
                                safePath,
                                "filename",
                                o.get("filename").toString(),
                                "mediaType",
                                o.get("media_type").toString(),
                                "size",
                                content.length,
                                "sha256",
                                sha));
                originalPayloads.add(
                        new FinanceArchiveUstar.OriginalPayload(
                                oid,
                                safePath,
                                o.get("filename").toString(),
                                o.get("media_type").toString(),
                                content,
                                sha));
                records.add(
                        map(
                                "id",
                                "orig-anchor:" + o.get("id"),
                                "type",
                                "original-anchor",
                                "yearId",
                                y.toString(),
                                "values",
                                map("filename", o.get("filename").toString()),
                                "refs",
                                List.of(yearRecId),
                                "originalIds",
                                List.of(oid),
                                "auditIds",
                                List.of()));
            }
        }

        String sourceVectorDigest =
                FinanceArchiveUstar.sha256Hex(
                        FinanceArchiveUstar.canonicalJson(snap.sourceVector)
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Map<String, Object> indexMap =
                map(
                        "format",
                        "lumira-finance-archive-ustar-v1",
                        "scope",
                        map(
                                "tenantId",
                                tenant.toString(),
                                "bookId",
                                book.toString(),
                                "yearIds",
                                yearIds.stream().map(UUID::toString).toList()),
                        "snapshotId",
                        "snap:" + requestId,
                        "sourceVectorDigest",
                        sourceVectorDigest,
                        "coverage",
                        snap.coverage,
                        "precision",
                        precision,
                        "dictionary",
                        dictionary,
                        "records",
                        records,
                        "audits",
                        audits,
                        "originals",
                        originalsIndex,
                        "counts",
                        map(
                                "records",
                                String.valueOf(records.size()),
                                "audits",
                                String.valueOf(audits.size()),
                                "originals",
                                String.valueOf(originalsIndex.size())),
                        "totals",
                        map("debit", null, "credit", null),
                        "readingInstructions",
                        "Bounded POSIX ustar diagnostic snapshot. Verify offline with node plugins/finance/archive/verify-archive.mjs <archive.tar> <manifestSha256>. legalArchiveComplete=false.");
        return FinanceArchiveUstar.build(indexMap, originalPayloads);
    }

    private void reauthorizeYears(String principal, UUID book, List<UUID> yearIds) {
        for (UUID y : yearIds) {
            finance.originalScope(principal, book, y, "read");
        }
    }

    private List<UUID> parseYearIds(Object rawJson) {
        List<UUID> out = new ArrayList<>();
        for (Object x : jsonList(rawJson)) out.add(id(x));
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<Object> jsonList(Object value) {
        return gate.json.readValue(
                value instanceof String s ? s : value.toString(), List.class);
    }

    private Object replay(
            Call c,
            UUID tenant,
            UUID book,
            UUID actorId,
            String route,
            String key,
            String payloadHash) {
        var rows =
                c.list(
                        "SELECT payload_hash,response FROM plugin_finance_archive_idempotency "
                            + "WHERE tenant_id=? AND book_id=? AND actor_id=? AND route=? AND idempotency_key=?",
                        tenant,
                        book,
                        actorId,
                        route,
                        key);
        if (rows.isEmpty()) return null;
        if (!payloadHash.equals(rows.getFirst().get("payload_hash")))
            throw error("IDEMPOTENCY_CONFLICT", "导出请求幂等键绑定了不同请求正文");
        return json(rows.getFirst().get("response"));
    }

    private void saveReplay(
            Call c,
            UUID tenant,
            UUID book,
            UUID actorId,
            String route,
            String key,
            String payloadHash,
            Object response) {
        c.update(
                "INSERT INTO plugin_finance_archive_idempotency("
                    + "tenant_id,book_id,actor_id,route,idempotency_key,payload_hash,response) "
                    + "VALUES(?,?,?,?,?,?,?::jsonb)",
                tenant,
                book,
                actorId,
                route,
                key,
                payloadHash,
                finance.encode(response));
    }

    private Map<String, Object> detailEnvelope(
            Call c, String principal, UUID tenant, UUID book, UUID requestId) {
        var req =
                c.one(
                        "SELECT * FROM plugin_finance_archive_request WHERE tenant_id=? AND book_id=? AND id=?",
                        tenant,
                        book,
                        requestId);
        var pf =
                c.one(
                        "SELECT * FROM plugin_finance_archive_preflight WHERE tenant_id=? AND book_id=? AND id=?",
                        tenant,
                        book,
                        req.get("preflight_id"));
        List<UUID> yearIds = parseYearIds(pf.get("year_ids"));
        reauthorizeYears(principal, book, yearIds);
        Object artifact = null;
        if (Set.of("READY", "DELIVERED").contains(req.get("state"))) {
            artifact =
                    map(
                            "format",
                            "lumira-finance-archive-ustar-v1",
                            "bytes",
                            req.get("artifact_bytes").toString(),
                            "sha256",
                            req.get("artifact_sha256"),
                            "manifestSha256",
                            req.get("manifest_sha256"),
                            "expiresAt",
                            FinanceArchiveUstar.formatInstantMs(
                                    ((Timestamp) req.get("artifact_expires_at")).toInstant()),
                            "packageIntegrity",
                            "PACKAGE_INTEGRITY_VERIFIED",
                            "legalArchiveComplete",
                            false);
        }
        return map(
                "requestId",
                requestId.toString(),
                "preflightId",
                req.get("preflight_id").toString(),
                "purpose",
                req.get("purpose"),
                "proposalDigest",
                req.get("proposal_digest"),
                "state",
                req.get("state"),
                "version",
                req.get("version").toString(),
                "scope",
                map(
                        "tenantId",
                        tenant.toString(),
                        "bookId",
                        book.toString(),
                        "yearIds",
                        yearIds.stream().map(UUID::toString).toList()),
                "coverage",
                jsonList(pf.get("coverage")),
                "counts",
                jsonList(pf.get("counts")),
                "totals",
                jsonList(pf.get("totals")),
                "blockers",
                jsonList(pf.get("blockers")),
                "artifact",
                artifact,
                "receipt",
                req.get("receipt") == null ? null : json(req.get("receipt")),
                "legalArchiveComplete",
                false);
    }

    private Map<String, Object> summaryRow(Map<String, Object> r) {
        return map(
                "requestId",
                r.get("id").toString(),
                "preflightId",
                r.get("preflight_id").toString(),
                "purpose",
                r.get("purpose"),
                "state",
                r.get("state"),
                "version",
                r.get("version").toString(),
                "yearIds",
                jsonList(r.get("year_ids")),
                "blockers",
                jsonList(r.get("blockers")),
                "manifestSha256",
                r.get("manifest_sha256"),
                "artifactSha256",
                r.get("artifact_sha256"),
                "artifactBytes",
                r.get("artifact_bytes") == null ? null : r.get("artifact_bytes").toString(),
                "legalArchiveComplete",
                false);
    }
}
