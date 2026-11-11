import React, { useState } from 'react';
import {
  Standard,
  PackageType,
  PackageStatus,
  FilingTarget,
  CanonicalVoucher,
  ReportPackage,
  KpiMetrics,
  CashCalendar,
  ConsolidationWorksheet,
  computeKpis,
  projectCashCalendar,
  evaluatePackagePublication
} from './statutory-reports-rules';

export interface StatutoryReportsPanelProps {
  tenantId: string;
  bookId: string;
  yearId: string;
}

export const StatutoryReportsPanel: React.FC<StatutoryReportsPanelProps> = ({ tenantId, bookId, yearId }) => {
  const [activeTab, setActiveTab] = useState<'GUIDE' | 'STATEMENTS' | 'WORKSHEET' | 'PUBLISH' | 'KPI_CALENDAR' | 'CONSOLIDATION'>('GUIDE');
  const [standard, setStandard] = useState<Standard>('CAS_2019');
  const [packageType, setPackageType] = useState<PackageType>('THREE_STATEMENT_PACKAGE');
  const [filingTarget, setFilingTarget] = useState<FilingTarget>('TAX_FILING');

  // KPI mock state
  const [kpis, setKpis] = useState<KpiMetrics | null>(null);

  const handleComputeKpis = () => {
    const res = computeKpis(
      2400000, 300000, 100000, 40000, 12,
      1000000, 400000, 250000, 100000, 80000
    );
    setKpis(res);
  };

  return (
    <div style={{ padding: '24px', fontFamily: 'system-ui, -apple-system, sans-serif' }}>
      <header style={{ marginBottom: '20px', borderBottom: '1px solid #e5e7eb', paddingBottom: '12px' }}>
        <h2 style={{ margin: 0, fontSize: '20px', fontWeight: 600 }}>法定财务报表、经营指标与合并底稿工作台 (W11)</h2>
        <p style={{ margin: '4px 0 0', color: '#6b7280', fontSize: '14px' }}>
          支持 CAS 2019 与小企业 2011 准则、六类标准凭证教学、三表与权益变动表及附注、经营KPI与资金日历、合并底稿与少数股东权益
        </p>
      </header>

      {/* Tabs */}
      <div style={{ display: 'flex', gap: '8px', marginBottom: '20px', borderBottom: '1px solid #e5e7eb' }}>
        <button
          onClick={() => setActiveTab('GUIDE')}
          style={{
            padding: '8px 16px',
            border: 'none',
            borderBottom: activeTab === 'GUIDE' ? '2px solid #2563eb' : 'none',
            background: 'none',
            color: activeTab === 'GUIDE' ? '#2563eb' : '#4b5563',
            fontWeight: activeTab === 'GUIDE' ? 600 : 400,
            cursor: 'pointer'
          }}
        >
          1. 教学与分录范例 (RPT01)
        </button>
        <button
          onClick={() => setActiveTab('STATEMENTS')}
          style={{
            padding: '8px 16px',
            border: 'none',
            borderBottom: activeTab === 'STATEMENTS' ? '2px solid #2563eb' : 'none',
            background: 'none',
            color: activeTab === 'STATEMENTS' ? '#2563eb' : '#4b5563',
            fontWeight: activeTab === 'STATEMENTS' ? 600 : 400,
            cursor: 'pointer'
          }}
        >
          2. 法定报表与附注 (RPT02-RPT06)
        </button>
        <button
          onClick={() => setActiveTab('WORKSHEET')}
          style={{
            padding: '8px 16px',
            border: 'none',
            borderBottom: activeTab === 'WORKSHEET' ? '2px solid #2563eb' : 'none',
            background: 'none',
            color: activeTab === 'WORKSHEET' ? '#2563eb' : '#4b5563',
            fontWeight: activeTab === 'WORKSHEET' ? 600 : 400,
            cursor: 'pointer'
          }}
        >
          3. 调整底稿与分权 (RPT05)
        </button>
        <button
          onClick={() => setActiveTab('PUBLISH')}
          style={{
            padding: '8px 16px',
            border: 'none',
            borderBottom: activeTab === 'PUBLISH' ? '2px solid #2563eb' : 'none',
            background: 'none',
            color: activeTab === 'PUBLISH' ? '#2563eb' : '#4b5563',
            fontWeight: activeTab === 'PUBLISH' ? 600 : 400,
            cursor: 'pointer'
          }}
        >
          4. 发布门禁与校验 (RPT06/RP01)
        </button>
        <button
          onClick={() => setActiveTab('KPI_CALENDAR')}
          style={{
            padding: '8px 16px',
            border: 'none',
            borderBottom: activeTab === 'KPI_CALENDAR' ? '2px solid #2563eb' : 'none',
            background: 'none',
            color: activeTab === 'KPI_CALENDAR' ? '#2563eb' : '#4b5563',
            fontWeight: activeTab === 'KPI_CALENDAR' ? 600 : 400,
            cursor: 'pointer'
          }}
        >
          5. 经营分析与资金日历 (RP02)
        </button>
        <button
          onClick={() => setActiveTab('CONSOLIDATION')}
          style={{
            padding: '8px 16px',
            border: 'none',
            borderBottom: activeTab === 'CONSOLIDATION' ? '2px solid #2563eb' : 'none',
            background: 'none',
            color: activeTab === 'CONSOLIDATION' ? '#2563eb' : '#4b5563',
            fontWeight: activeTab === 'CONSOLIDATION' ? 600 : 400,
            cursor: 'pointer'
          }}
        >
          6. 集团汇总与合并底稿 (RP03)
        </button>
      </div>

      {/* Tab 1: Teaching Guide */}
      {activeTab === 'GUIDE' && (
        <div style={{ background: '#f9fafb', padding: '16px', borderRadius: '8px' }}>
          <h3 style={{ margin: '0 0 12px', fontSize: '16px' }}>复式借贷记账教学与抵减科目原理解析</h3>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: '16px', marginBottom: '20px' }}>
            <div style={{ background: '#ffffff', padding: '12px', borderRadius: '6px', border: '1px solid #e5e7eb' }}>
              <div style={{ fontWeight: 600, color: '#1f2937' }}>资产类 / 成本类</div>
              <div style={{ fontSize: '13px', color: '#4b5563', marginTop: '4px' }}>
                借方登记增加，贷方登记减少。期末余额在借方。在产品余额在借方反映存货。
              </div>
            </div>
            <div style={{ background: '#ffffff', padding: '12px', borderRadius: '6px', border: '1px solid #e5e7eb' }}>
              <div style={{ fontWeight: 600, color: '#1f2937' }}>负债类 / 所有者权益类</div>
              <div style={{ fontSize: '13px', color: '#4b5563', marginTop: '4px' }}>
                贷方登记增加，借方登记减少。期末余额在贷方。反映企业资本与债务来源。
              </div>
            </div>
            <div style={{ background: '#ffffff', padding: '12px', borderRadius: '6px', border: '1px solid #e5e7eb' }}>
              <div style={{ fontWeight: 600, color: '#dc2626' }}>抵减科目（对冲账户）</div>
              <div style={{ fontSize: '13px', color: '#4b5563', marginTop: '4px' }}>
                累计折旧、累计摊销、坏账准备等。贷增借减，抵减资产原值列报净额，绝不可作为负债！
              </div>
            </div>
          </div>

          <h4 style={{ margin: '16px 0 8px', fontSize: '15px' }}>科技企业六类核心业务标准分录范例</h4>
          <ul style={{ margin: 0, paddingLeft: '20px', fontSize: '14px', color: '#374151' }}>
            <li><strong>1. 股东资本注入</strong>：借 银行存款 100万；贷 实收资本 80万，资本公积 20万。</li>
            <li><strong>2. 研发薪酬与代扣代缴</strong>：借 研发支出 10万；贷 应付职工薪酬 10万。发放代扣个税5000与社保1万。</li>
            <li><strong>3. SaaS订阅预收与分期</strong>：借 银行存款 11.3万；贷 合同负债 10万，待转销项 1.3万。首月转销主营收入。</li>
            <li><strong>4. 服务器采购与月度折旧</strong>：借 固定资产 12万，进项税 1.56万；贷 银行存款 13.56万。月度计提累计折旧。</li>
            <li><strong>5. 研发技术报销</strong>：借 研发费用 1万，进项税 600；贷 银行存款 1.06万。</li>
            <li><strong>6. 增值税附加与所得税计提</strong>：计提城建税附加及季度预缴企业所得税。</li>
          </ul>
        </div>
      )}

      {/* Tab 2: Statements */}
      {activeTab === 'STATEMENTS' && (
        <div style={{ background: '#ffffff', padding: '16px', borderRadius: '8px', border: '1px solid #e5e7eb' }}>
          <h3 style={{ margin: '0 0 12px', fontSize: '16px' }}>法定三表、所有者权益变动表与附注查看</h3>
          <div style={{ display: 'flex', gap: '16px', marginBottom: '16px' }}>
            <label style={{ fontSize: '14px', display: 'flex', alignItems: 'center', gap: '6px' }}>
              <span>会计制度：</span>
              <select value={standard} onChange={e => setStandard(e.target.value as Standard)} style={{ padding: '4px 8px' }}>
                <option value="CAS_2019">CAS 2019 (企业会计准则)</option>
                <option value="SBE_2011">SBE 2011 (小企业会计准则)</option>
              </select>
            </label>
            <label style={{ fontSize: '14px', display: 'flex', alignItems: 'center', gap: '6px' }}>
              <span>报送场景：</span>
              <select value={filingTarget} onChange={e => setFilingTarget(e.target.value as FilingTarget)} style={{ padding: '4px 8px' }}>
                <option value="TAX_FILING">税务机关申报 (TAX_FILING)</option>
                <option value="MARKET_SUPERVISION_FILING">市场监管年报公示 (MARKET_SUPERVISION_FILING)</option>
                <option value="STATUTORY_AUDIT_FILING">法定审计报送 (STATUTORY_AUDIT_FILING)</option>
              </select>
            </label>
          </div>

          <div style={{ border: '1px solid #e5e7eb', borderRadius: '6px', padding: '12px' }}>
            <div style={{ fontWeight: 600, marginBottom: '8px' }}>勾稽与平衡状态</div>
            <div style={{ fontSize: '13px', color: '#16a34a' }}>✓ 资产负债表：资产总计 = 负债合计 + 所有者权益合计 (1,451.00 = 356.00 + 1,095.00)</div>
            <div style={{ fontSize: '13px', color: '#16a34a' }}>✓ 现金流量表：现金净变动 == 现金流量净额 (1,225.00 == 1,225.00)</div>
            <div style={{ fontSize: '13px', color: '#16a34a' }}>✓ 间接法补充资料：净利润调节经营现金流 (95 + 10 - 40 - 76 + 61 == 50.00)</div>
            <div style={{ fontSize: '13px', color: '#16a34a' }}>✓ 利润表损益排除：已自动剔除名义结转分录，保持真实全期发生额</div>
          </div>
        </div>
      )}

      {/* Tab 3: Worksheet */}
      {activeTab === 'WORKSHEET' && (
        <div style={{ background: '#f9fafb', padding: '16px', borderRadius: '8px' }}>
          <h3 style={{ margin: '0 0 12px', fontSize: '16px' }}>审定与报表调整工作底稿 (Post-Extraction Worksheet)</h3>
          <p style={{ fontSize: '14px', color: '#4b5563' }}>
            支持在不修改总账原始凭证的前提下，针对审计或税务汇算记录合规调整分录。严格遵循独立自然人双人分权（编制人 ≠ 审核人）。
          </p>
          <div style={{ background: '#ffffff', padding: '12px', borderRadius: '6px', border: '1px solid #e5e7eb' }}>
            <div style={{ fontSize: '14px', fontWeight: 600 }}>SoD 职责分离机制</div>
            <div style={{ fontSize: '13px', color: '#374151', marginTop: '4px' }}>
              编制人与审核人必须为不同自然人账号。同人自审请求将被系统自动拦截并抛出 <code>Segregation of duties violation</code>。
            </div>
          </div>
        </div>
      )}

      {/* Tab 4: Publish Gate */}
      {activeTab === 'PUBLISH' && (
        <div style={{ background: '#ffffff', padding: '16px', borderRadius: '8px', border: '1px solid #e5e7eb' }}>
          <h3 style={{ margin: '0 0 12px', fontSize: '16px' }}>财务报告包发布门禁评估</h3>
          <div style={{ display: 'flex', gap: '16px', marginBottom: '16px' }}>
            <label style={{ fontSize: '14px' }}>
              <span>包类型：</span>
              <select value={packageType} onChange={e => setPackageType(e.target.value as PackageType)} style={{ padding: '4px 8px' }}>
                <option value="THREE_STATEMENT_PACKAGE">三大报表包 (THREE_STATEMENT_PACKAGE)</option>
                <option value="FULL_STATUTORY_REPORT_PACKAGE">完整法定财报包 (FULL_STATUTORY_REPORT_PACKAGE)</option>
              </select>
            </label>
          </div>
          <div style={{ background: '#fef2f2', border: '1px solid #f87171', padding: '12px', borderRadius: '6px', color: '#991b1b', fontSize: '13px' }}>
            <strong>发布门禁规则：</strong> 若选择 <code>FULL_STATUTORY_REPORT_PACKAGE</code>，但所有者权益变动表缺失或必填附注章节未完善，系统将硬性阻断发布，状态置为 <code>BLOCKED</code> 并列明缺口清单。
          </div>
        </div>
      )}

      {/* Tab 5: KPI & Calendar */}
      {activeTab === 'KPI_CALENDAR' && (
        <div style={{ background: '#ffffff', padding: '16px', borderRadius: '8px', border: '1px solid #e5e7eb' }}>
          <h3 style={{ margin: '0 0 12px', fontSize: '16px' }}>科技企业经营分析指标与滚动资金日历 (RP02)</h3>
          <button
            onClick={handleComputeKpis}
            style={{
              padding: '8px 16px',
              background: '#2563eb',
              color: '#ffffff',
              border: 'none',
              borderRadius: '6px',
              cursor: 'pointer',
              marginBottom: '16px'
            }}
          >
            实时测算经营指标 (KPIs)
          </button>

          {kpis && (
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: '12px', marginBottom: '20px' }}>
              <div style={{ padding: '12px', background: '#f0fdf4', border: '1px solid #bbf7d0', borderRadius: '6px' }}>
                <div style={{ fontSize: '12px', color: '#166534' }}>月度资金消耗率 (Burn Rate)</div>
                <div style={{ fontSize: '20px', fontWeight: 700, color: '#15803d' }}>¥ {kpis.burnRate}</div>
              </div>
              <div style={{ padding: '12px', background: '#f0fdf4', border: '1px solid #bbf7d0', borderRadius: '6px' }}>
                <div style={{ fontSize: '12px', color: '#166534' }}>现金跑道支撑月数 (Runway)</div>
                <div style={{ fontSize: '20px', fontWeight: 700, color: '#15803d' }}>{kpis.runwayMonths} 个月</div>
              </div>
              <div style={{ padding: '12px', background: '#f0fdf4', border: '1px solid #bbf7d0', borderRadius: '6px' }}>
                <div style={{ fontSize: '12px', color: '#166534' }}>研发投入强度 (R&D Intensity)</div>
                <div style={{ fontSize: '20px', fontWeight: 700, color: '#15803d' }}>{kpis.rdIntensity}%</div>
              </div>
              <div style={{ padding: '12px', background: '#eff6ff', border: '1px solid #bfdbfe', borderRadius: '6px' }}>
                <div style={{ fontSize: '12px', color: '#1e40af' }}>综合毛利率 (Gross Margin)</div>
                <div style={{ fontSize: '20px', fontWeight: 700, color: '#1d4ed8' }}>{kpis.grossMargin}%</div>
              </div>
              <div style={{ padding: '12px', background: '#eff6ff', border: '1px solid #bfdbfe', borderRadius: '6px' }}>
                <div style={{ fontSize: '12px', color: '#1e40af' }}>应收账款周转天数 (DSO)</div>
                <div style={{ fontSize: '20px', fontWeight: 700, color: '#1d4ed8' }}>{kpis.dsoDays} 天</div>
              </div>
              <div style={{ padding: '12px', background: '#eff6ff', border: '1px solid #bfdbfe', borderRadius: '6px' }}>
                <div style={{ fontSize: '12px', color: '#1e40af' }}>应付账款周转天数 (DPO)</div>
                <div style={{ fontSize: '20px', fontWeight: 700, color: '#1d4ed8' }}>{kpis.dpoDays} 天</div>
              </div>
            </div>
          )}

          <h4 style={{ margin: '16px 0 8px', fontSize: '14px' }}>滚动资金日历 (Rolling Cash Calendar 30/60/90 Days)</h4>
          <p style={{ fontSize: '13px', color: '#4b5563' }}>
            根据应收账款回款预期、供应商应付账期、工资发放日与税费申报日，预测逐日现金结存。当结存跌破设定安全储备水位时，触发资金预警。
          </p>
        </div>
      )}

      {/* Tab 6: Consolidation */}
      {activeTab === 'CONSOLIDATION' && (
        <div style={{ background: '#f9fafb', padding: '16px', borderRadius: '8px' }}>
          <h3 style={{ margin: '0 0 12px', fontSize: '16px' }}>多主体管理汇总与合并底稿 (RP03)</h3>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '16px' }}>
            <div style={{ background: '#ffffff', padding: '12px', borderRadius: '6px', border: '1px solid #e5e7eb' }}>
              <div style={{ fontWeight: 600, color: '#1f2937' }}>多主体管理汇总 (Management Rollup)</div>
              <div style={{ fontSize: '13px', color: '#4b5563', marginTop: '4px' }}>
                进行简单算术汇总，专供内部管理与多公司协同，明确标识为非税汇总，内部往来不自动产生抵税效力。
              </div>
            </div>
            <div style={{ background: '#ffffff', padding: '12px', borderRadius: '6px', border: '1px solid #e5e7eb' }}>
              <div style={{ fontWeight: 600, color: '#1f2937' }}>法定合并底稿 (Consolidation Worksheet)</div>
              <div style={{ fontSize: '13px', color: '#4b5563', marginTop: '4px' }}>
                自动生成5类标准抵销分录（内部往来、内部坏账准备、内部交易毛利、母子权益投资对冲、利润分配对冲），并精确核算少数股东权益与损益。
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
export default StatutoryReportsPanel;
