import test from "node:test";
import assert from "node:assert/strict";
import {
  arActions,
  arAllocation,
  arCanonical,
  arCny,
  arLabels,
  arRetryIdentity,
  arUnits,
} from "./receivables-rules.ts";

const r1 = "00000000-0000-4000-8000-000000000001";
const r2 = "00000000-0000-4000-8000-000000000002";

test("CNY二位字符串严格校验，拒绝浮点、超两位小数、零金额与越界值", () => {
  assert.equal(arCny("100"), "100.00");
  assert.equal(arCny("100.5"), "100.50");
  assert.equal(arCny("10000000000000000000.01"), "10000000000000000000.01");
  assert.equal(arCny("0", true), "0.00");
  assert.equal(arUnits("12.34"), 1234n);

  for (const invalid of [
    "0",
    "0.00",
    "-1.00",
    "01.00",
    "1.001",
    "1.005",
    "1e2",
    100,
    null,
    undefined,
    "100000000000000000000.00",
  ]) {
    assert.throws(() => arCny(invalid));
  }
});

test("核销明细校验1至50条、无重复识别码、合计严格等于核销总额并按ID穩定排序", () => {
  const items = arAllocation(
    [
      { recognitionId: r2.toUpperCase(), amount: "40" },
      { recognitionId: r1, amount: "60.5" },
    ],
    "100.50",
  );
  assert.deepEqual(items, [
    { recognitionId: r1, amount: "60.50" },
    { recognitionId: r2, amount: "40.00" },
  ]);

  assert.throws(() => arAllocation([], "0.00"));
  assert.throws(() =>
    arAllocation(
      [
        { recognitionId: r1, amount: "50.00" },
        { recognitionId: r1.toUpperCase(), amount: "50.00" },
      ],
      "100.00",
    ),
  );
  assert.throws(() =>
    arAllocation([{ recognitionId: r1, amount: "99.99" }], "100.00"),
  );
  assert.throws(() =>
    arAllocation([{ recognitionId: "not-a-uuid", amount: "100.00" }], "100.00"),
  );
});

test("状态动作矩阵闭合，已确认/已记账/已作废对象不暴露非法变更动作", () => {
  assert.deepEqual(arActions("DRAFT"), ["submit", "cancel"]);
  assert.deepEqual(arActions("SUBMITTED"), [
    "approve",
    "reject",
    "withdraw",
    "cancel",
  ]);
  assert.deepEqual(arActions("APPROVED"), [
    "post",
    "reject",
    "withdraw",
    "cancel",
  ]);
  assert.deepEqual(arActions("CONFIRMED"), []);
  assert.deepEqual(arActions("POSTED"), []);
  assert.deepEqual(arActions("CANCELLED"), []);
  assert.equal(Object.keys(arLabels).length, 6);
});

test("应收原生凭证类型识别闭合，不误判人工或应付/资产凭证", async () => {
  const { AR_PRODUCER_JOURNAL_TYPES, isArProducerJournalType } = await import(
    "./receivables-rules.ts"
  );
  assert.equal(AR_PRODUCER_JOURNAL_TYPES.length, 4);
  for (const type of AR_PRODUCER_JOURNAL_TYPES) {
    assert.equal(isArProducerJournalType(type), true);
  }
  for (const other of [undefined, "NORMAL", "AP_EXPENSE", "ASSET_ACQUIRE"]) {
    assert.equal(isArProducerJournalType(other), false);
  }
});

test("重试身份按scope+route+canonical正文持久保留，跨刷新同输入复用原键，不同scope或正文隔离且不落盘敏感正文", async () => {
  const map = new Map();
  const storage = {
    getItem: (k) => map.get(k) ?? null,
    setItem: (k, v) => map.set(k, v),
  };
  let seq = 0;
  const nextUuid = () =>
    `00000000-0000-4000-8000-${String(++seq).padStart(12, "0")}`;

  const bodyA = { b: 2, a: { y: "secret-customer", x: "100.00" } };
  const bodyAReordered = { a: { x: "100.00", y: "secret-customer" }, b: 2 };
  assert.equal(arCanonical(bodyA), arCanonical(bodyAReordered));

  const first = await arRetryIdentity(
    "acct1:person1:book1:year1",
    "/receivables",
    bodyA,
    storage,
    nextUuid,
  );
  const replayed = await arRetryIdentity(
    "acct1:person1:book1:year1",
    "/receivables",
    bodyAReordered,
    storage,
    nextUuid,
  );
  assert.deepEqual(replayed, first);

  const otherScope = await arRetryIdentity(
    "acct2:person2:book1:year1",
    "/receivables",
    bodyA,
    storage,
    nextUuid,
  );
  assert.notDeepEqual(otherScope, first);

  for (const [k, v] of map.entries()) {
    assert.ok(k.startsWith("finance:ar:v1:retry:"));
    assert.equal(v.includes("secret-customer"), false);
  }

  const onlyKey = [...map.keys()][0];
  map.set(onlyKey, JSON.stringify({ broken: true }));
  await assert.rejects(() =>
    arRetryIdentity(
      "acct1:person1:book1:year1",
      "/receivables",
      bodyA,
      storage,
      nextUuid,
    ),
  );
});
