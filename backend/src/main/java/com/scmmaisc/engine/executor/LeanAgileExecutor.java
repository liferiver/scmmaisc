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
import static com.scmmaisc.engine.executor.ExecutorSupport.round2;

/**
 * 精益/敏捷/精敏混合供应链策略仿真执行器（T058，CH7-003）。
 * 模型：产品 Fisher 矩阵定位（需求可预测性 × 供应不确定性热力图）→ 按产品类型推荐策略
 * （功能型→Lean / 创新型→Agile / 混合型→Leagile）→ 三种策略的成本效率-响应速度对比 →
 * 策略失配损失量化（选择与推荐不符时按可预测度偏差线性放大）。
 */
@Component
public class LeanAgileExecutor implements ScenarioExecutor {

    @Override
    public String engineKey() {
        return "lean-agile";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        enumParam(params, "product_type", Set.of("functional", "innovative", "hybrid"), errors);
        enumParam(params, "lifecycle_stage", Set.of("intro", "growth", "maturity", "decline"), errors);
        enumParam(params, "strategy", Set.of("lean", "agile", "leagile"), errors);
        Double pred = doubleParam(params, "demand_predictability", 0.1, 0.9, errors);
        Double supp = doubleParam(params, "supply_uncertainty", 0.05, 0.4, errors);
        Double leff = doubleParam(params, "lean_efficiency", 0.5, 1.0, errors);
        Double aflex = doubleParam(params, "agile_flexibility", 0.5, 1.0, errors);
        if (errors.isEmpty() && pred != null && supp != null && leff != null && aflex != null) {
            // 约束 strategy_match：策略必须与产品类型匹配（功能型需高可预测度，创新型需低可预测度）
            if (pred < 0.1) {
                errors.add("strategy_match 约束不满足：需求可预测度需 ≥ 0.1，当前策略可能产生失配损失");
            }
        }
        return errors;
    }

    @Override
    public Integer describeSteps(Map<String, Object> params) {
        return 4;
    }

    /** 按产品类型推荐策略：功能型→Lean，创新型→Agile，混合型→Leagile。 */
    private static String recommend(String productType) {
        return switch (productType) {
            case "functional" -> "lean";
            case "innovative" -> "agile";
            default -> "leagile";
        };
    }

    /** 策略理想需求可预测度：Lean 0.75 / Agile 0.25 / Leagile 0.5。 */
    private static double idealPredictability(String strategy) {
        return switch (strategy) {
            case "lean" -> 0.75;
            case "agile" -> 0.25;
            default -> 0.5;
        };
    }

