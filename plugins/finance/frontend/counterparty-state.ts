import type {
  CounterpartyDetail,
  RevisionDetail,
} from "./counterparty-contract";
/** Parent-owned immutable command basis and editable local form; never keyed by fiscal year. */
export type PartyDraft = {
  identity: string;
  kind: "create" | "propose" | "approve" | "reject" | "cancel";
  key: string;
  values: Record<string, unknown>;
  operationId: string;
  revision: RevisionDetail | null;
  party: CounterpartyDetail | null;
};
