package com.lumira.finance.subledgerext;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

import com.lumira.finance.subledgerext.SubledgerExtModels.*;
import com.lumira.finance.subledgerext.SubledgerExtRules.*;

/**
 * Transactional application service coordinating AP procurement accrual closure,
 * AR CAS 14 contract assets / variable consideration, and AR credit loss / allowance adjustments.
 */
@Service
public final class SubledgerExtService {
    private final SubledgerExtStore store;

    public SubledgerExtService(SubledgerExtStore store) {
        this.store = Objects.requireNonNull(store);
    }

    // --- AP Procurement Accrual Operations ---
    public PoOrder createPo(UUID tenantId, UUID bookId, UUID yearId, UUID actorId, Map<String, Object> raw) {
        Map<String, Object> norm = SubledgerExtInput.normalizePoCreate(raw);
        String idemKey = (String) norm.get("idempotencyKey");
        Optional<UUID> existing = store.findIdempotentResourceId(tenantId, bookId, yearId, idemKey, "CREATE_PO");
        if (existing.isPresent()) {
            return store.getPo(existing.get()).orElseThrow();
        }
        PoOrder po = store.insertPo(tenantId, bookId, yearId, actorId, norm);
        store.saveIdempotentResponse(tenantId, bookId, yearId, idemKey, "CREATE_PO", po.id(),
                "{\"id\":\"" + po.id() + "\",\"status\":\"" + po.status() + "\"}");
        return po;
    }

    public void approvePo(UUID tenantId, UUID bookId, UUID yearId, UUID actorId, UUID poId, Map<String, Object> raw) {
        Map<String, Object> norm = SubledgerExtInput.normalizeApproval(raw);
        String idemKey = (String) norm.get("idempotencyKey");
        Optional<UUID> existing = store.findIdempotentResourceId(tenantId, bookId, yearId, idemKey, "APPROVE_PO");
        if (existing.isPresent()) {
            return;
        }
        PoOrder po = store.getPo(poId).orElseThrow(() -> new SubledgerException("PO_NOT_FOUND"));
        SubledgerExtRules.validateIndependentApproval(po.createdBy(), actorId);
        store.approvePo(poId, actorId);
        store.saveIdempotentResponse(tenantId, bookId, yearId, idemKey, "APPROVE_PO", poId, "{\"status\":\"APPROVED\"}");
    }

    public PoAcceptance createAcceptance(UUID tenantId, UUID bookId, UUID yearId, UUID actorId, Map<String, Object> raw) {
        Map<String, Object> norm = SubledgerExtInput.normalizeAcceptanceCreate(raw);
        String idemKey = (String) norm.get("idempotencyKey");
        Optional<UUID> existing = store.findIdempotentResourceId(tenantId, bookId, yearId, idemKey, "CREATE_ACCEPTANCE");
        if (existing.isPresent()) {
            return store.getAcceptance(existing.get()).orElseThrow();
        }
        UUID poId = (UUID) norm.get("poId");
        PoOrder po = store.getPo(poId).orElseThrow(() -> new SubledgerException("PO_NOT_FOUND"));
        SubledgerExtRules.require(po.status() == PoStatus.APPROVED, "PO_NOT_APPROVED", "Cannot accept unapproved purchase order");
        PoAcceptance acc = store.insertAcceptance(tenantId, bookId, yearId, actorId, norm);
        store.saveIdempotentResponse(tenantId, bookId, yearId, idemKey, "CREATE_ACCEPTANCE", acc.id(),
                "{\"id\":\"" + acc.id() + "\",\"status\":\"" + acc.status() + "\"}");
        return acc;
    }

