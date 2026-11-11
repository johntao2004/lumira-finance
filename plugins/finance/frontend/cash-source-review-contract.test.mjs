import test from "node:test";
import assert from "node:assert/strict";
import {
  sourceReviewChecklist,
  sourceReviewCreatePayload,
  sourceReviewActionPayload,
  sourceReviewReleasePayload,
  sourceReviewReleaseAction,
  sameReviewClaim,
  sourceReviewIndependent,
  sourceReviewUseLabel,
} from "./cash-source-review-contract.ts";
const claim = {
  rawRegistrationId: "raw",
  rawContentDigest: "a".repeat(64),
  claimId: "claim",
  claimKind: "MATCH_CORRECTION",
  claimGeneration: 2,
  claimContentDigest: "b".repeat(64),
  sourceMatchGeneration: 2,
  rawStatus: "VOIDED",
  sourceIdentity: { preparedBy: "raw-maker" },
};
const form = {
  businessPurposeStatement: "实际普通权益资本全额投入的用途证据",
  factReviewStatement:
    "人工检查来源全文件行、范围工具及已记账现金行，未凭科目名推断",
  checklist: Object.fromEntries(
    sourceReviewChecklist.map(([key]) => [key, true]),
  ),
  evidence: [
    {
      originalId: "actual-original",
      purpose: "BUSINESS_PURPOSE_SUPPORT",
      description: "实际保留的业务文件",
      sha256: "client-not-authority",
      originalVersion: 999,
    },
  ],
  reason: "提交人工复核",
};
const review = {
  id: "review",
  version: 4,
  contentDigest: "c".repeat(64),
  preparedBy: "review-maker",
  claimSnapshot: claim,
  activeUsageId: "usage",
  status: "APPROVED",
  currentEffective: true,
  pendingRelease: null,
};
test("effective correction of a VOID raw is reviewable without mutating raw facts or forwarding client hashes", () => {
  const payload = sourceReviewCreatePayload(
    { ...form, bankAuthenticityVerified: true, policyId: "no-policy" },
    claim,
  );
  assert.equal(payload.expectedClaim.claimKind, "MATCH_CORRECTION");
  assert.equal(payload.expectedClaim.rawRegistrationId, "raw");
  assert.equal(payload.conclusion, "SUPPORTED_FOR_PREPARATION");
  assert.deepEqual(payload.evidence, [
    {
      originalId: "actual-original",
      purpose: "BUSINESS_PURPOSE_SUPPORT",
      description: "实际保留的业务文件",
    },
  ]);
  assert.ok(!("rawStatus" in payload.expectedClaim));
  assert.ok(!("policyId" in payload));
  assert.ok(!("bankAuthenticityVerified" in payload));
});
test("every factual declaration must be actively true, not merely truthy or omitted", () => {
  for (const [key] of sourceReviewChecklist) {
    for (const value of [false, undefined, "true", 1]) {
      assert.throws(
        () =>
          sourceReviewCreatePayload(
            { ...form, checklist: { ...form.checklist, [key]: value } },
            claim,
          ),
        /主动确认/,
      );
    }
  }
});
test("a factual corroboration alone or duplicate original cannot substitute concrete purpose evidence", () => {
  assert.throws(
    () =>
      sourceReviewCreatePayload(
        {
          ...form,
          evidence: [{ ...form.evidence[0], purpose: "FACT_CORROBORATION" }],
        },
        claim,
      ),
    /业务用途/,
  );
  assert.throws(
    () =>
      sourceReviewCreatePayload(
        { ...form, evidence: [...form.evidence, ...form.evidence] },
        claim,
      ),
    /不重复/,
  );
});
test("claim comparison binds source generation and digest as well as typed claim identity", () => {
  assert.equal(
    sameReviewClaim(claim, { ...claim, rawStatus: "REGISTERED" }),
    true,
  );
  for (const change of [
    { sourceMatchGeneration: 3 },
    { claimGeneration: 3 },
    { claimContentDigest: "d".repeat(64) },
    { rawContentDigest: "e".repeat(64) },
    { claimKind: "RAW_REGISTRATION" },
    { claimId: "other" },
  ])
    assert.equal(sameReviewClaim(claim, { ...claim, ...change }), false);
});
test("stale cancellation/rejection actions bind proposal snapshot rather than replacement current claim", () => {
  const payload = sourceReviewActionPayload(review, "拒绝过期提案");
  assert.equal(payload.expectedVersion, 4);
  assert.equal(payload.expectedClaim.sourceMatchGeneration, 2);
  assert.equal(payload.expectedClaim.claimId, "claim");
  assert.equal(payload.contentDigest, review.contentDigest);
});
test("review and release approvers exclude raw maker, review maker and request maker independently", () => {
  for (const actor of [undefined, "raw-maker", "review-maker", "request-maker"])
    assert.equal(
      sourceReviewIndependent(actor, review, "request-maker"),
      false,
    );
  assert.equal(
    sourceReviewIndependent("independent-reviewer", review, "request-maker"),
    true,
  );
});
test("release binds actual active usage while request decisions bind their own immutable version", () => {
  assert.equal(
    sourceReviewReleasePayload(review, "handoff释放").activeUsageId,
    "usage",
  );
  assert.throws(
    () =>
      sourceReviewReleasePayload({ ...review, activeUsageId: null }, "释放"),
    /没有可释放/,
  );
  const payload = sourceReviewReleaseAction(
    {
      id: "request",
      version: 2,
      contentDigest: "f".repeat(64),
      usageId: "server-usage",
    },
    "独立批准释放",
  );
  assert.deepEqual(payload, {
    schemaVersion: "cash-source-review/1",
    expectedVersion: 2,
    contentDigest: "f".repeat(64),
    reason: "独立批准释放",
  });
});
test("pending release retains active usage, released history never implies claim release or bank verification", () => {
  assert.match(
    sourceReviewUseLabel({ ...review, pendingRelease: { id: "pending" } }),
    /仍占用.*冻结/,
  );
  assert.match(
    sourceReviewUseLabel({
      ...review,
      status: "RELEASED",
      currentEffective: false,
    }),
    /现金匹配声明未释放/,
  );
  assert.match(
    sourceReviewUseLabel({
      ...review,
      status: "DRAFT",
      currentEffective: false,
    }),
    /尚无已批准/,
  );
});
