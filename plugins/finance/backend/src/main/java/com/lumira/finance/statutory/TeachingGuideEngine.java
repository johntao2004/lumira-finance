package com.lumira.finance.statutory;

import static com.lumira.finance.statutory.StatutoryModels.*;
import java.math.BigDecimal;
import java.util.*;

public final class TeachingGuideEngine {
    private TeachingGuideEngine() {}

    private static VoucherLine vl(int lineNo, String code, String name, String direction, String amount, String memo) {
        return new VoucherLine(lineNo, code, name, direction, new BigDecimal(amount), memo);
    }

    public static List<CanonicalVoucher> getCanonicalVouchers(Standard standard) {
        boolean cas = standard == Standard.CAS_2019;
        List<CanonicalVoucher> vouchers = new ArrayList<>();

        // 1. Capital contribution
        vouchers.add(new CanonicalVoucher(
                "VCH_CAPITAL",
                "科技企业股东资本注入",
                standard,
                "天使轮或增资扩股资金到位，存入公司基本存款账户，按约定认缴注册资本和资本公积溢价入账。",
                List.of(
                        vl(1, "1002", "银行存款", "DEBIT", "1000000.00", "收到天使轮股东现金出资款"),
                        vl(2, "4001", "实收资本", "CREDIT", "800000.00", "确认注册资本认缴额"),
                        vl(3, "4002", cas ? "资本公积—资本溢价" : "资本公积", "CREDIT", "200000.00", "超出注册资本溢价部分计入公积")
                ),
                new BigDecimal("1000000.00"),
                new BigDecimal("1000000.00")
        ));

        // 2. R&D payroll accrual and payout with withholding
        vouchers.add(new CanonicalVoucher(
                "VCH_RD_PAYROLL",
                "研发人员薪酬计提、代扣税费与发放",
                standard,
                "月末计提技术开发团队当月应发工资，次月发放并代扣代缴个人所得税及个人应负担社保公积金。",
                List.of(
                        vl(1, cas ? "5301" : "5602", cas ? "研发支出—费用化支出" : "管理费用—研究费用", "DEBIT", "100000.00", "计提核心研发工程师团队本月工资"),
                        vl(2, "2211", "应付职工薪酬—工资", "CREDIT", "100000.00", "确认应发职工薪酬负债"),
                        vl(3, "2211", "应付职工薪酬—工资", "DEBIT", "100000.00", "银行代发研发团队实发工资结清负债"),
                        vl(4, "2221", "应交税费—代扣个人所得税", "CREDIT", "5000.00", "代扣研发人员综合所得个人所得税"),
                        vl(5, "2241", "其他应付款—代扣个人社保公积金", "CREDIT", "10000.00", "代扣个人承担部分三险一金"),
                        vl(6, "1002", "银行存款", "CREDIT", "85000.00", "银行银企直联实付到员工个人银行卡")
                ),
                new BigDecimal("200000.00"),
                new BigDecimal("200000.00")
        ));

        // 3. SaaS revenue annual advance and monthly recognition
        vouchers.add(new CanonicalVoucher(
                "VCH_SAAS_REVENUE",
                "SaaS软件年度订阅预收与首月收入分期确认",
                standard,
                "签约企业客户预付一年期SaaS云平台订阅使用费含6%现代信息技术服务增值税，开具增值税专用发票，并确认首月服务履约收入。",
                List.of(
                        vl(1, "1002", "银行存款", "DEBIT", "106000.00", "收到企业客户SaaS年度订阅预收款全额含6%增值税"),
                        vl(2, cas ? "2203" : "2205", cas ? "合同负债" : "预收账款", "CREDIT", "100000.00", "预收不含税服务款计入负债待分期履约"),
                        vl(3, "2221", "应交税费—应交增值税(销项税额)", "CREDIT", "6000.00", "确认SaaS现代服务业6%增值税销项税额"),
                        vl(4, cas ? "2203" : "2205", cas ? "合同负债" : "预收账款", "DEBIT", "8333.33", "首月履约完毕按1/12转销预收负债"),
                        vl(5, cas ? "6001" : "5001", "主营业务收入", "CREDIT", "8333.33", "确认首月SaaS云订阅主营业务收入")
                ),
                new BigDecimal("114333.33"),
                new BigDecimal("114333.33")
        ));

        // 4. Server purchase and monthly depreciation
        vouchers.add(new CanonicalVoucher(
                "VCH_SERVER_DEPRECIATION",
                "高算力服务器设备购置与按月直线法计提折旧",
                standard,
                "采购AI训练GPU服务器设备一套并投入研发使用，取得13%增值税专用发票并于当月完成抵扣，次月起按36个月直线法计提折旧。",
                List.of(
                        vl(1, "1601", "固定资产—服务器设备", "DEBIT", "120000.00", "服务器验收入库达到预定可使用状态入账"),
                        vl(2, "2221", "应交税费—应交增值税(进项税额)", "DEBIT", "15600.00", "认证抵扣13%增值税专用发票进项税额"),
                        vl(3, "1002", "银行存款", "CREDIT", "135600.00", "对公电汇支付供应商设备全额采购款"),
                        vl(4, cas ? "6602" : "5602", cas ? "管理费用/研发费用—折旧费" : "管理费用—折旧费", "DEBIT", "3333.33", "次月按3年直线法月度计提折旧费"),
                        vl(5, "1602", "累计折旧", "CREDIT", "3333.33", "贷记累计折旧作为固定资产抵减科目")
                ),
                new BigDecimal("138933.33"),
                new BigDecimal("138933.33")
        ));

        // 5. Expense claim
        vouchers.add(new CanonicalVoucher(
                "VCH_EXPENSE_CLAIM",
                "研发架构师出差与技术报销凭证",
                standard,
                "技术骨干参加行业峰会出差报销，附机票航空运输电子行程单与增值税专票，财务会计核准报销额并转账支付。",
                List.of(
                        vl(1, cas ? "6605" : "5602", cas ? "研发费用—差旅费" : "管理费用—差旅费", "DEBIT", "10000.00", "研发架构师出差差旅及会务费入账"),
                        vl(2, "2221", "应交税费—应交增值税(进项税额)", "DEBIT", "600.00", "客运交通与会务专用发票计算可抵扣进项税"),
                        vl(3, "1002", "银行存款", "CREDIT", "10600.00", "网银转账支付报销人银行卡结清费用")
                ),
                new BigDecimal("10600.00"),
                new BigDecimal("10600.00")
        ));

        // 6. VAT & CIT accrual
        vouchers.add(new CanonicalVoucher(
                "VCH_TAX_ACCRUAL",
                "增值税月末转出未交、附加税计提与季度所得税费用核算",
                standard,
                "月末汇总计算当期销项与进项税额，结转应交增值税至未交增值税；按当月应缴增值税计提城建税及附加；季末依据会计利润纳税调整后计提企业所得税。",
                List.of(
                        vl(1, cas ? "6403" : "5403", "税金及附加", "DEBIT", "1200.00", "计提城建税(7%)、教育费附加(3%)及地方教育附加(2%)"),
                        vl(2, "2221", "应交税费—城建税及附加", "CREDIT", "1200.00", "应缴地方税费及附加科目贷记确认负债"),
                        vl(3, cas ? "6801" : "5801", "所得税费用", "DEBIT", "25000.00", "季度汇算当期企业所得税会计所得税费用"),
                        vl(4, "2221", "应交税费—应交企业所得税", "CREDIT", "25000.00", "预缴季度企业所得税应交税费负债确认")
                ),
                new BigDecimal("26200.00"),
                new BigDecimal("26200.00")
        ));

        return vouchers;
    }

