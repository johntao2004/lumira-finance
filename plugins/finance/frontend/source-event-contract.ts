export type SourceSummary = {
  id: string;
  sourceSystem: string;
  sourceEventId: string;
  eventType: string;
  effectType: string;
  sourceDocumentId: string;
  sourceDocumentVersion: number;
  accountingDate: string;
  createdAt: string;
  journalId: string;
  effectKey: string;
  payloadDigest: string;
  mappingRuleVersion: string;
  handlerVersion: string;
  status: string;
  journalStatus?: string;
};
export type OutboxItem = {
  id: string;
  eventType: string;
  aggregateId: string;
  aggregateVersion: number;
  state: string;
  attempts: number;
  nextAttemptAt: string | null;
  leaseUntil: string | null;
  lastErrorCode: string | null;
  createdAt: string;
  processedAt: string | null;
  payload?: unknown;
  payloadDigest?: string;
};
export type SourceForm = Record<string, unknown> & {
  sourceSystem: string;
  sourceEventId: string;
  sourceDocumentId: string;
  sourceDocumentVersion: string;
  eventType: string;
  businessDate: string;
  accountingDate: string;
  occurredAt: string;
  reason: string;
  sourceLineId: string;
  businessSemantic: string;
  description: string;
  quantity: string;
  netAmount: string;
  taxAmount: string;
  grossAmount: string;
  summary: string;
  mappingRuleVersion: string;
  handlerVersion: string;
  journalLines: {
    sourceLineId?: string;
    accountId: string;
    debit: string;
    credit: string;
    memo?: string;
  }[];
};
export function sourcePayload(values: SourceForm, eventId: string) {
  return {
    mappingRuleVersion: values.mappingRuleVersion,
    handlerVersion: values.handlerVersion,
    event: {
      schemaVersion: "1.0.0",
      eventId,
      sourceSystem: values.sourceSystem,
      sourceEventId: values.sourceEventId,
      sourceDocumentId: values.sourceDocumentId,
      sourceDocumentVersion: Number(values.sourceDocumentVersion),
      eventType: values.eventType,
      effectType: "CREATE_JOURNAL_DRAFT",
      businessDate: values.businessDate,
      accountingDate: values.accountingDate,
      occurredAt: values.occurredAt,
      currency: "CNY",
      reason: values.reason,
      lines: [
        {
          sourceLineId: values.sourceLineId,
          businessSemantic: values.businessSemantic,
          description: values.description,
          quantity: values.quantity,
          netAmount: values.netAmount,
          taxAmount: values.taxAmount,
          grossAmount: values.grossAmount,
          dimensions: [],
          taxTreatment: "UNDETERMINED",
        },
      ],
      evidenceIds: [],
      reversesSourceEventId: null,
    },
    journal: {
      summary: values.summary,
      lines: values.journalLines.map((line) => ({
        sourceLineId: values.sourceLineId,
        accountId: line.accountId,
        debit: line.debit,
        credit: line.credit,
        memo: line.memo ?? "",
      })),
    },
  };
}
