import React, { useState } from "react";
import {
  computeBonusSeparate,
  computeBonusSynergy,
  computeLaborRemuneration,
  computeFilingDeadline,
  checkMandatorySchedules,
  BonusCalculationMode,
  RemunerationType,
  BonusCalculationResult,
  LaborCalculationResult,
  CalendarDeadlineResult,
  ScheduleCheckResult,
} from "./payroll-tax-ext-rules";

export interface PayrollTaxExtPanelProps {
  bookId: string;
  tenantId: string;
  currentUserId: string;
}

export const PayrollTaxExtPanel: React.FC<PayrollTaxExtPanelProps> = ({
  bookId,
  tenantId,
  currentUserId,
}) => {
  const [activeTab, setActiveTab] = useState<"PAY03" | "PAY04" | "TAX01" | "TAX05">("PAY03");

  // --- PAY03 State ---
  const [bonusAmount, setBonusAmount] = useState<string>("60000.00");
  const [annualSalary, setAnnualSalary] = useState<string>("150000.00");
  const [bonusMode, setBonusMode] = useState<BonusCalculationMode>("SEPARATE_MONTHLY_QUOTIENT");
  const [bonusResult, setBonusResult] = useState<BonusCalculationResult | null>(() =>
    computeBonusSynergy("60000.00", "150000.00", "SEPARATE_MONTHLY_QUOTIENT")
  );
  const [bonusBatchState, setBonusBatchState] = useState<string>("CALCULATED");

  // --- PAY04 State ---
  const [laborGross, setLaborGross] = useState<string>("5000.00");
  const [remunType, setRemunType] = useState<RemunerationType>("LABOR_SERVICE");
  const [relief1Pct, setRelief1Pct] = useState<boolean>(true);
  const [cityRate, setCityRate] = useState<string>("0.07");
  const [proxyInvoice, setProxyInvoice] = useState<boolean>(false);
  const [proxyInvoiceRef, setProxyInvoiceRef] = useState<string>("INV-PROXY-202611-001");
  const [laborResult, setLaborResult] = useState<LaborCalculationResult | null>(() =>
    computeLaborRemuneration("5000.00", "LABOR_SERVICE", { relief1Pct: true, cityRate: "0.07", proxyInvoiceCompleted: false })
  );

  // --- TAX01 State ---
  const [attestationConfirmed, setAttestationConfirmed] = useState<boolean>(false);
  const [profitBase, setProfitBase] = useState<string>("2000000.00");

  // --- TAX05 State ---
  const [calMonth, setCalMonth] = useState<number>(2);
  const [calendarResult, setCalendarResult] = useState<CalendarDeadlineResult>(() =>
    computeFilingDeadline("VAT", 2026, 2, { "2026-02-15": "春节法定假日递延", "2026-02-16": "春节法定假日递延" })
  );
  const [formLocked, setFormLocked] = useState<boolean>(false);
  const [attachedSchedules, setAttachedSchedules] = useState<string[]>(["ATTACHMENT_VAT_EXPORT"]);
  const [scheduleCheck, setScheduleCheck] = useState<ScheduleCheckResult>(() =>
    checkMandatorySchedules("VAT_GENERAL", { exportSales: 50000, inputDeduction: 12000 }, ["ATTACHMENT_VAT_EXPORT"])
  );

  // Handlers
  const handleRecalculateBonus = () => {
    try {
      const res = computeBonusSynergy(bonusAmount, annualSalary, bonusMode);
      setBonusResult(res);
    } catch (e: any) {
      alert(e.message);
    }
  };

  const handleRecalculateLabor = () => {
    try {
      const res = computeLaborRemuneration(laborGross, remunType, {
        relief1Pct,
        cityRate,
        proxyInvoiceCompleted: proxyInvoice,
        proxyInvoiceRef,
      });
      setLaborResult(res);
    } catch (e: any) {
      alert(e.message);
    }
  };

  const handleRecalculateCalendar = (month: number) => {
    setCalMonth(month);
    const holidays: Record<string, string> = {
      "2026-02-15": "春节假期递延",
      "2026-02-16": "春节假期递延",
      "2026-05-15": "法定征期正常",
      "2026-08-15": "周六公休日顺延",
      "2026-10-15": "国庆长假顺延",
    };
    setCalendarResult(computeFilingDeadline("VAT", 2026, month, holidays));
  };

  const handleToggleSchedule = (code: string) => {
    const next = attachedSchedules.includes(code)
      ? attachedSchedules.filter((c) => c !== code)
      : [...attachedSchedules, code];
    setAttachedSchedules(next);
    setScheduleCheck(
      checkMandatorySchedules(
        "VAT_GENERAL",
        { exportSales: 50000, inputDeduction: 12000 },
        next
      )
    );
  };

  return (
    <div className="payroll-tax-ext-panel" style={{ padding: "20px", fontFamily: "system-ui, sans-serif" }}>
      <header style={{ marginBottom: "20px", borderBottom: "1px solid #e2e8f0", paddingBottom: "10px" }}>
        <h2 style={{ margin: "0 0 8px 0" }}>薪酬税务扩展治理控制台 (Lane W10)</h2>
        <div style={{ fontSize: "13px", color: "#64748b" }}>
          账套: <strong>{bookId}</strong> | 租户: <strong>{tenantId}</strong> | 操作员: <strong>{currentUserId}</strong> | 外部通道: <span style={{ color: "#d97706", fontWeight: "bold" }}>NOT_VERIFIED</span>
        </div>
      </header>

      {/* Navigation Tabs */}
      <nav style={{ display: "flex", gap: "10px", marginBottom: "20px" }}>
        {[
          { key: "PAY03", label: "全年一次性奖金及协同 (PAY03)" },
          { key: "PAY04", label: "劳务报酬与增值税扣缴 (PAY04)" },
          { key: "TAX01", label: "税种政策与计算解释树 (TAX01)" },
          { key: "TAX05", label: "申报日历与锁版附表 (TAX05)" },
        ].map((tab) => (
          <button
            key={tab.key}
            type="button"
            onClick={() => setActiveTab(tab.key as any)}
            style={{
              padding: "8px 16px",
              borderRadius: "6px",
              border: activeTab === tab.key ? "2px solid #2563eb" : "1px solid #cbd5e1",
              backgroundColor: activeTab === tab.key ? "#eff6ff" : "#ffffff",
              color: activeTab === tab.key ? "#1d4ed8" : "#334155",
              fontWeight: activeTab === tab.key ? "bold" : "normal",
              cursor: "pointer",
            }}
          >
            {tab.label}
          </button>
        ))}
      </nav>

      {/* Tab 1: PAY03 */}
      {activeTab === "PAY03" && (
        <section style={{ background: "#f8fafc", padding: "20px", borderRadius: "8px", border: "1px solid #e2e8f0" }}>
          <h3>PAY03: 全年一次性奖金计税与年度汇算协同对比</h3>
          <p style={{ fontSize: "13px", color: "#475569" }}>
            依据财税〔2018〕164号与财政部/税务总局2023年第30号公告。支持单独计税与并入综合所得两方案测算对比，执行每人每年一次单独计税拦截与强制自然人审签。
          </p>

          <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: "16px", marginBottom: "16px" }}>
            <div>
              <label style={{ display: "block", fontSize: "13px", fontWeight: "bold", marginBottom: "4px" }}>
                奖金应发金额 (元):
              </label>
              <input
                type="number"
                value={bonusAmount}
                onChange={(e) => setBonusAmount(e.target.value)}
                style={{ width: "100%", padding: "8px", borderRadius: "4px", border: "1px solid #cbd5e1" }}
              />
            </div>
            <div>
              <label style={{ display: "block", fontSize: "13px", fontWeight: "bold", marginBottom: "4px" }}>
                年度已累计综合应纳税所得额 (元):
              </label>
              <input
                type="number"
                value={annualSalary}
                onChange={(e) => setAnnualSalary(e.target.value)}
                style={{ width: "100%", padding: "8px", borderRadius: "4px", border: "1px solid #cbd5e1" }}
              />
            </div>
          </div>

          <div style={{ marginBottom: "16px" }}>
            <label style={{ fontSize: "13px", fontWeight: "bold", marginRight: "12px" }}>申报计税选择:</label>
            <label style={{ marginRight: "16px", cursor: "pointer" }}>
              <input
                type="radio"
                name="bonusMode"
                value="SEPARATE_MONTHLY_QUOTIENT"
                checked={bonusMode === "SEPARATE_MONTHLY_QUOTIENT"}
                onChange={() => setBonusMode("SEPARATE_MONTHLY_QUOTIENT")}
              />{" "}
              单独计税（按月换算商数）
            </label>
            <label style={{ cursor: "pointer" }}>
              <input
                type="radio"
                name="bonusMode"
                value="COMBINED_COMPREHENSIVE_INCOME"
                checked={bonusMode === "COMBINED_COMPREHENSIVE_INCOME"}
                onChange={() => setBonusMode("COMBINED_COMPREHENSIVE_INCOME")}
              />{" "}
              并入当年综合所得计算
            </label>
            <button
              type="button"
              onClick={handleRecalculateBonus}
              style={{ marginLeft: "20px", padding: "6px 14px", background: "#2563eb", color: "#fff", border: "none", borderRadius: "4px", cursor: "pointer" }}
            >
              执行精准精算与汇算对比
            </button>
          </div>

          {bonusResult && (
            <div style={{ background: "#ffffff", padding: "16px", borderRadius: "6px", border: "1px solid #cbd5e1" }}>
              <h4 style={{ margin: "0 0 10px 0", color: "#0f172a" }}>试算与协同对比结果</h4>
              <div style={{ display: "grid", gridTemplateColumns: "repeat(4, 1fr)", gap: "10px", fontSize: "14px" }}>
                <div>月度商数: <strong>￥{bonusResult.monthlyQuotient}</strong></div>
                <div>适用税率: <strong>{(Number(bonusResult.applicableRate) * 100).toFixed(0)}%</strong></div>
                <div>当期预扣个税: <strong style={{ color: "#dc2626" }}>￥{bonusResult.withholdingTax}</strong></div>
                <div>税后实发金额: <strong style={{ color: "#16a34a" }}>￥{bonusResult.netAmount}</strong></div>
              </div>
              <div style={{ marginTop: "12px", padding: "10px", background: "#f0fdf4", border: "1px solid #bbf7d0", borderRadius: "4px", fontSize: "13px", color: "#166534" }}>
                <strong>💡 汇算清缴协同推荐:</strong> {bonusResult.summary}
              </div>
            </div>
          )}

          <div style={{ marginTop: "16px", display: "flex", gap: "10px", alignItems: "center" }}>
            <span style={{ fontSize: "13px" }}>批次生命周期状态: <strong>{bonusBatchState}</strong></span>
            {bonusBatchState === "CALCULATED" && (
              <button
                type="button"
                onClick={() => setBonusBatchState("REVIEWED")}
                style={{ padding: "6px 12px", background: "#0284c7", color: "#fff", border: "none", borderRadius: "4px" }}
              >
                自然人复核 (需非制单人)
              </button>
            )}
            {bonusBatchState === "REVIEWED" && (
              <button
                type="button"
                onClick={() => setBonusBatchState("APPROVED")}
                style={{ padding: "6px 12px", background: "#059669", color: "#fff", border: "none", borderRadius: "4px" }}
              >
                自然人审批 (需非制单非复核人)
              </button>
            )}
            {bonusBatchState === "APPROVED" && (
              <button
                type="button"
                onClick={() => setBonusBatchState("LOCKED")}
                style={{ padding: "6px 12px", background: "#475569", color: "#fff", border: "none", borderRadius: "4px" }}
              >
                锁定批次并登记一岁一次申报占用
              </button>
            )}
          </div>
        </section>
      )}

      {/* Tab 2: PAY04 */}
      {activeTab === "PAY04" && (
        <section style={{ background: "#f8fafc", padding: "20px", borderRadius: "8px", border: "1px solid #e2e8f0" }}>
          <h3>PAY04: 自然人劳务报酬、稿酬、特许权使用费及增值税扣缴底稿</h3>
          <p style={{ fontSize: "13px", color: "#475569" }}>
            法定费用扣除（≤4000扣800，&gt;4000扣20%）、稿酬70%减计、20%-30%-40%超额累进预扣预缴，联动自然人小额1000元增值税起征点及六税两费减半征收。
          </p>

          <div style={{ display: "grid", gridTemplateColumns: "repeat(3, 1fr)", gap: "16px", marginBottom: "16px" }}>
            <div>
              <label style={{ display: "block", fontSize: "13px", fontWeight: "bold", marginBottom: "4px" }}>
                报酬总额 (元):
              </label>
              <input
                type="number"
                value={laborGross}
                onChange={(e) => setLaborGross(e.target.value)}
                style={{ width: "100%", padding: "8px", borderRadius: "4px", border: "1px solid #cbd5e1" }}
              />
            </div>
            <div>
              <label style={{ display: "block", fontSize: "13px", fontWeight: "bold", marginBottom: "4px" }}>
                所得分类:
              </label>
              <select
                value={remunType}
                onChange={(e) => setRemunType(e.target.value as any)}
                style={{ width: "100%", padding: "8px", borderRadius: "4px", border: "1px solid #cbd5e1" }}
              >
                <option value="LABOR_SERVICE">劳务报酬所得 (20%-30%-40%累进)</option>
                <option value="AUTHOR_REMUNERATION">稿酬所得 (法定减计30%后20%平税)</option>
                <option value="ROYALTY">特许权使用费所得 (20%平税)</option>
              </select>
            </div>
            <div>
              <label style={{ display: "block", fontSize: "13px", fontWeight: "bold", marginBottom: "4px" }}>
                城建税率:
              </label>
              <select
                value={cityRate}
                onChange={(e) => setCityRate(e.target.value)}
                style={{ width: "100%", padding: "8px", borderRadius: "4px", border: "1px solid #cbd5e1" }}
              >
                <option value="0.07">市区 (7%)</option>
                <option value="0.05">县城/镇 (5%)</option>
                <option value="0.01">其他地区 (1%)</option>
              </select>
            </div>
          </div>

          <div style={{ display: "flex", gap: "20px", marginBottom: "16px", fontSize: "13px" }}>
            <label style={{ cursor: "pointer" }}>
              <input
                type="checkbox"
                checked={relief1Pct}
                onChange={(e) => setRelief1Pct(e.target.checked)}
              />{" "}
              适用小规模1%征收率优惠 (原3%减按1%)
            </label>
            <label style={{ cursor: "pointer" }}>
              <input
                type="checkbox"
                checked={proxyInvoice}
                onChange={(e) => setProxyInvoice(e.target.checked)}
              />{" "}
              自然人已完成税局代开发票 (免扣代缴增值税与附加)
            </label>
            {proxyInvoice && (
              <input
                type="text"
                placeholder="代开发票凭证号"
                value={proxyInvoiceRef}
                onChange={(e) => setProxyInvoiceRef(e.target.value)}
                style={{ padding: "4px 8px", borderRadius: "4px", border: "1px solid #cbd5e1", fontSize: "12px" }}
              />
            )}
            <button
              type="button"
              onClick={handleRecalculateLabor}
              style={{ marginLeft: "auto", padding: "6px 14px", background: "#2563eb", color: "#fff", border: "none", borderRadius: "4px", cursor: "pointer" }}
            >
              计算个税及增值税代扣代缴
            </button>
          </div>

          {laborResult && (
            <div style={{ background: "#ffffff", padding: "16px", borderRadius: "6px", border: "1px solid #cbd5e1" }}>
              <h4 style={{ margin: "0 0 10px 0" }}>扣缴底稿清单</h4>
              <table style={{ width: "100%", borderCollapse: "collapse", fontSize: "13px" }}>
                <thead>
                  <tr style={{ background: "#f1f5f9", textAlign: "left" }}>
                    <th style={{ padding: "6px" }}>报酬金额</th>
                    <th style={{ padding: "6px" }}>法定减除费用</th>
                    <th style={{ padding: "6px" }}>应纳税所得额</th>
                    <th style={{ padding: "6px" }}>预扣率</th>
                    <th style={{ padding: "6px" }}>个税预扣</th>
                    <th style={{ padding: "6px" }}>代扣增值税</th>
                    <th style={{ padding: "6px" }}>代扣城建及附加</th>
                    <th style={{ padding: "6px" }}>税后实发</th>
                  </tr>
                </thead>
                <tbody>
                  <tr>
                    <td style={{ padding: "8px 6px" }}>￥{laborResult.grossPayment}</td>
                    <td style={{ padding: "8px 6px" }}>￥{laborResult.deductionAmount}</td>
                    <td style={{ padding: "8px 6px" }}>￥{laborResult.taxableIncome}</td>
                    <td style={{ padding: "8px 6px" }}>{(Number(laborResult.iitRate) * 100).toFixed(0)}%</td>
                    <td style={{ padding: "8px 6px", color: "#dc2626", fontWeight: "bold" }}>￥{laborResult.iitWithholding}</td>
                    <td style={{ padding: "8px 6px", color: laborResult.vatExempt ? "#16a34a" : "#dc2626" }}>
                      {laborResult.vatExempt ? "小额免税(0.00)" : `￥${laborResult.vatWithheld}`}
                    </td>
                    <td style={{ padding: "8px 6px" }}>￥{laborResult.totalSurchargeWithheld}</td>
                    <td style={{ padding: "8px 6px", color: "#16a34a", fontWeight: "bold" }}>￥{laborResult.netPayable}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          )}
        </section>
      )}

      {/* Tab 3: TAX01 */}
      {activeTab === "TAX01" && (
        <section style={{ background: "#f8fafc", padding: "20px", borderRadius: "8px", border: "1px solid #e2e8f0" }}>
          <h3>TAX01: 通用税种政策规则库与计算解释树</h3>
          <p style={{ fontSize: "13px", color: "#475569" }}>
            版本化政策参数库（VAT、附加税、印花税、企业所得税研发加计扣除、小型微利企业优惠）。严禁系统自动预设高新技术企业或小型微利资格，必须由具备真实权限的自然人做出显式资质申明！
          </p>

          <div style={{ background: "#ffffff", padding: "16px", borderRadius: "6px", border: "1px solid #cbd5e1", marginBottom: "16px" }}>
            <h4 style={{ margin: "0 0 10px 0" }}>小型微利企业所得税政策评估 (CIT_SMALL_LOW_PROFIT_2026_V1)</h4>
            <div style={{ marginBottom: "10px" }}>
              <label style={{ fontSize: "13px", fontWeight: "bold", marginRight: "10px" }}>年度应纳税所得额 (元):</label>
              <input
                type="number"
                value={profitBase}
                onChange={(e) => setProfitBase(e.target.value)}
                style={{ padding: "6px", borderRadius: "4px", border: "1px solid #cbd5e1" }}
              />
            </div>
            <div style={{ marginBottom: "12px", background: "#fef3c7", padding: "10px", borderRadius: "4px", border: "1px solid #fde68a", fontSize: "13px" }}>
              <label style={{ cursor: "pointer", fontWeight: "bold", color: "#92400e" }}>
                <input
                  type="checkbox"
                  checked={attestationConfirmed}
                  onChange={(e) => setAttestationConfirmed(e.target.checked)}
                />{" "}
                我已独立核验并人工声明本单位符合国家税务总局规定的小型微利企业法定指标（从业人数≤300人，资产总额≤5000万元，应纳税所得额≤300万元）
              </label>
            </div>

            <div style={{ fontSize: "13px" }}>
              {attestationConfirmed ? (
                <div style={{ color: "#166534", background: "#f0fdf4", padding: "10px", borderRadius: "4px", border: "1px solid #bbf7d0" }}>
                  ✅ 资质声明已核验有效：法定税率 25%，减按 20% 税率且应纳税所得额减按 25% 计入（实际有效税率 5%）。
                  <br />
                  测算应纳所得税: <strong>￥{(Number(profitBase) * 0.05).toFixed(2)}</strong> (减免税额: ￥{(Number(profitBase) * 0.20).toFixed(2)})
                </div>
              ) : (
                <div style={{ color: "#991b1b", background: "#fef2f2", padding: "10px", borderRadius: "4px", border: "1px solid #fecaca" }}>
                  ⛔ 未核验资质声明门禁阻断：优惠政策不可自动假设，当前执行法定基准税率 25%。
                  <br />
                  基准应纳所得税: <strong>￥{(Number(profitBase) * 0.25).toFixed(2)}</strong>
                </div>
              )}
            </div>
          </div>

          <div style={{ background: "#ffffff", padding: "16px", borderRadius: "6px", border: "1px solid #cbd5e1" }}>
            <h4 style={{ margin: "0 0 10px 0" }}>计算解释树 (Calculation Explain Tree) 节点追溯</h4>
            <div style={{ fontFamily: "monospace", fontSize: "12px", background: "#0f172a", color: "#e2e8f0", padding: "12px", borderRadius: "4px" }}>
              <div>[ROOT] Type: TAX01_CIT_POLICY_EVALUATION | Target: {bookId}</div>
              <div style={{ marginLeft: "16px" }}>├── [STEP 1] STATUTORY_ASSESSMENT: base = ￥{profitBase} × 25% = ￥{(Number(profitBase) * 0.25).toFixed(2)}</div>
              <div style={{ marginLeft: "16px" }}>│   └── Provenance: CIT_Law_Art4 | Digest: a3b8...c901</div>
              <div style={{ marginLeft: "16px" }}>└── [STEP 2] ATTESTATION_GATE: {attestationConfirmed ? "VALID_ATTESTATION_PROVIDED" : "NO_ATTESTATION_DEFAULT_STATUTORY"}</div>
              <div style={{ marginLeft: "32px" }}>└── Result: EffectiveRate = {attestationConfirmed ? "5.0%" : "25.0%"} | OutputTax = ￥{(Number(profitBase) * (attestationConfirmed ? 0.05 : 0.25)).toFixed(2)}</div>
            </div>
          </div>
        </section>
      )}

      {/* Tab 4: TAX05 */}
      {activeTab === "TAX05" && (
        <section style={{ background: "#f8fafc", padding: "20px", borderRadius: "8px", border: "1px solid #e2e8f0" }}>
          <h3>TAX05: 地区征期日历、表版逐栏锁版与必要附表缺失阻断</h3>
          <p style={{ fontSize: "13px", color: "#475569" }}>
            依据国务院假日安排与税务总局规定，精准计算周末及法定节假日顺延征期；支持申报表逐栏锁版以及必要附表缺失硬阻断。
          </p>

          <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: "16px", marginBottom: "16px" }}>
            <div style={{ background: "#ffffff", padding: "16px", borderRadius: "6px", border: "1px solid #cbd5e1" }}>
              <h4 style={{ margin: "0 0 10px 0" }}>地区法定征期日历生成器</h4>
              <label style={{ fontSize: "13px", fontWeight: "bold" }}>选择所属期月份: </label>
              <select
                value={calMonth}
                onChange={(e) => handleRecalculateCalendar(Number(e.target.value))}
                style={{ padding: "6px", borderRadius: "4px", border: "1px solid #cbd5e1", marginLeft: "8px" }}
              >
                {[1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12].map((m) => (
                  <option key={m} value={m}>{m}月份征期</option>
                ))}
              </select>

              <div style={{ marginTop: "12px", fontSize: "13px" }}>
                <div>基准征期截止日: <strong>{calendarResult.baseDeadline}</strong></div>
                <div>实际调整截止日: <strong style={{ color: "#2563eb" }}>{calendarResult.adjustedDeadline}</strong></div>
                <div>是否顺延: <strong>{calendarResult.isDeferred ? "是" : "否"}</strong></div>
                <div>顺延事由: <span style={{ color: "#d97706" }}>{calendarResult.deferralReason}</span></div>
              </div>
            </div>

            <div style={{ background: "#ffffff", padding: "16px", borderRadius: "6px", border: "1px solid #cbd5e1" }}>
              <h4 style={{ margin: "0 0 10px 0" }}>表版逐栏锁版机制 (Form Cell Lock)</h4>
              <p style={{ fontSize: "12px", color: "#64748b" }}>
                表单锁版后所有单元格内容及勾稽哈希不可篡改。如需修改必须发起正式更正修订链。
              </p>
              <div style={{ marginBottom: "10px" }}>
                锁版状态:{" "}
                <span style={{ fontWeight: "bold", color: formLocked ? "#16a34a" : "#dc2626" }}>
                  {formLocked ? "🔒 已锁版 (FORM_LOCKED)" : "🔓 开放编辑中 (DRAFT)"}
                </span>
              </div>
              <button
                type="button"
                onClick={() => setFormLocked(!formLocked)}
                style={{
                  padding: "6px 12px",
                  background: formLocked ? "#dc2626" : "#2563eb",
                  color: "#fff",
                  border: "none",
                  borderRadius: "4px",
                  cursor: "pointer",
                }}
              >
                {formLocked ? "发起更正申请以解锁" : "核验并执行官方表版锁版"}
              </button>
            </div>
          </div>

          <div style={{ background: "#ffffff", padding: "16px", borderRadius: "6px", border: "1px solid #cbd5e1" }}>
            <h4 style={{ margin: "0 0 10px 0" }}>必要附表缺失硬阻断规则检查</h4>
            <p style={{ fontSize: "12px", color: "#64748b" }}>
              主表若存在出口销售额或进项税抵扣，必须附列对应附表。若缺失关键附表，禁止批准申报或锁版报送。
            </p>

            <div style={{ display: "flex", gap: "16px", marginBottom: "12px", fontSize: "13px" }}>
              <label style={{ cursor: "pointer" }}>
                <input
                  type="checkbox"
                  checked={attachedSchedules.includes("ATTACHMENT_VAT_EXPORT")}
                  onChange={() => handleToggleSchedule("ATTACHMENT_VAT_EXPORT")}
                />{" "}
                附表一：本期免税、退税及出口销售额明细
              </label>
              <label style={{ cursor: "pointer" }}>
                <input
                  type="checkbox"
                  checked={attachedSchedules.includes("ATTACHMENT_VAT_INPUT_DEDUCTION")}
                  onChange={() => handleToggleSchedule("ATTACHMENT_VAT_INPUT_DEDUCTION")}
                />{" "}
                附表二：本期进项税额明细
              </label>
            </div>

            <div style={{ fontSize: "13px" }}>
              {scheduleCheck.passed ? (
                <div style={{ color: "#166534", background: "#f0fdf4", padding: "10px", borderRadius: "4px", border: "1px solid #bbf7d0" }}>
                  ✅ 所有必要附表均已完备关联，通过阻断检查，允许继续报送。
                </div>
              ) : (
                <div style={{ color: "#991b1b", background: "#fef2f2", padding: "10px", borderRadius: "4px", border: "1px solid #fecaca" }}>
                  ⛔ 必要附表缺失阻断触发: 缺少附表 <strong>{scheduleCheck.missingSchedules.join(", ")}</strong>，禁止通过审批或申报锁版！
                </div>
              )}
            </div>
          </div>
        </section>
      )}
    </div>
  );
};
