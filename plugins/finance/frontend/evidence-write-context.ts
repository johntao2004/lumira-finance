import { createContext } from "react";
import type { EvidenceRequestOwner } from "./evidence-request-state";
export const EvidenceWriteContext = createContext<{
  owner: EvidenceRequestOwner;
  identity: string;
} | null>(null);
