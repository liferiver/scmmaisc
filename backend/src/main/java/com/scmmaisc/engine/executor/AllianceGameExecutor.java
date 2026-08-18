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
import static com.scmmaisc.engine.executor.ExecutorSupport.matrixParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.round2;

/**
 * 物流联盟合作博弈仿真执行器（T052，CH1-007）。
 * 模型：联盟总成本 = 独立成本之和 × 协同系数 + 资源池年摊销；联盟节省 = 独立 − 联盟；
 * 特征函数 v(S) = 独立成本(S) × (1 − 协同节省率 × (|S|−1)/(n−1))；
 * Shapley 值 = 全排列边际贡献均值（n≤6，≤720 排列）；
 * 分配方案：均分/按量比例/Shapley值/按贡献，输出公平性指数（1−变异系数）与联盟稳定性。
 * 确定性模型：seed 无关（FR-008）。
 */
@Component
public class AllianceGameExecutor implements ScenarioExecutor {

    private static final Set<String> ALLOCATION_METHODS = Set.of("equal", "by_volume", "shapley", "by_contribution");

    @Override
    public String engineKey() {
        return "alliance-game";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        Integer memberCount = intParam(params, "member_count", 3, 6, errors);
        double[][] members = params.containsKey("members")
                ? matrixParam(params, "members", 3, 6, 2, 0, 1_000_000, errors)
                : null; // 可选矩阵：缺省时由 run() 按种子合成
        doubleParam(params, "resource_pool", 0, 1_000_000, errors);
        Double synergy = doubleParam(params, "synergy_factor", 0.8, 1.0, errors);
        enumParam(params, "allocation_method", ALLOCATION_METHODS, errors);
        if (errors.isEmpty() && memberCount != null && members != null && members.length != memberCount) {
            errors.add("members 行数必须等于 member_count");
        }
        if (errors.isEmpty() && members != null && synergy != null) {
            // 约束 collective_rationality：联盟总节省必须大于 0（否则联盟无意义）
            double sum = 0;
            for (double[] m : members) {
                sum += m[1];
            }
            double saving = sum * (1 - synergy);
            if (saving <= 0) {
                errors.add("collective_rationality 约束不满足：协同系数 " + synergy + " 下联盟无节省");
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
        int n = ((Number) params.get("member_count")).intValue();
        double[][] members = matrixParam(params, "members", 3, 6, 2, 0, 1_000_000, new ArrayList<>());
        if (members == null) {
            members = new double[n][2];
            for (int i = 0; i < n; i++) {
                members[i][0] = round2(1000 + ctx.random().nextDouble() * 9000);   // 缺省合成：年货量
                members[i][1] = round2(50000 + ctx.random().nextDouble() * 150000); // 独立成本
            }
        }
        double resourcePool = ((Number) params.get("resource_pool")).doubleValue();
        double synergy = ((Number) params.get("synergy_factor")).doubleValue();
        String method = String.valueOf(params.get("allocation_method"));

        double[] volume = new double[n];
        double[] cost = new double[n];
        double sumCost = 0;
        for (int i = 0; i < n; i++) {
            volume[i] = members[i][0];
            cost[i] = members[i][1];
            sumCost += cost[i];
        }
        double amort = resourcePool * 0.01;               // 共享资源池年摊销（1%）
        double g = 1 - synergy;                           // 协同节省率
        double allianceCost = sumCost * synergy + amort;
        double totalSaving = sumCost - allianceCost;

        // 步骤 1：独立运营成本测算
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append("、");
            }
            sb.append(String.format("企业%d（年物流量 %.0f 万吨、独立成本 %.0f 万元）", i + 1, volume[i], cost[i]));
        }
        ctx.step(String.format("独立运营成本测算：%s，合计 %.0f 万元", sb, sumCost),
                Map.of("independent_total", round2(sumCost)));

        // 步骤 2：联盟成本与总节省
        ctx.step(String.format("联盟组建：协同系数 %.2f（节省率 %.0f%%）+ 资源池摊销 %.0f 万元 → 联盟总成本 %.0f 万元，总节省 %.0f 万元",
                synergy, g * 100, amort, allianceCost, totalSaving),
                Map.of("alliance_cost", round2(allianceCost), "total_saving", round2(totalSaving)));

        // 步骤 3：Shapley 值计算（特征函数 v(S) = 独立成本(S) × (1 − g×(|S|−1)/(n−1))）
        double[] shapley = new double[n];
        int[] perm = new int[n];
        for (int i = 0; i < n; i++) {
            perm[i] = i;
        }
        int count = 0;
        do {
            double vPrev = 0;
            for (int k = 0; k < n; k++) {
                int i = perm[k];
                double vNext = coalitionValue(cost, k + 1, n, g, perm, k, amort);
                shapley[i] += vNext - vPrev;
                vPrev = vNext;
            }
            count++;
        } while (nextPermutation(perm));
        for (int i = 0; i < n; i++) {
            shapley[i] = shapley[i] / count;
        }
        List<Map<String, Object>> shapleyItems = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            shapleyItems.add(Map.of("name", "企业" + (i + 1), "value", round2(shapley[i])));
        }
        ctx.step("Shapley 值计算完成（" + count + " 种排列的边际贡献均值）：" + shapleySummary(shapley),
                Map.of("permutation_count", count));

