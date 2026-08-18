package com.scmmaisc.engine.executor;

import com.scmmaisc.engine.ScenarioExecutor;
import com.scmmaisc.engine.SimContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.scmmaisc.engine.executor.ExecutorSupport.doubleParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.intParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.round2;

/**
 * 物流系统 5S 目标协同仿真执行器（T053，CH2-001）。
 * 模型：服务/速度/空间/节约/库存五目标各自达成率（目标约束越严苛达成率越低）→
 * 加权综合得分（权重和=1）→ 目标两两冲突矩阵（速度 vs 节约天然冲突最强）→
 * Pareto 前沿：在服务与节约之间平移资源生成折中方案序列。
 * 确定性模型：seed 无关（FR-008）。
 */
@Component
public class FiveSTargetExecutor implements ScenarioExecutor {

    private static final List<String> S_NAMES = List.of("服务S1", "速度S2", "空间S3", "节约S4", "库存S5");

    /** 5S 两两冲突基准度（0-100，对称，对角线=100 自身完全一致）。 */
    private static final double[][] CONFLICT = {
            {100, 40, 30, 70, 55},
            {40, 100, 35, 85, 60},
            {30, 35, 100, 45, 75},
            {70, 85, 45, 100, 50},
            {55, 60, 75, 50, 100}};

    @Override
    public String engineKey() {
        return "five-s";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        Double wService = doubleParam(params, "weight_service", 0, 1, errors);
        Double wSpeed = doubleParam(params, "weight_speed", 0, 1, errors);
        Double wSpace = doubleParam(params, "weight_space", 0, 1, errors);
        Double wSavings = doubleParam(params, "weight_savings", 0, 1, errors);
        Double wStock = doubleParam(params, "weight_stock", 0, 1, errors);
        doubleParam(params, "stockout_limit", 0.01, 0.10, errors);
        doubleParam(params, "ontime_limit", 0.90, 0.99, errors);
        doubleParam(params, "capacity_limit", 0.70, 0.95, errors);
        doubleParam(params, "cost_rate_limit", 0.05, 0.20, errors);
        intParam(params, "turnover_limit", 6, 52, errors);
        if (errors.isEmpty() && wService != null && wSpeed != null && wSpace != null
                && wSavings != null && wStock != null) {
            // 约束 weight_sum：5S 权重和必须等于 1
            double sum = wService + wSpeed + wSpace + wSavings + wStock;
            if (Math.abs(sum - 1.0) > 1e-6) {
                errors.add("weight_sum 约束不满足：5S 权重和必须等于 1（当前 " + round2(sum) + "）");
            }
        }
        return errors;
    }

    @Override
    public Integer describeSteps(Map<String, Object> params) {
        return 4;
    }

