import test from "node:test";
import assert from "node:assert/strict";
import {
  carryMoney,
  carryAllowedTargets,
  carrySelectionKey,
  carryScopeMatches,
  carrySummaryMatches,
  carryPageMatches,
  carryAccountsPath,
  carryCheckLabels,
  carryGroupVisible,
} from "./carry-readiness-rules.ts";
const id = (n) => `${String(n).padStart(8, "0")}-1111-4111-8111-111111111111`;
const money = (amount) => ({ state: "KNOWN", amount, reason: null });
const actor = {
  accountId: id(1),
  personId: null,
  identityAvailability: "UNBOUND",
};
const selection = {
  tenantId: id(2),
  legalEntityId: id(3),
  bookId: id(4),
  sourceYearId: id(5),
  sourceYear: 2031,
  targetYearId: id(6),
  targetYear: 2032,
  authHint: "read-snapshot",
  actorHint: actor,
  filter: "ALL",
  limit: 20,
  refreshToken: 0,
  yearHints: "both-year-version1",
};
const scope = {
  tenantId: selection.tenantId,
  legalEntityId: selection.legalEntityId,
  bookId: selection.bookId,
  sourceYearId: selection.sourceYearId,
  sourceYear: 2031,
  targetYearId: selection.targetYearId,
  targetYear: 2032,
  standard: "CAS",
  baseCurrency: "CNY",
  bothYearsReadAuthorized: true,
};
const counts = {
  draft: "0",
  submitted: "0",
  approved: "0",
  posted: "1",
  cancelled: "0",
};
const observation = (yearId, yearNumber) => ({
  yearId,
  yearNumber,
  rangeStart: `${yearNumber}-01-01`,
  rangeEnd: `${yearNumber}-12-31`,
  dataQualification: "SUPPORTED_V4",
  accountCount: 2,
  periods: [],
  openingRowCount: "0",
  openingFactModel: "PRODUCT_BALANCE_ROWS_ONLY",
  formalOpeningQualification: "NOT_IMPLEMENTED",
  openingDebit: money("0.00000000"),
  openingCredit: money("0.00000000"),
  postedMovementsDefinition:
    "FULL_CALENDAR_YEAR_POSTED_GROSS_INCLUDING_CLOSINGS_NOT_ECONOMIC_PROFIT",
  postedDebit: money("20.00000000"),
  postedCredit: money("20.00000000"),
  endingDebitBalances: money("20.00000000"),
  endingCreditBalances: money("20.00000000"),
  trialBalanced: true,
  journalCounts: counts,
  closingRunCounts: { ...counts, posted: "0" },
  nonzeroProfitLossAccountCount: 0,
  currentYearProfitAccountCount: 1,
  currentYearProfitResidual: money("0.00000000"),
  importedYtdBaselineQualification: "NOT_IMPLEMENTED",
  importedYtdBaselineAmount: {
    state: "NOT_IMPLEMENTED",
    amount: null,
    reason: "No formal baseline model",
  },
});
const vector = (yearId, yearNumber) => ({
  yearId,
  yearNumber,
  yearVersion: "1",
  yearStatus: "OPEN",
  precisionProfile: "V4",
  precisionReviewRequired: false,
  currencyMinorUnit: 2,
  personMode: "JOURNAL_PERSON_SOD",
  modeEpoch: id(7),
  modeGeneration: "2",
  postingSeq: "9007199254740993",
  openingVersion: "0",
  baselineVersion: "0",
  sourceCloseVersion: "0",
  journalReadEpoch: id(8),
  journalGeneration: "4",
  closingGeneration: "0",
  accountsDigest: "a".repeat(64),
  periodsDigest: "b".repeat(64),
});
const summary = {
  schemaVersion: "carry-readiness/1",
  scope,
  observation: {
    token: "opaque+/token=",
    contextDigest: "a".repeat(64),
    source: vector(id(5), 2031),
    target: vector(id(6), 2032),
    legacyPlansDigest: "b".repeat(64),
    authorizationDigest: "c".repeat(64),
    qualification: "READ_ONLY_NONPERSISTED_OBSERVATION_NOT_APPROVAL",
  },
  currentActor: actor,
  source: observation(id(5), 2031),
  target: observation(id(6), 2032),
  mappingTotals: {
    populationRows: 3,
    sourceAccounts: 2,
    targetAccounts: 2,
    compatibleCandidates: 1,
    missingTarget: 1,
    targetOnly: 1,
    semanticConflicts: 0,
    disabledCandidates: 0,
    unknownMonetaryRows: 2,
    approvalQualification: "NO_APPROVED_MAPPING_MODEL",
    dependsOnPageFilter: false,
  },
  legacyPlans: {
    total: 0,
    preview: 0,
    approved: 0,
    executed: 0,
    other: 0,
    qualification: "LEGACY_SCALAR_OBSERVATION_NOT_NEW_APPROVED_MAPPING_OR_BASE",
  },
  checks: Object.keys(carryCheckLabels).map((id) => ({
    id,
    group: "MISSING_CAPABILITY",
    status: "MISSING",
    blockingForBase: true,
    message: "Explicit missing capability",
    amountKnowledge: "MISSING_MODEL",
    amount: null,
    observedCount: null,
  })),
  overall: "INCOMPLETE_NOT_EXECUTABLE",
  canExecuteBase: false,
  yearCloseEnabled: false,
  formalReportEnabled: false,
  persistentSnapshotCreated: false,
};
const page = {
  schemaVersion: "carry-readiness/1",
  scope,
  observationToken: summary.observation.token,
  contextDigest: summary.observation.contextDigest,
  currentActor: actor,
  filter: "ALL",
  limit: 20,
  populationRows: 3,
  filteredRows: 3,
  items: [],
  nextCursor: null,
  canExecuteBase: false,
  yearCloseEnabled: false,
};
test("known zero and exact large8 remain original strings; missing/unsupported/null never become zero", () => {
  assert.equal(carryMoney(money("0.00000000")), "0.00000000");
  assert.equal(
    carryMoney(money("10000000000000000000.01000001")),
    "10000000000000000000.01000001",
  );
  for (const state of [
    "MISSING_ACCOUNT",
    "UNSUPPORTED_PRECISION",
    "UNSUPPORTED_BASELINE",
    "NOT_COMPARABLE",
    "NOT_IMPLEMENTED",
  ])
    assert.match(
      carryMoney({ state, amount: "0.00000000", reason: null }),
      /^未知/,
    );
  assert.match(carryMoney(money(null)), /^未知/);
  assert.match(carryMoney(money("NaN")), /^未知/);
});
test("target choices are only actually listed authorized consecutive years; missing choice never invents an existing year", () => {
  assert.deepEqual(
    carryAllowedTargets(
      [
        { id: "source", year: 2031 },
        { id: "skip", year: 2033 },
      ],
      "source",
    ),
    [],
  );
  assert.deepEqual(
    carryAllowedTargets(
      [
        { id: "source", year: 2031 },
        { id: "next", year: 2032 },
        { id: "skip", year: 2033 },
      ],
      "source",
    ).map((y) => y.id),
    ["next"],
  );
  assert.equal(
    carryScopeMatches(scope, { ...selection, targetYear: 2033 }),
    false,
  );
  assert.equal(
    carryScopeMatches({ ...scope, legalEntityId: id(99) }, selection),
    false,
  );
});
test("a valid read observation does not require bound person or TOTP; fixed24checks are exact-once and no executable flag can be imported", () => {
  assert.equal(carrySummaryMatches(summary, selection), true);
  assert.equal(
    carrySummaryMatches(
      {
        ...summary,
        checks: [...summary.checks.slice(0, 23), summary.checks[0]],
      },
      selection,
    ),
    false,
  );
  assert.equal(
    carrySummaryMatches({ ...summary, canExecuteBase: true }, selection),
    false,
  );
});
test("summary and page must match exact observation token/context/observer/pair before any joint values are shown", () => {
  assert.equal(carryPageMatches(page, summary, selection), true);
  assert.equal(
    carryPageMatches(
      { ...page, observationToken: "other" },
      summary,
      selection,
    ),
    false,
  );
  assert.equal(
    carryPageMatches(
      {
        ...page,
        currentActor: {
          ...actor,
          personId: id(20),
          identityAvailability: "AVAILABLE",
        },
      },
      summary,
      selection,
    ),
    false,
  );
  assert.equal(
    carryPageMatches(
      { ...page, scope: { ...scope, targetYearId: id(30) } },
      summary,
      selection,
    ),
    false,
  );
  assert.equal(
    carryPageMatches(
      { ...page, contextDigest: "f".repeat(64) },
      summary,
      selection,
    ),
    false,
  );
});
test("display filter does not replace overall denominator, and opacity is preserved on first and later page URLs", () => {
  const filtered = { ...page, filter: "NONZERO", filteredRows: 1 };
  assert.equal(
    carryPageMatches(filtered, summary, { ...selection, filter: "NONZERO" }),
    true,
  );
  assert.equal(
    carryPageMatches({ ...filtered, populationRows: 1 }, summary, {
      ...selection,
      filter: "NONZERO",
    }),
    false,
  );
  for (const cursor of [null, "opaque+/cursor="]) {
    const url = new URL(
      carryAccountsPath(selection, summary.observation.token, cursor),
      "http://example.invalid",
    );
    assert.equal(
      url.searchParams.get("observationToken"),
      summary.observation.token,
    );
    assert.equal(url.searchParams.get("cursor"), cursor);
    assert.equal(url.searchParams.get("targetYearId"), selection.targetYearId);
  }
});
test("current group masks immediately on target/person/auth/metadata/header/filter/limit transition and any non-ready phase", () => {
  const first = carrySelectionKey(selection);
  for (const change of [
    { targetYearId: id(90) },
    {
      actorHint: {
        ...actor,
        personId: id(91),
        identityAvailability: "AVAILABLE",
      },
    },
    { authHint: "new-auth" },
    { yearHints: "target-version2" },
    { refreshToken: 1 },
    { filter: "NONZERO" },
    { limit: 50 },
  ])
    assert.equal(
      carryGroupVisible(
        first,
        carrySelectionKey({ ...selection, ...change }),
        true,
        "READY",
      ),
      false,
    );
  assert.equal(carryGroupVisible(first, first, true, "PAGE"), false);
  assert.equal(carryGroupVisible(first, first, false, "READY"), false);
  assert.equal(carryGroupVisible(first, first, true, "READY"), true);
});