    @Override
    public void run(Map<String, Object> params, SimContext ctx) {
        String productType = String.valueOf(params.get("product_type"));
        String lifecycle = String.valueOf(params.get("lifecycle_stage"));
        String strategy = String.valueOf(params.get("strategy"));
        double pred = ((Number) params.get("demand_predictability")).doubleValue();
        double supp = ((Number) params.get("supply_uncertainty")).doubleValue();
        double leff = ((Number) params.get("lean_efficiency")).doubleValue();
        double aflex = ((Number) params.get("agile_flexibility")).doubleValue();

        // 步骤 1：Fisher 矩阵定位（需求可预测性 × 供应不确定性）
        // 象限推荐：可预测+稳定→Lean高效(1)，可预测+变化→风险规避(2)，
        //          不可预测+稳定→响应型(3)，不可预测+变化→精敏敏捷(4)
        List<String> rows = List.of("需求可预测", "需求不可预测");
        List<String> cols = List.of("供应稳定", "供应变化");
        List<List<Double>> data = List.of(
                List.of(1.0, 2.0),
                List.of(3.0, 4.0));
        Map<String, Object> heatmap = new LinkedHashMap<>();
        heatmap.put("rows", rows);
        heatmap.put("columns", cols);
        heatmap.put("data", data);
        int row = pred >= 0.5 ? 0 : 1;
        int col = supp >= 0.2 ? 1 : 0;
        String[] quadrant = {"Lean高效供应链", "风险规避供应链", "响应型供应链", "精敏敏捷供应链"};
        String quadrantName = quadrant[row * 2 + col];
        ctx.step(String.format("Fisher 矩阵定位：需求可预测度 %.2f（%s），供应不确定性 %.2f（%s）→ %s",
                        pred, pred >= 0.5 ? "高" : "低", supp, supp >= 0.2 ? "高" : "低", quadrantName),
                Map.of("fisher_matrix", heatmap, "product_type", productType,
                        "demand_predictability", round2(pred), "supply_uncertainty", round2(supp)));

        // 步骤 2：策略推荐与匹配度评分
        String rec = recommend(productType);
        double fit = 100.0;
        if (!strategy.equals(rec)) {
            fit -= 35;
        }
        fit -= Math.min(50, Math.abs(pred - idealPredictability(strategy)) * 80);
        if (lifecycle.equals("intro")) {
            fit += strategy.equals("agile") ? 8 : strategy.equals("lean") ? -5 : 0;
        } else if (lifecycle.equals("maturity")) {
            fit += strategy.equals("lean") ? 8 : strategy.equals("agile") ? -5 : 0;
        } else if (lifecycle.equals("decline")) {
            fit += strategy.equals("agile") ? 5 : strategy.equals("lean") ? -3 : 0;
        }
        fit = Math.max(5, Math.min(100, fit));
        String[] names = {"Lean精益", "Agile敏捷", "Leagile精敏"};
        String recName = names[switch (rec) { case "lean" -> 0; case "agile" -> 1; default -> 2; }];
        ctx.step(String.format("%s 产品（%s 阶段）→ 推荐 %s 策略；当前选择 %s，匹配度 %.1f 分",
                        productType, lifecycle, recName,
                        names[switch (strategy) { case "lean" -> 0; case "agile" -> 1; default -> 2; }], fit),
                Map.of("recommended_strategy", recName, "strategy_fit", round2(fit),
                        "lifecycle_stage", lifecycle));

        // 步骤 3：成本效率-响应速度对比（% 制评分）
        List<Map<String, Object>> compare = new ArrayList<>();
        compare.add(Map.of("name", "Lean成本效率", "value", round2(leff * 100)));
        compare.add(Map.of("name", "Lean响应速度", "value", round2(35 + leff * 15)));
        compare.add(Map.of("name", "Agile成本效率", "value", round2(40 + aflex * 35)));
        compare.add(Map.of("name", "Agile响应速度", "value", round2(aflex * 100)));
        compare.add(Map.of("name", "Leagile成本效率", "value", round2((leff * 0.6 + aflex * 0.4) * 92)));
        compare.add(Map.of("name", "Leagile响应速度", "value", round2((aflex * 0.6 + leff * 0.4) * 95)));
        ctx.step("三种策略在成本效率与响应速度维度的评分对比（精益重成本、敏捷重响应、精敏兼顾）",
                Map.of("efficiency_vs_speed", compare));

        // 步骤 4：失配损失量化（选择与推荐不符 → 按可预测度偏差放大，元）
        double loss = 0;
        if (!strategy.equals(rec)) {
            loss = round2(Math.abs(pred - idealPredictability(strategy)) * 2_000_000.0);
        }
        String verdict = loss <= 0
                ? "策略与产品类型匹配，未产生失配损失"
                : String.format("策略与推荐不符，预计失配损失 %,.0f 元（可预测度偏差 %.2f）",
                loss, Math.abs(pred - idealPredictability(strategy)));
        ctx.step(verdict, Map.of("mismatch_loss", round2(loss), "strategy_fit_final", round2(fit)));

        // 输出指标（FR-007）
        ctx.output("fisher_matrix", "Fisher矩阵定位", "heatmap", heatmap, null);
        ctx.output("strategy_fit", "策略匹配度评分", "scalar", round2(fit), null);
        ctx.output("efficiency_vs_speed", "成本效率vs响应速度对比", "compare", compare, null);
        ctx.output("mismatch_loss", "失配损失", "scalar", round2(loss), "元");
    }
}
