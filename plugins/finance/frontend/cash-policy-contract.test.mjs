import test from "node:test";
import assert from "node:assert/strict";
import {
  policyAssertions,
  policyCreatePayload,
  policyActionPayload,
  policyPublicationLabel,
} from "./cash-policy-contract.ts";
const catalog = {
  standard: "CAS",
  templateId: "CAS-CF-2019-A2",
  dictionaryVersion: "server-v1",
  items: [
    {
      transactionKind: "ORDINARY_PAYROLL",
      businessPurpose: "OPERATING_EMPLOYEE_PAY_GROSS",
      direction: "OUT",
      semanticLeaf: "OP_PAYROLL",
      rowId: "C06",
    },
  ],
};
const assertions = Object.fromEntries(
  policyAssertions.map(([key]) => [key, true]),
);
const form = {
  policyCode: "OPERATING_PAYROLL",
  catalogKind: "ORDINARY_PAYROLL",
  effectiveFrom: "2026-01-01",
  effectiveTo: "2026-12-31",
  policyBasis: "real basis",
  applicabilityStatement:
    "ordinary operating employee cash pay only; no construction/development payroll",
  assertions,
  evidence: [
    {
      originalId: "basis-id",
      purpose: "POLICY_BASIS",
      description: "basis bytes",
      sha256: "untrusted",
    },
    {
      originalId: "support-id",
      purpose: "APPLICABILITY_SUPPORT",
      description: "applicability bytes",
      originalVersion: 999,
    },
  ],
  reason: "new proposal",
};
test("policy proposal uses precise server tuple and real evidence identities, never client leaf or evidence hashes", () => {
  const payload = policyCreatePayload(
    { ...form, rowId: "CUSTOM", semanticLeaf: "OTHER", verified: true },
    catalog,
    2026,
    null,
  );
  assert.equal(payload.businessPurpose, "OPERATING_EMPLOYEE_PAY_GROSS");
  assert.equal(payload.supersedesPolicyId, null);
  assert.deepEqual(payload.evidence[0], {
    originalId: "basis-id",
    purpose: "POLICY_BASIS",
    description: "basis bytes",
  });
  assert.ok(
    !("rowId" in payload) &&
      !("semanticLeaf" in payload) &&
      !("templateId" in payload) &&
      !("verified" in payload),
  );
  assert.throws(() =>
    policyCreatePayload(
      { ...form, catalogKind: "CAPITALIZED_PAYROLL" },
      catalog,
      2026,
      null,
    ),
  );
});
test("all seven boundary assertions must be explicitly true; missing or unchecked cannot be silently accepted", () => {
  for (const [key] of policyAssertions) {
    assert.throws(() =>
      policyCreatePayload(
        { ...form, assertions: { ...assertions, [key]: false } },
        catalog,
        2026,
        null,
      ),
    );
    const missing = { ...assertions };
    delete missing[key];
    assert.throws(() =>
      policyCreatePayload(
        { ...form, assertions: missing },
        catalog,
        2026,
        null,
      ),
    );
  }
});
test("two evidence roles require distinct actual IDs but equal bytes metadata does not itself prove or disprove independence", () => {
  assert.throws(() =>
    policyCreatePayload(
      {
        ...form,
        evidence: [
          form.evidence[0],
          { ...form.evidence[1], originalId: "basis-id" },
        ],
      },
      catalog,
      2026,
      null,
    ),
  );
  assert.throws(() =>
    policyCreatePayload(
      {
        ...form,
        evidence: [
          form.evidence[0],
          { ...form.evidence[1], purpose: "POLICY_BASIS" },
        ],
      },
      catalog,
      2026,
      null,
    ),
  );
  assert.equal(
    policyCreatePayload(
      {
        ...form,
        evidence: form.evidence.map((e) => ({ ...e, sha256: "same-sha" })),
      },
      catalog,
      2026,
      null,
    ).evidence.length,
    2,
  );
});
test("successor points to latest selected proposal even when rejected, preserving same-code dimensions and actual-year dates", () => {
  const predecessor = {
    id: "latest-rejected-revision",
    policyCode: form.policyCode,
    transactionKind: "ORDINARY_PAYROLL",
    businessPurpose: "OPERATING_EMPLOYEE_PAY_GROSS",
    status: "REJECTED",
  };
  assert.equal(
    policyCreatePayload(form, catalog, 2026, predecessor).supersedesPolicyId,
    "latest-rejected-revision",
  );
  assert.throws(() =>
    policyCreatePayload(
      { ...form, policyCode: "OTHER_CODE" },
      catalog,
      2026,
      predecessor,
    ),
  );
  assert.throws(() =>
    policyCreatePayload(
      { ...form, effectiveTo: "2027-01-01" },
      catalog,
      2026,
      null,
    ),
  );
  assert.throws(() =>
    policyCreatePayload(
      { ...form, effectiveFrom: "2026-02-30" },
      catalog,
      2026,
      null,
    ),
  );
});
test("publication eligibility uses server status and pending-retirement result, not today or expired submission history", () => {
  assert.match(
    policyPublicationLabel({
      status: "APPROVED",
      activeForNewUse: true,
      effectiveFrom: "2020-01-01",
      effectiveTo: "2020-01-02",
      submissionExpiresAt: "2020-01-08",
    }),
    /具后续逐笔评估资格/,
  );
  assert.match(
    policyPublicationLabel({ status: "APPROVED", activeForNewUse: false }),
    /冻结/,
  );
  assert.match(
    policyPublicationLabel({ status: "RETIRED", activeForNewUse: false }),
    /不可新用/,
  );
  assert.deepEqual(
    policyActionPayload(
      {
        version: 5,
        contentDigest: "request-or-policy-digest",
        targetContentVersion: 999,
      },
      "review",
    ),
    {
      schemaVersion: "cash-classification-policy/1",
      expectedVersion: 5,
      contentDigest: "request-or-policy-digest",
      reason: "review",
    },
  );
});
