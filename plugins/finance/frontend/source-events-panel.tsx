import { useEffect, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Alert,
  Button,
  Collapse,
  Descriptions,
  Input,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from "antd";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import { api, ApiError, mutate } from "@/platform/api/client";
import { type Account, cnyUnits } from "./types";
import {
  type SourceSummary,
  type OutboxItem,
  type SourceForm,
  sourcePayload,
} from "./source-event-contract";
const required = [{ required: true, message: "请填写此项" }];
const sourceTypes = [
  ["REVENUE_RECOGNIZED", "收入确认"],
  ["PURCHASE_ACCEPTED", "采购验收"],
  ["CASH_RECEIVED", "资金收取"],
  ["CASH_PAID", "资金支出"],
  ["ASSET_DEPRECIATED", "资产折旧"],
  ["PAYROLL_ACCRUED", "薪酬计提"],
  ["ADJUSTMENT", "调整"],
];
const stateLabels: Record<string, string> = {
  PENDING: "待处理",
  PROCESSING: "处理中",
  DONE: "投影已处理",
  FAILED: "处理失败",
  DRAFT_CREATED: "初次已生成草稿",
  DRAFT: "草稿",
  SUBMITTED: "待审核",
  APPROVED: "已审核",
  POSTED: "已记账",
  CANCELLED: "已作废",
};
const conflicts: Record<string, string> = {
  SOURCE_SCOPE_CONFLICT:
    "稳定来源已存在于其他范围，不能换账套或年度重复生成；请在有权访问的原范围核对。",
  SOURCE_INTEGRITY_FAILED:
    "来源证据完整性校验失败，禁止继续处理，请联系负责人核查。",
  OUTBOX_INTEGRITY_FAILED:
    "事件完整性校验失败，不能视作处理完成，请核查权威业务记录。",
  OUTBOX_LEASE_ACTIVE: "消费者正在处理该事件，请稍后刷新，不要并发重试。",
  OUTBOX_RETRY_EXHAUSTED: "事件已达到重试上限，请核查错误后再申请受控重试。",
  ASYNC_REBUILD_REQUIRED: "投影规模超出同步重建范围，需要受控异步处理。",
  EFFECT_PAYLOAD_CONFLICT:
    "该稳定业务来源已产生效果，本次分录内容不同，请核对原凭证，不能重复生成。",
  SOURCE_PAYLOAD_CONFLICT:
    "该稳定来源已有不同的业务内容，请核对来源版本与原记录。",
};
type Props = {
  bookId: string;
  yearId: string;
  mode: "source-events" | "outbox";
  active: boolean;
  canManage: boolean;
  canCreate: boolean;
  restriction: string | null;
  accounts: Account[];
  readable: (error: unknown) => string;
  onReadDenied: () => void;
  onJournal: (id: string) => void;
};
export function SourceEventsPanel({
  bookId,
  yearId,
  mode,
  active,
  canManage,
  canCreate,
  restriction,
  accounts,
  readable,
  onReadDenied,
  onJournal,
}: Props) {
  const scope = `/finance/v1/books/${bookId}/years/${yearId}`;
  const client = useQueryClient();
  const [cursor, setCursor] = useState<string | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [retryId, setRetryId] = useState<string | null>(null);
  const [reason, setReason] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [pending, setPending] = useState(false);
  const [form] = Form.useForm<SourceForm>();
  const busy = useRef(false);
  const operation = useRef<AbortController | null>(null);
  const keys = useRef(new Map<string, string>());
  const deliveryIds = useRef(new Map<string, string>());
  useEffect(() => {
    operation.current = new AbortController();
    return () => {
      operation.current?.abort();
      void client.cancelQueries({
        queryKey: ["finance", bookId, yearId, mode],
      });
      client.removeQueries({ queryKey: ["finance", bookId, yearId, mode] });
    };
  }, [bookId, yearId, mode, client]);
  const list = useQuery({
    queryKey: ["finance", bookId, yearId, mode, "list", cursor],
    enabled: active && canManage,
    queryFn: ({ signal }) =>
      api<{ items: (SourceSummary | OutboxItem)[]; nextCursor: string | null }>(
        `${scope}/${mode}?limit=20${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ""}`,
        { signal },
      ),
  });
  const detail = useQuery({
    queryKey: ["finance", bookId, yearId, mode, "detail", selectedId],
    enabled: active && canManage && !!selectedId,
    queryFn: ({ signal }) =>
      api<
        SourceSummary &
          OutboxItem & {
            event?: unknown;
            mappingSnapshot?: unknown;
            journal?: { id: string; status: string };
          }
      >(`${scope}/${mode}/${selectedId}`, { signal }),
  });
  const readError = list.error ?? detail.error;
  useEffect(() => {
    if (readError instanceof ApiError && readError.status === 403)
      onReadDenied();
  }, [readError, onReadDenied]);
  function message(cause: unknown) {
    return cause instanceof Error && conflicts[cause.message]
      ? conflicts[cause.message]
      : readable(cause);
  }
  async function write(
    path: string,
    payload: Record<string, unknown>,
    done: (result: unknown) => void,
  ) {
    if (
      busy.current ||
      !canManage ||
      (mode === "source-events" && !canCreate) ||
      restriction
    )
      return;
    const signal = operation.current?.signal;
    if (!signal || signal.aborted) return;
    busy.current = true;
    setPending(true);
    setError("");
    setNotice("");
    const fingerprint = JSON.stringify([path, payload]);
    const idempotencyKey = keys.current.get(fingerprint) ?? crypto.randomUUID();
    keys.current.set(fingerprint, idempotencyKey);
    try {
      const result = await mutate(
        path,
        "POST",
        { ...payload, idempotencyKey },
        signal,
      );
      keys.current.delete(fingerprint);
      await client.invalidateQueries({ queryKey: ["finance", bookId, yearId] });
      if (!signal.aborted) done(result);
    } catch (cause) {
      if (!signal.aborted) setError(message(cause));
    } finally {
      busy.current = false;
      if (!signal.aborted) setPending(false);
    }
  }
  function create(values: SourceForm) {
    try {
      if (!/^[1-9]\d{0,8}$/.test(values.sourceDocumentVersion))
        throw new Error("来源单据版本必须为正整数");
      for (const value of [
        values.netAmount,
        values.taxAmount,
        values.grossAmount,
      ])
        cnyUnits(value);
      let debit = 0n,
        credit = 0n;
      for (const line of values.journalLines) {
        const d = cnyUnits(line.debit),
          c = cnyUnits(line.credit);
        if ((d === 0n) === (c === 0n))
          throw new Error("每条分录须且仅须一侧金额大于零");
        debit += d;
        credit += c;
      }
      if (debit === 0n || debit !== credit)
        throw new Error("草稿分录必须非零且借贷平衡");
    } catch (cause) {
      setError(message(cause));
      return;
    }
    const fingerprint = JSON.stringify(values);
    const eventId = deliveryIds.current.get(fingerprint) ?? crypto.randomUUID();
    deliveryIds.current.set(fingerprint, eventId);
    void write(
      `${scope}/source-events`,
      sourcePayload(values, eventId),
      (result) => {
        setCreateOpen(false);
        setSelectedId((result as SourceSummary).id);
        setNotice(
          "来源处理完成或返回原有效果；请以刷新后的当前凭证状态为准。不会自动审签或记账。",
        );
      },
    );
  }
  const data = detail.data;
  const accountOptions = accounts
    .filter((a) => a.enabled)
    .map((a) => ({ value: a.id, label: `${a.code} ${a.name}` }));
  if (!canManage || (readError instanceof ApiError && readError.status === 403))
    return null;
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <Alert
        type="info"
        title={
          mode === "source-events"
            ? "业务来源只生成受控草稿，不自动审核或记账"
            : "事件消费者仅处理本年度财务投影，不执行外部支付、申报或任意URL/SQL"
        }
        description={
          mode === "source-events"
            ? "本片支持单条业务来源金额及多条明确科目分录；辅助维度、原件自动联动和来源反向事件尚未开放。处理器或映射版本变化不能生成第二份相同业务效果。"
            : "待处理、处理中和失败均不代表业务完成；“投影已处理”仅表示本地投影消费者完成。"
        }
      />
      {restriction && <Alert type="warning" title={restriction} />}
      <Space>
        {mode === "source-events" && (
          <Button
            type="primary"
            disabled={!canCreate || !!restriction || pending}
            onClick={() => {
              setCreateOpen(true);
              setError("");
              form.resetFields();
            }}
          >
            创建来源事件与草稿
          </Button>
        )}
        <Button onClick={() => void list.refetch()} loading={list.isFetching}>
          刷新
        </Button>
      </Space>
      {(error || readError) && (
        <Alert type="error" title={error || message(readError)} />
      )}{" "}
      {notice && <Alert type="success" title={notice} />}
      <Table<SourceSummary | OutboxItem>
        rowKey="id"
        loading={list.isFetching}
        dataSource={list.isError ? [] : (list.data?.items ?? [])}
        pagination={false}
        scroll={{ x: 900 }}
        columns={
          mode === "source-events"
            ? [
                { title: "来源系统", dataIndex: "sourceSystem" },
                { title: "稳定来源事件ID", dataIndex: "sourceEventId" },
                { title: "事件类型", dataIndex: "eventType" },
                { title: "映射版本", dataIndex: "mappingRuleVersion" },
                { title: "处理器版本", dataIndex: "handlerVersion" },
                {
                  title: "当前凭证状态",
                  dataIndex: "journalStatus",
                  render: (v) => (
                    <Tag>{stateLabels[v] ?? v ?? "请查看详情"}</Tag>
                  ),
                },
                {
                  title: "状态",
                  dataIndex: "status",
                  render: (v) => <Tag>{stateLabels[v] ?? v}</Tag>,
                },
                {
                  title: "操作",
                  render: (_, row) => (
                    <Button onClick={() => setSelectedId(row.id)}>
                      查看详情
                    </Button>
                  ),
                },
              ]
            : [
                { title: "事件类型", dataIndex: "eventType" },
                { title: "业务对象", dataIndex: "aggregateId" },
                {
                  title: "状态",
                  dataIndex: "state",
                  render: (v) => <Tag>{stateLabels[v] ?? v}</Tag>,
                },
                { title: "尝试次数", dataIndex: "attempts" },
                { title: "下次尝试", dataIndex: "nextAttemptAt" },
                { title: "错误码", dataIndex: "lastErrorCode" },
                {
                  title: "操作",
                  render: (_, row) => (
                    <Button onClick={() => setSelectedId(row.id)}>
                      查看详情
                    </Button>
                  ),
                },
              ]
        }
      />
      <Space>
        <Button
          disabled={!cursor || list.isFetching}
          onClick={() => setCursor(null)}
        >
          返回首屏
        </Button>
        <Button
          disabled={!list.data?.nextCursor || list.isError || list.isFetching}
          onClick={() => setCursor(list.data?.nextCursor ?? null)}
        >
          下一页
        </Button>
      </Space>
      <Modal
        title={mode === "source-events" ? "来源事件详情" : "事件处理详情"}
        open={!!selectedId}
        onCancel={() => setSelectedId(null)}
        footer={null}
        width={850}
      >
        <Button
          loading={detail.isFetching}
          onClick={() => void detail.refetch()}
        >
          刷新详情
        </Button>
        {detail.isFetching && <Alert title="正在读取权威详情" />}
        {data && !detail.isError && (
          <Space orientation="vertical" style={{ width: "100%" }}>
            <Descriptions
              column={1}
              items={Object.entries(data)
                .filter(
                  ([name]) =>
                    ![
                      "payload",
                      "event",
                      "journal",
                      "mappingSnapshot",
                    ].includes(name),
                )
                .map(([name, value]) => ({
                  key: name,
                  label: name,
                  children: String(value ?? "—"),
                }))}
            />
            {data.journal && (
              <Button onClick={() => onJournal(data.journal!.id)}>
                查看关联凭证（
                {stateLabels[data.journal.status] ?? data.journal.status}）
              </Button>
            )}
            <Collapse
              items={[
                {
                  key: "payload",
                  label: "查看结构化内容",
                  children: (
                    <pre
                      style={{
                        whiteSpace: "pre-wrap",
                        overflowWrap: "anywhere",
                      }}
                    >
                      {JSON.stringify(data.event ?? data.payload, null, 2)}
                    </pre>
                  ),
                },
              ]}
            />
            {mode === "source-events" && (
              <Collapse
                items={[
                  {
                    key: "mapping",
                    label: "初次实际映射快照（保留内部八位金额）",
                    children: (
                      <pre
                        style={{
                          whiteSpace: "pre-wrap",
                          overflowWrap: "anywhere",
                        }}
                      >
                        {JSON.stringify(data.mappingSnapshot, null, 2) ??
                          "映射快照暂不可用，请刷新核对。"}
                      </pre>
                    ),
                  },
                ]}
              />
            )}
            {mode === "outbox" && (
              <Button
                disabled={
                  !!restriction ||
                  pending ||
                  data.state === "DONE" ||
                  data.state === "PROCESSING"
                }
                onClick={() => {
                  setRetryId(data.id);
                  setReason("");
                }}
              >
                申请受控重试投影消费者
              </Button>
            )}
          </Space>
        )}
        {detail.error && <Alert type="error" title={message(detail.error)} />}
      </Modal>
      <Modal
        title="确认重试本地投影消费者"
        open={!!retryId}
        onCancel={() => !pending && setRetryId(null)}
        onOk={() =>
          retryId &&
          void write(`${scope}/outbox/${retryId}/retry`, { reason }, () => {
            setRetryId(null);
            setNotice("重试请求已记录，请刷新核对处理状态。");
          })
        }
        confirmLoading={pending}
        okButtonProps={{ disabled: !reason.trim() || !!restriction }}
      >
        <Alert title="仅重试已注册的本地投影消费者；不会重新记账或执行外部渠道。" />
        <Input.TextArea
          aria-label="重试原因"
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          maxLength={500}
        />
        {error && <Alert type="error" title={error} />}
      </Modal>
      <Modal
        title="创建结构化来源事件"
        open={createOpen}
        onCancel={() => !pending && setCreateOpen(false)}
        footer={null}
        width={950}
        destroyOnHidden
      >
        <Form
          form={form}
          layout="vertical"
          onFinish={create}
          initialValues={{
            sourceDocumentVersion: "1",
            eventType: "ADJUSTMENT",
            quantity: "1",
            taxAmount: "0.00",
            journalLines: [
              { debit: "0.00", credit: "0.00" },
              { debit: "0.00", credit: "0.00" },
            ],
          }}
        >
          <Alert title="金额为人民币两位提交。本片仅生成草稿，需人工明确科目映射；不能据此宣称ERP、税务或支付全流程已完成。" />
          <Form.Item name="eventType" label="业务事件类型" rules={required}>
            <Select
              options={sourceTypes.map(([value, label]) => ({ value, label }))}
            />
          </Form.Item>
          {(
            [
              ["sourceSystem", "来源系统"],
              ["sourceEventId", "稳定来源事件ID（重投保持不变）"],
              ["sourceDocumentId", "来源单据ID"],
              ["sourceDocumentVersion", "来源单据版本（正整数）"],
              ["mappingRuleVersion", "映射规则版本"],
              ["handlerVersion", "处理器版本"],
              ["businessDate", "业务日期 YYYY-MM-DD"],
              ["accountingDate", "会计日期 YYYY-MM-DD"],
              ["occurredAt", "来源发生时间（UTC ISO格式）"],
              ["reason", "业务依据与原因"],
              ["sourceLineId", "来源行ID"],
              ["businessSemantic", "业务语义"],
              ["description", "业务行说明"],
              ["quantity", "业务数量"],
              ["netAmount", "不含税金额"],
              ["taxAmount", "税额"],
              ["grossAmount", "含税金额"],
              ["summary", "凭证摘要"],
            ] as const
          ).map(([name, label]) => (
            <Form.Item key={name} name={name} label={label} rules={required}>
              <Input maxLength={1000} />
            </Form.Item>
          ))}
          <Typography.Title level={5}>明确科目映射分录</Typography.Title>
          <Form.List name="journalLines">
            {(fields, { add, remove }) => (
              <Space orientation="vertical" style={{ width: "100%" }}>
                {fields.map((field) => (
                  <Space key={field.key} wrap>
                    <Form.Item
                      name={[field.name, "accountId"]}
                      label="科目"
                      rules={required}
                    >
                      <Select style={{ width: 240 }} options={accountOptions} />
                    </Form.Item>
                    <Form.Item
                      name={[field.name, "debit"]}
                      label="借方"
                      rules={required}
                    >
                      <Input />
                    </Form.Item>
                    <Form.Item
                      name={[field.name, "credit"]}
                      label="贷方"
                      rules={required}
                    >
                      <Input />
                    </Form.Item>
                    <Form.Item name={[field.name, "memo"]} label="行摘要">
                      <Input />
                    </Form.Item>
                    <Button
                      disabled={fields.length <= 2}
                      onClick={() => remove(field.name)}
                    >
                      移除
                    </Button>
                  </Space>
                ))}
                <Button onClick={() => add({ debit: "0.00", credit: "0.00" })}>
                  增加分录
                </Button>
              </Space>
            )}
          </Form.List>
          {error && <Alert type="error" title={error} />}
          <Button
            htmlType="submit"
            type="primary"
            loading={pending}
            disabled={!canCreate || !!restriction || pending}
          >
            接收来源并生成待审草稿
          </Button>
        </Form>
      </Modal>
    </Space>
  );
}
