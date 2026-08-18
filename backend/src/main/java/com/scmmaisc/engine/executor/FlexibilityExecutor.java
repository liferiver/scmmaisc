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
import static com.scmmaisc.engine.executor.ExecutorSupport.series;

/**
 * 供应链柔性设计仿真执行器（T058，CH7-004）。
 * 模型：N 个需求情景（各产品带独立冲击）→ 专用设备（低固定/无柔性，逐产品产能封顶）与
 * 柔性设备（高固定/产能可切换，总量弹性分配）逐情景利润对比 → 期望利润/柔性投资 ROI/
 * 最差情景损失 → 柔性利用率序列。柔性价值类似实物期权：高波动情景下柔性更优。
 */
@Component
public class FlexibilityExecutor implements ScenarioExecutor {

    private static final double BASE_DEMAND = 100_000; // 每产品基准年需求量（件）
    private static final double PRICE = 150.0;         // 售价（元/件）

    @Override
    public String engineKey() {
        return "flexibility";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        Integer n = intParam(params, "scenario_count", 3, 10, errors);
        Object probsRaw = params.get("scenario_probs");
        if (probsRaw != null) {
            double[][] p = matrixParam(params, "scenario_probs", 1, 10, 1, 0.0, 1.0, errors);
            if (p != null) {
                double sum = 0;
                for (double[] row : p) {
                    sum += row[0];
                }
                if (Math.abs(sum - 1.0) > 0.01) {
                    errors.add("prob_sum_ok 约束不满足：各情景概率之和需等于 1（当前 " + round2(sum) + "）");
                }
            }
        }
        doubleParam(params, "dedicated_fixed_cost", 100, 1000, errors);
        doubleParam(params, "dedicated_var_cost", 50, 200, errors);
        doubleParam(params, "flexible_fixed_cost", 150, 1500, errors);
        doubleParam(params, "flexible_var_cost", 60, 250, errors);
        intParam(params, "product_types", 2, 5, errors);
        doubleParam(params, "shortage_loss_ratio", 0.3, 1.0, errors);
        doubleParam(params, "surplus_discount", 0.5, 0.8, errors);
        if (errors.isEmpty() && n != null && n < 3) {
            errors.add("prob_sum_ok 约束不满足：需求情景数需 ≥ 3");
        }
        return errors;
    }

    @Override
    public Integer describeSteps(Map<String, Object> params) {
        return 5;
    }

