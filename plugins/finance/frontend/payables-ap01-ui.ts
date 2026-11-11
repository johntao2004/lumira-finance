import type { Account } from "./types";
import type { ApStoredOriginal } from "./payables-ap01-views";
export type ApUi = {
  canOwner: boolean;
  canMake: boolean;
  canReview: boolean;
  canPost: boolean;
  newUse: boolean;
  originals: ApStoredOriginal[];
  accounts: Account[];
  changed: () => void;
  refreshOriginals: () => Promise<unknown>;
};
