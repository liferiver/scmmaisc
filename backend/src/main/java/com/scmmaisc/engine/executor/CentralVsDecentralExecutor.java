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
import static com.scmmaisc.engine.executor.ExecutorSupport.intParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.round2;

/**
 * 全球供应链集中化 vs 分散化管理仿真执行器（T061，CH10-002）。
 * 模型：同一跨国企业在多区域市场下比较三种管理模式——集中化（总部统一决策：标准化高效但本地
 * 响应差）、分散化（各地区自主：灵活适应但缺乏协同）、混合（战略集中 + 运营分散）；需求差异度
 * 越大分散化越优、采购协同潜力越大集中化越优，量化总利润/协同节省/响应速度/复杂度成本并给出
 * 最优模式（解释"一管就死、一放就乱"困境）。
 */
@Component
public class CentralVsDecentralExecutor implements ScenarioExecutor {

    private static final double BASE_PROFIT_PER_REGION = 5_000_000.0; // 单区域基准利润（元）

    @Override
    public String engineKey() {
        return "central-vs-decentral";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        Integer regions = intParam(params, "region_count", 3, 7, errors);
        doubleParam(params, "demand_difference", 0, 1, errors);
        doubleParam(params, "synergy_potential", 0, 1, errors);
        doubleParam(params, "info_efficiency", 0.5, 1.0, errors);
        doubleParam(params, "local_knowledge", 0, 1, errors);
        enumParam(params, "management_mode", Set.of("centralized", "decentralized", "hybrid"), errors);
        if (errors.isEmpty() && regions != null && regions < 3) {
            errors.add("regions_ok 约束不满足：至少 3 个区域市场才能体现管理模式差异；需满足各国合规要求");
        }
        return errors;
    }

    @Override
    public Integer describeSteps(Map<String, Object> params) {
        return 5;
    }

