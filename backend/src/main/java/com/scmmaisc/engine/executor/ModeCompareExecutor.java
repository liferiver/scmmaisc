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
 * 自营/3PL/物流联盟/4PL 模式对比仿真执行器（T027，CH1-004）。
 * 模型：四种模式成本-服务-风险三维评估 —— 自营（高固定低可变/高控制）、
 * 3PL（按量付费/低控制）、联盟（共享资源节省/利益绑定）、4PL（整合优化/强依赖费率）。
 * 按服务要求（standard/high/ultra）过滤可行模式，取总成本最低者推荐。
 * 确定性模型：seed 无关（FR-008）。
 */
@Component
public class ModeCompareExecutor implements ScenarioExecutor {

    private static final Set<String> SERVICE_LEVELS = Set.of("standard", "high", "ultra");

    @Override
    public String engineKey() {
        return "mode-compare";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        Integer volume = intParam(params, "annual_volume", 10_000, 10_000_000, errors);
        Double invest = doubleParam(params, "self_fixed_invest", 100, 100_000, errors);
        Double varCost = doubleParam(params, "self_variable_cost", 1, 100, errors);
        Double thirdPartyPrice = doubleParam(params, "third_party_unit_price", 1, 100, errors);
        Integer members = intParam(params, "alliance_members", 2, 10, errors);
        Double feeRate = doubleParam(params, "fourth_party_fee_rate", 0.05, 0.15, errors);
        String service = enumParam(params, "service_level", SERVICE_LEVELS, errors);
        if (errors.isEmpty() && volume != null && invest != null && varCost != null
                && thirdPartyPrice != null && members != null && feeRate != null && service != null) {
            double required = requiredService(service);
            // 约束：至少一种模式满足最低服务要求
            double bestFeasible = Double.MAX_VALUE;
            double selfCost = invest * 10_000 + varCost * volume;
            if (0.95 >= required) {
                bestFeasible = Math.min(bestFeasible, selfCost);
            }
            double thirdCost = thirdPartyPrice * volume;
            if (0.85 >= required) {
                bestFeasible = Math.min(bestFeasible, thirdCost);
            }
            double allianceCost = thirdPartyPrice * volume * 0.9 * (1 - 0.03 * (members - 2));
            if (0.88 >= required) {
                bestFeasible = Math.min(bestFeasible, allianceCost);
            }
            double fourthCost = thirdPartyPrice * volume * 0.95 * (1 + feeRate);
            if (0.90 >= required) {
                bestFeasible = Math.min(bestFeasible, fourthCost);
            }
            if (bestFeasible == Double.MAX_VALUE) {
                errors.add("service_level 要求过高：四种模式均无法满足最低服务要求，请降低服务标准");
            }
        }
        return errors;
    }

    @Override
    public Integer describeSteps(Map<String, Object> params) {
        return 5;
    }

    @Override
    public void run(Map<String, Object> params, SimContext ctx) {
        int volume = ((Number) params.get("annual_volume")).intValue();
        double invest = ((Number) params.get("self_fixed_invest")).doubleValue();
        double varCost = ((Number) params.get("self_variable_cost")).doubleValue();
        double thirdPartyPrice = ((Number) params.get("third_party_unit_price")).doubleValue();
        int members = ((Number) params.get("alliance_members")).intValue();
        double feeRate = ((Number) params.get("fourth_party_fee_rate")).doubleValue();
        String service = String.valueOf(params.get("service_level"));
        double required = requiredService(service);

        // 四种模式成本/服务/控制力/风险
        double selfCost = invest * 10_000 + varCost * volume;              // 自营：高固定低可变
        double thirdCost = thirdPartyPrice * volume;                        // 3PL：按量付费
        double allianceCost = thirdPartyPrice * volume * 0.9 * (1 - 0.03 * (members - 2)); // 联盟：共享节省
        double fourthCost = thirdPartyPrice * volume * 0.95 * (1 + feeRate);               // 4PL：整合+费率

        // 步骤 1：企业特征与物流需求分析
        ctx.step(String.format("年物流量 %d 单位，服务要求 %s（最低达成率 %.0f%%），自营固定投资 %.0f 万元",
                volume, service, required * 100, invest),
                Map.of("annual_volume", volume, "service_level", service));

        // 步骤 2：四种模式成本测算
        Map<String, Object> costs = new LinkedHashMap<>();
        costs.put("self", round2(selfCost));
        costs.put("third_party", round2(thirdCost));
        costs.put("alliance", round2(allianceCost));
        costs.put("fourth_party", round2(fourthCost));
        ctx.step(String.format("成本测算：自营 %.0f 万、3PL %.0f 万、联盟 %.0f 万、4PL %.0f 万（单位：元）",
                selfCost / 10000, thirdCost / 10000, allianceCost / 10000, fourthCost / 10000), costs);

        // 步骤 3：服务达成率评估（过滤不满足服务要求的模式）
        String[] names = {"自营", "3PL", "物流联盟", "4PL"};
        double[] modeCosts = {selfCost, thirdCost, allianceCost, fourthCost};
        double[] modeService = {0.95, 0.85, 0.88, 0.90};
        double[] modeControl = {0.90, 0.40, 0.60, 0.50};
        double[] modeRisk = {0.30, 0.60, 0.45, 0.55};
        List<Map<String, Object>> serviceCompare = new ArrayList<>();
        List<Map<String, Object>> riskCompare = new ArrayList<>();
        List<Map<String, Object>> costCompare = new ArrayList<>();
        double bestCost = Double.MAX_VALUE;
        String bestMode = "";
        for (int i = 0; i < names.length; i++) {
            serviceCompare.add(Map.of("name", names[i], "value", round2(modeService[i] * 100)));
            riskCompare.add(Map.of("name", names[i] + "控制力", "value", round2(modeControl[i] * 100)));
            riskCompare.add(Map.of("name", names[i] + "风险", "value", round2(modeRisk[i] * 100)));
            costCompare.add(Map.of("name", names[i], "value", round2(modeCosts[i])));
            if (modeService[i] >= required && modeCosts[i] < bestCost) {
                bestCost = modeCosts[i];
                bestMode = names[i];
            }
        }
        ctx.step(String.format("服务过滤：要求 ≥%.0f%%，可行模式中总成本最低为「%s」", required * 100, bestMode),
                Map.of("required_service", round2(required * 100), "best_mode", bestMode));

        // 步骤 4：最优模式推荐
        ctx.step(String.format("最优模式推荐：%s（总成本 %.0f 万元）；联盟模式随成员数 %d 共享资源",
                bestMode, bestCost / 10000, members),
                Map.of("best_mode", bestMode, "best_cost", round2(bestCost), "alliance_members", members));

        // 步骤 5：决策边界提示
        ctx.step("决策要点：规模扩大 → 固定成本摊薄使自营可行；服务要求极高 → 3PL/联盟被排除",
                Map.of("service_level", service));

        // 输出指标（FR-007）
        ctx.output("total_cost_compare", "四种模式总成本对比", "compare", costCompare, "元");
        ctx.output("service_rates", "服务达成率", "compare", serviceCompare, "%");
        ctx.output("control_risk_scores", "控制力与风险评分", "compare", riskCompare, "分");
        ctx.output("best_mode", "最优模式推荐", "scalar", bestMode, null);
    }

    private static double requiredService(String service) {
        return switch (service) {
            case "standard" -> 0.85;
            case "high" -> 0.90;
            default -> 0.95; // ultra
        };
    }
}
