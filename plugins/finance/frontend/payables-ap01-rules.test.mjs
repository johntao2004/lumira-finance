import test from "node:test";
import assert from "node:assert/strict";
import {
  apRaw,
  apProducerMatches,
  apCreate,
  apBasisCreate,
  apCny,
  apMoney,
  apAction,
  apApprove,
  apPost,
  apReversal,
  apIndependent,
  apRequestIdentity,
  apDraft,
  apDate,
  apExpiry,
} from "./payables-ap01-rules.ts";
import { EvidenceRequestOwner } from "./evidence-request-state.ts";
const a = "00000000-0000-4000-8000-000000000001",
  b = "00000000-0000-4000-8000-000000000002",
  c = "00000000-0000-4000-8000-000000000003";
const parent = {
  eligibleForPreparation: true,
  parent: {
    allocationId: a,
    documentId: b,
    netConfirmedAmount: "600.01000000",
    documentExpectation: { documentId: b, currentReviewId: c },
  },
  sourceSlot: { latestAttemptId: c, latestAttemptVersion: "9007199254740993" },
  capacity: {
    qualification: "ORG_REVIEWED",
    capacity: "800.00000000",
    available: "800.00000000",
    currentBasis: { basisId: b, basisVersion: "8", contentDigest: "basis" },
  },
  expenseCapacity: "999.00000000",
};
const supplier = {
  counterpartyId: a,
  governanceHomeBookId: b,
  registryVersion: "9007199254740994",
  approvedRevisionId: c,
  approvedRevisionDigest: "supplier",
};
const values = {
  reason: "原样\n理由",
  accountingDate: "2036-02-29",
  dueDate: "",
  expenseAccountId: a,
  payableAccountId: b,
  serviceAcceptanceOriginalId: a,
  recognitionOriginalId: c,
  recognitionReason: "真实验收说明",
  summary: "两行非现金费用应付",
  ordinaryServiceExpenseConfirmed: true,
  supplierMatchesDocumentConfirmed: true,
  serviceAcceptedConfirmed: true,
  noTaxAssetInventoryPayrollConfirmed: true,
  payableAccountUseConfirmed: true,
  amount: "99999.99",
  sourceSystem: "FAKE",
  secret: "never",
};
const detail = {
  payable: {
    id: a,
    version: "9007199254740993",
    contentVersion: "9007199254740994",
    contentDigest: "content",
    sourceDigest: "source",
  },
  currentSubmission: { id: b },
  currentApproval: { id: c },
  currentSourceSlot: {
    latestAttemptId: c,
    latestAttemptVersion: "9007199254740995",
  },
  postReceipt: { id: b },
  openItem: { id: c, version: "9007199254740996" },
};
test("AP显式raw producer分流，不能伪装NORMAL或SYSTEM走旧动作", () => {
  assert.equal(apRaw("AP_EXPENSE"), true);
  assert.equal(apRaw("AP_EXPENSE_REVERSAL"), true);
  const link = {
    journalId: a,
    payableId: b,
    rawType: "AP_EXPENSE",
    producerKind: "AP_EXPENSE",
    resolution: "FOUND",
  };
  assert.equal(apProducerMatches(link, a, "AP_EXPENSE"), true);
  assert.equal(apProducerMatches(link, c, "AP_EXPENSE"), false);
  assert.equal(
    apProducerMatches({ ...link, producerKind: null }, a, "AP_EXPENSE"),
    false,
  );
  assert.equal(apProducerMatches(link, a, "NORMAL"), false);
  for (const t of ["NORMAL", "REVERSAL", "PROFIT_CLOSE", "SYSTEM", undefined])
    assert.equal(apRaw(t), false);
});
test("准备严格白名单且无自由金额/来源键，绑定真实全父份额及统一latest前驱", () => {
  const p = apCreate(values, parent, supplier, a);
  assert.equal(p.expectedParent.netConfirmedAmount, "600.01000000");
  assert.equal(p.expectedPredecessorId, c);
  assert.equal(p.expectedPredecessorVersion, "9007199254740993");
  assert.equal(p.expectedAccountingBasis, parent.capacity.currentBasis);
  for (const k of ["amount", "sourceSystem", "secret"])
    assert.equal(k in p, false);
  assert.equal(p.dueDate, null);
});
test("未知或缺会计依据不能借expenseCapacity/面额冒充可用", () => {
  assert.throws(() =>
    apCreate(
      values,
      {
        ...parent,
        capacity: {
          ...parent.capacity,
          qualification: "UNKNOWN",
          capacity: null,
          available: null,
          currentBasis: null,
        },
      },
      supplier,
      a,
    ),
  );
  assert.throws(() =>
    apCreate(values, { ...parent, eligibleForPreparation: false }, supplier, a),
  );
});
test("供应商应付科目用途和全部声明均须主动确认", () => {
  for (const key of [
    "ordinaryServiceExpenseConfirmed",
    "supplierMatchesDocumentConfirmed",
    "serviceAcceptedConfirmed",
    "noTaxAssetInventoryPayrollConfirmed",
    "payableAccountUseConfirmed",
  ])
    assert.throws(() =>
      apCreate({ ...values, [key]: false }, parent, supplier, a),
    );
});
test("CNY二位输入与内部八位读数分离，20位整数保持字符串精确", () => {
  assert.equal(apCny("10000000000000000000.01"), "10000000000000000000.01");
  assert.equal(apCny("0", true), "0");
  for (const n of [
    "1.001",
    "1.005",
    "01.00",
    "-1.00",
    1.01,
    "100000000000000000000.00",
  ])
    assert.throws(() => apCny(n));
  assert.equal(apMoney("600.01000000"), "600.01000000");
  assert.equal(apMoney(null), "未知（未取得依据）");
});
test("独立会计cap来自主动输入不是费用上限，首版nullable基准明确", () => {
  const p = apBasisCreate(
    {
      reason: "独立额度",
      accountingCapacity: "500.02",
      basisOriginalId: a,
      recognitionEligibilityReason: "会计用途依据",
      organizationReviewedNotTaxVerified: true,
    },
    { ...parent, capacity: { ...parent.capacity, currentBasis: null } },
  );
  assert.equal(p.accountingCapacity, "500.02");
  assert.equal(p.expectedCurrentBasisId, null);
  assert.equal(p.expectedCurrentBasisVersion, null);
  assert.equal("expenseCapacity" in p, false);
});
test("命令保留超safeinteger的字符串版本与原reason，批准/记账须真实收据", () => {
  const p = apAction(values, detail);
  assert.equal(p.expectedVersion, "9007199254740993");
  assert.equal(p.reason, "原样\n理由");
  assert.equal(apApprove(values, detail).requestedExpiresAt, null);
  assert.equal(apPost(values, detail).approvalId, c);
  assert.throws(() => apPost(values, { ...detail, currentApproval: null }));
  assert.throws(() =>
    apApprove(values, { ...detail, currentSubmission: null }),
  );
});
test("全额反向绑定原真实post/item，前驱为最新attempt而非原确认ID", () => {
  const p = apReversal(
    { reason: "全额会计反向", accountingReasonOriginalId: a },
    detail,
    b,
  );
  assert.equal(p.expectedPredecessorId, c);
  assert.equal(p.originalPostReceiptId, b);
  assert.equal(p.originalOpenItemId, c);
  assert.equal(p.expectedOpenItemVersion, "9007199254740996");
  assert.equal("amount" in p, false);
  assert.equal("accountingDate" in p, false);
});
test("同人别名禁止审签，普通绑定不需要目录治理字段", () => {
  const actor = {
    accountId: a,
    personId: b,
    identityAvailability: "AVAILABLE",
  };
  assert.equal(apIndependent(actor, { accountId: c, personId: b }), false);
  assert.equal(apIndependent(actor, { accountId: c, personId: c }), true);
  assert.equal(
    apIndependent(
      { ...actor, identityAvailability: "UNBOUND" },
      { accountId: c, personId: c },
    ),
    false,
  );
});
test("实际parent owner按scope/person/auth隔离且同正文跨modal刷新保留key，草稿有界20", () => {
  const owner = new EvidenceRequestOwner(),
    drafts = new Map(),
    actor = { accountId: a, personId: b, identityAvailability: "AVAILABLE" },
    id = apRequestIdentity("book/year", "auth", actor),
    d = apDraft(drafts, "create", () => ({ operationId: a, values }));
  const body = apCreate(d.values, parent, supplier, d.operationId);
  owner.key(id, JSON.stringify(body), () => a);
  assert.equal(
    apDraft(drafts, "create", () => null),
    d,
  );
  assert.equal(
    owner.key(
      apRequestIdentity("book/year", "auth", actor),
      JSON.stringify(apCreate(d.values, parent, supplier, d.operationId)),
      () => b,
    ),
    a,
  );
  assert.equal(
    owner.key(
      apRequestIdentity("book/year", "auth", { ...actor, personId: c }),
      JSON.stringify(body),
      () => b,
    ),
    b,
  );
  assert.equal(
    owner.key(
      apRequestIdentity("book/next-year", "auth", actor),
      JSON.stringify(body),
      () => c,
    ),
    c,
  );
  for (let i = 0; i < 100; i++) apDraft(drafts, String(i), () => ({ i }));
  assert.equal(drafts.size, 20);
});
test("日期与期限不随重试重新计算，非法日期不可悄悄归一化", () => {
  assert.equal(apDate("2036-02-29"), "2036-02-29");
  assert.throws(() => apDate("2035-02-29"));
  assert.equal(apExpiry(null), null);
  assert.equal(apExpiry("2036-01-01T12:00:00Z"), "2036-01-01T12:00:00Z");
  assert.throws(() => apExpiry("next week"));
});
