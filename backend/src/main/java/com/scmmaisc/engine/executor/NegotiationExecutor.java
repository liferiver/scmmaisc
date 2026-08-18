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
 * 供应链合同谈判综合博弈仿真执行器（T059，CH8-008，综合难度）。
 * 模型：五类市场场景 × 五种合同模板（批发价/回购/收益共享/数量折扣/数量柔性）适配度矩阵 →
 * 供应商与零售商按当前场景-合同组合评估利润与链效率 → 限时谈判博弈（谈判时间 + 目标效率
 * 决定达成率）→ 激励相容/参与约束校验（双方优于无协议底线）→ 各场景最优合同热力图与
 * Pareto 改进幅度。
 */
@Component
public class NegotiationExecutor implements ScenarioExecutor {

    /** 五种合同的基础协调效率（批发价 78% 为底线）。 */
    private static final String[] CONTRACTS = {"wholesale", "buyback", "revenue_sharing", "qty_discount", "qty_flexibility"};
    private static final String[] CONTRACT_NAMES = {"批发价", "回购", "收益共享", "数量折扣", "数量柔性"};
    private static final double[] BASE_EFF = {0.78, 0.92, 0.95, 0.88, 0.90};
    /** 五类场景 × 五合同适配度乘子（行=场景，列=合同）。 */
    private static final double[][] SCENE_FIT = {
            {0.85, 1.08, 1.05, 0.90, 0.95},   // fashion 时尚品
            {1.05, 0.85, 0.90, 1.08, 0.95},   // functional 功能品
            {0.90, 0.95, 1.10, 0.95, 0.95},   // high_fixed_cost 高固定成本
            {0.80, 1.05, 1.02, 0.85, 1.10},   // high_demand_uncertainty 高需求不确定
            {0.90, 0.90, 1.02, 0.92, 1.08}};  // long_lead_time 长提前期
    private static final String[] SCENES = {"fashion", "functional", "high_fixed_cost", "high_demand_uncertainty", "long_lead_time"};
    private static final String[] SCENE_NAMES = {"时尚品", "功能品", "高固定成本", "高需求不确定", "长提前期"};
    private static final double[] SCENE_POTENTIAL = {1_200_000, 900_000, 1_500_000, 1_000_000, 1_100_000};

    @Override
    public String engineKey() {
        return "negotiation";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        enumParam(params, "scene_type", Set.of(SCENES), errors);
        enumParam(params, "contract_type", Set.of(CONTRACTS), errors);
        intParam(params, "negotiation_minutes", 10, 20, errors);
        Double target = doubleParam(params, "target_efficiency", 0.7, 1.0, errors);
        if (errors.isEmpty() && target != null && target > 1.0) {
            errors.add("incentive_ok 约束不满足：合同需满足激励相容和参与约束（目标效率需 ≤ 1）");
        }
        return errors;
    }

    @Override
    public Integer describeSteps(Map<String, Object> params) {
        return 5;
    }

    /** 合同在某场景下的效率（基础效率 × 适配度，钳制 [0.5, 1.0]）。 */
    private static double contractEff(int sceneIdx, int contractIdx) {
        return Math.max(0.5, Math.min(1.0, BASE_EFF[contractIdx] * SCENE_FIT[sceneIdx][contractIdx]));
    }

