import test from "node:test";
import assert from "node:assert/strict";
import {
  taxNumber,
  taxContent,
  taxCreate,
  taxProposal,
  taxAction,
  taxCanDecide,
  taxConflictReferences,
  taxOriginalVersion,
  taxIdentity,
  boundedTaxDraft,
} from "./tax-entity-rules.ts";
import { EvidenceRequestOwner } from "./evidence-request-state.ts";
const id = "12345678-1234-1234-1234-123456789abc",
  other = "23456789-2345-2345-2345-23456789abcd";
const evidence = {
  yearId: id,
  originalId: other,
  expectedVersion: "9007199254740993",
  expectedSha256: "a".repeat(64),
  sourceType: "ELECTRONIC_ORIGINAL",
  originalLocation: null,
};
const values = {
  displayName: "真实组织",
  entityKind: "LEGAL_ENTITY",
  registrationTypeDeclaration: "组织登记记录",
  residencyDeclaration: "UNKNOWN",
  organizationVerificationBasis: "组织材料核对\n第二行",
  sourceType: "ELECTRONIC_ORIGINAL",
  singleTaxpayerConfirmed: true,
  allKnownRegistrationIdentifiersDeclared: true,
  organizationReviewedNotGovernmentVerified: true,
  reason: "真实治理依据",
  taxNumber: " org-cn/20 ",
};
const identity = {
  accountId: id,
  personId: other,
  identityAvailability: "AVAILABLE",
};
const revision = {
  taxEntity: {
    id,
    version: "9007199254740995",
    latestRevisionId: other,
    effectiveStatus: "INACTIVE",
    approvedRevision: { id, contentDigest: "b".repeat(64) },
  },
  revision: {
    id: other,
    version: "9007199254740994",
    contentDigest: "c".repeat(64),
    preparedBy: { accountId: "maker", personId: "maker-person" },
  },
};
test("tax identity canonicalizes ASCII only, never expands unicode or accepts control/placeholder syntax", () => {
  assert.equal(taxNumber(values.taxNumber), "ORG-CN/20");
  for (const v of ["ß123", "org\n123", " a b ", "", null])
    assert.throws(() => taxNumber(v));
});
test("profile explicitly whitelists nullable fields and real evidence versions without returning form/server-only metadata", () => {
  const payload = taxCreate(
    {
      ...values,
      filename: "spoof",
      governmentVerified: true,
      taxAmount: "0.00",
    },
    id,
    evidence,
  );
  assert.deepEqual(Object.keys(payload).sort(), [
    "content",
    "identity",
    "operationId",
    "reason",
    "schemaVersion",
  ]);
  assert.equal(
    payload.content.registrationEvidence.expectedVersion,
    "9007199254740993",
  );
  assert.equal(payload.content.establishedOn, null);
  assert.equal(payload.content.authorityCodeDeclaration, null);
  assert.equal(payload.content.residencyDeclaration, "UNKNOWN");
  assert.equal("filename" in payload.content, false);
  assert.equal("taxAmount" in payload.content, false);
});
test("three declarations are active requirements and paper scans require actual paper location", () => {
  for (const name of [
    "singleTaxpayerConfirmed",
    "allKnownRegistrationIdentifiersDeclared",
    "organizationReviewedNotGovernmentVerified",
  ])
    assert.throws(() => taxContent({ ...values, [name]: false }, evidence));
  assert.throws(() =>
    taxContent({ ...values, sourceType: "PAPER_SCAN" }, evidence),
  );
  assert.equal(
    taxContent(
      { ...values, sourceType: "PAPER_SCAN", originalLocation: "档案柜20" },
      evidence,
    ).registrationEvidence.originalLocation,
    "档案柜20",
  );
  assert.throws(() =>
    taxContent({ ...values, establishedOn: "2034-02-30" }, evidence),
  );
});
test("revision preserves exact latest/approved baselines, permits independent inactive restoration and rejects unpublished inactive or historical latest", () => {
  const p = taxProposal(
    { ...values, desiredStatus: "ACTIVE" },
    revision,
    evidence,
  );
  assert.equal(p.expectedEntityVersion, "9007199254740995");
  assert.equal(p.expectedLatestRevisionVersion, "9007199254740994");
  assert.equal(p.expectedApprovedRevisionId, id);
  assert.throws(() =>
    taxProposal(
      { ...values, desiredStatus: "INACTIVE" },
      {
        ...revision,
        taxEntity: { ...revision.taxEntity, effectiveStatus: "UNPUBLISHED" },
      },
      evidence,
    ),
  );
  assert.throws(() =>
    taxProposal(
      { ...values, desiredStatus: "ACTIVE" },
      {
        ...revision,
        taxEntity: { ...revision.taxEntity, latestRevisionId: id },
      },
      evidence,
    ),
  );
  assert.equal(taxAction(values, revision).expectedVersion, "9007199254740994");
});
test("independence compares both ordinary person and account, not governance assignment", () => {
  assert.equal(taxCanDecide(identity, revision, "REVIEWER", true), true);
  assert.equal(
    taxCanDecide(
      { ...identity, accountId: "alias", personId: "maker-person" },
      revision,
      "OWNER",
      true,
    ),
    false,
  );
  assert.equal(
    taxCanDecide(
      { ...identity, identityAvailability: "UNBOUND", personId: null },
      revision,
      "OWNER",
      true,
    ),
    false,
  );
  assert.equal(taxCanDecide(identity, revision, "MAKER", true), false);
});
test("authorized conflict refs need exact code/status/order/row/canonical UUID and cannot be inferred from human text", () => {
  const issues = [
    { row: 1, field: "existingTaxEntityId", reason: id },
    { row: 1, field: "governanceHomeBookId", reason: other },
  ];
  assert.deepEqual(
    taxConflictReferences(409, "TAX_ENTITY_IDENTITY_EXISTS", issues),
    { existingTaxEntityId: id, governanceHomeBookId: other },
  );
  for (const [status, code, items] of [
    [403, "TAX_ENTITY_IDENTITY_EXISTS", issues],
    [409, "VERSION_CONFLICT", issues],
    [409, "TAX_ENTITY_IDENTITY_EXISTS", [...issues].reverse()],
    [
      409,
      "TAX_ENTITY_IDENTITY_EXISTS",
      [{ ...issues[0], reason: id.toUpperCase() }, issues[1]],
    ],
    [409, "TAX_ENTITY_IDENTITY_EXISTS", [{ ...issues[0], row: 2 }, issues[1]]],
    [
      409,
      "TAX_ENTITY_IDENTITY_EXISTS",
      [{ ...issues[0], extra: true }, issues[1]],
    ],
  ])
    assert.equal(taxConflictReferences(status, code, items), null);
});
test("book/account/person/auth owner preserves unknown-key across form lifecycle and rejects actual person change; drafts remain bounded", () => {
  const owner = new EvidenceRequestOwner(),
    a = taxIdentity("book", "auth", identity),
    b = taxIdentity("book", "auth", { ...identity, personId: "new-person" });
  let n = 0;
  const key = () => String(++n);
  const fingerprint = JSON.stringify([
    "route",
    taxCreate(values, id, evidence),
  ]);
  assert.equal(
    owner.key(a, fingerprint, key),
    owner.key(taxIdentity("book", "auth", identity), fingerprint, key),
  );
  assert.notEqual(
    owner.key(b, fingerprint, key),
    owner.key(a, fingerprint, key),
  );
  const drafts = new Map();
  for (let i = 0; i < 100; i++)
    boundedTaxDraft(drafts, String(i), { operationId: id });
  assert.equal(drafts.size, 20);
  assert.equal(drafts.has("0"), false);
});
test("original versions preserve large decimal strings and refuse unknown/rounded metadata", () => {
  assert.equal(taxOriginalVersion("9007199254740993"), "9007199254740993");
  assert.equal(taxOriginalVersion(2), "2");
  for (const v of [null, undefined, 9007199254740992, "01", "1.0", -1])
    assert.throws(() => taxOriginalVersion(v));
});
