import React, { useState } from 'react';
import {
  validateCnyAmount,
  formatCny,
  canActivateAccount,
  canFreezeAccount,
  canCloseAccount,
  validateTransferPairing,
  canApproveInstruction,
  canSettleInstruction,
  canFailInstruction,
  calculateBankReconciliation,
  calculateAgingDays,
  isOverdueWarning,
  calculateCashForecast
} from './treasury-rules';

export interface TreasuryPanelProps {
  tenantId: string;
  bookId: string;
  currentUserId: string;
}

export const TreasuryPanel: React.FC<TreasuryPanelProps> = ({
  tenantId,
  bookId,
  currentUserId
}) => {
  const [activeTab, setActiveTab] = useState<'accounts' | 'orders' | 'instructions' | 'statements' | 'reconciliation' | 'forecast'>('accounts');

  // Accounts state
  const [accounts, setAccounts] = useState<any[]>([
    {
      id: 'acct-1',
      accountNo: '62220210001',
      accountName: '基本存款户',
      bankName: '中国工商银行',
      branchName: '深圳高新支行',
      accountType: 'BASIC',
      currency: 'CNY',
      status: 'ACTIVE',
      balance: '1500000.00',
      makerPersonId: 'user-maker',
      reviewerPersonId: 'user-reviewer'
    },
    {
      id: 'acct-2',
      accountNo: '62220210002',
      accountName: '日常收支一般户',
      bankName: '招商银行',
      branchName: '科苑支行',
      accountType: 'GENERAL',
      currency: 'CNY',
      status: 'ACTIVE',
      balance: '350000.00',
      makerPersonId: 'user-maker',
      reviewerPersonId: 'user-reviewer'
    }
  ]);

  // Payment Instructions state
  const [instructions, setInstructions] = useState<any[]>([
    {
      id: 'pi-1',
      instructionNo: 'PI-202611-0001',
      payerAccountId: 'acct-1',
      payeeAccountName: '云服务提供商',
      amount: '50000.00',
      status: 'DISPATCHED_UNKNOWN',
      bankDirectChannel: 'NOT_VERIFIED',
      makerPersonId: 'user-maker',
      receiptOriginalSha256: null
    }
  ]);

  // Reconciliation state
  const [bankStmtBal, setBankStmtBal] = useState('100000.00');
  const [ledgerBal, setLedgerBal] = useState('95000.00');
  const [unreachedItems, setUnreachedItems] = useState<any[]>([
    { id: '1', itemType: 'ENTERPRISE_RECORDED_BANK_UNRECORDED', amount: 20000, recordedDate: '2026-11-28', summary: '在途客户汇款' },
    { id: '2', itemType: 'ENTERPRISE_PAID_BANK_UNPAID', amount: 10000, recordedDate: '2026-11-29', summary: '已开出未兑现支票' },
    { id: '3', itemType: 'BANK_CREDITED_ENTERPRISE_UNRECORDED', amount: 18000, recordedDate: '2026-11-15', summary: '银行代收租金', supplementaryEvidenceId: 'ev-101' },
    { id: '4', itemType: 'BANK_DEBITED_ENTERPRISE_UNRECORDED', amount: 3000, recordedDate: '2026-10-10', summary: '银行年费手续费' }
  ]);

  const reconResult = calculateBankReconciliation(
    parseFloat(bankStmtBal) || 0,
    parseFloat(ledgerBal) || 0,
    unreachedItems
  );

  return (
    <div style={{ padding: 24, fontFamily: 'sans-serif', maxWidth: 1200, margin: '0 auto' }}>
      <header style={{ marginBottom: 20, borderBottom: '1px solid #e2e8f0', paddingBottom: 16 }}>
        <h1 style={{ fontSize: 24, fontWeight: 700, margin: 0, color: '#1e293b' }}>
          资金与银行管理工作台 (W07 Treasury)
        </h1>
        <div style={{ display: 'flex', gap: 12, marginTop: 8, fontSize: 13, color: '#64748b' }}>
          <span>租户: {tenantId}</span>
          <span>账套: {bookId}</span>
          <span>当前人员: {currentUserId}</span>
          <span style={{ color: '#b91c1c', fontWeight: 600 }}>银企直联渠道: NOT_VERIFIED (严格阻断外部直付)</span>
        </div>
      </header>

      {/* Tabs */}
      <nav style={{ display: 'flex', gap: 8, marginBottom: 20, borderBottom: '2px solid #f1f5f9' }}>
        {[
          { key: 'accounts', label: '资金账户 (MD04)' },
          { key: 'orders', label: '收付款与内部划转 (TR01)' },
          { key: 'instructions', label: '支付指令与结果核验 (TR02)' },
          { key: 'statements', label: '流水导入与匹配 (TR03)' },
          { key: 'reconciliation', label: '银行余额调节表 (TR04)' },
          { key: 'forecast', label: '分层资金预测 (TR04)' }
        ].map(tab => (
          <button
            key={tab.key}
            onClick={() => setActiveTab(tab.key as any)}
            style={{
              padding: '8px 16px',
              border: 'none',
              background: activeTab === tab.key ? '#2563eb' : '#f8fafc',
              color: activeTab === tab.key ? '#fff' : '#475569',
              borderRadius: '6px 6px 0 0',
              fontWeight: 600,
              cursor: 'pointer'
            }}
          >
            {tab.label}
          </button>
        ))}
      </nav>

      {/* Tab 1: Bank Accounts (MD04) */}
      {activeTab === 'accounts' && (
        <section>
          <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
            <h2 style={{ fontSize: 18, fontWeight: 600 }}>资金账户目录 (MD04)</h2>
            <button
              style={{ background: '#10b981', color: '#fff', border: 'none', padding: '6px 12px', borderRadius: 4, cursor: 'pointer' }}
              onClick={() => alert('打开开户申请弹窗')}
            >
              + 新建资金账户
            </button>
          </div>
          <table style={{ width: '100%', borderCollapse: 'collapse', border: '1px solid #e2e8f0' }}>
            <thead>
              <tr style={{ background: '#f8fafc', textAlign: 'left', borderBottom: '1px solid #cbd5e1' }}>
                <th style={{ padding: 10 }}>账号</th>
                <th style={{ padding: 10 }}>账户名称</th>
                <th style={{ padding: 10 }}>开户银行及支行</th>
                <th style={{ padding: 10 }}>类型</th>
                <th style={{ padding: 10 }}>状态</th>
                <th style={{ padding: 10, textAlign: 'right' }}>余额 (CNY)</th>
                <th style={{ padding: 10 }}>操作 (需独立自然人复核)</th>
              </tr>
            </thead>
            <tbody>
              {accounts.map(a => (
                <tr key={a.id} style={{ borderBottom: '1px solid #f1f5f9' }}>
                  <td style={{ padding: 10, fontWeight: 600 }}>{a.accountNo}</td>
                  <td style={{ padding: 10 }}>{a.accountName}</td>
                  <td style={{ padding: 10 }}>{a.bankName} - {a.branchName}</td>
                  <td style={{ padding: 10 }}><span style={{ padding: '2px 6px', background: '#e0f2fe', borderRadius: 4, fontSize: 12 }}>{a.accountType}</span></td>
                  <td style={{ padding: 10 }}>
                    <span style={{
                      padding: '2px 6px',
                      borderRadius: 4,
                      fontSize: 12,
                      fontWeight: 600,
                      background: a.status === 'ACTIVE' ? '#dcfce7' : a.status === 'FROZEN' ? '#fee2e2' : '#fef9c3',
                      color: a.status === 'ACTIVE' ? '#15803d' : a.status === 'FROZEN' ? '#b91c1c' : '#a16207'
                    }}>
                      {a.status}
                    </span>
                  </td>
                  <td style={{ padding: 10, textAlign: 'right', fontFamily: 'monospace' }}>{formatCny(parseFloat(a.balance))}</td>
                  <td style={{ padding: 10 }}>
                    <div style={{ display: 'flex', gap: 6 }}>
                      {canFreezeAccount(a.status) && (
                        <button style={{ padding: '4px 8px', fontSize: 12, background: '#ef4444', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer' }}>
                          冻结
                        </button>
                      )}
                      {canCloseAccount(a.status, parseFloat(a.balance)) && (
                        <button style={{ padding: '4px 8px', fontSize: 12, background: '#64748b', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer' }}>
                          销户
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      )}

      {/* Tab 2: Orders & Internal Transfers (TR01) */}
      {activeTab === 'orders' && (
        <section>
          <h2 style={{ fontSize: 18, fontWeight: 600, marginBottom: 12 }}>收付款及内部划转 (TR01)</h2>
          <div style={{ background: '#f8fafc', padding: 16, borderRadius: 8, marginBottom: 20, border: '1px solid #e2e8f0' }}>
            <h3 style={{ fontSize: 15, fontWeight: 600, marginTop: 0 }}>发起内部账户划转</h3>
            <p style={{ fontSize: 13, color: '#64748b', marginTop: 4 }}>
              规则：同一现金范围内账户划转必须两端配对；手续费按性质单列（财务费用），在途资金期间汇总现金不重复增长。
            </p>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 12, marginTop: 12 }}>
              <div>
                <label style={{ fontSize: 12, fontWeight: 600 }}>划出账户</label>
                <select style={{ width: '100%', padding: 6, marginTop: 4 }}>
                  <option>基本存款户 (6222...001)</option>
                </select>
              </div>
              <div>
                <label style={{ fontSize: 12, fontWeight: 600 }}>划入账户</label>
                <select style={{ width: '100%', padding: 6, marginTop: 4 }}>
                  <option>日常收支一般户 (6222...002)</option>
                </select>
              </div>
              <div>
                <label style={{ fontSize: 12, fontWeight: 600 }}>划转金额 (CNY)</label>
                <input defaultValue="50000.00" style={{ width: '100%', padding: 6, marginTop: 4 }} />
              </div>
              <div>
                <label style={{ fontSize: 12, fontWeight: 600 }}>银行手续费 (CNY)</label>
                <input defaultValue="15.00" style={{ width: '100%', padding: 6, marginTop: 4 }} />
              </div>
            </div>
            <button style={{ marginTop: 16, padding: '8px 16px', background: '#2563eb', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer' }}>
              提交内部划转申请 (生成在途单据)
            </button>
          </div>
        </section>
      )}

      {/* Tab 3: Payment Instructions (TR02) */}
      {activeTab === 'instructions' && (
        <section>
          <h2 style={{ fontSize: 18, fontWeight: 600, marginBottom: 12 }}>支付指令与结果核验 (TR02)</h2>
          <div style={{ background: '#fef2f2', border: '1px solid #fecaca', padding: 12, borderRadius: 6, marginBottom: 16, fontSize: 13, color: '#991b1b' }}>
            <strong>严格 UNKNOWN 挂起机制：</strong> 银企渠道超时或未返回终态时，恒保持 <code>DISPATCHED_UNKNOWN</code>。禁止自动重试付款，禁止假设成功。结算必须录入真实银行电子回执原件 SHA-256 进行人工审签。
          </div>
          <table style={{ width: '100%', borderCollapse: 'collapse', border: '1px solid #e2e8f0' }}>
            <thead>
              <tr style={{ background: '#f8fafc', textAlign: 'left' }}>
                <th style={{ padding: 10 }}>指令编号</th>
                <th style={{ padding: 10 }}>收款方</th>
                <th style={{ padding: 10, textAlign: 'right' }}>金额 (CNY)</th>
                <th style={{ padding: 10 }}>状态</th>
                <th style={{ padding: 10 }}>渠道</th>
                <th style={{ padding: 10 }}>核验与结算</th>
              </tr>
            </thead>
            <tbody>
              {instructions.map(inst => (
                <tr key={inst.id} style={{ borderBottom: '1px solid #f1f5f9' }}>
                  <td style={{ padding: 10, fontWeight: 600 }}>{inst.instructionNo}</td>
                  <td style={{ padding: 10 }}>{inst.payeeAccountName}</td>
                  <td style={{ padding: 10, textAlign: 'right', fontFamily: 'monospace' }}>{formatCny(parseFloat(inst.amount))}</td>
                  <td style={{ padding: 10 }}>
                    <span style={{ padding: '2px 8px', background: '#fef3c7', color: '#92400e', borderRadius: 4, fontSize: 12, fontWeight: 700 }}>
                      {inst.status}
                    </span>
                  </td>
                  <td style={{ padding: 10 }}>
                    <span style={{ padding: '2px 6px', background: '#fee2e2', color: '#b91c1c', borderRadius: 4, fontSize: 12 }}>
                      {inst.bankDirectChannel}
                    </span>
                  </td>
                  <td style={{ padding: 10 }}>
                    <button
                      style={{ padding: '4px 8px', fontSize: 12, background: '#10b981', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer' }}
                      onClick={() => alert('录入银行回执流水号及上传回执文件SHA-256进行终态核销')}
                    >
                      核验回执并结算 (SHA-256)
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      )}

      {/* Tab 4: Bank Statement Import & Matching (TR03) */}
      {activeTab === 'statements' && (
        <section>
          <h2 style={{ fontSize: 18, fontWeight: 600, marginBottom: 12 }}>银行流水导入与自动匹配 (TR03)</h2>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 2fr', gap: 20 }}>
            <div style={{ background: '#f8fafc', padding: 16, borderRadius: 8, border: '1px solid #e2e8f0' }}>
              <h3 style={{ fontSize: 15, fontWeight: 600, marginTop: 0 }}>导入对账单 (CSV/JSON)</h3>
              <p style={{ fontSize: 12, color: '#64748b' }}>
                系统将校验文件级 SHA-256 与行级 Hash。重复批次不重入；期初期末余额与总额不平将自动阻断。
              </p>
              <textarea
                rows={6}
                style={{ width: '100%', fontFamily: 'monospace', fontSize: 12, padding: 8, boxSizing: 'border-box' }}
                placeholder="粘贴 CSV 流水内容..."
                defaultValue="2026-11-05,2026-11-05,CREDIT,20000.00,120000.00,622201,客户A,TX001,技术服务费"
              />
              <button style={{ marginTop: 12, padding: '8px 16px', background: '#2563eb', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer' }}>
                解析并执行对账校验
              </button>
            </div>
            <div>
              <h3 style={{ fontSize: 15, fontWeight: 600, marginTop: 0 }}>匹配建议与人工转单</h3>
              <p style={{ fontSize: 12, color: '#64748b' }}>相同金额存在多个候选时，自动标记为 <code>AMBIGUOUS_MULTI_CANDIDATE</code>，禁止自动核销。</p>
              <div style={{ padding: 16, background: '#fff', border: '1px solid #e2e8f0', borderRadius: 6 }}>
                <span style={{ padding: '2px 6px', background: '#fef3c7', color: '#b45309', borderRadius: 4, fontSize: 12, fontWeight: 600 }}>
                  AMBIGUOUS_MULTI_CANDIDATE (2个候选单据)
                </span>
                <p style={{ fontSize: 13, marginTop: 8 }}>2026-11-05 贷方 20,000.00 匹配到 2 笔未结收款单。请人工确认匹配。</p>
                <button style={{ padding: '4px 8px', fontSize: 12, background: '#2563eb', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer' }}>
                  人工确认匹配
                </button>
              </div>
            </div>
          </div>
        </section>
      )}

      {/* Tab 5: Bank Balance Reconciliation Sheet (TR04) */}
      {activeTab === 'reconciliation' && (
        <section>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
            <h2 style={{ fontSize: 18, fontWeight: 600, margin: 0 }}>银行存款余额调节表 (TR04)</h2>
            <span style={{
              padding: '4px 12px',
              borderRadius: 20,
              fontSize: 13,
              fontWeight: 700,
              background: reconResult.isBalanced ? '#dcfce7' : '#fee2e2',
              color: reconResult.isBalanced ? '#15803d' : '#b91c1c'
            }}>
              {reconResult.isBalanced ? '✓ 调节后两端平衡' : '✗ 两端不平衡'}
            </span>
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 20, marginBottom: 20 }}>
            {/* Side A: Bank Balance */}
            <div style={{ border: '1px solid #cbd5e1', borderRadius: 8, padding: 16, background: '#f8fafc' }}>
              <h3 style={{ fontSize: 15, fontWeight: 700, margin: '0 0 12px 0', borderBottom: '1px solid #e2e8f0', paddingBottom: 6 }}>
                银行对账单余额调整
              </h3>
              <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 8, fontSize: 14 }}>
                <span>银行对账单期末余额:</span>
                <span style={{ fontFamily: 'monospace', fontWeight: 600 }}>{formatCny(parseFloat(bankStmtBal))}</span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 8, fontSize: 14, color: '#15803d' }}>
                <span>加：企业已收，银行未收款 (在途存款):</span>
                <span style={{ fontFamily: 'monospace' }}>+{formatCny(reconResult.entRecBankUnrec)}</span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 12, fontSize: 14, color: '#b91c1c' }}>
                <span>减：企业已付，银行未付款 (未兑现支票):</span>
                <span style={{ fontFamily: 'monospace' }}>-{formatCny(reconResult.entPaidBankUnpaid)}</span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', borderTop: '2px dashed #cbd5e1', paddingTop: 8, fontSize: 15, fontWeight: 700 }}>
                <span>调节后银行存款余额:</span>
                <span style={{ fontFamily: 'monospace', color: '#1e40af' }}>{formatCny(reconResult.adjustedBankBalance)}</span>
              </div>
            </div>

            {/* Side B: Ledger Balance */}
            <div style={{ border: '1px solid #cbd5e1', borderRadius: 8, padding: 16, background: '#f8fafc' }}>
              <h3 style={{ fontSize: 15, fontWeight: 700, margin: '0 0 12px 0', borderBottom: '1px solid #e2e8f0', paddingBottom: 6 }}>
                企业银行日记账余额调整
              </h3>
              <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 8, fontSize: 14 }}>
                <span>企业银行存款日记账余额:</span>
                <span style={{ fontFamily: 'monospace', fontWeight: 600 }}>{formatCny(parseFloat(ledgerBal))}</span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 8, fontSize: 14, color: '#15803d' }}>
                <span>加：银行已收，企业未收款 (委托收款):</span>
                <span style={{ fontFamily: 'monospace' }}>+{formatCny(reconResult.bankCredEntUnrec)}</span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 12, fontSize: 14, color: '#b91c1c' }}>
                <span>减：银行已付，企业未付款 (直接扣款):</span>
                <span style={{ fontFamily: 'monospace' }}>-{formatCny(reconResult.bankDebEntUnrec)}</span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', borderTop: '2px dashed #cbd5e1', paddingTop: 8, fontSize: 15, fontWeight: 700 }}>
                <span>调节后企业存款余额:</span>
                <span style={{ fontFamily: 'monospace', color: '#1e40af' }}>{formatCny(reconResult.adjustedLedgerBalance)}</span>
              </div>
            </div>
          </div>

          <h3 style={{ fontSize: 15, fontWeight: 600, marginBottom: 8 }}>未达账项清单与账龄分析</h3>
          <table style={{ width: '100%', borderCollapse: 'collapse', border: '1px solid #e2e8f0', fontSize: 13 }}>
            <thead>
              <tr style={{ background: '#f1f5f9', textAlign: 'left' }}>
                <th style={{ padding: 8 }}>类型</th>
                <th style={{ padding: 8 }}>登记日期</th>
                <th style={{ padding: 8, textAlign: 'right' }}>金额 (CNY)</th>
                <th style={{ padding: 8 }}>摘要</th>
                <th style={{ padding: 8 }}>账龄 (天)</th>
                <th style={{ padding: 8 }}>预警状态</th>
              </tr>
            </thead>
            <tbody>
              {unreachedItems.map(item => {
                const aging = calculateAgingDays(item.recordedDate, '2026-11-30');
                const warning = isOverdueWarning(aging);
                return (
                  <tr key={item.id} style={{ borderBottom: '1px solid #f1f5f9' }}>
                    <td style={{ padding: 8 }}>{item.itemType}</td>
                    <td style={{ padding: 8 }}>{item.recordedDate}</td>
                    <td style={{ padding: 8, textAlign: 'right', fontFamily: 'monospace' }}>{formatCny(item.amount)}</td>
                    <td style={{ padding: 8 }}>{item.summary}</td>
                    <td style={{ padding: 8 }}>{aging} 天</td>
                    <td style={{ padding: 8 }}>
                      {warning ? (
                        <span style={{ padding: '2px 6px', background: '#fee2e2', color: '#b91c1c', borderRadius: 4, fontWeight: 700 }}>
                          ⚠ 长期未达预警 (&gt;30天)
                        </span>
                      ) : (
                        <span style={{ color: '#16a34a' }}>正常</span>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </section>
      )}

      {/* Tab 6: Cash Forecast (TR04) */}
      {activeTab === 'forecast' && (
        <section>
          <h2 style={{ fontSize: 18, fontWeight: 600, marginBottom: 8 }}>分层短期资金预测 (TR04)</h2>
          <div style={{ background: '#eff6ff', border: '1px solid #bfdbfe', padding: 12, borderRadius: 6, marginBottom: 16, fontSize: 13, color: '#1e40af' }}>
            ℹ <strong>只读预测约束：</strong> 资金预测分为 <code>CONFIRMED</code> (已确定), <code>COMMITTED</code> (已承诺合同/审批), <code>PLANNED</code> (预算计划) 三层，区间覆盖 7/14/30/90 天。预测快照恒为只读，绝对不写回总账。
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 16 }}>
            {[
              { tier: 'CONFIRMED', label: '已确定层 (CONFIRMED)', inflow: 50000, outflow: 10000, color: '#15803d', bg: '#f0fdf4' },
              { tier: 'COMMITTED', label: '已承诺层 (COMMITTED)', inflow: 20000, outflow: 15000, color: '#b45309', bg: '#fffbeb' },
              { tier: 'PLANNED', label: '计划预测层 (PLANNED)', inflow: 80000, outflow: 30000, color: '#4338ca', bg: '#eef2ff' }
            ].map(card => {
              const open = 100000;
              const close = calculateCashForecast(open, card.inflow, card.outflow);
              return (
                <div key={card.tier} style={{ border: `1px solid ${card.color}`, borderRadius: 8, padding: 16, background: card.bg }}>
                  <h3 style={{ fontSize: 15, fontWeight: 700, margin: '0 0 10px 0', color: card.color }}>{card.label}</h3>
                  <div style={{ fontSize: 13, display: 'flex', justifyContent: 'space-between', marginBottom: 6 }}>
                    <span>当前现金:</span>
                    <span style={{ fontFamily: 'monospace' }}>{formatCny(open)}</span>
                  </div>
                  <div style={{ fontSize: 13, display: 'flex', justifyContent: 'space-between', marginBottom: 6, color: '#16a34a' }}>
                    <span>预计流入:</span>
                    <span style={{ fontFamily: 'monospace' }}>+{formatCny(card.inflow)}</span>
                  </div>
                  <div style={{ fontSize: 13, display: 'flex', justifyContent: 'space-between', marginBottom: 10, color: '#dc2626' }}>
                    <span>预计流出:</span>
                    <span style={{ fontFamily: 'monospace' }}>-{formatCny(card.outflow)}</span>
                  </div>
                  <div style={{ fontSize: 14, fontWeight: 700, display: 'flex', justifyContent: 'space-between', borderTop: '1px solid #cbd5e1', paddingTop: 8 }}>
                    <span>预测期末现金:</span>
                    <span style={{ fontFamily: 'monospace' }}>{formatCny(close)}</span>
                  </div>
                </div>
              );
            })}
          </div>
        </section>
      )}
    </div>
  );
};
