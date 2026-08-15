package com.scmmaisc.engine.executor;

import com.scmmaisc.engine.ScenarioExecutor;
import com.scmmaisc.engine.SimContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.scmmaisc.engine.executor.ExecutorSupport.doubleParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.intParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.round2;

/**
 * 物流 7R 服务目标履约仿真执行器（T027，CH1-002）。
 * 7R：Right Product / Quantity / Condition / Place / Time / Customer / Cost。
 * 模型：库存准确率 → 产品/数量 R；品质合格率 → 品质 R；配送准时率 → 地点/时间 R；
 * 客户匹配为各率综合；成本 R 由单均履约成本与成本上限之比给出。
 * 确定性模型：seed 无关（FR-008）。
 */
@Component
public class SevenRExecutor implements ScenarioExecutor {

    private static final String[] R_KEYS = {
            "product", "quantity", "condition", "place", "time", "customer", "cost"
    };
    private static final String[] R_LABELS = {
            "产品正确", "数量正确", "品质完好", "地点准确", "时间准时", "客户匹配", "成本达标"
    };

    @Override
    public String engineKey() {
        return "seven-r";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        Integer volume = intParam(params, "order_volume", 50, 500, errors);
        Integer skus = intParam(params, "sku_count", 10, 200, errors);
        Double accuracy = doubleParam(params, "inventory_accuracy", 0.9, 1.0, errors);
        Double quality = doubleParam(params, "quality_rate", 0.9, 1.0, errors);
        Double onTime = doubleParam(params, "delivery_on_time_rate", 0.8, 1.0, errors);
        Double costLimit = doubleParam(params, "cost_per_order_limit", 10, 200, errors);
        if (errors.isEmpty() && volume != null && skus != null && accuracy != null
                && quality != null && onTime != null && costLimit != null) {
            double avgCost = avgCost(quality, onTime, accuracy);
            if (avgCost > costLimit) {
                errors.add(String.format("cost_per_order_limit 约束不满足：单均履约成本 %.1f 元超过上限 %.1f 元",
                        avgCost, costLimit));
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
        int volume = ((Number) params.get("order_volume")).intValue();
        int skus = ((Number) params.get("sku_count")).intValue();
        double accuracy = ((Number) params.get("inventory_accuracy")).doubleValue();
        double quality = ((Number) params.get("quality_rate")).doubleValue();
        double onTime = ((Number) params.get("delivery_on_time_rate")).doubleValue();
        double costLimit = ((Number) params.get("cost_per_order_limit")).doubleValue();

        // 步骤 1：订单接收与需求特征
        ctx.step(String.format("订单接收：每轮 %d 单、%d 个 SKU；库存准确率 %.0f%%，品质合格率 %.0f%%，配送准时率 %.0f%%",
                volume, skus, accuracy * 100, quality * 100, onTime * 100),
                Map.of("order_volume", volume, "sku_count", skus));

        // 步骤 2：产品/数量/品质核验（R1–R3）
        ctx.step(String.format("产品与数量核验：依赖库存准确率 %.0f%%（错货/错量源于账实不符）；品质确认依赖合格率 %.0f%%",
                accuracy * 100, quality * 100), Map.of("inventory_accuracy", round2(accuracy),
                "quality_rate", round2(quality)));

        // 步骤 3：地点/时效承诺（R4–R5）
        ctx.step(String.format("目的地分配与时效承诺：准时率 %.0f%% 决定地点/时间两个 R 的达成",
                onTime * 100), Map.of("delivery_on_time_rate", round2(onTime)));

        // 步骤 4：成本核算（R7）
        double avgCost = avgCost(quality, onTime, accuracy);
        double costRate = Math.min(1.0, costLimit / avgCost);
        ctx.step(String.format("成本核算：单均履约成本 %.1f 元（上限 %.0f 元）→ 成本达成率 %.0f%%",
                avgCost, costLimit, costRate * 100),
                Map.of("avg_cost", round2(avgCost), "cost_limit", costLimit, "cost_rate", round2(costRate)));

        // 步骤 5：7R 综合评分
        double[] rates = {
                accuracy,                // 产品
                accuracy,                // 数量
                quality,                 // 品质
                onTime,                  // 地点
                onTime,                  // 时间
                (accuracy + quality) / 2, // 客户匹配
                costRate                 // 成本
        };
        double score = 0;
        for (double r : rates) {
            score += r;
        }
        score = score / rates.length * 100;

        List<Map<String, Object>> rRates = new ArrayList<>();
        List<Map<String, Object>> violations = new ArrayList<>();
        int qualified = 0;
        for (int i = 0; i < rates.length; i++) {
            rRates.add(Map.of("name", R_LABELS[i], "value", round2(rates[i] * 100)));
            violations.add(Map.of("name", R_LABELS[i] + "违约", "value", Math.round(volume * (1 - rates[i]))));
            if (rates[i] >= 0.95) {
                qualified++;
            }
        }
        ctx.step(String.format("7R 综合达成率 %.1f%%，其中 %d/7 项达成率 ≥ 95%%（约束：至少 5 项）",
                score, qualified),
                Map.of("seven_r_score", round2(score), "qualified_count", qualified));

        // 输出指标（FR-007）
        ctx.output("seven_r_score", "7R 综合达成率", "gauge", round2(score), "%");
        ctx.output("r_rates", "各 R 达成率", "compare", rRates, "%");
        ctx.output("violation_dist", "违约订单分布", "dist", violations, "单/轮");
    }

    private static double avgCost(double quality, double onTime, double accuracy) {
        // 简化模型：基准 40 元 + 品质/准时/准确率惩罚
        return 40 + (1 - quality) * 80 + (1 - onTime) * 60 + (1 - accuracy) * 40;
    }
}
