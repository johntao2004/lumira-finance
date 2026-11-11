import { directoryRequestKey } from "./person-directory-rules.ts";
export const evidenceOriginalMaximumBytes = 5 * 1024 * 1024;
export function evidenceOriginalSizeAllowed(size: number) {
  return (
    Number.isSafeInteger(size) &&
    size > 0 &&
    size <= evidenceOriginalMaximumBytes
  );
}
export function evidenceOriginalFingerprint(
  scope: string,
  name: string,
  digest: string,
) {
  if (!/^[a-f0-9]{64}$/.test(digest))
    throw new Error("原件请求摘要格式不可用。");
  return JSON.stringify([`${scope}/originals`, name, digest]);
}
export function evidenceOriginalRequestKey(
  keys: Map<string, string>,
  fingerprint: string,
  createKey: () => string,
) {
  return directoryRequestKey(keys, fingerprint, createKey);
}
