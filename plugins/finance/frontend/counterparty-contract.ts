// Finance-owned closed organization counterparty DTOs; frozen MD02 contract.
export type Actor = {
  accountId: string;
  personId: string | null;
  identityAvailability: "AVAILABLE" | "UNBOUND" | "REVOKED" | "UNCONFIGURED";
};
export type RegistrationIdentity = {
  namespace: "ORGANIZATION_DECLARED_REGISTRATION";
  jurisdiction: string;
  identifier: string;
  verification: "ORGANIZATION_DECLARED_NOT_GOVERNMENT_VERIFIED";
};
export type Content = {
  displayName: string;
  roles: ("CUSTOMER" | "SUPPLIER")[];
  settlementTerms: string | null;
  contractDescription: string | null;
  relatedPartyStatus: "UNKNOWN" | "RELATED" | "NOT_RELATED";
  relatedPartyBasis: string | null;
  registrationBasis: string;
};
export type Scope = {
  tenantId: string;
  legalEntityId: string;
  currentBookId: string;
  governanceHomeBookId: string;
  bothBooksReadAuthorized: true;
};
export type Blocker = { code: string; message: string };
export type RevisionRef = {
  id: string;
  revision: string;
  version: string;
  status: "PENDING" | "APPROVED" | "REJECTED" | "CANCELLED";
  desiredStatus: "ACTIVE" | "FROZEN" | "INACTIVE";
  contentDigest: string;
};
export type CounterpartySummary = {
  id: string;
  governanceHomeBookId: string;
  registryVersion: string;
  identity: RegistrationIdentity;
  effectiveStatus: "ACTIVE" | "FROZEN" | "INACTIVE" | null;
  effectiveDisplayName: string | null;
  effectiveRevision: RevisionRef | null;
  pendingRevisionId: string | null;
  latestRevisionId: string;
  newUseEligible: boolean;
  createdBy: string;
  createdAt: string;
};
export type CounterpartyDetail = {
  schemaVersion: "counterparty/1";
  scope: Scope;
  currentActor: Actor;
  counterparty: CounterpartySummary;
  effectiveContent: Content | null;
  allowedActions: (
    "CREATE" | "PROPOSE_REVISION" | "APPROVE" | "REJECT" | "CANCEL"
  )[];
  blockers: Blocker[];
  consumerImplemented: false;
  bankAccountSupported: false;
  externalIdentityVerified: false;
};
export type Decision = {
  id: string;
  kind: "APPROVE" | "REJECT" | "CANCEL";
  actorAccountId: string;
  actorPersonId: string;
  reason: string;
  decidedAt: string;
};
export type RevisionDetail = {
  schemaVersion: "counterparty/1";
  scope: Scope;
  currentActor: Actor;
  counterpartyId: string;
  identity: RegistrationIdentity;
  revision: RevisionRef;
  previousRevisionId: string | null;
  baseApprovedRevisionId: string | null;
  content: Content;
  reason: string;
  makerAccountId: string;
  makerPersonId: string;
  createdAt: string;
  decision: Decision | null;
  allowedActions: (
    "CREATE" | "PROPOSE_REVISION" | "APPROVE" | "REJECT" | "CANCEL"
  )[];
  blockers: Blocker[];
  counterparty: CounterpartySummary;
};
export type List = {
  schemaVersion: "counterparty/1";
  currentActor: Actor;
  tenantId: string;
  legalEntityId: string;
  currentBookId: string;
  registryEpoch: string;
  generation: string;
  limit: number;
  items: CounterpartySummary[];
  nextCursor: string | null;
  total: null;
  totalStatus: "NOT_COMPUTED";
};
export type RevisionList = {
  schemaVersion: "counterparty/1";
  scope: Scope;
  currentActor: Actor;
  counterpartyId: string;
  registryEpoch: string;
  generation: string;
  limit: number;
  items: RevisionRef[];
  nextCursor: string | null;
  total: null;
  totalStatus: "NOT_COMPUTED";
};
export type Create = {
  schemaVersion: "counterparty/1";
  idempotencyKey: string;
  reason: string;
  operationId: string;
  identity: RegistrationIdentity;
  content: Content;
  organizationDeclarationConfirmed: true;
  identityNotPlaceholderConfirmed: true;
};
export type ProposeRevision = {
  schemaVersion: "counterparty/1";
  idempotencyKey: string;
  reason: string;
  expectedRegistryVersion: string;
  baseApprovedRevisionId: string | null;
  baseApprovedContentDigest: string | null;
  previousRevisionId: string;
  desiredStatus: "ACTIVE" | "FROZEN" | "INACTIVE";
  content: Content;
};
export type Action = {
  schemaVersion: "counterparty/1";
  idempotencyKey: string;
  reason: string;
  expectedRegistryVersion: string;
  expectedRevisionVersion: string;
  contentDigest: string;
};