    @Override
    public void run(Map<String, Object> params, SimContext ctx) {
        int regions = ((Number) params.get("region_count")).intValue();
        double diff = ((Number) params.get("demand_difference")).doubleValue();
        double synergy = ((Number) params.get("synergy_potential")).doubleValue();
        double info = ((Number) params.get("info_efficiency")).doubleValue();
        double local = ((Number) params.get("local_knowledge")).doubleValue();
        String mode = String.valueOf(params.get("management_mode"));

        // 步骤 1：区域市场与策略参数
        ctx.step(String.format("%d 个区域市场（需求差异度 %.0f%% / 协同潜力 %.0f%% / 信息效率 %.0f%% / "
                        + "本地知识需求 %.0f%%）；管理模式：%s",
                regions, diff * 100, synergy * 100, info * 100, local * 100, modeName(mode)),
                Map.of("region_count", regions, "management_mode", modeName(mode)));

        // 步骤 2：三种模式利润测算
        double[] profits = {centralizedProfit(regions, diff, synergy, info),
                decentralizedProfit(regions, diff, local),
                hybridProfit(regions, diff, synergy, info)};
        double[] synergySavings = {synergy * 2_000_000.0 * regions, 0,
                synergy * 1_200_000.0 * regions};
        double[] complexityCosts = {0.4 * 1_000_000.0 * regions, 1.0 * 1_000_000.0 * regions,
                0.8 * 1_000_000.0 * regions};
        List<Map<String, Object>> profitCompare = List.of(
                Map.of("name", "集中化", "value", round2(profits[0])),
                Map.of("name", "分散化", "value", round2(profits[1])),
                Map.of("name", "混合模式", "value", round2(profits[2])));
        ctx.step(String.format("利润测算：集中化 %,.0f 元 / 分散化 %,.0f 元 / 混合 %,.0f 元",
                        profits[0], profits[1], profits[2]),
                Map.of("profit_compare", profitCompare));

        // 步骤 3：协同节省与复杂度成本
        List<Map<String, Object>> synergyCompare = List.of(
                Map.of("name", "集中化-协同节省", "value", round2(synergySavings[0])),
                Map.of("name", "分散化-协同节省", "value", 0.0),
                Map.of("name", "混合-协同节省", "value", round2(synergySavings[2])));
        List<Map<String, Object>> complexityCompare = List.of(
                Map.of("name", "集中化-复杂度成本", "value", round2(complexityCosts[0])),
                Map.of("name", "分散化-复杂度成本", "value", round2(complexityCosts[1])),
                Map.of("name", "混合-复杂度成本", "value", round2(complexityCosts[2])));
        ctx.step(String.format("协同节省：集中化 %,.0f 元（协同潜力 %.0f%%）；复杂度成本："
                        + "集中化 %,.0f / 分散化 %,.0f / 混合 %,.0f 元",
                synergySavings[0], synergy * 100, complexityCosts[0], complexityCosts[1], complexityCosts[2]),
                Map.of("synergy_compare", synergyCompare, "complexity_compare", complexityCompare));

        // 步骤 4：本地市场响应速度对比（0-100 分）
        double[] response = {
                round2(100 * (1 - diff * 0.6) * info),   // 集中化：受信息损耗与需求差异拖累
                round2(100 * (1 - local * 0.2)),          // 分散化：本地决策最快
                round2(100 * (1 - diff * 0.25))};         // 混合：运营分散缓解响应压力
        List<Map<String, Object>> responseCompare = List.of(
                Map.of("name", "集中化", "value", response[0]),
                Map.of("name", "分散化", "value", response[1]),
                Map.of("name", "混合模式", "value", response[2]));
        ctx.step(String.format("响应速度评分：集中化 %.0f / 分散化 %.0f / 混合 %.0f（满分 100）",
                        response[0], response[1], response[2]),
                Map.of("response_compare", responseCompare));

        // 步骤 5：最优模式与汇总（一管就死、一放就乱分析）
        int best = 0;
        for (int i = 1; i < 3; i++) {
            if (profits[i] > profits[best]) {
                best = i;
            }
        }
        String[] names = {"集中化", "分散化", "混合模式"};
        String conclusion;
        if (diff > 0.6 && synergy < 0.5) {
            conclusion = "需求差异大而协同潜力低 → 分散化更优（一放就活）；强行集中则一管就死";
        } else if (synergy > 0.6 && diff < 0.4) {
            conclusion = "协同潜力高而需求差异小 → 集中化更优（标准化规模效应）";
        } else {
            conclusion = "两者兼顾 → 混合模式（战略集中 + 运营分散）规避一管就死、一放就乱";
        }
        ctx.step(String.format("最优模式：【%s】（总利润 %,.0f 元）；%s",
                        names[best], profits[best], conclusion),
                Map.of("best_mode", names[best], "total_profit", round2(profits[best])));

        // 输出指标（FR-007）
        ctx.output("total_profit", "总利润", "scalar", round2(profits[best]), "元");
        ctx.output("synergy_saving", "采购协同节省", "scalar", round2(synergySavings[best]), "元");
        ctx.output("response_compare", "本地市场响应速度", "compare", responseCompare, null);
        ctx.output("complexity_cost", "管理复杂度成本", "scalar", round2(complexityCosts[best]), "元");
        ctx.output("best_mode", "最优管理模式", "scalar", names[best], null);
    }

    private static String modeName(String mode) {
        return switch (mode) {
            case "centralized" -> "集中化";
            case "decentralized" -> "分散化";
            default -> "混合模式";
        };
    }

    /** 集中化：协同节省 − 响应损失 − 信息损耗成本。 */
    private static double centralizedProfit(int n, double diff, double synergy, double info) {
        double base = BASE_PROFIT_PER_REGION * n;
        double synergySaving = synergy * 2_000_000.0 * n;
        double responseLoss = diff * 3_000_000.0 * n;
        double infoCost = (1 - info) * 1_500_000.0 * n;
        return base + synergySaving - responseLoss - infoCost;
    }

    /** 分散化：本地适配收益 − 重复建设成本（无协同）。 */
    private static double decentralizedProfit(int n, double diff, double local) {
        double base = BASE_PROFIT_PER_REGION * n;
        double localGain = (diff * 0.6 + local * 0.4) * 2_500_000.0 * n;
        double duplication = 1_000_000.0 * n;
        return base + localGain - duplication;
    }

    /** 混合：战略集中（60% 协同）+ 运营分散（70% 本地收益）− 协调成本。 */
    private static double hybridProfit(int n, double diff, double synergy, double info) {
        double base = BASE_PROFIT_PER_REGION * n;
        double synergySaving = synergy * 1_200_000.0 * n;
        double localGain = diff * 0.7 * 2_500_000.0 * n;
        double infoCost = (1 - info) * 400_000.0 * n;
        return base + synergySaving + localGain - infoCost;
    }
}
