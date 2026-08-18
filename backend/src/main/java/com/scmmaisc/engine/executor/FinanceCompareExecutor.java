package com.scmmaisc.engine.executor;

import com.scmmaisc.engine.ScenarioExecutor;
import com.scmmaisc.engine.SimContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.scmmaisc.engine.executor.ExecutorSupport.doubleParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.enumParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.round2;

/**
 * 供应链金融三种模式对比与选择仿真执行器（T060，CH9-004）。
 * 模型：资产负债表识别可融资资产（应收/存货/预付）→ 按供应链位置与核心企业评级评估三种模式
 * （应收账款融资/预付账款融资/存货质押融资）的可得性 → 利率×金额计算年化融资成本 → 综合
 * 评分（可得性 50% + 成本 25% + 办理速度 25%）推荐最优模式与组合方案 → 钢铁经销商/电子供应商/
 * 农产品加工三类典型企业验证。
 */
@Component
public class FinanceCompareExecutor implements ScenarioExecutor {

    private static final String[] MODES = {"receivable", "prepayment", "inventory"};
    private static final String[] MODE_NAMES = {"应收账款融资", "预付账款融资", "存货质押融资"};

    @Override
    public String engineKey() {
        return "finance-compare";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        doubleParam(params, "asset_total", 1000, 100000, errors);
        Double rec = doubleParam(params, "receivable_ratio", 0, 0.5, errors);
        Double inv = doubleParam(params, "inventory_ratio", 0, 0.5, errors);
        Double prep = doubleParam(params, "prepayment_ratio", 0, 0.5, errors);
        enumParam(params, "supply_chain_position", Set.of("upstream", "midstream", "downstream"), errors);
        enumParam(params, "core_credit_rating", Set.of("AAA", "AA", "A", "BBB"), errors);
        doubleParam(params, "rate_receivable", 0.04, 0.08, errors);
        doubleParam(params, "rate_prepayment", 0.05, 0.1, errors);
        Double rateInv = doubleParam(params, "rate_inventory", 0.06, 0.12, errors);
        doubleParam(params, "days_receivable", 3, 20, errors);
        doubleParam(params, "days_prepayment", 3, 20, errors);
        doubleParam(params, "days_inventory", 3, 20, errors);
        if (errors.isEmpty() && rec != null && inv != null && prep != null) {
            if (rec + inv + prep > 1) {
                errors.add("ratio_sum_ok 约束不满足：资产负债表各项占比之和不能超过 1");
            }
        }
        if (errors.isEmpty() && rateInv != null && rateInv > 0.12) {
            errors.add("cost_within_margin 约束不满足：融资总成本不超过企业利润率（存货利率需 ≤12%）");
        }
        return errors;
    }

    @Override
    public Integer describeSteps(Map<String, Object> params) {
        return 5;
    }