    @Override
    public void run(Map<String, Object> params, SimContext ctx) {
        double[] w = {
                ((Number) params.get("weight_service")).doubleValue(),
                ((Number) params.get("weight_speed")).doubleValue(),
                ((Number) params.get("weight_space")).doubleValue(),
                ((Number) params.get("weight_savings")).doubleValue(),
                ((Number) params.get("weight_stock")).doubleValue()};
        double stockoutLimit = ((Number) params.get("stockout_limit")).doubleValue();
        double ontimeLimit = ((Number) params.get("ontime_limit")).doubleValue();
        double capacityLimit = ((Number) params.get("capacity_limit")).doubleValue();
        double costRateLimit = ((Number) params.get("cost_rate_limit")).doubleValue();
        int turnoverLimit = ((Number) params.get("turnover_limit")).intValue();

        // 步骤 1：各 S 达成率（约束越严苛 → 达成率越低；默认阈值下无惩罚）
        double[] a = new double[5];
        a[0] = 65 + w[0] * 35 - Math.max(0, 0.05 - stockoutLimit) * 100;      // 服务：缺货率上限
        a[1] = 65 + w[1] * 35 - Math.max(0, ontimeLimit - 0.95) * 200;        // 速度：准时率下限
        a[2] = 65 + w[2] * 35 - Math.max(0, capacityLimit - 0.85) * 100;      // 空间：库容率上限
        a[3] = 65 + w[3] * 35 - Math.max(0, 0.12 - costRateLimit) * 100;      // 节约：成本率上限
        a[4] = 65 + w[4] * 35 - Math.max(0, turnoverLimit - 24) * 0.5;        // 库存：周转率下限
        for (int i = 0; i < 5; i++) {
            a[i] = Math.max(10, Math.min(100, a[i]));
        }
        ctx.step(String.format("5S 达成率测算：服务 %.0f%%、速度 %.0f%%、空间 %.0f%%、节约 %.0f%%、库存 %.0f%%"
                        + "（约束：缺货率≤%.0f%%、准时率≥%.0f%%、库容率≤%.0f%%、成本率≤%.0f%%、周转率≥%d 次）",
                a[0], a[1], a[2], a[3], a[4], stockoutLimit * 100, ontimeLimit * 100,
                capacityLimit * 100, costRateLimit * 100, turnoverLimit),
                Map.of("s1_service", round2(a[0]), "s2_speed", round2(a[1]), "s3_space", round2(a[2]),
                        "s4_savings", round2(a[3]), "s5_stock", round2(a[4])));

        // 步骤 2：加权综合得分
        double score = 0;
        for (int i = 0; i < 5; i++) {
            score += w[i] * a[i];
        }
        List<Map<String, Object>> radar = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            radar.add(Map.of("name", S_NAMES.get(i), "value", round2(a[i])));
        }
        ctx.step(String.format("5S 综合得分 %.1f 分（权重 服务%.0f%%/速度%.0f%%/空间%.0f%%/节约%.0f%%/库存%.0f%%）",
                score, w[0] * 100, w[1] * 100, w[2] * 100, w[3] * 100, w[4] * 100),
                Map.of("s5_score", round2(score)));

        // 步骤 3：目标冲突矩阵（冲突最强的速度-节约 85）
        List<List<Double>> data = new ArrayList<>();
        for (int r = 0; r < 5; r++) {
            List<Double> row = new ArrayList<>();
            for (int c = 0; c < 5; c++) {
                row.add((double) CONFLICT[r][c]);
            }
            data.add(row);
        }
        Map<String, Object> heatmap = new LinkedHashMap<>();
        heatmap.put("rows", S_NAMES);
        heatmap.put("columns", S_NAMES);
        heatmap.put("data", data);
        ctx.step("目标冲突矩阵：速度-节约冲突最强（85），空间-库存次之（75）；权重侧重冲突目标时需协同折中",
                Map.of("max_conflict", 85));

        // 步骤 4：Pareto 前沿（服务 vs 节约 资源平移，其余目标微降）
        List<Map<String, Object>> frontier = new ArrayList<>();
        for (int k = 0; k <= 10; k++) {
            double shift = k * 2.0;                       // 资源向服务平移 0~20%
            double s4 = Math.max(10, a[3] - shift * 1.2); // 节约让渡
            double s1 = Math.min(100, a[0] + shift * 0.8);// 服务提升
            double s2 = Math.max(10, a[1] - shift * 0.3); // 速度微降
            double scoreK = w[0] * s1 + w[1] * s2 + w[2] * a[2] + w[3] * s4 + w[4] * a[4];
            frontier.add(Map.of("name", "方案" + (k + 1) + "(服务+节约折中)",
                    "value", round2(scoreK)));
        }
        ctx.step(String.format("Pareto 前沿搜索完成：11 个折中方案（资源向服务平移 0~20%%），"
                        + "权重向量决定最优解在前沿上的落点（当前 %.1f 分）",
                score), Map.of("frontier_size", frontier.size()));

        // 输出指标（FR-007）
        ctx.output("s5_score", "5S综合得分", "scalar", round2(score), "分");
        ctx.output("s5_radar", "各S达成率雷达图", "compare", radar, "%");
        ctx.output("pareto_frontier", "Pareto前沿", "compare", frontier, "分");
        ctx.output("conflict_matrix", "目标冲突矩阵", "heatmap", heatmap, null);
    }
}
