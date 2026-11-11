// Finance-owned closed readonly carry readiness DTOs.
export type SignedAmount = {
  state:
    | "KNOWN"
    | "MISSING_ACCOUNT"
    | "UNSUPPORTED_PRECISION"
    | "UNSUPPORTED_BASELINE"
    | "UNKNOWN_INVALID_VALUE"
    | "NOT_COMPARABLE"
    | "NOT_IMPLEMENTED";
  amount: string | null;
  reason: string | null;
};
export type UnsignedAmount = {
  state:
    | "KNOWN"
    | "MISSING_ACCOUNT"
    | "UNSUPPORTED_PRECISION"
    | "UNSUPPORTED_BASELINE"
    | "UNKNOWN_INVALID_VALUE"
    | "NOT_COMPARABLE"
    | "NOT_IMPLEMENTED";
  amount: string | null;
  reason: string | null;
};
export type Actor = {
  accountId: string;
  personId: string | null;
  identityAvailability: "AVAILABLE" | "UNBOUND" | "REVOKED" | "UNCONFIGURED";
};
export type Account = {
  id: string;
  code: string;
  name: string;
  category: "ASSET" | "LIABILITY" | "EQUITY" | "REVENUE" | "EXPENSE";
  normalSide: "DEBIT" | "CREDIT";
  purpose: "NONE" | "CURRENT_YEAR_PROFIT" | "RETAINED_EARNINGS";
  enabled: boolean;
};
export type Period = {
  id: string;
  month: number;
  status: "OPEN" | "CLOSED";
  version: string;
};
export type YearVector = {
  yearId: string;
  yearNumber: number;
  yearVersion: string;
  yearStatus: "OPEN" | "CLOSING" | "CLOSED_PENDING_ARCHIVE" | "CLOSED";
  precisionProfile: "V4" | "LEGACY_V3" | "UNKNOWN";
  precisionReviewRequired: boolean;
  currencyMinorUnit: number;
  personMode: "ACCOUNT_ONLY" | "JOURNAL_PERSON_SOD";
  modeEpoch: string;
  modeGeneration: string;
  postingSeq: string;
  openingVersion: string;
  baselineVersion: string;
  sourceCloseVersion: string;
  journalReadEpoch: string;
  journalGeneration: string;
  closingGeneration: string;
  accountsDigest: string;
  periodsDigest: string;
};
export type Observation = {
  token: string;
  contextDigest: string;
  source: YearVector;
  target: YearVector;
  legacyPlansDigest: string;
  authorizationDigest: string;
  qualification: "READ_ONLY_NONPERSISTED_OBSERVATION_NOT_APPROVAL";
};
export type Scope = {
  tenantId: string;
  legalEntityId: string;
  bookId: string;
  standard: "CAS" | "SMALL";
  baseCurrency: "CNY";
  sourceYearId: string;
  targetYearId: string;
  sourceYear: number;
  targetYear: number;
  bothYearsReadAuthorized: true;
};
export type WorkflowCounts = {
  draft: string;
  submitted: string;
  approved: string;
  posted: string;
  cancelled: string;
};
export type YearObservation = {
  yearId: string;
  yearNumber: number;
  rangeStart: string;
  rangeEnd: string;
  dataQualification:
    | "SUPPORTED_V4"
    | "UNSUPPORTED_PRECISION"
    | "UNSUPPORTED_BASELINE"
    | "UNKNOWN_INVALID_DATA";
  accountCount: number;
  periods: Period[];
  openingRowCount: string;
  openingFactModel: "PRODUCT_BALANCE_ROWS_ONLY";
  formalOpeningQualification: "NOT_IMPLEMENTED";
  openingDebit: UnsignedAmount;
  openingCredit: UnsignedAmount;
  postedMovementsDefinition: "FULL_CALENDAR_YEAR_POSTED_GROSS_INCLUDING_CLOSINGS_NOT_ECONOMIC_PROFIT";
  postedDebit: UnsignedAmount;
  postedCredit: UnsignedAmount;
  endingDebitBalances: UnsignedAmount;
  endingCreditBalances: UnsignedAmount;
  trialBalanced: boolean | null;
  journalCounts: WorkflowCounts;
  journalCountsIncludeClosingJournals: true;
  closingRunCounts: WorkflowCounts;
  nonzeroProfitLossAccountCount: number | null;
  currentYearProfitAccountCount: number;
  currentYearProfitResidual: SignedAmount;
  importedYtdBaselineQualification: "NOT_IMPLEMENTED";
  importedYtdBaselineAmount: SignedAmount & {
    state: "NOT_IMPLEMENTED";
    amount: null;
  };
};
export type Check = {
  id:
    | "SOURCE_DATA"
    | "TARGET_DATA"
    | "SOURCE_PERSON_MODE"
    | "TARGET_PERSON_MODE"
    | "SOURCE_PERIODS_CLOSED"
    | "SOURCE_PENDING_JOURNALS"
    | "SOURCE_PENDING_CLOSING"
    | "SOURCE_TRIAL_BALANCED"
    | "SOURCE_PROFIT_LOSS_ZERO"
    | "SOURCE_CURRENT_PROFIT_ZERO"
    | "TARGET_OPENING_FACTS"
    | "TARGET_POSTED_YEAR_MOVEMENTS"
    | "CANDIDATE_ACCOUNT_MAPPING"
    | "APPROVED_ACCOUNT_MAPPING"
    | "FORMAL_OPENING_BATCH"
    | "YTD_BASELINE_MODEL"
    | "SUBLEDGER_OPEN_ITEMS"
    | "BANK_RECONCILIATION"
    | "EXTERNAL_CASH_UNIVERSE"
    | "ASSET_CONTINUITY"
    | "INVENTORY_QUANTITY"
    | "TAX_CONTINUITY"
    | "FORMAL_REPORT_APPROVAL"
    | "ARCHIVE";
  group:
    | "LEDGER_OBSERVATION"
    | "WORKFLOW_OBSERVATION"
    | "MAPPING_OBSERVATION"
    | "MISSING_CAPABILITY";
  status: "PASS" | "FAIL" | "MISSING" | "UNSUPPORTED";
  blockingForBase: true;
  message: string;
  amountKnowledge:
    "KNOWN" | "NOT_MEASURED" | "MISSING_MODEL" | "UNSUPPORTED_DATA";
  amount: string | null;
  observedCount: string | null;
};
export type MappingTotals = {
  populationRows: number;
  sourceAccounts: number;
  targetAccounts: number;
  compatibleCandidates: number;
  missingTarget: number;
  targetOnly: number;
  semanticConflicts: number;
  disabledCandidates: number;
  unknownMonetaryRows: number;
  approvalQualification: "NO_APPROVED_MAPPING_MODEL";
  dependsOnPageFilter: false;
};
export type LegacyPlanCounts = {
  total: number;
  preview: number;
  approved: number;
  executed: number;
  other: number;
  qualification: "LEGACY_SCALAR_OBSERVATION_NOT_NEW_APPROVED_MAPPING_OR_BASE";
};
export type Summary = {
  schemaVersion: "carry-readiness/1";
  scope: Scope;
  observation: Observation;
  currentActor: Actor;
  source: YearObservation;
  target: YearObservation;
  mappingTotals: MappingTotals;
  legacyPlans: LegacyPlanCounts;
  checks: Check[];
  overall: "INCOMPLETE_NOT_EXECUTABLE";
  canExecuteBase: false;
  yearCloseEnabled: false;
  formalReportEnabled: false;
  persistentSnapshotCreated: false;
};
export type AccountRow = {
  rowKey: string;
  sourcePresence: "PRESENT" | "MISSING_ACCOUNT";
  targetPresence: "PRESENT" | "MISSING_ACCOUNT";
  sourceAccount: Account | null;
  targetCandidate: Account | null;
  candidateStatus:
    | "CANDIDATE_COMPATIBLE"
    | "MISSING_TARGET"
    | "TARGET_ONLY"
    | "SEMANTIC_CONFLICT"
    | "DISABLED_ACCOUNT";
  mappingStatus: "NOT_APPROVED";
  semanticDifferences: (
    | "CATEGORY"
    | "NORMAL_SIDE"
    | "PURPOSE"
    | "SOURCE_DISABLED"
    | "TARGET_DISABLED"
  )[];
  sourceOpening: SignedAmount;
  sourcePostedDebit: UnsignedAmount;
  sourcePostedCredit: UnsignedAmount;
  sourceEndingBalance: SignedAmount;
  targetOpeningRowExists: boolean | null;
  targetOpening: SignedAmount;
  targetPostedDebit: UnsignedAmount;
  targetPostedCredit: UnsignedAmount;
  targetPostedNet: SignedAmount;
  targetEndingBalance: SignedAmount;
  candidateOpeningDifference: SignedAmount;
  differenceQualification: "UNAPPROVED_CANDIDATE_ONLY" | "NOT_COMPARABLE";
  isApprovedCarryAmount: false;
};
export type AccountPage = {
  schemaVersion: "carry-readiness/1";
  scope: Scope;
  observationToken: string;
  contextDigest: string;
  currentActor: Actor;
  filter: "ALL" | "NONZERO";
  limit: number;
  populationRows: number;
  filteredRows: number;
  items: AccountRow[];
  nextCursor: string | null;
  canExecuteBase: false;
  yearCloseEnabled: false;
};
