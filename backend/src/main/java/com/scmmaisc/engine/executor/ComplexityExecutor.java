package com.scmmaisc.engine.executor;

import com.scmmaisc.engine.ScenarioExecutor;
import com.scmmaisc.engine.SimContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.scmmaisc.engine.executor.ExecutorSupport.distParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.doubleParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.intParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.round2;
import static com.scmmaisc.engine.executor.ExecutorSupport.series;

/**
 * 供应链复杂度管理仿真执行器（T058，CH7-005）。
 * 模型：SKU 销量 Pareto 长尾分布（少数 SKU 贡献多数销量）→ 复杂度指数（SKU/BOM/供应商/客户
 * 四个维度的结构复杂度）→ 复杂度-成本非线性曲线（间接成本随复杂度指数乘方放大）→
 * SKU 精简方案评估（直接成本节省 vs 长尾 SKU 利润损失）。
 */
@Component
public class ComplexityExecutor implements ScenarioExecutor {

    private static final double UNIT_DIRECT_COST = 50.0;   // 单位直接成本（元/件）
    private static final double UNIT_PROFIT = 20.0;        // 单位利润（元/件）
    private static final double BASE_INDIRECT = 20_000_000.0; // 基准间接成本（元）

    @Override
    public String engineKey() {
        return "complexity";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        intParam(params, "sku_count", 50, 10000, errors);
        doubleParam(params, "bom_levels", 2, 8, errors);
        intParam(params, "supplier_count", 20, 500, errors);
        intParam(params, "customer_count", 50, 5000, errors);
        if (params.get("sku_sales_dist") != null) {
            distParam(params, "sku_sales_dist", Map.of("mean", new double[]{1, 100000},
                    "sigma", new double[]{1, 50000}), errors);
        }
        doubleParam(params, "complexity_cost_factor", 1.2, 3.0, errors);
        Double ratio = doubleParam(params, "sku_reduction_ratio", 0, 0.8, errors);
        if (errors.isEmpty() && ratio != null && ratio > 0.8) {
            errors.add("reduction_ok 约束不满足：SKU 精简比例需 ≤ 80%（保留捆绑购买关系的核心组合）");
        }
        return errors;
    }

    @Override
    public Integer describeSteps(Map<String, Object> params) {
        return 5;
    }

    /** 结构复杂度指数：SKU/BOM/供应商/客户四维加权（对数刻度），乘复杂度成本系数。 */
    private static double complexityIndex(int sku, double bom, int supplier, int customer,
                                          double factor, double alpha) {
        double base = 0.6 * Math.log10(Math.max(1, sku) / 1000.0)
                + 0.12 * (bom - 2)
                + 0.35 * Math.log10(Math.max(1, supplier) / 100.0)
                + 0.3 * Math.log10(Math.max(1, customer) / 500.0);
        return Math.max(1.0, 1 + factor * base + 0.2 * (alpha - 1));
    }

    /** 给定精简后 SKU 数，计算总成本（直接成本 + 随复杂度指数乘方放大的间接成本）。 */
    private static double totalCost(double[] sales, int keptSku, double index, double factor) {
        double units = 0;
        for (int k = 0; k < keptSku; k++) {
            units += sales[k];
        }
        double indirect = BASE_INDIRECT * Math.pow(factor, index - 1);
        return units * UNIT_DIRECT_COST + indirect;
    }

