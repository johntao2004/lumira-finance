package com.lumira.finance.fapiao;

import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * JDBC Store for Evidence Completeness, Verification, Extended Quotas,
 * Evidence Graph, Exceptions, Fapiao Pool, Issuance Apps, and Red Notices.
 */
@Repository
public class EvidenceFapiaoJdbcStore {
    private final DataSource dataSource;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public EvidenceFapiaoJdbcStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    private String toJson(Object obj) {
        if (obj == null) return "{}";
        try {
            return jsonMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fromJsonMap(String json) {
        if (json == null || json.isBlank()) return Collections.emptyMap();
        try {
            return jsonMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> fromJsonStringList(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return jsonMapper.readValue(json, List.class);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fromJsonMapList(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return jsonMapper.readValue(json, List.class);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    // =========================================================================
    // EV01: Completeness
    // =========================================================================

    public void insertCompleteness(Connection c, EvidenceFapiaoModels.CompletenessRecord r) throws SQLException {
        String sql = """
            INSERT INTO plugin_finance_evidence_completeness (
                tenant_id, legal_entity_id, id, source_original_id, classification,
                completeness_state, is_complete, missing_reasons, paper_storage_location,
                metadata_index, version, predecessor_id, created_by, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?::jsonb, ?, ?, ?::jsonb, ?, ?)
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, r.tenantId());
            ps.setObject(2, r.legalEntityId());
            ps.setObject(3, r.id());
            ps.setObject(4, r.sourceOriginalId());
            ps.setString(5, r.classification().name());
            ps.setString(6, r.completenessState().name());
            ps.setBoolean(7, r.isComplete());
            ps.setString(8, toJson(r.missingReasons()));
            ps.setString(9, r.paperStorageLocation());
            ps.setString(10, toJson(r.metadataIndex()));
            ps.setLong(11, r.version());
            ps.setObject(12, r.predecessorId());
            ps.setString(13, toJson(r.createdBy()));
            ps.setTimestamp(14, Timestamp.from(r.createdAt()));
            ps.setTimestamp(15, Timestamp.from(r.updatedAt()));
            ps.executeUpdate();
        }
    }

    public Optional<EvidenceFapiaoModels.CompletenessRecord> getCompleteness(Connection c, UUID tenantId, UUID legalEntityId, UUID id) throws SQLException {
        String sql = """
            SELECT * FROM plugin_finance_evidence_completeness
            WHERE tenant_id = ? AND legal_entity_id = ? AND id = ?
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, legalEntityId);
            ps.setObject(3, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(mapCompleteness(rs));
            }
        }
    }

    private EvidenceFapiaoModels.CompletenessRecord mapCompleteness(ResultSet rs) throws SQLException {
        return new EvidenceFapiaoModels.CompletenessRecord(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("tenant_id"),
                (UUID) rs.getObject("legal_entity_id"),
                (UUID) rs.getObject("source_original_id"),
                EvidenceClassification.valueOf(rs.getString("classification")),
                EvidenceCompletenessState.valueOf(rs.getString("completeness_state")),
                rs.getBoolean("is_complete"),
                fromJsonStringList(rs.getString("missing_reasons")),
                rs.getString("paper_storage_location"),
                fromJsonMap(rs.getString("metadata_index")),
                rs.getLong("version"),
                (UUID) rs.getObject("predecessor_id"),
                fromJsonMap(rs.getString("created_by")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    // =========================================================================
    // EV02: Verification
    // =========================================================================

    public void insertVerification(Connection c, EvidenceFapiaoModels.VerificationRecord r) throws SQLException {
        String sql = """
            INSERT INTO plugin_finance_evidence_verification (
                tenant_id, legal_entity_id, id, evidence_id, verification_method,
                structural_digest, ocr_digest, comparison_status, field_discrepancies,
                tax_bureau_verification, verifier, verified_at, review_notes
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, r.tenantId());
            ps.setObject(2, r.legalEntityId());
            ps.setObject(3, r.id());
            ps.setObject(4, r.evidenceId());
            ps.setString(5, r.verificationMethod());
            ps.setString(6, r.structuralDigest());
            ps.setString(7, r.ocrDigest());
            ps.setString(8, r.comparisonStatus());
            ps.setString(9, toJson(r.fieldDiscrepancies()));
            ps.setString(10, r.taxBureauVerification());
            ps.setString(11, r.verifier());
            ps.setTimestamp(12, Timestamp.from(r.verifiedAt()));
            ps.setString(13, r.reviewNotes());
            ps.executeUpdate();
        }
    }

    // =========================================================================
    // EV03: Extended Quota Buckets
    // =========================================================================

    public void insertQuota(Connection c, EvidenceFapiaoModels.ExtendedQuotaRecord r) throws SQLException {
        String sql = """
            INSERT INTO plugin_finance_evidence_extended_quota (
                tenant_id, legal_entity_id, id, evidence_id, bucket_type,
                currency, capacity, reserved_amount, committed_amount, released_amount,
                created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, r.tenantId());
            ps.setObject(2, r.legalEntityId());
            ps.setObject(3, r.id());
            ps.setObject(4, r.evidenceId());
            ps.setString(5, r.bucketType().name());
            ps.setString(6, r.currency());
            ps.setBigDecimal(7, r.capacity());
            ps.setBigDecimal(8, r.reservedAmount());
            ps.setBigDecimal(9, r.committedAmount());
            ps.setBigDecimal(10, r.releasedAmount());
            ps.setTimestamp(11, Timestamp.from(r.createdAt()));
            ps.setTimestamp(12, Timestamp.from(r.updatedAt()));
            ps.executeUpdate();
        }
    }

    public Optional<EvidenceFapiaoModels.ExtendedQuotaRecord> getQuotaForUpdate(
            Connection c, UUID tenantId, UUID legalEntityId, UUID evidenceId, ExtendedQuotaBucket bucket
    ) throws SQLException {
        String sql = """
            SELECT * FROM plugin_finance_evidence_extended_quota
            WHERE tenant_id = ? AND legal_entity_id = ? AND evidence_id = ? AND bucket_type = ?
            FOR UPDATE
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, legalEntityId);
            ps.setObject(3, evidenceId);
            ps.setString(4, bucket.name());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(mapQuota(rs));
            }
        }
    }

    public void updateQuotaAmounts(
            Connection c, UUID quotaId, BigDecimal reserved, BigDecimal committed, BigDecimal released
    ) throws SQLException {
        String sql = """
            UPDATE plugin_finance_evidence_extended_quota
            SET reserved_amount = ?, committed_amount = ?, released_amount = ?, updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setBigDecimal(1, reserved.setScale(2, RoundingMode.HALF_UP));
            ps.setBigDecimal(2, committed.setScale(2, RoundingMode.HALF_UP));
            ps.setBigDecimal(3, released.setScale(2, RoundingMode.HALF_UP));
            ps.setObject(4, quotaId);
            ps.executeUpdate();
        }
    }

    public void insertQuotaLog(Connection c, EvidenceFapiaoModels.ExtendedQuotaLogRecord log) throws SQLException {
        String sql = """
            INSERT INTO plugin_finance_evidence_extended_quota_log (
                tenant_id, legal_entity_id, id, quota_id, operation_type,
                amount, business_chain_id, operator, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, log.tenantId());
            ps.setObject(2, log.legalEntityId());
            ps.setObject(3, log.id());
            ps.setObject(4, log.quotaId());
            ps.setString(5, log.operationType());
            ps.setBigDecimal(6, log.amount());
            ps.setObject(7, log.businessChainId());
            ps.setString(8, toJson(log.operator()));
            ps.setTimestamp(9, Timestamp.from(log.createdAt()));
            ps.executeUpdate();
        }
    }

    private EvidenceFapiaoModels.ExtendedQuotaRecord mapQuota(ResultSet rs) throws SQLException {
        return new EvidenceFapiaoModels.ExtendedQuotaRecord(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("tenant_id"),
                (UUID) rs.getObject("legal_entity_id"),
                (UUID) rs.getObject("evidence_id"),
                ExtendedQuotaBucket.valueOf(rs.getString("bucket_type")),
                rs.getString("currency"),
                rs.getBigDecimal("capacity"),
                rs.getBigDecimal("reserved_amount"),
                rs.getBigDecimal("committed_amount"),
                rs.getBigDecimal("released_amount"),
                BigDecimal.ZERO, // computed in record
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    // =========================================================================
    // EV04: Business Evidence Graph & Exceptions
    // =========================================================================

    public void insertGraphNode(Connection c, EvidenceFapiaoModels.GraphNodeRecord r) throws SQLException {
        String sql = """
            INSERT INTO plugin_finance_evidence_graph_node (
                tenant_id, legal_entity_id, id, node_type, business_ref_id, node_label, metadata, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, r.tenantId());
            ps.setObject(2, r.legalEntityId());
            ps.setObject(3, r.id());
            ps.setString(4, r.nodeType().name());
            ps.setString(5, r.businessRefId());
            ps.setString(6, r.nodeLabel());
            ps.setString(7, toJson(r.metadata()));
            ps.setTimestamp(8, Timestamp.from(r.createdAt()));
            ps.executeUpdate();
        }
    }

    public void insertGraphEdge(Connection c, EvidenceFapiaoModels.GraphEdgeRecord r) throws SQLException {
        String sql = """
            INSERT INTO plugin_finance_evidence_graph_edge (
                tenant_id, legal_entity_id, id, source_node_id, target_node_id,
                relationship_type, amount, metadata, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, r.tenantId());
            ps.setObject(2, r.legalEntityId());
            ps.setObject(3, r.id());
            ps.setObject(4, r.sourceNodeId());
            ps.setObject(5, r.targetNodeId());
            ps.setString(6, r.relationshipType().name());
            ps.setBigDecimal(7, r.amount());
            ps.setString(8, toJson(r.metadata()));
            ps.setTimestamp(9, Timestamp.from(r.createdAt()));
            ps.executeUpdate();
        }
    }

    public List<EvidenceFapiaoModels.GraphNodeRecord> listGraphNodes(Connection c, UUID tenantId, UUID legalEntityId) throws SQLException {
        String sql = "SELECT * FROM plugin_finance_evidence_graph_node WHERE tenant_id = ? AND legal_entity_id = ? ORDER BY created_at";
        List<EvidenceFapiaoModels.GraphNodeRecord> list = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, legalEntityId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new EvidenceFapiaoModels.GraphNodeRecord(
                            (UUID) rs.getObject("id"),
                            (UUID) rs.getObject("tenant_id"),
                            (UUID) rs.getObject("legal_entity_id"),
                            GraphNodeType.valueOf(rs.getString("node_type")),
                            rs.getString("business_ref_id"),
                            rs.getString("node_label"),
                            fromJsonMap(rs.getString("metadata")),
                            rs.getTimestamp("created_at").toInstant()
                    ));
                }
            }
        }
        return list;
    }

    public List<EvidenceFapiaoModels.GraphEdgeRecord> listGraphEdges(Connection c, UUID tenantId, UUID legalEntityId) throws SQLException {
        String sql = "SELECT * FROM plugin_finance_evidence_graph_edge WHERE tenant_id = ? AND legal_entity_id = ? ORDER BY created_at";
        List<EvidenceFapiaoModels.GraphEdgeRecord> list = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, legalEntityId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new EvidenceFapiaoModels.GraphEdgeRecord(
                            (UUID) rs.getObject("id"),
                            (UUID) rs.getObject("tenant_id"),
                            (UUID) rs.getObject("legal_entity_id"),
                            (UUID) rs.getObject("source_node_id"),
                            (UUID) rs.getObject("target_node_id"),
                            GraphEdgeType.valueOf(rs.getString("relationship_type")),
                            rs.getBigDecimal("amount"),
                            fromJsonMap(rs.getString("metadata")),
                            rs.getTimestamp("created_at").toInstant()
                    ));
                }
            }
        }
        return list;
    }

    public boolean isReachable(Connection c, UUID tenantId, UUID legalEntityId, UUID fromNodeId, UUID toNodeId) throws SQLException {
        if (fromNodeId.equals(toNodeId)) return true;
        String sql = """
            WITH RECURSIVE reachable AS (
                SELECT target_node_id AS node_id
                FROM plugin_finance_evidence_graph_edge
                WHERE tenant_id = ? AND legal_entity_id = ? AND source_node_id = ?
                UNION
                SELECT e.target_node_id
                FROM plugin_finance_evidence_graph_edge e
                JOIN reachable r ON e.source_node_id = r.node_id
                WHERE e.tenant_id = ? AND e.legal_entity_id = ?
            )
            SELECT 1 FROM reachable WHERE node_id = ? LIMIT 1
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, legalEntityId);
            ps.setObject(3, fromNodeId);
            ps.setObject(4, tenantId);
            ps.setObject(5, legalEntityId);
            ps.setObject(6, toNodeId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public Optional<EvidenceFapiaoModels.GraphNodeRecord> getGraphNode(Connection c, UUID tenantId, UUID legalEntityId, UUID id) throws SQLException {
        String sql = "SELECT * FROM plugin_finance_evidence_graph_node WHERE tenant_id = ? AND legal_entity_id = ? AND id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, legalEntityId);
            ps.setObject(3, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new EvidenceFapiaoModels.GraphNodeRecord(
                        (UUID) rs.getObject("id"),
                        (UUID) rs.getObject("tenant_id"),
                        (UUID) rs.getObject("legal_entity_id"),
                        GraphNodeType.valueOf(rs.getString("node_type")),
                        rs.getString("business_ref_id"),
                        rs.getString("node_label"),
                        fromJsonMap(rs.getString("metadata")),
                        rs.getTimestamp("created_at").toInstant()
                ));
            }
        }
    }

    public EvidenceFapiaoModels.EvidencePathRecord traceEvidencePath(Connection c, UUID tenantId, UUID legalEntityId, UUID startNodeId) throws SQLException {
        String upstreamSql = """
            WITH RECURSIVE upstream AS (
                SELECT source_node_id AS node_id
                FROM plugin_finance_evidence_graph_edge
                WHERE tenant_id = ? AND legal_entity_id = ? AND target_node_id = ?
                UNION
                SELECT e.source_node_id
                FROM plugin_finance_evidence_graph_edge e
                JOIN upstream u ON e.target_node_id = u.node_id
                WHERE e.tenant_id = ? AND e.legal_entity_id = ?
            )
            SELECT DISTINCT n.*
            FROM plugin_finance_evidence_graph_node n
            JOIN upstream u ON n.id = u.node_id
            WHERE n.tenant_id = ? AND n.legal_entity_id = ?
        """;
        List<EvidenceFapiaoModels.GraphNodeRecord> upstreamNodes = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(upstreamSql)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, legalEntityId);
            ps.setObject(3, startNodeId);
            ps.setObject(4, tenantId);
            ps.setObject(5, legalEntityId);
            ps.setObject(6, tenantId);
            ps.setObject(7, legalEntityId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    upstreamNodes.add(new EvidenceFapiaoModels.GraphNodeRecord(
                            (UUID) rs.getObject("id"),
                            (UUID) rs.getObject("tenant_id"),
                            (UUID) rs.getObject("legal_entity_id"),
                            GraphNodeType.valueOf(rs.getString("node_type")),
                            rs.getString("business_ref_id"),
                            rs.getString("node_label"),
                            fromJsonMap(rs.getString("metadata")),
                            rs.getTimestamp("created_at").toInstant()
                    ));
                }
            }
        }

        String downstreamSql = """
            WITH RECURSIVE downstream AS (
                SELECT target_node_id AS node_id
                FROM plugin_finance_evidence_graph_edge
                WHERE tenant_id = ? AND legal_entity_id = ? AND source_node_id = ?
                UNION
                SELECT e.target_node_id
                FROM plugin_finance_evidence_graph_edge e
                JOIN downstream d ON e.source_node_id = d.node_id
                WHERE e.tenant_id = ? AND e.legal_entity_id = ?
            )
            SELECT DISTINCT n.*
            FROM plugin_finance_evidence_graph_node n
            JOIN downstream d ON n.id = d.node_id
            WHERE n.tenant_id = ? AND n.legal_entity_id = ?
        """;
        List<EvidenceFapiaoModels.GraphNodeRecord> downstreamNodes = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(downstreamSql)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, legalEntityId);
            ps.setObject(3, startNodeId);
            ps.setObject(4, tenantId);
            ps.setObject(5, legalEntityId);
            ps.setObject(6, tenantId);
            ps.setObject(7, legalEntityId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    downstreamNodes.add(new EvidenceFapiaoModels.GraphNodeRecord(
                            (UUID) rs.getObject("id"),
                            (UUID) rs.getObject("tenant_id"),
                            (UUID) rs.getObject("legal_entity_id"),
                            GraphNodeType.valueOf(rs.getString("node_type")),
                            rs.getString("business_ref_id"),
                            rs.getString("node_label"),
                            fromJsonMap(rs.getString("metadata")),
                            rs.getTimestamp("created_at").toInstant()
                    ));
                }
            }
        }

        Set<UUID> allNodeIds = new HashSet<>();
        allNodeIds.add(startNodeId);
        upstreamNodes.forEach(n -> allNodeIds.add(n.id()));
        downstreamNodes.forEach(n -> allNodeIds.add(n.id()));

        List<EvidenceFapiaoModels.GraphEdgeRecord> connectedEdges = new ArrayList<>();
        for (var edge : listGraphEdges(c, tenantId, legalEntityId)) {
            if (allNodeIds.contains(edge.sourceNodeId()) && allNodeIds.contains(edge.targetNodeId())) {
                connectedEdges.add(edge);
            }
        }

        return new EvidenceFapiaoModels.EvidencePathRecord(startNodeId, upstreamNodes, downstreamNodes, connectedEdges);
    }

    public void insertException(Connection c, EvidenceFapiaoModels.EvidenceExceptionRecord r) throws SQLException {
        String sql = """
            INSERT INTO plugin_finance_evidence_exception (
                tenant_id, legal_entity_id, id, business_chain_id, exception_type,
                description, provisional_amount, responsible_person_id, responsible_person_name,
                due_date, status, approval_reason, approved_by, approved_at,
                created_by, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?::jsonb, ?, ?)
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, r.tenantId());
            ps.setObject(2, r.legalEntityId());
            ps.setObject(3, r.id());
            ps.setObject(4, r.businessChainId());
            ps.setString(5, r.exceptionType().name());
            ps.setString(6, r.description());
            ps.setBigDecimal(7, r.provisionalAmount());
            ps.setString(8, r.responsiblePersonId());
            ps.setString(9, r.responsiblePersonName());
            ps.setDate(10, java.sql.Date.valueOf(r.dueDate()));
            ps.setString(11, r.status().name());
            ps.setString(12, r.approvalReason());
            ps.setString(13, r.approvedBy() != null ? toJson(r.approvedBy()) : null);
            ps.setTimestamp(14, r.approvedAt() != null ? Timestamp.from(r.approvedAt()) : null);
            ps.setString(15, toJson(r.createdBy()));
            ps.setTimestamp(16, Timestamp.from(r.createdAt()));
            ps.setTimestamp(17, Timestamp.from(r.updatedAt()));
            ps.executeUpdate();
        }
    }

    public Optional<EvidenceFapiaoModels.EvidenceExceptionRecord> getException(Connection c, UUID tenantId, UUID legalEntityId, UUID id) throws SQLException {
        String sql = "SELECT * FROM plugin_finance_evidence_exception WHERE tenant_id = ? AND legal_entity_id = ? AND id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, legalEntityId);
            ps.setObject(3, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(mapException(rs));
            }
        }
    }

    public void approveException(
            Connection c, UUID tenantId, UUID legalEntityId, UUID id,
            String approvalReason, Map<String, Object> approvedBy
    ) throws SQLException {
        String sql = """
            UPDATE plugin_finance_evidence_exception
            SET status = 'APPROVED_EXCEPTION', approval_reason = ?, approved_by = ?::jsonb,
                approved_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
            WHERE tenant_id = ? AND legal_entity_id = ? AND id = ?
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, approvalReason);
            ps.setString(2, toJson(approvedBy));
            ps.setObject(3, tenantId);
            ps.setObject(4, legalEntityId);
            ps.setObject(5, id);
            ps.executeUpdate();
        }
    }

    private EvidenceFapiaoModels.EvidenceExceptionRecord mapException(ResultSet rs) throws SQLException {
        return new EvidenceFapiaoModels.EvidenceExceptionRecord(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("tenant_id"),
                (UUID) rs.getObject("legal_entity_id"),
                (UUID) rs.getObject("business_chain_id"),
                EvidenceExceptionType.valueOf(rs.getString("exception_type")),
                rs.getString("description"),
                rs.getBigDecimal("provisional_amount"),
                rs.getString("responsible_person_id"),
                rs.getString("responsible_person_name"),
                rs.getDate("due_date").toLocalDate(),
                EvidenceExceptionStatus.valueOf(rs.getString("status")),
                rs.getString("approval_reason"),
                fromJsonMap(rs.getString("approved_by")),
                rs.getTimestamp("approved_at") != null ? rs.getTimestamp("approved_at").toInstant() : null,
                fromJsonMap(rs.getString("created_by")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    // =========================================================================
    // FP01: Fapiao Pool
    // =========================================================================

    public void insertFapiao(Connection c, EvidenceFapiaoModels.FapiaoPoolRecord r) throws SQLException {
        String sql = """
            INSERT INTO plugin_finance_fapiao_pool (
                tenant_id, legal_entity_id, id, direction, invoice_code,
                fapiao_number, fapiao_type, issue_date, buyer_tax_no, buyer_name,
                seller_tax_no, seller_name, untaxed_amount, tax_rate, tax_amount,
                total_amount, original_document_id, tax_bureau_verification,
                lifecycle_status, ledger_journal_id, tax_worksheet_id, reconciliation_status,
                created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, r.tenantId());
            ps.setObject(2, r.legalEntityId());
            ps.setObject(3, r.id());
            ps.setString(4, r.direction().name());
            ps.setString(5, r.invoiceCode());
            ps.setString(6, r.fapiaoNumber());
            ps.setString(7, r.fapiaoType().name());
            ps.setDate(8, java.sql.Date.valueOf(r.issueDate()));
            ps.setString(9, r.buyerTaxNo());
            ps.setString(10, r.buyerName());
            ps.setString(11, r.sellerTaxNo());
            ps.setString(12, r.sellerName());
            ps.setBigDecimal(13, r.untaxedAmount());
            ps.setBigDecimal(14, r.taxRate());
            ps.setBigDecimal(15, r.taxAmount());
            ps.setBigDecimal(16, r.totalAmount());
            ps.setObject(17, r.originalDocumentId());
            ps.setString(18, r.taxBureauVerification());
            ps.setString(19, r.lifecycleStatus().name());
            ps.setObject(20, r.ledgerJournalId());
            ps.setObject(21, r.taxWorksheetId());
            ps.setString(22, r.reconciliationStatus());
            ps.setTimestamp(23, Timestamp.from(r.createdAt()));
            ps.setTimestamp(24, Timestamp.from(r.updatedAt()));
            ps.executeUpdate();
        }
    }

    public Optional<EvidenceFapiaoModels.FapiaoPoolRecord> getFapiao(Connection c, UUID tenantId, UUID legalEntityId, UUID id) throws SQLException {
        String sql = "SELECT * FROM plugin_finance_fapiao_pool WHERE tenant_id = ? AND legal_entity_id = ? AND id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, legalEntityId);
            ps.setObject(3, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(mapFapiao(rs));
            }
        }
    }

    public Optional<EvidenceFapiaoModels.FapiaoPoolRecord> getFapiaoByNumber(
            Connection c, UUID tenantId, UUID legalEntityId, String invoiceCode, String fapiaoNumber
    ) throws SQLException {
        String sql = "SELECT * FROM plugin_finance_fapiao_pool WHERE tenant_id = ? AND legal_entity_id = ? AND invoice_code = ? AND fapiao_number = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, legalEntityId);
            ps.setString(3, invoiceCode != null ? invoiceCode : "");
            ps.setString(4, fapiaoNumber);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(mapFapiao(rs));
            }
        }
    }

    public void updateFapiaoStatus(Connection c, UUID tenantId, UUID legalEntityId, UUID id, FapiaoLifecycleStatus newStatus) throws SQLException {
        String sql = """
            UPDATE plugin_finance_fapiao_pool
            SET lifecycle_status = ?, updated_at = CURRENT_TIMESTAMP
            WHERE tenant_id = ? AND legal_entity_id = ? AND id = ?
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, newStatus.name());
            ps.setObject(2, tenantId);
            ps.setObject(3, legalEntityId);
            ps.setObject(4, id);
            ps.executeUpdate();
        }
    }

    public Optional<EvidenceFapiaoModels.FapiaoPoolRecord> getFapiaoForUpdate(Connection c, UUID tenantId, UUID legalEntityId, UUID id) throws SQLException {
        String sql = "SELECT * FROM plugin_finance_fapiao_pool WHERE tenant_id = ? AND legal_entity_id = ? AND id = ? FOR UPDATE";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, legalEntityId);
            ps.setObject(3, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(mapFapiao(rs));
            }
        }
    }

    public void reconcileFapiao(Connection c, UUID tenantId, UUID legalEntityId, UUID fapiaoId, UUID ledgerJournalId, UUID taxWorksheetId) throws SQLException {
        String sql = """
            UPDATE plugin_finance_fapiao_pool
            SET ledger_journal_id = COALESCE(?, ledger_journal_id),
                tax_worksheet_id = COALESCE(?, tax_worksheet_id),
                reconciliation_status = 'RECONCILED',
                lifecycle_status = CASE WHEN lifecycle_status = 'UNVERIFIED_LOCAL' THEN 'MATCHED' ELSE lifecycle_status END,
                updated_at = CURRENT_TIMESTAMP
            WHERE tenant_id = ? AND legal_entity_id = ? AND id = ?
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, ledgerJournalId);
            ps.setObject(2, taxWorksheetId);
            ps.setObject(3, tenantId);
            ps.setObject(4, legalEntityId);
            ps.setObject(5, fapiaoId);
            int updated = ps.executeUpdate();
            if (updated == 0) {
                throw new IllegalArgumentException("Fapiao not found for reconciliation: " + fapiaoId);
            }
        }
    }

    public EvidenceFapiaoModels.CumulativeRedNoticeInfo getCumulativeRedNotices(
            Connection c, UUID tenantId, UUID legalEntityId, UUID originalBlueFapiaoId,
            BigDecimal blueUntaxed, BigDecimal blueTax, BigDecimal blueTotal
    ) throws SQLException {
        String sql = """
            SELECT
                COALESCE(SUM(ABS(red_untaxed_amount)), 0) AS cum_untaxed,
                COALESCE(SUM(ABS(red_tax_amount)), 0) AS cum_tax,
                COALESCE(SUM(ABS(red_total_amount)), 0) AS cum_total
            FROM plugin_finance_fapiao_red_notice
            WHERE tenant_id = ? AND legal_entity_id = ? AND original_fapiao_id = ?
              AND status IN ('WAITING_CONFIRMATION', 'CONFIRMED', 'RED_INVOICE_ISSUED')
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, legalEntityId);
            ps.setObject(3, originalBlueFapiaoId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    BigDecimal cumUntaxed = rs.getBigDecimal("cum_untaxed").setScale(2, RoundingMode.HALF_UP);
                    BigDecimal cumTax = rs.getBigDecimal("cum_tax").setScale(2, RoundingMode.HALF_UP);
                    BigDecimal cumTotal = rs.getBigDecimal("cum_total").setScale(2, RoundingMode.HALF_UP);
                    BigDecimal remUntaxed = blueUntaxed.subtract(cumUntaxed).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
                    BigDecimal remTax = blueTax.subtract(cumTax).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
                    BigDecimal remTotal = blueTotal.subtract(cumTotal).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
                    return new EvidenceFapiaoModels.CumulativeRedNoticeInfo(cumUntaxed, cumTax, cumTotal, remUntaxed, remTax, remTotal);
                }
                return new EvidenceFapiaoModels.CumulativeRedNoticeInfo(
                        BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                        BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                        BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                        blueUntaxed.setScale(2, RoundingMode.HALF_UP),
                        blueTax.setScale(2, RoundingMode.HALF_UP),
                        blueTotal.setScale(2, RoundingMode.HALF_UP)
                );
            }
        }
    }

    public BigDecimal getCumulativeIssuedRedTotal(Connection c, UUID tenantId, UUID legalEntityId, UUID originalBlueFapiaoId) throws SQLException {
        String sql = """
            SELECT COALESCE(SUM(ABS(red_total_amount)), 0) AS issued_red_total
            FROM plugin_finance_fapiao_red_notice
            WHERE tenant_id = ? AND legal_entity_id = ? AND original_fapiao_id = ?
              AND status = 'RED_INVOICE_ISSUED'
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, legalEntityId);
            ps.setObject(3, originalBlueFapiaoId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getBigDecimal("issued_red_total").setScale(2, RoundingMode.HALF_UP);
                }
                return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            }
        }
    }

    private EvidenceFapiaoModels.FapiaoPoolRecord mapFapiao(ResultSet rs) throws SQLException {
        return new EvidenceFapiaoModels.FapiaoPoolRecord(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("tenant_id"),
                (UUID) rs.getObject("legal_entity_id"),
                FapiaoDirection.valueOf(rs.getString("direction")),
                rs.getString("invoice_code"),
                rs.getString("fapiao_number"),
                FapiaoType.valueOf(rs.getString("fapiao_type")),
                rs.getDate("issue_date").toLocalDate(),
                rs.getString("buyer_tax_no"),
                rs.getString("buyer_name"),
                rs.getString("seller_tax_no"),
                rs.getString("seller_name"),
                rs.getBigDecimal("untaxed_amount"),
                rs.getBigDecimal("tax_rate"),
                rs.getBigDecimal("tax_amount"),
                rs.getBigDecimal("total_amount"),
                (UUID) rs.getObject("original_document_id"),
                rs.getString("tax_bureau_verification"),
                FapiaoLifecycleStatus.valueOf(rs.getString("lifecycle_status")),
                (UUID) rs.getObject("ledger_journal_id"),
                (UUID) rs.getObject("tax_worksheet_id"),
                rs.getString("reconciliation_status"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    // =========================================================================
    // FP02: Issuance Application
    // =========================================================================

    public void insertIssuanceApp(Connection c, EvidenceFapiaoModels.IssuanceAppRecord r) throws SQLException {
        String sql = """
            INSERT INTO plugin_finance_fapiao_issuance_app (
                tenant_id, legal_entity_id, id, application_number, applicant,
                application_date, contract_id, customer_id, buyer_tax_no, buyer_name,
                buyer_address_phone, buyer_bank_account, buyer_qualification_status,
                qualification_notes, reviewer, reviewed_at, untaxed_amount, tax_amount,
                total_amount, items, status, external_tax_channel, issued_fapiao_id,
                manual_confirmation_notes, confirmed_by, confirmed_at, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, r.tenantId());
            ps.setObject(2, r.legalEntityId());
            ps.setObject(3, r.id());
            ps.setString(4, r.applicationNumber());
            ps.setString(5, toJson(r.applicant()));
            ps.setDate(6, java.sql.Date.valueOf(r.applicationDate()));
            ps.setString(7, r.contractId());
            ps.setString(8, r.customerId());
            ps.setString(9, r.buyerTaxNo());
            ps.setString(10, r.buyerName());
            ps.setString(11, r.buyerAddressPhone());
            ps.setString(12, r.buyerBankAccount());
            ps.setString(13, r.buyerQualificationStatus().name());
            ps.setString(14, r.qualificationNotes());
            ps.setString(15, r.reviewer() != null ? toJson(r.reviewer()) : null);
            ps.setTimestamp(16, r.reviewedAt() != null ? Timestamp.from(r.reviewedAt()) : null);
            ps.setBigDecimal(17, r.untaxedAmount());
            ps.setBigDecimal(18, r.taxAmount());
            ps.setBigDecimal(19, r.totalAmount());
            ps.setString(20, toJson(r.items()));
            ps.setString(21, r.status().name());
            ps.setString(22, r.externalTaxChannel());
            ps.setObject(23, r.issuedFapiaoId());
            ps.setString(24, r.manualConfirmationNotes());
            ps.setString(25, r.confirmedBy() != null ? toJson(r.confirmedBy()) : null);
            ps.setTimestamp(26, r.confirmedAt() != null ? Timestamp.from(r.confirmedAt()) : null);
            ps.setTimestamp(27, Timestamp.from(r.createdAt()));
            ps.setTimestamp(28, Timestamp.from(r.updatedAt()));
            ps.executeUpdate();
        }
    }

    public Optional<EvidenceFapiaoModels.IssuanceAppRecord> getIssuanceApp(Connection c, UUID tenantId, UUID legalEntityId, UUID id) throws SQLException {
        String sql = "SELECT * FROM plugin_finance_fapiao_issuance_app WHERE tenant_id = ? AND legal_entity_id = ? AND id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, legalEntityId);
            ps.setObject(3, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(mapIssuanceApp(rs));
            }
        }
    }

    public void updateBuyerQualification(
            Connection c, UUID tenantId, UUID legalEntityId, UUID id,
            BuyerQualificationStatus status, String notes, Map<String, Object> reviewer
    ) throws SQLException {
        String sql = """
            UPDATE plugin_finance_fapiao_issuance_app
            SET buyer_qualification_status = ?, qualification_notes = ?, reviewer = ?::jsonb,
                reviewed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
            WHERE tenant_id = ? AND legal_entity_id = ? AND id = ?
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, status.name());
            ps.setString(2, notes);
            ps.setString(3, toJson(reviewer));
            ps.setObject(4, tenantId);
            ps.setObject(5, legalEntityId);
            ps.setObject(6, id);
            ps.executeUpdate();
        }
    }

    public void confirmManualIssuance(
            Connection c, UUID tenantId, UUID legalEntityId, UUID id,
            UUID issuedFapiaoId, String notes, Map<String, Object> confirmedBy
    ) throws SQLException {
        String sql = """
            UPDATE plugin_finance_fapiao_issuance_app
            SET status = 'ISSUED_MANUAL_CONFIRMED', issued_fapiao_id = ?, manual_confirmation_notes = ?,
                confirmed_by = ?::jsonb, confirmed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
            WHERE tenant_id = ? AND legal_entity_id = ? AND id = ?
              AND status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'WAITING_CHANNEL')
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, issuedFapiaoId);
            ps.setString(2, notes);
            ps.setString(3, toJson(confirmedBy));
            ps.setObject(4, tenantId);
            ps.setObject(5, legalEntityId);
            ps.setObject(6, id);
            int updated = ps.executeUpdate();
            if (updated == 0) {
                throw new IllegalStateException("Application cannot be confirmed in current status or does not exist: " + id);
            }
        }
    }

    private EvidenceFapiaoModels.IssuanceAppRecord mapIssuanceApp(ResultSet rs) throws SQLException {
        return new EvidenceFapiaoModels.IssuanceAppRecord(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("tenant_id"),
                (UUID) rs.getObject("legal_entity_id"),
                rs.getString("application_number"),
                fromJsonMap(rs.getString("applicant")),
                rs.getDate("application_date").toLocalDate(),
                rs.getString("contract_id"),
                rs.getString("customer_id"),
                rs.getString("buyer_tax_no"),
                rs.getString("buyer_name"),
                rs.getString("buyer_address_phone"),
                rs.getString("buyer_bank_account"),
                BuyerQualificationStatus.valueOf(rs.getString("buyer_qualification_status")),
                rs.getString("qualification_notes"),
                fromJsonMap(rs.getString("reviewer")),
                rs.getTimestamp("reviewed_at") != null ? rs.getTimestamp("reviewed_at").toInstant() : null,
                rs.getBigDecimal("untaxed_amount"),
                rs.getBigDecimal("tax_amount"),
                rs.getBigDecimal("total_amount"),
                fromJsonMapList(rs.getString("items")),
                IssuanceAppStatus.valueOf(rs.getString("status")),
                rs.getString("external_tax_channel"),
                (UUID) rs.getObject("issued_fapiao_id"),
                rs.getString("manual_confirmation_notes"),
                fromJsonMap(rs.getString("confirmed_by")),
                rs.getTimestamp("confirmed_at") != null ? rs.getTimestamp("confirmed_at").toInstant() : null,
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    // =========================================================================
    // FP03: Red Notice
    // =========================================================================

    public void insertRedNotice(Connection c, EvidenceFapiaoModels.RedNoticeRecord r) throws SQLException {
        String sql = """
            INSERT INTO plugin_finance_fapiao_red_notice (
                tenant_id, legal_entity_id, id, red_notice_number, original_fapiao_id,
                direction, initiator, reason, red_untaxed_amount, red_tax_amount,
                red_total_amount, status, registered_at, expires_at, confirmed_at,
                credit_fapiao_id, accounting_revenue_reversal_amount, vat_adjustment_amount,
                revenue_reversal_journal_id, vat_adjustment_journal_id, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, r.tenantId());
            ps.setObject(2, r.legalEntityId());
            ps.setObject(3, r.id());
            ps.setString(4, r.redNoticeNumber());
            ps.setObject(5, r.originalFapiaoId());
            ps.setString(6, r.direction());
            ps.setString(7, r.initiator());
            ps.setString(8, r.reason());
            ps.setBigDecimal(9, r.redUntaxedAmount());
            ps.setBigDecimal(10, r.redTaxAmount());
            ps.setBigDecimal(11, r.redTotalAmount());
            ps.setString(12, r.status().name());
            ps.setTimestamp(13, Timestamp.from(r.registeredAt()));
            ps.setTimestamp(14, Timestamp.from(r.expiresAt()));
            ps.setTimestamp(15, r.confirmedAt() != null ? Timestamp.from(r.confirmedAt()) : null);
            ps.setObject(16, r.creditFapiaoId());
            ps.setBigDecimal(17, r.accountingRevenueReversalAmount());
            ps.setBigDecimal(18, r.vatAdjustmentAmount());
            ps.setObject(19, r.revenueReversalJournalId());
            ps.setObject(20, r.vatAdjustmentJournalId());
            ps.setTimestamp(21, Timestamp.from(r.createdAt()));
            ps.setTimestamp(22, Timestamp.from(r.updatedAt()));
            ps.executeUpdate();
        }
    }

    public Optional<EvidenceFapiaoModels.RedNoticeRecord> getRedNotice(Connection c, UUID tenantId, UUID legalEntityId, UUID id) throws SQLException {
        String sql = "SELECT * FROM plugin_finance_fapiao_red_notice WHERE tenant_id = ? AND legal_entity_id = ? AND id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, legalEntityId);
            ps.setObject(3, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(mapRedNotice(rs));
            }
        }
    }

    public void confirmRedNotice(Connection c, UUID tenantId, UUID legalEntityId, UUID id) throws SQLException {
        String sql = """
            UPDATE plugin_finance_fapiao_red_notice
            SET status = 'CONFIRMED', confirmed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
            WHERE tenant_id = ? AND legal_entity_id = ? AND id = ?
              AND status = 'WAITING_CONFIRMATION'
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, legalEntityId);
            ps.setObject(3, id);
            int updated = ps.executeUpdate();
            if (updated == 0) {
                throw new IllegalStateException("Red notice is not in WAITING_CONFIRMATION status or does not exist: " + id);
            }
        }
    }

    public void expireRedNotice(Connection c, UUID tenantId, UUID legalEntityId, UUID id) throws SQLException {
        String sql = """
            UPDATE plugin_finance_fapiao_red_notice
            SET status = 'EXPIRED', updated_at = CURRENT_TIMESTAMP
            WHERE tenant_id = ? AND legal_entity_id = ? AND id = ?
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, legalEntityId);
            ps.setObject(3, id);
            ps.executeUpdate();
        }
    }

    public void linkCreditFapiao(
            Connection c, UUID tenantId, UUID legalEntityId, UUID redNoticeId,
            UUID creditFapiaoId, BigDecimal revenueReversal, BigDecimal vatAdjustment,
            UUID revJournalId, UUID vatJournalId
    ) throws SQLException {
        String sql = """
            UPDATE plugin_finance_fapiao_red_notice
            SET status = 'RED_INVOICE_ISSUED', credit_fapiao_id = ?,
                accounting_revenue_reversal_amount = ?, vat_adjustment_amount = ?,
                revenue_reversal_journal_id = ?, vat_adjustment_journal_id = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE tenant_id = ? AND legal_entity_id = ? AND id = ?
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, creditFapiaoId);
            ps.setBigDecimal(2, revenueReversal.setScale(2, RoundingMode.HALF_UP));
            ps.setBigDecimal(3, vatAdjustment.setScale(2, RoundingMode.HALF_UP));
            ps.setObject(4, revJournalId);
            ps.setObject(5, vatJournalId);
            ps.setObject(6, tenantId);
            ps.setObject(7, legalEntityId);
            ps.setObject(8, redNoticeId);
            ps.executeUpdate();
        }
    }

    private EvidenceFapiaoModels.RedNoticeRecord mapRedNotice(ResultSet rs) throws SQLException {
        return new EvidenceFapiaoModels.RedNoticeRecord(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("tenant_id"),
                (UUID) rs.getObject("legal_entity_id"),
                rs.getString("red_notice_number"),
                (UUID) rs.getObject("original_fapiao_id"),
                rs.getString("direction"),
                rs.getString("initiator"),
                rs.getString("reason"),
                rs.getBigDecimal("red_untaxed_amount"),
                rs.getBigDecimal("red_tax_amount"),
                rs.getBigDecimal("red_total_amount"),
                RedNoticeStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("registered_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("confirmed_at") != null ? rs.getTimestamp("confirmed_at").toInstant() : null,
                (UUID) rs.getObject("credit_fapiao_id"),
                rs.getBigDecimal("accounting_revenue_reversal_amount"),
                rs.getBigDecimal("vat_adjustment_amount"),
                (UUID) rs.getObject("revenue_reversal_journal_id"),
                (UUID) rs.getObject("vat_adjustment_journal_id"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }
}