    @Override
    public void run(Map<String, Object> params, SimContext ctx) {
        String scene = String.valueOf(params.get("scene_type"));
        String contract = String.valueOf(params.get("contract_type"));
        int minutes = ((Number) params.get("negotiation_minutes")).intValue();
        double target = ((Number) params.get("target_efficiency")).doubleValue();
        int si = List.of(SCENES).indexOf(scene);
        int ci = List.of(CONTRACTS).indexOf(contract);
        double potential = SCENE_POTENTIAL[si];

        // 步骤 1：市场场景与合同模板选择
        ctx.step(String.format("市场场景：%s（利润潜力 %,.0f 元）；合同模板：%s（谈判限时 %d 分钟，目标效率 %.0f%%）",
                        SCENE_NAMES[si], potential, CONTRACT_NAMES[ci], minutes, target * 100),
                Map.of("scene_type", SCENE_NAMES[si], "contract_type", CONTRACT_NAMES[ci]));

        // 步骤 2：供应商/零售商双方评估（五种合同效率对比）
        List<Map<String, Object>> candidates = new ArrayList<>();
        double bestEff = 0;
        int bestIdx = 0;
        for (int i = 0; i < 5; i++) {
            double eff = contractEff(si, i);
            candidates.add(Map.of("name", CONTRACT_NAMES[i], "value", round2(eff * 100)));
            if (eff > bestEff) {
                bestEff = eff;
                bestIdx = i;
            }
        }
        ctx.step(String.format("双方评估五种合同：%s 在当前场景适配度最高（效率 %.0f%%）",
                        CONTRACT_NAMES[bestIdx], bestEff * 100),
                Map.of("candidate_contracts", candidates));

        // 步骤 3：谈判博弈（限时 + 目标效率 → 达成率）
        double eff = contractEff(si, ci);
        double dealRate = Math.max(0, Math.min(1, 0.55 + (minutes - 10) * 0.03
                - (target - 0.7) * 0.5 + (eff - 0.78) * 0.8)) * 100;
        boolean deal = dealRate >= 50 || eff >= target;
        ctx.step(String.format("谈判 %s：达成率 %.0f%%（合同效率 %.1f%% vs 目标 %.0f%%）",
                        deal ? "达成协议" : "未达成（可调整参数重谈）", dealRate, eff * 100, target * 100),
                Map.of("deal_rate", round2(dealRate), "contract_efficiency", round2(eff * 100)));

        // 步骤 4：利润分配与激励相容/参与约束校验
        double retailerProfit = potential * eff * 0.45;
        double supplierProfit = potential * eff * 0.55;
        double baseline = potential * 0.78; // 无协议底线（批发价合同效率）
        boolean incentive = retailerProfit > baseline * 0.45 && supplierProfit > baseline * 0.55;
        List<Map<String, Object>> profitCompare = List.of(
                Map.of("name", "零售商利润", "value", round2(retailerProfit)),
                Map.of("name", "供应商利润", "value", round2(supplierProfit)),
                Map.of("name", "供应链总利润", "value", round2(potential * eff)),
                Map.of("name", "无协议底线", "value", round2(baseline)));
        ctx.step(String.format("利润分配：零售商 %,.0f / 供应商 %,.0f / 链总 %,.0f 元；参与约束%s满足",
                        retailerProfit, supplierProfit, potential * eff, incentive ? "" : "未"),
                Map.of("profit_compare", profitCompare, "incentive_ok", incentive));

        // 步骤 5：各场景最优合同热力图 + Pareto 改进幅度
        List<String> rows = new ArrayList<>(List.of(SCENE_NAMES));
        List<String> cols = new ArrayList<>(List.of(CONTRACT_NAMES));
        List<List<Double>> grid = new ArrayList<>();
        for (int r = 0; r < 5; r++) {
            List<Double> rowData = new ArrayList<>();
            for (int c = 0; c < 5; c++) {
                rowData.add(round2(contractEff(r, c) * 100));
            }
            grid.add(rowData);
        }
        Map<String, Object> heatmap = new LinkedHashMap<>();
        heatmap.put("rows", rows);
        heatmap.put("columns", cols);
        heatmap.put("data", grid);
        double paretoGain = (eff - 0.78) / 0.78 * 100;
        ctx.step(String.format("Pareto 改进幅度 %.1f%%（当前合同效率 %.1f%% vs 批发价底线 78%%）；"
                        + "时尚品→回购/收益共享，功能品→数量折扣，高不确定→数量柔性",
                        paretoGain, eff * 100),
                Map.of("scene_contract_map", heatmap, "pareto_gain", round2(paretoGain)));

        // 输出指标（FR-007）
        ctx.output("profit_compare", "各方利润", "compare", profitCompare, "元");
        ctx.output("contract_efficiency", "合同效率", "gauge", List.of(
                Map.of("name", "合同效率", "value", round2(eff * 100))), "%");
        ctx.output("scene_contract_map", "各场景最优合同", "heatmap", heatmap, null);
        ctx.output("deal_rate", "谈判达成率", "scalar", round2(dealRate), "%");
        ctx.output("pareto_gain", "Pareto改进幅度", "scalar", round2(paretoGain), "%");
    }
}
