// Finance-owned closed organization taxpayer registry DTOs.
export type Actor = {
  accountId: string;
  personId: string | null;
  identityAvailability: "AVAILABLE" | "UNBOUND" | "UNAVAILABLE";
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
  currentBookId: string;
  governanceHomeBookId: string;
  bothBooksReadAuthorized: true;
};
export type OriginalReference = {
  yearId: string;
  originalId: string;
  expectedVersion: string;
  expectedSha256: string;
  sourceType: "ELECTRONIC_ORIGINAL" | "PAPER_SCAN";
  originalLocation: string | null;
};
export type OriginalSnapshot = {
  bookId: string;
  yearId: string;
  id: string;
  version: string;
  sha256: string;
  filename: string;
  createdBy: string;
  sourceType: "ELECTRONIC_ORIGINAL" | "PAPER_SCAN";
  originalLocation: string | null;
  authenticity: "UNVERIFIED";
};
export type ProfileInput = {
  displayName: string;
  entityKind: "LEGAL_ENTITY" | "BRANCH" | "OTHER_REGISTERED_TAXPAYER";
  registrationTypeDeclaration: string;
  residencyDeclaration: "RESIDENT" | "NON_RESIDENT" | "UNKNOWN";
  establishedOn: string | null;
  cancelledOn: string | null;
  authorityCodeDeclaration: string | null;
  administrativeDivisionDeclaration: string | null;
  organizationVerificationBasis: string;
  registrationEvidence: OriginalReference;
  singleTaxpayerConfirmed: true;
  allKnownRegistrationIdentifiersDeclared: true;
  organizationReviewedNotGovernmentVerified: true;
};
export type ProfileSnapshot = {
  displayName: string;
  entityKind: "LEGAL_ENTITY" | "BRANCH" | "OTHER_REGISTERED_TAXPAYER";
  registrationTypeDeclaration: string;
  residencyDeclaration: "RESIDENT" | "NON_RESIDENT" | "UNKNOWN";
  establishedOn: string | null;
  cancelledOn: string | null;
  authorityCodeDeclaration: string | null;
  administrativeDivisionDeclaration: string | null;
  organizationVerificationBasis: string;
  registrationEvidence: OriginalSnapshot;
  singleTaxpayerConfirmed: true;
  allKnownRegistrationIdentifiersDeclared: true;
  organizationReviewedNotGovernmentVerified: true;
};
export type TaxIdentity = {
  country: "CN";
  canonicalTaxRegistrationNo: string;
  verification: "ORGANIZATION_DECLARED_NOT_GOVERNMENT_VERIFIED";
};
export type Blocker = { code: string; message: string };
export type RevisionSummary = {
  id: string;
  revision: string;
  version: string;
  status: "PENDING" | "APPROVED" | "REJECTED" | "CANCELLED";
  desiredStatus: "ACTIVE" | "INACTIVE";
  previousRevisionId: string | null;
  baseApprovedRevisionId: string | null;
  contentDigest: string;
  preparedBy: Identity;
  createdAt: string;
};
export type TaxEntitySummary = {
  id: string;
  governanceHomeBookId: string;
  identity: TaxIdentity;
  version: string;
  effectiveStatus: "UNPUBLISHED" | "ACTIVE" | "INACTIVE";
  effectiveDisplayName: string | null;
  approvedRevision: RevisionSummary | null;
  pendingRevisionId: string | null;
  latestRevisionId: string;
  masterDataEligible: boolean;
  financialSourceMappingImplemented: false;
  governmentVerification: "NOT_VERIFIED";
  taxRulesReview: "UNREVIEWED";
  taxEligibilityReview: "UNREVIEWED";
  officialFilingCapability: "NOT_VERIFIED";
  createdBy: string;
  createdAt: string;
};
export type Decision = {
  actor: Identity;
  reason: string;
  decidedAt: string;
  outcome: "APPROVED" | "REJECTED" | "CANCELLED";
};
export type CommandReceipt = {
  id: string;
  taxEntityId: string;
  revisionId: string;
  action: "CREATE" | "PROPOSE_REVISION" | "APPROVE" | "REJECT" | "CANCEL";
  targetVersion: string;
  contentDigest: string;
  actor: Identity;
  reason: string;
  createdAt: string;
};
export type Detail = {
  schemaVersion: "tax-entity/1";
  scope: Scope;
  currentActor: Actor;
  taxEntity: TaxEntitySummary;
  effectiveContent: ProfileSnapshot | null;
  allowedActions: (
    "CREATE" | "PROPOSE_REVISION" | "APPROVE" | "REJECT" | "CANCEL"
  )[];
  blockers: Blocker[];
};
export type RevisionDetail = {
  schemaVersion: "tax-entity/1";
  scope: Scope;
  currentActor: Actor;
  taxEntity: TaxEntitySummary;
  revision: RevisionSummary;
  identity: TaxIdentity;
  content: ProfileSnapshot;
  decision: Decision | null;
  allowedActions: (
    "CREATE" | "PROPOSE_REVISION" | "APPROVE" | "REJECT" | "CANCEL"
  )[];
  blockers: Blocker[];
};
export type CommandResult = {
  commandReceipt: CommandReceipt;
  current: RevisionDetail;
  replayed: boolean;
};
export type Page = {
  schemaVersion: "tax-entity/1";
  tenantId: string;
  legalEntityId: string;
  currentBookId: string;
  currentActor: Actor;
  listingScope: "GOVERNANCE_HOME_BOOK_ONLY";
  readEpoch: string;
  generation: string;
  limit: number;
  items: TaxEntitySummary[];
  nextCursor: string | null;
  total: null;
  totalStatus: "NOT_COMPUTED";
};
export type Catalog = {
  schemaVersion: "tax-entity/1";
  scope: Scope;
  currentActor: Actor;
  registrationNamespace: "ORGANIZATION_DECLARED_TAX_REGISTRATION";
  country: "CN";
  identityPermanentTenantWide: true;
  registrationImplemented: true;
  taxCalculationImplemented: false;
  taxObligationsImplemented: false;
  governmentVerification: "NOT_VERIFIED";
  taxRulesReview: "UNREVIEWED";
  officialFilingCapability: "NOT_VERIFIED";
  taxAmount: null;
  taxAmountState: "NOT_ASSESSED";
  allowedActions: (
    "CREATE" | "PROPOSE_REVISION" | "APPROVE" | "REJECT" | "CANCEL"
  )[];
  limitations: string[];
  taxEligibilityImplemented: false;
  taxFilingImplemented: false;
  taxPaymentImplemented: false;
  officialVerificationImplemented: false;
};
export type CreateRequest = {
  schemaVersion: "tax-entity/1";
  idempotencyKey: string;
  reason: string;
  operationId: string;
  identity: TaxIdentity;
  content: ProfileInput;
};
export type RevisionRequest = {
  schemaVersion: "tax-entity/1";
  idempotencyKey: string;
  reason: string;
  expectedEntityVersion: string;
  expectedLatestRevisionId: string;
  expectedLatestRevisionVersion: string;
  expectedApprovedRevisionId: string | null;
  expectedApprovedContentDigest: string | null;
  desiredStatus: "ACTIVE" | "INACTIVE";
  content: ProfileInput;
};
export type ActionRequest = {
  schemaVersion: "tax-entity/1";
  idempotencyKey: string;
  reason: string;
  expectedEntityVersion: string;
  expectedVersion: string;
  contentDigest: string;
};