    @Override
    public void run(Map<String, Object> params, SimContext ctx) {
        int n = ((Number) params.get("scenario_count")).intValue();
        int products = ((Number) params.get("product_types")).intValue();
        double dedFixed = ((Number) params.get("dedicated_fixed_cost")).doubleValue() * 10000;
        double dedVar = ((Number) params.get("dedicated_var_cost")).doubleValue();
        double flexFixed = ((Number) params.get("flexible_fixed_cost")).doubleValue() * 10000;
        double flexVar = ((Number) params.get("flexible_var_cost")).doubleValue();
        double shortageRatio = ((Number) params.get("shortage_loss_ratio")).doubleValue();
        double surplusDisc = ((Number) params.get("surplus_discount")).doubleValue();

        // 步骤 1：需求情景构建（需求因子 0.6-1.8 × 产品独立冲击 ±30%，seed 确定性）
        double[] probs = new double[n];
        double[][] probsRaw = matrixParam(params, "scenario_probs", 1, 10, 1, 0.0, 1.0, new ArrayList<>());
        if (probsRaw != null) {
            for (int i = 0; i < n; i++) {
                probs[i] = probsRaw[0][i];
            }
        } else {
            for (int i = 0; i < n; i++) {
                probs[i] = 1.0 / n;
            }
        }
        double[][] demand = new double[n][products];
        double[] totalD = new double[n];
        for (int s = 0; s < n; s++) {
            double factor = 0.6 + ctx.random().nextDouble() * 1.2;
            for (int i = 0; i < products; i++) {
                double shock = ctx.random().nextDouble() * 0.6 - 0.3;
                demand[s][i] = factor * BASE_DEMAND * (1 + shock);
                totalD[s] += demand[s][i];
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int s = 0; s < n; s++) {
            sb.append(String.format("情景%d需求 %,.0f 件（概率 %.2f）；", s + 1, totalD[s], probs[s]));
        }
        ctx.step("构建 " + n + " 个需求情景：" + sb, Map.of("scenario_count", n));

        // 步骤 2/3：专用与柔性设备逐情景利润（专用逐产品封顶，柔性总量弹性分配）
        double dedCap = BASE_DEMAND;
        double flexCap = BASE_DEMAND * products;
        double[] dedProfit = new double[n];
        double[] flexProfit = new double[n];
        double[] util = new double[n];
        for (int s = 0; s < n; s++) {
            double ded = 0;
            for (int i = 0; i < products; i++) {
                double d = demand[s][i];
                double sold = Math.min(d, dedCap);
                double surplus = Math.max(0, dedCap - d);
                double shortage = Math.max(0, d - dedCap);
                ded += PRICE * sold + PRICE * surplus * surplusDisc
                        - PRICE * shortage * shortageRatio - dedVar * sold;
            }
            dedProfit[s] = ded - dedFixed;
            double total = totalD[s];
            double soldF = Math.min(total, flexCap);
            double surplusF = Math.max(0, flexCap - total);
            double shortageF = Math.max(0, total - flexCap);
            flexProfit[s] = PRICE * soldF + PRICE * surplusF * surplusDisc
                    - PRICE * shortageF * shortageRatio - flexVar * soldF - flexFixed;
            util[s] = soldF / flexCap * 100;
        }
        double eDed = 0, eFlex = 0;
        for (int s = 0; s < n; s++) {
            eDed += probs[s] * dedProfit[s];
            eFlex += probs[s] * flexProfit[s];
        }
        ctx.step(String.format("专用设备期望利润 %,.0f 元，柔性设备期望利润 %,.0f 元（固定成本差 %,.0f 元）",
                        eDed, eFlex, flexFixed - dedFixed),
                Map.of("expected_profit_dedicated", round2(eDed),
                        "expected_profit_flexible", round2(eFlex)));

        // 步骤 4：柔性价值评估（ROI、最差情景、利用率）
        double invest = Math.max(1.0, flexFixed - dedFixed);
        double roi = (eFlex - eDed) / invest * 100;
        double worst = 0;
        double minFlex = flexProfit[0];
        for (double p : flexProfit) {
            minFlex = Math.min(minFlex, p);
        }
        worst = Math.max(0, -minFlex);
        List<Double> utilX = new ArrayList<>();
        List<Double> utilY = new ArrayList<>();
        for (int s = 0; s < n; s++) {
            utilX.add((double) (s + 1));
            utilY.add(round2(util[s]));
        }
        ctx.step(String.format("柔性投资 ROI %.1f%%，最差情景损失 %,.0f 元，柔性利用率 %,.1f%%（均值）",
                        roi, worst, avg(util)),
                Map.of("flexibility_roi", round2(roi), "worst_case_loss", round2(worst)));

        // 步骤 5：结果汇总输出
        List<Map<String, Object>> dist = new ArrayList<>();
        for (int s = 0; s < n; s++) {
            dist.add(Map.of("name", "情景" + (s + 1), "value", round2(flexProfit[s])));
        }
        dist.add(Map.of("name", "专用期望", "value", round2(eDed)));
        dist.add(Map.of("name", "柔性期望", "value", round2(eFlex)));
        ctx.step("各情景利润分布与期望值汇总完成", Map.of("profit_dist", dist));

        // 输出指标（FR-007）
        ctx.output("profit_dist", "各情景利润分布", "dist", dist, "元");
        ctx.output("expected_profit", "期望利润", "scalar", round2(eFlex), "元");
        ctx.output("flexibility_roi", "柔性投资ROI", "scalar", round2(roi), "%");
        ctx.output("worst_case_loss", "最差情景损失", "scalar", round2(worst), "元");
        ctx.output("flexibility_utilization", "柔性利用率", "series",
                series(utilX, "利用率(%)", utilY), "%");
    }

    private static double avg(double[] arr) {
        double s = 0;
        for (double v : arr) {
            s += v;
        }
        return s / arr.length;
    }
}