    @Override
    public void run(Map<String, Object> params, SimContext ctx) {
        int sku = ((Number) params.get("sku_count")).intValue();
        double bom = ((Number) params.get("bom_levels")).doubleValue();
        int supplier = ((Number) params.get("supplier_count")).intValue();
        int customer = ((Number) params.get("customer_count")).intValue();
        double factor = ((Number) params.get("complexity_cost_factor")).doubleValue();
        double ratio = ((Number) params.get("sku_reduction_ratio")).doubleValue();

        // 步骤 1：SKU 销量 Pareto 长尾分布（α=1.0-1.5 幂律，seed 确定性）
        double mean = 1000.0;
        if (params.get("sku_sales_dist") != null) {
            Map<String, Double> dist = distParam(params, "sku_sales_dist",
                    Map.of("mean", new double[]{1, 100000}, "sigma", new double[]{1, 50000}), new ArrayList<>());
            if (dist != null) {
                mean = dist.get("mean");
            }
        }
        double alpha = 1.0 + ctx.random().nextDouble() * 0.5;
        double[] sales = new double[sku];
        double total = 0;
        for (int r = 0; r < sku; r++) {
            sales[r] = mean / Math.pow(r + 1, alpha);
            total += sales[r];
        }
        // Pareto 曲线：累计 SKU 占比 vs 累计销量占比
        List<Double> paretoX = new ArrayList<>();
        List<Double> paretoY = new ArrayList<>();
        for (int pct = 0; pct <= 100; pct += 5) {
            int k = (int) Math.floor(sku * pct / 100.0);
            double cum = 0;
            for (int r = 0; r < k; r++) {
                cum += sales[r];
            }
            paretoX.add((double) pct);
            paretoY.add(round2(cum / total * 100));
        }
        double top20 = 0;
        for (int r = 0; r < sku / 5; r++) {
            top20 += sales[r];
        }
        ctx.step(String.format("载入 %d 个 SKU：Pareto 系数 α=%.2f，前 20%% SKU 贡献 %,.1f%% 销量",
                        sku, alpha, top20 / total * 100),
                Map.of("pareto_alpha", round2(alpha), "top20_share", round2(top20 / total * 100)));

        // 步骤 2：复杂度指数度量
        double index = complexityIndex(sku, bom, supplier, customer, factor, alpha);
        ctx.step(String.format("复杂度指数 %.2f（SKU %d / BOM %.1f 层 / 供应商 %d / 客户 %d，成本系数 %.2f）",
                        index, sku, bom, supplier, customer, factor),
                Map.of("complexity_index", round2(index)));

        // 步骤 3：复杂度-成本曲线（精简比例 0-80%，步长 10%）
        List<Double> curveX = new ArrayList<>();
        List<Double> curveY = new ArrayList<>();
        double cost0 = totalCost(sales, sku, index, factor);
        for (int r = 0; r <= 8; r++) {
            double cut = r * 0.1;
            int kept = (int) Math.floor(sku * (1 - cut));
            double idx = complexityIndex(kept, bom, supplier, customer, factor, alpha);
            curveX.add(cut * 100);
            curveY.add(round2(totalCost(sales, kept, idx, factor)));
        }
        ctx.step(String.format("复杂度-成本曲线生成（精简比例 0-80%%：%.0f 元 → %.0f 元）",
                        cost0, curveY.get(8)),
                Map.of("cost_curve", curveY));

        // 步骤 4：精简方案评估（成本节省 vs 长尾利润损失）
        int kept = (int) Math.floor(sku * (1 - ratio));
        double idxNew = complexityIndex(kept, bom, supplier, customer, factor, alpha);
        double costNew = totalCost(sales, kept, idxNew, factor);
        double lostUnits = 0;
        for (int r = kept; r < sku; r++) {
            lostUnits += sales[r];
        }
        double lostProfit = lostUnits * UNIT_PROFIT;
        double saving = (cost0 - costNew) - lostProfit;
        List<Map<String, Object>> tail = List.of(
                Map.of("name", "长尾SKU占比", "value", round2(ratio * 100)),
                Map.of("name", "长尾销量占比", "value", round2(lostUnits / total * 100)),
                Map.of("name", "长尾利润贡献", "value", round2(lostProfit)),
                Map.of("name", "精简净节省", "value", round2(saving)));
        ctx.step(String.format("精简比例 %.0f%%：成本 %,.0f → %,.0f 元，损失长尾利润 %,.0f 元，净节省 %,.0f 元",
                        ratio * 100, cost0, costNew, lostProfit, saving),
                Map.of("cost_saving", round2(saving), "lost_tail_profit", round2(lostProfit)));

        // 步骤 5：汇总输出
        ctx.step("Pareto 曲线、复杂度-成本曲线与长尾利润贡献汇总完成",
                Map.of("longtail_profit", tail));

        // 输出指标（FR-007）
        ctx.output("pareto_curve", "Pareto曲线(SKU-销量)", "series",
                series(paretoX, "累计销量占比(%)", paretoY), null);
        ctx.output("complexity_cost_curve", "复杂度-成本曲线", "series",
                series(curveX, "总成本(元)", curveY), "元");
        ctx.output("cost_saving", "精简后成本节省", "scalar", round2(saving), "元");
        ctx.output("complexity_index", "复杂度指数", "scalar", round2(index), null);
        ctx.output("longtail_profit", "长尾SKU利润贡献", "compare", tail, "元");
    }
}
