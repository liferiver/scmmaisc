package com.scmmaisc.engine.executor;

import com.scmmaisc.engine.ScenarioExecutor;
import com.scmmaisc.engine.SimContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.scmmaisc.engine.executor.ExecutorSupport.doubleParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.intParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.round2;
import static com.scmmaisc.engine.executor.ExecutorSupport.series;

/**
 * 绿色供应链策略综合选择仿真执行器（T062，CH11-006）。
 * 模型（轻量启发式）：四项绿色化策略（绿色采购/制造/包装/物流，各有成本上浮 premium 与碳减排
 * cut 效果）在年预算约束（green_budget_ratio×年营收）下枚举 16 种组合 → 多期 NPV 评估
 * （绿色溢价收入 + 碳价节省 + 政府补贴 − 策略成本，按 discount_rate 折现；无绿色化组合承担
 * 全额碳税）→ 碳排放减少/含碳总成本/市场份额走势 → Pareto 前沿（减排 vs NPV 非支配组合）。
 */
@Component
public class GreenStrategyExecutor implements ScenarioExecutor {

    private static final String[] NAMES = {"绿色采购", "绿色制造", "绿色包装", "绿色物流"};
    private static final double[] SEG_COST = {0.35, 0.35, 0.10, 0.20};     // 各环节成本占比
    private static final double[] SEG_EMISSION = {0.30, 0.30, 0.10, 0.30}; // 各环节碳排放占比
    private static final double BASELINE_EMISSION = 10000.0;               // 年碳排放基数（吨）
    private static final double COST_RATIO = 0.60;                         // 成本占营收比例

    @Override
    public String engineKey() {
        return "green-strategy";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        doubleParam(params, "annual_revenue", 1000, 100000, errors);
        Double budgetRatio = doubleParam(params, "green_budget_ratio", 1, 10, errors);
        doubleParam(params, "purchase_premium", 5, 20, errors);
        doubleParam(params, "manufacture_premium", 5, 20, errors);
        doubleParam(params, "packaging_premium", 5, 20, errors);
        doubleParam(params, "logistics_premium", 5, 20, errors);
        doubleParam(params, "purchase_cut", 20, 60, errors);
        doubleParam(params, "manufacture_cut", 20, 60, errors);
        doubleParam(params, "packaging_cut", 20, 60, errors);
        doubleParam(params, "logistics_cut", 20, 60, errors);
        doubleParam(params, "green_premium_willingness", 0, 15, errors);
        doubleParam(params, "government_subsidy", 0, 30, errors);
        doubleParam(params, "carbon_price", 50, 200, errors);
        doubleParam(params, "carbon_fine", 50, 200, errors);
        intParam(params, "investment_years", 3, 10, errors);
        Double dr = doubleParam(params, "discount_rate", 0.05, 0.12, errors);
        if (errors.isEmpty() && budgetRatio != null && budgetRatio > 10) {
            errors.add("budget_ok 约束不满足：总投资需 ≤ 预算（绿色化预算为年营收的 1-10%）");
        }
        if (errors.isEmpty() && dr != null && dr < 0.05) {
            errors.add("compliance_ok 约束不满足：折现率过低无法覆盖合规情景下的碳成本风险");
        }
        return errors;
    }

    @Override
    public Integer describeSteps(Map<String, Object> params) {
        return 5;
    }

