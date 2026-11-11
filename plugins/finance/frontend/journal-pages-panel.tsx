import { useState } from "react";
import {
  Alert,
  Button,
  Collapse,
  Input,
  Select,
  Space,
  Table,
  Tag,
} from "antd";
import type { Account } from "./types";
import { useFinancePages } from "./use-finance-pages";
import {
  emptyPageFilters,
  ledgerLineKey,
  unknownPageTotal,
  type PageFilters,
  type FinancePageResource,
  type PagedJournalSummary,
  type PagedLedgerLine,
} from "./journal-pages-contract";
type Props = {
  bookId: string;
  yearId: string;
  year: number;
  resource: FinancePageResource;
  authHint: string;
  active: boolean;
  canRead: boolean;
  accounts: Account[];
  onReadDenied: () => void;
  onResetDetails: () => void;
  onJournal: (id: string) => void;
  readable: (error: unknown) => string;
};
const statuses: Record<string, string> = {
  DRAFT: "草稿",
  SUBMITTED: "待审核",
  APPROVED: "已审核",
  POSTED: "已记账",
  CANCELLED: "已作废",
};
export function JournalPagesPanel(props: Props) {
  const { resource, year, accounts, onJournal } = props;
  const [filters, setFilters] = useState<PageFilters>(emptyPageFilters);
  const [limit, setLimit] = useState(20);
  const pager = useFinancePages(props);
  function change(field: keyof PageFilters, value: string) {
    pager.reset();
    setFilters((previous) => ({ ...previous, [field]: value }));
  }
  const journalPage =
    pager.page?.resource === "journals" ? pager.page : undefined;
  const ledgerPage = pager.page?.resource === "ledger" ? pager.page : undefined;
  const page = pager.page;
  if (!props.canRead) return null;
  return (
    <Space
      orientation="vertical"
      style={{ width: "100%", overflowWrap: "anywhere" }}
    >
      <Alert
        type="info"
        title={
          resource === "journals"
            ? "凭证摘要：服务器有界分页"
            : "单科目明细账：逐条已记账分录分页"
        }
        description={`当前年度${year}。留空日期按本年度取数；工作台上方月份选择不暗改本列表。仅当前页，不以页面金额或已看页推算全年总额；试算、结账仍独立取数。历史精度按原字符串保留。`}
      />
      <Space wrap>
        {resource === "ledger" ? (
          <Select
            aria-label="分页明细账科目（含历史停用）"
            placeholder="选择当前年度科目（含历史停用）"
            style={{ minWidth: 280, maxWidth: "100%" }}
            showSearch
            optionFilterProp="label"
            value={filters.accountId || undefined}
            options={accounts.map((account) => ({
              value: account.id,
              label: `${account.code} ${account.name}${account.enabled ? "" : "（历史停用，仍可查）"}`,
            }))}
            onChange={(value) => change("accountId", value)}
          />
        ) : (
          <Select
            aria-label="分页凭证状态"
            value={filters.status}
            options={[
              { value: "", label: "全部凭证状态" },
              ...Object.entries(statuses).map(([value, label]) => ({
                value,
                label,
              })),
            ]}
            onChange={(value) => change("status", value)}
          />
        )}
        <label>
          同年度起日{" "}
          <Input
            aria-label={`${resource}日期起日`}
            type="date"
            value={filters.dateFrom}
            min={`${year}-01-01`}
            max={`${year}-12-31`}
            onChange={(event) => change("dateFrom", event.target.value)}
          />
        </label>
        <label>
          同年度止日{" "}
          <Input
            aria-label={`${resource}日期止日`}
            type="date"
            value={filters.dateTo}
            min={`${year}-01-01`}
            max={`${year}-12-31`}
            onChange={(event) => change("dateTo", event.target.value)}
          />
        </label>
        {resource === "journals" && (
          <Input
            aria-label="凭证摘要文字关键词"
            placeholder="摘要包含文字（不区分大小写）"
            maxLength={100}
            style={{ width: 300 }}
            value={filters.keyword}
            onChange={(event) => change("keyword", event.target.value)}
          />
        )}
        <Select
          aria-label={`${resource}每页条数`}
          value={limit}
          options={[20, 50, 100].map((value) => ({
            value,
            label: `每页${value}条`,
          }))}
          onChange={(value) => {
            pager.reset();
            setLimit(value);
          }}
        />
        <Button
          type="primary"
          loading={pager.loading}
          disabled={
            pager.loading || (resource === "ledger" && !filters.accountId)
          }
          onClick={() => pager.first(filters, limit)}
        >
          应用筛选并读取首屏
        </Button>
      </Space>
      {resource === "journals" && (
        <Alert title="关键词按原文字包含匹配，不区分大小写，% / _ / 反斜杠均按文字；首尾空格不删除。非空（包括空格）必须显式填写本年度日期起止。可能触发3秒语句 / 10秒读服务预算，失败时缩小日期重取，不自动去掉关键词。" />
      )}
      {pager.error && <Alert type="error" title={pager.error} />}
      {!page && !pager.loading && (
        <Alert title="尚无当前页，请应用筛选并显式读取首屏。未加载或失败不表示零条记录。" />
      )}
      {pager.loading && (
        <Alert title="正在读取当前页；旧页与旧详情已移除，不与本次新页拼接。" />
      )}
      {page && (
        <>
          <Alert
            title={`本次返回${page.returnedCount}条；${unknownPageTotal(page.totalCount)}`}
            description={`真实取数日期 ${page.scope.dateFrom} 至 ${page.scope.dateTo}。单次一致观察，事实或授权改变后旧游标会失效；本页不是全年枚举或完整导出。`}
          />
          {ledgerPage && (
            <Alert
              title={`${ledgerPage.scope.accountCode} ${ledgerPage.scope.accountName}${ledgerPage.scope.accountEnabled ? "" : "（历史停用科目）"}：每个line独立列示，不合并同凭证同科目的多行。`}
            />
          )}
          {journalPage && (
            <Table<PagedJournalSummary>
              rowKey="id"
              pagination={false}
              tableLayout="fixed"
              scroll={{ x: 1000 }}
              dataSource={journalPage.items}
              columns={[
                { title: "日期", dataIndex: "date", width: 130 },
                {
                  title: "凭证号",
                  dataIndex: "number",
                  width: 130,
                  render: (value) => value ?? "未记账",
                },
                { title: "真实摘要", dataIndex: "summary", width: 400 },
                {
                  title: "状态",
                  dataIndex: "status",
                  width: 140,
                  render: (value) => <Tag>{statuses[value] ?? value}</Tag>,
                },
                {
                  title: "真实详情 / 处理",
                  width: 200,
                  render: (_, item) => (
                    <Button onClick={() => onJournal(item.id)}>
                      读取最新详情 / 处理
                    </Button>
                  ),
                },
              ]}
            />
          )}
          {ledgerPage && (
            <Table<PagedLedgerLine>
              rowKey={ledgerLineKey}
              pagination={false}
              tableLayout="fixed"
              scroll={{ x: 1350 }}
              dataSource={ledgerPage.items}
              columns={[
                { title: "日期", dataIndex: "date", width: 130 },
                {
                  title: "凭证号 / 分录行",
                  width: 150,
                  render: (_, item) =>
                    `${item.number ?? "未知凭证号"} / ${item.lineNo}`,
                },
                { title: "真实摘要", dataIndex: "summary", width: 300 },
                { title: "借方（原精度）", dataIndex: "debit", width: 210 },
                { title: "贷方（原精度）", dataIndex: "credit", width: 210 },
                {
                  title: "历史精度 / 真实记账序列",
                  width: 230,
                  render: (_, item) =>
                    `${item.amountFormatVersion} / ${item.postingSeq ?? "历史序列缺失，未伪造0"}`,
                },
                {
                  title: "凭证详情",
                  width: 180,
                  render: (_, item) => (
                    <Button onClick={() => onJournal(item.journalId)}>
                      读取最新凭证详情
                    </Button>
                  ),
                },
              ]}
            />
          )}
          <Space wrap>
            <Button
              disabled={!page.hasPrevious || pager.loading}
              onClick={() => pager.move("previous")}
            >
              服务器上一页
            </Button>
            <Button
              disabled={!page.hasNext || pager.loading}
              onClick={() => pager.move("next")}
            >
              服务器下一页
            </Button>
            <Button
              disabled={pager.loading}
              onClick={() => pager.first(filters, limit)}
            >
              丢弃游标并重取首屏
            </Button>
          </Space>
          {page.items.length === 0 && (
            <Alert title="本次筛选页没有记录；总数仍未计算，不等同于全年度没有凭证或发生额。" />
          )}
          <Collapse
            items={[
              {
                key: "baseline",
                label:
                  "查看取数上下文、技术水位与限制（技术代次不等于业务数量）",
                children: (
                  <pre
                    style={{ whiteSpace: "pre-wrap", overflowWrap: "anywhere" }}
                  >
                    {JSON.stringify(
                      {
                        scope: page.scope,
                        asOf: page.asOf,
                        contextDigest: page.contextDigest,
                        limitations: page.limitations,
                      },
                      null,
                      2,
                    )}
                  </pre>
                ),
              },
            ]}
          />
        </>
      )}
    </Space>
  );
}
