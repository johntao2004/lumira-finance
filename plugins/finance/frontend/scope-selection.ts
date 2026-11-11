/** Successful server lists are authoritative; pending lists must not revoke a selection. */
export function revokedSelection(
  id: string,
  success: boolean,
  allowed: readonly { id: string }[] | undefined,
) {
  return Boolean(
    id && success && allowed && !allowed.some((item) => item.id === id),
  );
}
