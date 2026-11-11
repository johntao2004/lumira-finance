// Finance-owned closed directory DTOs; no authentication/session evidence is accepted.
export type Configuration = {
  schemaVersion: "1";
  tenantId: string;
  authorityId: string;
  authorityName: string;
  assurance: "ORG_ATTESTED";
  bootstrapManifestSha256: string;
  generation: string;
  businessSodEnforcement: "NOT_ENFORCED";
  authenticationGate: {
    type: "RECENT_FINAL_LOGIN_WITH_TOTP";
    maximumLoginAgeSeconds: 300;
    futureToleranceSeconds: 30;
    actionBound: false;
  };
  supportedOperations: (
    "CREATE_PERSON_AND_BIND" | "BIND_EXISTING_PERSON" | "REVOKE_BINDING"
  )[];
  limitations: string[];
  currentActorPersonId: string;
  currentActorAbilities: ("READ" | "PROPOSE" | "CONFIRM")[];
  currentActorAccountId: string;
};
export type PersonSummary = {
  id: string;
  displayName: string;
  personReference: string;
  assurance: "ORG_ATTESTED";
  createdAt: string;
  version: string;
};
export type Binding = {
  id: string;
  accountId: string;
  personId: string;
  status: "ACTIVE" | "REVOKED";
  governanceSeed: boolean;
  createdAt: string;
  revokedAt: string | null;
  proposalId: string | null;
  version: string;
};
export type PersonDetail = {
  person: PersonSummary;
  bindings: Binding[];
  bindingsTruncated: boolean;
  sourceReference: string;
  sourceSha256: string;
};
export type ProposalSummary = {
  id: string;
  operation:
    "CREATE_PERSON_AND_BIND" | "BIND_EXISTING_PERSON" | "REVOKE_BINDING";
  status: "PENDING" | "APPROVED" | "REJECTED" | "CANCELLED";
  accountId: string;
  personId: string | null;
  displayName: string | null;
  preparedBy: string;
  preparedPersonId: string;
  createdAt: string;
  expiresAt: string;
  version: string;
  contentDigest: string;
  decidedBy: string | null;
  decidedPersonId: string | null;
  decidedAt: string | null;
};
export type ProposalDetail = {
  noAccountTransferConfirmed: true;
  allKnownAccountsDeclared: true;
  proposal: ProposalSummary;
  sourceReference: string;
  sourceSha256: string;
  reason: string;
  decisionReason: string | null;
  effectPersonId: string | null;
  effectBindingId: string | null;
  generation: string;
  businessSodEnforcement: "NOT_ENFORCED";
};
export type PersonPage = {
  items: PersonSummary[];
  nextCursor: string | null;
  generation: string;
};
export type ProposalPage = {
  items: ProposalSummary[];
  nextCursor: string | null;
  generation: string;
};
export type Readiness = {
  tenantId: string;
  directoryConfigured: true;
  assurance: "ORG_ATTESTED";
  generation: string;
  activePersonCount: string;
  activeBindingCount: string;
  businessSodEnforcement: "NOT_ENFORCED";
  legacyApprovalPersonProof: "NOT_BACKFILLED";
  actionBoundStepUp: false;
  limitations: string[];
};
export type CreateProposal = {
  schemaVersion: "1";
  idempotencyKey: string;
  operation:
    "CREATE_PERSON_AND_BIND" | "BIND_EXISTING_PERSON" | "REVOKE_BINDING";
  accountId: string;
  personId: string | null;
  displayName: string | null;
  sourceReference: string;
  sourceSha256: string;
  reason: string;
  noAccountTransferConfirmed: true;
  allKnownAccountsDeclared: true;
};
export type ConfirmProposal = {
  schemaVersion: "1";
  idempotencyKey: string;
  expectedVersion: string;
  contentDigest: string;
  decision: "APPROVE" | "REJECT";
  reason: string;
};
export type CancelProposal = {
  schemaVersion: "1";
  idempotencyKey: string;
  expectedVersion: string;
  contentDigest: string;
  reason: string;
};
