import { JournalPersonWriteGate } from "./journal-person-rules.ts";
import { directoryRequestKey } from "./person-directory-rules.ts";
type RequestScope = {
  identity: string;
  keys: Map<string, string>;
  gate: JournalPersonWriteGate;
};
export type EvidenceRequestLease = { scope: RequestScope; token: symbol };
/** One current authorization boundary, not one cache per modal or selected object. */
export class EvidenceRequestOwner {
  private current: RequestScope | null = null;
  bind(identity: string) {
    if (this.current?.identity !== identity)
      this.current = {
        identity,
        keys: new Map(),
        gate: new JournalPersonWriteGate(),
      };
    return this.current;
  }
  clear() {
    this.current = null;
  }
  key(identity: string, fingerprint: string, createKey: () => string) {
    return directoryRequestKey(
      this.bind(identity).keys,
      fingerprint,
      createKey,
    );
  }
  succeeded(identity: string, fingerprint: string) {
    if (this.current?.identity === identity)
      this.current.keys.delete(fingerprint);
  }
  acquire(identity: string): EvidenceRequestLease | null {
    const scope = this.bind(identity),
      token = scope.gate.acquire();
    return token ? { scope, token } : null;
  }
  release(lease: EvidenceRequestLease) {
    lease.scope.gate.release(lease.token);
  }
  isCurrent(lease: EvidenceRequestLease) {
    return this.current === lease.scope;
  }
  size() {
    return this.current?.keys.size ?? 0;
  }
}

/** The exact parent boundary used by EV03 UI, independent of read revisions. */
export function evidenceRequestIdentity(
  scope: string,
  authHint: string,
  actor: { accountId: string; personId: string | null } | null,
) {
  return JSON.stringify([
    scope,
    authHint,
    actor?.accountId ?? null,
    actor?.personId ?? null,
  ]);
}
