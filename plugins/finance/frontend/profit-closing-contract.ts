// Finance-owned closed typed profit closing DTOs.
export type Identity = {
  accountId: string;
  personId: string;
  bindingId: string;
  bindingVersion: string;
  authorityId: string;
  authorityManifestDigest: string;
  assurance: "ORG_ATTESTED";
};
export type Actor = {
  accountId: string;
  personId: string | null;
  identityAvailability: "AVAILABLE" | "UNBOUND" | "REVOKED" | "UNCONFIGURED";
};
export type Blocker = { code: string; message: string };
export type Account = {
  id: string;
  code: string;
  name: string;
  category: "ASSET" | "LIABILITY" | "EQUITY" | "REVENUE" | "EXPENSE";
  normalSide: "DEBIT" | "CREDIT";
  enabled: boolean;
  purpose: "NONE" | "CURRENT_YEAR_PROFIT" | "RETAINED_EARNINGS";
};
export type SourceBasis = {
  tenantId: string;
  bookId: string;
  yearId: string;
  cutoff: string;
  modeEpoch: string;
  modeGeneration: string;
  periodId: string;
  periodVersion: string;
  includedPostingSeq: string;
  openingVersion: string;
  baselineVersion: string;
  sourceCloseVersion: string;
  accountSemanticDigest: string;
};
export type SourceRow = {
  account: Account;
  opening: string;
  debit: string;
  credit: string;
  balance: string;
};
export type SourceSnapshot = {
  format: "PROFIT_CLOSING_SOURCE_PG_V1";
  basis: SourceBasis;
  sourceDigest: string;
  observedYearPostingSeq: string;
  sourceRows: SourceRow[];
  totalDebit: string;
  totalCredit: string;
  balanced: true;
  includesPriorPostedClosings: true;
  latestPostedRunId: string | null;
  latestEffectiveTransferRunId: string | null;
};
export type GeneratedLine = {
  lineNo: number;
  account: Account;
  debit: string;
  credit: string;
  memo: string;
};
export type Content = {
  format: "CLOSING_PERSON_CONTENT_PG_V1";
  producerKind: "PROFIT_LOSS" | "YEAR_END" | "TRANSFER_REVERSAL";
  rawType: "PROFIT_CLOSE" | "YEAR_END" | "REVERSAL";
  cutoff: string;
  summary: string;
  currency: "CNY";
  sourceDigest: string;
  generatedLines: GeneratedLine[];
  documents: never[];
  reversesJournalId: string | null;
  reason: string;
};
export type Approval = {
  id: string;
  approver: Identity;
  contentVersion: string;
  contentDigest: string;
  sourceDigest: string;
  approvedJournalVersion: string;
  decidedAt: string;
  expiresAt: string;
  eligibilityForNewPost:
    | "FRESH"
    | "EXPIRED"
    | "SOURCE_STALE"
    | "ACTOR_STALE"
    | "SUPERSEDED"
    | "CONSUMED";
};
export type PostProof = {
  id: string;
  approvalId: string;
  poster: Identity;
  postedAt: string;
  postingSeq: string;
  postingDigest: string;
  contentVersion: string;
  contentDigest: string;
  sourceDigest: string;
  includedSourcePostingSeq: string;
  prePostYearPostingSeq: string;
  effectKey: string;
};
export type Preview = {
  schemaVersion: "profit-closing/1";
  tenantId: string;
  bookId: string;
  yearId: string;
  standard: "CAS" | "SMALL";
  producerKind: "PROFIT_LOSS" | "YEAR_END" | "TRANSFER_REVERSAL";
  cutoff: string;
  reversesRunId: string | null;
  outcome: "PRODUCIBLE" | "NOOP" | "BLOCKED";
  source: SourceSnapshot | null;
  generatedLines: GeneratedLine[] | null;
  generatedDigest: string | null;
  currentActor: Actor;
  blockers: Blocker[];
  allowedActions: "create-run"[];
  doesNotClosePeriodOrYear: true;
};
export type CreateRun = {
  schemaVersion: "profit-closing/1";
  idempotencyKey: string;
  operationId: string;
  producerKind: "PROFIT_LOSS" | "YEAR_END" | "TRANSFER_REVERSAL";
  cutoff: string;
  expectedSource: SourceBasis;
  expectedSourceDigest: string;
  expectedGeneratedDigest: string;
  predecessorPostedRunId: string | null;
  supersedesCancelledAttemptId: string | null;
  expectedSupersededVersion: string | null;
  reversesRunId: string | null;
  reason: string;
};
export type Action = {
  schemaVersion: "profit-closing/1";
  idempotencyKey: string;
  expectedVersion: string;
  expectedContentVersion: string;
  contentDigest: string;
  reason: string;
};
export type Approve = {
  schemaVersion: "profit-closing/1";
  idempotencyKey: string;
  expectedVersion: string;
  expectedContentVersion: string;
  contentDigest: string;
  reason: string;
  requestedExpiresAt: string | null;
};
export type Post = {
  schemaVersion: "profit-closing/1";
  idempotencyKey: string;
  expectedVersion: string;
  expectedContentVersion: string;
  contentDigest: string;
  reason: string;
  approvalId: string;
};
export type Termination = {
  actor: Identity;
  reason: string;
  at: string;
  journalVersion: string;
  contentVersion: string;
  contentDigest: string;
};
export type RunSummary = {
  id: string;
  journalId: string;
  producerKind: "PROFIT_LOSS" | "YEAR_END" | "TRANSFER_REVERSAL";
  cutoff: string;
  status: "DRAFT" | "SUBMITTED" | "APPROVED" | "POSTED" | "CANCELLED";
  journalStatus: "DRAFT" | "SUBMITTED" | "APPROVED" | "POSTED" | "CANCELLED";
  version: string;
  journalVersion: string;
  contentVersion: string;
  contentDigest: string;
  sourceDigest: string;
  sourceFresh: boolean;
  economicEffect: "NOT_POSTED" | "ACTIVE" | "FULLY_REVERSED";
  preparedBy: Identity;
  lastEditor: Identity;
  predecessorPostedRunId: string | null;
  supersedesCancelledAttemptId: string | null;
  reversesRunId: string | null;
  postedReversalRunId: string | null;
  createdAt: string;
  allowedActions: (
    | "submit"
    | "approve"
    | "post"
    | "reject"
    | "withdraw"
    | "cancel"
    | "admin-terminate"
    | "propose-full-reversal"
    | "propose-residual"
  )[];
  blockers: Blocker[];
};
export type RunDetail = {
  id: string;
  journalId: string;
  producerKind: "PROFIT_LOSS" | "YEAR_END" | "TRANSFER_REVERSAL";
  cutoff: string;
  status: "DRAFT" | "SUBMITTED" | "APPROVED" | "POSTED" | "CANCELLED";
  journalStatus: "DRAFT" | "SUBMITTED" | "APPROVED" | "POSTED" | "CANCELLED";
  version: string;
  journalVersion: string;
  contentVersion: string;
  contentDigest: string;
  sourceDigest: string;
  sourceFresh: boolean;
  economicEffect: "NOT_POSTED" | "ACTIVE" | "FULLY_REVERSED";
  preparedBy: Identity;
  lastEditor: Identity;
  predecessorPostedRunId: string | null;
  supersedesCancelledAttemptId: string | null;
  reversesRunId: string | null;
  postedReversalRunId: string | null;
  createdAt: string;
  allowedActions: (
    | "submit"
    | "approve"
    | "post"
    | "reject"
    | "withdraw"
    | "cancel"
    | "admin-terminate"
    | "propose-full-reversal"
    | "propose-residual"
  )[];
  blockers: Blocker[];
  source: SourceSnapshot;
  content: Content;
  currentActor: Actor;
  currentApproval: Approval | null;
  postProof: PostProof | null;
  administrativeTermination: Termination | null;
  yearCloseProtocol: "BLOCKED";
  cashPersonSod: "NOT_ENFORCED";
};
export type RunPage = {
  items: RunSummary[];
  nextCursor: string | null;
  generation: string;
  currentActor: Actor;
};
export type JournalProducerLink = {
  journalId: string;
  rawType:
    | "NORMAL"
    | "REVERSAL"
    | "PROFIT_CLOSE"
    | "YEAR_END"
    | "AP_EXPENSE"
    | "AP_EXPENSE_REVERSAL"
    | "AR_REVENUE"
    | "AR_RECEIPT"
    | "AR_ALLOCATION"
    | "AR_REVERSAL"
    | "ASSET_ACQUIRE"
    | "ASSET_DEPRECIATE"
    | "ASSET_DISPOSE"
    | "ASSET_REVERSE"
    | "ASSET_CORRECT_DEPRECIATION";
  resolution: "FOUND" | "NONE" | "UNSUPPORTED";
  runId: string | null;
  producerKind: "PROFIT_LOSS" | "YEAR_END" | "TRANSFER_REVERSAL" | null;
  reason: string | null;
};
