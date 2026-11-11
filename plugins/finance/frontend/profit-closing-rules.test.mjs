import test from "node:test";
import assert from "node:assert/strict";
import {
  closingNormalLane,
  closingDate,
  closingTotals,
  closingCreatePayload,
  closingCommandAllowed,
  closingActionPayload,
  closingSameRun,
  closingResourceMatches,
  closingWriteScopeDenied,
  closingDraftKey,
} from "./profit-closing-rules.ts";
const actor = {
  accountId: "review-account",
  personId: "review-person",
  identityAvailability: "AVAILABLE",
};
const identity = { accountId: "maker-account", personId: "maker-person" };
const run = {
  id: "run",
  journalId: "journal",
  version: "9007199254740993",
  journalVersion: "9007199254740994",
  contentVersion: "2",
  contentDigest: "a".repeat(64),
  sourceDigest: "b".repeat(64),
  preparedBy: identity,
  lastEditor: identity,
  currentActor: actor,
  sourceFresh: true,
  status: "APPROVED",
  currentApproval: {
    id: "approval",
    eligibilityForNewPost: "FRESH",
    expiresAt: "2099-01-01T00:00:00Z",
  },
  allowedActions: [
    "approve",
    "post",
    "reject",
    "admin-terminate",
    "submit",
    "withdraw",
    "cancel",
  ],
};
const permission = {
  active: true,
  newUseAllowed: true,
  canMake: true,
  canApprove: true,
  canPost: true,
};
test("raw closing/reversal/unknown journal never enters NORMAL14 lane; month-end and year-end use actual calendar dates", () => {
  assert.equal(closingNormalLane("NORMAL"), true);
  for (const value of [
    "PROFIT_CLOSE",
    "YEAR_END",
    "REVERSAL",
    "SYSTEM",
    undefined,
  ])
    assert.equal(closingNormalLane(value), false);
  assert.equal(closingDate("2028-02-29", "PROFIT_LOSS"), true);
  assert.equal(closingDate("2029-02-29", "PROFIT_LOSS"), false);
  assert.equal(closingDate("2028-12-30", "YEAR_END"), false);
  assert.equal(closingDate("2028-12-31", "YEAR_END"), true);
  assert.equal(closingDate("2028-07-15", "TRANSFER_REVERSAL"), false);
});
test("generated exact8 totals preserve large integer cents and fractional8 without Number or silent rounding", () => {
  const lines = [
    { debit: "10000000000000000000.01000001", credit: "0.00000000" },
    { debit: "0", credit: "10000000000000000000.01000001" },
  ];
  assert.deepEqual(closingTotals(lines), {
    debit: "10000000000000000000.01000001",
    credit: "10000000000000000000.01000001",
    balanced: true,
  });
  assert.equal(
    closingTotals([{ debit: "0.00000001", credit: "0" }]).balanced,
    false,
  );
  assert.throws(() => closingTotals([{ debit: "-1", credit: "0" }]));
});
test("stale or expired approval blocks new effect but independent legal exit does not require old maker currently employed", () => {
  const stale = {
    ...run,
    sourceFresh: false,
    currentApproval: {
      ...run.currentApproval,
      eligibilityForNewPost: "EXPIRED",
    },
  };
  assert.equal(
    closingCommandAllowed("post", stale, permission, Date.now()),
    false,
  );
  assert.equal(
    closingCommandAllowed("approve", stale, permission, Date.now()),
    false,
  );
  assert.equal(
    closingCommandAllowed("reject", stale, permission, Date.now()),
    true,
  );
  assert.equal(
    closingCommandAllowed(
      "admin-terminate",
      { ...stale, status: "DRAFT" },
      permission,
      Date.now(),
    ),
    true,
  );
  assert.equal(
    closingCommandAllowed(
      "reject",
      stale,
      { ...permission, active: false },
      Date.now(),
    ),
    false,
  );
});
test("same-person alias cannot approve; already independent valid approval does not require third poster", () => {
  const alias = {
    ...run,
    currentActor: { ...actor, personId: "maker-person" },
  };
  assert.equal(
    closingCommandAllowed(
      "approve",
      { ...alias, status: "SUBMITTED" },
      permission,
      Date.now(),
    ),
    false,
  );
  assert.equal(
    closingCommandAllowed("post", alias, permission, Date.now()),
    true,
  );
  assert.equal(
    closingCommandAllowed(
      "approve",
      { ...run, status: "SUBMITTED" },
      { ...permission, canApprove: false },
      Date.now(),
    ),
    false,
  );
  assert.equal(
    closingCommandAllowed("cancel", run, permission, Date.now()),
    false,
  );
});
test("commands keep large version strings and whitelist frozen content rather than returning server readonly fields", () => {
  const payload = closingActionPayload(
    { ...run, evil: "untrusted" },
    "real reason",
  );
  assert.equal(payload.expectedVersion, "9007199254740993");
  assert.deepEqual(
    Object.keys(payload).sort(),
    [
      "schemaVersion",
      "expectedVersion",
      "expectedContentVersion",
      "contentDigest",
      "reason",
    ].sort(),
  );
  assert.equal(
    closingSameRun(run, { ...run, journalVersion: "9007199254740995" }),
    false,
  );
});
const source = {
  basis: { includedPostingSeq: "9007199254740993" },
  sourceDigest: "b".repeat(64),
  latestPostedRunId: "prior-posted",
};
const preview = {
  outcome: "PRODUCIBLE",
  producerKind: "PROFIT_LOSS",
  cutoff: "2028-12-31",
  reversesRunId: null,
  source,
  generatedLines: [
    { debit: "1", credit: "0" },
    { debit: "0", credit: "1" },
  ],
  generatedDigest: "c".repeat(64),
  allowedActions: ["create-run"],
};
const values = {
  operationId: "12345678-1234-1234-1234-123456789abc",
  reason: "explicit real run",
};
test("NOOP/BLOCKED cannot generate journal; new residual binds actual predecessor and exact source only", () => {
  for (const outcome of ["NOOP", "BLOCKED"])
    assert.throws(() =>
      closingCreatePayload({ ...preview, outcome }, values, null),
    );
  const payload = closingCreatePayload(
    { ...preview, currentActor: actor, unknownReadonly: "never send" },
    values,
    null,
  );
  assert.equal(payload.predecessorPostedRunId, "prior-posted");
  assert.equal(payload.expectedSourceDigest, source.sourceDigest);
  assert.equal(payload.supersedesCancelledAttemptId, null);
  assert.equal("currentActor" in payload, false);
  assert.equal("unknownReadonly" in payload, false);
});
test("rebuilding cancelled attempt is explicit exact stage/cutoff/reversal+version, never revival of original operation", () => {
  const cancelled = {
    id: "cancelled",
    version: "3",
    status: "CANCELLED",
    producerKind: "PROFIT_LOSS",
    cutoff: "2028-12-31",
    reversesRunId: null,
  };
  const payload = closingCreatePayload(preview, values, cancelled);
  assert.equal(payload.supersedesCancelledAttemptId, "cancelled");
  assert.equal(payload.expectedSupersededVersion, "3");
  assert.throws(() =>
    closingCreatePayload(preview, values, { ...cancelled, status: "DRAFT" }),
  );
  assert.throws(() =>
    closingCreatePayload(preview, values, {
      ...cancelled,
      cutoff: "2028-11-30",
    }),
  );
  assert.throws(() =>
    closingCreatePayload(
      preview,
      { ...values, operationId: values.operationId.toUpperCase() },
      null,
    ),
  );
});

