import { claimOccupancy } from "./cash-match-contract";
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
import type { Period } from "./types";
import { useMovementEvidence } from "./movement-evidence";
import {
  movementPayload,
  movementDirectionLabel,
  movementVoidPayload,
  type MovementCatalog,
  type ApprovedMovementScope,
  type CashLeg,
  type MovementSummary,
  type MovementDetail,
  type MovementForm,
} from "./cash-movement-contract";
const required = [{ required: true, message: "请填写此项" }];
const kindLabels: Record<string, string> = {
  ORDINARY_CUSTOMER_RECEIPT: "普通客户收款",
  ORDINARY_SUPPLIER_PAYMENT: "普通供应商付款",
  ORDINARY_PAYROLL: "普通薪酬付款",
  ORDINARY_TAX_PAYMENT: "普通税款支付",
  ORDINARY_BORROWING_RECEIPT: "普通借款收取",
  ORDINARY_PRINCIPAL_REPAYMENT: "普通本金偿还",
  ORDINARY_CAPITAL_RECEIPT: "普通资本投入收取",
};
const errors: Record<string, string> = {
  CASH_SOURCE_DUPLICATE_CONFLICT:
    "该原件的同一全文件来源行已登记，换命名空间或稳定键不能重复登记。",
  SOURCE_ALREADY_VOIDED: "该稳定来源已作废并保留墓碑，不能重新启用。",
  CASH_LEG_ALREADY_REGISTERED: "该现金腿已有有效登记，请核对原记录。",
  CASH_MOVEMENT_MATCH_INVALID:
    "真实来源与已记账现金腿不完全一致，请核对日期、方向、科目和全额金额。",
  CASH_MOVEMENT_SCOPE_STALE: "选定月度范围、科目或证据已变化，请刷新重新核对。",
  CASH_REGISTRATION_IN_USE: "该登记被有效后续业务使用，不能作废。",
  SOURCE_PAYLOAD_CONFLICT:
    "该稳定来源已存在不同内容，不能覆盖或换请求键重复登记。",
  SOURCE_SCOPE_CONFLICT: "该稳定来源已绑定其他范围，不能跨账套或年度重复登记。",
};
type Props = {
  bookId: string;
  yearId: string;
  active: boolean;
  canRead: boolean;
  canCreate: boolean;
  restriction: string | null;
  periods: Period[];
  readable: (error: unknown) => string;
  onReadDenied: () => void;
  onCorrection?: (id: string) => void;
};
export function CashMovementPanel({
  bookId,
  yearId,
  active,
  canRead,
  canCreate,
  restriction,
  periods,
  readable,
  onReadDenied,
  onCorrection,
}: Props) {
  const scope = `/finance/v1/books/${bookId}/years/${yearId}`;
  const base = `${scope}/cashflow/movement-registrations`;
  const prefix = ["finance", bookId, yearId, "cash-movements"];
  const client = useQueryClient();
  const [cursor, setCursor] = useState<string | null>(null);
  const [status, setStatus] = useState("");
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [scopeCursor, setScopeCursor] = useState<string | null>(null);
  const [scopeId, setScopeId] = useState("");
  const [accountId, setAccountId] = useState("");
  const [legCursor, setLegCursor] = useState<string | null>(null);
  const [leg, setLeg] = useState<CashLeg | null>(null);
  const [voidOpen, setVoidOpen] = useState(false);
  const [reason, setReason] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [pending, setPending] = useState(false);
  const [form] = Form.useForm<MovementForm>();
  const controller = useRef<AbortController | null>(null);
  const keys = useRef(new Map<string, string>());
  const busy = useRef(false);
  useEffect(() => {
    controller.current = new AbortController();
    return () => {
      controller.current?.abort();
      void client.cancelQueries({ queryKey: prefix });
      client.removeQueries({ queryKey: prefix });
    };
  }, [bookId, yearId, client]);
  const identity = useQuery({
    queryKey: [...prefix, "actor"],
    enabled: active && canRead,
    queryFn: ({ signal }) => api<{ userId: string }>("/auth/me", { signal }),
  });
  const list = useQuery({
    queryKey: [...prefix, "list", status, cursor],
    enabled: active && canRead,
    queryFn: ({ signal }) =>
      api<{ items: MovementSummary[]; nextCursor: string | null }>(
        `${base}?limit=20${status ? `&status=${status}` : ""}${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ""}`,
        { signal },
      ),
  });
  const detail = useQuery({
    queryKey: [...prefix, "detail", selectedId],
    enabled: active && canRead && !!selectedId,
    queryFn: ({ signal }) =>
      api<MovementDetail>(`${base}/${selectedId}`, { signal }),
  });
  const catalog = useQuery({
    queryKey: [...prefix, "catalog"],
    enabled: active && canRead && creating,
    queryFn: ({ signal }) =>
      api<MovementCatalog>(`${base}/catalog`, { signal }),
  });
  const scopes = useQuery({
    queryKey: [...prefix, "scopes", scopeCursor],
    enabled: active && canRead && creating,
    queryFn: ({ signal }) =>
      api<{
        items: Omit<ApprovedMovementScope, "items">[];
        nextCursor: string | null;
      }>(
        `${scope}/cashflow/scope-revisions?limit=20${scopeCursor ? `&cursor=${encodeURIComponent(scopeCursor)}` : ""}`,
        { signal },
      ),
  });
  const approved = useQuery({
    queryKey: [...prefix, "scope", scopeId],
    enabled: active && canRead && creating && !!scopeId,
    queryFn: ({ signal }) =>
      api<ApprovedMovementScope>(
        `${scope}/cashflow/scope-revisions/${scopeId}`,
        { signal },
      ),
  });
  const legs = useQuery({
    queryKey: [...prefix, "legs", scopeId, accountId, legCursor],
    enabled: active && canRead && creating && !!scopeId && !!accountId,
    queryFn: ({ signal }) =>
      api<{ items: CashLeg[]; nextCursor: string | null }>(
        `${base}/eligible-cash-legs?cashScopeRevisionId=${encodeURIComponent(scopeId)}&accountId=${encodeURIComponent(accountId)}&limit=20${legCursor ? `&cursor=${encodeURIComponent(legCursor)}` : ""}`,
        { signal },
      ),
  });
  const originals = useQuery({
    queryKey: [...prefix, "originals"],
    enabled: active && canRead && creating,
    queryFn: ({ signal }) =>
      api<{ id: string; filename: string; sha256: string }[]>(
        `${scope}/originals`,
        { signal },
      ),
  });
  const reading = [
    identity,
    list,
    detail,
    catalog,
    scopes,
    approved,
    legs,
    originals,
  ];
  const readError = reading.find((query) => query.isError)?.error;
  const denied = reading.some(
    (query) =>
      query.isError &&
      query.error instanceof ApiError &&
      query.error.status === 403,
  );
  useEffect(() => {
    if (denied) onReadDenied();
  }, [denied, onReadDenied]);
  function message(cause: unknown) {
    return cause instanceof Error && errors[cause.message]
      ? errors[cause.message]
      : readable(cause);
  }
  const evidence = useMovementEvidence(
    scope,
    canRead && canCreate && !restriction,
    onReadDenied,
    message,
  );
  const data = detail.data;
  const periodOpen = (date: string) =>
    periods.some(
      (period) =>
        period.month === Number(date.slice(5, 7)) && period.status === "OPEN",
    );
  const canVoid = Boolean(
    canCreate &&
    canRead &&
    !restriction &&
    data?.status === "REGISTERED" &&
    identity.data?.userId === data?.preparedBy &&
    data &&
    periodOpen(data.occurredDate),
  );
  async function write(
    path: string,
    payload: Record<string, unknown>,
    done: (result: MovementDetail) => void,
  ) {
    if (!canCreate || !canRead || restriction || busy.current) return;
    const signal = controller.current?.signal;
    if (!signal || signal.aborted) return;
    busy.current = true;
    setPending(true);
    setError("");
    setNotice("");
    const fingerprint = JSON.stringify([path, payload]);
    const idempotencyKey = keys.current.get(fingerprint) ?? crypto.randomUUID();
    keys.current.set(fingerprint, idempotencyKey);
    try {
      const result = await mutate<MovementDetail>(
        path,
        "POST",
        { ...payload, idempotencyKey },
        signal,
      );
      keys.current.delete(fingerprint);
      await client.invalidateQueries({ queryKey: prefix });
      if (!signal.aborted) {
        done(result);
        setNotice(
          "请求已处理，请刷新查看权威当前状态。作废墓碑不会复活；本登记始终未核实、未分类且不计入报表。",
        );
      }
    } catch (cause) {
      if (!signal.aborted) setError(message(cause));
    } finally {
      busy.current = false;
      if (!signal.aborted) setPending(false);
    }
  }
  function create(values: MovementForm) {
    if (
      !approved.data ||
      !leg ||
      !catalog.data ||
      evidence.uploading ||
      !periodOpen(values.occurredDate)
    ) {
      setError("请选择已批准范围、真实现金腿和开放月份");
      return;
    }
    try {
      const payload = movementPayload(
        values,
        approved.data,
        accountId,
        leg,
        catalog.data,
      );
      void write(base, payload, (result) => {
        setCreating(false);
        setSelectedId(result.id);
      });
    } catch (cause) {
      setError(message(cause));
    }
  }
  const item = approved.data?.items.find(
    (item) => item.accountId === accountId,
  );
  const sourceOptions = catalog.data?.sourceSystems
    .filter(
      (source) => item && source.instrumentKinds.includes(item.instrumentKind),
    )
    .map((source) => ({
      value: source.id,
      label: `${source.label} · ${source.evidenceKind}`,
    }));
  if (!canRead)
    return (
      <Alert
        type="warning"
        title="来源登记需要明确读取权限才能选取范围、证据和已记账现金腿；制单权限不自动授予读取权限。"
      />
    );
  if (denied) return null;
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <Alert
        type="info"
        title="未核实现金来源登记（不入报表）"
        description="用户从实际原件、银行流水或现金簿行主动登记，再与一条完整已记账现金腿比对；不从凭证自动生成现金。始终未核实、未分类，不参与报表。不代表银行验真、对账完整或现金流完成。内部划转、退款、净额、费用拆分、复杂及跨年业务本片不支持。"
      />
      {restriction && <Alert type="warning" title={restriction} />}
      <Space wrap>
        <Button
          type="primary"
          disabled={!canCreate || !!restriction || pending}
          onClick={() => {
            setCreating(true);
            setScopeId("");
            setAccountId("");
            setLeg(null);
            setScopeCursor(null);
            setLegCursor(null);
            setError("");
            form.resetFields();
          }}
        >
          登记真实来源行
        </Button>
        <Select
          aria-label="登记状态筛选"
          value={status}
          options={[
            { value: "", label: "全部登记状态" },
            { value: "REGISTERED", label: "已登记（未核实）" },
            { value: "VOIDED", label: "已作废（永久墓碑）" },
          ]}
          onChange={(value) => {
            setStatus(value);
            setCursor(null);
          }}
        />
        <Button loading={list.isFetching} onClick={() => void list.refetch()}>
          刷新
        </Button>
      </Space>
      {(error || readError || evidence.error) && (
        <Alert
          type="error"
          title={error || (readError ? message(readError) : evidence.error)}
        />
      )}{" "}
      {notice && <Alert type="success" title={notice} />}
      <Table<MovementSummary>
        rowKey="id"
        loading={list.isFetching}
        dataSource={list.isError ? [] : (list.data?.items ?? [])}
        pagination={false}
        scroll={{ x: 1000 }}
        columns={[
          { title: "稳定来源键", dataIndex: "sourceMovementKey" },
          { title: "全文件来源行", dataIndex: "sourceRowReference" },
          { title: "实际日期", dataIndex: "occurredDate" },
          {
            title: "方向",
            dataIndex: "direction",
            render: (value) => movementDirectionLabel(value),
          },
          { title: "登记金额", dataIndex: "normalizedAmount" },
          {
            title: "状态",
            dataIndex: "status",
            render: (value) => (
              <Tag>
                {value === "VOIDED" ? "已作废（永久墓碑）" : "已登记（未核实）"}
              </Tag>
            ),
          },
          { title: "核实/分类", render: () => "未核实 / 未分类 / 不入报表" },
          {
            title: "操作",
            render: (_, row) => (
              <Button
                onClick={() => {
                  setSelectedId(row.id);
                  if (selectedId === row.id) void detail.refetch();
                }}
              >
                查看权威详情
              </Button>
            ),
          },
        ]}
      />
      <Space>
        <Button
          disabled={!cursor || list.isFetching}
          onClick={() => setCursor(null)}
        >
          返回首屏
        </Button>
        <Button
          disabled={!list.data?.nextCursor || list.isFetching || list.isError}
          onClick={() => setCursor(list.data?.nextCursor ?? null)}
        >
          下一页
        </Button>
      </Space>
      <Modal
        title="未核实来源登记详情"
        open={!!selectedId}
        onCancel={() => setSelectedId(null)}
        footer={null}
        width={950}
      >
        <Button
          loading={detail.isFetching}
          onClick={() => void detail.refetch()}
        >
          刷新权威状态
        </Button>
        {data && !detail.isError && (
          <Space orientation="vertical" style={{ width: "100%" }}>
            <Alert
              type={data.status === "VOIDED" ? "warning" : "info"}
              title={
                data.status === "VOIDED"
                  ? "已作废，稳定来源和全文件行墓碑永久保留，不能重新启用"
                  : "已登记，但未核实、未分类、不计入报表"
              }
            />
            <Descriptions
              column={2}
              items={[
                {
                  key: "system",
                  label: "来源目录",
                  children: data.sourceSystem,
                },
                {
                  key: "source",
                  label: "稳定来源键",
                  children: data.sourceMovementKey,
                },
                {
                  key: "row",
                  label: "全文件来源行",
                  children: data.sourceRowReference,
                },
                {
                  key: "date",
                  label: "实际来源日期",
                  children: data.occurredDate,
                },
                {
                  key: "amount",
                  label: "来源登记金额",
                  children: data.normalizedAmount,
                },
                {
                  key: "direction",
                  label: "方向",
                  children: movementDirectionLabel(data.direction),
                },
                {
                  key: "scope",
                  label: "所选月度批准水位",
                  children: data.approvedScopeVersion,
                },
                {
                  key: "digest",
                  label: "内容摘要",
                  children: data.contentDigest,
                },
                { key: "maker", label: "登记人", children: data.preparedBy },
                {
                  key: "void",
                  label: "作废原因",
                  children: data.voidReason ?? "—",
                },
                { key: "basis", label: "登记依据", children: data.reason },
              ]}
            />
            <Button
              onClick={() =>
                void evidence.download(
                  data.originalSnapshot.id,
                  data.originalSnapshot.filename,
                )
              }
            >
              下载独立来源原件复核
            </Button>
            <Collapse
              items={[
                {
                  key: "snapshots",
                  label: "查看服务端证据、范围、科目与匹配现金腿快照",
                  children: (
                    <pre
                      style={{
                        whiteSpace: "pre-wrap",
                        overflowWrap: "anywhere",
                      }}
                    >
                      {JSON.stringify(
                        {
                          verificationStatus: data.verificationStatus,
                          classificationStatus: data.classificationStatus,
                          reportContributionEnabled:
                            data.reportContributionEnabled,
                          original: data.originalSnapshot,
                          scope: data.scopeItemSnapshot,
                          account: data.accountSnapshot,
                          matchedLeg: data.matchedLegSnapshot,
                          watermark: data.asOfWatermark,
                        },
                        null,
                        2,
                      )}
                    </pre>
                  ),
                },
              ]}
            />
            <Button
              danger
              disabled={
                !canVoid || pending || detail.isFetching || detail.isError
              }
              onClick={() => {
                setVoidOpen(true);
                setReason("");
              }}
            >
              原登记人作废（保留墓碑）
            </Button>
            {(error || evidence.error) && (
              <Alert type="error" title={error || evidence.error} />
            )}
          </Space>
        )}
        {detail.error && <Alert type="error" title={message(detail.error)} />}
      </Modal>
      <Modal
        title="确认作废并永久保留来源墓碑"
        open={voidOpen}
        onCancel={() => !pending && setVoidOpen(false)}
        confirmLoading={pending}
        okButtonProps={{
          disabled:
            !canVoid || !reason.trim() || detail.isFetching || detail.isError,
        }}
        onOk={() => {
          if (!data || !canVoid) return;
          void write(
            `${base}/${data.id}/void`,
            movementVoidPayload(data, reason),
            () => setVoidOpen(false),
          );
        }}
      >
        <Alert
          type="warning"
          title="不删除原始内容，不反向修改任何记账事实，不能换请求键复活此来源。"
        />
        <Input.TextArea
          aria-label="作废原因"
          value={reason}
          onChange={(event) => setReason(event.target.value)}
          maxLength={1000}
        />
        {error && <Alert type="error" title={error} />}
      </Modal>
      <Modal
        title="登记实际独立来源行并比对完整现金腿"
        open={creating}
        onCancel={() => !pending && !evidence.uploading && setCreating(false)}
        footer={null}
        width={1100}
        destroyOnHidden
      >
        <Alert title="先从真实原件读取来源身份、日期、方向和金额。现金腿列表仅供选择和精确比对，绝不代表实际银行事实。" />
        <Typography.Title level={5}>
          1. 选择已批准的完整自然月范围（每页20）
        </Typography.Title>
        <Table
          rowKey="id"
          pagination={false}
          dataSource={
            scopes.isError
              ? []
              : (scopes.data?.items ?? []).filter(
                  (scope) => scope.status === "APPROVED",
                )
          }
          columns={[
            { title: "月初", dataIndex: "periodStart" },
            { title: "月末", dataIndex: "periodEnd" },
            { title: "本修订批准水位", dataIndex: "scopeVersion" },
            { title: "模板", dataIndex: "templateId" },
            {
              title: "选择",
              render: (_, row) => (
                <Button
                  disabled={pending}
                  onClick={() => {
                    setScopeId(row.id);
                    setAccountId("");
                    setLeg(null);
                    setLegCursor(null);
                    form.setFieldsValue({ sourceSystem: undefined });
                  }}
                >
                  使用此月范围
                </Button>
              ),
            },
          ]}
        />
        <Space>
          <Button
            disabled={!scopeCursor || scopes.isFetching}
            onClick={() => setScopeCursor(null)}
          >
            范围首屏
          </Button>
          <Button
            disabled={!scopes.data?.nextCursor || scopes.isFetching}
            onClick={() => setScopeCursor(scopes.data?.nextCursor ?? null)}
          >
            范围下一页
          </Button>
        </Space>
        {approved.data && (
          <Alert
            title={`已选 ${approved.data.periodStart} 至 ${approved.data.periodEnd}；本修订水位 ${approved.data.scopeVersion}；摘要 ${approved.data.contentDigest}`}
          />
        )}
        <Select
          aria-label="纳入的独立现金工具科目"
          placeholder="选择该批准范围中的INCLUDED科目"
          value={accountId || undefined}
          style={{ width: "100%", marginTop: 12 }}
          options={approved.data?.items
            .filter(
              (item) =>
                item.qualification === "INCLUDED" &&
                item.singleCashInstrument === true,
            )
            .map((item) => ({
              value: item.accountId,
              label: `${item.accountSnapshot?.code ?? ""} ${item.accountSnapshot?.name ?? item.accountId} · ${item.instrumentKind}`,
            }))}
          onChange={(id) => {
            setAccountId(id);
            setLeg(null);
            setLegCursor(null);
            form.setFieldsValue({ sourceSystem: undefined });
          }}
        />
        <Typography.Title level={5}>2. 从独立来源填写实际事实</Typography.Title>
        <Form form={form} layout="vertical" onFinish={create}>
          <Form.Item
            name="sourceSystem"
            label="服务端来源命名空间（手工来源，未核实渠道）"
            rules={required}
          >
            <Select options={sourceOptions} />
          </Form.Item>
          {(
            [
              ["sourceMovementKey", "真实稳定来源键（不是随机请求键）", 160],
              ["sourceDocumentId", "来源单据ID", 160],
              ["sourceDocumentVersion", "来源单据版本（正32位整数）", 10],
              [
                "sourceRowReference",
                "全文件唯一行定位（多账户文件必须含账户/章节/页/行）",
                120,
              ],
              ["occurredDate", "来源实际日期 YYYY-MM-DD", 10],
              ["amount", "从真实来源独立输入金额（人民币最多2位小数）", 23],
              ["description", "来源业务说明", 1000],
              ["reason", "登记依据与原因", 650],
            ] as const
          ).map(([name, label, maxLength]) => (
            <Form.Item key={name} name={name} label={label} rules={required}>
              <Input maxLength={maxLength} />
            </Form.Item>
          ))}
          <Form.Item name="direction" label="真实来源方向" rules={required}>
            <Select
              options={[
                { value: "IN", label: "流入" },
                { value: "OUT", label: "流出" },
              ]}
            />
          </Form.Item>
          <Form.Item
            name="transactionKind"
            label="目录中的普通业务类型（非现金流分类）"
            rules={required}
          >
            <Select
              options={catalog.data?.transactionKinds.map((kind) => ({
                value: kind.id,
                label: `${kindLabels[kind.id] ?? kind.id} · ${movementDirectionLabel(kind.direction)}`,
              }))}
            />
          </Form.Item>
          <Form.Item
            name="evidenceSource"
            label="证据原件类型"
            rules={required}
          >
            <Select
              options={[
                { value: "ELECTRONIC_ORIGINAL", label: "电子原件" },
                { value: "PAPER_SCAN", label: "纸质凭证扫描件" },
              ]}
            />
          </Form.Item>
          <Form.Item noStyle shouldUpdate>
            {() =>
              form.getFieldValue("evidenceSource") === "PAPER_SCAN" ? (
                <Form.Item
                  name="paperLocation"
                  label="实体原件保管位置"
                  rules={required}
                >
                  <Input maxLength={250} />
                </Form.Item>
              ) : null
            }
          </Form.Item>
          <Form.Item
            name="evidenceOriginalId"
            label="真实独立现金证据（不以记账凭证替代流水或现金簿）"
            rules={required}
          >
            <Select
              disabled={evidence.uploading}
              loading={originals.isFetching}
              options={originals.data?.map((original) => ({
                value: original.id,
                label: `${original.filename} · ${original.sha256}`,
              }))}
            />
          </Form.Item>
          <label>
            直接上传来源原件或扫描件（最多5MiB，非验真）：
            <input
              type="file"
              disabled={
                !canCreate || !!restriction || pending || evidence.uploading
              }
              onChange={(event) => {
                const file = event.target.files?.[0];
                if (file)
                  void evidence.upload(file, async (id) => {
                    await originals.refetch();
                    if (!controller.current?.signal.aborted)
                      form.setFieldValue("evidenceOriginalId", id);
                  });
                event.target.value = "";
              }}
            />
          </label>
          <Typography.Title level={5}>
            3. 选择一条已记账现金腿（不自动填写实际来源金额）
          </Typography.Title>
          <Table<CashLeg>
            rowKey={(row) => `${row.journalId}:${row.lineNo}`}
            dataSource={legs.isError ? [] : (legs.data?.items ?? [])}
            pagination={false}
            loading={legs.isFetching}
            rowSelection={{
              type: "radio",
              selectedRowKeys: leg ? [`${leg.journalId}:${leg.lineNo}`] : [],
              getCheckboxProps: (row) => ({
                disabled: !!row.activeClaimId || !!row.activeRegistrationId,
              }),
              onChange: (_, rows) => setLeg(rows[0] ?? null),
            }}
            scroll={{ x: 950 }}
            columns={[
              { title: "日期", dataIndex: "date" },
              { title: "凭证号", dataIndex: "number" },
              { title: "真实行号", dataIndex: "lineNo" },
              {
                title: "账面方向",
                dataIndex: "direction",
                render: movementDirectionLabel,
              },
              {
                title: "账面全额（非来源事实）",
                dataIndex: "normalizedAmount",
              },
              { title: "凭证版本", dataIndex: "journalVersion" },
              { title: "记账水位", dataIndex: "postingSeq" },
              {
                title: "有效占用",
                dataIndex: "activeRegistrationId",
                render: (_, row) => (
                  <Space orientation="vertical">
                    <span>{claimOccupancy(row)}</span>
                    {row.activeCorrectionId && onCorrection && (
                      <Button
                        onClick={() => onCorrection(row.activeCorrectionId!)}
                      >
                        查看占用更正
                      </Button>
                    )}
                  </Space>
                ),
              },
            ]}
          />
          {leg && (
            <Alert
              title={`明确选中的账面现金腿：${leg.number} / 行${leg.lineNo} / ${leg.date} / ${movementDirectionLabel(leg.direction)} / 账面${leg.normalizedAmount}；不代替实际来源事实。`}
            />
          )}
          <Space>
            <Button
              disabled={!legCursor || legs.isFetching}
              onClick={() => setLegCursor(null)}
            >
              现金腿首屏
            </Button>
            <Button
              disabled={!legs.data?.nextCursor || legs.isFetching}
              onClick={() => setLegCursor(legs.data?.nextCursor ?? null)}
            >
              现金腿下一页
            </Button>
          </Space>
          {(error || readError || evidence.error) && (
            <Alert
              type="error"
              title={error || (readError ? message(readError) : evidence.error)}
            />
          )}
          <Button
            htmlType="submit"
            type="primary"
            loading={pending}
            disabled={
              !canCreate ||
              !!restriction ||
              pending ||
              evidence.uploading ||
              !leg ||
              !approved.data ||
              catalog.isError ||
              catalog.isFetching ||
              approved.isError ||
              approved.isFetching ||
              legs.isFetching ||
              originals.isError
            }
          >
            登记为未核实 / 未分类 / 不入报表
          </Button>
        </Form>
      </Modal>
    </Space>
  );
}
