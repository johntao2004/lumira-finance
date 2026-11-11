// Finance-owned closed EV03 first-slice DTOs.
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
export type Original = {
  bookId: string;
  yearId: string;
  originalId: string;
  version: string;
  filename: string;
  sha256: string;
  verificationStatus: "UNVERIFIED_ORIGINAL_BYTES";
  createdBy: string;
};
export type Blocker = { code: string; message: string };
export type Catalog = {
  schemaVersion: "evidence-usage/1";
  tenantId: string;
  legalEntityId: string;
  bookId: string;
  yearId: string;
  journalMode: "ACCOUNT_ONLY" | "JOURNAL_PERSON_SOD";
  currentActor: Actor;
  supportedDocumentType: "ORDINARY_EXPENSE_INVOICE";
  sourceSystem: "ORG_MANUAL_EXPENSE_V1";
  supportedConsumptionPurpose: "EXPENSE_ALLOCATION";
  unsupportedConsumptionPurposes: [
    "SETTLEMENT",
    "ACCOUNTING_CONFIRMATION",
    "TAX_EVIDENCE",
  ];
  crossHomeScopeSupported: false;
  governmentAuthenticityVerified: false;
  bankRefundOrGlCorrectionSupported: false;
  eligibleForNewUse: boolean;
  blockers: Blocker[];
};
export type ReviewFields = {
  documentType: "ORDINARY_EXPENSE_INVOICE";
  issuerKey: string;
  externalIdentityKey: string;
  faceGross: string;
  expenseCapacity: string;
  basisOriginalId: string;
  reason: string;
  singleFileSingleDocumentConfirmed: true;
  allKnownDocumentIdentifiersDeclared: true;
  sameDocumentConfirmed: true;
  sourceType: "ELECTRONIC_ORIGINAL" | "PAPER_SCAN";
  originalLocation: string | null;
};
export type DocumentCreate = {
  schemaVersion: "evidence-usage/1";
  idempotencyKey: string;
  originalId: string;
  fields: ReviewFields;
};
export type ReviewCreate = {
  schemaVersion: "evidence-usage/1";
  idempotencyKey: string;
  expectedDocumentVersion: string;
  fields: ReviewFields;
};
export type Decision = {
  schemaVersion: "evidence-usage/1";
  idempotencyKey: string;
  expectedVersion: string;
  contentDigest: string;
  reason: string;
};
export type AllocationCreate = {
  schemaVersion: "evidence-usage/1";
  idempotencyKey: string;
  documentId: string;
  expectedBasisVersion: string;
  sourceSystem: "ORG_MANUAL_EXPENSE_V1";
  businessChainKey: string;
  sourceOperationKey: string;
  occurredDate: string;
  amount: string;
  purpose: "EXPENSE_ALLOCATION";
  purposeEvidenceOriginalId: string;
  description: string;
};
export type AllocationPatch = {
  schemaVersion: "evidence-usage/1";
  idempotencyKey: string;
  expectedVersion: string;
  contentDigest: string;
  expectedBasisVersion: string;
  occurredDate: string;
  amount: string;
  purposeEvidenceOriginalId: string;
  description: string;
};
export type AllocationAction = {
  schemaVersion: "evidence-usage/1";
  idempotencyKey: string;
  expectedVersion: string;
  contentDigest: string;
  reason: string;
};
export type ReverseCreate = {
  schemaVersion: "evidence-usage/1";
  idempotencyKey: string;
  expectedAllocationVersion: string;
  allocationContentDigest: string;
  amount: string;
  basisOriginalId: string;
  reason: string;
  scopeOfEffect: "EVIDENCE_CAPACITY_ONLY_NOT_BANK_REFUND_OR_GL_CORRECTION";
};
export type Bucket = {
  purpose: "EXPENSE_ALLOCATION" | "SETTLEMENT" | "ACCOUNTING_CONFIRMATION";
  consumptionSupported: boolean;
  qualification: "UNKNOWN" | "ORG_REVIEWED" | "UNSUPPORTED";
  currency: "CNY";
  capacity: string | null;
  reserved: string | null;
  netCommitted: string | null;
  available: string | null;
  version: string;
};
export type Basis = {
  reviewId: string;
  basisVersion: string;
  contentDigest: string;
  documentType: "ORDINARY_EXPENSE_INVOICE";
  issuerKey: string;
  externalIdentityKey: string;
  currency: "CNY";
  faceGross: string;
  expenseCapacity: string;
  sourceOriginal: Original;
  basisOriginal: Original;
  reviewedBy: Identity;
  reviewedAt: string;
  assurance: "ORG_REVIEWED";
  authenticity: "UNVERIFIED";
  sourceType: "ELECTRONIC_ORIGINAL" | "PAPER_SCAN";
  originalLocation: string | null;
};
export type ReviewDetail = {
  id: string;
  documentId: string;
  status: "PENDING" | "APPROVED" | "REJECTED" | "CANCELLED";
  version: string;
  contentDigest: string;
  previousBasisVersion: string | null;
  fields: ReviewContent;
  sourceOriginal: Original;
  basisOriginal: Original;
  preparedBy: Identity;
  createdAt: string;
  expiresAt: string;
  decidedBy: Identity | null;
  decidedAt: string | null;
  decisionReason: string | null;
  publishedBasisVersion: string | null;
  allowedActions: (
    | "create-document"
    | "create-review"
    | "approve-review"
    | "reject-review"
    | "cancel-review"
    | "create-allocation"
    | "edit-allocation"
    | "submit"
    | "confirm"
    | "withdraw"
    | "reject"
    | "admin-terminate"
    | "cancel"
    | "request-reverse"
    | "approve-reverse"
    | "reject-reverse"
    | "cancel-reverse"
  )[];
  blockers: Blocker[];
  previousReviewProposalId: string | null;
};
export type DocumentSummary = {
  id: string;
  tenantId: string;
  legalEntityId: string;
  homeBookId: string;
  homeYearId: string;
  version: string;
  sourceOriginal: Original;
  currentBasis: Basis | null;
  pendingReviewProposalId: string | null;
  latestReviewProposalId: string | null;
  newUseFrozen: boolean;
  authenticity: "UNVERIFIED";
  buckets: Bucket[];
  allowedActions: (
    | "create-document"
    | "create-review"
    | "approve-review"
    | "reject-review"
    | "cancel-review"
    | "create-allocation"
    | "edit-allocation"
    | "submit"
    | "confirm"
    | "withdraw"
    | "reject"
    | "admin-terminate"
    | "cancel"
    | "request-reverse"
    | "approve-reverse"
    | "reject-reverse"
    | "cancel-reverse"
  )[];
  blockers: Blocker[];
  sourceType: "ELECTRONIC_ORIGINAL" | "PAPER_SCAN";
  originalLocation: string | null;
};
export type DocumentDetail = {
  document: DocumentSummary;
  currentActor: Actor;
  identityAliases: {
    documentType: "ORDINARY_EXPENSE_INVOICE";
    issuerKey: string;
    externalIdentityKey: string;
    recordedAt: string;
  }[];
  aliasesTruncated: boolean;
  limitations: string[];
};
export type Usage = {
  id: string;
  submissionId: string;
  purpose: "EXPENSE_ALLOCATION";
  state: "RESERVED" | "COMMITTED" | "RELEASED";
  amount: string;
  reservedAt: string;
  expiresAt: string;
  releasedAt: string | null;
  releaseDecisionId: string | null;
  committedAt: string | null;
  confirmationId: string | null;
};
export type AllocationSummary = {
  id: string;
  documentId: string;
  businessChainId: string;
  businessChainKey: string;
  sourceSystem: "ORG_MANUAL_EXPENSE_V1";
  sourceOperationKey: string;
  purpose: "EXPENSE_ALLOCATION";
  status: "DRAFT" | "SUBMITTED" | "CONFIRMED" | "CANCELLED";
  version: string;
  contentVersion: string;
  contentDigest: string;
  occurredDate: string;
  amount: string;
  basis: Basis;
  purposeEvidence: Original;
  description: string;
  preparedBy: Identity;
  lastEditor: Identity;
  submissionId: string | null;
  reservationState: "RESERVED" | "COMMITTED" | "RELEASED" | null;
  reservationExpiresAt: string | null;
  confirmationId: string | null;
  confirmedBy: Identity | null;
  confirmedAt: string | null;
  reversedAmount: string;
  netConfirmedAmount: string;
  pendingReverseRequestId: string | null;
  latestReverseRequestId: string | null;
  allowedActions: (
    | "create-document"
    | "create-review"
    | "approve-review"
    | "reject-review"
    | "cancel-review"
    | "create-allocation"
    | "edit-allocation"
    | "submit"
    | "confirm"
    | "withdraw"
    | "reject"
    | "admin-terminate"
    | "cancel"
    | "request-reverse"
    | "approve-reverse"
    | "reject-reverse"
    | "cancel-reverse"
  )[];
  blockers: Blocker[];
};
export type AllocationDetail = {
  allocation: AllocationSummary;
  currentUsage: Usage | null;
  currentActor: Actor;
  latestTermination: {
    id: string;
    action: "WITHDRAW" | "REJECT" | "ADMIN_TERMINATE" | "CANCEL";
    actor: Identity;
    reason: string;
    decidedAt: string;
    submissionId: string | null;
    usageId: string | null;
  } | null;
  limitations: string[];
};
export type ReverseDetail = {
  id: string;
  allocationId: string;
  documentId: string;
  businessChainId: string;
  purpose: "EXPENSE_ALLOCATION";
  status: "PENDING" | "APPROVED" | "REJECTED" | "CANCELLED";
  version: string;
  contentDigest: string;
  originalConfirmationId: string;
  originalCommittedUsageId: string;
  expectedAllocationVersion: string;
  amount: string;
  basisOriginal: Original;
  reason: string;
  requestedBy: Identity;
  createdAt: string;
  expiresAt: string;
  reviewedBy: Identity | null;
  reviewedAt: string | null;
  decisionReason: string | null;
  reverseEffectId: string | null;
  scopeOfEffect: "EVIDENCE_CAPACITY_ONLY_NOT_BANK_REFUND_OR_GL_CORRECTION";
  allowedActions: (
    | "create-document"
    | "create-review"
    | "approve-review"
    | "reject-review"
    | "cancel-review"
    | "create-allocation"
    | "edit-allocation"
    | "submit"
    | "confirm"
    | "withdraw"
    | "reject"
    | "admin-terminate"
    | "cancel"
    | "request-reverse"
    | "approve-reverse"
    | "reject-reverse"
    | "cancel-reverse"
  )[];
  blockers: Blocker[];
  originalPreparedBy: Identity;
  originalConfirmedBy: Identity;
  originalConfirmedAmount: string;
  alreadyReversedAmount: string;
  netRemainingBefore: string;
  netRemainingAfterProposed: string;
  previousReverseRequestId: string | null;
};
export type DocumentPage = {
  items: DocumentSummary[];
  nextCursor: string | null;
  generation: string;
  currentActor: Actor;
};
export type AllocationPage = {
  items: AllocationSummary[];
  nextCursor: string | null;
  generation: string;
  currentActor: Actor;
};
export type ReviewContent = {
  documentType: "ORDINARY_EXPENSE_INVOICE";
  issuerKey: string;
  externalIdentityKey: string;
  faceGross: string;
  expenseCapacity: string;
  basisOriginalId: string;
  reason: string;
  singleFileSingleDocumentConfirmed: true;
  allKnownDocumentIdentifiersDeclared: true;
  sameDocumentConfirmed: true;
  sourceType: "ELECTRONIC_ORIGINAL" | "PAPER_SCAN";
  originalLocation: string | null;
};
