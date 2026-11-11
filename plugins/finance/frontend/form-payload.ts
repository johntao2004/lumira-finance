export const formFields = {
  book: [
    "name",
    "tenantId",
    "legalEntityId",
    "legalEntityName",
    "registrationId",
    "standard",
  ],
  account: [
    "code",
    "name",
    "normalSide",
    "category",
    "cashFlowCategory",
    "purpose",
  ],
  member: ["username", "role", "yearScope", "yearIds"],
  tax: ["vatIdentity", "incomeTaxMethod", "region", "notes"],
  closing: ["stage", "date"],
  reject: ["reason"],
  cancel: ["reason"],
  document: ["originalId", "source", "originalLocation"],
  reversal: ["date", "reason"],
  journal: ["date", "summary"],
  year: [],
} as const;
/** Form state includes shared defaults and read-only display metadata. Never transmit those implicitly. */
export function formPayload(
  kind: keyof typeof formFields,
  values: Record<string, unknown>,
) {
  return Object.fromEntries(
    formFields[kind]
      .filter((field) => values[field] !== undefined)
      .map((field) => [field, values[field]]),
  );
}
