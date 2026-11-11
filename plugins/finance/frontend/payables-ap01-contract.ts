// Finance-owned closed typed payable accounting confirmation DTOs.
export type Actor = {
  accountId: string;
  personId: string | null;
  identityAvailability: "AVAILABLE" | "UNBOUND" | "REVOKED" | "UNCONFIGURED";
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
export type Scope = {
  tenantId: string;
  legalEntityId: string;
  bookId: string;
  yearId: string;
  yearNumber: number;
  precisionProfile: "V4" | "LEGACY_V3" | "UNKNOWN";
  journalMode: "ACCOUNT_ONLY" | "JOURNAL_PERSON_SOD";
};
export type Blocker = { code: string; message: string };
export type Original = {
  bookId: string;
  yearId: string;
  id: string;
  version: string;
  filename: string;
  sha256: string;
  createdBy: string;
  authenticity: "UNVERIFIED";
};
export type Account = {
  id: string;
  code: string;
  name: string;
  category: "ASSET" | "LIABILITY" | "EQUITY" | "REVENUE" | "EXPENSE";
  normalSide: "DEBIT" | "CREDIT";
  purpose: "NONE" | "CURRENT_YEAR_PROFIT" | "RETAINED_EARNINGS";
  enabled: boolean;
  semanticDigest: string;
};
export type DocumentExpectation = {
  documentId: string;
  documentVersion: string;
  currentReviewId: string;
  basisVersion: string;
  basisContentDigest: string;
  sourceOriginalVersion: string;
  sourceOriginalSha256: string;
};
export type SupplierExpectation = {
  counterpartyId: string;
  governanceHomeBookId: string;
  registryVersion: string;
  approvedRevisionId: string;
  approvedRevisionDigest: string;
};
export type SupplierSnapshot = {
  expected: SupplierExpectation;
  displayName: string;
  roles: ("CUSTOMER" | "SUPPLIER")[];
  registrationJurisdiction: string;
  registrationIdentifier: string;
  verification: "ORGANIZATION_DECLARED_NOT_GOVERNMENT_VERIFIED";
};
export type ParentExpectation = {
  allocationId: string;
  documentId: string;
  chainId: string;
  version: string;
  contentVersion: string;
  contentDigest: string;
  submissionId: string;
  usageId: string;
  confirmationId: string;
  confirmedAmount: string;
  approvedReversedAmount: string;
  netConfirmedAmount: string;
  documentExpectation: DocumentExpectation;
};
export type SourceSlot = {
  parentAllocationId: string;
  chainId: string;
  sourceSystem: "V15_CONFIRMED_EXPENSE_AP_V1";
  purpose: "ACCOUNTING_CONFIRMATION";
  slotId: string | null;
  slotVersion: string | null;
  latestAttemptId: string | null;
  latestAttemptVersion: string | null;
  activeOriginalConfirmationId: string | null;
  activeRecognitionUsageId: string | null;
  activeOpenItemId: string | null;
};
export type BasisExpectation = {
  basisId: string;
  basisVersion: string;
  contentDigest: string;
  documentExpectation: DocumentExpectation;
};
export type AccountingCapacity = {
  documentId: string;
  purpose: "ACCOUNTING_CONFIRMATION";
  qualification: "UNKNOWN" | "ORG_REVIEWED";
  currency: "CNY";
  capacity: string | null;
  reserved: string | null;
  netCommitted: string | null;
  available: string | null;
  currentBasis: BasisExpectation | null;
  pendingBasisId: string | null;
  latestBasisId: string | null;
  eligibilityVersion: string;
};
export type CommandReceipt = {
  id: string;
  action: string;
  actor: Identity;
  createdAt: string;
  reason: string;
  targetId: string;
  targetVersion: string;
  contentDigest: string;
  fromState: string | null;
  toState: string;
  submissionId: string | null;
  approvalId: string | null;
  postReceiptId: string | null;
  releaseId: string | null;
};
export type BasisContent = {
  expectedDocument: DocumentExpectation;
  faceGrossSnapshot: string;
  accountingCapacity: string;
  basisOriginal: Original;
  recognitionEligibilityReason: string;
  organizationReviewedNotTaxVerified: true;
};
export type BasisSummary = {
  id: string;
  documentId: string;
  version: string;
  revision: string;
  status: "PENDING" | "APPROVED" | "REJECTED" | "CANCELLED";
  contentDigest: string;
  previousProposalId: string | null;
  baseApprovedBasisId: string | null;
  maker: Identity;
  createdAt: string;
};
export type BasisDetail = {
  schemaVersion: "payables-ap01/1";
  scope: Scope;
  currentActor: Actor;
  basis: BasisSummary;
  content: BasisContent;
  capacity: AccountingCapacity;
  decision: CommandReceipt | null;
  allowedActions: (
    | "CREATE_BASIS"
    | "APPROVE_BASIS"
    | "REJECT_BASIS"
    | "CANCEL_BASIS"
    | "CREATE"
    | "CREATE_FULL_REVERSAL"
    | "SUBMIT"
    | "APPROVE"
    | "POST"
    | "WITHDRAW"
    | "REJECT"
    | "CANCEL"
    | "ADMIN_TERMINATE"
  )[];
  blockers: Blocker[];
};
export type EligibleParent = {
  parent: ParentExpectation;
  sourceSlot: SourceSlot;
  purposeOriginal: Original;
  capacity: AccountingCapacity;
  pendingParentReverseId: string | null;
  eligibleForPreparation: boolean;
  blockers: Blocker[];
};
export type Line = {
  lineNo: number;
  account: Account;
  debit: string;
  credit: string;
  memo: string;
};
export type PayableContent = {
  format: "AP_PERSON_CONTENT_PG_V1";
  producerKind: "AP_EXPENSE" | "AP_EXPENSE_REVERSAL";
  rawType: "AP_EXPENSE" | "AP_EXPENSE_REVERSAL";
  source: ParentExpectation;
  slotAtPreparation: SourceSlot;
  supplier: SupplierSnapshot;
  accountingBasis: BasisExpectation;
  accountingDate: string;
  dueDate: string | null;
  currency: "CNY";
  amount: string;
  summary: string;
  serviceAcceptanceOriginal: Original;
  recognitionOriginal: Original;
  recognitionReason: string;
  ordinaryServiceExpenseConfirmed: true;
  supplierMatchesDocumentConfirmed: true;
  serviceAcceptedConfirmed: true;
  noTaxAssetInventoryPayrollConfirmed: true;
  generatedLines: Line[];
  reversesPayableId: string | null;
  reversesJournalId: string | null;
  reversalBasis: ReversalBasis | null;
  payableAccountUseConfirmed: true;
};
export type Submission = {
  id: string;
  payableId: string;
  contentVersion: string;
  contentDigest: string;
  submittedBy: Identity;
  submittedAt: string;
  expiresAt: string;
  recognitionUsageId: string | null;
  parentDependencyId: string | null;
  state: "RESERVED" | "RELEASED" | "COMMITTED" | "REVERSAL_PENDING";
};
export type Approval = {
  id: string;
  payableId: string;
  submissionId: string;
  contentVersion: string;
  contentDigest: string;
  sourceDigest: string;
  approvedBy: Identity;
  approvedAt: string;
  expiresAt: string;
  state: "EFFECTIVE" | "SUPERSEDED" | "EXPIRED";
};
export type PostReceipt = {
  id: string;
  payableId: string;
  journalId: string;
  postingSeq: string;
  journalVersion: string;
  contentVersion: string;
  contentDigest: string;
  submissionId: string;
  approvalId: string;
  recognitionUsageId: string;
  openItemId: string;
  openItemAdjustmentId: string | null;
  parentDependencyId: string;
  postedBy: Identity;
  postedAt: string;
  amount: string;
  reversesPostReceiptId: string | null;
};
export type OpenItem = {
  id: string;
  originalPayableId: string;
  journalId: string;
  postReceiptId: string;
  counterpartyId: string;
  supplierSnapshot: SupplierSnapshot;
  documentId: string;
  parentAllocationId: string;
  chainId: string;
  currency: "CNY";
  recognizedPrincipal: string;
  accountingReversedAmount: string;
  systemOutstanding: string;
  systemOutstandingDefinition: "PRINCIPAL_MINUS_TYPED_ACCOUNTING_REVERSAL_NOT_EXTERNAL_UNPAID_BALANCE";
  externalSettlementQualification: "UNKNOWN_AP02_NOT_IMPLEMENTED";
  externalSettlementAmount: null;
  bankPaidStatus: null;
  dueDate: string | null;
  status: "OPEN" | "ACCOUNTING_REVERSED";
  fullReversalAdjustmentId: string | null;
  version: string;
};
export type PayableSummary = {
  id: string;
  operationId: string;
  producerKind: "AP_EXPENSE" | "AP_EXPENSE_REVERSAL";
  journalId: string;
  rawType: "AP_EXPENSE" | "AP_EXPENSE_REVERSAL";
  journalStatus: "DRAFT" | "SUBMITTED" | "APPROVED" | "POSTED" | "CANCELLED";
  version: string;
  contentVersion: string;
  contentDigest: string;
  sourceDigest: string;
  parentAllocationId: string;
  documentId: string;
  counterpartyId: string;
  accountingDate: string;
  amount: string;
  currentEffect: "NONE" | "RESERVED" | "COMMITTED" | "FULLY_REVERSED";
  predecessorAttemptId: string | null;
  reversesPayableId: string | null;
  pendingReversalAttemptId: string | null;
  postedReversalAttemptId: string | null;
  maker: Identity;
  createdAt: string;
};
export type CommandHistory = {
  items: CommandReceipt[];
  nextCursor: string | null;
};
export type PayableDetail = {
  schemaVersion: "payables-ap01/1";
  scope: Scope;
  currentActor: Actor;
  payable: PayableSummary;
  content: PayableContent;
  currentSourceSlot: SourceSlot;
  capacity: AccountingCapacity;
  currentSupplierStatus:
    "ACTIVE" | "FROZEN" | "INACTIVE" | "UNPUBLISHED" | null;
  sourceFresh: boolean;
  currentSubmission: Submission | null;
  currentApproval: Approval | null;
  postReceipt: PostReceipt | null;
  openItem: OpenItem | null;
  history: CommandHistory;
  allowedActions: (
    | "CREATE_BASIS"
    | "APPROVE_BASIS"
    | "REJECT_BASIS"
    | "CANCEL_BASIS"
    | "CREATE"
    | "CREATE_FULL_REVERSAL"
    | "SUBMIT"
    | "APPROVE"
    | "POST"
    | "WITHDRAW"
    | "REJECT"
    | "CANCEL"
    | "ADMIN_TERMINATE"
  )[];
  blockers: Blocker[];
};
export type BasisCommandResult = {
  commandReceipt: CommandReceipt;
  current: BasisDetail;
  replayed: boolean;
};
export type PayableCommandResult = {
  commandReceipt: CommandReceipt;
  current: PayableDetail;
  replayed: boolean;
};
export type Producer = {
  schemaVersion: "payables-ap01/1";
  scope: Scope;
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
  payableId: string | null;
  producerKind: "AP_EXPENSE" | "AP_EXPENSE_REVERSAL" | null;
  reason: string | null;
};
export type Catalog = {
  schemaVersion: "payables-ap01/1";
  scope: Scope;
  currentActor: Actor;
  supportedPurpose: "ACCOUNTING_CONFIRMATION";
  sourceSystem: "V15_CONFIRMED_EXPENSE_AP_V1";
  businessKind: "ORDINARY_SUPPLIER_SERVICE_EXPENSE";
  parentAmountRule: "FULL_CURRENT_CONFIRMED_NET_ONLY";
  expenseProtocolPurpose: "EXPENSE_ALLOCATION";
  oldExpenseCatalogIsProtocolLocal: true;
  settlementSupported: false;
  bankPaidSupported: false;
  taxDeductionSupported: false;
  fullEnterpriseSubledgerComplete: false;
  canExecuteBase: false;
  yearCloseEnabled: false;
  eligibleForNewUse: boolean;
  allowedActions: (
    | "CREATE_BASIS"
    | "APPROVE_BASIS"
    | "REJECT_BASIS"
    | "CANCEL_BASIS"
    | "CREATE"
    | "CREATE_FULL_REVERSAL"
    | "SUBMIT"
    | "APPROVE"
    | "POST"
    | "WITHDRAW"
    | "REJECT"
    | "CANCEL"
    | "ADMIN_TERMINATE"
  )[];
  blockers: Blocker[];
};
export type OpenItemDetail = {
  schemaVersion: "payables-ap01/1";
  scope: Scope;
  currentActor: Actor;
  item: OpenItem;
  originalPayable: PayableSummary;
  postedReversal: PayableSummary | null;
  blockers: Blocker[];
};
export type Reconciliation = {
  schemaVersion: "payables-ap01/1";
  scope: Scope;
  currentActor: Actor;
  qualification: "AP_PRODUCER_SUBSET_ONLY" | "UNSUPPORTED_DATA";
  principal: string | null;
  accountingReversed: string | null;
  systemOutstanding: string | null;
  typedApGlNetCredit: string | null;
  openItemDifference: string | null;
  unattributedPostedNetOnObservedPayableAccounts: string | null;
  observedLiabilityAccountCount: number;
  externalSettlementAmount: null;
  completeEnterpriseSubledger: false;
  canExecuteBase: false;
  blockers: Blocker[];
};
export type BasisPage = {
  schemaVersion: "payables-ap01/1";
  scope: Scope;
  currentActor: Actor;
  epoch: string;
  generation: string;
  limit: number;
  items: BasisSummary[];
  nextCursor: string | null;
  total: null;
  totalStatus: "NOT_COMPUTED";
};
export type ParentPage = {
  schemaVersion: "payables-ap01/1";
  scope: Scope;
  currentActor: Actor;
  epoch: string;
  generation: string;
  limit: number;
  items: EligibleParent[];
  nextCursor: string | null;
  total: null;
  totalStatus: "NOT_COMPUTED";
};
export type PayablePage = {
  schemaVersion: "payables-ap01/1";
  scope: Scope;
  currentActor: Actor;
  epoch: string;
  generation: string;
  limit: number;
  items: PayableSummary[];
  nextCursor: string | null;
  total: null;
  totalStatus: "NOT_COMPUTED";
};
export type OpenItemPage = {
  schemaVersion: "payables-ap01/1";
  scope: Scope;
  currentActor: Actor;
  epoch: string;
  generation: string;
  limit: number;
  items: OpenItem[];
  nextCursor: string | null;
  total: null;
  totalStatus: "NOT_COMPUTED";
};
export type BasisCreateRequest = {
  schemaVersion: "payables-ap01/1";
  idempotencyKey: string;
  reason: string;
  documentExpectation: DocumentExpectation;
  expectedCurrentBasisId: string | null;
  expectedCurrentBasisVersion: string | null;
  accountingCapacity: string;
  basisOriginalId: string;
  recognitionEligibilityReason: string;
  organizationReviewedNotTaxVerified: true;
};
export type BasisActionRequest = {
  schemaVersion: "payables-ap01/1";
  idempotencyKey: string;
  reason: string;
  expectedVersion: string;
  contentDigest: string;
};
export type CreateRequest = {
  schemaVersion: "payables-ap01/1";
  idempotencyKey: string;
  reason: string;
  operationId: string;
  expectedPredecessorId: string | null;
  expectedPredecessorVersion: string | null;
  expectedParent: ParentExpectation;
  expectedSupplier: SupplierExpectation;
  expectedAccountingBasis: BasisExpectation;
  accountingDate: string;
  dueDate: string | null;
  expenseAccountId: string;
  payableAccountId: string;
  serviceAcceptanceOriginalId: string;
  recognitionOriginalId: string;
  recognitionReason: string;
  summary: string;
  ordinaryServiceExpenseConfirmed: true;
  supplierMatchesDocumentConfirmed: true;
  serviceAcceptedConfirmed: true;
  noTaxAssetInventoryPayrollConfirmed: true;
  payableAccountUseConfirmed: true;
};
export type ActionRequest = {
  schemaVersion: "payables-ap01/1";
  idempotencyKey: string;
  reason: string;
  expectedVersion: string;
  expectedContentVersion: string;
  contentDigest: string;
  sourceDigest: string;
};
export type SubmitRequest = {
  schemaVersion: "payables-ap01/1";
  idempotencyKey: string;
  reason: string;
  expectedVersion: string;
  expectedContentVersion: string;
  contentDigest: string;
  sourceDigest: string;
  requestedExpiresAt: string | null;
};
export type ApproveRequest = {
  schemaVersion: "payables-ap01/1";
  idempotencyKey: string;
  reason: string;
  expectedVersion: string;
  expectedContentVersion: string;
  contentDigest: string;
  sourceDigest: string;
  submissionId: string;
  requestedExpiresAt: string | null;
};
export type PostRequest = {
  schemaVersion: "payables-ap01/1";
  idempotencyKey: string;
  reason: string;
  expectedVersion: string;
  expectedContentVersion: string;
  contentDigest: string;
  sourceDigest: string;
  submissionId: string;
  approvalId: string;
};
export type FullReversalRequest = {
  schemaVersion: "payables-ap01/1";
  idempotencyKey: string;
  reason: string;
  operationId: string;
  expectedPredecessorId: string;
  expectedPredecessorVersion: string;
  expectedOriginalVersion: string;
  expectedOriginalContentDigest: string;
  originalPostReceiptId: string;
  originalOpenItemId: string;
  expectedOpenItemVersion: string;
  accountingReasonOriginalId: string;
};
export type ReversalBasis = {
  originalPayableId: string;
  originalJournalId: string;
  originalPostReceiptId: string;
  originalContentDigest: string;
  originalOpenItemId: string;
  originalOpenItemVersion: string;
  originalRecognitionUsageId: string;
  originalParentDependencyId: string;
  amount: string;
};
