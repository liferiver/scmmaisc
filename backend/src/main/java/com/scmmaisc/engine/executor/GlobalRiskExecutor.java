package com.scmmaisc.engine.executor;

import com.scmmaisc.engine.ScenarioExecutor;
import com.scmmaisc.engine.SimContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.scmmaisc.engine.executor.ExecutorSupport.doubleParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.matrixParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.round2;

/**
 * 全球供应链风险管理策略仿真执行器（T061，CH10-003）。
 * 模型：全球供应链三类风险源（外部/行业/内部，各含概率×影响矩阵，影响等级 1-5）→ 期望损失
 * EL=Σp×影响×损失系数×年收入；六种策略（韧性/缓冲库存/柔性产能/对冲/应急计划，各有成本与降低
 * 效果）→ 组合后风险调整总成本、各策略损失降低对比、VaR(95%)（EL+1.65×UL）、策略组合效率前沿
 * 与恢复时间分布；讨论新冠揭示的全球供应链脆弱性。
 */
@Component
public class GlobalRiskExecutor implements ScenarioExecutor {

    private static final double REVENUE = 1_000_000_000.0;  // 全球年收入（元）
    private static final double BASE_COST = 800_000_000.0;  // 基础运营成本（元）
    private static final double LOSS_RATE = 0.02;           // 影响等级 1 对应的损失占比（×年收入）
    private static final double INVENTORY_BASE = 160_000_000.0; // 库存基数（元）

    @Override
    public String engineKey() {
        return "global-risk";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        if (params.get("external_risk_matrix") != null) {
            matrixParam(params, "external_risk_matrix", 4, 4, 2, 0.0, 5.0, errors);
        }
        if (params.get("industry_risk_matrix") != null) {
            matrixParam(params, "industry_risk_matrix", 3, 3, 2, 0.0, 5.0, errors);
        }
        if (params.get("internal_risk_matrix") != null) {
            matrixParam(params, "internal_risk_matrix", 3, 3, 2, 0.0, 5.0, errors);
        }
        doubleParam(params, "resilience_cost", 5, 15, errors);
        doubleParam(params, "safety_stock_level", 10, 30, errors);
        doubleParam(params, "flexible_capacity", 5, 20, errors);
        doubleParam(params, "hedging_ratio", 1, 3, errors);
        Double adaptation = doubleParam(params, "adaptation_budget", 10, 500, errors);
        Double budget = doubleParam(params, "total_budget", 100, 5000, errors);
        if (errors.isEmpty() && adaptation != null && budget != null && adaptation > budget) {
            errors.add("budget_ok 约束不满足：额外投入需 ≤ 总预算");
        }
        return errors;
    }

    @Override
    public Integer describeSteps(Map<String, Object> params) {
        return 5;
    }

    /** 三类风险事件（外部/行业/内部）：概率 × 影响等级(1-5)。 */
    private static final String[][] EVENT_SETS = {
            {"自然灾害", "疫情封锁", "政治事件", "汇率波动"},
            {"供应商破产", "港口罢工", "关键物料短缺"},
            {"质量事故", "系统故障", "人才流失"}};
    private static final double[][] DEFAULT_PROBS = {
            {0.15, 0.10, 0.12, 0.40},
            {0.08, 0.10, 0.15},
            {0.10, 0.15, 0.20}};
    private static final double[][] DEFAULT_IMPACTS = {
            {2, 4, 3, 2},
            {4, 3, 3},
            {2, 2, 1}};