    public ProvisionalAccrual createProvisionalAccrual(UUID tenantId, UUID bookId, UUID yearId, UUID actorId, Map<String, Object> raw) {
        Map<String, Object> norm = SubledgerExtInput.normalizeProvisionalAccrualCreate(raw);
        String idemKey = (String) norm.get("idempotencyKey");
        Optional<UUID> existing = store.findIdempotentResourceId(tenantId, bookId, yearId, idemKey, "CREATE_PROVISIONAL");
        if (existing.isPresent()) {
            return store.getProvisionalAccrual(existing.get()).orElseThrow();
        }
        UUID accId = (UUID) norm.get("acceptanceId");
        PoAcceptance acc = store.getAcceptance(accId).orElseThrow(() -> new SubledgerException("ACCEPTANCE_NOT_FOUND"));
        SubledgerExtRules.require(acc.status() == AcceptanceStatus.ACCEPTED, "ACCEPTANCE_NOT_ACCEPTED");
        SubledgerExtRules.require(acc.provisionalStatus() == ProvisionalStatus.NONE, "ALREADY_PROVISIONALLY_ACCRUED");
        ProvisionalAccrual pa = store.insertProvisionalAccrual(tenantId, bookId, yearId, actorId, norm);
        store.saveIdempotentResponse(tenantId, bookId, yearId, idemKey, "CREATE_PROVISIONAL", pa.id(),
                "{\"id\":\"" + pa.id() + "\",\"status\":\"" + pa.status() + "\"}");
        return pa;
    }