        // 步骤 4：利益分配
        double[] gain = new double[n];
        switch (method) {
            case "equal" -> {
                for (int i = 0; i < n; i++) {
                    gain[i] = totalSaving / n;
                }
            }
            case "by_volume" -> {
                double sumV = 0;
                for (double v : volume) {
                    sumV += v;
                }
                for (int i = 0; i < n; i++) {
                    gain[i] = sumV == 0 ? 0 : totalSaving * volume[i] / sumV;
                }
            }
            case "by_contribution" -> {
                double sumC = 0;
                for (int i = 0; i < n; i++) {
                    sumC += cost[i] * volume[i];
                }
                for (int i = 0; i < n; i++) {
                    gain[i] = sumC == 0 ? 0 : totalSaving * cost[i] * volume[i] / sumC;
                }
            }
            default -> { // shapley：Shapley 值即节省份额
                for (int i = 0; i < n; i++) {
                    gain[i] = shapley[i];
                }
            }
        }
        List<Map<String, Object>> gainItems = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            gainItems.add(Map.of("name", "企业" + (i + 1), "value", round2(gain[i])));
        }
        String methodName = switch (method) {
            case "equal" -> "均分";
            case "by_volume" -> "按量比例";
            case "by_contribution" -> "按贡献";
            default -> "Shapley值";
        };
        ctx.step(String.format("分配方案【%s】：%s", methodName, gainSummary(gain)),
                Map.of("allocation_method", method));

        // 步骤 5：公平性与稳定性
        double mean = 0;
        for (double v : gain) {
            mean += v;
        }
        mean /= n;
        double variance = 0;
        for (double v : gain) {
            variance += (v - mean) * (v - mean);
        }
        double cv = mean == 0 ? 1 : Math.sqrt(variance / n) / mean;   // 变异系数
        double fairness = Math.max(0, Math.min(1, 1 - cv));
        boolean irrational = false;
        for (double v : gain) {
            if (v < 0) {
                irrational = true;    // 违反个体理性 → 联盟解体风险
            }
        }
        double stability = 100 * fairness * Math.min(1, 0.6 + 0.4 * g / 0.2);
        if (irrational) {
            stability *= 0.5;
        }
        stability = Math.max(0, Math.min(100, stability));
        ctx.step(String.format("公平性指数 %.2f（1−变异系数）；联盟稳定性 %.0f%%%s",
                fairness, stability, irrational ? "（存在个体理性破坏成员，稳定性折半）" : ""),
                Map.of("fairness_index", round2(fairness), "stability", round2(stability), "irrational", irrational));

        // 输出指标（FR-007）
        ctx.output("total_saving", "联盟总成本节省", "scalar", round2(totalSaving), "万元");
        ctx.output("member_gain", "各成员实际分得利益", "compare", gainItems, "万元");
        ctx.output("fairness_index", "分配公平性指数", "scalar", round2(fairness), null);
        ctx.output("stability", "联盟稳定性", "gauge", round2(stability), "%");
        ctx.output("shapley_values", "Shapley值计算结果", "compare", shapleyItems, "万元");
    }

    /** 特征函数：子集 S = perm[0..k] 的联盟节省（独立×节省率×规模系数 − 摊销分摊）。 */
    private double coalitionValue(double[] cost, int size, int n, double g, int[] perm, int k, double amort) {
        double sum = 0;
        for (int i = 0; i <= k; i++) {
            sum += cost[perm[i]];
        }
        double saving = sum * g * (size - 1.0) / (n - 1.0);
        return saving - size * amort / n;
    }

    private boolean nextPermutation(int[] a) {
        int i = a.length - 2;
        while (i >= 0 && a[i] >= a[i + 1]) {
            i--;
        }
        if (i < 0) {
            return false;
        }
        int j = a.length - 1;
        while (a[j] <= a[i]) {
            j--;
        }
        int t = a[i];
        a[i] = a[j];
        a[j] = t;
        for (int l = i + 1, r = a.length - 1; l < r; l++, r--) {
            t = a[l];
            a[l] = a[r];
            a[r] = t;
        }
        return true;
    }

    private String shapleySummary(double[] shapley) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < shapley.length; i++) {
            if (i > 0) {
                sb.append("、");
            }
            sb.append(String.format("企业%d %.0f 万元", i + 1, shapley[i]));
        }
        return sb.toString();
    }

    private String gainSummary(double[] gain) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < gain.length; i++) {
            if (i > 0) {
                sb.append("、");
            }
            sb.append(String.format("企业%d %.0f 万元", i + 1, gain[i]));
        }
        return sb.toString();
    }
}
