package com.scmmaisc.engine.executor;

import com.scmmaisc.engine.ScenarioExecutor;
import com.scmmaisc.engine.SimContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.scmmaisc.engine.executor.ExecutorSupport.doubleParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.enumParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.intParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.round2;

/**
 * 供应链金融综合运营仿真执行器（T060，CH9-006，综合难度）。
 * 模型：完整生态（银行 + 核心企业 + 多中小企业 + 监管方）→ 多周期运营循环：融资申请 → 审批 →
 * 放款 → 风险监控 → 回收；经济周期驱动违约率（扩张/平稳/衰退），银行在净利息收入与不良率之间
 * 权衡（超容忍率则收紧信贷）；期末输出净利息收入曲线、不良率曲线、ROE、资本充足率与中小企业
 * 融资满足率。
 */
@Component
public class FinanceComprehensiveExecutor implements ScenarioExecutor {

    private static final double[] MODE_RATES = {0.05, 0.06, 0.07};  // 应收/预付/存货 年化利率
    private static final double AVG_LOAN = 150.0;                   // 单户平均融资金额（万元）
    private static final double RWA_WEIGHT = 0.75;                  // 风险资产权重（巴塞尔简化）
    private static final double BOOK_CAP_MULTIPLE = 3.0;            // 贷款规模上限 = 3×资本金

    @Override
    public String engineKey() {
        return "finance-comprehensive";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        intParam(params, "sme_count", 10, 50, errors);
        intParam(params, "core_count", 1, 3, errors);
        Double rec = doubleParam(params, "receivable_share", 0, 1, errors);
        Double inv = doubleParam(params, "inventory_share", 0, 1, errors);
        Double prep = doubleParam(params, "prepayment_share", 0, 1, errors);
        Double capital = doubleParam(params, "bank_capital", 1, 10, errors);
        Double nplTol = doubleParam(params, "npl_tolerance", 0.02, 0.05, errors);
        enumParam(params, "cycle_phase", Set.of("expansion", "stable", "recession"), errors);
        intParam(params, "sim_quarters", 4, 20, errors);
        if (errors.isEmpty() && rec != null && inv != null && prep != null) {
            if (rec + inv + prep > 1) {
                errors.add("shares_ok 约束不满足：应收/存货/预付业务占比之和需等于 1");
            }
        }
        if (errors.isEmpty() && nplTol != null && nplTol >= 0.05) {
            errors.add("npl_ok 约束不满足：不良贷款率需 < 5%");
        }
        if (errors.isEmpty() && capital != null && capital < 1) {
            errors.add("capital_ok 约束不满足：资本充足率需 > 8%（受资本金约束）");
        }
        return errors;
    }

    @Override
    public Integer describeSteps(Map<String, Object> params) {
        return 5;
    }

