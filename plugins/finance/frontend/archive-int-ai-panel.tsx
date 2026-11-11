import React, { useState } from 'react';
import {
  STATUTORY_RETENTION_YEARS,
  calculateRetentionUntilDate,
  calculateMonthEndDate,
  suggestAccountFromOcr,
  dryRunCsv,
  reconcileControlTotals,
  checkAntiCrmGuard,
  validateActionPlanExecution,
  validateOfflineLease,
  verifyArchiveCompleteness,
  type FieldMapping,
  type DryRunResult,
  type ArchiveRecord,
} from './archive-int-ai-rules';

export const ArchiveIntAiPanel: React.FC = () => {
  const [activeTab, setActiveTab] = useState<'archive' | 'import' | 'erp' | 'schedules' | 'ai' | 'offline'>('archive');

  // Archive state
  const [archiveStartYear, setArchiveStartYear] = useState<number>(2026);
  const [archiveCategory, setArchiveCategory] = useState<string>('VOUCHER_GENERAL');
  const [archiveTitle, setArchiveTitle] = useState<string>('2026年第1季度记账凭证档案');
  const [legalHoldActive, setLegalHoldActive] = useState<boolean>(false);
  const [completenessResult, setCompletenessResult] = useState<{
    completenessSatisfied: boolean;
    legalArchiveComplete: boolean;
    blockers: string[];
  } | null>(null);

  // Import state
  const [csvInput, setCsvInput] = useState<string>(
    'voucher_no,account_code,debit,credit,accounting_date\nV001,100201,1500.00,0.00,2026-10-15\nV001,600101,0.00,1500.00,2026-10-15'
  );
  const [dryRunRes, setDryRunRes] = useState<DryRunResult | null>(null);

  // ERP state
  const [erpPayload, setErpPayload] = useState<string>(
    '{\n  "source_system": "SAP",\n  "event_type": "REVENUE_RECOGNIZED",\n  "net_amount": "1500.00"\n}'
  );
  const [antiCrmStatus, setAntiCrmStatus] = useState<string | null>(null);

  // Schedules state
  const [schedYear, setSchedYear] = useState<number>(2024);
  const [schedMonth, setSchedMonth] = useState<number>(2);
  const [leapRule, setLeapRule] = useState<string>('LAST_DAY_OF_MONTH');
  const [calcDate, setCalcDate] = useState<string>('');

  // AI state
  const [ocrInput, setOcrInput] = useState<string>('增值税普通发票 差旅报销 机票住宿 1200元');
  const [ocrSuggestion, setOcrSuggestion] = useState<any>(null);
  const [actionRisk, setActionRisk] = useState<string>('R1');
  const [actionCommand, setActionCommand] = useState<string>('JOURNAL_CREATE_DRAFT');
  const [planExecutionStatus, setPlanExecutionStatus] = useState<string | null>(null);

  // Offline lease state
  const [leaseSeconds, setLeaseSeconds] = useState<number>(28800);
  const [leaseStatus, setLeaseStatus] = useState<string | null>(null);

  const handleDryRun = () => {
    const mappings: FieldMapping[] = [
      { sourceColumn: 'voucher_no', targetField: 'voucher_no', dataType: 'STRING', required: true },
      { sourceColumn: 'account_code', targetField: 'account_code', dataType: 'STRING', required: true },
      { sourceColumn: 'debit', targetField: 'debit', dataType: 'DECIMAL', required: true },
      { sourceColumn: 'credit', targetField: 'credit', dataType: 'DECIMAL', required: true },
      { sourceColumn: 'accounting_date', targetField: 'accounting_date', dataType: 'DATE', required: true },
    ];
    const res = dryRunCsv(mappings, ',', csvInput);
    setDryRunRes(res);
  };

  const handleTestAntiCrm = () => {
    try {
      checkAntiCrmGuard(erpPayload);
      setAntiCrmStatus('PASSED: Clean accounting event payload. No CRM fields detected.');
    } catch (err: any) {
      setAntiCrmStatus('BLOCKED: ' + err.message);
    }
  };

  const handleCheckCompleteness = (allPass: boolean) => {
    const res = verifyArchiveCompleteness(allPass, allPass, allPass, allPass);
    setCompletenessResult(res);
  };

  const handleOcrSuggest = () => {
    const sugg = suggestAccountFromOcr(ocrInput);
    setOcrSuggestion(sugg);
  };

  const handleExecuteActionPlan = () => {
    try {
      validateActionPlanExecution([{ actionId: 'act-001', command: actionCommand, risk: actionRisk }]);
      setPlanExecutionStatus('SUCCESS: ActionPlan executed successfully within authorized budget.');
    } catch (err: any) {
      setPlanExecutionStatus('BLOCKED: ' + err.message);
    }
  };

  const handleIssueLease = () => {
    try {
      validateOfflineLease(leaseSeconds);
      setLeaseStatus(`ACTIVE: Non-renewable offline capture lease granted for ${leaseSeconds}s (Max 8h). Web-only client.`);
    } catch (err: any) {
      setLeaseStatus('ERROR: ' + err.message);
    }
  };

  return (
    <div style={{ padding: '24px', fontFamily: 'system-ui, sans-serif', maxWidth: '1200px', margin: '0 auto' }}>
      <h2>Lumira Finance - Candidate Lane W12 (Archive / Integration / AI / Offline)</h2>
      <p style={{ color: '#666' }}>
        Statutory Archive Governance (ARC01–03), Import Batch (INT01), ERP Integration & Anti-CRM (INT02/PLT03),
        Recurring Schedules (INT03), Controlled AI (AI01–02, AIX01–03), and Offline Lease (PLT01).
      </p>

      {/* Tabs */}
      <div style={{ display: 'flex', gap: '8px', borderBottom: '2px solid #ddd', marginBottom: '16px' }}>
        {[
          { key: 'archive', label: '1. 档案法定保管与移交销毁' },
          { key: 'import', label: '2. 结构化导入与试算对账' },
          { key: 'erp', label: '3. ERP集成与防CRM漂移' },
          { key: 'schedules', label: '4. 周期任务与日历规则' },
          { key: 'ai', label: '5. 受控AI与ActionPlan执行' },
          { key: 'offline', label: '6. 离线租约与加密存证' },
        ].map(t => (
          <button
            key={t.key}
            onClick={() => setActiveTab(t.key as any)}
            style={{
              padding: '8px 16px',
              border: 'none',
              background: activeTab === t.key ? '#0066cc' : 'transparent',
              color: activeTab === t.key ? '#fff' : '#333',
              cursor: 'pointer',
              fontWeight: activeTab === t.key ? 'bold' : 'normal',
              borderRadius: '4px 4px 0 0',
            }}
          >
            {t.label}
          </button>
        ))}
      </div>

      {/* Tab 1: Archive Governance */}
      {activeTab === 'archive' && (
        <div>
          <h3>法定保管期限与完整性检测（ARC01 / ARC02 / ARC03）</h3>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '20px' }}>
            <div style={{ border: '1px solid #ccc', padding: '16px', borderRadius: '4px' }}>
              <h4>档案登记与保管到期计算</h4>
              <label>档案类别：</label>
              <select
                value={archiveCategory}
                onChange={e => setArchiveCategory(e.target.value)}
                style={{ width: '100%', marginBottom: '10px', padding: '6px' }}
              >
                {Object.entries(STATUTORY_RETENTION_YEARS).map(([k, v]) => (
                  <option key={k} value={k}>
                    {k} - {v.desc} ({v.years === 999 ? '永久' : `${v.years}年`})
                  </option>
                ))}
              </select>
              <label>归档年度：</label>
              <input
                type="number"
                value={archiveStartYear}
                onChange={e => setArchiveStartYear(Number(e.target.value))}
                style={{ width: '100%', marginBottom: '10px', padding: '6px' }}
              />
              <p>
                <strong>法定保管期限：</strong>
                {STATUTORY_RETENTION_YEARS[archiveCategory].years === 999
                  ? '永久保管 (9999-12-31)'
                  : `${STATUTORY_RETENTION_YEARS[archiveCategory].years}年，到期日: ${calculateRetentionUntilDate(
                      archiveStartYear,
                      STATUTORY_RETENTION_YEARS[archiveCategory].years
                    )}`}
              </p>
              <label style={{ display: 'flex', alignItems: 'center', gap: '8px', cursor: 'pointer' }}>
                <input
                  type="checkbox"
                  checked={legalHoldActive}
                  onChange={e => setLegalHoldActive(e.target.checked)}
                />
                <strong>涉税审计/未了事项诉讼冻结 (Legal Hold)</strong>
              </label>
              {legalHoldActive && (
                <div style={{ marginTop: '10px', background: '#fff3cd', padding: '8px', borderRadius: '4px' }}>
                  ⚠️ 依据《会计档案管理办法》第十六条，未了事项冻结期间严禁销毁鉴定或处置。
                </div>
              )}
            </div>

            <div style={{ border: '1px solid #ccc', padding: '16px', borderRadius: '4px' }}>
              <h4>完整性校验与出境/归档放行网关</h4>
              <p>校验四维度门禁：三表已正式发布、年度已CLOSED、原件SHA-256复核通过、审计日志完备。</p>
              <div style={{ display: 'flex', gap: '10px', marginBottom: '10px' }}>
                <button
                  onClick={() => handleCheckCompleteness(true)}
                  style={{ padding: '8px 16px', background: '#28a745', color: '#fff', border: 'none', borderRadius: '4px' }}
                >
                  模拟全部合规放行
                </button>
                <button
                  onClick={() => handleCheckCompleteness(false)}
                  style={{ padding: '8px 16px', background: '#dc3545', color: '#fff', border: 'none', borderRadius: '4px' }}
                >
                  模拟门禁缺失阻断
                </button>
              </div>
              {completenessResult && (
                <div
                  style={{
                    padding: '12px',
                    borderRadius: '4px',
                    background: completenessResult.completenessSatisfied ? '#d4edda' : '#f8d7da',
                  }}
                >
                  <strong>放行状态：{completenessResult.legalArchiveComplete ? '✅ 放行归档/出境' : '❌ 阻断 (422)'}</strong>
                  {completenessResult.blockers.length > 0 && (
                    <ul>
                      {completenessResult.blockers.map(b => (
                        <li key={b}>{b}</li>
                      ))}
                    </ul>
                  )}
                </div>
              )}
            </div>
          </div>
        </div>
      )}

      {/* Tab 2: Import Batch */}
      {activeTab === 'import' && (
        <div>
          <h3>CSV/结构化数据导入与试算平衡预览（INT01）</h3>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '20px' }}>
            <div>
              <label>导入CSV内容：</label>
              <textarea
                rows={8}
                value={csvInput}
                onChange={e => setCsvInput(e.target.value)}
                style={{ width: '100%', fontFamily: 'monospace', padding: '8px', marginBottom: '10px' }}
              />
              <button
                onClick={handleDryRun}
                style={{ padding: '8px 16px', background: '#0066cc', color: '#fff', border: 'none', borderRadius: '4px' }}
              >
                执行确定性 Dry-Run 预检
              </button>
            </div>
            <div>
              <h4>Dry-Run 试算与行错预览结果</h4>
              {dryRunRes && (
                <div style={{ background: dryRunRes.passed ? '#d4edda' : '#f8d7da', padding: '12px', borderRadius: '4px' }}>
                  <p><strong>状态：</strong>{dryRunRes.passed ? '✅ DRY_RUN_PASSED' : '❌ DRY_RUN_FAILED'}</p>
                  <p>总行数: {dryRunRes.totalRows} | 有效行: {dryRunRes.validRows} | 错误行: {dryRunRes.errorRows}</p>
                  <p>借方合计: {dryRunRes.controlTotalDebit} | 贷方合计: {dryRunRes.controlTotalCredit}</p>
                  {dryRunRes.errors.length > 0 && (
                    <div>
                      <strong>行级错误清单：</strong>
                      <ul>
                        {dryRunRes.errors.map((err, i) => (
                          <li key={i}>
                            第 {err.lineNo} 行 [{err.column}]: {err.errorCode} - {err.message} (取值: '{err.sampleValue}')
                          </li>
                        ))}
                      </ul>
                    </div>
                  )}
                </div>
              )}
            </div>
          </div>
        </div>
      )}

      {/* Tab 3: ERP Integration & Anti-CRM */}
      {activeTab === 'erp' && (
        <div>
          <h3>外部ERP连接器授权与防CRM漂移网关（INT02 & PLT03）</h3>
          <p>严格只接收会计业务事件（AccountingEvent）。非财务CRM线索或商机字段将被硬性阻断（422）。</p>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '20px' }}>
            <div>
              <label>入站 JSON 报文测试：</label>
              <textarea
                rows={8}
                value={erpPayload}
                onChange={e => setErpPayload(e.target.value)}
                style={{ width: '100%', fontFamily: 'monospace', padding: '8px', marginBottom: '10px' }}
              />
              <div style={{ display: 'flex', gap: '10px' }}>
                <button
                  onClick={handleTestAntiCrm}
                  style={{ padding: '8px 16px', background: '#0066cc', color: '#fff', border: 'none', borderRadius: '4px' }}
                >
                  报文 Anti-CRM 卫兵检测
                </button>
                <button
                  onClick={() =>
                    setErpPayload(
                      '{\n  "source_system": "SAP",\n  "event_type": "REVENUE_RECOGNIZED",\n  "lead_id": "CRM-LEAD-9981",\n  "opportunity_id": "OPP-123"\n}'
                    )
                  }
                  style={{ padding: '8px 16px', background: '#6c757d', color: '#fff', border: 'none', borderRadius: '4px' }}
                >
                  填入违规CRM载荷测试
                </button>
              </div>
            </div>
            <div>
              <h4>卫兵阻断结果</h4>
              {antiCrmStatus && (
                <div
                  style={{
                    padding: '12px',
                    borderRadius: '4px',
                    background: antiCrmStatus.startsWith('PASSED') ? '#d4edda' : '#f8d7da',
                    color: antiCrmStatus.startsWith('PASSED') ? '#155724' : '#721c24',
                  }}
                >
                  {antiCrmStatus}
                </div>
              )}
            </div>
          </div>
        </div>
      )}

      {/* Tab 4: Recurring Schedules */}
      {activeTab === 'schedules' && (
        <div>
          <h3>周期任务与月末/闰年二月日历规则（INT03）</h3>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '20px' }}>
            <div style={{ border: '1px solid #ccc', padding: '16px', borderRadius: '4px' }}>
              <h4>月末日历计算器</h4>
              <label>年度：</label>
              <input
                type="number"
                value={schedYear}
                onChange={e => setSchedYear(Number(e.target.value))}
                style={{ width: '100%', marginBottom: '10px', padding: '6px' }}
              />
              <label>月份：</label>
              <input
                type="number"
                min={1}
                max={12}
                value={schedMonth}
                onChange={e => setSchedMonth(Number(e.target.value))}
                style={{ width: '100%', marginBottom: '10px', padding: '6px' }}
              />
              <label>二月闰日规则：</label>
              <select
                value={leapRule}
                onChange={e => setLeapRule(e.target.value)}
                style={{ width: '100%', marginBottom: '10px', padding: '6px' }}
              >
                <option value="LAST_DAY_OF_MONTH">LAST_DAY_OF_MONTH (闰年29/平年28)</option>
                <option value="EXACT_28_ALWAYS">EXACT_28_ALWAYS (恒定28日)</option>
              </select>
              <button
                onClick={() => setCalcDate(calculateMonthEndDate(schedYear, schedMonth, leapRule))}
                style={{ padding: '8px 16px', background: '#0066cc', color: '#fff', border: 'none', borderRadius: '4px' }}
              >
                计算月末执行日
              </button>
              {calcDate && (
                <p style={{ marginTop: '12px', fontSize: '18px', fontWeight: 'bold' }}>
                  计算结果：{calcDate}
                </p>
              )}
            </div>

            <div style={{ border: '1px solid #ccc', padding: '16px', borderRadius: '4px' }}>
              <h4>漏跑周期自动补跑检测机制</h4>
              <p>系统自动扫描本财年之前尚未执行的会计期间，将其标识为 <code>CATCH_UP</code> 自动追加补跑。</p>
              <p>周期幂等约束：同一账套同一周期任务严格单次执行，防重复入账。</p>
            </div>
          </div>
        </div>
      )}

      {/* Tab 5: AI & ActionPlan */}
      {activeTab === 'ai' && (
        <div>
          <h3>受控AI凭证草稿建议与重大动作分级阻断（AI01, AI02, AIX01–03）</h3>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '20px' }}>
            <div style={{ border: '1px solid #ccc', padding: '16px', borderRadius: '4px' }}>
              <h4>AI01 本地统计/规则发票OCR记账建议</h4>
              <input
                type="text"
                value={ocrInput}
                onChange={e => setOcrInput(e.target.value)}
                style={{ width: '100%', marginBottom: '10px', padding: '6px' }}
              />
              <button
                onClick={handleOcrSuggest}
                style={{ padding: '8px 16px', background: '#0066cc', color: '#fff', border: 'none', borderRadius: '4px' }}
              >
                生成本地科目建议
              </button>
              {ocrSuggestion && (
                <div style={{ marginTop: '10px', background: '#e9ecef', padding: '10px', borderRadius: '4px' }}>
                  <p>推荐科目: <strong>{ocrSuggestion.suggestedAccountCode}</strong> ({ocrSuggestion.accountName})</p>
                  <p>模型置信度: <strong>{ocrSuggestion.confidence}</strong></p>
                  <p>外部模型供应商: <code>{ocrSuggestion.externalModelProvider}</code></p>
                  <p>人工强制复核: <code>{ocrSuggestion.mandatoryHumanReview ? 'TRUE' : 'FALSE'}</code></p>
                </div>
              )}
            </div>

            <div style={{ border: '1px solid #ccc', padding: '16px', borderRadius: '4px' }}>
              <h4>AIX03 分级重大动作阻断防护（R4 禁止自动执行）</h4>
              <label>动作指令：</label>
              <select
                value={actionCommand}
                onChange={e => setActionCommand(e.target.value)}
                style={{ width: '100%', marginBottom: '10px', padding: '6px' }}
              >
                <option value="JOURNAL_CREATE_DRAFT">JOURNAL_CREATE_DRAFT (R1: 草稿)</option>
                <option value="JOURNAL_POST">JOURNAL_POST (R3: 记账过账)</option>
                <option value="TAX_SUBMIT">TAX_SUBMIT (R4: 税务申报)</option>
                <option value="TAX_PAYMENT">TAX_PAYMENT (R4: 缴税划扣)</option>
                <option value="BANK_DISBURSEMENT">BANK_DISBURSEMENT (R4: 银企出纳付款)</option>
              </select>

              <label>风险级别：</label>
              <select
                value={actionRisk}
                onChange={e => setActionRisk(e.target.value)}
                style={{ width: '100%', marginBottom: '10px', padding: '6px' }}
              >
                <option value="R1">R1 (低风险)</option>
                <option value="R2">R2 (常规中风险)</option>
                <option value="R3">R3 (高风险内部操作)</option>
                <option value="R4">R4 (重大外部风险 - 严格阻断)</option>
              </select>

              <button
                onClick={handleExecuteActionPlan}
                style={{ padding: '8px 16px', background: '#dc3545', color: '#fff', border: 'none', borderRadius: '4px' }}
              >
                尝试执行 ActionPlan
              </button>

              {planExecutionStatus && (
                <div
                  style={{
                    marginTop: '10px',
                    padding: '10px',
                    borderRadius: '4px',
                    background: planExecutionStatus.startsWith('SUCCESS') ? '#d4edda' : '#f8d7da',
                    color: planExecutionStatus.startsWith('SUCCESS') ? '#155724' : '#721c24',
                  }}
                >
                  {planExecutionStatus}
                </div>
              )}
            </div>
          </div>
        </div>
      )}

      {/* Tab 6: Offline Lease */}
      {activeTab === 'offline' && (
        <div>
          <h3>移动端/小程序离线采集租约与密文存证（PLT01）</h3>
          <div style={{ border: '1px solid #ccc', padding: '16px', borderRadius: '4px', maxWidth: '600px' }}>
            <p>法定离线租约规则：租约最长不超过 8 小时 (28,800 秒)，不可离线自续，重连联网自动校验撤权。</p>
            <label>申请租约时长（秒）：</label>
            <input
              type="number"
              value={leaseSeconds}
              onChange={e => setLeaseSeconds(Number(e.target.value))}
              style={{ width: '100%', marginBottom: '10px', padding: '6px' }}
            />
            <button
              onClick={handleIssueLease}
              style={{ padding: '8px 16px', background: '#0066cc', color: '#fff', border: 'none', borderRadius: '4px' }}
            >
              签发离线采集租约
            </button>
            {leaseStatus && (
              <div
                style={{
                  marginTop: '10px',
                  padding: '10px',
                  borderRadius: '4px',
                  background: leaseStatus.startsWith('ACTIVE') ? '#d4edda' : '#f8d7da',
                }}
              >
                {leaseStatus}
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
};
