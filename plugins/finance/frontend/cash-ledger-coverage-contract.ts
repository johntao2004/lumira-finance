export type CoverageQuery = {
  scopeRevisionId: string;
  limit?: number;
  cursor?: string;
  primaryStage?:
    | "UNSUPPORTED_LEDGER_FACT"
    | "SCOPE_LINEAGE_MISMATCH"
    | "NO_ACTIVE_CLAIM"
    | "NO_EFFECTIVE_REVIEW"
    | "NO_EFFECTIVE_ALLOCATION"
    | "DEPENDENCY_PENDING"
    | "SUPPORTED_CHAIN";
};
export type CoverageScope = {
  tenantId: string;
  legalEntityId: string;
  bookId: string;
  yearId: string;
  scopeRevisionId: string;
  approvedScopeVersion: number;
  scopeContentDigest: string;
  standard: "CAS" | "SMALL";
  templateId: "CAS-CF-2019-A2" | "SBE-CF-2011";
  periodStart: string;
  periodEnd: string;
  currency: "CNY";
  reportScope: "SEPARATE";
  includedAccountCount: number;
  excludedAccountCount: number;
  yearStatus: "OPEN" | "CLOSING" | "CLOSED_PENDING_ARCHIVE" | "CLOSED";
  periodStatus: "OPEN" | "CLOSED";
};
export type CoverageYearWatermark = {
  postingSeq: number;
  openingVersion: number;
  baselineVersion: number;
  sourceCloseVersion: number;
  legacyUnsequencedFacts: boolean;
};
export type CoverageAsOfWatermark = {
  actorId: string;
  authorizationContextDigest: string;
  scopeRevisionId: string;
  approvedScopeVersion: number;
  scopeContentDigest: string;
  periodStart: string;
  periodEnd: string;
  standard: "CAS" | "SMALL";
  currency: "CNY";
  reportScope: "SEPARATE";
  globalScopeVersion: number;
  year: CoverageYearWatermark;
  registrationVersion: number;
  yearMatchVersion: number;
  sourceReviewVersion: number;
  allocationVersion: number;
  policyPublicationVersion: number;
  sourceGenerationDigest: string;
  workflowDigest: string;
  populationDigest: string;
  evidenceStateDigest: string;
  queryRulesVersion: "ledger-cash-coverage-preview/1";
};
export type CoverageStageTotal = {
  rowCount: number;
  inCount: number;
  outCount: number;
  unknownDirectionCount: number;
  inAmount: string;
  outAmount: string;
};
export type CoverageStageTotals = {
  UNSUPPORTED_LEDGER_FACT: CoverageStageTotal;
  SCOPE_LINEAGE_MISMATCH: CoverageStageTotal;
  NO_ACTIVE_CLAIM: CoverageStageTotal;
  NO_EFFECTIVE_REVIEW: CoverageStageTotal;
  NO_EFFECTIVE_ALLOCATION: CoverageStageTotal;
  DEPENDENCY_PENDING: CoverageStageTotal;
  SUPPORTED_CHAIN: CoverageStageTotal;
};
export type CoverageSummary = {
  populationRowCount: number;
  displayMatchedRowCount: number;
  knownDirectionalRowCount: number;
  unknownDirectionRowCount: number;
  ledgerDebitTotal: string | null;
  ledgerCreditTotal: string | null;
  ledgerInTotal: string;
  ledgerOutTotal: string;
  supportedInTotal: string;
  supportedOutTotal: string;
  directionalTotalsStatus: "COMPLETE" | "PARTIAL_UNSUPPORTED_ROWS";
  stageTotals: CoverageStageTotals;
};
export type CoverageRaw = {
  id: string;
  status: "REGISTERED" | "VOIDED";
  version: number;
  contentDigest: string;
  sourceSystem:
    | "BANK_STATEMENT_MANUAL_V1"
    | "CASH_BOOK_MANUAL_V1"
    | "CASH_RECEIPT_MANUAL_V1";
  sourceMovementKey: string;
  sourceDocumentId: string;
  sourceRowReference: string;
  sourceOriginalId: string;
  sourceOriginalSha256: string;
  scopeRevisionId: string;
  scopeVersion: number;
  scopeDigest: string;
};
export type CoverageClaim = {
  id: string;
  kind: "RAW_REGISTRATION" | "MATCH_CORRECTION";
  correctionId: string | null;
  state: "ACTIVE";
  generation: number;
  contentDigest: string;
  sourceMatchGeneration: number;
};
export type CoverageReview = {
  id: string;
  status:
    "DRAFT" | "SUBMITTED" | "APPROVED" | "REJECTED" | "CANCELLED" | "RELEASED";
  version: number;
  contentDigest: string;
  activeUsageId: string | null;
};
export type CoverageAllocation = {
  id: string;
  status:
    | "DRAFT"
    | "SUBMITTED"
    | "APPROVED"
    | "REJECTED"
    | "CANCELLED"
    | "RELEASED"
    | "SUPERSEDED";
  version: number;
  contentDigest: string;
  activeDependencyId: string | null;
  pointerVersion: number | null;
  normalizedTotal: string;
  lineCount: number;
};
export type CoveragePending = {
  matchWithdrawalIds: Array<string>;
  reviewReleaseIds: Array<string>;
  allocationReleaseIds: Array<string>;
  replacementIds: Array<string>;
  reviewProposalIds: Array<string>;
  allocationProposalIds: Array<string>;
};
export type CoverageIssue = {
  code:
    | "MISSING_POSTING"
    | "UNSEQUENCED_POSTING"
    | "INVALID_CASH_LEG"
    | "LEGACY_PRECISION_UNSUPPORTED"
    | "AMOUNT_OVERFLOW"
    | "SCOPE_LINEAGE_MISMATCH"
    | "STALE_ACCOUNT"
    | "STALE_EVIDENCE"
    | "NO_ACTIVE_CLAIM"
    | "NO_EFFECTIVE_REVIEW"
    | "NO_EFFECTIVE_ALLOCATION"
    | "PENDING_MATCH_WITHDRAWAL"
    | "PENDING_REVIEW_RELEASE"
    | "PENDING_ALLOCATION_RELEASE"
    | "PENDING_REPLACEMENT"
    | "AWAITING_REVIEW"
    | "AWAITING_ALLOCATION"
    | "COMPLEX_FACT_REQUIRES_JUDGMENT"
    | "TYPED_LINK_INTEGRITY_FAILURE";
  message: string;
  severity: "BLOCKING" | "JUDGMENT_REQUIRED";
  relatedType:
    | "JOURNAL"
    | "ORIGINAL"
    | "SCOPE"
    | "RAW"
    | "CLAIM"
    | "CORRECTION"
    | "REVIEW"
    | "ALLOCATION"
    | "RELEASE"
    | "REPLACEMENT"
    | null;
  relatedId: string | null;
};
export type CoverageRow = {
  journalId: string;
  lineNo: number;
  accountId: string;
  accountingDate: string;
  journalVersion: number;
  postingSeq: number | null;
  postingDigest: string | null;
  number: number | null;
  amountFormatVersion: "V4_8" | "LEGACY_6" | "UNKNOWN";
  debit: string | null;
  credit: string | null;
  direction: "IN" | "OUT" | "UNKNOWN";
  normalizedAmount: string | null;
  primaryStage:
    | "UNSUPPORTED_LEDGER_FACT"
    | "SCOPE_LINEAGE_MISMATCH"
    | "NO_ACTIVE_CLAIM"
    | "NO_EFFECTIVE_REVIEW"
    | "NO_EFFECTIVE_ALLOCATION"
    | "DEPENDENCY_PENDING"
    | "SUPPORTED_CHAIN";
  issues: Array<CoverageIssue>;
  currentChainEffective: boolean;
  newUseFrozen: boolean;
  raw: CoverageRaw | null;
  claim: CoverageClaim | null;
  review: CoverageReview | null;
  allocation: CoverageAllocation | null;
  pending: CoveragePending;
};
export type CoveragePreview = {
  schemaVersion: "ledger-cash-coverage-preview/1";
  mode: "READ_ONLY_PREVIEW";
  pointInTimeOnly: true;
  resultStatus: "DIFFERENCES_FOUND" | "LEDGER_LINKS_SUPPORTED" | "UNDETERMINED";
  scope: CoverageScope;
  asOfWatermark: CoverageAsOfWatermark;
  contextDigest: string;
  summary: CoverageSummary;
  items: Array<CoverageRow>;
  nextCursor: string | null;
  externalSourceCompleteness: "MISSING";
  coverageVerified: false;
  formalReportContributionEnabled: false;
  reportPublishable: false;
  limitations: Array<string>;
};
export const coverageStages = {
  UNSUPPORTED_LEDGER_FACT: "账务事实不支持 / 待判断",
  SCOPE_LINEAGE_MISMATCH: "范围谱系或证据不一致",
  NO_ACTIVE_CLAIM: "无有效现金匹配声明",
  NO_EFFECTIVE_REVIEW: "无有效人工复核",
  NO_EFFECTIVE_ALLOCATION: "无有效组成分配",
  DEPENDENCY_PENDING: "依赖待审（仍可能有效，新用冻结）",
  SUPPORTED_CHAIN: "受限账务链路有支持（非全量完成）",
} as const;
export function coverageMoney(value: string | null | undefined) {
  return value == null ? "未知 / 缺失" : value;
}
export function coverageDirection(value: string) {
  return value === "IN"
    ? "账务流入腿"
    : value === "OUT"
      ? "账务流出腿"
      : "未知方向";
}
const uuid = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
export function coverageQuery(
  scopeRevisionId: string,
  stage: string,
  cursor: string | null,
) {
  if (!uuid.test(scopeRevisionId))
    throw new Error("请选择实际已批准的月度现金范围");
  if (stage && !Object.hasOwn(coverageStages, stage))
    throw new Error("无效差异阶段，请重新选择");
  if (cursor !== null && (!cursor || cursor.length > 4000))
    throw new Error("分页游标不可用，请重新读取首屏");
  const query = new URLSearchParams({ scopeRevisionId, limit: "20" });
  if (stage) query.set("primaryStage", stage);
  if (cursor !== null) query.set("cursor", cursor);
  return query.toString();
}
export function checkCoveragePage(
  data: CoveragePreview,
  context: {
    bookId: string;
    yearId: string;
    scopeRevisionId: string;
    contextDigest: string | null;
  },
) {
  if (
    data.schemaVersion !== "ledger-cash-coverage-preview/1" ||
    data.mode !== "READ_ONLY_PREVIEW" ||
    data.pointInTimeOnly !== true ||
    data.externalSourceCompleteness !== "MISSING" ||
    data.coverageVerified !== false ||
    data.formalReportContributionEnabled !== false ||
    data.reportPublishable !== false
  )
    throw new Error("预览边界与已冻结协议不符，未展示结果；请联系管理员");
  if (
    data.scope.bookId !== context.bookId ||
    data.scope.yearId !== context.yearId ||
    data.scope.scopeRevisionId !== context.scopeRevisionId ||
    !/^[a-f0-9]{64}$/.test(data.contextDigest) ||
    (context.contextDigest !== null &&
      context.contextDigest !== data.contextDigest)
  )
    throw new Error("CASH_COVERAGE_PREVIEW_STALE");
  return data;
}
export function coverageResultLabel(status: string) {
  if (status === "UNDETERMINED") return "含无法确定的账务事实 / 待专业判断";
  if (status === "DIFFERENCES_FOUND") return "已记账行存在链路差异";
  if (status === "LEDGER_LINKS_SUPPORTED")
    return "本次受限账务行链路均有支持；外部总体仍缺失";
  return "预览结果状态未知，不能推断已完成";
}
export type CoverageDrillKind =
  "JOURNAL" | "SCOPE" | "RAW" | "CORRECTION" | "REVIEW" | "ALLOCATION";