    @Override
    public void run(Map<String, Object> params, SimContext ctx) {
        int smeCount = ((Number) params.get("sme_count")).intValue();
        int coreCount = ((Number) params.get("core_count")).intValue();
        double[] shares = {
                ((Number) params.get("receivable_share")).doubleValue(),
                ((Number) params.get("prepayment_share")).doubleValue(),
                ((Number) params.get("inventory_share")).doubleValue()};
        double capital = ((Number) params.get("bank_capital")).doubleValue() * 10000; // 万元
        double nplTolerance = ((Number) params.get("npl_tolerance")).doubleValue();
        String cycle = String.valueOf(params.get("cycle_phase"));
        int quarters = ((Number) params.get("sim_quarters")).intValue();

        // 步骤 1：生态构建
        double basePd = switch (cycle) {             // 季度违约概率（经济周期驱动）
            case "expansion" -> 0.004;
            case "recession" -> 0.02;
            default -> 0.008;
        };
        double avgRate = MODE_RATES[0] * shares[0] + MODE_RATES[1] * shares[1] + MODE_RATES[2] * shares[2];
        ctx.step(String.format("金融生态：%d 家中小企业 + %d 家核心企业 + 监管方；资本金 %,.0f 万元，"
                        + "不良容忍率 %.1f%%；经济周期：%s（违约基准 %.2f%%/季）；加权利率 %.2f%%",
                smeCount, coreCount, capital, nplTolerance * 100, cycle, basePd * 100, avgRate * 100),
                Map.of("sme_count", smeCount, "core_count", coreCount, "avg_rate", round2(avgRate * 100)));

        // 步骤 2：多周期放款循环（审批 → 放款 → 监控 → 回收）
        double book = capital * 1.5;                 // 期初存量贷款（万元）
        double capBook = capital * BOOK_CAP_MULTIPLE;
        double approvalFactor = 1.0;                 // 收紧信号（不良超容忍率后降 50%）
        List<Double> niiCurve = new ArrayList<>();   // 净利息收入（元/季）
        List<Double> nplCurve = new ArrayList<>();   // 不良率（%/季）
        for (int q = 1; q <= quarters; q++) {
            double demand = smeCount * AVG_LOAN;     // 季度融资需求（万元）
            double newLoans = Math.min(demand * approvalFactor, Math.max(0, capBook - book));
            double repay = book * (0.3 + 0.1 * ctx.random().nextDouble());   // 正常回收
            double interest = book * avgRate / 4;
            double defaulted = book * basePd * (1 + 0.2 * ctx.random().nextGaussian());
            double npl = Math.min(1, defaulted / Math.max(1, book));
            nplCurve.add(round2(npl * 100));
            niiCurve.add(round2((interest - defaulted) * 10000));
            book = Math.max(0, book + newLoans - repay - defaulted);
            if (npl > nplTolerance) {
                approvalFactor = 0.5;                // 不良超限 → 收紧信贷
            }
        }
        ctx.step(String.format("%d 个季度运营完成：期末贷款余额 %,.0f 万元，累计放款 %,.0f 万元%s",
                        quarters, book, quarters * smeCount * AVG_LOAN,
                        approvalFactor < 1 ? "（后期因不良超容忍率收紧信贷）" : "（信贷政策持续宽松）"),
                Map.of("final_loan_book", round2(book), "tightened", approvalFactor < 1));

        // 步骤 3：收益-风险平衡分析（净利息收入 vs 不良率）
        double totalNii = 0, peakNpl = 0;
        for (int q = 0; q < quarters; q++) {
            totalNii += niiCurve.get(q);
            peakNpl = Math.max(peakNpl, nplCurve.get(q));
        }
        ctx.step(String.format("累计净利息收入 %,.0f 万元，峰值不良率 %.1f%%（容忍率 %.1f%%）%s",
                        totalNii / 10000, peakNpl, nplTolerance * 100,
                        peakNpl <= nplTolerance * 100 ? "，风险可控" : "，突破容忍率"),
                Map.of("total_net_interest_income", round2(totalNii), "peak_npl", round2(peakNpl)));

        // 步骤 4：资本充足率与 ROE
        double rwa = book * RWA_WEIGHT;
        double adequacy = rwa > 0 ? capital / rwa * 100 : 100;
        double roe = capital > 0 ? totalNii / capital * 100 : 0;
        ctx.step(String.format("资本充足率 %.1f%%（资本 %,.0f 万 / 风险加权资产 %,.0f 万，监管线 8%%）；"
                        + "ROE = %.2f%%（累计净利息 %,.0f 万 / 资本）",
                adequacy, capital, rwa, roe, totalNii),
                Map.of("capital_adequacy", round2(adequacy), "roe", round2(roe)));

        // 步骤 5：中小企业融资满足率与汇总
        double satisfied = Math.max(0, Math.min(100, 100 - peakNpl * 40 - (approvalFactor < 1 ? 25 : 0)));
        Map<String, Object> niiSeries = new LinkedHashMap<>();
        niiSeries.put("x", axis(quarters));
        niiSeries.put("series", List.of(Map.of("name", "净利息收入", "data", niiCurve)));
        Map<String, Object> nplSeries = new LinkedHashMap<>();
        nplSeries.put("x", axis(quarters));
        nplSeries.put("series", List.of(Map.of("name", "不良贷款率", "data", nplCurve)));
        ctx.step(String.format("中小企业融资满足率 %.0f%%（不良 %d 季超容忍、收紧 %s）；"
                        + "生态内银行在收益与风险间取得%s平衡",
                satisfied,
                (int) nplCurve.stream().filter(v -> v > nplTolerance * 100).count(),
                approvalFactor < 1 ? "触发" : "未触发",
                peakNpl <= nplTolerance * 100 ? "" : "动态"),
                Map.of("sme_satisfaction", round2(satisfied)));

        // 输出指标（FR-007）
        ctx.output("net_interest_income", "净利息收入", "series", niiSeries, "元");
        ctx.output("npl_ratio", "不良贷款率", "series", nplSeries, "%");
        ctx.output("roe", "ROE", "scalar", round2(roe), "%");
        ctx.output("capital_adequacy", "资本充足率", "gauge", List.of(
                Map.of("name", "资本充足率", "value", round2(adequacy))), "%");
        ctx.output("sme_satisfaction", "中小企业融资满足率", "scalar", round2(satisfied), "%");
    }

    /** 1..quarters 季度刻度。 */
    private static List<Double> axis(int n) {
        List<Double> x = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            x.add((double) i);
        }
        return x;
    }
}
