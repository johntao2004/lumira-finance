// Closed finance-owned journal person protocol DTOs.
export type ModeRequest = {
  schemaVersion: "journal-person-sod/1";
  idempotencyKey: string;
  expectedModeGeneration: string;
  reason: string;
};
export type ModeDecision = {
  schemaVersion: "journal-person-sod/1";
  idempotencyKey: string;
  expectedVersion: string;
  requestDigest: string;
  reason: string;
};
export type PersonApprovalInput = {
  schemaVersion: "journal-person-sod/1";
  idempotencyKey: string;
  expectedJournalVersion: string;
  expectedContentVersion: string;
  contentDigest: string;
  requestedExpiresAt: string;
};
export type PersonPostInput = {
  schemaVersion: "journal-person-sod/1";
  idempotencyKey: string;
  expectedJournalVersion: string;
  expectedContentVersion: string;
  contentDigest: string;
  approvalId: string;
};
export type TerminateDraftInput = {
  schemaVersion: "journal-person-sod/1";
  idempotencyKey: string;
  expectedJournalVersion: string;
  expectedContentVersion: string;
  contentDigest: string;
  reason: string;
};
export type Mode = {
  schemaVersion: "journal-person-sod/1";
  tenantId: string;
  bookId: string;
  yearId: string;
  mode: "ACCOUNT_ONLY" | "JOURNAL_PERSON_SOD";
  modeEpoch: string;
  modeGeneration: string;
  effectiveAt: string | null;
  activationRequestId: string | null;
  cashSodEnforcement: "NOT_ENFORCED";
  yearClosePersonProtocol: "UNSUPPORTED";
  pendingActivationRequestId: string | null;
  latestActivationRequestId: string | null;
  currentActorAccountId: string;
  currentActorPersonId: string | null;
  identityAvailability: "AVAILABLE" | "UNBOUND" | "REVOKED" | "UNCONFIGURED";
  activationEligible: boolean;
  blockers: Blocker[];
  capabilities: Capabilities;
};
export type ModeRequestDetail = {
  id: string;
  tenantId: string;
  bookId: string;
  yearId: string;
  status: "PENDING" | "APPROVED" | "REJECTED" | "CANCELLED";
  version: string;
  requestDigest: string;
  requestedBy: string;
  requestedPersonId: string;
  createdAt: string;
  expiresAt: string;
  reason: string;
  decidedBy: string | null;
  decidedPersonId: string | null;
  decidedAt: string | null;
  decisionReason: string | null;
  resultModeEpoch: string | null;
};
export type Identity = {
  accountId: string;
  personId: string;
  bindingId: string;
  bindingVersion: string;
  authorityId: string;
  authorityManifestDigest: string;
  assurance: "ORG_ATTESTED";
};
export type ApprovalReceipt = {
  id: string;
  journalId: string;
  contentVersion: string;
  contentDigest: string;
  approvedJournalVersion: string;
  approvedBy: Identity;
  approvedAt: string;
  expiresAt: string;
  status: "ACTIVE" | "CONSUMED" | "SUPERSEDED";
};
export type PostReceipt = {
  id: string;
  journalId: string;
  approvalId: string;
  contentVersion: string;
  contentDigest: string;
  postedBy: Identity;
  postedAt: string;
  postingSeq: string;
  postingDigest: string;
};
export type JournalPersonContext = {
  schemaVersion: "journal-person-sod/1";
  tenantId: string;
  bookId: string;
  yearId: string;
  journalId: string;
  mode: Mode;
  journalVersion: string;
  contentVersion: string | null;
  contentDigest: string | null;
  snapshotFormatVersion: string | null;
  maker: Identity | null;
  lastEditor: Identity | null;
  currentApproval: ApprovalReceipt | null;
  postReceipt: PostReceipt | null;
  personProofStatus:
    | "ACCOUNT_ONLY_LEGACY"
    | "PERSON_CONTENT_FROZEN"
    | "PERSON_APPROVED"
    | "PERSON_POSTED";
  limitations: string[];
  journalStatus: "DRAFT" | "SUBMITTED" | "APPROVED" | "POSTED" | "CANCELLED";
  contentSnapshot: ContentSnapshot | null;
  administrativeTermination: AdministrativeTermination | null;
  currentActorAccountId: string;
  currentActorPersonId: string | null;
  identityAvailability: "AVAILABLE" | "UNBOUND" | "REVOKED" | "UNCONFIGURED";
};
export type Blocker = { message: string; code: string };
export type Capabilities = {
  manualNormalJournals: true;
  reversals: false;
  legacyApprovePostInPersonMode: false;
  personReject: true;
  closingDrafts: false;
  sourceJournals: false;
  yearClose: false;
};
export type ContentLine = {
  accountId: string;
  credit: string;
  lineNo: number;
  memo: string;
  debit: string;
};
export type ContentDocument = {
  originalLocation: string;
  originalId: string;
  source: string;
  filename: string;
  sha256: string;
  id: string;
};
export type ContentSnapshot = {
  summary: string;
  amountFormatVersion: "V4_8";
  type: "NORMAL";
  lines: ContentLine[];
  reversalOf: null;
  sourceEventId: null;
  closingBasis: null;
  currency: "CNY";
  documents: ContentDocument[];
  date: string;
};
export type AdministrativeTermination = {
  reason: string;
  journalVersion: string;
  decidedAt: string;
  contentVersion: string;
  id: string;
  actor: Identity;
};
