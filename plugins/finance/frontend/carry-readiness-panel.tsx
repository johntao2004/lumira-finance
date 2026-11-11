import { useEffect, useState } from "react";
import { Alert, Button, Modal, Select, Space, Table, Typography } from "antd";
import type { Actor } from "./carry-readiness-contract";
import type { Year } from "./types";
import {
  carryAllowedTargets,
  carryCandidateLabels,
  carryDifferenceLabels,
  carryMoney,
  type CarryFilter,
  type CarrySelection,
} from "./carry-readiness-rules";
import { useCarryReadiness } from "./carry-readiness-resource";
import {
  CarryAccount,
  CarryRowDetail,
  CarrySummaryView,
} from "./carry-readiness-views";
type Props = {
  bookId: string;
  tenantId: string;
  legalEntityId: string;
  bookName: string;
  sourceYearId: string;
  years: Year[];
  authHint: string;
  actorHint: Actor | null;
  refreshToken: number;
  active: boolean;
  canRead: boolean;
  contextReady: boolean;
  onReadDenied: () => void;
  onActorChanged: () => void;
  readable: (e: unknown) => string;
};
export function CarryReadinessPanel(props: Props) {
  const [targetYearId, setTargetYearId] = useState(""),
    [filter, setFilter] = useState<CarryFilter>("ALL"),
    [limit, setLimit] = useState(20),
    [selected, setSelected] = useState<{
      key: string;
      token: string;
      rowKey: string;
    } | null>(null);
  const source = props.years.find((y) => y.id === props.sourceYearId),
    choices = carryAllowedTargets(props.years, props.sourceYearId),
    target = choices.find((y) => y.id === targetYearId);
  const selection: CarrySelection = {
    tenantId: props.tenantId,
    legalEntityId: props.legalEntityId,
    bookId: props.bookId,
    sourceYearId: props.sourceYearId,
    sourceYear: source?.year ?? 0,
    targetYearId: target?.id ?? "",
    targetYear: target?.year ?? 0,
    authHint: props.authHint,
    actorHint: props.actorHint,
    filter,
    limit,
    refreshToken: props.refreshToken,
    yearHints: JSON.stringify([
      source?.version,
      source?.status,
      source?.precisionProfile,
      source?.precisionReviewRequired,
      source?.currencyMinorUnit,
      target?.version,
      target?.status,
      target?.precisionProfile,
      target?.precisionReviewRequired,
      target?.currencyMinorUnit,
    ]),
  };
  const active = Boolean(
    props.active &&
    props.contextReady &&
    props.canRead &&
    props.bookId &&
    props.tenantId &&
    props.legalEntityId &&
    source &&
    target,
  );
  const resource = useCarryReadiness(
    selection,
    active,
    props.onReadDenied,
    props.onActorChanged,
    props.readable,
  );
  useEffect(() => {
    if (targetYearId && !target) setTargetYearId("");
  }, [targetYearId, target?.id]);
  useEffect(() => {
    setSelected(null);
  }, [resource.key]);
  const group = resource.group;
  const selectedRow =
    group &&
    selected?.key === resource.key &&
    selected.token === group.summary.observation.token
      ? group.page.items.find((r) => r.rowKey === selected.rowKey)
      : undefined;
  if (!props.canRead) return null;
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <Alert
        type="info"
        title="年度承接资格与科目余额对照（只读）"
        description="仅观察同一账套连续双年度事实及缺口。未创建持久快照或批准包；候选映射始终未批准，不能执行BASE、关闭年度、承接正式期初或生成正式报告。旧年结门禁不因本观察改变。"
      />
      <Typography.Paragraph>
        账套：{props.bookName || "未选择"}；源年度：{source?.year ?? "未选择"}
        （由工作台上方年度选择）；目标年度：{target?.year ?? "未选择"}
        。月份不改变本次完整自然年观察。
      </Typography.Paragraph>
      <Space wrap>
        <Select
          aria-label="只读承接目标年度"
          placeholder="选择已授权的相邻目标年度"
          style={{ minWidth: 260 }}
          value={target?.id}
          options={choices.map((y) => ({ value: y.id, label: String(y.year) }))}
          onChange={(id) => {
            setSelected(null);
            setTargetYearId(id);
          }}
        />
        <Select
          aria-label="科目对照显示范围"
          value={filter}
          options={[
            { value: "ALL", label: "全部科目联合总体" },
            { value: "NONZERO", label: "非零、差异或未知（不丢未知行）" },
          ]}
          style={{ minWidth: 280 }}
          onChange={(value) => {
            setSelected(null);
            setFilter(value);
          }}
        />
        <Select
          aria-label="承接科目对照每页条数"
          value={limit}
          options={[20, 50, 100].map((value) => ({
            value,
            label: `每页${value}条`,
          }))}
          onChange={(value) => {
            setSelected(null);
            setLimit(value);
          }}
        />
        <Button
          disabled={!active}
          loading={resource.loading}
          onClick={() => {
            setSelected(null);
            void resource.readFirst();
          }}
        >
          重新读取双年度首屏
        </Button>
      </Space>
      {!choices.length && (
        <Alert
          type="warning"
          title="未取得可读取的相邻目标年度范围；本页不会推断其是否存在或自动建年、授予权限。"
        />
      )}
      {!props.contextReady && (
        <Alert
          type="warning"
          title="当前年度读取上下文正在核对或未读取成功；旧双年度金额与计数已隐藏。未绑定不等于没有只读权限，请使用顶部刷新重试读取上下文。"
        />
      )}
      {resource.error && <Alert type="error" title={resource.error} />}{" "}
      {!group && (
        <Alert
          type="warning"
          title={
            resource.loading
              ? "正在取得并核对同一观察的双年度总览与当前页；旧金额、计数和详情已隐藏。"
              : "尚未取得同一观察的双年度完整结果，金额与数量未知，不代表0或没有差异。"
          }
        />
      )}{" "}
      {group && (
        <>
          <CarrySummaryView summary={group.summary} />
          <Typography.Title level={5}>
            科目候选对照差异（仅当前页）
          </Typography.Title>
          <Typography.Paragraph>
            全联合总体{group.page.populationRows}行；当前筛选总体
            {group.page.filteredRows}行；本页{group.page.items.length}
            行。上方全总体检查与数量不随此筛选改变。候选差额仅为源期末－目标期初，不加目标本年发生，也不是已批准承接金额。
          </Typography.Paragraph>
          <Table
            size="small"
            rowKey="rowKey"
            dataSource={group.page.items}
            pagination={false}
            scroll={{ x: 2350, y: 650 }}
            locale={{ emptyText: "本次真实观察的当前筛选页没有科目行" }}
            columns={[
              {
                title: "源年度真实科目",
                width: 300,
                render: (_, r) => (
                  <CarryAccount
                    account={r.sourceAccount}
                    presence={r.sourcePresence}
                  />
                ),
              },
              {
                title: "目标真实候选（未批准）",
                width: 300,
                render: (_, r) => (
                  <CarryAccount
                    account={r.targetCandidate}
                    presence={r.targetPresence}
                  />
                ),
              },
              {
                title: "候选状态 / 完整语义差异",
                width: 240,
                render: (_, r) => (
                  <>
                    {carryCandidateLabels[r.candidateStatus]}
                    <br />
                    {r.semanticDifferences
                      .map((d) => carryDifferenceLabels[d])
                      .join("；") || "无所列语义差异；仍未批准"}
                  </>
                ),
              },
              {
                title: "源年末余额（借正贷负）",
                width: 210,
                render: (_, r) => carryMoney(r.sourceEndingBalance),
              },
              {
                title: "目标实际期初（非正式批次）",
                width: 220,
                render: (_, r) => carryMoney(r.targetOpening),
              },
              {
                title: "目标全年借方发生",
                width: 200,
                render: (_, r) => carryMoney(r.targetPostedDebit),
              },
              {
                title: "目标全年贷方发生",
                width: 200,
                render: (_, r) => carryMoney(r.targetPostedCredit),
              },
              {
                title: "目标期末余额（借正贷负）",
                width: 220,
                render: (_, r) => carryMoney(r.targetEndingBalance),
              },
              {
                title: "未批准候选差额（源期末－目标期初）",
                width: 260,
                render: (_, r) => carryMoney(r.candidateOpeningDifference),
              },
              {
                title: "只读完整说明",
                width: 180,
                render: (_, r) => (
                  <Button
                    onClick={() =>
                      setSelected({
                        key: resource.key,
                        token: group.summary.observation.token,
                        rowKey: r.rowKey,
                      })
                    }
                  >
                    查看完整对照与原因
                  </Button>
                ),
              },
            ]}
          />
        </>
      )}
      <Space wrap>
        <Button
          disabled={!active || resource.loading}
          onClick={() => {
            setSelected(null);
            void resource.readFirst();
          }}
        >
          返回双年度首屏
        </Button>
        <Button
          disabled={!group?.page.nextCursor || resource.loading}
          onClick={() => {
            setSelected(null);
            void resource.next();
          }}
        >
          读取服务端下一页
        </Button>
      </Space>
      <Modal
        title="当前页只读科目对照（不是已批准映射）"
        open={!!selectedRow}
        onCancel={() => setSelected(null)}
        footer={null}
        width={1000}
        destroyOnHidden
      >
        {selectedRow && <CarryRowDetail row={selectedRow} />}
      </Modal>
    </Space>
  );
}