    public static List<TeachingGuide> getAllTeachingGuides(Standard standard) {
        List<TeachingGuide> guides = new ArrayList<>();
        var vouchers = getCanonicalVouchers(standard);

        // Guide 1: Double-entry rules
        guides.add(new TeachingGuide(
                "TG_DOUBLE_ENTRY",
                standard,
                "DOUBLE_ENTRY_RULES",
                "企业复式借贷记账法原理与会计恒等式",
                Map.of(
                        "coreFormula", "资产 = 负债 + 所有者权益",
                        "dynamicFormula", "资产 + 费用 = 负债 + 所有者权益 + 收入",
                        "directionRules", Map.of(
                                "ASSET", "借方表示增加，贷方表示减少，期末正常余额在借方",
                                "LIABILITY", "贷方表示增加，借方表示减少，期末正常余额在贷方",
                                "EQUITY", "贷方表示增加，借方表示减少，期末正常余额在贷方",
                                "COST", "借方归集发生成本，贷方结转至存货或产成品，在产品余额在借方",
                                "REVENUE", "贷方归集营业收入与利得，借方核算冲减与期末转结本年利润",
                                "EXPENSE", "借方归集期间费用与损失，贷方核算冲减与期末转结本年利润"
                        ),
                        "fundamentalRule", "有借必有贷，借贷必相等。单张凭证各分录借方总额必须精确等于贷方总额，只允许在最底层明细科目记账。"
                ),
                vouchers
        ));

        // Guide 2: Contra accounts
        guides.add(new TeachingGuide(
                "TG_CONTRA_ACCOUNTS",
                standard,
                "CONTRA_ACCOUNTS",
                "抵减科目（对冲科目）核算原理与资产净值列报机制",
                Map.of(
                        "concept", "抵减科目（Contra Account）的记账方向与其所调整的主体账户正好相反。其正常余额在贷方，用来抵减相关资产账户的原值，从而计算列报资产的账面价值（净额）。",
                        "classification", Map.of(
                                "ACCUMULATED_DEPRECIATION", "累计折旧：作为固定资产的抵减科目。固定资产账面价值 = 固定资产原价 - 累计折旧 - 固定资产减值准备。",
                                "ACCUMULATED_AMORTIZATION", "累计摊销：作为无形资产的抵减科目。无形资产账面价值 = 无形资产原价 - 累计摊销 - 无形资产减值准备。",
                                "BAD_DEBT_ALLOWANCE", "坏账准备：作为应收账款及其他应收款的抵减科目。应收账款列报净额 = 应收账款重分类后借方余额 - 坏账准备（注：小企业会计准则SBE不计提坏账准备）。",
                                "INVENTORY_IMPAIRMENT", "存货跌价准备：作为存货的抵减科目。存货净值 = 成本总额 - 存货跌价准备（小企业会计准则SBE不启用）。"
                        ),
                        "commonPitfalls", "严禁将累计折旧或坏账准备列作企业负债！在资产负债表编制时必须按照净额列报，不得将抵减科目简单当作未结借贷。"
                ),
                vouchers.stream().filter(v -> v.code().equals("VCH_SERVER_DEPRECIATION")).toList()
        ));

        // Guide 3: 6 Canonical tech vouchers
        guides.add(new TeachingGuide(
                "TG_CANONICAL_VOUCHERS",
                standard,
                "CANONICAL_VOUCHERS",
                "科技企业六类核心业务全流程记账实务指南",
                Map.of(
                        "description", "覆盖科技公司创立、研发、订阅收费、硬件投入、日常运营与税务申报的全生命周期标准凭证范例。",
                        "vouchersCount", vouchers.size()
                ),
                vouchers
        ));

        return guides;
    }
}