    public ProvisionalSettlement settleProvisionalAccrual(UUID tenantId, UUID bookId, UUID yearId, UUID actorId, Map<String, Object> raw) {
        Map<String, Object> norm = SubledgerExtInput.normalizeProvisionalSettlementCreate(raw);
        String idemKey = (String) norm.get("idempotencyKey");
        Optional<UUID> existing = store.findIdempotentResourceId(tenantId, bookId, yearId, idemKey, "SETTLE_PROVISIONAL");
        if (existing.isPresent()) {
            return store.getProvisionalSettlement(existing.get()).orElseThrow();
        }
        UUID provId = (UUID) norm.get("provisionalId");
        ProvisionalAccrual prov = store.getProvisionalAccrual(provId).orElseThrow(() -> new SubledgerException("PROVISIONAL_ACCRUAL_NOT_FOUND"));
        SubledgerExtRules.require(prov.status() == ProvisionalStatus.PROVISIONALLY_ACCRUED, "PROVISIONAL_ACCRUAL_NOT_ACCRUED");

        PoAcceptance acc = store.getAcceptance(prov.acceptanceId()).orElseThrow(() -> new SubledgerException("ACCEPTANCE_NOT_FOUND"));
        PoOrder po = store.getPo(prov.poId()).orElseThrow(() -> new SubledgerException("PO_NOT_FOUND"));

        BigDecimal actualNet = (BigDecimal) norm.get("actualNetAmount");
        BigDecimal actualTax = (BigDecimal) norm.get("actualTaxAmount");
        BigDecimal actualGross = (BigDecimal) norm.get("actualGrossAmount");

        BigDecimal provisionalNet = prov.estimatedNetAmount();
        BigDecimal totalVariance = SubledgerExtRules.calculateTotalVariance(provisionalNet, actualNet);

        BigDecimal acceptedQty = acc.lines().stream()
                .map(AcceptanceLine::acceptedQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal estUnitPrice = acceptedQty.signum() > 0 ? provisionalNet.divide(acceptedQty, 8, RoundingMode.HALF_UP) : BigDecimal.ZERO;

        BigDecimal invoicedQty = norm.get("invoicedQuantity") instanceof BigDecimal q ? q : acceptedQty;
        BigDecimal actualUnitPrice = invoicedQty.signum() > 0 ? actualNet.divide(invoicedQty, 8, RoundingMode.HALF_UP) : BigDecimal.ZERO;

        BigDecimal priceVariance = SubledgerExtRules.calculatePriceVariance(estUnitPrice, actualUnitPrice, invoicedQty);
        BigDecimal quantityVariance = SubledgerExtRules.calculateQuantityVariance(acceptedQty, invoicedQty, estUnitPrice);

        ProvisionalSettlement ps = store.insertProvisionalSettlement(
                tenantId, bookId, yearId, actorId,
                provId,
                (String) norm.get("invoiceNumber"),
                (LocalDate) norm.get("invoiceDate"),
                actualNet, actualTax, actualGross,
                priceVariance, quantityVariance, totalVariance,
                invoicedQty
        );
        store.saveIdempotentResponse(tenantId, bookId, yearId, idemKey, "SETTLE_PROVISIONAL", ps.id(),
                "{\"id\":\"" + ps.id() + "\",\"status\":\"" + ps.status() + "\"}");
        return ps;
    }

    // --- AR Contract Assets (CAS 14) ---
    public ContractAsset createContractAsset(UUID tenantId, UUID bookId, UUID yearId, UUID actorId, AccountingStandard standard, Map<String, Object> raw) {
        SubledgerExtRules.validateContractAssetAllowed(standard);
        Map<String, Object> norm = SubledgerExtInput.normalizeContractAssetCreate(raw);
        String idemKey = (String) norm.get("idempotencyKey");
        Optional<UUID> existing = store.findIdempotentResourceId(tenantId, bookId, yearId, idemKey, "CREATE_CONTRACT_ASSET");
        if (existing.isPresent()) {
            return store.getContractAsset(existing.get()).orElseThrow();
        }
        ContractAsset ca = store.insertContractAsset(tenantId, bookId, yearId, actorId, norm);
        store.saveIdempotentResponse(tenantId, bookId, yearId, idemKey, "CREATE_CONTRACT_ASSET", ca.id(),
                "{\"id\":\"" + ca.id() + "\",\"status\":\"" + ca.status() + "\"}");
        return ca;
    }

    public void reclassifyContractAsset(UUID tenantId, UUID bookId, UUID yearId, UUID actorId, UUID assetId, AccountingStandard standard, Map<String, Object> raw) {
        SubledgerExtRules.validateContractAssetAllowed(standard);
        Map<String, Object> norm = SubledgerExtInput.normalizeContractAssetReclassify(raw);
        String idemKey = (String) norm.get("idempotencyKey");
        Optional<UUID> existing = store.findIdempotentResourceId(tenantId, bookId, yearId, idemKey, "RECLASSIFY_CONTRACT_ASSET");
        if (existing.isPresent()) {
            return;
        }
        ContractAsset ca = store.getContractAsset(assetId).orElseThrow(() -> new SubledgerException("CONTRACT_ASSET_NOT_FOUND"));
        SubledgerExtRules.validateIndependentApproval(ca.createdBy(), actorId);

        UUID reclassifiedArId = UUID.randomUUID();
        store.reclassifyContractAsset(assetId, (LocalDate) norm.get("reclassifiedDate"), reclassifiedArId, actorId);
        store.saveIdempotentResponse(tenantId, bookId, yearId, idemKey, "RECLASSIFY_CONTRACT_ASSET", assetId, "{\"status\":\"RECLASSIFIED\"}");
    }

    // --- AR Variable Consideration ---
    public VariableConsideration createVariableConsideration(UUID tenantId, UUID bookId, UUID yearId, UUID actorId, AccountingStandard standard, Map<String, Object> raw) {
        SubledgerExtRules.validateContractAssetAllowed(standard);
        Map<String, Object> norm = SubledgerExtInput.normalizeVariableConsiderationCreate(raw);
        String idemKey = (String) norm.get("idempotencyKey");
        Optional<UUID> existing = store.findIdempotentResourceId(tenantId, bookId, yearId, idemKey, "CREATE_VAR_CONSIDERATION");
        if (existing.isPresent()) {
            return store.getVariableConsideration(existing.get()).orElseThrow();
        }
        VariableConsideration vc = store.insertVariableConsideration(tenantId, bookId, yearId, actorId, norm);
        store.saveIdempotentResponse(tenantId, bookId, yearId, idemKey, "CREATE_VAR_CONSIDERATION", vc.id(),
                "{\"id\":\"" + vc.id() + "\",\"status\":\"" + vc.status() + "\"}");
        return vc;
    }

    public void approveVariableConsideration(UUID tenantId, UUID bookId, UUID yearId, UUID actorId, UUID varId, Map<String, Object> raw) {
        Map<String, Object> norm = SubledgerExtInput.normalizeApproval(raw);
        String idemKey = (String) norm.get("idempotencyKey");
        Optional<UUID> existing = store.findIdempotentResourceId(tenantId, bookId, yearId, idemKey, "APPROVE_VAR_CONSIDERATION");
        if (existing.isPresent()) {
            return;
        }
        VariableConsideration vc = store.getVariableConsideration(varId).orElseThrow(() -> new SubledgerException("VARIABLE_CONSIDERATION_NOT_FOUND"));
        SubledgerExtRules.validateIndependentApproval(vc.preparerId(), actorId);

        String digest = SubledgerExtRules.computeApprovalDigest(actorId, varId.toString(), vc.constrainedCapAmount().toPlainString(), LocalDate.now().toString());
        store.approveVariableConsideration(varId, actorId, digest);
        store.saveIdempotentResponse(tenantId, bookId, yearId, idemKey, "APPROVE_VAR_CONSIDERATION", varId, "{\"status\":\"APPROVED\"}");
    }

    public void reviseVariableConsideration(UUID tenantId, UUID bookId, UUID yearId, UUID actorId, UUID varId, Map<String, Object> raw) {
        Map<String, Object> norm = SubledgerExtInput.normalizeVariableConsiderationRevise(raw);
        String idemKey = (String) norm.get("idempotencyKey");
        Optional<UUID> existing = store.findIdempotentResourceId(tenantId, bookId, yearId, idemKey, "REVISE_VAR_CONSIDERATION");
        if (existing.isPresent()) {
            return;
        }
        VariableConsideration vc = store.getVariableConsideration(varId).orElseThrow(() -> new SubledgerException("VARIABLE_CONSIDERATION_NOT_FOUND"));
        SubledgerExtRules.validateIndependentApproval(vc.preparerId(), actorId);

        BigDecimal newCap = (BigDecimal) norm.get("revisedCapAmount");
        SubledgerExtRules.require(newCap.compareTo(vc.estimatedAmount()) <= 0, "VARIABLE_CAP_EXCEEDS_ESTIMATED_AMOUNT",
                "Revised cap " + newCap.toPlainString() + " exceeds estimated amount " + vc.estimatedAmount().toPlainString());

        store.reviseVariableConsideration(varId, newCap, (String) norm.get("revisionReason"));
        store.saveIdempotentResponse(tenantId, bookId, yearId, idemKey, "REVISE_VAR_CONSIDERATION", varId, "{\"status\":\"REVISED\"}");
    }

    // --- AR Multi-Year Contract Continuity ---
    public MultiYearContract syncMultiYearContract(UUID tenantId, UUID bookId, UUID yearId, UUID actorId, Map<String, Object> raw) {
        Map<String, Object> norm = SubledgerExtInput.normalizeMultiYearSync(raw);
        String idemKey = (String) norm.get("idempotencyKey");
        MultiYearContract myc = store.upsertMultiYearContract(
                tenantId, bookId,
                (UUID) norm.get("contractId"),
                (Integer) norm.get("startYear"),
                (Integer) norm.get("endYear"),
                (BigDecimal) norm.get("totalContractValue"),
                (BigDecimal) norm.get("cumulativeProgressPct"),
                (Integer) norm.get("syncYear")
        );
        store.saveIdempotentResponse(tenantId, bookId, yearId, idemKey, "SYNC_MULTI_YEAR", myc.id(),
                "{\"id\":\"" + myc.id() + "\",\"syncYear\":" + norm.get("syncYear") + "}");
        return myc;
    }

    // --- AR ECL & Bad Debt Provision ---
    public EclRecord createEclRecord(UUID tenantId, UUID bookId, UUID yearId, UUID actorId, AccountingStandard standard, Map<String, Object> raw) {
        Map<String, Object> norm = SubledgerExtInput.normalizeEclCreate(raw);
        EclOperationKind opKind = (EclOperationKind) norm.get("operationKind");
        SubledgerExtRules.validateBadDebtProvisionAllowed(standard, opKind);
        String idemKey = (String) norm.get("idempotencyKey");
        Optional<UUID> existing = store.findIdempotentResourceId(tenantId, bookId, yearId, idemKey, "CREATE_ECL");
        if (existing.isPresent()) {
            return store.getEclRecord(existing.get()).orElseThrow();
        }
        EclRecord ecl = store.insertEclRecord(tenantId, bookId, yearId, actorId, norm);
        store.saveIdempotentResponse(tenantId, bookId, yearId, idemKey, "CREATE_ECL", ecl.id(),
                "{\"id\":\"" + ecl.id() + "\",\"status\":\"" + ecl.status() + "\"}");
        return ecl;
    }

    public void approveEclRecord(UUID tenantId, UUID bookId, UUID yearId, UUID actorId, UUID eclId, Map<String, Object> raw) {
        Map<String, Object> norm = SubledgerExtInput.normalizeApproval(raw);
        String idemKey = (String) norm.get("idempotencyKey");
        Optional<UUID> existing = store.findIdempotentResourceId(tenantId, bookId, yearId, idemKey, "APPROVE_ECL");
        if (existing.isPresent()) {
            return;
        }
        EclRecord ecl = store.getEclRecord(eclId).orElseThrow(() -> new SubledgerException("ECL_RECORD_NOT_FOUND"));
        SubledgerExtRules.validateIndependentApproval(ecl.preparerId(), actorId);

        String digest = SubledgerExtRules.computeApprovalDigest(actorId, eclId.toString(), ecl.provisionAmount().toPlainString(), LocalDate.now().toString());
        store.approveEclRecord(eclId, actorId, digest);
        store.saveIdempotentResponse(tenantId, bookId, yearId, idemKey, "APPROVE_ECL", eclId, "{\"status\":\"POSTED\"}");
    }

    // --- AR Sales Allowance, Discount & Return ---
    public AllowanceAdjustment createAllowanceAdjustment(UUID tenantId, UUID bookId, UUID yearId, UUID actorId, Map<String, Object> raw) {
        Map<String, Object> norm = SubledgerExtInput.normalizeAllowanceAdjustmentCreate(raw);
        String idemKey = (String) norm.get("idempotencyKey");
        Optional<UUID> existing = store.findIdempotentResourceId(tenantId, bookId, yearId, idemKey, "CREATE_ALLOWANCE");
        if (existing.isPresent()) {
            return store.getAllowanceAdjustment(existing.get()).orElseThrow();
        }
        AllowanceAdjustment adj = store.insertAllowanceAdjustment(tenantId, bookId, yearId, actorId, norm);
        store.saveIdempotentResponse(tenantId, bookId, yearId, idemKey, "CREATE_ALLOWANCE", adj.id(),
                "{\"id\":\"" + adj.id() + "\",\"status\":\"" + adj.status() + "\"}");
        return adj;
    }

    public void approveAllowanceAdjustment(UUID tenantId, UUID bookId, UUID yearId, UUID actorId, UUID adjId, Map<String, Object> raw) {
        Map<String, Object> norm = SubledgerExtInput.normalizeApproval(raw);
        String idemKey = (String) norm.get("idempotencyKey");
        Optional<UUID> existing = store.findIdempotentResourceId(tenantId, bookId, yearId, idemKey, "APPROVE_ALLOWANCE");
        if (existing.isPresent()) {
            return;
        }
        AllowanceAdjustment adj = store.getAllowanceAdjustment(adjId).orElseThrow(() -> new SubledgerException("ALLOWANCE_NOT_FOUND"));
        SubledgerExtRules.validateIndependentApproval(adj.preparerId(), actorId);

        String digest = SubledgerExtRules.computeApprovalDigest(actorId, adjId.toString(), adj.grossAdjustmentAmount().toPlainString(), LocalDate.now().toString());
        store.approveAllowanceAdjustment(adjId, actorId, digest);
        store.saveIdempotentResponse(tenantId, bookId, yearId, idemKey, "APPROVE_ALLOWANCE", adjId, "{\"status\":\"POSTED\"}");
    }

    public SubledgerExtStore getStore() {
        return store;
    }
}
