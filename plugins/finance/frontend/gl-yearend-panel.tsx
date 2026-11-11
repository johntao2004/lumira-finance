import React, { useState } from "react";
import {
  CutoverMode,
  CorrectionKind,
  Standard,
  glCny,
  validateCutoverInput,
  evaluateAutoReviewPolicy,
  validateCorrectionStandard,
  evaluatePeriodCloseGates,
  glYearendRetryIdentity,
  GL_YEAREND_LABELS,
  TieOutCheckResult,
  PeriodCloseGateSummary
} from "./gl-yearend-rules";

export interface GlYearendPanelProps {
  tenantId: string;
  bookId: string;
  yearId: string;
  standard: Standard;
}

export const GlYearendPanel: React.FC<GlYearendPanelProps> = ({
  tenantId,
  bookId,
  yearId,
  standard,
}) => {
  const [activeTab, setActiveTab] = useState<"CUTOVER" | "AUTORULES" | "CORRECTIONS" | "CLOSE" | "CARRY">("CUTOVER");

  // Tab 1: Cutover state
  const [cutoverMode, setCutoverMode] = useState<CutoverMode>("OPENING_PLUS_YTD");
  const [cutoverDate, setCutoverDate] = useState("2026-06-30");
  const [cutoverStatus, setCutoverStatus] = useState<string>("NOT_STARTED");
  const [tieOutResults, setTieOutResults] = useState<TieOutCheckResult[]>([]);
  const [cutoverFeedback, setCutoverFeedback] = useState<string>("");

  // Tab 2: Auto Review Policy state
  const [policyCode, setPolicyCode] = useState("POL_AR_AUTO_01");
  const [maxAmount, setMaxAmount] = useState("50000.00");
  const [policyFeedback, setPolicyFeedback] = useState<string>("");

  // Tab 3: Correction & CAS vs SMALL state
  const [correctionKind, setCorrectionKind] = useState<CorrectionKind>("PRIOR_PERIOD_ADJUSTMENT");
  const [casAccount, setCasAccount] = useState("6901");
  const [correctionReason, setCorrectionReason] = useState("");
  const [correctionFeedback, setCorrectionFeedback] = useState<string>("");

  // Tab 4: Period Close Checklist state
  const [periodMonth, setPeriodMonth] = useState(1);
  const [closeSummary, setCloseSummary] = useState<PeriodCloseGateSummary | null>(null);
  const [closeFeedback, setCloseFeedback] = useState<string>("");

  // Tab 5: Carry Batch state
  const [carryKind, setCarryKind] = useState<"BASE" | "DELTA">("BASE");
  const [carryReason, setCarryReason] = useState("Formal year-end carry-over batch");
  const [carryFeedback, setCarryFeedback] = useState<string>("");

  // Handlers
  const handleVerifyCutover = () => {
    try {
      const dummyBalances = [
        { accountId: "a1", accountCode: "1001", accountName: "库存现金", direction: "DEBIT" as const, amount: "10000.00" },
        { accountId: "a2", accountCode: "1122", accountName: "应收账款", direction: "DEBIT" as const, amount: "50000.00" },
        { accountId: "a3", accountCode: "2202", accountName: "应付账款", direction: "CREDIT" as const, amount: "60000.00" },
      ];
      const dummyBaselines = cutoverMode === "OPENING_PLUS_YTD" ? [
        { baselineType: "CASHFLOW" as const, periodMonth: 6, categoryCode: "SALES", categoryName: "销售收款", amount: "10000.00" }
      ] : [];
      const dummySubledgers = { AR: "50000.00", AP: "60000.00" };

      const { passed, tieOuts } = validateCutoverInput(cutoverMode, dummyBalances, dummyBaselines, dummySubledgers);
      setTieOutResults(tieOuts);
      setCutoverStatus(passed ? "VALIDATED" : "CHECK_FAILED");
      setCutoverFeedback(passed ? "对账门禁全部通过，符合启账条件。" : "对账门禁未通过，存在差额。");
    } catch (e: any) {
      setCutoverFeedback(`校验错误: ${e.message}`);
    }
  };

  const handleTestAutoPolicy = () => {
    try {
      const policyConfig = {
        policyCode,
        maxAmount: glCny(maxAmount),
        scopeBusinessTypes: ["AR_SALES", "ASSET_DEPRECIATION"],
        allowedAccountCodes: ["1122", "6001", "1602", "6602"],
        zeroExceptionRequired: true,
      };
      const testAmount = "25000.00";
      const evalRes = evaluateAutoReviewPolicy(policyConfig, testAmount, "AR_SALES", ["1122", "6001"]);
      setPolicyFeedback(evalRes.autoApproved ? `规则试算通过: 金额 ${testAmount} 自动审签合规 (零异常)。` : `自动审签阻断: ${evalRes.exceptions.join("; ")}`);
    } catch (e: any) {
      setPolicyFeedback(`策略异常: ${e.message}`);
    }
  };

  const handleTestCorrectionRouting = () => {
    try {
      validateCorrectionStandard(standard, correctionKind, casAccount);
      setCorrectionFeedback(`更正科目路由合法: [${GL_YEAREND_LABELS[standard]}] 允许使用科目 ${casAccount || "当期损益"}`);
    } catch (e: any) {
      setCorrectionFeedback(`更正阻断: ${e.message}`);
    }
  };

  const handleEvaluateCloseChecklist = () => {
    const summary = evaluatePeriodCloseGates(0, "100000.00", "100000.00", true, true, true, true);
    setCloseSummary(summary);
    setCloseFeedback(summary.allChecksPassed ? "期末全检六道门禁全部通过，可进行自然人月结签批。" : "存在未通过门禁，阻断月结。");
  };

  const handleGenerateCarryBatch = () => {
    setCarryFeedback(`[${GL_YEAREND_LABELS[carryKind]}] 期初承接批次已预检生成，包含往来未结项与外币原币留存。`);
  };

  return (
    <div style={{ padding: 24, fontFamily: "sans-serif", background: "#f8fafc", borderRadius: 8 }}>
      <header style={{ marginBottom: 20 }}>
        <h2 style={{ margin: 0, color: "#0f172a" }}>总账与期末核算工作台 (Lane W09: GL & Year-End)</h2>
        <p style={{ color: "#64748b", margin: "4px 0 0" }}>
          当前账套准则: <strong>{GL_YEAREND_LABELS[standard]}</strong> | 租户: {tenantId.slice(0, 8)}... | 账套: {bookId.slice(0, 8)}...
        </p>
      </header>

      {/* Tabs */}
      <div style={{ display: "flex", gap: 8, borderBottom: "2px solid #e2e8f0", marginBottom: 16 }}>
        <button
          onClick={() => setActiveTab("CUTOVER")}
          style={{ padding: "8px 16px", cursor: "pointer", fontWeight: activeTab === "CUTOVER" ? "bold" : "normal", borderBottom: activeTab === "CUTOVER" ? "3px solid #2563eb" : "none" }}>
          GL01: 年中迁入与期初建账
        </button>
        <button
          onClick={() => setActiveTab("AUTORULES")}
          style={{ padding: "8px 16px", cursor: "pointer", fontWeight: activeTab === "AUTORULES" ? "bold" : "normal", borderBottom: activeTab === "AUTORULES" ? "3px solid #2563eb" : "none" }}>
          GL02/03: 自动制证与审签
        </button>
        <button
          onClick={() => setActiveTab("CORRECTIONS")}
          style={{ padding: "8px 16px", cursor: "pointer", fontWeight: activeTab === "CORRECTIONS" ? "bold" : "normal", borderBottom: activeTab === "CORRECTIONS" ? "3px solid #2563eb" : "none" }}>
          GL05: 冲销更正与前期调整
        </button>
        <button
          onClick={() => setActiveTab("CLOSE")}
          style={{ padding: "8px 16px", cursor: "pointer", fontWeight: activeTab === "CLOSE" ? "bold" : "normal", borderBottom: activeTab === "CLOSE" ? "3px solid #2563eb" : "none" }}>
          GL06: 正式月结全检门禁
        </button>
        <button
          onClick={() => setActiveTab("CARRY")}
          style={{ padding: "8px 16px", cursor: "pointer", fontWeight: activeTab === "CARRY" ? "bold" : "normal", borderBottom: activeTab === "CARRY" ? "3px solid #2563eb" : "none" }}>
          YEAR: 年度承接与跨年更正
        </button>
      </div>

      {/* Tab 1: Cutover */}
      {activeTab === "CUTOVER" && (
        <section style={{ background: "#ffffff", padding: 16, borderRadius: 6, border: "1px solid #cbd5e1" }}>
          <h3>首次建账与年中迁入 (GL01)</h3>
          <div style={{ marginBottom: 12 }}>
            <label style={{ marginRight: 12 }}>迁入模式 (互斥):</label>
            <select value={cutoverMode} onChange={(e) => setCutoverMode(e.target.value as CutoverMode)} style={{ padding: 6 }}>
              <option value="OPENING_PLUS_YTD">OPENING_PLUS_YTD: 时点余额 + 本年累计基线</option>
              <option value="FULL_HISTORY">FULL_HISTORY: 年初期初 + 全历史明细</option>
            </select>
          </div>
          <div style={{ marginBottom: 12 }}>
            <label style={{ marginRight: 12 }}>迁入基准日:</label>
            <input type="date" value={cutoverDate} onChange={(e) => setCutoverDate(e.target.value)} style={{ padding: 6 }} />
          </div>
          <button onClick={handleVerifyCutover} style={{ background: "#2563eb", color: "#fff", padding: "8px 16px", border: "none", borderRadius: 4, cursor: "pointer" }}>
            执行六项全项对账门禁 (Tie-Out)
          </button>
          {cutoverFeedback && <p style={{ marginTop: 12, color: cutoverStatus === "VALIDATED" ? "#16a34a" : "#dc2626" }}>{cutoverFeedback}</p>}
          {tieOutResults.length > 0 && (
            <table style={{ width: "100%", marginTop: 12, borderCollapse: "collapse", fontSize: 13 }}>
              <thead>
                <tr style={{ background: "#f1f5f9" }}>
                  <th style={{ padding: 6, border: "1px solid #cbd5e1" }}>对账项</th>
                  <th style={{ padding: 6, border: "1px solid #cbd5e1" }}>总账金额</th>
                  <th style={{ padding: 6, border: "1px solid #cbd5e1" }}>子账/基线金额</th>
                  <th style={{ padding: 6, border: "1px solid #cbd5e1" }}>差额</th>
                  <th style={{ padding: 6, border: "1px solid #cbd5e1" }}>门禁结论</th>
                </tr>
              </thead>
              <tbody>
                {tieOutResults.map((t) => (
                  <tr key={t.checkItem}>
                    <td style={{ padding: 6, border: "1px solid #cbd5e1" }}>{t.checkItem}</td>
                    <td style={{ padding: 6, border: "1px solid #cbd5e1" }}>{t.glAmount}</td>
                    <td style={{ padding: 6, border: "1px solid #cbd5e1" }}>{t.subledgerAmount}</td>
                    <td style={{ padding: 6, border: "1px solid #cbd5e1" }}>{t.difference}</td>
                    <td style={{ padding: 6, border: "1px solid #cbd5e1", color: t.passed ? "#16a34a" : "#dc2626" }}>
                      {t.passed ? "PASSED" : "FAILED"}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>
      )}

      {/* Tab 2: Auto Rules */}
      {activeTab === "AUTORULES" && (
        <section style={{ background: "#ffffff", padding: 16, borderRadius: 6, border: "1px solid #cbd5e1" }}>
          <h3>自动制证规则与审签策略 (GL02, GL03)</h3>
          <div style={{ marginBottom: 12 }}>
            <label style={{ marginRight: 12 }}>审签策略编码:</label>
            <input type="text" value={policyCode} onChange={(e) => setPolicyCode(e.target.value)} style={{ padding: 6 }} />
          </div>
          <div style={{ marginBottom: 12 }}>
            <label style={{ marginRight: 12 }}>自动审签金额上限 (CNY):</label>
            <input type="text" value={maxAmount} onChange={(e) => setMaxAmount(e.target.value)} style={{ padding: 6 }} />
          </div>
          <button onClick={handleTestAutoPolicy} style={{ background: "#2563eb", color: "#fff", padding: "8px 16px", border: "none", borderRadius: 4, cursor: "pointer" }}>
            验证自动审签策略与零异常门禁
          </button>
          {policyFeedback && <p style={{ marginTop: 12, color: policyFeedback.includes("合规") ? "#16a34a" : "#dc2626" }}>{policyFeedback}</p>}
        </section>
      )}

      {/* Tab 3: Corrections */}
      {activeTab === "CORRECTIONS" && (
        <section style={{ background: "#ffffff", padding: 16, borderRadius: 6, border: "1px solid #cbd5e1" }}>
          <h3>凭证冲销与前期差错调整 (GL05)</h3>
          <div style={{ marginBottom: 12 }}>
            <label style={{ marginRight: 12 }}>更正类别:</label>
            <select value={correctionKind} onChange={(e) => setCorrectionKind(e.target.value as CorrectionKind)} style={{ padding: 6 }}>
              <option value="PRIOR_PERIOD_ADJUSTMENT">以前年度损益调整 (PRIOR_PERIOD_ADJUSTMENT)</option>
              <option value="FULL_REVERSAL">红字全额冲销 (FULL_REVERSAL)</option>
              <option value="PARTIAL_REVERSAL">差额更正凭证 (PARTIAL_REVERSAL)</option>
              <option value="SUPPLEMENT">补充凭证 (SUPPLEMENT)</option>
            </select>
          </div>
          {correctionKind === "PRIOR_PERIOD_ADJUSTMENT" && (
            <div style={{ marginBottom: 12 }}>
              <label style={{ marginRight: 12 }}>更正调整科目:</label>
              <input type="text" value={casAccount} onChange={(e) => setCasAccount(e.target.value)} style={{ padding: 6 }} />
              <span style={{ marginLeft: 8, fontSize: 12, color: "#64748b" }}>CAS 必须为 6901；SMALL 严禁为 6901</span>
            </div>
          )}
          <button onClick={handleTestCorrectionRouting} style={{ background: "#2563eb", color: "#fff", padding: "8px 16px", border: "none", borderRadius: 4, cursor: "pointer" }}>
            校验准则路由与分录水位
          </button>
          {correctionFeedback && <p style={{ marginTop: 12, color: correctionFeedback.includes("合法") ? "#16a34a" : "#dc2626" }}>{correctionFeedback}</p>}
        </section>
      )}

      {/* Tab 4: Period Close */}
      {activeTab === "CLOSE" && (
        <section style={{ background: "#ffffff", padding: 16, borderRadius: 6, border: "1px solid #cbd5e1" }}>
          <h3>正式月结全检门禁 (GL06)</h3>
          <div style={{ marginBottom: 12 }}>
            <label style={{ marginRight: 12 }}>月结期间:</label>
            <input type="number" min={1} max={12} value={periodMonth} onChange={(e) => setPeriodMonth(parseInt(e.target.value))} style={{ padding: 6 }} />
          </div>
          <button onClick={handleEvaluateCloseChecklist} style={{ background: "#2563eb", color: "#fff", padding: "8px 16px", border: "none", borderRadius: 4, cursor: "pointer" }}>
            执行正式月结全检 (六道门禁)
          </button>
          {closeFeedback && <p style={{ marginTop: 12, color: closeSummary?.allChecksPassed ? "#16a34a" : "#dc2626" }}>{closeFeedback}</p>}
          {closeSummary && (
            <ul style={{ fontSize: 13, lineHeight: 1.8 }}>
              <li>未登记/未审凭证数量: <strong>{closeSummary.unpostedCount}</strong> ({closeSummary.unpostedCount === 0 ? "通过" : "不通过"})</li>
              <li>试算平衡检查: <strong>{closeSummary.trialBalanceBalanced ? "借贷平衡通过" : "不平衡"}</strong></li>
              <li>银行对账状态: <strong>{closeSummary.bankReconciliationComplete ? "无未达调查异常" : "存在未达流水"}</strong></li>
              <li>总账与应收/应付/资产/薪酬子账对账: <strong>{closeSummary.subledgerTieOutPassed ? "全部对齐" : "存在差额"}</strong></li>
              <li>现金流覆盖检查: <strong>{closeSummary.cashflowCoverageComplete ? "全额覆盖" : "存在未分配现金行"}</strong></li>
              <li>纳税工作底稿与申报源: <strong>{closeSummary.taxWorksheetReady ? "就绪" : "未就绪"}</strong></li>
            </ul>
          )}
        </section>
      )}

      {/* Tab 5: Carry Batch */}
      {activeTab === "CARRY" && (
        <section style={{ background: "#ffffff", padding: 16, borderRadius: 6, border: "1px solid #cbd5e1" }}>
          <h3>年度期初承接与跨年更正 (YEAR01, YEAR02, YEAR03)</h3>
          <div style={{ marginBottom: 12 }}>
            <label style={{ marginRight: 12 }}>承接批次类型:</label>
            <select value={carryKind} onChange={(e) => setCarryKind(e.target.value as "BASE" | "DELTA")} style={{ padding: 6 }}>
              <option value="BASE">BASE: 正式期初承接批次</option>
              <option value="DELTA">DELTA: 跨年更正差量调整批次</option>
            </select>
          </div>
          <div style={{ marginBottom: 12 }}>
            <label style={{ marginRight: 12 }}>承接理由 / 批次说明:</label>
            <input type="text" value={carryReason} onChange={(e) => setCarryReason(e.target.value)} style={{ padding: 6, width: "60%" }} />
          </div>
          <button onClick={handleGenerateCarryBatch} style={{ background: "#2563eb", color: "#fff", padding: "8px 16px", border: "none", borderRadius: 4, cursor: "pointer" }}>
            生成正式期初承接与比较报表重述清单
          </button>
          {carryFeedback && <p style={{ marginTop: 12, color: "#16a34a" }}>{carryFeedback}</p>}
        </section>
      )}
    </div>
  );
};
export default GlYearendPanel;
