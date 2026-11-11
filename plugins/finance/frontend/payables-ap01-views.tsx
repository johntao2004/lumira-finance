import {
  Alert,
  Button,
  Collapse,
  Descriptions,
  Select,
  Space,
  Table,
  Typography,
} from "antd";
import { useEffect, useRef, type ReactNode } from "react";
import { useEvidenceOriginal } from "./use-evidence-original";
import { useApContext, useApResource } from "./payables-ap01-resource";
import type {
  Original,
  AccountingCapacity,
  PayableDetail,
  OpenItem,
  DocumentExpectation,
  BasisDetail,
} from "./payables-ap01-contract";
import type {
  ReviewDetail,
  Original as EvidenceOriginal,
} from "./evidence-usage-contract";
export function ApFacts({
  title,
  rows,
}: {
  title?: string;
  rows: [string, ReactNode][];
}) {
  return (
    <>
      <Typography.Title level={5}>{title}</Typography.Title>
      <Descriptions
        size="small"
        bordered
        column={1}
        styles={{
          content: { overflowWrap: "anywhere", whiteSpace: "pre-wrap" },
        }}
        items={rows.map(([label, children], i) => ({
          key: i,
          label,
          children,
        }))}
      />
    </>
  );
}
export function ApJson({
  value,
  label = "完整不可变证明",
}: {
  value: unknown;
  label?: string;
}) {
  return (
    <Collapse
      items={[
        {
          key: "facts",
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
export function ApBlockers({
  items,
}: {
  items: { code: string; message: string }[];
}) {
  return items.length ? (
    <Alert
      type="warning"
      title="当前业务检查"
      description={
        <ul>
          {items.map((b, i) => (
            <li key={i}>
              {b.message}（{b.code}）
            </li>
          ))}
        </ul>
      }
    />
  ) : null;
}
export function ApOriginal({ original }: { original: Original }) {
  const c = useApContext();
  return (
    <ApOriginalInner key={`${c.identity}:${original.id}`} original={original} />
  );
}
function ApOriginalInner({ original }: { original: Original }) {
  const c = useApContext(),
    file = useEvidenceOriginal(c.scope, false, c.denied, c.readable);
  return (
    <Space wrap>
      <Typography.Text style={{ overflowWrap: "anywhere" }}>
        {original.filename} · {original.sha256} · 版本{original.version} ·
        原件未验真
      </Typography.Text>
      <Button
        disabled={!c.ready}
        onClick={() => void file.download(original.id, original.filename)}
      >
        下载真实原件
      </Button>
      {file.error && <Alert type="error" title={file.error} />}
    </Space>
  );
}
/** V15 wire stays originalId/verificationStatus; do not cast it to the AP Original DTO. */
function ApEvidenceOriginal({ original }: { original: EvidenceOriginal }) {
  const c = useApContext();
  return (
    <ApEvidenceOriginalInner
      key={`${c.identity}:${original.originalId}`}
      original={original}
    />
  );
}
function ApEvidenceOriginalInner({ original }: { original: EvidenceOriginal }) {
  const c = useApContext(),
    file = useEvidenceOriginal(c.scope, false, c.denied, c.readable);
  const sameScope =
    c.scope === `/finance/v1/books/${original.bookId}/years/${original.yearId}`;
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <Typography.Text style={{ overflowWrap: "anywhere" }}>
        {original.filename} · {original.originalId}
        <br />
        服务端SHA-256：{original.sha256}；实际版本：{original.version}
        <br />
        上传账号：{original.createdBy}；原件字节未验真（
        {original.verificationStatus}）
      </Typography.Text>
      <Button
        disabled={!c.ready || !sameScope}
        onClick={() =>
          void file.download(original.originalId, original.filename)
        }
      >
        下载真实V15原件
      </Button>
      {!sameScope && (
        <Alert
          type="warning"
          title="冻结原件不在当前财务账套年度，不能代用或跨范围下载。"
        />
      )}
      {file.error && <Alert type="error" title={file.error} />}
    </Space>
  );
}
export type ApStoredOriginal = { id: string; filename: string; sha256: string };
export function ApOriginalPicker({
  value,
  onChange,
  originals,
  canUpload,
  refresh,
}: {
  value?: string;
  onChange?: (id: string) => void;
  originals: ApStoredOriginal[];
  canUpload: boolean;
  refresh: () => Promise<unknown>;
}) {
  const c = useApContext(),
    file = useEvidenceOriginal(
      c.scope,
      canUpload && c.ready,
      c.denied,
      c.readable,
    );
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <Select
        aria-label="选择真实已上传应付证据"
        value={value}
        onChange={onChange}
        showSearch
        optionFilterProp="label"
        style={{ width: "100%" }}
        options={originals.map((o) => ({
          value: o.id,
          label: `${o.filename} · ${o.sha256}`,
        }))}
      />
      <label>
        上传实际原件字节（最多5MiB，保存不代表验真）：
        <input
          type="file"
          disabled={!canUpload || !c.ready || file.uploading}
          onChange={(e) => {
            const f = e.target.files?.[0];
            if (f)
              void file.upload(f, async (id) => {
                await refresh();
                onChange?.(id);
              });
            e.target.value = "";
          }}
        />
      </label>
      {value && (
        <Button
          onClick={() => {
            const o = originals.find((x) => x.id === value);
            if (o) void file.download(o.id, o.filename);
          }}
        >
          下载所选真实原件
        </Button>
      )}
      {file.error && <Alert type="error" title={file.error} />}
    </Space>
  );
}
export function ApCapacity({ data }: { data: AccountingCapacity }) {
  return (
    <ApFacts
      title="独立会计确认用途额度（不与费用/结算相加）"
      rows={[
        ["单据 / 资格", `${data.documentId} / ${data.qualification}`],
        ["会计额度", data.capacity ?? "未知"],
        ["已预留", data.reserved ?? "未知"],
        ["净已记占用", data.netCommitted ?? "未知"],
        ["可用", data.available ?? "未知"],
        [
          "当前依据 / 版本",
          data.currentBasis
            ? `${data.currentBasis.basisId} / ${data.currentBasis.basisVersion}`
            : "尚无独立会计依据",
        ],
        [
          "待审 / 最新依据",
          `${data.pendingBasisId ?? "无"} / ${data.latestBasisId ?? "无"}`,
        ],
        ["资格版本", data.eligibilityVersion],
      ]}
    />
  );
}
export function ApFrozenDocument({
  expectation,
  onReady,
}: {
  expectation: DocumentExpectation;
  onReady?: (ready: boolean) => void;
}) {
  const c = useApContext(),
    r = useApResource<ReviewDetail>(
      `${c.scope}/evidence-usage/documents/${expectation.documentId}/review-proposals/${expectation.currentReviewId}`,
      true,
    ),
    d = r.data,
    valid =
      !!d &&
      d.id === expectation.currentReviewId &&
      d.documentId === expectation.documentId &&
      d.status === "APPROVED" &&
      d.publishedBasisVersion === expectation.basisVersion &&
      d.contentDigest === expectation.basisContentDigest &&
      d.sourceOriginal.version === expectation.sourceOriginalVersion &&
      d.sourceOriginal.sha256 === expectation.sourceOriginalSha256;
  const notify = useRef(onReady);
  notify.current = onReady;
  useEffect(() => {
    notify.current?.(valid);
  }, [
    valid,
    c.identity,
    c.revision,
    expectation.currentReviewId,
    expectation.basisContentDigest,
  ]);
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      {r.error && <Alert type="error" title={r.error} />}
      <Button onClick={() => void r.read()}>重读冻结法律单据原件</Button>
      {!valid ? (
        <Alert
          type="warning"
          title="冻结单据依据尚未读取成功，不可用当前单据或零替代。"
        />
      ) : (
        <>
          <ApFacts
            title="冻结法律单据及来源（组织复核，非政府验真）"
            rows={[
              [
                "类型 / 开具方 / 稳定标识",
                `${d.fields.documentType} / ${d.fields.issuerKey} / ${d.fields.externalIdentityKey}`,
              ],
              [
                "纸电来源 / 原件位置",
                `${d.fields.sourceType} / ${d.fields.originalLocation ?? "电子原件无纸质位置"}`,
              ],
              [
                "面额 / 费用上限（不是会计额度）",
                `${d.fields.faceGross} / ${d.fields.expenseCapacity}`,
              ],
              [
                "冻结依据版本 / digest",
                `${d.publishedBasisVersion} / ${d.contentDigest}`,
              ],
            ]}
          />
          <ApEvidenceOriginal original={d.sourceOriginal} />
          <ApEvidenceOriginal original={d.basisOriginal} />
        </>
      )}
    </Space>
  );
}
export function ApOpenItem({ data }: { data: OpenItem }) {
  return (
    <>
      <ApFacts
        title="本系统应付未结项（不代表外部未付款）"
        rows={[
          ["未结项 / 版本", `${data.id} / ${data.version}`],
          [
            "来源应付 / 凭证 / 记账收据",
            `${data.originalPayableId} / ${data.journalId} / ${data.postReceiptId}`,
          ],
          [
            "供应商快照",
            `${data.supplierSnapshot.displayName} / ${data.counterpartyId}`,
          ],
          ["已确认本金", data.recognizedPrincipal],
          ["实际会计反向", data.accountingReversedAmount],
          ["本系统待结算余额（本金减实际会计反向）", data.systemOutstanding],
          [
            "状态 / 组织声明到期日",
            `${data.status} / ${data.dueDate ?? "未声明"}`,
          ],
          ["外部结算 / 银行支付", "未知，AP02尚未实现；此余额不证明银行未付款"],
        ]}
      />
      <ApJson value={data} label="未结项完整证明" />
    </>
  );
}
export function ApBasisView({
  data,
  onEvidenceReady,
}: {
  data: BasisDetail;
  onEvidenceReady?: (ready: boolean) => void;
}) {
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <ApFacts
        title="会计额度提案的不可变基准与真实决定"
        rows={[
          [
            "依据ID / 状态 / 版本",
            `${data.basis.id} / ${data.basis.status} / ${data.basis.version}`,
          ],
          [
            "制备账号 / 自然人",
            `${data.basis.maker.accountId} / ${data.basis.maker.personId}`,
          ],
          ["内容digest", data.basis.contentDigest],
          [
            "当时面额 / 拟会计额度",
            `${data.content.faceGrossSnapshot} / ${data.content.accountingCapacity}`,
          ],
          ["会计资格理由", data.content.recognitionEligibilityReason],
          [
            "真实决定",
            data.decision
              ? `${data.decision.action} / ${data.decision.actor.accountId} / ${data.decision.reason}`
              : "未决定",
          ],
        ]}
      />
      <ApOriginal original={data.content.basisOriginal} />
      <ApFrozenDocument
        expectation={data.content.expectedDocument}
        onReady={onEvidenceReady}
      />
      <ApCapacity data={data.capacity} />
      <ApBlockers items={data.blockers} />
    </Space>
  );
}
export function ApPayableView({
  data,
  onEvidenceReady,
}: {
  data: PayableDetail;
  onEvidenceReady?: (ready: boolean) => void;
}) {
  const d = data;
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      {d.payable.journalStatus === "POSTED" &&
        (!d.postReceipt ||
          !d.postReceipt.recognitionUsageId ||
          !d.postReceipt.openItemId ||
          !d.postReceipt.parentDependencyId ||
          (d.payable.producerKind === "AP_EXPENSE" && !d.openItem) ||
          (d.payable.producerKind === "AP_EXPENSE_REVERSAL" &&
            (!d.postReceipt.openItemAdjustmentId ||
              !d.postReceipt.reversesPostReceiptId))) && (
          <Alert
            type="error"
            title="记账收据或真实效果证明缺失：原确认须有未结项，反向须有原收据引用及会计调整；不能据此宣称应付闭环成功。"
          />
        )}
      <ApFacts
        title="真实应付会计确认：当前状态与冻结内容分开"
        rows={[
          [
            "对象 / journal / 类型",
            `${d.payable.id} / ${d.payable.journalId} / ${d.payable.rawType}`,
          ],
          [
            "当前状态 / 经济效果",
            `${d.payable.journalStatus} / ${d.payable.currentEffect}`,
          ],
          [
            "当前供应商资格（非历史有效性）",
            d.currentSupplierStatus ??
              "当前治理账套不可读，保留授权财务历史快照",
          ],
          [
            "当前新用来源资格",
            d.sourceFresh
              ? "当前来源满足新用检查"
              : "当前来源不可新用；合法退出/反向按独立门禁",
          ],
          [
            "冻结供应商",
            `${d.content.supplier.displayName} / ${d.content.supplier.expected.counterpartyId} / ${d.content.supplier.expected.approvedRevisionId}`,
          ],
          [
            "原制备账号 / 自然人",
            `${d.payable.maker.accountId} / ${d.payable.maker.personId}`,
          ],
          ["金额（真实父净份额全额）", d.content.amount],
          [
            "会计日 / 到期日",
            `${d.content.accountingDate} / ${d.content.dueDate ?? "未声明"}`,
          ],
          [
            "摘要 / 认可依据",
            `${d.content.summary}\n${d.content.recognitionReason}`,
          ],
          [
            "对象版本 / 内容版本",
            `${d.payable.version} / ${d.payable.contentVersion}`,
          ],
          [
            "内容 / 来源digest",
            `${d.payable.contentDigest} / ${d.payable.sourceDigest}`,
          ],
          [
            "当前提交 / 状态 / 到期",
            d.currentSubmission
              ? `${d.currentSubmission.id} / ${d.currentSubmission.state} / ${d.currentSubmission.expiresAt}`
              : "无",
          ],
          [
            "当前批准人 / 状态 / 到期",
            d.currentApproval
              ? `${d.currentApproval.approvedBy.accountId} / ${d.currentApproval.approvedBy.personId} / ${d.currentApproval.state} / 批准${d.currentApproval.approvedAt} / 到期${d.currentApproval.expiresAt}`
              : "无",
          ],
          [
            "主动业务与应付科目用途声明",
            "已冻结全部主动声明；组织声明不是政府、税务或银行验真",
          ],
        ]}
      />
      <Table
        size="small"
        rowKey="lineNo"
        pagination={false}
        scroll={{ x: 800 }}
        dataSource={d.content.generatedLines}
        columns={[
          { title: "行", dataIndex: "lineNo", width: 60 },
          {
            title: "真实科目",
            width: 280,
            render: (_, l) =>
              `${l.account.code} ${l.account.name} (${l.account.id})`,
          },
          { title: "借方", dataIndex: "debit", width: 180 },
          { title: "贷方", dataIndex: "credit", width: 180 },
          { title: "备注", dataIndex: "memo", width: 180 },
        ]}
      />
      <ApOriginal original={d.content.serviceAcceptanceOriginal} />
      <ApOriginal original={d.content.recognitionOriginal} />
      <ApFrozenDocument
        expectation={d.content.source.documentExpectation}
        onReady={onEvidenceReady}
      />
      <ApCapacity data={d.capacity} />
      {d.postReceipt && (
        <ApFacts
          title="实际记账收据"
          rows={[
            [
              "收据 / journal / postingSeq",
              `${d.postReceipt.id} / ${d.postReceipt.journalId} / ${d.postReceipt.postingSeq}`,
            ],
            [
              "实际记账人 / 时间",
              `${d.postReceipt.postedBy.accountId} / ${d.postReceipt.postedBy.personId} / ${d.postReceipt.postedAt}`,
            ],
            [
              "会计用途 / 未结项 / 父依赖",
              `${d.postReceipt.recognitionUsageId} / ${d.postReceipt.openItemId} / ${d.postReceipt.parentDependencyId}`,
            ],
          ]}
        />
      )}
      {d.postReceipt && (
        <ApJson
          value={d.postReceipt}
          label="实际记账收据 / postingSeq / usage / 未结项证明"
        />
      )}
      {d.openItem && <ApOpenItem data={d.openItem} />}
      <ApJson
        value={{
          source: d.content.source,
          sourceSlot: d.currentSourceSlot,
          reversalBasis: d.content.reversalBasis,
          submission: d.currentSubmission,
          approval: d.currentApproval,
        }}
        label="冻结父份额、当前来源槽与反向/审签证明"
      />
      <ApBlockers items={d.blockers} />
    </Space>
  );
}
