package com.lumira.finance.subledgerext;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import com.lumira.finance.subledgerext.SubledgerExtRules.*;

class SubledgerExtRulesTest {

    @Test
    void testVarianceCalculations() {
        // Price Variance = (actualUnitPrice - estimatedUnitPrice) * invoicedQuantity
        BigDecimal estPrice = new BigDecimal("100.00000000");
        BigDecimal actPrice = new BigDecimal("105.00000000");
        BigDecimal invoicedQty = new BigDecimal("50.00000000");
        BigDecimal pv = SubledgerExtRules.calculatePriceVariance(estPrice, actPrice, invoicedQty);
        assertEquals(new BigDecimal("250.00000000"), pv);

        // Quantity Variance = (invoicedQuantity - acceptedQuantity) * estimatedUnitPrice
        BigDecimal acceptedQty = new BigDecimal("48.00000000");
        BigDecimal qv = SubledgerExtRules.calculateQuantityVariance(acceptedQty, invoicedQty, estPrice);
        assertEquals(new BigDecimal("200.00000000"), qv);

        // Total Variance = actualNet - provisionalNet
        BigDecimal provNet = estPrice.multiply(acceptedQty); // 4800.00
        BigDecimal actNet = actPrice.multiply(invoicedQty); // 5250.00
        BigDecimal tv = SubledgerExtRules.calculateTotalVariance(provNet, actNet);
        assertEquals(new BigDecimal("450.00000000"), tv);
        assertEquals(pv.add(qv), tv);
    }

    @Test
    void testEclAgingBucketsAndProvision() {
        BigDecimal lossNotDue = SubledgerExtRules.defaultLossRate(AgingBucket.NOT_DUE);
        assertEquals(new BigDecimal("0.005000000000"), lossNotDue);

        BigDecimal loss181Plus = SubledgerExtRules.defaultLossRate(AgingBucket.DAYS_181_PLUS);
        assertEquals(new BigDecimal("0.500000000000"), loss181Plus);

        BigDecimal receivable = new BigDecimal("10000.00000000");
        BigDecimal targetProv = SubledgerExtRules.calculateEclProvision(receivable, loss181Plus);
        assertEquals(new BigDecimal("5000.00000000"), targetProv);

        BigDecimal priorAllowance = new BigDecimal("2000.00000000");
        BigDecimal adj = SubledgerExtRules.calculateEclAdjustment(targetProv, priorAllowance);
        assertEquals(new BigDecimal("3000.00000000"), adj);
    }

    @Test
    void testVariableConsiderationCap() {
        BigDecimal estimatedBonus = new BigDecimal("50000.00000000");
        BigDecimal confidence = new BigDecimal("0.8000");
        BigDecimal maxCap = SubledgerExtRules.calculateConstrainedCap(estimatedBonus, confidence);
        assertEquals(new BigDecimal("40000.00000000"), maxCap);
    }

    @Test
    void testMultiYearContinuityMath() {
        BigDecimal totalContract = new BigDecimal("1000000.00000000");
        BigDecimal progressYear1 = new BigDecimal("0.300000000000");
        BigDecimal revYear1 = SubledgerExtRules.calculateMultiYearRevenue(totalContract, progressYear1, BigDecimal.ZERO);
        assertEquals(new BigDecimal("300000.00000000"), revYear1);

        BigDecimal progressYear2 = new BigDecimal("0.750000000000");
        BigDecimal revYear2 = SubledgerExtRules.calculateMultiYearRevenue(totalContract, progressYear2, revYear1);
        assertEquals(new BigDecimal("450000.00000000"), revYear2);
    }

    @Test
    void testCasVsSmallPolicySeparation() {
        // Contract Assets: CAS allowed, SMALL rejected
        assertDoesNotThrow(() -> SubledgerExtRules.validateContractAssetAllowed(AccountingStandard.CAS));
        SubledgerException exCa = assertThrows(SubledgerException.class,
                () -> SubledgerExtRules.validateContractAssetAllowed(AccountingStandard.SMALL));
        assertEquals("SMALL_DOES_NOT_SUPPORT_CONTRACT_ASSET", exCa.code);

        // Bad Debt Provision: CAS allowed, SMALL rejected for PROVISION and REVERSAL
        assertDoesNotThrow(() -> SubledgerExtRules.validateBadDebtProvisionAllowed(AccountingStandard.CAS, EclOperationKind.PROVISION));
        assertDoesNotThrow(() -> SubledgerExtRules.validateBadDebtProvisionAllowed(AccountingStandard.CAS, EclOperationKind.REVERSAL));

        SubledgerException exProv = assertThrows(SubledgerException.class,
                () -> SubledgerExtRules.validateBadDebtProvisionAllowed(AccountingStandard.SMALL, EclOperationKind.PROVISION));
        assertEquals("SMALL_DOES_NOT_SUPPORT_BAD_DEBT_PROVISION", exProv.code);

        SubledgerException exRev = assertThrows(SubledgerException.class,
                () -> SubledgerExtRules.validateBadDebtProvisionAllowed(AccountingStandard.SMALL, EclOperationKind.REVERSAL));
        assertEquals("SMALL_DOES_NOT_SUPPORT_BAD_DEBT_PROVISION", exRev.code);

        // Direct write-off: allowed under both CAS and SMALL
        assertDoesNotThrow(() -> SubledgerExtRules.validateBadDebtProvisionAllowed(AccountingStandard.CAS, EclOperationKind.WRITE_OFF));
        assertDoesNotThrow(() -> SubledgerExtRules.validateBadDebtProvisionAllowed(AccountingStandard.SMALL, EclOperationKind.WRITE_OFF));
    }

    @Test
    void testSegregationOfDuties() {
        UUID user1 = UUID.randomUUID();
        UUID user2 = UUID.randomUUID();

        assertDoesNotThrow(() -> SubledgerExtRules.validateIndependentApproval(user1, user2));

        SubledgerException ex = assertThrows(SubledgerException.class,
                () -> SubledgerExtRules.validateIndependentApproval(user1, user1));
        assertEquals("SELF_APPROVAL_FORBIDDEN", ex.code);
    }

    @Test
    void testMoneyScaleValidation() {
        assertEquals(new BigDecimal("100.50000000"), SubledgerExtRules.money("100.5", false));
        assertEquals(new BigDecimal("0.00000000"), SubledgerExtRules.money("0.00", true));

        assertThrows(SubledgerException.class, () -> SubledgerExtRules.money("0.00", false));
        assertThrows(SubledgerException.class, () -> SubledgerExtRules.money("-10.00", true));
        assertThrows(SubledgerException.class, () -> SubledgerExtRules.money("100.555", false)); // 3 decimals
        assertThrows(SubledgerException.class, () -> SubledgerExtRules.money("1e4", false));
    }
}
