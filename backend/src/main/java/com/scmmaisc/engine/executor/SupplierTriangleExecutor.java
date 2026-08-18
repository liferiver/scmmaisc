package com.scmmaisc.engine.executor;

import com.scmmaisc.engine.ScenarioExecutor;
import com.scmmaisc.engine.SimContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.scmmaisc.engine.executor.ExecutorSupport.doubleParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.intParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.matrixParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.round2;

/**
 * 华为供应商"铁三角"管理仿真执行器（T052，CH1-001）。
 * 模型：候选供应商六维打分（技术/质量/响应/交付/成本/环保）× 权重 → 综合排名 →
 * 样品测试（按样品合格率概率判定）→ 现场审核（审核项数越多越易发现缺陷）→ 分级认证；
 * 综合得分低于认证阈值或未通过样品/审核的供应商淘汰。
 * 随机判定仅用于样品测试与现场审核（R-05：种子可复现）。
 */
@Component
public class SupplierTriangleExecutor implements ScenarioExecutor {

    private static final List<String> DIMENSIONS = List.of("技术", "质量", "响应", "交付", "成本", "环保");
    private static final List<String> GRADES = List.of("A", "B", "C", "D");

    @Override
    public String engineKey() {
        return "supplier-triangle";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        Integer candidateCount = intParam(params, "candidate_count", 5, 50, errors);
        Double wTech = doubleParam(params, "weight_tech", 0, 1, errors);
        Double wQuality = doubleParam(params, "weight_quality", 0, 1, errors);
        Double wResponse = doubleParam(params, "weight_response", 0, 1, errors);
        Double wDelivery = doubleParam(params, "weight_delivery", 0, 1, errors);
        Double wCost = doubleParam(params, "weight_cost", 0, 1, errors);
        Double wEnv = doubleParam(params, "weight_environment", 0, 1, errors);
        double[][] scores = params.containsKey("supplier_scores")
                ? matrixParam(params, "supplier_scores", 5, 50, 6, 0, 100, errors)
                : null; // 可选矩阵：缺省时由 run() 按种子合成
        Double threshold = doubleParam(params, "pass_threshold", 60, 90, errors);
        Double sampleRate = doubleParam(params, "sample_pass_rate", 0, 1, errors);
        Integer auditItems = intParam(params, "audit_items", 10, 50, errors);
        if (errors.isEmpty() && candidateCount != null && scores != null && scores.length != candidateCount) {
            errors.add("supplier_scores 行数必须等于 candidate_count");
        }
        if (errors.isEmpty() && wTech != null && wQuality != null && wResponse != null
                && wDelivery != null && wCost != null && wEnv != null) {
            // 约束 weight_sum：六维权重和必须等于 1
            double sum = wTech + wQuality + wResponse + wDelivery + wCost + wEnv;
            if (Math.abs(sum - 1.0) > 1e-6) {
                errors.add("weight_sum 约束不满足：六维权重和必须等于 1（当前 " + round2(sum) + "）");
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
        int candidateCount = ((Number) params.get("candidate_count")).intValue();
        double[] weights = new double[]{
                ((Number) params.get("weight_tech")).doubleValue(),
                ((Number) params.get("weight_quality")).doubleValue(),
                ((Number) params.get("weight_response")).doubleValue(),
                ((Number) params.get("weight_delivery")).doubleValue(),
                ((Number) params.get("weight_cost")).doubleValue(),
                ((Number) params.get("weight_environment")).doubleValue()};
        double[][] scores = matrixParam(params, "supplier_scores", 5, 50, 6, 0, 100, new ArrayList<>());
        if (scores == null) {
            scores = new double[candidateCount][6];
            for (int i = 0; i < candidateCount; i++) {
                for (int d = 0; d < 6; d++) {
                    scores[i][d] = round2(60 + ctx.random().nextDouble() * 40); // 缺省合成：60~100 分
                }
            }
        }
        double threshold = ((Number) params.get("pass_threshold")).doubleValue();
        double sampleRate = ((Number) params.get("sample_pass_rate")).doubleValue();
        int auditItems = ((Number) params.get("audit_items")).intValue();

        // 步骤 1：权重设定与六维打分
        double[] total = new double[candidateCount];
        double[] dimAvg = new double[6];
        for (int i = 0; i < candidateCount; i++) {
            for (int d = 0; d < 6; d++) {
                total[i] += scores[i][d] * weights[d];
                dimAvg[d] += scores[i][d];
            }
        }
        for (int d = 0; d < 6; d++) {
            dimAvg[d] /= candidateCount;
        }
        ctx.step(String.format("六维加权打分完成（权重 技术%.0f%%/质量%.0f%%/响应%.0f%%/交付%.0f%%/成本%.0f%%/环保%.0f%%），"
                        + "平均分 技术%.1f/质量%.1f/响应%.1f/交付%.1f/成本%.1f/环保%.1f",
                weights[0] * 100, weights[1] * 100, weights[2] * 100, weights[3] * 100,
                weights[4] * 100, weights[5] * 100, dimAvg[0], dimAvg[1], dimAvg[2], dimAvg[3], dimAvg[4], dimAvg[5]),
                Map.of("weight_sum", round2(weights[0] + weights[1] + weights[2] + weights[3] + weights[4] + weights[5])));

        // 步骤 2：综合排名与初筛（低于阈值直接淘汰）
        int[] order = new int[candidateCount];
        for (int i = 0; i < candidateCount; i++) {
            order[i] = i;
        }
        for (int i = 0; i < candidateCount - 1; i++) {
            for (int j = i + 1; j < candidateCount; j++) {
                if (total[order[j]] > total[order[i]]) {
                    int t = order[i];
                    order[i] = order[j];
                    order[j] = t;
                }
            }
        }
        int aboveThreshold = 0;
        for (int i = 0; i < candidateCount; i++) {
            if (total[i] >= threshold) {
                aboveThreshold++;
            }
        }
        ctx.step(String.format("综合排名完成：得分≥%.0f 分的候选 %d/%d 家进入样品测试（最高 %.1f 分）",
                threshold, aboveThreshold, candidateCount, total[order[0]]),
                Map.of("above_threshold", aboveThreshold));

        // 步骤 3：样品测试（按样品合格率概率判定，种子可复现）
        boolean[] samplePass = new boolean[candidateCount];
        for (int i = 0; i < candidateCount; i++) {
            samplePass[i] = ctx.random().nextDouble() < sampleRate;
        }
        ctx.step(String.format("样品测试完成：合格率 %.0f%%，通过 %d 家", sampleRate * 100,
                countTrue(samplePass)), Map.of("sample_passed", countTrue(samplePass)));

        // 步骤 4：现场审核（审核项数越多越易发现缺陷；得分越低风险越大）
        boolean[] auditPass = new boolean[candidateCount];
        for (int i = 0; i < candidateCount; i++) {
            double failProb = (1 - total[i] / 100.0) * auditItems / 50.0;
            auditPass[i] = ctx.random().nextDouble() >= failProb;
        }
        ctx.step(String.format("现场审核完成（%d 项审核清单）：通过 %d 家", auditItems, countTrue(auditPass)),
                Map.of("audit_passed", countTrue(auditPass)));

        // 步骤 5：分级认证与结果输出
        List<Map<String, Object>> qualified = new ArrayList<>();
        int[] gradeCount = new int[GRADES.size()];
        for (int i = 0; i < candidateCount; i++) {
            if (total[i] >= threshold && samplePass[i] && auditPass[i]) {
                int grade = total[i] >= 90 ? 0 : total[i] >= 80 ? 1 : total[i] >= 70 ? 2 : 3;
                gradeCount[grade]++;
                qualified.add(Map.of("name", "供应商" + (i + 1), "value", round2(total[i])));
            }
        }
        double passRate = candidateCount == 0 ? 0 : qualified.size() * 100.0 / candidateCount;
        List<Map<String, Object>> gradeDist = new ArrayList<>();
        for (int g = 0; g < GRADES.size(); g++) {
            gradeDist.add(Map.of("name", GRADES.get(g) + "级", "value", gradeCount[g]));
        }
        List<Map<String, Object>> radar = new ArrayList<>();
        for (int d = 0; d < 6; d++) {
            radar.add(Map.of("name", DIMENSIONS.get(d), "value", round2(dimAvg[d])));
        }
        ctx.step(String.format("认证结果：合格供应商 %d 家（通过率 %.0f%%），等级分布 %s",
                qualified.size(), passRate, gradeSummary(gradeCount)),
                Map.of("qualified_count", qualified.size(), "pass_rate", round2(passRate)));

        // 输出指标（FR-007）
        ctx.output("qualified_suppliers", "合格供应商名单", "compare", qualified, "分");
        ctx.output("grade_distribution", "供应商等级分布", "compare", gradeDist, "家");
        ctx.output("pass_rate", "认证通过率", "scalar", round2(passRate), "%");
        ctx.output("score_radar", "各维度得分雷达图", "compare", radar, "分");
    }

    private int countTrue(boolean[] arr) {
        int c = 0;
        for (boolean b : arr) {
            if (b) {
                c++;
            }
        }
        return c;
    }

    private String gradeSummary(int[] gradeCount) {
        StringBuilder sb = new StringBuilder();
        for (int g = 0; g < GRADES.size(); g++) {
            if (g > 0) {
                sb.append("，");
            }
            sb.append(GRADES.get(g)).append("级 ").append(gradeCount[g]).append(" 家");
        }
        return sb.toString();
    }
}
