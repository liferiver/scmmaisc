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

/**
 * 数量折扣与数量柔性合同对比仿真执行器（T059，CH8-007）。
 * 模型：正态随机需求（μ=500,σ=100）下对比三种合同：批发价（w1）、数量折扣（≥Q0 全量 w2 /
 * 增量 w2，阈值附近存在过度订货扭曲）、数量柔性（承诺 [δF,γF] 区间弹性调整）→ 各方期望利润、
 * 链效率（相对集中决策）、全量 vs 增量差异与柔性价值。
 */
@Component
public class QtyDiscountExecutor implements ScenarioExecutor {

    private static final double UNIT_COST = 25.0; // 生产成本 c（元/件，教材相对值）

    @Override
    public String engineKey() {
        return "qty-discount";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        doubleParam(params, "retail_price", 50, 200, errors);
        intParam(params, "discount_threshold", 100, 1000, errors);
        Double w1 = doubleParam(params, "wholesale_price_w1", 30, 100, errors);
        Double w2 = doubleParam(params, "wholesale_price_w2", 10, 90, errors);
        intParam(params, "forecast", 100, 1000, errors);
        doubleParam(params, "min_buy_ratio", 0.7, 0.9, errors);
        doubleParam(params, "max_buy_ratio", 1.1, 1.3, errors);
        if (params.get("demand_dist") != null) {
            distParam(params, "demand_dist", Map.of("mean", new double[]{1, 10000},
                    "sigma", new double[]{1, 5000}), errors);
        }
        if (errors.isEmpty() && w2 != null && w1 != null && w2 >= w1) {
            errors.add("w2_lt_w1 约束不满足：折扣后批发价必须低于折扣前价格（w2 < w1）");
        }
        return errors;
    }

    @Override
    public Integer describeSteps(Map<String, Object> params) {
        return 5;
    }

    /** 期望销量 E[min(D,q)]（正态分布，利用损失函数 L(z)=φ(z)-z(1-Φ(z))）。 */
    private static double expectedSales(double q, double mu, double sigma) {
        double z = (q - mu) / sigma;
        return mu - sigma * NormalDist.loss(z);
    }

    /** 零售商利润：p×E[min] − w×q。 */
    private static double retailProfit(double q, double w, double p, double mu, double sigma) {
        return p * expectedSales(q, mu, sigma) - w * q;
    }

    /** 网格扫描最优订货量（闭区间 [lo, hi]，步长 1）。 */
    private static double bestQ(double lo, double hi, QProfit f) {
        double bestQ = lo;
        double best = Double.NEGATIVE_INFINITY;
        for (double q = lo; q <= hi; q += 1) {
            double v = f.profit(q);
            if (v > best) {
                best = v;
                bestQ = q;
            }
        }
        return bestQ;
    }

    private interface QProfit {
        double profit(double q);
    }

