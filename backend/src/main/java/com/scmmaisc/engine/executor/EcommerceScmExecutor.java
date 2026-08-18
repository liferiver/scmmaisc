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
import static com.scmmaisc.engine.executor.ExecutorSupport.series;

/**
 * 电子商务环境 SCM 战略仿真执行器（T057，CH6-005）。
 * 模型：传统模式（单中心仓、周补货、反应式）vs 电商优化策略（前置仓 + 高频补货 + 智能补货算法）
 * 对比缺货率；订单响应时效按前置仓数递减；库存周转率与长尾 SKU 满足率随策略提升。
 */
@Component
public class EcommerceScmExecutor implements ScenarioExecutor {

    private static final Set<String> ALGORITHMS = Set.of("min_max", "moving_average", "ml_forecast");

    @Override
    public String engineKey() {
        return "ecommerce-scm";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        Integer sku = intParam(params, "sku_count", 100, 100000, errors);
        Double cv = doubleParam(params, "demand_cv", 0.2, 1.5, errors);
        Double ret = doubleParam(params, "return_rate", 0.05, 0.3, errors);
        Integer front = intParam(params, "front_warehouse_count", 1, 20, errors);
        Integer freq = intParam(params, "replenish_frequency", 1, 7, errors);
        String algo = enumParam(params, "replenish_algorithm", ALGORITHMS, errors);
        if (errors.isEmpty() && sku != null && cv != null && ret != null
                && front != null && freq != null && algo != null) {
            // 约束 sla_ok：至少 1 个前置仓保障时效 SLA
            if (front < 1) {
                errors.add("sla_ok 约束不满足：需至少配置 1 个前置仓以保障平台 SLA");
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
        int sku = ((Number) params.get("sku_count")).intValue();
        double cv = ((Number) params.get("demand_cv")).doubleValue();
        double ret = ((Number) params.get("return_rate")).doubleValue();
        int front = ((Number) params.get("front_warehouse_count")).intValue();
        int freq = ((Number) params.get("replenish_frequency")).intValue();
        String algo = String.valueOf(params.get("replenish_algorithm"));

        // 步骤 1：电商环境特征
        ctx.step(String.format("SKU %d（长尾），需求波动 CV=%.2f（传统约 0.3），退货率 %.0f%%",
                        sku, cv, ret * 100),
                Map.of("sku_count", sku, "demand_cv", cv, "return_rate", ret));

        // 步骤 2：策略配置
        double algoBonus = switch (algo) {
            case "ml_forecast" -> 0.03;
            case "moving_average" -> 0.015;
            default -> 0.0;
        };
        double algoTurnover = switch (algo) {
            case "ml_forecast" -> 1.5;
            case "moving_average" -> 0.8;
            default -> 0.0;
        };
        ctx.step(String.format("电商策略：前置仓 %d 个 + 补货 %d 次/周 + 算法 %s（缺货减免 %.1f%%）",
                        front, freq, algo, algoBonus * 100),
                Map.of("front_warehouse_count", front, "replenish_frequency", freq,
                        "replenish_algorithm", algo));

        // 步骤 3：运行对比（传统 vs 电商策略）
        double traditional = Math.min(0.6, 0.12 + cv * 0.15 + ret * 0.3);
        double ecom = Math.max(0.01, traditional - front * 0.02 - freq * 0.01 - algoBonus);
        List<Map<String, Object>> stockoutCompare = List.of(
                Map.of("name", "传统模式", "value", round2(traditional * 100)),
                Map.of("name", "电商策略", "value", round2(ecom * 100)));
        List<Double> cum = new ArrayList<>();
        double t = 0;
        cum.add(round2(t));
        t += 0.5; // 订单分配
        cum.add(round2(t));
        t += 1.2; // 前置仓拣选
        cum.add(round2(t));
        t += Math.max(0.5, 2.5 - front * 0.2) + cv * 1.5; // 末端配送
        cum.add(round2(t));
        t += 0.3; // 签收
        cum.add(round2(t));
        ctx.step(String.format("缺货率：传统 %.1f%% → 电商策略 %.1f%%；订单响应累计 %.1f 小时",
                        traditional * 100, ecom * 100, t),
                Map.of("stockout_compare", stockoutCompare, "response_hours", round2(t)));

        // 步骤 4：库存周转与长尾满足率
        double turnover = Math.max(1, 4 + freq * 1.2 + algoTurnover - cv * 1.5);
        double longtail = Math.max(0.5, 0.98 - Math.log10(Math.max(100, sku) / 100.0) * 0.05
                - cv * 0.08 + front * 0.004);
        ctx.step(String.format("库存周转率 %.1f 次/年（补货 %d 次/周%s）；长尾 SKU 满足率 %.1f%%",
                        turnover, freq, algo.equals("ml_forecast") ? "，ML 预测加成" : "", longtail * 100),
                Map.of("inventory_turnover", round2(turnover), "longtail_fulfillment", round2(longtail * 100)));

        // 输出指标（FR-007）
        ctx.output("stockout_compare", "缺货率对比(传统vs电商策略)", "compare", stockoutCompare, "%");
        ctx.output("response_time", "订单响应时间", "series",
                series(List.of(1, 2, 3, 4, 5), "累计响应(小时)", cum), "小时");
        ctx.output("inventory_turnover", "库存周转率", "scalar", round2(turnover), "次/年");
        ctx.output("longtail_fulfillment", "长尾SKU满足率", "scalar", round2(longtail * 100), "%");
    }
}
