import test from "node:test";
import assert from "node:assert/strict";
import {
  directoryAbility,
  directoryConfigurationState,
  directoryCreatePayload,
  directoryGeneration,
  directoryRequestKey,
  directoryResourceFailure,
  directoryTabIntent,
  independentDirectoryConfirmation,
} from "./person-directory-rules.ts";
test("budget and busy reads discard the complete directory snapshot while writes retain their retry context; authentication rejections are distinct", () => {
  for (const code of [
    "PERSON_DIRECTORY_QUERY_BUDGET_EXCEEDED",
    "PERSON_DIRECTORY_BUSY",
  ]) {
    assert.equal(directoryResourceFailure(code, true).clearReadState, true);
    assert.match(directoryResourceFailure(code, true).message, /未读取成功/);
    assert.equal(directoryResourceFailure(code, false).clearReadState, false);
    assert.match(
      directoryResourceFailure(code, false).message,
      /处理结果尚未确认/,
    );
    assert.match(directoryResourceFailure(code, false).message, /原请求/);
  }
  for (const code of [
    "SELF_APPROVAL_FORBIDDEN",
    "PERSON_AUTH_ASSURANCE_REQUIRED",
    "SCOPE_DENIED",
  ])
    assert.equal(directoryResourceFailure(code, false), null);
});
test("directory shown before year selection becomes the actual tab intent, survives the first year, and denial still falls back", () => {
  const visibleBeforeYear = directoryTabIntent("journals", false, true);
  assert.equal(visibleBeforeYear, "person-directory");
  assert.equal(
    directoryTabIntent(visibleBeforeYear, true, true),
    "person-directory",
  );
  assert.equal(directoryTabIntent(visibleBeforeYear, true, false), "journals");
  assert.equal(directoryTabIntent(visibleBeforeYear, false, false), "journals");
  assert.equal(directoryTabIntent("ledger", true, true), "ledger");
  assert.equal(directoryTabIntent("journals", false, false), "journals");
});
test("failed request keys stay bounded and a recently retried unknown outcome preserves its key", () => {
  const keys = new Map();
  let count = 0;
  const create = () => `key-${++count}`;
  const first = directoryRequestKey(keys, "unknown-payload", create);
  for (let i = 0; i < 1000; i++) {
    directoryRequestKey(keys, `changed-${i}`, create);
    assert.equal(directoryRequestKey(keys, "unknown-payload", create), first);
    assert.ok(keys.size <= 20);
  }
  assert.equal(keys.size, 20);
  assert.equal(keys.has("changed-0"), false);
});
test("ordinary same-tenant configuration refetch retains the directory while writes pause; denial or a foreign tenant never retains visibility", () => {
  const base = {
    canRead: true,
    tenantId: "tenant-a",
    configurationTenantId: "tenant-a",
    denied: false,
    fetching: false,
    failed: false,
  };
  assert.deepEqual(directoryConfigurationState({ ...base, fetching: true }), {
    visible: true,
    authorizationPending: true,
  });
  assert.deepEqual(
    directoryConfigurationState({ ...base, failed: true, status: 503 }),
    { visible: true, authorizationPending: true },
  );
  assert.equal(
    directoryConfigurationState({ ...base, failed: true, status: 403 }).visible,
    false,
  );
  assert.equal(
    directoryConfigurationState({
      ...base,
      tenantId: "tenant-b",
      fetching: true,
    }).visible,
    false,
  );
  assert.equal(
    directoryConfigurationState({
      ...base,
      configurationTenantId: undefined,
      fetching: true,
    }).visible,
    false,
  );
  assert.equal(
    directoryConfigurationState({ ...base, denied: true }).visible,
    false,
  );
  assert.deepEqual(directoryConfigurationState(base), {
    visible: true,
    authorizationPending: false,
  });
});
const maker = "10000000-0000-0000-0000-000000000001",
  target = "10000000-0000-0000-0000-000000000002",
  other = "10000000-0000-0000-0000-000000000003";
const values = {
  operation: "CREATE_PERSON_AND_BIND",
  accountId: target,
  displayName: "组织核验人员",
  personId: other,
  sourceReference: "ORG/2026/27",
  sourceSha256: "a".repeat(64),
  reason: "核对组织人员记录及全部账号",
  noAccountTransferConfirmed: true,
  allKnownAccountsDeclared: true,
  sessionId: "must-not-send",
  totp: "must-not-send",
};
test("directory payload requires active assertions and removes authentication or stale form fields", () => {
  assert.throws(() =>
    directoryCreatePayload(
      { ...values, noAccountTransferConfirmed: false },
      "key",
    ),
  );
  assert.throws(() =>
    directoryCreatePayload(
      { ...values, allKnownAccountsDeclared: undefined },
      "key",
    ),
  );
  const actual = directoryCreatePayload(values, "key");
  assert.equal(actual.personId, null);
  assert.equal(actual.schemaVersion, "1");
  assert.equal("sessionId" in actual, false);
  assert.equal("totp" in actual, false);
  assert.equal(Object.keys(actual).length, 11);
  const bind = directoryCreatePayload(
    { ...values, operation: "BIND_EXISTING_PERSON" },
    "key",
  );
  assert.equal(bind.personId, other);
  assert.equal(bind.displayName, null);
  const uppercase = "ABCDEF12-ABCD-ABCD-ABCD-ABCDEF123456";
  const normalized = directoryCreatePayload(
    {
      ...values,
      operation: "BIND_EXISTING_PERSON",
      accountId: uppercase,
      personId: uppercase,
    },
    "key",
  );
  assert.equal(normalized.accountId, uppercase.toLowerCase());
  assert.equal(normalized.personId, uppercase.toLowerCase());
});
test("directory abilities intersect governance and platform permission, never book role", () => {
  const c = { currentActorAbilities: ["READ"] };
  assert.equal(directoryAbility(c, "PROPOSE", true), false);
  assert.equal(directoryAbility(c, "READ", false), false);
  assert.equal(directoryAbility(c, "READ", true), true);
});
test("confirmation excludes the same natural person across accounts and the target account", () => {
  const p = {
    status: "PENDING",
    accountId: target,
    preparedPersonId: maker,
    personId: target,
  };
  const independent = {
    currentActorAccountId: other,
    currentActorPersonId: other,
  };
  assert.equal(independentDirectoryConfirmation(independent, p), true);
  assert.equal(
    independentDirectoryConfirmation(
      { ...independent, currentActorPersonId: maker },
      p,
    ),
    false,
  );
  assert.equal(
    independentDirectoryConfirmation(
      { ...independent, currentActorPersonId: target },
      p,
    ),
    false,
  );
  assert.equal(
    independentDirectoryConfirmation(
      { ...independent, currentActorAccountId: target },
      p,
    ),
    false,
  );
  assert.equal(
    independentDirectoryConfirmation(independent, { ...p, status: "APPROVED" }),
    false,
  );
});
test("directory generation remains exact beyond JS safe integers and rejects overflow", () => {
  assert.equal(directoryGeneration("9007199254740993"), "9007199254740993");
  assert.equal(
    directoryGeneration("9223372036854775807"),
    "9223372036854775807",
  );
  for (const value of ["01", "-1", "1.0", "9223372036854775808"])
    assert.throws(() => directoryGeneration(value));
});