    @Override
    public void run(Map<String, Object> params, SimContext ctx) {
        double assetTotal = ((Number) params.get("asset_total")).doubleValue(); // 万元
        double[] ratios = {
                ((Number) params.get("receivable_ratio")).doubleValue(),
                ((Number) params.get("prepayment_ratio")).doubleValue(),
                ((Number) params.get("inventory_ratio")).doubleValue()};
        String position = String.valueOf(params.get("supply_chain_position"));
        String rating = String.valueOf(params.get("core_credit_rating"));
        double[] rates = {
                ((Number) params.get("rate_receivable")).doubleValue(),
                ((Number) params.get("rate_prepayment")).doubleValue(),
                ((Number) params.get("rate_inventory")).doubleValue()};
        double[] days = {
                ((Number) params.get("days_receivable")).doubleValue(),
                ((Number) params.get("days_prepayment")).doubleValue(),
                ((Number) params.get("days_inventory")).doubleValue()};

        // 步骤 1：资产负债表资产识别
        double[] assets = {assetTotal * ratios[0], assetTotal * ratios[1], assetTotal * ratios[2]};
        ctx.step(String.format("资产识别：总资产 %,.0f 万元 = 应收 %,.0f（%.0f%%）+ 预付 %,.0f（%.0f%%）"
                        + "+ 存货 %,.0f（%.0f%%）",
                assetTotal, assets[0], ratios[0] * 100, assets[1], ratios[1] * 100,
                assets[2], ratios[2] * 100),
                Map.of("receivable_asset", round2(assets[0]), "prepayment_asset", round2(assets[1]),
                        "inventory_asset", round2(assets[2])));

        // 步骤 2：三种模式可得性评估（供应链位置 + 核心企业评级）
        double ratingFactor = switch (rating) {
            case "AAA" -> 1.0;
            case "AA" -> 0.9;
            case "A" -> 0.8;
            default -> 0.7;
        };
        double[] positionFactor = switch (position) {
            case "upstream" -> new double[]{0.95, 0.55, 0.75};   // 上游多应收
            case "downstream" -> new double[]{0.55, 0.95, 0.75}; // 下游多预付
            default -> new double[]{0.75, 0.75, 0.85};           // 中游均衡
        };
        double[] availability = new double[3];
        for (int i = 0; i < 3; i++) {
            availability[i] = assets[i] > 0 ? Math.min(1, positionFactor[i] * ratingFactor) : 0;
        }
        List<Map<String, Object>> modeCompare = List.of(
                Map.of("name", MODE_NAMES[0] + "-可得性", "value", round2(availability[0] * 100)),
                Map.of("name", MODE_NAMES[1] + "-可得性", "value", round2(availability[1] * 100)),
                Map.of("name", MODE_NAMES[2] + "-可得性", "value", round2(availability[2] * 100)),
                Map.of("name", MODE_NAMES[0] + "-年化成本", "value", round2(assets[0] * rates[0])),
                Map.of("name", MODE_NAMES[1] + "-年化成本", "value", round2(assets[1] * rates[1])),
                Map.of("name", MODE_NAMES[2] + "-年化成本", "value", round2(assets[2] * rates[2])));
        ctx.step(String.format("模式可得性：应收 %.0f%% / 预付 %.0f%% / 存货 %.0f%%（位置：%s，核心企业评级 %s）",
                availability[0] * 100, availability[1] * 100, availability[2] * 100, position, rating),
                Map.of("mode_compare", modeCompare));

        // 步骤 3：融资成本对比（年化，元）
        List<Map<String, Object>> costCompare = new ArrayList<>();
        double totalFinanced = 0;
        for (int i = 0; i < 3; i++) {
            double cost = assets[i] * rates[i] * 10000;
            costCompare.add(Map.of("name", MODE_NAMES[i], "value", round2(cost)));
            totalFinanced += assets[i] * availability[i];
        }
        ctx.step("年化融资成本对比（利率：应收 < 预付 < 存货；成本 = 可融资资产 × 利率）",
                Map.of("cost_compare", costCompare));

        // 步骤 4：综合评分与最优模式推荐（可得性 50% + 成本 25% + 速度 25%）
        double[] scores = new double[3];
        int bestIdx = 0;
        for (int i = 0; i < 3; i++) {
            scores[i] = availability[i] * 0.5 + (1 - rates[i] / 0.12) * 0.25 + (1 - days[i] / 20) * 0.25;
            if (scores[i] > scores[bestIdx]) {
                bestIdx = i;
            }
        }
        double weightedRate = totalFinanced > 0
                ? (assets[0] * availability[0] * rates[0] + assets[1] * availability[1] * rates[1]
                + assets[2] * availability[2] * rates[2]) / totalFinanced : 0;
        double combinedEfficiency = totalFinanced / assetTotal * (1 - weightedRate) * 100;
        String bestMode = scores[bestIdx] > 0.3 ? MODE_NAMES[bestIdx] : "自筹资金（可得性不足）";
        ctx.step(String.format("综合评分：应收 %.2f / 预付 %.2f / 存货 %.2f → 推荐【%s】；"
                        + "组合融资效率 %.1f%%（可融 %,.0f 万元，加权利率 %.1f%%）",
                scores[0], scores[1], scores[2], bestMode, combinedEfficiency,
                totalFinanced, weightedRate * 100),
                Map.of("best_mode", bestMode, "combined_efficiency", round2(combinedEfficiency)));

        // 步骤 5：三类典型企业验证（钢铁经销商/电子供应商/农产品加工）
        String[][] presets = {
                {"钢铁经销商（下游）", "downstream", "0.05", "0.40", "0.35"},
                {"电子供应商（上游）", "upstream", "0.45", "0.05", "0.10"},
                {"农产品加工（中游）", "midstream", "0.20", "0.15", "0.30"}};
        List<String> advice = new ArrayList<>();
        for (String[] preset : presets) {
            double[] pRatios = {Double.parseDouble(preset[2]), Double.parseDouble(preset[3]), Double.parseDouble(preset[4])};
            double[] pPos = switch (preset[1]) {
                case "upstream" -> new double[]{0.95, 0.55, 0.75};
                case "downstream" -> new double[]{0.55, 0.95, 0.75};
                default -> new double[]{0.75, 0.75, 0.85};
            };
            int best = 0;
            double bestScore = -1;
            for (int i = 0; i < 3; i++) {
                double avail = pRatios[i] > 0 ? Math.min(1, pPos[i] * ratingFactor) : 0;
                double s = avail * 0.5 + (1 - rates[i] / 0.12) * 0.25 + (1 - days[i] / 20) * 0.25;
                if (s > bestScore) {
                    bestScore = s;
                    best = i;
                }
            }
            advice.add(preset[0] + " → " + MODE_NAMES[best]);
        }
        ctx.step("典型企业验证：" + String.join("；", advice),
                Map.of("typical_advice", advice));

        // 输出指标（FR-007）
        ctx.output("mode_compare", "三种模式可得性/成本/风险对比", "compare", modeCompare, null);
        ctx.output("best_mode", "最优模式推荐", "scalar", bestMode, null);
        ctx.output("combined_efficiency", "组合融资效率", "gauge", List.of(
                Map.of("name", "组合融资效率", "value", round2(combinedEfficiency))), "%");
        ctx.output("cost_compare", "融资成本对比", "compare", costCompare, "元");
    }
}