export function coverageDrillPath(
  scope: string,
  kind: CoverageDrillKind,
  id: string,
) {
  if (!uuid.test(id)) throw new Error("关联对象身份无效，不能下钻");
  const paths = {
    JOURNAL: "journals",
    SCOPE: "cashflow/scope-revisions",
    RAW: "cashflow/movement-registrations",
    CORRECTION: "cashflow/match-corrections",
    REVIEW: "cashflow/source-reviews",
    ALLOCATION: "cashflow/allocations",
  };
  if (!Object.hasOwn(paths, kind))
    throw new Error("该关联尚无可用只读详情入口");
  return `${scope}/${paths[kind]}/${id}`;
}
export function coverageOriginals(kind: CoverageDrillKind, value: unknown) {
  const originals = new Map<
    string,
    { id: string; filename: string; sha256: string | null }
  >();
  function object(v: unknown): Record<string, unknown> {
    return v && typeof v === "object" && !Array.isArray(v)
      ? (v as Record<string, unknown>)
      : {};
  }
  function add(id: unknown, filename: unknown, sha256: unknown) {
    if (typeof id === "string" && uuid.test(id))
      originals.set(id, {
        id,
        filename: typeof filename === "string" ? filename : `original-${id}`,
        sha256: typeof sha256 === "string" ? sha256 : null,
      });
  }
  const data = object(value);
  if (kind === "RAW") {
    const o = object(data.originalSnapshot);
    add(o.id, o.filename, o.sha256);
  }
  if (kind === "SCOPE" && Array.isArray(data.items))
    for (const value of data.items) {
      const item = object(value);
      add(item.evidenceOriginalId, item.originalFilename, item.originalSha256);
    }
  if (kind === "REVIEW") {
    const snapshot = object(data.claimSnapshot);
    for (const field of ["sourceOriginal", "scopeOriginal"]) {
      const o = object(snapshot[field]);
      add(o.originalId, o.filename, o.sha256);
    }
    if (Array.isArray(data.evidence))
      for (const value of data.evidence) {
        const o = object(value);
        add(o.originalId, o.filename, o.sha256);
      }
  }
  if (kind === "ALLOCATION" && Array.isArray(data.lines))
    for (const value of data.lines) {
      const o = object(object(value).purposeEvidence);
      add(o.originalId, o.filename, o.sha256);
    }
  return [...originals.values()];
}
