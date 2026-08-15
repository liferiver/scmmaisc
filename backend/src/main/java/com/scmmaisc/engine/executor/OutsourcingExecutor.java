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
import static com.scmmaisc.engine.executor.ExecutorSupport.matrixParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.round2;

/**
 * 核心竞争力识别与外包决策仿真执行器（T029，CH6-002）。
 * 模型：价值链活动按 VRIO 四维评分（价值/稀缺/模仿难度/组织）评估，均分 ≥ 4 为核心竞争力
 * （必须自制）；其余活动按市场成熟度/转换成本/质控难度计算外包倾向得分，≥ 0.55 建议外包。
 * 确定性模型：seed 无关（FR-008）。
 */
@Component
public class OutsourcingExecutor implements ScenarioExecutor {

    @Override
    public String engineKey() {
        return "outsourcing";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        Integer count = intParam(params, "activity_count", 5, 15, errors);
        double[][] vrio = matrixParam(params, "activity_vrio", 5, 15, 4, 1, 5, errors);
        Double maturity = doubleParam(params, "market_maturity", 0, 1, errors);
        Double switching = doubleParam(params, "switching_cost", 1, 100, errors);
        Double qcDifficulty = doubleParam(params, "quality_control_difficulty", 1, 5, errors);
        if (errors.isEmpty() && count != null && vrio != null && maturity != null
                && switching != null && qcDifficulty != null) {
            // 约束 core_inhouse：行数须与 activity_count 一致（VRIO 行 = 活动）
            if (vrio.length != count) {
                errors.add(String.format("activity_vrio 行数 %d 与 activity_count %d 不一致", vrio.length, count));
            }
            // 约束 core_inhouse：核心竞争力（VRIO 均分 ≥ 4）不允许外包
            for (int i = 0; i < vrio.length; i++) {
                if (avg(vrio[i]) >= 4.0) {
                    double score = outsourceScore(maturity, switching, qcDifficulty);
                    if (score >= 0.55) {
                        errors.add(String.format("core_inhouse 约束不满足：活动 %d 为核心竞争力（VRIO %.1f），不可外包",
                                i + 1, avg(vrio[i])));
                    }
                }
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
        int count = ((Number) params.get("activity_count")).intValue();
        @SuppressWarnings("unchecked")
        List<List<Number>> vrioRaw = (List<List<Number>>) params.get("activity_vrio");
        double[][] vrio = new double[vrioRaw.size()][4];
        for (int r = 0; r < vrioRaw.size(); r++) {
            for (int c = 0; c < 4; c++) {
                vrio[r][c] = vrioRaw.get(r).get(c).doubleValue();
            }
        }
        double maturity = ((Number) params.get("market_maturity")).doubleValue();
        double switching = ((Number) params.get("switching_cost")).doubleValue();
        double qcDifficulty = ((Number) params.get("quality_control_difficulty")).doubleValue();

        // 步骤 1：价值链分解
        ctx.step(String.format("价值链分解：识别 %d 项业务活动（采购/生产/物流/营销等），逐项 VRIO 评分",
                count), Map.of("activity_count", count));

        // 步骤 2：VRIO 评估与核心竞争力识别
        List<Map<String, Object>> matrixRows = new ArrayList<>();
        List<String> coreActivities = new ArrayList<>();
        for (int i = 0; i < vrio.length; i++) {
            boolean core = avg(vrio[i]) >= 4.0;
            if (core) {
                coreActivities.add("活动" + (i + 1));
            }
            matrixRows.add(Map.of(
                    "活动" + (i + 1), List.of(round2(vrio[i][0]), round2(vrio[i][1]),
                            round2(vrio[i][2]), round2(vrio[i][3]))));
        }
        ctx.step(String.format("VRIO 评估：核心活动 %d 项（%s）—— 价值+稀缺+难模仿+组织均分 ≥ 4",
                coreActivities.size(), String.join("、", coreActivities)),
                Map.of("core_count", coreActivities.size(), "core_activities", coreActivities));

        // 步骤 3：非核心活动外包可行性评估
        double score = outsourceScore(maturity, switching, qcDifficulty);
        int outsourceCount = 0;
        for (int i = 0; i < vrio.length; i++) {
            if (avg(vrio[i]) < 4.0 && score >= 0.55) {
                outsourceCount++;
            }
        }
        ctx.step(String.format("外包可行性：市场成熟度 %.0f%%，转换成本 %.0f，质控难度 %.1f/5 → 倾向分 %.2f，建议外包 %d 项",
                maturity * 100, switching, qcDifficulty, score, outsourceCount),
                Map.of("outsource_score", round2(score), "outsource_count", outsourceCount));

        // 步骤 4：决策矩阵与风险
        Map<String, Object> heatmap = new LinkedHashMap<>();
        heatmap.put("rows", List.of("活动1", "活动2", "活动3", "活动4", "活动5"));
        heatmap.put("columns", List.of("价值", "稀缺", "模仿难度", "组织", "外包倾向"));
        List<List<Double>> data = new ArrayList<>();
        for (int i = 0; i < vrio.length; i++) {
            double s = avg(vrio[i]) >= 4.0 ? 0 : score;   // 核心活动外包倾向记为 0
            data.add(List.of(round2(vrio[i][0]), round2(vrio[i][1]), round2(vrio[i][2]),
                    round2(vrio[i][3]), round2(s)));
        }
        heatmap.put("data", data);
        double risk = (qcDifficulty / 5.0) * 0.6 + (1 - maturity) * 0.4;
        ctx.step(String.format("外包风险暴露 %.0f%%（质控难度与市场成熟度综合）", risk * 100),
                Map.of("outsourcing_risk", round2(risk * 100)));

        // 步骤 5：成本对比
        double inHouseCost = 0;
        for (double[] row : vrio) {
            inHouseCost += avg(row) * 20;                 // 自制：能力强度 × 成本基数
        }
        double outsourceCost = inHouseCost * (1 - maturity * 0.3) + switching * outsourceCount;
        ctx.step(String.format("成本对比：全部自制 %.0f 万元 vs 混合外包 %.0f 万元（节省 %.0f 万元）",
                inHouseCost, outsourceCost, Math.max(0, inHouseCost - outsourceCost)),
                Map.of("in_house_cost", round2(inHouseCost), "outsource_cost", round2(outsourceCost)));

        // 输出指标（FR-007）
        ctx.output("core_competencies", "核心竞争力数量", "scalar", coreActivities.size(), "项");
        ctx.output("decision_matrix", "自制/外包决策矩阵", "heatmap", heatmap, null);
        ctx.output("outsourcing_risk", "外包风险暴露", "gauge", round2(risk * 100), "%");
        ctx.output("cost_compare", "自制 vs 外包成本对比", "compare",
                List.of(Map.of("name", "全部自制", "value", round2(inHouseCost)),
                        Map.of("name", "混合外包", "value", round2(outsourceCost))),
                "万元");
    }

    private static double avg(double[] row) {
        return (row[0] + row[1] + row[2] + row[3]) / 4.0;
    }

    private static double outsourceScore(double maturity, double switching, double qcDifficulty) {
        return maturity * 0.5 + (1 - qcDifficulty / 5.0) * 0.3 + (1 - Math.min(switching, 100) / 100.0) * 0.2;
    }
}