    @Override
    public void run(Map<String, Object> params, SimContext ctx) {
        double p = ((Number) params.get("retail_price")).doubleValue();
        int q0 = ((Number) params.get("discount_threshold")).intValue();
        double w1 = ((Number) params.get("wholesale_price_w1")).doubleValue();
        double w2 = ((Number) params.get("wholesale_price_w2")).doubleValue();
        double forecast = ((Number) params.get("forecast")).doubleValue();
        double delta = ((Number) params.get("min_buy_ratio")).doubleValue();
        double gamma = ((Number) params.get("max_buy_ratio")).doubleValue();
        double mu;
        double sigma;
        if (params.get("demand_dist") != null) {
            Map<String, Double> dist = distParam(params, "demand_dist",
                    Map.of("mean", new double[]{1, 10000}, "sigma", new double[]{1, 5000}),
                    new ArrayList<>());
            if (dist != null) {
                mu = dist.get("mean");
                sigma = dist.get("sigma");
            } else {
                mu = 500.0;
                sigma = 100.0;
            }
        } else {
            mu = 500.0;
            sigma = 100.0;
        }
        final double mean = mu;
        final double std = sigma;

        // 步骤 1：需求分布与集中决策基准
        double lo = Math.max(0, mean - 3 * std);
        double hi = mean + 3 * std;
        double qStar = bestQ(lo, hi, q -> p * expectedSales(q, mean, std) - UNIT_COST * q);
        double chainStar = p * expectedSales(qStar, mean, std) - UNIT_COST * qStar;
        ctx.step(String.format("需求 N(%.0f, %.0f)；集中决策 Q*=%.0f 件，链利润 %,.0f 元（c=%.0f）",
                        mean, std, qStar, chainStar, UNIT_COST),
                Map.of("optimal_order_qty", round2(qStar), "centralized_profit", round2(chainStar)));

        // 步骤 2：批发价合同与全量/增量数量折扣
        double qW = bestQ(lo, hi, q -> retailProfit(q, w1, p, mean, std));
        double retailW = retailProfit(qW, w1, p, mean, std);
        // 全量折扣：q<Q0 用 w1，q≥Q0 全量 w2（阈值附近可能过度订货）
        double qAllLow = bestQ(lo, Math.min(hi, q0 - 1), q -> retailProfit(q, w1, p, mean, std));
        double qAllHigh = bestQ(Math.max(lo, q0), hi, q -> retailProfit(q, w2, p, mean, std));
        boolean allHighBetter = retailProfit(qAllHigh, w2, p, mean, std) > retailProfit(qAllLow, w1, p, mean, std);
        double qAll = allHighBetter ? qAllHigh : qAllLow;
        double wAll = allHighBetter ? w2 : w1;
        double retailAll = retailProfit(qAll, wAll, p, mean, std);
        // 增量折扣：前 Q0 件 w1，超出部分 w2
        double qInc = bestQ(lo, hi, q -> p * expectedSales(q, mean, std)
                - (w1 * Math.min(q, q0) + w2 * Math.max(0, q - q0)));
        double retailInc = p * expectedSales(qInc, mean, std)
                - (w1 * Math.min(qInc, q0) + w2 * Math.max(0, qInc - q0));
        ctx.step(String.format("批发价 Qw=%.0f；全量折扣 Q=%.0f（w=%.0f）/ 增量折扣 Q=%.0f 件",
                        qW, qAll, wAll, qInc),
                Map.of("wholesale_qty", round2(qW), "all_units_qty", round2(qAll),
                        "incremental_qty", round2(qInc)));

        // 步骤 3：数量柔性合同（承诺 [δF, γF]，Monte Carlo 2000 样本）
        double minCommit = delta * forecast;
        double maxCommit = gamma * forecast;
        int samples = 2000;
        double sumSales = 0;
        double sumOrder = 0;
        double sumNoFlex = 0;
        for (int i = 0; i < samples; i++) {
            double d = mean + std * ctx.random().nextGaussian();
            double order = Math.max(minCommit, Math.min(maxCommit, d));
            sumSales += Math.min(d, order);
            sumOrder += order;
            sumNoFlex += Math.min(d, forecast);
        }
        double retailFlex = p * sumSales / samples - w1 * sumOrder / samples;
        double supplierFlex = (w1 - UNIT_COST) * sumOrder / samples;
        double retailNoFlex = p * sumNoFlex / samples - w1 * forecast;
        ctx.step(String.format("数量柔性 [%.0f, %.0f]：零售商 %,.0f 元（无柔性固定订货 %,.0f 元）",
                        minCommit, maxCommit, retailFlex, retailNoFlex),
                Map.of("flex_min", round2(minCommit), "flex_max", round2(maxCommit),
                        "retail_flex", round2(retailFlex)));

        // 步骤 4：合同效果对比
        double supplierAll = (wAll - UNIT_COST) * qAll;
        double supplierInc = (w1 - UNIT_COST) * Math.min(qInc, q0)
                + (w2 - UNIT_COST) * Math.max(0, qInc - q0);
        double supplierW = (w1 - UNIT_COST) * qW;
        List<Map<String, Object>> allVsInc = List.of(
                Map.of("name", "全量折扣-零售商", "value", round2(retailAll)),
                Map.of("name", "增量折扣-零售商", "value", round2(retailInc)),
                Map.of("name", "全量折扣-供应商", "value", round2(supplierAll)),
                Map.of("name", "增量折扣-供应商", "value", round2(supplierInc)));
        List<Map<String, Object>> flexVsNone = List.of(
                Map.of("name", "无柔性-零售商", "value", round2(retailNoFlex)),
                Map.of("name", "数量柔性-零售商", "value", round2(retailFlex)),
                Map.of("name", "无柔性-供应商", "value", round2((w1 - UNIT_COST) * forecast)),
                Map.of("name", "数量柔性-供应商", "value", round2(supplierFlex)));
        List<Map<String, Object>> profitCompare = List.of(
                Map.of("name", "折扣-零售商", "value", round2(Math.max(retailAll, retailInc))),
                Map.of("name", "折扣-供应商", "value", round2(Math.max(supplierAll, supplierInc))),
                Map.of("name", "折扣-链总", "value", round2(Math.max(retailAll + supplierAll, retailInc + supplierInc))),
                Map.of("name", "柔性-零售商", "value", round2(retailFlex)),
                Map.of("name", "柔性-供应商", "value", round2(supplierFlex)),
                Map.of("name", "柔性-链总", "value", round2(retailFlex + supplierFlex)));
        double chainDiscount = Math.max(retailAll + supplierAll, retailInc + supplierInc);
        double chainFlex = retailFlex + supplierFlex;
        double efficiency = Math.max(chainDiscount, chainFlex) / chainStar * 100;
        ctx.step(String.format("链效率：折扣 %,.1f%% / 柔性 %,.1f%%（集中最优 %,.0f 元）",
                        chainDiscount / chainStar * 100, chainFlex / chainStar * 100, chainStar),
                Map.of("chain_efficiency", round2(efficiency)));

        // 步骤 5：汇总输出
        ctx.step("数量折扣 vs 数量柔性合同对比完成（全量折扣在阈值附近存在过度订货扭曲）",
                Map.of("profit_compare", profitCompare));

        // 输出指标（FR-007）
        ctx.output("profit_compare", "各方期望利润", "compare", profitCompare, "元");
        ctx.output("chain_efficiency", "供应链效率", "gauge", List.of(
                Map.of("name", "供应链效率", "value", round2(efficiency))), "%");
        ctx.output("all_vs_incremental", "全量vs增量折扣效果差异", "compare", allVsInc, "元");
        ctx.output("flexibility_vs_none", "数量柔性vs无柔性对比", "compare", flexVsNone, "元");
    }
}
