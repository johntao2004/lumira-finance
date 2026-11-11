import test from "node:test";
import assert from "node:assert/strict";
import {
  partyCreate,
  partyProposal,
  partyAction,
  partyCanDecide,
  partyIdentity,
  partyTabIntent,
  boundedPartyDraft,
  partyConflictReferences,
} from "./counterparty-rules.ts";
import { EvidenceRequestOwner } from "./evidence-request-state.ts";
const actor = {
  accountId: "a",
  personId: "p",
  identityAvailability: "AVAILABLE",
};
const values = {
  jurisdiction: " cn ",
  identifier: " abc/123 ",
  displayName: "真实组织",
  roles: ["CUSTOMER", "SUPPLIER"],
  relatedPartyStatus: "UNKNOWN",
  relatedPartyBasis: "不应回传",
  registrationBasis: "组织记录ORG-18",
  reason: "独立治理",
  organizationDeclarationConfirmed: true,
  identityNotPlaceholderConfirmed: true,
  secret: "不允许回传",
};
const party = {
  registryVersion: "9007199254740993",
  effectiveRevision: { id: "approved", contentDigest: "hash" },
  latestRevisionId: "latest",
  effectiveStatus: "ACTIVE",
};
test("组织声明归一化ASCII身份，同时严格白名单且未知关联方依据保持null", () => {
  const p = partyCreate(values, "operation");
  assert.equal(p.identity.identifier, "ABC/123");
  assert.equal(p.identity.jurisdiction, "CN");
  assert.equal(p.content.relatedPartyBasis, null);
  assert.deepEqual(p.content.roles, ["CUSTOMER", "SUPPLIER"]);
  assert.equal("secret" in p, false);
  assert.equal("secret" in p.content, false);
});
test("两项声明必须主动确认且非ASCII登记身份不能被悄悄接受", () => {
  assert.throws(() =>
    partyCreate({ ...values, organizationDeclarationConfirmed: false }, "op"),
  );
  assert.throws(() => partyCreate({ ...values, identifier: "组织一" }, "op"));
  assert.throws(() => partyCreate({ ...values, jurisdiction: "\tCN" }, "op"));
});
test("修订采用当前registry与最新任意状态前驱，counter保持字符串精度", () => {
  const p = partyProposal({ ...values, desiredStatus: "FROZEN" }, party);
  assert.equal(p.expectedRegistryVersion, "9007199254740993");
  assert.equal(p.previousRevisionId, "latest");
  assert.equal(p.baseApprovedRevisionId, "approved");
  assert.throws(() =>
    partyProposal(
      { ...values, desiredStatus: "ACTIVE" },
      { ...party, effectiveStatus: "INACTIVE" },
    ),
  );
});
test("同自然人不同账号不能审核，独立reviewer不需OWNER治理岗位", () => {
  const r = { makerAccountId: "other", makerPersonId: "p" };
  assert.equal(partyCanDecide(actor, r, "REVIEWER", true), false);
  assert.equal(
    partyCanDecide({ ...actor, personId: "independent" }, r, "REVIEWER", true),
    true,
  );
  assert.equal(
    partyCanDecide({ ...actor, personId: "independent" }, r, "REVIEWER", false),
    false,
  );
});
test("Action只发送冻结正文版本和digest，不回传只读快照或拟发布状态", () => {
  const p = partyAction("理由", {
    counterparty: party,
    revision: {
      version: "9007199254740994",
      contentDigest: "digest",
      desiredStatus: "ACTIVE",
    },
  });
  assert.deepEqual(p, {
    schemaVersion: "counterparty/1",
    reason: "理由",
    expectedRegistryVersion: "9007199254740993",
    expectedRevisionVersion: "9007199254740994",
    contentDigest: "digest",
  });
});
test("实际父owner同身份跨modal/刷新保留同正文key，person或book改变必须清", () => {
  const owner = new EvidenceRequestOwner(),
    id = partyIdentity("book1", "read", actor);
  const draft = new Map();
  const a = boundedPartyDraft(draft, "approve", () => ({ reason: "保留正文" }));
  owner.key(id, JSON.stringify(a), () => "first");
  assert.equal(
    boundedPartyDraft(draft, "approve", () => ({ reason: "错误新正文" })),
    a,
  );
  assert.equal(
    owner.key(id, JSON.stringify(a), () => "changed"),
    "first",
  );
  assert.equal(
    owner.key(
      partyIdentity("book1", "read", { ...actor, personId: "other" }),
      JSON.stringify(a),
      () => "person-new",
    ),
    "person-new",
  );
  assert.equal(
    owner.key(
      partyIdentity("book2", "read", actor),
      JSON.stringify(a),
      () => "scope-new",
    ),
    "scope-new",
  );
});
test("失败正文缓存和草稿均受20条上限，不积累游标或多身份缓存", () => {
  const drafts = new Map(),
    owner = new EvidenceRequestOwner();
  for (let i = 0; i < 200; i++) {
    boundedPartyDraft(drafts, String(i), () => ({ i }));
    owner.key("identity", String(i), () => String(i));
  }
  assert.equal(drafts.size, 20);
  assert.equal(owner.size(), 20);
});
test("账套主数据不依赖年度OPEN，首次选年度保留已见往来tab，撤权才退回", () => {
  assert.equal(
    partyTabIntent("journals", true, false, false, true),
    "counterparties",
  );
  assert.equal(
    partyTabIntent("counterparties", true, true, true, true),
    "counterparties",
  );
  assert.equal(
    partyTabIntent("counterparties", true, false, true, true),
    "counterparties",
  );
  assert.equal(
    partyTabIntent("counterparties", true, true, true, false),
    "journals",
  );
  assert.equal(partyTabIntent("journals", true, true, false, true), null);
});

