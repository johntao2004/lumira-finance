import { useEffect, useRef, useState } from "react";
import {
  Alert,
  Button,
  Checkbox,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Table,
  Typography,
} from "antd";
import type {
  CounterpartyDetail,
  RevisionDetail,
} from "./counterparty-contract";
import { useCounterparties } from "./counterparty-resource";
import {
  boundedPartyDraft,
  partyAction,
  partyCanDecide,
  partyCreate,
  partyProposal,
  partyStatus,
} from "./counterparty-rules";
import {
  PartyContent,
  PartyCurrent,
  PartyRevision,
} from "./counterparty-views";
import type { PartyDraft as Draft } from "./counterparty-state";
export function CounterpartyPanel({
  bookId,
  authHint,
  active,
  refreshToken,
  role,
  grants,
  onReadDenied,
  readable,
}: {
  bookId: string;
  authHint: string;
  active: boolean;
  refreshToken: number;
  role: string;
  grants: Record<string, boolean>;
  onReadDenied: () => void;
  readable: (e: unknown) => string;
}) {
  const resource = useCounterparties(
      bookId,
      authHint,
      active && !!bookId && !!grants.read,
      refreshToken,
      onReadDenied,
      readable,
    ),
    [knownId, setKnownId] = useState(""),
    [modal, setModal] = useState<Draft | null>(null),
    [formError, setFormError] = useState(""),
    [form] = Form.useForm<Record<string, unknown>>();
  const group = resource.group,
    detail = group?.detail,
    revision = group?.revision,
    actor = group?.list.currentActor,
    home = detail?.scope.governanceHomeBookId === bookId,
    canMake =
      role === "OWNER" &&
      !!grants.manage &&
      !!grants.create &&
      actor?.identityAvailability === "AVAILABLE" &&
      !!actor.personId,
    canDecide =
      !!revision &&
      !!actor &&
      partyCanDecide(actor, revision, role, !!grants.approve),
    available = !!group && !resource.busy && !resource.writing;
  const lastBoundary = useRef(resource.boundary);
  useEffect(() => {
    if (lastBoundary.current !== resource.boundary) {
      lastBoundary.current = resource.boundary;
      setKnownId("");
      setModal(null);
      setFormError("");
      form.resetFields();
    }
  }, [resource.boundary, form]);
  const panelLive = useRef({ alive: true, identity: resource.identity });
  panelLive.current.identity = resource.identity;
  useEffect(() => {
    panelLive.current.alive = true;
    return () => {
      panelLive.current.alive = false;
    };
  }, []);
  const currentModal = modal?.identity === resource.identity ? modal : null;
  function open(kind: Draft["kind"]) {
    if (!available) return;
    if (kind !== "create" && !detail) return;
    const target =
        kind === "create"
          ? "new"
          : kind === "propose"
            ? detail!.counterparty.id
            : revision!.revision.id,
      key = JSON.stringify([resource.identity, kind, target]);
    const record = boundedPartyDraft(resource.drafts, key, () => ({
      identity: resource.identity,
      kind,
      key,
      values: {
        ...(kind === "propose"
          ? (detail!.effectiveContent ?? revision?.content ?? {})
          : {}),
        relatedPartyStatus:
          kind === "propose"
            ? ((detail!.effectiveContent ?? revision?.content)
                ?.relatedPartyStatus ?? "UNKNOWN")
            : "UNKNOWN",
        desiredStatus: detail?.counterparty.effectiveStatus ?? "ACTIVE",
        organizationDeclarationConfirmed: false,
        identityNotPlaceholderConfirmed: false,
      },
      operationId: crypto.randomUUID(),
      revision: revision ?? null,
      party: detail ?? null,
    }));
    const draft = record;
    setModal(draft);
    setFormError("");
    form.resetFields();
    form.setFieldsValue(draft.values);
  }
  async function submit() {
    const draft = currentModal;
    if (!draft || !available) return;
    try {
      const values = await form.validateFields();
      if (
        !panelLive.current.alive ||
        panelLive.current.identity !== draft.identity
      )
        return;
      draft.values = values;
      let path = resource.scope,
        payload: Record<string, unknown>;
      if (draft.kind === "create")
        payload = partyCreate(values, draft.operationId);
      else if (draft.kind === "propose") {
        if (!draft.party) throw new Error("请先读取当前组织。");
        path += `/${draft.party.counterparty.id}/revision-proposals`;
        payload = partyProposal(values, draft.party.counterparty);
      } else {
        if (!draft.revision) throw new Error("请先读取完整修订。");
        path += `/${draft.revision.counterpartyId}/revisions/${draft.revision.revision.id}/${draft.kind}`;
        payload = partyAction(values.reason, draft.revision);
      }
      const response = await resource.write<
        CounterpartyDetail | RevisionDetail
      >(path, payload, (result) => ({
        ...resource.selection,
        id:
          "counterpartyId" in result
            ? result.counterpartyId
            : result.counterparty.id,
        revisionId:
          "revision" in result
            ? result.revision.id
            : (result.counterparty.pendingRevisionId ?? ""),
        cursor: null,
        revisionCursor: null,
      }));
      if (
        response &&
        panelLive.current.alive &&
        draft.identity === panelLive.current.identity
      ) {
        resource.drafts.delete(draft.key);
        setModal(null);
        form.resetFields();
      }
    } catch (error) {
      if (
        error instanceof Error &&
        panelLive.current.alive &&
        panelLive.current.identity === draft.identity
      )
        setFormError(readable(error));
    }
  }
  const fullForm =
    currentModal?.kind === "create" || currentModal?.kind === "propose";
  return (
    <Space orientation="vertical" size="middle" style={{ width: "100%" }}>
      <Alert
        type="info"
        showIcon
        title="组织客户 / 供应商主数据"
        description="本页仅维护组织声明与独立发布资格，客户和供应商角色可并存。列表仅含当前治理归属账套；已知组织ID可在同租户、同企业且双方账套均可读时查看。本页只核验组织档案，不代表政府身份、银行账户或税务资格。业务使用仍须在对应应付等业务中独立核验、审签；本页不执行记账或付款。与年度开启状态无关。"
      />
      <Space wrap>
        <Button
          onClick={() =>
            void resource.read({
              ...resource.selection,
              cursor: null,
              revisionCursor: null,
            })
          }
          disabled={resource.writing}
        >
          重新读取首屏
        </Button>
        <Select
          aria-label="组织状态筛选"
          value={resource.selection.status}
          style={{ width: 160 }}
          onChange={(status) =>
            void resource.read({
              ...resource.selection,
              status,
              id: "",
              revisionId: "",
              cursor: null,
              revisionCursor: null,
            })
          }
          disabled={resource.writing}
          options={[
            "ALL",
            "ACTIVE",
            "FROZEN",
            "INACTIVE",
            "UNPUBLISHED",
            "PENDING",
          ].map((value) => ({
            value,
            label:
              value === "ALL"
                ? "全部"
                : value === "UNPUBLISHED"
                  ? "尚未发布"
                  : partyStatus(value),
          }))}
        />
        <Select
          aria-label="组织当前页条数"
          value={resource.selection.limit}
          style={{ width: 120 }}
          options={[20, 50, 100].map((value) => ({
            value,
            label: `每页${value}条`,
          }))}
          onChange={(limit) =>
            void resource.read({
              ...resource.selection,
              limit,
              cursor: null,
              revisionCursor: null,
            })
          }
          disabled={resource.writing}
        />
        <Button
          onClick={() => open("create")}
          disabled={!available || !canMake}
        >
          新建组织及待审修订
        </Button>
      </Space>
      <Space.Compact style={{ width: "100%" }}>
        <Input
          aria-label="已知组织ID"
          value={lastBoundary.current === resource.boundary ? knownId : ""}
          onChange={(e) => setKnownId(e.target.value)}
          placeholder="按已知组织 UUID 读取（不是全局身份搜索）"
        />
        <Button
          disabled={resource.writing || !knownId}
          onClick={() => {
            if (
              !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(
                knownId,
              )
            ) {
              setFormError("请输入完整组织 UUID。");
              return;
            }
            setFormError("");
            void resource.read({
              ...resource.selection,
              id: knownId.toLowerCase(),
              revisionId: "",
              revisionCursor: null,
            });
          }}
        >
          读取已知组织
        </Button>
      </Space.Compact>
      {resource.error && <Alert type="error" title={resource.error} />}
      {formError &&
        lastBoundary.current === resource.boundary &&
        !currentModal && <Alert type="error" title={formError} />}
      {resource.notice && <Alert type="success" title={resource.notice} />}
      {resource.busy && (
        <Alert
          type="info"
          title="正在读取当前账套组织及身份，旧组织、修订与数量已隐藏，期间不可操作。"
        />
      )}
      {group && (
        <>
          <Typography.Text>
            当前页 {group.list.items.length} 条；总量未计算。目录代次{" "}
            {group.list.generation}。当前账号 {actor?.accountId}；自然人{" "}
            {actor?.personId ?? "未绑定"}
            。读取不要求绑定或TOTP，治理写操作要求当前绑定及近期登录TOTP。
          </Typography.Text>
          <Table
            rowKey="id"
            size="small"
            pagination={false}
            scroll={{ x: 1000 }}
            dataSource={group.list.items}
            columns={[
              {
                title: "当前名称 / 组织ID",
                width: 300,
                render: (_, p) => (
                  <span style={{ overflowWrap: "anywhere" }}>
                    {p.effectiveDisplayName ?? "尚未发布"}
                    <br />
                    {p.id}
                  </span>
                ),
              },
              {
                title: "当前状态",
                width: 140,
                render: (_, p) => partyStatus(p.effectiveStatus),
              },
              {
                title: "登记身份",
                width: 220,
                render: (_, p) =>
                  `${p.identity.jurisdiction} / ${p.identity.identifier}`,
              },
              {
                title: "主数据新用",
                width: 150,
                render: (_, p) =>
                  p.newUseEligible ? "仅主数据资格可用" : "不可新用",
              },
              {
                title: "操作",
                width: 140,
                render: (_, p) => (
                  <Button
                    onClick={() =>
                      void resource.read({
                        ...resource.selection,
                        id: p.id,
                        revisionId: "",
                        revisionCursor: null,
                      })
                    }
                    disabled={resource.writing}
                  >
                    查看组织 / 修订
                  </Button>
                ),
              },
            ]}
          />
          <Space wrap>
            <Button
              disabled={!group.list.nextCursor || resource.writing}
              onClick={() =>
                void resource.read({
                  ...resource.selection,
                  cursor: group.list.nextCursor,
                  id: "",
                  revisionId: "",
                  revisionCursor: null,
                })
              }
            >
              下一页组织
            </Button>
            <Typography.Text>
              有界当前页，不累计全部组织或游标。
            </Typography.Text>
          </Space>
        </>
      )}
      {detail && (
        <>
          <Typography.Title level={4}>组织当前发布状态</Typography.Title>
          <PartyCurrent party={revision?.counterparty ?? detail.counterparty} />
          {!home && (
            <Alert
              type="warning"
              title="此组织归属其他治理账套，当前只读。必须进入治理归属账套后方可提出或处理修订。"
            />
          )}
          {detail.effectiveContent && (
            <PartyContent content={detail.effectiveContent} />
          )}
          {detail.blockers.map((b, i) => (
            <Alert key={`${b.code}:${i}`} type="warning" title={b.message} />
          ))}
          <Space wrap>
            <Button
              disabled={
                !available ||
                !home ||
                !canMake ||
                !detail.allowedActions.includes("PROPOSE_REVISION")
              }
              onClick={() => open("propose")}
            >
              提出新修订 / 冻结 / 再启用 / 停用
            </Button>
            <Button
              disabled={!available}
              onClick={() =>
                void resource.read({
                  ...resource.selection,
                  revisionId:
                    detail.counterparty.pendingRevisionId ??
                    detail.counterparty.latestRevisionId,
                  revisionCursor: null,
                })
              }
            >
              读取待审或最新修订
            </Button>
          </Space>
          {group?.revisions && (
            <>
              <Table
                rowKey="id"
                pagination={false}
                size="small"
                scroll={{ x: 850 }}
                dataSource={group.revisions.items}
                columns={[
                  { title: "修订序号", width: 100, dataIndex: "revision" },
                  {
                    title: "修订状态",
                    width: 160,
                    render: (_, r) => partyStatus(r.status),
                  },
                  {
                    title: "历史拟发布状态",
                    width: 180,
                    render: (_, r) => partyStatus(r.desiredStatus),
                  },
                  {
                    title: "修订ID",
                    width: 300,
                    dataIndex: "id",
                    render: (v) => (
                      <span style={{ overflowWrap: "anywhere" }}>{v}</span>
                    ),
                  },
                  {
                    title: "操作",
                    width: 110,
                    render: (_, r) => (
                      <Button
                        disabled={resource.writing}
                        onClick={() =>
                          void resource.read({
                            ...resource.selection,
                            revisionId: r.id,
                          })
                        }
                      >
                        读取修订
                      </Button>
                    ),
                  },
                ]}
              />
              <Space wrap>
                <Button
                  disabled={resource.writing}
                  onClick={() =>
                    void resource.read({
                      ...resource.selection,
                      revisionCursor: null,
                    })
                  }
                >
                  修订首屏
                </Button>
                <Button
                  disabled={resource.writing || !group.revisions.nextCursor}
                  onClick={() =>
                    void resource.read({
                      ...resource.selection,
                      revisionCursor: group.revisions!.nextCursor,
                    })
                  }
                >
                  下一页修订
                </Button>
                <Typography.Text>修订总量未计算。</Typography.Text>
              </Space>
            </>
          )}
        </>
      )}
      {revision && (
        <>
          <PartyRevision revision={revision} />
          {revision.blockers.map((b, i) => (
            <Alert key={`${b.code}:${i}`} type="warning" title={b.message} />
          ))}
          <Space wrap>
            <Button
              disabled={
                !available ||
                !home ||
                !canDecide ||
                !revision.allowedActions.includes("APPROVE")
              }
              onClick={() => open("approve")}
            >
              独立确认并发布此修订
            </Button>
            <Button
              disabled={
                !available ||
                !home ||
                !canDecide ||
                !revision.allowedActions.includes("REJECT")
              }
              onClick={() => open("reject")}
            >
              独立驳回待审修订
            </Button>
            <Button
              disabled={
                !available ||
                !home ||
                !canMake ||
                actor?.accountId !== revision.makerAccountId ||
                !revision.allowedActions.includes("CANCEL")
              }
              onClick={() => open("cancel")}
            >
              原提出账号取消
            </Button>
            {revision.previousRevisionId && (
              <Button
                disabled={!available}
                onClick={() =>
                  void resource.read({
                    ...resource.selection,
                    revisionId: revision.previousRevisionId!,
                  })
                }
              >
                读取前驱修订
              </Button>
            )}
          </Space>
          <Typography.Text>
            同自然人多账号不能独立审核。服务端最终检查当前身份、岗位、范围、原提出人资格和近期认证；取消或驳回保留稳定组织ID，不解除登记身份占用。
          </Typography.Text>
        </>
      )}
      <Modal
        title={
          currentModal?.kind === "create"
            ? "新建组织（直接形成待审修订）"
            : currentModal?.kind === "propose"
              ? "提出不可变新修订"
              : currentModal?.kind === "approve"
                ? "独立确认并立即发布"
                : currentModal?.kind === "reject"
                  ? "独立驳回"
                  : "原提出账号取消"
        }
        open={!!currentModal && !!group}
        onCancel={() => {
          if (!resource.writing) {
            setModal(null);
            setFormError("");
          }
        }}
        onOk={() => void submit()}
        confirmLoading={resource.writing}
        okButtonProps={{ disabled: !available }}
        width={760}
        destroyOnHidden={false}
      >
        <Alert
          type="info"
          title="提交后不能直接编辑修订；操作失败或结果未知保留正文及请求键。请显式读取权威当前状态后再决定重试。没有提交、付款或记账动作。"
        />
        {formError && <Alert type="error" title={formError} />}
        {resource.error && <Alert type="error" title={resource.error} />}
        {currentModal?.revision && !fullForm && (
          <>
            <Alert
              type="info"
              title="命令冻结基准（非当前状态）"
              description={`基准组织版本 ${currentModal.revision.counterparty.registryVersion}；基准状态 ${partyStatus(currentModal.revision.counterparty.effectiveStatus)}。刷新不会改写本次命令正文、版本或请求键。`}
            />
            {detail?.counterparty.id ===
              currentModal.revision.counterpartyId && (
              <PartyCurrent
                party={revision?.counterparty ?? detail.counterparty}
              />
            )}
            <PartyRevision revision={currentModal.revision} />
          </>
        )}
        {currentModal && (
          <Button
            disabled={!available}
            onClick={() => {
              resource.drafts.delete(currentModal.key);
              setModal(null);
              setFormError("");
              form.resetFields();
            }}
          >
            放弃本地正文，关闭后可按当前版本重新填写（不撤销已发命令）
          </Button>
        )}
        <Form
          form={form}
          layout="vertical"
          onValuesChange={(_, values) => {
            if (currentModal) {
              currentModal.values = values;
              resource.drafts.set(currentModal.key, currentModal);
            }
          }}
        >
          {currentModal?.kind === "create" && (
            <>
              <Form.Item
                name="jurisdiction"
                label="登记辖区（两位英文字母）"
                rules={[{ required: true }]}
              >
                <Input maxLength={2} />
              </Form.Item>
              <Form.Item
                name="identifier"
                label="真实组织登记标识（不可变，不得占位）"
                rules={[{ required: true }]}
              >
                <Input maxLength={64} />
              </Form.Item>
            </>
          )}
          {currentModal?.kind === "propose" && (
            <Form.Item
              name="desiredStatus"
              label="本修订拟发布状态（不是当前状态）"
              rules={[{ required: true }]}
            >
              <Select
                options={(currentModal?.party?.counterparty.effectiveStatus ===
                null
                  ? ["ACTIVE"]
                  : ["ACTIVE", "FROZEN", "INACTIVE"]
                ).map((value) => ({
                  value,
                  label: partyStatus(value),
                }))}
              />
            </Form.Item>
          )}
          {fullForm && (
            <>
              <Form.Item
                name="displayName"
                label="组织名称"
                rules={[{ required: true }]}
              >
                <Input maxLength={300} />
              </Form.Item>
              <Form.Item
                name="roles"
                label="组织角色（可同时选择）"
                rules={[{ required: true }]}
              >
                <Checkbox.Group
                  options={[
                    { label: "客户", value: "CUSTOMER" },
                    { label: "供应商", value: "SUPPLIER" },
                  ]}
                />
              </Form.Item>
              <Form.Item name="settlementTerms" label="结算条款组织说明">
                <Input.TextArea maxLength={1000} />
              </Form.Item>
              <Form.Item name="contractDescription" label="合同说明">
                <Input.TextArea maxLength={1000} />
              </Form.Item>
              <Form.Item
                name="relatedPartyStatus"
                label="关联方声明"
                rules={[{ required: true }]}
              >
                <Select
                  options={[
                    { value: "UNKNOWN", label: "未知" },
                    { value: "RELATED", label: "关联方" },
                    { value: "NOT_RELATED", label: "非关联方" },
                  ]}
                />
              </Form.Item>
              <Form.Item
                name="relatedPartyBasis"
                label="关联方声明依据（未知时不提交依据）"
              >
                <Input.TextArea maxLength={1000} />
              </Form.Item>
              <Form.Item
                name="registrationBasis"
                label="真实组织登记核验引用及说明（组织人工声明）"
                rules={[{ required: true }]}
              >
                <Input.TextArea maxLength={1000} />
              </Form.Item>
            </>
          )}
          <Form.Item
            name="reason"
            label="本次操作理由"
            rules={[{ required: true }]}
          >
            <Input.TextArea maxLength={1000} />
          </Form.Item>
          {currentModal?.kind === "create" && (
            <>
              <Form.Item
                name="organizationDeclarationConfirmed"
                valuePropName="checked"
              >
                <Checkbox>
                  我确认上述内容为有真实依据的组织声明，不将其称为政府认证。
                </Checkbox>
              </Form.Item>
              <Form.Item
                name="identityNotPlaceholderConfirmed"
                valuePropName="checked"
              >
                <Checkbox>
                  我确认登记身份不是占位或临时替代标识，同一组织不通过重复登记规避永久身份约束。
                </Checkbox>
              </Form.Item>
            </>
          )}
        </Form>
      </Modal>
    </Space>
  );
}
