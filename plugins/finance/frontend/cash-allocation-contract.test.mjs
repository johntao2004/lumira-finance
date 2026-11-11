import test from "node:test";
import assert from "node:assert/strict";
import {
  allocationCreatePayload,
  expectedAllocationParent,
  sameAllocationParent,
  allocationActionPayload,
  allocationReleasePayload,
  allocationDecisionPayload,
  allocationReplacementPayload,
  allocationIndependent,
  allocationEffectLabel,
  allocationPolicyEligible,
} from "./cash-allocation-contract.ts";
const parent = {
  reviewId: "review",
  expectedReviewVersion: 3,
  reviewContentDigest: "a".repeat(64),
  activeReviewUsageId: "actual-review-usage",
  claimId: "actual-claim",
  claimGeneration: 5,
  claimContentDigest: "b".repeat(64),
  sourceMatchGeneration: 5,
  rawRegistrationId: "permanently-void-raw",
  rawContentDigest: "c".repeat(64),
  claimKind: "MATCH_CORRECTION",
  occurredDate: "2026-04-15",
  direction: "IN",
  currency: "CNY",
  grossAmount: "100.00000000",
  transactionKind: "ORDINARY_CAPITAL_RECEIPT",
};
const policy = {
  id: "real-policy",
  version: 4,
  contentDigest: "d".repeat(64),
  status: "APPROVED",
  activeForNewUse: true,
  transactionKind: "ORDINARY_CAPITAL_RECEIPT",
  businessPurpose: "EQUITY_CAPITAL_CASH_GROSS",
  direction: "IN",
  effectiveFrom: "2026-04-01",
  effectiveTo: "2026-04-30",
};
const line = (componentKey, amount, original) => ({
  componentKey,
  amount,
  policyId: policy.id,
  purposeEvidenceOriginalId: original,
  description: "实际组成用途证据，不按科目猜",
  ordinaryPurposeConfirmed: true,
});
const form = {
  lines: [
    line("actual-A", "60.00", "purpose-A"),
    line("actual-B", "40.00", "purpose-B"),
  ],
  reason: "人工复核后普通全额组成分配",
};
const old = {
  id: "old",
  version: 5,
  contentDigest: "e".repeat(64),
  parentSnapshot: parent,
  status: "APPROVED",
  currentEffective: true,
  activeDependencyId: "old-real-dependency",
  pendingRelease: null,
  pendingReplacement: null,
  supersedesAllocationId: null,
};
const next = {
  id: "new",
  version: 2,
  contentDigest: "f".repeat(64),
  parentSnapshot: parent,
  status: "SUBMITTED",
  currentEffective: false,
  activeDependencyId: null,
  pendingRelease: null,
  pendingReplacement: null,
  supersedesAllocationId: "old",
};
test("100=60+40 same-policy components remain distinct and client rows, leaves, hashes and inferred account fields never reach server", () => {
  const payload = allocationCreatePayload(
    {
      ...form,
      reportRow: "forged",
      lines: form.lines.map((l) => ({
        ...l,
        semanticLeaf: "OTHER",
        rowId: "fake",
        expectedPolicyVersion: 999,
        purposeEvidenceSha256: "untrusted",
      })),
    },
    parent,
    [policy],
    null,
  );
  assert.equal(payload.lines.length, 2);
  assert.deepEqual(
    payload.lines.map((l) => l.amount),
    ["60.00", "40.00"],
  );
  assert.equal(payload.lines[0].expectedPolicyVersion, 4);
  assert.equal(payload.lines[0].businessPurpose, "EQUITY_CAPITAL_CASH_GROSS");
  assert.equal(payload.supersedesAllocationId, null);
  assert.equal(payload.expectedSupersededVersion, null);
  assert.equal(payload.supersededContentDigest, null);
  for (const field of ["rowId", "semanticLeaf", "purposeEvidenceSha256"])
    assert.ok(!(field in payload.lines[0]));
  assert.ok(!("reportRow" in payload));
  assert.equal(Object.keys(payload.expectedParent).length, 8);
});
test("CNY magnitude and full-parent totals are exact beyond Number safe precision", () => {
  const bigParent = { ...parent, grossAmount: "10000000000000000000.01000000" };
  const bigForm = {
    ...form,
    lines: [
      line("capital", "10000000000000000000.00", "a"),
      line("last-cent", "0.01", "b"),
    ],
  };
  assert.equal(
    allocationCreatePayload(bigForm, bigParent, [policy], null).lines[1].amount,
    "0.01",
  );
  assert.throws(
    () =>
      allocationCreatePayload(
        {
          ...bigForm,
          lines: [
            line("capital", "10000000000000000000.00", "a"),
            line("last-cent", "0.02", "b"),
          ],
        },
        bigParent,
        [policy],
        null,
      ),
    /精确等于/,
  );
  for (const value of ["99.99", "100.01"])
    assert.throws(
      () =>
        allocationCreatePayload(
          { ...form, lines: [line("part", value, "a")] },
          parent,
          [policy],
          null,
        ),
      /精确等于/,
    );
});
test("excess fractional precision, negative, zero, overflow and invented residual components cannot be rounded into validity", () => {
  for (const value of [
    "1.000",
    "1.001",
    "1.005",
    "-1",
    "0",
    "100000000000000000000.00",
    "1e2",
  ])
    assert.throws(() =>
      allocationCreatePayload(
        { ...form, lines: [line("part", value, "a")] },
        parent,
        [policy],
        null,
      ),
    );
  assert.throws(
    () =>
      allocationCreatePayload(
        {
          ...form,
          lines: [line("duplicate", "60", "a"), line("duplicate", "40", "b")],
        },
        parent,
        [policy],
        null,
      ),
    /唯一/,
  );
  assert.throws(
    () =>
      allocationCreatePayload({ ...form, lines: [] }, parent, [policy], null),
    /1至20/,
  );
  assert.throws(
    () =>
      allocationCreatePayload(
        {
          ...form,
          lines: Array.from({ length: 21 }, (_, i) =>
            line(`actual-${i}`, "1", `evidence-${i}`),
          ),
        },
        parent,
        [policy],
        null,
      ),
    /1至20/,
  );
});
test("actual cash date and typed ordinary source purpose govern candidates, not today or account names", () => {
  assert.equal(allocationPolicyEligible(policy, parent), true);
  for (const changed of [
    { status: "RETIRED" },
    { activeForNewUse: false },
    { direction: "OUT" },
    { transactionKind: "ORDINARY_PAYROLL" },
    { effectiveTo: "2026-04-14" },
    { effectiveFrom: "2026-04-16" },
  ]) {
    assert.equal(
      allocationPolicyEligible({ ...policy, ...changed }, parent),
      false,
    );
    assert.throws(
      () =>
        allocationCreatePayload(
          form,
          parent,
          [{ ...policy, ...changed }],
          null,
        ),
      /须选择/,
    );
  }
});
test("positive amounts require per-component active purpose confirmation and actual original identity", () => {
  for (const ordinaryPurposeConfirmed of [false, undefined, 1, "true"])
    assert.throws(
      () =>
        allocationCreatePayload(
          {
            ...form,
            lines: form.lines.map((l) => ({ ...l, ordinaryPurposeConfirmed })),
          },
          parent,
          [policy],
          null,
        ),
      /主动确认/,
    );
  assert.throws(
    () =>
      allocationCreatePayload(
        {
          ...form,
          lines: form.lines.map((l) => ({
            ...l,
            purposeEvidenceOriginalId: "",
          })),
        },
        parent,
        [policy],
        null,
      ),
    /实际用途原件/,
  );
});
test("frozen expectedParent binds exact review usage, digest and match generation without adopting newer parent state for rejection", () => {
  const action = allocationActionPayload(
    next,
    "直接独立驳回未提交或已提交的过期提案",
  );
  assert.deepEqual(action.expectedParent, expectedAllocationParent(parent));
  for (const changes of [
    { activeReviewUsageId: "new-use" },
    { expectedReviewVersion: 4 },
    { sourceMatchGeneration: 6 },
    { claimContentDigest: "9".repeat(64) },
    { reviewId: "other-parent" },
  ])
    assert.equal(
      sameAllocationParent(parent, { ...parent, ...changes }),
      false,
    );
  assert.equal(action.expectedVersion, next.version);
});
test("replacement draft and request bind actual same-parent old dependency/new candidate instead of ordinary approval or arbitrary effect IDs", () => {
  const draft = allocationCreatePayload(form, parent, [policy], old);
  assert.equal(draft.supersedesAllocationId, old.id);
  assert.equal(draft.expectedSupersededVersion, old.version);
  const request = allocationReplacementPayload(old, next, "真实原子替换请求");
  assert.equal(request.oldDependencyId, "old-real-dependency");
  assert.equal(request.oldAllocationId, "old");
  assert.equal(request.newAllocationId, "new");
  for (const changed of [
    { status: "DRAFT" },
    { supersedesAllocationId: "different-old" },
    { parentSnapshot: { ...parent, reviewId: "other-parent" } },
    { pendingReplacement: { id: "pending" } },
  ])
    assert.throws(
      () => allocationReplacementPayload(old, { ...next, ...changed }, "替换"),
      /原子替换/,
    );
  for (const changed of [
    { currentEffective: false },
    { pendingRelease: { id: "pending" } },
    { activeDependencyId: null },
  ])
    assert.throws(
      () => allocationReplacementPayload({ ...old, ...changed }, next, "替换"),
      /原子替换/,
    );
});
test("release only binds actual current allocation dependency; subsequent request decisions bind request version/digest", () => {
  assert.equal(
    allocationReleasePayload(old, "负责人handoff释放").activeDependencyId,
    old.activeDependencyId,
  );
  assert.throws(
    () =>
      allocationReleasePayload({ ...old, activeDependencyId: null }, "释放"),
    /没有可释放/,
  );
  assert.deepEqual(
    allocationDecisionPayload(
      { version: 3, contentDigest: "1".repeat(64), fakeEffect: "forged" },
      "独立审签",
    ),
    {
      schemaVersion: "cash-allocation/1",
      expectedVersion: 3,
      contentDigest: "1".repeat(64),
      reason: "独立审签",
    },
  );
});
test("replacement approver excludes raw creator, old/new makers and requester; historical parent-review maker is not an extra unagreed exclusion", () => {
  for (const actor of [
    undefined,
    "raw-maker",
    "old-maker",
    "new-maker",
    "request-maker",
  ])
    assert.equal(
      allocationIndependent(
        actor,
        "raw-maker",
        "old-maker",
        "new-maker",
        "request-maker",
      ),
      false,
    );
  assert.equal(
    allocationIndependent(
      "parent-review-maker",
      "raw-maker",
      "old-maker",
      "new-maker",
      "request-maker",
    ),
    true,
  );
  assert.equal(
    allocationIndependent("reviewer", undefined, "old-maker"),
    false,
  );
});
test("pending release/replacement preserves effect and superseded history survives successor's later release", () => {
  assert.match(
    allocationEffectLabel({ ...old, pendingReplacement: { id: "request" } }),
    /仍有效.*冻结/,
  );
  assert.match(
    allocationEffectLabel({
      ...old,
      status: "SUPERSEDED",
      currentEffective: false,
    }),
    /不据其后续状态抹除/,
  );
  assert.match(
    allocationEffectLabel({
      ...next,
      status: "RELEASED",
      currentEffective: false,
    }),
    /父级人工复核使用关系与匹配声明未释放/,
  );
});
