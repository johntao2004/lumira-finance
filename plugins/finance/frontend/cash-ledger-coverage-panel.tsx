import { useEffect, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Alert,
  Button,
  Collapse,
  Descriptions,
  Modal,
  Select,
  Space,
  Table,
  Tag,
} from "antd";
import { api, ApiError } from "@/platform/api/client";
import { useMovementEvidence } from "./movement-evidence";
import type { CashScopeSummary } from "./cash-scope-contract";
import {
  coverageStages,
  coverageMoney,
  coverageDirection,
  coverageQuery,
  checkCoveragePage,
  coverageResultLabel,
  coverageDrillPath,
  coverageOriginals,
  type CoveragePreview,
  type CoverageRow,
  type CoverageIssue,
  type CoverageStageTotal,
  type CoverageDrillKind,
} from "./cash-ledger-coverage-contract";
const errors: Record<string, string> = {
  CASH_COVERAGE_PREVIEW_STALE:
    "取数上下文已变化，整组旧页已移除。请显式重新读取首屏，不续接旧页。",
  CASH_COVERAGE_SCOPE_UNAVAILABLE:
    "所选月度范围不可用于预览，不能按零行处理。请刷新并选择真实已批准范围。",
  PREVIEW_LIMIT_EXCEEDED:
    "预览总体或关联证据超过本片受控上限，未截断取数，也未返回零差异。请联系管理员处理范围。",
  PREVIEW_TOTAL_OVERFLOW:
    "总体金额超出本片输出容量，未舍入、截断或改成零。请联系管理员。",
};
type PageRequest = {
  scopeId: string;
  stage: string;
  cursor: string | null;
  contextDigest: string | null;
  revision: number;
};
type Drill = { kind: CoverageDrillKind; id: string };
type Props = {
  bookId: string;
  yearId: string;
  active: boolean;
  canRead: boolean;
  readable: (error: unknown) => string;
  onReadDenied: () => void;
};
export function CashLedgerCoveragePanel({
  bookId,
  yearId,
  active,
  canRead,
  readable,
  onReadDenied,
}: Props) {
  const scope = `/finance/v1/books/${bookId}/years/${yearId}`;
  const prefix = ["finance", bookId, yearId, "cash-ledger-coverage"];
  const previewPrefix = [...prefix, "preview"];
  const client = useQueryClient();
  const [scopeCursor, setScopeCursor] = useState<string | null>(null);
  const [scopeId, setScopeId] = useState("");
  const [stage, setStage] = useState("");
  const [request, setRequest] = useState<PageRequest | null>(null);
  const [row, setRow] = useState<CoverageRow | null>(null);
  const [drill, setDrill] = useState<Drill | null>(null);
  const [warning, setWarning] = useState("");
  const revision = useRef(0);
  useEffect(
    () => () => {
      void client.cancelQueries({ queryKey: prefix });
      client.removeQueries({ queryKey: prefix });
    },
    [bookId, yearId, client],
  );
  const scopes = useQuery({
    queryKey: [...prefix, "scopes", scopeCursor],
    enabled: active && canRead,
    retry: false,
    queryFn: ({ signal }) =>
      api<{ items: CashScopeSummary[]; nextCursor: string | null }>(
        `${scope}/cashflow/scope-revisions?limit=20${scopeCursor ? `&cursor=${encodeURIComponent(scopeCursor)}` : ""}`,
        { signal },
      ),
  });
  const preview = useQuery({
    queryKey: [...previewPrefix, request],
    enabled: active && canRead && !!request,
    retry: false,
    staleTime: Infinity,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
    queryFn: async ({ signal }) => {
      const selected = request!;
      const data = await api<CoveragePreview>(
        `${scope}/cashflow/ledger-coverage-preview?${coverageQuery(selected.scopeId, selected.stage, selected.cursor)}`,
        { signal },
      );
      return checkCoveragePage(data, {
        bookId,
        yearId,
        scopeRevisionId: selected.scopeId,
        contextDigest: selected.contextDigest,
      });
    },
  });
  const related = useQuery({
    queryKey: [...prefix, "related", drill],
    enabled: active && canRead && !!drill,
    retry: false,
    queryFn: ({ signal }) =>
      api<unknown>(coverageDrillPath(scope, drill!.kind, drill!.id), {
        signal,
      }),
  });
  const queries = [scopes, preview, related];
  const denied = queries.some(
    (q) => q.isError && q.error instanceof ApiError && q.error.status === 403,
  );
  function message(cause: unknown) {
    return cause instanceof Error && errors[cause.message]
      ? errors[cause.message]
      : readable(cause);
  }
  const evidence = useMovementEvidence(scope, false, onReadDenied, message);
  function clearPreview(text = "") {
    void client.cancelQueries({ queryKey: previewPrefix });
    client.removeQueries({ queryKey: previewPrefix });
    void client.cancelQueries({ queryKey: [...prefix, "related"] });
    client.removeQueries({ queryKey: [...prefix, "related"] });
    setRequest(null);
    setRow(null);
    setDrill(null);
    setWarning(text);
  }
  useEffect(() => {
    if (denied) onReadDenied();
  }, [denied, onReadDenied]);
  useEffect(() => {
    if (preview.isError && request) clearPreview(message(preview.error));
  }, [preview.isError, preview.error, request]);
  useEffect(() => {
    if (scopes.isError) clearPreview(message(scopes.error));
  }, [scopes.isError, scopes.error]);
  useEffect(() => {
    if (
      scopeId &&
      scopes.isSuccess &&
      !scopes.data.items.some(
        (item) => item.id === scopeId && item.status === "APPROVED",
      )
    ) {
      clearPreview(
        "所选范围不再出现在当前已授权批准列表，旧预览已移除。请重新选择真实月度范围。",
      );
      setScopeId("");
    }
  }, [scopeId, scopes.isSuccess, scopes.data]);
  useEffect(() => {
    if (!canRead) {
      clearPreview();
      void client.cancelQueries({ queryKey: prefix });
      client.removeQueries({ queryKey: prefix });
    }
  }, [canRead]);
  const result =
    request && !preview.isError && !preview.isFetching
      ? preview.data
      : undefined;
  function firstPage() {
    if (!canRead || denied || !scopeId || scopes.isError || preview.isFetching)
      return;
    clearPreview();
    setRequest({
      scopeId,
      stage,
      cursor: null,
      contextDigest: null,
      revision: ++revision.current,
    });
  }
  function nextPage() {
    if (!result?.nextCursor || !request || preview.isFetching || denied) return;
    setRow(null);
    setDrill(null);
    setWarning("");
    setRequest({
      scopeId: request.scopeId,
      stage: request.stage,
      cursor: result.nextCursor,
      contextDigest: result.contextDigest,
      revision: ++revision.current,
    });
  }
  function openRelated(kind: CoverageDrillKind, id: string) {
    setDrill({ kind, id });
    if (drill?.id === id && drill.kind === kind) void related.refetch();
  }
  function frozen(value: unknown, label: string) {
    return (
      <Collapse
        items={[
          {
            key: "value",
            label,
            children: (
              <pre style={{ whiteSpace: "pre-wrap", overflowWrap: "anywhere" }}>
                {JSON.stringify(value, null, 2)}
              </pre>
            ),
          },
        ]}
      />
    );
  }
  function issues(items: CoverageIssue[]) {
    if (!items.length)
      return <span>本次行级检查无已列问题（非外部完整性结论）</span>;
    return (
      <ul style={{ paddingInlineStart: 20, margin: 0 }}>
        {items.map((issue, i) => (
          <li key={`${issue.code}:${i}`} style={{ overflowWrap: "anywhere" }}>
            <strong>
              {issue.severity === "JUDGMENT_REQUIRED" ? "待判断" : "阻断"}
            </strong>{" "}
            · {issue.message}
            <br />
            <small>
              {issue.code}
              {issue.relatedType
                ? ` / ${issue.relatedType} / ${issue.relatedId ?? "关联身份缺失"}`
                : ""}
            </small>
          </li>
        ))}
      </ul>
    );
  }
  function chainButtons(item: CoverageRow) {
    return (
      <Space wrap>
        <Button onClick={() => openRelated("JOURNAL", item.journalId)}>
          只读凭证详情
        </Button>
        <Button
          onClick={() => openRelated("SCOPE", result!.scope.scopeRevisionId)}
        >
          只读月度范围
        </Button>
        {item.raw && (
          <>
            <Button onClick={() => openRelated("RAW", item.raw!.id)}>
              原登记详情（
              {item.raw.status === "VOIDED" ? "永久作废" : "未核实登记"}）
            </Button>
            <Button
              onClick={() =>
                void evidence.download(
                  item.raw!.sourceOriginalId,
                  `source-${item.raw!.sourceOriginalId}`,
                )
              }
            >
              下载来源实际原件
            </Button>
          </>
        )}
        {item.claim?.correctionId && (
          <Button
            onClick={() => openRelated("CORRECTION", item.claim!.correctionId!)}
          >
            只读有效更正详情
          </Button>
        )}
        {item.review && (
          <Button onClick={() => openRelated("REVIEW", item.review!.id)}>
            只读人工复核详情
          </Button>
        )}
        {item.allocation && (
          <Button
            onClick={() => openRelated("ALLOCATION", item.allocation!.id)}
          >
            只读分配与证据详情
          </Button>
        )}
      </Space>
    );
  }
  const stageRows = result
    ? Object.entries(coverageStages).map(([key, label]) => ({
        key,
        label,
        ...result.summary.stageTotals[key as keyof typeof coverageStages],
      }))
    : [];
  const relatedOriginals =
    drill && !related.isError
      ? coverageOriginals(drill.kind, related.data)
      : [];
  if (!canRead || denied) return null;
  return (
    <Space
      orientation="vertical"
      style={{ width: "100%", overflowWrap: "anywhere" }}
    >
      <Alert
        type="info"
        title="已记账现金行差异：只读即时预览"
        description="从所选已批准自然月范围内全部已记账现金行枚举差异，不只统计已登记来源。仅本次数据库视图，不是冻结、批准或可供报表消费的工作包。取数后业务仍可变化，跨页变化须重新首屏读取；本页不写入任何财务或依赖状态。"
      />
      <Alert
        type="warning"
        title="外部来源总体缺失：MISSING；不能确认现金覆盖完整"
        description="未取得全部账户、全部文件及全文件行总体。已上传字节、Raw未核实标记或账务链路有支持均不等于银行验真、对账完整或现金流完成。不参与正式报表，也不可发布报表。证据水位仅核对原件元数据，预览未重新校验实际文件字节；可另行下载复核。"
      />
      <Space wrap>
        <Select
          aria-label="预览已批准自然月现金范围"
          placeholder="选择实际已批准自然月范围"
          style={{ width: "min(100%, 520px)", minWidth: 240 }}
          value={scopeId || undefined}
          options={(scopes.isError ? [] : (scopes.data?.items ?? []))
            .filter((s) => s.status === "APPROVED")
            .map((s) => ({
              value: s.id,
              label: `${s.periodStart} 至 ${s.periodEnd} · 批准水位${s.scopeVersion ?? "缺失"} · ${s.id}`,
            }))}
          onChange={(id) => {
            clearPreview();
            setScopeId(id);
          }}
        />
        <Button
          loading={scopes.isFetching}
          onClick={() => {
            clearPreview();
            void scopes.refetch();
          }}
        >
          刷新范围列表并清除旧预览
        </Button>
        <Button
          disabled={!scopeCursor || scopes.isFetching}
          onClick={() => {
            clearPreview();
            setScopeId("");
            setScopeCursor(null);
          }}
        >
          范围首屏
        </Button>
        <Button
          disabled={
            !scopes.data?.nextCursor || scopes.isError || scopes.isFetching
          }
          onClick={() => {
            clearPreview();
            setScopeId("");
            setScopeCursor(scopes.data?.nextCursor ?? null);
          }}
        >
          下一页范围
        </Button>
      </Space>
      <Space wrap>
        <Select
          aria-label="账务差异主阶段筛选"
          value={stage}
          style={{ minWidth: 260 }}
          options={[
            { value: "", label: "全部账务差异阶段" },
            ...Object.entries(coverageStages).map(([value, label]) => ({
              value,
              label,
            })),
          ]}
          onChange={(value) => {
            clearPreview();
            setStage(value);
          }}
        />
        <Button
          type="primary"
          disabled={!scopeId || scopes.isError || preview.isFetching}
          loading={preview.isFetching}
          onClick={firstPage}
        >
          显式重新读取首屏
        </Button>
      </Space>
      {(warning || scopes.isError || related.isError || evidence.error) && (
        <Alert
          type="error"
          title={
            warning ||
            (scopes.isError
              ? message(scopes.error)
              : related.isError
                ? message(related.error)
                : evidence.error)
          }
        />
      )}
      {!result && !preview.isFetching && (
        <Alert title="尚无可展示的当前预览。请选择实际范围并显式读取；取数失败或失效不表示零差异。" />
      )}
      {preview.isFetching && (
        <Alert title="正在读取当前页；未展示旧结果，未将加载状态当成零。" />
      )}
      {result && (
        <>
          <Alert
            type={
              result.resultStatus === "LEDGER_LINKS_SUPPORTED"
                ? "info"
                : "warning"
            }
            title={coverageResultLabel(result.resultStatus)}
            description="全总体汇总不随筛选或分页缩小；阶段是互斥主阶段，多个issues标签不能重复加总为差异金额。账务流入/流出腿不是已证明的外部现金收付。"
          />
          <Descriptions
            column={1}
            items={[
              {
                key: "scope",
                label: "实际月度范围 / 批准水位",
                children: `${result.scope.periodStart} 至 ${result.scope.periodEnd} / ${result.scope.approvedScopeVersion} / ${result.scope.scopeRevisionId}`,
              },
              {
                key: "standard",
                label: "制度 / 范围边界",
                children: `${result.scope.standard === "CAS" ? "企业准则" : result.scope.standard === "SMALL" ? "小企业准则" : "未知制度"} · ${result.scope.currency} · 个别口径 · 包含${result.scope.includedAccountCount}科目 / 排除${result.scope.excludedAccountCount}科目；范围外完整性未建立`,
              },
              {
                key: "status",
                label: "当前年度 / 月度状态（只读可查）",
                children: `${result.scope.yearStatus === "CLOSED_PENDING_ARCHIVE" ? "结转已提交，待归档" : result.scope.yearStatus} / ${result.scope.periodStatus}`,
              },
              {
                key: "count",
                label: "全总体 / 筛选命中 / 本页",
                children: `${result.summary.populationRowCount}行 / ${result.summary.displayMatchedRowCount}行 / ${result.items.length}行`,
              },
              {
                key: "direction",
                label: "方向质量",
                children: `${result.summary.knownDirectionalRowCount}行方向可识别 / ${result.summary.unknownDirectionRowCount}行方向未知 · ${result.summary.directionalTotalsStatus === "COMPLETE" ? "受限账务方向金额可确定（非外部完整性）" : "部分异常行未纳入方向合计，不能当完整总体收付"}`,
              },
              {
                key: "debit",
                label: "全总体原始借方 / 贷方（非外部收付）",
                children: `${coverageMoney(result.summary.ledgerDebitTotal)} / ${coverageMoney(result.summary.ledgerCreditTotal)}`,
              },
              {
                key: "ledger",
                label: "全总体已识别账务流入腿 / 流出腿",
                children: `${coverageMoney(result.summary.ledgerInTotal)} / ${coverageMoney(result.summary.ledgerOutTotal)}`,
              },
              {
                key: "supported",
                label: "无阻断且链路有支持的流入 / 流出",
                children: `${coverageMoney(result.summary.supportedInTotal)} / ${coverageMoney(result.summary.supportedOutTotal)}（不是正式报表贡献）`,
              },
            ]}
          />
          <Table<CoverageStageTotal & { key: string; label: string }>
            rowKey="key"
            tableLayout="fixed"
            pagination={false}
            scroll={{ x: 1100 }}
            dataSource={stageRows}
            columns={[
              {
                title: "全总体互斥主阶段（不随筛选改变）",
                dataIndex: "label",
                width: 300,
              },
              { title: "行数", dataIndex: "rowCount", width: 90 },
              {
                title: "流入行 / 流出行 / 未知方向行",
                width: 220,
                render: (_, item) =>
                  `${item.inCount} / ${item.outCount} / ${item.unknownDirectionCount}`,
              },
              {
                title: "已识别流入金额",
                dataIndex: "inAmount",
                width: 240,
                render: coverageMoney,
              },
              {
                title: "已识别流出金额",
                dataIndex: "outAmount",
                width: 240,
                render: coverageMoney,
              },
            ]}
          />
          {frozen(
            {
              contextDigest: result.contextDigest,
              asOfWatermark: result.asOfWatermark,
              limitations: result.limitations,
            },
            "查看本次取数水位、元数据摘要与限制（非持久批准快照）",
          )}
          <Table<CoverageRow>
            rowKey={(item) => `${item.journalId}:${item.lineNo}`}
            tableLayout="fixed"
            pagination={false}
            scroll={{ x: 1970 }}
            dataSource={result.items}
            columns={[
              {
                title: "会计日期 / 凭证号 / 行",
                width: 180,
                render: (_, item) =>
                  `${item.accountingDate} / ${item.number ?? "未知凭证号"} / ${item.lineNo}`,
              },
              { title: "年度科目", dataIndex: "accountId", width: 220 },
              {
                title: "原始借方 / 贷方 / 精度",
                width: 240,
                render: (_, item) => (
                  <span>
                    {coverageMoney(item.debit)} / {coverageMoney(item.credit)}
                    <br />
                    {item.amountFormatVersion}
                  </span>
                ),
              },
              {
                title: "账务方向 / 金额",
                width: 240,
                render: (_, item) =>
                  `${coverageDirection(item.direction)} / ${coverageMoney(item.normalizedAmount)}`,
              },
              {
                title: "互斥主阶段",
                width: 230,
                render: (_, item) => (
                  <Tag style={{ whiteSpace: "normal" }}>
                    {coverageStages[item.primaryStage] ?? item.primaryStage}
                  </Tag>
                ),
              },
              {
                title: "当前链路 / 后续新用",
                width: 200,
                render: (_, item) =>
                  `${item.currentChainEffective ? "当前链路仍有效" : "当前链路不有效"} / ${item.newUseFrozen ? "新用冻结" : "未标记新用冻结（非放行）"}`,
              },
              {
                title: "全部行级问题（不截断）",
                width: 420,
                render: (_, item) => issues(item.issues),
              },
              {
                title: "只读下钻",
                width: 180,
                render: (_, item) => (
                  <Button
                    onClick={() => {
                      setRow(item);
                      setDrill(null);
                    }}
                  >
                    查看链路与原件
                  </Button>
                ),
              },
            ]}
          />
          <Space wrap>
            <Button
              disabled={!request?.cursor || preview.isFetching}
              onClick={firstPage}
            >
              丢弃旧页并重取首屏
            </Button>
            <Button
              disabled={!result.nextCursor || preview.isFetching}
              onClick={nextPage}
            >
              读取下一页（同一上下文）
            </Button>
          </Space>
          {!result.items.length && (
            <Alert title="本次请求成功，但当前筛选没有行；请同时查看全总体行数。外部总体仍缺失，不能推断无现金差异。" />
          )}
        </>
      )}
      <Modal
        title="已记账行的只读链路与全部问题"
        open={!!row && !!result}
        footer={null}
        width={1100}
        styles={{
          body: {
            maxHeight: "72vh",
            overflowY: "auto",
            overflowWrap: "anywhere",
          },
        }}
        onCancel={() => {
          setRow(null);
          setDrill(null);
        }}
      >
        {row && result && (
          <Space orientation="vertical" style={{ width: "100%" }}>
            <Alert title="只读解释本次预览投影，不提交、审签、修复或发布。原登记仍为未核实/未分类，不因人工支持链变成银行验真。" />
            {issues(row.issues)}
            {chainButtons(row)}
            {frozen(
              row,
              "查看当前声明、人审、分配、待审ID及原始账务投影（null保持未知）",
            )}
          </Space>
        )}
      </Modal>
      <Modal
        title="关联业务只读详情与实际原件"
        open={!!drill && !!result}
        footer={null}
        width={1100}
        styles={{
          body: {
            maxHeight: "72vh",
            overflowY: "auto",
            overflowWrap: "anywhere",
          },
        }}
        onCancel={() => setDrill(null)}
      >
        {related.isError ? (
          <Alert type="error" title={message(related.error)} />
        ) : related.isFetching ? (
          <Alert title="正在读取真实关联对象，未展示旧详情。" />
        ) : (
          drill &&
          related.data !== undefined &&
          related.data !== null && (
            <Space orientation="vertical" style={{ width: "100%" }}>
              <Alert title="关联详情是另一次只读取数，可能晚于预览。发现变化时请关闭并重新读取整组预览，不能拼成一份已冻结证据。" />
              {evidence.error && <Alert type="error" title={evidence.error} />}
              {relatedOriginals.map((original) => (
                <Space key={original.id} wrap>
                  <Button
                    onClick={() =>
                      void evidence.download(original.id, original.filename)
                    }
                  >
                    下载实际原件：{original.filename}
                  </Button>
                  <span>
                    服务端原件元数据SHA：{original.sha256 ?? "未提供，不等于零"}
                  </span>
                </Space>
              ))}
              {frozen(
                related.data,
                `查看${drill.kind}业务详情 / 版本与证据元数据`,
              )}
            </Space>
          )
        )}
      </Modal>
    </Space>
  );
}
