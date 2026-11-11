import type { RevisionDetail, OriginalReference } from "./tax-entity-contract";
export type TaxDraft = {
  identity: string;
  key: string;
  kind: "create" | "propose" | "approve" | "reject" | "cancel";
  operationId: string;
  values: Record<string, unknown>;
  revision: RevisionDetail | null;
  evidence: OriginalReference | null;
};
