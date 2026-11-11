import { Collapse, Descriptions, Space, Table, Tag, Typography } from "antd";
import type {
  Account,
  AccountRow,
  Check,
  SignedAmount,
  UnsignedAmount,
  Summary,
  WorkflowCounts,
  YearObservation,
  YearVector,
} from "./carry-readiness-contract";
import {
  carryMoney,
  carryCheckMoney,
  carryCheckLabels,
  carryCandidateLabels,
  carryDifferenceLabels,
} from "./carry-readiness-rules";
const categoryLabels = {
  ASSET: "资产",
  LIABILITY: "负债",
  EQUITY: "权益",
  REVENUE: "收入",
  EXPENSE: "费用",
};
const purposeLabels = {
  NONE: "无特殊用途",
  CURRENT_YEAR_PROFIT: "本年利润",
  RETAINED_EARNINGS: "未分配利润",
};
export function CarryMoneyFact({
  fact,
}: {
  fact: SignedAmount | UnsignedAmount;
}) {
  return (
    <Space orientation="vertical" size={0}>
      <Typography.Text style={{ fontVariantNumeric: "tabular-nums" }}>
        {carryMoney(fact)}
      </Typography.Text>
      {fact.reason && (
        <Typography.Text type="secondary">{fact.reason}</Typography.Text>
      )}
    </Space>
  );
}
export function CarryAccount({
  account,
  presence,
}: {
  account: Account | null;
  presence: "PRESENT" | "MISSING_ACCOUNT";
}) {
  if (!account || presence !== "PRESENT")
    return <Typography.Text>无对应科目，相关金额不按0处理</Typography.Text>;
  return (
    <Space orientation="vertical" size={0}>
      <Typography.Text>
        {account.code} {account.name}
      </Typography.Text>
      <Typography.Text type="secondary">
        {categoryLabels[account.category]} /{" "}
        {account.normalSide === "DEBIT" ? "借方正常" : "贷方正常"} /{" "}
        {purposeLabels[account.purpose]} / {account.enabled ? "启用" : "停用"}
      </Typography.Text>
      <Typography.Text type="secondary" style={{ overflowWrap: "anywhere" }}>
        {account.id}
      </Typography.Text>
    </Space>
  );
}
function Counts({ value }: { value: WorkflowCounts }) {
  return (
    <Typography.Text>
      草稿{value.draft}；待审{value.submitted}；已批准{value.approved}；已记账
      {value.posted}；已终结{value.cancelled}
    </Typography.Text>
  );
}
const yearStatuses = {
  OPEN: "开放",
  CLOSING: "关闭处理中",
  CLOSED_PENDING_ARCHIVE: "结转已提交，待归档",
  CLOSED: "已关闭",
};
export function CarryYearObservation({
  value,
  vector,
  title,
}: {
  value: YearObservation;
  vector: YearVector;
  title: string;
}) {
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <Typography.Title level={5}>
        {title} · {value.yearNumber}
      </Typography.Title>
      <Descriptions
        bordered
        size="small"
        column={1}
        styles={{
          label: { width: 240 },
          content: { overflowWrap: "anywhere" },
        }}
        items={[
          {
            key: "range",
            label: "真实全年范围 / 年度状态",
            children: `${value.rangeStart} 至 ${value.rangeEnd} / ${yearStatuses[vector.yearStatus]}`,
          },
          {
            key: "qualification",
            label: "数据口径 / 人员模式",
            children: `${{ SUPPORTED_V4: "V4账务可观察", UNSUPPORTED_PRECISION: "当前精度未支持", UNSUPPORTED_BASELINE: "累计基线未支持", UNKNOWN_INVALID_DATA: "数据尚不可确认" }[value.dataQualification]} / ${vector.personMode === "JOURNAL_PERSON_SOD" ? "人员审签" : "账号模式"}`,
          },
          {
            key: "opening",
            label: "实际产品期初余额行（非正式批次）",
            children: (
              <>
                <Typography.Paragraph>
                  实际行数：{value.openingRowCount}
                  。正式期初承接批次资格尚未实现，不以已存在余额行冒充已正式承接。
                </Typography.Paragraph>
                借方：
                <CarryMoneyFact fact={value.openingDebit} />
                <br />
                贷方：
                <CarryMoneyFact fact={value.openingCredit} />
              </>
            ),
          },
          {
            key: "movements",
            label: "全年已记账总发生（含结转）",
            children: (
              <>
                借方：
                <CarryMoneyFact fact={value.postedDebit} />
                <br />
                贷方：
                <CarryMoneyFact fact={value.postedCredit} />
                <Typography.Paragraph type="secondary">
                  按完整自然年取数；包括真实结转及反向的总发生额，不是收入、净利润或现金总体。
                </Typography.Paragraph>
              </>
            ),
          },
          {
            key: "ending",
            label: "实际期末借 / 贷余额合计",
            children: (
              <>
                借余：
                <CarryMoneyFact fact={value.endingDebitBalances} />
                <br />
                贷余：
                <CarryMoneyFact fact={value.endingCreditBalances} />
              </>
            ),
          },
          {
            key: "trial",
            label: "实际试算 / 非零损益科目",
            children: `${value.trialBalanced === null ? "未知" : value.trialBalanced ? "借贷平衡" : "借贷不平"} / ${value.nonzeroProfitLossAccountCount === null ? "未知" : value.nonzeroProfitLossAccountCount}`,
          },
          {
            key: "profit",
            label: "本年利润科目数量 / 真实残额",
            children: (
              <>
                {value.currentYearProfitAccountCount} /{" "}
                <CarryMoneyFact fact={value.currentYearProfitResidual} />
              </>
            ),
          },
          {
            key: "baseline",
            label: "迁移累计基线",
            children: <CarryMoneyFact fact={value.importedYtdBaselineAmount} />,
          },
          {
            key: "journals",
            label: "凭证流程实际数量（含系统结转）",
            children: <Counts value={value.journalCounts} />,
          },
          {
            key: "closing",
            label: "其中受控结转运行数量",
            children: (
              <>
                <Counts value={value.closingRunCounts} />
                <Typography.Paragraph type="secondary">
                  与上项有重叠，不相加为总凭证数。
                </Typography.Paragraph>
              </>
            ),
          },
          {
            key: "periods",
            label: "真实期间状态",
            children: value.periods.length ? (
              <Space wrap>
                {value.periods.map((p) => (
                  <Tag key={p.id}>
                    {p.month}月 {p.status === "OPEN" ? "开放" : "关闭"}
                  </Tag>
                ))}
              </Space>
            ) : (
              "未取得实际期间，不自动补齐"
            ),
          },
        ]}
      />
    </Space>
  );
}
const checkStatus = {
  PASS: "本项观察通过（不是执行资格）",
  FAIL: "本项未满足",
  MISSING: "能力或依据缺失",
  UNSUPPORTED: "当前不支持",
};
export function CarryChecks({ checks }: { checks: Check[] }) {
  return (
    <Table
      size="small"
      rowKey="id"
      pagination={false}
      dataSource={checks}
      scroll={{ x: 1150 }}
      columns={[
        {
          title: "检查项",
          width: 230,
          render: (_, c) => carryCheckLabels[c.id],
        },
        {
          title: "观察状态",
          width: 240,
          render: (_, c) => checkStatus[c.status],
        },
        { title: "完整说明 / 缺项依据", width: 400, dataIndex: "message" },
        {
          title: "本项金额 / 观察数量",
          width: 260,
          render: (_, c) => (
            <>
              <div>{carryCheckMoney(c)}</div>
              <div>数量：{c.observedCount ?? "未知 / 未测量"}</div>
            </>
          ),
        },
      ]}
    />
  );
}
export function CarrySummaryView({ summary }: { summary: Summary }) {
  const totals = summary.mappingTotals;
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <Typography.Title level={5}>
        全总体观察（不随差异页筛选改变）
      </Typography.Title>
      <Typography.Paragraph>
        联合总体{totals.populationRows}行；源科目{totals.sourceAccounts}
        ；目标科目{totals.targetAccounts}；相容候选{totals.compatibleCandidates}
        ；无目标{totals.missingTarget}；仅目标{totals.targetOnly}；语义冲突
        {totals.semanticConflicts}；停用候选{totals.disabledCandidates}
        ；含未知金额行{totals.unknownMonetaryRows}
        。这些是候选对照数量，不是已批准映射。
      </Typography.Paragraph>
      <Typography.Paragraph>
        源{summary.scope.sourceYear}年末与目标{summary.scope.targetYear}
        期初、目标全年发生分别观察；上方月份不改变本次全年口径。目标已发生业务不会被本查询覆盖或重复记账。
      </Typography.Paragraph>
      <CarryYearObservation
        value={summary.source}
        vector={summary.observation.source}
        title="源年度年末账务观察"
      />
      <CarryYearObservation
        value={summary.target}
        vector={summary.observation.target}
        title="目标实际期初及全年发生观察"
      />
      <Typography.Title level={5}>固定24项检查与未实现边界</Typography.Title>
      <CarryChecks checks={summary.checks} />
      <Collapse
        items={[
          {
            key: "technical",
            label: "当前观察水位及旧计划事实（不是冻结或批准包）",
            children: (
              <Space orientation="vertical" style={{ width: "100%" }}>
                <Typography.Paragraph>
                  旧标量计划总数{summary.legacyPlans.total}：预览
                  {summary.legacyPlans.preview}，批准
                  {summary.legacyPlans.approved}，执行
                  {summary.legacyPlans.executed}，其他
                  {summary.legacyPlans.other}
                  。这些旧事实不代表本片存在已批准映射或正式BASE批次。
                </Typography.Paragraph>
                <Typography.Paragraph>
                  普通当前账号：{summary.currentActor.accountId}；人员：
                  {summary.currentActor.personId ?? "未绑定"}
                  。已授权只读不要求目录治理或业务人员绑定；此投影不授予任何新写权限。
                </Typography.Paragraph>
                <pre
                  style={{ whiteSpace: "pre-wrap", overflowWrap: "anywhere" }}
                >
                  {JSON.stringify(
                    { scope: summary.scope, observation: summary.observation },
                    null,
                    2,
                  )}
                </pre>
              </Space>
            ),
          },
        ]}
      />
    </Space>
  );
}
export function CarryRowDetail({ row }: { row: AccountRow }) {
  const facts: [string, SignedAmount | UnsignedAmount][] = [
    ["源实际期初", row.sourceOpening],
    ["源全年借方发生（含结转）", row.sourcePostedDebit],
    ["源全年贷方发生（含结转）", row.sourcePostedCredit],
    ["源年末借正贷负余额", row.sourceEndingBalance],
    ["目标实际期初余额行", row.targetOpening],
    ["目标全年借方发生（含结转）", row.targetPostedDebit],
    ["目标全年贷方发生（含结转）", row.targetPostedCredit],
    ["目标全年发生净额", row.targetPostedNet],
    ["目标期末借正贷负余额", row.targetEndingBalance],
    [
      "未批准候选差额（源期末－目标期初，不含目标本年发生）",
      row.candidateOpeningDifference,
    ],
  ];
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <CarryAccount account={row.sourceAccount} presence={row.sourcePresence} />
      <CarryAccount
        account={row.targetCandidate}
        presence={row.targetPresence}
      />
      <Typography.Paragraph>
        {carryCandidateLabels[row.candidateStatus]}；始终未批准。
        {row.semanticDifferences
          .map((d) => carryDifferenceLabels[d])
          .join("；") || "当前未列语义差异，不代表已批准或已承接。"}
      </Typography.Paragraph>
      <Descriptions
        bordered
        column={1}
        size="small"
        styles={{
          label: { width: 260 },
          content: { overflowWrap: "anywhere" },
        }}
        items={[
          {
            key: "opening",
            label: "目标是否实际存在产品期初行",
            children:
              row.targetOpeningRowExists === null
                ? "未知"
                : row.targetOpeningRowExists
                  ? "存在实际余额行（不等于正式批次）"
                  : "没有余额行（已存在科目可有已知原始0，仍不是正式批次）",
          },
          ...facts.map(([label, fact]) => ({
            key: label,
            label,
            children: <CarryMoneyFact fact={fact} />,
          })),
        ]}
      />
      <Typography.Paragraph>
        对照差额不是已批准承接金额；未覆盖的子账、税项、现金总体、报表及档案缺项不会因余额为0而自动通过。
      </Typography.Paragraph>
      <Collapse
        items={[
          {
            key: "row",
            label: "当前页完整对照行（只读）",
            children: (
              <pre style={{ whiteSpace: "pre-wrap", overflowWrap: "anywhere" }}>
                {JSON.stringify(row, null, 2)}
              </pre>
            ),
          },
        ]}
      />
    </Space>
  );
}