    @Override
    public void run(Map<String, Object> params, SimContext ctx) {
        double revenue = ((Number) params.get("annual_revenue")).doubleValue() * 10000; // 元
        double budgetRatio = ((Number) params.get("green_budget_ratio")).doubleValue();
        double[] premium = {((Number) params.get("purchase_premium")).doubleValue(),
                ((Number) params.get("manufacture_premium")).doubleValue(),
                ((Number) params.get("packaging_premium")).doubleValue(),
                ((Number) params.get("logistics_premium")).doubleValue()};
        double[] cut = {((Number) params.get("purchase_cut")).doubleValue(),
                ((Number) params.get("manufacture_cut")).doubleValue(),
                ((Number) params.get("packaging_cut")).doubleValue(),
                ((Number) params.get("logistics_cut")).doubleValue()};
        double willingness = ((Number) params.get("green_premium_willingness")).doubleValue() / 100.0;
        double subsidy = ((Number) params.get("government_subsidy")).doubleValue() / 100.0;
        double carbonPrice = ((Number) params.get("carbon_price")).doubleValue();
        int years = ((Number) params.get("investment_years")).intValue();
        double dr = ((Number) params.get("discount_rate")).doubleValue();

        double budget = budgetRatio / 100.0 * revenue;
        double baselineCost = COST_RATIO * revenue;
        double maxCut = 0;
        for (int i = 0; i < 4; i++) {
            maxCut += BASELINE_EMISSION * SEG_EMISSION[i] * cut[i] / 100.0;
        }

        // 步骤 1：绿色化策略选项（成本上浮与碳减排效果）
        ctx.step(String.format("年营收 %,.0f 元，成本基数 %,.0f 元，碳排放基数 %.0f 吨/年；"
                        + "四项策略：采购(+%.0f%%/-%.0f%%)、制造(+%.0f%%/-%.0f%%)、包装(+%.0f%%/-%.0f%%)、"
                        + "物流(+%.0f%%/-%.0f%%)；最大减排 %.0f 吨",
                revenue, baselineCost, BASELINE_EMISSION, premium[0], cut[0], premium[1], cut[1],
                premium[2], cut[2], premium[3], cut[3], maxCut),
                Map.of("budget", round2(budget), "baseline_cost", round2(baselineCost),
                        "max_cut_tons", round2(maxCut)));

        // 步骤 2：预算约束下的组合枚举（16 种）
        double[] comboCost = new double[16];
        double[] comboCut = new double[16];
        for (int m = 0; m < 16; m++) {
            for (int i = 0; i < 4; i++) {
                if ((m & (1 << i)) != 0) {
                    comboCost[m] += SEG_COST[i] * baselineCost * premium[i] / 100.0;
                    comboCut[m] += BASELINE_EMISSION * SEG_EMISSION[i] * cut[i] / 100.0;
                }
            }
        }
        int feasible = 0;
        StringBuilder fb = new StringBuilder();
        for (int m = 1; m < 16; m++) {
            if (comboCost[m] <= budget) {
                feasible++;
                fb.append(comboName(m)).append("、");
            }
        }
        ctx.step(String.format("预算 %.0f 元（营收的 %.0f%%）约束下可行组合 %d 种：%s",
                budget, budgetRatio, feasible, fb.length() > 0 ? fb.substring(0, fb.length() - 1) : "无"),
                Map.of("feasible_combos", feasible, "budget_ratio", round2(budgetRatio)));

        // 步骤 3：多期 NPV 评估（绿色溢价 + 碳价 + 补贴；无绿色化承担碳税）
        double[] comboNpv = new double[16];
        for (int m = 0; m < 16; m++) {
            double discount = 0;
            for (int t = 1; t <= years; t++) {
                discount += 1 / Math.pow(1 + dr, t);
            }
            if (m == 0) {
                comboNpv[0] = -BASELINE_EMISSION * carbonPrice * discount; // 全额碳税情景
            } else if (comboCost[m] <= budget) {
                double benefit = revenue * willingness * (comboCut[m] / maxCut)
                        + comboCut[m] * carbonPrice + comboCost[m] * subsidy;
                comboNpv[m] = (benefit - comboCost[m]) * discount;
            } else {
                comboNpv[m] = Double.NEGATIVE_INFINITY;
            }
        }
        int bestMask = 0;
        for (int m = 1; m < 16; m++) {
            if (comboNpv[m] > comboNpv[bestMask]) {
                bestMask = m;
            }
        }
        ctx.step(String.format("多期 NPV（%d 年，折现率 %.0f%%）：无绿色化 %,.0f 元（碳税情景）；"
                        + "最优组合「%s」NPV %,.0f 元（年收益 = 绿色溢价 %,.0f + 碳价节省 %,.0f + 补贴 %,.0f − 策略成本 %,.0f）",
                years, dr * 100, comboNpv[0], comboName(bestMask), comboNpv[bestMask],
                revenue * willingness * (comboCut[bestMask] / maxCut), comboCut[bestMask] * carbonPrice,
                comboCost[bestMask] * subsidy, comboCost[bestMask]),
                Map.of("best_combo", comboName(bestMask), "best_npv", round2(comboNpv[bestMask])));

        // 步骤 4：Pareto 前沿（减排 vs NPV 非支配组合）
        List<Integer> order = new ArrayList<>();
        for (int m = 0; m < 16; m++) {
            if (comboNpv[m] != Double.NEGATIVE_INFINITY) {
                order.add(m);
            }
        }
        order.sort((a, b) -> Double.compare(comboCut[b], comboCut[a]));
        List<Map<String, Object>> pareto = new ArrayList<>();
        double runningMax = Double.NEGATIVE_INFINITY;
        for (int m : order) {
            if (comboNpv[m] > runningMax) {
                runningMax = comboNpv[m];
                pareto.add(Map.of("name", comboName(m), "value", round2(comboNpv[m])));
            }
        }
        ctx.step(String.format("Pareto 前沿（减排↑ 与 NPV↑ 非支配组合 %d 个）：%s",
                pareto.size(), pareto.get(pareto.size() - 1).get("name")),
                Map.of("pareto_frontier", pareto, "pareto_size", pareto.size()));

        // 步骤 5：总排放、含碳总成本与市场份额走势
        double carbonReduction = comboCut[bestMask];
        double totalCost = baselineCost + comboCost[bestMask] - carbonReduction * carbonPrice;
        List<Double> x = new ArrayList<>();
        List<Double> share = new ArrayList<>();
        for (int t = 1; t <= years; t++) {
            x.add((double) t);
            share.add(round2(20 + willingness * 30 * (carbonReduction / maxCut) * (t / (double) years)));
        }
        List<Map<String, Object>> npvCompare = new ArrayList<>();
        npvCompare.add(Map.of("name", "无绿色化(碳税)", "value", round2(comboNpv[0])));
        for (int i = 0; i < 4; i++) {
            int mask = 1 << i;
            npvCompare.add(Map.of("name", NAMES[i], "value",
                    comboNpv[mask] == Double.NEGATIVE_INFINITY ? 0 : round2(comboNpv[mask])));
        }
        npvCompare.add(Map.of("name", "最优组合", "value", round2(comboNpv[bestMask])));
        ctx.step(String.format("最优组合「%s」减排 %.0f 吨（%.0f%%），含碳总成本 %,.0f 元（−%,.0f 元碳成本）；"
                        + "绿色市场份额由 20%% 升至 %.1f%%——碳价越高、补贴越大，绿色化越占优",
                comboName(bestMask), carbonReduction, carbonReduction / BASELINE_EMISSION * 100,
                totalCost, carbonReduction * carbonPrice, share.get(years - 1)),
                Map.of("carbon_reduction", round2(carbonReduction), "total_cost", round2(totalCost),
                        "market_share_series", series(x, "绿色市场份额(%)", share),
                        "npv_compare", npvCompare));

        // 输出指标（FR-007）
        ctx.output("npv_compare", "各策略NPV", "compare", npvCompare, "元");
        ctx.output("carbon_reduction", "总碳排放减少", "scalar", round2(carbonReduction), "吨");
        ctx.output("total_cost", "含碳成本的总成本", "scalar", round2(totalCost), "元");
        ctx.output("market_share_series", "绿色市场份额变化", "series",
                series(x, "绿色市场份额(%)", share), "%");
        ctx.output("pareto_frontier", "最优策略组合(Pareto前沿)", "compare", pareto, null);
    }

    private String comboName(int mask) {
        if (mask == 0) {
            return "无绿色化";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            if ((mask & (1 << i)) != 0) {
                sb.append(NAMES[i]).append("+");
            }
        }
        return sb.substring(0, sb.length() - 1);
    }
}
