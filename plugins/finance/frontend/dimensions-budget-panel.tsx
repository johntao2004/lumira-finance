import React, { useState } from 'react';
import {
  allocateCost,
  computeBudgetAvailable,
  rollbackBudgetConsumption,
  computeForecastProjection,
  validateSoD,
  TargetRatio,
  AllocatedTargetResult,
  BudgetControlStatus,
  ForecastProjection,
} from './dimensions-budget-rules';

export function DimensionsBudgetPanel() {
  const [activeTab, setActiveTab] = useState<'MD01' | 'MD02' | 'MD03' | 'BD01_02'>('MD03');

  // Allocation Calculator State (MD03)
  const [totalAmount, setTotalAmount] = useState('100.00');
  const [scale, setScale] = useState<2 | 8>(2);
  const [ratios, setRatios] = useState<TargetRatio[]>([
    { targetId: 'DEPT-ENG', ratio: '0.500000000000', priority: 1 },
    { targetId: 'DEPT-SALES', ratio: '0.300000000000', priority: 2 },
    { targetId: 'DEPT-OPS', ratio: '0.200000000000', priority: 3 },
  ]);
  const [allocResult, setAllocResult] = useState<AllocatedTargetResult[] | null>(null);
  const [allocError, setAllocError] = useState<string | null>(null);

  // Budget Control State (BD02)
  const [budgetAmt, setBudgetAmt] = useState('100000.00000000');
  const [reservedAmt, setReservedAmt] = useState('30000.00000000');
  const [consumedAmt, setConsumedAmt] = useState('20000.00000000');
  const [rollbackAmt, setRollbackAmt] = useState('5000.00000000');
  const [budgetStatus, setBudgetStatus] = useState<BudgetControlStatus | null>(null);
  const [budgetError, setBudgetError] = useState<string | null>(null);

  // SoD State
  const [makerPerson, setMakerPerson] = useState('11111111-1111-1111-1111-111111111111');
  const [reviewerPerson, setReviewerPerson] = useState('22222222-2222-2222-2222-222222222222');
  const [sodStatus, setSodStatus] = useState<string | null>(null);

  const handleCalculateAllocation = () => {
    try {
      setAllocError(null);
      const res = allocateCost(totalAmount, ratios, scale);
      setAllocResult(res);
    } catch (e: any) {
      setAllocError(e.message);
      setAllocResult(null);
    }
  };

  const handleComputeBudget = () => {
    try {
      setBudgetError(null);
      const res = computeBudgetAvailable(budgetAmt, reservedAmt, consumedAmt);
      setBudgetStatus(res);
    } catch (e: any) {
      setBudgetError(e.message);
      setBudgetStatus(null);
    }
  };

  const handleRollback = () => {
    try {
      setBudgetError(null);
      const newConsumed = rollbackBudgetConsumption(consumedAmt, rollbackAmt);
      setConsumedAmt(newConsumed);
      const res = computeBudgetAvailable(budgetAmt, reservedAmt, newConsumed);
      setBudgetStatus(res);
    } catch (e: any) {
      setBudgetError(e.message);
    }
  };

  const handleCheckSoD = () => {
    try {
      validateSoD(makerPerson, reviewerPerson);
      setSodStatus('SUCCESS: Maker and Reviewer are distinct natural persons.');
    } catch (e: any) {
      setSodStatus(`VIOLATION: ${e.message}`);
    }
  };

  return (
    <div style={{ padding: '20px', fontFamily: 'sans-serif' }}>
      <h2>Dimensions & Budget Control Workbench (Lane W03)</h2>
      <div style={{ display: 'flex', gap: '10px', marginBottom: '20px' }}>
        <button onClick={() => setActiveTab('MD01')} style={{ fontWeight: activeTab === 'MD01' ? 'bold' : 'normal' }}>
          MD01: Account Auxiliary Dimensions
        </button>
        <button onClick={() => setActiveTab('MD02')} style={{ fontWeight: activeTab === 'MD02' ? 'bold' : 'normal' }}>
          MD02: Counterparty Bank & Identity
        </button>
        <button onClick={() => setActiveTab('MD03')} style={{ fontWeight: activeTab === 'MD03' ? 'bold' : 'normal' }}>
          MD03: Cost Allocation Calculator
        </button>
        <button onClick={() => setActiveTab('BD01_02')} style={{ fontWeight: activeTab === 'BD01_02' ? 'bold' : 'normal' }}>
          BD01/BD02: Budget Control & Invariants
        </button>
      </div>

      {activeTab === 'MD01' && (
        <div style={{ border: '1px solid #ccc', padding: '15px', borderRadius: '4px' }}>
          <h3>Account Auxiliary Dimension Bindings & Deactivation Approval</h3>
          <p>Chart of Accounts Standard: <strong>CAS 2006</strong> / <strong>SMALL 2013</strong></p>
          <div style={{ marginBottom: '10px' }}>
            <label><input type="checkbox" defaultChecked /> Require Department</label><br />
            <label><input type="checkbox" defaultChecked /> Require Project</label><br />
            <label><input type="checkbox" /> Require Counterparty</label><br />
            <label><input type="checkbox" /> Require Employee</label><br />
            <label><input type="checkbox" defaultChecked /> Require Cost Center</label>
          </div>
          <h4>Independent Deactivation Approval (SoD Guard)</h4>
          <div>
            <label>Maker Person ID: </label>
            <input value={makerPerson} onChange={(e) => setMakerPerson(e.target.value)} style={{ width: '300px' }} />
          </div>
          <div style={{ marginTop: '5px' }}>
            <label>Reviewer Person ID: </label>
            <input value={reviewerPerson} onChange={(e) => setReviewerPerson(e.target.value)} style={{ width: '300px' }} />
          </div>
          <button onClick={handleCheckSoD} style={{ marginTop: '10px' }}>Validate Deactivation SoD</button>
          {sodStatus && <p style={{ marginTop: '10px', color: sodStatus.startsWith('SUCCESS') ? 'green' : 'red' }}>{sodStatus}</p>}
        </div>
      )}

      {activeTab === 'MD02' && (
        <div style={{ border: '1px solid #ccc', padding: '15px', borderRadius: '4px' }}>
          <h3>Counterparty Settlement Bank Accounts & Identity Changes</h3>
          <ul>
            <li>Bank Settlement Account: Multi-account registry, purpose tag, default flag.</li>
            <li>Independent Bank Review: Changes and deactivations require dual-person sign-off.</li>
            <li>Identity History: Immutable legal-name and tax identifier history with evidence SHA-256 hash.</li>
            <li>Framework Contracts: Credit days and penalty interest rate `numeric(18,12)`.</li>
          </ul>
        </div>
      )}

      {activeTab === 'MD03' && (
        <div style={{ border: '1px solid #ccc', padding: '15px', borderRadius: '4px' }}>
          <h3>Multi-Target Cost Allocation Calculator (Zero-Leakage Remainder)</h3>
          <div style={{ marginBottom: '10px' }}>
            <label>Total Amount: </label>
            <input value={totalAmount} onChange={(e) => setTotalAmount(e.target.value)} />
            <span style={{ marginLeft: '15px' }}>
              Scale:
              <select value={scale} onChange={(e) => setScale(Number(e.target.value) as 2 | 8)}>
                <option value={2}>2 (Penny)</option>
                <option value={8}>8 (Exact Monetary)</option>
              </select>
            </span>
          </div>

          <table border={1} cellPadding={5} style={{ marginBottom: '10px', borderCollapse: 'collapse' }}>
            <thead>
              <tr>
                <th>Target ID</th>
                <th>Ratio (numeric 18,12)</th>
                <th>Priority</th>
              </tr>
            </thead>
            <tbody>
              {ratios.map((r, i) => (
                <tr key={r.targetId}>
                  <td>{r.targetId}</td>
                  <td>{r.ratio}</td>
                  <td>{r.priority}</td>
                </tr>
              ))}
            </tbody>
          </table>

          <button onClick={handleCalculateAllocation}>Execute Allocation</button>
          {allocError && <p style={{ color: 'red' }}>Error: {allocError}</p>}

          {allocResult && (
            <div style={{ marginTop: '15px' }}>
              <h4>Allocation Results (Sum Guaranteed Exact):</h4>
              <ul>
                {allocResult.map((res) => (
                  <li key={res.targetId}>
                    Target <strong>{res.targetId}</strong>: {res.allocatedAmount} (Ratio: {res.ratio})
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
      )}

      {activeTab === 'BD01_02' && (
        <div style={{ border: '1px solid #ccc', padding: '15px', borderRadius: '4px' }}>
          <h3>Real-Time Budget Control Ledger & Invariant Verifier</h3>
          <div>
            <label>Budget Amount: </label>
            <input value={budgetAmt} onChange={(e) => setBudgetAmt(e.target.value)} />
          </div>
          <div style={{ marginTop: '5px' }}>
            <label>Reserved Amount (Pre-encumbrance): </label>
            <input value={reservedAmt} onChange={(e) => setReservedAmt(e.target.value)} />
          </div>
          <div style={{ marginTop: '5px' }}>
            <label>Consumed Amount (Executed): </label>
            <input value={consumedAmt} onChange={(e) => setConsumedAmt(e.target.value)} />
          </div>

          <div style={{ marginTop: '10px', display: 'flex', gap: '10px' }}>
            <button onClick={handleComputeBudget}>Verify Control Ledger</button>
          </div>

          <div style={{ marginTop: '15px', borderTop: '1px dashed #ddd', paddingTop: '10px' }}>
            <h4>Accounting Reversal (Consumption Rollback):</h4>
            <label>Rollback Amount: </label>
            <input value={rollbackAmt} onChange={(e) => setRollbackAmt(e.target.value)} />
            <button onClick={handleRollback} style={{ marginLeft: '10px' }}>Rollback Consumption</button>
          </div>

          {budgetError && <p style={{ color: 'red' }}>Error: {budgetError}</p>}

          {budgetStatus && (
            <div style={{ marginTop: '15px', backgroundColor: '#f0f8ff', padding: '10px' }}>
              <h4>Control Ledger Status:</h4>
              <p>Budget: {budgetStatus.budgetAmount}</p>
              <p>Reserved: {budgetStatus.reservedAmount}</p>
              <p>Consumed: {budgetStatus.consumedAmount}</p>
              <p><strong>Available Quota: {budgetStatus.availableAmount}</strong></p>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
