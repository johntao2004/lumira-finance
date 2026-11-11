import test from "node:test";
import assert from "node:assert/strict";
import {
  evidenceMoney,
  evidencePositive,
  evidenceBasisAmounts,
  evidencePreview,
  evidenceSourceLocation,
  evidenceIndependent,
  evidenceOwns,
  evidenceActionAllowed,
  evidenceReviewFields,
  evidenceAllocationMatches,
  evidenceReadMatches,
} from "./evidence-usage-rules.ts";
test("an explicit refresh immediately masks old counts and objects even before its clearing effect runs", () => {
  const stored = { path: "/current-resource", revision: 4 };
  assert.equal(evidenceReadMatches("/current-resource", 4, stored), true);
  assert.equal(evidenceReadMatches("/current-resource", 5, stored), false);
  assert.equal(evidenceReadMatches("/other-resource", 4, stored), false);
  assert.equal(evidenceReadMatches("/current-resource", 4, null), false);
});
test("changing the selected document masks an old allocation synchronously before a cleanup effect can run", () => {
  const old = { id: "allocation-old", documentId: "doc-old" };
  assert.equal(
    evidenceAllocationMatches("allocation-old", "doc-new", old),
    false,
  );
  assert.equal(
    evidenceAllocationMatches("allocation-new", "doc-old", old),
    false,
  );
  assert.equal(
    evidenceAllocationMatches("allocation-old", "doc-old", old),
    true,
  );
  assert.equal(evidenceAllocationMatches("allocation-old", "", old), true);
  assert.equal(
    evidenceAllocationMatches("allocation-old", "doc-old", null),
    false,
  );
});
test("basis proposal requires three active declarations and whitelists trusted-reference fields without sending client hashes or read-only bucket state", () => {
  const fields = {
    issuerKey: "ORG-EXACT",
    externalIdentityKey: "INVOICE-EXACT",
    faceGross: "1000.00",
    expenseCapacity: "800.00",
    basisOriginalId: "10000000-0000-0000-0000-000000000001",
    reason: "Organization record independently reviewed",
    singleFileSingleDocumentConfirmed: true,
    allKnownDocumentIdentifiersDeclared: true,
    sameDocumentConfirmed: true,
    sourceType: "PAPER_SCAN",
    originalLocation: "Archive A",
    sha256: "fake",
    available: "9999",
    reviewedBy: "fake",
  };
  const payload = evidenceReviewFields(fields);
  assert.equal(payload.sourceType, "PAPER_SCAN");
  assert.equal(payload.originalLocation, "Archive A");
  for (const name of ["sha256", "available", "reviewedBy"])
    assert.equal(name in payload, false);
  for (const name of [
    "singleFileSingleDocumentConfirmed",
    "allKnownDocumentIdentifiersDeclared",
    "sameDocumentConfirmed",
  ])
    assert.throws(() => evidenceReviewFields({ ...fields, [name]: false }));
});
test("unknown capacity stays unknown, while current exact availability and proposed cents never use Number or clamp an excess to zero", () => {
  assert.equal(evidenceMoney(null), "未知");
  assert.equal(evidenceMoney("0.00000000"), "0.00000000");
  assert.equal(evidencePreview(null, "100.00"), "未知，不能估算");
  assert.equal(
    evidencePreview("10000000000000000000.01000000", "10000000000000000000.00"),
    "0.01000000",
  );
  assert.equal(evidencePreview("100.00000000", "100.01"), "-0.01000000");
});
test("face and expense capacity are separate, cap may be zero but cannot exceed face; invalid precision is rejected without rounding", () => {
  assert.deepEqual(evidenceBasisAmounts("1000.00", "800.00"), {
    faceGross: "1000.00",
    expenseCapacity: "800.00",
  });
  assert.equal(evidenceBasisAmounts("1000", "0").expenseCapacity, "0");
  for (const args of [
    ["100", "100.01"],
    ["0", "0"],
    ["1.005", "1"],
    ["100", "1.000"],
  ])
    assert.throws(() => evidenceBasisAmounts(...args));
  for (const value of [12.34, "0", "-1", "1.001"])
    assert.throws(() => evidencePositive(value));
});
test("paper source requires a real location; electronic source can explicitly retain null and neither attests authenticity", () => {
  assert.deepEqual(evidenceSourceLocation("ELECTRONIC_ORIGINAL", null), {
    sourceType: "ELECTRONIC_ORIGINAL",
    originalLocation: null,
  });
  assert.equal(
    evidenceSourceLocation("PAPER_SCAN", "Archive A/2").originalLocation,
    "Archive A/2",
  );
  assert.throws(() => evidenceSourceLocation("PAPER_SCAN", " "));
  assert.throws(() => evidenceSourceLocation("UNKNOWN", null));
});
test("closed or correction-frozen new use does not disable authorized exits and partial reversals; platform permission is still required", () => {
  const actions = [
    "submit",
    "confirm",
    "withdraw",
    "reject",
    "admin-terminate",
    "cancel",
    "request-reverse",
    "approve-reverse",
    "reject-review",
  ];
  assert.equal(evidenceActionAllowed(actions, "submit", false, true), false);
  assert.equal(evidenceActionAllowed(actions, "confirm", false, true), false);
  for (const action of actions.slice(2))
    assert.equal(evidenceActionAllowed(actions, action, false, true), true);
  assert.equal(evidenceActionAllowed(actions, "withdraw", false, false), false);
  assert.equal(
    evidenceActionAllowed(actions, "create-document", true, true),
    false,
  );
});
test("reverse independence excludes only its requester person, not a historical expense maker; creator-only ownership remains account-specific", () => {
  const requester = { personId: "requester" },
    oldMaker = { accountId: "old-maker", personId: "maker" };
  assert.equal(evidenceIndependent(oldMaker, requester), true);
  assert.equal(
    evidenceIndependent({ personId: "requester" }, requester),
    false,
  );
  assert.equal(evidenceIndependent({ personId: null }, requester), false);
  assert.equal(
    evidenceOwns({ accountId: "alias" }, { accountId: "maker" }),
    false,
  );
});
