import { Alert, Collapse, Descriptions, Space, Table, Typography } from "antd";
import type {
  Blocker,
  GeneratedLine,
  SourceSnapshot,
  RunDetail,
} from "./profit-closing-contract";
import {
  closingKinds,
  closingStates,
  closingTotals,
} from "./profit-closing-rules";
export function ProfitClosingBlockers({ items }: { items: Blocker[] }) {
  return items.length ? (
    <Alert
      type="warning"
      title="当前检查说明"
      description={
        <ul>
          {items.map((b, i) => (
            <li key={`${b.code}:${i}`}>
              {b.message}（{b.code}）
            </li>
          ))}
        </ul>
      }
    />
  ) : null;
}
export function ProfitClosingLines({ lines }: { lines: GeneratedLine[] }) {
  let total: string;
  try {
    const t = closingTotals(lines);
    total = `借方${t.debit}；贷方${t.credit}；${t.balanced ? "借贷相等" : "不平衡，不得执行"}`;
  } catch {
    total = "金额未知或格式异常，不得执行";
  }
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <Typography.Text>
        服务端只读生成分录：{total}。不允许手改科目或金额。
      </Typography.Text>
      <Table
        size="small"
        rowKey="lineNo"
        dataSource={lines}
        pagination={false}
        scroll={{ x: 1050, y: 480 }}
        columns={[
          { title: "行号", dataIndex: "lineNo", width: 70 },
          {
            title: "真实科目编码及名称",
            width: 300,
            render: (_, l) => `${l.account.code} ${l.account.name}`,
          },
          { title: "借方", dataIndex: "debit", width: 180 },
          { title: "贷方", dataIndex: "credit", width: 180 },
          { title: "摘要", dataIndex: "memo", width: 320 },
        ]}
      />
    </Space>
  );
}
export function ProfitClosingSource({ source }: { source: SourceSnapshot }) {
  return (
    <Collapse
      items={[
        {
          key: "source",
          label: `真实来源与截止证明 · ${source.basis.cutoff} · 包含既有已记账结转`,
          children: (
            <Space orientation="vertical" style={{ width: "100%" }}>
              <Typography.Paragraph style={{ overflowWrap: "anywhere" }}>
                来源摘要：{source.sourceDigest}；已含截止日内记账序号：
                {source.basis.includedPostingSeq}；观察到的全年度序号：
                {source.observedYearPostingSeq}
                （二者不等不自动判失效）。正式期初/基线/来源年结版本：
                {source.basis.openingVersion}/{source.basis.baselineVersion}/
                {source.basis.sourceCloseVersion}。最近已记账运行：
                {source.latestPostedRunId ?? "无"}；最近有效转存：
                {source.latestEffectiveTransferRunId ?? "无"}。
              </Typography.Paragraph>
              <Table
                size="small"
                rowKey={(r) => r.account.id}
                dataSource={source.sourceRows}
                pagination={{ pageSize: 20, showSizeChanger: false }}
                scroll={{ x: 1100 }}
                columns={[
                  {
                    title: "真实科目",
                    width: 300,
                    render: (_, r) => `${r.account.code} ${r.account.name}`,
                  },
                  {
                    title: "正式期初（借正贷负）",
                    dataIndex: "opening",
                    width: 190,
                  },
                  { title: "截止日内借方发生", dataIndex: "debit", width: 190 },
                  {
                    title: "截止日内贷方发生",
                    dataIndex: "credit",
                    width: 190,
                  },
                  {
                    title: "实际余额（借正贷负）",
                    dataIndex: "balance",
                    width: 190,
                  },
                ]}
              />
              <pre style={{ whiteSpace: "pre-wrap", overflowWrap: "anywhere" }}>
                {JSON.stringify(source.basis, null, 2)}
              </pre>
            </Space>
          ),
        },
      ]}
    />
  );
}
export function ProfitClosingProof({ run }: { run: RunDetail }) {
  const who = (a: { accountId: string; personId: string }) =>
    `${a.accountId} / ${a.personId}`;
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <Descriptions
        bordered
        size="small"
        column={1}
        styles={{
          content: { overflowWrap: "anywhere" },
          label: { width: 220 },
        }}
        items={[
          {
            key: "id",
            label: "运行 / 真实凭证",
            children: `${run.id} / ${run.journalId}`,
          },
          {
            key: "stage",
            label: "阶段 / 截止 / 状态",
            children: `${closingKinds[run.producerKind]} / ${run.cutoff} / ${closingStates[run.status]}`,
          },
          {
            key: "effect",
            label: "实际经济效果",
            children:
              run.economicEffect === "FULLY_REVERSED"
                ? "已被额外真实记账全额反向，原凭证仍不可变"
                : run.economicEffect === "ACTIVE"
                  ? "真实记账效果保留"
                  : "尚未记账",
          },
          {
            key: "v",
            label: "运行 / 凭证 / 内容版本",
            children: `${run.version} / ${run.journalVersion} / ${run.contentVersion}`,
          },
          {
            key: "digest",
            label: "内容 / 来源摘要",
            children: (
              <>
                {run.contentDigest}
                <br />
                {run.sourceDigest}
              </>
            ),
          },
          {
            key: "maker",
            label: "原制备 / 最后编辑账号及人员",
            children: (
              <>
                {who(run.preparedBy)}
                <br />
                {who(run.lastEditor)}
              </>
            ),
          },
          {
            key: "links",
            label: "前序记账 / 原已终结尝试",
            children: `${run.predecessorPostedRunId ?? "无"} / ${run.supersedesCancelledAttemptId ?? "无"}`,
          },
          {
            key: "reverse",
            label: "反向原运行 / 已记账反向运行",
            children: `${run.reversesRunId ?? "无"} / ${run.postedReversalRunId ?? "无"}`,
          },
          {
            key: "approval",
            label: "真实批准（历史与当前资格分开）",
            children: run.currentApproval ? (
              <>
                {run.currentApproval.id}
                <br />
                {who(run.currentApproval.approver)}
                <br />
                决定：{run.currentApproval.decidedAt}；到期：
                {run.currentApproval.expiresAt}
                <br />
                新记账资格：
                {
                  {
                    FRESH: "当前可用",
                    EXPIRED: "已过期",
                    SOURCE_STALE: "来源已变化",
                    ACTOR_STALE: "批准人员资格已变化",
                    SUPERSEDED: "已被后续决定取代",
                    CONSUMED: "已用于真实记账",
                  }[run.currentApproval.eligibilityForNewPost]
                }
              </>
            ) : (
              "无批准证明"
            ),
          },
          {
            key: "post",
            label: "真实记账证明",
            children: run.postProof ? (
              <>
                {run.postProof.id}
                <br />
                {who(run.postProof.poster)}
                <br />
                {run.postProof.postedAt}；记账序号{run.postProof.postingSeq}
                <br />
                {run.postProof.postingDigest}
              </>
            ) : (
              "尚未记账，不等于执行成功"
            ),
          },
          {
            key: "termination",
            label: "行政终结决定",
            children: run.administrativeTermination
              ? `${who(run.administrativeTermination.actor)} / ${run.administrativeTermination.at} / ${run.administrativeTermination.reason}`
              : "无",
          },
        ]}
      />
      <Typography.Paragraph>
        {run.content.summary}；{run.content.reason}
        。本次仅结转分录，不改变期间/年度状态，不生成正式期初、报表、分红或归档。
      </Typography.Paragraph>
      <ProfitClosingBlockers items={run.blockers} />
      {!run.sourceFresh && run.status !== "POSTED" && (
        <Alert
          type="warning"
          title="当前来源已变化，旧内容不能新记账；仍可按实际权限退出，不能手改旧生成行。"
        />
      )}
      <ProfitClosingLines lines={run.content.generatedLines} />
      <ProfitClosingSource source={run.source} />
      <Collapse
        items={[
          {
            key: "proof",
            label: "不可变内容及实际决定完整快照",
            children: (
              <pre style={{ whiteSpace: "pre-wrap", overflowWrap: "anywhere" }}>
                {JSON.stringify(
                  {
                    content: run.content,
                    approval: run.currentApproval,
                    post: run.postProof,
                    administrativeTermination: run.administrativeTermination,
                  },
                  null,
                  2,
                )}
              </pre>
            ),
          },
        ]}
      />
    </Space>
  );
}