test("resource read identity masks old actor immediately, and only genuine write scope403 clears retry state", () => {
  const stored = {
    path: "same-book/run",
    revision: 4,
    identity: "account/person-a/auth",
    available: true,
  };
  assert.equal(
    closingResourceMatches(
      stored,
      stored.path,
      4,
      "account/person-b/auth",
      true,
    ),
    false,
  );
  assert.equal(
    closingResourceMatches(stored, stored.path, 4, stored.identity, false),
    false,
  );
  assert.equal(
    closingResourceMatches(stored, stored.path, 5, stored.identity, true),
    false,
  );
  assert.equal(
    closingResourceMatches(stored, stored.path, 4, stored.identity, true),
    true,
  );
  assert.equal(closingWriteScopeDenied(403, "SCOPE_DENIED"), true);
  for (const code of [
    "SELF_APPROVAL_FORBIDDEN",
    "PERSON_AUTH_ASSURANCE_REQUIRED",
  ])
    assert.equal(closingWriteScopeDenied(403, code), false);
  assert.notEqual(
    closingDraftKey("person-a", true, "same-body"),
    closingDraftKey("person-b", true, "same-body"),
  );
  assert.equal(
    closingDraftKey("person-a", true, "same-body"),
    closingDraftKey("person-a", true, "same-body"),
  );
});
