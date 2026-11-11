import { useState } from "react";
import { Alert, Button, Input, Select, Space, Table, Typography } from "antd";
import type {
  ParentPage,
  BasisPage,
  PayablePage,
  OpenItemPage,
  BasisDetail,
  PayableDetail,
  EligibleParent,
  PayableCommandResult,
  BasisCommandResult,
  Reconciliation,
  OpenItemDetail,
  Catalog,
  SupplierExpectation,
  Producer,
} from "./payables-ap01-contract";
import type { CounterpartyDetail } from "./counterparty-contract";
import { useApContext, useApResource } from "./payables-ap01-resource";
import {
  ApBasisView,
  ApPayableView,
  ApCapacity,
  ApFacts,
  ApBlockers,
  ApJson,
  ApOpenItem,
  ApOriginal,
  ApFrozenDocument,
} from "./payables-ap01-views";
import { ApCommand } from "./payables-ap01-actions";
import { apIndependent } from "./payables-ap01-rules";
import type { ApUi } from "./payables-ap01-ui";
type Result = PayableCommandResult | BasisCommandResult;
function PageBar({
  next,
  limit,
  onLimit,
  onNext,
  onFirst,
}: {
  next: string | null;
  limit: number;
  onLimit: (v: number) => void;
  onNext: () => void;
  onFirst: () => void;
}) {
  return (
    <Space wrap>
      <Select
        aria-label="应付当前页条数"
        value={limit}
        onChange={onLimit}
        options={[20, 50, 100].map((value) => ({
          value,
          label: `每页${value}条`,
        }))}
      />
      <Button onClick={onFirst}>返回首屏</Button>
      <Button disabled={!next} onClick={onNext}>
        读取服务端下一页
      </Button>
      <Typography.Text>总量未计算；只保留有界当前页。</Typography.Text>
    </Space>
  );
}
export function ApPreparation({
  ui,
  onResult,
  initialId,
  onSelected,
}: {
  ui: ApUi;
  onResult: (v: Result) => void;
  initialId?: string;
  onSelected?: (id: string) => void;
}) {
  const c = useApContext(),
    [cursor, setCursor] = useState<string | null>(null),
    [limit, setLimit] = useState(20),
    [selected, setSelected] = useState(initialId ?? ""),
    [supplierInput, setSupplierInput] = useState(""),
    [supplierId, setSupplierId] = useState(""),
    r = useApResource<ParentPage>(
      `${c.scope}/payables-ap01/eligible-parents?limit=${limit}${cursor ? "&cursor=" + encodeURIComponent(cursor) : ""}`,
      true,
    ),
    parent = r.data?.items.find((x) => x.parent.allocationId === selected),
    s = useApResource<CounterpartyDetail>(
      `${c.scope.replace(/\/years\/[^/]+$/, "")}/counterparties/${supplierId}`,
      !!supplierId,
    ),
    sd = s.data;
  const supplier: SupplierExpectation | null =
    sd &&
    sd.counterparty.effectiveStatus === "ACTIVE" &&
    sd.counterparty.newUseEligible &&
    sd.effectiveContent?.roles.includes("SUPPLIER") &&
    sd.counterparty.effectiveRevision
      ? {
          counterpartyId: sd.counterparty.id,
          governanceHomeBookId: sd.counterparty.governanceHomeBookId,
          registryVersion: sd.counterparty.registryVersion,
          approvedRevisionId: sd.counterparty.effectiveRevision.id,
          approvedRevisionDigest:
            sd.counterparty.effectiveRevision.contentDigest,
        }
      : null;
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <Alert title="选择真实已确认费用父份额，金额为当前正净份额全额；费用用途上限不替代独立会计额度。未知额度的父份额也列出，先进行会计额度独立复核。" />
      {r.error && <Alert type="error" title={r.error} />}
      <Button onClick={() => void r.read()}>重新读取真实父份额</Button>
      {r.data && (
        <>
          <Table
            size="small"
            pagination={false}
            rowKey={(x) => x.parent.allocationId}
            scroll={{ x: 1100 }}
            dataSource={r.data.items}
            columns={[
              {
                title: "父费用 / 单据 / 业务链",
                width: 360,
                render: (_, p) => (
                  <span style={{ overflowWrap: "anywhere" }}>
                    {p.parent.allocationId}
                    <br />
                    {p.parent.documentId}
                    <br />
                    {p.parent.chainId}
                  </span>
                ),
              },
              {
                title: "已确认 / 已反向 / 当前净额",
                width: 300,
                render: (_, p) =>
                  `${p.parent.confirmedAmount} / ${p.parent.approvedReversedAmount} / ${p.parent.netConfirmedAmount}`,
              },
              {
                title: "当前资格",
                width: 150,
                render: (_, p) =>
                  p.eligibleForPreparation ? "可准备" : "有阻断，仍可查看依据",
              },
              {
                title: "操作",
                width: 160,
                render: (_, p) => (
                  <Button
                    onClick={() => {
                      setSelected(p.parent.allocationId);
                      onSelected?.(p.parent.allocationId);
                    }}
                  >
                    选择真实父份额
                  </Button>
                ),
              },
            ]}
          />
          <PageBar
            next={r.data.nextCursor}
            limit={limit}
            onLimit={(v) => {
              setLimit(v);
              setCursor(null);
              setSelected("");
            }}
            onFirst={() => {
              setCursor(null);
              setSelected("");
              void r.read();
            }}
            onNext={() => {
              setCursor(r.data!.nextCursor);
              setSelected("");
            }}
          />
        </>
      )}
      {parent && (
        <>
          <ApFacts
            title="真实父份额与来源槽"
            rows={[
              ["父净份额全额", parent.parent.netConfirmedAmount],
              [
                "真实确认 / 使用份额",
                `${parent.parent.confirmationId} / ${parent.parent.usageId}`,
              ],
              [
                "当前latest前驱",
                `${parent.sourceSlot.latestAttemptId ?? "首个尝试"} / ${parent.sourceSlot.latestAttemptVersion ?? "无"}`,
              ],
              ["上游待审反向", parent.pendingParentReverseId ?? "无"],
            ]}
          />
          <ApOriginal original={parent.purposeOriginal} />
          <ApFrozenDocument expectation={parent.parent.documentExpectation} />
          <ApCapacity data={parent.capacity} />
          <ApBlockers items={parent.blockers} />
          <ApCommand
            kind="basis-create"
            ui={ui}
            seed={{ parent }}
            enabled={ui.canOwner && ui.newUse}
            onResult={onResult}
          />
          <Space.Compact style={{ width: "100%" }}>
            <Input
              aria-label="真实已知供应商组织ID"
              value={supplierInput}
              onChange={(e) => setSupplierInput(e.target.value)}
              placeholder="输入真实稳定供应商UUID；跨治理账套须双方可读"
            />
            <Button
              onClick={() => {
                if (
                  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(
                    supplierInput,
                  )
                )
                  setSupplierId(supplierInput);
              }}
            >
              读取真实供应商版本
            </Button>
          </Space.Compact>
          {s.error && <Alert type="error" title={s.error} />}{" "}
          {sd && (
            <ApFacts
              title="当前组织主数据资格（不是财务资格）"
              rows={[
                [
                  "名称 / 稳定ID",
                  `${sd.counterparty.effectiveDisplayName ?? "尚未发布"} / ${sd.counterparty.id}`,
                ],
                [
                  "当前状态 / 角色",
                  `${sd.counterparty.effectiveStatus ?? "尚未发布"} / ${sd.effectiveContent?.roles.join("、") ?? "无"}`,
                ],
                [
                  "已批准revision",
                  sd.counterparty.effectiveRevision?.id ?? "无",
                ],
                [
                  "AP新用前提",
                  supplier
                    ? "当前发布SUPPLIER候选；仍由AP服务校验"
                    : "不可新用",
                ],
              ]}
            />
          )}
          <ApCommand
            kind="create"
            ui={ui}
            seed={{ parent, supplier: supplier ?? undefined }}
            enabled={
              ui.canMake &&
              ui.newUse &&
              parent.eligibleForPreparation &&
              !!parent.capacity.currentBasis &&
              !!supplier
            }
            onResult={onResult}
          />
        </>
      )}
    </Space>
  );
}
export function ApBases({
  ui,
  onResult,
  initialId,
  onSelected,
}: {
  ui: ApUi;
  onResult: (v: Result) => void;
  initialId?: string;
  onSelected?: (id: string) => void;
}) {
  const c = useApContext(),
    [cursor, setCursor] = useState<string | null>(null),
    [limit, setLimit] = useState(20),
    [status, setStatus] = useState("ALL"),
    [id, setId] = useState(initialId ?? ""),
    r = useApResource<BasisPage>(
      `${c.scope}/payables-ap01/accounting-bases?limit=${limit}&status=${status}${cursor ? "&cursor=" + encodeURIComponent(cursor) : ""}`,
      true,
    ),
    d = useApResource<BasisDetail>(
      `${c.scope}/payables-ap01/accounting-bases/${id}`,
      !!id,
    );
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <Select
        aria-label="会计额度提案状态"
        value={status}
        onChange={(v) => {
          setStatus(v);
          setCursor(null);
          setId("");
        }}
        options={["ALL", "PENDING", "APPROVED", "REJECTED", "CANCELLED"].map(
          (value) => ({ value, label: value }),
        )}
      />
      {r.error && <Alert type="error" title={r.error} />}{" "}
      {r.data && (
        <>
          <Table
            size="small"
            rowKey="id"
            pagination={false}
            scroll={{ x: 850 }}
            dataSource={r.data.items}
            columns={[
              {
                title: "依据 / 单据ID",
                width: 400,
                render: (_, b) => (
                  <span style={{ overflowWrap: "anywhere" }}>
                    {b.id}
                    <br />
                    {b.documentId}
                  </span>
                ),
              },
              {
                title: "状态 / 修订",
                width: 180,
                render: (_, b) => `${b.status} / ${b.revision}`,
              },
              {
                title: "读取",
                width: 130,
                render: (_, b) => (
                  <Button
                    onClick={() => {
                      setId(b.id);
                      onSelected?.(b.id);
                    }}
                  >
                    读取真实依据
                  </Button>
                ),
              },
            ]}
          />
          <PageBar
            next={r.data.nextCursor}
            limit={limit}
            onLimit={(v) => {
              setLimit(v);
              setCursor(null);
            }}
            onFirst={() => {
              setCursor(null);
              void r.read();
            }}
            onNext={() => setCursor(r.data!.nextCursor)}
          />
        </>
      )}
      {d.data && (
        <ApBasisDetailView data={d.data} ui={ui} onResult={onResult} />
      )}{" "}
      {d.error && <Alert type="error" title={d.error} />}
    </Space>
  );
}
function ApBasisDetailView({
  data,
  ui,
  onResult,
}: {
  data: BasisDetail;
  ui: ApUi;
  onResult: (v: Result) => void;
}) {
  const c = useApContext(),
    [proof, setProof] = useState<{ key: string; ready: boolean } | null>(null),
    key = JSON.stringify([
      c.identity,
      c.revision,
      data.basis.id,
      data.basis.contentDigest,
    ]),
    independent = apIndependent(c.actor, data.basis.maker);
  return (
    <>
      <ApBasisView
        data={data}
        onEvidenceReady={(ready) =>
          setProof((old) =>
            old?.key === key && old.ready === ready ? old : { key, ready },
          )
        }
      />
      <Space wrap>
        <ApCommand
          kind="basis-approve"
          ui={ui}
          seed={{ basis: data }}
          enabled={
            ui.canReview &&
            independent &&
            ui.newUse &&
            proof?.key === key &&
            proof.ready &&
            data.allowedActions.includes("APPROVE_BASIS")
          }
          onResult={onResult}
        />
        <ApCommand
          kind="basis-reject"
          ui={ui}
          seed={{ basis: data }}
          enabled={
            ui.canReview &&
            independent &&
            data.allowedActions.includes("REJECT_BASIS")
          }
          onResult={onResult}
        />
        <ApCommand
          kind="basis-cancel"
          ui={ui}
          seed={{ basis: data }}
          enabled={
            ui.canOwner &&
            c.actor.accountId === data.basis.maker.accountId &&
            data.allowedActions.includes("CANCEL_BASIS")
          }
          onResult={onResult}
        />
      </Space>
    </>
  );
}
export function ApPayables({
  ui,
  onResult,
  initialId,
  onSelected,
}: {
  ui: ApUi;
  onResult: (v: Result) => void;
  initialId?: string;
  onSelected?: (id: string) => void;
}) {
  const c = useApContext(),
    [cursor, setCursor] = useState<string | null>(null),
    [limit, setLimit] = useState(20),
    [status, setStatus] = useState("ALL"),
    [id, setId] = useState(initialId ?? ""),
    r = useApResource<PayablePage>(
      `${c.scope}/payables-ap01/expense-payables?limit=${limit}&status=${status}${cursor ? "&cursor=" + encodeURIComponent(cursor) : ""}`,
      true,
    );
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <Select
        aria-label="应付确认状态"
        value={status}
        onChange={(v) => {
          setStatus(v);
          setCursor(null);
          setId("");
        }}
        options={[
          "ALL",
          "DRAFT",
          "SUBMITTED",
          "APPROVED",
          "POSTED",
          "CANCELLED",
        ].map((value) => ({ value, label: value }))}
      />
      {r.data && (
        <>
          <Table
            size="small"
            rowKey="id"
            pagination={false}
            scroll={{ x: 1100 }}
            dataSource={r.data.items}
            columns={[
              {
                title: "真实应付 / 原凭证",
                width: 400,
                render: (_, p) => (
                  <span style={{ overflowWrap: "anywhere" }}>
                    {p.id}
                    <br />
                    {p.journalId}
                  </span>
                ),
              },
              {
                title: "类型 / 状态 / 经济效果",
                width: 250,
                render: (_, p) =>
                  `${p.producerKind} / ${p.journalStatus} / ${p.currentEffect}`,
              },
              { title: "金额", width: 180, dataIndex: "amount" },
              {
                title: "读取",
                width: 150,
                render: (_, p) => (
                  <Button
                    onClick={() => {
                      setId(p.id);
                      onSelected?.(p.id);
                    }}
                  >
                    读取应付及真实证明
                  </Button>
                ),
              },
            ]}
          />
          <PageBar
            next={r.data.nextCursor}
            limit={limit}
            onLimit={(v) => {
              setLimit(v);
              setCursor(null);
            }}
            onFirst={() => {
              setCursor(null);
              void r.read();
            }}
            onNext={() => setCursor(r.data!.nextCursor)}
          />
        </>
      )}
      {r.error && <Alert type="error" title={r.error} />}{" "}
      {id && (
        <ApPayableDetailView key={id} id={id} ui={ui} onResult={onResult} />
      )}
    </Space>
  );
}
export function ApPayableDetailView({
  id,
  ui,
  onResult,
}: {
  id: string;
  ui: ApUi;
  onResult: (v: Result) => void;
}) {
  const c = useApContext(),
    [historyCursor, setHistoryCursor] = useState<string | null>(null),
    r = useApResource<PayableDetail>(
      `${c.scope}/payables-ap01/expense-payables/${id}?historyLimit=20${historyCursor ? "&historyCursor=" + encodeURIComponent(historyCursor) : ""}`,
      true,
    ),
    d = r.data,
    [proof, setProof] = useState<{ key: string; ready: boolean } | null>(null),
    key = JSON.stringify([
      c.identity,
      c.revision,
      id,
      d?.payable.contentDigest,
    ]),
    independent = !!d && apIndependent(c.actor, d.payable.maker),
    owner = !!d && c.actor.accountId === d.payable.maker.accountId;
  function allowed(kind: PayableDetail["allowedActions"][number]) {
    return !!d && d.allowedActions.includes(kind);
  }
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <Button onClick={() => void r.read()}>显式重读当前应付证明</Button>
      {r.error && <Alert type="error" title={r.error} />}{" "}
      {!d && (
        <Alert title="当前应付及证据未读取成功，不使用旧快照审签或记账。" />
      )}
      {d && (
        <>
          <ApPayableView
            data={d}
            onEvidenceReady={(ready) =>
              setProof((old) =>
                old?.key === key && old.ready === ready ? old : { key, ready },
              )
            }
          />
          <Space wrap>
            {[
              ["submit", ui.canMake && owner && ui.newUse && allowed("SUBMIT")],
              [
                "approve",
                ui.canReview &&
                  independent &&
                  ui.newUse &&
                  proof?.key === key &&
                  proof.ready &&
                  allowed("APPROVE"),
              ],
              [
                "post",
                ui.canPost &&
                  proof?.key === key &&
                  proof.ready &&
                  allowed("POST"),
              ],
              ["withdraw", ui.canMake && owner && allowed("WITHDRAW")],
              ["reject", ui.canReview && independent && allowed("REJECT")],
              ["cancel", ui.canMake && owner && allowed("CANCEL")],
              [
                "admin-terminate",
                ui.canReview && independent && allowed("ADMIN_TERMINATE"),
              ],
              [
                "full-reversal",
                ui.canMake &&
                  !!d.postReceipt &&
                  !!d.openItem &&
                  allowed("CREATE_FULL_REVERSAL"),
              ],
            ].map(([kind, enabled]) => (
              <ApCommand
                key={String(kind)}
                kind={
                  kind as
                    | "submit"
                    | "approve"
                    | "post"
                    | "withdraw"
                    | "reject"
                    | "cancel"
                    | "admin-terminate"
                    | "full-reversal"
                }
                ui={ui}
                seed={{ payable: d }}
                enabled={!!enabled}
                onResult={onResult}
              />
            ))}{" "}
          </Space>
          <ApJson
            value={d.history.items}
            label="本页真实命令决定（历史，不替代当前效果）"
          />
          <Space>
            <Button onClick={() => setHistoryCursor(null)}>决定历史首屏</Button>
            <Button
              disabled={!d.history.nextCursor}
              onClick={() => setHistoryCursor(d.history.nextCursor)}
            >
              下一页真实决定
            </Button>
          </Space>
          {d.currentSourceSlot.latestAttemptId &&
            d.currentSourceSlot.latestAttemptId !== id && (
              <Typography.Text>
                当前来源槽最新尝试 {d.currentSourceSlot.latestAttemptId}
                ；当前对象是历史尝试，不自动切换或复活。
              </Typography.Text>
            )}
        </>
      )}
    </Space>
  );
}
export function ApOpenItems() {
  const c = useApContext(),
    [cursor, setCursor] = useState<string | null>(null),
    [limit, setLimit] = useState(20),
    [id, setId] = useState(""),
    r = useApResource<OpenItemPage>(
      `${c.scope}/payables-ap01/open-items?limit=${limit}${cursor ? "&cursor=" + encodeURIComponent(cursor) : ""}`,
      true,
    ),
    d = useApResource<OpenItemDetail>(
      `${c.scope}/payables-ap01/open-items/${id}`,
      !!id,
    ),
    recon = useApResource<Reconciliation>(
      `${c.scope}/payables-ap01/reconciliation`,
      true,
    );
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <Alert
        type="warning"
        title="仅本应付producer子集；系统本金减实际会计反向，不是外部银行未付款余额。AP02、完整企业子账及BASE仍未开放。"
      />
      {r.data && (
        <>
          <Table
            rowKey="id"
            pagination={false}
            size="small"
            scroll={{ x: 950 }}
            dataSource={r.data.items}
            columns={[
              {
                title: "未结项 / 供应商",
                width: 350,
                render: (_, i) => (
                  <span style={{ overflowWrap: "anywhere" }}>
                    {i.id}
                    <br />
                    {i.supplierSnapshot.displayName}
                  </span>
                ),
              },
              {
                title: "本金 / 会计反向 / 本系统余额",
                width: 400,
                render: (_, i) =>
                  `${i.recognizedPrincipal} / ${i.accountingReversedAmount} / ${i.systemOutstanding}`,
              },
              { title: "状态", width: 150, dataIndex: "status" },
              {
                title: "读取",
                width: 120,
                render: (_, i) => (
                  <Button onClick={() => setId(i.id)}>读取未结项</Button>
                ),
              },
            ]}
          />
          <PageBar
            next={r.data.nextCursor}
            limit={limit}
            onLimit={(v) => {
              setLimit(v);
              setCursor(null);
            }}
            onFirst={() => {
              setCursor(null);
              void r.read();
            }}
            onNext={() => setCursor(r.data!.nextCursor)}
          />
        </>
      )}
      {d.data && <ApOpenItem data={d.data.item} />}{" "}
      {recon.data && (
        <>
          <ApFacts
            title="本producer对账（不是完整企业子账资格）"
            rows={[
              ["资格", recon.data.qualification],
              [
                "本金 / 会计反向 / 系统余额",
                `${recon.data.principal ?? "未知"} / ${recon.data.accountingReversed ?? "未知"} / ${recon.data.systemOutstanding ?? "未知"}`,
              ],
              [
                "真实AP总账净贷方 / 未结项差异",
                `${recon.data.typedApGlNetCredit ?? "未知"} / ${recon.data.openItemDifference ?? "未知"}`,
              ],
              [
                "所观察负债科目中未归属本producer记账净额",
                recon.data.unattributedPostedNetOnObservedPayableAccounts ??
                  "未知",
              ],
              ["外部结算 / 完整子账 / BASE", "未知 / 未实现 / 不可执行"],
            ]}
          />
          <ApBlockers items={recon.data.blockers} />
        </>
      )}
    </Space>
  );
}
