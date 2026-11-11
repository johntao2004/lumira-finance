import { useEffect, useRef, useState } from "react";
import {
  Alert,
  Button,
  Input,
  Modal,
  Select,
  Space,
  Table,
  Typography,
} from "antd";
import { PlatformForm as Form } from "@/platform/components/platform-form";
import type {
  Preview,
  RunDetail,
  RunPage,
  RunSummary,
} from "./profit-closing-contract";
import {
  closingCreatePayload,
  closingDate,
  closingKinds,
  closingStates,
  closingTotals,
} from "./profit-closing-rules";
import {
  ProfitClosingLines,
  ProfitClosingSource,
  ProfitClosingBlockers,
} from "./profit-closing-views";
import { ProfitClosingRun, type ClosingSeed } from "./profit-closing-run";
import {
  useProfitClosingResource,
  useProfitClosingDrafts,
  useProfitClosingIdentity,
} from "./profit-closing-resource";
import type { ProfitClosingProps } from "./profit-closing-actions";
export function ProfitClosingPanel({
  ui,
  year,
  seed,
  onSeedConsumed,
}: {
  ui: ProfitClosingProps;
  year: number;
  seed: ClosingSeed | null;
  onSeedConsumed: () => void;
}) {
  const [kind, setKind] = useState<Preview["producerKind"]>("PROFIT_LOSS"),
    [cutoff, setCutoff] = useState(""),
    [reverseId, setReverseId] = useState(""),
    [cancelled, setCancelled] = useState<RunSummary | null>(null),
    [query, setQuery] = useState(""),
    [selected, setSelected] = useState(""),
    [cursor, setCursor] = useState<string | null>(null),
    [limit, setLimit] = useState(20),
    [localRevision, setLocalRevision] = useState(0),
    [error, setError] = useState(""),
    [notice, setNotice] = useState(""),
    [modal, setModal] = useState<Preview | null>(null);
  const identity = useProfitClosingIdentity();
  const modalIdentity = useRef(identity);
  const drafts = useProfitClosingDrafts();
  const draftKey = (p: Preview) =>
    JSON.stringify([
      "create",
      p.producerKind,
      p.cutoff,
      p.source?.sourceDigest,
      p.generatedDigest,
      p.reversesRunId,
      cancelled?.id ?? null,
      cancelled?.version ?? null,
    ]);
  const [form] = Form.useForm();
  const revision = ui.revision + localRevision;
  const childUi = {
    ...ui,
    revision,
    onChanged: () => {
      setCursor(null);
      setLocalRevision((x) => x + 1);
      ui.onChanged();
    },
  };
  const base = `${ui.scope}/profit-closing`;
  const page = useProfitClosingResource<RunPage>(
    `${base}/runs?limit=${limit}${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ""}`,
    ui.active,
    revision,
    ui.onReadDenied,
    ui.readable,
  );
  const preview = useProfitClosingResource<Preview>(
    query,
    ui.active && !!query,
    revision,
    ui.onReadDenied,
    ui.readable,
  );
  useEffect(() => {
    setCursor(null);
    setSelected("");
    setQuery("");
    setModal(null);
    setError("");
    setCancelled(null);
    setReverseId("");
  }, [ui.scope, ui.revision, identity]);
  useEffect(() => {
    if (page.error) {
      setSelected("");
      setQuery("");
      setModal(null);
    }
  }, [page.error]);
  function prepare(value: ClosingSeed) {
    setSelected(value.run.id);
    setKind(
      value.action === "reversal"
        ? "TRANSFER_REVERSAL"
        : value.run.producerKind,
    );
    setCutoff(value.run.cutoff);
    setReverseId(
      value.action === "reversal"
        ? value.run.id
        : (value.run.reversesRunId ?? ""),
    );
    setCancelled(value.action === "rebuild" ? value.run : null);
    setQuery("");
    setModal(null);
    setError("");
    setNotice(
      "已明确准备对象，请读取当前真实来源；新操作标识只在明确生成时建立，不沿用已终结effect。",
    );
  }
  useEffect(() => {
    if (seed) {
      prepare(seed);
      onSeedConsumed();
    }
  }, [seed]);
  function resetPreview() {
    setQuery("");
    setModal(null);
    setCancelled(null);
    setError("");
  }
  function readPreview() {
    if (!closingDate(cutoff, kind) || !cutoff.startsWith(`${year}-`)) {
      setError("请选择当前年度真实月末；本年利润转存须12月31日。");
      return;
    }
    if (kind === "TRANSFER_REVERSAL" && !reverseId) {
      setError("请从真实已记账运行的全额反向入口选择目标，不手填凭证或金额。");
      return;
    }
    setError("");
    const path = `${base}/preview?producerKind=${kind}&cutoff=${cutoff}${kind === "TRANSFER_REVERSAL" ? `&reversesRunId=${encodeURIComponent(reverseId)}` : ""}`;
    if (query === path) void preview.read();
    else setQuery(path);
  }
  const p =
    preview.data?.cutoff === cutoff && preview.data.producerKind === kind
      ? preview.data
      : null;
  const mayCreate =
    !!p &&
    ui.active &&
    ui.newUseAllowed &&
    ui.canMake &&
    p.currentActor.identityAvailability === "AVAILABLE" &&
    !!p.currentActor.personId &&
    p.outcome === "PRODUCIBLE" &&
    p.allowedActions.includes("create-run") &&
    !!p.generatedLines?.length &&
    closingTotals(p.generatedLines).balanced;
  async function create(values: { operationId: string; reason: string }) {
    if (
      !modal ||
      modalIdentity.current !== identity ||
      !p ||
      !mayCreate ||
      modal.source?.sourceDigest !== p.source?.sourceDigest ||
      modal.generatedDigest !== p.generatedDigest
    ) {
      setError("来源或生成内容已变化，请重新读取，不会自动替换冻结正文。");
      return;
    }
    try {
      const payload = closingCreatePayload(modal, values, cancelled);
      const result = await preview.write<RunDetail>(`${base}/runs`, {
        ...payload,
      });
      if (result) {
        drafts.clear(draftKey(modal));
        setSelected(result.id);
        setCursor(null);
        setModal(null);
        setNotice(
          "运行已返回，请以当前运行、独立批准及真实记账证明为准；生成不等于记账或年度关闭。",
        );
        setLocalRevision((x) => x + 1);
        ui.onChanged();
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : ui.readable(e));
    }
  }
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <Alert
        type="info"
        title="人员审签结转（受控系统生成）"
        description="仅损益转本年利润、本年利润转未分配利润及一次全额反向。来源含既有真实已记账结转；不提供手工分录、不自动关闭期间或年度，不生成BASE/正式期初、分红、报表或归档。"
      />
      {error && <Alert type="error" title={error} />}{" "}
      {notice && <Alert type="info" title={notice} />}
      <Space wrap>
        <Select
          aria-label="受控结转阶段"
          value={kind}
          style={{ minWidth: 290 }}
          options={Object.entries(closingKinds)
            .filter(([key]) => key !== "TRANSFER_REVERSAL" || !!reverseId)
            .map(([value, label]) => ({ value, label }))}
          onChange={(value) => {
            setKind(value);
            resetPreview();
            if (value !== "TRANSFER_REVERSAL") setReverseId("");
          }}
        />
        <Input
          aria-label="结转会计截止日"
          disabled={kind === "TRANSFER_REVERSAL"}
          type="date"
          value={cutoff}
          min={`${year}-01-01`}
          max={`${year}-12-31`}
          onChange={(e) => {
            setCutoff(e.target.value);
            resetPreview();
          }}
          style={{ width: 190 }}
        />
        <Button
          onClick={readPreview}
          disabled={!ui.active}
          loading={preview.loading}
        >
          读取真实来源与只读预览
        </Button>
      </Space>
      {kind === "YEAR_END" && (
        <Alert
          type="info"
          title="本年利润转存仅12月31日；须损益结转已真实记账且损益余额实际清零。生成或批准不代替记账，不代表正式年度关闭。"
        />
      )}
      {reverseId && (
        <Alert
          type="warning"
          title={`明确全额反向原运行：${reverseId}；须最近有效结转优先，不能反向再反向。`}
        />
      )}{" "}
      {cancelled && (
        <Alert
          type="warning"
          title={`明确重建前次已终结尝试：${cancelled.id} / 版本${cancelled.version}；仍需新operationId，不复活原操作。`}
        />
      )}{" "}
      {preview.error && <Alert type="error" title={preview.error} />}{" "}
      {p && (
        <>
          <Alert
            type={p.outcome === "PRODUCIBLE" ? "info" : "warning"}
            title={
              p.outcome === "NOOP"
                ? "当前真实余额无需此阶段结转，不生成零凭证。"
                : p.outcome === "BLOCKED"
                  ? "当前条件阻断结转，不生成凭证。"
                  : "预览可供核对；尚未生成、审签或记账。"
            }
          />
          <ProfitClosingBlockers items={p.blockers} />
          {p.source ? (
            <ProfitClosingSource source={p.source} />
          ) : (
            <Alert
              type="warning"
              title="未提供可读来源快照，金额未知；不能按0或部分来源生成。"
            />
          )}{" "}
          {p.generatedLines && <ProfitClosingLines lines={p.generatedLines} />}
        </>
      )}
      <Button
        disabled={!mayCreate || preview.writing}
        onClick={() => {
          if (!p) return;
          form.resetFields();
          const saved = drafts.get(draftKey(p)) ?? {
            operationId: crypto.randomUUID(),
          };
          form.setFieldsValue(saved);
          drafts.save(draftKey(p), saved);
          modalIdentity.current = identity;
          setModal(p);
        }}
      >
        明确新操作并生成受控草稿
      </Button>
      <Typography.Title level={5}>本年度受控运行（当前页）</Typography.Title>
      <Space wrap>
        <Select
          aria-label="结转运行每页条数"
          value={limit}
          options={[20, 50, 100].map((value) => ({
            value,
            label: `每页${value}条`,
          }))}
          onChange={(v) => {
            setLimit(v);
            setCursor(null);
            setSelected("");
          }}
        />
        <Button
          onClick={() => {
            setCursor(null);
            setSelected("");
            if (cursor === null) void page.read();
          }}
        >
          运行首屏
        </Button>
        <Button
          disabled={!page.data?.nextCursor || page.loading}
          onClick={() => {
            setSelected("");
            setCursor(page.data?.nextCursor ?? null);
          }}
        >
          运行下一页
        </Button>
      </Space>
      {page.error && <Alert type="error" title={page.error} />}{" "}
      {!page.data && (
        <Alert
          type="warning"
          title="运行列表未读取成功，未知不代表没有运行；显式首屏可重读。"
        />
      )}
      <Table
        size="small"
        rowKey="id"
        pagination={false}
        dataSource={page.data?.items ?? []}
        locale={{
          emptyText: page.data ? "当前页没有运行" : "未读取成功，运行数量未知",
        }}
        loading={page.loading}
        scroll={{ x: 1150 }}
        columns={[
          {
            title: "运行 / 真实凭证",
            width: 340,
            render: (_, r: RunSummary) => (
              <span style={{ overflowWrap: "anywhere" }}>
                {r.id}
                <br />
                {r.journalId}
              </span>
            ),
          },
          {
            title: "阶段 / 截止",
            width: 250,
            render: (_, r: RunSummary) =>
              `${closingKinds[r.producerKind]} / ${r.cutoff}`,
          },
          {
            title: "状态 / 来源",
            width: 200,
            render: (_, r: RunSummary) =>
              `${closingStates[r.status]} / ${r.sourceFresh ? "当前来源匹配" : "当前来源已变化"}`,
          },
          {
            title: "经济效果",
            width: 160,
            dataIndex: "economicEffect",
            render: (v) =>
              v === "ACTIVE"
                ? "已记账生效"
                : v === "FULLY_REVERSED"
                  ? "已有真实全额反向"
                  : "尚未记账",
          },
          {
            title: "操作",
            width: 180,
            render: (_, r: RunSummary) => (
              <Button
                onClick={() => {
                  if (selected === r.id) setLocalRevision((x) => x + 1);
                  else setSelected(r.id);
                }}
              >
                查看来源、分录与处理
              </Button>
            ),
          },
        ]}
      />
      {selected && (
        <ProfitClosingRun
          key={selected}
          ui={childUi}
          runId={selected}
          onPrepare={prepare}
        />
      )}
      <Modal
        title="明确新操作生成只读系统凭证"
        open={!!modal && modalIdentity.current === identity && ui.active && !!p}
        onCancel={() => setModal(null)}
        onOk={() => form.submit()}
        confirmLoading={preview.writing}
        okButtonProps={{ disabled: !mayCreate || preview.writing }}
        destroyOnHidden
      >
        <Typography.Paragraph>
          操作标识为新真实运行永久身份；失败或未知结果须保留同一标识与正文。取消旧尝试后必须明确重建关系，不能自动换标识绕防重。
        </Typography.Paragraph>
        <Form
          form={form}
          layout="vertical"
          onFinish={create}
          onValuesChange={(_, values) => {
            if (modal) drafts.save(draftKey(modal), values);
          }}
        >
          <Form.Item
            name="operationId"
            label="本次新运行操作标识（小写UUID，重试保持）"
            rules={[{ required: true }]}
          >
            <Input maxLength={36} />
          </Form.Item>
          <Form.Item
            name="reason"
            label="生成及组织核验理由"
            rules={[{ required: true }, { max: 1000 }]}
          >
            <Input.TextArea maxLength={1000} rows={4} />
          </Form.Item>
        </Form>
      </Modal>
    </Space>
  );
}