test("描述和理由保留LF/CR/TAB真实正文，名称控制符和非ASCII大小写扩展不得绕登记身份校验", () => {
  const result = partyCreate(
    {
      ...values,
      settlementTerms: "\t条款\r\n第二行\t",
      reason: "\t理由\n保持",
    },
    "op",
  );
  assert.equal(result.content.settlementTerms, "\t条款\r\n第二行\t");
  assert.equal(result.reason, "\t理由\n保持");
  assert.throws(() => partyCreate({ ...values, identifier: "ß" }, "op"));
  assert.throws(() => partyCreate({ ...values, displayName: "\n组织" }, "op"));
  assert.throws(() =>
    partyCreate({ ...values, registrationBasis: "  \t" }, "op"),
  );
  assert.throws(() =>
    partyProposal(
      { ...values, desiredStatus: "FROZEN" },
      { ...party, effectiveStatus: null },
    ),
  );
});

test("只有确切授权冲突协议的有序双issue投影显示规范UUID，403及畸形均不泄露refs", () => {
  const refs = {
    counterpartyId: "e75e74e7-ef46-4c09-ba24-455ba976919d",
    governanceHomeBookId: "03a15dc4-f55f-447c-bc80-56776668fda4",
  };
  const issues = [
    { row: 1, field: "counterpartyId", reason: refs.counterpartyId },
    {
      row: 1,
      field: "governanceHomeBookId",
      reason: refs.governanceHomeBookId,
    },
  ];
  assert.deepEqual(
    partyConflictReferences(409, "COUNTERPARTY_IDENTITY_EXISTS", issues),
    refs,
  );
  assert.equal(
    partyConflictReferences(403, "COUNTERPARTY_IDENTITY_EXISTS", issues),
    null,
  );
  assert.equal(partyConflictReferences(409, "VERSION_CONFLICT", issues), null);
  for (const invalid of [
    null,
    [],
    issues.slice(0, 1),
    [...issues, issues[0]],
    [issues[1], issues[0]],
    [{ ...issues[0], row: 2 }, issues[1]],
    [{ ...issues[0], reason: refs.counterpartyId.toUpperCase() }, issues[1]],
    [{ ...issues[0], reason: "not-a-uuid" }, issues[1]],
    [{ ...issues[0], extra: "not-contract" }, issues[1]],
  ])
    assert.equal(
      partyConflictReferences(409, "COUNTERPARTY_IDENTITY_EXISTS", invalid),
      null,
    );
});