    @Override
    public void run(Map<String, Object> params, SimContext ctx) {
        double resilience = ((Number) params.get("resilience_cost")).doubleValue();     // %
        double safety = ((Number) params.get("safety_stock_level")).doubleValue();      // %
        double flexCap = ((Number) params.get("flexible_capacity")).doubleValue();      // %
        double hedging = ((Number) params.get("hedging_ratio")).doubleValue();          // %
        double adaptation = ((Number) params.get("adaptation_budget")).doubleValue();   // 万元
        double totalBudget = ((Number) params.get("total_budget")).doubleValue();       // 万元

        // 步骤 1：风险事件库载入（概率 × 影响矩阵，缺省值兜底）
        double[][][] events = new double[3][][];
        events[0] = loadMatrix(params, "external_risk_matrix", DEFAULT_PROBS[0], DEFAULT_IMPACTS[0]);
        events[1] = loadMatrix(params, "industry_risk_matrix", DEFAULT_PROBS[1], DEFAULT_IMPACTS[1]);
        events[2] = loadMatrix(params, "internal_risk_matrix", DEFAULT_PROBS[2], DEFAULT_IMPACTS[2]);
        double elBase = 0;
        for (int g = 0; g < 3; g++) {
            for (double[] e : events[g]) {
                elBase += e[0] * e[1] * LOSS_RATE * REVENUE;
            }
        }
        ctx.step(String.format("风险事件库：外部 %d 项 / 行业 %d 项 / 内部 %d 项；无策略基线期望损失 %,.0f 元"
                        + "（%.2f%% 年收入）",
                events[0].length, events[1].length, events[2].length, elBase, elBase / REVENUE * 100),
                Map.of("baseline_loss", round2(elBase)));

        // 步骤 2：六种策略配置与成本（各策略独立的损失降低系数）
        double resilienceReduction = resilience / 100 * 1.5;   // 多源采购 → 中断概率
        double stockReduction = safety / 100 * 1.2;            // 缓冲库存 → 缺货影响
        double flexReduction = flexCap / 100 * 1.5;            // 柔性产能 → 恢复速度
        double hedgeReduction = hedging / 100 * 2.0;           // 金融对冲 → 汇率/价格风险
        double adaptationReduction = adaptation / totalBudget; // 应急计划 → 响应速度（预算占比）
        double strategyCost = resilience / 100 * BASE_COST + safety / 100 * INVENTORY_BASE
                + flexCap / 100 * (BASE_COST * 0.4) + hedging / 100 * REVENUE + adaptation * 10000;
        ctx.step(String.format("策略投入合计 %,.0f 元（预算 %,.0f 元，占用 %.0f%%）：韧性 %.1f%% / "
                        + "缓冲 %.1f%% / 柔性 %.1f%% / 对冲 %.1f%% / 应急 %,.0f 元",
                strategyCost, totalBudget * 10000, strategyCost / (totalBudget * 10000) * 100,
                resilience, safety, flexCap, hedging, adaptation * 10000),
                Map.of("strategy_cost", round2(strategyCost)));

        // 步骤 3：组合后风险调整总成本与各策略损失降低
        double[] singleReduction = new double[5];
        for (int g = 0; g < 3; g++) {
            for (int i = 0; i < events[g].length; i++) {
                double[] e = events[g][i];
                double baseLoss = e[0] * e[1] * LOSS_RATE * REVENUE;
                singleReduction[0] += baseLoss * resilienceReduction;   // 韧性覆盖全部
                singleReduction[1] += baseLoss * stockReduction;
                singleReduction[2] += baseLoss * flexReduction;
                if (g == 0 && i == 3) {
                    singleReduction[3] += baseLoss * hedgeReduction;    // 对冲仅汇率
                }
                singleReduction[4] += baseLoss * adaptationReduction * 0.5;
            }
        }
        double combinedFactor = Math.max(0, 1 - resilienceReduction)
                * Math.max(0, 1 - stockReduction) * Math.max(0, 1 - flexReduction)
                * Math.max(0, 1 - hedgeReduction) * Math.max(0, 1 - adaptationReduction * 0.5);
        double residualLoss = elBase * combinedFactor;
        double riskAdjustedCost = BASE_COST + strategyCost + residualLoss;
        List<Map<String, Object>> lossReduction = List.of(
                Map.of("name", "韧性-多源采购", "value", round2(singleReduction[0])),
                Map.of("name", "缓冲-安全库存", "value", round2(singleReduction[1])),
                Map.of("name", "柔性-产能储备", "value", round2(singleReduction[2])),
                Map.of("name", "对冲-金融工具", "value", round2(singleReduction[3])),
                Map.of("name", "适应-应急计划", "value", round2(singleReduction[4])));
        ctx.step(String.format("组合后残余损失 %,.0f 元（降低 %.1f%%）；风险调整总成本 %,.0f 元",
                residualLoss, (1 - combinedFactor) * 100, riskAdjustedCost),
                Map.of("loss_reduction_compare", lossReduction,
                        "risk_adjusted_cost", round2(riskAdjustedCost)));

        // 步骤 4：VaR(95%) 与策略组合效率前沿
        double ul2 = 0;
        for (int g = 0; g < 3; g++) {
            for (double[] e : events[g]) {
                double loss = e[1] * LOSS_RATE * REVENUE * combinedFactor;
                ul2 += e[0] * (1 - e[0]) * loss * loss;
            }
        }
        double var95 = residualLoss + 1.65 * Math.sqrt(ul2);
        double totalReduction = elBase - residualLoss;
        List<Map<String, Object>> frontier = List.of(
                Map.of("name", "单策略-韧性", "value", round2(singleReduction[0] / (resilience / 100 * BASE_COST))),
                Map.of("name", "双策略-韧性+缓冲", "value", round2((singleReduction[0] + singleReduction[1])
                        / (resilience / 100 * BASE_COST + safety / 100 * INVENTORY_BASE))),
                Map.of("name", "三策略-+柔性产能", "value", round2((singleReduction[0] + singleReduction[1] + singleReduction[2])
                        / (resilience / 100 * BASE_COST + safety / 100 * INVENTORY_BASE + flexCap / 100 * BASE_COST * 0.4))),
                Map.of("name", "四策略-+对冲", "value", round2((totalReduction - singleReduction[4])
                        / (strategyCost - adaptation * 10000))),
                Map.of("name", "全策略组合", "value", round2(totalReduction / strategyCost)));
        ctx.step(String.format("VaR(95%%) = %,.0f 元（95%% 分位损失）；效率前沿（每元投入降低损失）："
                        + "全策略组合 %.2f 元",
                var95, totalReduction / strategyCost),
                Map.of("var_95", round2(var95), "efficiency_frontier", frontier));

        // 步骤 5：恢复时间分布与结论（新冠疫情启示）
        List<Map<String, Object>> recoveryDist = List.of(
                Map.of("name", "<7天", "value", 15),
                Map.of("name", "7-14天", "value", 40),
                Map.of("name", "14-30天", "value", 30),
                Map.of("name", ">30天", "value", 15));
        ctx.step("恢复时间分布（天）：新冠启示——单源采购/零库存的全球供应链在疫情封锁面前极为脆弱，"
                        + "韧性（多源）+ 缓冲 + 数字化可见性才是有效策略",
                Map.of("recovery_time_dist", recoveryDist));

        // 输出指标（FR-007）
        ctx.output("risk_adjusted_cost", "风险调整后总成本", "scalar", round2(riskAdjustedCost), "元");
        ctx.output("loss_reduction_compare", "各策略期望损失降低", "compare", lossReduction, "元");
        ctx.output("var_95", "最差情景(95%VaR)", "scalar", round2(var95), "元");
        ctx.output("efficiency_frontier", "策略组合效率前沿", "compare", frontier, null);
        ctx.output("recovery_time_dist", "恢复时间分布", "dist", recoveryDist, "天");
    }

    /** 载入风险矩阵（每行 [概率, 影响]），缺省按内置概率/影响。 */
    private static double[][] loadMatrix(Map<String, Object> params, String key,
                                         double[] probs, double[] impacts) {
        double[][] matrix = params.get(key) != null
                ? matrixParam(params, key, probs.length, probs.length, 2, 0.0, 5.0, new ArrayList<>())
                : null;
        if (matrix == null) {
            matrix = new double[probs.length][2];
            for (int i = 0; i < probs.length; i++) {
                matrix[i][0] = probs[i];
                matrix[i][1] = impacts[i];
            }
        }
        return matrix;
    }
}
