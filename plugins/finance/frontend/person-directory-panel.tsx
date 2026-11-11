import { useEffect, useRef, useState } from "react";
import {
  Alert,
  Button,
  Checkbox,
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
import type {
  Configuration,
  PersonPage,
  PersonDetail,
  ProposalPage,
  ProposalDetail,
  Readiness,
  ConfirmProposal,
  CancelProposal,
} from "./person-directory-contract";
import {
  directoryAbility,
  directoryCreatePayload,
  directoryErrors,
  directoryGeneration,
  directoryOperations,
  directoryRequestKey,
  directoryResourceFailure,
  independentDirectoryConfirmation,
} from "./person-directory-rules";
const labels = {
  PENDING: "待独立确认",
  APPROVED: "已确认",
  REJECTED: "已驳回",
  CANCELLED: "已撤销",
};
const required = [{ required: true, message: "请填写此项" }];
type Props = {
  configuration: Configuration;
  active: boolean;
  canPropose: boolean;
  canConfirm: boolean;
  authorizationPending: boolean;
  configurationError: unknown;
  onReadDenied: () => void;
  readable: (error: unknown) => string;
};
export function PersonDirectoryPanel({
  configuration,
  active,
  canPropose,
  canConfirm,
  authorizationPending,
  configurationError,
  onReadDenied,
  readable,
}: Props) {
  const base = `/finance/v1/tenants/${configuration.tenantId}/person-directory`;
  const [persons, setPersons] = useState<PersonPage | null>(null),
    [proposals, setProposals] = useState<ProposalPage | null>(null),
    [readiness, setReadiness] = useState<Readiness | null>(null);
  const [person, setPerson] = useState<PersonDetail | null>(null),
    [proposal, setProposal] = useState<ProposalDetail | null>(null);
  const [error, setError] = useState(""),
    [notice, setNotice] = useState(""),
    [loading, setLoading] = useState(false),
    [busy, setBusy] = useState(false),
    [limit, setLimit] = useState(20);
  const [dialog, setDialog] = useState<
    "create" | "APPROVE" | "REJECT" | "cancel" | null
  >(null);
  const [form] = Form.useForm();
  const operation = Form.useWatch("operation", form);
  const alive = useRef(false),
    epoch = useRef(0),
    controllers = useRef(new Set<AbortController>()),
    keys = useRef(new Map<string, string>());
  const canPrepare =
      !authorizationPending &&
      directoryAbility(configuration, "PROPOSE", canPropose),
    canReview =
      !authorizationPending &&
      directoryAbility(configuration, "CONFIRM", canConfirm);
  function clear() {
    epoch.current++;
    for (const controller of controllers.current) controller.abort();
    controllers.current.clear();
    setPersons(null);
    setProposals(null);
    setReadiness(null);
    setPerson(null);
    setProposal(null);
    setDialog(null);
    form.resetFields();
    setLoading(false);
    setBusy(false);
    setNotice("");
  }
  function explain(cause: unknown) {
    const code = cause instanceof Error ? cause.message : "";
    return directoryErrors[code] ?? readable(cause);
  }
  function failed(cause: unknown, reading: boolean) {
    if (reading && cause instanceof ApiError && cause.status === 403) {
      clear();
      setError("当前账号已无此租户目录读取授权，目录内容已清理。");
      onReadDenied();
      return;
    }
    const resourceFailure = directoryResourceFailure(
      cause instanceof Error ? cause.message : "",
      reading,
    );
    if (resourceFailure) {
      if (resourceFailure.clearReadState) clear();
      setError(resourceFailure.message);
      return;
    }
    if (
      cause instanceof Error &&
      cause.message === "PERSON_DIRECTORY_PAGE_STALE"
    ) {
      clear();
    }
    setError(explain(cause));
  }
  async function get<T>(path: string): Promise<T> {
    const controller = new AbortController();
    controllers.current.add(controller);
    try {
      return await api<T>(`${base}${path}`, { signal: controller.signal });
    } finally {
      controllers.current.delete(controller);
    }
  }
  function beginRead() {
    epoch.current++;
    for (const controller of controllers.current) controller.abort();
    controllers.current.clear();
    return epoch.current;
  }
  async function load(
    cursor: string | null = null,
    resource: "persons" | "proposals" | "both" = "both",
    size = limit,
  ) {
    const revision = beginRead();
    setLoading(true);
    setError("");
    setPerson(null);
    setProposal(null);
    setDialog(null);
    form.resetFields();
    if (resource === "persons" || resource === "both") setPersons(null);
    if (resource === "proposals" || resource === "both") setProposals(null);
    const query = `?limit=${size}${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ""}`;
    try {
      const [people, plans, ready] = await Promise.all([
        resource !== "proposals"
          ? get<PersonPage>(`/persons${query}`)
          : Promise.resolve(null),
        resource !== "persons"
          ? get<ProposalPage>(`/proposals${query}`)
          : Promise.resolve(null),
        get<Readiness>("/readiness"),
      ]);
      if (!alive.current || revision !== epoch.current) return;
      const expected = directoryGeneration(ready.generation);
      if (
        (people && directoryGeneration(people.generation) !== expected) ||
        (plans && directoryGeneration(plans.generation) !== expected) ||
        (resource !== "both" &&
          ((persons && persons.generation !== expected) ||
            (proposals && proposals.generation !== expected)))
      )
        throw new Error("PERSON_DIRECTORY_PAGE_STALE");
      if (people) setPersons(people);
      if (plans) setProposals(plans);
      setReadiness(ready);
    } catch (cause) {
      if (alive.current && revision === epoch.current) failed(cause, true);
    } finally {
      if (alive.current && revision === epoch.current) setLoading(false);
    }
  }
  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
      epoch.current++;
      for (const controller of controllers.current) controller.abort();
      controllers.current.clear();
      keys.current.clear();
    };
  }, []);
  useEffect(() => {
    if (!active) {
      clear();
      setError("");
    } else if (alive.current) void load();
  }, [active, configuration.generation]);
  useEffect(() => {
    if (
      configurationError instanceof Error &&
      directoryResourceFailure(configurationError.message, true)
    )
      failed(configurationError, true);
  }, [configurationError]);
  async function detail(resource: "persons" | "proposals", id: string) {
    const revision = beginRead();
    setPerson(null);
    setProposal(null);
    setLoading(true);
    setError("");
    try {
      if (resource === "persons") {
        const data = await get<PersonDetail>(`/persons/${id}`);
        if (alive.current && revision === epoch.current) setPerson(data);
      } else {
        const data = await get<ProposalDetail>(`/proposals/${id}`);
        if (readiness && data.generation !== readiness.generation)
          throw new Error("PERSON_DIRECTORY_PAGE_STALE");
        if (alive.current && revision === epoch.current) setProposal(data);
      }
    } catch (cause) {
      if (alive.current && revision === epoch.current) failed(cause, true);
    } finally {
      if (alive.current && revision === epoch.current) setLoading(false);
    }
  }
  async function write(values: Record<string, unknown>) {
    if (busy || !active || !dialog || authorizationPending) return;
    if (
      dialog === "create"
        ? !canPrepare
        : dialog === "cancel"
          ? !canPrepare
          : !canReview
    )
      return;
    const revision = epoch.current;
    let path = "/proposals",
      payload;
    try {
      if (dialog === "create")
        payload = directoryCreatePayload(values, "pending");
      else {
        if (!proposal || proposal.proposal.status !== "PENDING")
          throw new Error("请重新读取待确认提案。");
        if (
          dialog === "cancel" &&
          configuration.currentActorAccountId !== proposal.proposal.preparedBy
        )
          throw new Error("仅原制备账号可撤销。");
        if (
          dialog !== "cancel" &&
          !independentDirectoryConfirmation(configuration, proposal.proposal)
        )
          throw new Error("SELF_APPROVAL_FORBIDDEN");
        const reason = values.reason;
        if (
          typeof reason !== "string" ||
          !reason.trim() ||
          reason.length > 1000
        )
          throw new Error("请填写操作理由（最多1000字）。");
        const common: CancelProposal = {
          schemaVersion: "1",
          idempotencyKey: "pending",
          expectedVersion: directoryGeneration(proposal.proposal.version),
          contentDigest: proposal.proposal.contentDigest,
          reason,
        };
        path += `/${proposal.proposal.id}/${dialog === "cancel" ? "cancel" : "confirm"}`;
        payload =
          dialog === "cancel"
            ? common
            : ({ ...common, decision: dialog } satisfies ConfirmProposal);
      }
      const fingerprint = JSON.stringify([path, payload]);
      const key = directoryRequestKey(keys.current, fingerprint, () =>
        crypto.randomUUID(),
      );
      payload.idempotencyKey = key;
      setBusy(true);
      setError("");
      setNotice("");
      const controller = new AbortController();
      controllers.current.add(controller);
      try {
        await mutate<ProposalDetail>(
          `${base}${path}`,
          "POST",
          payload,
          controller.signal,
        );
      } finally {
        controllers.current.delete(controller);
      }
      if (!alive.current || revision !== epoch.current) return;
      keys.current.delete(fingerprint);
      setDialog(null);
      form.resetFields();
      setPerson(null);
      setProposal(null);
      setNotice("目录操作已处理，请以重新读取后的当前提案与绑定状态为准。");
      setBusy(false);
      await load();
    } catch (cause) {
      if (alive.current && revision === epoch.current) failed(cause, false);
    } finally {
      if (alive.current && revision === epoch.current) setBusy(false);
    }
  }
  function open(action: NonNullable<typeof dialog>) {
    form.resetFields();
    setError("");
    setDialog(action);
  }
  const pending = proposal?.proposal.status === "PENDING";
  const independent = proposal
    ? proposal.noAccountTransferConfirmed === true &&
      proposal.allKnownAccountsDeclared === true &&
      independentDirectoryConfirmation(configuration, proposal.proposal)
    : false;
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      {authorizationPending && (
        <Alert
          type="info"
          title="正在重新核对目录授权；如读取失败，请重查授权。当前面板保留，写操作暂不可用。"
        />
      )}
      <Alert
        type="info"
        showIcon
        title="组织人工核验目录"
        description="此目录依据组织人工核验记录管理人员与账号关系。人员UUID不是身份证号码，不代表政府身份认证。人员目录本身不启用全局财务职责分离；凭证保护以各年度已启用的人员审签模式为准；现金、年结未接入，历史身份未补核；本次认证不绑定单一业务动作。"
      />
      <Alert
        type="warning"
        title="账号不可转让"
        description="同一人员的多个账号应绑定已有人员；账号一经绑定即保留永久归属历史，撤销后不能恢复或改绑。治理种子不可公开撤销。所有写操作要求最近5分钟最终登录并通过TOTP；请在平台登录流程完成，不在此提交验证码或身份材料。"
      />
      {error && <Alert type="error" title={error} />}
      {notice && <Alert type="success" title={notice} />}
      <Descriptions
        size="small"
        bordered
        column={1}
        items={[
          {
            key: "authority",
            label: "组织核验责任主体",
            children: configuration.authorityName,
          },
          {
            key: "count",
            label: "当前目录",
            children: readiness
              ? `${readiness.activePersonCount}名有效人员 / ${readiness.activeBindingCount}个有效账号绑定；版本 ${readiness.generation}`
              : "尚未读取，不能视为零",
          },
          {
            key: "limits",
            label: "明确边界",
            children:
              "人员目录本身不启用全局财务职责分离；凭证保护以各年度已启用的人员审签模式为准；现金、年结未接入，历史身份未补核；本次认证不绑定单一业务动作。",
          },
        ]}
      />
      <Space wrap>
        <Button onClick={() => void load()} loading={loading} disabled={busy}>
          重新读取首屏
        </Button>
        <Select
          aria-label="目录每页条数"
          value={limit}
          options={[20, 50, 100].map((value) => ({
            value,
            label: `每页${value}条`,
          }))}
          disabled={busy || loading}
          onChange={(value) => {
            clear();
            setLimit(value);
            void load(null, "both", value);
          }}
        />
        <Button
          type="primary"
          disabled={!canPrepare || busy || loading}
          onClick={() => open("create")}
        >
          准备目录提案
        </Button>
      </Space>
      <Typography.Title level={5}>人员（仅当前页）</Typography.Title>
      <Table
        size="small"
        rowKey="id"
        loading={loading}
        pagination={false}
        dataSource={persons?.items ?? []}
        locale={{
          emptyText: persons ? "当前页无人员" : "尚未读取成功，不代表零条记录",
        }}
        scroll={{ x: 850 }}
        columns={[
          { title: "显示名称", dataIndex: "displayName", width: 180 },
          {
            title: "人员标识 / 组织引用",
            render: (_, row) => (
              <>
                {row.id}
                <br />
                {row.personReference}
              </>
            ),
          },
          { title: "版本", dataIndex: "version", width: 90 },
          {
            title: "操作",
            width: 110,
            render: (_, row) => (
              <Button
                disabled={loading || busy}
                onClick={() => void detail("persons", row.id)}
              >
                查看绑定
              </Button>
            ),
          },
        ]}
      />
      <Space>
        <Button
          disabled={loading || busy || !persons}
          onClick={() => void load(null, "persons")}
        >
          人员首屏
        </Button>
        <Button
          disabled={loading || busy || !persons?.nextCursor}
          onClick={() => void load(persons?.nextCursor ?? null, "persons")}
        >
          人员下一页
        </Button>
      </Space>
      {person && (
        <>
          <Descriptions
            column={1}
            bordered
            size="small"
            items={[
              {
                key: "id",
                label: "人员",
                children: `${person.person.displayName} · ${person.person.id}`,
              },
              {
                key: "ref",
                label: "组织核验引用",
                children: person.sourceReference,
              },
              {
                key: "sha",
                label: "组织记录摘要",
                children: (
                  <Typography.Text style={{ overflowWrap: "anywhere" }}>
                    {person.sourceSha256}
                  </Typography.Text>
                ),
              },
            ]}
          />
          <Table
            rowKey="id"
            size="small"
            pagination={false}
            dataSource={person.bindings}
            scroll={{ x: 800 }}
            columns={[
              { title: "账号UUID", dataIndex: "accountId" },
              {
                title: "状态",
                render: (_, row) =>
                  row.status === "ACTIVE" ? "有效" : "永久撤销",
              },
              {
                title: "治理种子",
                render: (_, row) =>
                  row.governanceSeed ? "受保护" : "普通绑定",
              },
              { title: "撤销时间", dataIndex: "revokedAt" },
            ]}
          />
          {person.bindingsTruncated && (
            <Alert
              type="warning"
              title="绑定仅返回前100条，不能视为全部已知账号。请核对组织记录。"
            />
          )}
        </>
      )}
      <Typography.Title level={5}>不可变提案（仅当前页）</Typography.Title>
      <Table
        size="small"
        rowKey="id"
        loading={loading}
        pagination={false}
        dataSource={proposals?.items ?? []}
        locale={{
          emptyText: proposals
            ? "当前页无提案"
            : "尚未读取成功，不代表零条记录",
        }}
        scroll={{ x: 1000 }}
        columns={[
          {
            title: "操作",
            width: 220,
            render: (_, row) => directoryOperations[row.operation],
          },
          { title: "目标账号", dataIndex: "accountId", width: 310 },
          {
            title: "状态",
            width: 130,
            render: (_, row) => <Tag>{labels[row.status]}</Tag>,
          },
          {
            title: "制备时间 / 到期时间",
            width: 230,
            render: (_, row) => (
              <>
                {row.createdAt}
                <br />
                {row.expiresAt}
              </>
            ),
          },
          {
            title: "操作",
            width: 100,
            render: (_, row) => (
              <Button
                disabled={loading || busy}
                onClick={() => void detail("proposals", row.id)}
              >
                查看提案
              </Button>
            ),
          },
        ]}
      />
      <Space>
        <Button
          disabled={loading || busy || !proposals}
          onClick={() => void load(null, "proposals")}
        >
          提案首屏
        </Button>
        <Button
          disabled={loading || busy || !proposals?.nextCursor}
          onClick={() => void load(proposals?.nextCursor ?? null, "proposals")}
        >
          提案下一页
        </Button>
      </Space>
      {proposal && (
        <>
          <Descriptions
            bordered
            column={1}
            size="small"
            items={[
              {
                key: "operation",
                label: "拟处理事项",
                children: directoryOperations[proposal.proposal.operation],
              },
              {
                key: "status",
                label: "当前状态",
                children: labels[proposal.proposal.status],
              },
              {
                key: "account",
                label: "目标账号 / 人员",
                children: `${proposal.proposal.accountId} / ${proposal.proposal.personId ?? proposal.proposal.displayName ?? "待建立"}`,
              },
              {
                key: "maker",
                label: "制备账号 / 人员",
                children: `${proposal.proposal.preparedBy} / ${proposal.proposal.preparedPersonId}`,
              },
              {
                key: "evidence",
                label: "组织核验引用",
                children: proposal.sourceReference,
              },
              {
                key: "sha",
                label: "组织记录摘要",
                children: (
                  <Typography.Text style={{ overflowWrap: "anywhere" }}>
                    {proposal.sourceSha256}
                  </Typography.Text>
                ),
              },
              { key: "reason", label: "制备说明", children: proposal.reason },
              {
                key: "transfer",
                label: "冻结的禁止转让声明",
                children:
                  proposal.noAccountTransferConfirmed === true
                    ? "制备人已主动确认账号未转让、撤销后不恢复或改绑"
                    : "声明缺失，不能确认",
              },
              {
                key: "accounts",
                label: "冻结的全部已知账号声明",
                children:
                  proposal.allKnownAccountsDeclared === true
                    ? "制备人已主动声明全部已知账号、同人不另建重复身份"
                    : "声明缺失，不能确认",
              },
              {
                key: "decision",
                label: "决定说明",
                children: proposal.decisionReason ?? "尚无决定",
              },
              {
                key: "version",
                label: "冻结版本 / 内容摘要",
                children: (
                  <Typography.Text style={{ overflowWrap: "anywhere" }}>
                    {proposal.proposal.version} /{" "}
                    {proposal.proposal.contentDigest}
                  </Typography.Text>
                ),
              },
              {
                key: "expires",
                label: "独立确认期限",
                children: proposal.proposal.expiresAt,
              },
            ]}
          />
          <Space wrap>
            <Button
              disabled={
                !canReview ||
                !pending ||
                !independent ||
                busy ||
                loading ||
                Date.parse(proposal.proposal.expiresAt) <= Date.now()
              }
              onClick={() => open("APPROVE")}
            >
              独立确认
            </Button>
            <Button
              disabled={
                !canReview || !pending || !independent || busy || loading
              }
              onClick={() => open("REJECT")}
            >
              独立驳回
            </Button>
            <Button
              disabled={
                !canPrepare ||
                !pending ||
                proposal.proposal.preparedBy !==
                  configuration.currentActorAccountId ||
                busy ||
                loading
              }
              onClick={() => open("cancel")}
            >
              原账号撤销提案
            </Button>
          </Space>
          <Collapse
            items={[
              {
                key: "snapshot",
                label: "公开不可变提案与处理快照",
                children: (
                  <pre
                    style={{ whiteSpace: "pre-wrap", overflowWrap: "anywhere" }}
                  >
                    {JSON.stringify(proposal, null, 2)}
                  </pre>
                ),
              },
            ]}
          />
        </>
      )}
      <Modal
        title={
          dialog === "create"
            ? "准备组织人工核验提案"
            : dialog === "APPROVE"
              ? "独立确认目录变更"
              : dialog === "REJECT"
                ? "独立驳回提案"
                : "原制备账号撤销提案"
        }
        open={!!dialog}
        onCancel={() => {
          if (!busy) {
            setDialog(null);
            form.resetFields();
          }
        }}
        onOk={() => form.submit()}
        confirmLoading={busy}
        okButtonProps={{ disabled: authorizationPending }}
        destroyOnHidden
      >
        {error && <Alert type="error" title={error} />}
        <Form form={form} layout="vertical" onFinish={write}>
          {dialog === "create" && (
            <>
              <Form.Item name="operation" label="目录操作" rules={required}>
                <Select
                  options={Object.entries(directoryOperations).map(
                    ([value, label]) => ({ value, label }),
                  )}
                />
              </Form.Item>
              <Form.Item
                name="accountId"
                label="组织已核验的目标账号UUID（不是身份证）"
                rules={required}
              >
                <Input maxLength={36} />
              </Form.Item>
              {operation === "CREATE_PERSON_AND_BIND" ? (
                <Form.Item
                  name="displayName"
                  label="组织核验的人员显示名称"
                  rules={required}
                >
                  <Input maxLength={120} />
                </Form.Item>
              ) : (
                <Form.Item
                  name="personId"
                  label="已有人员UUID（可从本页真实人员详情复制）"
                  rules={required}
                >
                  <Input maxLength={36} />
                </Form.Item>
              )}
              <Form.Item
                name="sourceReference"
                label="组织人工核验记录引用（不上传证件材料）"
                rules={required}
              >
                <Input maxLength={500} />
              </Form.Item>
              <Form.Item
                name="sourceSha256"
                label="实际组织核验记录SHA-256"
                rules={[
                  ...required,
                  {
                    pattern: /^[a-f0-9]{64}$/,
                    message: "请输入实际记录的64位小写SHA-256",
                  },
                ]}
              >
                <Input maxLength={64} />
              </Form.Item>
              <Form.Item
                name="noAccountTransferConfirmed"
                valuePropName="checked"
                rules={[
                  {
                    validator: (_, value) =>
                      value === true
                        ? Promise.resolve()
                        : Promise.reject(new Error("请主动确认")),
                  },
                ]}
              >
                <Checkbox>
                  我确认账号未转让给另一人员，撤销后不得恢复或改绑。
                </Checkbox>
              </Form.Item>
              <Form.Item
                name="allKnownAccountsDeclared"
                valuePropName="checked"
                rules={[
                  {
                    validator: (_, value) =>
                      value === true
                        ? Promise.resolve()
                        : Promise.reject(new Error("请主动声明")),
                  },
                ]}
              >
                <Checkbox>
                  我已核对并声明该人员全部已知账号；同一人员不另建重复身份。
                </Checkbox>
              </Form.Item>
            </>
          )}
          <Form.Item
            name="reason"
            label={dialog === "create" ? "组织核验及操作说明" : "本次决定理由"}
            rules={required}
          >
            <Input.TextArea maxLength={1000} rows={4} />
          </Form.Item>
        </Form>
      </Modal>
    </Space>
  );
}
